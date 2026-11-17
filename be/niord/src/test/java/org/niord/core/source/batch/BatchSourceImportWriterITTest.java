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
package org.niord.core.source.batch;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.source.Source;
import org.niord.core.source.SourceDesc;
import org.niord.core.source.SourceService;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration test for {@link BatchSourceImportWriter} — the persistence half of the source import
 * job. The writer simply delegates each item to {@code sourceService.saveEntity}; this asserts that a
 * source handed to {@code writeItems} actually lands in the database (verified by re-reading it), not
 * merely that the service was invoked.
 *
 * <p>Driven without JBeret by sub-classing the writer, silencing the batch-folder logger and wiring
 * the real {@link SourceService}. Runs in {@link TestTransaction} so the write rolls back.
 *
 * <p>Framework-coupled (Quarkus CDI + seeded DB) — see docs/framework-coupled-tests.md.
 */
class BatchSourceImportWriterITTest extends ITTest {

    static class TestableWriter extends BatchSourceImportWriter {
        @Override
        protected Logger getLog() {
            Logger log = Logger.getLogger("BatchSourceImportWriterITTest");
            log.setUseParentHandlers(false);
            return log;
        }

        void wire(SourceService service) {
            this.sourceService = service;
        }
    }

    @Autowired
    SourceService sourceService;

    @PersistenceContext
    EntityManager em;

    @Test
    @Transactional
    void writeItemsPersistsTheSource() throws Exception {
        TestableWriter writer = new TestableWriter();
        writer.wire(sourceService);

        Source source = new Source();
        SourceDesc desc = source.createDesc("en");
        desc.setName("IT-Writer-Source");
        desc.setAbbreviation("ITWS");

        writer.writeItems(new ArrayList<>(List.of(source)));
        em.flush();
        Integer id = source.getId();
        assertNotNull(id, "saveEntity must assign an id to the new source");
        em.clear();

        Source found = em.find(Source.class, id);
        assertNotNull(found, "the source must be persisted to the database");
        assertEquals("IT-Writer-Source", found.getDesc("en").getName());
        assertEquals("ITWS", found.getDesc("en").getAbbreviation());
    }
}
