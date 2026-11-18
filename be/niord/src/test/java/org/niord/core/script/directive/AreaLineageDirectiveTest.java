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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.niord.core.message.vo.SystemMessageVo;

import java.util.Map;

import static org.niord.core.script.directive.DirectiveTestSupport.data;
import static org.niord.core.script.directive.DirectiveTestSupport.render;

/**
 * Framework-neutral Tier-1 unit tests for {@link AreaLineageDirective}.
 * <p>
 * {@code AreaLineageDirective} formats the area lineage (area title) of a message, optionally
 * post-processed for the NAVTEX format. The full area-lineage computation
 * ({@link org.niord.core.message.Message#computeAreaTitle}) plus the NAVTEX post-processing
 * (uppercasing, stripping the word "the", and wrapping at {@code NAVTEX_LINE_LENGTH} = 40)
 * depend on CDI beans ({@code NiordApp}, {@code MessageService}) and the database to resolve the
 * persisted areas of the message. Those code paths are ONLY reachable from a Quarkus integration
 * test (see the {@code FmTemplateService} integration test) and are deliberately NOT covered here.
 * <p>
 * In a pure unit test (no Quarkus, no CDI container, no DB) only two branches of
 * {@link AreaLineageDirective#execute} are observable:
 * <ol>
 *     <li>The required {@code message} parameter is missing &rarr; a
 *         {@link freemarker.template.TemplateException} (a {@code TemplateModelException}) is thrown
 *         <em>before</em> the directive's internal try/catch.</li>
 *     <li>A {@code message} IS supplied but no CDI container exists, so
 *         {@code CdiUtils.getBean(...)} throws and the directive's "prefer robustness over
 *         correctness" catch emits NOTHING (an empty string) and logs a warning
 *         ({@link DirectiveFallbackLogTest}).</li>
 * </ol>
 */
public class AreaLineageDirectiveTest {

    // Missing the required 'message' parameter propagates a TemplateException out of the directive (thrown before its try/catch).
    @Test
    public void missingMessageParameterThrowsTemplateException() throws Exception {
        Map<String, Object> data = data();
        data.put("a", new AreaLineageDirective());
        try {
            render("<@a/>", data);
            Assertions.fail("Expected a TemplateException because the required 'message' parameter is missing");
        } catch (freemarker.template.TemplateException expected) {
            // expected: "The 'message' parameter must be specified"
        }
    }

    // With a message but no CDI container, CdiUtils.getBean throws and the robustness catch emits an empty string (and a warning).
    @Test
    public void messageWithoutCdiContainerEmitsEmptyString() throws Exception {
        Map<String, Object> data = data();
        data.put("a", new AreaLineageDirective());
        data.put("msg", new SystemMessageVo());
        Assertions.assertEquals("", render("<@a message=msg/>", data));
    }

    // The optional lang and format=navtex parameters are accepted, but without CDI the directive still emits an empty string.
    @Test
    public void navtexFormatAndLangWithoutCdiContainerEmitsEmptyString() throws Exception {
        Map<String, Object> data = data();
        data.put("a", new AreaLineageDirective());
        data.put("msg", new SystemMessageVo());
        Assertions.assertEquals("", render("<@a message=msg lang=\"da\" format=\"navtex\"/>", data));
    }

    // An explicitly empty (default) SystemMessageVo is a valid 'message', so the directive reaches its catch and emits an empty string rather than throwing.
    @Test
    public void emptyMessageVoIsAcceptedAndEmitsEmptyString() throws Exception {
        Map<String, Object> data = data();
        data.put("a", new AreaLineageDirective());
        data.put("msg", new SystemMessageVo());
        Assertions.assertEquals("", render("<@a message=msg format=\"plain\"/>", data));
    }
}
