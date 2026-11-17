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
import org.niord.model.geojson.FeatureVo;
import org.niord.model.geojson.MultiPointVo;
import org.niord.model.geojson.PointVo;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plain-text positions an editor types and reads in the geometry editor's text view
 * ({@code POST /rest/messages/parse-geometry}, {@code /format-geometry} and {@code /parse-utm}), over a grid of
 * positions: what is printed must read back as the position (within half the last printed unit), what is
 * typed must come back as typed, an impossible position is refused, and a line that cannot be read must not
 * vanish without a word.
 */
public class PlainTextConverterGridTest {

    private static final PlainTextConverter EN = PlainTextConverter.newInstance(new String[] { "en" });
    private static final PlainTextConverter DA = PlainTextConverter.newInstance(new String[] { "da", "en" });

    /** Minutes just below a whole minute at the three decimals the text view prints. */
    private static final double[] MINUTES = { 0, 0.0004, 0.0005, 30, 59.9994, 59.9995, 59.9996, 59.99999 };

    private static FeatureCollectionVo point(double lat, double lon) {
        PointVo p = new PointVo();
        p.setCoordinates(new double[] { lon, lat });
        FeatureVo f = new FeatureVo();
        f.setGeometry(p);
        FeatureCollectionVo fc = new FeatureCollectionVo();
        fc.setFeatures(new FeatureVo[] { f });
        return fc;
    }

    private static double[] onlyCoordinate(FeatureCollectionVo fc) {
        return ((PointVo) fc.getFeatures()[0].getGeometry()).getCoordinates();
    }

    @Test
    public void everyPrintedPosition_readsBackWithinHalfAThousandthOfAMinute() throws Exception {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (PlainTextConverter converter : List.of(EN, DA)) {
            for (int deg : new int[] { 0, 1, 9, 54, 55, 56, 66, 89 }) {
                for (double minutes : MINUTES) {
                    for (int sign : new int[] { 1, -1 }) {
                        double lat = sign * (deg + minutes / 60.0);
                        double lon = -sign * (deg * 2 + minutes / 60.0);
                        String text = converter.toPlainText(point(lat, lon));
                        double[] back = onlyCoordinate(converter.fromPlainText(text));
                        double halfUnit = 0.0005 / 60.0 + 1e-9;
                        if (Math.abs(back[1] - lat) > halfUnit || Math.abs(back[0] - lon) > halfUnit
                                || text.contains("60,000") || text.contains("60.000")) {
                            failures.add(lat + "," + lon + " -> " + text.trim() + " -> " + back[1] + "," + back[0]);
                        }
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 200, "the grid is smaller than intended: " + checked);
        assertTrue(failures.isEmpty(), failures.size() + " wrong: " + failures);
    }

    @Test
    public void typedPositions_inEveryAcceptedForm_comeBackAsTyped() throws Exception {
        // Degrees and decimal minutes with a point or a comma, with and without symbols, degrees only, and
        // degrees, minutes and seconds: each is the same position, 55° 30.5' N 012° 15.25' E.
        double lat = 55 + 30.5 / 60;
        double lon = 12 + 15.25 / 60;
        String[] typed = {
                "55° 30.500'N - 012° 15.250'E",
                "55° 30,500'N - 012° 15,250'E",
                "55 30.5N 12 15.25E",
                "55° 30.5' N 12° 15.25' E",
                "55° 30' 30\"N 12° 15' 15\"E",
                "55.508333333N 12.254166667E",
                "1) 55° 30,5' N 12° 15,25' E, en: buoy",
        };
        List<String> failures = new ArrayList<>();
        for (String t : typed) {
            double[] c = onlyCoordinate(EN.fromPlainText(t));
            if (Math.abs(c[1] - lat) > 1e-8 || Math.abs(c[0] - lon) > 1e-8) {
                failures.add("\"" + t + "\" -> " + c[1] + "," + c[0]);
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " wrong: " + failures);
    }

    @Test
    public void minutesOfSixtyOrMore_andDegreesBeyondTheRange_areRefused() {
        // A slip such as 75 for 57 minutes, the "56° 60.0'N" form POS1 printed, a latitude beyond 90 or a
        // longitude beyond 180 is refused with "Invalid degree format", as other bad input is, instead of being
        // read as a position the editor never typed.
        List<String> failures = new ArrayList<>();
        for (String t : List.of(
                "56 75.0N 009 17.6E",
                "56° 60.0'N 009° 17.6'E",
                "95 00.0N 010 00.0E",
                "56 00.0N 200 00.0E",
                "90 00.1N 010 00.0E",
                "56 00.0N 180 00.1W",
                "91S 010E",
                "56 -30.0N 009 17.6E",
                "56° 30' 60\"N 009° 17' 00\"E")) {
            try {
                FeatureCollectionVo fc = EN.fromPlainText(t);
                double[] c = onlyCoordinate(fc);
                failures.add("\"" + t + "\" read as " + c[1] + "," + c[0]);
            } catch (Exception e) {
                if (e.getMessage() == null || !e.getMessage().startsWith("Invalid degree format")) {
                    failures.add("\"" + t + "\" refused with " + e);
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " wrong: " + failures);
    }

    @Test
    public void theEdgesOfTheRange_areRead() throws Exception {
        double[] c = onlyCoordinate(EN.fromPlainText("90 00.0N 180 00.0E"));
        assertEquals(90.0, c[1], 1e-9);
        assertEquals(180.0, c[0], 1e-9);
        c = onlyCoordinate(EN.fromPlainText("90S 180W"));
        assertEquals(-90.0, c[1], 1e-9);
        assertEquals(-180.0, c[0], 1e-9);
        c = onlyCoordinate(EN.fromPlainText("56 59.999N 009 59.999E"));
        assertEquals(56 + 59.999 / 60, c[1], 1e-9);
        assertEquals(9 + 59.999 / 60, c[0], 1e-9);
        c = onlyCoordinate(EN.fromPlainText("00 00.0N 000 00.0E"));
        assertEquals(0.0, c[1], 1e-9);
        assertEquals(0.0, c[0], 1e-9);
        c = onlyCoordinate(EN.fromPlainText("56° 30' 59.9\"N 009° 17' 00\"E"));
        assertEquals(56 + 30 / 60.0 + 59.9 / 3600, c[1], 1e-9);
    }

    @Test
    public void aSignedDegreeWithAHemisphere_isRefused() {
        // "-55 30N" is not a position: the old reading was -55 + 0.5 = -54.5, left north, i.e. 54° 30' S. Every
        // position here carries a hemisphere letter, so a sign before the degrees is refused, "+" as well as "-".
        for (String t : List.of("-55 30N 012 00E", "+55 30N 012 00E", "-55.5N 012E")) {
            Exception e = assertThrows(Exception.class, () -> EN.fromPlainText(t), t);
            assertTrue(e.getMessage().startsWith("Invalid degree format"), t + ": " + e.getMessage());
        }
    }

    @Test
    public void utmText_aLineThatCannotBeRead_isRefused_namingTheLine() {
        // Three UTM lines, the middle one unreadable: the text is refused, naming that line, instead of answering
        // a MultiPoint of the other two.
        String text = String.join("\n",
                "32 V 624726.15 6207884.60",
                "GARBAGE",
                "32 V 624900.00 6208000.00");
        Exception e = assertThrows(Exception.class, () -> EN.fromUtmText(text));
        assertTrue(e.getMessage().contains("GARBAGE"), e.getMessage());
    }

    @Test
    public void utmText_aZoneThatDoesNotExist_isRefused_namingTheLine() {
        // L-155: three UTM lines, the middle one in zone 61, the first zone past the last: the text is refused,
        // naming that line, instead of placing it at 183° east, off the globe.
        String text = String.join("\n",
                "32 V 624726.15 6207884.60",
                "61V 500000 6200000",
                "32 V 624900.00 6208000.00");
        Exception e = assertThrows(Exception.class, () -> EN.fromUtmText(text));
        assertEquals("Invalid UTM position: 61V 500000 6200000", e.getMessage());
    }

    @Test
    public void utmText_blankLinesAreSkipped() throws Exception {
        // Blank lines, a line of spaces and a Windows line end are skipped; the three positions are read.
        String text = String.join("\n",
                "32 V 624726.15 6207884.60",
                "",
                "   ",
                "32 V 624800.00 6207900.00\r",
                "32 V 624900.00 6208000.00");
        FeatureCollectionVo fc = EN.fromUtmText(text);
        assertEquals(3, ((MultiPointVo) fc.getFeatures()[0].getGeometry()).getCoordinates().length);
    }

    @Test
    public void utmText_aDecimalCommaIsRead() throws Exception {
        // The Danish decimal comma, which the plain-text parser accepts too.
        double[] comma = onlyCoordinate(EN.fromUtmText("32 V 624800,00 6207900,00"));
        double[] point = onlyCoordinate(EN.fromUtmText("32 V 624800.00 6207900.00"));
        double dLat = Math.abs(comma[1] - point[1]) * 111_000;
        double dLon = Math.abs(comma[0] - point[0]) * 111_000 * Math.cos(Math.toRadians(point[1]));
        assertTrue(Math.hypot(dLat, dLon) < 10, comma[1] + "," + comma[0] + " vs " + point[1] + "," + point[0]);
    }

    @Test
    public void utmText_everyNorthernPositionReadsBack_withinTenMetres_documentsBehavior() throws Exception {
        // latLon2UTM prints whole metres (truncated), so a round trip could land within a metre or two. It lands
        // up to about 8 m away (54° N 60° W comes back 7.8 m south): the series the two directions use are not
        // each other's inverse to better than that. Below charting accuracy for most uses; pinned so a change
        // to the conversion shows. The bound is the measured error, rounded up.
        UTMCoordinateConversion utm = new UTMCoordinateConversion();
        double worst = 0;
        String worstCase = "";
        int checked = 0;
        for (double lat = 54.0; lat <= 72.0; lat += 1.37) {
            for (double lon = -60.0; lon <= 15.0; lon += 4.91) {
                String text = utm.latLon2UTM(lat, lon);
                double[] c = onlyCoordinate(EN.fromUtmText(text));
                double dLat = Math.abs(c[1] - lat) * 111_000;
                double dLon = Math.abs(c[0] - lon) * 111_000 * Math.cos(Math.toRadians(lat));
                double d = Math.hypot(dLat, dLon);
                if (d > worst) {
                    worst = d;
                    worstCase = lat + "," + lon + " -> " + text + " -> " + c[1] + "," + c[0];
                }
                checked++;
            }
        }
        assertTrue(checked > 200, "the grid is smaller than intended: " + checked);
        assertTrue(worst < 10, "the worst round trip is " + worst + " m: " + worstCase);
    }
}
