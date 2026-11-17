/*
 * Copyright 2016 Danish Maritime Authority.
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
package org.niord.core.repo;

import jakarta.inject.Inject;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The cache tree on the application's volume: the derived files the repository used to keep next to their
 * originals, the image thumbnails ({@code <name>_thumb_<size>.<ext>}) and the generated map images
 * ({@code map_<size>_<version>.png}), now mirrored under {@code <home>/cache/<repository path>}. Nothing backs
 * the tree up; a file that is missing is made again on the next request.
 * <p>
 * The home is the {@code niord.home} setting, which a deployment points at its volume; when nothing names it the
 * tree lives under the JVM's temporary folder, as the tile cache does.
 */
@Service
public class RepoCache {

    /** The folder under the home that holds the cache tree */
    public static final String CACHE_FOLDER = "cache";

    /**
     * The name of a thumbnail of the days the repository was on disk, {@code <name>_thumb_<size>.<ext>}, which the
     * import meets beside its source and the cache adopts once: group 1 the source's base name, group 2 the size
     * (one of {@link IconSize}), group 3 the extension. A thumbnail the cache draws carries its content's hash too.
     */
    public static final Pattern THUMBNAIL = Pattern.compile("(.+)_thumb_(32|64|128)\\.([A-Za-z0-9]+)");

    /** The characters of a row's hash a thumbnail's name carries */
    private static final int HASH_IN_NAME = 16;

    /**
     * The name of a generated map image, {@code map_<size>[_<version>].png}: group 1 the size in pixels, group 2 the
     * portrayal's image version when the name has one
     */
    public static final Pattern MAP_IMAGE = Pattern.compile("map_(\\d{1,4})(?:_([0-9a-f]{1,32}))?\\.png");

    private static final Logger log = LoggerFactory.getLogger(RepoCache.class);

    @Inject
    SettingsService settingsService;

    private volatile Path home;

    /**
     * The home folder: the {@code niord.home} setting, else {@code <java.io.tmpdir>/niord}. Read once, at the first
     * use (a tile request or a thumbnail does not open a transaction for it); a changed setting needs a restart.
     */
    public Path home() {
        Path resolved = home;
        if (resolved == null) {
            resolved = resolveHome();
            home = resolved;
        }
        return resolved;
    }

    private Path resolveHome() {
        Object home = settingsService.get(SettingsService.NIORD_HOME);
        if (home != null && StringUtils.isNotBlank(home.toString())) {
            try {
                return Path.of(home.toString().trim());
            } catch (InvalidPathException e) {
                log.warn("niord.home {} is not a path; the cache lives under the temporary folder", home);
            }
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "niord");
    }

    /** The root of the cache tree */
    public Path root() {
        return home().resolve(CACHE_FOLDER);
    }

    /**
     * The place of a repository path in the cache tree. The path is normalised first, and one that is no path of the
     * repository is refused here, whoever hands it over: a row's path was normalised when it was written, but a
     * message's or an issue's folder is a stored string, and the place it names is read and written on the volume.
     *
     * @param repoPath the repository path
     * @return its place under the cache root
     * @throws IllegalArgumentException for null, the root, an absolute path or one that climbs out of the repository
     */
    public Path pathFor(String repoPath) {
        String key = RepoPaths.normalize(repoPath);
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Not a path of the repository: " + repoPath);
        }
        return root().resolve(key);
    }

    /**
     * The name of the cached thumbnail of a row: {@code <name>_thumb_<size>_<hash>.<ext>}, the hash the start of the
     * row's content's. The thumbnail is the content's, so a file written again under its name, a save that copies
     * an older row over it, or a database restored to an earlier state names another thumbnail, drawn on request.
     */
    public static String thumbnailName(String fileName, IconSize size, String sha256) {
        String hash = sha256 == null ? "0" : sha256.substring(0, Math.min(HASH_IN_NAME, sha256.length()));
        return String.format("%s_thumb_%d_%s.%s",
                FilenameUtils.removeExtension(fileName), size.getSize(), hash, FilenameUtils.getExtension(fileName));
    }

    /** The thumbnail's name of the days the repository was on disk: {@code <name>_thumb_<size>.<ext>} */
    public static String legacyThumbnailName(String fileName, IconSize size) {
        return String.format("%s_thumb_%d.%s",
                FilenameUtils.removeExtension(fileName), size.getSize(), FilenameUtils.getExtension(fileName));
    }

    /** The cached thumbnail of a row: {@link #thumbnailName} in the file's cache folder */
    public Path thumbnailFor(RepoFileInfo file, IconSize size) {
        return pathFor(file.folder()).resolve(thumbnailName(file.name(), size, file.sha256()));
    }

    /** Where the import put the thumbnail of the days the repository was on disk, if it did */
    public Path legacyThumbnailFor(RepoFileInfo file, IconSize size) {
        return pathFor(file.folder()).resolve(legacyThumbnailName(file.name(), size));
    }

    /**
     * Every cached thumbnail of a repository path, of every size, content and age, and the markers of the refused
     * ones: what a delete of the row removes
     */
    public List<Path> thumbnailsOf(String repoPath) {
        String name = RepoPaths.nameOf(repoPath);
        String prefix = FilenameUtils.removeExtension(name) + "_thumb_";
        String repoFolder = RepoPaths.folderOf(repoPath);
        if (repoFolder.isEmpty()) {
            // Nothing is written directly under the root, so nothing is cached there either
            return List.of();
        }
        Path folder = pathFor(repoFolder);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(p -> p.getFileName().toString().startsWith(prefix)).toList();
        } catch (IOException e) {
            log.debug("Could not list the cache folder {}: {}", folder, e.toString());
            return List.of();
        }
    }

    /**
     * Deletes the cached files of a repository folder whose rows are gone, and the folder's place in the tree:
     * the thumbnails and map images of a deleted issue or message are not made again.
     *
     * @param folder the repository folder
     * @return the number of files deleted
     */
    public int evict(String folder) {
        Path root = pathFor(folder);
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    // Asked before the delete: afterwards nothing is a directory any more
                    boolean file = !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
                    if (Files.deleteIfExists(p) && file) {
                        deleted++;
                    }
                } catch (IOException e) {
                    log.debug("Could not delete the cached file {}: {}", p, e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("Could not evict the cache folder {}: {}", root, e.toString());
        }
        return deleted;
    }

    /**
     * Deletes the cached files of the temp root that are older than the given age, and the folders they leave
     * empty: a thumbnail of a temp upload goes with the upload's row.
     *
     * @param maxAge the age past which a cached temp file is deleted
     * @return the number of files deleted
     */
    public int sweepTemp(Duration maxAge) {
        return sweep(pathFor(RepoPaths.TEMP_ROOT), maxAge, p -> false);
    }

    /**
     * Deletes the files below a cache folder that are older than the given age, except those to keep, and the
     * folders the sweep leaves empty. A file it cannot delete is logged and left.
     *
     * @param root the cache folder; nothing happens when it does not exist
     * @param maxAge the age past which a file is deleted
     * @param keep the files that stay whatever their age
     * @return the number of files deleted
     */
    public int sweep(Path root, Duration maxAge, Predicate<Path> keep) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(maxAge);
        int deleted = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (p.equals(root)) {
                    continue;
                }
                try {
                    if (Files.isDirectory(p)) {
                        try (Stream<Path> children = Files.list(p)) {
                            if (children.findAny().isEmpty()) {
                                Files.deleteIfExists(p);
                            }
                        }
                    } else if (!keep.test(p) && Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(p);
                        deleted++;
                    }
                } catch (IOException e) {
                    log.debug("Could not sweep the cached file {}: {}", p, e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("Could not sweep the cache folder {}: {}", root, e.toString());
        }
        return deleted;
    }
}
