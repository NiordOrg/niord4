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
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.geojson.FeatureVo;
import org.niord.model.geojson.GeometryCollectionVo;
import org.niord.model.geojson.GeometryVo;
import org.niord.model.geojson.LineStringVo;
import org.niord.model.geojson.MultiPointVo;
import org.niord.model.geojson.PointVo;
import org.niord.model.geojson.PolygonVo;

import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for {@link PlainTextConverter}, the text &lt;-&gt; GeoJSON converter used by the
 * NtM editing UI. The converter is plain Java with no framework dependencies, so these tests run as
 * Tier-1 JUnit 4 unit tests and verify concrete parsed geometry types, exact coordinate values
 * (degrees + minutes/60, comma decimal), localized name properties, error handling, the GeoJSON
 * -&gt; text formatting, and UTM parsing.
 * <p>
 * The coordinate delta of 1e-4 corresponds to ~0.006 of an arc-minute, far finer than the 3-decimal
 * minute precision the format produces, so it pins the parsing maths without being brittle.
 */
public class PlainTextConverterTest {

    private static final double COORD_DELTA = 1e-4;

    /** Convenience: a converter configured for English + Danish, a two-language deployment. */
    private PlainTextConverter enDa() {
        return PlainTextConverter.newInstance(new String[]{"en", "da"});
    }

    /** Convenience: extract the single feature's geometry from a feature collection. */
    private GeometryVo singleGeometry(FeatureCollectionVo fc) {
        assertNotNull(fc, "feature collection should not be null");
        assertNotNull(fc.getFeatures(), "features should not be null");
        assertEquals(1, fc.getFeatures().length, "expected exactly one feature");
        return fc.getFeatures()[0].getGeometry();
    }

    /***********************************************/
    /** newInstance language defaulting           **/
    /***********************************************/

    @Test
    public void newInstanceNullDefaultsToEnglish() throws Exception {
        // A null language array must default to "en" - verified through behaviour: a name with no
        // language tag is stored under "name:0:en".
        PlainTextConverter converter = PlainTextConverter.newInstance(null);

        FeatureCollectionVo fc = converter.fromPlainText("54° 45,7' N 10° 29,1' E, Buoy");
        FeatureVo feature = fc.getFeatures()[0];

        assertEquals("Buoy", feature.getProperties().get("name:0:en"));
        // Danish was not configured, so no Danish name should be stored.
        assertFalse(feature.getProperties().containsKey("name:0:da"));
    }

    @Test
    public void newInstanceEmptyArrayDefaultsToEnglish() throws Exception {
        PlainTextConverter converter = PlainTextConverter.newInstance(new String[0]);

        FeatureCollectionVo fc = converter.fromPlainText("54° 45,7' N 10° 29,1' E, Buoy");
        FeatureVo feature = fc.getFeatures()[0];

        assertEquals("Buoy", feature.getProperties().get("name:0:en"));
        assertFalse(feature.getProperties().containsKey("name:0:da"));
    }

    /***********************************************/
    /** fromPlainText - type inference            **/
    /***********************************************/

    @Test
    public void singlePositionInfersPointWithExactCoordinates() throws Exception {
        FeatureCollectionVo fc = enDa().fromPlainText("54° 41,000'N - 010° 35,000'E");

        GeometryVo geometry = singleGeometry(fc);
        assertTrue(geometry instanceof PointVo, "single position with no type header should be a Point");

        PointVo point = (PointVo) geometry;
        // GeoJSON stores coordinates as [lon, lat]. 41,000' = 41/60 = 0.68333..., 35,000' = 0.58333...
        double[] coords = point.getCoordinates();
        assertEquals(10.0 + 35.0 / 60.0, coords[0], COORD_DELTA, "longitude");
        assertEquals(54.0 + 41.0 / 60.0, coords[1], COORD_DELTA, "latitude");
    }

    @Test
    public void twoPositionsWithoutHeaderInferMultiPoint() throws Exception {
        FeatureCollectionVo fc = enDa().fromPlainText(
                "54° 41,000'N - 010° 35,000'E\n" +
                "54° 35,566'N - 010° 35,010'E");

        GeometryVo geometry = singleGeometry(fc);
        assertTrue(geometry instanceof MultiPointVo, "two positions with no type header should be a MultiPoint");

        MultiPointVo multiPoint = (MultiPointVo) geometry;
        assertEquals(2, multiPoint.getCoordinates().length, "multipoint should have two coordinates");
        assertArrayEquals(
                new double[]{10.0 + 35.0 / 60.0, 54.0 + 41.0 / 60.0},
                multiPoint.getCoordinates()[0], COORD_DELTA);
        assertArrayEquals(
                new double[]{10.0 + 35.010 / 60.0, 54.0 + 35.566 / 60.0},
                multiPoint.getCoordinates()[1], COORD_DELTA);
    }

    @Test
    public void explicitLineStringHeaderProducesLineString() throws Exception {
        FeatureCollectionVo fc = enDa().fromPlainText(
                "LineString\n" +
                "54° 35,566'N - 010° 35,010'E\n" +
                "54° 38,397'N - 010° 25,125'E");

        GeometryVo geometry = singleGeometry(fc);
        assertTrue(geometry instanceof LineStringVo, "explicit LineString header should yield a LineString");

        LineStringVo lineString = (LineStringVo) geometry;
        assertEquals(2, lineString.getCoordinates().length, "line string should retain both coordinates");
        assertArrayEquals(
                new double[]{10.0 + 35.010 / 60.0, 54.0 + 35.566 / 60.0},
                lineString.getCoordinates()[0], COORD_DELTA);
        assertArrayEquals(
                new double[]{10.0 + 25.125 / 60.0, 54.0 + 38.397 / 60.0},
                lineString.getCoordinates()[1], COORD_DELTA);
    }

    @Test
    public void explicitPolygonHeaderClosesRing() throws Exception {
        FeatureCollectionVo fc = enDa().fromPlainText(
                "Polygon\n" +
                "54° 41,000'N - 010° 35,000'E\n" +
                "54° 35,566'N - 010° 35,010'E\n" +
                "54° 38,397'N - 010° 25,125'E");

        GeometryVo geometry = singleGeometry(fc);
        assertTrue(geometry instanceof PolygonVo, "explicit Polygon header should yield a Polygon");

        PolygonVo polygon = (PolygonVo) geometry;
        assertEquals(1, polygon.getCoordinates().length, "polygon should have exactly one ring");
        double[][] ring = polygon.getCoordinates()[0];
        // GeoJSON linear ring: the 3 supplied positions plus the first repeated as the closing point.
        assertEquals(4, ring.length, "ring should be closed -> 3 supplied + 1 repeated = 4 coords");
        assertArrayEquals(ring[0], ring[3], 0.0, "first and last ring coordinate must be identical");
        assertArrayEquals(
                new double[]{10.0 + 35.0 / 60.0, 54.0 + 41.0 / 60.0},
                ring[0], COORD_DELTA);
    }

    /***********************************************/
    /** fromPlainText - localized names           **/
    /***********************************************/

    @Test
    public void typeHeaderWithLanguageTagsStoresLocalizedNames() throws Exception {
        FeatureCollectionVo fc = enDa().fromPlainText(
                "Point, da: Ubåd, en: Submarine\n" +
                "54° 41,000'N - 010° 35,000'E");

        FeatureVo feature = fc.getFeatures()[0];
        // Header-level names use the "name:" prefix and are split per language indicator. The Danish
        // value must be trimmed (no leading space, no trailing comma).
        assertEquals("Ubåd", feature.getProperties().get("name:da"));
        assertEquals("Submarine", feature.getProperties().get("name:en"));
    }

    @Test
    public void untaggedPositionDescriptionStoredForAllLanguages() throws Exception {
        FeatureCollectionVo fc = enDa().fromPlainText("54° 45,7' N 10° 29,1' E, Ærø S.");

        FeatureVo feature = fc.getFeatures()[0];
        // With no language indicators, the description is registered for EVERY configured language,
        // each under the per-coordinate "name:<index>:" prefix.
        assertEquals("Ærø S.", feature.getProperties().get("name:0:en"));
        assertEquals("Ærø S.", feature.getProperties().get("name:0:da"));
    }

    @Test
    public void aWordEndingInALanguageCodeAndAColonIsNoLanguageMarker() throws Exception {
        // CG9: "Havnen:" ends in "en:" and "Kirken:" too; neither is a marker, so the untagged name is kept whole
        // for every language, on a position and on the type header.
        FeatureCollectionVo fc = enDa().fromPlainText(
                "Polygon, Kirken: syd\n" +
                "54° 41,000'N - 010° 35,000'E, Havnen: nordmole\n" +
                "54° 35,566'N - 010° 35,010'E, Rønda: BROEN: øst\n" +
                "54° 38,397'N - 010° 25,125'E");

        FeatureVo feature = fc.getFeatures()[0];
        assertEquals("Kirken: syd", feature.getProperties().get("name:en"));
        assertEquals("Kirken: syd", feature.getProperties().get("name:da"));
        assertEquals("Havnen: nordmole", feature.getProperties().get("name:0:en"));
        assertEquals("Havnen: nordmole", feature.getProperties().get("name:0:da"));
        assertEquals("Rønda: BROEN: øst", feature.getProperties().get("name:1:en"));
        assertEquals("Rønda: BROEN: øst", feature.getProperties().get("name:1:da"));
    }

    @Test
    public void aTaggedNameWithAWordEndingInALanguageCodeKeepsTheWord() throws Exception {
        FeatureCollectionVo fc = enDa().fromPlainText(
                "54° 41,000'N - 010° 35,000'E, da: Havnen: nordmole, en: Golden: wreck");

        FeatureVo feature = fc.getFeatures()[0];
        assertEquals("Havnen: nordmole", feature.getProperties().get("name:0:da"));
        assertEquals("Golden: wreck", feature.getProperties().get("name:0:en"));
    }

    @Test
    public void languageMarkersAfterACommaWithoutSpacesSplitTheName() throws Exception {
        FeatureCollectionVo fc = enDa().fromPlainText("54° 45,7' N 10° 29,1' E,da:Ærø S.,en:Aeroe S.");

        FeatureVo feature = fc.getFeatures()[0];
        assertEquals("Ærø S.", feature.getProperties().get("name:0:da"));
        assertEquals("Aeroe S.", feature.getProperties().get("name:0:en"));
    }

    @Test
    public void aLanguageMarkerAfterASpaceStillSplitsTheName() throws Exception {
        // The form the original parser's own test types ("da: Ærø S. en: Aeroe S."): a marker that is a word of its
        // own, after a space, still starts the next language's name.
        FeatureCollectionVo fc = enDa().fromPlainText("54° 45,7' N 10° 29,1' E, da: Ærø S. en: Aeroe S.");

        FeatureVo feature = fc.getFeatures()[0];
        assertEquals("Ærø S.", feature.getProperties().get("name:0:da"));
        assertEquals("Aeroe S.", feature.getProperties().get("name:0:en"));
    }

    /***********************************************/
    /** fromPlainText - error / empty handling    **/
    /***********************************************/

    @Test
    public void nullInputReturnsNull() throws Exception {
        assertNull(enDa().fromPlainText(null));
    }

    @Test
    public void blankInputReturnsNull() throws Exception {
        assertNull(enDa().fromPlainText("   "));
    }

    @Test
    public void explicitPointHeaderWithTwoPositionsThrows() {
        try {
            enDa().fromPlainText(
                    "Point\n" +
                    "54° 41,000'N - 010° 35,000'E\n" +
                    "54° 35,566'N - 010° 35,010'E");
            fail("a Point with two coordinates must be rejected");
        } catch (Exception e) {
            assertEquals("Point must have one coordinate", e.getMessage());
        }
    }

    @Test
    public void explicitPolygonWithTwoPositionsThrows() {
        try {
            enDa().fromPlainText(
                    "Polygon\n" +
                    "54° 41,000'N - 010° 35,000'E\n" +
                    "54° 35,566'N - 010° 35,010'E");
            fail("a Polygon with fewer than three coordinates must be rejected");
        } catch (Exception e) {
            assertEquals("Polygon must have at least three coordinates", e.getMessage());
        }
    }

    @Test
    public void unparseablePositionLineThrows() {
        try {
            enDa().fromPlainText("this is not a coordinate");
            fail("an unparseable line must raise an exception");
        } catch (Exception e) {
            // The converter rejects the line either at the line-format stage ("Invalid format:")
            // or, when it partially matches the position regex, at degree parsing
            // ("Invalid degree format:"). Both are valid parse-error outcomes.
            assertTrue(e.getMessage().startsWith("Invalid format:")
                            || e.getMessage().startsWith("Invalid degree format:"),
                    "message should flag a parse error, was: " + e.getMessage());
        }
    }

    /***********************************************/
    /** toPlainText                               **/
    /***********************************************/

    @Test
    public void toPlainTextNullReturnsNull() throws Exception {
        assertNull(enDa().toPlainText(null));
    }

    @Test
    public void toPlainTextPointStartsWithTypeAndFormatsPosition() throws Exception {
        // {lon, lat} = {10.5, 54.5}; LATLON_DEC_3 formats 0.5deg as 30.000 minutes.
        String text = enDa().toPlainText(new PointVo(new double[]{10.5, 54.5}));

        assertTrue(text.startsWith("Point"), "output should start with the geometry type, was: " + text);
        // 54.5 -> "54° 30.000'N"; 10.5 -> "010° 30.000'E". Assert the load-bearing pieces.
        assertTrue(text.contains("54° 30.000'N"),
                "latitude minutes should be formatted to 3 decimals, was: " + text);
        assertTrue(text.contains("010° 30.000'E"),
                "longitude minutes should be formatted to 3 decimals, was: " + text);
    }

    @Test
    public void toPlainTextUnsupportedGeometryThrows() {
        // GeometryCollection is explicitly not handled by the converter.
        try {
            enDa().toPlainText(new GeometryCollectionVo(new GeometryVo[]{
                    new PointVo(new double[]{10.0, 54.0})}));
            fail("GeometryCollection is unsupported and must throw");
        } catch (Exception e) {
            assertTrue(e.getMessage().startsWith("Unsupported GeoJSON type"),
                    "message should name the unsupported type, was: " + e.getMessage());
        }
    }

    @Test
    public void toPlainTextPointWithNullCoordinatesThrows() {
        try {
            enDa().toPlainText(new PointVo((double[]) null));
            fail("a Point without coordinates must throw");
        } catch (Exception e) {
            assertEquals("Point has no associated coordinates", e.getMessage());
        }
    }

    @Test
    public void toPlainTextPolygonWithInteriorRingThrows() {
        // A polygon with two rings (exterior + interior hole) is not supported by the text format.
        double[][] exterior = {{0, 0}, {0, 10}, {10, 10}, {10, 0}, {0, 0}};
        double[][] interior = {{2, 2}, {2, 4}, {4, 4}, {4, 2}, {2, 2}};
        PolygonVo polygon = new PolygonVo(new double[][][]{exterior, interior});
        try {
            enDa().toPlainText(polygon);
            fail("a polygon with an interior ring must throw");
        } catch (Exception e) {
            assertEquals("Only polygons with an exterior ring and no interior rings supported", e.getMessage());
        }
    }

    /***********************************************/
    /** Round-trip text -> GeoJSON -> text        **/
    /***********************************************/

    @Test
    public void multiPointRoundTripsCoordinatesWithinDelta() throws Exception {
        PlainTextConverter converter = enDa();
        String originalText =
                "54° 41,000'N - 010° 35,000'E\n" +
                "54° 35,566'N - 010° 35,010'E";

        // text -> GeoJSON (MultiPoint)
        FeatureCollectionVo fc = converter.fromPlainText(originalText);
        GeometryVo geometry = singleGeometry(fc);
        assertTrue(geometry instanceof MultiPointVo);
        double[][] originalCoords = ((MultiPointVo) geometry).getCoordinates();

        // GeoJSON -> text -> GeoJSON again, and compare the re-parsed coordinates (not exact strings).
        String roundTripText = converter.toPlainText(geometry);
        FeatureCollectionVo fc2 = converter.fromPlainText(roundTripText);
        GeometryVo geometry2 = singleGeometry(fc2);
        assertTrue(geometry2 instanceof MultiPointVo, "round-tripped geometry should remain a MultiPoint");
        double[][] roundTrippedCoords = ((MultiPointVo) geometry2).getCoordinates();

        assertEquals(originalCoords.length, roundTrippedCoords.length);
        for (int i = 0; i < originalCoords.length; i++) {
            assertArrayEquals(originalCoords[i], roundTrippedCoords[i], 1e-3,
                    "coordinate " + i + " should survive the round-trip");
        }
    }

    /***********************************************/
    /** fromUtmText                               **/
    /***********************************************/

    @Test
    public void singleUtmLineProducesPointInPlausibleRange() throws Exception {
        FeatureCollectionVo fc = enDa().fromUtmText("32 V 624726 6207884");

        GeometryVo geometry = singleGeometry(fc);
        assertTrue(geometry instanceof PointVo, "single UTM line should yield a Point");

        double[] coords = ((PointVo) geometry).getCoordinates();
        // Zone 32V over Denmark: lat band 56-64, longitude east of zone meridian (9 deg E).
        double lon = coords[0];
        double lat = coords[1];
        assertTrue(lat >= 50.0 && lat <= 60.0, "latitude " + lat + " should be in a plausible northern range");
        assertTrue(lon >= 8.0 && lon <= 20.0, "longitude " + lon + " should be in a plausible eastern range");
    }

    @Test
    public void twoUtmLinesProduceMultiPoint() throws Exception {
        FeatureCollectionVo fc = enDa().fromUtmText(
                "32 V 624726 6207884\n" +
                "32 V 600000 6200000");

        GeometryVo geometry = singleGeometry(fc);
        assertTrue(geometry instanceof MultiPointVo, "two UTM lines should yield a MultiPoint");
        assertEquals(2, ((MultiPointVo) geometry).getCoordinates().length);
    }

    @Test
    public void blankUtmTextReturnsNull() throws Exception {
        assertNull(enDa().fromUtmText("  "));
    }

    @Test
    public void utmLineWithoutSpaceBetweenZoneAndLatZoneIsStillParsed() throws Exception {
        // prepareUtmLine() repairs "32V ..." -> "32 V ..."; both forms must parse to the same point.
        FeatureCollectionVo withSpace = enDa().fromUtmText("32 V 624726 6207884");
        FeatureCollectionVo withoutSpace = enDa().fromUtmText("32V 624726 6207884");

        GeometryVo g1 = singleGeometry(withSpace);
        GeometryVo g2 = singleGeometry(withoutSpace);
        assertTrue(g1 instanceof PointVo);
        assertTrue(g2 instanceof PointVo, "missing zone/lat-zone space must still parse to a Point");

        assertArrayEquals(((PointVo) g1).getCoordinates(), ((PointVo) g2).getCoordinates(), COORD_DELTA,
                "repaired UTM line should map to the same coordinate");
    }

    @Test
    public void utmLineWithLowerCaseSouthernBandIsReadAsSouthern() throws Exception {
        // L-048: a lower-case band letter, glued to the zone or spaced, reads as the upper-case one. Band M is
        // southern (8° S to 0°), so the point lies south of the equator, not mirrored into the north.
        double[] upper = ((PointVo) singleGeometry(enDa().fromUtmText("33 M 500000 9500000"))).getCoordinates();
        assertTrue(upper[1] < 0 && upper[1] > -8, "band M lies between 8 deg S and the equator: " + upper[1]);
        for (String line : new String[] { "33 m 500000 9500000", "33m 500000 9500000" }) {
            double[] lower = ((PointVo) singleGeometry(enDa().fromUtmText(line))).getCoordinates();
            assertArrayEquals(upper, lower, COORD_DELTA, line);
        }
    }

    @Test
    public void utmLineWithAZoneOrBandThatDoesNotExistIsRefused() {
        // L-155: zones are 1 to 60 and bands C to X without I and O; anything else is refused, naming the line
        for (String line : new String[] { "0 V 500000 6200000", "61 V 500000 6200000", "32 Y 500000 6200000",
                "32I 500000 6200000", "32 624726 6207884" }) {
            Exception e = assertThrows(Exception.class, () -> enDa().fromUtmText(line), line);
            assertEquals("Invalid UTM position: " + line, e.getMessage());
        }
    }

    /***********************************************/
    /** Public patterns                           **/
    /***********************************************/

    @Test
    public void typeFormatMatchesKnownTypesAndCapturesDescription() {
        Matcher m = PlainTextConverter.TYPE_FORMAT.matcher("Point, da: Ubåd, en: Submarine");
        assertTrue(m.find(), "TYPE_FORMAT should match a Point header");
        assertEquals("Point", m.group("type"));
        assertEquals(", da: Ubåd, en: Submarine", m.group("desc"));

        // A bare position line is not a type header.
        assertFalse(PlainTextConverter.TYPE_FORMAT.matcher("54° 41,000'N - 010° 35,000'E").find());
    }

    @Test
    public void positionFormatCapturesLatLonDirectionsAndDescription() {
        Matcher m = PlainTextConverter.POSITION_FORMAT.matcher("54° 45,7' N 10° 29,1' E, Ærø S.");
        assertTrue(m.find(), "POSITION_FORMAT should match a position line");
        assertEquals("N", m.group("latDir"));
        assertEquals("E", m.group("lonDir"));
        assertEquals(", Ærø S.", m.group("desc"));
    }

    @Test
    public void utmZoneFormatMatchesNoSpaceZoneAndLatZonePrefix() {
        // UTM_ZONE_FORMAT requires zone digits IMMEDIATELY followed by the lat-zone letter and
        // whitespace, i.e. it detects the broken "32V ..." form that prepareUtmLine() repairs.
        Matcher m = PlainTextConverter.UTM_ZONE_FORMAT.matcher("32V 624726 6207884");
        assertTrue(m.find(), "UTM_ZONE_FORMAT should match the no-space zone prefix");
        assertEquals("32", m.group("zone"));
        assertEquals("V", m.group("latZone"));

        // The already-correct "32 V ..." form has a space before the letter, so the pattern does
        // NOT match it (prepareUtmLine only upper-cases the band letter of such lines).
        assertFalse(PlainTextConverter.UTM_ZONE_FORMAT.matcher("32 V 624726 6207884").find(),
                "UTM_ZONE_FORMAT should not match the spaced zone prefix");
    }
}
