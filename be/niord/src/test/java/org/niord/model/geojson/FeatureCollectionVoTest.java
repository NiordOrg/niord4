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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FeatureCollectionVo}.
 *
 * Focus areas:
 *  - the "type" discriminator set by the constructor
 *  - all branches of {@link FeatureCollectionVo#toGeometry()}
 *  - coordinate aggregation across the contained features via visitCoordinates
 *  - id / features round-tripping
 *  - bbox aggregation inherited from GeoJsonVo across multiple features
 *
 * These tests deliberately avoid Quarkus / Jackson and only exercise pure value-object
 * behaviour so they survive a future migration away from Quarkus.
 */
public class FeatureCollectionVoTest {

    /**
     * Collects every coordinate pair handed to a {@code visitCoordinates} handler so the
     * test can assert both the number of visits and the exact pairs that were visited.
     */
    private static List<double[]> collect(GeoJsonVo vo) {
        List<double[]> collected = new ArrayList<>();
        Consumer<double[]> handler = xy -> collected.add(xy);
        vo.visitCoordinates(handler);
        return collected;
    }

    private static FeatureVo featureWithGeometry(GeometryVo geometry) {
        FeatureVo feature = new FeatureVo();
        feature.setGeometry(geometry);
        return feature;
    }

    // ------------------------------------------------------------------
    // type discriminator
    // ------------------------------------------------------------------

    @Test
    public void constructorSetsFeatureCollectionType() {
        FeatureCollectionVo fc = new FeatureCollectionVo();
        assertEquals("FeatureCollection", fc.getType());
    }

    // ------------------------------------------------------------------
    // toGeometry() - all branches
    // ------------------------------------------------------------------

    @Test
    public void toGeometryReturnsNullWhenFeaturesNull() {
        FeatureCollectionVo fc = new FeatureCollectionVo();
        // features left null
        assertNull(fc.getFeatures());
        assertNull(fc.toGeometry());
    }

    @Test
    public void toGeometryReturnsNullWhenFeaturesEmpty() {
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[0]);
        assertNull(fc.toGeometry());
    }

    @Test
    public void toGeometryReturnsTheSingleFeatureGeometryInstance() {
        PointVo point = new PointVo(new double[]{ 7.0, 8.0 });
        FeatureVo feature = featureWithGeometry(point);
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{ feature });

        // Single-feature branch returns the feature's geometry as-is (same instance).
        assertSame(point, fc.toGeometry());
    }

    @Test
    public void toGeometryReturnsNullWhenSingleFeatureGeometryIsNull() {
        FeatureVo feature = featureWithGeometry(null);
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{ feature });

        // Single-feature branch returns the geometry as-is, even when null.
        assertNull(fc.toGeometry());
    }

    @Test
    public void toGeometryWrapsMultipleFeaturesInGeometryCollection() {
        PointVo p1 = new PointVo(new double[]{ 1.0, 2.0 });
        LineStringVo l2 = new LineStringVo(new double[][]{ { 3.0, 4.0 }, { 5.0, 6.0 } });
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{ featureWithGeometry(p1), featureWithGeometry(l2) });

        GeometryVo geometry = fc.toGeometry();
        assertTrue(geometry instanceof GeometryCollectionVo);
        assertEquals("GeometryCollection", geometry.getType());

        GeometryVo[] geometries = ((GeometryCollectionVo) geometry).getGeometries();
        assertEquals(2, geometries.length);
        // Order is preserved and the original geometry instances are reused.
        assertSame(p1, geometries[0]);
        assertSame(l2, geometries[1]);
    }

    @Test
    public void toGeometryFiltersOutNullGeometriesWhenMultipleFeatures() {
        PointVo p1 = new PointVo(new double[]{ 1.0, 2.0 });
        FeatureCollectionVo fc = new FeatureCollectionVo();
        // Two features, but one has no geometry -> it must be filtered out.
        fc.setFeatures(new FeatureVo[]{ featureWithGeometry(p1), featureWithGeometry(null) });

        GeometryVo geometry = fc.toGeometry();
        assertTrue(geometry instanceof GeometryCollectionVo);

        GeometryVo[] geometries = ((GeometryCollectionVo) geometry).getGeometries();
        assertEquals(1, geometries.length);
        assertSame(p1, geometries[0]);
    }

    @Test
    public void toGeometryYieldsEmptyGeometryCollectionWhenAllGeometriesNull() {
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{ featureWithGeometry(null), featureWithGeometry(null) });

        GeometryVo geometry = fc.toGeometry();
        assertTrue(geometry instanceof GeometryCollectionVo);
        // Both geometries filtered out -> empty (but non-null) geometry array.
        assertEquals(0, ((GeometryCollectionVo) geometry).getGeometries().length);
    }

    // ------------------------------------------------------------------
    // visitCoordinates - aggregation across features
    // ------------------------------------------------------------------

    @Test
    public void visitCoordinatesAggregatesAcrossAllFeatures() {
        PointVo point = new PointVo(new double[]{ 1.0, 2.0 });
        LineStringVo line = new LineStringVo(new double[][]{ { 3.0, 4.0 }, { 5.0, 6.0 } });
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{ featureWithGeometry(point), featureWithGeometry(line) });

        List<double[]> coords = collect(fc);

        // 1 point + 2 line vertices = 3 visited coordinate pairs.
        assertEquals(3, coords.size());
        assertArrayEquals(new double[]{ 1.0, 2.0 }, coords.get(0), 0.0);
        assertArrayEquals(new double[]{ 3.0, 4.0 }, coords.get(1), 0.0);
        assertArrayEquals(new double[]{ 5.0, 6.0 }, coords.get(2), 0.0);
    }

    @Test
    public void visitCoordinatesSkipsFeaturesWithNullGeometry() {
        PointVo point = new PointVo(new double[]{ 9.0, 9.0 });
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{ featureWithGeometry(null), featureWithGeometry(point) });

        List<double[]> coords = collect(fc);

        assertEquals(1, coords.size());
        assertArrayEquals(new double[]{ 9.0, 9.0 }, coords.get(0), 0.0);
    }

    @Test
    public void visitCoordinatesSkipsNullFeatureElement() {
        // Symmetric with the GeometryCollectionVo G2 fix: a null FEATURE element in the array is
        // skipped (its non-null siblings are still visited) instead of throwing NullPointerException.
        PointVo point = new PointVo(new double[]{ 4.0, 5.0 });
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{ null, featureWithGeometry(point) });

        List<double[]> coords = collect(fc);

        assertEquals(1, coords.size());
        assertArrayEquals(new double[]{ 4.0, 5.0 }, coords.get(0), 0.0);
    }

    @Test
    public void visitCoordinatesIsNoOpWhenFeaturesNull() {
        FeatureCollectionVo fc = new FeatureCollectionVo();
        // features left null -> handler must never be invoked.
        List<double[]> coords = collect(fc);
        assertEquals(0, coords.size());
    }

    @Test
    public void visitCoordinatesIsNoOpWhenFeaturesEmpty() {
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[0]);
        List<double[]> coords = collect(fc);
        assertEquals(0, coords.size());
    }

    // ------------------------------------------------------------------
    // id and features accessors
    // ------------------------------------------------------------------

    @Test
    public void idRoundTrips() {
        FeatureCollectionVo fc = new FeatureCollectionVo();
        assertNull(fc.getId());

        Object id = "fc-42";
        fc.setId(id);
        assertSame(id, fc.getId());
    }

    @Test
    public void featuresRoundTrip() {
        FeatureVo f1 = featureWithGeometry(new PointVo(new double[]{ 0.0, 0.0 }));
        FeatureVo f2 = featureWithGeometry(new PointVo(new double[]{ 1.0, 1.0 }));
        FeatureVo[] features = { f1, f2 };

        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(features);

        FeatureVo[] returned = fc.getFeatures();
        assertSame(features, returned);
        assertEquals(2, returned.length);
        assertSame(f1, returned[0]);
        assertSame(f2, returned[1]);
    }

    // ------------------------------------------------------------------
    // computeBBox / computeCenter aggregation (inherited from GeoJsonVo)
    // ------------------------------------------------------------------

    @Test
    public void computeBBoxCoversAllFeatures() {
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{
                featureWithGeometry(new PointVo(new double[]{ 0.0, 0.0 })),
                featureWithGeometry(new PointVo(new double[]{ 10.0, 10.0 }))
        });

        double[] bbox = fc.computeBBox();
        assertArrayEquals(new double[]{ 0.0, 0.0, 10.0, 10.0 }, bbox, 0.0);
    }

    @Test
    public void computeBBoxSpansAcrossDifferentGeometryTypes() {
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{
                featureWithGeometry(new PointVo(new double[]{ -5.0, 3.0 })),
                featureWithGeometry(new LineStringVo(new double[][]{ { 2.0, -7.0 }, { 8.0, 4.0 } }))
        });

        double[] bbox = fc.computeBBox();
        // minX=-5, minY=-7, maxX=8, maxY=4
        assertArrayEquals(new double[]{ -5.0, -7.0, 8.0, 4.0 }, bbox, 0.0);
    }

    @Test
    public void computeCenterIsMidpointOfBBox() {
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{
                featureWithGeometry(new PointVo(new double[]{ 0.0, 0.0 })),
                featureWithGeometry(new PointVo(new double[]{ 10.0, 20.0 }))
        });

        double[] center = fc.computeCenter();
        assertArrayEquals(new double[]{ 5.0, 10.0 }, center, 0.0);
    }

    /**
     * Documents the missing empty-geometry guard in GeoJsonVo#computeBBox()/computeCenter()
     * (the source even carries a "TODO: Naive implementation" comment). With no visitable
     * coordinates the bbox stays at its int sentinels and the center collapses to {-0.5,-0.5}.
     * This pins the CURRENT behaviour; it is reported as a suspected bug.
     */
    @Test
    public void computeBBoxReturnsSentinelForEmptyCollection_documentsBug() {
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[0]);

        double[] bbox = fc.computeBBox();
        assertArrayEquals(
                new double[]{ Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE },
                bbox, 0.0);

        double[] center = fc.computeCenter();
        assertArrayEquals(new double[]{ -0.5, -0.5 }, center, 0.0);
    }
}
