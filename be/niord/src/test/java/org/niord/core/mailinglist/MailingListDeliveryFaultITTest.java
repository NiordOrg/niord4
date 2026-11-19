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
package org.niord.core.mailinglist;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSRuntimeException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.mail.ScheduledMail;
import org.niord.core.message.Message;
import org.niord.core.message.MessageHistory;
import org.niord.core.message.MessageLuceneIndex;
import org.niord.core.message.MessagePart;
import org.niord.core.message.MessagePartDesc;
import org.niord.core.message.MessageQueryService;
import org.niord.core.message.MessageSeries;
import org.niord.core.message.MessageService;
import org.niord.core.script.ScriptResourceService;
import org.niord.core.user.Contact;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.Status;
import org.niord.model.message.Type;
import org.niord.test.ITTest;
import org.niord.test.ScriptResourceFixtures;
import org.niord.test.Tx;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fault injection on the mailing-list delivery path: what is made, recorded or sent when a step between a status
 * change (or a schedule) and the subscribers' mail fails: a status-change trigger whose template or filter fails, the
 * broker that cannot be reached (the status change is kept in the mailing lists' outbox and mailed from there), a
 * rolled-back status change, the outbox's lease, a scheduled trigger whose reschedule or run fails, and a scheduled
 * trigger whose search fails, which fails the trigger rather than mail an empty list.
 *
 * <p>The status-change cases publish a committed message and let the real listener ({@code MailingListMessageListener}
 * on the embedded broker, prompted by the status topic) drain the outbox and execute the committed triggers, or drain
 * it themselves, as the start and the per-minute sweep do (the sweep is switched off in the suite, see
 * {@code ITTest}); each trigger's message filter names the test's own message, so a publication elsewhere in the run
 * makes no mail. The scheduled cases run the real per-minute scheduler. The mailing lists, contacts, messages and
 * outbox rows are removed afterwards.
 */
class MailingListDeliveryFaultITTest extends ITTest {

    private static final String IT_TEMPLATE = "templates/mailinglist/it-test.ftl";

    @Autowired
    MessageService messageService;

    @Autowired
    MessageQueryService messageQueryService;

    @Autowired
    MailingListSchedulerService schedulerService;

    @Autowired
    MailingListExecutionService executionService;

    @Autowired
    MessageLuceneIndex messageLuceneIndex;

    @Autowired
    ScriptResourceService resourceService;

    @Autowired
    MailingListMessageListener listener;

    @Autowired
    MailingListStatusChangeOutbox outbox;

    @PersistenceContext
    EntityManager em;

    /** The UIDs whose outbox rows are removed afterwards: the test's messages', and the made-up ones */
    private final List<String> outboxUids = new ArrayList<>();
    private final List<Integer> mailingListIds = new ArrayList<>();
    private final List<Integer> contactIds = new ArrayList<>();
    private final List<String> messageUids = new ArrayList<>();
    private final List<String> subjects = new ArrayList<>();
    private final List<String> listSuffixes = new ArrayList<>();

    @BeforeEach
    void addTheTestTemplate() {
        ScriptResourceFixtures.ensure(resourceService, IT_TEMPLATE);
    }

    @AfterEach
    void cleanup() {
        // First, so no drain runs the triggers of a message removed below
        List<String> rowUids = new ArrayList<>(messageUids);
        rowUids.addAll(outboxUids);
        for (String uid : rowUids) {
            bestEffort(() -> em.createNativeQuery(
                            "DELETE FROM public.\"MailingListStatusChange\" WHERE \"messageUid\" = :uid")
                    .setParameter("uid", uid).executeUpdate());
        }
        outboxUids.clear();
        for (Integer id : mailingListIds) {
            bestEffort(() -> {
                MailingList ml = em.find(MailingList.class, id);
                if (ml != null) {
                    em.remove(ml);
                }
            });
        }
        for (Integer id : contactIds) {
            bestEffort(() -> {
                Contact c = em.find(Contact.class, id);
                if (c != null) {
                    em.remove(c);
                }
            });
        }
        for (String subject : subjects) {
            bestEffort(() -> em.createQuery("select m from ScheduledMail m where m.subject = :s", ScheduledMail.class)
                    .setParameter("s", subject).getResultList().forEach(em::remove));
        }
        for (String n : listSuffixes) {
            bestEffort(() -> em.createQuery("select m from ScheduledMail m where m.subject like :p", ScheduledMail.class)
                    .setParameter("p", errorRecordPrefix(n) + "%").getResultList().forEach(em::remove));
            bestEffort(() -> em.createQuery("select m from ScheduledMail m where m.subject like :p", ScheduledMail.class)
                    .setParameter("p", waitRecordPrefix(n) + "%").getResultList().forEach(em::remove));
        }
        for (String uid : messageUids) {
            bestEffort(() -> {
                Message msg = messageQueryService.findByUid(uid);
                if (msg != null) {
                    em.createNamedQuery("MessageHistory.findByMessageId", MessageHistory.class)
                            .setParameter("messageId", msg.getId()).getResultList().forEach(em::remove);
                    em.remove(msg);
                }
            });
        }
        mailingListIds.clear();
        contactIds.clear();
        subjects.clear();
        listSuffixes.clear();
        messageUids.clear();
    }

    /**
     * Waits until the next run of {@code MailingListSchedulerService.checkExecuteScheduledTriggers}
     * ({@code @Scheduled(cron = "37 *&#47;1 *&#47;1 * * ?")}, second 37 of every minute in any zone) is at least 20 seconds
     * away.
     */
    private static void awayFromTheRealSchedulersRun() throws InterruptedException {
        awayFromSecond(37);
    }

    /**
     * Waits until a per-minute job that runs at the given second of the minute is at least 20 seconds away and its
     * last run was due at least five seconds ago (the application's jobs share a scheduler of four threads, so a job
     * may start a little after its second when every thread is busy). A narrowing of the window, not a lock: a job
     * held up for longer than that by the ones before it could still run inside the test.
     */
    private static void awayFromSecond(int cronSecond) throws InterruptedException {
        while (true) {
            int sinceRun = Math.floorMod(java.time.LocalTime.now().getSecond() - cronSecond, 60);
            if (sinceRun >= 5 && sinceRun <= 40) {
                return;
            }
            Thread.sleep(250);
        }
    }

    private static void bestEffort(Tx.ThrowingRunnable work) {
        try {
            Tx.requiringNew().run(work);
        } catch (Exception ignored) {
            // best effort
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Fixtures
    // ------------------------------------------------------------------------------------------- //

    private static Date pastPublishDate() {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2001, Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    /** A committed DRAFT NW message. */
    private String committedDraft(String title) {
        String uid = Tx.requiringNew().call(() -> {
            Message msg = new Message();
            msg.setMessageSeries(new MessageSeries("dma-nw"));
            msg.setType(Type.COASTAL_WARNING);
            msg.setAutoTitle(false);
            msg.setPublishDateFrom(pastPublishDate());
            MessagePart part = new MessagePart(MessagePartType.DETAILS);
            MessagePartDesc pd = part.createDesc("en");
            pd.setSubject("subject");
            pd.setDetails("details");
            msg.addPart(part);
            msg.createDesc("en").setTitle(title);
            return messageService.createMessage(msg).getUid();
        });
        messageUids.add(uid);
        return uid;
    }

    private void publish(String uid) {
        Tx.requiringNew().run(() -> {
            messageService.updateStatus(uid, Status.VERIFIED);
            messageService.updateStatus(uid, Status.PUBLISHED);
        });
    }

    /** One active mailing list with one contact, committed. Returns the list's id. */
    private Integer committedListWithOneContact(String n) {
        Integer[] ids = new Integer[2];
        Tx.requiringNew().run(() -> {
            Contact contact = new Contact();
            contact.setName("IT fault subscriber " + n);
            contact.setEmail("it-fault-" + n + "@example.com");
            em.persist(contact);
            MailingList ml = new MailingList();
            ml.setMailingListId("IT-MLFAULT-" + n);
            ml.setActive(true);
            ml.createDesc("en").setName("IT fault list " + n);
            ml.getContacts().add(contact);
            em.persist(ml);
            em.flush();
            ids[0] = ml.getId();
            ids[1] = contact.getId();
        });
        mailingListIds.add(0, ids[0]);
        contactIds.add(ids[1]);
        listSuffixes.add(n);
        return ids[0];
    }

    /** A committed status-change trigger on PUBLISHED, filtered to one message, with the given template. */
    private void addStatusChangeTrigger(Integer mailingListId, String subject, String templatePath, String messageUid) {
        addStatusChangeTrigger(mailingListId, subject, templatePath, messageUid, Status.PUBLISHED);
    }

    /** A committed status-change trigger on the given status, filtered to one message, with the given template. */
    private void addStatusChangeTrigger(Integer mailingListId, String subject, String templatePath, String messageUid,
                                        Status onStatus) {
        addFilteredStatusChangeTrigger(mailingListId, subject, templatePath, "msg.getUid() == '" + messageUid + "'",
                onStatus);
    }

    /** A committed status-change trigger on the given status, with the given filter and template. */
    private void addFilteredStatusChangeTrigger(Integer mailingListId, String subject, String templatePath,
                                                String filter, Status onStatus) {
        subjects.add(subject);
        Tx.requiringNew().run(() -> {
            MailingList ml = em.find(MailingList.class, mailingListId);
            MailingListTrigger trigger = new MailingListTrigger();
            trigger.setType(TriggerType.STATUS_CHANGE);
            trigger.setStatusChanges(Set.of(onStatus));
            trigger.setMessageFilter(filter);
            trigger.getScriptResourcePaths().add(templatePath);
            trigger.createDesc("en").setSubject(subject);
            ml.addTrigger(trigger);
            em.persist(trigger);
        });
    }

    private long mailsWithSubject(String subject) {
        return Tx.requiringNew().call(() -> em.createQuery(
                        "select count(m) from ScheduledMail m where m.subject = :s", Long.class)
                .setParameter("s", subject).getSingleResult());
    }

    private ScheduledMail.Status mailStatus(String subject) {
        return Tx.requiringNew().call(() -> em.createQuery(
                        "select m.status from ScheduledMail m where m.subject = :s", ScheduledMail.Status.class)
                .setParameter("s", subject).getSingleResult());
    }

    /** The statuses of the outbox rows of the given message, in the order they were written */
    private List<String> outboxRows(String uid) {
        return Tx.requiringNew().call(() -> em.createNativeQuery(
                        "SELECT status FROM public.\"MailingListStatusChange\" WHERE \"messageUid\" = :uid ORDER BY id",
                        String.class)
                .setParameter("uid", uid).getResultList());
    }

    /** The subject prefix of the failure records of the test list {@code IT-MLFAULT-<n>} */
    private static String errorRecordPrefix(String n) {
        return "Not sent: mailing list IT-MLFAULT-" + n + ",";
    }

    /** The subject prefix of the records of a scheduled trigger of the test list that waits for the index */
    private static String waitRecordPrefix(String n) {
        return "Delayed: mailing list IT-MLFAULT-" + n + ",";
    }

    /** The failure records of the test list: subject, status, last error and the first recipient, each */
    private List<String[]> errorRecords(String n) {
        return recordsWithPrefix(errorRecordPrefix(n));
    }

    /** The records of the test list's waits for the index, as {@link #errorRecords(String)} */
    private List<String[]> waitRecords(String n) {
        return recordsWithPrefix(waitRecordPrefix(n));
    }

    private List<String[]> recordsWithPrefix(String prefix) {
        return Tx.requiringNew().call(() -> em.createQuery(
                        "select m from ScheduledMail m where m.subject like :p", ScheduledMail.class)
                .setParameter("p", prefix + "%")
                .getResultList().stream()
                .map(m -> new String[] {
                        m.getSubject(),
                        m.getStatus().name(),
                        String.valueOf(m.getLastError()),
                        m.getRecipients().isEmpty() ? "" : m.getRecipients().get(0).getAddress() })
                .toList());
    }

    // ------------------------------------------------------------------------------------------- //
    // A status-change trigger whose template fails
    // ------------------------------------------------------------------------------------------- //

    /**
     * Two status-change triggers on one list fire for the same publication: one whose template does not
     * exist, one with a working template. The working one's mail is queued (the listener did process the event). The
     * failing one used to be logged at ERROR and leave nothing, so the Mail Queue page could not show that a
     * subscriber's mail was due. Now it leaves one {@code ScheduledMail} in status ERROR, never sent, that names the
     * list and the message in its subject and the template error as its last error, with the list's recipient.
     */
    @Test
    void aStatusChangeTriggerWhoseTemplateFailsLeavesAnErrorRecordInTheMailQueue() {
        String n = Long.toString(System.nanoTime(), 36);
        String uid = committedDraft("IT status template " + n);
        Integer list = committedListWithOneContact(n);
        String failingSubject = "IT-STATUS-TEMPLATE-FAIL-" + n;
        String workingSubject = "IT-STATUS-TEMPLATE-OK-" + n;
        addStatusChangeTrigger(list, failingSubject, "templates/mailinglist/it-no-such-template-" + n + ".ftl", uid);
        addStatusChangeTrigger(list, workingSubject, IT_TEMPLATE, uid);

        publish(uid);

        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .until(() -> mailsWithSubject(workingSubject) == 1 && !errorRecords(n).isEmpty());
        String shortId = Tx.requiringNew().call(() -> messageQueryService.findByUid(uid).getShortId());
        assertEquals(Status.PUBLISHED, Tx.requiringNew().call(() -> messageQueryService.findByUid(uid).getStatus()));
        assertEquals(0, mailsWithSubject(failingSubject), "the failing trigger's own mail was never made");
        assertEquals(ScheduledMail.Status.PENDING, mailStatus(workingSubject), "the working trigger's mail is queued");

        List<String[]> records = errorRecords(n);
        assertEquals(1, records.size(), "one record of the failed trigger");
        String[] record = records.get(0);
        assertEquals("Not sent: mailing list IT-MLFAULT-" + n + ", message " + shortId, record[0],
                "the subject names the list and the message");
        assertEquals("ERROR", record[1], "in status ERROR, so it is never sent");
        assertTrue(record[2].contains("it-no-such-template-" + n), "the last error names the template error: " + record[2]);
        assertEquals("it-fault-" + n + "@example.com", record[3].replaceAll(".*<(.*)>.*", "$1"),
                "and it holds the list's recipient: " + record[3]);
    }

    /**
     * A filter that throws, end to end. A status-change trigger whose message filter throws for the published warning (a
     * Java exception from a method the filter calls) used to read as "no match" with nothing logged. Now the trigger
     * fails with the filter named, and its failure is recorded in the Mail Queue in status ERROR.
     */
    @Test
    void aStatusChangeTriggerWhoseFilterThrowsLeavesAnErrorRecord() {
        String n = Long.toString(System.nanoTime(), 36);
        String uid = committedDraft("IT status filter " + n);
        Integer list = committedListWithOneContact(n);
        String subject = "IT-STATUS-FILTER-" + n;
        String filter = "msg.getUid() == '" + uid + "' && msg.getAreas().get(0).getId() > 0";
        addFilteredStatusChangeTrigger(list, subject, IT_TEMPLATE, filter, Status.PUBLISHED);

        publish(uid);

        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .until(() -> !errorRecords(n).isEmpty());
        assertEquals(0, mailsWithSubject(subject), "the trigger made no mail");
        List<String[]> records = errorRecords(n);
        assertEquals(1, records.size(), "one record of the failed trigger");
        assertEquals("ERROR", records.get(0)[1]);
        assertTrue(records.get(0)[2].startsWith("The message filter '" + filter.substring(0, 20)),
                "the last error names the filter: " + records.get(0)[2]);
    }

    // ------------------------------------------------------------------------------------------- //
    // The broker down at publish
    // ------------------------------------------------------------------------------------------- //

    /** The listener's drain lock: held by a test, no drain of this context takes a row (the test's own drains do) */
    private ReentrantLock drainLock() {
        Object target = AopTestUtils.getUltimateTargetObject(listener);
        return (ReentrantLock) ReflectionTestUtils.getField(target, "drainLock");
    }

    /**
     * The broker down at publish: the status topic cannot be reached when the publication commits (here the connection
     * factory throws {@link JMSRuntimeException}, as it does when the embedded broker is stopping or out of memory), so
     * no listener hears of the status change, as of one committed just before a restart. The error hunt of 2026-11-12
     * pinned what followed: the warning PUBLISHED, its subscribers' mail never made, and nothing to retry it.
     *
     * <p>Now each status change leaves a row in the mailing lists' outbox, committed with it: here two, VERIFIED and
     * PUBLISHED. The next drain (at start, by the per-minute sweep or on the next status change's prompt; here the
     * test's own) makes the mail and deletes both rows, the VERIFIED one with no trigger to run, and a second drain
     * makes no second mail. FAILS on the code before the outbox: no row is written, and no drain makes the mail.
     */
    @Test
    void aStatusChangeThatCannotReachTheBrokerIsStillMailed() {
        String n = Long.toString(System.nanoTime(), 36);
        String uid = committedDraft("IT broker down " + n);
        Integer list = committedListWithOneContact(n);
        String subject = "IT-BROKER-DOWN-" + n;
        addStatusChangeTrigger(list, subject, IT_TEMPLATE, uid);

        ConnectionFactory broken = mock(ConnectionFactory.class);
        when(broken.createContext(anyInt())).thenThrow(new JMSRuntimeException("IT: the broker cannot be reached"));
        Object target = AopTestUtils.getUltimateTargetObject(messageService);
        Object realFactory = ReflectionTestUtils.getField(target, "connectionFactory");
        ReentrantLock drains = drainLock();
        drains.lock();
        try {
            ReflectionTestUtils.setField(target, "connectionFactory", broken);
            try {
                publish(uid);
            } finally {
                ReflectionTestUtils.setField(target, "connectionFactory", realFactory);
            }

            assertEquals(Status.PUBLISHED, Tx.requiringNew().call(() -> messageQueryService.findByUid(uid).getStatus()),
                    "the publication itself succeeded");
            assertEquals(List.of("VERIFIED", "PUBLISHED"), outboxRows(uid),
                    "both status changes are kept in the outbox, though no listener heard of them");
            assertEquals(0, mailsWithSubject(subject), "and no mail is made yet");

            assertTrue(listener.drainOutbox() >= 2, "the drain takes both rows");
            assertEquals(1, mailsWithSubject(subject), "the next drain makes the subscribers' mail");
            assertEquals(ScheduledMail.Status.PENDING, mailStatus(subject), "queued to be sent");
            assertEquals(List.of(), outboxRows(uid), "and deletes both rows");

            listener.drainOutbox();
            assertEquals(1, mailsWithSubject(subject), "a second drain makes no second mail");
        } finally {
            drains.unlock();
        }
    }

    /**
     * The outbox row is written in the status change's own transaction: a publication whose transaction rolls back
     * after the status change (here the test throws) leaves no row, and no drain mails a warning that was never
     * published. Would fail if the row were written in a transaction of its own.
     */
    @Test
    void aRolledBackStatusChangeLeavesNoOutboxRowAndNoMail() {
        String n = Long.toString(System.nanoTime(), 36);
        String uid = committedDraft("IT rolled back " + n);
        Integer list = committedListWithOneContact(n);
        String subject = "IT-ROLLED-BACK-" + n;
        addStatusChangeTrigger(list, subject, IT_TEMPLATE, uid);

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> Tx.requiringNew().run(() -> {
            messageService.updateStatus(uid, Status.VERIFIED);
            messageService.updateStatus(uid, Status.PUBLISHED);
            throw new IllegalStateException("IT: the publication's transaction fails after the status change");
        }));
        assertTrue(failure.getMessage().startsWith("IT:"), failure.getMessage());

        assertEquals(Status.DRAFT, Tx.requiringNew().call(() -> messageQueryService.findByUid(uid).getStatus()),
                "the publication rolled back");
        assertEquals(List.of(), outboxRows(uid), "and so did its outbox rows");
        listener.drainOutbox();
        assertEquals(0, mailsWithSubject(subject), "no drain mails a status change that was rolled back");
    }

    /**
     * A row taken for its triggers is out of every other drain's reach until its lease ends (two backends on one
     * database take different rows), and due again after it, when its run failed or its backend stopped. The row is
     * made with the outbox alone, for a made-up message, so no prompt drains it; the context's own drains are held off
     * by its drain lock, and every row due before it is taken first, so the first take is this row's.
     */
    @Test
    void aClaimedStatusChangeIsNotClaimedAgainBeforeItsLeaseEnds() {
        String uid = UUID.randomUUID().toString();
        outboxUids.add(uid);
        Object target = AopTestUtils.getUltimateTargetObject(outbox);
        Duration lease = (Duration) ReflectionTestUtils.getField(target, "claimLease");
        ReentrantLock drains = drainLock();
        drains.lock();
        try {
            listener.drainOutbox();
            Tx.requiringNew().run(() -> outbox.enqueue(uid, Status.DRAFT));
            long id = Tx.requiringNew().call(() -> ((Number) em.createNativeQuery(
                            "SELECT id FROM public.\"MailingListStatusChange\" WHERE \"messageUid\" = :uid")
                    .setParameter("uid", uid).getSingleResult()).longValue());
            ReflectionTestUtils.setField(target, "claimLease", Duration.ofSeconds(2));

            Optional<MailingListStatusChangeOutbox.Claim> first = claimOwn(id);
            assertEquals(Optional.of(new MailingListStatusChangeOutbox.Claim(id, uid, Status.DRAFT)), first,
                    "the row is taken");
            assertTrue(claimOwn(id).isEmpty(), "and not taken again while its lease runs");

            Optional<MailingListStatusChangeOutbox.Claim> again = Awaitility.await().atMost(Duration.ofSeconds(15))
                    .pollInterval(Duration.ofMillis(250))
                    .until(() -> claimOwn(id), Optional::isPresent);
            assertEquals(id, again.orElseThrow().id(), "it is due again once the lease ran out");
            assertEquals(2, Tx.requiringNew().call(() -> ((Number) em.createNativeQuery(
                            "SELECT attempts FROM public.\"MailingListStatusChange\" WHERE id = :id")
                    .setParameter("id", id).getSingleResult()).intValue()), "taken twice");

            outbox.complete(id);
            assertEquals(List.of(), outboxRows(uid), "a completed row is deleted");
        } finally {
            ReflectionTestUtils.setField(target, "claimLease", lease);
            drains.unlock();
        }
    }

    /**
     * A row whose run failed and was recorded ({@code failed}: the triggers could not be looked up) does not wait out
     * the ten-minute lease it was taken with, since nothing runs it any more: it keeps its error and is due again
     * after as many minutes as it was taken. Would fail if the record left the row's due time at the lease's end.
     */
    @Test
    void aRowWhoseRunFailedIsDueAgainWithinMinutes() {
        String uid = UUID.randomUUID().toString();
        outboxUids.add(uid);
        ReentrantLock drains = drainLock();
        drains.lock();
        try {
            listener.drainOutbox();
            Tx.requiringNew().run(() -> outbox.enqueue(uid, Status.DRAFT));
            long id = Tx.requiringNew().call(() -> ((Number) em.createNativeQuery(
                            "SELECT id FROM public.\"MailingListStatusChange\" WHERE \"messageUid\" = :uid")
                    .setParameter("uid", uid).getSingleResult()).longValue());
            assertTrue(claimOwn(id).isPresent(), "the row is taken");

            outbox.failed(id, "IT: the triggers could not be looked up");

            Object[] row = Tx.requiringNew().call(() -> (Object[]) em.createNativeQuery(
                            "SELECT \"lastError\", \"dueAt\" > now() + interval '30 seconds', "
                                    + "\"dueAt\" <= now() + interval '1 minute' "
                                    + "FROM public.\"MailingListStatusChange\" WHERE id = :id")
                    .setParameter("id", id).getSingleResult());
            assertEquals("IT: the triggers could not be looked up", row[0], "the row keeps its error");
            assertEquals(Boolean.TRUE, row[1], "it is not due at once");
            assertEquals(Boolean.TRUE, row[2], "but a minute after its first take, not at the lease's end");
        } finally {
            drains.unlock();
        }
    }

    /**
     * A row whose status is no {@link Status} (renamed or removed since it was written, or written by hand) used to
     * throw at the take and roll back its lease: as the oldest due row it was taken first by every later drain, and
     * no status change after it was mailed again. Now the take skips it, leased, with its error, and takes the next
     * row.
     */
    @Test
    void anOutboxRowWithAnUnknownStatusDoesNotHoldBackTheRowsAfterIt() {
        String badUid = UUID.randomUUID().toString();
        String goodUid = UUID.randomUUID().toString();
        outboxUids.add(badUid);
        outboxUids.add(goodUid);
        ReentrantLock drains = drainLock();
        drains.lock();
        try {
            listener.drainOutbox();
            Tx.requiringNew().run(() -> em.createNativeQuery("INSERT INTO public.\"MailingListStatusChange\" "
                            + "(\"messageUid\", status, created, \"dueAt\") VALUES (:uid, 'IT_NO_SUCH_STATUS', now(), now())")
                    .setParameter("uid", badUid).executeUpdate());
            Tx.requiringNew().run(() -> outbox.enqueue(goodUid, Status.PUBLISHED));
            long goodId = Tx.requiringNew().call(() -> ((Number) em.createNativeQuery(
                            "SELECT id FROM public.\"MailingListStatusChange\" WHERE \"messageUid\" = :uid")
                    .setParameter("uid", goodUid).getSingleResult()).longValue());

            assertEquals(Optional.of(new MailingListStatusChangeOutbox.Claim(goodId, goodUid, Status.PUBLISHED)),
                    claimOwn(goodId), "the row after the unknown status is taken");
            Object[] bad = Tx.requiringNew().call(() -> (Object[]) em.createNativeQuery(
                            "SELECT attempts, \"lastError\", \"dueAt\" > now() FROM public.\"MailingListStatusChange\" "
                                    + "WHERE \"messageUid\" = :uid")
                    .setParameter("uid", badUid).getSingleResult());
            assertEquals(1, ((Number) bad[0]).intValue(), "the unknown status's row was taken once");
            assertEquals("Unknown message status 'IT_NO_SUCH_STATUS'", bad[1], "it keeps the reason");
            assertEquals(Boolean.TRUE, bad[2], "and its lease, so the next take does not meet it again");
            outbox.complete(goodId);
        } finally {
            drains.unlock();
        }
    }

    /**
     * Takes due rows until the given one comes back, or none is due. The table is shared by every context of the run:
     * a row another class left leased (a failed trigger lookup) may come due during a test, and as the older row it
     * is taken first. Taken here, it is due again when this take's lease ends, for a later drain to run.
     */
    private Optional<MailingListStatusChangeOutbox.Claim> claimOwn(long id) {
        while (true) {
            Optional<MailingListStatusChangeOutbox.Claim> claim = outbox.claimNextDue();
            if (claim.isEmpty() || claim.get().id() == id) {
                return claim;
            }
        }
    }
    // ------------------------------------------------------------------------------------------- //
    // A status-change trigger's message filter
    // ------------------------------------------------------------------------------------------- //

    /**
     * A status-change trigger whose filter names one warning mails for that warning and for no other: a second
     * warning published after it on the same topic makes no mail. The pins above each publish only the message
     * their trigger names, so a trigger that ignored its filter and mailed every publication passed them (detector
     * check MC, M15). The listener drains the outbox one row at a time, in the order the rows were written (the suite
     * switches the per-minute sweep off, so only this context's drain takes them); the last row is a sentinel
     * warning's CANCELLED, which only the sentinel trigger (on CANCELLED) answers, so once its mail exists every
     * publication before it has been handled.
     */
    @Test
    void aStatusChangeTriggerMailsTheWarningItsFilterNamesAndNoOther() {
        String n = Long.toString(System.nanoTime(), 36);
        String sentinel = committedDraft("IT filter sentinel " + n);
        String named = committedDraft("IT filter named " + n);
        String other = committedDraft("IT filter other " + n);
        Integer list = committedListWithOneContact(n);
        String subject = "IT-FILTER-" + n;
        String sentinelSubject = "IT-FILTER-SENTINEL-" + n;
        addStatusChangeTrigger(list, subject, IT_TEMPLATE, named);
        addStatusChangeTrigger(list, sentinelSubject, IT_TEMPLATE, sentinel, Status.CANCELLED);

        publish(sentinel);
        publish(named);
        publish(other);
        Tx.requiringNew().run(() -> messageService.updateStatus(sentinel, Status.CANCELLED));

        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .until(() -> mailsWithSubject(sentinelSubject) >= 1);
        assertEquals(1, mailsWithSubject(subject),
                "one mail, for the warning the filter names; none for the warnings published before and after it");
    }

    // ------------------------------------------------------------------------------------------- //
    // The free-text index down under a scheduled trigger
    // ------------------------------------------------------------------------------------------- //

    /**
     * A scheduled trigger whose query has a free-text term ({@code query=nw}) mails the matching published warnings.
     * With the free-text index unreadable (here its folder is an empty one, so opening the reader throws
     * {@code IndexNotFoundException}, an {@code IOException}), the search fails and so does the trigger: the
     * scheduler logs it and postpones it, and no mail is made. A mail listing no warning would tell the subscribers
     * that there are none.
     */
    @Test
    @Transactional
    void aScheduledTriggerWithAFreeTextQueryFailsWhenTheIndexIsDown() throws Exception {
        String n = Long.toString(System.nanoTime(), 36);
        MailingList ml = new MailingList();
        ml.setMailingListId("IT-MLINDEX-" + n);
        ml.setActive(true);
        ml.createDesc("en").setName("IT index down " + n);
        Contact contact = new Contact();
        contact.setName("IT index subscriber");
        contact.setEmail("it-index-" + n + "@example.com");
        em.persist(contact);
        ml.getContacts().add(contact);
        em.persist(ml);
        MailingListTrigger trigger = new MailingListTrigger();
        trigger.setType(TriggerType.SCHEDULED);
        trigger.setScheduleType(ScheduleType.DAILY);
        trigger.setScheduledExecutionTime("12:00");
        trigger.setMessageQuery("query=nw&status=PUBLISHED");
        trigger.getScriptResourcePaths().add(IT_TEMPLATE);
        trigger.createDesc("en").setSubject("IT index down " + n);
        ml.addTrigger(trigger);
        em.persist(trigger);
        em.flush();

        // Control: with the index up, the fixture's published NW warnings are listed. The index is rebuilt from the
        // committed rows first: every application context of the run empties the shared index folder when it starts,
        // and one closed before its start-up pass leaves it empty (DeliveryFaultRestITTest met that in a whole run)
        messageLuceneIndex.recreateIndex();
        Awaitility.await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(2))
                .until(() -> !messageLuceneIndex.searchIndex("nw", "en", 10).isEmpty());
        List<ScheduledMail> up = executionService.executeScheduledTrigger(trigger, false);
        assertEquals(1, up.size(), "one mail for the one recipient");
        assertFalse(up.get(0).getHtmlContents().contains("count=0"),
                "with the index up the mail lists warnings: " + up.get(0).getHtmlContents());

        // The shared index's own pass (MessageLuceneIndex.updateLuceneIndex, second 38 of every minute) must not run
        // while its folder is swapped: it would fill the empty folder, and the shared index would miss what it indexed
        Object index = AopTestUtils.getUltimateTargetObject(messageLuceneIndex);
        awayFromSecond(38);
        Object sharedFolder = ReflectionTestUtils.getField(index, "indexFolder");
        Object sharedReader = ReflectionTestUtils.getField(index, "reader");
        Path emptyFolder = Files.createTempDirectory("niord-it-index-down");
        ReflectionTestUtils.setField(index, "indexFolder", emptyFolder);
        ReflectionTestUtils.setField(index, "reader", null);
        try {
            assertThrows(Exception.class, () -> executionService.executeScheduledTrigger(trigger, false),
                    "the trigger fails rather than make a mail that lists no warning");
        } finally {
            ReflectionTestUtils.setField(index, "indexFolder", sharedFolder);
            ReflectionTestUtils.setField(index, "reader", sharedReader);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // A scheduled trigger whose reschedule or run fails
    // ------------------------------------------------------------------------------------------- //

    /**
     * A committed, due scheduled trigger on the given list (daily at the given time, in UTC), with the given template,
     * whose stored time is then replaced by {@code storedTime} when that is not null (a value the save validation
     * refuses today but a legacy or imported row may hold). Returns the trigger's id.
     */
    private Integer committedDueScheduledTrigger(Integer list, String subject, String time, String templatePath,
                                                 String storedTime) {
        return committedDueScheduledTrigger(list, subject, time, templatePath, storedTime,
                "status=PUBLISHED&mainType=NW");
    }

    /** As above, with the given message query */
    private Integer committedDueScheduledTrigger(Integer list, String subject, String time, String templatePath,
                                                 String storedTime, String messageQuery) {
        subjects.add(subject);
        Integer triggerId = Tx.requiringNew().call(() -> {
            MailingList ml = em.find(MailingList.class, list);
            MailingListTrigger trigger = new MailingListTrigger();
            trigger.setType(TriggerType.SCHEDULED);
            trigger.setScheduleType(ScheduleType.DAILY);
            trigger.setScheduledExecutionTime(time);
            trigger.setScheduledExecutionTimeZone("UTC");
            trigger.setMessageQuery(messageQuery);
            trigger.getScriptResourcePaths().add(templatePath);
            trigger.createDesc("en").setSubject(subject);
            ml.addTrigger(trigger);
            em.persist(trigger);
            em.flush();
            return trigger.getId();
        });
        Tx.requiringNew().run(() -> em.createQuery("update MailingListTrigger t set t.nextScheduledExecution = :past, "
                        + "t.scheduledExecutionTime = :stored where t.id = :id")
                .setParameter("past", new Date(System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000L))
                .setParameter("stored", storedTime != null ? storedTime : time)
                .setParameter("id", triggerId)
                .executeUpdate());
        return triggerId;
    }

    private Date nextExecutionOf(Integer triggerId) {
        return Tx.requiringNew().call(() -> em.find(MailingListTrigger.class, triggerId).getNextScheduledExecution());
    }

    /** Runs the real scheduler's pass once with its log captured, and returns its ERROR events about the trigger */
    private List<ILoggingEvent> schedulerRunErrorsAbout(Integer triggerId) {
        Logger logger = (Logger) LoggerFactory.getLogger(MailingListSchedulerService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            schedulerService.checkExecuteScheduledTriggers();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .filter(e -> e.getFormattedMessage().contains("trigger " + triggerId + " "))
                .toList();
    }

    /** The message of the throwable logged with an event, and of its causes */
    private static String causesOf(ILoggingEvent event) {
        StringBuilder text = new StringBuilder();
        for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
            text.append(t.getClassName()).append(": ").append(t.getMessage()).append('\n');
        }
        return text.toString();
    }

    /** Asserts that the date lies about an hour after the given instant (the postponement of a failed trigger) */
    private static void assertAboutAnHourAfter(long beforeRun, long afterRun, Date next) {
        long hour = 60L * 60L * 1000L;
        assertTrue(next.getTime() >= beforeRun + hour && next.getTime() <= afterRun + hour,
                "the next execution is an hour after the failed run: " + next.toInstant()
                        + ", the run was between " + new Date(beforeRun).toInstant() + " and "
                        + new Date(afterRun).toInstant());
    }

    /**
     * A due scheduled trigger whose stored execution time cannot be read ({@code 08:30:00}) used to be
     * executed and its mails committed in their own transaction; then {@code computeNextScheduledExecution} failed,
     * its catch logged one line without the cause, the next execution stayed in the past, and the next minute's run
     * mailed the recipients again. Now the next execution is computed first, in the same transaction as the mails:
     * it fails before any mail is made, the scheduler logs one ERROR with the cause, and the trigger is postponed an
     * hour (its regular time cannot be computed), so a second run in the same minute does nothing.
     */
    @Test
    void aScheduledTriggerWhoseRescheduleFailsMailsNobodyAndIsTriedAgainInAnHour() throws InterruptedException {
        // The real scheduler runs checkExecuteScheduledTriggers at second 37 of every minute in this context too: had it
        // run between the trigger falling due and the first run here, this test's log capture would miss its ERROR.
        // Start only when its next run is at least 20 seconds away.
        awayFromTheRealSchedulersRun();
        String n = Long.toString(System.nanoTime(), 36);
        Integer list = committedListWithOneContact(n);
        String subject = "IT-MAILLIST3-" + n;
        Integer triggerId = committedDueScheduledTrigger(list, subject, "12:00", IT_TEMPLATE, "08:30:00");

        long beforeRun = System.currentTimeMillis();
        List<ILoggingEvent> firstRun = schedulerRunErrorsAbout(triggerId);
        long afterRun = System.currentTimeMillis();
        assertEquals(0, mailsWithSubject(subject), "the run mails nobody");
        assertEquals(1, firstRun.size(), "one ERROR line about the trigger: " + firstRun);
        assertTrue(firstRun.get(0).getFormattedMessage().contains("tried again at "),
                "the line says when the trigger is tried again: " + firstRun.get(0).getFormattedMessage());
        assertTrue(causesOf(firstRun.get(0)).contains("Invalid scheduled execution time format 08:30:00"),
                "the line carries the cause: " + causesOf(firstRun.get(0)));
        assertAboutAnHourAfter(beforeRun, afterRun, nextExecutionOf(triggerId));
        List<String[]> records = errorRecords(n);
        assertEquals(1, records.size(), "the failure is recorded in the Mail Queue");
        assertTrue(records.get(0)[2].contains("Invalid scheduled execution time format 08:30:00"),
                "the record's last error is the cause: " + records.get(0)[2]);

        List<ILoggingEvent> secondRun = schedulerRunErrorsAbout(triggerId);
        assertEquals(0, mailsWithSubject(subject), "the next run mails nobody either");
        assertTrue(secondRun.isEmpty(), "the postponed trigger is not run again: " + secondRun);
    }

    /**
     * A due scheduled trigger whose template fails (here it does not exist) used to be left due when its
     * run threw, so the scheduler ran it again every minute, for ever, with an ERROR and its trace each time. Now one
     * run logs one ERROR with the cause, makes no mail, and moves the next execution to one hour from now (its next
     * regular time, three hours away, is later); a second run in the same minute does not run it.
     */
    @Test
    void aScheduledTriggerWhoseRunFailsIsTriedAgainInAnHourNotEveryMinute() throws InterruptedException {
        awayFromTheRealSchedulersRun();
        String n = Long.toString(System.nanoTime(), 36);
        Integer list = committedListWithOneContact(n);
        String subject = "IT-MAILLIST6-" + n;
        String inThreeHours = ZonedDateTime.now(ZoneOffset.UTC).plusHours(3)
                .format(DateTimeFormatter.ofPattern("HH:mm"));
        String missingTemplate = "templates/mailinglist/it-no-such-template-" + n + ".ftl";
        Integer triggerId = committedDueScheduledTrigger(list, subject, inThreeHours, missingTemplate, null);

        long beforeRun = System.currentTimeMillis();
        List<ILoggingEvent> firstRun = schedulerRunErrorsAbout(triggerId);
        long afterRun = System.currentTimeMillis();
        assertEquals(0, mailsWithSubject(subject), "the failed run mails nobody");
        assertEquals(1, firstRun.size(), "one ERROR line about the trigger: " + firstRun);
        assertTrue(causesOf(firstRun.get(0)).contains("it-no-such-template-" + n),
                "the line carries the cause: " + causesOf(firstRun.get(0)));
        Date next = nextExecutionOf(triggerId);
        assertAboutAnHourAfter(beforeRun, afterRun, next);
        assertTheFailureIsRecordedOnce(n, triggerId);

        List<ILoggingEvent> secondRun = schedulerRunErrorsAbout(triggerId);
        assertTrue(secondRun.isEmpty(), "the postponed trigger is not run again in the same minute: " + secondRun);
        assertEquals(0, mailsWithSubject(subject));
        assertEquals(next, nextExecutionOf(triggerId), "and its next execution is unchanged");
        assertEquals(1, errorRecords(n).size(), "and it leaves no second record");
    }

    /**
     * The scheduled trigger whose template is missing has left one record in the Mail Queue: in status ERROR, so it
     * is never sent, its subject naming the list and the trigger, the template error as its last error and the
     * list's recipient. FAILS on the pre-fix code, which recorded the failure in the log only.
     */
    private void assertTheFailureIsRecordedOnce(String n, Integer triggerId) {
        List<String[]> records = errorRecords(n);
        assertEquals(1, records.size(), "one record of the failed run: " + records.size());
        String[] record = records.get(0);
        assertEquals("Not sent: mailing list IT-MLFAULT-" + n + ", scheduled trigger " + triggerId, record[0],
                "the subject names the list and the trigger");
        assertEquals("ERROR", record[1], "in status ERROR, so it is never sent");
        assertTrue(record[2].contains("it-no-such-template-" + n), "the last error names the template error: " + record[2]);
        assertEquals("it-fault-" + n + "@example.com", record[3].replaceAll(".*<(.*)>.*", "$1"),
                "and it holds the list's recipient: " + record[3]);
    }

    /**
     * A scheduled trigger that keeps failing is tried again every hour, but is recorded in the Mail Queue at most once
     * a day: a second failed run, the trigger forced due again, leaves no second record.
     */
    @Test
    void aScheduledTriggerThatKeepsFailingIsRecordedOnceADay() throws InterruptedException {
        awayFromTheRealSchedulersRun();
        String n = Long.toString(System.nanoTime(), 36);
        Integer list = committedListWithOneContact(n);
        String subject = "IT-MAILLIST6-AGAIN-" + n;
        String inThreeHours = ZonedDateTime.now(ZoneOffset.UTC).plusHours(3)
                .format(DateTimeFormatter.ofPattern("HH:mm"));
        String missingTemplate = "templates/mailinglist/it-no-such-template-" + n + ".ftl";
        Integer triggerId = committedDueScheduledTrigger(list, subject, inThreeHours, missingTemplate, null);

        assertEquals(1, schedulerRunErrorsAbout(triggerId).size(), "the first run fails");
        assertTheFailureIsRecordedOnce(n, triggerId);

        // Due again, as an hour later
        Tx.requiringNew().run(() -> em.createQuery(
                        "update MailingListTrigger t set t.nextScheduledExecution = :past where t.id = :id")
                .setParameter("past", new Date(System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000L))
                .setParameter("id", triggerId)
                .executeUpdate());
        List<ILoggingEvent> secondRun = schedulerRunErrorsAbout(triggerId);
        assertEquals(1, secondRun.size(), "the trigger due again fails again: " + secondRun);
        assertEquals(1, errorRecords(n).size(), "but leaves no second record within the day");
    }

    /**
     * The right path beside the two failures above: a due scheduled trigger whose run succeeds is mailed once and
     * moved to its next regular time (three hours away, not the hour of a failure), so a second run in the same
     * minute makes no second mail.
     */
    @Test
    void aScheduledTriggerWhoseRunSucceedsIsMailedOnceAndMovedToItsNextRegularTime() throws InterruptedException {
        awayFromTheRealSchedulersRun();
        String n = Long.toString(System.nanoTime(), 36);
        Integer list = committedListWithOneContact(n);
        String subject = "IT-MAILLIST6-OK-" + n;
        ZonedDateTime inThreeHours = ZonedDateTime.now(ZoneOffset.UTC).plusHours(3);
        Integer triggerId = committedDueScheduledTrigger(list, subject,
                inThreeHours.format(DateTimeFormatter.ofPattern("HH:mm")), IT_TEMPLATE, null);

        List<ILoggingEvent> firstRun = schedulerRunErrorsAbout(triggerId);
        assertTrue(firstRun.isEmpty(), "the run succeeds: " + firstRun);
        assertEquals(1, mailsWithSubject(subject), "the run mails the recipient");
        Date next = nextExecutionOf(triggerId);
        assertEquals(inThreeHours.truncatedTo(ChronoUnit.MINUTES).toInstant(), next.toInstant(),
                "the next execution is the next regular time");

        schedulerService.checkExecuteScheduledTriggers();
        assertEquals(1, mailsWithSubject(subject), "a second run in the same minute makes no second mail");
        assertTrue(errorRecords(n).isEmpty(), "a run that succeeds leaves no failure record");
    }

    // ------------------------------------------------------------------------------------------- //
    // A scheduled trigger whose free-text search meets the index being filled after the start
    // ------------------------------------------------------------------------------------------- //

    /**
     * Runs the work with this context's free-text index marked as not complete, as after a start until the passes
     * have indexed every message again; its writer lock is held, so no pass marks it complete meanwhile. The mark is
     * put back afterwards.
     */
    private <T> T withTheIndexIncomplete(Callable<T> work) throws Exception {
        Object index = AopTestUtils.getUltimateTargetObject(messageLuceneIndex);
        Path folder = (Path) ReflectionTestUtils.getField(index, "indexFolder");
        ReentrantLock lock = ReflectionTestUtils.invokeMethod(MessageLuceneIndex.class, "writerLock", folder);
        lock.lock();
        Object was = ReflectionTestUtils.getField(index, "allMessagesIndexed");
        try {
            ReflectionTestUtils.setField(index, "allMessagesIndexed", false);
            return work.call();
        } finally {
            ReflectionTestUtils.setField(index, "allMessagesIndexed", was);
            lock.unlock();
        }
    }

    /** Runs the work with this context's execution service started at the given time, put back afterwards */
    private <T> T startedAt(Instant started, Callable<T> work) throws Exception {
        MailingListExecutionService service =
                (MailingListExecutionService) AopTestUtils.getUltimateTargetObject(executionService);
        Instant was = service.startedAt;
        service.startedAt = started;
        try {
            return work.call();
        } finally {
            service.startedAt = was;
        }
    }

    /**
     * Runs the real scheduler's pass once with the scheduler's and the execution's log captured, and returns their
     * WARN and ERROR events about the trigger
     */
    private List<ILoggingEvent> schedulerRunWarningsAbout(Integer triggerId) {
        List<Logger> loggers = List.of(
                (Logger) LoggerFactory.getLogger(MailingListSchedulerService.class),
                (Logger) LoggerFactory.getLogger(MailingListExecutionService.class));
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        loggers.forEach(l -> l.addAppender(appender));
        try {
            schedulerService.checkExecuteScheduledTriggers();
        } finally {
            loggers.forEach(l -> l.detachAppender(appender));
        }
        return appender.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .filter(e -> e.getFormattedMessage().contains("trigger " + triggerId + " "))
                .toList();
    }

    /**
     * A due scheduled trigger whose query has a free-text term, run in the half hour after the start while the index
     * is still being filled: its search answers from the messages indexed so far, and a mail cannot say that it may
     * list too few. So no mail is made; the trigger is postponed by a few minutes (not the hour of a failure, nor
     * the whole half hour), so its mail follows soon after the index is complete, and the scheduler logs one WARN, no
     * ERROR, saying it waits for the index, when it is tried again and by when its mail is made. The wait is recorded
     * in the Mail Queue apart from the failures: a "Delayed:" record that says the mail follows, and that does not
     * hold back the record of a run that fails for a real cause later the same day. Tried again while the index is
     * still being filled, it waits again with no second WARN and no second record. FAILS without the wait: the mail
     * is made from the incomplete index and the trigger moves to its next regular time.
     */
    @Test
    void aScheduledTriggerWhoseFreeTextSearchMeetsTheIndexBeingFilledWaitsInTheHalfHourAfterTheStart()
            throws Exception {
        messageLuceneIndex.recreateIndex();
        awayFromTheRealSchedulersRun();
        String n = Long.toString(System.nanoTime(), 36);
        Integer list = committedListWithOneContact(n);
        String subject = "IT-INDEX-WAIT-" + n;
        String inThreeHours = ZonedDateTime.now(ZoneOffset.UTC).plusHours(3)
                .format(DateTimeFormatter.ofPattern("HH:mm"));
        Integer triggerId = committedDueScheduledTrigger(list, subject, inThreeHours, IT_TEMPLATE, null,
                "query=nw&status=PUBLISHED");
        Instant started = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant endOfTheWait = started.plus(MailingListExecutionService.INCOMPLETE_INDEX_WAIT);

        long beforeRun = System.currentTimeMillis();
        List<ILoggingEvent> run = startedAt(started, () -> withTheIndexIncomplete(
                () -> schedulerRunWarningsAbout(triggerId)));
        long afterRun = System.currentTimeMillis();

        assertEquals(0, mailsWithSubject(subject), "no mail is made from the incomplete index");
        assertEquals(1, run.size(), "one line about the trigger: " + run);
        assertEquals(Level.WARN, run.get(0).getLevel(), "a warning, not an error: " + run.get(0));
        String line = run.get(0).getFormattedMessage();
        assertTrue(line.contains("waits for the free-text index"), "the line says why: " + line);
        Date next = nextExecutionOf(triggerId);
        assertTrue(line.contains("tried again at " + next.toInstant()), "and when it is tried again: " + line);
        assertTrue(line.contains("by " + endOfTheWait + " at the latest"), "and by when its mail is made: " + line);
        long retry = MailingListExecutionService.INCOMPLETE_INDEX_RETRY.toMillis();
        assertTrue(next.getTime() >= beforeRun + retry && next.getTime() <= afterRun + retry,
                "postponed by a few minutes, not to the end of the half hour nor by the hour of a failure: "
                        + next.toInstant());
        assertTrue(errorRecords(n).isEmpty(), "no record of a failed run: " + errorRecords(n).size());
        List<String[]> waits = waitRecords(n);
        assertEquals(1, waits.size(), "one record of the wait in the Mail Queue");
        assertEquals("Delayed: mailing list IT-MLFAULT-" + n + ", scheduled trigger " + triggerId, waits.get(0)[0]);
        assertEquals("ERROR", waits.get(0)[1], "in status ERROR, so it is never sent");
        assertTrue(waits.get(0)[2].startsWith("The text search index is being built again after a restart"),
                "its last error says why: " + waits.get(0)[2]);
        assertTrue(waits.get(0)[2].contains("by " + endOfTheWait + " at the latest"),
                "and by when the mail is made: " + waits.get(0)[2]);

        // Due again, as at the retry, the index still being filled: it waits again, without a second WARN or record
        Tx.requiringNew().run(() -> em.createQuery(
                        "update MailingListTrigger t set t.nextScheduledExecution = :past where t.id = :id")
                .setParameter("past", new Date(System.currentTimeMillis() - 60_000L))
                .setParameter("id", triggerId)
                .executeUpdate());
        List<ILoggingEvent> retried = startedAt(started, () -> withTheIndexIncomplete(
                () -> schedulerRunWarningsAbout(triggerId)));
        assertTrue(retried.isEmpty(), "no second WARN while it waits: " + retried);
        assertEquals(0, mailsWithSubject(subject), "and still no mail");
        assertTrue(nextExecutionOf(triggerId).after(new Date()), "postponed again");
        assertEquals(1, waitRecords(n).size(), "and no second record of the wait");

        // A run that fails for a real cause the same day is still recorded as a failure
        assertTrue(executionService.recordScheduledTriggerFailure(triggerId,
                        new IllegalStateException("it-real-failure-" + n)),
                "the record of the wait does not hold back the record of a failure");
        List<String[]> failures = errorRecords(n);
        assertEquals(1, failures.size());
        assertEquals("Not sent: mailing list IT-MLFAULT-" + n + ", scheduled trigger " + triggerId, failures.get(0)[0]);
    }

    /**
     * A trigger that waits for the index near the end of the half hour after the start is tried again at its end,
     * not a few minutes on, so the half hour bounds the delay of its mail.
     */
    @Test
    void aScheduledTriggerThatWaitsNearTheEndOfTheHalfHourIsTriedAgainAtItsEnd() throws Exception {
        messageLuceneIndex.recreateIndex();
        awayFromTheRealSchedulersRun();
        String n = Long.toString(System.nanoTime(), 36);
        Integer list = committedListWithOneContact(n);
        String subject = "IT-INDEX-END-" + n;
        String inThreeHours = ZonedDateTime.now(ZoneOffset.UTC).plusHours(3)
                .format(DateTimeFormatter.ofPattern("HH:mm"));
        Integer triggerId = committedDueScheduledTrigger(list, subject, inThreeHours, IT_TEMPLATE, null,
                "query=nw&status=PUBLISHED");
        Instant started = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                .minus(MailingListExecutionService.INCOMPLETE_INDEX_WAIT).plusSeconds(60);
        Instant endOfTheWait = started.plus(MailingListExecutionService.INCOMPLETE_INDEX_WAIT);

        startedAt(started, () -> withTheIndexIncomplete(() -> schedulerRunWarningsAbout(triggerId)));

        assertEquals(0, mailsWithSubject(subject), "no mail is made from the incomplete index");
        assertEquals(endOfTheWait, nextExecutionOf(triggerId).toInstant(),
                "tried again at the end of the half hour after the start");
    }

    /**
     * The same trigger run more than half an hour after the start, the index still not complete (a refill that
     * stalls): the wait does not stop the mail. It is made from what the index found, the trigger moves to its next
     * regular time, no failure is recorded, and one WARN names the trigger.
     */
    @Test
    void aScheduledTriggerWhoseFreeTextSearchMeetsTheIndexBeingFilledIsMailedAfterTheHalfHour() throws Exception {
        messageLuceneIndex.recreateIndex();
        awayFromTheRealSchedulersRun();
        String n = Long.toString(System.nanoTime(), 36);
        Integer list = committedListWithOneContact(n);
        String subject = "IT-INDEX-LATE-" + n;
        ZonedDateTime inThreeHours = ZonedDateTime.now(ZoneOffset.UTC).plusHours(3);
        Integer triggerId = committedDueScheduledTrigger(list, subject,
                inThreeHours.format(DateTimeFormatter.ofPattern("HH:mm")), IT_TEMPLATE, null,
                "query=nw&status=PUBLISHED");
        Instant longAgo = Instant.now().minus(MailingListExecutionService.INCOMPLETE_INDEX_WAIT).minusSeconds(60);

        List<ILoggingEvent> run = startedAt(longAgo, () -> withTheIndexIncomplete(
                () -> schedulerRunWarningsAbout(triggerId)));

        assertEquals(1, mailsWithSubject(subject), "the mail is made");
        assertEquals(ScheduledMail.Status.PENDING, mailStatus(subject), "and queued to be sent");
        assertEquals(1, run.size(), "one line about the trigger: " + run);
        assertEquals(Level.WARN, run.get(0).getLevel());
        assertTrue(run.get(0).getFormattedMessage().contains("is mailed although the free-text index is still not "
                + "complete"), "the warning says so: " + run.get(0).getFormattedMessage());
        assertEquals(inThreeHours.truncatedTo(ChronoUnit.MINUTES).toInstant(), nextExecutionOf(triggerId).toInstant(),
                "the trigger moves to its next regular time");
        assertTrue(errorRecords(n).isEmpty(), "and leaves no failure record");
        assertTrue(waitRecords(n).isEmpty(), "nor a record of a wait");
    }
}
