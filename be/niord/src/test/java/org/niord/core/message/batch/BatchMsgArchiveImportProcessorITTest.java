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
import org.niord.core.chart.ChartService;
import org.niord.core.geojson.FeatureService;
import org.niord.core.message.Attachment;
import org.niord.core.message.Message;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageQueryService;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.message.Reference;
import org.niord.core.settings.SettingsService;
import org.niord.model.message.MainType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;

import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link BatchMsgArchiveImportProcessor#processMessage(Message)}, the core
 * normalisation step of the message-archive import batch job, run against the seeded MySQL container
 * of the {@link ITTest} infrastructure.
 *
 * <p>{@code processMessage} cannot be exercised through the full JSR-352 batch runtime in a unit-like
 * test (it needs a {@code JobContext}, a persisted {@link BatchData} row and a job listener), so the
 * behaviour under test is reached by a {@link TestableProcessor} subclass that exposes the injected
 * collaborators and the protected {@code job} field, and overrides {@code getLog()} with a quiet
 * logger so no batch-job log file is created. The real Niord beans are CDI-injected and wired into the
 * processor, so the message-series/area/category/chart resolution and feature/attachment handling all
 * run against real seed data.
 *
 * <p>Each test builds its own detached import {@link Message} (with a unique uid) and a fresh
 * {@link BatchData} carrying the batch properties the processor reads. {@code processMessage} mutates
 * and returns the in-memory message without persisting it, so the assertions inspect the returned
 * object directly; the one test that needs a pre-existing row ({@link #skipsExistingUid()}) persists a
 * minimal blocker message and reloads via {@code em.flush()} to prove the row really hit the database.
 * All tests are wrapped in {@link TestTransaction} so any writes are rolled back, keeping the shared
 * seed database pristine and the tests independent and repeatable.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchMsgArchiveImportProcessorITTest extends ITTest {

    @Autowired
    MessageQueryService messageQueryService;

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

    @Autowired
    FeatureService featureService;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Test harness
    // ------------------------------------------------------------------------------------------- //

    /**
     * Test-only subclass that exposes the package-private/protected injected fields and the protected
     * {@code job} field for wiring, and replaces the batch-folder logger with a quiet, parent-less
     * logger so no log file is opened during the test.
     */
    static class TestableProcessor extends BatchMsgArchiveImportProcessor {
        private final java.util.logging.Logger noop = java.util.logging.Logger.getLogger("it-proc-arch");
        { noop.setUseParentHandlers(false); }
        @Override protected java.util.logging.Logger getLog() { return noop; }
        void wire(MessageQueryService ms, MessageSeriesService mss, SettingsService ss, AreaService as, CategoryService cs,
                  ChartService chs, FeatureService fs, BatchData j) {
            this.messageQueryService=ms; this.messageSeriesService=mss; this.settingsService=ss; this.areaService=as;
            this.categoryService=cs; this.chartService=chs; this.featureService=fs; this.job=j;
        }
    }

    /** Builds a wired processor backed by the injected beans and the given batch job. */
    private TestableProcessor processor(BatchData job) {
        TestableProcessor p = new TestableProcessor();
        p.wire(messageQueryService, messageSeriesService, settingsService, areaService, categoryService,
                chartService, featureService, job);
        return p;
    }

    /**
     * Builds a {@link BatchData} carrying every property {@code processMessage} reads. The four
     * boolean flags are read as {@link Boolean} and unboxed, so all of them must be present, plus the
     * default {@code seriesId} and the {@code validMessageSeries} allow-list.
     */
    private BatchData job(boolean assignNewUids, boolean assignDefaultSeries, boolean preserveStatus,
                          boolean createBaseData, String seriesId, Collection<String> validSeries) {
        BatchData j = new BatchData();
        Map<String, Object> p = j.getProperties();
        p.put("assignNewUids", assignNewUids);
        p.put("assignDefaultSeries", assignDefaultSeries);
        p.put("preserveStatus", preserveStatus);
        p.put("createBaseData", createBaseData);
        p.put("seriesId", seriesId);
        p.put("validMessageSeries", validSeries);
        return j;
    }

    /** Builds a detached import message of the given type/status on the {@code dma-nw} series. */
    private Message importMessage(String uid, Type type, Status status) {
        Message m = new Message();
        m.setUid(uid);
        m.setMessageSeries(new MessageSeries("dma-nw"));
        m.setMainType(MainType.NW);
        m.setType(type);
        m.setStatus(status);
        return m;
    }

    /** Persists a minimal blocker message row directly so a uid clash can be exercised. */
    private void persistMinimalMessage(String uid) {
        Message blocker = new Message();
        blocker.setUid(uid);
        blocker.setRepoPath("messages/" + uid);
        blocker.setMainType(MainType.NW);
        blocker.setType(Type.LOCAL_WARNING);
        blocker.setStatus(Status.DRAFT);
        em.persist(blocker);
        em.flush();
    }

    // ------------------------------------------------------------------------------------------- //
    // Status / publish-info normalisation
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void forcesDraftAndStripsPublishInfoWhenNotPreservingStatus() throws Exception {
        Message m = importMessage(UUID.randomUUID().toString(), Type.LOCAL_WARNING, Status.PUBLISHED);
        m.setShortId("NW-123-26");
        m.setPublishDateFrom(new Date());
        m.setPublishDateTo(new Date());

        BatchData job = job(false, false, false, false, "dma-nw", Set.of("dma-nw"));
        Message out = processor(job).processMessage(m);

        assertEquals(Status.DRAFT, out.getStatus(),
                "without preserveStatus the import must be forced back to DRAFT");
        assertNull(out.getPublishDateFrom(), "DRAFT import must clear the publish-from date");
        assertNull(out.getPublishDateTo(), "DRAFT import must clear the publish-to date");
        assertNull(out.getShortId(), "a non-MANUAL series must clear the carried short ID");
        assertEquals("dma-nw", out.getMessageSeries().getSeriesId(),
                "the detached series must be replaced by the resolved seeded series");
        assertNull(out.getId(), "processMessage must null the id so the message is treated as new");
    }

    @Test
    @Transactional
    void preserveStatusStampsPublishFromForPublicStatus() throws Exception {
        Message m = importMessage(UUID.randomUUID().toString(), Type.LOCAL_WARNING, Status.PUBLISHED);
        m.setPublishDateFrom(null);

        BatchData job = job(false, false, true, false, "dma-nw", Set.of("dma-nw"));
        Message out = processor(job).processMessage(m);

        assertEquals(Status.PUBLISHED, out.getStatus(),
                "preserveStatus must keep the original PUBLISHED status");
        assertNotNull(out.getPublishDateFrom(),
                "a public status with no publish-from date must be stamped with one");
        // PUBLISHED messages keep an open-ended validity, so no publish-to date is stamped.
        assertNull(out.getPublishDateTo(),
                "a PUBLISHED (vs cancelled/expired) message must not be stamped with a publish-to date");
    }

    // ------------------------------------------------------------------------------------------- //
    // UID handling
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void skipsExistingUid() throws Exception {
        String uid = UUID.randomUUID().toString();
        persistMinimalMessage(uid);

        // A fresh import message carrying the SAME uid must be skipped because a row already exists.
        Message importMsg = importMessage(uid, Type.LOCAL_WARNING, Status.PUBLISHED);
        BatchData job = job(false, false, false, false, "dma-nw", Set.of("dma-nw"));

        Message out = processor(job).processMessage(importMsg);
        assertNull(out, "an import whose uid already exists in the database must be skipped (null)");
    }

    @Test
    @Transactional
    void assignsNewUidWhenRequested() throws Exception {
        String origUid = UUID.randomUUID().toString();
        Message m = importMessage(origUid, Type.LOCAL_WARNING, Status.PUBLISHED);

        BatchData job = job(true, false, false, false, "dma-nw", Set.of("dma-nw"));
        Message out = processor(job).processMessage(m);

        assertNotEquals(origUid, out.getUid(),
                "assignNewUids must replace the imported uid with a freshly generated one");
        assertEquals(origUid, out.getLegacyId(),
                "the original uid must be preserved as the legacy id");
    }

    // ------------------------------------------------------------------------------------------- //
    // Message-series resolution & validation
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void throwsWhenNoValidSeries() {
        Message m = importMessage(UUID.randomUUID().toString(), Type.LOCAL_WARNING, Status.PUBLISHED);

        // The carried series id is dma-nw, the default seriesId job property is dma-nw, but the
        // valid-series allow-list is EMPTY, so neither the carried nor the default series resolves.
        BatchData job = job(false, false, false, false, "dma-nw", Set.of());

        Exception ex = assertThrows(Exception.class, () -> processor(job).processMessage(m));
        assertTrue(ex.getMessage().contains("No valid message series"),
                "an unresolvable series must fail with a 'No valid message series' error: " + ex.getMessage());
    }

    @Test
    @Transactional
    void adjustsTypeToSeriesMainType() throws Exception {
        // dma-nw is an NW series, but the imported message carries an NM type. The processor must
        // realign the main type to the series (NW) and replace the now-inconsistent type with an NW type.
        Message m = importMessage(UUID.randomUUID().toString(), Type.PERMANENT_NOTICE, Status.PUBLISHED);
        m.setMainType(MainType.NM); // mirror an inconsistent NM import on an NW series

        BatchData job = job(false, false, false, false, "dma-nw", Set.of("dma-nw"));
        Message out = processor(job).processMessage(m);

        assertEquals(MainType.NW, out.getMainType(),
                "the main type must be realigned to the resolved NW series");
        assertEquals(MainType.NW, out.getType().getMainType(),
                "an NM type on an NW series must be replaced by an NW-compatible type");
    }

    // ------------------------------------------------------------------------------------------- //
    // Attachment id reset
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void resetsAttachmentIds() throws Exception {
        Message m = importMessage(UUID.randomUUID().toString(), Type.LOCAL_WARNING, Status.PUBLISHED);
        Attachment att = new Attachment();
        att.setId(5);
        m.getAttachments().add(att);

        BatchData job = job(false, false, false, false, "dma-nw", Set.of("dma-nw"));
        Message out = processor(job).processMessage(m);

        assertEquals(1, out.getAttachments().size(), "the attachment must be carried through unchanged in count");
        assertNull(out.getAttachments().get(0).getId(),
                "the imported attachment id must be reset so it is persisted as a new row");
    }

    // ------------------------------------------------------------------------------------------- //
    // Missing boolean job properties (MSGSVC3)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void defaultsMissingBooleanPropertiesToFalseWithoutNpe() throws Exception {
        String uid = UUID.randomUUID().toString();
        Message m = importMessage(uid, Type.LOCAL_WARNING, Status.PUBLISHED);
        m.setPublishDateFrom(new Date());

        // Build a job that OMITS all four boolean flags (assignNewUids / assignDefaultSeries /
        // preserveStatus / createBaseData) — do not use the job(...) helper, which always supplies them.
        // Before the MSGSVC3 fix each absent flag auto-unboxed a null Boolean and threw a
        // NullPointerException at the first read; now an absent flag defaults to false.
        BatchData job = new BatchData();
        job.getProperties().put("seriesId", "dma-nw");
        job.getProperties().put("validMessageSeries", Set.of("dma-nw"));

        Message out = processor(job).processMessage(m);

        assertNotNull(out, "a job missing the boolean flags must still process the message, not NPE");
        // absent preserveStatus -> false -> status forced back to DRAFT and publish info stripped.
        assertEquals(Status.DRAFT, out.getStatus(),
                "an absent preserveStatus flag must default to false (force DRAFT)");
        assertNull(out.getPublishDateFrom(), "force-DRAFT must clear the publish-from date");
        // absent assignNewUids -> false -> the imported uid is left unchanged and no legacy id is captured.
        assertEquals(uid, out.getUid(), "an absent assignNewUids flag must default to false (uid unchanged)");
        assertNull(out.getLegacyId(), "without assignNewUids no legacy id must be captured");
    }

    // ------------------------------------------------------------------------------------------- //
    // Message ids from the archive
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void refusesAKeptShortIdWithAMarkupCharacter() {
        Message m = importMessage(UUID.randomUUID().toString(), Type.LOCAL_WARNING, Status.PUBLISHED);
        m.setShortId("NW-001-26<b>");

        // preserveStatus keeps the archive's short id, so it is checked
        BatchData job = job(false, false, true, false, "dma-nw", Set.of("dma-nw"));
        Exception ex = assertThrows(Exception.class, () -> processor(job).processMessage(m));
        assertEquals("A message short id cannot contain <, >, \" or &", ex.getMessage());
    }

    @Test
    @Transactional
    void aShortIdTheImportDropsIsNotChecked() throws Exception {
        Message m = importMessage(UUID.randomUUID().toString(), Type.LOCAL_WARNING, Status.PUBLISHED);
        m.setShortId("NW-001-26<b>");

        // Without preserveStatus a non-MANUAL series drops the archive's short id before the check
        BatchData job = job(false, false, false, false, "dma-nw", Set.of("dma-nw"));
        Message out = processor(job).processMessage(m);

        assertNull(out.getShortId(), "the dropped short id must not reach the database");
    }

    @Test
    @Transactional
    void refusesAReferencedMessageIdWithAMarkupCharacter() {
        Message m = importMessage(UUID.randomUUID().toString(), Type.LOCAL_WARNING, Status.PUBLISHED);
        Reference reference = new Reference();
        reference.setMessageId("NW-002-26&amp;");
        m.addReference(reference);

        BatchData job = job(false, false, false, false, "dma-nw", Set.of("dma-nw"));
        Exception ex = assertThrows(Exception.class, () -> processor(job).processMessage(m));
        assertEquals("A referenced message id cannot contain <, >, \" or &", ex.getMessage());
    }
}
