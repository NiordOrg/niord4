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
import java.util.ResourceBundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PositionAssembler} and its two concrete implementations obtained via the
 * {@code newNavtexPositionAssembler()} and {@code newAudioPositionAssembler(Locale, ResourceBundle)}
 * factory methods.
 *
 * <p>The NAVTEX assembler produces a compact textual position (e.g. {@code 56-53.195N}), while the
 * audio assembler produces a spoken/long-form position localised via a {@link ResourceBundle} and a
 * {@link Locale} (e.g. {@code 56 degrees 53.195 min. North}).</p>
 */
public class PositionAssemblerTest {

    /** Backed by src/main/resources/defaults/base/texts/en/template.properties (Locale.ROOT: no locale fallback). */
    private static final ResourceBundle EN_BUNDLE =
            ResourceBundle.getBundle("defaults/base/texts/en/template", Locale.ROOT);

    // ------------------------------------------------------------------------
    // Factory methods
    // ------------------------------------------------------------------------

    @Test
    public void newNavtexPositionAssemblerReturnsAssembler() {
        assertNotNull(PositionAssembler.newNavtexPositionAssembler());
    }

    @Test
    public void newAudioPositionAssemblerReturnsAssembler() {
        assertNotNull(PositionAssembler.newAudioPositionAssembler(Locale.ENGLISH, EN_BUNDLE));
    }

    // ------------------------------------------------------------------------
    // NAVTEX assembler - normal flows
    // ------------------------------------------------------------------------

    @Test
    public void navtexAssemblesDegreesMinutesAndDirection() {
        PositionAssembler a = PositionAssembler.newNavtexPositionAssembler();
        assertEquals("56-53.195N", a.assemble("56", "53,195", "N"));
    }

    @Test
    public void navtexConvertsCommaDecimalSeparatorToDot() {
        // The comma in the minutes part is always normalised to an English '.' separator.
        PositionAssembler a = PositionAssembler.newNavtexPositionAssembler();
        assertEquals("12-34.567W", a.assemble("12", "34,567", "W"));
    }

    @Test
    public void navtexKeepsExistingDotDecimalSeparator() {
        PositionAssembler a = PositionAssembler.newNavtexPositionAssembler();
        assertEquals("56-53.195N", a.assemble("56", "53.195", "N"));
    }

    @Test
    public void navtexWithoutMinutesOmitsMinutesPart() {
        // minutes == null -> no "-minutes" segment, just degrees + direction.
        PositionAssembler a = PositionAssembler.newNavtexPositionAssembler();
        assertEquals("009E", a.assemble("009", null, "E"));
    }

    @Test
    public void navtexWithBlankMinutesOmitsMinutesPart() {
        // Blank (whitespace) minutes are treated as "no minutes".
        PositionAssembler a = PositionAssembler.newNavtexPositionAssembler();
        assertEquals("009E", a.assemble("009", "   ", "E"));
    }

    @Test
    public void navtexWithEmptyMinutesOmitsMinutesPart() {
        PositionAssembler a = PositionAssembler.newNavtexPositionAssembler();
        assertEquals("009E", a.assemble("009", "", "E"));
    }

    @Test
    public void navtexSouthernDirection() {
        PositionAssembler a = PositionAssembler.newNavtexPositionAssembler();
        assertEquals("01-02.030S", a.assemble("01", "02,030", "S"));
    }

    // ------------------------------------------------------------------------
    // NAVTEX assembler - edge / negative cases
    // ------------------------------------------------------------------------

    @Test
    public void navtexWithEmptyDegreesReturnsEmptyString() {
        PositionAssembler a = PositionAssembler.newNavtexPositionAssembler();
        assertEquals("", a.assemble("", "53,195", "N"));
    }

    @Test
    public void navtexWithBlankDegreesReturnsEmptyString() {
        PositionAssembler a = PositionAssembler.newNavtexPositionAssembler();
        assertEquals("", a.assemble(" ", "53,195", "N"));
    }

    @Test
    public void navtexWithNullDegreesReturnsEmptyString() {
        // StringUtils.isBlank(null) == true, so a null degrees short-circuits to "".
        PositionAssembler a = PositionAssembler.newNavtexPositionAssembler();
        assertEquals("", a.assemble(null, "53,195", "N"));
    }

    @Test
    public void navtexWithNullDirectionAppendsLiteralNull_documentsBehavior() {
        // A null direction is appended verbatim by StringBuilder, yielding the literal text "null".
        // This is a latent edge case: callers are expected to always supply a direction.
        PositionAssembler a = PositionAssembler.newNavtexPositionAssembler();
        assertEquals("56-53.195null", a.assemble("56", "53,195", null));
    }

    @Test
    public void navtexConvertsAllCommasInMinutes() {
        // replace(",", ".") replaces every comma, not just the first.
        PositionAssembler a = PositionAssembler.newNavtexPositionAssembler();
        assertEquals("56-1.2.3N", a.assemble("56", "1,2,3", "N"));
    }

    // ------------------------------------------------------------------------
    // Audio assembler (English) - normal flows
    // ------------------------------------------------------------------------

    @Test
    public void audioEnglishAssemblesFullPosition() {
        PositionAssembler a = PositionAssembler.newAudioPositionAssembler(Locale.ENGLISH, EN_BUNDLE);
        assertEquals("56 degrees 53.195 min. North", a.assemble("56", "53.195", "N"));
    }

    @Test
    public void audioEnglishKeepsDotDecimalSeparator() {
        // English locale uses '.' as the decimal separator, so an input '.' is preserved.
        PositionAssembler a = PositionAssembler.newAudioPositionAssembler(Locale.ENGLISH, EN_BUNDLE);
        assertEquals("12 degrees 34.567 min. West", a.assemble("12", "34.567", "W"));
    }

    @Test
    public void audioEnglishConvertsCommaToDotSeparator() {
        // A comma in the input is normalised to the locale's '.' separator for English.
        PositionAssembler a = PositionAssembler.newAudioPositionAssembler(Locale.ENGLISH, EN_BUNDLE);
        assertEquals("56 degrees 53.195 min. North", a.assemble("56", "53,195", "N"));
    }

    @Test
    public void audioEnglishAllFourDirectionsAreLocalised() {
        PositionAssembler a = PositionAssembler.newAudioPositionAssembler(Locale.ENGLISH, EN_BUNDLE);
        assertEquals("10 degrees 5 min. North", a.assemble("10", "5", "N"));
        assertEquals("10 degrees 5 min. South", a.assemble("10", "5", "S"));
        assertEquals("10 degrees 5 min. East", a.assemble("10", "5", "E"));
        assertEquals("10 degrees 5 min. West", a.assemble("10", "5", "W"));
    }

    @Test
    public void audioEnglishWithoutMinutesOmitsMinutesPart() {
        // null minutes -> "<deg> degrees <direction>" with no "min." segment.
        PositionAssembler a = PositionAssembler.newAudioPositionAssembler(Locale.ENGLISH, EN_BUNDLE);
        assertEquals("009 degrees East", a.assemble("009", null, "E"));
    }

    @Test
    public void audioEnglishWithBlankMinutesOmitsMinutesPart() {
        PositionAssembler a = PositionAssembler.newAudioPositionAssembler(Locale.ENGLISH, EN_BUNDLE);
        assertEquals("009 degrees East", a.assemble("009", "  ", "E"));
    }

    // ------------------------------------------------------------------------
    // Audio assembler (English) - unknown / null direction fallback
    // ------------------------------------------------------------------------

    @Test
    public void audioEnglishUnknownDirectionFallsBackToRawDirection() {
        // An unmapped direction code falls back to the supplied raw direction string.
        PositionAssembler a = PositionAssembler.newAudioPositionAssembler(Locale.ENGLISH, EN_BUNDLE);
        String result = a.assemble("10", "5", "X");
        assertEquals("10 degrees 5 min. X", result);
        assertTrue(result.endsWith("X"));
    }

    @Test
    public void audioEnglishLowercaseDirectionIsNotMappedAndFallsBack() {
        // The direction map is keyed by upper-case codes only; "n" is not mapped.
        PositionAssembler a = PositionAssembler.newAudioPositionAssembler(Locale.ENGLISH, EN_BUNDLE);
        assertEquals("10 degrees 5 min. n", a.assemble("10", "5", "n"));
    }

    @Test
    public void audioEnglishNullDirectionFallsBackToLiteralNull_documentsBehavior() {
        // directions.get(null) is null and StringUtils.defaultIfBlank(null, null) returns null,
        // so StringBuilder appends the literal text "null". Callers are expected to supply a direction.
        PositionAssembler a = PositionAssembler.newAudioPositionAssembler(Locale.ENGLISH, EN_BUNDLE);
        assertEquals("10 degrees 5 min. null", a.assemble("10", "5", null));
    }

    // ------------------------------------------------------------------------
    // Audio assembler (English) - empty/blank degrees
    // ------------------------------------------------------------------------

    @Test
    public void audioEnglishWithEmptyDegreesReturnsEmptyString() {
        PositionAssembler a = PositionAssembler.newAudioPositionAssembler(Locale.ENGLISH, EN_BUNDLE);
        assertEquals("", a.assemble("", "5", "N"));
    }

    @Test
    public void audioEnglishWithBlankDegreesReturnsEmptyString() {
        PositionAssembler a = PositionAssembler.newAudioPositionAssembler(Locale.ENGLISH, EN_BUNDLE);
        assertEquals("", a.assemble("  ", "5", "N"));
    }

    @Test
    public void audioEnglishWithNullDegreesReturnsEmptyString() {
        PositionAssembler a = PositionAssembler.newAudioPositionAssembler(Locale.ENGLISH, EN_BUNDLE);
        assertEquals("", a.assemble(null, "5", "N"));
    }

    // ------------------------------------------------------------------------
    // Audio assembler (Danish locale) - locale-specific decimal separator
    // ------------------------------------------------------------------------

    @Test
    public void audioDanishLocaleUsesCommaDecimalSeparator() {
        // For a Danish locale the decimal separator is ',', so an input '.' becomes ',' in the output.
        // Direction names still come from the (English) bundle, so "North" is unchanged.
        Locale danish = new Locale("da", "DK");
        PositionAssembler a = PositionAssembler.newAudioPositionAssembler(danish, EN_BUNDLE);
        String result = a.assemble("56", "53.195", "N");
        assertTrue(result.contains("53,195"), "Expected comma decimal separator, was: " + result);
        assertEquals("56 degrees 53,195 min. North", result);
    }

    @Test
    public void audioDanishLocaleConvertsExistingCommaToComma() {
        // A comma input stays a comma under the Danish locale.
        Locale danish = new Locale("da", "DK");
        PositionAssembler a = PositionAssembler.newAudioPositionAssembler(danish, EN_BUNDLE);
        assertEquals("56 degrees 53,195 min. North", a.assemble("56", "53,195", "N"));
    }
}
