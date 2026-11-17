/*
 * Copyright 2026 Danish Maritime Authority.
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
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.niord.test.JmsFaults;
import org.niord.test.Tx;
import org.niord.test.http.HttpStub;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fault injection on the Niord integration pull: what the target series holds after the other server answers
 * something implausible, and what an imported warning loses and how the run says so. The error hunt of 2026-11-12,
 * wave 2 (track C-tests), ran INT5 and INT6 here; their fixes turned the pins into these tests. The other server is an
 * {@link HttpStub}; the engine runs in its own committed transaction, so the fixtures are committed and removed by
 * their legacy ids afterwards, as in {@link NiordIntegrationImportITTest}.
 */
class NiordIntegrationFaultITTest extends ITTest {

    private static final String TARGET_SERIES = "dma-nw";

    @Autowired
    NiordIntegrationExecutionService executionService;

    @Autowired
    MessageService messageService;

    @PersistenceContext
    EntityManager em;

    private final List<String> legacyIds = new ArrayList<>();

    @BeforeEach
    void allowLoopbackFetches() {
        AopTestUtils.<NiordIntegrationExecutionService>getTargetObject(executionService).allowPrivateHosts = true;
    }

    @AfterEach
    void cleanup() {
        for (String legacyId : legacyIds) {
            try {
                Tx.requiringNew().run(() -> {
                    for (Message m : em.createQuery("select m from Message m where m.legacyId = :lid", Message.class)
                            .setParameter("lid", legacyId).getResultList()) {
                        em.createQuery("delete from MessageHistory mh where mh.message.id = :id")
                                .setParameter("id", m.getId()).executeUpdate();
                        em.remove(m);
                    }
                });
            } catch (Exception ignored) {
                // best effort
            }
        }
        legacyIds.clear();
        AopTestUtils.<NiordIntegrationExecutionService>getTargetObject(executionService).allowPrivateHosts = false;
    }

    private String legacyId() {
        String id = UidUtils.newUid();
        legacyIds.add(id);
        return id;
    }

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

    private static String remoteMessage(String id, String sourceSeries, long updated, String extraFields) {
        return "{"
                + "\"id\":\"" + id + "\","
                + "\"updated\":" + updated + ","
                + "\"messageSeries\":{\"seriesId\":\"" + sourceSeries + "\",\"mainType\":\"NW\"},"
                + "\"mainType\":\"NW\","
                + "\"type\":\"COASTAL_WARNING\","
                + "\"status\":\"PUBLISHED\","
                + "\"publishDateFrom\":" + System.currentTimeMillis() + ","
                + extraFields
                + "\"parts\":[{\"type\":\"DETAILS\",\"descs\":[{\"lang\":\"en\",\"subject\":\"s\",\"details\":\"d\"}]}],"
                + "\"descs\":[{\"lang\":\"en\",\"title\":\"remote title\"}]"
                + "}";
    }

    /** The committed rows carrying the legacy id, oldest first, as their statuses. */
    private List<Status> statusesOf(String legacyId) {
        return Tx.requiringNew().call(() -> em.createQuery(
                        "select m.status from Message m where m.legacyId = :lid order by m.id", Status.class)
                .setParameter("lid", legacyId).getResultList());
    }

    /** Something the engine runs, which may throw. */
    private interface Run {
        void run() throws Exception;
    }

    /** What the engine logs at WARN or above while the given runs, as text. */
    private static List<String> warningsOf(Run run) throws Exception {
        var log = JmsFaults.captureLog(NiordIntegrationExecutionService.class);
        try {
            run.run();
        } finally {
            JmsFaults.stopCapture(NiordIntegrationExecutionService.class, log);
        }
        return JmsFaults.warningsAndErrors(log);
    }

    /** A committed local PUBLISHED copy on the target series with the given legacy id. */
    private void commitLocalPublished(String legacyId, String title) {
        Tx.requiringNew().run(() -> {
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
            d.setTitle(title);
            messageService.createMessage(msg);
        });
    }

    // ------------------------------------------------------------------------------------------- //
    // INT5 (a): an empty answer
    // ------------------------------------------------------------------------------------------- //

    /**
     * INT5 (a). The other server first serves two warnings, which are imported PUBLISHED. Then it answers
     * {@code 200 []} (its source series renamed, an empty server behind the URL, or its own search swallowing a
     * failure, PORT-2). Every imported warning of the target series used to be cancelled with {@code updateStatus},
     * with nothing logged, and the next normal answer imported the two again as new rows.
     *
     * <p>Now an empty answer for a series that holds imported warnings is not read as a withdrawal of all of them:
     * both stay PUBLISHED, a WARN names the source and the target series, and the next normal answer adds no second
     * copy.
     */
    @Test
    void anEmptyAnswerFromTheOtherServerKeepsTheImportedWarnings() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            String source = "it-int5-src-" + System.nanoTime();
            String first = legacyId();
            String second = legacyId();
            String both = "[" + remoteMessage(first, source, System.currentTimeMillis(), "") + ","
                    + remoteMessage(second, source, System.currentTimeMillis(), "") + "]";
            NiordIntegrationVo vo = integration(stub.baseUrl(), source);

            stub.respondJson(both);
            executionService.processNiordIntegration(vo);
            assertEquals(List.of(Status.PUBLISHED), statusesOf(first), "precondition: the first warning is imported");
            assertEquals(List.of(Status.PUBLISHED), statusesOf(second), "precondition: the second warning is imported");

            // The other server knows neither warning by its id either (its series renamed, or no data behind the URL)
            stub.respond(req -> req.path().startsWith("/rest/public/v1/message/")
                    ? HttpStub.StubResponse.status(404)
                    : HttpStub.StubResponse.json("[]"));
            List<String> warnings = warningsOf(() -> executionService.processNiordIntegration(vo));
            assertEquals(List.of(Status.PUBLISHED), statusesOf(first), "an empty answer keeps the first warning");
            assertEquals(List.of(Status.PUBLISHED), statusesOf(second), "and the second");
            assertTrue(warnings.stream().anyMatch(w -> w.contains("served no message of") && w.contains(source)
                            && w.contains(TARGET_SERIES) && w.contains("rather than cancelling them")),
                    "a WARN names the source and the target series: " + warnings);

            stub.respondJson(both);
            executionService.processNiordIntegration(vo);
            assertEquals(List.of(Status.PUBLISHED), statusesOf(first),
                    "the next normal answer adds no second copy of the first warning");
            assertEquals(List.of(Status.PUBLISHED), statusesOf(second), "nor of the second");
        }
    }

    /**
     * R5-1 (INT5). The source series' only published warning is imported here. The other server then cancels it, and
     * its list answers {@code 200 []}: an empty answer, like the renamed series above, but the warning was withdrawn.
     * The run asks the other server for the warning by its id, reads CANCELLED, and cancels the copy here, as it did
     * before the INT5 change. A warning the other server still answers as PUBLISHED is kept.
     */
    @Test
    void theLastWarningOfASeriesCancelledAtTheSourceIsCancelledHere() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            String source = "it-int5-src-" + System.nanoTime();
            String withdrawn = legacyId();
            String stillPublished = legacyId();
            NiordIntegrationVo vo = integration(stub.baseUrl(), source);

            stub.respondJson("[" + remoteMessage(withdrawn, source, System.currentTimeMillis(), "") + ","
                    + remoteMessage(stillPublished, source, System.currentTimeMillis(), "") + "]");
            executionService.processNiordIntegration(vo);
            assertEquals(List.of(Status.PUBLISHED), statusesOf(withdrawn), "precondition: the warning is imported");
            assertEquals(List.of(Status.PUBLISHED), statusesOf(stillPublished), "precondition: and the other");

            String cancelledThere = remoteMessage(withdrawn, source, System.currentTimeMillis(), "")
                    .replace("\"status\":\"PUBLISHED\"", "\"status\":\"CANCELLED\"");
            String publishedThere = remoteMessage(stillPublished, source, System.currentTimeMillis(), "");
            stub.respond(req -> {
                if (req.path().equals("/rest/public/v1/message/" + withdrawn)) {
                    return HttpStub.StubResponse.json(cancelledThere);
                }
                if (req.path().equals("/rest/public/v1/message/" + stillPublished)) {
                    return HttpStub.StubResponse.json(publishedThere);
                }
                return HttpStub.StubResponse.json("[]");
            });
            executionService.processNiordIntegration(vo);

            assertEquals(List.of(Status.CANCELLED), statusesOf(withdrawn),
                    "the warning cancelled at the source is cancelled here");
            assertEquals(List.of(Status.PUBLISHED), statusesOf(stillPublished),
                    "a warning the source still answers as published is kept");
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // INT5 (b): two own copies with one legacy id
    // ------------------------------------------------------------------------------------------- //

    /**
     * INT5 (b). The target series holds two PUBLISHED copies of one source warning (no unique index on the legacy
     * id; a manual Execute overlapping the per-minute run, or a re-import, leaves them). {@code Collectors.toMap} used
     * to throw {@code IllegalStateException} on the duplicate key before any cancel, create or update, every run: a
     * new warning the other server served was never imported, with an ERROR line per run as the only trace.
     *
     * <p>Now the two copies are reported in a WARN naming both uids and the legacy id, the copy with the lowest id is
     * the one kept in step, and the rest of the series reconciles: the new warning is imported, and the two copies stay
     * as they were.
     */
    @Test
    void twoOwnCopiesWithOneLegacyIdAreReportedAndTheSeriesStillReconciles() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            String source = "it-int5b-src-" + System.nanoTime();
            String duplicated = legacyId();
            String fresh = legacyId();
            commitLocalPublished(duplicated, "IT int5b copy 1");
            commitLocalPublished(duplicated, "IT int5b copy 2");
            List<String> copies = Tx.requiringNew().call(() -> em.createQuery(
                            "select m.uid from Message m where m.legacyId = :lid order by m.id", String.class)
                    .setParameter("lid", duplicated).getResultList());
            stub.respondJson("[" + remoteMessage(duplicated, source, 0L, "") + ","
                    + remoteMessage(fresh, source, System.currentTimeMillis(), "") + "]");

            List<String> warnings = warningsOf(() -> {
                executionService.processNiordIntegration(integration(stub.baseUrl(), source));
                executionService.processNiordIntegration(integration(stub.baseUrl(), source));
            });

            assertEquals(2, stub.requestCount(), "both runs fetched from the other server");
            assertEquals(List.of(Status.PUBLISHED), statusesOf(fresh), "the new warning the other server serves is imported");
            assertEquals(List.of(Status.PUBLISHED, Status.PUBLISHED), statusesOf(duplicated),
                    "the two copies stay as they were");
            assertTrue(warnings.stream().anyMatch(w -> w.contains(copies.get(0)) && w.contains(copies.get(1))
                            && w.contains(duplicated)),
                    "a WARN names both copies and the legacy id: " + warnings);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // A warning with a position off the globe (the review's finding R6, with P1-12)
    // ------------------------------------------------------------------------------------------- //

    /**
     * The other server (an older one, without the position check) serves a new warning with a latitude of 95 among
     * others. Since P1-12 this Niord refuses the position when it builds the warning, and the refusal used to leave the
     * run: every warning after it in the list, and every update of an imported warning, was skipped, every run, for
     * as long as the other server served it.
     *
     * <p>Now that warning alone is skipped, with a WARN that names it; the warning after it is imported, and an
     * imported warning updated at the source is imported again and its old copy cancelled.
     */
    @Test
    void aWarningWithAPositionOffTheGlobeIsSkippedAndTheRestOfTheRunGoesOn() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            String source = "it-r6-src-" + System.nanoTime();
            String updated = legacyId();
            String offTheGlobe = legacyId();
            String good = legacyId();
            NiordIntegrationVo vo = integration(stub.baseUrl(), source);

            stub.respondJson("[" + remoteMessage(updated, source, System.currentTimeMillis() - 60_000L, "") + "]");
            executionService.processNiordIntegration(vo);
            assertEquals(List.of(Status.PUBLISHED), statusesOf(updated), "precondition: the first warning is imported");

            String latitude95 = remoteMessage(offTheGlobe, source, System.currentTimeMillis(), "").replace(
                    "\"parts\":[{\"type\":\"DETAILS\",",
                    "\"parts\":[{\"type\":\"DETAILS\",\"geometry\":{\"type\":\"FeatureCollection\",\"features\":["
                            + "{\"type\":\"Feature\",\"properties\":{},"
                            + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[10.0,95.0]}}]},");
            assertTrue(latitude95.contains("95.0"), "precondition: the warning carries the latitude of 95");
            stub.respondJson("[" + latitude95 + ","
                    + remoteMessage(good, source, System.currentTimeMillis(), "") + ","
                    + remoteMessage(updated, source, System.currentTimeMillis() + 60_000L, "") + "]");

            List<String> warnings = warningsOf(() -> executionService.processNiordIntegration(vo));

            assertEquals(List.of(), statusesOf(offTheGlobe), "the warning with the latitude of 95 is not imported");
            assertEquals(List.of(Status.PUBLISHED), statusesOf(good), "the warning after it is imported");
            assertEquals(List.of(Status.CANCELLED, Status.PUBLISHED), statusesOf(updated),
                    "the warning updated at the source is imported again, and its old copy cancelled");
            assertTrue(warnings.stream().anyMatch(w -> w.contains(offTheGlobe) && w.contains("skipped")),
                    "a WARN names the skipped warning: " + warnings);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // INT6: unresolvable areas, categories and charts
    // ------------------------------------------------------------------------------------------- //

    /**
     * INT6. With {@code createBaseData} off, a served warning whose area, category and chart the target does not have
     * is imported PUBLISHED without any of them ({@code importArea}, {@code importCategory} and {@code importChart}
     * answer null), as {@code NiordIntegrationImportITTest.importWithCreateBaseDataFalseDropsUnresolvableAreas} holds
     * for the area. It used to be left out with no word; now the run writes one WARN per reference left out, naming
     * the message's legacy id and the reference.
     */
    @Test
    void anImportedWarningWithUnresolvableReferencesIsImportedWithAWarningForEach() throws Exception {
        try (HttpStub stub = HttpStub.start()) {
            String source = "it-int6-src-" + System.nanoTime();
            String id = legacyId();
            long n = System.nanoTime();
            stub.respondJson("[" + remoteMessage(id, source, System.currentTimeMillis(),
                    "\"areas\":[{\"id\":-987654321,\"descs\":[{\"lang\":\"en\",\"name\":\"No such IT area " + n + "\"}]}],"
                            + "\"categories\":[{\"id\":-987654322,\"descs\":[{\"lang\":\"en\",\"name\":\"No such IT category "
                            + n + "\"}]}],"
                            + "\"charts\":[{\"chartNumber\":\"IT-NOCHART-" + n + "\"}],") + "]");
            NiordIntegrationVo vo = integration(stub.baseUrl(), source);
            vo.setCreateBaseData(false);

            List<String> warnings = warningsOf(() -> executionService.processNiordIntegration(vo));
            String prefix = "Integration 1: message " + id + ": the ";
            String suffix = " is not in this Niord and is left out";
            assertEquals(List.of(
                            prefix + "area 'No such IT area " + n + "' (en)" + suffix,
                            prefix + "category 'No such IT category " + n + "' (en)" + suffix,
                            prefix + "chart 'IT-NOCHART-" + n + "'" + suffix),
                    warnings.stream().filter(w -> w.contains(id)).toList(),
                    "one WARN for the area, the category and the chart: " + warnings);

            List<String> kept = Tx.requiringNew().call(() -> {
                Message m = em.createQuery("select m from Message m where m.legacyId = :lid", Message.class)
                        .setParameter("lid", id).getSingleResult();
                List<String> refs = new ArrayList<>();
                refs.add("status=" + m.getStatus());
                refs.addAll(m.getAreas().stream().map(a -> "area").collect(Collectors.toList()));
                refs.addAll(m.getCategories().stream().map(c -> "category").collect(Collectors.toList()));
                refs.addAll(m.getCharts().stream().map(c -> "chart").collect(Collectors.toList()));
                return refs;
            });
            assertEquals(List.of("status=PUBLISHED"), kept,
                    "the warning is published without its area, its category and its chart");
        }
    }
}
