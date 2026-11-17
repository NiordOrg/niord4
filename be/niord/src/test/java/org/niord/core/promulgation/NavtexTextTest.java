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
package org.niord.core.promulgation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NavtexText}: the mapping into the NAVTEX alphabet (PROM16). Every result holds only letters, figures,
 * space, line break and {@code ? : ( ) . , ' = / + -}.
 */
class NavtexTextTest {

    private static void assertInAlphabet(String text) {
        assertTrue(text.codePoints().allMatch(NavtexText::inAlphabet), "outside the NAVTEX alphabet: [" + text + "]");
    }

    @Test
    void theTable_mapsEachCharacterToItsNavtexForm() {
        Map<String, String> table = Map.ofEntries(
                Map.entry("Skagen Ø", "SKAGEN OE"),
                Map.entry("Ærø", "AEROE"),
                Map.entry("Århus", "AARHUS"),
                Map.entry("Løgstør", "LOEGSTOER"),
                Map.entry("Kärnten Öl Übung", "KAERNTEN OEL UEBUNG"),
                Map.entry("Café Señor Göta Ålborg", "CAFE SENOR GOETA AALBORG"),
                Map.entry("Straße", "STRASSE"),
                Map.entry("by \"POSEIDON\"", "BY 'POSEIDON'"),
                Map.entry("by “POSEIDON” and „here“", "BY 'POSEIDON' AND 'HERE'"),
                Map.entry("by »HENRY P LADING«", "BY 'HENRY P LADING'"),
                Map.entry("it’s ‘so’ `x´", "IT'S 'SO' 'X'"),
                Map.entry("a b c d\te", "A B C D E"),
                Map.entry("10–12 — more", "10-12 - MORE"),
                Map.entry("keep clear; danger!", "KEEP CLEAR, DANGER."),
                Map.entry("[see] {this}", "(SEE) (THIS)"),
                Map.entry("buoys & beacons", "BUOYS AND BEACONS"),
                Map.entry("50% reduced", "50 PERCENT REDUCED"),
                Map.entry("54° 45,7' N", "54 45,7' N"),
                Map.entry("54º 45,7' N", "54 45,7' N"),
                Map.entry("wait…", "WAIT..."),
                Map.entry("soft­hyphen zero​width", "SOFTHYPHEN ZEROWIDTH"));
        table.forEach((in, out) -> {
            String actual = NavtexText.normalise(in);
            assertEquals(out, actual, in);
            assertInAlphabet(actual);
            assertEquals(List.of(), NavtexText.outsideAlphabet(in), in);
        });
    }

    @Test
    void aLetterTypedWithACombiningRing_isFoundAsTheComposedLetter() {
        assertEquals("AARHUS", NavtexText.normalise("Århus"));
    }

    @Test
    void lineBreaksAreKept_andACarriageReturnBecomesALineBreak() {
        assertEquals("FIRST\nSECOND\nTHIRD\n\nFIFTH", NavtexText.normalise("first\r\nsecond\rthird\n\nfifth"));
    }

    @Test
    void aLeftover_becomesASpace_andIsNamedForTheLog() {
        String text = "Call *123# or mail duty@example.org";
        assertEquals(List.of("*", "#", "@"), NavtexText.outsideAlphabet(text));
        String normalised = NavtexText.normalise(text);
        assertEquals("CALL 123 OR MAIL DUTY EXAMPLE.ORG", normalised);
        assertInAlphabet(normalised);
    }

    @Test
    void aSpaceLeftNextToALineBreak_goes() {
        assertEquals("POS 54 45,7' N\nKEEP CLEAR", NavtexText.normalise("pos 54 45,7' N °\n keep clear"));
    }

    @Test
    void aPlainNavtexText_staysUnchanged() {
        String plain = "WRECK IN POS 57-01.7N 003-30.6E.\nMARINERS ADVISED TO KEEP CLEAR.\n"
                + "(REF NW 012/26) ? : = / + -";
        assertEquals(plain, NavtexText.normalise(plain));
        assertEquals(List.of(), NavtexText.outsideAlphabet(plain));
    }

    @Test
    void normalisingTwice_givesTheSameText() {
        for (String in : List.of("Løgstør & Ærø: 50% «X»", "a  b  c", "x\r\n y ;")) {
            String once = NavtexText.normalise(in);
            assertEquals(once, NavtexText.normalise(once), in);
        }
    }

    @Test
    void nullStaysNull() {
        assertNull(NavtexText.normalise(null));
        assertEquals(List.of(), NavtexText.outsideAlphabet(null));
    }
}
