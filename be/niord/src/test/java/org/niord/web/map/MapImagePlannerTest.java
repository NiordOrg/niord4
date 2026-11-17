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

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.niord.core.portrayal.PortrayalDefinition;
import org.niord.core.portrayal.PortrayalTestSupport;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.geojson.FeatureVo;
import org.niord.model.geojson.GeometryVo;
import org.niord.model.geojson.LineStringVo;
import org.niord.model.geojson.MultiPointVo;
import org.niord.model.geojson.PointVo;
import org.niord.model.geojson.PolygonVo;
import org.niord.web.map.MapImageMark.IconMark;
import org.niord.web.map.MapImageMark.PathMark;
import org.niord.web.map.MapImageMark.Subpath;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The map-image planner draws what the web maps draw. The golden fixtures of the portrayal
 * ({@code fe/shared-map/portrayal/fixtures}) hold map views of message geometry and, in {@code expected.json}, the
 * draw list OpenLayers makes of each with {@code map-styles.ts}; that file is written from the web maps by
 * {@code npm run portrayal:fixtures} and checked there by {@code portrayal-fixtures.spec.ts}. This test plans every
 * case with the same size set and compares: the same fills, strokes and symbols in the same order, the path
 * coordinates within a thousandth of a pixel and every symbol on the same whole pixel.
 *
 * <p>Maven does not copy the fixtures folder onto the class path, so the files are read from the repository,
 * found by walking up from the module's folder.
 */
class MapImagePlannerTest {

    private static final String FIXTURES = "fe/shared-map/portrayal/fixtures/";

    /** How far a path coordinate may be from the web's, in pixels */
    private static final double TOLERANCE = 1e-3;

    private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults().build();

    private final PortrayalDefinition portrayal = PortrayalTestSupport.classPathDefinition();

    // ------------------------------------------------------------------------------------------- //
    // The golden fixtures
    // ------------------------------------------------------------------------------------------- //

    @TestFactory
    Stream<DynamicTest> everyFixtureIsPlannedAsTheWebMapsDrawIt() throws IOException {
        JsonNode cases = read("cases.json");
        JsonNode expected = read("expected.json").get("cases");
        assertEquals("screen", cases.get("sizeSet").asString(), "the fixtures are drawn with the screen size set");

        Set<String> ids = new HashSet<>();
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode fixture : cases.get("cases")) {
            String id = fixture.get("id").asString();
            ids.add(id);
            tests.add(DynamicTest.dynamicTest(id, () -> {
                JsonNode expectedOps = expected.get(id);
                assertNotNull(expectedOps, "expected.json has no draw list for " + id + ": npm run portrayal:fixtures");
                List<String> differences = new ArrayList<>();
                compare(ops(plan(fixture)), expectedOps, id, differences);
                assertTrue(differences.isEmpty(), "the plan of " + id + " differs from the web maps' draw list:\n  "
                        + String.join("\n  ", differences));
            }));
        }
        Set<String> expectedIds = new HashSet<>();
        expected.propertyNames().forEach(expectedIds::add);
        tests.add(DynamicTest.dynamicTest("expected.json has exactly the cases there are",
                () -> assertEquals(ids, expectedIds)));
        return tests.stream();
    }

    /** The plan of a fixture case: its window, as the generator frames an image, and the screen size set */
    private List<MapImageMark> plan(JsonNode fixture) {
        JsonNode view = fixture.get("view");
        TileWindow window = TileWindow.centredOn(
                OpenLayersGeometry.x3857(view.get("centre").get(0).asDouble()),
                OpenLayersGeometry.y3857(view.get("centre").get(1).asDouble()),
                view.get("zoom").asInt(), view.get("size").get(0).asInt(), view.get("size").get(1).asInt());
        ObjectNode collection = JSON.createObjectNode();
        collection.put("type", "FeatureCollection");
        collection.set("features", fixture.get("features"));
        FeatureCollectionVo fc = JSON.treeToValue(collection, FeatureCollectionVo.class);
        String messageType = fixture.has("messageType") ? fixture.get("messageType").asString() : null;
        return MapImagePlanner.plan(new FeatureCollectionVo[] { fc }, fixture.get("mainType").asString(), messageType,
                portrayal, portrayal.screen(), window);
    }

    /** The draw list in the form of expected.json: a fill and then a stroke per path, and each symbol */
    private static ArrayNode ops(List<MapImageMark> marks) {
        ArrayNode ops = JSON.createArrayNode();
        for (MapImageMark mark : marks) {
            switch (mark) {
                case PathMark path -> {
                    if (path.fill() != null) {
                        ObjectNode fill = ops.addObject();
                        fill.put("pass", path.pass().builderType());
                        fill.put("op", "fill");
                        fill.set("colour", colour(path.fill()));
                        fill.set("subpaths", subpaths(path.subpaths()));
                    }
                    if (path.stroke() != null) {
                        ObjectNode stroke = ops.addObject();
                        stroke.put("pass", path.pass().builderType());
                        stroke.put("op", "stroke");
                        stroke.set("colour", colour(path.stroke().colour()));
                        stroke.put("width", path.stroke().width());
                        ArrayNode dash = stroke.putArray("dash");
                        path.stroke().dash().forEach(dash::add);
                        stroke.put("cap", path.stroke().lineStyle().cap());
                        stroke.put("join", path.stroke().lineStyle().join());
                        stroke.put("miterLimit", path.stroke().lineStyle().miterLimit());
                        stroke.set("subpaths", subpaths(path.subpaths()));
                    }
                }
                case IconMark icon -> {
                    ObjectNode op = ops.addObject();
                    op.put("pass", icon.pass().builderType());
                    op.put("op", "icon");
                    op.put("symbol", icon.symbolId());
                    op.put("x", icon.x());
                    op.put("y", icon.y());
                    op.put("width", icon.width());
                    op.put("height", icon.height());
                }
            }
        }
        return ops;
    }

    /** A colour as the canvas records it: red, green, blue and an alpha from 0 to 1 */
    private static ArrayNode colour(Color colour) {
        ArrayNode rgba = JSON.createArrayNode();
        rgba.add(colour.getRed()).add(colour.getGreen()).add(colour.getBlue()).add(colour.getAlpha() / 255.0);
        return rgba;
    }

    private static ArrayNode subpaths(List<Subpath> subpaths) {
        ArrayNode array = JSON.createArrayNode();
        for (Subpath subpath : subpaths) {
            ObjectNode node = array.addObject();
            node.put("closed", subpath.closed());
            ArrayNode points = node.putArray("points");
            double[] xy = subpath.xy();
            for (int i = 0; i < xy.length; i += 2) {
                points.addArray().add(xy[i]).add(xy[i + 1]);
            }
        }
        return array;
    }

    /**
     * Collects the differences between a draw list and the expected one. Path coordinates may differ by
     * {@value #TOLERANCE} px; an alpha by what the canvas can hold (a 256th); every other number must be the
     * same, symbol positions included.
     */
    private static void compare(JsonNode actual, JsonNode expected, String path, List<String> out) {
        if (expected == null || actual == null) {
            if (expected != actual) {
                out.add(path + ": " + actual + " is not " + expected);
            }
        } else if (expected.isNumber() && actual.isNumber()) {
            double tolerance = path.contains(".points") ? TOLERANCE : path.endsWith("colour[3]") ? 0.5 / 255 : 1e-9;
            if (!(Math.abs(actual.asDouble() - expected.asDouble()) <= tolerance)) {
                out.add(path + ": " + actual + " is not " + expected);
            }
        } else if (expected.isArray() && actual.isArray()) {
            if (actual.size() != expected.size()) {
                out.add(path + ": " + actual.size() + " entries, not " + expected.size() + " (" + actual + ")");
            } else {
                for (int i = 0; i < expected.size(); i++) {
                    compare(actual.get(i), expected.get(i), path + "[" + i + "]", out);
                }
            }
        } else if (expected.isObject() && actual.isObject()) {
            Set<String> keys = new HashSet<>();
            expected.propertyNames().forEach(keys::add);
            actual.propertyNames().forEach(keys::add);
            for (String key : keys) {
                compare(actual.get(key), expected.get(key), path + "." + key, out);
            }
        } else if (!actual.equals(expected)) {
            out.add(path + ": " + actual + " is not " + expected);
        }
    }

    private static JsonNode read(String file) throws IOException {
        return JSON.readTree(Files.readString(PortrayalTestSupport.repositoryPath(FIXTURES + file)));
    }

    // ------------------------------------------------------------------------------------------- //
    // Framing
    // ------------------------------------------------------------------------------------------- //

    /** One point is shown at the configured zoom level, centred on it */
    @Test
    void aSinglePointIsShownAtTheConfiguredZoomLevel() {
        TileWindow window = MapImagePlanner.frame(fcs(new PointVo(new double[] { 11, 55.5 })), 256, 7);

        assertEquals(7, window.zoom());
        assertEquals(256, window.width());
        assertEquals(256, window.height());
        assertEquals(128, window.imageX(OpenLayersGeometry.x3857(11)), 0.5);
        assertEquals(128, window.imageY(OpenLayersGeometry.y3857(55.5)), 0.5);
    }

    /**
     * A multi-point with one point is not "a single point": its bounding box has no size, so it is shown at the
     * highest zoom level
     */
    @Test
    void aMultiPointOfOnePointIsFittedLikeAnyOtherGeometry() {
        TileWindow window = MapImagePlanner.frame(fcs(new MultiPointVo(new double[][] { { 11, 55.5 } })), 256, 7);

        assertEquals(MapImagePlanner.MAX_ZOOM, window.zoom());
    }

    /**
     * Any other geometry gets the highest zoom level at which its bounding box fills at most 80 % of the image: a
     * box of 150 x 150 px at zoom 10 (under 204.8) fits there but not at zoom 11 (300 px)
     */
    @Test
    void aGeometryIsShownAtTheHighestZoomLevelWhereItFillsAtMost80PercentOfTheImage() {
        double lonPerPixel10 = 360.0 / (256 << 10);
        double west = 11;
        double east = west + 150 * lonPerPixel10;
        LineStringVo line = new LineStringVo(new double[][] { { west, 55.5 }, { east, 55.5 } });

        assertEquals(10, MapImagePlanner.frame(fcs(line), 256, 7).zoom());
        // 210 px at zoom 10 is more than 204.8
        double wider = west + 210 * lonPerPixel10;
        assertEquals(9, MapImagePlanner.frame(fcs(new LineStringVo(new double[][] { { west, 55.5 }, { wider, 55.5 } })),
                256, 7).zoom());
    }

    @Test
    void aTinyGeometryIsShownAtTheHighestZoomLevelAndAHugeOneAtTheLowest() {
        LineStringVo tiny = new LineStringVo(new double[][] { { 11, 55.5 }, { 11.001, 55.501 } });
        LineStringVo huge = new LineStringVo(new double[][] { { -100, -40 }, { 100, 60 } });

        assertEquals(MapImagePlanner.MAX_ZOOM, MapImagePlanner.frame(fcs(tiny), 256, 7).zoom());
        assertEquals(MapImagePlanner.MIN_ZOOM, MapImagePlanner.frame(fcs(huge), 256, 7).zoom());
    }

    /** The image is centred on the average of the feature collections' centres, as the previous images were */
    @Test
    void theImageIsCentredOnTheAverageOfTheFeatureCollectionsCentres() {
        FeatureCollectionVo[] fcs = {
                fcs(new PointVo(new double[] { 10, 55 }))[0],
                fcs(new LineStringVo(new double[][] { { 11, 56 }, { 13, 56 } }))[0] };

        TileWindow window = MapImagePlanner.frame(fcs, 256, 7);

        // Centres (10, 55) and (12, 56): the image centre is (11, 55.5)
        assertEquals(128, window.imageX(OpenLayersGeometry.x3857(11)), 0.5);
        assertEquals(128, window.imageY(OpenLayersGeometry.y3857(55.5)), 0.5);
    }

    @Test
    void aGeometryWithoutPositionsHasNoFrame() {
        assertNull(MapImagePlanner.frame(null, 256, 7));
        assertNull(MapImagePlanner.frame(new FeatureCollectionVo[0], 256, 7));
        FeatureVo empty = new FeatureVo();
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[] { empty });
        assertNull(MapImagePlanner.frame(new FeatureCollectionVo[] { fc }, 256, 7));
    }

    // ------------------------------------------------------------------------------------------- //
    // Inputs
    // ------------------------------------------------------------------------------------------- //

    /** Framing and planning read the message's geometry and leave every coordinate as it was */
    @Test
    void framingAndPlanningLeaveTheGeometryUnchanged() {
        double[][][] rings = { { { 10.9, 55.4 }, { 11.1, 55.4 }, { 11.1, 55.6 }, { 10.9, 55.4 } } };
        double[][] line = { { 10.8, 55.5 }, { 11.2, 55.55 } };
        FeatureCollectionVo[] fcs = {
                fcs(new PolygonVo(rings))[0],
                fcs(new LineStringVo(line))[0],
                fcs(new PointVo(new double[] { 11, 55.5 }), Map.of("approximate", true))[0] };
        double[][][] ringsBefore = deepCopy(rings);
        double[][] lineBefore = Arrays.stream(line).map(double[]::clone).toArray(double[][]::new);

        TileWindow window = MapImagePlanner.frame(fcs, 256, 7);
        List<MapImageMark> marks = MapImagePlanner.plan(fcs, "NW", null, portrayal, portrayal.print(256), window);

        assertTrue(marks.size() > 3, "the plan draws the geometry: " + marks);
        assertTrue(Arrays.deepEquals(ringsBefore, ((PolygonVo) fcs[0].getFeatures()[0].getGeometry()).getCoordinates()));
        assertTrue(Arrays.deepEquals(lineBefore, ((LineStringVo) fcs[1].getFeatures()[0].getGeometry()).getCoordinates()));
        assertTrue(Arrays.equals(new double[] { 11, 55.5 },
                ((PointVo) fcs[2].getFeatures()[0].getGeometry()).getCoordinates()));
    }

    /** The print size set scaled to the image: symbols at the definition's print sizes for a 256 px image */
    @Test
    void theSymbolsAreDrawnAtTheSizesOfTheSizeSetGiven() {
        FeatureCollectionVo[] fcs = fcs(new PointVo(new double[] { 11, 55.5 }));
        TileWindow window = MapImagePlanner.frame(fcs, 512, 7);

        List<MapImageMark> marks = MapImagePlanner.plan(fcs, "NM", null, portrayal, portrayal.print(512), window);

        assertEquals(1, marks.size());
        IconMark icon = (IconMark) marks.getFirst();
        assertEquals("nm-point", icon.symbolId());
        assertEquals(2 * portrayal.print().symbol("nm-point").width(), icon.width(), 1e-9);
        // The symbol's pivot lies on the point, within the whole-pixel snap
        assertEquals(256, icon.x() + portrayal.symbol("nm-point").anchorX() * icon.width(), 1.0);
    }

    private static double[][][] deepCopy(double[][][] rings) {
        return Arrays.stream(rings)
                .map(ring -> Arrays.stream(ring).map(double[]::clone).toArray(double[][]::new))
                .toArray(double[][][]::new);
    }

    private static FeatureCollectionVo[] fcs(GeometryVo geometry) {
        return fcs(geometry, Map.of());
    }

    private static FeatureCollectionVo[] fcs(GeometryVo geometry, Map<String, Object> properties) {
        FeatureVo feature = new FeatureVo();
        feature.setGeometry(geometry);
        feature.setProperties(new java.util.HashMap<>(properties));
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[] { feature });
        return new FeatureCollectionVo[] { fc };
    }
}
