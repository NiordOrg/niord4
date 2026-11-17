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
package org.niord.web.map;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import org.niord.core.portrayal.PortrayalDefinition;
import org.niord.core.portrayal.PortrayalSymbols;
import org.niord.core.settings.annotation.Setting;
import org.niord.core.util.MdcContext;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.geojson.FeatureVo;
import org.niord.model.geojson.GeometryVo;
import org.niord.model.geojson.LineStringVo;
import org.niord.model.geojson.PointVo;
import org.niord.model.geojson.PolygonVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.niord.core.settings.Setting.Type.Integer;

/**
 * Makes the map image of a message: the OpenStreetMap background of the part of the map its geometry lies in,
 * with the message's portrayal drawn on it as the web maps draw it.
 *
 * <p>The image is made in three steps. {@link MapImagePlanner} frames the geometry and plans the marks from the
 * portrayal definition's print size set, scaled to the image size; {@link TileFetcher} composes the background
 * from OpenStreetMap tiles (the only tiles an image ever shows); {@link MapImagePainter} paints the marks on it.
 * The image is written as a PNG whose modification time is the message's change time, which is how a current
 * image is told from an outdated one.
 */
@Component
public class MessageMapImageGenerator {

    private static final Logger log = LoggerFactory.getLogger(MessageMapImageGenerator.class);

    @Inject
    PortrayalDefinition portrayal;

    @Inject
    PortrayalSymbols symbols;

    @Inject
    TileFetcher tileFetcher;

    @Setting(value = "mapImageSize", defaultValue = "256", type = Integer, web = true,
            description = "Size of map thumbnails. Read at start-up.")
    Integer mapImageSize;

    @Setting(value = "mapImageZoomLevel", defaultValue = "8", type = Integer,
            description = "The map thumbnail zoom level used for single-position messages. Read at start-up.")
    Integer zoomLevel;

    /** Returns the size of the map image */
    public java.lang.Integer getMapImageSize() {
        return mapImageSize;
    }

    /**
     * Prepares the drawing of the map images on a thread of its own, started when the bean is created, so that
     * the first map image after a start waits for none of it: the portrayal symbols are rasterised at the sizes the
     * images draw them at, and a trial image is drawn ({@link #drawTrialImage}). It reads nothing from the database,
     * fetches no tile and writes no file, so it need not wait until the application is ready, and it runs while the
     * rest of the application starts. Start-up work with no calling request: the thread gets a job id of its own
     * (MdcContext).
     */
    @PostConstruct
    void warmUp() {
        Thread.ofPlatform().daemon().name("map-image-warm-up").start(MdcContext.withFreshJobId(() -> {
            if (!portrayal.available()) {
                return;
            }
            try {
                symbols.rasteriseAll(portrayal.print(mapImageSize));
                long start = System.nanoTime();
                drawTrialImage();
                log.info("Drew a trial map image in {} ms", millis(System.nanoTime() - start));
            } catch (IOException | RuntimeException e) {
                // Only a head start: the first map image loads and rasterises what it needs itself
                log.warn("Cannot prepare the drawing of the map images ahead of the first one: {}", e.getMessage());
            }
        }));
    }

    /**
     * Draws the map image of a made-up message, with a point, a line, an area, a buffer and an approximate
     * position, on a background composed of blank tiles decoded as tiles are, and encodes it as PNG in memory,
     * through the same planner, composition, painter and codecs as a real image. It loads and links that code, which
     * a real image would otherwise load while a request waits for it. Nothing is written and nothing is fetched.
     *
     * @return the image
     */
    BufferedImage drawTrialImage() throws IOException {
        FeatureCollectionVo[] fcs = { TrialMessage.geometry() };
        TileWindow window = MapImagePlanner.frame(fcs, mapImageSize, zoomLevel);
        List<MapImageMark> marks = MapImagePlanner.plan(fcs, TrialMessage.MAIN_TYPE, null, portrayal,
                portrayal.print(mapImageSize), window);
        BufferedImage tile = TileFetcher.decodeTile(TileFetcher.blankTilePng());
        BufferedImage image = TileFetcher.compose(window, placement -> tile);
        MapImagePainter.paint(image, marks, symbols);
        try (ImageOutputStream out = new MemoryCacheImageOutputStream(OutputStream.nullOutputStream())) {
            if (!ImageIO.write(image, "png", out)) {
                throw new IOException("No PNG writer is available");
            }
        }
        return image;
    }

    /** The geometry of the made-up message of {@link #drawTrialImage}, off the Danish coast */
    private static final class TrialMessage {

        static final String MAIN_TYPE = "NW";

        static FeatureCollectionVo geometry() {
            FeatureVo point = feature(new PointVo(new double[] { 11.0, 55.6 }),
                    Map.of(MapImagePlanner.APPROXIMATE_KEY, true));
            FeatureVo line = feature(new LineStringVo(new double[][] { { 10.8, 55.5 }, { 11.2, 55.7 } }), Map.of());
            FeatureVo area = feature(new PolygonVo(new double[][][] { {
                    { 10.9, 55.45 }, { 11.1, 55.45 }, { 11.1, 55.55 }, { 10.9, 55.55 }, { 10.9, 55.45 } } }), Map.of());
            FeatureVo buffer = feature(new PolygonVo(new double[][][] { {
                    { 10.95, 55.55 }, { 11.05, 55.55 }, { 11.05, 55.65 }, { 10.95, 55.65 }, { 10.95, 55.55 } } }),
                    Map.of(MapImagePlanner.PARENT_FEATURE_IDS_KEY, List.of("point")));
            FeatureCollectionVo collection = new FeatureCollectionVo();
            collection.setFeatures(new FeatureVo[] { point, line, area, buffer });
            return collection;
        }

        private static FeatureVo feature(GeometryVo geometry, Map<String, Object> properties) {
            FeatureVo feature = new FeatureVo();
            feature.setGeometry(geometry);
            feature.setProperties(new HashMap<>(properties));
            return feature;
        }
    }

    /**
     * The file name of a message's generated map image. It carries the portrayal definition's image
     * version, so a portrayal change gives every image a new name and the old file marks it as outdated.
     * @return the file name, e.g. {@code map_256_053d3a24b6af.png}
     */
    public String imageFileName() {
        return String.format(Locale.ROOT, "map_%d_%s.png", mapImageSize, portrayal.imageVersion());
    }

    /**
     * Draws the map image of one message and writes it to the given path. The file is written whole or not at
     * all: the PNG goes to a temporary file in the same folder, which gets the message's change time as its
     * modification time and then replaces the image in one move.
     *
     * @param request the message's geometry, type and change time, and where the background tiles come from
     * @param imageRepoPath the path of the image
     * @return if the image file was created; false when the geometry has nothing to draw, in which case no tile
     *         is requested and nothing is written
     * @throws IOException when the image cannot be made: the portrayal definition is not available, the image
     *         size or zoom level is out of range, a symbol cannot be rasterised, the tiles are not available
     *         ({@link TileUnavailableException}) or the file cannot be written; nothing is written then
     */
    public boolean generate(MapImageRequest request, Path imageRepoPath) throws IOException {
        if (!portrayal.available()) {
            throw new IOException("The portrayal definition is not available, so no map image can be drawn");
        }
        long start = System.nanoTime();

        FeatureCollectionVo[] fcs = request.featureCollections();
        TileWindow window;
        List<MapImageMark> marks;
        try {
            window = MapImagePlanner.frame(fcs, mapImageSize, zoomLevel);
            if (window == null) {
                return false;
            }
            marks = MapImagePlanner.plan(fcs, request.mainType(), request.messageType(), portrayal,
                    portrayal.print(mapImageSize), window);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // An image size or zoom level out of range, or a definition that cannot draw this message
            throw new IOException("Cannot plan the map image: " + e.getMessage(), e);
        }
        if (marks.isEmpty()) {
            return false;
        }
        long planned = System.nanoTime();

        // Resolved here, on the thread that draws the image, and only for an image with something to draw
        Supplier<TileSource> tiles = request.tiles();
        TileSource source = tiles != null ? tiles.get() : null;
        if (source == null) {
            throw new IOException("No tile source for the map image background");
        }
        BufferedImage image = tileFetcher.background(source, window);
        long background = System.nanoTime();

        try {
            MapImagePainter.paint(image, marks, symbols);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // A symbol that cannot be rasterised
            throw new IOException("Cannot paint the map image: " + e.getMessage(), e);
        }
        long painted = System.nanoTime();

        write(image, imageRepoPath, request.updatedMillis());

        log.info("Generated {} in {} ms (background {} ms, marks {} ms)", imageRepoPath,
                millis(System.nanoTime() - start), millis(background - planned),
                millis((planned - start) + (painted - background)));
        return true;
    }

    /**
     * Writes the image as a PNG with the given modification time, replacing the file in one move. The temporary
     * file has a name no other write uses and is written straight to disk, as the image was before, with the
     * default permissions, which the image keeps.
     */
    private static void write(BufferedImage image, Path target, long modifiedMillis) throws IOException {
        Path folder = target.toAbsolutePath().getParent();
        Files.createDirectories(folder);
        Path temporary = folder.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            if (!ImageIO.write(image, "png", temporary.toFile())) {
                throw new IOException("No PNG writer is available");
            }
            Files.setLastModifiedTime(temporary, FileTime.fromMillis(modifiedMillis));
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static long millis(long nanos) {
        return nanos / 1_000_000;
    }

    /**
     * Validates the the given image data buffer represents an image. If the image does not have the
     * proper proportions, it will be scaled; see {@link CustomThumbnailScaler}.
     *
     * @param imageData the image data
     * @return the thumbnail as a PNG of the map image size, or null when the data is not an image
     */
    public byte[] scaleCustomThumbnail(byte[] imageData) throws IOException {
        return CustomThumbnailScaler.scale(imageData, mapImageSize);
    }
}
