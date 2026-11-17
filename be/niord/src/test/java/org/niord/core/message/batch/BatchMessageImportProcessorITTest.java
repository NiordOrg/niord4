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
package org.niord.core.message.batch;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.area.AreaService;
import org.niord.core.batch.BatchData;
import org.niord.core.category.CategoryService;
import org.niord.core.chart.Chart;
import org.niord.core.chart.ChartService;
import org.niord.core.domain.Domain;
import org.niord.core.message.Message;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.message.MessageService;
import org.niord.core.message.Reference;
import org.niord.core.message.vo.SystemMessageSeriesVo.NumberSequenceType;
import org.niord.core.settings.SettingsService;
import org.niord.model.message.MainType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the heavy {@code protected} logic in {@link BatchMessageImportProcessor#processMessage},
 * exercised against the seeded MySQL container of the {@link ITTest} infrastructure.
 *
 * <p>The processor is normally instantiated and driven by the JBeret batch runtime, which injects its service
 * dependencies, the per-instance {@link BatchData} ({@code job}) and a {@code FileHandler}-backed logger that writes
 * into the batch job folder. To test the business logic without that runtime, this suite uses a {@link
 * TestableProcessor} subclass declared in the same package: it overrides {@code getLog()} to return a no-op
 * {@link java.util.logging.Logger} (so the {@code FileHandler} that targets the batch folder is never opened) and
 * exposes a {@code wire(...)} method that assigns the real CDI service beans and a hand-built {@code BatchData}
 * directly into the package-private/protected fields. {@code processMessage} is {@code protected} and this test lives
 * in {@code org.niord.core.message.batch}, so it is invoked directly.
 *
 * <p>Every test is wrapped in {@link TestTransaction}: the {@code importArea}/{@code importCategory}/{@code
 * importChart} writes join the test transaction and are rolled back, keeping the shared seed database pristine and the
 * tests independent and repeatable.
 *
 * <p>Behaviour covered: null status defaulting to {@link Status#DRAFT}; default message-series resolution from the
 * {@code seriesId} batch property; the failure mode when no series can be resolved; and base-data import that creates
 * a missing {@link Chart}, asserted by reloading the persisted row.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchMessageImportProcessorITTest extends ITTest {

    @Autowired
    MessageService messageService;

    @Autowired
    MessageSeriesService messageSeriesService;

    @Autowired
    SettingsService settingsService;

    @Autowired
    AreaService areaService;

    @Autowired
    CategoryService categoryService;

    @Autowired
    ChartService chartService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Test scaffolding
    // ------------------------------------------------------------------------------------------- //

    /**
     * A {@link BatchMessageImportProcessor} that can be driven outside the JBeret batch runtime.
     *
     * <p>It replaces the batch-folder {@code FileHandler} logger with a parent-less no-op logger (avoiding any file
     * I/O into a non-existent batch job folder) and lets the test inject the real service beans and the {@code job}
     * batch data directly. All assigned fields are package-private/protected and reachable from this same package.
     */
    static class TestableProcessor extends BatchMessageImportProcessor {
        private final java.util.logging.Logger noop = java.util.logging.Logger.getLogger("it-proc-msg");

        {
            noop.setUseParentHandlers(false);
        }

        @Override
        protected java.util.logging.Logger getLog() {
            return noop;
        }

        void wire(MessageService ms, MessageSeriesService mss, SettingsService ss, AreaService as,
                  CategoryService cs, ChartService chs, BatchData j) {
            this.messageService = ms;
            this.messageSeriesService = mss;
            this.settingsService = ss;
            this.areaService = as;
            this.categoryService = cs;
            this.chartService = chs;
            this.job = j;
        }
    }

    /** Builds a {@link BatchData} whose properties map carries the given {@code seriesId} (or none when null). */
    private BatchData jobWithSeriesId(String seriesId) {
        BatchData job = new BatchData();
        if (seriesId != null) {
            job.getProperties().put("seriesId", seriesId);
        }
        return job;
    }

    /** Wires a fresh {@link TestableProcessor} with the injected beans and the supplied batch data. */
    private TestableProcessor processorFor(BatchData job) {
        TestableProcessor processor = new TestableProcessor();
        processor.wire(messageService, messageSeriesService, settingsService, areaService, categoryService,
                chartService, job);
        return processor;
    }

    /** Builds a minimal detached NW message with a null status (the lists are empty by construction). */
    private Message minimalNwMessage() {
        Message m = new Message();
        m.setMainType(MainType.NW);
        m.setType(Type.LOCAL_WARNING);
        // status intentionally left null - processMessage must default it
        return m;
    }

    // ------------------------------------------------------------------------------------------- //
    // Status defaulting
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void defaultsStatusToDraft() throws Exception {
        TestableProcessor processor = processorFor(jobWithSeriesId("dma-nw"));
        Message m = minimalNwMessage();
        assertNull(m.getStatus(), "precondition: the message starts with no status");

        processor.processMessage(m);

        assertEquals(Status.DRAFT, m.getStatus(),
                "an imported message with no status must default to DRAFT");
    }

    // ------------------------------------------------------------------------------------------- //
    // Default message-series resolution
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void resolvesDefaultSeriesFromJobProperty() throws Exception {
        TestableProcessor processor = processorFor(jobWithSeriesId("dma-nw"));
        Message m = minimalNwMessage();
        assertNull(m.getMessageSeries(), "precondition: the message has no message series");

        processor.processMessage(m);

        assertNotNull(m.getMessageSeries(), "the default series must be resolved from the 'seriesId' job property");
        assertEquals("dma-nw", m.getMessageSeries().getSeriesId(),
                "the resolved series must be the one named by the 'seriesId' job property");
        assertNotNull(m.getMessageSeries().getId(),
                "the resolved series must be the persisted (seeded) entity, not the detached template");
    }

    @Test
    @Transactional
    void throwsWhenNoSeriesResolvable() {
        // No "seriesId" job property, and the "batchMessageSeriesId" row start-up added holds no value -> nothing resolves.
        TestableProcessor processor = processorFor(jobWithSeriesId(null));
        Message m = minimalNwMessage();

        Exception ex = assertThrows(Exception.class, () -> processor.processMessage(m));
        assertTrue(ex.getMessage().contains("No default message series"),
                "with no resolvable series the processor must fail with 'No default message series resolved', was: "
                        + ex.getMessage());
    }

    // ------------------------------------------------------------------------------------------- //
    // Base-data import (charts)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void importsChartsCreatingThem() throws Exception {
        // A chart number that does not exist in the seed data, so importChart(create=true) must create it.
        String chartNumber = "IT-" + UUID.randomUUID();
        assertNull(chartService.findByChartNumber(chartNumber),
                "precondition: the unique chart must not already exist");

        Chart template = new Chart();
        template.setChartNumber(chartNumber);

        Message m = minimalNwMessage();
        m.setCharts(new ArrayList<>(List.of(template)));

        TestableProcessor processor = processorFor(jobWithSeriesId("dma-nw"));
        processor.processMessage(m);

        // The chart collection must hold exactly the one chart, now created with a generated id.
        List<Chart> charts = m.getCharts();
        assertEquals(1, charts.size(), "the single template chart must survive the import");
        Chart imported = charts.get(0);
        assertEquals(chartNumber, imported.getChartNumber());
        assertNotNull(imported.getId(), "importChart(create=true) must create and assign an id to the new chart");

        // Confirm it actually persisted: reload the row by primary key from a cleared context.
        em.flush();
        em.clear();
        Chart reloaded = em.find(Chart.class, imported.getId());
        assertNotNull(reloaded, "the created chart row must exist in the database");
        assertEquals(chartNumber, reloaded.getChartNumber(),
                "the persisted chart must carry the imported chart number");
    }

    // ------------------------------------------------------------------------------------------- //
    // Message ids from the file
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void refusesAShortIdWithAMarkupCharacter() {
        TestableProcessor processor = processorFor(jobWithSeriesId("dma-nw"));
        for (String c : new String[] { "<", ">", "\"", "&" }) {
            Message m = minimalNwMessage();
            m.setShortId("NW-001" + c + "26");

            Exception ex = assertThrows(Exception.class, () -> processor.processMessage(m), c);
            assertEquals("A message short id cannot contain <, >, \" or &", ex.getMessage(), c);
        }
    }

    @Test
    @Transactional
    void refusesAReferencedMessageIdWithAMarkupCharacter() {
        Message m = minimalNwMessage();
        Reference reference = new Reference();
        reference.setMessageId("NW-002-26\"><script>");
        m.addReference(reference);

        Exception ex = assertThrows(Exception.class, () -> processorFor(jobWithSeriesId("dma-nw")).processMessage(m));
        assertEquals("A referenced message id cannot contain <, >, \" or &", ex.getMessage());
    }

    // ------------------------------------------------------------------------------------------- //
    // The year of an imported message
    // ------------------------------------------------------------------------------------------- //

    /**
     * An imported message (the processor, then the writer's {@code createMessage}) gets the year of its publish date
     * in the zone of its series' domain, not the JVM's. Kiritimati (UTC+14) is far from any JVM's zone a developer
     * or CI runs in: 15:00 UTC on 31 December 2019 is 05:00 on 1 January 2020 there.
     */
    @Test
    @Transactional
    void anImportedMessageTakesTheYearOfItsPublishDateInTheDomainZone() throws Exception {
        MessageSeries series = new MessageSeries("it-imp-year-" + UUID.randomUUID().toString().substring(0, 8));
        series.setMainType(MainType.NW);
        series.setNumberSequenceType(NumberSequenceType.YEARLY);
        series = messageSeriesService.createMessageSeries(series);
        Domain domain = new Domain();
        domain.setDomainId("it-imp-year-" + UUID.randomUUID().toString().substring(0, 8));
        domain.setName("Import year test domain");
        domain.setTimeZone("Pacific/Kiritimati");
        domain.getMessageSeries().add(series);
        em.persist(domain);
        em.flush();

        Message m = minimalNwMessage();
        MessageSeries ref = new MessageSeries(series.getSeriesId());
        ref.setMainType(MainType.NW);
        m.setMessageSeries(ref);
        m.setPublishDateFrom(Date.from(Instant.parse("2019-12-31T15:00:00Z")));
        m.createDesc("en").setTitle("Imported");

        processorFor(jobWithSeriesId(null)).processMessage(m);
        Message created = messageService.createMessage(m);
        em.flush();
        em.clear();

        Integer year = em.createQuery("select m.year from Message m where m.id = :id", Integer.class)
                .setParameter("id", created.getId())
                .getSingleResult();
        assertEquals(2020, year, "the year of the publish date in Kiritimati, not in the JVM's zone");
    }

    @Test
    @Transactional
    void keepsAFiringAreaShortId() throws Exception {
        Message m = minimalNwMessage();
        m.setShortId("FA/EK-D-371 2017");

        processorFor(jobWithSeriesId("dma-nw")).processMessage(m);

        assertEquals("FA/EK-D-371 2017", m.getShortId(), "a short id with slashes and spaces is imported as it is");
    }
}
