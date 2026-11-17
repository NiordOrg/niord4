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

package org.niord.core.aton;

import org.junit.jupiter.api.Test;
import org.niord.core.aton.LightCharacterModel.LightGroup;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for {@link LightCharacterParser}, the core (framework-neutral) parser that turns a
 * light characteristic string such as {@code "Fl(2+1)W.10s"} into a {@link LightCharacterModel}.
 * <p>
 * This is plain Java with no Quarkus dependency, so the tests run as Tier-1 JUnit 4 unit tests. Every
 * oracle below was traced against the actual regexes in the production source:
 * <ul>
 *   <li>{@code normalize()} strips every '.' that is not a decimal point between two digits, collapses
 *       whitespace and applies the textual replacements
 *       (Qk-&gt;Q, Bu-&gt;B, Bl-&gt;B, Occ-&gt;Oc, Alt-&gt;Al, AlFl-&gt;Al, Gp-&gt;"").</li>
 *   <li>{@code LIGHT_CHARACTER_GROUPS} captures phase, an optional (group) and an optional colors run.</li>
 *   <li>Elevation = {@code (\d+(\.\d+)?)m}, period = {@code (\d+(\.\d+)?)[sS]}, range = {@code (\d+(\.\d+)?)M},
 *       extracted in that order from whatever remains after the light groups, as {@code BigDecimal}.</li>
 * </ul>
 * The tests verify concrete parsed phase/color/group values, telephony (Morse) decoding, the numeric
 * elevation/period/range fields, the {@code isComposite()} logic, and the exact exception messages.
 */
public class LightCharacterParserTest {

    private LightCharacterParser parser() {
        return LightCharacterParser.getInstance();
    }

    private LightGroup onlyGroup(LightCharacterModel model) {
        List<LightGroup> groups = model.getLightGroups();
        assertEquals(1, groups.size(), "expected exactly one light group");
        return groups.get(0);
    }

    // ------------------------------------------------------------------
    // normalize()
    // ------------------------------------------------------------------

    @Test
    public void normalizeStripsDotsAndTrims() {
        // '.' removed, surrounding whitespace trimmed
        assertEquals("Iso", parser().normalize("  Iso.  "));
    }

    @Test
    public void normalizeKeepsADecimalPointBetweenTwoDigits() {
        // ATON14: the separators go, the decimal point of "2.5s" stays
        assertEquals("FlG2.5s", parser().normalize("Fl.G.2.5s"));
        assertEquals("Fl(2)W7.5s 12m 8M", parser().normalize("Fl(2)W.7.5s. 12m. 8M."));
    }

    @Test
    public void normalizeCollapsesWhitespace() {
        assertEquals("Fl W 10s", parser().normalize("Fl   W    10s"));
    }

    @Test
    public void normalizeAppliesTextualReplacements() {
        // Qk->Q, Bu->B, Bl->B, Occ->Oc, Alt->Al, AlFl->Al, Gp->""
        assertEquals("Q", parser().normalize("Qk"));
        assertEquals("Oc", parser().normalize("Occ"));
        assertEquals("Al", parser().normalize("Alt"));
        assertEquals("Al", parser().normalize("AlFl"));
        // "Gp Fl" -> "Gp" removed, then whitespace collapse leaves a leading space, then trim
        assertEquals("Fl", parser().normalize("GpFl"));
    }

    @Test
    public void normalizeBlankInputReturnsInputUnchanged() {
        // Blank short-circuits and returns the argument as-is (including null)
        assertNull(parser().normalize(null));
        assertEquals("", parser().normalize(""));
        assertEquals("   ", parser().normalize("   "));
    }

    // ------------------------------------------------------------------
    // parse() - successful phase / color / group flows
    // ------------------------------------------------------------------

    @Test
    public void parseMorseWithPeriod() throws Exception {
        LightCharacterModel model = parser().parse("Mo(U)15s");
        LightGroup g = onlyGroup(model);
        assertEquals("Mo", g.getPhase());
        // Mo => the group spec is decoded as a telephony/Morse code, not a numeric group
        assertEquals("Uniform", g.getMorseCode());
        assertFalse(g.isGrouped());
        assertTrue(g.getColors().isEmpty());
        assertTrue(g.getGroupSpec().isEmpty());
        assertEquals(new BigDecimal("15"), model.getPeriod());
        assertNull(model.getElevation());
        assertNull(model.getRange());
    }

    @Test
    public void parseIsoWithThreeColorsKeepsOrder() throws Exception {
        LightCharacterModel model = parser().parse("Iso.WRG.4s");
        LightGroup g = onlyGroup(model);
        assertEquals("Iso", g.getPhase());
        assertEquals(java.util.Arrays.asList("W", "R", "G"), g.getColors());
        assertEquals(new BigDecimal("4"), model.getPeriod());
        assertFalse(g.isGrouped());
        assertNull(g.getMorseCode());
    }

    @Test
    public void parseFixedRedNoPeriod() throws Exception {
        LightCharacterModel model = parser().parse("F.R");
        LightGroup g = onlyGroup(model);
        assertEquals("F", g.getPhase());
        assertEquals(java.util.Arrays.asList("R"), g.getColors());
        assertNull(model.getPeriod());
        assertNull(model.getElevation());
        assertNull(model.getRange());
    }

    @Test
    public void parseCompositeGroupFlash() throws Exception {
        LightCharacterModel model = parser().parse("Fl(2+1)W.10s");
        LightGroup g = onlyGroup(model);
        assertEquals("Fl", g.getPhase());
        assertTrue(g.isGrouped());
        assertEquals(java.util.Arrays.asList(2, 1), g.getGroupSpec());
        assertEquals(java.util.Arrays.asList("W"), g.getColors());
        assertEquals(new BigDecimal("10"), model.getPeriod());
        // grouped && groupSpec.size() > 1
        assertTrue(g.isComposite());
        assertNull(g.getMorseCode());
    }

    @Test
    public void parseQuickWithNoColor() throws Exception {
        LightCharacterModel model = parser().parse("Q.5s");
        LightGroup g = onlyGroup(model);
        assertEquals("Q", g.getPhase());
        assertTrue(g.getColors().isEmpty(), "Q has no color; colors run is optional");
        assertEquals(new BigDecimal("5"), model.getPeriod());
    }

    @Test
    public void parseSingleNumberGroupIsNotComposite() throws Exception {
        // Group with a single element: grouped is true, but isComposite requires size > 1
        LightCharacterModel model = parser().parse("Fl(3)W.6s");
        LightGroup g = onlyGroup(model);
        assertEquals("Fl", g.getPhase());
        assertTrue(g.isGrouped());
        assertEquals(java.util.Arrays.asList(3), g.getGroupSpec());
        assertFalse(g.isComposite(), "single-element groupSpec is not composite");
        assertEquals(new BigDecimal("6"), model.getPeriod());
    }

    @Test
    public void parseOcclusionNormalizedFromOcc() throws Exception {
        // "Occ" -> normalized to "Oc" which is a valid phase
        LightCharacterModel model = parser().parse("Occ.W.8s");
        LightGroup g = onlyGroup(model);
        assertEquals("Oc", g.getPhase());
        assertEquals(java.util.Arrays.asList("W"), g.getColors());
        assertEquals(new BigDecimal("8"), model.getPeriod());
    }

    // ------------------------------------------------------------------
    // parse() - elevation / period / range extraction
    // ------------------------------------------------------------------

    @Test
    public void parseElevationAndRangeWithoutPeriod() throws Exception {
        // "15m" -> elevation, "10M" -> range; no seconds means period stays null
        LightCharacterModel model = parser().parse("Fl(3)W15m10M");
        LightGroup g = onlyGroup(model);
        assertEquals("Fl", g.getPhase());
        assertEquals(new BigDecimal("15"), model.getElevation());
        assertEquals(new BigDecimal("10"), model.getRange());
        assertNull(model.getPeriod());
    }

    @Test
    public void parseElevationPeriodAndRangeTogether() throws Exception {
        // Remainder "10s12m8M": elevation regex (\d+)m matches "12m", period (\d+)s matches "10s",
        // range (\d+)M matches "8M".
        LightCharacterModel model = parser().parse("Fl.W.10s12m8M");
        LightGroup g = onlyGroup(model);
        assertEquals("Fl", g.getPhase());
        assertEquals(java.util.Arrays.asList("W"), g.getColors());
        assertEquals(new BigDecimal("12"), model.getElevation());
        assertEquals(new BigDecimal("10"), model.getPeriod());
        assertEquals(new BigDecimal("8"), model.getRange());
    }

    @Test
    public void parseUppercasePeriodSecondsAccepted() throws Exception {
        // PERIOD pattern ends in [sS]; an uppercase 'S' is also matched
        LightCharacterModel model = parser().parse("Fl.W.7S");
        assertEquals("Fl", onlyGroup(model).getPhase());
        assertEquals(new BigDecimal("7"), model.getPeriod());
    }

    // ------------------------------------------------------------------
    // parse() - error scenarios
    // ------------------------------------------------------------------

    @Test
    public void parseNullThrowsBlankLightCharacter() {
        try {
            parser().parse(null);
            fail("expected exception for null input");
        } catch (Exception e) {
            assertEquals("Blank light character", e.getMessage());
        }
    }

    @Test
    public void parseEmptyThrowsBlankLightCharacter() {
        try {
            parser().parse("");
            fail("expected exception for empty input");
        } catch (Exception e) {
            assertEquals("Blank light character", e.getMessage());
        }
    }

    @Test
    public void parseWhitespaceThrowsBlankLightCharacter() {
        try {
            parser().parse("   ");
            fail("expected exception for whitespace-only input");
        } catch (Exception e) {
            assertEquals("Blank light character", e.getMessage());
        }
    }

    @Test
    public void parseUnrecognizedPhaseThrowsInvalidFormat() {
        try {
            parser().parse("XYZ");
            fail("expected exception for unrecognized phase");
        } catch (Exception e) {
            assertNotNull(e.getMessage());
            assertTrue(e.getMessage().startsWith("Invalid light character format"),
                    "message should start with 'Invalid light character format', was: " + e.getMessage());
            // No "Unknown part" because no light group was ever created
            assertFalse(e.getMessage().contains("Unknown part"));
        }
    }

    @Test
    public void parseUnparsableTrailingRemainderThrowsUnknownPart() {
        try {
            // "FlZ": Fl matches as a phase, 'Z' is not a color/elevation/period/range so it is left over
            parser().parse("FlZ");
            fail("expected exception for unparsable trailing remainder");
        } catch (Exception e) {
            assertNotNull(e.getMessage());
            assertTrue(e.getMessage().contains("Unknown part"),
                    "message should mention the unknown part, was: " + e.getMessage());
            assertTrue(e.getMessage().contains("Z"));
        }
    }

    // ------------------------------------------------------------------
    // getTelephonyCode(char)
    // ------------------------------------------------------------------

    @Test
    public void telephonyCodeForLetterIsCaseInsensitive() {
        assertEquals("Alpha", parser().getTelephonyCode('A'));
        assertEquals("Alpha", parser().getTelephonyCode('a'));
    }

    @Test
    public void telephonyCodeForDigits() {
        assertEquals("One", parser().getTelephonyCode('1'));
        assertEquals("Zero", parser().getTelephonyCode('0'));
        assertEquals("Nine", parser().getTelephonyCode('9'));
    }

    @Test
    public void telephonyCodeForInvalidCharIsEmpty() {
        assertEquals("", parser().getTelephonyCode('!'));
        assertEquals("", parser().getTelephonyCode(' '));
    }

    // ------------------------------------------------------------------
    // getTelephonyCode(String)
    // ------------------------------------------------------------------

    @Test
    public void telephonyCodeForStringJoinsWithDash() {
        assertEquals("Alpha-Bravo", parser().getTelephonyCode("AB"));
        assertEquals("Alpha-One", parser().getTelephonyCode("A1"));
    }

    @Test
    public void telephonyCodeForStringWithInvalidCharProducesEmptySegment() {
        // 'A' -> "Alpha", '!' -> "" ; joined with '-' yields a trailing empty segment
        assertEquals("Alpha-", parser().getTelephonyCode("A!"));
    }

    @Test
    public void telephonyCodeForEmptyStringIsEmpty() {
        assertEquals("", parser().getTelephonyCode(""));
    }

    // ------------------------------------------------------------------
    // telephonyCodeValid(String)
    // ------------------------------------------------------------------

    @Test
    public void telephonyCodeValidWhenAllCharsKnown() {
        assertTrue(parser().telephonyCodeValid("AB1"));
    }

    @Test
    public void telephonyCodeValidFalseWhenAnyCharUnknown() {
        assertFalse(parser().telephonyCodeValid("A!"));
    }

    @Test
    public void telephonyCodeValidEmptyStringIsTrue() {
        // allMatch over an empty stream is vacuously true
        assertTrue(parser().telephonyCodeValid(""));
    }
}
