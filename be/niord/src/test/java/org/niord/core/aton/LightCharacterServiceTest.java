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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure unit tests for {@link LightCharacterService}.
 *
 * <p>The two public instance methods of {@code LightCharacterService}
 * ({@code formatLightCharacter} and {@code formatTelephonyCode}) translate a light character /
 * telephony code into a localised English (or other-language) phrase by running a FreeMarker
 * template through the injected {@link org.niord.core.script.FmTemplateService} (an
 * {@code @ApplicationScoped} CDI bean that itself depends on {@code DictionaryService},
 * {@code DomainService}, {@code ScriptResourceService} and the persistence layer) and the
 * injected {@code NiordApp}. Those translations therefore cannot be exercised as a Tier-1 unit
 * test without the Quarkus container, so the "Fl R 5s -&gt; flashing red phrase",
 * "Fl(2+1) grouped phrase", "Mo(A) telephony phrase" and elevation/range-phrase scenarios are
 * deliberately omitted here and belong in an integration test.
 *
 * <p>What <em>is</em> framework-neutral and fully unit-testable is the public static
 * {@link LightCharacterService#trimResult(String)} routine that post-processes the rendered
 * template output. Its exact transformation pipeline (traced against the production source) is,
 * in order:
 * <ol>
 *   <li>{@code replaceAll("\\s+", " ")} — collapse every run of whitespace (spaces, tabs,
 *       newlines) into a single space;</li>
 *   <li>{@code replace(" ;", ";")} — drop a space that precedes a semicolon;</li>
 *   <li>{@code replace(" ,", ",")} — drop a space that precedes a comma;</li>
 *   <li>{@code replace("- ", "-")} — drop a space that follows a hyphen;</li>
 *   <li>{@code trim()} — strip leading/trailing whitespace.</li>
 * </ol>
 * Blank / {@code null} input short-circuits via {@code StringUtils.isNotBlank} and is returned
 * unchanged. Every assertion below verifies a concrete expected string, not merely non-null.
 */
public class LightCharacterServiceTest {

    // ------------------------------------------------------------------
    // trimResult() - whitespace collapsing
    // ------------------------------------------------------------------

    @Test
    public void trimResultCollapsesInternalWhitespaceRuns() {
        // Multiple spaces, a tab and a newline between words all collapse to a single space.
        assertEquals("flashing red every 5 seconds",
                LightCharacterService.trimResult("flashing   red\tevery\n5  seconds"));
    }

    @Test
    public void trimResultTrimsLeadingAndTrailingWhitespace() {
        // Surrounding whitespace is removed; the interior single spaces are preserved.
        assertEquals("two long flashes",
                LightCharacterService.trimResult("   two long flashes   "));
    }

    // ------------------------------------------------------------------
    // trimResult() - punctuation spacing fix-ups
    // ------------------------------------------------------------------

    @Test
    public void trimResultRemovesSpaceBeforeSemicolonAndComma() {
        // " ;" -> ";" and " ," -> "," ; the comma after the first clause keeps its trailing space.
        assertEquals("red, green; white",
                LightCharacterService.trimResult("red , green ; white"));
    }

    @Test
    public void trimResultRemovesSpaceAfterHyphen() {
        // "- " -> "-" turns a spaced telephony rendering "Alpha- Bravo" into "Alpha-Bravo".
        assertEquals("Alpha-Bravo-Charlie",
                LightCharacterService.trimResult("Alpha- Bravo- Charlie"));
    }

    @Test
    public void trimResultAppliesAllTransformationsTogether() {
        // Combined: whitespace runs collapse, then " ;"/" ,"/"- " fix-ups, then trim.
        // Note the order matters: whitespace collapse runs first, so "Alpha -  Bravo" becomes
        // "Alpha - Bravo", and only the "- " replacement fires (not " ,"/" ;"), giving "Alpha -Bravo".
        assertEquals("composite groups of 2 + 1 long flashes,green; Alpha -Bravo",
                LightCharacterService.trimResult(
                        "  composite groups of 2 + 1   long flashes ,green ;  Alpha -  Bravo  "));
    }

    // ------------------------------------------------------------------
    // trimResult() - blank / null short-circuit
    // ------------------------------------------------------------------

    @Test
    public void trimResultReturnsNullForNullInput() {
        // isNotBlank(null) is false -> the null argument is returned unchanged.
        assertNull(LightCharacterService.trimResult(null));
    }

    @Test
    public void trimResultReturnsBlankInputUnchanged() {
        // isNotBlank("") and isNotBlank("   ") are both false -> the (un-trimmed) input is returned.
        assertEquals("", LightCharacterService.trimResult(""));
        assertEquals("   ", LightCharacterService.trimResult("   "));
    }
}
