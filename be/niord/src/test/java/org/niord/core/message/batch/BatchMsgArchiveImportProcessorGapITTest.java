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
import org.niord.core.area.Area;
import org.niord.core.area.AreaService;
import org.niord.core.batch.BatchData;
import org.niord.core.category.CategoryService;
import org.niord.core.chart.ChartService;
import org.niord.core.geojson.FeatureService;
import org.niord.core.message.Message;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageQueryService;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.message.batch.BatchMsgArchiveImportProcessor.ExtractedArchiveMessage;
import org.niord.core.message.batch.BatchMsgArchiveImportReader.ExtractedArchiveMessageVo;
import org.niord.core.message.vo.SystemMessageSeriesVo;
import org.niord.core.message.vo.SystemMessageSeriesVo.NumberSequenceType;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.PromulgationManager;
import org.niord.core.settings.SettingsService;
import org.niord.model.message.MainType;
import org.niord.model.message.MessagePartVo;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;

import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gap-filling integration tests for {@link BatchMsgArchiveImportProcessor}, the normalisation step of
 * the message-archive import batch job. This is the sibling of
 * {@link BatchMsgArchiveImportProcessorITTest} and deliberately covers the branches that suite does not:
 *
 * <ul>
 *   <li>the {@code assignDefaultSeries} branch - a carried series is overridden by the batch default,
 *       and the default is itself allow-list-validated;</li>
 *   <li>the {@code preserveStatus} branch for a <em>non-PUBLISHED public</em> status (CANCELLED), which
 *       must stamp <em>both</em> the publish-from and publish-to dates;</li>
 *   <li>the {@code createBaseData=false} area branch - an area whose lineage is not seeded resolves to
 *       {@code null} and is dropped, while an existing seeded area is kept;</li>
 *   <li>the {@code processItem(Object)} entry point - the repo-path rewrite of rich-text part
 *       descriptions and the wrapping into an {@link ExtractedArchiveMessage};</li>
 *   <li>the MANUAL number-sequence branch under force-DRAFT - a carried short ID is preserved (unlike a
 *       YEARLY/NONE series, which clears it).</li>
 * </ul>
 *
 * <p>Like the sibling, {@code processMessage}/{@code processItem} cannot be exercised through the full
 * JSR-352 batch runtime in a unit-like test, so they are reached via a {@link TestableProcessor}
 * subclass that exposes the injected collaborators and the protected {@code job} field and overrides
 * {@code getLog()} with a quiet logger. The real Niord beans are CDI-injected, so series/area/category/
 * chart/promulgation resolution all run against real seed data. Mockito is intentionally not used (it is
 * not on the niord-dk test classpath); the processor is driven with real injected beans instead.
 *
 * <p>Each test builds its own detached import {@link Message} (or {@link SystemMessageVo}) with a unique
 * uid; {@code processMessage} mutates and returns the in-memory message without persisting it, so the
 * assertions inspect the returned object directly. The {@code createBaseData=false} test seeds a real
 * {@link Area} row (flushed so it really hits the database) and then proves the processor resolves it.
 * All tests are wrapped in {@link TestTransaction} so any writes are rolled back, keeping the shared seed
 * database pristine and the tests independent and repeatable.
 *
 * <p>This is a framework-coupled test - see docs/framework-coupled-tests.md.
 */
class BatchMsgArchiveImportProcessorGapITTest extends ITTest {

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

    @Autowired
    PromulgationManager promulgationManager;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Test harness
    // ------------------------------------------------------------------------------------------- //

    /**
     * Test-only subclass that exposes the package-private/protected injected fields and the protected
     * {@code job} field for wiring, and replaces the batch-folder logger with a quiet, parent-less logger
     * so no log file is opened during the test.
     */
    static class TestableProcessor extends BatchMsgArchiveImportProcessor {
        private final java.util.logging.Logger noop = java.util.logging.Logger.getLogger("it-proc-arch-gap");
        { noop.setUseParentHandlers(false); }
        @Override protected java.util.logging.Logger getLog() { return noop; }
        void wire(MessageQueryService ms, MessageSeriesService mss, SettingsService ss, AreaService as, CategoryService cs,
                  ChartService chs, FeatureService fs, PromulgationManager pm, BatchData j) {
            this.messageQueryService=ms; this.messageSeriesService=mss; this.settingsService=ss; this.areaService=as;
            this.categoryService=cs; this.chartService=chs; this.featureService=fs; this.promulgationManager=pm;
            this.job=j;
        }
    }

    /** Builds a wired processor backed by the injected beans and the given batch job. */
    private TestableProcessor processor(BatchData job) {
        TestableProcessor p = new TestableProcessor();
        p.wire(messageQueryService, messageSeriesService, settingsService, areaService, categoryService,
                chartService, featureService, promulgationManager, job);
        return p;
    }

    /**
     * Builds a {@link BatchData} carrying every property the processor reads. The four boolean flags are
     * read as {@link Boolean} and unboxed, so all of them must be present, plus the default {@code
     * seriesId} and the {@code validMessageSeries} allow-list.
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

    /** Builds a detached import message of the given type/status carrying the given series id. */
    private Message importMessage(String uid, String seriesId, MainType mainType, Type type, Status status) {
        Message m = new Message();
        m.setUid(uid);
        m.setMessageSeries(new MessageSeries(seriesId));
        m.setMainType(mainType);
        m.setType(type);
        m.setStatus(status);
        return m;
    }

    /** Builds a detached area template carrying a single name/lang desc (no MRN, top-level). */
    private Area areaTemplate(String name, String lang) {
        Area a = new Area();
        a.checkCreateDesc(lang).setName(name);
        return a;
    }

    // ------------------------------------------------------------------------------------------- //
    // assignDefaultSeries override
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void assignDefaultSeriesOverridesCarriedSeriesWithDefault() throws Exception {
        // The import carries the seeded NM series 'dma-nm' (which WOULD resolve on its own), but the
        // batch is run with assignDefaultSeries=true and default seriesId 'dma-nw'. The carried series
        // must be discarded and replaced by the default - which is itself allow-list checked.
        Message m = importMessage(UUID.randomUUID().toString(), "dma-nm",
                MainType.NM, Type.PERMANENT_NOTICE, Status.PUBLISHED);

        BatchData job = job(false, true, false, false, "dma-nw", Set.of("dma-nw", "dma-nm"));
        Message out = processor(job).processMessage(m);

        assertEquals("dma-nw", out.getMessageSeries().getSeriesId(),
                "assignDefaultSeries=true must override the carried series with the default series");
        assertEquals(MainType.NW, out.getMainType(),
                "the main type must be realigned to the resolved default NW series");
        assertEquals(MainType.NW, out.getType().getMainType(),
                "the NM type carried on the NM import must be replaced by an NW-compatible type");
    }

    @Test
    @Transactional
    void assignDefaultSeriesStillRejectsDefaultNotOnAllowList() {
        // Even when assignDefaultSeries=true, the default seriesId must pass the allow-list check.
        // Here the carried series 'dma-nm' is allow-listed, but the default 'dma-nw' is NOT, so the
        // default cannot resolve and the import must fail rather than silently fall back.
        Message m = importMessage(UUID.randomUUID().toString(), "dma-nm",
                MainType.NM, Type.PERMANENT_NOTICE, Status.PUBLISHED);

        BatchData job = job(false, true, false, false, "dma-nw", Set.of("dma-nm"));

        Exception ex = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> processor(job).processMessage(m));
        assertTrue(ex.getMessage().contains("No valid message series"),
                "a default series not on the allow-list must fail with 'No valid message series': "
                        + ex.getMessage());
    }

    // ------------------------------------------------------------------------------------------- //
    // preserveStatus for non-PUBLISHED public status (CANCELLED)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void preserveStatusStampsBothPublishDatesForCancelled() throws Exception {
        // CANCELLED is a public, non-PUBLISHED status. With preserveStatus=true and no publish dates
        // carried, the processor must stamp BOTH the publish-from AND the publish-to date.
        Message m = importMessage(UUID.randomUUID().toString(), "dma-nw",
                MainType.NW, Type.LOCAL_WARNING, Status.CANCELLED);
        m.setPublishDateFrom(null);
        m.setPublishDateTo(null);

        BatchData job = job(false, false, true, false, "dma-nw", Set.of("dma-nw"));
        Message out = processor(job).processMessage(m);

        assertEquals(Status.CANCELLED, out.getStatus(),
                "preserveStatus must keep the original CANCELLED status");
        assertNotNull(out.getPublishDateFrom(),
                "a cancelled (public) message with no publish-from date must be stamped with one");
        assertNotNull(out.getPublishDateTo(),
                "a cancelled (non-PUBLISHED) message with no publish-to date must be stamped with one");
    }

    // ------------------------------------------------------------------------------------------- //
    // createBaseData=false area resolution / drop
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createBaseDataFalseDropsUnseededAreaAndKeepsExistingArea() throws Exception {
        // Seed one resolvable area row (flushed so it really hits the DB).
        String existingName = "IT-Arch-Area-" + UUID.randomUUID();
        Area created = areaService.importArea(areaTemplate(existingName, "en"), true, false);
        em.flush();
        assertNotNull(created.getId(), "the seeded area must have been persisted with an id");

        // Build an import message carrying TWO area templates: one matching the seeded area by name,
        // and one whose name does not exist anywhere in the tree.
        Message m = importMessage(UUID.randomUUID().toString(), "dma-nw",
                MainType.NW, Type.LOCAL_WARNING, Status.PUBLISHED);
        m.getAreas().add(areaTemplate(existingName, "en"));
        m.getAreas().add(areaTemplate("IT-Arch-Missing-" + UUID.randomUUID(), "en"));

        // createBaseData=false => importArea(create=false) returns null for the unseeded area.
        BatchData job = job(false, false, false, false, "dma-nw", Set.of("dma-nw"));
        Message out = processor(job).processMessage(m);

        assertEquals(1, out.getAreas().size(),
                "the unseeded area must be dropped and only the resolvable area kept");
        assertEquals(created.getId(), out.getAreas().get(0).getId(),
                "the kept area must be the existing seeded area row (resolved, not re-created)");
    }

    // ------------------------------------------------------------------------------------------- //
    // processItem: repo-path rewrite + ExtractedArchiveMessage wrapping
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void processItemRewritesDescRepoLinksAndWrapsResult() throws Exception {
        String uid = UUID.randomUUID().toString();
        String repoPath = "messages/aa/bb/" + uid;

        // Build an extracted-archive VO whose part description embeds a bare archive repo link.
        SystemMessageVo vo = new SystemMessageVo();
        vo.setId(uid);
        vo.setRepoPath(repoPath);
        vo.setMainType(MainType.NW);
        vo.setType(Type.LOCAL_WARNING);
        vo.setStatus(Status.PUBLISHED);
        SystemMessageSeriesVo series = new SystemMessageSeriesVo();
        series.setSeriesId("dma-nw");
        vo.setMessageSeries(series);

        MessagePartVo part = new MessagePartVo();
        // The image src embeds the bare archive repo path: "<repoPath>/x.png
        part.createDesc("en").setDetails("<p><img src=\"" + repoPath + "/x.png\"></p>");
        vo.checkCreateParts().add(part);

        ExtractedArchiveMessageVo item = new ExtractedArchiveMessageVo(vo, "temp/edit/" + repoPath);

        BatchData job = job(false, false, false, false, "dma-nw", Set.of("dma-nw"));
        Object result = processor(job).processItem(item);

        assertNotNull(result, "processItem must wrap a successfully processed message");
        assertTrue(result instanceof ExtractedArchiveMessage,
                "processItem must return an ExtractedArchiveMessage");
        ExtractedArchiveMessage extracted = (ExtractedArchiveMessage) result;

        assertEquals("temp/edit/" + repoPath, extracted.getEditRepoPath(),
                "the edit repo path of the source archive item must be carried through");

        String details = extracted.getMessage().getParts().get(0).getDescs().get(0).getDetails();
        assertEquals("<p><img src=\"/rest/repo/file/" + repoPath + "/x.png\"></p>", details,
                "the bare archive repo link must be rewritten to a /rest/repo/file/ link");
    }

    // ------------------------------------------------------------------------------------------- //
    // MANUAL number-sequence series under force-DRAFT preserves the carried short ID
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void manualNumberSequenceSeriesUnderForceDraftPreservesShortId() throws Exception {
        // Confirm 'dma-nm-annex' really is a MANUAL number-sequence series in the seed data, so the
        // behaviour under test is anchored to real configuration rather than an assumption.
        MessageSeries annex = messageSeriesService.findBySeriesId("dma-nm-annex");
        assertNotNull(annex, "the seed must contain the 'dma-nm-annex' series");
        assertEquals(NumberSequenceType.MANUAL, annex.getNumberSequenceType(),
                "'dma-nm-annex' must be a MANUAL number-sequence series");

        // Import a message on the MANUAL series with a carried short ID, NOT preserving status.
        Message m = importMessage(UUID.randomUUID().toString(), "dma-nm-annex",
                MainType.NM, Type.PERMANENT_NOTICE, Status.PUBLISHED);
        m.setShortId("B/42 2026");
        m.setPublishDateFrom(new Date());
        m.setPublishDateTo(new Date());

        BatchData job = job(false, false, false, false, "dma-nm-annex", Set.of("dma-nm-annex"));
        Message out = processor(job).processMessage(m);

        assertEquals(Status.DRAFT, out.getStatus(),
                "without preserveStatus the import must be forced back to DRAFT");
        assertEquals("B/42 2026", out.getShortId(),
                "a MANUAL number-sequence series must preserve the user-supplied short ID even on force-DRAFT");
        assertNull(out.getPublishDateFrom(), "force-DRAFT must still clear the publish-from date");
        assertNull(out.getPublishDateTo(), "force-DRAFT must still clear the publish-to date");
        assertEquals("dma-nm-annex", out.getMessageSeries().getSeriesId(),
                "the MANUAL series must be resolved from the seed");
    }
}
