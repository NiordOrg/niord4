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
package org.niord.core.chart.batch;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchData;
import org.niord.core.chart.Chart;
import org.niord.core.chart.ChartMapper;
import org.niord.core.chart.ChartService;
import org.niord.core.chart.vo.SystemChartVo;
import org.niord.test.ITTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for {@link BatchChartImportProcessor#processItem(Object)}, exercised against the seeded MySQL
 * container of the {@link ITTest} infrastructure.
 *
 * <p>The processor is normally instantiated and driven by the JBeret batch runtime, which injects its {@link
 * ChartService} dependency, the per-instance {@link BatchData} ({@code job}) and a {@code FileHandler}-backed logger
 * that writes into the batch job folder. To test the import-filter business logic without that runtime, this suite
 * uses a {@link TestableProcessor} subclass declared in the same package: it overrides {@code getLog()} to return a
 * parent-less no-op {@link java.util.logging.Logger} (so the {@code FileHandler} that targets the batch folder is
 * never opened) and exposes a {@code wire(...)} method that assigns the real CDI {@code chartService} bean and a
 * hand-built {@code BatchData} directly into the package-private/protected fields.
 *
 * <p>The processor only decides what to <em>return</em> to the JBeret writer; it does not itself persist new charts.
 * The cases below cover the three return branches plus the unchecked-cast contract:
 * <ul>
 *     <li>NEW (no existing chart) - returns a fresh transient {@link Chart} with no id;</li>
 *     <li>CHANGED (existing chart, differing fields) - mutates and returns the managed original;</li>
 *     <li>UNCHANGED (existing chart, identical fields) - returns {@code null};</li>
 *     <li>wrong item type - throws {@link ClassCastException}.</li>
 * </ul>
 *
 * <p>Each test builds its own {@link Chart} rows (rather than relying on seed internals) and is wrapped in {@link
 * TestTransaction}, so every {@code em.persist} joins the test transaction and rolls back, keeping the shared seed
 * database pristine and the tests independent and repeatable.
 *
 * <p>This is a framework-coupled test - see docs/framework-coupled-tests.md.
 */
class BatchChartImportProcessorITTest extends ITTest {

    @Autowired
    ChartService chartService;

    @Autowired
    ChartMapper chartMapper;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Test scaffolding
    // ------------------------------------------------------------------------------------------- //

    /**
     * A {@link BatchChartImportProcessor} that can be driven outside the JBeret batch runtime: it replaces the
     * batch-folder {@code FileHandler} logger with a parent-less no-op logger (avoiding file I/O into a non-existent
     * batch job folder) and lets the test inject the real {@code chartService} and {@code chartMapper} beans and the
     * {@code job} batch data directly. All assigned fields are package-private/protected and reachable from this same
     * package.
     */
    static class TestableProcessor extends BatchChartImportProcessor {
        private final java.util.logging.Logger noop = java.util.logging.Logger.getLogger("it-proc-chart");

        {
            noop.setUseParentHandlers(false);
        }

        @Override
        protected java.util.logging.Logger getLog() {
            return noop;
        }

        void wire(ChartService cs, ChartMapper cm, BatchData j) {
            this.chartService = cs;
            this.chartMapper = cm;
            this.job = j;
        }
    }

    /**
     * Wires a fresh {@link TestableProcessor} with the injected {@link ChartService} and {@link ChartMapper} beans and
     * an empty batch data.
     */
    private TestableProcessor newProcessor() {
        TestableProcessor processor = new TestableProcessor();
        processor.wire(chartService, chartMapper, new BatchData());
        return processor;
    }

    /** Builds a detached {@link SystemChartVo} carrying the given chart number, name and scale. */
    private SystemChartVo chartVo(String chartNumber, String name, Integer scale) {
        SystemChartVo vo = new SystemChartVo();
        vo.setChartNumber(chartNumber);
        vo.setName(name);
        vo.setScale(scale);
        return vo;
    }

    /** A chart number that is guaranteed not to collide with any seeded row. */
    private String uniqueChartNumber() {
        return "IT-CH-" + UUID.randomUUID();
    }

    // ------------------------------------------------------------------------------------------- //
    // (1) NEW chart - no existing row -> return the transient template, unpersisted
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void newChartReturnsTransientTemplate() throws Exception {
        String chartNumber = uniqueChartNumber();
        assertNull(chartService.findByChartNumber(chartNumber),
                "precondition: the unique chart must not already exist");

        SystemChartVo vo = chartVo(chartNumber, "Kattegat North", 50000);

        Object result = newProcessor().processItem(vo);

        assertNotNull(result, "an unknown chart must yield a new template to persist");
        Chart chart = assertChart(result);
        assertEquals(chartNumber, chart.getChartNumber(), "the new chart must carry the imported chart number");
        assertEquals("Kattegat North", chart.getName(), "the new chart must carry the imported name");
        assertEquals(50000, chart.getScale(), "the new chart must carry the imported scale");
        assertNull(chart.getId(),
                "the processor must NOT persist the new chart itself - it has no generated id (the writer persists)");

        // And nothing was written to the database by the processor.
        em.flush();
        em.clear();
        assertNull(em.createNamedQuery("Chart.findByChartNumber", Chart.class)
                        .setParameter("chartNumber", chartNumber)
                        .getResultStream().findFirst().orElse(null),
                "the processor must not have inserted a row for the new chart");
    }

    // ------------------------------------------------------------------------------------------- //
    // (2) CHANGED chart - existing row with a differing field -> mutate and return the managed original
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void changedChartUpdatesAndReturnsExisting() throws Exception {
        String chartNumber = uniqueChartNumber();

        Chart persisted = new Chart(chartNumber, null);
        persisted.setName("Old name");
        em.persist(persisted);
        em.flush();
        Integer persistedId = persisted.getId();
        assertNotNull(persistedId, "precondition: the original chart must have a generated id");

        // Same chart number, but a different name => hasChanged() must be true.
        SystemChartVo vo = chartVo(chartNumber, "New name", null);

        Object result = newProcessor().processItem(vo);

        Chart chart = assertChart(result);
        assertEquals(persistedId, chart.getId(),
                "the changed chart must be the SAME managed original, not a new instance");
        assertEquals("New name", chart.getName(), "the original's name must be updated to the imported value");
        assertEquals(chartNumber, chart.getChartNumber(), "the original keeps its (unchanged) chart number");

        // The update is reflected on the persisted row once the transaction flushes.
        em.flush();
        em.clear();
        Chart reloaded = em.find(Chart.class, persistedId);
        assertNotNull(reloaded, "the original row must still exist");
        assertEquals("New name", reloaded.getName(), "the persisted row must reflect the updated name");
    }

    // ------------------------------------------------------------------------------------------- //
    // (3) UNCHANGED chart - existing row identical to the import -> return null (ignore)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void unchangedChartReturnsNull() throws Exception {
        String chartNumber = uniqueChartNumber();
        SystemChartVo vo = chartVo(chartNumber, "Identical", 25000);

        // Persist a chart built from the very same VO, so the import compares equal field-by-field.
        Chart persisted = new Chart(vo);
        em.persist(persisted);
        em.flush();
        assertNotNull(persisted.getId(), "precondition: the original chart must have a generated id");

        Object result = newProcessor().processItem(vo);

        assertNull(result, "an unchanged chart must be ignored by returning null");
    }

    // ------------------------------------------------------------------------------------------- //
    // (4) Wrong item type -> ClassCastException (the processor casts the item to SystemChartVo)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void wrongItemTypeThrowsClassCastException() {
        TestableProcessor processor = newProcessor();
        assertThrows(ClassCastException.class, () -> processor.processItem("not a SystemChartVo"));
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Asserts the processor result is a {@link Chart} and returns it. */
    private Chart assertChart(Object result) {
        assertNotNull(result, "the processor must return a chart for create/update branches");
        assertEquals(Chart.class, result.getClass(), "the processor must return a Chart instance");
        return (Chart) result;
    }
}
