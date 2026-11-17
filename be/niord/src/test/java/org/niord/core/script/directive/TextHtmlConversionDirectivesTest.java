/*
 * Copyright 2016 Danish Maritime Authority.
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
package org.niord.core.script.directive;

import freemarker.template.TemplateException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.niord.core.script.directive.DirectiveTestSupport.data;
import static org.niord.core.script.directive.DirectiveTestSupport.render;

/**
 * Framework-neutral JUnit4 unit tests for the three text/HTML conversion Freemarker directives:
 * {@link TextToHtmlDirective}, {@link HtmlToTextDirective} and {@link DebugDirective}.
 *
 * <p>Each directive is exercised exactly as production templates use it: an instance is placed in the
 * Freemarker data model under a short key and invoked via {@code <@key .../>} through the shared
 * {@link DirectiveTestSupport} renderer (plain Freemarker, no Quarkus / DB).
 *
 * <ul>
 *   <li>{@link TextToHtmlDirective} delegates to {@code TextUtils.txt2html}, simultaneously mapping
 *       {@code & -> &amp;}, {@code " -> &quot;}, {@code < -> &lt;}, {@code > -> &gt;},
 *       newline {@code -> <br>} and tab {@code -> three &nbsp;}. A missing {@code text} parameter, or
 *       one whose value is null, prints nothing.</li>
 *   <li>{@link HtmlToTextDirective} delegates to {@code TextUtils.html2txt} (Jsoup
 *       {@code HtmlToPlainText} then {@code .trim()}). A missing required {@code html} parameter
 *       throws a {@link TemplateException}.</li>
 *   <li>{@link DebugDirective} writes its optional {@code value} parameter to {@code System.err} and
 *       emits nothing to the template output.</li>
 * </ul>
 */
public class TextHtmlConversionDirectivesTest {

    // ------------------------------------------------------------------
    // TextToHtmlDirective
    // ------------------------------------------------------------------

    @Test
    public void textToHtml_escapesAngleBracketsAmpersandAndQuote() throws Exception {
        // txt2html maps <, >, & and " to their HTML entities in a single pass.
        Map<String, Object> data = data();
        data.put("toHtml", new TextToHtmlDirective());
        data.put("s", "a<b>&\"c");
        Assertions.assertEquals("a&lt;b&gt;&amp;&quot;c", render("<@toHtml text=s/>", data));
    }

    @Test
    public void textToHtml_convertsNewlineToBrTag() throws Exception {
        // A newline character becomes a <br> element.
        Map<String, Object> data = data();
        data.put("toHtml", new TextToHtmlDirective());
        data.put("s", "a\nb");
        Assertions.assertEquals("a<br>b", render("<@toHtml text=s/>", data));
    }

    @Test
    public void textToHtml_convertsTabToThreeNonBreakingSpaces() throws Exception {
        // A tab character becomes three &nbsp; entities.
        Map<String, Object> data = data();
        data.put("toHtml", new TextToHtmlDirective());
        data.put("s", "a\tb");
        Assertions.assertEquals("a&nbsp;&nbsp;&nbsp;b", render("<@toHtml text=s/>", data));
    }

    @Test
    public void textToHtml_emptyStringProducesEmptyOutput() throws Exception {
        // An empty (non-null) text parameter renders to an empty string, not a throw.
        Map<String, Object> data = data();
        data.put("toHtml", new TextToHtmlDirective());
        data.put("s", "");
        Assertions.assertEquals("", render("<@toHtml text=s/>", data));
    }

    @Test
    public void textToHtml_missingTextPrintsNothing() throws Exception {
        // A missing 'text' parameter and a text whose value is null reach the directive alike, as null, and print
        // nothing: the NAVTEX station mail passes a promulgation's preamble, which a series without a NAVTEX format
        // never fills in (MAILLIST4).
        Map<String, Object> data = data();
        data.put("toHtml", new TextToHtmlDirective());
        Assertions.assertEquals("", render("<@toHtml/>", data));
        Map<String, Object> promulgation = new HashMap<>();
        promulgation.put("preamble", null);
        promulgation.put("text", "TEXT");
        data.put("navtex", promulgation);
        Assertions.assertEquals("[]TEXT", render("[<@toHtml text=navtex.preamble/>]<@toHtml text=navtex.text/>", data));
    }

    // ------------------------------------------------------------------
    // HtmlToTextDirective
    // ------------------------------------------------------------------

    @Test
    public void htmlToText_stripsInlineMarkupKeepingText() throws Exception {
        // Bold tags are removed; the words survive joined by their natural space.
        Map<String, Object> data = data();
        data.put("toText", new HtmlToTextDirective());
        data.put("h", "<b>Hello</b> world");
        Assertions.assertEquals("Hello world", render("<@toText html=h/>", data));
    }

    @Test
    public void htmlToText_separatesTwoParagraphsByBlankLine() throws Exception {
        // Block formatting separates the two paragraphs; html2txt trims the leading/trailing newlines.
        Map<String, Object> data = data();
        data.put("toText", new HtmlToTextDirective());
        data.put("h", "<p>one</p><p>two</p>");
        Assertions.assertEquals("one\n\ntwo", render("<@toText html=h/>", data));
    }

    @Test
    public void htmlToText_breaksTheLineBetweenTwoDivs() throws Exception {
        // TEXT2: a div's end breaks the line like a paragraph's; "<div>one</div><div>two</div>" used to give "onetwo"
        Map<String, Object> data = data();
        data.put("toText", new HtmlToTextDirective());
        data.put("h", "<div>one</div><div>two</div>");
        Assertions.assertEquals("one\ntwo", render("<@toText html=h/>", data));
    }

    @Test
    public void htmlToText_decodesHtmlEntitiesToCharacters() throws Exception {
        // Numeric/named HTML entities are decoded back to their characters.
        Map<String, Object> data = data();
        data.put("toText", new HtmlToTextDirective());
        data.put("h", "<p>a &amp; b &lt; c</p>");
        Assertions.assertEquals("a & b < c", render("<@toText html=h/>", data));
    }

    @Test
    public void htmlToText_missingRequiredHtmlParamThrowsTemplateException() throws Exception {
        // The null-check on the required 'html' parameter runs before the swallowing try/catch, so it propagates.
        Map<String, Object> data = data();
        data.put("toText", new HtmlToTextDirective());
        try {
            render("<@toText/>", data);
            Assertions.fail("Expected TemplateException for missing 'html' parameter");
        } catch (TemplateException expected) {
            // expected
        }
    }

    // ------------------------------------------------------------------
    // DebugDirective
    // ------------------------------------------------------------------

    @Test
    public void debug_withValueEmitsNothingToTemplateOutput() throws Exception {
        // The debug directive writes only to System.err and contributes no characters to the output.
        Map<String, Object> data = data();
        data.put("dbg", new DebugDirective());
        Assertions.assertEquals("", render("<@dbg value=\"x\"/>", data));
    }

    @Test
    public void debug_withoutValueIsOptionalAndEmitsNothing() throws Exception {
        // The 'value' parameter is optional: omitting it neither throws nor produces output.
        Map<String, Object> data = data();
        data.put("dbg", new DebugDirective());
        Assertions.assertEquals("", render("<@dbg/>", data));
    }
}
