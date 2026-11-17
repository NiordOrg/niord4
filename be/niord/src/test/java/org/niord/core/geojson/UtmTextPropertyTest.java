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
package org.niord.core.geojson;

import org.junit.jupiter.api.Test;
import org.niord.model.geojson.FeatureCollectionVo;
import org.niord.model.geojson.PointVo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Properties of the UTM text an editor imports in the geometry editor ({@code POST /rest/messages/parse-utm},
 * {@link PlainTextConverter#fromUtmText}), over positions generated from a fixed seed, all over the globe.
 * <p>
 * The reference is a forward conversion written here from the published series (Snyder, "Map Projections: A
 * Working Manual", USGS 1987, pp. 61-64), WGS 84, standard 6-degree zones and standard band letters; it is exact to
 * well below a centimetre inside a zone. So a property that fails here names the product's reading, not a round trip
 * through its own forward conversion ({@code latLon2UTM}, which no product code calls). The northern round trip
 * through that forward conversion is covered by PlainTextConverterGridTest.
 */
public class UtmTextPropertyTest {

    private static final long SEED = 20261115L;

    private static final PlainTextConverter EN = PlainTextConverter.newInstance(new String[] { "en" });

    /** The standard UTM latitude bands, C from 80° S to X from 72° N to 84° N */
    private static final String BANDS = "CDEFGHJKLMNPQRSTUVWX";

    // WGS 84
    private static final double A = 6378137.0;
    private static final double F = 1 / 298.257223563;
    private static final double E2 = F * (2 - F);
    private static final double EP2 = E2 / (1 - E2);
    private static final double K0 = 0.9996;

    /** The standard zone of a longitude in [-180, 180) */
    static int zone(double lon) {
        return Math.min(60, (int) Math.floor((lon + 180) / 6) + 1);
    }

    /** The standard band letter of a latitude in [-80, 84] */
    static char band(double lat) {
        int i = (int) Math.floor((lat + 80) / 8);
        return BANDS.charAt(Math.max(0, Math.min(19, i)));
    }

    /** Easting and northing of a position in its standard zone (Snyder 8-9 to 8-10, 3-21) */
    static double[] toUtm(double lat, double lon) {
        double phi = Math.toRadians(lat);
        double lambda0 = Math.toRadians(-183 + 6 * zone(lon));
        double n = A / Math.sqrt(1 - E2 * Math.sin(phi) * Math.sin(phi));
        double t = Math.tan(phi) * Math.tan(phi);
        double c = EP2 * Math.cos(phi) * Math.cos(phi);
        double a = (Math.toRadians(lon) - lambda0) * Math.cos(phi);
        double e4 = E2 * E2;
        double e6 = e4 * E2;
        double m = A * ((1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi
                - (3 * E2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * Math.sin(2 * phi)
                + (15 * e4 / 256 + 45 * e6 / 1024) * Math.sin(4 * phi)
                - (35 * e6 / 3072) * Math.sin(6 * phi));
        double easting = K0 * n * (a + (1 - t + c) * Math.pow(a, 3) / 6
                + (5 - 18 * t + t * t + 72 * c - 58 * EP2) * Math.pow(a, 5) / 120) + 500000;
        double northing = K0 * (m + n * Math.tan(phi) * (a * a / 2
                + (5 - t + 9 * c + 4 * c * c) * Math.pow(a, 4) / 24
                + (61 - 58 * t + t * t + 600 * c - 330 * EP2) * Math.pow(a, 6) / 720));
        if (lat < 0) {
            northing += 10000000;
        }
        return new double[] { easting, northing };
    }

    /** The UTM line an editor would type for the position, to the centimetre */
    static String utmLine(double lat, double lon, boolean spaceAfterZone) {
        double[] en = toUtm(lat, lon);
        return String.format(Locale.ROOT, "%d%s%c %.2f %.2f", zone(lon), spaceAfterZone ? " " : "", band(lat),
                en[0], en[1]);
    }

    static double[] read(String line) throws Exception {
        FeatureCollectionVo fc = EN.fromUtmText(line);
        return ((PointVo) fc.getFeatures()[0].getGeometry()).getCoordinates();
    }

    /** Distance in metres between two near positions (equirectangular; exact enough below a kilometre) */
    static double metres(double lat1, double lon1, double lat2, double lon2) {
        double dLat = (lat2 - lat1) * 111_320;
        double dLon = (lon2 - lon1) * 111_320 * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        return Math.hypot(dLat, dLon);
    }

    @Test
    public void theReference_isTheStandardProjection() {
        // Two fixes that show the reference itself is right: the false origin of zone 31 on the equator, and 1
        // degree south of the equator on a central meridian, 10,000,000 m less the scaled meridian arc of 1 degree
        // (110,574 m on WGS 84).
        double[] origin = toUtm(0, 3);
        assertEquals(500000, origin[0], 1e-6);
        assertEquals(0, origin[1], 1e-6);
        double[] south = toUtm(-1, 9);
        assertEquals(500000, south[0], 1e-6);
        assertEquals(10000000 - 0.9996 * 110574.4, south[1], 2);
    }

    @Test
    public void everyStandardUtmPosition_readsBackWithinAMetre_documentsBug() throws Exception {
        // PB-5. For positions all over the UTM domain (80° S to 84° N), the line "zone band easting northing", written
        // to the centimetre from the standard projection, must be read back within a metre: the reading is the
        // inverse series of the same projection, good to millimetres inside a zone. It is read back up to 8 m off,
        // always in latitude, nothing on a zone's central meridian and most at its edges: the inverse adds the D^4
        // term of the latitude series where the series (Snyder 8-17) subtracts it
        // (UTMCoordinateConversion.UTM2LatLon, "phi1 - fact1 * (fact2 + fact3 + fact4)"). The same 8 m shows
        // through the product's own forward conversion (PlainTextConverterGridTest). Pinned: the count beyond a
        // metre and the worst case below 10 m, so the fix fails this test.
        Random random = new Random(SEED);
        List<String> failures = new ArrayList<>();
        double worst = 0;
        String worstCase = "";
        int checked = 0;
        for (int i = 0; i < 4000; i++) {
            double lat = -80 + random.nextDouble() * 164;
            double lon = -180 + random.nextDouble() * 359.999;
            String line = utmLine(lat, lon, random.nextBoolean());
            double[] c = read(line);
            double d = metres(lat, lon, c[1], c[0]);
            String thisCase = String.format(Locale.ROOT, "%.6f,%.6f -> \"%s\" -> %.6f,%.6f (%.2f m)",
                    lat, lon, line, c[1], c[0], d);
            if (d > worst) {
                worst = d;
                worstCase = thisCase;
            }
            if (d > 1.0) {
                failures.add(thisCase);
            }
            checked++;
        }
        assertEquals(4000, checked);
        // Found 2026-11-15: 1,280 of the 4,000 beyond a metre; the worst -42.119124,-137.988165 read 8.04 m north
        assertTrue(failures.size() > 1000, failures.size() + " beyond a metre, the first: "
                + failures.stream().limit(5).toList() + "; the worst: " + worstCase);
        assertTrue(worst < 10, "the worst: " + worstCase);
    }

    @Test
    public void aPositionOnTheCentralMeridian_readsBackExactly_andTheErrorGrowsTowardsTheZonesEdge() throws Exception {
        // PB-5, the smallest input and its shape: on the central meridian (D = 0) the reading is exact; 1°, 2° and 3°
        // east of it, at 56° N, the error grows with the fourth power of the distance, as a wrong D^4 term does.
        double[] errors = new double[4];
        for (int d = 0; d <= 3; d++) {
            double lat = 56;
            double lon = 9 + d;
            double[] c = read(utmLine(lat, lon, true));
            errors[d] = metres(lat, lon, c[1], c[0]);
        }
        assertTrue(errors[0] < 0.01, "on the central meridian: " + errors[0] + " m");
        assertTrue(errors[1] < errors[2] && errors[2] < errors[3], java.util.Arrays.toString(errors));
        assertTrue(errors[3] > 1, "3° off the central meridian: " + errors[3] + " m");
        // Fourth power: 3° off is about (3/2)^4 = 5 times 2° off
        assertTrue(errors[3] / errors[2] > 3.5 && errors[3] / errors[2] < 7, java.util.Arrays.toString(errors));
    }

    @Test
    public void positionsOnTheBandAndZoneEdges_readBackWithinTenMetres() throws Exception {
        // The boundaries: the equator from both sides, every band edge, the poles' limits of UTM, the antimeridian
        // and the zone edges, each approached to within a millionth of a degree. No hemisphere or zone is mistaken
        // (that would be kilometres off); the bound is PB-5's 10 m, not a metre.
        List<String> failures = new ArrayList<>();
        double[] lats = { -80, -72.000001, -8.000001, -1e-6, 0, 1e-6, 7.999999, 55.999999, 56, 63.999999, 71.999999,
                72, 83.999999 };
        double[] lons = { -180, -179.999999, -174.000001, -0.000001, 0, 2.999999, 5.999999, 6, 11.999999, 179.999999 };
        for (double lat : lats) {
            for (double lon : lons) {
                String line = utmLine(lat, lon, true);
                double[] c = read(line);
                double d = metres(lat, lon, c[1], c[0]);
                if (d > 10.0) {
                    failures.add(String.format(Locale.ROOT, "%.6f,%.6f -> \"%s\" -> %.6f,%.6f (%.0f m)",
                            lat, lon, line, c[1], c[0], d));
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " beyond 10 m: " + failures);
    }

    @Test
    public void theBandLettersCase_changesNothing() throws Exception {
        // A fault that is fixed: a lower-case southern band letter
        // was read as a northern one ("32c ..." landed about 10,000 km from the position typed). The band letter is
        // read in either case, glued to the zone or spaced: every line reads as its upper-case form, to the
        // millimetre, in both hemispheres.
        Random random = new Random(SEED + 1);
        List<String> differ = new ArrayList<>();
        int southern = 0;
        for (int i = 0; i < 400; i++) {
            double lat = -80 + random.nextDouble() * 164;
            double lon = -180 + random.nextDouble() * 359.999;
            boolean spaced = random.nextBoolean();
            String upper = utmLine(lat, lon, spaced);
            String lower = upper.toLowerCase(Locale.ROOT);
            double[] u = read(upper);
            double[] l = read(lower);
            if (metres(u[1], u[0], l[1], l[0]) > 0.001) {
                differ.add(lower + " -> " + l[1] + "," + l[0] + " for " + u[1] + "," + u[0]);
            }
            if (lat < 0) {
                southern++;
            }
        }
        assertTrue(southern > 150, "the generator gave too few southern positions: " + southern);
        assertTrue(differ.isEmpty(), differ.size() + " differ: " + differ.stream().limit(5).toList());
    }

    @Test
    public void theSmallestLowerCaseSouthernBand_isReadAsSouthern() throws Exception {
        // The smallest input: band C (80° S to 72° S) in lower case, glued and spaced, is the same position as
        // in upper case, in the southern hemisphere.
        double[] upper = read("32C 500000 1500000");
        assertTrue(upper[1] < -72 && upper[1] > -80, "upper case: " + upper[1]);
        for (String line : List.of("32c 500000 1500000", "32 c 500000 1500000")) {
            double[] lower = read(line);
            assertEquals(upper[0], lower[0], 1e-12, line);
            assertEquals(upper[1], lower[1], 1e-12, line);
        }
    }

    @Test
    public void everyAcceptedUtmLine_isAPositionOnTheGlobe() {
        // PB-2, fixed by P1-11. Whatever the parser accepts becomes a message position. Lines generated with zones 0
        // to 99, every letter in either case as the band, and eastings and northings well outside a zone: an
        // accepted line is a position on the globe (latitude within 90, longitude within 180), in a zone that exists
        // (1 to 60) and a band that exists (C to X, without I and O). A refused line is refused naming the line.
        // Before the fix a zone above 60 was read with a central meridian beyond 180° (zone 61 at 183°, zone 99 at
        // 411°), zone 0 as if it were zone 31, and a line far outside its zone was placed off the globe.
        Random random = new Random(SEED + 2);
        List<String> offTheGlobe = new ArrayList<>();
        List<String> zoneThatDoesNotExist = new ArrayList<>();
        List<String> bandThatDoesNotExist = new ArrayList<>();
        List<String> refusedWithoutTheLine = new ArrayList<>();
        int accepted = 0;
        for (int i = 0; i < 3000; i++) {
            int zone = random.nextInt(100);
            char band = (char) ('A' + random.nextInt(26));
            if (random.nextBoolean()) {
                band = Character.toLowerCase(band);
            }
            double easting = -100000 + random.nextDouble() * 1200000;
            double northing = -500000 + random.nextDouble() * 11000000;
            String line = String.format(Locale.ROOT, "%d %c %.1f %.1f", zone, band, easting, northing);
            double[] c;
            try {
                c = read(line);
            } catch (Exception refused) {
                if (!("Invalid UTM position: " + line).equals(refused.getMessage())) {
                    refusedWithoutTheLine.add(line + ": " + refused);
                }
                continue;
            }
            accepted++;
            if (!(Math.abs(c[1]) <= 90 && Math.abs(c[0]) <= 180)) {
                offTheGlobe.add(line);
            }
            if (zone < 1 || zone > 60) {
                zoneThatDoesNotExist.add(line);
            }
            if (BANDS.indexOf(Character.toUpperCase(band)) < 0) {
                bandThatDoesNotExist.add(line);
            }
        }
        assertTrue(offTheGlobe.isEmpty(), "lines accepted off the globe: " + offTheGlobe.size() + ", the first: "
                + offTheGlobe.stream().limit(5).toList());
        assertTrue(zoneThatDoesNotExist.isEmpty(), "zones that do not exist accepted: " + zoneThatDoesNotExist.size()
                + ", the first: " + zoneThatDoesNotExist.stream().limit(5).toList());
        assertTrue(bandThatDoesNotExist.isEmpty(), "bands that do not exist accepted: " + bandThatDoesNotExist.size()
                + ", the first: " + bandThatDoesNotExist.stream().limit(5).toList());
        assertTrue(refusedWithoutTheLine.isEmpty(), "" + refusedWithoutTheLine.stream().limit(5).toList());
        // About 46% of the lines have a zone and a band that exist; most of them are on the globe and accepted
        assertTrue(accepted > 800, "too few lines accepted: " + accepted);
    }

    @Test
    public void theSmallestLinesOffTheGlobe_areRefused() throws Exception {
        // PB-2, the smallest inputs: the first zone past the last, at the false easting, in Denmark's band, a zone
        // far past it, and zone 0, which was read as zone 31. Each is refused, naming the line.
        for (String line : List.of("61 V 500000 6200000", "99 V 500000 6200000", "0 V 500000 6200000",
                "61V 500000 6200000", "0v 500000 6200000")) {
            Exception e = assertThrows(Exception.class, () -> read(line), line);
            assertEquals("Invalid UTM position: " + line, e.getMessage());
        }
        // The first and the last zone exist: at the false easting, on their central meridians
        assertEquals(-177.0, read("1 V 500000 6200000")[0], 1e-9);
        assertEquals(177.0, read("60 V 500000 6200000")[0], 1e-9);
    }

    @Test
    public void aBandLetterThatDoesNotExist_isRefused() throws Exception {
        // The bands are C to X without I and O; A, B, Y and Z are the polar areas, which UTM does not cover
        for (String line : List.of("32 A 500000 6200000", "32 B 500000 6200000", "32 I 500000 6200000",
                "32 O 500000 6200000", "32 Y 500000 6200000", "32 Z 500000 6200000", "32o 500000 6200000")) {
            Exception e = assertThrows(Exception.class, () -> read(line), line);
            assertEquals("Invalid UTM position: " + line, e.getMessage());
        }
        // The first and the last band exist
        assertTrue(read("32 C 500000 1500000")[1] < -72);
        assertTrue(read("32 X 500000 8500000")[1] > 72);
    }

    @Test
    public void aPositionOnTheAntimeridian_isReadAt180_andOneWellPastIt_isRefused() throws Exception {
        // 80° S on the antimeridian, typed to the centimetre in zone 1, is read a hair west of -180°, the
        // conversion's rounding: it is accepted at -180°. Zone 60 at 400 km east of its central meridian in
        // Denmark's band is 183° and more, off the globe: refused, naming the line.
        String antimeridian = utmLine(-80, -180, true);
        double[] c = read(antimeridian);
        assertEquals(-180.0, c[0], 1e-5, antimeridian);
        assertTrue(c[0] >= -180.0, antimeridian + " -> " + c[0]);
        String pastIt = "60 V 900000 6200000";
        Exception e = assertThrows(Exception.class, () -> read(pastIt));
        assertEquals("Invalid UTM position: " + pastIt, e.getMessage());
    }

    @Test
    public void aLineInAZoneAndBandThatExist_butOffTheGlobe_isRefused() {
        // Near the pole the inverse series runs away: 100 km east of the central meridian, 10 km short of the pole's
        // northing, zone 1 band X is read thousands of degrees east. It is refused, naming the line.
        String line = "1 X 600000 9990000";
        Exception e = assertThrows(Exception.class, () -> read(line));
        assertEquals("Invalid UTM position: " + line, e.getMessage());
    }
}
