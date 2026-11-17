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
package org.niord.core.script.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.script.ScriptResource;
import org.niord.core.script.vo.ScriptResourceVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Framework-neutral unit tests for {@link BatchScriptResourceImportProcessor#processItem(Object)}.
 *
 * <p>The processor is the conversion step of the script-resource import batch job: it casts each batch item
 * to a {@link ScriptResourceVo}, resets the value object's id to {@code null} (matching on path rather than
 * id during the subsequent write step) and constructs the corresponding {@link ScriptResource} entity. The
 * CDI-injected {@code ScriptResourceService} is never touched by {@code processItem}, and neither is any of
 * the batch infrastructure inherited from {@code AbstractItemHandler}, so the bean is exercised here as a
 * plain object via {@code new BatchScriptResourceImportProcessor()} — mirroring the
 * {@code BatchPromulgationTypeImportProcessorTest} approach. No batch runtime, no database and no Quarkus
 * container is involved.
 *
 * <p>These tests pin three things: (1) the documented id reset on the incoming value object, (2) the
 * value-object-to-entity field mapping (path + content carried over, type derived from the path extension,
 * id left unassigned because it was reset before construction) and (3) the unchecked cast — a
 * non-{@link ScriptResourceVo} item surfaces as a {@link ClassCastException} rather than being silently
 * skipped. Also (4) the refusal: a file the script check refuses, or whose path appears earlier in the same import,
 * becomes {@code null}, which the job skips; outside a job the refusal goes to the system log.
 */
public class BatchScriptResourceImportProcessorTest {

    private BatchScriptResourceImportProcessor processor;

    @BeforeEach
    public void setUp() {
        processor = new BatchScriptResourceImportProcessor();
    }

    /**
     * A {@link ScriptResourceVo} item carrying a non-null id is converted into a {@link ScriptResource}
     * entity that keeps the path and content verbatim, derives its type from the {@code .ftl} extension and
     * has no id assigned — because {@code processItem} resets the value object's id to {@code null} before
     * the entity is constructed.
     */
    @Test
    public void processItemWithScriptResourceVo_resetsIdAndMapsFields() throws Exception {
        ScriptResourceVo vo = new ScriptResourceVo();
        vo.setId(42);
        vo.setPath("templates/x.ftl");
        vo.setContent("<#-- freemarker body -->");
        // Deliberately set a wrong/contradictory type to prove the entity re-derives it from the path.
        vo.setType(ScriptResource.Type.JS);

        Object result = processor.processItem(vo);

        // The processor mutates the incoming VO: its id must have been reset to null.
        assertNull(vo.getId(), "processItem must reset the incoming VO id to null");

        assertTrue(result instanceof ScriptResource,
                "processItem must return a ScriptResource entity");
        ScriptResource entity = (ScriptResource) result;

        assertEquals("templates/x.ftl", entity.getPath(), "path must be copied from the VO");
        assertEquals("<#-- freemarker body -->", entity.getContent(), "content must be copied from the VO");
        assertEquals(ScriptResource.Type.FM, entity.getType(),
                "type must be derived from the .ftl path extension");
        assertNull(entity.getId(),
                "entity id must be unassigned because the VO id was reset before construction");
    }

    /**
     * A {@code .js} path resource is mapped to the {@link ScriptResource.Type#JS} type, confirming the type
     * is derived from the path extension and not merely copied from the value object.
     */
    @Test
    public void processItemWithJsPath_derivesJavaScriptType() throws Exception {
        ScriptResourceVo vo = new ScriptResourceVo();
        vo.setPath("scripts/helper.js");
        vo.setContent("function f() { return 1; }");

        Object result = processor.processItem(vo);

        ScriptResource entity = (ScriptResource) result;
        assertEquals(ScriptResource.Type.JS, entity.getType(),
                "type must be derived from the .js path extension");
        assertEquals("scripts/helper.js", entity.getPath(), "path must be copied from the VO");
        assertEquals("function f() { return 1; }", entity.getContent(),
                "content must be copied from the VO");
    }

    /**
     * The processor performs an unchecked cast to {@link ScriptResourceVo}. Feeding it an item of an
     * unrelated type fails fast with a {@link ClassCastException} rather than producing a malformed entity.
     */
    @Test
    public void processItemWithWrongType_throwsClassCastException() throws Exception {
        try {
            processor.processItem("not a vo");
            fail("Expected ClassCastException when item is not a ScriptResourceVo");
        } catch (ClassCastException expected) {
            // Expected: documents the unchecked (ScriptResourceVo) cast in processItem.
        }
    }

    /** A script the script check refuses is skipped: the processor returns null */
    @Test
    public void processItemWithARefusedScript_returnsNull() throws Exception {
        ScriptResourceVo vo = new ScriptResourceVo();
        vo.setPath("templates/tmpl/bad.js");
        vo.setContent("var r = Java.type('java.lang.Runtime');");

        assertNull(processor.processItem(vo), "a refused script is skipped");
    }

    /** A path that appears earlier in the same import is skipped the second time */
    @Test
    public void processItemWithAPathSeenBefore_returnsNull() throws Exception {
        ScriptResourceVo first = new ScriptResourceVo();
        first.setPath("templates/x.ftl");
        first.setContent("first");
        ScriptResourceVo second = new ScriptResourceVo();
        second.setPath("templates/x.ftl");
        second.setContent("second");

        assertTrue(processor.processItem(first) instanceof ScriptResource);
        assertNull(processor.processItem(second), "the second file at the path is skipped");
    }
}
