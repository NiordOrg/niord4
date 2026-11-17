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

import org.junit.jupiter.api.Test;
import org.niord.core.batch.AbstractItemHandler;
import org.niord.core.batch.BatchData;
import org.niord.core.category.CategoryService;
import org.niord.core.chart.ChartService;
import org.niord.core.area.AreaService;
import org.niord.core.geojson.FeatureCollection;
import org.niord.core.geojson.FeatureService;
import org.niord.core.message.Message;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.message.MessageService;
import org.niord.core.message.MessageQueryService;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessageTag;
import org.niord.core.message.MessageTagService;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.promulgation.PromulgationManager;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsService;
import org.niord.model.message.MainType;
import org.niord.model.message.Status;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Plain-JUnit unit tests for the message-import batch processors and the abstract import writer.
 *
 * <p>{@link BatchMessageImportProcessor}, {@link BatchMsgArchiveImportProcessor} and
 * {@link AbstractMessageImportWriter} are {@code @Dependent} CDI beans, but their import logic does not
 * actually require the container: every collaborator is reachable through an {@code @Inject} field. So the
 * beans are built as bare objects, Mockito mocks are pushed into the {@code @Inject} fields by reflection
 * (exactly as {@code MessageImportTagAssignmentTest} does for the writer) and the package-visible
 * {@code processMessage(...)} hooks are invoked directly. No Quarkus, no DB, no batch runtime is started.
 *
 * <p>The {@code job} ({@link BatchData}) field is inherited from {@link AbstractItemHandler}; its
 * {@code properties} map drives most of the import branches, so each test seeds the exact keys the code
 * reads ({@code seriesId}, {@code assignNewUids}, {@code assignDefaultSeries}, {@code preserveStatus},
 * {@code validMessageSeries}). Every assertion checks a concrete outcome — a defaulted field, a returned
 * series instance, a mock interaction count or a thrown exception — never mere non-nullness.
 */
public class BatchMessageProcessorUnitTest {

    // --------------------------------------------------------------------------------------------- //
    // reflection helpers — inject mocks / state into the @Inject + inherited fields
    // --------------------------------------------------------------------------------------------- //

    /**
     * Sets an {@code @Inject} field on the target, walking up the class hierarchy so it also works for the
     * anonymous {@link AbstractMessageImportWriter} subclass (whose field is declared on the super-class).
     */
    private static void set(Object target, String field, Object value) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Could not set field " + field, e);
            }
        }
        throw new RuntimeException("No such field " + field);
    }

    /**
     * Sets the {@code job} field declared on the {@link AbstractItemHandler} super-class, and pre-seeds the
     * private {@code log} field so {@code getLog()} short-circuits and never touches the (absent) JobContext
     * / BatchService file-logging machinery. {@code processMessage(...)} calls {@code getLog().info(...)},
     * so without this the handler NPEs on a null {@code jobContext} rather than exercising the import logic.
     */
    private static void setJob(AbstractItemHandler target, BatchData job) {
        try {
            Field jobField = AbstractItemHandler.class.getDeclaredField("job");
            jobField.setAccessible(true);
            jobField.set(target, job);

            Field logField = AbstractItemHandler.class.getDeclaredField("log");
            logField.setAccessible(true);
            logField.set(target, java.util.logging.Logger.getLogger("BatchMessageProcessorUnitTest"));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Could not set job/log", e);
        }
    }

    private static BatchData job() {
        return new BatchData();
    }

    private static MessageSeries series(String seriesId, MainType mainType) {
        MessageSeries ms = new MessageSeries();
        ms.setSeriesId(seriesId);
        ms.setMainType(mainType);
        return ms;
    }

    // =============================================================================================== //
    // BatchMessageImportProcessor.processItem / processMessage
    // =============================================================================================== //

    /**
     * Builds a {@link BatchMessageImportProcessor} with all collaborators mocked and a seeded job, and a
     * default message series wired up via the "seriesId" batch property so processMessage can resolve one.
     */
    private BatchMessageImportProcessor importProcessor(MessageSeriesService seriesService) {
        BatchMessageImportProcessor p = new BatchMessageImportProcessor();
        set(p, "messageService", mock(MessageService.class));
        set(p, "messageSeriesService", seriesService);
        set(p, "settingsService", mock(SettingsService.class));
        set(p, "areaService", mock(AreaService.class));
        set(p, "categoryService", mock(CategoryService.class));
        set(p, "chartService", mock(ChartService.class));
        return p;
    }

    @Test
    public void importProcessItem_defaultsStatusToDraftAndResolvesDefaultSeries() throws Exception {
        MessageSeriesService seriesService = mock(MessageSeriesService.class);
        MessageSeries defaultSeries = series("dma-nw", MainType.NW);
        when(seriesService.findBySeriesId("dma-nw")).thenReturn(defaultSeries);

        BatchMessageImportProcessor p = importProcessor(seriesService);
        BatchData job = job();
        job.getProperties().put("seriesId", "dma-nw");
        setJob(p, job);

        // A SystemMessageVo with no status and no message series — the two defaulting branches.
        SystemMessageVo vo = new SystemMessageVo();

        Object result = p.processItem(vo);

        assertTrue(result instanceof Message, "processItem must return a Message entity");
        Message message = (Message) result;
        assertEquals(Status.DRAFT, message.getStatus(), "missing status must default to DRAFT");
        assertSame(defaultSeries, message.getMessageSeries(),
                "missing message series must be the resolved default series");
    }

    @Test
    public void importProcessMessage_keepsExplicitStatusAndSeries() throws Exception {
        MessageSeriesService seriesService = mock(MessageSeriesService.class);
        BatchMessageImportProcessor p = importProcessor(seriesService);
        setJob(p, job());

        MessageSeries explicit = series("local-nm", MainType.NM);
        Message message = new Message();
        message.setStatus(Status.VERIFIED);
        message.setMessageSeries(explicit);

        Message result = p.processMessage(message);

        // Neither defaulting branch must fire when both are already set.
        assertEquals(Status.VERIFIED, result.getStatus(), "an explicit status must be preserved");
        assertSame(explicit, result.getMessageSeries(), "an explicit series must be preserved");
        // No default series lookup should have happened.
        verify(seriesService, never()).findBySeriesId(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    public void importProcessMessage_numberWithBlankShortIdTriggersIdUpdate() throws Exception {
        MessageSeriesService seriesService = mock(MessageSeriesService.class);
        BatchMessageImportProcessor p = importProcessor(seriesService);
        setJob(p, job());

        MessageSeries explicitSeries = series("dma-nw", MainType.NW);
        Message message = new Message();
        message.setMessageSeries(explicitSeries);
        message.setNumber(42);
        message.setShortId("   "); // blank — triggers the message-id update

        Message result = p.processMessage(message);

        // number != null && blank shortId => updateMessageIdsFromMessageSeries(message, false)
        verify(seriesService, times(1)).updateMessageIdsFromMessageSeries(message, false);
        // Final state: processMessage ran to completion on the same instance it returns, defaulting the
        // (missing) status to DRAFT and leaving the already-set series untouched.
        assertSame(message, result, "processMessage must return the same message instance it processed");
        assertEquals(Status.DRAFT, message.getStatus(),
                "a message imported with no status must be defaulted to DRAFT");
        assertSame(explicitSeries, message.getMessageSeries(), "an already-set series must not be re-resolved");
    }

    @Test
    public void importProcessMessage_doesNotUpdateIdsWhenShortIdPresent() throws Exception {
        MessageSeriesService seriesService = mock(MessageSeriesService.class);
        BatchMessageImportProcessor p = importProcessor(seriesService);
        setJob(p, job());

        Message message = new Message();
        message.setMessageSeries(series("dma-nw", MainType.NW));
        message.setNumber(42);
        message.setShortId("NW-042-25"); // non-blank — branch must NOT fire

        Message result = p.processMessage(message);

        verify(seriesService, never())
                .updateMessageIdsFromMessageSeries(org.mockito.ArgumentMatchers.any(Message.class),
                        org.mockito.ArgumentMatchers.anyBoolean());
        // Final state: with the id-update branch skipped, the supplied shortId survives verbatim, while
        // the rest of processMessage still runs (status defaulted to DRAFT) and returns the same message.
        assertEquals("NW-042-25", message.getShortId(),
                "a present shortId must be left untouched when the id-update branch is skipped");
        assertEquals(Status.DRAFT, message.getStatus(), "the missing status must still be defaulted to DRAFT");
        assertSame(message, result, "processMessage must return the same message instance");
    }

    @Test
    public void importProcessMessage_doesNotUpdateIdsWhenNumberMissing() throws Exception {
        MessageSeriesService seriesService = mock(MessageSeriesService.class);
        BatchMessageImportProcessor p = importProcessor(seriesService);
        setJob(p, job());

        Message message = new Message();
        message.setMessageSeries(series("dma-nw", MainType.NW));
        message.setNumber(null); // no number — branch must NOT fire even though shortId is blank
        message.setShortId(null);

        Message result = p.processMessage(message);

        verify(seriesService, never())
                .updateMessageIdsFromMessageSeries(org.mockito.ArgumentMatchers.any(Message.class),
                        org.mockito.ArgumentMatchers.anyBoolean());
        // Final state: with no number the id-update branch never fires, so number/shortId stay null;
        // the standard defaulting still applies and the same instance is returned.
        assertNull(message.getNumber(), "number must stay null when never assigned");
        assertNull(message.getShortId(), "shortId must stay null when the id-update branch does not fire");
        assertEquals(Status.DRAFT, message.getStatus(), "the missing status must still be defaulted to DRAFT");
        assertSame(message, result, "processMessage must return the same message instance");
    }

    // --------------------------------------------------------------------------------------------- //
    // BatchMessageImportProcessor.resolveDefaultMessageSeries — caching & failure
    // --------------------------------------------------------------------------------------------- //

    @Test
    public void importDefaultSeries_isResolvedOnceAndCachedAcrossMessages() throws Exception {
        MessageSeriesService seriesService = mock(MessageSeriesService.class);
        MessageSeries defaultSeries = series("dma-nw", MainType.NW);
        when(seriesService.findBySeriesId("dma-nw")).thenReturn(defaultSeries);

        BatchMessageImportProcessor p = importProcessor(seriesService);
        BatchData job = job();
        job.getProperties().put("seriesId", "dma-nw");
        setJob(p, job);

        // First message resolves the default series ...
        Message m1 = new Message();
        p.processMessage(m1);
        // ... a second message with no series must reuse the cached instance.
        Message m2 = new Message();
        p.processMessage(m2);

        assertSame(m1.getMessageSeries(), m2.getMessageSeries(),
                "both messages must share the cached default series");
        // The lookup must only have happened once — the second call hits the cache.
        verify(seriesService, times(1)).findBySeriesId("dma-nw");
    }

    @Test
    public void importDefaultSeries_fallsBackToSettingWhenNoBatchProperty() throws Exception {
        MessageSeriesService seriesService = mock(MessageSeriesService.class);
        MessageSeries settingSeries = series("setting-nw", MainType.NW);
        when(seriesService.findBySeriesId("setting-nw")).thenReturn(settingSeries);

        BatchMessageImportProcessor p = importProcessor(seriesService);
        // No "seriesId" job property — resolution falls through to the system setting.
        setJob(p, job());
        SettingsService settings = mock(SettingsService.class);
        when(settings.getString(org.mockito.ArgumentMatchers.any(Setting.class))).thenReturn("setting-nw");
        set(p, "settingsService", settings);

        Message message = new Message();
        p.processMessage(message);

        assertSame(settingSeries, message.getMessageSeries(),
                "series must be resolved from the system setting fallback");
    }

    @Test
    public void importDefaultSeries_throwsWhenNothingResolves() {
        MessageSeriesService seriesService = mock(MessageSeriesService.class);
        BatchMessageImportProcessor p = importProcessor(seriesService);
        setJob(p, job()); // no seriesId property
        SettingsService settings = mock(SettingsService.class);
        when(settings.getString(org.mockito.ArgumentMatchers.any(Setting.class))).thenReturn(null);
        set(p, "settingsService", settings);

        Message message = new Message(); // no series of its own

        try {
            p.processMessage(message);
            fail("processMessage must propagate the 'no default series' failure");
        } catch (Exception e) {
            assertEquals("No default message series resolved", e.getMessage());
        }
    }

    // =============================================================================================== //
    // BatchMsgArchiveImportProcessor.processMessage
    // =============================================================================================== //

    /** Builds an archive processor with mocks and a job seeded with the boolean flags the code unboxes. */
    private BatchMsgArchiveImportProcessor archiveProcessor(MessageQueryService messageQueryService,
                                                            MessageSeriesService seriesService,
                                                            FeatureService featureService,
                                                            BatchData job) {
        BatchMsgArchiveImportProcessor p = new BatchMsgArchiveImportProcessor();
        set(p, "messageQueryService", messageQueryService);
        set(p, "messageSeriesService", seriesService);
        set(p, "settingsService", mock(SettingsService.class));
        set(p, "areaService", mock(AreaService.class));
        set(p, "categoryService", mock(CategoryService.class));
        set(p, "chartService", mock(ChartService.class));
        set(p, "featureService", featureService);
        set(p, "promulgationManager", mock(PromulgationManager.class));
        setJob(p, job);
        return p;
    }

    /** A job pre-seeded with the boolean flags processMessage unboxes (so it won't NPE) + a valid series. */
    private static BatchData archiveJob(String seriesId) {
        BatchData job = job();
        job.getProperties().put("assignNewUids", Boolean.FALSE);
        job.getProperties().put("assignDefaultSeries", Boolean.FALSE);
        job.getProperties().put("preserveStatus", Boolean.FALSE);
        job.getProperties().put("seriesId", seriesId);
        job.getProperties().put("validMessageSeries",
                new ArrayList<>(Arrays.asList(seriesId)));
        return job;
    }

    private static Message archiveMessage(MainType mainType) {
        Message message = new Message();
        message.assignNewUid(); // give it a UID so findByUid is meaningfully queried
        message.setStatus(Status.PUBLISHED);
        message.setMessageSeries(series("dma-nw", mainType));
        return message;
    }

    @Test
    public void archiveProcessMessage_forcesDraftAndSyncsMainTypeFromSeries() throws Exception {
        MessageQueryService messageQueryService = mock(MessageQueryService.class);
        when(messageQueryService.findByUid(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        MessageSeriesService seriesService = mock(MessageSeriesService.class);
        MessageSeries nm = series("dma-nw", MainType.NM);
        when(seriesService.findBySeriesId("dma-nw")).thenReturn(nm);
        FeatureService featureService = mock(FeatureService.class);

        BatchMsgArchiveImportProcessor p =
                archiveProcessor(messageQueryService, seriesService, featureService, archiveJob("dma-nw"));

        Message message = archiveMessage(MainType.NW); // incoming PUBLISHED + NW

        Message result = p.processMessage(message);

        assertEquals(Status.DRAFT, result.getStatus(), "preserveStatus=false must force DRAFT");
        assertEquals(MainType.NM, result.getMainType(), "main type must be taken from the resolved series");
        assertNull(result.getId(), "id must be cleared so it is treated as a new entity");
    }

    @Test
    public void archiveProcessMessage_returnsNullWhenMessageAlreadyExists() throws Exception {
        MessageQueryService messageQueryService = mock(MessageQueryService.class);
        // An existing message with the same UID already exists -> import must skip (return null).
        when(messageQueryService.findByUid(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new Message());
        MessageSeriesService seriesService = mock(MessageSeriesService.class);
        FeatureService featureService = mock(FeatureService.class);

        BatchMsgArchiveImportProcessor p =
                archiveProcessor(messageQueryService, seriesService, featureService, archiveJob("dma-nw"));

        Message result = p.processMessage(archiveMessage(MainType.NW));

        assertNull(result, "an already-existing message UID must cause the import to be skipped");
        // No series resolution should occur once we've decided to skip.
        verifyNoInteractions(seriesService);
    }

    @Test
    public void archiveProcessMessage_blankAndNullSeriesIdAreNotResolved() throws Exception {
        MessageQueryService messageQueryService = mock(MessageQueryService.class);
        when(messageQueryService.findByUid(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        MessageSeriesService seriesService = mock(MessageSeriesService.class);
        FeatureService featureService = mock(FeatureService.class);

        // The series carried by the message has a blank seriesId, and the job has no usable default.
        BatchData job = job();
        job.getProperties().put("assignNewUids", Boolean.FALSE);
        job.getProperties().put("assignDefaultSeries", Boolean.FALSE);
        job.getProperties().put("preserveStatus", Boolean.FALSE);
        job.getProperties().put("seriesId", "   "); // blank
        job.getProperties().put("validMessageSeries", new ArrayList<String>());

        BatchMsgArchiveImportProcessor p =
                archiveProcessor(messageQueryService, seriesService, featureService, job);

        Message message = new Message();
        message.assignNewUid();
        message.setMessageSeries(series("  ", MainType.NW)); // blank series id

        try {
            p.processMessage(message);
            fail("a message with no resolvable series must fail the import");
        } catch (Exception e) {
            assertTrue(e.getMessage().startsWith("No valid message series resolved for message"),
                    "failure must name the unresolved-series condition for the message");
        }
        // Blank series ids must never reach the lookup service.
        verify(seriesService, never()).findBySeriesId(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    public void archiveProcessMessage_seriesIdNotInValidListIsRejected() throws Exception {
        MessageQueryService messageQueryService = mock(MessageQueryService.class);
        when(messageQueryService.findByUid(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        MessageSeriesService seriesService = mock(MessageSeriesService.class);
        FeatureService featureService = mock(FeatureService.class);

        // seriesId is non-blank but NOT in the validMessageSeries whitelist -> resolves to null.
        BatchData job = job();
        job.getProperties().put("assignNewUids", Boolean.FALSE);
        job.getProperties().put("assignDefaultSeries", Boolean.FALSE);
        job.getProperties().put("preserveStatus", Boolean.FALSE);
        job.getProperties().put("seriesId", "forbidden-series");
        job.getProperties().put("validMessageSeries",
                new ArrayList<>(Arrays.asList("allowed-series")));

        BatchMsgArchiveImportProcessor p =
                archiveProcessor(messageQueryService, seriesService, featureService, job);

        Message message = new Message();
        message.assignNewUid();
        message.setMessageSeries(series("forbidden-series", MainType.NW));

        try {
            p.processMessage(message);
            fail("a series id outside the valid whitelist must be rejected");
        } catch (Exception e) {
            assertTrue(e.getMessage().startsWith("No valid message series resolved for message"));
        }
        // Security gate: a non-whitelisted id must never be looked up against the DB.
        verify(seriesService, never()).findBySeriesId("forbidden-series");
    }

    @Test
    public void archiveProcessMessage_resetsGeometryUidAndAssignsNewFeatureUids() throws Exception {
        MessageQueryService messageQueryService = mock(MessageQueryService.class);
        when(messageQueryService.findByUid(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        MessageSeriesService seriesService = mock(MessageSeriesService.class);
        when(seriesService.findBySeriesId("dma-nw")).thenReturn(series("dma-nw", MainType.NW));
        FeatureService featureService = mock(FeatureService.class);

        BatchMsgArchiveImportProcessor p =
                archiveProcessor(messageQueryService, seriesService, featureService, archiveJob("dma-nw"));

        Message message = archiveMessage(MainType.NW);
        MessagePart part = new MessagePart();
        FeatureCollection geometry = new FeatureCollection();
        geometry.setUid("old-collection-uid");
        part.setGeometry(geometry);
        List<MessagePart> parts = new ArrayList<>();
        parts.add(part);
        message.setParts(parts);

        p.processMessage(message);

        assertNull(geometry.getUid(), "each part's geometry UID must be reset");
        // ... and the feature-uid reassignment must run for that geometry.
        verify(featureService, times(1)).assignNewFeatureUids(geometry);
    }

    @Test
    public void archiveProcessMessage_preserveStatusKeepsPublishedAndSetsPublishFrom() throws Exception {
        MessageQueryService messageQueryService = mock(MessageQueryService.class);
        when(messageQueryService.findByUid(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        MessageSeriesService seriesService = mock(MessageSeriesService.class);
        when(seriesService.findBySeriesId("dma-nw")).thenReturn(series("dma-nw", MainType.NW));
        FeatureService featureService = mock(FeatureService.class);

        BatchData job = archiveJob("dma-nw");
        job.getProperties().put("preserveStatus", Boolean.TRUE); // keep the incoming status
        BatchMsgArchiveImportProcessor p =
                archiveProcessor(messageQueryService, seriesService, featureService, job);

        Message message = archiveMessage(MainType.NW); // PUBLISHED, no publishDateFrom
        assertNull(message.getPublishDateFrom(), "precondition: no publish-from date");

        Message result = p.processMessage(message);

        assertEquals(Status.PUBLISHED, result.getStatus(),
                "preserveStatus=true must keep the PUBLISHED status");
        // A public status with no publish-from date must be back-filled with a date.
        assertTrue(result.getPublishDateFrom() != null,
                "a published message must get a publish-from date");
    }

    @Test
    public void archiveProcessMessage_preserveStatusReadsTheYearInTheSeriesZone() throws Exception {
        // TZ2: 22:30 on 31 December 2025 in Nuuk is 2026 in UTC and in Copenhagen; the series' domain is in Nuuk
        java.time.ZoneId nuuk = java.time.ZoneId.of("America/Nuuk");
        MessageQueryService messageQueryService = mock(MessageQueryService.class);
        when(messageQueryService.findByUid(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        MessageSeriesService seriesService = mock(MessageSeriesService.class);
        MessageSeries nw = series("dma-nw", MainType.NW);
        when(seriesService.findBySeriesId("dma-nw")).thenReturn(nw);
        when(seriesService.seriesZone(nw)).thenReturn(nuuk);

        BatchData job = archiveJob("dma-nw");
        job.getProperties().put("preserveStatus", Boolean.TRUE);
        BatchMsgArchiveImportProcessor p =
                archiveProcessor(messageQueryService, seriesService, mock(FeatureService.class), job);

        Message message = archiveMessage(MainType.NW);
        message.setNumber(1);
        message.setPublishDateFrom(java.util.Date.from(
                java.time.LocalDateTime.of(2025, 12, 31, 22, 30).atZone(nuuk).toInstant()));

        Message result = p.processMessage(message);

        assertEquals("001/25", result.computeNumberYearId(), "the year is read in the series' domain zone");
        // ... and the save callback keeps it
        result.onPersist();
        assertEquals("001/25", result.computeNumberYearId());
    }

    // =============================================================================================== //
    // AbstractMessageImportWriter
    // =============================================================================================== //

    /** Concrete subclass so the abstract writer can be instantiated; only the tag service is wired. */
    private static AbstractMessageImportWriter writer(MessageTagService tagService, BatchData job) {
        AbstractMessageImportWriter w = new AbstractMessageImportWriter() {};
        set(w, "messageTagService", tagService);
        if (job != null) {
            setJob(w, job);
        }
        return w;
    }

    @Test
    public void writerGetMessageTag_returnsNullForBlankTagId() throws Exception {
        MessageTagService tagService = mock(MessageTagService.class);
        BatchData job = job();
        job.getProperties().put("tagId", "   "); // blank
        AbstractMessageImportWriter w = writer(tagService, job);

        assertNull(w.getMessageTag(), "a blank tagId must short-circuit to null");
        // The service must never be queried for a blank id.
        verifyNoInteractions(tagService);
    }

    @Test
    public void writerGetMessageTag_returnsNullForMissingTagIdProperty() throws Exception {
        MessageTagService tagService = mock(MessageTagService.class);
        AbstractMessageImportWriter w = writer(tagService, job()); // no tagId property at all

        assertNull(w.getMessageTag(), "a missing tagId property must resolve to null");
        verifyNoInteractions(tagService);
    }

    @Test
    public void writerGetMessageTag_looksUpTagWhenTagIdPresent() throws Exception {
        MessageTagService tagService = mock(MessageTagService.class);
        MessageTag tag = new MessageTag();
        when(tagService.findTag(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("nw-batch")))
                .thenReturn(tag);
        BatchData job = job();
        job.getProperties().put("tagId", "nw-batch");
        AbstractMessageImportWriter w = writer(tagService, job);

        MessageTag result = w.getMessageTag();

        assertSame(tag, result, "the tag resolved from the service must be returned");
        verify(tagService, times(1))
                .findTag(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("nw-batch"));
    }

    @Test
    public void writerCheckAddMessageToTag_existingNullIdMemberDoesNotBlockDistinctIncoming() {
        // FIXED (BATCH1): checkAddMessageToTag now de-dups via Objects.equals(m.getId(), message.getId()),
        // so an EXISTING null-id tag member no longer NPEs the id comparison. A distinct incoming message
        // (id 5) does not match the null-id member and is added as before — just without the crash.
        AbstractMessageImportWriter w = writer(mock(MessageTagService.class), null);

        MessageTag tag = new MessageTag();
        Message unsavedMember = new Message(); // id == null — an unsaved message already on the tag
        tag.getMessages().add(unsavedMember);

        Message incoming = new Message();
        incoming.setId(5);

        MessageTag result = w.checkAddMessageToTag(incoming, tag);

        assertSame(tag, result, "the supplied tag is returned");
        assertEquals(2, tag.getMessages().size(),
                "the distinct incoming message is added despite the null-id member (no NPE)");
        assertTrue(tag.getMessages().contains(incoming), "the incoming message is now a tag member");
        assertTrue(incoming.getTags().contains(tag), "the bidirectional back-reference is wired");
    }

    @Test
    public void writerCheckAddMessageToTag_unsavedIncomingIsAddedWhenMembersHaveIds() {
        // PIN current behaviour: when existing members all have non-null ids, an incoming UNSAVED message
        // (id == null) does NOT match any of them (Integer.equals(null) == false), so it IS added — even
        // though, once persisted, it may collide. This is a real correctness smell worth documenting.
        AbstractMessageImportWriter w = writer(mock(MessageTagService.class), null);

        MessageTag tag = new MessageTag();
        Message saved = new Message();
        saved.setId(100);
        tag.getMessages().add(saved);

        Message unsaved = new Message(); // id == null
        MessageTag result = w.checkAddMessageToTag(unsaved, tag);

        assertSame(tag, result, "the supplied tag is returned");
        assertEquals(2, tag.getMessages().size(),
                "the unsaved message is added (null id never matches the saved id)");
        assertTrue(tag.getMessages().contains(unsaved), "the unsaved message is now a tag member");
        assertTrue(unsaved.getTags().contains(tag), "the bidirectional back-reference is wired");
    }

    @Test
    public void writerCheckAddMessageToTag_nullTagIsNoOp() {
        AbstractMessageImportWriter w = writer(mock(MessageTagService.class), null);
        Message m = new Message();
        m.setId(7);

        assertNull(w.checkAddMessageToTag(m, null), "a null tag must short-circuit to null");
        assertTrue(m.getTags().isEmpty(), "the message tag list must stay empty when no tag is given");
    }
}
