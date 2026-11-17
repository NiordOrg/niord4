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
package org.niord.core.promulgation.batch;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.promulgation.PromulgationType;
import org.niord.core.promulgation.PromulgationTypeService;
import org.niord.core.promulgation.vo.PromulgationTypeVo;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Integration tests for {@link BatchPromulgationTypeImportWriter} — the persistence half of the
 * promulgation-type import job. The writer's distinctive logic is its create-vs-update branch: it
 * looks up an existing type by id and either {@code createPromulgationType} or
 * {@code updatePromulgationType}. Both branches are exercised here against the seeded MySQL DB and the
 * resulting database state is asserted (not merely that a method was called).
 *
 * <p>The writer is driven without JBeret by sub-classing it, silencing the batch-folder logger and
 * wiring the real {@link PromulgationTypeService}. Each test runs in {@link TestTransaction} so the
 * writes (including to the seeded {@code navtex} row) roll back.
 *
 * <p>Framework-coupled (Quarkus CDI + seeded DB) — see docs/framework-coupled-tests.md.
 */
class BatchPromulgationTypeImportWriterITTest extends ITTest {

    /** Writer sub-class that silences the batch-folder log and exposes a wiring hook. */
    static class TestableWriter extends BatchPromulgationTypeImportWriter {
        @Override
        protected Logger getLog() {
            Logger log = Logger.getLogger("BatchPromulgationTypeImportWriterITTest");
            log.setUseParentHandlers(false);
            return log;
        }

        void wire(PromulgationTypeService service) {
            this.promulgationTypeService = service;
        }
    }

    @Autowired
    PromulgationTypeService promulgationTypeService;

    @PersistenceContext
    EntityManager em;

    private TestableWriter writer() {
        TestableWriter writer = new TestableWriter();
        writer.wire(promulgationTypeService);
        return writer;
    }

    private static PromulgationType type(String typeId, String name) {
        PromulgationTypeVo vo = new PromulgationTypeVo();
        vo.setTypeId(typeId);
        vo.setServiceId("navtex");
        vo.setName(name);
        vo.setPriority(5);
        vo.setActive(Boolean.TRUE);
        vo.setRequirement(PromulgationType.Requirement.OPTIONAL);
        vo.setLanguage("en");
        return new PromulgationType(vo);
    }

    @Test
    @Transactional
    void writesNewPromulgationTypeViaCreateBranch() throws Exception {
        String typeId = "it-writer-new-type";
        assertNull(promulgationTypeService.getPromulgationType(typeId), "precondition: type must not exist");

        writer().writeItems(new ArrayList<>(List.of(type(typeId, "IT Writer New"))));
        em.flush();
        em.clear();

        PromulgationType persisted = promulgationTypeService.getPromulgationType(typeId);
        assertNotNull(persisted, "the create branch must insert a new promulgation type row");
        assertEquals("IT Writer New", persisted.getName());
        assertEquals("navtex", persisted.getServiceId());
        assertEquals(PromulgationType.Requirement.OPTIONAL, persisted.getRequirement());
    }

    @Test
    @Transactional
    void writesExistingPromulgationTypeViaUpdateBranch() throws Exception {
        // 'navtex' is seeded. Re-importing it with a changed name must take the update branch.
        PromulgationType seeded = promulgationTypeService.getPromulgationType("navtex");
        assertNotNull(seeded, "precondition: the seed defines 'navtex'");
        assertEquals("NAVTEX", seeded.getName(), "precondition: seeded name");

        writer().writeItems(new ArrayList<>(List.of(type("navtex", "NAVTEX Renamed"))));
        em.flush();
        em.clear();

        PromulgationType updated = promulgationTypeService.getPromulgationType("navtex");
        assertNotNull(updated, "the update branch must keep the existing row");
        assertEquals("NAVTEX Renamed", updated.getName(), "the update branch must apply the new name");
    }

    @Test
    @Transactional
    void writesAMixedBatchCreatingAndUpdatingInOnePass() throws Exception {
        String newId = "it-writer-mixed-type";

        writer().writeItems(new ArrayList<>(List.of(
                type(newId, "Mixed New"),
                type("navtex", "Mixed Updated"))));
        em.flush();
        em.clear();

        assertEquals("Mixed New", promulgationTypeService.getPromulgationType(newId).getName());
        assertEquals("Mixed Updated", promulgationTypeService.getPromulgationType("navtex").getName());
    }
}
