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
package org.niord.core.area.batch;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.area.Area;
import org.niord.core.area.AreaService;
import org.niord.core.area.vo.SystemAreaVo;
import org.niord.core.batch.BatchData;
import org.niord.test.ITTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for {@link BatchAreaImportProcessor#processItem}, exercised against the seeded MySQL
 * container of the {@link ITTest} infrastructure.
 *
 * <p>The processor is normally instantiated and driven by the JBeret batch runtime, which injects its
 * {@link AreaService} dependency, the per-instance {@link BatchData} ({@code job}) and a {@code FileHandler}-backed
 * logger that writes into the batch job folder. To test the business logic without that runtime, this suite uses a
 * {@link TestableProcessor} subclass declared in the same package: it overrides {@code getLog()} to return a no-op
 * {@link java.util.logging.Logger} (so the {@code FileHandler} that targets the batch folder is never opened) and
 * exposes a {@code wire(...)} method that assigns the real CDI {@link AreaService} bean and a hand-built
 * {@link BatchData} directly into the package-private/protected fields.
 *
 * <p>Every test is wrapped in {@link TestTransaction}: {@code AreaService.importArea} is {@code @Transactional} and
 * joins the test transaction, so the area rows it creates/updates are rolled back, keeping the shared seed database
 * pristine and the tests independent and repeatable. All test areas use an {@code IT-Area-} name prefix plus a random
 * UUID that does not occur in the seed data, so the by-name match key stays isolated from seeded areas.
 *
 * <p>Behaviour covered: importing a brand-new top-level area creates and persists a row; re-importing the same VO
 * (same by-name match key under a null parent) updates the existing row rather than creating a duplicate; and the
 * unchecked cast in {@code processItem} fails with {@link ClassCastException} for a wrong-type item.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchAreaImportProcessorITTest extends ITTest {

    @Autowired
    AreaService areaService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Test scaffolding
    // ------------------------------------------------------------------------------------------- //

    /**
     * A {@link BatchAreaImportProcessor} that can be driven outside the JBeret batch runtime.
     *
     * <p>It replaces the batch-folder {@code FileHandler} logger with a parent-less no-op logger (avoiding any file
     * I/O into a non-existent batch job folder) and lets the test inject the real {@link AreaService} bean and the
     * {@code job} batch data directly. The assigned fields are package-private/protected and reachable from this
     * same package.
     */
    static class TestableProcessor extends BatchAreaImportProcessor {
        private final java.util.logging.Logger noop = java.util.logging.Logger.getLogger("it-proc-area");

        {
            noop.setUseParentHandlers(false);
        }

        @Override
        protected java.util.logging.Logger getLog() {
            return noop;
        }

        void wire(AreaService as, BatchData j) {
            this.areaService = as;
            this.job = j;
        }
    }

    /** Wires a fresh {@link TestableProcessor} with the injected service and a hand-built batch data. */
    private TestableProcessor newProcessor() {
        TestableProcessor processor = new TestableProcessor();
        processor.wire(areaService, new BatchData());
        return processor;
    }

    /** Builds a detached top-level (no parent) {@link SystemAreaVo} with a single English name and no id/MRN. */
    private SystemAreaVo topLevelAreaVo(String name) {
        SystemAreaVo vo = new SystemAreaVo();
        vo.createDesc("en").setName(name);
        return vo;
    }

    // ------------------------------------------------------------------------------------------- //
    // (1) Brand-new top-level area is created and persisted
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void importsBrandNewTopLevelArea() throws Exception {
        String name = "IT-Area-" + UUID.randomUUID();
        // Precondition: no area with this unique name exists at the top level.
        assertNull(areaService.findByName(name, "en", null),
                "precondition: the unique top-level area must not already exist");

        Object result = newProcessor().processItem(topLevelAreaVo(name));

        assertNotNull(result, "processItem must return the imported area");
        Area imported = (Area) result;
        Integer id = imported.getId();
        assertNotNull(id, "importArea(create=true) must create the area and assign a generated id");
        assertEquals(name, imported.getDesc("en").getName(),
                "the imported area must carry the supplied localized name");

        // Confirm it actually persisted: reload the row by primary key from a cleared context.
        em.flush();
        em.clear();
        Area reloaded = em.find(Area.class, id);
        assertNotNull(reloaded, "the created area row must exist in the database");
        assertEquals(name, reloaded.getDesc("en").getName(),
                "the persisted area must carry the imported name");
        assertNull(reloaded.getParent(), "a top-level area must have no parent");
    }

    // ------------------------------------------------------------------------------------------- //
    // (2) Re-importing the same VO updates rather than duplicating
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void reImportingSameAreaUpdatesRatherThanDuplicating() throws Exception {
        String name = "IT-Area-" + UUID.randomUUID();
        TestableProcessor processor = newProcessor();

        // First import creates the area.
        Area first = (Area) processor.processItem(topLevelAreaVo(name));
        Integer firstId = first.getId();
        assertNotNull(firstId, "first import must create a persisted area with an id");

        // Second import of a VO with the same by-name match key (same name, null parent, no id) must
        // resolve the existing row via findByName and update it - not create a second row.
        Area second = (Area) processor.processItem(topLevelAreaVo(name));

        assertEquals(firstId, second.getId(),
                "re-importing the same area must update the existing row, returning the same id - no duplicate");

        // Confirm there is still exactly one matching top-level area in the database.
        em.flush();
        em.clear();
        Area match = areaService.findByName(name, "en", null);
        assertNotNull(match, "the single area must still be resolvable by its unique name");
        assertEquals(firstId, match.getId(),
                "the by-name lookup must resolve the same single row that the first import created");
    }

    // ------------------------------------------------------------------------------------------- //
    // (3) Wrong-type item is rejected by the unchecked cast
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void wrongTypeItemThrowsClassCastException() {
        TestableProcessor processor = newProcessor();
        // processItem casts the item to SystemAreaVo without an instanceof guard.
        assertThrows(ClassCastException.class, () -> processor.processItem("not-an-area-vo"));
    }
}
