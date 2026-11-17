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
 * Framework-neutral Tier-1 unit tests for {@link LightCharacterDirective}.
 * <p>
 * The directive emits a light character. In its default (non-verbose) mode it writes the supplied
 * light character verbatim. In {@code format="verbose"} mode it tries to resolve a
 * {@link org.niord.core.aton.LightCharacterService} via {@code CdiUtils.getBean(...)} to translate the
 * light character into human-readable prose. That CDI lookup is unavailable in a plain unit test, so the
 * directive's internal {@code catch} block ("Prefer robustness over correctness") falls back to writing
 * the raw light character. These tests therefore exercise the verbatim/blank/missing-parameter branches.
 * <p>
 * NOTE: the verbose -&gt; human-readable light-character translation performed by
 * {@link org.niord.core.aton.LightCharacterService} cannot be exercised here because it requires a CDI
 * container; that behavior is only verifiable in a Quarkus integration test.
 */
public class LightCharacterDirectiveTest {

    @Test
    public void nonVerbose_emitsLightCharacterVerbatim() throws Exception {
        // Default (non-verbose) mode writes the supplied light character unchanged.
        Map<String, Object> data = data();
        data.put("l", new LightCharacterDirective());
        Assertions.assertEquals("Fl(2) 10s", render("<@l light=\"Fl(2) 10s\"/>", data));
    }

    @Test
    public void verbose_withoutCdiContainer_fallsBackToVerbatim() throws Exception {
        // Verbose mode needs the LightCharacterService bean; with no container the catch emits the raw light.
        Map<String, Object> data = data();
        data.put("l", new LightCharacterDirective());
        Assertions.assertEquals("Fl(2) 10s", render("<@l light=\"Fl(2) 10s\" format=\"verbose\"/>", data));
    }

    @Test
    public void verbose_withExplicitLang_stillFallsBackToVerbatim() throws Exception {
        // The 'lang' parameter is accepted, but without CDI the translation is skipped and the raw light is emitted.
        Map<String, Object> data = data();
        data.put("l", new LightCharacterDirective());
        Assertions.assertEquals("Fl(2) 10s", render("<@l light=\"Fl(2) 10s\" format=\"verbose\" lang=\"da\"/>", data));
    }

    @Test
    public void verbose_withBlankLight_emitsEmptyString() throws Exception {
        // A blank light fails the isNotBlank guard, so the else branch emits the (empty) light character.
        Map<String, Object> data = data();
        data.put("l", new LightCharacterDirective());
        Assertions.assertEquals("", render("<@l light=\"\" format=\"verbose\"/>", data));
    }

    @Test
    public void nonVerbose_withBlankLight_emitsEmptyString() throws Exception {
        // Non-verbose mode with a blank light writes the empty string verbatim.
        Map<String, Object> data = data();
        data.put("l", new LightCharacterDirective());
        Assertions.assertEquals("", render("<@l light=\"\"/>", data));
    }

    @Test
    public void missingLightParameter_throwsTemplateException() throws Exception {
        // A missing required 'light' parameter throws before the internal try/catch, propagating a TemplateException.
        Map<String, Object> data = data();
        data.put("l", new LightCharacterDirective());
        try {
            render("<@l/>", data);
            Assertions.fail("Expected TemplateException for missing 'light' parameter");
        } catch (TemplateException expected) {
            // expected
        }
    }
}
