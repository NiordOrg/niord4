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
package org.niord.core.message;

import org.junit.jupiter.api.Test;
import org.niord.model.message.MainType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Framework-neutral unit tests for {@link MessageScriptFilterEvaluator}: the Nashorn-backed JavaScript
 * message filter that decides whether a {@link Message} is included by a filter expression. The tests
 * exercise the enum imports injected by the evaluator ({@code Status}, {@code Type}, {@code MainType}),
 * the optional {@code data} parameter binding, the null/blank filter short-circuit, the static
 * {@code EXCLUDE_ALL} singleton, invalid-script reporting and the runtime-error-to-{@code false} fallback.
 *
 * <p>These use only {@code javax.script} (Nashorn, a compile dependency of the {@code niord} module) and
 * plain domain objects, so they are expected to survive a future Quarkus-to-Spring migration unchanged.
 */
public class MessageScriptFilterEvaluatorTest {

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    private static Message message(MainType mainType, Type type, Status status) {
        Message m = new Message();
        m.setMainType(mainType);
        m.setType(type);
        m.setStatus(status);
        return m;
    }

    // ------------------------------------------------------------------------------------------- //
    // Status filter
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void validStatusFilterIncludesOnlyPublished() throws Exception {
        MessageScriptFilterEvaluator evaluator =
                new MessageScriptFilterEvaluator("msg.status == Status.PUBLISHED");

        Message published = message(MainType.NW, Type.LOCAL_WARNING, Status.PUBLISHED);
        Message draft = message(MainType.NW, Type.LOCAL_WARNING, Status.DRAFT);

        assertTrue(evaluator.includeMessage(published, null),
                "PUBLISHED message should pass a PUBLISHED status filter");
        assertFalse(evaluator.includeMessage(draft, null),
                "DRAFT message should not pass a PUBLISHED status filter");
    }

    // ------------------------------------------------------------------------------------------- //
    // Type filter (disjunction)
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void typeFilterMatchesEither() throws Exception {
        MessageScriptFilterEvaluator evaluator = new MessageScriptFilterEvaluator(
                "msg.type == Type.TEMPORARY_NOTICE || msg.type == Type.PRELIMINARY_NOTICE");

        Message temporary = message(MainType.NM, Type.TEMPORARY_NOTICE, Status.PUBLISHED);
        Message permanent = message(MainType.NM, Type.PERMANENT_NOTICE, Status.PUBLISHED);

        assertTrue(evaluator.includeMessage(temporary, null),
                "TEMPORARY_NOTICE is one of the accepted types");
        assertFalse(evaluator.includeMessage(permanent, null),
                "PERMANENT_NOTICE is not one of the accepted types");
    }

    // ------------------------------------------------------------------------------------------- //
    // Main type filter
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void mainTypeFilter() throws Exception {
        MessageScriptFilterEvaluator evaluator =
                new MessageScriptFilterEvaluator("msg.mainType == MainType.NW");

        Message nw = message(MainType.NW, Type.LOCAL_WARNING, Status.PUBLISHED);
        Message nm = message(MainType.NM, Type.PERMANENT_NOTICE, Status.PUBLISHED);

        assertTrue(evaluator.includeMessage(nw, null), "NW message should pass a MainType.NW filter");
        assertFalse(evaluator.includeMessage(nm, null), "NM message should not pass a MainType.NW filter");
    }

    // ------------------------------------------------------------------------------------------- //
    // Compound filter (conjunction of type + status)
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void compoundFilter() throws Exception {
        MessageScriptFilterEvaluator evaluator = new MessageScriptFilterEvaluator(
                "(msg.type == Type.TEMPORARY_NOTICE) && msg.status == Status.PUBLISHED");

        Message published = message(MainType.NM, Type.TEMPORARY_NOTICE, Status.PUBLISHED);
        Message draft = message(MainType.NM, Type.TEMPORARY_NOTICE, Status.DRAFT);

        assertTrue(evaluator.includeMessage(published, null), "Both conjuncts hold -> included");
        assertFalse(evaluator.includeMessage(draft, null), "Status conjunct fails -> excluded");
    }

    // ------------------------------------------------------------------------------------------- //
    // The optional 'data' parameter binding
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void dataParameterBinds() throws Exception {
        MessageScriptFilterEvaluator evaluator = new MessageScriptFilterEvaluator("data == true");

        Message msg = message(MainType.NW, Type.LOCAL_WARNING, Status.PUBLISHED);

        assertTrue(evaluator.includeMessage(msg, Boolean.TRUE),
                "data == Boolean.TRUE should evaluate true");
        assertFalse(evaluator.includeMessage(msg, Boolean.FALSE),
                "data == Boolean.FALSE should evaluate false");
    }

    // ------------------------------------------------------------------------------------------- //
    // Null / blank filters never include
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void nullFilterNeverIncludes() throws Exception {
        MessageScriptFilterEvaluator evaluator = new MessageScriptFilterEvaluator(null);

        Message msg = message(MainType.NW, Type.LOCAL_WARNING, Status.PUBLISHED);

        assertFalse(evaluator.includeMessage(msg, null),
                "A null filter compiles to no filter function and includes nothing");
        assertNull(evaluator.getFilter(), "getFilter() returns the configured (null) filter");
    }

    @Test
    public void blankFilterNeverIncludes() throws Exception {
        MessageScriptFilterEvaluator evaluator = new MessageScriptFilterEvaluator("   ");

        Message msg = message(MainType.NW, Type.LOCAL_WARNING, Status.PUBLISHED);

        assertFalse(evaluator.includeMessage(msg, null),
                "A blank filter compiles to no filter function and includes nothing");
    }

    // ------------------------------------------------------------------------------------------- //
    // getFilter()
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void getFilterReturnsConfiguredFilter() throws Exception {
        String filter = "msg.status == Status.PUBLISHED";
        MessageScriptFilterEvaluator evaluator = new MessageScriptFilterEvaluator(filter);
        assertEquals(filter, evaluator.getFilter());
    }

    // ------------------------------------------------------------------------------------------- //
    // EXCLUDE_ALL singleton
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void excludeAllStaticAlwaysFalse() {
        Message published = message(MainType.NW, Type.LOCAL_WARNING, Status.PUBLISHED);
        assertFalse(MessageScriptFilterEvaluator.EXCLUDE_ALL.includeMessage(published, null),
                "EXCLUDE_ALL must reject every message");
    }

    // ------------------------------------------------------------------------------------------- //
    // Invalid script -> constructor throws with a descriptive message
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void invalidScriptThrowsWithMessage() {
        try {
            new MessageScriptFilterEvaluator("=== not valid javascript ===");
            fail("A syntactically invalid script must raise an exception from the constructor");
        } catch (Exception e) {
            assertTrue(e.getMessage() != null && e.getMessage().startsWith("Invalid message script:"),
                    "Exception message should describe the invalid script, was: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Sandbox (F-X43): the filter is publication data, so it runs without Java, exit/quit or load
    // ------------------------------------------------------------------------------------------- //

    @Test
    void filterRunsWithoutJavaAndWithoutProcessOrLoadFunctions() throws Exception {
        // Checked with typeof: calling quit() itself would end the test JVM if the sandbox regressed.
        MessageScriptFilterEvaluator evaluator = new MessageScriptFilterEvaluator(
                "typeof Java === 'undefined' && typeof Packages === 'undefined' && typeof java === 'undefined'"
                        + " && typeof quit === 'undefined' && typeof exit === 'undefined'"
                        + " && typeof load === 'undefined' && typeof loadWithNewGlobal === 'undefined'");

        assertTrue(evaluator.includeMessage(message(MainType.NW, Type.LOCAL_WARNING, Status.PUBLISHED), null),
                "none of these globals exists in the filter's engine");
    }

    @Test
    void javaClassLookupInAFilterFindsNothing() throws Exception {
        // Pre-fix: Java.type resolves the class and the filter includes the message.
        MessageScriptFilterEvaluator evaluator =
                new MessageScriptFilterEvaluator("Java.type('java.lang.System') != null");

        assertFalse(evaluator.includeMessage(message(MainType.NW, Type.LOCAL_WARNING, Status.PUBLISHED), null));
    }

    @Test
    void theDefaultPublicationFilterStillWorks() throws Exception {
        // PublicationService.DEFAULT_MESSAGE_TAG_FILTER: the enums and the data map are still reachable
        MessageScriptFilterEvaluator evaluator = new MessageScriptFilterEvaluator(
                "data.phase == 'msg-status-change' && (msg.status == Status.PUBLISHED || data.isIncluded)");
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("phase", "msg-status-change");
        data.put("isIncluded", false);

        assertTrue(evaluator.includeMessage(message(MainType.NW, Type.LOCAL_WARNING, Status.PUBLISHED), data));
        assertFalse(evaluator.includeMessage(message(MainType.NW, Type.LOCAL_WARNING, Status.EXPIRED), data));
        data.put("isIncluded", true);
        assertTrue(evaluator.includeMessage(message(MainType.NW, Type.LOCAL_WARNING, Status.EXPIRED), data));
    }

    // ------------------------------------------------------------------------------------------- //
    // Runtime error inside the filter is swallowed to false
    // ------------------------------------------------------------------------------------------- //

    @Test
    public void runtimeErrorInsideFilterIsSwallowedToFalse() throws Exception {
        // The script compiles fine (it is syntactically valid), but calling a non-existent method
        // throws at evaluation time. includeMessage() must catch this and return false.
        MessageScriptFilterEvaluator evaluator = new MessageScriptFilterEvaluator("msg.noSuchMethod()");

        Message msg = message(MainType.NW, Type.LOCAL_WARNING, Status.PUBLISHED);

        assertFalse(evaluator.includeMessage(msg, null),
                "A runtime error inside the filter function should be swallowed to false");
    }
}
