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

package org.niord.core.integration;

import org.springframework.transaction.annotation.Transactional;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.integration.vo.MessageSeriesMappingVo;
import org.niord.core.integration.vo.NiordIntegrationVo;
import org.niord.core.message.Message;
import org.niord.core.message.MessageDesc;
import org.niord.core.message.MessageKind;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessagePartDesc;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.message.MessageService;
import org.niord.core.util.UidUtils;
import org.niord.model.message.MainType;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.niord.test.http.HttpStub;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests that close the remaining gaps in
 * {@link NiordIntegrationExecutionService#processNiordIntegration(NiordIntegrationVo)} left by
 * {@code NiordIntegrationImportITTest}.
 *
 * <p><b>What the sibling already covers</b> ({@code NiordIntegrationImportITTest}): each create / cancel /
 * re-import / coercion branch is asserted, but always in isolation — every test drives the engine with a
 * SINGLE remote message and a SINGLE source-to-target mapping. The orchestration loop in
 * {@code processNiordIntegration} that simultaneously builds the {@code cancelMessages},
 * {@code createMessages} and {@code updateMessages} lists for a target series (lines ~135-161), the
 * many-source-series-to-one-target fan-in (lines ~117-120) and the unknown-target-series {@code continue}
 * guard (lines ~110-113) are therefore never exercised together. This class fills exactly those gaps and
 * pins the INT3 hazard.
 *
 * <p><b>Reused mechanics.</b> The transaction model, fixture-commit and cleanup strategy are identical to
 * {@code NiordIntegrationImportITTest}: {@code processNiordIntegration} runs in a fresh committed
 * {@code REQUIRES_NEW} transaction (Quarkus CDI ignores {@code @Asynchronous}, so the call is synchronous),
 * so local fixtures are committed through {@link Tx#requiringNew()}, source/legacy ids are
 * fresh UUIDs (a non-UUID id makes the {@code Message(MessageVo)} constructor throw "Invalid UID format"),
 * and every committed row is best-effort deleted in {@link #cleanup()} so each test passes alone AND in the
 * full suite.
 *
 * <p><b>Seed facts.</b> {@code dma-nw} is the seeded {@link MainType#NW} target series whose fixed
 * {@code types} element-collection is empty, so the engine never takes the "fixed type set" coercion branch
 * for it — as established by the sibling class.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class NiordIntegrationExecutionGapITTest extends ITTest {

    /** The seeded target message series: NW main-type, empty fixed type set. */
    private static final String TARGET_SERIES = "dma-nw";

    @Autowired
    NiordIntegrationExecutionService executionService;

    @Autowired
    MessageService messageService;

    @Autowired
    MessageSeriesService messageSeriesService;

    @PersistenceContext
    EntityManager em;

    /** Legacy ids of messages COMMITTED outside the test transaction, cleaned up best-effort in {@link #cleanup()}. */
    private final List<String> touchedLegacyIds = new ArrayList<>();

    /** Set by {@link #captureNumberSequence()}; drives the sequence restore in {@link #cleanup()}. */
    private boolean numberSequenceCaptured;
    private int currentYear;
    private Integer nextNumberBefore;

    /**
     * The importer refuses non-public fetch targets unless {@code niordIntegrationAllowPrivateHosts} is
     * on, and the {@link HttpStub} necessarily binds loopback. Flip the flag on the un-proxied bean, the
     * way the SECOM ITs flip their {@code @Setting} fields; {@link #cleanup()} restores it.
     */
    @BeforeEach
    void allowLoopbackFetches() {
        AopTestUtils.<NiordIntegrationExecutionService>getTargetObject(executionService).allowPrivateHosts = true;
    }

    @AfterEach
    void cleanup() {
        for (String legacyId : touchedLegacyIds) {
            try {
                Tx.requiringNew().run(() -> {
                    List<Message> rows = em.createQuery(
                                    "select m from Message m where m.legacyId = :lid", Message.class)
                            .setParameter("lid", legacyId)
                            .getResultList();
                    for (Message m : rows) {
                        // Defensively remove any cancellation warning minted for this source. There must be
                        // none (see cancelLegMintsNothingEvenThoughAnEditorialCancelWould), but a failing run
                        // must not leak a minted row into the shared seed.
                        em.createQuery("select cw from Message cw join cw.references r "
                                        + "where cw.kind = :kind and r.messageId = :uid", Message.class)
                                .setParameter("kind", MessageKind.CANCELLATION)
                                .setParameter("uid", m.getUid())
                                .getResultList()
                                .forEach(cw -> {
                                    em.createQuery("delete from MessageHistory mh where mh.message.id = :id")
                                            .setParameter("id", cw.getId()).executeUpdate();
                                    em.remove(cw);
                                });
                        // MessageHistory has no cascade from Message - remove it first to avoid an FK violation.
                        em.createQuery("delete from MessageHistory mh where mh.message.id = :id")
                                .setParameter("id", m.getId())
                                .executeUpdate();
                        em.remove(m);
                    }
                });
            } catch (Exception ignore) {
                // best-effort cleanup
            }
        }
        touchedLegacyIds.clear();

        if (numberSequenceCaptured) {
            // Restore the captured current-year sequence, so the shared dma-nw series is left exactly
            // as found (a mint - which must not happen - would have bumped it).
            Tx.requiringNew().run(() ->
                    messageSeriesService.setNextMessageNumber(TARGET_SERIES, currentYear, nextNumberBefore));
            numberSequenceCaptured = false;
        }
        AopTestUtils.<NiordIntegrationExecutionService>getTargetObject(executionService).allowPrivateHosts = false;
    }

    /**
     * Captures the current-year number sequence of the seeded {@code dma-nw} target series for restore.
     * An editorial cancel of a dma-nw warning always mints (dma-nw is an auto-numbered NW series), so a
     * cancel that mints nothing proves the mirror cancels via {@code updateStatus}, never
     * {@code cancelMessage} - and a regression that did mint would bump this sequence.
     */
    private void captureNumberSequence() {
        currentYear = Calendar.getInstance().get(Calendar.YEAR);
        nextNumberBefore = Tx.requiringNew().call(() ->
                messageSeriesService.getNextMessageNumber(TARGET_SERIES, currentYear));
        numberSequenceCaptured = true;
    }

    // ------------------------------------------------------------------------------------------- //
    // Fixtures & helpers (mirrors NiordIntegrationImportITTest)
    // ------------------------------------------------------------------------------------------- //

    /** A unique source series id for the remote messages of a single test. */
    private String uniqueSourceSeries() {
        return "it-gap-src-" + System.nanoTime();
    }

    /** A unique legacy/source message id (a valid UUID), registered for cleanup. */
    private String uniqueLegacyId() {
        String id = UidUtils.newUid();
        touchedLegacyIds.add(id);
        return id;
    }

    /** An active integration mapping a single {@code sourceSeries -> dma-nw}, pointing at the stub. */
    private NiordIntegrationVo integration(String baseUrl, String sourceSeries) {
        NiordIntegrationVo vo = new NiordIntegrationVo();
        vo.setId(1);
        vo.setUrl(baseUrl);
        vo.setActive(true);
        MessageSeriesMappingVo mapping = new MessageSeriesMappingVo();
        mapping.setSourceSeriesId(sourceSeries);
        mapping.setTargetSeriesId(TARGET_SERIES);
        vo.getMessageSeriesMappings().add(mapping);
        return vo;
    }

    /** Adds a {@code sourceSeries -> targetSeries} mapping to an existing integration vo. */
    private void addMapping(NiordIntegrationVo vo, String sourceSeries, String targetSeries) {
        MessageSeriesMappingVo mapping = new MessageSeriesMappingVo();
        mapping.setSourceSeriesId(sourceSeries);
        mapping.setTargetSeriesId(targetSeries);
        vo.getMessageSeriesMappings().add(mapping);
    }

    /**
     * Builds one remote message JSON object restricted to known {@code MessageVo} fields. Dates are epoch
     * millis (the Jackson default). The message carries one DETAILS part and an "en" title so it survives
     * {@code createMessage}.
     */
    private String remoteMessageJson(String id, String sourceSeries, MainType mainType, Type type,
                                     long updatedMillis, Long publishFromMillis) {
        String publishFrom = publishFromMillis == null ? "null" : Long.toString(publishFromMillis);
        return "{"
                + "\"id\":\"" + id + "\","
                + "\"updated\":" + updatedMillis + ","
                + "\"messageSeries\":{\"seriesId\":\"" + sourceSeries + "\",\"mainType\":\"" + mainType + "\"},"
                + "\"mainType\":\"" + mainType + "\","
                + "\"type\":\"" + type + "\","
                + "\"status\":\"PUBLISHED\","
                + "\"publishDateFrom\":" + publishFrom + ","
                + "\"parts\":[{\"type\":\"DETAILS\",\"descs\":[{\"lang\":\"en\",\"subject\":\"s\",\"details\":\"d\"}]}],"
                + "\"descs\":[{\"lang\":\"en\",\"title\":\"remote title\"}]"
                + "}";
    }

    /**
     * Builds a remote message JSON object that OMITS the {@code type} field entirely. On an NW target series
     * whose fixed type set is empty the engine then evaluates {@code message.getType().getMainType()} in
     * {@code importMessage} — and {@code getType()} is {@code null}, so the re-import throws an NPE. Used to
     * deterministically force the INT3 failure on the re-import leg.
     */
    private String remoteMessageJsonNoType(String id, String sourceSeries, MainType mainType, long updatedMillis) {
        return "{"
                + "\"id\":\"" + id + "\","
                + "\"updated\":" + updatedMillis + ","
                + "\"messageSeries\":{\"seriesId\":\"" + sourceSeries + "\",\"mainType\":\"" + mainType + "\"},"
                + "\"mainType\":\"" + mainType + "\","
                + "\"status\":\"PUBLISHED\","
                + "\"publishDateFrom\":" + System.currentTimeMillis() + ","
                + "\"parts\":[{\"type\":\"DETAILS\",\"descs\":[{\"lang\":\"en\",\"subject\":\"s\",\"details\":\"d\"}]}],"
                + "\"descs\":[{\"lang\":\"en\",\"title\":\"remote title\"}]"
                + "}";
    }

    /**
     * Commits a local PUBLISHED message on the target series carrying the given legacy id and "updated"
     * timestamp, so the engine's {@code fetchOwnMessages} (status=PUBLISHED, non-blank legacy id) sees it.
     * Returns the committed entity id. The {@code updated} column is forced with native SQL to bypass the
     * {@code @PreUpdate} that would otherwise stamp it to "now".
     */
    private Integer commitLocalPublished(String legacyId, Date updated) {
        Integer[] holder = new Integer[1];
        Tx.requiringNew().run(() -> {
            try {
                Message msg = new Message();
                msg.setMessageSeries(new MessageSeries(TARGET_SERIES));
                msg.setType(Type.COASTAL_WARNING);
                msg.setStatus(Status.PUBLISHED);
                msg.setLegacyId(legacyId);
                msg.setAutoTitle(false);
                msg.setPublishDateFrom(new Date(0L));
                MessagePart part = new MessagePart(MessagePartType.DETAILS);
                MessagePartDesc pd = part.createDesc("en");
                pd.setSubject("local subject");
                pd.setDetails("local details");
                msg.addPart(part);
                MessageDesc d = msg.createDesc("en");
                d.setTitle("local title");
                Message created = messageService.createMessage(msg);
                holder[0] = created.getId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Tx.requiringNew().run(() ->
                em.createNativeQuery("update \"Message\" set updated = :ts where id = :id")
                        .setParameter("ts", updated)
                        .setParameter("id", holder[0])
                        .executeUpdate());
        return holder[0];
    }

    /** Reloads (in a fresh tx) every committed message carrying the given legacy id. */
    private List<Message> reloadByLegacyId(String legacyId) {
        List<Message> result = new ArrayList<>();
        Tx.requiringNew().run(() ->
                result.addAll(em.createQuery(
                                "select m from Message m where m.legacyId = :lid order by m.id", Message.class)
                        .setParameter("lid", legacyId)
                        .getResultList()));
        return result;
    }

    /** Reloads a single committed message by primary key in a fresh tx. */
    private Message reloadById(Integer id) {
        Message[] holder = new Message[1];
        Tx.requiringNew().run(() -> holder[0] = em.find(Message.class, id));
        return holder[0];
    }

    /** The single status of the only row carrying the given legacy id (fails if there is not exactly one). */
    private Status soleStatus(String legacyId) {
        List<Message> rows = reloadByLegacyId(legacyId);
        assertEquals(1, rows.size(), "expected exactly one row for legacy id " + legacyId);
        return rows.get(0).getStatus();
    }

    // ------------------------------------------------------------------------------------------- //
    // ORCHESTRATION - one run that simultaneously cancels, creates AND re-imports across one target
    // ------------------------------------------------------------------------------------------- //

    @Test
    void singleRunSimultaneouslyCancelsCreatesAndReImportsAcrossOneTargetSeries() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            String src = uniqueSourceSeries();

            // A) exists locally, present remotely with a NEWER "updated" -> update leg (cancel old + re-import).
            String idUpdate = uniqueLegacyId();
            Integer oldUpdateRowId = commitLocalPublished(idUpdate,
                    new Date(System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000L));

            // B) exists locally, ABSENT from the remote list -> cancel leg.
            String idCancel = uniqueLegacyId();
            Integer cancelRowId = commitLocalPublished(idCancel, new Date());

            // C) not held locally, present remotely -> create leg.
            String idCreate = uniqueLegacyId();

            long newer = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000L;
            // The remote list contains A (newer) and C, but NOT B.
            stub.respondJson("["
                    + remoteMessageJson(idUpdate, src, MainType.NW, Type.COASTAL_WARNING, newer, System.currentTimeMillis())
                    + ","
                    + remoteMessageJson(idCreate, src, MainType.NW, Type.COASTAL_WARNING,
                            System.currentTimeMillis(), System.currentTimeMillis())
                    + "]");

            executionService.processNiordIntegration(integration(stub.baseUrl(), src));

            // Exactly one remote fetch covering all three reconciliation decisions.
            assertEquals(1, stub.requestCount(),
                    "the engine must fetch the remote message list exactly once per run");

            // B (cancel leg): the locally-held message absent from the fetch must be CANCELLED in place.
            Message cancelled = reloadById(cancelRowId);
            assertEquals(Status.CANCELLED, cancelled.getStatus(),
                    "a locally-held message absent from the remote list must be CANCELLED");

            // A (update leg): the old copy CANCELLED, a fresh distinct row re-imported on the target series.
            List<Message> updateRows = reloadByLegacyId(idUpdate);
            assertEquals(2, updateRows.size(),
                    "an updated remote message must yield two rows: the cancelled old copy and a fresh re-import");
            Message oldRow = updateRows.stream().filter(r -> r.getId().equals(oldUpdateRowId)).findFirst().orElse(null);
            Message freshRow = updateRows.stream().filter(r -> !r.getId().equals(oldUpdateRowId)).findFirst().orElse(null);
            assertNotNull(oldRow, "the original local copy must still be present");
            assertNotNull(freshRow, "a freshly re-imported copy must have been created");
            assertEquals(Status.CANCELLED, oldRow.getStatus(), "the old copy must be CANCELLED before re-import");
            assertEquals(TARGET_SERIES, freshRow.getMessageSeries().getSeriesId(),
                    "the re-imported copy must be on the target series");

            // C (create leg): a brand-new message imported with the source id as its legacy id.
            List<Message> createdRows = reloadByLegacyId(idCreate);
            assertEquals(1, createdRows.size(), "a remote message not held locally must be created exactly once");
            assertEquals(TARGET_SERIES, createdRows.get(0).getMessageSeries().getSeriesId(),
                    "the created copy must be on the target series");
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // FAN-IN - several SOURCE series mapped to the SAME target series in one integration
    // ------------------------------------------------------------------------------------------- //

    @Test
    void twoSourceSeriesMappedToTheSameTargetBothImportOntoThatTarget() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            String srcA = uniqueSourceSeries();
            String srcB = uniqueSourceSeries() + "-b";
            String idFromA = uniqueLegacyId();
            String idFromB = uniqueLegacyId();

            // One remote message on each source series; both sources map to dma-nw.
            stub.respondJson("["
                    + remoteMessageJson(idFromA, srcA, MainType.NW, Type.COASTAL_WARNING,
                            System.currentTimeMillis(), System.currentTimeMillis())
                    + ","
                    + remoteMessageJson(idFromB, srcB, MainType.NW, Type.COASTAL_WARNING,
                            System.currentTimeMillis(), System.currentTimeMillis())
                    + "]");

            NiordIntegrationVo vo = integration(stub.baseUrl(), srcA);
            addMapping(vo, srcB, TARGET_SERIES); // second source -> same target

            executionService.processNiordIntegration(vo);

            // The fan-in must collapse to a single target-series reconciliation that imports BOTH messages.
            List<Message> a = reloadByLegacyId(idFromA);
            List<Message> b = reloadByLegacyId(idFromB);
            assertEquals(1, a.size(), "the message from source A must be imported once");
            assertEquals(1, b.size(), "the message from source B must be imported once");
            assertEquals(TARGET_SERIES, a.get(0).getMessageSeries().getSeriesId(),
                    "the message from source A must land on the shared target series");
            assertEquals(TARGET_SERIES, b.get(0).getMessageSeries().getSeriesId(),
                    "the message from source B must land on the shared target series");
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // GUARD - a mapping whose TARGET series does not exist locally must be skipped (continue)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void mappingToUnknownTargetSeriesIsSkippedWithoutImportingOrThrowing() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            String src = uniqueSourceSeries();
            String legacyId = uniqueLegacyId();
            stub.respondJson("[" + remoteMessageJson(legacyId, src, MainType.NW, Type.COASTAL_WARNING,
                    System.currentTimeMillis(), System.currentTimeMillis()) + "]");

            NiordIntegrationVo vo = new NiordIntegrationVo();
            vo.setId(7);
            vo.setUrl(stub.baseUrl());
            vo.setActive(true);
            MessageSeriesMappingVo mapping = new MessageSeriesMappingVo();
            mapping.setSourceSeriesId(src);
            mapping.setTargetSeriesId("no-such-target-series-" + System.nanoTime()); // unresolvable target
            vo.getMessageSeriesMappings().add(mapping);

            // Must not throw despite the unknown target series.
            executionService.processNiordIntegration(vo);

            assertTrue(stub.requestCount() >= 1,
                    "the engine still fetches the remote list before resolving target series");
            assertTrue(reloadByLegacyId(legacyId).isEmpty(),
                    "a mapping whose target series cannot be resolved must import nothing (continue branch)");
        }
    }

    @Test
    void unknownTargetSeriesIsSkippedButAKnownTargetInTheSameRunStillImports() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            String srcKnown = uniqueSourceSeries();
            String srcUnknown = uniqueSourceSeries() + "-u";
            String idKnown = uniqueLegacyId();
            String idUnknown = uniqueLegacyId();

            stub.respondJson("["
                    + remoteMessageJson(idKnown, srcKnown, MainType.NW, Type.COASTAL_WARNING,
                            System.currentTimeMillis(), System.currentTimeMillis())
                    + ","
                    + remoteMessageJson(idUnknown, srcUnknown, MainType.NW, Type.COASTAL_WARNING,
                            System.currentTimeMillis(), System.currentTimeMillis())
                    + "]");

            NiordIntegrationVo vo = integration(stub.baseUrl(), srcKnown); // srcKnown -> dma-nw (valid)
            addMapping(vo, srcUnknown, "no-such-target-series-" + System.nanoTime()); // srcUnknown -> invalid

            executionService.processNiordIntegration(vo);

            assertEquals(1, reloadByLegacyId(idKnown).size(),
                    "the message routed to the known target series must still be imported");
            assertEquals(TARGET_SERIES, reloadByLegacyId(idKnown).get(0).getMessageSeries().getSeriesId(),
                    "the imported message must be on the resolved target series");
            assertTrue(reloadByLegacyId(idUnknown).isEmpty(),
                    "the message routed to the unresolvable target series must be skipped, not imported");
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // INT3 FIX - re-import before cancel: a failed re-import leaves the old copy PUBLISHED and intact.
    //
    // The update leg now imports the new remote version FIRST and only cancels our own copy once the
    // re-import has succeeded. This test forces the re-import to fail deterministically: the remote
    // payload OMITS its `type`, so importMessage NPEs on `message.getType().getMainType()` for the
    // empty-fixed-type-set dma-nw series. The NPE happens BEFORE createMessage runs (no partial row is
    // written) and BEFORE the cancel is reached (the old copy is never cancelled); it is then swallowed
    // by processNiordIntegration's method-level catch-all.
    //
    // RESULT: the existing published Notice-to-Mariners stays PUBLISHED with its original content - there
    // is never an orphaned CANCELLED copy with no replacement (the pre-fix Spring behavior, where the
    // cancel ran first and survived the swallowed NPE). The newer remote update is still not applied and
    // the swallowed failure is still not surfaced to the operator - that residual "silent swallow / shared
    // per-target transaction" concern is tracked separately (see docs/potential-errors.md, INT4).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void failedReimportLeavesOldCopyPublishedAndImportsNoReplacement() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            String src = uniqueSourceSeries();
            String legacyId = uniqueLegacyId();

            // A local PUBLISHED copy, "updated" in the past so the remote copy is reliably newer (update leg).
            Integer localId = commitLocalPublished(legacyId,
                    new Date(System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000L));

            // Remote copy: NEWER, but with NO `type` field -> importMessage will NPE on the re-import leg,
            // which now runs BEFORE our own copy is cancelled.
            long newer = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000L;
            stub.respondJson("[" + remoteMessageJsonNoType(legacyId, src, MainType.NW, newer) + "]");

            // Must not throw - processNiordIntegration swallows the re-import failure.
            executionService.processNiordIntegration(integration(stub.baseUrl(), src));

            // FIXED behavior: the failed re-import produced no replacement row and the old copy was never
            // cancelled, so there is still exactly ONE row - the ORIGINAL local copy - and it is PUBLISHED.
            List<Message> rows = reloadByLegacyId(legacyId);
            assertEquals(1, rows.size(),
                    "INT3: the failed re-import produces no replacement row - only the original survives");
            assertEquals(localId, rows.get(0).getId(),
                    "INT3: the surviving row is the original local copy, not a re-import");
            assertEquals(Status.PUBLISHED, soleStatus(legacyId),
                    "INT3 fixed: importing before cancelling means a failed re-import leaves the existing "
                            + "published NtM PUBLISHED and intact - never an orphaned CANCELLED with no replacement");
            // The newer remote version was not applied (the re-import failed before any change), so the
            // copy keeps its ORIGINAL local title. The failure is still swallowed (the scheduler advances);
            // surfacing it to the operator is tracked as a separate follow-up.
            String[] title = new String[1];
            Tx.requiringNew().run(() ->
                    title[0] = em.find(Message.class, localId).getDesc("en").getTitle());
            assertEquals("local title", title[0],
                    "INT3: the failed re-import leaves the ORIGINAL local title - the newer remote update was "
                            + "not applied, but crucially the old copy was not cancelled either");
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // MUST-NOT-MINT - the mirror cancels via updateStatus(CANCELLED), never cancelMessage, so it must
    // NOT mint an S-124 cancellation warning. This is non-trivial: the cancelled message is a warning
    // on the auto-numbered NW dma-nw series and so satisfies the full minting predicate, so a
    // regression that routed the mirror's cancel through cancelMessage WOULD mint here.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void cancelLegMintsNothingEvenThoughAnEditorialCancelWould() throws Exception {
        captureNumberSequence(); // dma-nw mints on editorial (cancelMessage) cancels

        try (HttpStub stub = HttpStub.start()) {
            String src = uniqueSourceSeries();

            // A locally-held published dma-nw warning, absent from the remote list -> the cancel leg.
            String legacyId = uniqueLegacyId();
            Integer localId = commitLocalPublished(legacyId, new Date());

            // Non-triviality precondition: were the mirror to route this cancel through cancelMessage,
            // it WOULD mint - the message satisfies the full minting predicate on the dma-nw series.
            assertTrue(Tx.requiringNew().call(() ->
                            messageService.willIssueCancellationWarning(em.find(Message.class, localId))),
                    "setup must be non-trivial: cancelling this message via cancelMessage would mint");

            // The remote list holds another warning but not ours. (An empty list would not do: since INT5 it
            // is not read as every warning withdrawn, and cancels nothing.)
            stub.respondJson("[" + remoteMessageJson(uniqueLegacyId(), src, MainType.NW, Type.COASTAL_WARNING,
                    System.currentTimeMillis(), System.currentTimeMillis()) + "]");

            executionService.processNiordIntegration(integration(stub.baseUrl(), src));

            // The mirror cancels via updateStatus(CANCELLED): the message is CANCELLED...
            Message cancelled = reloadById(localId);
            assertEquals(Status.CANCELLED, cancelled.getStatus(),
                    "a locally-held message absent from the remote list must be CANCELLED");

            // ...but NOT a single cancellation warning was minted - the mirror never calls cancelMessage.
            List<Message> minted = Tx.requiringNew().call(() ->
                    em.createQuery("select m from Message m join m.references r "
                                    + "where m.kind = :kind and r.messageId = :uid", Message.class)
                            .setParameter("kind", MessageKind.CANCELLATION)
                            .setParameter("uid", cancelled.getUid())
                            .getResultList());
            assertTrue(minted.isEmpty(),
                    "the mirror must never mint a cancellation warning: it cancels via updateStatus, not cancelMessage");
        }
    }
}
