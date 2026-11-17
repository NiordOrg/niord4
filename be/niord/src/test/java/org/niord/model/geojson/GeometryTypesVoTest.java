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
 * Unit tests for the concrete GeoJSON geometry value objects:
 * {@link PointVo}, {@link MultiPointVo}, {@link LineStringVo}, {@link MultiLineStringVo},
 * {@link PolygonVo}, {@link MultiPolygonVo} and {@link GeometryCollectionVo}.
 *
 * <p>These tests exercise the REST/JSON API contract of the geometry model: the type
 * discriminator set by each no-arg constructor, coordinate accessor round-tripping with the
 * correct array dimensionality, the {@code visitCoordinates(...)} traversal (count and exact
 * pairs), and the Jackson polymorphic (de)serialization that the model's
 * {@code @JsonTypeInfo}/{@code @JsonSubTypes} annotations drive.
 *
 * <p>Framework-neutral: only JUnit 4 + Jackson are used. Jackson is a production dependency of
 * the model itself ({@link GeoJsonVo#toString()} serialises via Jackson), so the (de)serialization
 * assertions verify the actual API contract rather than a framework helper.
 */
public class GeometryTypesVoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Helper consumer that records every visited coordinate pair into a backing list,
     * so tests can assert both the visit count and the exact coordinates.
     */
    private static final class CoordCollector implements Consumer<double[]> {
        final List<double[]> visited = new ArrayList<>();
        @Override
        public void accept(double[] xy) {
            visited.add(xy);
        }
    }

    /** Returns true if {@code visited} contains a coordinate pair equal to {@code expected}. */
    private static boolean contains(List<double[]> visited, double[] expected) {
        for (double[] xy : visited) {
            if (xy.length == expected.length) {
                boolean equal = true;
                for (int i = 0; i < expected.length; i++) {
                    if (Double.compare(xy[i], expected[i]) != 0) {
                        equal = false;
                        break;
                    }
                }
                if (equal) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Default type discriminators set by the no-arg constructors
    // ------------------------------------------------------------------

    @Test
    public void noArgConstructorsSetTheCorrectTypeDiscriminator() {
        assertEquals("Point", new PointVo().getType());
        assertEquals("MultiPoint", new MultiPointVo().getType());
        assertEquals("LineString", new LineStringVo().getType());
        assertEquals("MultiLineString", new MultiLineStringVo().getType());
        assertEquals("Polygon", new PolygonVo().getType());
        assertEquals("MultiPolygon", new MultiPolygonVo().getType());
        assertEquals("GeometryCollection", new GeometryCollectionVo().getType());
    }

    @Test
    public void coordinateConstructorAlsoSetsTheTypeDiscriminator() {
        // The coordinate-taking constructors chain to this() so the type must still be set.
        assertEquals("Point", new PointVo(new double[]{1, 2}).getType());
        assertEquals("MultiPoint", new MultiPointVo(new double[][]{{1, 2}}).getType());
        assertEquals("LineString", new LineStringVo(new double[][]{{1, 2}, {3, 4}}).getType());
        assertEquals("MultiLineString", new MultiLineStringVo(new double[][][]{{{1, 2}}}).getType());
        assertEquals("Polygon", new PolygonVo(new double[][][]{{{1, 2}}}).getType());
        assertEquals("MultiPolygon", new MultiPolygonVo(new double[][][][]{{{{1, 2}}}}).getType());
        assertEquals("GeometryCollection", new GeometryCollectionVo(new GeometryVo[0]).getType());
    }

    // ------------------------------------------------------------------
    // PointVo
    // ------------------------------------------------------------------

    @Test
    public void pointCoordinatesRoundTripViaConstructorAndSetter() {
        PointVo p = new PointVo(new double[]{10, 20});
        assertArrayEquals(new double[]{10, 20}, p.getCoordinates(), 0.0);

        p.setCoordinates(new double[]{-7.5, 42.25});
        assertArrayEquals(new double[]{-7.5, 42.25}, p.getCoordinates(), 0.0);
    }

    @Test
    public void pointVisitsItsSingleCoordinatePair() {
        CoordCollector c = new CoordCollector();
        new PointVo(new double[]{10, 20}).visitCoordinates(c);

        assertEquals(1, c.visited.size());
        assertArrayEquals(new double[]{10, 20}, c.visited.get(0), 0.0);
    }

    @Test
    public void pointWithNullCoordinatesVisitsNothing() {
        CoordCollector c = new CoordCollector();
        new PointVo().visitCoordinates(c);
        assertEquals(0, c.visited.size());
    }

    @Test
    public void pointWithSingleElementCoordinateIsSkipped() {
        // visitCoordinates only accepts arrays of length >= 2; a length-1 array is skipped.
        CoordCollector c = new CoordCollector();
        new PointVo(new double[]{5}).visitCoordinates(c);
        assertEquals(0, c.visited.size());
    }

    @Test
    public void pointWithThreeDimensionalCoordinateIsVisited() {
        // length >= 2 qualifies, so a 3D coordinate (x, y, z) is passed through unchanged.
        CoordCollector c = new CoordCollector();
        new PointVo(new double[]{1, 2, 3}).visitCoordinates(c);
        assertEquals(1, c.visited.size());
        assertArrayEquals(new double[]{1, 2, 3}, c.visited.get(0), 0.0);
    }

    // ------------------------------------------------------------------
    // MultiPointVo
    // ------------------------------------------------------------------

    @Test
    public void multiPointCoordinatesRoundTrip() {
        double[][] coords = {{1, 1}, {2, 2}, {3, 3}};
        MultiPointVo mp = new MultiPointVo(coords);
        assertSame(coords, mp.getCoordinates());
        assertArrayEquals(new double[]{2, 2}, mp.getCoordinates()[1], 0.0);

        double[][] replaced = {{9, 8}};
        mp.setCoordinates(replaced);
        assertSame(replaced, mp.getCoordinates());
    }

    @Test
    public void multiPointVisitsEachPointInOrder() {
        CoordCollector c = new CoordCollector();
        new MultiPointVo(new double[][]{{1, 1}, {2, 2}, {3, 3}}).visitCoordinates(c);

        assertEquals(3, c.visited.size());
        assertArrayEquals(new double[]{1, 1}, c.visited.get(0), 0.0);
        assertArrayEquals(new double[]{2, 2}, c.visited.get(1), 0.0);
        assertArrayEquals(new double[]{3, 3}, c.visited.get(2), 0.0);
    }

    // ------------------------------------------------------------------
    // LineStringVo
    // ------------------------------------------------------------------

    @Test
    public void lineStringCoordinatesRoundTrip() {
        double[][] coords = {{0, 0}, {1, 1}};
        LineStringVo ls = new LineStringVo(coords);
        assertSame(coords, ls.getCoordinates());

        double[][] replaced = {{5, 5}, {6, 6}, {7, 7}};
        ls.setCoordinates(replaced);
        assertEquals(3, ls.getCoordinates().length);
        assertArrayEquals(new double[]{7, 7}, ls.getCoordinates()[2], 0.0);
    }

    @Test
    public void lineStringVisitsEachVertex() {
        CoordCollector c = new CoordCollector();
        new LineStringVo(new double[][]{{3, 4}, {5, 6}}).visitCoordinates(c);

        assertEquals(2, c.visited.size());
        assertArrayEquals(new double[]{3, 4}, c.visited.get(0), 0.0);
        assertArrayEquals(new double[]{5, 6}, c.visited.get(1), 0.0);
    }

    // ------------------------------------------------------------------
    // MultiLineStringVo
    // ------------------------------------------------------------------

    @Test
    public void multiLineStringCoordinatesRoundTrip() {
        double[][][] coords = {{{0, 0}, {1, 1}}, {{2, 2}, {3, 3}}};
        MultiLineStringVo mls = new MultiLineStringVo(coords);
        assertSame(coords, mls.getCoordinates());
        assertArrayEquals(new double[]{3, 3}, mls.getCoordinates()[1][1], 0.0);

        double[][][] replaced = {{{8, 8}, {9, 9}}};
        mls.setCoordinates(replaced);
        assertSame(replaced, mls.getCoordinates());
    }

    @Test
    public void multiLineStringVisitsAllVerticesAcrossLines() {
        CoordCollector c = new CoordCollector();
        new MultiLineStringVo(new double[][][]{
                {{0, 0}, {1, 1}},
                {{2, 2}, {3, 3}}
        }).visitCoordinates(c);

        assertEquals(4, c.visited.size());
        assertTrue(contains(c.visited, new double[]{0, 0}));
        assertTrue(contains(c.visited, new double[]{1, 1}));
        assertTrue(contains(c.visited, new double[]{2, 2}));
        assertTrue(contains(c.visited, new double[]{3, 3}));
    }

    // ------------------------------------------------------------------
    // PolygonVo
    // ------------------------------------------------------------------

    @Test
    public void polygonCoordinatesRoundTrip() {
        double[][][] ring = {{{0, 0}, {4, 0}, {4, 4}, {0, 0}}};
        PolygonVo poly = new PolygonVo(ring);
        assertSame(ring, poly.getCoordinates());
        assertArrayEquals(new double[]{4, 4}, poly.getCoordinates()[0][2], 0.0);

        double[][][] replaced = {{{1, 1}, {2, 2}}};
        poly.setCoordinates(replaced);
        assertSame(replaced, poly.getCoordinates());
    }

    @Test
    public void polygonVisitsEveryRingVertex() {
        CoordCollector c = new CoordCollector();
        new PolygonVo(new double[][][]{
                {{0, 0}, {4, 0}, {4, 4}, {0, 0}}
        }).visitCoordinates(c);

        assertEquals(4, c.visited.size());
        assertArrayEquals(new double[]{0, 0}, c.visited.get(0), 0.0);
        assertArrayEquals(new double[]{4, 0}, c.visited.get(1), 0.0);
        assertArrayEquals(new double[]{4, 4}, c.visited.get(2), 0.0);
        assertArrayEquals(new double[]{0, 0}, c.visited.get(3), 0.0);
    }

    // ------------------------------------------------------------------
    // MultiPolygonVo
    // ------------------------------------------------------------------

    @Test
    public void multiPolygonCoordinatesRoundTrip() {
        double[][][][] coords = {
                {{{0, 0}, {1, 0}, {1, 1}, {0, 0}}},
                {{{5, 5}, {6, 5}, {6, 6}, {5, 5}}}
        };
        MultiPolygonVo mp = new MultiPolygonVo(coords);
        assertSame(coords, mp.getCoordinates());
        assertArrayEquals(new double[]{6, 6}, mp.getCoordinates()[1][0][2], 0.0);

        double[][][][] replaced = {{{{9, 9}, {8, 8}}}};
        mp.setCoordinates(replaced);
        assertSame(replaced, mp.getCoordinates());
    }

    @Test
    public void multiPolygonVisitsEveryVertexAcrossAllPolygons() {
        CoordCollector c = new CoordCollector();
        new MultiPolygonVo(new double[][][][]{
                {{{0, 0}, {1, 0}, {1, 1}, {0, 0}}},
                {{{5, 5}, {6, 5}, {6, 6}, {5, 5}}}
        }).visitCoordinates(c);

        // Two polygons, one ring of 4 points each -> 8 visits.
        assertEquals(8, c.visited.size());
        assertTrue(contains(c.visited, new double[]{0, 0}));
        assertTrue(contains(c.visited, new double[]{1, 1}));
        assertTrue(contains(c.visited, new double[]{5, 5}));
        assertTrue(contains(c.visited, new double[]{6, 6}));
    }

    // ------------------------------------------------------------------
    // GeometryCollectionVo
    // ------------------------------------------------------------------

    @Test
    public void geometryCollectionGeometriesRoundTrip() {
        GeometryVo[] geometries = {
                new PointVo(new double[]{1, 2}),
                new LineStringVo(new double[][]{{3, 4}, {5, 6}})
        };
        GeometryCollectionVo gc = new GeometryCollectionVo(geometries);
        assertSame(geometries, gc.getGeometries());
        assertEquals(2, gc.getGeometries().length);

        GeometryVo[] replaced = {new PointVo(new double[]{7, 8})};
        gc.setGeometries(replaced);
        assertSame(replaced, gc.getGeometries());
        assertEquals(1, gc.getGeometries().length);
    }

    @Test
    public void geometryCollectionVisitsCoordinatesOfAllChildGeometries() {
        CoordCollector c = new CoordCollector();
        new GeometryCollectionVo(new GeometryVo[]{
                new PointVo(new double[]{1, 2}),
                new LineStringVo(new double[][]{{3, 4}, {5, 6}})
        }).visitCoordinates(c);

        // PointVo (1 coord) + LineStringVo (2 coords) = 3 visits.
        assertEquals(3, c.visited.size());
        assertTrue(contains(c.visited, new double[]{1, 2}));
        assertTrue(contains(c.visited, new double[]{3, 4}));
        assertTrue(contains(c.visited, new double[]{5, 6}));
    }

    @Test
    public void geometryCollectionWithNullGeometriesVisitsNothing() {
        // No-arg constructor leaves geometries == null; visitCoordinates is a guarded no-op.
        CoordCollector c = new CoordCollector();
        new GeometryCollectionVo().visitCoordinates(c);
        assertEquals(0, c.visited.size());
    }

    @Test
    public void geometryCollectionWithEmptyArrayVisitsNothing() {
        CoordCollector c = new CoordCollector();
        new GeometryCollectionVo(new GeometryVo[0]).visitCoordinates(c);
        assertEquals(0, c.visited.size());
    }

    @Test
    public void geometryCollectionWithNullChildElementSkipsIt() {
        // G2 fixed: GeometryCollectionVo.visitCoordinates now null-guards each child element, so a
        // null child is skipped (its non-null siblings are still visited) instead of throwing NPE.
        CoordCollector c = new CoordCollector();
        new GeometryCollectionVo(new GeometryVo[]{null, new PointVo(new double[]{10, 20})})
                .visitCoordinates(c);
        assertEquals(1, c.visited.size());
        assertTrue(contains(c.visited, new double[]{10, 20}));
    }

    // ------------------------------------------------------------------
    // computeBBox / computeCenter on the geometry subtypes
    // ------------------------------------------------------------------

    @Test
    public void computeBBoxOnPolygonReturnsMinMaxOfVertices() {
        PolygonVo poly = new PolygonVo(new double[][][]{
                {{0, 0}, {4, 1}, {2, 5}, {0, 0}}
        });
        // bbox = {minX, minY, maxX, maxY}
        assertArrayEquals(new double[]{0, 0, 4, 5}, poly.computeBBox(), 0.0);
    }

    @Test
    public void computeCenterOnPolygonReturnsBBoxMidpoint() {
        PolygonVo poly = new PolygonVo(new double[][][]{
                {{0, 0}, {4, 0}, {4, 6}, {0, 6}, {0, 0}}
        });
        assertArrayEquals(new double[]{2, 3}, poly.computeCenter(), 0.0);
    }

    @Test
    public void computeBBoxWithNegativeCoordinates() {
        LineStringVo ls = new LineStringVo(new double[][]{{-10, -20}, {-1, -2}});
        assertArrayEquals(new double[]{-10, -20, -1, -2}, ls.computeBBox(), 0.0);
    }

    @Test
    public void computeBBoxOnEmptyGeometryReturnsSentinelBounds_documentsBug() {
        // KNOWN EDGE: computeBBox() has no empty-geometry guard ("TODO: Naive implementation").
        // With no visitable coordinates, the sentinel {MAX,MAX,MIN,MIN} is returned unchanged,
        // i.e. an inverted bounding box. Pinning current (surprising) behavior.
        double[] bbox = new GeometryCollectionVo(new GeometryVo[0]).computeBBox();
        assertEquals((double) Integer.MAX_VALUE, bbox[0], 0.0);
        assertEquals((double) Integer.MAX_VALUE, bbox[1], 0.0);
        assertEquals((double) Integer.MIN_VALUE, bbox[2], 0.0);
        assertEquals((double) Integer.MIN_VALUE, bbox[3], 0.0);
    }

    @Test
    public void computeCenterOnEmptyGeometryReturnsMinusHalf_documentsBug() {
        // KNOWN EDGE: center of the sentinel bbox is ((MAX+MIN)/2, (MAX+MIN)/2) = (-0.5, -0.5).
        double[] center = new PointVo().computeCenter();
        assertArrayEquals(new double[]{-0.5, -0.5}, center, 0.0);
    }

    // ------------------------------------------------------------------
    // Jackson polymorphic (de)serialization - the JSON API contract
    // ------------------------------------------------------------------

    @Test
    public void serializationEmitsTheTypeDiscriminator() throws Exception {
        String json = mapper.writeValueAsString(new PointVo(new double[]{12, 34}));
        assertTrue(json.contains("\"type\":\"Point\""), "expected type discriminator in: " + json);
        assertTrue(json.contains("\"coordinates\":[12.0,34.0]"), "expected coordinates in: " + json);
    }

    @Test
    public void deserializationViaGeoJsonVoResolvesPointSubclass() throws Exception {
        GeoJsonVo result = mapper.readValue(
                "{\"type\":\"Point\",\"coordinates\":[1.5,2.5]}", GeoJsonVo.class);
        assertTrue(result instanceof PointVo);
        assertEquals("Point", result.getType());
        assertArrayEquals(new double[]{1.5, 2.5}, ((PointVo) result).getCoordinates(), 0.0);
    }

    @Test
    public void deserializationViaGeometryVoResolvesPolygonSubclass() throws Exception {
        GeometryVo result = mapper.readValue(
                "{\"type\":\"Polygon\",\"coordinates\":[[[0.0,0.0],[1.0,0.0],[1.0,1.0],[0.0,0.0]]]}",
                GeometryVo.class);
        assertTrue(result instanceof PolygonVo);
        double[][][] coords = ((PolygonVo) result).getCoordinates();
        assertArrayEquals(new double[]{1, 1}, coords[0][2], 0.0);
    }

    @Test
    public void deserializationResolvesGeometryCollectionAndNestedGeometries() throws Exception {
        String json = "{\"type\":\"GeometryCollection\",\"geometries\":["
                + "{\"type\":\"Point\",\"coordinates\":[1.0,2.0]},"
                + "{\"type\":\"LineString\",\"coordinates\":[[3.0,4.0],[5.0,6.0]]}]}";
        GeometryVo result = mapper.readValue(json, GeometryVo.class);

        assertTrue(result instanceof GeometryCollectionVo);
        GeometryVo[] geometries = ((GeometryCollectionVo) result).getGeometries();
        assertEquals(2, geometries.length);
        assertTrue(geometries[0] instanceof PointVo);
        assertTrue(geometries[1] instanceof LineStringVo);

        // The reconstructed collection visits all nested coordinates.
        CoordCollector c = new CoordCollector();
        result.visitCoordinates(c);
        assertEquals(3, c.visited.size());
        assertTrue(contains(c.visited, new double[]{1, 2}));
        assertTrue(contains(c.visited, new double[]{5, 6}));
    }

    @Test
    public void roundTripSerializationPreservesTypeAndCoordinates() throws Exception {
        MultiPointVo original = new MultiPointVo(new double[][]{{1, 1}, {2, 2}, {3, 3}});
        String json = mapper.writeValueAsString(original);
        GeoJsonVo back = mapper.readValue(json, GeoJsonVo.class);

        assertTrue(back instanceof MultiPointVo);
        assertEquals("MultiPoint", back.getType());
        double[][] coords = ((MultiPointVo) back).getCoordinates();
        assertEquals(3, coords.length);
        assertArrayEquals(new double[]{1, 1}, coords[0], 0.0);
        assertArrayEquals(new double[]{3, 3}, coords[2], 0.0);
    }
}
