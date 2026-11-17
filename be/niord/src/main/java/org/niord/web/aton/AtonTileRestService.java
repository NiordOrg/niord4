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
package org.niord.web.aton;

import org.apache.commons.io.IOUtils;
import org.niord.core.aton.AtonSearchParams;
import org.niord.core.aton.AtonService;
import org.niord.core.repo.RepoCache;
import org.niord.core.util.GlobalMercator;
import org.niord.core.util.GraphicsUtils;
import org.niord.core.web.NiordWebException;
import org.slf4j.Logger;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

import javax.imageio.ImageIO;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Duration;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;

/**
 * Feeds AtoN data as bitmaps.
 * Can be used for servicing an OpenStreetMap Layer in Openlayers.
 * The layer should be configured to have the url "/rest/aton-tiles/${z}/${x}/${y}.png"
 * <p>
 * The handling of blank tiles in particular is un-optimal. This is due to problems getting the service
 * to work with Microsoft IE and Edge:
 * <ul>
 *     <li>IE and Edge will abort redirection requests, preventing us from redirecting blank tiles to
 *         a common global bitmap, which would have been more efficient.</li>
 *     <li>This also prevents us from redirecting the streaming of tiles to the repository service.</li>
 *     <li>The tiles have to be 256x256, preventing us from streaming a 1x1 blank tile.</li>
 * </ul>
 */
@RestController
@RequestMapping("/rest/aton-tiles")
@PermitAll
public class AtonTileRestService {

    static final int        TILE_SIZE           = 256;
    static final int        MAX_ZOOM            = 20; // The deepest zoom level served
    static final int        TILE_TTL_HOURS      = 24; // A tile file is refreshed every 24 hours...
    /** The folder under the home that holds the tiles: a cache on the application's volume, apart from the repository */
    static final String     TILE_CACHE_FOLDER   = "aton-tiles";
    static final String     BLANK_TILE          = "blank_256.png"; // The empty tile, copied into the folder once
    static final Color      ATON_COLOR          = new Color(200, 0, 0);

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AtonTileRestService.class);

    private final AtonService atonService;

    private final AtonBlankTileCache blankTileCache;

    private final RepoCache repoCache;

    /** The folder the tiles are cached in */
    Path tileFolder() {
        return repoCache.home().resolve(TILE_CACHE_FOLDER);
    }

    /**
     * Nightly, deletes the cached tiles older than their lifetime: a tile is drawn again after
     * {@value #TILE_TTL_HOURS} hours anyway, and the folder has no other bound.
     */
    @Scheduled(cron = "0 33 3 * * ?")
    void sweepTiles() {
        int deleted = repoCache.sweep(tileFolder(), Duration.ofHours(TILE_TTL_HOURS),
                p -> p.getFileName().toString().equals(BLANK_TILE));
        if (deleted > 0) {
            log.info("Deleted " + deleted + " AtoN tiles older than " + TILE_TTL_HOURS + " hours");
        }
    }

    AtonTileRestService(AtonService atonService, AtonBlankTileCache blankTileCache, RepoCache repoCache) {
        this.atonService = atonService;
        this.blankTileCache = blankTileCache;
        this.repoCache = repoCache;
    }

    /**
     * Streams the given tile.
     *
     * A tile outside the pyramid is refused with 400 before anything is looked up, cached or written:
     * only zoom levels 0 to {@link #MAX_ZOOM} are served, and at zoom z only the tile columns and rows
     * 0 to 2^z - 1 exist. That keeps one tile file and one blank-tile cache entry per real tile, and
     * keeps the pixel arithmetic of {@link GlobalMercator} inside the int range.
     */
    @GetMapping("/{z}/{x}/{y}.png")
    public ResponseEntity<?> streamTile(@PathVariable("z") int z, @PathVariable("x") int x, @PathVariable("y") int y,
                                        HttpServletRequest request, HttpServletResponse response) throws IOException {

        if (!isServedTile(z, x, y)) {
            throw new NiordWebException(400);
        }

        try {
            // Next, check if the tile exists in the cache
            Path file = tileFolder()
                    .resolve(String.valueOf(z))
                    .resolve(String.valueOf(x))
                    .resolve(String.valueOf(y) + ".png");

            long ttlMs = 1000L * 60L * 60L * TILE_TTL_HOURS;
            Date expirationDate = new Date(System.currentTimeMillis() + ttlMs);

            // Check if the tile is a known blank tile
            if (blankTileCache.getCache().containsKey(file.toString())) {
                return streamBlankTile(expirationDate);

            } else if (Files.exists(file) &&
                    System.currentTimeMillis() < Files.getLastModifiedTime(file).toMillis() + ttlMs) {
                // The tile exists and is not expired

                // Check for an ETag match
                String etag = entityTagForFile(file);
                if (new ServletWebRequest(request, response).checkNotModified(etag)) {
                    // Etag match
                    log.trace("File unchanged. Return code 304");
                    HttpHeaders headers = new HttpHeaders();
                    headers.setExpires(expirationDate.getTime());
                    return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(headers).build();
                } else {
                    log.trace("Return existing tile " + file);
                    return streamTile(file, expirationDate, etag);
                }
            }

            // Search all messages in the bounds of the tile
            long t0 = System.currentTimeMillis();
            GlobalMercator mercator = new GlobalMercator();
            double[] bounds = mercator.TileLatLonBounds(x, y, z);

            // Convert to mapExtents search parameters
            AtonSearchParams param = new AtonSearchParams()
                .extent(-bounds[2], bounds[1], -bounds[0], bounds[3]);

            // Compute the atons of the tile extent
            java.util.List<double[]> atonLonLast = atonService.searchPositions(param);

            // If the search result is empty, return blank and cache the result
            if (atonLonLast.isEmpty()) {
                blankTileCache.getCache().put(file.toString(), file.toString());
                return streamBlankTile(expirationDate);
            }

            // Generate an image
            BufferedImage image = generateAtonTile(z, bounds, mercator, atonLonLast);

            // Write the image to the repository
            checkCreateParentDirs(file);
            ImageIO.write(image, "png", file.toFile());
            log.debug("Generated " + file + " in " + (System.currentTimeMillis() - t0) + " ms");

            return streamTile(file, expirationDate, null);

        } catch (Exception e) {
            log.error(String.format("Error generating tile z=%d, x=%d, y=%d. Error=%s", z, x, y, e));
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(String.format("Error generating tile z=%d, x=%d, y=%d. Error=%s", z, x, y, e));

        }
    }

    /**
     * Whether the tile exists in the pyramid this service serves: zoom 0 to {@link #MAX_ZOOM}, and a
     * column and row within the 2^z tiles of that zoom level.
     * @param z the zoom level
     * @param x the tile column
     * @param y the tile row
     * @return whether the tile is served
     */
    static boolean isServedTile(int z, int x, int y) {
        if (z < 0 || z > MAX_ZOOM) {
            return false;
        }
        int tiles = 1 << z;
        return x >= 0 && x < tiles && y >= 0 && y < tiles;
    }

    /**
     * Generates an AtoN tile
     * @param z the zoom level
     * @param bounds the tile bounds
     * @param mercator the mercator calculator
     * @param atonLonLats the aton positions
     * @return the resulting image
     */
    private BufferedImage generateAtonTile(int z, double[] bounds, GlobalMercator mercator, java.util.List<double[]> atonLonLats) {

        BufferedImage image = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        GraphicsUtils.antialias(g2);

        int xy0[] =  mercator.LatLonToPixels(-bounds[0], bounds[1], z);

        atonLonLats.stream().forEach(lonLat -> {

            int xy[] = mercator.LatLonToPixels(lonLat[1], lonLat[0], z);
            double px = xy[0] - xy0[0];
            double py = -(xy[1] - xy0[1]);
            double radius = (z < 6) ? 0.5 : 1.0;

            Shape theCircle = new Ellipse2D.Double(px - radius, py - radius, 2.0 * radius, 2.0 * radius);
            g2.setColor(ATON_COLOR);
            g2.fill(theCircle);
        });

        g2.dispose();
        return image;
    }

    /**
     * Ensures that parent directories are created
     * @param file the file whose parent directories will be created
     */
    private void checkCreateParentDirs(Path file) throws IOException {
        if (!Files.exists(file.getParent())) {
            Files.createDirectories(file.getParent());
        }
    }

    /**
     * Streams a tile
     * @param file the tile to stream
     * @param expirationDate the expiration date of the returned tile
     * @param etag the E-Tag. May be null.
     * @return the response
     */
    private ResponseEntity<Resource> streamTile(Path file, Date expirationDate, String etag) throws IOException {
        if (etag == null) {
            etag = entityTagForFile(file);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setExpires(expirationDate.getTime());
        return ResponseEntity.ok()
                .headers(headers)
                .eTag(etag)
                .contentType(MediaType.IMAGE_PNG)
                .body(new FileSystemResource(file.toFile()));
    }

    /**
     * Streams a blank tile
     * @param expirationDate the expiration date of the returned tile
     * @return the response
     */
    private ResponseEntity<Resource> streamBlankTile(Date expirationDate) throws IOException {
        Path file = tileFolder().resolve(BLANK_TILE);

        // Make sure the blank file is present in the cache
        if (Files.notExists(file)) {
            checkCreateParentDirs(file);
            IOUtils.copy(
                    getClass().getResourceAsStream("/blank_256.png"),
                    new FileOutputStream(file.toFile()));
        }

        log.trace("Streaming blank file: " + file);
        // NB: the blank-tile branch intentionally carries no ETag (matching the original), so repeated
        // requests for a blank tile are not 304-cached.
        HttpHeaders headers = new HttpHeaders();
        headers.setExpires(expirationDate.getTime());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.IMAGE_PNG)
                .body(new FileSystemResource(file.toFile()));
    }

    /**
     * Computes an E-Tag for the file
     * @param file the file
     * @return the E-Tag for the file
     */
    private String entityTagForFile(Path file) throws IOException {
        return "W/\"" + Files.getLastModifiedTime(file).toMillis() + "_" + Files.size(file) + "\"";
    }

}
