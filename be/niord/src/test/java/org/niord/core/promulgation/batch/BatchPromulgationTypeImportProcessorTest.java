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
package org.niord.core.promulgation.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.promulgation.PromulgationType;
import org.niord.core.promulgation.vo.PromulgationTypeVo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Framework-neutral unit tests for {@link BatchPromulgationTypeImportProcessor#processItem(Object)}.
 *
 * <p>The processor is the conversion step of the promulgation-type import batch job: it casts each batch
 * item to a {@link PromulgationTypeVo} and constructs the corresponding {@link PromulgationType} entity
 * template. The CDI-injected infrastructure inherited from {@code AbstractItemHandler} (job context, logger
 * bridge, etc.) is never touched by {@code processItem}, so the bean is exercised here as a plain object via
 * {@code new BatchPromulgationTypeImportProcessor()} — mirroring the {@code TwitterMessageListenerTest}
 * approach. No broker, no database, no Quarkus container is involved.
 *
 * <p>These tests pin two things: (1) the value-object-to-entity field mapping performed by the processor and
 * (2) the documented unchecked cast — a non-{@code PromulgationTypeVo} item surfaces as a
 * {@link ClassCastException} rather than being silently skipped.
 */
public class BatchPromulgationTypeImportProcessorTest {

    private BatchPromulgationTypeImportProcessor processor;

    @BeforeEach
    public void setUp() {
        processor = new BatchPromulgationTypeImportProcessor();
    }

    /**
     * A {@link PromulgationTypeVo} item is converted into a {@link PromulgationType} entity that carries the
     * value object's non-detail fields verbatim.
     */
    @Test
    public void processItemWithPromulgationTypeVo_copiesFieldsIntoEntity() throws Exception {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        vo.setTypeId("navtex");
        vo.setServiceId("navtex");
        vo.setName("NAVTEX");
        vo.setPriority(3);
        vo.setRequirement(PromulgationType.Requirement.OPTIONAL);

        Object result = processor.processItem(vo);

        assertTrue(result instanceof PromulgationType,
                "processItem must return a PromulgationType entity");

        PromulgationType type = (PromulgationType) result;
        assertEquals("navtex", type.getTypeId(), "typeId must be copied from the VO");
        assertEquals("navtex", type.getServiceId(), "serviceId must be copied from the VO");
        assertEquals("NAVTEX", type.getName(), "name must be copied from the VO");
        assertEquals(3, type.getPriority(), "priority must be copied from the VO");
        assertEquals(PromulgationType.Requirement.OPTIONAL, type.getRequirement(),
                "requirement must be copied from the VO");
    }

    /**
     * The processor performs an unchecked cast to {@link PromulgationTypeVo}. Feeding it an item of an
     * unrelated type fails fast with a {@link ClassCastException} rather than producing a malformed entity.
     */
    @Test
    public void processItemWithWrongType_throwsClassCastException() throws Exception {
        try {
            processor.processItem("not a vo");
            fail("Expected ClassCastException when item is not a PromulgationTypeVo");
        } catch (ClassCastException expected) {
            // Expected: documents the unchecked (PromulgationTypeVo) cast in processItem.
        }
    }
}
