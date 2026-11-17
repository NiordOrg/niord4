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
package org.niord.core.source.batch;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchData;
import org.niord.core.source.Source;
import org.niord.core.source.SourceDesc;
import org.niord.core.source.SourceService;
import org.niord.core.source.vo.SourceDescVo;
import org.niord.core.source.vo.SourceVo;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link BatchSourceImportProcessor#processItem(Object)}, exercised against the seeded
 * MySQL container of the {@link ITTest} infrastructure.
 *
 * <p>The processor is normally instantiated and driven by the JBeret batch runtime, which injects its {@code
 * SourceService} dependency, the per-instance {@link BatchData} ({@code job}) and a {@code FileHandler}-backed logger
 * that writes into the batch job folder. To test the import-or-update business logic without that runtime, this suite
 * uses a {@link TestableProcessor} subclass declared in the same package: it overrides {@code getLog()} to return a
 * no-op {@link java.util.logging.Logger} (so the {@code FileHandler} that targets the batch folder is never opened) and
 * exposes a {@code wire(...)} method that assigns the real CDI {@code SourceService} bean and a hand-built {@code
 * BatchData} directly into the package-private/protected fields. {@code processItem} lives on the same package as this
 * test, so it (and the package-private {@code sourceService} field) are reachable directly.
 *
 * <p>Every test is wrapped in {@link TestTransaction}: any {@code Source}/{@code SourceDesc} rows persisted to set up
 * the "matching name" precondition join the test transaction and are rolled back, keeping the shared seed database
 * pristine and the tests independent and repeatable. Unique per-test source names guarantee {@code findByName} (which
 * uses {@code getSingleResult()}) sees at most one match, so a test passes whether run alone or in the full suite.
 *
 * <p>Behaviour covered: a brand-new source (no name match) is returned as a transient {@code Source} with a null id and
 * the VO id reset to null by the processor; an existing source (name match) is loaded and updated in place, returning
 * the SAME managed entity with the changed description; and the unchecked-cast contract that a wrong-typed item throws
 * {@link ClassCastException}.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchSourceImportProcessorITTest extends ITTest {

    @Autowired
    SourceService sourceService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Test scaffolding
    // ------------------------------------------------------------------------------------------- //

    /**
     * A {@link BatchSourceImportProcessor} that can be driven outside the JBeret batch runtime.
     *
     * <p>It replaces the batch-folder {@code FileHandler} logger with a parent-less no-op logger (avoiding any file
     * I/O into a non-existent batch job folder) and lets the test inject the real {@code SourceService} bean and the
     * {@code job} batch data directly. All assigned fields are package-private/protected and reachable from this same
     * package.
     */
    static class TestableProcessor extends BatchSourceImportProcessor {
        private final java.util.logging.Logger noop = java.util.logging.Logger.getLogger("it-proc-source");

        {
            noop.setUseParentHandlers(false);
        }

        @Override
        protected java.util.logging.Logger getLog() {
            return noop;
        }

        void wire(SourceService ss, BatchData j) {
            this.sourceService = ss;
            this.job = j;
        }
    }

    /** Wires a fresh {@link TestableProcessor} with the injected {@code SourceService} and a hand-built batch data. */
    private TestableProcessor newProcessor() {
        TestableProcessor processor = new TestableProcessor();
        processor.wire(sourceService, new BatchData());
        return processor;
    }

    /** Builds a {@link SourceVo} with the given id and a single English description (name + abbreviation). */
    private SourceVo sourceVo(Integer id, String name, String abbreviation) {
        SourceVo vo = new SourceVo();
        vo.setId(id);
        vo.setActive(true);
        SourceDescVo desc = new SourceDescVo();
        desc.setLang("en");
        desc.setName(name);
        desc.setAbbreviation(abbreviation);
        vo.setDescs(new ArrayList<>(List.of(desc)));
        return vo;
    }

    // ------------------------------------------------------------------------------------------- //
    // (1) New source: no existing name match -> transient Source returned, VO id reset
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void newSourceReturnedTransientAndVoIdReset() throws Exception {
        // A unique name that does not exist in the seed data, so findByName resolves nothing.
        String name = "IT-source-" + UUID.randomUUID();
        assertNull(sourceService.findByName("en", name),
                "precondition: no source with the unique name must exist yet");

        // The VO carries a non-null id to prove the processor resets it to null before building the entity.
        SourceVo vo = sourceVo(123456789, name, "ITSRC");

        Object result = newProcessor().processItem(vo);

        assertNull(vo.getId(), "the processor must reset the incoming VO id to null");

        assertNotNull(result, "processItem must return the new transient source");
        Source source = (Source) result;
        assertTrue(source.isNew(),
                "with no name match the returned source must be new (transient, no id)");
        assertNull(source.getId(), "a brand-new source must not have an id yet");
        assertEquals(1, source.getDescs().size(), "the single en description must be carried over from the VO");
        SourceDesc desc = source.getDescs().get(0);
        assertEquals("en", desc.getLang());
        assertEquals(name, desc.getName(), "the description name must match the imported VO");
        assertEquals("ITSRC", desc.getAbbreviation(), "the description abbreviation must match the imported VO");
    }

    // ------------------------------------------------------------------------------------------- //
    // (2) Existing source: name match -> same managed entity updated in place
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void existingSourceMatchedAndUpdatedInPlace() throws Exception {
        // Persist an existing source whose en name we will re-import, so findByName matches it.
        String name = "IT-source-" + UUID.randomUUID();
        Source existing = new Source();
        existing.setActive(true);
        SourceDesc existingDesc = new SourceDesc();
        existingDesc.setLang("en");
        existingDesc.setName(name);
        existingDesc.setAbbreviation("OLD");
        existing.addDesc(existingDesc);
        em.persist(existing);
        em.flush();
        Integer existingId = existing.getId();
        assertNotNull(existingId, "precondition: the existing source must have been persisted with an id");

        // Confirm findByName resolves the persisted source (this is the match the processor relies on).
        Source found = sourceService.findByName("en", name);
        assertNotNull(found, "precondition: findByName must resolve the persisted source by its en name");
        assertEquals(existingId, found.getId(), "findByName must return the persisted row");

        // Re-import the SAME en name but with a changed abbreviation.
        SourceVo vo = sourceVo(null, name, "NEW");

        Object result = newProcessor().processItem(vo);

        Source updated = (Source) result;
        assertEquals(existingId, updated.getId(),
                "a name match must return the SAME managed original source, not a new transient one");
        assertSame(found, updated,
                "the processor must update and return the very managed entity findByName resolved");
        assertEquals(1, updated.getDescs().size(),
                "after updateSource the original must carry exactly the one re-imported description");
        SourceDesc updatedDesc = updated.getDescs().get(0);
        assertEquals("en", updatedDesc.getLang());
        assertEquals(name, updatedDesc.getName(), "the matched name must be preserved");
        assertEquals("NEW", updatedDesc.getAbbreviation(),
                "updateSource must copy the changed abbreviation onto the original source");
    }

    // ------------------------------------------------------------------------------------------- //
    // (3) Wrong-typed item: the unchecked cast must fail fast
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void wrongTypeThrowsClassCastException() {
        assertThrows(ClassCastException.class, () -> newProcessor().processItem("not-a-source-vo"));
    }
}
