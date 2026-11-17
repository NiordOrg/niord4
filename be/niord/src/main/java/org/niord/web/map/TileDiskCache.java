/*
 * Copyright 2026 Danish Maritime Authority.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.niord.web.map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * The files of the tile disk cache.
 *
 * <p>Layout: {@code <cacheDir>/<host>/<template>/<z>/<x>/<y>.png} holds a tile's bytes exactly as the host served
 * them, and the sibling {@code <y>.meta} (a properties file) its validators and freshness: {@code etag},
 * {@code lastModified}, {@code expiresAt} and {@code fetchedAt} (epoch milliseconds). {@code <host>} is the tile
 * host (with {@code _<port>} when the address names one) so an operator can see whose tiles take the space;
 * {@code <template>} is a short hash of the whole address template, so two tile styles on the same host never
 * share a file.
 *
 * <p>Every file is written to a temporary sibling and moved into place atomically, so a reader never sees half a
 * tile. A tile without its {@code .meta} counts as stale without validators and is fetched again.
 */
final class TileDiskCache {

    /** The tile machinery logs under one category, the fetcher's. */
    private static final Logger log = LoggerFactory.getLogger(TileFetcher.class);

    static final String TILE_SUFFIX = ".png";
    static final String META_SUFFIX = ".meta";
    static final String TEMP_SUFFIX = ".tmp";

    /** A temporary file this old was left by an interrupted write and is deleted by {@link #evict}. */
    static final long ABANDONED_TEMP_MILLIS = 60L * 60 * 1000;

    private TileDiskCache() {
    }

    /**
     * What is known about a cached tile.
     *
     * @param etag         the {@code ETag} the host sent, or null
     * @param lastModified the {@code Last-Modified} the host sent, verbatim, or null
     * @param expiresAt    until when the tile is used without asking the host (epoch milliseconds)
     * @param fetchedAt    when the tile was last fetched or revalidated (epoch milliseconds)
     */
    record Meta(String etag, String lastModified, long expiresAt, long fetchedAt) {

        /** The meta of a tile whose {@code .meta} file is missing or unreadable: stale, nothing to revalidate with. */
        static final Meta NONE = new Meta(null, null, 0L, 0L);

        boolean isFresh(long now) {
            return now < expiresAt;
        }

        boolean hasValidators() {
            return etag != null || lastModified != null;
        }
    }

    /** A cached tile: its bytes as served, and its meta. */
    record Entry(byte[] bytes, Meta meta) {
    }

    /** The cache file of a tile; its {@code .meta} is {@link #metaFile(Path) beside it}. */
    static Path tileFile(Path cacheDir, TileSource source, int z, int x, int y) {
        return cacheDir
                .resolve(folderName(source.host()))
                .resolve(templateHash(source.urlTemplate()))
                .resolve(Integer.toString(z))
                .resolve(Integer.toString(x))
                .resolve(y + TILE_SUFFIX);
    }

    static Path metaFile(Path tileFile) {
        String name = tileFile.getFileName().toString();
        return tileFile.resolveSibling(name.substring(0, name.length() - TILE_SUFFIX.length()) + META_SUFFIX);
    }

    /** The cached tile, or null when there is none. */
    static Entry read(Path tileFile) throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(tileFile);
        } catch (NoSuchFileException e) {
            return null;
        }
        return new Entry(bytes, readMeta(metaFile(tileFile)));
    }

    /** Stores a tile and its meta; the tile goes first, so a meta never describes bytes that are not there. */
    static void write(Path tileFile, byte[] bytes, Meta meta) throws IOException {
        Files.createDirectories(tileFile.getParent());
        writeAtomically(tileFile, bytes);
        writeMeta(tileFile, meta);
    }

    /** Replaces the meta of a cached tile, e.g. after a {@code 304 Not Modified}. */
    static void writeMeta(Path tileFile, Meta meta) throws IOException {
        Properties properties = new Properties();
        if (meta.etag() != null) {
            properties.setProperty("etag", meta.etag());
        }
        if (meta.lastModified() != null) {
            properties.setProperty("lastModified", meta.lastModified());
        }
        properties.setProperty("expiresAt", Long.toString(meta.expiresAt()));
        properties.setProperty("fetchedAt", Long.toString(meta.fetchedAt()));
        StringWriter text = new StringWriter();
        properties.store(text, null);
        Files.createDirectories(tileFile.getParent());
        writeAtomically(metaFile(tileFile), text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Meta readMeta(Path metaFile) {
        try {
            Properties properties = new Properties();
            properties.load(new StringReader(Files.readString(metaFile, StandardCharsets.UTF_8)));
            return new Meta(
                    properties.getProperty("etag"),
                    properties.getProperty("lastModified"),
                    Long.parseLong(properties.getProperty("expiresAt", "0")),
                    Long.parseLong(properties.getProperty("fetchedAt", "0")));
        } catch (IOException | IllegalArgumentException e) {
            return Meta.NONE;
        }
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", TEMP_SUFFIX);
        try {
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /**
     * Shrinks the cache to at most {@code maxBytes}, removing the least recently fetched or revalidated tiles
     * first, and deletes temporary files an interrupted write left behind.
     *
     * @return the number of tiles removed
     */
    static int evict(Path cacheDir, long maxBytes, long now) throws IOException {
        if (!Files.isDirectory(cacheDir)) {
            return 0;
        }
        Map<Path, CachedTile> tiles = new HashMap<>();
        long total = 0;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(cacheDir)) {
            files = walk.toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(file, BasicFileAttributes.class);
            } catch (NoSuchFileException e) {
                continue;
            }
            if (!attributes.isRegularFile()) {
                continue;
            }
            long modified = attributes.lastModifiedTime().toMillis();
            if (name.endsWith(TEMP_SUFFIX)) {
                if (now - modified > ABANDONED_TEMP_MILLIS) {
                    delete(file);
                }
                continue;
            }
            String base;
            if (name.endsWith(TILE_SUFFIX)) {
                base = name.substring(0, name.length() - TILE_SUFFIX.length());
            } else if (name.endsWith(META_SUFFIX)) {
                base = name.substring(0, name.length() - META_SUFFIX.length());
            } else {
                continue;
            }
            tiles.computeIfAbsent(file.resolveSibling(base), key -> new CachedTile())
                    .add(file, attributes.size(), modified);
            total += attributes.size();
        }
        if (total <= maxBytes) {
            return 0;
        }

        List<CachedTile> oldestFirst = new ArrayList<>(tiles.values());
        oldestFirst.sort(Comparator.comparingLong(CachedTile::lastTouched));
        int removed = 0;
        for (CachedTile tile : oldestFirst) {
            if (total <= maxBytes) {
                break;
            }
            boolean gone = true;
            for (Path file : tile.files) {
                gone &= delete(file);
            }
            if (gone) {
                total -= tile.bytes;
                removed++;
            }
        }
        log.info("Tile cache {}: removed the {} oldest tiles, {} bytes left", cacheDir, removed, total);
        return removed;
    }

    /** Deletes a file; one that cannot be deleted now (e.g. open elsewhere) is left for the next sweep. */
    private static boolean delete(Path file) {
        try {
            Files.deleteIfExists(file);
            return true;
        } catch (IOException e) {
            log.debug("Could not delete the cached tile file {}: {}", file, e.toString());
            return false;
        }
    }

    /** A tile's files as the sweep sees them: their sizes, and the newest modification among them. */
    private static final class CachedTile {
        private final List<Path> files = new ArrayList<>(2);
        private long bytes;
        private long lastTouched;

        void add(Path file, long size, long modified) {
            files.add(file);
            bytes += size;
            lastTouched = Math.max(lastTouched, modified);
        }

        long lastTouched() {
            return lastTouched;
        }
    }

    /** The host as a folder name: letters, digits, dots and dashes, the port after an underscore. */
    private static String folderName(String host) {
        String name = host.replace(':', '_').replaceAll("[^A-Za-z0-9._-]", "_");
        return name.matches("\\.*") ? "_" + name : name;
    }

    /** The first 8 hex digits of the template's SHA-256: short, and stable across restarts and machines. */
    private static String templateHash(String urlTemplate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(urlTemplate.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
        }
    }
}
