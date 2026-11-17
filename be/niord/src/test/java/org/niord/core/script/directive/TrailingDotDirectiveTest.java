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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.niord.core.script.directive.DirectiveTestSupport.data;
import static org.niord.core.script.directive.DirectiveTestSupport.render;

/**
 * Framework-neutral Tier-1 unit tests for {@link TrailingDotDirective}.
 * <p>
 * The directive operates on its nested body: a non-blank trimmed body that does not already end with
 * {@code "."} is trimmed and a dot is appended; a body already ending with {@code "."} is merely
 * trimmed; a blank body is emitted unchanged (no dot); and an absent body emits nothing at all.
 * <p>
 * These tests exercise that observable behavior through a real Freemarker configuration (see
 * {@link DirectiveTestSupport}) and assert exact output strings. Only Freemarker is involved, so the
 * tests survive a later Quarkus-to-Spring migration unchanged.
 */
public class TrailingDotDirectiveTest {

    @Test
    public void appendsDotWhenBodyHasNoTrailingDot() throws Exception {
        // A non-blank body without a trailing dot gets a single dot appended.
        Map<String, Object> data = data();
        data.put("t", new TrailingDotDirective());
        Assertions.assertEquals("Hello.", render("<@t>Hello</@t>", data));
    }

    @Test
    public void leavesExistingTrailingDotUntouched() throws Exception {
        // A body that already ends with a dot is left with exactly one dot (no second dot added).
        Map<String, Object> data = data();
        data.put("t", new TrailingDotDirective());
        Assertions.assertEquals("Hello.", render("<@t>Hello.</@t>", data));
    }

    @Test
    public void trimsSurroundingWhitespaceThenAppendsDot() throws Exception {
        // Leading/trailing whitespace around a non-blank body is trimmed before the dot is appended.
        Map<String, Object> data = data();
        data.put("t", new TrailingDotDirective());
        Assertions.assertEquals("Hello.", render("<@t>  Hello  </@t>", data));
    }

    @Test
    public void appendsDotEvenWhenBodyEndsWithOtherPunctuation() throws Exception {
        // A body ending in non-dot punctuation ("!") still receives an appended dot, yielding "Hello!.".
        Map<String, Object> data = data();
        data.put("t", new TrailingDotDirective());
        Assertions.assertEquals("Hello!.", render("<@t>Hello!</@t>", data));
    }

    @Test
    public void emitsBlankBodyUnchangedWithoutAddingDot() throws Exception {
        // A blank (whitespace-only) body is emitted verbatim with no dot appended.
        Map<String, Object> data = data();
        data.put("t", new TrailingDotDirective());
        Assertions.assertEquals("   ", render("<@t>   </@t>", data));
    }

    @Test
    public void emitsNothingWhenThereIsNoBody() throws Exception {
        // An empty directive call with no nested body produces no output at all.
        Map<String, Object> data = data();
        data.put("t", new TrailingDotDirective());
        Assertions.assertEquals("", render("<@t/>", data));
    }
}
