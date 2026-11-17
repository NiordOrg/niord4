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
package org.niord.web.wms;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The name of one sea-chart tile in the {@link WmsTileCache}: the provider's host, the variant of the map it was
 * asked for, and the tile's zoom, column and row.
 *
 * <p>A web map asks the WMS for 256 x 256 pixel images whose boxes are the tiles of the Web Mercator grid
 * (EPSG:3857, the grid of an {@code {z}/{x}/{y}} tile address: zoom 0 is the whole world in one tile, each zoom
 * halves the tile's side, columns count from the west and rows from the north). Only such a request has a key:
 * {@link #of} gives none for any other size, projection, image format or box, and the request is then proxied
 * without the cache.
 *
 * <p>The variant is a hash of the provider's address and of every parameter sent to it except the box, so
 * another provider, other layers or styles, or another WMS version never share a file. It is long enough, and
 * its input is written unambiguously enough, that a caller cannot make up parameters whose tile would take the
 * name of another map's: a tile is stored under the name of exactly what the provider was asked for. The
 * provider's token is no parameter here and is never part of a key.
 *
 * <p>The parameters that are constants of the protocol must be the ones a web map sends ({@code SERVICE},
 * {@code VERSION}, {@code TRANSPARENT}); the layers and the styles are a deployment's own and may be anything.
 *
 * @param host    the provider's host as a folder name, with {@code _<port>} when the address names a port
 * @param variant 32 hex digits: the hash of the provider's address and the parameters other than the box
 * @param z       the zoom, 0 to {@value #MAX_ZOOM}
 * @param x       the column, 0 to 2^z - 1, counted from the west
 * @param y       the row, 0 to 2^z - 1, counted from the north
 */
record WmsTileKey(String host, String variant, int z, int x, int y) {

    /** The side of a tile in pixels */
    static final int TILE_SIZE = 256;

    /** The deepest zoom cached: a tile of 38 m, 15 cm a pixel */
    static final int MAX_ZOOM = 20;

    /** Half the side of the Web Mercator world in metres: the grid spans -HALF_WORLD to +HALF_WORLD both ways */
    static final double HALF_WORLD = 20037508.342789244;

    static final String PROJECTION = "EPSG:3857";
    static final String FORMAT = "image/png";
    static final String TILE_SUFFIX = ".png";

    /**
     * How far a box may be from a tile of the grid, as a fraction of the tile's side: 0.0003 of a pixel. A map
     * computes its boxes in floating point, which is off by a few parts in 10^16; a box of another grid is off by a
     * pixel or more.
     */
    static final double TOLERANCE = 1e-6;

    /** A plain decimal number, as a box holds four of: no NaN, no Infinity, no hexadecimal form */
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");

    /** The WMS versions a web map speaks */
    private static final Set<String> VERSIONS = Set.of("1.3.0", "1.1.1");

    /** The values of TRANSPARENT a web map sends */
    private static final Set<String> BOOLEANS = Set.of("TRUE", "FALSE", "true", "false");

    /** The bytes of the hash a variant shows: 16, as 32 hex digits */
    private static final int VARIANT_BYTES = 16;

    /**
     * Returns the key of the tile a request asks for.
     *
     * @param provider  the WMS provider's address
     * @param forwarded the parameters sent to the provider, by upper-case name, the token left out
     * @return the key, or empty when the request is not for one 256 x 256 PNG tile of the Web Mercator grid
     */
    static Optional<WmsTileKey> of(String provider, Map<String, String> forwarded) {
        String side = Integer.toString(TILE_SIZE);
        if (!"GetMap".equals(forwarded.get("REQUEST"))
                || !side.equals(forwarded.get("WIDTH"))
                || !side.equals(forwarded.get("HEIGHT"))
                || !FORMAT.equals(forwarded.get("FORMAT"))
                || !webMercator(forwarded)
                || !VERSIONS.contains(forwarded.getOrDefault("VERSION", ""))
                || !forwarded.getOrDefault("SERVICE", "WMS").equals("WMS")
                || !BOOLEANS.contains(forwarded.getOrDefault("TRANSPARENT", "TRUE"))) {
            return Optional.empty();
        }
        String host = hostFolder(provider);
        double[] box = box(forwarded.get("BBOX"));
        if (host == null || box == null) {
            return Optional.empty();
        }

        double width = box[2] - box[0];
        double height = box[3] - box[1];
        if (!(width > 0) || !(height > 0)) {
            return Optional.empty();
        }
        long z = Math.round(Math.log(2 * HALF_WORLD / width) / Math.log(2));
        if (z < 0 || z > MAX_ZOOM) {
            return Optional.empty();
        }
        // A power of two scales a double exactly, so the tile's side has no rounding of its own
        double span = Math.scalb(2 * HALF_WORLD, (int) -z);
        if (Math.abs(width - span) > TOLERANCE * span || Math.abs(height - span) > TOLERANCE * span) {
            return Optional.empty();
        }
        double column = (box[0] + HALF_WORLD) / span;
        double row = (HALF_WORLD - box[3]) / span;
        long x = Math.round(column);
        long y = Math.round(row);
        long tiles = 1L << z;
        if (Math.abs(column - x) > TOLERANCE || Math.abs(row - y) > TOLERANCE
                || x < 0 || x >= tiles || y < 0 || y >= tiles) {
            return Optional.empty();
        }
        return Optional.of(new WmsTileKey(host, variant(provider, forwarded), (int) z, (int) x, (int) y));
    }

    /** The tile's file under the cache folder: {@code <host>/<variant>/<z>/<x>/<y>.png} */
    Path file(Path cacheDir) {
        return cacheDir
                .resolve(host)
                .resolve(variant)
                .resolve(Integer.toString(z))
                .resolve(Integer.toString(x))
                .resolve(y + TILE_SUFFIX);
    }

    @Override
    public String toString() {
        return host + "/" + variant + "/" + z + "/" + x + "/" + y;
    }

    /** Whether the projection named, as CRS (WMS 1.3.0) or SRS (1.1.1), is Web Mercator; one of them is */
    private static boolean webMercator(Map<String, String> forwarded) {
        String crs = forwarded.get("CRS");
        String srs = forwarded.get("SRS");
        return (crs != null || srs != null)
                && (crs == null || PROJECTION.equals(crs))
                && (srs == null || PROJECTION.equals(srs));
    }

    /** The four numbers of a box, minX, minY, maxX, maxY, or null when the value is not four plain numbers */
    private static double[] box(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split(",", -1);
        if (parts.length != 4) {
            return null;
        }
        double[] box = new double[4];
        for (int i = 0; i < 4; i++) {
            if (!NUMBER.matcher(parts[i]).matches()) {
                return null;
            }
            box[i] = Double.parseDouble(parts[i]);
            if (!Double.isFinite(box[i])) {
                return null;
            }
        }
        return box;
    }

    /** The provider's host as a folder name: letters, digits, dots and dashes, the port after an underscore */
    private static String hostFolder(String provider) {
        URI uri;
        try {
            uri = URI.create(provider.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (uri.getHost() == null) {
            return null;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String name = (uri.getPort() == -1 ? host : host + "_" + uri.getPort()).replaceAll("[^a-z0-9._-]", "_");
        return name.matches("\\.*") ? "_" + name : name;
    }

    /**
     * The first 32 hex digits of the SHA-256 of the provider's address and the parameters other than the box, in
     * the order of their names. Each text is written with its length in front, so no value can be made to read as
     * the end of one parameter and the start of another.
     */
    private static String variant(String provider, Map<String, String> forwarded) {
        StringBuilder text = new StringBuilder();
        lengthFirst(text, provider.trim());
        new TreeMap<>(forwarded).forEach((name, value) -> {
            if (!"BBOX".equals(name)) {
                lengthFirst(text, name);
                lengthFirst(text, value);
            }
        });
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, VARIANT_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
        }
    }

    private static void lengthFirst(StringBuilder text, String value) {
        text.append(value.length()).append(':').append(value);
    }
}
