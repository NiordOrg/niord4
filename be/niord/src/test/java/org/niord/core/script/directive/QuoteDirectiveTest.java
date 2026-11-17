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
package org.niord.core.script.directive;

import freemarker.template.TemplateException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.niord.core.script.directive.DirectiveTestSupport.data;
import static org.niord.core.script.directive.DirectiveTestSupport.render;

/**
 * Framework-neutral Tier-1 unit tests for {@link QuoteDirective}.
 * <p>
 * The directive wraps either its {@code text} parameter or its (trimmed) nested body in quote
 * characters chosen by the {@code format} parameter:
 * <ul>
 *   <li>{@code plain} (default / unknown formats) -&gt; a literal double-quote on each side,</li>
 *   <li>{@code angular} / {@code angular-in} -&gt; {@code &raquo;} ... {@code &laquo;},</li>
 *   <li>{@code angular-out} -&gt; {@code &laquo;} ... {@code &raquo;}.</li>
 * </ul>
 * Blank text emits nothing; bodies are trimmed before quoting; a missing body with no {@code text}
 * parameter raises a {@link freemarker.template.TemplateModelException} that surfaces as a
 * {@link TemplateException} out of render.
 * <p>
 * The directive is exercised through a real Freemarker {@link freemarker.template.Configuration} wired
 * exactly like production (see {@link DirectiveTestSupport}); only Freemarker is involved, so the tests
 * survive a later Quarkus-to-Spring migration unchanged.
 */
public class QuoteDirectiveTest {

    @Test
    public void plainFormatByDefaultWrapsTextInDoubleQuotes() throws Exception {
        // No format param defaults to "plain": a literal double-quote is emitted on each side.
        Map<String, Object> data = data();
        data.put("q", new QuoteDirective());
        Assertions.assertEquals("\"hi\"", render("<@q text=\"hi\"/>", data));
    }

    @Test
    public void angularInFormatUsesRaquoThenLaquo() throws Exception {
        // format="angular-in" wraps the text with the left "&raquo;" and right "&laquo;" entities.
        Map<String, Object> data = data();
        data.put("q", new QuoteDirective());
        Assertions.assertEquals("&raquo;hi&laquo;", render("<@q text=\"hi\" format=\"angular-in\"/>", data));
    }

    @Test
    public void angularFormatIsAliasOfAngularIn() throws Exception {
        // format="angular" is an alias of "angular-in" and emits the same "&raquo;...&laquo;" quotes.
        Map<String, Object> data = data();
        data.put("q", new QuoteDirective());
        Assertions.assertEquals("&raquo;hi&laquo;", render("<@q text=\"hi\" format=\"angular\"/>", data));
    }

    @Test
    public void angularOutFormatUsesLaquoThenRaquo() throws Exception {
        // format="angular-out" reverses the angular quotes: left "&laquo;" and right "&raquo;".
        Map<String, Object> data = data();
        data.put("q", new QuoteDirective());
        Assertions.assertEquals("&laquo;hi&raquo;", render("<@q text=\"hi\" format=\"angular-out\"/>", data));
    }

    @Test
    public void unknownFormatFallsBackToPlainDoubleQuotes() throws Exception {
        // An unrecognized format value falls through to the default branch and uses plain double-quotes.
        Map<String, Object> data = data();
        data.put("q", new QuoteDirective());
        Assertions.assertEquals("\"hi\"", render("<@q text=\"hi\" format=\"unknown\"/>", data));
    }

    @Test
    public void blankTextParameterEmitsNothing() throws Exception {
        // A blank "text" parameter is treated as empty: no quotes and no content are emitted.
        Map<String, Object> data = data();
        data.put("q", new QuoteDirective());
        Assertions.assertEquals("", render("<@q text=\"\"/>", data));
    }

    @Test
    public void bodyIsTrimmedBeforePlainQuoting() throws Exception {
        // When no text param is given, the nested body is rendered, trimmed, then wrapped in plain quotes.
        Map<String, Object> data = data();
        data.put("q", new QuoteDirective());
        Assertions.assertEquals("\"hi\"", render("<@q>  hi  </@q>", data));
    }

    @Test
    public void bodyContentRespectsAngularOutFormat() throws Exception {
        // The format parameter applies to body content the same way it applies to the text parameter.
        Map<String, Object> data = data();
        data.put("q", new QuoteDirective());
        Assertions.assertEquals("&laquo;hi&raquo;", render("<@q format=\"angular-out\">hi</@q>", data));
    }

    @Test
    public void missingTextAndMissingBodyThrowsTemplateException() throws Exception {
        // With neither a text parameter nor a body, the directive throws a TemplateModelException("missing body"),
        // consistent with the parameter-validation directives; Freemarker surfaces it as a TemplateException.
        Map<String, Object> data = data();
        data.put("q", new QuoteDirective());
        try {
            render("<@q/>", data);
            Assertions.fail("Expected a TemplateException when both text and body are absent");
        } catch (TemplateException expected) {
            Assertions.assertTrue(expected.getMessageWithoutStackTop().contains("missing body"),
                    "missing-body error should surface as a TemplateException carrying the description");
        }
    }
}
