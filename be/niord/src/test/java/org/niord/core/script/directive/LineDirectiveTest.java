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
 * Framework-neutral JUnit4 unit tests for {@link LineDirective}, the Freemarker line-flow directive.
 * <p>
 * {@code LineDirective} operates on its nested body: it collapses all whitespace/newlines to single
 * spaces, rewrites {@code " ,"} to {@code ","} and {@code " ."} to {@code "."}, optionally upper/lower
 * cases the text, optionally wraps lines at a {@code maxLength} (via {@code TextUtils.maxLineLength}),
 * and always guarantees exactly one trailing newline. With {@code format="navtex"} it forces upper-case,
 * removes whitespace-delimited {@code "the"} words, and pins {@code maxLength} to 40. A missing body
 * raises a {@link freemarker.template.TemplateModelException} that surfaces as a {@link TemplateException}
 * out of rendering.
 * <p>
 * These tests exercise only Freemarker plus the directive, so they survive a Quarkus-to-Spring migration.
 */
public class LineDirectiveTest {

    /** Plain body is emitted unchanged apart from the always-appended trailing newline. */
    @Test
    public void plainBodyGetsTrailingNewline() throws Exception {
        Map<String, Object> data = data();
        data.put("line", new LineDirective());
        Assertions.assertEquals("hello world\n", render("<@line>hello world</@line>", data));
    }

    /** case="upper" upper-cases the entire body. */
    @Test
    public void caseUpperUppercasesBody() throws Exception {
        Map<String, Object> data = data();
        data.put("line", new LineDirective());
        Assertions.assertEquals("HELLO\n", render("<@line case=\"upper\">hello</@line>", data));
    }

    /** case="lower" lower-cases the entire body. */
    @Test
    public void caseLowerLowercasesBody() throws Exception {
        Map<String, Object> data = data();
        data.put("line", new LineDirective());
        Assertions.assertEquals("hello\n", render("<@line case=\"lower\">HELLO</@line>", data));
    }

    /** Runs of multiple spaces inside the body collapse to a single space. */
    @Test
    public void multipleSpacesCollapseToSingleSpace() throws Exception {
        Map<String, Object> data = data();
        data.put("line", new LineDirective());
        Assertions.assertEquals("a b c\n", render("<@line>a   b  c</@line>", data));
    }

    /** A space preceding a comma is removed (" ," -> ","). */
    @Test
    public void spaceBeforeCommaIsRemoved() throws Exception {
        Map<String, Object> data = data();
        data.put("line", new LineDirective());
        Assertions.assertEquals("a, b\n", render("<@line>a , b</@line>", data));
    }

    /** A space preceding a period is removed (" ." -> "."). */
    @Test
    public void spaceBeforePeriodIsRemoved() throws Exception {
        Map<String, Object> data = data();
        data.put("line", new LineDirective());
        Assertions.assertEquals("a. b\n", render("<@line>a . b</@line>", data));
    }

    /** format="navtex" upper-cases the text and removes whitespace-delimited "the" words. */
    @Test
    public void navtexFormatRemovesTheWordsAndUppercases() throws Exception {
        Map<String, Object> data = data();
        data.put("line", new LineDirective());
        Assertions.assertEquals("IN AREA\n", render("<@line format=\"navtex\">in the area</@line>", data));
    }

    /** maxLength wraps the text at the given column, inserting a newline between wrapped words. */
    @Test
    public void maxLengthWrapsBodyAtBoundary() throws Exception {
        Map<String, Object> data = data();
        data.put("line", new LineDirective());
        Assertions.assertEquals("hello\nworld\n", render("<@line maxLength=5>hello world</@line>", data));
    }

    /** maxLength=-1 is no limit, as a missing maxLength is. */
    @Test
    public void maxLengthMinusOneDoesNotWrap() throws Exception {
        Map<String, Object> data = data();
        data.put("line", new LineDirective());
        Assertions.assertEquals("hello world\n", render("<@line maxLength=-1>hello world</@line>", data));
    }

    /**
     * A maxLength of 0, or below it other than -1, is refused with a TemplateException naming the parameter.
     * A length of 0 used to loop forever, appending empty lines until the render ran out of memory.
     */
    @Test
    public void maxLengthBelowOneIsRefused() throws Exception {
        for (String maxLength : new String[] { "0", "-2" }) {
            Map<String, Object> data = data();
            data.put("line", new LineDirective());
            TemplateException e = Assertions.assertThrows(TemplateException.class,
                    () -> render("<@line maxLength=" + maxLength + ">hello world</@line>", data));
            Assertions.assertTrue(e.getMessageWithoutStackTop().contains("maxLength must be at least 1"),
                    "maxLength=" + maxLength + ": " + e.getMessageWithoutStackTop());
        }
    }

    /** format="navtex" sets its own line length, so a maxLength given beside it is not read, and not refused. */
    @Test
    public void navtexFormatIgnoresAMaxLengthBelowOne() throws Exception {
        Map<String, Object> data = data();
        data.put("line", new LineDirective());
        Assertions.assertEquals("IN AREA\n",
                render("<@line format=\"navtex\" maxLength=0>in the area</@line>", data));
    }

    /** Embedded newlines in the body are collapsed to a single space (single trailing newline remains). */
    @Test
    public void embeddedNewlinesCollapseToSingleSpace() throws Exception {
        Map<String, Object> data = data();
        data.put("line", new LineDirective());
        Assertions.assertEquals("a b\n", render("<@line>a\nb</@line>", data));
    }

    /**
     * A missing nested body throws a {@link freemarker.template.TemplateModelException}("missing body"),
     * consistent with the parameter-validation directives (LatLon, DateInterval, …). Freemarker surfaces it
     * as a {@link TemplateException} out of rendering, carrying the "missing body" description.
     */
    @Test
    public void missingBodyThrowsTemplateException() throws Exception {
        Map<String, Object> data = data();
        data.put("line", new LineDirective());
        try {
            render("<@line/>", data);
            Assertions.fail("expected a TemplateException for missing body");
        } catch (TemplateException expected) {
            Assertions.assertTrue(expected.getMessageWithoutStackTop().contains("missing body"),
                    "missing-body error should surface as a TemplateException carrying the description");
        }
    }
}
