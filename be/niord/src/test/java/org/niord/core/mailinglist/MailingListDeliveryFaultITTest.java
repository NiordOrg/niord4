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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Set;

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
 * broker that cannot be reached (MAILLIST2 in be/docs/potential-errors.md), a scheduled trigger whose reschedule
 * or run fails, and a scheduled trigger whose search fails, which fails the trigger rather than mail an empty list.
 *
 * <p>The status-change cases publish a committed message and let the real listener ({@code MailingListMessageListener}
 * on the embedded broker) execute the committed triggers; each trigger's message filter names the test's own message,
 * so a publication elsewhere in the run makes no mail. The scheduled cases run the real per-minute scheduler. The
 * mailing lists, contacts and messages are removed afterwards.
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

    @PersistenceContext
    EntityManager em;

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

    /** The subject prefix of the failure records of the test list {@code IT-MLFAULT-<n>} */
    private static String errorRecordPrefix(String n) {
        return "Not sent: mailing list IT-MLFAULT-" + n + ",";
    }

    /** The failure records of the test list: subject, status, last error and the first recipient, each */
    private List<String[]> errorRecords(String n) {
        return Tx.requiringNew().call(() -> em.createQuery(
                        "select m from ScheduledMail m where m.subject like :p", ScheduledMail.class)
                .setParameter("p", errorRecordPrefix(n) + "%")
                .getResultList().stream()
                .map(m -> new String[] {
                        m.getSubject(),
                        m.getStatus().name(),
                        String.valueOf(m.getLastError()),
                        m.getRecipients().isEmpty() ? "" : m.getRecipients().get(0).getAddress() })
                .toList());
    }

    // ------------------------------------------------------------------------------------------- //
    // MAILLIST2: a status-change trigger whose template fails
    // ------------------------------------------------------------------------------------------- //

    /**
     * MAILLIST2 (i). Two status-change triggers on one list fire for the same publication: one whose template does not
     * exist, one with a working template. The working one's mail is queued (the listener did process the event). The
     * failing one used to be logged at ERROR and leave nothing, so the Mail Queue page could not show that a
     * subscriber's mail was due. Now it leaves one {@code ScheduledMail} in status ERROR, never sent, that names the
     * list and the message in its subject and the template error as its last error, with the list's recipient.
     */
    @Test
    void aStatusChangeTriggerWhoseTemplateFailsLeavesAnErrorRecordInTheMailQueue() {
        String n = Long.toString(System.nanoTime(), 36);
        String uid = committedDraft("IT maillist2 " + n);
        Integer list = committedListWithOneContact(n);
        String failingSubject = "IT-MAILLIST2-FAIL-" + n;
        String workingSubject = "IT-MAILLIST2-OK-" + n;
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
     * MAILLIST2 (ii) end to end. A status-change trigger whose message filter throws for the published warning (a
     * Java exception from a method the filter calls) used to read as "no match" with nothing logged. Now the trigger
     * fails with the filter named, and its failure is recorded in the Mail Queue in status ERROR.
     */
    @Test
    void aStatusChangeTriggerWhoseFilterThrowsLeavesAnErrorRecord() {
        String n = Long.toString(System.nanoTime(), 36);
        String uid = committedDraft("IT maillist2 filter " + n);
        Integer list = committedListWithOneContact(n);
        String subject = "IT-MAILLIST2-FILTER-" + n;
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

    /**
     * MAILLIST2, the broker: the status topic cannot be reached when the publication commits (here the connection
     * factory throws {@link JMSRuntimeException}, as it does when the embedded broker is stopping or out of memory).
     * {@code MessageService.broadcastStatusUpdate} 738-744 logs the failure after the commit; the warning is
     * PUBLISHED, and the subscribed mailing list's mail is never made, with nothing to retry it.
     *
     * <p>What is wrong: the publication succeeds while its delivery is lost, and no screen shows it. The right
     * assertion: the status change is kept for a later delivery (an outbox), or a record of the failed broadcast
     * exists; the mail is queued once the broker is back. NB: the pin counts the first warning's mail rows, so it
     * flips for the outbox form of the fix; a fix that only records the failed broadcast must add an assertion on
     * that record.
     */
    @Test
    void aStatusChangeThatCannotReachTheBrokerIsLostWithoutARecord_documentsBug() throws Exception {
        String n = Long.toString(System.nanoTime(), 36);
        String uid = committedDraft("IT broker down " + n);
        Integer list = committedListWithOneContact(n);
        String subject = "IT-BROKER-DOWN-" + n;
        addStatusChangeTrigger(list, subject, IT_TEMPLATE, uid);

        ConnectionFactory broken = mock(ConnectionFactory.class);
        when(broken.createContext(anyInt())).thenThrow(new JMSRuntimeException("IT: the broker cannot be reached"));
        Object target = AopTestUtils.getUltimateTargetObject(messageService);
        Object realFactory = ReflectionTestUtils.getField(target, "connectionFactory");
        ReflectionTestUtils.setField(target, "connectionFactory", broken);
        try {
            publish(uid);
        } finally {
            ReflectionTestUtils.setField(target, "connectionFactory", realFactory);
        }

        assertEquals(Status.PUBLISHED, Tx.requiringNew().call(() -> messageQueryService.findByUid(uid).getStatus()),
                "the publication itself succeeded");

        // Control, with the broker back: a second warning on the same list is published and its mail is made, so the
        // listener is alive and the list delivers, and the first warning's missing mail is the lost event. Without
        // it this test passed for any reason the listener made no mail at all; the single
        // listener thread handles the events in order, so the first one's mail would exist by now.
        String control = committedDraft("IT broker back " + n);
        String controlSubject = "IT-BROKER-BACK-" + n;
        addStatusChangeTrigger(list, controlSubject, IT_TEMPLATE, control);
        publish(control);
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .until(() -> mailsWithSubject(controlSubject) == 1);
        assertEquals(0, mailsWithSubject(subject),
                "the subscribers' mail was never made, and nothing will make it later");
    }

    // ------------------------------------------------------------------------------------------- //
    // A status-change trigger's message filter
    // ------------------------------------------------------------------------------------------- //

    /**
     * A status-change trigger whose filter names one warning mails for that warning and for no other: a second
     * warning published after it on the same topic makes no mail. The pins above each publish only the message
     * their trigger names, so a trigger that ignored its filter and mailed every publication passed them (detector
     * check MC, M15). The listener is one thread reading the topic in order; the last event is a sentinel warning's
     * CANCELLED, which only the sentinel trigger (on CANCELLED) answers, so once its mail exists every publication
     * before it has been handled.
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
        subjects.add(subject);
        Integer triggerId = Tx.requiringNew().call(() -> {
            MailingList ml = em.find(MailingList.class, list);
            MailingListTrigger trigger = new MailingListTrigger();
            trigger.setType(TriggerType.SCHEDULED);
            trigger.setScheduleType(ScheduleType.DAILY);
            trigger.setScheduledExecutionTime(time);
            trigger.setScheduledExecutionTimeZone("UTC");
            trigger.setMessageQuery("status=PUBLISHED&mainType=NW");
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
}
