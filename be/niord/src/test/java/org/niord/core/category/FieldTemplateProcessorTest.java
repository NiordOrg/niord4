/*
 * Copyright 2017 Danish Maritime Authority.
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

package org.niord.core.category;

import org.junit.jupiter.api.Test;
import org.niord.core.category.FieldTemplateProcessor.FieldTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pure, framework-neutral unit tests for {@link FieldTemplateProcessor} and its inner
 * {@link FieldTemplate} class.
 *
 * No Quarkus / Spring / CDI / database involved - this exercises the static parsing and
 * content-normalisation logic only, so the assertions remain valid across a future migration.
 */
public class FieldTemplateProcessorTest {

    // ------------------------------------------------------------------------
    // FieldTemplateProcessor.parse(String)
    // ------------------------------------------------------------------------

    /**
     * 1. Three blocks separated by newlines -> list of size 3, in document order. The trailing
     * newline is incidental: parse() now also handles input ending exactly at "</field-template>"
     * (see {@link #parseInputEndingExactlyAtClosingTagReturnsTheBlock}).
     */
    @Test
    public void parseThreeBlocksReturnsThreeInOrder() {
        String txt =
                "<field-template field=\"a\" format=\"text\" update=\"replace\">one</field-template>\n" +
                "<field-template field=\"b\" format=\"text\" update=\"append\">two</field-template>\n" +
                "<field-template field=\"c\" format=\"html\" update=\"replace\">three</field-template>\n";

        List<FieldTemplate> result = FieldTemplateProcessor.parse(txt);

        assertEquals(3, result.size());
        assertEquals("a", result.get(0).getField());
        assertEquals("b", result.get(1).getField());
        assertEquals("c", result.get(2).getField());
        assertEquals("one", result.get(0).getContent());
        assertEquals("two", result.get(1).getContent());
        assertEquals("three", result.get(2).getContent());
    }

    /** 2. Text containing no field-template blocks -> empty list. */
    @Test
    public void parseNoBlocksReturnsEmptyList() {
        List<FieldTemplate> result = FieldTemplateProcessor.parse("just some random text with no templates");
        assertTrue(result.isEmpty());
    }

    /** 3. A start tag with no closing tag -> the loop breaks and returns an empty list. */
    @Test
    public void parseStartTagWithoutEndTagReturnsEmptyList() {
        String txt = "<field-template field=\"a\" format=\"text\">never closed";
        List<FieldTemplate> result = FieldTemplateProcessor.parse(txt);
        assertTrue(result.isEmpty());
    }

    /** 4. parse() extracts field/format from every block. */
    @Test
    public void parseExtractsFieldAndFormatPerBlock() {
        String txt =
                "<field-template field=\"subject\" format=\"text\">S</field-template>\n" +
                "<field-template field=\"body\" format=\"html\">B</field-template>\n";

        List<FieldTemplate> result = FieldTemplateProcessor.parse(txt);

        assertEquals(2, result.size());
        assertEquals("subject", result.get(0).getField());
        assertEquals("text", result.get(0).getFormat());
        assertEquals("body", result.get(1).getField());
        assertEquals("html", result.get(1).getFormat());
    }

    // ------------------------------------------------------------------------
    // FieldTemplate.parse(String) - attribute extraction
    // ------------------------------------------------------------------------

    /** 5. Well-formed single block parses field/format/update/content. */
    @Test
    public void fieldTemplateParseWellFormedBlock() {
        FieldTemplate ft = FieldTemplate.parse(
                "<field-template field=\"part.getDesc('da').subject\" format=\"text\" update=\"replace\">Fyr slukket</field-template>");

        assertEquals("part.getDesc('da').subject", ft.getField());
        assertEquals("text", ft.getFormat());
        assertEquals("replace", ft.getUpdate());
        assertEquals("Fyr slukket", ft.getContent());
    }

    /** 6. Missing attributes -> the corresponding getters return null. */
    @Test
    public void fieldTemplateParseMissingAttributesAreNull() {
        FieldTemplate ft = FieldTemplate.parse("<field-template>hello</field-template>");

        assertNull(ft.getField());
        assertNull(ft.getFormat());
        assertNull(ft.getUpdate());
        assertEquals("hello", ft.getContent());
    }

    /** 7. A string that does not start with the opening tag -> IllegalArgumentException. */
    @Test
    public void fieldTemplateParseRejectsMissingStartTag() {
        try {
            FieldTemplate.parse("oops</field-template>");
            fail("Expected IllegalArgumentException for missing start tag");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    /** 8. A string that does not end with the closing tag -> IllegalArgumentException. */
    @Test
    public void fieldTemplateParseRejectsMissingEndTag() {
        try {
            FieldTemplate.parse("<field-template>oops");
            fail("Expected IllegalArgumentException for missing end tag");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    /** 9. The field regex find() stops the value at the first double-quote. */
    @Test
    public void fieldTemplateParseFieldValueStopsAtFirstQuote() {
        FieldTemplate ft = FieldTemplate.parse(
                "<field-template field=\"a.b\" format=\"text\">x</field-template>");
        assertEquals("a.b", ft.getField());
    }

    // ------------------------------------------------------------------------
    // parseContent / cleanUpPlainTextContent
    // ------------------------------------------------------------------------

    /** 10. format=text: internal multiple spaces are compressed to a single space. */
    @Test
    public void parseContentTextCompressesInternalSpaces() {
        FieldTemplate ft = FieldTemplate.parse(
                "<field-template format=\"text\">  Light   Alpha   unlit  </field-template>");
        assertEquals("Light Alpha unlit", ft.getContent());
    }

    /**
     * 11a. format=text: a single newline between two non-whitespace-padded lines is preserved, and
     * surrounding blank lines/whitespace are trimmed away.
     */
    @Test
    public void parseContentTextPreservesSingleNewlineBetweenLines() {
        FieldTemplate ft = FieldTemplate.parse(
                "<field-template format=\"text\">\nFirst line\nSecond line\n</field-template>");
        assertEquals("First line\nSecond line", ft.getContent());
    }

    /**
     * 11b. QUIRK: when newlines are padded by whitespace, the clean-up step
     * {@code replaceAll("(?m)^\\s|\\s$", "")} also consumes the newlines themselves (because
     * {@code \\s} matches {@code \\n} and the multiline anchors sit at the newline boundaries), so the
     * two lines are JOINED with no separator. This pins the actual (surprising) behavior of
     * FieldTemplate.parse for whitespace-padded multi-line content.
     */
    @Test
    public void parseContentTextWhitespacePaddedNewlinesCollapseAndJoin_documentsQuirk() {
        FieldTemplate ft = FieldTemplate.parse(
                "<field-template format=\"text\">\n   Line one   \n\n   Line two   \n</field-template>");
        assertEquals("Line oneLine two", ft.getContent());
    }

    /** 12. The "\\s+\\." -> "." rule removes whitespace before a period (runs before clean-up). */
    @Test
    public void parseContentTextRemovesSpaceBeforePeriod() {
        FieldTemplate ft = FieldTemplate.parse(
                "<field-template format=\"text\">The light is unlit .</field-template>");
        assertEquals("The light is unlit.", ft.getContent());
    }

    /** 13a. format=html: trimmed + space-before-dot rule, but internal spaces preserved. */
    @Test
    public void parseContentHtmlPreservesInternalSpaces() {
        FieldTemplate ft = FieldTemplate.parse(
                "<field-template format=\"html\">  <p>a   b</p>  </field-template>");
        assertEquals("<p>a   b</p>", ft.getContent());
    }

    /** 13b. format=text: same input collapses the internal spaces - proves the html/text difference. */
    @Test
    public void parseContentTextCollapsesWhereHtmlPreserves() {
        FieldTemplate ft = FieldTemplate.parse(
                "<field-template format=\"text\">  <p>a   b</p>  </field-template>");
        assertEquals("<p>a b</p>", ft.getContent());
    }

    /** 14a. format=boolean: "true" -> Boolean.TRUE. */
    @Test
    public void parseContentBooleanTrue() {
        FieldTemplate ft = FieldTemplate.parse(
                "<field-template format=\"boolean\">true</field-template>");
        assertTrue(ft.getContent() instanceof Boolean);
        assertEquals(Boolean.TRUE, ft.getContent());
    }

    /** 14b. format=boolean: "false" -> Boolean.FALSE. */
    @Test
    public void parseContentBooleanFalse() {
        FieldTemplate ft = FieldTemplate.parse(
                "<field-template format=\"boolean\">false</field-template>");
        assertTrue(ft.getContent() instanceof Boolean);
        assertEquals(Boolean.FALSE, ft.getContent());
    }

    /** 14c. format=boolean: "TRUE" -> Boolean.TRUE (Boolean.valueOf is case-insensitive). */
    @Test
    public void parseContentBooleanUpperCaseTrue() {
        FieldTemplate ft = FieldTemplate.parse(
                "<field-template format=\"boolean\">TRUE</field-template>");
        assertTrue(ft.getContent() instanceof Boolean);
        assertEquals(Boolean.TRUE, ft.getContent());
    }

    /** 14d. format=boolean: any non-"true" value -> Boolean.FALSE. */
    @Test
    public void parseContentBooleanGarbageIsFalse() {
        FieldTemplate ft = FieldTemplate.parse(
                "<field-template format=\"boolean\">garbage</field-template>");
        assertTrue(ft.getContent() instanceof Boolean);
        assertEquals(Boolean.FALSE, ft.getContent());
    }

    /** 15. Blank (whitespace-only) content is returned unchanged (isNotBlank guard). */
    @Test
    public void parseContentBlankIsReturnedUnchanged() {
        FieldTemplate ft = FieldTemplate.parse(
                "<field-template format=\"text\">   </field-template>");
        assertEquals("   ", ft.getContent());
    }

    // ------------------------------------------------------------------------
    // toString()
    // ------------------------------------------------------------------------

    /** 16. toString() includes all non-blank attributes and wraps the content. */
    @Test
    public void toStringIncludesAllAttributes() {
        FieldTemplate ft = FieldTemplate.parse(
                "<field-template field=\"f\" format=\"text\" update=\"append\">x</field-template>");
        assertEquals(
                "<field-template field=\"f\" format=\"text\" update=\"append\">x</field-template>",
                ft.toString());
    }

    /** 17. toString() omits attributes that are null/blank. */
    @Test
    public void toStringOmitsBlankAttributes() {
        FieldTemplate ft = FieldTemplate.parse("<field-template>hello</field-template>");
        assertEquals("<field-template>hello</field-template>", ft.toString());
    }

    // ------------------------------------------------------------------------
    // Extra edge cases verified against the source
    // ------------------------------------------------------------------------

    /**
     * Two blocks separated by a single character (here a space) parse to two results: the loop's
     * {@code txt.substring(endIndex)} retains the separator, and the second block's opening
     * "<field-template" tag is found by the next indexOf.
     */
    @Test
    public void parseTwoBlocksSeparatedBySingleChar() {
        String txt =
                "<field-template field=\"a\" format=\"text\">one</field-template> " +
                "<field-template field=\"b\" format=\"text\">two</field-template>\n";

        List<FieldTemplate> result = FieldTemplateProcessor.parse(txt);

        assertEquals(2, result.size());
        assertEquals("a", result.get(0).getField());
        assertEquals("one", result.get(0).getContent());
        assertEquals("b", result.get(1).getField());
        assertEquals("two", result.get(1).getContent());
    }

    /**
     * Fixed (docs/potential-errors.md TMPL3): parse() returns the final block when the input ends
     * EXACTLY at a "</field-template>" closing tag with no trailing character. Previously the loop
     * ran {@code txt = txt.substring(endIndex + 1)} with {@code endIndex == txt.length()} and threw
     * StringIndexOutOfBoundsException; it now uses {@code substring(endIndex)} which is in bounds.
     */
    @Test
    public void parseInputEndingExactlyAtClosingTagReturnsTheBlock() {
        String txt = "<field-template field=\"a\" format=\"text\">one</field-template>";

        List<FieldTemplate> result = FieldTemplateProcessor.parse(txt);

        assertEquals(1, result.size());
        assertEquals("a", result.get(0).getField());
        assertEquals("one", result.get(0).getContent());
    }

    /**
     * Fixed (docs/potential-errors.md TMPL3): two directly-adjacent blocks (no separator) now both
     * parse. Previously {@code txt.substring(endIndex + 1)} consumed the second block's leading "<",
     * so {@code indexOf("<field-template")} no longer matched and the second block was lost; with
     * {@code substring(endIndex)} the "<" is retained and both blocks are discovered.
     */
    @Test
    public void parseTwoAdjacentBlocksYieldsBoth() {
        String txt =
                "<field-template field=\"a\" format=\"text\">one</field-template>" +
                "<field-template field=\"b\" format=\"text\">two</field-template>";

        List<FieldTemplate> result = FieldTemplateProcessor.parse(txt);

        assertEquals(2, result.size());
        assertEquals("a", result.get(0).getField());
        assertEquals("one", result.get(0).getContent());
        assertEquals("b", result.get(1).getField());
        assertEquals("two", result.get(1).getContent());
    }

    /**
     * Attribute order does not matter - each attribute is found independently by its own regex.
     */
    @Test
    public void fieldTemplateParseAttributesInDifferentOrder() {
        FieldTemplate ft = FieldTemplate.parse(
                "<field-template update=\"replace\" format=\"text\" field=\"subj\">body</field-template>");
        assertEquals("subj", ft.getField());
        assertEquals("text", ft.getFormat());
        assertEquals("replace", ft.getUpdate());
        assertEquals("body", ft.getContent());
    }

    /**
     * A body that itself contains the substring "field=" does not corrupt attribute parsing,
     * because attributes are taken only from the text before the first ">".
     */
    @Test
    public void fieldTemplateParseContentContainingFieldEqualsIsNotMisparsed() {
        FieldTemplate ft = FieldTemplate.parse(
                "<field-template field=\"realField\" format=\"html\">the body has field=\"trap\" inside</field-template>");
        assertEquals("realField", ft.getField());
        assertEquals("html", ft.getFormat());
        assertEquals("the body has field=\"trap\" inside", ft.getContent());
    }

    /**
     * Surrounding whitespace and a leading message-template wrapper are tolerated by parse():
     * the block is located by indexOf and parsed; the inner block's leading/trailing whitespace
     * is trimmed away by FieldTemplate.parse before content normalisation.
     */
    @Test
    public void parseLocatesBlockWithinSurroundingText() {
        String txt =
                "<message-template>\n" +
                "    <field-template field=\"part.getDesc('da').subject\" format=\"text\" update=\"replace\">\n" +
                "        Fyr slukket\n" +
                "    </field-template>\n" +
                "</message-template>";

        List<FieldTemplate> result = FieldTemplateProcessor.parse(txt);

        assertEquals(1, result.size());
        assertEquals("part.getDesc('da').subject", result.get(0).getField());
        assertEquals("text", result.get(0).getFormat());
        assertEquals("replace", result.get(0).getUpdate());
        assertEquals("Fyr slukket", result.get(0).getContent());
    }
}
