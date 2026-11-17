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
package org.niord.core.geojson;

import org.junit.jupiter.api.Test;
import org.niord.core.geojson.GeoJsonUtils.SerializedCoordinates;
import org.niord.core.geojson.GeoJsonUtils.SerializedFeature;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.geojson.FeatureVo;
import org.niord.model.geojson.GeoJsonVo;
import org.niord.model.geojson.LineStringVo;
import org.niord.model.geojson.PointVo;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure, static utility methods of {@link GeoJsonUtils}.
 *
 * <p>The GeoTools-backed methods ({@code createBufferGeometry}, {@code addAffectedRadius},
 * {@code addAtonAffectedRadius}, {@code createAffectedRadius}) are exercised elsewhere
 * ({@code org.niord.core.GeoJsonTest}) and are intentionally not the focus here.
 */
public class GeoJsonUtilsTest {

    private static final double DELTA = 1e-9;

    // ------------------------------------------------------------------
    // roundCoordinates
    // ------------------------------------------------------------------

    @Test
    public void roundCoordinatesRoundsPointInPlaceToGivenDecimals() {
        PointVo pt = new PointVo(new double[]{1.236, 2.984});

        GeoJsonUtils.roundCoordinates(pt, 2);

        // Mutates the very same coordinate array in place.
        assertArrayEquals(new double[]{1.24, 2.98}, pt.getCoordinates(), DELTA);
    }

    @Test
    public void roundCoordinatesWithZeroDecimalsRoundsToInteger() {
        PointVo pt = new PointVo(new double[]{1.236, 2.984});

        GeoJsonUtils.roundCoordinates(pt, 0);

        assertArrayEquals(new double[]{1.0, 3.0}, pt.getCoordinates(), DELTA);
    }

    @Test
    public void roundCoordinatesAppliesToEveryCoordinateOfALineString() {
        LineStringVo ls = new LineStringVo(new double[][]{
                {1.111, 2.222},
                {3.336, 4.984}
        });

        GeoJsonUtils.roundCoordinates(ls, 2);

        assertArrayEquals(new double[]{1.11, 2.22}, ls.getCoordinates()[0], DELTA);
        assertArrayEquals(new double[]{3.34, 4.98}, ls.getCoordinates()[1], DELTA);
    }

    // ------------------------------------------------------------------
    // swapCoordinates
    // ------------------------------------------------------------------

    @Test
    public void swapCoordinatesSwapsLonLatOfAPointInPlace() {
        PointVo pt = new PointVo(new double[]{10.0, 56.0});

        GeoJsonUtils.swapCoordinates(pt);

        assertArrayEquals(new double[]{56.0, 10.0}, pt.getCoordinates(), DELTA);
    }

    @Test
    public void swapCoordinatesSwapsEveryCoordinateOfALineString() {
        LineStringVo ls = new LineStringVo(new double[][]{
                {10.0, 56.0},
                {11.0, 57.0}
        });

        GeoJsonUtils.swapCoordinates(ls);

        assertArrayEquals(new double[]{56.0, 10.0}, ls.getCoordinates()[0], DELTA);
        assertArrayEquals(new double[]{57.0, 11.0}, ls.getCoordinates()[1], DELTA);
    }

    @Test
    public void swapCoordinatesTwiceRestoresOriginalOrder() {
        PointVo pt = new PointVo(new double[]{10.0, 56.0});

        GeoJsonUtils.swapCoordinates(pt);
        GeoJsonUtils.swapCoordinates(pt);

        assertArrayEquals(new double[]{10.0, 56.0}, pt.getCoordinates(), DELTA);
    }

    // ------------------------------------------------------------------
    // computeCoordinate
    // ------------------------------------------------------------------

    @Test
    public void computeCoordinateReturnsNthCoordinate() {
        LineStringVo ls = new LineStringVo(new double[][]{
                {0, 0}, {1, 1}, {2, 2}
        });

        assertArrayEquals(new double[]{0, 0}, GeoJsonUtils.computeCoordinate(ls, 0), DELTA);
        assertArrayEquals(new double[]{1, 1}, GeoJsonUtils.computeCoordinate(ls, 1), DELTA);
        assertArrayEquals(new double[]{2, 2}, GeoJsonUtils.computeCoordinate(ls, 2), DELTA);
    }

    @Test
    public void computeCoordinateReturnsNullWhenIndexOutOfRange() {
        LineStringVo ls = new LineStringVo(new double[][]{
                {0, 0}, {1, 1}, {2, 2}
        });

        assertNull(GeoJsonUtils.computeCoordinate(ls, 5));
    }

    @Test
    public void computeCoordinateReturnsTheLiveCoordinateArray() {
        // The returned array is the same instance held by the geometry, not a copy.
        double[] first = new double[]{4.0, 8.0};
        LineStringVo ls = new LineStringVo(new double[][]{ first });

        assertSame(first, GeoJsonUtils.computeCoordinate(ls, 0));
    }

    // ------------------------------------------------------------------
    // computeCenter
    // ------------------------------------------------------------------

    @Test
    public void computeCenterAveragesPerGeometryCenters() {
        GeoJsonVo[] geometries = {
                new PointVo(new double[]{0, 0}),
                new PointVo(new double[]{10, 10})
        };

        double[] center = GeoJsonUtils.computeCenter(geometries);

        assertArrayEquals(new double[]{5.0, 5.0}, center, DELTA);
    }

    @Test
    public void computeCenterOfSingleGeometryIsItsOwnCenter() {
        // The center of a LineString is the centre of its bounding box.
        LineStringVo ls = new LineStringVo(new double[][]{
                {0, 0}, {10, 20}
        });

        double[] center = GeoJsonUtils.computeCenter(new GeoJsonVo[]{ ls });

        assertArrayEquals(new double[]{5.0, 10.0}, center, DELTA);
    }

    @Test
    public void computeCenterReturnsNullForNullArray() {
        assertNull(GeoJsonUtils.computeCenter(null));
    }

    @Test
    public void computeCenterReturnsNullForEmptyArray() {
        assertNull(GeoJsonUtils.computeCenter(new GeoJsonVo[0]));
    }

    // ------------------------------------------------------------------
    // computeBBox
    // ------------------------------------------------------------------

    @Test
    public void computeBBoxSpansAllGeometries() {
        GeoJsonVo[] geometries = {
                new PointVo(new double[]{0, 0}),
                new PointVo(new double[]{10, 20})
        };

        double[] bbox = GeoJsonUtils.computeBBox(geometries);

        // [minX, minY, maxX, maxY]
        assertArrayEquals(new double[]{0, 0, 10, 20}, bbox, DELTA);
    }

    @Test
    public void computeBBoxHandlesNegativeCoordinates() {
        GeoJsonVo[] geometries = {
                new PointVo(new double[]{-5, -7}),
                new PointVo(new double[]{3, 4})
        };

        double[] bbox = GeoJsonUtils.computeBBox(geometries);

        assertArrayEquals(new double[]{-5, -7, 3, 4}, bbox, DELTA);
    }

    @Test
    public void computeBBoxReturnsNullForNullArray() {
        assertNull(GeoJsonUtils.computeBBox(null));
    }

    @Test
    public void computeBBoxReturnsNullForEmptyArray() {
        assertNull(GeoJsonUtils.computeBBox(new GeoJsonVo[0]));
    }

    // ------------------------------------------------------------------
    // setLanguage
    // ------------------------------------------------------------------

    @Test
    public void setLanguageRemovesOtherNamesWhenRequested() {
        FeatureCollectionVo fc = featureCollectionWithNames();

        GeoJsonUtils.setLanguage(fc, "da", true);

        FeatureVo f = fc.getFeatures()[0];
        assertEquals("da", f.getProperties().get("language"));
        assertEquals("X", f.getProperties().get("name:da"));
        assertFalse(f.getProperties().containsKey("name:en"), "The non-matching name should be removed");
    }

    @Test
    public void setLanguageKeepsOtherNamesWhenRemoveOtherNamesIsFalse() {
        FeatureCollectionVo fc = featureCollectionWithNames();

        GeoJsonUtils.setLanguage(fc, "da", false);

        FeatureVo f = fc.getFeatures()[0];
        assertEquals("da", f.getProperties().get("language"));
        assertEquals("X", f.getProperties().get("name:da"));
        assertEquals("Y", f.getProperties().get("name:en"));
    }

    @Test
    public void setLanguageReturnsTheSameInstance() {
        FeatureCollectionVo fc = featureCollectionWithNames();

        GeoJsonVo result = GeoJsonUtils.setLanguage(fc, "da", true);

        assertSame(fc, result);
    }

    @Test
    public void setLanguageDoesNotRemoveNonNameProperties() {
        FeatureVo f = new FeatureVo();
        f.getProperties().put("name:en", "Y");
        f.getProperties().put("restriction", "affected");
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{ f });

        GeoJsonUtils.setLanguage(fc, "da", true);

        // "restriction" is not a FeatureName, so it must survive removeOtherNames.
        assertEquals("affected", f.getProperties().get("restriction"));
        assertFalse(f.getProperties().containsKey("name:en"));
        assertEquals("da", f.getProperties().get("language"));
    }

    @Test
    public void setLanguageReturnsNullForNullInput() {
        assertNull(GeoJsonUtils.setLanguage((GeoJsonVo) null, "da", true));
    }

    @Test
    public void setLanguageWithBlankLanguageReturnsInputUnchanged() {
        FeatureCollectionVo fc = featureCollectionWithNames();

        GeoJsonVo result = GeoJsonUtils.setLanguage(fc, "  ", true);

        assertSame(fc, result);
        FeatureVo f = fc.getFeatures()[0];
        // No language flag was set and no names were stripped.
        assertFalse(f.getProperties().containsKey("language"));
        assertEquals("X", f.getProperties().get("name:da"));
        assertEquals("Y", f.getProperties().get("name:en"));
    }

    // ------------------------------------------------------------------
    // serializeFeatureCollection
    // ------------------------------------------------------------------

    @Test
    public void serializeFeatureCollectionFlattensNamesAndCoordinates() {
        FeatureVo feature = new FeatureVo();
        feature.setGeometry(new LineStringVo(new double[][]{
                {10, 56}, {11, 57}
        }));
        feature.getProperties().put("name:en", "feat");
        feature.getProperties().put("name:0:en", "a");
        feature.getProperties().put("name:1:en", "b");

        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{ feature });

        List<SerializedFeature> result = GeoJsonUtils.serializeFeatureCollection(fc, "en");

        assertEquals(1, result.size());
        SerializedFeature sf = result.get(0);
        assertEquals("feat", sf.getName());
        assertEquals("LineString", sf.getGeometryType());

        List<SerializedCoordinates> coords = sf.getCoordinates();
        assertEquals(2, coords.size());

        assertEquals("a", coords.get(0).getName());
        assertArrayEquals(new double[]{10, 56}, coords.get(0).getCoordinates(), DELTA);

        assertEquals("b", coords.get(1).getName());
        assertArrayEquals(new double[]{11, 57}, coords.get(1).getCoordinates(), DELTA);
    }

    @Test
    public void serializeFeatureCollectionAssignsSequentialOneBasedIndexes() {
        FeatureVo feature = new FeatureVo();
        feature.setGeometry(new LineStringVo(new double[][]{
                {10, 56}, {11, 57}
        }));
        feature.getProperties().put("name:en", "feat");

        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{ feature });

        List<SerializedFeature> result = GeoJsonUtils.serializeFeatureCollection(fc, "en");

        SerializedFeature sf = result.get(0);
        // startIndex defaults to 1 and coordinate indexes run 1, 2, ...
        assertEquals(1, sf.getStartIndex());
        assertEquals(1, sf.getCoordinates().get(0).getIndex());
        assertEquals(2, sf.getCoordinates().get(1).getIndex());
    }

    @Test
    public void serializeFeatureCollectionSkipsDerivedParentFeatures() {
        FeatureVo derived = new FeatureVo();
        derived.setGeometry(new PointVo(new double[]{1, 2}));
        derived.getProperties().put("name:en", "derived");
        derived.getProperties().put("parentFeatureIds", "abc");

        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{ derived });

        List<SerializedFeature> result = GeoJsonUtils.serializeFeatureCollection(fc, "en");

        assertTrue(result.isEmpty(), "Features with parentFeatureIds must be skipped");
    }

    @Test
    public void serializeFeatureCollectionSkipsAffectedRestrictionFeatures() {
        FeatureVo affected = new FeatureVo();
        affected.setGeometry(new PointVo(new double[]{1, 2}));
        affected.getProperties().put("name:en", "affected-feat");
        affected.getProperties().put("restriction", "affected");

        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{ affected });

        List<SerializedFeature> result = GeoJsonUtils.serializeFeatureCollection(fc, "en");

        assertTrue(result.isEmpty(), "Features with restriction=affected must be skipped");
    }

    @Test
    public void serializeFeatureCollectionContinuesIndexingAcrossFeatures() {
        FeatureVo f1 = new FeatureVo();
        f1.setGeometry(new LineStringVo(new double[][]{ {0, 0}, {1, 1} }));
        f1.getProperties().put("name:en", "first");

        FeatureVo f2 = new FeatureVo();
        f2.setGeometry(new LineStringVo(new double[][]{ {2, 2}, {3, 3} }));
        f2.getProperties().put("name:en", "second");

        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{ f1, f2 });

        List<SerializedFeature> result = GeoJsonUtils.serializeFeatureCollection(fc, "en");

        assertEquals(2, result.size());
        // First feature occupies indexes 1, 2; second feature continues at 3, 4.
        assertEquals(1, result.get(0).getStartIndex());
        assertEquals(1, result.get(0).getCoordinates().get(0).getIndex());
        assertEquals(2, result.get(0).getCoordinates().get(1).getIndex());

        assertEquals(3, result.get(1).getStartIndex());
        assertEquals(3, result.get(1).getCoordinates().get(0).getIndex());
        assertEquals(4, result.get(1).getCoordinates().get(1).getIndex());
    }

    @Test
    public void serializeFeatureCollectionHonoursStartCoordIndexProperty() {
        FeatureVo feature = new FeatureVo();
        feature.setGeometry(new LineStringVo(new double[][]{ {0, 0}, {1, 1} }));
        feature.getProperties().put("name:en", "feat");
        feature.getProperties().put("startCoordIndex", 10);

        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{ feature });

        List<SerializedFeature> result = GeoJsonUtils.serializeFeatureCollection(fc, "en");

        SerializedFeature sf = result.get(0);
        assertEquals(10, sf.getStartIndex());
        assertEquals(10, sf.getCoordinates().get(0).getIndex());
        assertEquals(11, sf.getCoordinates().get(1).getIndex());
    }

    @Test
    public void serializeFeatureCollectionFallsBackToFeatureLanguageProperty() {
        // When no language argument is supplied, the feature's own "language" property is used.
        FeatureVo feature = new FeatureVo();
        feature.setGeometry(new PointVo(new double[]{5, 5}));
        feature.getProperties().put("language", "da");
        feature.getProperties().put("name:da", "danskNavn");
        feature.getProperties().put("name:en", "englishName");

        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{ feature });

        List<SerializedFeature> result = GeoJsonUtils.serializeFeatureCollection(fc, null);

        assertEquals(1, result.size());
        assertEquals("danskNavn", result.get(0).getName());
    }

    @Test
    public void serializeFeatureCollectionDefaultsToEnglishWhenNoLanguageAvailable() {
        FeatureVo feature = new FeatureVo();
        feature.setGeometry(new PointVo(new double[]{5, 5}));
        feature.getProperties().put("name:en", "englishName");
        feature.getProperties().put("name:da", "danskNavn");

        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{ feature });

        List<SerializedFeature> result = GeoJsonUtils.serializeFeatureCollection(fc, null);

        assertEquals("englishName", result.get(0).getName());
    }

    @Test
    public void serializeFeatureCollectionSkipsLastRingCoordinateOfPolygon() {
        // Polygons close their exterior ring (last coord == first). serializeFeatureCollection
        // skips the duplicated closing coordinate via IncludeCoord.ALL_BUT_LAST.
        org.niord.model.geojson.PolygonVo polygon = new org.niord.model.geojson.PolygonVo(new double[][][]{
                { {0, 0}, {0, 4}, {4, 4}, {0, 0} } // 4 coords, last duplicates first
        });
        FeatureVo feature = new FeatureVo();
        feature.setGeometry(polygon);
        feature.getProperties().put("name:en", "poly");

        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{ feature });

        List<SerializedFeature> result = GeoJsonUtils.serializeFeatureCollection(fc, "en");

        SerializedFeature sf = result.get(0);
        // 4 ring coordinates minus the closing duplicate => 3 serialized coordinates.
        assertEquals(3, sf.getCoordinates().size());
        assertArrayEquals(new double[]{0, 0}, sf.getCoordinates().get(0).getCoordinates(), DELTA);
        assertArrayEquals(new double[]{0, 4}, sf.getCoordinates().get(1).getCoordinates(), DELTA);
        assertArrayEquals(new double[]{4, 4}, sf.getCoordinates().get(2).getCoordinates(), DELTA);
    }

    @Test
    public void serializeFeatureCollectionReturnsEmptyListForNullCollection() {
        List<SerializedFeature> result = GeoJsonUtils.serializeFeatureCollection(null, "en");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void serializeFeatureCollectionDropsUnnamedCoordinatelessFeature() {
        // A feature with no geometry and no name produces an empty SerializedFeature
        // (no name, no coordinates) and is dropped from the result.
        FeatureVo feature = new FeatureVo();
        // no geometry, no name properties
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{ feature });

        List<SerializedFeature> result = GeoJsonUtils.serializeFeatureCollection(fc, "en");

        assertTrue(result.isEmpty());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static FeatureCollectionVo featureCollectionWithNames() {
        FeatureVo feature = new FeatureVo();
        feature.getProperties().put("name:da", "X");
        feature.getProperties().put("name:en", "Y");
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[]{ feature });
        return fc;
    }
}
