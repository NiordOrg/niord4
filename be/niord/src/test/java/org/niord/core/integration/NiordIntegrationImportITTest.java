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
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessagePartDesc;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageService;
import org.niord.core.util.UidUtils;
import org.niord.model.message.MainType;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.niord.test.http.HttpStub;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link NiordIntegrationExecutionService#processNiordIntegration(NiordIntegrationVo)},
 * the remote-import engine that fetches public messages from another Niord server and reconciles them into
 * a local target {@link MessageSeries}. Run against the seeded MySQL container of the {@link ITTest}
 * infrastructure.
 *
 * <p><b>Why a sibling class.</b> {@code NiordIntegrationServiceITTest} covers the CRUD/scheduling
 * {@link NiordIntegrationService} and deliberately skips the happy path of the import engine ("delegates to
 * the remote-HTTP {@code NiordIntegrationExecutionService}, which cannot be driven from an integration test
 * without a live remote Niord server"). This class fills exactly that gap by standing up an in-process HTTP
 * server ({@link HttpStub}) that serves the {@code /rest/public/v1/messages} JSON the engine fetches, so the
 * create / cancel / re-import / coercion branches can finally be asserted end-to-end.
 *
 * <p><b>Transaction model (critical).</b> {@code processNiordIntegration(vo)} is
 * {@code @Transactional(REQUIRES_NEW)} (and {@code @jakarta.ejb.Asynchronous}, which Quarkus CDI does NOT
 * honour without the EJB extension, so a direct call runs synchronously). Because it runs in a FRESH
 * committed transaction, it cannot see rows left uncommitted in a {@link io.quarkus.test.TestTransaction},
 * and the messages it creates / cancels are COMMITTED. Therefore these tests:
 * <ul>
 *   <li>commit their own pre-existing "local" fixtures through {@link Tx#requiringNew()} so
 *       the engine's {@code fetchOwnMessages} (which runs inside the engine's own transaction) can see them;</li>
 *   <li>use a unique, freshly generated UUID as the source/legacy-id per test so they never collide with
 *       each other or the seed (a non-UUID id would make the {@code Message(MessageVo)} constructor throw
 *       "Invalid UID format" before anything is persisted);</li>
 *   <li>track every touched legacy-id and best-effort delete the committed rows in {@link #cleanup()} so each
 *       test passes alone AND in the full suite.</li>
 * </ul>
 *
 * <p><b>Seed facts the assertions rely on.</b> {@code dma-nw} is a seeded {@link MainType#NW} series (id 822)
 * whose fixed {@code types} element-collection is empty ({@code MessageSeries_types} is seeded empty). The
 * engine therefore never takes the "fixed type set" coercion branch for a seeded target, only the
 * mainType-mismatch branch — see the notes on
 * {@link #importCoercesTypeWhoseMainTypeDiffersFromTargetSeries()}.
 *
 * <p><b>Remote payload.</b> The remote messages are hand-built JSON restricted to fields that exist on
 * {@code MessageVo} (the engine deserialises with a bare {@code new ObjectMapper()} that fails on unknown
 * properties). Dates are serialised as epoch milliseconds, the Jackson default for {@code java.util.Date}.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class NiordIntegrationImportITTest extends ITTest {

    /** The seeded target message series: NW main-type, empty fixed type set. */
    private static final String TARGET_SERIES = "dma-nw";

    @Autowired
    NiordIntegrationExecutionService executionService;

    @Autowired
    MessageService messageService;

    @PersistenceContext
    EntityManager em;

    /** Legacy ids of messages COMMITTED outside the test transaction, cleaned up best-effort in {@link #cleanup()}. */
    private final List<String> touchedLegacyIds = new ArrayList<>();

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
        AopTestUtils.<NiordIntegrationExecutionService>getTargetObject(executionService).allowPrivateHosts = false;
    }

    // ------------------------------------------------------------------------------------------- //
    // Fixtures & helpers
    // ------------------------------------------------------------------------------------------- //

    /** A unique source series id for the remote messages of a single test. */
    private String uniqueSourceSeries() {
        return "it-import-src-" + System.nanoTime();
    }

    /**
     * A unique legacy/source message id, registered for cleanup.
     *
     * <p>The remote {@code MessageVo.id} this returns becomes the imported message's UID inside the
     * {@code Message(MessageVo)} constructor, which immediately runs it through
     * {@link UidUtils#uidToHashedFolderPath} — a method that throws {@code IllegalArgumentException
     * ("Invalid UID format ...")} for anything that is not a valid UUID. So the source id MUST be a
     * valid UUID, not a free-form {@code "IT-IMPORT-<nanos>"} string, or {@code importMessage} blows up
     * before persisting anything and the import silently yields zero rows. A fresh {@link UidUtils#newUid()}
     * is globally unique, so it also satisfies the per-test isolation requirement. The engine preserves
     * this id only as the {@code legacyId}, which is what the assertions check.
     */
    private String uniqueLegacyId() {
        String id = UidUtils.newUid();
        touchedLegacyIds.add(id);
        return id;
    }

    /** An active integration mapping {@code sourceSeries -> dma-nw}, pointing at the stub. */
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
     * Commits a local PUBLISHED message on the target series carrying the given legacy id and "updated"
     * timestamp, so the engine's {@code fetchOwnMessages} (status=PUBLISHED, non-blank legacy id) sees it.
     * Returns the committed entity id.
     *
     * <p>The exact {@code updated} timestamp must survive, but every JPA write triggers
     * {@link io.quarkus.test.junit.QuarkusTest}'s {@code @PreUpdate}, which stamps {@code updated = now}.
     * So the timestamp is forced with a native SQL update that bypasses the JPA lifecycle callbacks. The
     * update-detection branch under test ({@code remote.updated.after(local.updated)}) then reads exactly
     * this column value when the engine reloads the message in its own transaction.
     */
    private Integer commitLocalPublished(String legacyId, Date updated) {
        Integer[] holder = new Integer[1];
        Tx.requiringNew().run(() -> {
            try {
                Message msg = new Message();
                msg.setMessageSeries(new MessageSeries(TARGET_SERIES)); // substituted with the persisted series
                msg.setType(Type.COASTAL_WARNING);
                msg.setStatus(Status.PUBLISHED); // createMessage keeps a non-null status as-is
                msg.setLegacyId(legacyId);
                msg.setAutoTitle(false);
                // A far-past publish date keeps any number-sequence side effects off the current year.
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
        // Native update of the "updated" column - bypasses @PreUpdate so the intended timestamp survives.
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

    // ------------------------------------------------------------------------------------------- //
    // Guard branches - must return immediately, no NPE, no persistence
    // ------------------------------------------------------------------------------------------- //

    @Test
    void inactiveIntegrationIsIgnored() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            String legacyId = uniqueLegacyId();
            String src = uniqueSourceSeries();
            stub.respondJson("[" + remoteMessageJson(legacyId, src, MainType.NW, Type.COASTAL_WARNING,
                    System.currentTimeMillis(), System.currentTimeMillis()) + "]");

            NiordIntegrationVo vo = integration(stub.baseUrl(), src);
            vo.setActive(false); // guard: inactive -> return immediately

            executionService.processNiordIntegration(vo);

            assertEquals(0, stub.requestCount(),
                    "an inactive integration must not even contact the remote server");
            assertTrue(reloadByLegacyId(legacyId).isEmpty(),
                    "an inactive integration must not import any message");
        }
    }

    @Test
    void integrationWithEmptyMappingsIsIgnored() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            stub.respondJson("[]");

            NiordIntegrationVo vo = new NiordIntegrationVo();
            vo.setId(2);
            vo.setUrl(stub.baseUrl());
            vo.setActive(true);
            // No mappings - guard must short-circuit before any NPE on the empty mapping list.

            executionService.processNiordIntegration(vo);

            assertEquals(0, stub.requestCount(),
                    "an integration with no message-series mappings must not contact the remote server");
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // IMPORT - create a fetched message not yet held locally
    // ------------------------------------------------------------------------------------------- //

    @Test
    void importCreatesNewMessageWithLegacyIdAndTargetSeries() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            String legacyId = uniqueLegacyId();
            String src = uniqueSourceSeries();
            stub.respondJson("[" + remoteMessageJson(legacyId, src, MainType.NW, Type.COASTAL_WARNING,
                    System.currentTimeMillis(), System.currentTimeMillis()) + "]");

            executionService.processNiordIntegration(integration(stub.baseUrl(), src));

            assertEquals("/rest/public/v1/messages", stub.lastRequest().path(),
                    "the engine must fetch the public messages endpoint");

            List<Message> imported = reloadByLegacyId(legacyId);
            assertEquals(1, imported.size(), "exactly one message must be imported");
            Message m = imported.get(0);
            assertEquals(legacyId, m.getLegacyId(), "the imported message must carry the source id as its legacy id");
            assertNotNull(m.getMessageSeries(), "the imported message must be assigned a message series");
            assertEquals(TARGET_SERIES, m.getMessageSeries().getSeriesId(),
                    "the imported message must be assigned the TARGET series, not the source series");
            assertNotNull(m.getMessageSeries().getId(),
                    "the assigned series must be a persisted seeded entity");
            // Re-read the lazy desc collection inside an open session to avoid LazyInitializationException.
            String[] title = new String[1];
            Tx.requiringNew().run(() ->
                    title[0] = em.find(Message.class, m.getId()).getDesc("en").getTitle());
            assertEquals("remote title", title[0],
                    "the imported message must carry the remote title");
        }
    }

    @Test
    void importLeavesAlreadyImportedMessageWithEqualUpdatedUntouched() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            String legacyId = uniqueLegacyId();
            String src = uniqueSourceSeries();

            // A local copy already exists, "updated" well in the future so the remote copy is NOT newer.
            Date localUpdated = new Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000L);
            Integer localId = commitLocalPublished(legacyId, localUpdated);

            // Remote "updated" is in the past => older than the local copy => must be left untouched.
            long remoteUpdated = System.currentTimeMillis() - 10L * 24 * 60 * 60 * 1000L;
            stub.respondJson("[" + remoteMessageJson(legacyId, src, MainType.NW, Type.COASTAL_WARNING,
                    remoteUpdated, System.currentTimeMillis()) + "]");

            executionService.processNiordIntegration(integration(stub.baseUrl(), src));

            List<Message> rows = reloadByLegacyId(legacyId);
            assertEquals(1, rows.size(),
                    "an already-imported message that is not newer remotely must NOT be re-imported");
            assertEquals(localId, rows.get(0).getId(), "the existing local row must be kept");
            assertEquals(Status.PUBLISHED, rows.get(0).getStatus(),
                    "an untouched message must remain PUBLISHED, not cancelled");
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // CANCEL - an own PUBLISHED message no longer present remotely
    // ------------------------------------------------------------------------------------------- //

    @Test
    void ownPublishedMessageMissingFromFetchListIsCancelled() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            String legacyId = uniqueLegacyId();
            String src = uniqueSourceSeries();

            Integer localId = commitLocalPublished(legacyId, new Date());

            // The remote server serves another warning but not ours -> our published copy is withdrawn remotely.
            String other = uniqueLegacyId();
            stub.respondJson("[" + remoteMessageJson(other, src, MainType.NW, Type.COASTAL_WARNING,
                    System.currentTimeMillis(), System.currentTimeMillis()) + "]");

            executionService.processNiordIntegration(integration(stub.baseUrl(), src));

            Message reloaded = reloadById(localId);
            assertNotNull(reloaded, "the message row must still exist (cancel is a soft state change)");
            assertEquals(Status.CANCELLED, reloaded.getStatus(),
                    "a published message absent from the fetch list must be CANCELLED");
            assertNotNull(reloaded.getPublishDateTo(), "cancelling must stamp publishDateTo");
            assertEquals(1, reloadByLegacyId(other).size(), "the warning the remote server serves is imported");
        }
    }

    /**
     * An empty answer is not read as every warning withdrawn (INT5): the source series renamed or removed there, or
     * a server with no data yet behind the URL, would otherwise cancel every imported warning in force. The own
     * published message is kept, and the run logs a WARN instead.
     */
    @Test
    void anEmptyAnswerKeepsTheOwnPublishedMessages() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            String legacyId = uniqueLegacyId();
            String src = uniqueSourceSeries();

            Integer localId = commitLocalPublished(legacyId, new Date());
            // The other server knows none of the imported warnings by id either (its series renamed, or no data)
            stub.respond(req -> req.path().startsWith("/rest/public/v1/message/")
                    ? HttpStub.StubResponse.status(404)
                    : HttpStub.StubResponse.json("[]"));

            executionService.processNiordIntegration(integration(stub.baseUrl(), src));

            assertEquals(1, stub.requests().stream().filter(r -> r.path().equals("/rest/public/v1/messages")).count(),
                    "the engine fetched the list from the remote server");
            assertTrue(stub.requests().stream().anyMatch(r -> r.path().equals("/rest/public/v1/message/" + legacyId)),
                    "and asked it for the imported warning by its id (R5-1)");
            Message reloaded = reloadById(localId);
            assertNotNull(reloaded);
            assertEquals(Status.PUBLISHED, reloaded.getStatus(),
                    "an empty answer must not cancel the own published message");
            assertEquals(1, reloadByLegacyId(legacyId).size(), "and adds no row");
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // RE-IMPORT - a fetched message updated after the local copy
    // ------------------------------------------------------------------------------------------- //

    @Test
    void fetchedMessageNewerThanLocalCopyCancelsOldAndReImports() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            String legacyId = uniqueLegacyId();
            String src = uniqueSourceSeries();

            // Local copy "updated" in the past so the remote copy is reliably newer.
            Date localUpdated = new Date(System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000L);
            Integer oldLocalId = commitLocalPublished(legacyId, localUpdated);

            long remoteUpdated = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000L; // newer than local
            stub.respondJson("[" + remoteMessageJson(legacyId, src, MainType.NW, Type.COASTAL_WARNING,
                    remoteUpdated, System.currentTimeMillis()) + "]");

            executionService.processNiordIntegration(integration(stub.baseUrl(), src));

            List<Message> rows = reloadByLegacyId(legacyId);
            assertEquals(2, rows.size(),
                    "an updated remote message must yield two rows: the cancelled old copy and a fresh re-import");

            Message oldRow = rows.stream().filter(r -> r.getId().equals(oldLocalId)).findFirst().orElse(null);
            Message newRow = rows.stream().filter(r -> !r.getId().equals(oldLocalId)).findFirst().orElse(null);
            assertNotNull(oldRow, "the original local row must still be present");
            assertNotNull(newRow, "a freshly imported row must have been created");

            assertEquals(Status.CANCELLED, oldRow.getStatus(), "the old copy must be CANCELLED before re-import");
            assertEquals(legacyId, newRow.getLegacyId(), "the re-imported copy must keep the same legacy id");
            assertEquals(TARGET_SERIES, newRow.getMessageSeries().getSeriesId(),
                    "the re-imported copy must be on the target series");
            assertNotEquals(oldLocalId, newRow.getId(), "the re-import must be a distinct new row");
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // importMessage - field normalisation
    // ------------------------------------------------------------------------------------------- //

    @Test
    void importCoercesTypeWhoseMainTypeDiffersFromTargetSeries() throws Exception {
        // The seeded dma-nw target series has an EMPTY fixed type set, so the engine never takes the
        // "fixed types -> first allowed" branch for it. It DOES take the adjacent branch: it forces the
        // imported message's mainType to the series mainType (NW) and, because the remote NM type's
        // mainType (NM) then differs from NW, coerces the type to an NW type. We assert that observable
        // outcome. (The exact NW type chosen by MainType.anyType() comes from a HashSet iterator and is
        // intentionally not asserted; only that the result is an NW type.) See notes for why the literal
        // "fixed type set" sub-case is omitted.
        try (HttpStub stub = HttpStub.start()) {
            String legacyId = uniqueLegacyId();
            String src = uniqueSourceSeries();
            // Remote message declares an NM type on an NW target series.
            stub.respondJson("[" + remoteMessageJson(legacyId, src, MainType.NM, Type.TEMPORARY_NOTICE,
                    System.currentTimeMillis(), System.currentTimeMillis()) + "]");

            executionService.processNiordIntegration(integration(stub.baseUrl(), src));

            List<Message> imported = reloadByLegacyId(legacyId);
            assertEquals(1, imported.size(), "the message must still be imported despite the mismatched type");
            Message m = imported.get(0);
            assertEquals(MainType.NW, m.getMainType(),
                    "the imported message main-type must be coerced to the target series main-type (NW)");
            assertNotNull(m.getType(), "a type must be assigned");
            assertEquals(MainType.NW, m.getType().getMainType(),
                    "the type must be coerced to one whose main-type matches the NW target series");
            assertNotEquals(Type.TEMPORARY_NOTICE, m.getType(),
                    "the original NM type must not survive on an NW series");
        }
    }

    @Test
    void importDefaultsMissingPublishDateFromToAboutNow() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            String legacyId = uniqueLegacyId();
            String src = uniqueSourceSeries();
            long before = System.currentTimeMillis() - 1000L;
            // publishDateFrom = null in the remote payload -> engine must default it to ~now.
            stub.respondJson("[" + remoteMessageJson(legacyId, src, MainType.NW, Type.COASTAL_WARNING,
                    System.currentTimeMillis(), null) + "]");

            executionService.processNiordIntegration(integration(stub.baseUrl(), src));
            long after = System.currentTimeMillis() + 60_000L;

            List<Message> imported = reloadByLegacyId(legacyId);
            assertEquals(1, imported.size());
            Date publishFrom = imported.get(0).getPublishDateFrom();
            assertNotNull(publishFrom, "a null remote publishDateFrom must be defaulted, not left null");
            assertTrue(publishFrom.getTime() >= before && publishFrom.getTime() <= after,
                    "the defaulted publishDateFrom must be approximately now, was " + publishFrom);
        }
    }

    @Test
    void importWithAssignNewUidsGivesAFreshUidNotTheSourceId() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            String legacyId = uniqueLegacyId();
            String src = uniqueSourceSeries();
            stub.respondJson("[" + remoteMessageJson(legacyId, src, MainType.NW, Type.COASTAL_WARNING,
                    System.currentTimeMillis(), System.currentTimeMillis()) + "]");

            NiordIntegrationVo vo = integration(stub.baseUrl(), src);
            vo.setAssignNewUids(true);

            executionService.processNiordIntegration(vo);

            List<Message> imported = reloadByLegacyId(legacyId);
            assertEquals(1, imported.size());
            Message m = imported.get(0);
            assertNotNull(m.getUid(), "assignNewUids must still leave a non-null uid");
            assertNotEquals(legacyId, m.getUid(),
                    "with assignNewUids the uid must NOT be reused from the source id");
            // The source id is preserved only as the legacy id.
            assertEquals(legacyId, m.getLegacyId(), "the source id must be preserved as the legacy id");
        }
    }

    @Test
    void importWithCreateBaseDataFalseDropsUnresolvableAreas() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            String legacyId = uniqueLegacyId();
            String src = uniqueSourceSeries();
            // A remote message carrying an area that does not exist locally. With createBaseData=false the
            // engine must NOT create it and must drop the unresolved area, yet still import the message.
            String json = "{"
                    + "\"id\":\"" + legacyId + "\","
                    + "\"updated\":" + System.currentTimeMillis() + ","
                    + "\"messageSeries\":{\"seriesId\":\"" + src + "\",\"mainType\":\"NW\"},"
                    + "\"mainType\":\"NW\","
                    + "\"type\":\"COASTAL_WARNING\","
                    + "\"status\":\"PUBLISHED\","
                    + "\"publishDateFrom\":" + System.currentTimeMillis() + ","
                    + "\"areas\":[{\"id\":-987654321,\"descs\":[{\"lang\":\"en\",\"name\":\"No Such IT Area\"}]}],"
                    + "\"parts\":[{\"type\":\"DETAILS\",\"descs\":[{\"lang\":\"en\",\"subject\":\"s\",\"details\":\"d\"}]}],"
                    + "\"descs\":[{\"lang\":\"en\",\"title\":\"remote title\"}]"
                    + "}";
            stub.respondJson("[" + json + "]");

            NiordIntegrationVo vo = integration(stub.baseUrl(), src);
            vo.setCreateBaseData(false);

            executionService.processNiordIntegration(vo);

            List<Message> imported = reloadByLegacyId(legacyId);
            assertEquals(1, imported.size(), "the message must be imported even when its area cannot be resolved");
            // Re-read the lazy areas collection inside an open session to avoid LazyInitializationException.
            Integer importedId = imported.get(0).getId();
            boolean[] areasEmpty = new boolean[1];
            Tx.requiringNew().run(() ->
                    areasEmpty[0] = em.find(Message.class, importedId).getAreas().isEmpty());
            assertTrue(areasEmpty[0],
                    "an unresolvable area must be dropped when createBaseData is false");
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // FAULT TOLERANCE - remote failures are swallowed, nothing persisted, no exception
    // ------------------------------------------------------------------------------------------- //

    @Test
    void remoteServerErrorIsSwallowedAndNothingIsImported() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            String legacyId = uniqueLegacyId();
            String src = uniqueSourceSeries();
            stub.respondStatus(500); // remote responds 500 -> fetch throws internally and is swallowed

            // Must not throw.
            executionService.processNiordIntegration(integration(stub.baseUrl(), src));

            assertTrue(stub.requestCount() >= 1, "the engine must have attempted the remote fetch");
            assertTrue(reloadByLegacyId(legacyId).isEmpty(),
                    "a 5xx remote response must result in no imported messages");
        }
    }

    @Test
    void closedRemotePortIsSwallowedAndNothingIsImported() {
        String legacyId = uniqueLegacyId();
        String src = uniqueSourceSeries();
        // No server is listening on this URL: the connect attempt fails fast and is swallowed.
        NiordIntegrationVo vo = integration("http://127.0.0.1:1", src);

        // Must not throw.
        executionService.processNiordIntegration(vo);

        assertTrue(reloadByLegacyId(legacyId).isEmpty(),
                "an unreachable remote server must result in no imported messages and no exception");
    }
}
