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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Additional pure-logic unit tests for {@link TextUtils}, complementing {@link TextUtilsTest}.
 *
 * <p>Where {@link TextUtilsTest} pins the common happy-path behaviour of each utility, this class
 * closes the remaining gaps: multi-line {@code removeWords} processing and its silent
 * exception-swallowing on null input / invalid regex, the exact handling of already-dotted and
 * dot-only strings in {@code trailingDot}/{@code removeTrailingDot}, the deliberate
 * <em>absence</em> of comma/newline escaping in {@code encodeCVSField} (per-field encoding only
 * doubles quotes; structural quoting is the line's job), separator-collision behaviour in
 * {@code encodeCVSLine}, and the null-ordering sign contract of {@code compareIgnoreCase}.
 * All assertions are deterministic and independent of locale, timezone or current time.
 */
public class TextUtilsPureLogicTest {

    // ------------------------------------------------------------------
    // removeWords - multi-line, normalisation and exception-swallowing
    // ------------------------------------------------------------------

    @Test
    public void removeWordsProcessesEachLineIndependentlyAndTrimsResult() {
        // Input is split on '\n'; each line has the word removed in isolation, lines are re-joined
        // with the platform line separator, and the whole result is trimmed at the end.
        assertEquals("quick" + System.lineSeparator() + "fox",
                TextUtils.removeWords("the quick\nthe fox", "the"));
    }

    @Test
    public void removeWordsCollapsesConsecutiveRemovedWordsIntoSingleGap() {
        // "the the quick": leading "the " is stripped, then the now-leading "the " is stripped,
        // leaving just the remaining word with no doubled whitespace.
        assertEquals("quick", TextUtils.removeWords("the the quick", "the"));
    }

    @Test
    public void removeWordsReplacesMiddleWordWithSingleSpace() {
        // A removed middle word collapses its surrounding whitespace down to one space.
        assertEquals("a b", TextUtils.removeWords("a the b", "the"));
    }

    @Test
    public void removeWordsReturnsInputUnchangedWhenWordsIsInvalidRegex() {
        // The words argument is interpolated directly into a regex. An unbalanced group "("
        // makes Pattern.compile throw PatternSyntaxException, which the catch block swallows,
        // returning the original text untouched. This pins the exception-swallowing contract.
        assertEquals("the quick", TextUtils.removeWords("the quick", "("));
    }

    @Test
    public void removeWordsReturnsNullWhenTextIsNull() {
        // text.split(...) throws NullPointerException on null, which is swallowed; the original
        // (null) text reference is returned.
        assertNull(TextUtils.removeWords(null, "the"));
    }

    @Test
    public void removeWordsEmptyTextReturnsEmptyString() {
        // "".split("\n") yields [""], the line is unchanged, and the trailing separator is trimmed away.
        assertEquals("", TextUtils.removeWords("", "the"));
    }

    // ------------------------------------------------------------------
    // trailingDot - already-dotted edge cases
    // ------------------------------------------------------------------

    @Test
    public void trailingDotLeavesMultipleExistingDotsUntouched() {
        // endsWith(".") is already true, so no further dot is appended - the doubled dots survive.
        assertEquals("abc..", TextUtils.trailingDot("abc.."));
    }

    @Test
    public void trailingDotPreservesInternalSpacesWhileAddingDot() {
        // Only leading/trailing whitespace is trimmed; internal spaces are preserved before the dot.
        assertEquals("a b.", TextUtils.trailingDot("a b"));
    }

    // ------------------------------------------------------------------
    // removeTrailingDot - dot-only / interspersed-whitespace edge cases
    // ------------------------------------------------------------------

    @Test
    public void removeTrailingDotReducesDotOnlyStringToEmpty() {
        // "." is not blank, so it is trimmed and the lone dot is stripped, leaving the empty string.
        assertEquals("", TextUtils.removeTrailingDot("."));
    }

    @Test
    public void removeTrailingDotStripsDotsSeparatedByWhitespace() {
        // The while-loop trims between dots, so "abc . ." has both trailing dots (and their
        // surrounding spaces) removed.
        assertEquals("abc", TextUtils.removeTrailingDot("abc . ."));
    }

    @Test
    public void removeTrailingDotLeavesInternalDotIntact() {
        // Only the trailing dot is removed; the dot inside "a.b" is not at the end and survives.
        assertEquals("a.b", TextUtils.removeTrailingDot("a.b."));
    }

    // ------------------------------------------------------------------
    // compareIgnoreCase - null ordering sign contract
    // ------------------------------------------------------------------

    @Test
    public void compareIgnoreCaseTreatsFirstNullAsGreaterRegardlessOfSecondValue() {
        // A null first argument always returns exactly +1, even when the second value is empty.
        assertEquals(1, TextUtils.compareIgnoreCase(null, ""));
    }

    @Test
    public void compareIgnoreCaseTreatsSecondNullAsSmallerRegardlessOfFirstValue() {
        // A null second argument always returns exactly -1, even when the first value is empty.
        assertEquals(-1, TextUtils.compareIgnoreCase("", null));
    }

    @Test
    public void compareIgnoreCaseIsAntisymmetricForUnequalValues() {
        // Swapping arguments flips the sign of the result for two non-null unequal values.
        assertTrue(TextUtils.compareIgnoreCase("Apple", "banana") < 0);
        assertTrue(TextUtils.compareIgnoreCase("banana", "Apple") > 0);
    }

    // ------------------------------------------------------------------
    // encodeCVSField - quote escaping only, no structural quoting
    // ------------------------------------------------------------------

    @Test
    public void encodeCVSFieldDoesNotEscapeOrQuoteEmbeddedCommas() {
        // Per-field encoding only doubles quotes; commas are left untouched at this stage
        // (structural quoting happens in encodeCVSLine). This pins the field/line responsibility split.
        assertEquals("a,b", TextUtils.encodeCVSField("a,b"));
    }

    @Test
    public void encodeCVSFieldDoesNotEscapeEmbeddedNewlines() {
        // Newlines inside a field are not modified by encodeCVSField.
        assertEquals("a\nb", TextUtils.encodeCVSField("a\nb"));
    }

    @Test
    public void encodeCVSFieldDoublesEveryEmbeddedQuote() {
        // Two adjacent quotes become four quotes (each is doubled independently).
        assertEquals("\"\"\"\"", TextUtils.encodeCVSField("\"\""));
    }

    @Test
    public void encodeCVSFieldUsesBooleanToString() {
        // Non-String objects are rendered via toString(); Boolean.TRUE.toString() == "true".
        assertEquals("true", TextUtils.encodeCVSField(Boolean.TRUE));
    }

    // ------------------------------------------------------------------
    // encodeCVSLine - separator collisions and quote escaping inside fields
    // ------------------------------------------------------------------

    @Test
    public void encodeCVSLineWrapsFieldContainingSeparatorInQuotesWithoutSplitting() {
        // A field that itself contains the separator stays a single quoted field; the separator
        // only appears un-quoted between the two distinct fields.
        assertEquals("\"a,b\",\"c\"", TextUtils.encodeCVSLine(",", "a,b", "c"));
    }

    @Test
    public void encodeCVSLineEscapesQuotesWithinEachFieldAndKeepsCustomSeparator() {
        // Each field is independently quote-escaped before being wrapped and joined on ';'.
        assertEquals("\"say \"\"hi\"\"\";\"plain\"",
                TextUtils.encodeCVSLine(";", "say \"hi\"", "plain"));
    }

    // ------------------------------------------------------------------
    // joiningLastDelimiter - delimiters may be identical / multi-char
    // ------------------------------------------------------------------

    @Test
    public void joiningLastDelimiterWithIdenticalDelimitersJoinsUniformly() {
        // When the regular and last delimiter are identical the output is a uniform join,
        // confirming the last boundary is not special-cased beyond which string it uses.
        Function<List<String>, String> f = TextUtils.joiningLastDelimiter(", ", ", ");
        assertEquals("a, b, c", f.apply(Arrays.asList("a", "b", "c")));
    }

    @Test
    public void joiningLastDelimiterSingleElementIgnoresBothDelimiters() {
        // With one element, neither delimiter is applied - the lone value is returned verbatim.
        Function<List<String>, String> f = TextUtils.joiningLastDelimiter(" / ", " & ");
        assertEquals("solo", f.apply(Collections.singletonList("solo")));
    }

    // ------------------------------------------------------------------
    // escapeLike - the one LIKE escape, paired with ESCAPE '!'
    // ------------------------------------------------------------------

    @Test
    public void escapeLikeEscapesBothWildcardsAndTheEscapeCharacterItself() {
        assertEquals("100!%", TextUtils.escapeLike("100%"));
        assertEquals("a!_b", TextUtils.escapeLike("a_b"));
        // The escape character is doubled first, so an escape already in the value is not read as one
        assertEquals("!!!%", TextUtils.escapeLike("!%"));
        assertEquals("plain text", TextUtils.escapeLike("plain text"));
        assertEquals("", TextUtils.escapeLike(""));
    }
}
