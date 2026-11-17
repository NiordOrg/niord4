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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.niord.core.portrayal.PortrayalDefinition;
import org.niord.core.portrayal.PortrayalDefinition.SymbolSize;
import org.niord.core.portrayal.PortrayalSymbols;
import org.niord.core.portrayal.PortrayalTestSupport;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.geojson.FeatureVo;
import org.niord.model.geojson.GeometryVo;
import org.niord.model.geojson.LineStringVo;
import org.niord.model.geojson.PointVo;
import org.niord.model.geojson.PolygonVo;
import org.niord.test.http.HttpStub;
import org.niord.test.http.HttpStub.RecordedRequest;
import org.niord.test.http.HttpStub.StubResponse;
import org.niord.web.map.MapImageMark.IconMark;
import org.niord.web.map.MapImageMark.PathMark;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole making of a message's map image by {@link MessageMapImageGenerator#generate}: framing, the background
 * from an {@link HttpStub} tile server that answers every {@code /tiles/<z>/<x>/<y>.png} with a white 256 x 256
 * tile, the marks painted from the portrayal definition's print size set, and the file written.
 *
 * <p>The oracles are pixels of the written PNG over the white background: the dashed magenta pen along a line,
 * no fill inside an area, the tag sprite composited at the area's interior point, the buffer fill's colour, the
 * point symbol; and the file itself: its modification time, no temporary file left, no file at all when there is
 * nothing to draw or no tiles to draw on.
 */
class MessageMapImagePipelineTest {

    private static final String USER_AGENT = "Niord/4.0.0-TEST (+https://niord.example.org)";
    private static final Pattern TILE_PATH = Pattern.compile("/tiles/(\\d+)/(\\d+)/(\\d+)\\.png");
    private static final int SIZE = 256;
    private static final int SINGLE_POINT_ZOOM = 8;
    private static final long UPDATED = 1_700_000_000_000L;

    /** The magenta of the portrayal's pens and symbols */
    private static final Color NWMGD = new Color(0xC0, 0x45, 0xD1);

    private static PortrayalDefinition portrayal;
    private static PortrayalSymbols symbols;
    private static byte[] whiteTile;

    @TempDir
    Path tmp;

    private HttpStub stub;
    private TileFetcher tileFetcher;
    private MessageMapImageGenerator generator;
    private Path target;

    @BeforeAll
    static void definition() throws IOException {
        System.setProperty("java.awt.headless", "true");
        portrayal = PortrayalTestSupport.classPathDefinition();
        symbols = PortrayalSymbols.of(portrayal);
        whiteTile = MapImageTestSupport.solidPng(256, 256, Color.WHITE);
    }

    @BeforeEach
    void startTileServer() throws IOException {
        stub = HttpStub.start();
        stub.respond(request -> TILE_PATH.matcher(request.path()).matches()
                ? StubResponse.bytes("image/png", whiteTile)
                : StubResponse.status(404));
        tileFetcher = new TileFetcher();
        generator = new MessageMapImageGenerator();
        generator.portrayal = portrayal;
        generator.symbols = symbols;
        generator.tileFetcher = tileFetcher;
        generator.mapImageSize = SIZE;
        generator.zoomLevel = SINGLE_POINT_ZOOM;
        target = tmp.resolve("repo").resolve("a1").resolve("map_256_test.png");
    }

    @AfterEach
    void stopTileServer() {
        tileFetcher.shutdown();
        stub.close();
    }

    // ------------------------------------------------------------------------------------------- //
    // The file
    // ------------------------------------------------------------------------------------------- //

    @Test
    void writesAPngOfTheImageSizeWithTheMessagesChangeTimeAndNoTemporaryFile() throws IOException {
        assertTrue(generator.generate(request(fcs(square(11, 55.5, 0.05))), target));

        BufferedImage image = MapImageTestSupport.read(target);
        assertEquals(SIZE, image.getWidth());
        assertEquals(SIZE, image.getHeight());
        assertEquals(UPDATED, Files.getLastModifiedTime(target).toMillis(), "the mtime is the message's change time");
        try (Stream<Path> files = Files.list(target.getParent())) {
            assertEquals(List.of(target.getFileName().toString()),
                    files.map(file -> file.getFileName().toString()).toList(), "only the image is left in the folder");
        }
    }

    /** An image that is there already is replaced whole, and gets the new change time */
    @Test
    void replacesAnExistingImage() throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, "an outdated image");

        assertTrue(generator.generate(request(fcs(square(11, 55.5, 0.05))), target));

        assertEquals(SIZE, MapImageTestSupport.read(target).getWidth());
        assertEquals(UPDATED, Files.getLastModifiedTime(target).toMillis());
        try (Stream<Path> files = Files.list(target.getParent())) {
            assertEquals(1, files.count());
        }
    }

    /** The background is exactly the tiles under the image, at the zoom level that frames the geometry */
    @Test
    void fetchesTheTilesOfTheFramedWindowWithTheTileSourcesUserAgent() throws IOException {
        FeatureCollectionVo[] fcs = fcs(new PointVo(new double[] { 11, 55.5 }));

        assertTrue(generator.generate(request(fcs), target));

        TileWindow window = MapImagePlanner.frame(fcs, SIZE, SINGLE_POINT_ZOOM);
        List<String> expected = window.placements().stream()
                .map(p -> "/tiles/" + SINGLE_POINT_ZOOM + "/" + p.x() + "/" + p.y() + ".png").sorted().toList();
        assertEquals(expected, stub.requests().stream().map(RecordedRequest::path).sorted().toList());
        stub.requests().forEach(r -> assertEquals(USER_AGENT, r.header("User-Agent")));
    }

    @Test
    void zoomsToTheGeometry() throws IOException {
        assertTrue(generator.generate(request(fcs(line(11, 55.5, 11.001, 55.5005))), target));
        assertEquals(List.of(MapImagePlanner.MAX_ZOOM), requestedZooms(), "a tiny geometry at the highest zoom");

        stub.reset();
        assertTrue(generator.generate(request(fcs(line(-60, -30, 60, 40))), target));
        assertEquals(List.of(MapImagePlanner.MIN_ZOOM), requestedZooms(), "a huge geometry at the lowest zoom");
    }

    // ------------------------------------------------------------------------------------------- //
    // What is drawn
    // ------------------------------------------------------------------------------------------- //

    /**
     * A line is drawn with the dashed magenta pen: along the pixel row it runs through there are fully covered
     * pixels in the pen's colour and white gaps, run after run
     */
    @Test
    void aLineIsDrawnDashedInTheDefinitionsMagenta() throws IOException {
        FeatureCollectionVo[] fcs = fcs(line(10.9, 55.5, 11.1, 55.5));
        assertTrue(generator.generate(request(fcs), target));
        BufferedImage image = MapImageTestSupport.read(target);

        List<MapImageMark> plan = plan(fcs);
        PathMark path = (PathMark) plan.getFirst();
        double[] xy = path.subpaths().getFirst().xy();
        int row = (int) Math.floor(xy[1]);
        List<IconMark> tags = icons(plan);
        int dashes = 0;
        int gaps = 0;
        Boolean inked = null;
        for (int x = (int) Math.ceil(xy[0]) + 2; x < (int) Math.floor(xy[2]) - 2; x++) {
            if (inside(tags, x, row)) {
                inked = null;
                continue;
            }
            Color colour = MapImageTestSupport.rgb(image, x, row);
            boolean ink = colour.equals(NWMGD);
            boolean white = colour.equals(Color.WHITE);
            if (ink && !Boolean.TRUE.equals(inked)) {
                dashes++;
                inked = true;
            } else if (white && !Boolean.FALSE.equals(inked)) {
                gaps++;
                inked = false;
            }
        }
        assertTrue(dashes >= 3 && gaps >= 3, "the line is not dashed: " + dashes + " dashes, " + gaps + " gaps");
    }

    /** An area is outlined and never filled: inside it the background shows */
    @Test
    void anAreaIsOutlinedAndNotFilled() throws IOException {
        FeatureCollectionVo[] fcs = fcs(square(11, 55.5, 0.06));
        assertTrue(generator.generate(request(fcs), target));
        BufferedImage image = MapImageTestSupport.read(target);

        List<MapImageMark> plan = plan(fcs);
        PathMark outline = (PathMark) plan.getFirst();
        double[] ring = outline.subpaths().getFirst().xy();
        double west = Arrays.stream(new double[] { ring[0], ring[2], ring[4], ring[6] }).min().orElseThrow();
        int centreY = (int) ((ring[1] + ring[5]) / 2);
        IconMark tag = icons(plan).getFirst();

        // Just inside the west edge, between the edge and the tag at the centre
        int from = (int) west + 4;
        int to = Math.min((int) west + 20, tag.x() - 2);
        assertTrue(to - from >= 5, "no room between the outline and the tag: " + from + " to " + to);
        for (int x = from; x < to; x++) {
            assertEquals(Color.WHITE, MapImageTestSupport.rgb(image, x, centreY), "filled at " + x + "," + centreY);
        }
        boolean outlined = false;
        for (int y = 0; y < SIZE; y++) {
            outlined |= MapImageTestSupport.rgb(image, (int) Math.floor(west), y).equals(NWMGD);
        }
        assertTrue(outlined, "no outline along the west edge");
    }

    /**
     * The tag of an area is its sprite, drawn at the interior point: every fully covered pixel of the sprite is
     * in the image, in the sprite's colour, at the tag's position, and the symbol's anchor is on the interior point.
     * (The outline crosses the tag's box here, so only the sprite's own ink is compared.)
     */
    @Test
    void theTagOfAnAreaIsItsSpriteAtTheInteriorPoint() throws IOException {
        // A U whose notch holds the centre of its extent: the interior point is in the east arm
        PolygonVo u = new PolygonVo(new double[][][] { {
                { 10.9, 55.45 }, { 11.1, 55.45 }, { 11.1, 55.55 }, { 11.04, 55.55 }, { 11.04, 55.48 },
                { 10.95, 55.48 }, { 10.95, 55.55 }, { 10.9, 55.55 }, { 10.9, 55.45 } } });
        FeatureCollectionVo[] fcs = fcs(u);
        assertTrue(generator.generate(request(fcs), target));
        BufferedImage image = MapImageTestSupport.read(target);

        IconMark tag = icons(plan(fcs)).getFirst();
        assertEquals("nw-tag", tag.symbolId());
        TileWindow window = MapImagePlanner.frame(fcs, SIZE, SINGLE_POINT_ZOOM);
        double interiorX = window.imageX(OpenLayersGeometry.x3857((11.04 + 11.1) / 2));
        assertEquals(interiorX, tag.x() + portrayal.symbol("nw-tag").anchorX() * tag.width(), 1.0,
                "the tag's anchor is on the interior point, in the east arm");

        BufferedImage sprite = symbols.sprite("nw-tag", new SymbolSize(tag.width(), tag.height()));
        int opaque = 0;
        for (int y = 0; y < sprite.getHeight(); y++) {
            for (int x = 0; x < sprite.getWidth(); x++) {
                if ((sprite.getRGB(x, y) >>> 24) == 255) {
                    opaque++;
                    assertClose(overWhite(sprite.getRGB(x, y)), MapImageTestSupport.rgb(image, tag.x() + x, tag.y() + y),
                            1, "tag pixel " + x + "," + y);
                }
            }
        }
        assertTrue(opaque > 20, "the tag sprite has too little ink to compare: " + opaque);
    }

    /** A point gets the point symbol of its message's main type, centred on it */
    @Test
    void aPointGetsThePointSymbolOfItsMainType() throws IOException {
        FeatureCollectionVo[] fcs = fcs(new PointVo(new double[] { 11, 55.5 }));
        assertTrue(generator.generate(request(fcs, "NM"), target));
        BufferedImage nm = MapImageTestSupport.read(target);
        assertTrue(generator.generate(request(fcs, "NW"), target));
        BufferedImage nw = MapImageTestSupport.read(target);

        IconMark point = icons(plan(fcs)).getFirst();
        SymbolSize size = portrayal.print(SIZE).symbol("nw-point");
        assertEquals(size.width(), point.width(), 1e-9, "drawn at the print size");
        assertSpriteAt(nw, symbols.sprite("nw-point", size), point.x(), point.y());
        assertSpriteAt(nm, symbols.sprite("nm-point", size), point.x(), point.y());
        assertFalse(Arrays.equals(pixels(nw), pixels(nm)), "the NW and NM images are the same");
    }

    /** A buffer area gets the definition's translucent buffer fill, and its parent point its symbol */
    @Test
    void aBufferAreaIsFilledWithTheBufferColour() throws IOException {
        double lat = 55.5;
        double rLat = 0.015;
        double rLon = rLat / Math.cos(Math.toRadians(lat));
        double[][] ring = new double[17][];
        for (int k = 0; k <= 16; k++) {
            double a = 2 * Math.PI * (k % 16) / 16;
            ring[k] = new double[] { 11 + rLon * Math.cos(a), lat + rLat * Math.sin(a) };
        }
        FeatureVo parent = feature(new PointVo(new double[] { 11, lat }), Map.of());
        FeatureVo buffer = feature(new PolygonVo(new double[][][] { ring }), Map.of("parentFeatureIds", List.of("p")));
        FeatureCollectionVo[] fcs = { collection(parent, buffer) };
        assertTrue(generator.generate(request(fcs), target));
        BufferedImage image = MapImageTestSupport.read(target);

        TileWindow window = MapImagePlanner.frame(fcs, SIZE, SINGLE_POINT_ZOOM);
        int cx = (int) window.imageX(OpenLayersGeometry.x3857(11));
        int cy = (int) window.imageY(OpenLayersGeometry.y3857(lat));
        int radius = (int) (window.imageX(OpenLayersGeometry.x3857(11 + rLon)) - cx);
        Color fill = portrayal.colour(portrayal.buffer().fill());
        Color expected = overWhite(fill.getRGB());
        // Between the point symbol at the centre and the outline
        int x = cx + (int) (0.75 * radius);
        assertClose(expected, MapImageTestSupport.rgb(image, x, cy), 1, "buffer fill at " + x + "," + cy);
        assertEquals(Color.WHITE, MapImageTestSupport.rgb(image, cx + radius + 6, cy), "no fill outside the buffer");
        IconMark point = icons(plan(fcs)).getFirst();
        assertEquals("nw-point", point.symbolId());
    }

    // ------------------------------------------------------------------------------------------- //
    // Inputs, nothing to draw, failures
    // ------------------------------------------------------------------------------------------- //

    @Test
    void theMessagesGeometryIsLeftUnchanged() throws IOException {
        double[][][] rings = { { { 10.9, 55.4 }, { 11.1, 55.4 }, { 11.1, 55.6 }, { 10.9, 55.4 } } };
        double[][][] before = Arrays.stream(rings)
                .map(r -> Arrays.stream(r).map(double[]::clone).toArray(double[][]::new)).toArray(double[][][]::new);
        FeatureCollectionVo[] fcs = fcs(new PolygonVo(rings));

        assertTrue(generator.generate(request(fcs), target));

        assertTrue(Arrays.deepEquals(before, ((PolygonVo) fcs[0].getFeatures()[0].getGeometry()).getCoordinates()));
    }

    /** Without anything to draw there is no image, no tile request and no file */
    @Test
    void nothingToDrawGivesNoImageNoRequestAndNoFile() throws IOException {
        FeatureVo withoutGeometry = feature(null, Map.of());
        FeatureVo bufferOfAPoint = feature(new PointVo(new double[] { 11, 55.5 }), Map.of("parentFeatureIds", "p"));

        assertFalse(generator.generate(request(null), target));
        assertFalse(generator.generate(request(new FeatureCollectionVo[0]), target));
        assertFalse(generator.generate(request(new FeatureCollectionVo[] { collection(withoutGeometry) }), target));
        assertFalse(generator.generate(request(new FeatureCollectionVo[] { collection(bufferOfAPoint) }), target));

        assertEquals(0, stub.requestCount(), "no tile is requested");
        assertFalse(Files.exists(target.getParent()), "nothing is written");
    }

    /** When the tiles cannot be had, the failure reaches the caller and nothing is written */
    @Test
    void aTileFailureFailsTheImageAndWritesNothing() {
        stub.respondStatus(503);

        assertThrows(TileUnavailableException.class,
                () -> generator.generate(request(fcs(square(11, 55.5, 0.05))), target));

        assertFalse(Files.exists(target.getParent()), "nothing is written");
    }

    /** Without a usable portrayal definition no image can be drawn: the caller shows its placeholder */
    @Test
    void anUnavailableDefinitionFailsTheImage() {
        generator.portrayal = PortrayalTestSupport.unavailableDefinition();

        assertThrows(IOException.class, () -> generator.generate(request(fcs(square(11, 55.5, 0.05))), target));

        assertEquals(0, stub.requestCount());
        assertFalse(Files.exists(target.getParent()));
    }

    /** An image size or a zoom level the map cannot have fails the image as an I/O failure, before any request */
    @Test
    void anImpossibleImageSizeOrZoomLevelFailsTheImage() {
        generator.mapImageSize = 4096;
        assertThrows(IOException.class, () -> generator.generate(request(fcs(square(11, 55.5, 0.05))), target));

        generator.mapImageSize = SIZE;
        generator.zoomLevel = 40;
        assertThrows(IOException.class,
                () -> generator.generate(request(fcs(new PointVo(new double[] { 11, 55.5 }))), target));

        assertEquals(0, stub.requestCount());
        assertFalse(Files.exists(target.getParent()));
    }

    /**
     * The tile source is read by the thread that draws the image, and only when there is something to draw: a
     * request whose image cannot show anything reads no settings
     */
    @Test
    void theTileSourceIsResolvedOnlyForAnImageWithSomethingToDraw() throws IOException {
        TileSource tiles = new TileSource(stub.baseUrl() + "/tiles/{z}/{x}/{y}.png", USER_AGENT, tmp.resolve("tile-cache"));
        List<String> resolvedOn = new ArrayList<>();
        Supplier<TileSource> counting = () -> {
            resolvedOn.add(Thread.currentThread().getName());
            return tiles;
        };

        assertFalse(generator.generate(new MapImageRequest(new FeatureCollectionVo[0], "NW", null, UPDATED, counting),
                target));
        assertEquals(List.of(), resolvedOn, "nothing to draw: the tile source is not read");

        assertTrue(generator.generate(new MapImageRequest(fcs(square(11, 55.5, 0.05)), "NW", null, UPDATED, counting),
                target));
        assertEquals(List.of(Thread.currentThread().getName()), resolvedOn, "read once, by the drawing thread");
    }

    /**
     * The trial image of the start-up draws the planner's, the painter's and the codecs' whole path without a tile
     * request and without a file
     */
    @Test
    void theTrialImageIsDrawnWithoutATileRequestOrAFile() throws IOException {
        BufferedImage image = generator.drawTrialImage();

        assertEquals(SIZE, image.getWidth());
        assertEquals(SIZE, image.getHeight());
        int magenta = 0;
        for (int rgb : pixels(image)) {
            magenta += near(new Color(rgb), NWMGD, 40) ? 1 : 0;
        }
        assertTrue(magenta > 100, "the marks are painted: " + magenta + " magenta pixels");
        assertEquals(0, stub.requestCount(), "no tile is requested");
        try (Stream<Path> files = Files.walk(tmp)) {
            assertEquals(List.of(tmp), files.toList(), "nothing is written");
        }
    }

    /** The file name carries the image size and the definition's image version */
    @Test
    void theFileNameCarriesTheSizeAndTheImageVersion() {
        assertEquals("map_256_" + portrayal.imageVersion() + ".png", generator.imageFileName());
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    private MapImageRequest request(FeatureCollectionVo[] fcs) {
        return request(fcs, "NW");
    }

    private MapImageRequest request(FeatureCollectionVo[] fcs, String mainType) {
        TileSource tiles = new TileSource(stub.baseUrl() + "/tiles/{z}/{x}/{y}.png", USER_AGENT, tmp.resolve("tile-cache"));
        return new MapImageRequest(fcs, mainType, null, UPDATED, () -> tiles);
    }

    /** The plan the generator paints, for finding where on the image to look */
    private List<MapImageMark> plan(FeatureCollectionVo[] fcs) {
        return plan(fcs, "NW");
    }

    private List<MapImageMark> plan(FeatureCollectionVo[] fcs, String mainType) {
        TileWindow window = MapImagePlanner.frame(fcs, SIZE, SINGLE_POINT_ZOOM);
        return MapImagePlanner.plan(fcs, mainType, null, portrayal, portrayal.print(SIZE), window);
    }

    private static List<IconMark> icons(List<MapImageMark> plan) {
        return plan.stream().filter(IconMark.class::isInstance).map(IconMark.class::cast).toList();
    }

    private static boolean inside(List<IconMark> icons, int x, int y) {
        return icons.stream().anyMatch(i -> x >= i.x() - 1 && x <= i.x() + i.width() + 1
                && y >= i.y() - 1 && y <= i.y() + i.height() + 1);
    }

    private List<Integer> requestedZooms() {
        List<Integer> zooms = new ArrayList<>();
        for (RecordedRequest r : stub.requests()) {
            Matcher m = TILE_PATH.matcher(r.path());
            assertTrue(m.matches(), r.path());
            int zoom = Integer.parseInt(m.group(1));
            if (!zooms.contains(zoom)) {
                zooms.add(zoom);
            }
        }
        return zooms;
    }

    /** A pixel of a sprite composited over white, as the image shows it */
    private static Color overWhite(int argb) {
        double a = (argb >>> 24) / 255.0;
        int r = (int) Math.round(((argb >> 16) & 0xFF) * a + 255 * (1 - a));
        int g = (int) Math.round(((argb >> 8) & 0xFF) * a + 255 * (1 - a));
        int b = (int) Math.round((argb & 0xFF) * a + 255 * (1 - a));
        return new Color(r, g, b);
    }

    private static void assertSpriteAt(BufferedImage image, BufferedImage sprite, int left, int top) {
        for (int y = 0; y < sprite.getHeight(); y++) {
            for (int x = 0; x < sprite.getWidth(); x++) {
                assertClose(overWhite(sprite.getRGB(x, y)), MapImageTestSupport.rgb(image, left + x, top + y), 1,
                        "symbol pixel " + x + "," + y);
            }
        }
    }

    private static void assertClose(Color expected, Color actual, int tolerance, String what) {
        assertTrue(Math.abs(expected.getRed() - actual.getRed()) <= tolerance
                        && Math.abs(expected.getGreen() - actual.getGreen()) <= tolerance
                        && Math.abs(expected.getBlue() - actual.getBlue()) <= tolerance,
                what + ": expected " + expected + " but was " + actual);
    }

    private static boolean near(Color actual, Color expected, int tolerance) {
        return Math.abs(expected.getRed() - actual.getRed()) <= tolerance
                && Math.abs(expected.getGreen() - actual.getGreen()) <= tolerance
                && Math.abs(expected.getBlue() - actual.getBlue()) <= tolerance;
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static PolygonVo square(double lon, double lat, double d) {
        return new PolygonVo(new double[][][] { {
                { lon - d, lat - d }, { lon + d, lat - d }, { lon + d, lat + d }, { lon - d, lat + d },
                { lon - d, lat - d } } });
    }

    private static LineStringVo line(double lon1, double lat1, double lon2, double lat2) {
        return new LineStringVo(new double[][] { { lon1, lat1 }, { lon2, lat2 } });
    }

    private static FeatureCollectionVo[] fcs(GeometryVo geometry) {
        return new FeatureCollectionVo[] { collection(feature(geometry, Map.of())) };
    }

    private static FeatureVo feature(GeometryVo geometry, Map<String, Object> properties) {
        FeatureVo feature = new FeatureVo();
        feature.setGeometry(geometry);
        feature.setProperties(new HashMap<>(properties));
        return feature;
    }

    private static FeatureCollectionVo collection(FeatureVo... features) {
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(features);
        return fc;
    }
}
