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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PositionUtils}: the static lat/lon formatting helpers, the public
 * latitude/longitude/position regular expressions and the HTML position re-assembly and
 * separator-replacement utilities.
 *
 * <p>The {@code °} degree sign is written out explicitly in all expected strings so the
 * assertions are unambiguous regardless of how the source file is encoded.
 */
public class PositionUtilsTest {

    /** Degree sign reused throughout the expected strings. */
    private static final char DEG = '°';

    private static ResourceBundle bundle;

    @BeforeAll
    public static void loadBundle() {
        // Backed by src/main/resources/defaults/base/texts/en/template.properties; Locale.ROOT so no locale-specific
        // sibling bundle is ever chosen through the default locale.
        bundle = ResourceBundle.getBundle("defaults/base/texts/en/template", Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // formatLat
    // ------------------------------------------------------------------

    @Test
    public void formatLat_positiveDecimal1_emitsNorth() {
        assertEquals("55" + DEG + " 30.0'N",
                PositionUtils.formatLat(Locale.ENGLISH, PositionFormatter.LATLON_DEC_1, 55.5));
    }

    @Test
    public void formatLat_negativeDecimal1_emitsSouth() {
        // Negative latitude selects the second DIR token ("S").
        assertEquals("55" + DEG + " 30.0'S",
                PositionUtils.formatLat(Locale.ENGLISH, PositionFormatter.LATLON_DEC_1, -55.5));
    }

    @Test
    public void formatLat_zero_emitsNorthBecauseZeroIsNonNegative() {
        // value >= 0.0 -> signIndex 0 -> North even for exactly 0.
        assertEquals("00" + DEG + " 00.0'N",
                PositionUtils.formatLat(Locale.ENGLISH, PositionFormatter.LATLON_DEC_1, 0.0));
    }

    @Test
    public void formatLat_decimal3_usesThreeMinuteDecimals() {
        assertEquals("55" + DEG + " 13.266'N",
                PositionUtils.formatLat(Locale.ENGLISH, PositionFormatter.LATLON_DEC_3, 55.2211));
    }

    @Test
    public void formatLat_danishLocaleUsesCommaDecimalSeparator() {
        // The locale only governs the decimal separator of the minutes part.
        assertEquals("55" + DEG + " 30,0'N",
                PositionUtils.formatLat(new Locale("da"), PositionFormatter.LATLON_DEC_1, 55.5));
    }

    // ------------------------------------------------------------------
    // formatLon
    // ------------------------------------------------------------------

    @Test
    public void formatLon_positiveDecimal1_emitsEastAndThreeDegreeDigits() {
        assertEquals("012" + DEG + " 15.0'E",
                PositionUtils.formatLon(Locale.ENGLISH, PositionFormatter.LATLON_DEC_1, 12.25));
    }

    @Test
    public void formatLon_negativeDecimal1_emitsWest() {
        assertEquals("012" + DEG + " 15.0'W",
                PositionUtils.formatLon(Locale.ENGLISH, PositionFormatter.LATLON_DEC_1, -12.25));
    }

    @Test
    public void formatLon_navtex1_usesDashSeparator() {
        assertEquals("012-15.0E",
                PositionUtils.formatLon(Locale.ENGLISH, PositionFormatter.LATLON_NAVTEX_1, 12.25));
    }

    // ------------------------------------------------------------------
    // getAudioFormat
    // ------------------------------------------------------------------

    @Test
    public void getAudioFormat_latFormatProducesSpokenLatitude() {
        PositionFormatter.Format fmt = PositionUtils.getAudioFormat(bundle, 1);
        assertEquals("56 degrees 30.0 min. North",
                PositionFormatter.format(Locale.ENGLISH, fmt.getLatFormat(), 56.5));
    }

    @Test
    public void getAudioFormat_lonFormatProducesSpokenLongitude() {
        PositionFormatter.Format fmt = PositionUtils.getAudioFormat(bundle, 1);
        assertEquals("12 degrees 15.0 min. East",
                PositionFormatter.format(Locale.ENGLISH, fmt.getLonFormat(), 12.25));
    }

    @Test
    public void getAudioFormat_negativeLatitudeSpokenAsSouth() {
        PositionFormatter.Format fmt = PositionUtils.getAudioFormat(bundle, 1);
        assertEquals("56 degrees 30.0 min. South",
                PositionFormatter.format(Locale.ENGLISH, fmt.getLatFormat(), -56.5));
    }

    @Test
    public void getAudioFormat_zeroDecimalsRoundsMinutesToInteger() {
        PositionFormatter.Format fmt = PositionUtils.getAudioFormat(bundle, 0);
        assertEquals("56 degrees 30 min. North",
                PositionFormatter.format(Locale.ENGLISH, fmt.getLatFormat(), 56.5));
    }

    @Test
    public void getAudioFormat_twoDecimalsKeepsTwoMinuteDecimals() {
        PositionFormatter.Format fmt = PositionUtils.getAudioFormat(bundle, 2);
        assertEquals("56 degrees 30.00 min. North",
                PositionFormatter.format(Locale.ENGLISH, fmt.getLatFormat(), 56.5));
    }

    // ------------------------------------------------------------------
    // LATITUDE_PATTERN
    // ------------------------------------------------------------------

    @Test
    public void latitudePattern_matchesNamedGroups() {
        Matcher m = PositionUtils.LATITUDE_PATTERN.matcher("56" + DEG + " 53,195'N");
        assertTrue(m.find());
        assertEquals("56", m.group("latDegs"));
        assertEquals("53,195", m.group("latMin"));
        assertEquals("N", m.group("latDir"));
    }

    @Test
    public void latitudePattern_matchesWithoutMinutes() {
        // The minutes group is optional.
        Matcher m = PositionUtils.LATITUDE_PATTERN.matcher("56" + DEG + "S");
        assertTrue(m.find());
        assertEquals("56", m.group("latDegs"));
        assertNull(m.group("latMin"));
        assertEquals("S", m.group("latDir"));
    }

    @Test
    public void latitudePattern_doesNotMatchLongitudeDirection() {
        // A trailing E/W is not a valid latitude direction.
        Matcher m = PositionUtils.LATITUDE_PATTERN.matcher("56" + DEG + " 53,195'E");
        assertFalse(m.find());
    }

    // ------------------------------------------------------------------
    // LONGITUDE_PATTERN
    // ------------------------------------------------------------------

    @Test
    public void longitudePattern_matchesNamedGroups() {
        Matcher m = PositionUtils.LONGITUDE_PATTERN.matcher("009" + DEG + " 02,501'E");
        assertTrue(m.find());
        assertEquals("009", m.group("lonDegs"));
        assertEquals("02,501", m.group("lonMin"));
        assertEquals("E", m.group("lonDir"));
    }

    @Test
    public void longitudePattern_doesNotMatchLatitudeDirection() {
        Matcher m = PositionUtils.LONGITUDE_PATTERN.matcher("009" + DEG + " 02,501'N");
        assertFalse(m.find());
    }

    // ------------------------------------------------------------------
    // POSITION_PATTERN
    // ------------------------------------------------------------------

    @Test
    public void positionPattern_matchesFullPositionWithAllNamedGroups() {
        Matcher m = PositionUtils.POSITION_PATTERN.matcher(
                "56" + DEG + " 53,195'N - 009" + DEG + " 02,501'E");
        assertTrue(m.find());
        assertEquals("56", m.group("latDegs"));
        assertEquals("53,195", m.group("latMin"));
        assertEquals("N", m.group("latDir"));
        assertEquals("009", m.group("lonDegs"));
        assertEquals("02,501", m.group("lonMin"));
        assertEquals("E", m.group("lonDir"));
        assertEquals(" - ", m.group("separator"));
    }

    @Test
    public void positionPattern_latAndLonGroupsCaptureWholePositions() {
        Matcher m = PositionUtils.POSITION_PATTERN.matcher(
                "56" + DEG + " 53,195'N - 009" + DEG + " 02,501'E");
        assertTrue(m.find());
        assertEquals("56" + DEG + " 53,195'N", m.group("lat"));
        assertEquals("009" + DEG + " 02,501'E", m.group("lon"));
    }

    @Test
    public void positionPattern_requiresDashSeparatorBetweenPositions() {
        // Only a space, no " - " dash separator -> no full position match.
        Matcher m = PositionUtils.POSITION_PATTERN.matcher(
                "56" + DEG + " 53,195'N 009" + DEG + " 02,501'E");
        assertFalse(m.find());
    }

    // ------------------------------------------------------------------
    // updatePositionFormat
    // ------------------------------------------------------------------

    @Test
    public void updatePositionFormat_navtexAssemblerRewritesBothPositions() {
        assertEquals("56-53.195N - 009-02.501E",
                PositionUtils.updatePositionFormat(
                        "56" + DEG + " 53,195'N - 009" + DEG + " 02,501'E",
                        PositionAssembler.newNavtexPositionAssembler()));
    }

    @Test
    public void updatePositionFormat_navtexAssemblerNormalisesCommaToPeriod() {
        // A single latitude: comma decimal separator becomes a period in NAVTEX form.
        assertEquals("56-53.195N",
                PositionUtils.updatePositionFormat(
                        "56" + DEG + " 53,195'N",
                        PositionAssembler.newNavtexPositionAssembler()));
    }

    @Test
    public void updatePositionFormat_leavesSurroundingTextIntact() {
        assertEquals("pos 56-53.195N here",
                PositionUtils.updatePositionFormat(
                        "pos 56" + DEG + " 53,195'N here",
                        PositionAssembler.newNavtexPositionAssembler()));
    }

    @Test
    public void updatePositionFormat_audioAssemblerUsesSpokenWords() {
        assertEquals("56 degrees 53.195 min. North",
                PositionUtils.updatePositionFormat(
                        "56" + DEG + " 53,195'N",
                        PositionAssembler.newAudioPositionAssembler(Locale.ENGLISH, bundle)));
    }

    @Test
    public void updatePositionFormat_nullReturnsNull() {
        assertNull(PositionUtils.updatePositionFormat(null,
                PositionAssembler.newNavtexPositionAssembler()));
    }

    @Test
    public void updatePositionFormat_blankReturnedUnchanged() {
        // StringUtils.isBlank short-circuits and returns the input verbatim.
        assertEquals("   ",
                PositionUtils.updatePositionFormat("   ",
                        PositionAssembler.newNavtexPositionAssembler()));
    }

    @Test
    public void updatePositionFormat_textWithoutPositionsLeftUnchanged() {
        assertEquals("no coordinates here",
                PositionUtils.updatePositionFormat("no coordinates here",
                        PositionAssembler.newNavtexPositionAssembler()));
    }

    // ------------------------------------------------------------------
    // replaceSeparator
    // ------------------------------------------------------------------

    @Test
    public void replaceSeparator_replacesDashWithSingleSpace() {
        assertEquals("56" + DEG + " 53,195'N 009" + DEG + " 02,501'E",
                PositionUtils.replaceSeparator(
                        "56" + DEG + " 53,195'N - 009" + DEG + " 02,501'E", " "));
    }

    @Test
    public void replaceSeparator_replacesDashWithComma() {
        assertEquals("56" + DEG + " 53,195'N, 009" + DEG + " 02,501'E",
                PositionUtils.replaceSeparator(
                        "56" + DEG + " 53,195'N - 009" + DEG + " 02,501'E", ", "));
    }

    @Test
    public void replaceSeparator_nullSeparatorRemovesTheSeparatorEntirely() {
        // StringUtils.defaultString(null) yields "" -> positions become adjacent.
        assertEquals("56" + DEG + " 53,195'N009" + DEG + " 02,501'E",
                PositionUtils.replaceSeparator(
                        "56" + DEG + " 53,195'N - 009" + DEG + " 02,501'E", null));
    }

    @Test
    public void replaceSeparator_nullHtmlReturnsNull() {
        assertNull(PositionUtils.replaceSeparator(null, " "));
    }

    @Test
    public void replaceSeparator_blankHtmlReturnedUnchanged() {
        assertEquals("   ", PositionUtils.replaceSeparator("   ", " "));
    }

    @Test
    public void replaceSeparator_noPositionPairLeavesTextUnchanged() {
        // A single latitude without a paired longitude does not match POSITION_PATTERN.
        assertEquals("56" + DEG + " 53,195'N",
                PositionUtils.replaceSeparator("56" + DEG + " 53,195'N", " "));
    }
}
