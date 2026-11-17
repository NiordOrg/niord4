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
package org.niord.core.util;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link PositionFormatter}.
 * <p>
 * The formatter turns a decimal coordinate into a human-readable string driven by a flexible
 * template language (DEG/MIN/SEC/DIR tokens, an optional "-F" floor flag, and optional
 * "[printf]" / "[pos,neg]" specs). The tests below pin the exact formatted output for the
 * built-in formats, the individual tokens, locale-driven decimal separators, sign handling
 * and the various edge cases of the template parser.
 * <p>
 * Determinism note: every assertion supplies an explicit {@link Locale} (or relies on the
 * documented ENGLISH default) so results never depend on the JVM's default locale. The degree
 * sign in the expected strings is the same {@code °} character used by the format constants.
 */
public class PositionFormatterTest {

    // ------------------------------------------------------------------
    // Built-in lat/lon formats via the default-format overloads
    // ------------------------------------------------------------------

    @Test
    public void format_latLon_defaultsToSecondsFormatAndLatLonTemplate() {
        // format(lat, lon) -> LATLON_SEC + "LAT LON" template
        assertEquals("55° 00' 00\"N 012° 00' 00\"E",
                PositionFormatter.format(55.0, 12.0));
    }

    @Test
    public void format_latLon_southernAndWesternHemisphere() {
        // Negative latitude -> S, negative longitude -> W; sign is dropped from the numbers.
        assertEquals("55° 00' 00\"S 012° 00' 00\"W",
                PositionFormatter.format(-55.0, -12.0));
    }

    @Test
    public void format_latLon_secondsAreFlooredNotRounded() {
        // 55.51 deg -> 30 min, 35.999... sec which floors to 35 (not rounded up to 36).
        // Longitude 12.0 stays clean.
        assertEquals("55° 30' 35\"N 012° 00' 00\"E",
                PositionFormatter.format(55.51, 12.0));
    }

    @Test
    public void format_withFormatOverload_usesGivenFormat() {
        // format(Format, lat, lon) -> uses the supplied Format with the default "LAT LON" template.
        assertEquals("55° 13.266'N 012° 30.000'E",
                PositionFormatter.format(PositionFormatter.LATLON_DEC_3, 55.2211, 12.5));
    }

    @Test
    public void format_withCustomLatLonTemplate_replacesLatAndLon() {
        // A custom template that orders/wraps the components differently.
        assertEquals("[012° 00' 00\"E / 55° 00' 00\"N]",
                PositionFormatter.format(PositionFormatter.LATLON_SEC, "[LON / LAT]", 55.0, 12.0));
    }

    @Test
    public void format_withLocaleAndFormatOverload_danishDecimalSeparator() {
        // The Danish locale formats the decimal minutes with a comma separator.
        assertEquals("55° 30,0'N 012° 30,0'E",
                PositionFormatter.format(new Locale("da"), PositionFormatter.LATLON_DEC_1, 55.5, 12.5));
    }

    // ------------------------------------------------------------------
    // Single-value format(Locale, String, double) - DEG token
    // ------------------------------------------------------------------

    @Test
    public void format_degFloored_padsToTwoDigits() {
        assertEquals("05", PositionFormatter.format(Locale.ENGLISH, "DEG-F[%02d]", 5.9));
    }

    @Test
    public void format_degNotFloored_usesProvidedPrintfFormat() {
        // Without "-F" the raw (absolute) value is used, here formatted to one decimal.
        assertEquals("5.9", PositionFormatter.format(Locale.ENGLISH, "DEG[%.1f]", 5.9));
    }

    @Test
    public void format_degNotFlooredNoSpec_usesDefaultTwoDecimals() {
        // No format spec -> default "%.2f" applies to the absolute degree value.
        assertEquals("5.90", PositionFormatter.format(Locale.ENGLISH, "DEG", 5.9));
    }

    @Test
    public void format_degFlooredNoSpec_usesDefaultTwoDigitInteger() {
        // No format spec on a floored token -> default "%02d" integer padding.
        assertEquals("07", PositionFormatter.format(Locale.ENGLISH, "DEG-F", 7.9));
    }

    @Test
    public void format_degUsesAbsoluteValue_forNegativeInput() {
        // The sign is stripped; only DIR carries hemisphere information.
        assertEquals("05", PositionFormatter.format(Locale.ENGLISH, "DEG-F[%02d]", -5.9));
    }

    // ------------------------------------------------------------------
    // MIN token
    // ------------------------------------------------------------------

    @Test
    public void format_minFloored_padsToTwoDigits() {
        // 0.51 deg -> 30.6 min -> floored to 30.
        assertEquals("30", PositionFormatter.format(Locale.ENGLISH, "MIN-F[%02d]", 55.51));
    }

    @Test
    public void format_minDecimal_threeDecimals() {
        assertEquals("13.266", PositionFormatter.format(Locale.ENGLISH, "MIN[%06.3f]", 55.2211));
    }

    // ------------------------------------------------------------------
    // SEC token
    // ------------------------------------------------------------------

    @Test
    public void format_secFloored_padsToTwoDigits() {
        // 55.51 deg -> 30 min, ~36 sec; floor() gives 35.
        assertEquals("35", PositionFormatter.format(Locale.ENGLISH, "SEC-F[%02d]", 55.51));
    }

    @Test
    public void format_secFloored_zeroForWholeDegree() {
        assertEquals("00", PositionFormatter.format(Locale.ENGLISH, "SEC-F[%02d]", 55.0));
    }

    // ------------------------------------------------------------------
    // DIR token (sign handling)
    // ------------------------------------------------------------------

    @Test
    public void format_dir_positiveValueUsesFirstEntry() {
        assertEquals("N", PositionFormatter.format(Locale.ENGLISH, "DIR[N,S]", 5.0));
    }

    @Test
    public void format_dir_negativeValueUsesSecondEntry() {
        assertEquals("S", PositionFormatter.format(Locale.ENGLISH, "DIR[N,S]", -5.0));
    }

    @Test
    public void format_dir_zeroCountsAsPositive() {
        // value >= 0.0 is treated as positive, so exactly 0.0 yields the first entry.
        assertEquals("N", PositionFormatter.format(Locale.ENGLISH, "DIR[N,S]", 0.0));
    }

    @Test
    public void format_dir_eastWestPair() {
        assertEquals("W", PositionFormatter.format(Locale.ENGLISH, "DIR[E,W]", -12.0));
    }

    @Test
    public void format_dir_indexOutOfRangeYieldsEmptyString() {
        // Negative value -> signIndex 1, but the spec has only one entry (length 1),
        // so replaceIndex returns the empty replacement.
        assertEquals("", PositionFormatter.format(Locale.ENGLISH, "DIR[N]", -1.0));
    }

    @Test
    public void format_dir_singleEntryPositiveValueUsesIt() {
        // Positive value -> signIndex 0, which is in range for a single-entry spec.
        assertEquals("N", PositionFormatter.format(Locale.ENGLISH, "DIR[N]", 1.0));
    }

    // ------------------------------------------------------------------
    // Combined / literal handling and edge cases
    // ------------------------------------------------------------------

    @Test
    public void format_literalTextAroundTokensIsPreserved() {
        // Literal characters before/after tokens survive verbatim.
        assertEquals("<05>", PositionFormatter.format(Locale.ENGLISH, "<DEG-F[%02d]>", 5.9));
    }

    @Test
    public void format_navtexLatFormat_dashSeparated() {
        // 55.512345 deg -> 30.7407 min -> "%04.1f" -> "30.7".
        assertEquals("55-30.7N",
                PositionFormatter.format(Locale.ENGLISH, PositionFormatter.LATLON_NAVTEX_1.getLatFormat(), 55.512345));
    }

    @Test
    public void format_nullLocaleDefaultsToEnglish() {
        // A null locale must behave exactly like Locale.ENGLISH (period decimal separator).
        assertEquals("5.90", PositionFormatter.format(null, "DEG", 5.9));
    }

    @Test
    public void format_noRecognisedToken_returnsTemplateUnchanged() {
        // A template with no DEG/MIN/SEC/DIR token is returned verbatim.
        assertEquals("no tokens here",
                PositionFormatter.format(Locale.ENGLISH, "no tokens here", 55.0));
    }

    @Test
    public void format_decFormatTwoDecimals_matchesLatlonDec2() {
        // Exercises the LATLON_DEC_2 latitude template end to end.
        assertEquals("55° 13.27'N",
                PositionFormatter.format(Locale.ENGLISH, PositionFormatter.LATLON_DEC_2.getLatFormat(), 55.2211));
    }

    // ------------------------------------------------------------------
    // Format value object
    // ------------------------------------------------------------------

    @Test
    public void formatObject_exposesLatAndLonTemplates() {
        PositionFormatter.Format f = new PositionFormatter.Format("LATPART", "LONPART");
        assertEquals("LATPART", f.getLatFormat());
        assertEquals("LONPART", f.getLonFormat());
    }
}
