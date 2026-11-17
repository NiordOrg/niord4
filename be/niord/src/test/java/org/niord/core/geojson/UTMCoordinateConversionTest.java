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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link UTMCoordinateConversion}, the pure geodetic conversion utility
 * (a vendored copy of Sami Salkosua's CoordinateConversion class).
 *
 * <p>These tests are framework-neutral: they exercise only the public API
 * ({@code degreeToRadian}, {@code radianToDegree}, {@code latLon2UTM}, {@code utm2LatLon},
 * {@code latLon2MGRUTM}, {@code mgrutm2LatLon}) using fixed, explicit coordinate inputs so they
 * are fully deterministic and order-independent. Because easting/northing are truncated to
 * {@code int} (≈ 1 m resolution) the lat/lon round-trips are asserted within a small tolerance.</p>
 */
public class UTMCoordinateConversionTest {

    /** Tolerance for the angle-conversion identities. */
    private static final double ANGLE_DELTA = 1e-9;

    /**
     * Round-trip tolerance for UTM (≈ 0.01 deg). Easting/northing are int-truncated to ~1 m, so the
     * reconstructed lat/lon is only metre-accurate.
     */
    private static final double UTM_ROUNDTRIP_DELTA = 0.01;

    /** Round-trip tolerance for MGRS/MGRUTM, which keeps only 5-digit easting/northing groups. */
    private static final double MGRUTM_ROUNDTRIP_DELTA = 0.05;

    // ---------------------------------------------------------------------
    // degreeToRadian / radianToDegree
    // ---------------------------------------------------------------------

    @Test
    public void degreeToRadian_180Degrees_isPi() {
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        assertEquals(Math.PI, c.degreeToRadian(180.0), ANGLE_DELTA);
    }

    @Test
    public void degreeToRadian_zero_isZero() {
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        assertEquals(0.0, c.degreeToRadian(0.0), ANGLE_DELTA);
    }

    @Test
    public void degreeToRadian_90Degrees_isHalfPi() {
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        assertEquals(Math.PI / 2.0, c.degreeToRadian(90.0), ANGLE_DELTA);
    }

    @Test
    public void degreeToRadian_negativeDegrees_isNegativeRadians() {
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        assertEquals(-Math.PI, c.degreeToRadian(-180.0), ANGLE_DELTA);
    }

    @Test
    public void radianToDegree_pi_is180() {
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        assertEquals(180.0, c.radianToDegree(Math.PI), ANGLE_DELTA);
    }

    @Test
    public void radianToDegree_zero_isZero() {
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        assertEquals(0.0, c.radianToDegree(0.0), ANGLE_DELTA);
    }

    @Test
    public void degreeRadianRoundTrip_preservesValue() {
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        assertEquals(57.3, c.radianToDegree(c.degreeToRadian(57.3)), ANGLE_DELTA);
    }

    @Test
    public void radianDegreeRoundTrip_preservesValue() {
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        // Start from a radian value and convert back through degrees.
        assertEquals(1.234, c.degreeToRadian(c.radianToDegree(1.234)), ANGLE_DELTA);
    }

    // ---------------------------------------------------------------------
    // latLon2UTM - zone prefix (the reliable, format-stable part of the output)
    // ---------------------------------------------------------------------

    @Test
    public void latLon2UTM_copenhagen_isZone33BandU() {
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        String utm = c.latLon2UTM(55.6761, 12.5683);
        // Long zone 33 (12.5683/6 + 31 = 33), latitude band U (48..56 deg N).
        assertTrue(utm.startsWith("33 U "),
                "Expected Copenhagen to be in zone '33 U ', was: " + utm);
    }

    @Test
    public void latLon2UTM_format_hasFourSpaceSeparatedTokens() {
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        String[] tokens = c.latLon2UTM(55.6761, 12.5683).split(" ");
        assertEquals(4, tokens.length);
        assertEquals("33", tokens[0]);
        assertEquals("U", tokens[1]);
        // Easting/northing are int-truncated; parse must succeed as integers.
        Integer.parseInt(tokens[2]);
        Integer.parseInt(tokens[3]);
    }

    @Test
    public void latLon2UTM_singleDigitZone_isZeroPadded() {
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        // lon -177 -> long zone ((180-177)/6)+1 = 1, must be zero-padded to "01".
        String utm = c.latLon2UTM(0.0, -177.0);
        assertTrue(utm.startsWith("01 "),
                "Expected single-digit zone to be zero-padded to '01 ', was: " + utm);
    }

    @Test
    public void latLon2UTM_zonePrefixIsAlwaysTwoChars() {
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        // A two-digit zone (Copenhagen, 33) and a one-digit (padded) zone both yield a 2-char prefix.
        assertEquals("33", c.latLon2UTM(55.6761, 12.5683).substring(0, 2));
        assertEquals("01", c.latLon2UTM(0.0, -177.0).substring(0, 2));
    }

    // ---------------------------------------------------------------------
    // latLon2UTM / utm2LatLon round-trip (metre accuracy)
    // ---------------------------------------------------------------------

    private void assertUtmRoundTrip(double lat, double lon) {
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        String utm = c.latLon2UTM(lat, lon);
        double[] back = c.utm2LatLon(utm);
        assertEquals(lat, back[0], UTM_ROUNDTRIP_DELTA,
                "latitude round-trip failed for (" + lat + "," + lon + ") via " + utm);
        assertEquals(lon, back[1], UTM_ROUNDTRIP_DELTA,
                "longitude round-trip failed for (" + lat + "," + lon + ") via " + utm);
    }

    @Test
    public void utmRoundTrip_copenhagen() {
        assertUtmRoundTrip(55.68, 12.57);
    }

    @Test
    public void utmRoundTrip_newYork() {
        assertUtmRoundTrip(40.0, -74.0);
    }

    @Test
    public void utmRoundTrip_capeTown_southernHemisphere() {
        // Southern-hemisphere point exercises the 10,000,000 m false-northing branch.
        assertUtmRoundTrip(-34.0, 18.0);
    }

    @Test
    public void utm2LatLon_returnsLatThenLon() {
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        String utm = c.latLon2UTM(55.68, 12.57);
        double[] back = c.utm2LatLon(utm);
        assertEquals(2, back.length, "Result must be a [lat, lon] pair");
        // Index 0 is latitude, index 1 is longitude (ordering contract relied on by callers).
        assertEquals(55.68, back[0], UTM_ROUNDTRIP_DELTA);
        assertEquals(12.57, back[1], UTM_ROUNDTRIP_DELTA);
    }

    // ---------------------------------------------------------------------
    // latLon2MGRUTM / mgrutm2LatLon round-trip
    // ---------------------------------------------------------------------

    @Test
    public void latLon2MGRUTM_hasNoSpaces_andStartsWithZoneAndBand() {
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        String mgr = c.latLon2MGRUTM(55.68, 12.57);
        assertFalse(mgr.contains(" "), "MGRUTM string must be space-free, was: " + mgr);
        // Concatenated form: 2-char zone "33" + lat band "U" + 2 digraph letters + 5+5 digits.
        assertTrue(mgr.startsWith("33U"), "Expected MGRUTM to start with '33U', was: " + mgr);
        assertEquals(15, mgr.length(),
                "MGRUTM should be zone(2)+band(1)+digraphs(2)+easting(5)+northing(5) = 15 chars");
    }

    /**
     * The MGRUTM round-trip now works for the northern hemisphere: {@code mgrutm2LatLon} reconstructs
     * the northing in the correct 2,000,000 m grid cycle, so {@code latLon2MGRUTM} -&gt;
     * {@code mgrutm2LatLon} recovers the Copenhagen input. Previously the band reconstruction snapped
     * one cycle too low and the latitude came back near 37.7 instead of 55.68.
     * {@code latLon2UTM}/{@code utm2LatLon} already round-tripped correctly.
     */
    @Test
    public void mgrutmRoundTrip_copenhagen_recoversInput() {
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        String mgr = c.latLon2MGRUTM(55.68, 12.57);
        double[] back = c.mgrutm2LatLon(mgr);
        assertEquals(55.68, back[0], MGRUTM_ROUNDTRIP_DELTA, "MGRUTM latitude round-trip");
        assertEquals(12.57, back[1], MGRUTM_ROUNDTRIP_DELTA, "MGRUTM longitude round-trip");
    }

    /**
     * Round-trips a spread of northern-hemisphere coordinates across several latitude bands - notably
     * band U (which straddles a 2,000,000 m grid-cycle boundary; Copenhagen sits in its upper half) and
     * the far-northern band W (Tromso) - the exact cases the old {@code Math.floor} cycle-base picked
     * wrong. A correct reconstruction recovers each input to well within {@link #MGRUTM_ROUNDTRIP_DELTA};
     * empirically the residual is ~1e-4 deg.
     *
     * <p>Scoped to the northern hemisphere on purpose: the southern-hemisphere MGRUTM reverse path has a
     * separate, pre-existing latitude-band defect (reconstructed latitude ~90 deg off) that this fix does
     * not address - see docs/potential-errors.md (CG4).</p>
     */
    @Test
    public void mgrutmRoundTrip_northernHemisphere_recoversInputAcrossBands() {
        double[][] coords = {
                { 55.68, 12.57 },   // Copenhagen - band U (straddles a 2,000,000 m boundary)
                { 59.91, 10.75 },   // Oslo       - band V
                { 40.71, -74.01 },  // New York   - band T (western/negative longitude)
                { 35.68, 139.77 },  // Tokyo      - band S
                { 69.65, 18.96 },   // Tromso     - band W (far north; floor->round would mis-snap)
                {  1.35, 103.82 },  // Singapore  - band N (just north of the equator)
        };
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        for (double[] ll : coords) {
            String mgr = c.latLon2MGRUTM(ll[0], ll[1]);
            double[] back = c.mgrutm2LatLon(mgr);
            assertEquals(ll[0], back[0], MGRUTM_ROUNDTRIP_DELTA, "MGRUTM latitude round-trip for " + mgr);
            assertEquals(ll[1], back[1], MGRUTM_ROUNDTRIP_DELTA, "MGRUTM longitude round-trip for " + mgr);
        }
    }

    // ---------------------------------------------------------------------
    // validate() - exercised through the public conversion entry points
    // ---------------------------------------------------------------------

    @Test
    public void latLon2UTM_latitudeAbove90_throws() {
        assertThrows(IllegalArgumentException.class, () -> new UTMCoordinateConversion().latLon2UTM(90.5, 0.0));
    }

    @Test
    public void latLon2UTM_latitudeBelowMinus90_throws() {
        assertThrows(IllegalArgumentException.class, () -> new UTMCoordinateConversion().latLon2UTM(-90.5, 0.0));
    }

    @Test
    public void latLon2UTM_longitude180_throws() {
        // Upper longitude bound is exclusive: 180.0 is invalid.
        assertThrows(IllegalArgumentException.class, () -> new UTMCoordinateConversion().latLon2UTM(0.0, 180.0));
    }

    @Test
    public void latLon2UTM_longitudeBelowMinus180_throws() {
        assertThrows(IllegalArgumentException.class, () -> new UTMCoordinateConversion().latLon2UTM(0.0, -180.5));
    }

    @Test
    public void latLon2MGRUTM_latitudeOutOfRange_throws() {
        // validate() is shared by the MGRUTM entry point too.
        assertThrows(IllegalArgumentException.class, () -> new UTMCoordinateConversion().latLon2MGRUTM(95.0, 0.0));
    }

    @Test
    public void latLon2UTM_latitude90_isValidBoundary() {
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        // 90.0 is the inclusive upper latitude bound and must NOT throw.
        String utm = c.latLon2UTM(90.0, 0.0);
        assertNotNull(utm);
        assertFalse(utm.isEmpty(), "Expected a non-empty UTM string at the +90 boundary");
        // The first two characters are the zero-padded long zone.
        assertEquals("31", utm.substring(0, 2));
    }

    @Test
    public void latLon2UTM_longitudeMinus180_isValidBoundary() {
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        // -180.0 is the inclusive lower longitude bound and must NOT throw.
        String utm = c.latLon2UTM(0.0, -180.0);
        assertNotNull(utm);
        assertFalse(utm.isEmpty(), "Expected a non-empty UTM string at the -180 boundary");
        // long zone ((180-180)/6)+1 = 1 -> zero-padded "01".
        assertEquals("01", utm.substring(0, 2));
    }

    // ---------------------------------------------------------------------
    // Documented current behaviour (pinning surprising edge cases)
    // ---------------------------------------------------------------------

    /**
     * Documents that the latitude band for the +90 boundary is 'Z'. The latitude-band table
     * (LatZones) only spans bands up to 'X' for the official MGRS scheme (X covers 72..84 deg);
     * a latitude of exactly 90 falls past the table and the implementation returns the last
     * positive letter 'Z'. This pins the current behaviour rather than endorsing it as correct
     * MGRS - the official UTM/MGRS grid is not defined for the poles.
     */
    @Test
    public void latLon2UTM_latitude90_returnsBandZ_documentsBehavior() {
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        String[] tokens = c.latLon2UTM(90.0, 0.0).split(" ");
        assertEquals("Z", tokens[1], "Latitude band for +90 is the fall-through 'Z'");
    }

    /**
     * Documents that the southern-hemisphere false northing of 10,000,000 m is applied, so the
     * northern and corresponding southern UTM strings differ (and are not symmetric in northing).
     * This pins the hemisphere-handling behaviour.
     */
    @Test
    public void latLon2UTM_southernHemisphere_usesFalseNorthing_documentsBehavior() {
        UTMCoordinateConversion c = new UTMCoordinateConversion();
        String south = c.latLon2UTM(-34.0, 18.0);
        String[] tokens = south.split(" ");
        // Southern band 'H' (covers -40..-32) is selected for -34 deg.
        assertEquals("H", tokens[1]);
        // Northing in the southern hemisphere is offset by the 10,000,000 m false northing,
        // so it is well above 6,000,000 m for a point at -34 deg.
        long northing = Long.parseLong(tokens[3]);
        assertTrue(northing > 6_000_000L,
                "Southern-hemisphere northing should carry the 10,000,000 m false northing, was: "
                + northing);
    }
}
