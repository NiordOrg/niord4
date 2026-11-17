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

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Where {@link TileFetcher} gets the raster tiles of a map background from, how it identifies itself to that
 * host, and where it caches what it got.
 *
 * <p>The values come from the deployment's settings and are resolved for each image by the thread that draws it,
 * so a settings change applies to the next image without a restart.
 *
 * @param urlTemplate an absolute {@code http} or {@code https} address of one 256 x 256 tile with the
 *                    placeholders {@code {z}}, {@code {x}} and {@code {y}} (XYZ numbering: {@code y} counts
 *                    from the north, as OpenStreetMap does), e.g.
 *                    {@code https://tile.openstreetmap.org/{z}/{x}/{y}.png}
 * @param userAgent   the {@code User-Agent} sent with every tile request: it names the application, its version
 *                    and a contact, as the OpenStreetMap tile usage policy requires
 * @param cacheDir    the folder of the tile disk cache; it must lie outside the public repository folder, since
 *                    tiles may be served to users only as part of a composed map image
 */
public record TileSource(String urlTemplate, String userAgent, Path cacheDir) {

    public TileSource {
        Objects.requireNonNull(urlTemplate, "urlTemplate");
        Objects.requireNonNull(userAgent, "userAgent");
        Objects.requireNonNull(cacheDir, "cacheDir");
        if (!urlTemplate.contains("{z}") || !urlTemplate.contains("{x}") || !urlTemplate.contains("{y}")) {
            throw new IllegalArgumentException("A tile address needs the placeholders {z}, {x} and {y}");
        }
        if (userAgent.isBlank()) {
            throw new IllegalArgumentException("A tile request needs a User-Agent that identifies the application");
        }
        URI sample;
        try {
            sample = URI.create(fill(urlTemplate, 0, 0, 0));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a valid tile address: " + e.getMessage(), e);
        }
        String scheme = sample.getScheme() == null ? "" : sample.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new IllegalArgumentException("A tile address must be http or https");
        }
        if (sample.getHost() == null) {
            throw new IllegalArgumentException("A tile address must name a host");
        }
    }

    /** The address of one tile. The numbers are written with {@link Integer#toString}, never locale-formatted. */
    URI tileUri(int z, int x, int y) {
        return URI.create(fill(urlTemplate, z, x, y));
    }

    /**
     * The host the tiles come from, with the port when the address names one: the unit of the per-host request
     * limit and of the circuit breaker.
     */
    String host() {
        URI sample = tileUri(0, 0, 0);
        String host = sample.getHost().toLowerCase(Locale.ROOT);
        return sample.getPort() == -1 ? host : host + ":" + sample.getPort();
    }

    private static String fill(String template, int z, int x, int y) {
        return template
                .replace("{z}", Integer.toString(z))
                .replace("{x}", Integer.toString(x))
                .replace("{y}", Integer.toString(y));
    }
}
