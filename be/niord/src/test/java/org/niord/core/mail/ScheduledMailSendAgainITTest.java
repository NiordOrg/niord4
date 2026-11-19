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
package org.niord.core.mail;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.mail.ScheduledMail.Status;
import org.niord.core.mail.ScheduledMailRecipient.RecipientType;
import org.niord.core.mail.vo.SendAgainRequestVo.Item;
import org.niord.core.mail.vo.SendAgainResultVo;
import org.niord.core.mail.vo.SendAgainResultVo.Reason;
import org.niord.test.ITTest;
import org.niord.test.MailHog;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Mail Queue's Send again ({@link ScheduledMailService#sendAgain}) with the mail filter open: a held, sent or
 * failed mail is put back in the queue as it was made, a mail that changed since the page read it, a record and a
 * deleted mail are refused, and a held mail sent again reaches the mail server once.
 *
 * <p>Set up as {@code MailDeliveryITTest}: {@code mailValidRecipients = "ALL"} before the context, so a send reaches
 * the test's MailHog, in a context of its own that is evicted after the class. The minute sender is switched off in
 * that context ({@code niord.schedule.mail-send-cron = -}), and the test sends a mail sent again as the sender does
 * ({@link MailService#sendScheduledMail(Integer)}). Another cached context's minute sender may still send it first, as
 * the mail is due at once and the filter is set for the whole JVM, so a test reads a queued mail as pending or as sent
 * once since ({@code assertQueuedAgain}). Every row is committed, as Send again and the send run in transactions of
 * their own, and removed afterwards.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScheduledMailSendAgainITTest extends ITTest {

    private static final String SYSADMIN = "it-send-again-sysadmin";

    @DynamicPropertySource
    static void realSendMode(DynamicPropertyRegistry registry) {
        System.setProperty("mailValidRecipients", "ALL");
        registry.add("niord.test.mailValidRecipients", () -> "ALL");
        registry.add("niord.schedule.mail-send-cron", () -> "-");
    }

    @AfterAll
    static void clearRealSendMode() {
        System.clearProperty("mailValidRecipients");
    }

    @Autowired
    ScheduledMailService scheduledMailService;

    @Autowired
    MailService mailService;

    @PersistenceContext
    EntityManager em;

    private final List<Integer> committedMailIds = new ArrayList<>();

    @BeforeEach
    void beforeEach() {
        MailHog.clear();
    }

    @AfterEach
    void afterEach() {
        for (Integer id : committedMailIds) {
            try {
                Tx.requiringNew().run(() -> {
                    ScheduledMail m = em.find(ScheduledMail.class, id);
                    if (m != null) {
                        em.remove(m);
                    }
                });
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        committedMailIds.clear();
    }

    private static String unique(String tag) {
        return "IT-SEND-AGAIN-" + tag + "-" + System.nanoTime();
    }

    /** Commits a mail with the given subject, status, attempts, last error and last Send again */
    private Integer commit(String subject, Status status, int attempts, String lastError, Date sentAgainAt) {
        Integer id = Tx.requiringNew().call(() -> {
            ScheduledMail mail = new ScheduledMail();
            mail.setCreated(new Date(System.currentTimeMillis() - 60 * 60 * 1000L));
            mail.setSender("sender@example.com");
            mail.setSubject(subject);
            mail.setHtmlContents("<html><body><p>" + subject + "</p></body></html>");
            mail.setStatus(status);
            if (status == Status.PENDING) {
                // Due tomorrow, so no minute sender (another test context's) takes it up
                mail.setSendDate(new Date(System.currentTimeMillis() + 24L * 60 * 60 * 1000L));
            }
            mail.setAttempts(attempts);
            mail.setLastError(lastError);
            mail.setSentAgainAt(sentAgainAt);
            mail.setSentAgainBy(sentAgainAt == null ? null : "an-earlier-sysadmin");
            mail.addRecipient(new ScheduledMailRecipient(RecipientType.TO, "to-" + status + "@example.com"));
            mail.addRecipient(new ScheduledMailRecipient(RecipientType.CC, "cc-" + status + "@example.com"));
            em.persist(mail);
            em.flush();
            return mail.getId();
        });
        committedMailIds.add(id);
        return id;
    }

    private ScheduledMail reload(Integer id) {
        return Tx.requiringNew().call(() -> {
            ScheduledMail mail = em.find(ScheduledMail.class, id);
            if (mail != null) {
                mail.getRecipients().size();
            }
            return mail;
        });
    }

    private static List<String> addresses(ScheduledMail mail) {
        return mail.getRecipients().stream().map(ScheduledMailRecipient::getAddress).sorted().toList();
    }

    /**
     * The mail was put back in the queue with the full ladder of retries: still pending with no attempt, or already
     * sent once. A mail sent again is due at once, and the minute sender of another cached test context (this class
     * switches off only its own) may send it, under the filter ALL this class sets for the whole JVM, between the
     * request and the test's read.
     */
    private static void assertQueuedAgain(ScheduledMail mail, String what) {
        boolean pending = mail.getStatus() == Status.PENDING && mail.getAttempts() == 0;
        boolean sentSince = mail.getStatus() == Status.SENT && mail.getAttempts() == 1;
        assertTrue(pending || sentSince, what + " is pending again with no attempt, or sent once since; is "
                + mail.getStatus() + " after " + mail.getAttempts() + " attempts");
    }

    private static Map<Integer, Reason> refusals(SendAgainResultVo result) {
        return result.getRefused().stream()
                .collect(Collectors.toMap(SendAgainResultVo.Refusal::getId, SendAgainResultVo.Refusal::getReason));
    }

    @Test
    void aHeldASentAndAFailedMailAreQueuedAsTheyWereMade() throws Exception {
        Integer held = commit(unique("held"), Status.HELD, 1, null, null);
        Integer sent = commit(unique("sent"), Status.SENT, 1, null, null);
        Integer failed = commit(unique("failed"), Status.ERROR, 5, "Connection refused", null);
        List<ScheduledMail> before = List.of(reload(held), reload(sent), reload(failed));

        long t0 = System.currentTimeMillis();
        SendAgainResultVo result = scheduledMailService.sendAgain(List.of(
                new Item(failed, Status.ERROR, null), new Item(held, Status.HELD, null),
                new Item(sent, Status.SENT, null)), SYSADMIN);

        assertEquals(List.of(held, sent, failed), result.getQueued(), "all three are queued, in id order");
        assertTrue(result.getRefused().isEmpty(), "none is refused: " + refusals(result));
        for (ScheduledMail was : before) {
            ScheduledMail now = reload(was.getId());
            String what = was.getStatus() + " mail";
            assertQueuedAgain(now, what);
            assertNull(now.getLastError(), what);
            assertNotNull(now.getSentAgainAt(), what + ": when it was sent again");
            assertTrue(now.getSentAgainAt().getTime() >= t0 - 1000, what);
            assertEquals(now.getSentAgainAt().getTime(), now.getSendDate().getTime(), what + ": due at once");
            assertEquals(SYSADMIN, now.getSentAgainBy(), what + ": who sent it again");
            assertEquals(was.getCreated().getTime(), now.getCreated().getTime(), what + ": made when it was");
            assertEquals(was.getSender(), now.getSender(), what);
            assertEquals(was.getSubject(), now.getSubject(), what);
            assertArrayEquals(was.getContents(), now.getContents(), what + ": its contents are as stored");
            assertEquals(addresses(was), addresses(now), what + ": its recipients are as stored");
        }
    }

    @Test
    void aGoneARecordAndAChangedMailAreRefusedAndTheOthersStillQueued() {
        Integer held = commit(unique("ok"), Status.HELD, 1, null, null);
        Integer gone = commit(unique("gone"), Status.HELD, 1, null, null);
        Tx.requiringNew().run(() -> em.remove(em.find(ScheduledMail.class, gone)));
        // A record of a failed trigger, as MailingListExecutionService.saveFailureRecord makes it
        Integer record = commit("Not sent: " + unique("record"), Status.ERROR, 0, "the cause", null);
        Integer staleStatus = commit(unique("stale-status"), Status.HELD, 1, null, null);
        Date earlier = new Date(System.currentTimeMillis() - 10 * 60 * 1000L);
        Integer staleSentAgain = commit(unique("stale-sent-again"), Status.HELD, 1, null, earlier);
        Integer pending = commit(unique("pending"), Status.PENDING, 0, null, null);

        SendAgainResultVo result = scheduledMailService.sendAgain(List.of(
                new Item(held, Status.HELD, null),
                new Item(gone, Status.HELD, null),
                new Item(record, Status.ERROR, null),
                new Item(staleStatus, Status.SENT, null),
                new Item(staleSentAgain, Status.HELD, null),
                new Item(pending, Status.HELD, null)), SYSADMIN);

        assertEquals(List.of(held), result.getQueued());
        assertEquals(Map.of(gone, Reason.GONE, record, Reason.RECORD, staleStatus, Reason.CHANGED,
                staleSentAgain, Reason.CHANGED, pending, Reason.CHANGED), refusals(result));
        assertQueuedAgain(reload(held), "the accepted mail is committed");
        assertEquals(SYSADMIN, reload(held).getSentAgainBy());
        assertEquals(Status.ERROR, reload(record).getStatus(), "the record is as it was");
        assertEquals(0, reload(record).getAttempts());
        assertEquals(Status.HELD, reload(staleStatus).getStatus(), "a changed mail is as it was");
        assertNull(reload(staleStatus).getSentAgainAt());
        assertEquals(earlier.getTime(), reload(staleSentAgain).getSentAgainAt().getTime());
        assertEquals("an-earlier-sysadmin", reload(staleSentAgain).getSentAgainBy());
        assertEquals(0, reload(pending).getAttempts());
        assertNull(reload(pending).getSentAgainAt());
    }

    @Test
    void aHeldMailSentAgainReachesTheMailServerOnce() {
        String subject = unique("delivered");
        Integer id = commit(subject, Status.HELD, 1, null, null);

        SendAgainResultVo first = scheduledMailService.sendAgain(List.of(new Item(id, Status.HELD, null)), SYSADMIN);
        assertEquals(List.of(id), first.getQueued());

        // Sent as the minute sender sends it
        mailService.sendScheduledMail(id);
        MailHog.Message delivered = MailHog.waitForOne(subject);
        assertNotNull(delivered, "the mail sent again reaches the mail server");
        assertTrue(delivered.toAddresses().contains("to-HELD@example.com"), "was " + delivered.toAddresses());
        ScheduledMail sent = reload(id);
        assertEquals(Status.SENT, sent.getStatus());
        assertEquals(1, sent.getAttempts());

        // The page's old view of the mail (HELD, never sent again) is stale now
        SendAgainResultVo second = scheduledMailService.sendAgain(List.of(new Item(id, Status.HELD, null)), SYSADMIN);
        assertTrue(second.getQueued().isEmpty());
        assertEquals(Map.of(id, Reason.CHANGED), refusals(second));
        mailService.sendScheduledMail(id);
        assertEquals(1, MailHog.messagesWithSubject(subject).size(), "nothing more is delivered");
        assertEquals(Status.SENT, reload(id).getStatus());
    }

    @Test
    void twoConcurrentRequestsForOneMailQueueItOnce() throws Exception {
        Integer id = commit(unique("concurrent"), Status.HELD, 1, null, null);

        int requests = 2;
        CyclicBarrier startLine = new CyclicBarrier(requests);
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        List<SendAgainResultVo> results = new ArrayList<>();
        try {
            List<Future<SendAgainResultVo>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                String user = SYSADMIN + "-" + i;
                futures.add(pool.submit(() -> {
                    startLine.await(10, TimeUnit.SECONDS);
                    return scheduledMailService.sendAgain(List.of(new Item(id, Status.HELD, null)), user);
                }));
            }
            for (Future<SendAgainResultVo> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "the request pool must terminate");
        }

        long queued = results.stream().filter(r -> r.getQueued().equals(List.of(id))).count();
        long changed = results.stream().filter(r -> Map.of(id, Reason.CHANGED).equals(refusals(r))).count();
        assertEquals(1, queued, "one request queues the mail: " + Arrays.toString(results.stream()
                .map(r -> r.getQueued() + " " + refusals(r)).toArray()));
        assertEquals(1, changed, "the other finds it changed");
        ScheduledMail after = reload(id);
        assertQueuedAgain(after, "the mail");
        assertTrue(after.getSentAgainBy().startsWith(SYSADMIN), after.getSentAgainBy());
    }
}
