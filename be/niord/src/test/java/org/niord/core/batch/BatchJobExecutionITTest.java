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
package org.niord.core.batch;

import org.springframework.transaction.annotation.Transactional;
import jakarta.batch.operations.JobOperator;
import org.niord.test.Tx;
import jakarta.batch.runtime.BatchStatus;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.chart.Chart;
import org.niord.core.chart.vo.SystemChartVo;
import org.niord.core.source.Source;
import org.niord.core.source.vo.SourceDescVo;
import org.niord.core.source.vo.SourceVo;
import org.niord.test.BatchJobs;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration tests that drive the JSR-352 import batch jobs through their full
 * reader &rarr; processor &rarr; writer pipeline and assert the resulting database state.
 *
 * <p>Previously the batch subsystem was only exercised at the {@code /rest/batch} authorization boundary
 * (see {@link org.niord.web.BatchRestServiceITTest}); the jobs themselves were never run to completion. These
 * tests submit a real data file via {@link BatchService} (the same code path the upload REST endpoints use),
 * then poll the asynchronous job through the injected {@link JobOperator} until it reaches a terminal
 * {@link BatchStatus} (see {@link BatchJobs}), and finally verify that the writer persisted the expected rows.
 *
 * <p>The jobs commit their own transactions on a separate thread, so these tests are NOT {@code @Transactional}:
 * every assertion reads committed state inside a fresh transaction, and every persisted row is removed in a
 * fresh transaction afterwards so the shared seed database stays pristine and the tests stay independent.
 * Unique natural keys (UUID-suffixed chart numbers / source names) keep concurrent or repeated runs isolated.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchJobExecutionITTest extends ITTest {

    @Autowired
    BatchService batchService;

    @Autowired
    JobOperator jobOperator;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // chart-import: happy path
    // ------------------------------------------------------------------------------------------- //

    @Test
    void chartImportJobCompletesAndPersistsChart() throws Exception {
        String chartNumber = "IT-JOB-CH-" + UUID.randomUUID();
        try {
            SystemChartVo vo = new SystemChartVo();
            vo.setChartNumber(chartNumber);
            vo.setName("Batch import chart");
            vo.setScale(123456);

            long execId = batchService.startBatchJobWithJsonData(
                    "chart-import", new ArrayList<>(List.of(vo)), "charts.json", new HashMap<>());

            BatchStatus status = BatchJobs.awaitCompletion(jobOperator, execId);
            assertEquals(BatchStatus.COMPLETED, status,
                    "the chart-import job must run its reader/processor/writer chain to COMPLETED");

            Chart persisted = Tx.requiringNew()
                    .call(() -> em.createQuery("select c from Chart c where c.chartNumber = :n", Chart.class)
                            .setParameter("n", chartNumber)
                            .getResultStream().findFirst().orElse(null));
            assertNotNull(persisted, "the writer must have persisted the imported chart");
            assertEquals("Batch import chart", persisted.getName(),
                    "the processor must have mapped the chart name from the VO");
            assertEquals(Integer.valueOf(123456), persisted.getScale(),
                    "the processor must have mapped the chart scale from the VO");

            // The BatchData entity is persisted by the BatchJobListener and carries the data-file name.
            long instanceId = jobOperator.getJobInstance(execId).getInstanceId();
            BatchData job = Tx.requiringNew()
                    .call(() -> batchService.findByInstanceId(instanceId));
            assertNotNull(job, "a BatchData row must be persisted for the started job");
            assertEquals("chart-import", job.getJobName());
            assertEquals("charts.json", job.getDataFileName());
        } finally {
            deleteCharts(chartNumber);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // source-import: happy path (a second, distinct pipeline + writer)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void sourceImportJobCompletesAndPersistsSource() throws Exception {
        String name = "IT Batch Source " + UUID.randomUUID();
        try {
            SourceVo vo = new SourceVo();
            vo.setActive(true);
            SourceDescVo desc = new SourceDescVo();
            desc.setLang("en");
            desc.setName(name);
            desc.setAbbreviation("ITBS");
            vo.setDescs(new ArrayList<>(List.of(desc)));

            long execId = batchService.startBatchJobWithJsonData(
                    "source-import", new ArrayList<>(List.of(vo)), "sources.json", new HashMap<>());

            BatchStatus status = BatchJobs.awaitCompletion(jobOperator, execId);
            assertEquals(BatchStatus.COMPLETED, status, "the source-import job must complete");

            Source persisted = findSourceByName(name);
            assertNotNull(persisted, "the writer must have persisted the imported source");
            assertTrue(persisted.isActive(), "the imported source must be active as supplied");
        } finally {
            deleteSourceByName(name);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // chart-import: failure path — an unparseable data file must FAIL the job, not silently pass
    // ------------------------------------------------------------------------------------------- //

    @Test
    void chartImportJobFailsOnUnparseableData() throws Exception {
        // The reader expects a JSON array of charts; a bare JSON string cannot be read as List<SystemChartVo>,
        // so reader.open() throws and the chunk step (no skip/retry configured) fails the whole job.
        long execId = batchService.startBatchJobWithJsonData(
                "chart-import", "this-is-not-a-chart-array", "bad.json", new HashMap<>());

        BatchStatus status = BatchJobs.awaitCompletion(jobOperator, execId);
        assertEquals(BatchStatus.FAILED, status,
                "an import job whose data file cannot be parsed must end FAILED, never COMPLETED");
    }

    // ------------------------------------------------------------------------------------------- //
    // helpers
    // ------------------------------------------------------------------------------------------- //

    private Source findSourceByName(String name) {
        return Tx.requiringNew().call(() ->
                em.createQuery("select distinct s from Source s join s.descs d where d.lang = 'en' and d.name = :n",
                                Source.class)
                        .setParameter("n", name)
                        .getResultStream().findFirst().orElse(null));
    }

    private void deleteCharts(String chartNumber) {
        Tx.requiringNew().run(() ->
                em.createQuery("delete from Chart where chartNumber = :n")
                        .setParameter("n", chartNumber).executeUpdate());
    }

    private void deleteSourceByName(String name) {
        Tx.requiringNew().run(() -> {
            List<Source> sources = em.createQuery(
                            "select distinct s from Source s join s.descs d where d.lang = 'en' and d.name = :n",
                            Source.class)
                    .setParameter("n", name).getResultList();
            sources.forEach(em::remove);
        });
    }
}
