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
package org.niord.core.message;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSConsumer;
import jakarta.jms.MapMessage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.area.Area;
import org.niord.core.message.vo.SystemMessageSeriesVo.NumberSequenceType;
import org.niord.core.promulgation.PromulgationException;
import org.niord.core.promulgation.PromulgationManager;
import org.niord.core.schedule.FiringExerciseService;
import org.niord.core.schedule.FiringPeriod;
import org.niord.model.message.MainType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.DeliveryFixtures;
import org.niord.test.DeliveryFixtures.FiringFixture;
import org.niord.test.ITTest;
import org.niord.test.JmsTestClient;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.niord.test.DeliveryFixtures.futureDay;
import static org.niord.test.DeliveryFixtures.newDraft;
import static org.niord.test.DeliveryFixtures.pastDate;
import static org.niord.test.DeliveryFixtures.uniq;

/**
 * Fault injection on the unattended delivery paths: the per-minute publication and expiry sweeps of
 * {@link ScheduledMessageService}, the hourly {@link FiringExerciseService} run and the per-minute free-text index
 * pass of {@link MessageLuceneIndex}. Each test makes one dependency fail the way a real fault would and asserts what
 * the system then does to the other messages of the same run: a sweep's message that cannot be changed, the firing
 * run's exercise that cannot be created or replaced, and the index pass of LUCENE-5 in be/docs/potential-errors.md.
 * The editor's own status changes over REST, which roll back as a whole for any exception, are here too, since they
 * share the promulgation spy.
 *
 * <p>Every run here is called through the bean's proxy, with no transaction of the test around it, so the run's own
 * transactions are the ones that commit or roll back. The fixtures are committed with {@link DeliveryFixtures} into
 * domains, message series and firing areas of their own, and removed after each test. The sweeps are called through
 * their seams ({@code publishDueMessages}, {@code expireDueMessages}) with this test's domains only, and the firing run
 * through {@code updateFiringExercises(Set)} with this test's schedules only, so the fixture's and other tests'
 * committed messages are never published, expired or cancelled.
 *
 * <p>The spies give this class an application context of its own; {@link DirtiesContext} closes it after the class,
 * as {@code S124ReconcileFailureITTest} does.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeliveryFaultInjectionITTest extends ITTest {

    @MockitoSpyBean
    PromulgationManager promulgationManager;

    @Autowired
    MessageService messageService;

    @MockitoSpyBean
    MessageSearchService messageSearchService;

    @MockitoSpyBean
    MessageQueryService messageQueryService;

    @Autowired
    ScheduledMessageService scheduledMessageService;

    @Autowired
    FiringExerciseService firingExerciseService;

    @Autowired
    MessageLuceneIndex messageLuceneIndex;

    @Autowired
    ConnectionFactory connectionFactory;

    @Autowired
    ApplicationContext context;

    @Value("${niord.jms.topic.messagestatustopic:messageStatus}")
    String messageStatusTopic;

    @PersistenceContext
    EntityManager em;

    private DeliveryFixtures fixtures;

    @BeforeEach
    void newFixtures() {
        fixtures = new DeliveryFixtures(context, em);
    }

    @AfterEach
    void removeTheFixtures() {
        fixtures.removeAll();
    }

    // ------------------------------------------------------------------------------------------- //
    // Fixtures
    // ------------------------------------------------------------------------------------------- //

    private String createSeries(String seriesId, MainType mainType, NumberSequenceType numbering, String shortFormat) {
        return fixtures.createSeries(seriesId, mainType, numbering, shortFormat);
    }

    private String createDomain(String domainId, String... seriesIds) {
        return fixtures.createDomain(domainId, seriesIds);
    }

    /** A committed VERIFIED NW message due for publication (a past publish-from date). */
    private String verifiedDue(String seriesId, String title) {
        return fixtures.verifiedDue(seriesId, title);
    }

    /** A committed PUBLISHED NW message whose publish-to date has passed: due for expiry. */
    private String publishedDueForExpiry(String seriesId, String title) {
        return fixtures.publishedDueForExpiry(seriesId, title);
    }

    private Status statusOf(String uid) {
        return fixtures.statusOf(uid);
    }

    private Integer numberOf(String uid) {
        return fixtures.numberOf(uid);
    }

    private int historyCount(String uid) {
        return fixtures.historyStatuses(uid).size();
    }

    private int nextNumber(String seriesId) {
        return fixtures.nextNumber(seriesId);
    }

    /** Every status event the topic carried within the wait, as "uid STATUS". */
    private static List<String> drain(JMSConsumer consumer, long waitMillis) throws Exception {
        List<String> events = new ArrayList<>();
        long deadline = System.currentTimeMillis() + waitMillis;
        while (System.currentTimeMillis() < deadline) {
            MapMessage m = (MapMessage) consumer.receive(200);
            if (m != null) {
                events.add(m.getString("UID") + " " + m.getString("STATUS"));
            }
        }
        return events;
    }

    /**
     * Every status event the topic carried until the expected ones have arrived (at most ten seconds), and in the
     * 1.5 seconds after: the window in which a second, duplicate or unexpected event would show. A fixed 1.5-second
     * window alone made an assertion that an event IS broadcast fail on a broker slower than that.
     */
    private static List<String> drainUntil(JMSConsumer consumer, List<String> expected) throws Exception {
        List<String> events = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline && !events.containsAll(expected)) {
            MapMessage m = (MapMessage) consumer.receive(200);
            if (m != null) {
                events.add(m.getString("UID") + " " + m.getString("STATUS"));
            }
        }
        events.addAll(drain(consumer, 1500));
        return events;
    }

    private static List<String> eventsOf(List<String> events, String uid) {
        return events.stream().filter(e -> e.startsWith(uid + " ")).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------------------------------- //
    // The publication sweep
    // ------------------------------------------------------------------------------------------- //

    /**
     * The publication sweep. Three warnings are due in the same minute: A in a series numbered by hand
     * whose short id nobody filled in, B beside it in the same domain, C in another domain. A's publish throws
     * {@code IllegalArgumentException("Message must be assigned a short ID")} from the numbering, an unchecked
     * exception. Each message's change runs in a transaction of its own, so A's failure rolls back A alone (back to
     * VERIFIED), and the DRAFT fallback then sets A back to DRAFT in a transaction of its own, with a DRAFT entry in
     * its history. B and C are published, numbered 1 each, committed and broadcast.
     *
     * <p>Before the fix the sweep was one transaction: A's failure marked it rollback-only, the commit threw
     * {@code UnexpectedRollbackException}, nothing was published or broadcast, a number of each series was used up,
     * and the next minute did the same.
     */
    @Test
    void oneWarningThatCannotBeNumberedIsSetBackToDraftAndEveryOtherDueWarningIsPublished() throws Exception {
        String n = uniq();
        String manual = createSeries("it-sched-m-" + n, MainType.NW, NumberSequenceType.MANUAL, null);
        String yearly1 = fixtures.createYearlyNwSeries("it-sched-y1-" + n, "S1");
        String yearly2 = fixtures.createYearlyNwSeries("it-sched-y2-" + n, "S2");
        String d1 = createDomain("it-sched-d1-" + n, manual, yearly1);
        String d2 = createDomain("it-sched-d2-" + n, yearly2);

        String a = verifiedDue(manual, "IT sched A " + n);   // manual numbering, no short id: cannot be published
        String b = verifiedDue(yearly1, "IT sched B " + n);
        String c = verifiedDue(yearly2, "IT sched C " + n);
        assertEquals(1, nextNumber(yearly1), "precondition: series 1 has numbered nothing");
        assertEquals(1, nextNumber(yearly2), "precondition: series 2 has numbered nothing");

        try (JmsTestClient jms = JmsTestClient.using(connectionFactory)) {
            JMSConsumer consumer = jms.subscribe(messageStatusTopic);

            // The first minute
            List<String> failed = scheduledMessageService.publishDueMessages(Set.of(d1, d2));
            assertEquals(List.of(a), failed, "A's publication failed, and only A's");
            List<String> events = drainUntil(consumer, List.of(b + " PUBLISHED", c + " PUBLISHED"));

            assertEquals(Status.PUBLISHED, statusOf(b), "B, due and publishable, is published");
            assertEquals(Integer.valueOf(1), numberOf(b), "B takes number 1 of its series");
            assertEquals(Status.PUBLISHED, statusOf(c), "C, due in another domain, is published");
            assertEquals(Integer.valueOf(1), numberOf(c), "C takes number 1 of its series");
            assertEquals(List.of(b + " PUBLISHED"), eventsOf(events, b), "B's publication is broadcast");
            assertEquals(List.of(c + " PUBLISHED"), eventsOf(events, c), "C's publication is broadcast");

            assertEquals(Status.DRAFT, statusOf(a), "A is set back to DRAFT, where a duty officer finds it");
            assertEquals(Status.DRAFT, fixtures.historyStatuses(a).get(0), "A's history records the DRAFT change");
            assertFalse(eventsOf(events, a).contains(a + " PUBLISHED"), "A's failed publication is not broadcast");

            // The next minute publishes nothing more and uses no number
            assertTrue(scheduledMessageService.publishDueMessages(Set.of(d1, d2)).isEmpty(),
                    "the next minute has nothing that fails");
            assertEquals(Status.PUBLISHED, statusOf(b));
            assertEquals(Status.PUBLISHED, statusOf(c));
            assertEquals(Status.DRAFT, statusOf(a), "A is not tried again");
            assertEquals(2, nextNumber(yearly1), "series 1 handed out B's number only");
            assertEquals(2, nextNumber(yearly2), "series 2 handed out C's number only");
        }
    }

    /**
     * The publication sweep, with a checked exception after the status is set: A's promulgation
     * throws a {@link PromulgationException} (checked) from {@code onUpdateMessageStatus}. A's change runs in a
     * transaction of its own that rolls back for any exception, so A's publication (its status and the number it
     * took) is rolled back, and the DRAFT fallback sets A back to DRAFT. B in the other domain is published and
     * broadcast.
     *
     * <p>Before the fix a checked exception did not mark the sweep's transaction, and the commit wrote A PUBLISHED
     * with its number, without its history entry or broadcast.
     */
    @Test
    void aCheckedPromulgationFailureOfAScheduledPublicationRollsItBackAndSetsTheWarningBackToDraft() throws Exception {
        String n = uniq();
        String yearly1 = fixtures.createYearlyNwSeries("it-schedc-y1-" + n, "C1");
        String yearly2 = fixtures.createYearlyNwSeries("it-schedc-y2-" + n, "C2");
        String d1 = createDomain("it-schedc-d1-" + n, yearly1);
        String d2 = createDomain("it-schedc-d2-" + n, yearly2);
        String a = verifiedDue(yearly1, "IT schedc A " + n);
        String b = verifiedDue(yearly2, "IT schedc B " + n);
        doThrow(new PromulgationException("IT: the promulgation of A failed"))
                .when(promulgationManager)
                .onUpdateMessageStatus(argThat((Message m) -> m != null && a.equals(m.getUid())
                        && m.getStatus() == Status.PUBLISHED));

        try (JmsTestClient jms = JmsTestClient.using(connectionFactory)) {
            JMSConsumer consumer = jms.subscribe(messageStatusTopic);

            List<String> failed = scheduledMessageService.publishDueMessages(Set.of(d1, d2));

            List<String> events = drainUntil(consumer, List.of(b + " PUBLISHED"));
            assertEquals(List.of(a), failed, "A's publication failed");
            assertEquals(Status.PUBLISHED, statusOf(b), "B in the other domain is published");
            assertEquals(List.of(b + " PUBLISHED"), eventsOf(events, b), "B's publication is broadcast");

            assertEquals(Status.DRAFT, statusOf(a), "A's publication is rolled back, and A is set back to DRAFT");
            assertNull(numberOf(a), "A keeps no number of its failed publication");
            assertFalse(eventsOf(events, a).contains(a + " PUBLISHED"), "A's failed publication is not broadcast");
            assertEquals(Status.DRAFT, fixtures.historyStatuses(a).get(0), "A's history records the DRAFT change");
        }
    }

    /**
     * The publication sweep, when the DRAFT fallback fails too: A's promulgation throws for every status
     * change. Both of A's changes roll back alone, A stays VERIFIED (and is tried again the next minute), and B in
     * the same domain is published.
     */
    @Test
    void aWarningThatCanNeitherBePublishedNorSetBackToDraftStaysVerifiedAndTheOthersArePublished() throws Exception {
        String n = uniq();
        String yearly = fixtures.createYearlyNwSeries("it-schedf-y-" + n, "F1");
        String d = createDomain("it-schedf-d-" + n, yearly);
        String a = verifiedDue(yearly, "IT schedf A " + n);
        String b = verifiedDue(yearly, "IT schedf B " + n);
        int historyBefore = historyCount(a);
        doThrow(new IllegalStateException("IT: every status change of A fails"))
                .when(promulgationManager)
                .onUpdateMessageStatus(argThat((Message m) -> m != null && a.equals(m.getUid())));

        assertEquals(List.of(a), scheduledMessageService.publishDueMessages(Set.of(d)), "A's publication failed");
        assertEquals(Status.PUBLISHED, statusOf(b), "B is published");
        assertEquals(Status.VERIFIED, statusOf(a), "A stays VERIFIED: its DRAFT fallback failed too");
        assertNull(numberOf(a), "A keeps no number");
        assertEquals(historyBefore, historyCount(a), "nothing of A's two failed changes is in its history");

        assertEquals(List.of(a), scheduledMessageService.publishDueMessages(Set.of(d)),
                "A is tried again the next minute");
    }

    /**
     * The publication sweep, a message series in two domains: both domains' searches can answer the same due
     * warning. The sweep searches a domain after the changes of the previous one are committed, and handles a
     * warning once, so the warning is published once, numbered 1, broadcast once, and nothing fails: a second
     * attempt would be refused as PUBLISHED to PUBLISHED, and its DRAFT fallback would run against the published
     * warning.
     */
    @Test
    void aDueWarningWhoseSeriesIsInTwoDomainsIsPublishedOnce() throws Exception {
        String n = uniq();
        String yearly = fixtures.createYearlyNwSeries("it-sched2d-y-" + n, "T1");
        String d1 = createDomain("it-sched2d-d1-" + n, yearly);
        String d2 = createDomain("it-sched2d-d2-" + n, yearly);
        String w = verifiedDue(yearly, "IT sched2d W " + n);

        try (JmsTestClient jms = JmsTestClient.using(connectionFactory)) {
            JMSConsumer consumer = jms.subscribe(messageStatusTopic);

            List<String> failed = scheduledMessageService.publishDueMessages(Set.of(d1, d2));

            List<String> events = drainUntil(consumer, List.of(w + " PUBLISHED"));
            assertTrue(failed.isEmpty(), "nothing failed: " + failed);
            assertEquals(Status.PUBLISHED, statusOf(w), "the warning is published");
            assertEquals(Integer.valueOf(1), numberOf(w), "with number 1 of its series");
            assertEquals(2, nextNumber(yearly), "and the series handed out one number");
            assertEquals(List.of(w + " PUBLISHED"), eventsOf(events, w), "its publication is broadcast once");
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // The same checked failure on the editor's own status changes
    // ------------------------------------------------------------------------------------------- //

    /** A committed VERIFIED dma-nw warning (a past date keeps its number off the current year). */
    private String verifiedNw(String title) {
        return Tx.requiringNew().call(() -> {
            Message m = messageService.createMessage(newDraft("dma-nw", Type.COASTAL_WARNING, title, pastDate()));
            messageService.updateStatus(m.getUid(), Status.VERIFIED);
            return m.getUid();
        });
    }

    /**
     * Expires a dma-nw warning the test left PUBLISHED. The series is the seed's own, which no fixture removes, and a
     * published NW without geometry stays in the shared database for every later class: the S-124 reconcile then
     * counts it failed on every sweep (SECOM5), and the S-124 classes that expect no failed unit fail.
     */
    private void takeOutOfForce(String uid) throws Exception {
        if (statusOf(uid) == Status.PUBLISHED) {
            Tx.requiringNew().run(() -> messageService.updateStatus(uid, Status.EXPIRED));
        }
    }

    /**
     * The editor publishes a warning over REST and a promulgation service throws its declared, checked
     * {@link PromulgationException} from {@code onUpdateMessageStatus} (no base-product service does; a country's
     * {@code BasePromulgationService}, a documented extension point, may). {@code MessageService.updateStatus} and
     * the endpoint roll back for any exception, so the request changes nothing: the editor is answered 500, and the
     * warning is still VERIFIED, with no number, no history entry and no broadcast.
     *
     * <p>Before the fix the transactions rolled back for unchecked exceptions only: the request committed the
     * status and number {@code updateStatus} had already set, without the history entry or the broadcast, behind
     * the 500.
     */
    @Test
    void aCheckedPromulgationFailureOnAManualPublishAnswers500AndChangesNothing() throws Exception {
        String uid = verifiedNw("IT manual publish " + uniq());
        int historyBefore = historyCount(uid);
        doThrow(new PromulgationException("IT: the country's promulgation refused the publication"))
                .when(promulgationManager)
                .onUpdateMessageStatus(argThat((Message m) -> m != null && uid.equals(m.getUid())
                        && m.getStatus() == Status.PUBLISHED));

        try (JmsTestClient jms = JmsTestClient.using(connectionFactory)) {
            JMSConsumer consumer = jms.subscribe(messageStatusTopic);

            io.restassured.RestAssured.given().auth().oauth2(accessToken())
                    .header("NiordDomain", "niord-nw")
                    .contentType(io.restassured.http.ContentType.JSON)
                    .body("PUBLISHED")
                    .when().put("/rest/messages/message/" + uid + "/status")
                    .then().statusCode(500);

            List<String> events = drain(consumer, 1500);
            assertEquals(Status.VERIFIED, statusOf(uid), "the failed publication is rolled back");
            assertNull(numberOf(uid), "and keeps no number");
            assertTrue(eventsOf(events, uid).isEmpty(), "nothing is broadcast: " + events);
            assertEquals(historyBefore, historyCount(uid), "and the warning's history is unchanged");
        }
    }

    /**
     * The editor cancels a published NW warning of an auto-numbered series over REST; {@code cancelMessage}
     * sets it CANCELLED and then creates its cancellation warning, whose {@code createMessage} fails with a checked
     * {@link PromulgationException} (from {@code onCreateMessage}). The cancellation rolls back with it, as
     * {@code cancelMessage}'s Javadoc promises: the editor is answered 500, the warning is still PUBLISHED, nothing is
     * broadcast and no cancellation warning exists.
     *
     * <p>Before the fix the request committed the source CANCELLED and broadcast it, without the cancellation NW the
     * series always issues, behind the 500.
     */
    @Test
    void aCheckedFailureOfTheCancellationWarningAnswers500AndLeavesTheWarningInForce() throws Exception {
        String uid = verifiedNw("IT manual cancel " + uniq());
        Tx.requiringNew().run(() -> messageService.updateStatus(uid, Status.PUBLISHED));
        try {
            doThrow(new PromulgationException("IT: the country's promulgation refused the cancellation warning"))
                    .when(promulgationManager)
                    .onCreateMessage(argThat((Message m) -> m != null && m.getKind() == MessageKind.CANCELLATION
                            && m.getReferences().stream().anyMatch(r -> uid.equals(r.getMessageId()))));

            try (JmsTestClient jms = JmsTestClient.using(connectionFactory)) {
                JMSConsumer consumer = jms.subscribe(messageStatusTopic);

                io.restassured.RestAssured.given().auth().oauth2(accessToken())
                        .header("NiordDomain", "niord-nw")
                        .contentType(io.restassured.http.ContentType.JSON)
                        .body("CANCELLED")
                        .when().put("/rest/messages/message/" + uid + "/status")
                        .then().statusCode(500);

                List<String> events = drain(consumer, 1500);
                assertEquals(Status.PUBLISHED, statusOf(uid), "the failed cancellation is rolled back");
                assertTrue(eventsOf(events, uid).isEmpty(), "nothing is broadcast: " + events);
            }
            Long minted = Tx.requiringNew().call(() -> em.createQuery("select count(m) from Message m join m.references r "
                            + "where m.kind = :kind and r.messageId = :uid", Long.class)
                    .setParameter("kind", MessageKind.CANCELLATION)
                    .setParameter("uid", uid)
                    .getSingleResult());
            assertEquals(0L, minted, "and no cancellation warning exists");
        } finally {
            takeOutOfForce(uid);
        }
    }

    /**
     * The cascade. The editor publishes P with "Also cancel the referenced messages", and P cancels
     * (a CANCELLATION reference) the published V of the same domain. V's cancellation fails with a checked
     * {@link PromulgationException}. The publication and the cancellations asked for are one act, so the whole
     * request rolls back: the editor is answered 500, P is still VERIFIED with no number, V is still PUBLISHED, and
     * nothing is broadcast.
     *
     * <p>Before the fix the cascade caught V's failure, logged it and went on: P was published and V stayed in
     * force beside its replacement, behind a 200.
     */
    @Test
    void aFailedCancellationOfAReferencedMessageRollsThePublishBack() throws Exception {
        String n = uniq();
        String victim = verifiedNw("IT cascade victim " + n);
        Tx.requiringNew().run(() -> messageService.updateStatus(victim, Status.PUBLISHED));
        try {
            String publisher = Tx.requiringNew().call(() -> {
                Message msg = newDraft("dma-nw", Type.COASTAL_WARNING, "IT cascade publisher " + n, pastDate());
                Reference ref = new Reference();
                ref.setMessageId(victim);
                ref.setType(org.niord.model.message.ReferenceType.CANCELLATION);
                msg.addReference(ref);
                Message m = messageService.createMessage(msg);
                messageService.updateStatus(m.getUid(), Status.VERIFIED);
                return m.getUid();
            });
            doThrow(new PromulgationException("IT: the country's promulgation refused the cancellation"))
                    .when(promulgationManager)
                    .onUpdateMessageStatus(argThat((Message m) -> m != null && victim.equals(m.getUid())
                            && m.getStatus() == Status.CANCELLED));

            try (JmsTestClient jms = JmsTestClient.using(connectionFactory)) {
                JMSConsumer consumer = jms.subscribe(messageStatusTopic);

                io.restassured.RestAssured.given().auth().oauth2(accessToken())
                        .header("NiordDomain", "niord-nw")
                        .contentType(io.restassured.http.ContentType.JSON)
                        .queryParam("cancelReferencedMessages", true)
                        .body("PUBLISHED")
                        .when().put("/rest/messages/message/" + publisher + "/status")
                        .then().statusCode(500);

                List<String> events = drain(consumer, 1500);
                assertEquals(Status.VERIFIED, statusOf(publisher), "the publication is rolled back with the cascade");
                assertNull(numberOf(publisher), "and the publisher keeps no number");
                assertEquals(Status.PUBLISHED, statusOf(victim), "the referenced warning is still in force");
                assertTrue(eventsOf(events, publisher).isEmpty(), "the publication is not broadcast: " + events);
                assertTrue(eventsOf(events, victim).isEmpty(), "the cancellation is not broadcast: " + events);
            }
        } finally {
            takeOutOfForce(victim);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // The expiry sweep
    // ------------------------------------------------------------------------------------------- //

    /**
     * The expiry sweep. P, Q (same domain) and R (another domain) are PUBLISHED with a publish-to date
     * that has passed. P's promulgation throws an unchecked exception when it expires. P's expiry rolls back alone:
     * P stays PUBLISHED and is tried again the next minute; Q and R are expired, committed and broadcast.
     *
     * <p>Before the fix the sweep was one transaction, rolled back by P's failure: Q and R stayed PUBLISHED on the
     * public site and in the API, minute after minute.
     */
    @Test
    void oneWarningWhoseExpiryFailsStaysInForceAndEveryOtherDueWarningExpires() throws Exception {
        String n = uniq();
        String yearly1 = fixtures.createYearlyNwSeries("it-exp-y1-" + n, "E1");
        String yearly2 = fixtures.createYearlyNwSeries("it-exp-y2-" + n, "E2");
        String d1 = createDomain("it-exp-d1-" + n, yearly1);
        String d2 = createDomain("it-exp-d2-" + n, yearly2);
        String p = publishedDueForExpiry(yearly1, "IT exp P " + n);
        String q = publishedDueForExpiry(yearly1, "IT exp Q " + n);
        String r = publishedDueForExpiry(yearly2, "IT exp R " + n);
        doThrow(new IllegalStateException("IT: the promulgation of P failed at expiry"))
                .when(promulgationManager)
                .onUpdateMessageStatus(argThat((Message m) -> m != null && p.equals(m.getUid())
                        && m.getStatus() == Status.EXPIRED));

        try (JmsTestClient jms = JmsTestClient.using(connectionFactory)) {
            JMSConsumer consumer = jms.subscribe(messageStatusTopic);

            assertEquals(List.of(p), scheduledMessageService.expireDueMessages(Set.of(d1, d2)),
                    "P's expiry failed, and only P's");
            List<String> events = drainUntil(consumer, List.of(q + " EXPIRED", r + " EXPIRED"));
            assertEquals(Status.EXPIRED, statusOf(q), "Q, due for expiry, is expired");
            assertEquals(Status.EXPIRED, statusOf(r), "R, due for expiry in another domain, is expired");
            assertEquals(Status.PUBLISHED, statusOf(p), "P stays PUBLISHED");
            assertEquals(List.of(q + " EXPIRED"), eventsOf(events, q), "Q's expiry is broadcast");
            assertEquals(List.of(r + " EXPIRED"), eventsOf(events, r), "R's expiry is broadcast");
            assertTrue(eventsOf(events, p).isEmpty(), "P's failed expiry is not broadcast: " + events);

            // The next minute tries P again and leaves Q and R as they are
            assertEquals(List.of(p), scheduledMessageService.expireDueMessages(Set.of(d1, d2)),
                    "P is tried again the next minute");
            assertEquals(Status.PUBLISHED, statusOf(p), "P stays PUBLISHED");
            assertEquals(Status.EXPIRED, statusOf(q));
            assertEquals(Status.EXPIRED, statusOf(r));
        }
    }

    /**
     * The sweeps' own work when nothing fails, the regression guard beside the sweep tests: a VERIFIED warning
     * whose publish-from date passed two minutes ago is PUBLISHED, and a PUBLISHED one whose publish-to date passed
     * two minutes ago (earlier today, not in another year) is EXPIRED, each with its status event on the topic. A
     * sweep that read "due" by the day instead of the minute, skipped today's expiries or no longer broadcast fails
     * here (detector check MC, M12 and M13).
     */
    @Test
    void dueWarningsArePublishedAndExpiredByTheSweepsAndTheirChangesBroadcast() throws Exception {
        String n = uniq();
        String yearly = fixtures.createYearlyNwSeries("it-sweep-y-" + n, "W");
        String d = createDomain("it-sweep-d-" + n, yearly);
        Date twoMinutesAgo = new Date(System.currentTimeMillis() - 2 * 60_000L);
        String toPublish = fixtures.verified(yearly, "IT sweep publish " + n, twoMinutesAgo);
        String toExpire = fixtures.published(yearly, "IT sweep expire " + n, twoMinutesAgo);

        try (JmsTestClient jms = JmsTestClient.using(connectionFactory)) {
            JMSConsumer consumer = jms.subscribe(messageStatusTopic);

            assertTrue(scheduledMessageService.publishDueMessages(Set.of(d)).isEmpty(), "no publication failed");
            assertTrue(scheduledMessageService.expireDueMessages(Set.of(d)).isEmpty(), "no expiry failed");

            // Waits for both events (at most ten seconds), then watches 1.5 seconds for a duplicate: a fixed 1.5-second
            // window alone fails this assertion on a broker slower than that
            List<String> events = drainUntil(consumer, List.of(toPublish + " PUBLISHED", toExpire + " EXPIRED"));
            assertEquals(Status.PUBLISHED, statusOf(toPublish), "the warning due two minutes ago is published");
            assertNotNull(numberOf(toPublish), "with a number of its series");
            assertEquals(Status.EXPIRED, statusOf(toExpire), "the warning whose end passed two minutes ago is expired");
            assertEquals(List.of(toPublish + " PUBLISHED"), eventsOf(events, toPublish), "the publication is broadcast");
            assertEquals(List.of(toExpire + " EXPIRED"), eventsOf(events, toExpire), "the expiry is broadcast");
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // The hourly firing-exercise run
    // ------------------------------------------------------------------------------------------- //

    /** A firing schedule over a source domain of its own and a target domain of its own, two areas, two templates. */
    private FiringFixture firingFixture() {
        return fixtures.firingFixture();
    }

    /** The committed exercise messages of the target series in the given area and status. */
    private List<String> exercises(FiringFixture f, Area area, Status status) {
        return Tx.requiringNew().call(() -> em.createQuery(
                        "select m.uid from Message m join m.areas a where m.messageSeries.seriesId = :series "
                                + "and a.id = :area and m.status = :status", String.class)
                .setParameter("series", f.targetSeries())
                .setParameter("area", area.getId())
                .setParameter("status", status)
                .getResultList());
    }

    private static boolean carriesArea(Message m, Area area) {
        return m != null && m.getAreas().stream().anyMatch(a -> area.getId().equals(a.getId()));
    }

    /** Matches the generated exercise of the given area, as {@code createMessage} hands it to the promulgations. */
    private static Message exerciseOf(FiringFixture f, Area area) {
        return argThat((Message m) -> m != null && m.getMessageSeries() != null
                && f.targetSeries().equals(m.getMessageSeries().getSeriesId()) && carriesArea(m, area));
    }

    /** One hourly run over the given schedules only (the run's seam), so no other schedule is touched. */
    private void runFiring(Integer... scheduleIds) {
        firingExerciseService.updateFiringExercises(Set.of(scheduleIds));
    }

    /**
     * The exercise of area 1 cannot be created: inside {@code createMessage} an unchecked exception is
     * thrown (here by the promulgation step, {@code PromulgationManager.onCreateMessage}; a flush failure or an NPE
     * does the same). Each area's exercise is created in a transaction of its own, so area 1's failure rolls back
     * area 1 alone: area 2's exercise is issued, the run throws nothing, and the next hour tries area 1 again without
     * issuing area 2's a second time.
     *
     * <p>Before the fix the run was one transaction: area 1's failure marked it rollback-only, the commit rolled
     * back area 2's exercise too, and the next hour did the same.
     */
    @Test
    void oneFiringAreaWhoseExerciseCannotBeCreatedLeavesTheOtherAreasIssued() throws Exception {
        FiringFixture f = firingFixture();
        doThrow(new IllegalStateException("IT: the exercise of area 1 cannot be saved"))
                .when(promulgationManager).onCreateMessage(exerciseOf(f, f.area1()));

        runFiring(f.scheduleId());

        assertEquals(1, exercises(f, f.area2(), Status.PUBLISHED).size(), "area 2's exercise is issued");
        assertTrue(exercises(f, f.area1(), Status.PUBLISHED).isEmpty(), "area 1's exercise is not");

        runFiring(f.scheduleId());
        assertEquals(1, exercises(f, f.area2(), Status.PUBLISHED).size(),
                "the next hour leaves area 2 with its one exercise, no duplicate");
        assertTrue(exercises(f, f.area1(), Status.PUBLISHED).isEmpty(), "area 1's exercise still fails");
    }

    /**
     * The exercise that cannot be created, the checked case: a CHECKED exception from inside {@code createMessage} for area 1 (the kind of
     * {@code createMessage}'s own validations, "Invalid type for message", and of the promulgation step's
     * {@link PromulgationException}) rolls back area 1's exercise alone, and area 2's exercise is issued. Before the
     * fix this case already held (a checked exception did not mark the run's one transaction); a guard that the
     * transaction per area keeps it.
     */
    @Test
    void aCheckedFailureOfOneFiringAreaLeavesTheOtherExercisesIssued() throws Exception {
        FiringFixture f = firingFixture();
        doThrow(new PromulgationException("IT: the exercise of area 1 is refused"))
                .when(promulgationManager).onCreateMessage(exerciseOf(f, f.area1()));

        runFiring(f.scheduleId());

        assertEquals(1, exercises(f, f.area2(), Status.PUBLISHED).size(), "area 2's exercise is issued");
        assertTrue(exercises(f, f.area1(), Status.PUBLISHED).isEmpty(), "area 1's exercise is not");
    }

    /**
     * Area 1's live exercise is replaced, and its replacement cannot be created: the first run issues both areas'
     * exercises, area 1's period is then extended, and the next run's new exercise for area 1 is refused with the
     * given exception from the promulgation step. The new exercise and the cancellation of the one it replaces are
     * one transaction, so the failure rolls back both: area 1's live exercise stays PUBLISHED, in force while its
     * period is scheduled, nothing of area 1 is cancelled or broadcast, and area 2 is untouched.
     */
    private void aReplacementThatCannotBeCreatedLeavesTheLiveOneInForce(Exception failure) throws Exception {
        FiringFixture f = firingFixture();
        runFiring(f.scheduleId());
        List<String> live = exercises(f, f.area1(), Status.PUBLISHED);
        List<String> other = exercises(f, f.area2(), Status.PUBLISHED);
        assertEquals(1, live.size(), "precondition: the first run issues area 1's exercise");
        assertEquals(1, other.size(), "precondition: the first run issues area 2's exercise");

        // Area 1's period is extended, so the next run replaces its exercise; the replacement is refused
        Tx.requiringNew().run(() -> em.createQuery("select p from FiringPeriod p where p.area.id = :area",
                        FiringPeriod.class)
                .setParameter("area", f.area1().getId())
                .getResultList()
                .forEach(p -> p.setToDate(futureDay(3, 14))));
        doThrow(failure).when(promulgationManager).onCreateMessage(exerciseOf(f, f.area1()));

        try (JmsTestClient jms = JmsTestClient.using(connectionFactory)) {
            JMSConsumer consumer = jms.subscribe(messageStatusTopic);

            runFiring(f.scheduleId());

            List<String> events = drain(consumer, 1500);
            assertEquals(Status.PUBLISHED, statusOf(live.get(0)), "the live exercise of area 1 stays in force");
            assertEquals(live, exercises(f, f.area1(), Status.PUBLISHED), "and nothing else of area 1 is in force");
            assertTrue(exercises(f, f.area1(), Status.CANCELLED).isEmpty(), "nothing of area 1 is cancelled");
            assertTrue(eventsOf(events, live.get(0)).isEmpty(), "and no change of it is broadcast: " + events);
            assertEquals(other, exercises(f, f.area2(), Status.PUBLISHED), "area 2's exercise is untouched");
        }
    }

    /**
     * A replacement exercise that cannot be created, with a checked failure ({@link PromulgationException}; the series' own type check, "Invalid type for
     * message", is the other checked cause). Before the fix the old exercise left the list of current exercises only
     * after its replacement was created, so the end of the run cancelled it, committed and broadcast: area 1 had no
     * firing warning in force while its period was still scheduled.
     */
    @Test
    void aReplacementExerciseThatCannotBeCreatedLeavesTheLiveOneInForce() throws Exception {
        aReplacementThatCannotBeCreatedLeavesTheLiveOneInForce(
                new PromulgationException("IT: the replacement exercise of area 1 is refused"));
    }

    /**
     * A replacement exercise that cannot be created, with an unchecked failure ({@link IllegalStateException}): the case a transaction per area alone, with
     * the old exercise left in the list of exercises to cancel, would send down the path where the live exercise is cancelled.
     */
    @Test
    void anUncheckedFailureOfAReplacementLeavesTheLiveOneInForce() throws Exception {
        aReplacementThatCannotBeCreatedLeavesTheLiveOneInForce(
                new IllegalStateException("IT: the replacement exercise of area 1 cannot be saved"));
    }

    /**
     * A failed template search. After a normal run has issued both exercises, the template search of the next run fails. The run
     * uses the search that lets its failure through ({@code MessageSearchService.searchOrFail}), so the schedule is
     * skipped for this run: both live exercises stay PUBLISHED, nothing is cancelled or broadcast, and the run throws
     * nothing.
     *
     * <p>Before the fix the search answered its failure with an empty page (PORT-2), which read as "no template":
     * every current exercise was cancelled, and the cancellations broadcast to the mailing lists and SECOM, while the
     * periods were still scheduled.
     */
    @Test
    void aFailedTemplateSearchLeavesEveryCurrentFiringExerciseInForce() throws Exception {
        FiringFixture f = firingFixture();
        runFiring(f.scheduleId());
        List<String> issued1 = exercises(f, f.area1(), Status.PUBLISHED);
        List<String> issued2 = exercises(f, f.area2(), Status.PUBLISHED);
        assertEquals(1, issued1.size(), "precondition: the first run issues area 1's exercise");
        assertEquals(1, issued2.size(), "precondition: the first run issues area 2's exercise");

        doThrow(new jakarta.persistence.PersistenceException("IT: the template search failed"))
                .when(messageSearchService)
                .searchOrFail(argThat((MessageSearchParams p) -> p != null && p.getSeriesIds() != null
                        && p.getSeriesIds().contains(f.sourceSeries())));

        try (JmsTestClient jms = JmsTestClient.using(connectionFactory)) {
            JMSConsumer consumer = jms.subscribe(messageStatusTopic);

            runFiring(f.scheduleId());

            List<String> events = drain(consumer, 1500);
            assertEquals(Status.PUBLISHED, statusOf(issued1.get(0)), "area 1's live exercise stays in force");
            assertEquals(Status.PUBLISHED, statusOf(issued2.get(0)), "area 2's live exercise stays in force");
            assertTrue(eventsOf(events, issued1.get(0)).isEmpty(), "nothing of area 1 is broadcast: " + events);
            assertTrue(eventsOf(events, issued2.get(0)).isEmpty(), "nothing of area 2 is broadcast: " + events);
        }
    }

    /**
     * The right path beside the failed template search: the source domain has withdrawn its firing-area templates (both
     * set CANCELLED), so the template search answers a genuine "none". The run then cancels both current exercises,
     * and the cancellations are broadcast. No cancellation warning is issued for them (by design, see
     * FiringExerciseServiceGapITTest).
     */
    @Test
    void aSourceDomainWithNoPublishedTemplateCancelsItsExercises() throws Exception {
        FiringFixture f = firingFixture();
        runFiring(f.scheduleId());
        List<String> issued1 = exercises(f, f.area1(), Status.PUBLISHED);
        List<String> issued2 = exercises(f, f.area2(), Status.PUBLISHED);
        assertEquals(1, issued1.size(), "precondition: the first run issues area 1's exercise");
        assertEquals(1, issued2.size(), "precondition: the first run issues area 2's exercise");

        Tx.requiringNew().run(() -> {
            messageService.updateStatus(f.template1(), Status.CANCELLED);
            messageService.updateStatus(f.template2(), Status.CANCELLED);
        });

        try (JmsTestClient jms = JmsTestClient.using(connectionFactory)) {
            JMSConsumer consumer = jms.subscribe(messageStatusTopic);

            runFiring(f.scheduleId());

            List<String> events = drainUntil(consumer,
                    List.of(issued1.get(0) + " CANCELLED", issued2.get(0) + " CANCELLED"));
            assertEquals(Status.CANCELLED, statusOf(issued1.get(0)), "area 1's exercise is cancelled");
            assertEquals(Status.CANCELLED, statusOf(issued2.get(0)), "area 2's exercise is cancelled");
            assertTrue(exercises(f, f.area1(), Status.PUBLISHED).isEmpty(), "and nothing replaces it");
            assertTrue(exercises(f, f.area2(), Status.PUBLISHED).isEmpty(), "and nothing replaces it");
            assertEquals(List.of(issued1.get(0) + " CANCELLED"), eventsOf(events, issued1.get(0)),
                    "the withdrawal of area 1's exercise is broadcast to the listeners");
            assertEquals(List.of(issued2.get(0) + " CANCELLED"), eventsOf(events, issued2.get(0)),
                    "the withdrawal of area 2's exercise is broadcast to the listeners");
        }
        Long minted = Tx.requiringNew().call(() -> em.createQuery("select count(m) from Message m join m.references r "
                        + "where m.kind = :kind and r.messageId in :uids", Long.class)
                .setParameter("kind", MessageKind.CANCELLATION)
                .setParameter("uids", List.of(issued1.get(0), issued2.get(0)))
                .getSingleResult());
        assertEquals(0L, minted, "no cancellation warning is issued for the cancelled exercises");
    }

    /**
     * One schedule's failure does not stop the others: two schedules of their own, and the template search
     * of the first one fails. The first schedule is skipped for this run, and the second one's exercises are issued
     * in the same run.
     */
    @Test
    void aScheduleWhoseTemplateSearchFailsIsSkippedAndTheOtherSchedulesRun() throws Exception {
        FiringFixture f1 = firingFixture();
        FiringFixture f2 = firingFixture();
        doThrow(new jakarta.persistence.PersistenceException("IT: the template search of schedule 1 failed"))
                .when(messageSearchService)
                .searchOrFail(argThat((MessageSearchParams p) -> p != null && p.getSeriesIds() != null
                        && p.getSeriesIds().contains(f1.sourceSeries())));

        runFiring(f1.scheduleId(), f2.scheduleId());

        assertTrue(exercises(f1, f1.area1(), Status.PUBLISHED).isEmpty(), "schedule 1 is skipped");
        assertEquals(1, exercises(f2, f2.area1(), Status.PUBLISHED).size(), "schedule 2 issues area 1's exercise");
        assertEquals(1, exercises(f2, f2.area2(), Status.PUBLISHED).size(), "schedule 2 issues area 2's exercise");
    }

    // ------------------------------------------------------------------------------------------- //
    // LUCENE-5: the free-text index pass
    // ------------------------------------------------------------------------------------------- //

    /**
     * LUCENE-5. Three committed messages carry a token each; the first of them throws while it is indexed (its
     * references cannot be read). The index pass runs in an empty index folder of its own, so other contexts'
     * passes on the shared folder do not interfere. The pass indexes the changed messages in one try: the failure
     * returns 0 before the marker and the reader refresh, so the next pass (every minute) starts from the same
     * marker and fails on the same message, and a re-index from the admin page ({@code recreateIndex}) first deletes
     * the index and then fails the same way. M2 and M3 are never searchable, and nothing but an ERROR line says so.
     *
     * <p>What is wrong: one message that cannot be indexed hides every later change from free-text search (the
     * admin app's, and mailing-list triggers with a query). The right assertions: M2 and M3 are found after the
     * pass, and M1's failure is recorded.
     */
    @Test
    void oneMessageThatCannotBeIndexedHidesEveryLaterMessageFromFreeTextSearch_documentsBug() throws Exception {
        String n = uniq();
        String[] tokens = {"zzfault" + n + "a", "zzfault" + n + "b", "zzfault" + n + "c"};
        List<String> uids = new ArrayList<>();
        for (String token : tokens) {
            uids.add(Tx.requiringNew().call(() ->
                    messageService.createMessage(newDraft("dma-nw", Type.COASTAL_WARNING, token, null)).getUid()));
            Thread.sleep(5); // distinct updated times, in creation order
        }
        String failing = uids.get(0);
        // The read the index pass makes. Since the LUCENE-4 fix (2026-11-12) that is findUpdatedMessagesAfter; the
        // stub was on findUpdatedMessages, which the pass no longer called, so nothing threw and this pin read as
        // flipped. The counter below shows that the fault reached the pass.
        java.util.concurrent.atomic.AtomicInteger injected = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<Message> real = (List<Message>) inv.callRealMethod();
            return real.stream().map(m -> {
                if (failing.equals(m.getUid())) {
                    injected.incrementAndGet();
                    return unindexable(m);
                }
                return m;
            }).collect(Collectors.toList());
        }).when(messageQueryService).findUpdatedMessagesAfter(any(Date.class), anyInt(), anyInt());

        MessageLuceneIndex index = AopTestUtils.getUltimateTargetObject(messageLuceneIndex);
        Path sharedFolder = index.indexFolder;
        var sharedReader = index.reader;
        Path ownFolder = Files.createTempDirectory("niord-it-lucene-fault");
        index.indexFolder = ownFolder;
        index.reader = null;
        try {
            messageLuceneIndex.updateLuceneIndex();
            assertTrue(injected.get() > 0, "the pass read M1 through the stub, so the fault was injected");
            assertNotFound(tokens[1], "M2 is not searchable after the pass that failed on M1");
            assertNotFound(tokens[2], "M3 is not searchable after the pass that failed on M1");

            messageLuceneIndex.updateLuceneIndex();
            assertNotFound(tokens[2], "the next pass fails on M1 again");

            messageLuceneIndex.recreateIndex();
            assertNotFound(tokens[1], "a re-index from the admin page fails on M1 too");
            assertNotFound(tokens[2], "a re-index from the admin page fails on M1 too");

            // Control: once M1 can be read again, the same re-index finds all three tokens in this folder, so the
            // "not found" above is the fault and not a search that could never find them (an IOException read as
            // "not found", a token the analyser splits). Without it a fix could leave this pin green (detector
            // check MC).
            org.mockito.Mockito.doCallRealMethod().when(messageQueryService)
                    .findUpdatedMessagesAfter(any(Date.class), anyInt(), anyInt());
            messageLuceneIndex.recreateIndex();
            for (String token : tokens) {
                assertFalse(messageLuceneIndex.searchIndex(token, "en", 10).isEmpty(),
                        "control: " + token + " is found once nothing throws");
            }
        } finally {
            if (index.reader != null) {
                index.reader.close();
            }
            index.indexFolder = sharedFolder;
            index.reader = sharedReader;
        }
    }

    /**
     * LUCENE-6 beside LUCENE-5. Each index pass also indexes the messages of a window behind its marker that it
     * missed: a message stamped before the time a pass read up to, and committed after that pass. Such a message
     * that throws while it is indexed must not change what the pass does with the messages after the marker. In an
     * index folder of its own: M1 is indexed and is the marker; M2 is then committed with a time two seconds before
     * the marker, and M3 after it; M2 throws while it is indexed. The pass indexes M3 and moves the marker to it,
     * leaves M2 out, and the next pass, once M2 can be read, indexes M2, which is still in the window behind the new
     * marker.
     */
    @Test
    void aMissedMessageThatCannotBeIndexedDoesNotHoldBackTheMessagesAfterTheMarker() throws Exception {
        String n = uniq();
        String[] tokens = {"zzlate" + n + "a", "zzlate" + n + "b", "zzlate" + n + "c"};

        MessageLuceneIndex index = AopTestUtils.getUltimateTargetObject(messageLuceneIndex);
        Path sharedFolder = index.indexFolder;
        var sharedReader = index.reader;
        Path ownFolder = Files.createTempDirectory("niord-it-lucene-late");
        index.indexFolder = ownFolder;
        index.reader = null;
        java.util.concurrent.locks.ReentrantLock lock = MessageLuceneIndex.writerLock(ownFolder);
        lock.lock();
        try {
            Tx.requiringNew().call(() ->
                    messageService.createMessage(newDraft("dma-nw", Type.COASTAL_WARNING, tokens[0], null)).getUid());
            messageLuceneIndex.updateLuceneIndex();
            assertFalse(messageLuceneIndex.searchIndex(tokens[0], "en", 10).isEmpty(), "M1 is indexed");
            MessageLuceneIndex.Marker marker = index.getMarker();

            java.time.Instant early = marker.updated().minusSeconds(2);
            Message late = Tx.requiringNew().call(() -> {
                Message m = messageService.createMessage(newDraft("dma-nw", Type.COASTAL_WARNING, tokens[1], null));
                em.createQuery("update Message m set m.updated = :t where m.id = :id")
                        .setParameter("t", java.sql.Timestamp.from(early))
                        .setParameter("id", m.getId())
                        .executeUpdate();
                return m;
            });
            Message after = Tx.requiringNew().call(() ->
                    messageService.createMessage(newDraft("dma-nw", Type.COASTAL_WARNING, tokens[2], null)));

            java.util.concurrent.atomic.AtomicInteger injected = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(inv -> {
                @SuppressWarnings("unchecked")
                List<Message> real = (List<Message>) inv.callRealMethod();
                return real.stream().map(m -> {
                    if (late.getUid().equals(m.getUid())) {
                        injected.incrementAndGet();
                        return unindexable(m);
                    }
                    return m;
                }).collect(Collectors.toList());
            }).when(messageQueryService).findByIds(any());

            messageLuceneIndex.updateLuceneIndex();
            assertTrue(injected.get() > 0, "the pass read M2 behind its marker, so the fault was injected");
            assertFalse(messageLuceneIndex.searchIndex(tokens[2], "en", 10).isEmpty(),
                    "M3, after the marker, is indexed in the same pass");
            assertEquals(after.getId(), index.getMarker().messageId(), "and the marker moves to M3");
            assertNotFound(tokens[1], "M2, which throws, is left out");

            org.mockito.Mockito.doCallRealMethod().when(messageQueryService).findByIds(any());
            messageLuceneIndex.updateLuceneIndex();
            assertFalse(messageLuceneIndex.searchIndex(tokens[1], "en", 10).isEmpty(),
                    "once M2 can be read, the next pass indexes it: it is still in the window behind the marker");
        } finally {
            lock.unlock();
            org.mockito.Mockito.doCallRealMethod().when(messageQueryService).findByIds(any());
            if (index.reader != null) {
                index.reader.close();
            }
            index.indexFolder = sharedFolder;
            index.reader = sharedReader;
        }
    }

    /** A copy of the message whose references cannot be read, the first value the index walk reads after its ids. */
    private static Message unindexable(Message m) {
        Message spy = org.mockito.Mockito.spy(m);
        doThrow(new IllegalStateException("IT: this message cannot be indexed")).when(spy).getReferences();
        return spy;
    }

    private void assertNotFound(String token, String message) throws Exception {
        List<Long> ids;
        try {
            ids = messageLuceneIndex.searchIndex(token, "en", 10);
        } catch (java.io.IOException noIndexYet) {
            ids = List.of();
        }
        assertFalse(!ids.isEmpty(), message + " (found " + ids + ")");
    }
}
