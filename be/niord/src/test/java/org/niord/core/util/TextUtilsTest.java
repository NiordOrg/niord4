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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TextUtils}, a collection of static String utility methods.
 *
 * <p>The tests pin the observable behaviour of HTML/plain-text conversion, whitespace
 * trimming, line-length wrapping, word removal, trailing-dot handling, case-insensitive
 * comparison, CSV encoding and the "join with a different last delimiter" function.
 * All assertions are deterministic and independent of locale, timezone or current time.
 */
public class TextUtilsTest {

    // ------------------------------------------------------------------
    // trimHtmlWhitespace
    // ------------------------------------------------------------------

    @Test
    public void trimHtmlWhitespaceCollapsesNewlinesAndRunsOfSpaces() {
        // Newlines/carriage-returns become spaces, runs of whitespace collapse to one space, then trim().
        assertEquals("a b c", TextUtils.trimHtmlWhitespace("a\n  b\r c"));
    }

    @Test
    public void trimHtmlWhitespaceTrimsLeadingAndTrailing() {
        assertEquals("hello world", TextUtils.trimHtmlWhitespace("   hello    world   "));
    }

    @Test
    public void trimHtmlWhitespaceReturnsNullForNull() {
        assertNull(TextUtils.trimHtmlWhitespace(null));
    }

    @Test
    public void trimHtmlWhitespaceReturnsBlankUnchanged() {
        // StringUtils.isNotBlank() is false for blank input, so the original string is returned as-is.
        assertEquals("   ", TextUtils.trimHtmlWhitespace("   "));
    }

    @Test
    public void trimHtmlWhitespaceReturnsEmptyUnchanged() {
        assertEquals("", TextUtils.trimHtmlWhitespace(""));
    }

    // ------------------------------------------------------------------
    // txt2html
    // ------------------------------------------------------------------

    @Test
    public void txt2htmlEscapesEntitiesNewlinesAndTabs() {
        assertEquals("a &amp; b &lt; c &gt; &quot;d&quot;<br>&nbsp;&nbsp;&nbsp;",
                TextUtils.txt2html("a & b < c > \"d\"\n\t"));
    }

    @Test
    public void txt2htmlLeavesPlainTextUnchanged() {
        assertEquals("just plain text", TextUtils.txt2html("just plain text"));
    }

    @Test
    public void txt2htmlEscapesAmpersandOnce() {
        // The ampersand is the first replacement target, so an already-escaped entity gets double-escaped.
        assertEquals("&amp;amp;", TextUtils.txt2html("&amp;"));
    }

    @Test
    public void txt2htmlConvertsNewlineToBreak() {
        assertEquals("line1<br>line2", TextUtils.txt2html("line1\nline2"));
    }

    // ------------------------------------------------------------------
    // html2txt
    // ------------------------------------------------------------------

    @Test
    public void html2txtReturnsNullForNull() {
        assertNull(TextUtils.html2txt(null));
    }

    @Test
    public void html2txtStripsTagsAndTrims() {
        assertEquals("Hello world", TextUtils.html2txt("<p>Hello <b>world</b></p>"));
    }

    @Test
    public void html2txtReturnsBlankUnchanged() {
        // Blank input never enters the Jsoup branch and is returned verbatim.
        assertEquals("   ", TextUtils.html2txt("   "));
    }

    @Test
    public void html2txtReturnsEmptyUnchanged() {
        assertEquals("", TextUtils.html2txt(""));
    }

    @Test
    public void html2txtPlainTextPassesThrough() {
        assertEquals("plain", TextUtils.html2txt("plain"));
    }

    @Test
    public void html2txtWithCleanUpWhitespaceCollapsesWhitespace() {
        // With cleanUpWhiteSpace=true, newlines and whitespace runs are normalised before stripping tags.
        assertEquals("Hello world", TextUtils.html2txt("<p>Hello\n   <b>world</b></p>", true));
    }

    @Test
    public void html2txtKeepsTheSpaceBeforeAnInlineElementAfterALongRun() {
        // TEXT2: the run is wrapped word by word, and the space at its end used to be lost ("moved to57° ...")
        String html = "<p>Mariners are advised that the light buoy marking the wreck has now been moved to "
                + "<strong>57° 01.7'N - 003° 30.6'E</strong>.</p>";
        assertTrue(TextUtils.html2txt(html, true).endsWith("to 57° 01.7'N - 003° 30.6'E."),
                TextUtils.html2txt(html, true));
    }

    @Test
    public void html2txtBreaksTheLineAtTheEndOfADivListTableAndBlockquote() {
        // TEXT2: "<div>003°</div><div>are</div>" used to give "003°are"
        assertEquals("003°\nare", TextUtils.html2txt("<div>003°</div><div>are</div>", true));
        assertEquals("* a\nb", TextUtils.html2txt("<ul><li>a</li></ul>b", true));
        assertEquals("a b\nc", TextUtils.html2txt("<table><tr><td>a</td><td>b</td></tr></table>c", true));
        assertEquals("a\nb", TextUtils.html2txt("<blockquote>a</blockquote>b", true));
    }

    // ------------------------------------------------------------------
    // maxLineLength
    // ------------------------------------------------------------------

    @Test
    public void maxLineLengthReturnsBlankUnchanged() {
        assertNull(TextUtils.maxLineLength(null, 10));
        assertEquals("", TextUtils.maxLineLength("", 10));
        assertEquals("   ", TextUtils.maxLineLength("   ", 10));
    }

    @Test
    public void maxLineLengthRefusesALengthBelowOne() {
        // A window of no characters consumes nothing: a length of 0 looped forever, appending empty
        // lines until memory ran out, and a negative one failed with an IndexOutOfBoundsException
        assertThrows(IllegalArgumentException.class, () -> TextUtils.maxLineLength("aaa bbb", 0));
        assertThrows(IllegalArgumentException.class, () -> TextUtils.maxLineLength("aaa bbb", -2));
        assertThrows(IllegalArgumentException.class, () -> TextUtils.maxLineLength("", 0));
        assertEquals("a\nb\n", TextUtils.maxLineLength("a b", 1));
    }

    @Test
    public void maxLineLengthAppendsNewlineToShortText() {
        // Text already within the limit is emitted unchanged plus a trailing newline.
        assertEquals("short\n", TextUtils.maxLineLength("short", 10));
    }

    @Test
    public void maxLineLengthWrapsAtWordBoundary() {
        // "aaa bbb ccc" with max 7. Walking the algorithm: the first window is substring(0,7)="aaa bbb",
        // whose last space is at index 3, so the emitted line is substring(0,4).trim()="aaa" and the
        // remainder becomes "bbb ccc". "bbb ccc" has length 7 (not > 7) so it is emitted unchanged.
        // Exact value confirmed by executing the algorithm.
        assertEquals("aaa\nbbb ccc\n", TextUtils.maxLineLength("aaa bbb ccc", 7));
    }

    @Test
    public void maxLineLengthWrapsLongUnbrokenWord() {
        // No space within the window: the word is hard-split at maxLength.
        // "abcdefgh" with max 3 -> "abc", "def", "gh".
        assertEquals("abc\ndef\ngh\n", TextUtils.maxLineLength("abcdefgh", 3));
    }

    @Test
    public void maxLineLengthNoResultingLineExceedsMaxWhenSplittable() {
        // General invariant check: every produced line is either <= maxLength or a single word.
        int max = 8;
        String input = "the quick brown fox jumped over the lazy dog";
        String result = TextUtils.maxLineLength(input, max);
        for (String line : result.split("\n")) {
            boolean singleWord = !line.trim().contains(" ");
            assertTrue(line.length() <= max || singleWord,
                    "Line too long and not a single word: '" + line + "'");
        }
    }

    @Test
    public void maxLineLengthPreservesAllWords() {
        // Wrapping must not drop or duplicate words.
        String input = "alpha beta gamma delta";
        String result = TextUtils.maxLineLength(input, 6);
        String rejoined = result.replace("\n", " ").trim().replaceAll("\\s+", " ");
        assertEquals("alpha beta gamma delta", rejoined);
    }

    // The cases below pin the output of the window-based implementation against the one it replaced,
    // which cut and trimmed a new remainder string after every emitted line.

    @Test
    public void maxLineLengthWrapsEveryLineOfAMultiLineText() {
        assertEquals("aaa\nbbb ccc\nshort\n", TextUtils.maxLineLength("aaa bbb ccc\nshort", 7));
        // Empty lines in the middle stay; String.split drops the trailing empty ones
        assertEquals("aaa\n\nbbb\n", TextUtils.maxLineLength("aaa\n\nbbb\n\n", 7));
    }

    @Test
    public void maxLineLengthTrimsTheBrokenPartsButNotALineThatFits() {
        // The first part and the rest are trimmed once a line is broken...
        assertEquals("aaa\nbbb ccc\n", TextUtils.maxLineLength("  aaa bbb ccc  ", 7));
        // ...but a line within the limit is emitted as it is, surrounding blanks included
        assertEquals("  ab  \n", TextUtils.maxLineLength("  ab  ", 10));
    }

    @Test
    public void maxLineLengthTrimsControlCharactersButBreaksOnlyAtSpaces() {
        // A tab is no break point, but trim() removes it and every other char <= ' ' at either end
        assertEquals("aaaa\nbbbb\ncccc\n", TextUtils.maxLineLength("\u0001aaaa\tbbbb cccc\u0002", 6));
        // A carriage return before the newline is trimmed from a broken line
        assertEquals("aaa\nbbb\nccc\n", TextUtils.maxLineLength("aaa bbb\r\nccc", 5));
    }

    @Test
    public void maxLineLengthCutsALongUnbrokenWordIntoFullLines() {
        String word = "x".repeat(200_000);
        String expected = ("x".repeat(40) + "\n").repeat(5_000);
        assertEquals(expected, TextUtils.maxLineLength(word, 40));
    }

    @Test
    public void maxLineLengthPacksALongSingleLine() {
        // 20 000 ten-character words "abcdefghi " on one 200 000-character line, NAVTEX width: every
        // window of 40 characters ends in a space, so each line holds four words.
        String line = "abcdefghi ".repeat(20_000);
        String expected = "abcdefghi abcdefghi abcdefghi abcdefghi\n".repeat(5_000);
        assertEquals(expected, TextUtils.maxLineLength(line, 40));
    }

    @Test
    public void maxLineLengthMatchesThePreviousImplementationOnMixedInput() {
        // Seeded, so every run checks the same inputs: letters, spaces, tabs, control characters,
        // carriage returns and newlines in every mix, at the widths the callers use and narrower.
        Random random = new Random(20260925L);
        String alphabet = "abc  \t\u0001\r\nxyz ";
        for (int n = 0; n < 5_000; n++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(160);
            for (int i = 0; i < length; i++) {
                text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            for (int maxLength : new int[] { 1, 2, 3, 7, 12, 40 }) {
                assertEquals(previousMaxLineLength(text.toString(), maxLength),
                        TextUtils.maxLineLength(text.toString(), maxLength),
                        "maxLength " + maxLength + ", text '" + text + "'");
            }
        }
    }

    /** The implementation maxLineLength had until 2026-09: the reference for its output */
    private static String previousMaxLineLength(String text, int maxLength) {
        if (org.apache.commons.lang3.StringUtils.isBlank(text)) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String s = line;
            while (s.length() > maxLength) {
                String t = s.substring(0, maxLength);
                int idx = t.lastIndexOf(" ");
                if (idx != -1) {
                    sb.append(s.substring(0, idx + 1).trim()).append("\n");
                    s = s.substring(idx + 1).trim();
                } else {
                    sb.append(t.trim()).append("\n");
                    s = s.substring(t.length()).trim();
                }
            }
            sb.append(s).append("\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // removeWords
    // ------------------------------------------------------------------

    @Test
    public void removeWordsRemovesLeadingMiddleAndKeepsRest() {
        // Leading "the ", and the middle " the " are removed; result is trimmed.
        assertEquals("quick fox", TextUtils.removeWords("the quick the fox", "the"));
    }

    @Test
    public void removeWordsRemovesTrailingWord() {
        assertEquals("quick fox", TextUtils.removeWords("quick fox the", "the"));
    }

    @Test
    public void removeWordsIsCaseInsensitive() {
        // The regex is compiled with (?i), so casing of the matched word is ignored.
        assertEquals("quick fox", TextUtils.removeWords("The quick THE fox", "the"));
    }

    @Test
    public void removeWordsSupportsPipeSeparatedAlternatives() {
        assertEquals("quick fox", TextUtils.removeWords("the quick a fox", "the|a"));
    }

    @Test
    public void removeWordsLeavesNonMatchingTextUnchanged() {
        assertEquals("quick brown fox", TextUtils.removeWords("quick brown fox", "the"));
    }

    @Test
    public void removeWordsDoesNotRemoveSubstringWithinWord() {
        // "the" inside "theatre" is not surrounded by whitespace, so it survives.
        assertEquals("theatre show", TextUtils.removeWords("theatre show", "the"));
    }

    // ------------------------------------------------------------------
    // trailingDot
    // ------------------------------------------------------------------

    @Test
    public void trailingDotAddsDotWhenMissing() {
        assertEquals("abc.", TextUtils.trailingDot("abc"));
    }

    @Test
    public void trailingDotKeepsExistingDot() {
        assertEquals("abc.", TextUtils.trailingDot("abc."));
    }

    @Test
    public void trailingDotTrimsBeforeAddingDot() {
        assertEquals("abc.", TextUtils.trailingDot("  abc  "));
    }

    @Test
    public void trailingDotReturnsNullForNull() {
        assertNull(TextUtils.trailingDot(null));
    }

    @Test
    public void trailingDotReturnsBlankUnchanged() {
        // Blank input is not trimmed or dotted because StringUtils.isNotBlank() is false.
        assertEquals("   ", TextUtils.trailingDot("   "));
    }

    // ------------------------------------------------------------------
    // removeTrailingDot
    // ------------------------------------------------------------------

    @Test
    public void removeTrailingDotRemovesMultipleDots() {
        assertEquals("abc", TextUtils.removeTrailingDot("abc..."));
    }

    @Test
    public void removeTrailingDotLeavesTextWithoutDotUnchanged() {
        assertEquals("abc", TextUtils.removeTrailingDot("abc"));
    }

    @Test
    public void removeTrailingDotTrimsSurroundingWhitespace() {
        assertEquals("abc", TextUtils.removeTrailingDot("  abc.  "));
    }

    @Test
    public void removeTrailingDotReturnsNullForNull() {
        assertNull(TextUtils.removeTrailingDot(null));
    }

    @Test
    public void removeTrailingDotReturnsBlankUnchanged() {
        assertEquals("   ", TextUtils.removeTrailingDot("   "));
    }

    // ------------------------------------------------------------------
    // compareIgnoreCase
    // ------------------------------------------------------------------

    @Test
    public void compareIgnoreCaseEqualIgnoringCase() {
        assertEquals(0, TextUtils.compareIgnoreCase("A", "a"));
    }

    @Test
    public void compareIgnoreCaseBothNull() {
        assertEquals(0, TextUtils.compareIgnoreCase(null, null));
    }

    @Test
    public void compareIgnoreCaseFirstNull() {
        assertEquals(1, TextUtils.compareIgnoreCase(null, "a"));
    }

    @Test
    public void compareIgnoreCaseSecondNull() {
        assertEquals(-1, TextUtils.compareIgnoreCase("a", null));
    }

    @Test
    public void compareIgnoreCaseLessThan() {
        assertTrue(TextUtils.compareIgnoreCase("a", "b") < 0);
    }

    @Test
    public void compareIgnoreCaseGreaterThan() {
        assertTrue(TextUtils.compareIgnoreCase("b", "a") > 0);
    }

    @Test
    public void compareIgnoreCaseMixedCaseEqual() {
        assertEquals(0, TextUtils.compareIgnoreCase("HeLLo", "hello"));
    }

    // ------------------------------------------------------------------
    // encodeCVSField
    // ------------------------------------------------------------------

    @Test
    public void encodeCVSFieldNullBecomesEmptyString() {
        assertEquals("", TextUtils.encodeCVSField(null));
    }

    @Test
    public void encodeCVSFieldDoublesEmbeddedQuotes() {
        assertEquals("a\"\"b", TextUtils.encodeCVSField("a\"b"));
    }

    @Test
    public void encodeCVSFieldLeavesPlainValueUnchanged() {
        assertEquals("plain", TextUtils.encodeCVSField("plain"));
    }

    @Test
    public void encodeCVSFieldUsesToStringForNonStrings() {
        assertEquals("42", TextUtils.encodeCVSField(Integer.valueOf(42)));
    }

    // ------------------------------------------------------------------
    // encodeCVSLine
    // ------------------------------------------------------------------

    @Test
    public void encodeCVSLineQuotesFieldsAndEscapesQuotes() {
        assertEquals("\"a\",\"b\"\"c\"", TextUtils.encodeCVSLine(",", "a", "b\"c"));
    }

    @Test
    public void encodeCVSLineDefaultsSeparatorToCommaWhenBlank() {
        // A blank separator is replaced by "," via StringUtils.defaultIfBlank.
        assertEquals("\"a\",\"b\"", TextUtils.encodeCVSLine("", "a", "b"));
    }

    @Test
    public void encodeCVSLineHonoursCustomSeparator() {
        assertEquals("\"a\";\"b\"", TextUtils.encodeCVSLine(";", "a", "b"));
    }

    @Test
    public void encodeCVSLineEncodesNullFieldAsEmptyQuotedValue() {
        assertEquals("\"a\",\"\"", TextUtils.encodeCVSLine(",", "a", (Object) null));
    }

    @Test
    public void encodeCVSLineSingleField() {
        assertEquals("\"only\"", TextUtils.encodeCVSLine(",", "only"));
    }

    @Test
    public void encodeCVSLineNoFieldsProducesEmptyString() {
        assertEquals("", TextUtils.encodeCVSLine(","));
    }

    // ------------------------------------------------------------------
    // joiningLastDelimiter
    // ------------------------------------------------------------------

    @Test
    public void joiningLastDelimiterThreeElements() {
        Function<List<String>, String> f = TextUtils.joiningLastDelimiter(", ", " and ");
        assertEquals("a, b and c", f.apply(Arrays.asList("a", "b", "c")));
    }

    @Test
    public void joiningLastDelimiterTwoElements() {
        Function<List<String>, String> f = TextUtils.joiningLastDelimiter(", ", " and ");
        assertEquals("a and b", f.apply(Arrays.asList("a", "b")));
    }

    @Test
    public void joiningLastDelimiterSingleElement() {
        Function<List<String>, String> f = TextUtils.joiningLastDelimiter(", ", " and ");
        assertEquals("a", f.apply(Collections.singletonList("a")));
    }

    @Test
    public void joiningLastDelimiterEmptyList() {
        // last = -1 -> String.join over an empty list yields the empty string.
        Function<List<String>, String> f = TextUtils.joiningLastDelimiter(", ", " and ");
        assertEquals("", f.apply(new ArrayList<String>()));
    }

    @Test
    public void joiningLastDelimiterFourElements() {
        // Only the final boundary uses the last delimiter; all earlier ones use the regular delimiter.
        Function<List<String>, String> f = TextUtils.joiningLastDelimiter(", ", " and ");
        assertEquals("a, b, c and d", f.apply(Arrays.asList("a", "b", "c", "d")));
    }

    // ------------------------------------------------------------------
    // cleanHtml
    // ------------------------------------------------------------------

    @Test
    public void cleanHtmlWrapsFragmentInHtmlElement() {
        // JTidy normalises the fragment into a full XHTML document whose root is <html>.
        org.w3c.dom.Document doc = TextUtils.cleanHtml("<p>hi</p>");
        assertEquals("html", doc.getDocumentElement().getTagName());
    }
}
