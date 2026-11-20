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

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HtmlToPlainText#getPlainText(org.jsoup.nodes.Element)}, which converts an
 * HTML DOM into lightly-formatted plain text: stripping markup, turning list items into
 * " * " bullets, separating block elements with newlines, expanding anchors into
 * "text &lt;absolute-url&gt;", and word-wrapping at 80 columns.
 *
 * <p>The visitor does NOT trim its output, so the tests trim where leading/trailing newlines from
 * block formatting are not the behaviour under test, and assert on the exact whitespace where it is.
 */
public class HtmlToPlainTextTest {

    private String plain(String html) {
        return new HtmlToPlainText().getPlainText(Jsoup.parse(html));
    }

    private String plain(String html, String baseUri) {
        return new HtmlToPlainText().getPlainText(Jsoup.parse(html, baseUri));
    }

    // ---------------------------------------------------------------------
    // Tag stripping / basic text
    // ---------------------------------------------------------------------

    @Test
    public void stripsInlineMarkupKeepingText() {
        // Bold tags are removed but the words survive, joined by the natural space.
        assertEquals("Hello world", plain("<p>Hello <b>world</b></p>").trim());
    }

    @Test
    public void plainTextWithoutMarkupIsUnchanged() {
        assertEquals("just text", plain("just text").trim());
    }

    @Test
    public void nestedInlineMarkupIsFlattened() {
        assertEquals("a b c", plain("<p>a <b><i>b</i></b> c</p>").trim());
    }

    @Test
    public void emptyDocumentProducesEmptyString() {
        // No text nodes and no block elements -> nothing is accumulated.
        assertEquals("", plain(""));
    }

    @Test
    public void blankWhitespaceDocumentProducesEmptyTrimmedString() {
        // Runs of whitespace collapse; trimming leaves nothing.
        assertEquals("", plain("   ").trim());
    }

    // ---------------------------------------------------------------------
    // Block separation (paragraphs / headings)
    // ---------------------------------------------------------------------

    @Test
    public void twoParagraphsAreSeparatedByNewline() {
        // head() prepends "\n" on each <p>, tail() appends "\n"; after trimming the
        // interior separator between the two paragraphs remains.
        assertEquals("one\n\ntwo", plain("<p>one</p><p>two</p>").trim());
    }

    @Test
    public void headingIntroducesNewlineBeforeContent() {
        assertEquals("Title\n\nBody", plain("<h1>Title</h1><p>Body</p>").trim());
    }

    @Test
    public void lineBreakTagAppendsNewline() {
        assertEquals("line1\nline2", plain("line1<br>line2").trim());
    }

    // ---------------------------------------------------------------------
    // Block ends: div, ul, ol, table, blockquote and the other blocks break the line, td and th are spaced (TEXT2)
    // ---------------------------------------------------------------------

    @Test
    public void divsWithoutWhiteSpaceBetweenAreOnLinesOfTheirOwn() {
        assertEquals("one\ntwo", plain("<div>one</div><div>two</div>").trim());
    }

    @Test
    public void textBeforeAndAfterABlockquoteIsOnLinesOfItsOwn() {
        assertEquals("before\nquote\nafter", plain("before<blockquote>quote</blockquote>after").trim());
    }

    @Test
    public void textAfterAListStartsANewLine() {
        assertEquals("* a\n * b\nafter", plain("<ul><li>a</li></ul><ol><li>b</li></ol>after").trim());
    }

    @Test
    public void tableCellsAreSpacedAndTheTableEndsTheLine() {
        assertEquals("a b\nc d\nafter",
                plain("<table><tr><th>a</th><th>b</th></tr><tr><td>c</td><td>d</td></tr></table>after").trim());
    }

    @Test
    public void aBlockInsideADivAddsNoBlankLine() {
        // The div's own break is not added where the paragraph, the list item or the table row already breaks
        assertEquals("x\na\ny", plain("x<div><p>a</p></div>y").trim());
        assertEquals("x\n * a\ny", plain("x<div><ul><li>a</li></ul></div>y").trim());
        assertEquals("x\na\ny", plain("x<table><tr><td>a</td></tr></table>y").trim());
    }

    @Test
    public void sixthLevelHeadingsAreOnLinesOfTheirOwn() {
        // h6, as h1 to h5: older or imported HTML and the source view write it
        assertEquals("a\n\nb", plain("<h6>a</h6><h6>b</h6>").trim());
    }

    @Test
    public void codeBlocksAndAHorizontalRuleBreakTheLine() {
        // The editor's full preset writes a code block as pre and a horizontal rule as hr
        assertEquals("a\nb", plain("<pre><code>a</code></pre><pre><code>b</code></pre>").trim());
        assertEquals("a\nb", plain("a<hr>b").trim());
        // A rule between paragraphs adds no line to theirs
        assertEquals("a\n\nb", plain("<p>a</p><hr><p>b</p>").trim());
    }

    @Test
    public void sectioningAndOtherOlderBlocksAreOnLinesOfTheirOwn() {
        // Older or imported HTML and the source view: the HTML5 sectioning elements, address, figure, center, dl
        for (String tag : new String[] { "section", "article", "header", "footer", "aside", "nav", "main",
                "address", "figure", "center" }) {
            assertEquals("a\nb", plain("<" + tag + ">a</" + tag + "><" + tag + ">b</" + tag + ">").trim(), tag);
        }
        assertEquals("a\ncaption\nb", plain("<figure>a<figcaption>caption</figcaption></figure>b").trim());
        assertEquals("before\n  term\nafter", plain("before<dl><dt>term</dt></dl>after").trim());
    }

    @Test
    public void whiteSpaceBetweenBlocksGivesNoBlankLine() {
        assertEquals("one\ntwo", plain("<div>one</div>\n  <div>two</div>").trim());
        assertEquals("a b", plain("<table><tr><td>a</td> <td>b</td></tr></table>").trim());
    }

    @Test
    public void theSpaceAfterAWrappedWordStaysBeforeAnInlineElement() {
        // The run does not fit what is left of the line, so it is wrapped word by word; its last word moves to a new
        // line, and the space at the run's end must still separate it from the bold word.
        String html = "<p>" + "x".repeat(40) + " " + "y".repeat(39) + " <b>Wreck</b></p>";
        assertEquals("x".repeat(40) + " \n" + "y".repeat(39) + " Wreck", plain(html).trim());
    }

    // ---------------------------------------------------------------------
    // Lists
    // ---------------------------------------------------------------------

    @Test
    public void unorderedListItemsBecomeBullets() {
        String result = plain("<ul><li>a</li><li>b</li></ul>");
        assertTrue(result.contains("\n * a"), "expected ' * a' bullet, was: " + result);
        assertTrue(result.contains("\n * b"), "expected ' * b' bullet, was: " + result);
    }

    @Test
    public void singleListItemTrimsToBulletedText() {
        // Leading "\n * " from <li> -> trimmed value starts with "* ".
        assertEquals("* only", plain("<ul><li>only</li></ul>").trim());
    }

    @Test
    public void orderedAndUnorderedListsUseSameBulletPrefix() {
        // The formatter does not distinguish <ol> from <ul>; both <li> get " * ".
        assertEquals("* first\n * second", plain("<ol><li>first</li><li>second</li></ol>").trim());
    }

    // ---------------------------------------------------------------------
    // Definition lists (dt / dd)
    // ---------------------------------------------------------------------

    @Test
    public void definitionTermAndValueAreSeparatedByOneNewline() {
        // <dt> head() appends "  " and tail() appends "\n"; <dd> only tail() appends "\n".
        // After trimming the leading indent and trailing newline, term and value are
        // separated by a single newline.
        assertEquals("term\nvalue", plain("<dl><dt>term</dt><dd>value</dd></dl>").trim());
    }

    // ---------------------------------------------------------------------
    // Anchors / absolute URL resolution
    // ---------------------------------------------------------------------

    @Test
    public void anchorWithBaseUriExpandsToAbsoluteUrl() {
        String result = plain("<a href=\"/x\">link</a>", "http://example.com/").trim();
        assertEquals("link <http://example.com/x>", result);
    }

    @Test
    public void anchorWithAbsoluteHrefIsPreserved() {
        String result = plain("<a href=\"http://other.org/page\">go</a>", "http://example.com/").trim();
        assertEquals("go <http://other.org/page>", result);
    }

    @Test
    public void anchorWithoutBaseUri_keepsItsWordsAndPrintsNoEmptyAngleBrackets() {
        // With no base URI, absUrl("href") on a relative link returns "", and a link inside Niord (a message
        // link "#/message/...", a repository path) has nothing to show; it printed " <>" after the link's words.
        assertEquals("link", plain("<a href=\"/x\">link</a>").trim());
        assertEquals("See NW-001-26.", plain("<p>See <a href=\"#/message/NW-001-26\">NW-001-26</a>.</p>").trim());
    }

    // ---------------------------------------------------------------------
    // Whitespace collapsing
    // ---------------------------------------------------------------------

    @Test
    public void runsOfSpacesBetweenWordsAreCollapsedToSingleSpace() {
        // append() skips a lone " " when the previous char is already a space or newline.
        assertEquals("a b", plain("<p>a     b</p>").trim());
    }

    // ---------------------------------------------------------------------
    // Word wrapping at the 80-column boundary
    // ---------------------------------------------------------------------

    @Test
    public void textUnderEightyColumnsIsNotWrapped() {
        // 70 'a' characters: comfortably under maxWidth, so no newline is introduced.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            sb.append('a');
        }
        String word = sb.toString();
        String result = plain("<p>" + word + "</p>").trim();
        assertEquals(word, result);
        assertFalse(result.contains("\n"));
    }

    @Test
    public void longLineIsWrappedAtWordBoundaryBeyondEightyColumns() {
        // Twenty 5-char words separated by spaces = far past 80 cols; wrapping inserts
        // at least one newline, but no word is split internally.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append("abcde");
        }
        String result = plain("<p>" + sb + "</p>").trim();
        assertTrue(result.contains("\n"), "expected wrapping newline, was: " + result);
        // No 5-char word was broken: every token between newlines/spaces is still "abcde".
        for (String token : result.split("\\s+")) {
            assertEquals("abcde", token);
        }
    }

    // ---------------------------------------------------------------------
    // HTML entity decoding
    // ---------------------------------------------------------------------

    @Test
    public void htmlEntitiesAreDecodedToCharacters() {
        assertEquals("a & b < c", plain("<p>a &amp; b &lt; c</p>").trim());
    }
}
