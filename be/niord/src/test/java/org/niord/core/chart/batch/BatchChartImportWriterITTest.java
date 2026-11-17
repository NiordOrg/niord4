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
package org.niord.core.chart.batch;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.chart.Chart;
import org.niord.core.chart.ChartService;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration test for {@link BatchChartImportWriter} — the persistence half of the chart import job.
 * The writer delegates each item to {@code chartService.saveEntity}; this asserts the chart actually
 * lands in the database (verified by re-reading it and its fields), not merely that the service was
 * invoked.
 *
 * <p>Driven without JBeret by sub-classing the writer, silencing the batch-folder logger and wiring
 * the real {@link ChartService}. Runs in {@link TestTransaction} so the write rolls back.
 *
 * <p>Framework-coupled (Quarkus CDI + seeded DB) — see docs/framework-coupled-tests.md.
 */
class BatchChartImportWriterITTest extends ITTest {

    static class TestableWriter extends BatchChartImportWriter {
        @Override
        protected Logger getLog() {
            Logger log = Logger.getLogger("BatchChartImportWriterITTest");
            log.setUseParentHandlers(false);
            return log;
        }

        void wire(ChartService service) {
            this.chartService = service;
        }
    }

    @Autowired
    ChartService chartService;

    @PersistenceContext
    EntityManager em;

    @Test
    @Transactional
    void writeItemsPersistsTheChart() throws Exception {
        TestableWriter writer = new TestableWriter();
        writer.wire(chartService);

        Chart chart = new Chart("IT-W-99999", 99999);
        chart.setName("IT Writer Chart");
        chart.setScale(50000);

        writer.writeItems(new ArrayList<>(List.of(chart)));
        em.flush();
        Integer id = chart.getId();
        assertNotNull(id, "saveEntity must assign an id to the new chart");
        em.clear();

        Chart found = em.find(Chart.class, id);
        assertNotNull(found, "the chart must be persisted to the database");
        assertEquals("IT-W-99999", found.getChartNumber());
        assertEquals(Integer.valueOf(50000), found.getScale());
    }
}
