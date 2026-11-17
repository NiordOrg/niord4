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
 * Framework-neutral Tier-1 unit tests for {@link CallSignDirective}.
 * <p>
 * The directive emits a vessel call sign. In its default (non-verbose) mode it simply uppercases
 * the {@code callSign} parameter. In "verbose" mode it attempts to translate the call sign into
 * telephony codes (e.g. {@code "OUXR2" -> "Oscar Uniform X-ray Romeo Two"}) by resolving a
 * {@link org.niord.core.aton.LightCharacterService} bean via CDI.
 * <p>
 * NOTE: the verbose -> telephony-code translation path depends on {@code CdiUtils.getBean(...)},
 * which is only available inside a running CDI/Quarkus container. It therefore cannot be exercised
 * in a plain unit test and is left for a Quarkus integration test. Here we pin the documented
 * no-CDI fallback: the directive's catch-all ("Prefer robustness over correctness") writes the
 * RAW, un-uppercased call sign when the bean lookup fails.
 */
public class CallSignDirectiveTest {

    @Test
    public void nonVerboseUppercasesMixedCaseCallSign() throws Exception {
        // Default (no format) mode uppercases the supplied call sign.
        Map<String, Object> data = data();
        data.put("c", new CallSignDirective());
        Assertions.assertEquals("OUXR2", render("<@c callSign=\"ouxr2\"/>", data));
    }

    @Test
    public void nonVerboseLeavesAlreadyUppercaseCallSignUnchanged() throws Exception {
        // An already-uppercase call sign passes through uppercasing unchanged.
        Map<String, Object> data = data();
        data.put("c", new CallSignDirective());
        Assertions.assertEquals("OZ", render("<@c callSign=\"OZ\"/>", data));
    }

    @Test
    public void anyNonVerboseFormatStillUppercases() throws Exception {
        // Any "format" value other than "verbose" is treated as non-verbose and uppercases.
        Map<String, Object> data = data();
        data.put("c", new CallSignDirective());
        Assertions.assertEquals("AB", render("<@c callSign=\"ab\" format=\"plain\"/>", data));
    }

    @Test
    public void verboseWithoutCdiEmitsRawCallSign() throws Exception {
        // With no CDI container the verbose telephony lookup fails and the catch emits the raw (un-uppercased) call sign.
        Map<String, Object> data = data();
        data.put("c", new CallSignDirective());
        Assertions.assertEquals("ouxr2", render("<@c callSign=\"ouxr2\" format=\"verbose\"/>", data));
    }

    @Test
    public void verboseIsCaseInsensitiveAndStillFallsBackToRaw() throws Exception {
        // "VERBOSE" (any case) selects verbose mode; with no CDI the fallback still emits the raw call sign.
        Map<String, Object> data = data();
        data.put("c", new CallSignDirective());
        Assertions.assertEquals("oz", render("<@c callSign=\"oz\" format=\"VERBOSE\"/>", data));
    }

    @Test
    public void missingCallSignParameterThrowsTemplateException() throws Exception {
        // The required 'callSign' parameter is validated before the try/catch, so its absence propagates a TemplateException.
        Map<String, Object> data = data();
        data.put("c", new CallSignDirective());
        try {
            render("<@c/>", data);
            Assertions.fail("Expected a TemplateException for the missing 'callSign' parameter");
        } catch (TemplateException expected) {
            // expected
        }
    }
}
