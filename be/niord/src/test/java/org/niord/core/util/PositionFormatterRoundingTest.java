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
package org.niord.core.util;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rounding of {@link PositionFormatter} (POS1): a position is rounded once, on the smallest unit it prints, and
 * the rounding is carried into the larger units, so no format ever prints 60 minutes or 60 seconds.
 * <p>
 * Before the fix the degrees were floored and the minutes rounded on their own by their printf format, so a light
 * at 56.99940° N printed "56° 60.0'N" (and "56-60.0N", "56 degrees 60.0 min. North") instead of "57° 00.0'N".
 * <p>
 * Three kinds of test: the case POS1 was found by, for every format; a property over a grid of values (every
 * whole degree, both hemispheres, the poles, the date line and the values just below the next unit of each format),
 * which reads every printed position back; and a table of known-good strings, printed by the formatter before the
 * fix for ordinary values (none near a rounding boundary), which the fix must print unchanged
 * ({@code position-formatter-known-good.txt}).
 */
public class PositionFormatterRoundingTest {

    /** The light at Aggersund, message NW-319-26 of the test data: 56.9994° N is 56° 59.964'. */
    private static final double AGGERSUND_LAT = 56.99940309853659;
    private static final double AGGERSUND_LON = 9.294097396125087;

    private static ResourceBundle bundle;

    /** The formats a template can choose, by name: the fixed ones and the English audio format. */
    private static final Map<String, PositionFormatter.Format> FORMATS = new LinkedHashMap<>();

    @BeforeAll
    public static void loadFormats() {
        bundle = ResourceBundle.getBundle("defaults/base/texts/en/template", Locale.ROOT);
        FORMATS.put("SEC", PositionFormatter.LATLON_SEC);
        FORMATS.put("DEC_1", PositionFormatter.LATLON_DEC_1);
        FORMATS.put("DEC_2", PositionFormatter.LATLON_DEC_2);
        FORMATS.put("DEC_3", PositionFormatter.LATLON_DEC_3);
        FORMATS.put("NAVTEX_1", PositionFormatter.LATLON_NAVTEX_1);
        FORMATS.put("NAVTEX_2", PositionFormatter.LATLON_NAVTEX_2);
        FORMATS.put("NAVTEX_3", PositionFormatter.LATLON_NAVTEX_3);
        FORMATS.put("AUDIO_0", PositionUtils.getAudioFormat(bundle, 0));
        FORMATS.put("AUDIO_1", PositionUtils.getAudioFormat(bundle, 1));
        FORMATS.put("AUDIO_2", PositionUtils.getAudioFormat(bundle, 2));
    }

    // ------------------------------------------------------------------
    // The case POS1 was found by
    // ------------------------------------------------------------------

    @Test
    public void aggersundLight_carriesTheMinutesIntoTheDegrees_inEveryFormat() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("SEC", "56° 59' 57\"N 009° 17' 38\"E");
        expected.put("DEC_1", "57° 00.0'N 009° 17.6'E");
        expected.put("DEC_2", "56° 59.96'N 009° 17.65'E");
        expected.put("DEC_3", "56° 59.964'N 009° 17.646'E");
        expected.put("NAVTEX_1", "57-00.0N 009-17.6E");
        expected.put("NAVTEX_2", "56-59.96N 009-17.65E");
        expected.put("NAVTEX_3", "56-59.964N 009-17.646E");
        expected.put("AUDIO_0", "57 degrees 0 min. North 9 degrees 18 min. East");
        expected.put("AUDIO_1", "57 degrees 0.0 min. North 9 degrees 17.6 min. East");
        expected.put("AUDIO_2", "56 degrees 59.96 min. North 9 degrees 17.65 min. East");
        for (Map.Entry<String, String> e : expected.entrySet()) {
            assertEquals(e.getValue(),
                    PositionFormatter.format(Locale.ENGLISH, FORMATS.get(e.getKey()), AGGERSUND_LAT, AGGERSUND_LON),
                    e.getKey());
        }
    }

    @Test
    public void aggersundLight_danishDetailsText() {
        assertEquals("57° 00,0'N - 009° 17,6'E",
                PositionFormatter.format(Locale.forLanguageTag("da"), PositionFormatter.LATLON_DEC_1,
                        "LAT - LON", AGGERSUND_LAT, AGGERSUND_LON));
    }

    @Test
    public void southernAndWesternHemisphere_carryLikeTheNorthernAndEastern() {
        assertEquals("57° 00.0'S 010° 00.0'W",
                PositionFormatter.format(Locale.ENGLISH, PositionFormatter.LATLON_DEC_1,
                        -(56 + 59.96 / 60), -(9 + 59.97 / 60)));
        assertEquals("57-00.00S 010-00.00W",
                PositionFormatter.format(Locale.ENGLISH, PositionFormatter.LATLON_NAVTEX_2,
                        -(56 + 59.996 / 60), -(9 + 59.9999 / 60)));
    }

    @Test
    public void poleAndDateLine_carryUpToNinetyAndOneHundredAndEighty() {
        assertEquals("90° 00.0'N 180° 00.0'E",
                PositionFormatter.format(Locale.ENGLISH, PositionFormatter.LATLON_DEC_1, 89.99999, 179.99999));
        assertEquals("90° 00.000'S 180° 00.000'W",
                PositionFormatter.format(Locale.ENGLISH, PositionFormatter.LATLON_DEC_3, -89.9999999, -179.9999999));
    }

    @Test
    public void justBelowZero_keepsItsHemisphere() {
        // A value that rounds to zero is printed with the side it lies on, as before the fix and as the
        // administration app prints it: -0.0004° is 0.024' south of the equator.
        assertEquals("00° 00.0'S 000° 00.0'W",
                PositionFormatter.format(Locale.ENGLISH, PositionFormatter.LATLON_DEC_1, -0.0004, -0.0004));
        assertEquals("00° 00.024'S",
                PositionFormatter.format(Locale.ENGLISH, PositionFormatter.LATLON_DEC_3.getLatFormat(), -0.0004));
        assertEquals("00° 00.0'N",
                PositionFormatter.format(Locale.ENGLISH, PositionFormatter.LATLON_DEC_1.getLatFormat(), 0.0));
    }

    @Test
    public void secondsWithDecimals_carryIntoTheMinutesAndTheDegrees() {
        // The template language allows rounded seconds; they carry like rounded minutes.
        String format = "DEG-F[%02d]° MIN-F[%02d]' SEC[%04.1f]\"DIR[N,S]";
        assertEquals("57° 00' 00.0\"N", PositionFormatter.format(Locale.ENGLISH, format, 56 + 59 / 60.0 + 59.96 / 3600));
        assertEquals("56° 59' 59.9\"N", PositionFormatter.format(Locale.ENGLISH, format, 56 + 59 / 60.0 + 59.94 / 3600));
        assertEquals("56° 31' 00.0\"N", PositionFormatter.format(Locale.ENGLISH, format, 56 + 30 / 60.0 + 59.97 / 3600));
    }

    @Test
    public void decimalDegrees_roundAsBefore() {
        assertEquals("57.00", PositionFormatter.format(Locale.ENGLISH, "DEG", 56.999));
        assertEquals("56.9", PositionFormatter.format(Locale.ENGLISH, "DEG[%.1f]", 56.94));
    }

    @Test
    public void decimalDegrees_besideOtherParts_areTheValueRoundedOnce() {
        // Decimal degrees print the value itself, by their own format, not a value another part rounded first:
        // 56.99499 is 56.99°, though its minutes round to 59.7' (56.995°), and 56.9496 is 56.9° at one decimal,
        // though it is 56.950° at three.
        assertEquals("56.99 / 59.7", PositionFormatter.format(Locale.ENGLISH, "DEG[%.2f] / MIN[%.1f]", 56.99499));
        assertEquals("56.9 56.950", PositionFormatter.format(Locale.ENGLISH, "DEG[%.1f] DEG[%.3f]", 56.9496));
        assertEquals("56,99 / 59,7", PositionFormatter.format(Locale.forLanguageTag("da"),"DEG[%.2f] / MIN[%.1f]", 56.99499));
    }

    // ------------------------------------------------------------------
    // The property over a grid of values
    // ------------------------------------------------------------------

    /** Minutes just below a whole minute, for every number of decimals the formats print (59.95 is the tie at 1). */
    private static final double[] MINUTES_BELOW_THE_NEXT = {
            59.94, 59.95, 59.96, 59.994, 59.995, 59.996, 59.9994, 59.9995, 59.9996, 59.999, 59.99999, 29.96, 0.04
    };
    /** Seconds just below a whole second, on the 59th minute. */
    private static final double[] SECONDS_BELOW_THE_NEXT = { 59.4, 59.5, 59.6, 59.94, 59.96, 59.999 };

    @Test
    public void everyFormattedPosition_isWellFormed_andReadsBackToItsValue() {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (boolean latitude : new boolean[] { true, false }) {
            int max = latitude ? 90 : 180;
            for (double value : gridValues(max)) {
                for (double signed : new double[] { value, -value }) {
                    for (Map.Entry<String, PositionFormatter.Format> e : FORMATS.entrySet()) {
                        String template = latitude ? e.getValue().getLatFormat() : e.getValue().getLonFormat();
                        String printed = PositionFormatter.format(Locale.ENGLISH, template, signed);
                        String problem = check(e.getKey(), latitude, signed, printed);
                        if (problem != null) {
                            failures.add(e.getKey() + " " + signed + " -> \"" + printed + "\": " + problem);
                        }
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 40_000, "the grid is smaller than intended: " + checked);
        assertTrue(failures.isEmpty(), failures.size() + " of " + checked + " positions are wrong, the first: "
                + failures.subList(0, Math.min(25, failures.size())));
    }

    /** Every whole degree up to max, with the minutes and seconds just below the next unit, plus the near-zero values. */
    private static List<Double> gridValues(int max) {
        List<Double> values = new ArrayList<>();
        for (int d = 0; d <= max; d++) {
            values.add((double) d);
            if (d == max) {
                break;
            }
            values.add(d + 0.5);
            for (double m : MINUTES_BELOW_THE_NEXT) {
                values.add(d + m / 60.0);
            }
            for (double s : SECONDS_BELOW_THE_NEXT) {
                values.add(d + 59 / 60.0 + s / 3600.0);
                values.add(d + 29 / 60.0 + s / 3600.0);
            }
        }
        values.add(0.0004);
        values.add(0.0000001);
        values.add(max - 0.0000001);
        return values;
    }

    private static final Pattern SEC = Pattern.compile("(\\d+)° (\\d{2})' (\\d{2})\"([NSEW])");
    private static final Pattern DEC = Pattern.compile("(\\d+)° (\\d{2}\\.\\d+)'([NSEW])");
    private static final Pattern NAVTEX = Pattern.compile("(\\d+)-(\\d{2}\\.\\d+)([NSEW])");
    private static final Pattern AUDIO = Pattern.compile("(\\d+) degrees (\\d+(?:\\.\\d+)?) min\\. (North|South|East|West)");

    /**
     * Checks one printed latitude or longitude: its shape, the range of each number, the hemisphere, and that reading
     * it back gives the value within half a unit of the last printed digit (or, for the floored seconds format,
     * within one unit below it). Returns the problem, or null.
     */
    private static String check(String formatName, boolean latitude, double value, String printed) {
        Matcher m;
        int decimals;
        boolean floored = false;
        double seconds = 0;
        String hemisphere;
        if (formatName.equals("SEC")) {
            m = SEC.matcher(printed);
            if (!m.matches()) return "not of the form DD° MM' SS\"X";
            seconds = Integer.parseInt(m.group(3));
            hemisphere = m.group(4);
            decimals = 0;
            floored = true;
        } else if (formatName.startsWith("DEC")) {
            m = DEC.matcher(printed);
            if (!m.matches()) return "not of the form DD° MM.m'X";
            hemisphere = m.group(3);
            decimals = Integer.parseInt(formatName.substring(4));
        } else if (formatName.startsWith("NAVTEX")) {
            m = NAVTEX.matcher(printed);
            if (!m.matches()) return "not of the form DD-MM.mX";
            hemisphere = m.group(3);
            decimals = Integer.parseInt(formatName.substring(7));
        } else {
            m = AUDIO.matcher(printed);
            if (!m.matches()) return "not of the form D degrees M min. Direction";
            hemisphere = m.group(3).substring(0, 1);
            decimals = Integer.parseInt(formatName.substring(6));
        }
        String degreeText = m.group(1);
        int degrees = Integer.parseInt(degreeText);
        String minuteText = m.group(2);
        double minutes = Double.parseDouble(minuteText);

        if (!formatName.startsWith("AUDIO") && degreeText.length() != (latitude ? 2 : 3)) {
            return "the degrees are not padded to " + (latitude ? 2 : 3) + " digits";
        }
        int fractionDigits = minuteText.contains(".") ? minuteText.length() - minuteText.indexOf('.') - 1 : 0;
        if (!formatName.equals("SEC") && fractionDigits != decimals) {
            return "the minutes have " + fractionDigits + " decimals, not " + decimals;
        }
        if (minutes >= 60) return "the minutes are 60 or more";
        if (seconds >= 60) return "the seconds are 60 or more";
        int max = latitude ? 90 : 180;
        if (degrees > max || (degrees == max && (minutes > 0 || seconds > 0))) return "beyond " + max + "°";

        String expectedHemisphere = latitude ? (value >= 0 ? "N" : "S") : (value >= 0 ? "E" : "W");
        if (!hemisphere.equals(expectedHemisphere)) return "the hemisphere is not " + expectedHemisphere;

        double readBack = degrees + minutes / 60.0 + seconds / 3600.0;
        double abs = Math.abs(value);
        double epsilon = 1e-9;
        if (floored) {
            double unit = 1 / 3600.0;
            if (readBack > abs + epsilon || abs - readBack >= unit + epsilon) {
                return "reads back as " + readBack + ", not within one second below " + abs;
            }
        } else {
            double halfUnit = 0.5 * Math.pow(10, -decimals) / 60.0;
            if (Math.abs(readBack - abs) > halfUnit + epsilon) {
                return "reads back as " + readBack + ", more than half a unit from " + abs;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Known-good strings: unchanged by the fix
    // ------------------------------------------------------------------

    /**
     * Each line of {@code position-formatter-known-good.txt} is {@code language|format|lat|lon|printed}, printed by
     * the formatter before the fix for positions not near a rounding boundary. The fix must print every one the same.
     */
    @Test
    public void ordinaryPositions_printExactlyAsBeforeTheFix() throws IOException {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        try (InputStream in = getClass().getResourceAsStream("position-formatter-known-good.txt")) {
            assertNotNull(in, "position-formatter-known-good.txt is missing");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\\|", 5);
                Locale locale = Locale.forLanguageTag(f[0]);
                PositionFormatter.Format format = FORMATS.get(f[1]);
                assertNotNull(format, "unknown format " + f[1]);
                String printed = PositionFormatter.format(locale, format,
                        Double.parseDouble(f[2]), Double.parseDouble(f[3]));
                if (!printed.equals(f[4])) {
                    failures.add(line + " -> now \"" + printed + "\"");
                }
                checked++;
            }
        }
        assertTrue(checked >= 250, "the table is shorter than intended: " + checked);
        assertTrue(failures.isEmpty(), failures.size() + " changed: " + failures);
    }
}
