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
package org.niord.core.script;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.ScriptResourceFixtures;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link JsResourceService} — the Nashorn-based JavaScript resource evaluator.
 *
 * <p>The tests persist {@code ScriptResource} rows of type JS and evaluate them through the full
 * engine setup: the {@code em}/{@code messageService} bindings, the {@code updateScript} import
 * prefix that exposes selected Java types, the custom {@code load('niord:...')} scheme that pulls in
 * other resources, plus the validation paths (invalid path, non-JS resource, blank path). Since C4.2a a script
 * is read from the database only; the test script {@code test-scripts/it-common.js} comes in as a row
 * ({@link ScriptResourceFixtures}).
 *
 * <p>Nashorn is available because {@code org.openjdk.nashorn:nashorn-core} is a compile dependency of
 * the niord module; it is deprecated-for-removal on the JDK but functional on JDK 21.
 *
 * <p>Framework-coupled (Quarkus DI + Hibernate + Nashorn); see {@code docs/framework-coupled-tests.md}.
 */
class JsResourceServiceITTest extends ITTest {

    @Autowired
    JsResourceService jsService;

    @Autowired
    ScriptResourceService resourceService;

    private static final String COMMON_JS = "test-scripts/it-common.js";

    @BeforeEach
    void addTheTestScript() {
        ScriptResourceFixtures.ensure(resourceService, COMMON_JS);
    }

    private static String jsPath() {
        return "test-scripts/jrs-" + System.nanoTime() + ".js";
    }

    /** Persists a JS resource with the given content and returns its path. */
    private String persistJs(String content) {
        ScriptResource r = new ScriptResource();
        r.setPath(jsPath());
        r.setContent(content);
        resourceService.createScriptResource(r);
        return r.getPath();
    }

    // ------------------------------------------------------------------ evaluation

    @Test
    @Transactional
    void evaluate_returnsLastExpressionValue() throws Exception {
        Object result = jsService.newJsResourceBuilder()
                .resourcePath(persistJs("1 + 2;"))
                .evaluate();
        assertEquals(3, ((Number) result).intValue());
    }

    @Test
    @Transactional
    void evaluate_bindsCustomData() throws Exception {
        Object result = jsService.newJsResourceBuilder()
                .resourcePath(persistJs("y + 1;"))
                .data("y", 5)
                .evaluate();
        assertEquals(6, ((Number) result).intValue());
    }

    @Test
    @Transactional
    void evaluate_bindsEmButNotMessageService() throws Exception {
        // JsResourceService binds ONLY the entity manager as "em" (see JsResourceService.evaluate()).
        // The "messageService" binding was deliberately removed; scripts that need message services
        // resolve them via CdiUtils.getBean(...) (see common.js), so "messageService" is not a script
        // binding. Asserting it is "undefined" documents that contract and guards against a leaked global
        // from an earlier-ordered script (which would make it "object" and fail this test).
        assertEquals("object", jsService.newJsResourceBuilder()
                .resourcePath(persistJs("typeof em;")).evaluate());
        assertEquals("undefined", jsService.newJsResourceBuilder()
                .resourcePath(persistJs("typeof messageService;")).evaluate());
    }

    @Test
    @Transactional
    void evaluate_importsJavaTypesViaUpdateScriptPrefix() throws Exception {
        // updateScript() prepends "var Status = Java.type('org.niord.model.message.Status');" etc.,
        // so the enum is usable without an explicit import in the script body.
        Object result = jsService.newJsResourceBuilder()
                .resourcePath(persistJs("Status.PUBLISHED.toString();"))
                .evaluate();
        assertEquals("PUBLISHED", result.toString());
    }

    @Test
    @Transactional
    void evaluate_loadsAnotherResourceViaNiordScheme() throws Exception {
        // it-common.js (a row) defines tripleUp(); the main script load()s it via the niord: scheme.
        String main = persistJs("load('niord:test-scripts/it-common.js'); tripleUp('ab');");
        Object result = jsService.newJsResourceBuilder().resourcePath(main).evaluate();
        assertEquals("ababab", result.toString());
    }

    // ------------------------------------------------------------------ validation / errors

    @Test
    @Transactional
    void evaluate_unknownPathRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                jsService.newJsResourceBuilder().resourcePath("test-scripts/missing-" + System.nanoTime() + ".js").evaluate());
    }

    @Test
    @Transactional
    void evaluate_nonJavaScriptResourceRejected() {
        // A Freemarker (.ftl) resource is not a JS resource -> rejected before evaluation.
        ScriptResource ftl = new ScriptResource();
        ftl.setPath("test-scripts/not-js-" + System.nanoTime() + ".ftl");
        ftl.setContent("Hello");
        resourceService.createScriptResource(ftl);

        assertThrows(IllegalArgumentException.class, () ->
                jsService.newJsResourceBuilder().resourcePath(ftl.getPath()).evaluate());
    }

    @Test
    @Transactional
    void evaluate_blankPathRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                jsService.newJsResourceBuilder().resourcePath("  ").evaluate());
    }

    // ------------------------------------------------------------------ resource lookup helpers

    /** Finds a row, and returns null for a path with no row (writes none), even when the class path holds it */
    @Test
    @Transactional
    void findScriptResource_findsARowAndReturnsNullForAPathWithNoRow() {
        ScriptResource row = jsService.findScriptResource(COMMON_JS);
        assertNotNull(row);
        assertEquals(ScriptResource.Type.JS, row.getType());
        assertTrue(row.getContent().contains("tripleUp"));

        String unknown = "test-scripts/unknown-" + System.nanoTime() + ".js";
        assertNull(jsService.findScriptResource(unknown));
        assertNull(resourceService.findByPath(unknown));

        String classPathOnly = "test-scripts/template-exec-it.js";
        ScriptResource existing = resourceService.findByPath(classPathOnly);
        if (existing != null) {
            resourceService.deleteScriptResource(existing.getId());
        }
        assertNotNull(getClass().getClassLoader().getResource(classPathOnly), "the file is on the class path");
        assertNull(jsService.findScriptResource(classPathOnly));
        assertNull(resourceService.findByPath(classPathOnly), "no copy-on-use: the lookup writes no row");
    }

    @Test
    @Transactional
    void createCustomSource_returnsNamedScriptForJsAndNullForUnknown() {
        Object src = jsService.createCustomSource(COMMON_JS);
        assertNotNull(src);
        @SuppressWarnings("unchecked")
        Map<String, String> map = (Map<String, String>) src;
        assertEquals(COMMON_JS, map.get("name"));
        assertTrue(map.get("script").contains("tripleUp"));

        assertNull(jsService.createCustomSource("test-scripts/unknown-" + System.nanoTime() + ".js"));
    }

    @Test
    void updateScript_prependsJavaTypeImports() {
        // Pure method: prefixes the script with Java.type declarations for the exposed classes.
        String updated = jsService.updateScript("foo();");
        assertTrue(updated.contains("var CdiUtils = Java.type('org.niord.core.util.CdiUtils');"), updated);
        assertTrue(updated.contains("var Status = Java.type('org.niord.model.message.Status');"), updated);
        assertTrue(updated.endsWith("foo();"));
    }
}
