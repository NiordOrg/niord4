/*
 * Copyright 2024 Danish Maritime Authority.
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
package org.niord.model.geojson;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Framework-neutral unit tests for the shared base logic in {@link GeoJsonVo},
 * exercised through its concrete geometry subclasses.
 *
 * <p>These tests cover the dimension-walking {@code visitCoordinates} helper, the
 * {@code computeBBox}/{@code computeCenter} computations, accessor round-trips and
 * the JSON serialisation/deserialisation contract (including the polymorphic
 * "type" discriminator). They depend only on JUnit 4 and Jackson, both of which
 * already ship with the project.</p>
 */
public class GeoJsonVoTest {

    /** Collects every coordinate array passed to a {@link Consumer} handler. */
    private List<double[]> collector() {
        return new ArrayList<>();
    }

    private Consumer<double[]> collectInto(List<double[]> sink) {
        return sink::add;
    }

    // ------------------------------------------------------------------
    // Static dimension-walking visitCoordinates: counts and exact pairs
    // ------------------------------------------------------------------

    @Test
    public void visitCoordinates_point_visitsSingleCoordinatePair() {
        List<double[]> visited = collector();
        new PointVo(new double[]{1, 2}).visitCoordinates(collectInto(visited));

        assertEquals(1, visited.size());
        assertArrayEquals(new double[]{1, 2}, visited.get(0), 0.0);
    }

    @Test
    public void visitCoordinates_lineString_visitsEachVertexInOrder() {
        List<double[]> visited = collector();
        new LineStringVo(new double[][]{{0, 0}, {2, 4}}).visitCoordinates(collectInto(visited));

        assertEquals(2, visited.size());
        assertArrayEquals(new double[]{0, 0}, visited.get(0), 0.0);
        assertArrayEquals(new double[]{2, 4}, visited.get(1), 0.0);
    }

    @Test
    public void visitCoordinates_polygon_visitsEveryRingVertex() {
        List<double[]> visited = collector();
        new PolygonVo(new double[][][]{{{0, 0}, {1, 0}, {1, 1}, {0, 0}}})
                .visitCoordinates(collectInto(visited));

        assertEquals(4, visited.size());
        assertArrayEquals(new double[]{0, 0}, visited.get(0), 0.0);
        assertArrayEquals(new double[]{1, 0}, visited.get(1), 0.0);
        assertArrayEquals(new double[]{1, 1}, visited.get(2), 0.0);
        assertArrayEquals(new double[]{0, 0}, visited.get(3), 0.0);
    }

    @Test
    public void visitCoordinates_multiPolygon_walksFourDimensionalArray() {
        List<double[]> visited = collector();
        // one polygon, one ring, three points
        double[][][][] coords = new double[][][][]{
                {{{10, 20}, {30, 40}, {50, 60}}}
        };
        new MultiPolygonVo(coords).visitCoordinates(collectInto(visited));

        assertEquals(3, visited.size());
        assertArrayEquals(new double[]{10, 20}, visited.get(0), 0.0);
        assertArrayEquals(new double[]{30, 40}, visited.get(1), 0.0);
        assertArrayEquals(new double[]{50, 60}, visited.get(2), 0.0);
    }

    // ------------------------------------------------------------------
    // The length >= 2 skip rule, and null no-op
    // ------------------------------------------------------------------

    @Test
    public void visitCoordinates_skipsCoordinateArrayShorterThanTwo() {
        List<double[]> visited = collector();
        new PointVo(new double[]{5}).visitCoordinates(collectInto(visited));

        assertEquals(0, visited.size());
    }

    @Test
    public void visitCoordinates_passesWholeArrayWhenLongerThanTwo() {
        List<double[]> visited = collector();
        // A 3-element coordinate (e.g. x,y,z) is accepted whole, not truncated.
        new PointVo(new double[]{1, 2, 3}).visitCoordinates(collectInto(visited));

        assertEquals(1, visited.size());
        assertArrayEquals(new double[]{1, 2, 3}, visited.get(0), 0.0);
    }

    @Test
    public void visitCoordinates_nullCoordinatesIsNoOp() {
        List<double[]> visited = collector();
        new PointVo((double[]) null).visitCoordinates(collectInto(visited));

        assertEquals(0, visited.size());
    }

    @Test
    public void visitCoordinates_emptyLineStringVisitsNothing() {
        List<double[]> visited = collector();
        new LineStringVo(new double[][]{}).visitCoordinates(collectInto(visited));

        assertEquals(0, visited.size());
    }

    // ------------------------------------------------------------------
    // computeBBox / computeCenter
    // ------------------------------------------------------------------

    @Test
    public void computeBBox_lineString_returnsMinMaxExtents() {
        double[] bbox = new LineStringVo(new double[][]{{0, 0}, {2, 4}}).computeBBox();
        assertArrayEquals(new double[]{0.0, 0.0, 2.0, 4.0}, bbox, 0.0);
    }

    @Test
    public void computeBBox_handlesNegativeCoordinates() {
        // {minX, minY, maxX, maxY} over the two vertices, including negatives.
        double[] bbox = new PolygonVo(new double[][][]{{{-1, -3}, {5, 2}}}).computeBBox();
        assertArrayEquals(new double[]{-1.0, -3.0, 5.0, 2.0}, bbox, 0.0);
    }

    @Test
    public void computeCenter_lineString_returnsMidpointOfBBox() {
        double[] center = new LineStringVo(new double[][]{{0, 0}, {2, 4}}).computeCenter();
        assertArrayEquals(new double[]{1.0, 2.0}, center, 0.0);
    }

    @Test
    public void computeBBox_singlePoint_isDegenerateBoxAtThatPoint() {
        double[] bbox = new PointVo(new double[]{7, 9}).computeBBox();
        assertArrayEquals(new double[]{7.0, 9.0, 7.0, 9.0}, bbox, 0.0);
    }

    // ------------------------------------------------------------------
    // Empty-geometry edge: no empty-guard in computeBBox/computeCenter.
    // The source carries a "TODO: Naive implementation" comment, so these
    // pin the CURRENT (surprising) sentinel behaviour rather than asserting
    // it is correct. See structured findings.
    // ------------------------------------------------------------------

    @Test
    public void computeBBox_emptyGeometry_returnsSentinelBounds_documentsBehavior() {
        // No visitable coordinates -> the initial sentinels are never replaced:
        // {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE}.
        double[] bbox = new PointVo((double[]) null).computeBBox();
        assertArrayEquals(
                new double[]{2.147483647E9, 2.147483647E9, -2.147483648E9, -2.147483648E9},
                bbox, 0.0);
    }

    @Test
    public void computeCenter_emptyGeometry_returnsHalfNegativeHalf_documentsBehavior() {
        // (MAX_VALUE + MIN_VALUE)/2 == -0.5 on both axes for an empty geometry.
        double[] center = new PointVo((double[]) null).computeCenter();
        assertArrayEquals(new double[]{-0.5, -0.5}, center, 0.0);
    }

    // ------------------------------------------------------------------
    // Accessors: bbox / type / crs round-trips
    // ------------------------------------------------------------------

    @Test
    public void setBbox_getBbox_roundTripsArray() {
        PointVo pt = new PointVo(new double[]{0, 0});
        double[] box = {1.0, 2.0, 3.0, 4.0};
        pt.setBbox(box);

        assertSame(box, pt.getBbox());
        assertArrayEquals(new double[]{1.0, 2.0, 3.0, 4.0}, pt.getBbox(), 0.0);
    }

    @Test
    public void getType_reflectsConstructorAndSetterOverride() {
        // Each concrete constructor sets its discriminator string...
        assertEquals("Point", new PointVo(new double[]{0, 0}).getType());
        assertEquals("LineString", new LineStringVo(new double[][]{{0, 0}}).getType());
        assertEquals("Polygon", new PolygonVo(new double[][][]{}).getType());
        assertEquals("MultiPolygon", new MultiPolygonVo(new double[][][][]{}).getType());

        // ...and setType can override it.
        PointVo pt = new PointVo(new double[]{0, 0});
        pt.setType("Custom");
        assertEquals("Custom", pt.getType());
    }

    @Test
    public void setCrs_getCrs_roundTripsCrsValueObject() {
        PointVo pt = new PointVo(new double[]{0, 0});
        CrsVo crs = new CrsVo();
        crs.setType("name");
        pt.setCrs(crs);

        assertSame(crs, pt.getCrs());
        assertEquals("name", pt.getCrs().getType());
    }

    @Test
    public void crsVo_propertiesDefaultsToEmptyAndIsMutable() {
        CrsVo crs = new CrsVo();
        assertNotNull(crs.getProperties());
        assertTrue(crs.getProperties().isEmpty());

        crs.getProperties().setProperty("name", "EPSG:4326");
        assertEquals("EPSG:4326", crs.getProperties().getProperty("name"));
    }

    // ------------------------------------------------------------------
    // toString() serialises via Jackson and emits the type discriminator
    // ------------------------------------------------------------------

    @Test
    public void toString_serialisesPointAsJsonWithTypeAndCoordinates() {
        String json = new PointVo(new double[]{1, 2}).toString();

        assertTrue(json.contains("\"type\":\"Point\""),
                "expected type discriminator, was: " + json);
        assertTrue(json.contains("1.0"), "expected x coordinate, was: " + json);
        assertTrue(json.contains("2.0"), "expected y coordinate, was: " + json);
    }

    // ------------------------------------------------------------------
    // Polymorphic Jackson round-trip via the @JsonTypeInfo discriminator
    // ------------------------------------------------------------------

    @Test
    public void deserialize_pointJson_yieldsPointVoSubclassWithCoordinates() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        GeoJsonVo vo = mapper.readValue(
                "{\"type\":\"Point\",\"coordinates\":[1.0,2.0]}", GeoJsonVo.class);

        assertTrue(vo instanceof PointVo, "expected PointVo, was " + vo.getClass());
        assertEquals("Point", vo.getType());
        assertArrayEquals(new double[]{1.0, 2.0}, ((PointVo) vo).getCoordinates(), 0.0);
    }

    @Test
    public void deserialize_polygonJson_yieldsPolygonVoSubclass() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        GeoJsonVo vo = mapper.readValue(
                "{\"type\":\"Polygon\",\"coordinates\":[[[0.0,0.0],[1.0,0.0],[1.0,1.0],[0.0,0.0]]]}",
                GeoJsonVo.class);

        assertTrue(vo instanceof PolygonVo, "expected PolygonVo, was " + vo.getClass());

        List<double[]> visited = collector();
        vo.visitCoordinates(collectInto(visited));
        assertEquals(4, visited.size());
    }

    @Test
    public void serializeThenDeserialize_lineString_preservesTypeAndCoordinates() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        LineStringVo original = new LineStringVo(new double[][]{{0, 0}, {2, 4}});

        String json = mapper.writeValueAsString(original);
        assertTrue(json.contains("\"type\":\"LineString\""), "missing discriminator: " + json);

        GeoJsonVo back = mapper.readValue(json, GeoJsonVo.class);
        assertTrue(back instanceof LineStringVo, "expected LineStringVo, was " + back.getClass());

        List<double[]> visited = collector();
        back.visitCoordinates(collectInto(visited));
        assertEquals(2, visited.size());
        assertArrayEquals(new double[]{0, 0}, visited.get(0), 0.0);
        assertArrayEquals(new double[]{2, 4}, visited.get(1), 0.0);
    }
}
