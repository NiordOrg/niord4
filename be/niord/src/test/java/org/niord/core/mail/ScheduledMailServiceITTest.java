/*
 * Copyright 2017 Danish Maritime Authority.
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

import org.springframework.transaction.annotation.Transactional;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.mail.MessagingException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.mail.ScheduledMail.Status;
import org.niord.core.mail.ScheduledMailRecipient.RecipientType;
import org.niord.core.settings.Setting;
import org.niord.core.settings.SettingsService;
import org.niord.model.search.PagedSearchResultVo;
import org.niord.test.ITTest;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link ScheduledMailService} search/lookup behaviour plus a couple of
 * {@link MailService} send behaviours, run against the seeded MySQL container of the {@link ITTest}
 * infrastructure.
 *
 * <p>Most tests are wrapped in {@link TestTransaction} so all writes are rolled back, keeping the
 * shared seed database pristine and the tests independent. The {@code search()} criteria query and
 * the {@code ScheduledMail.findPendingMails} named query run in the caller transaction, so rows
 * persisted+flushed in the same test are visible to them. Every test uses a UNIQUE subject (suffixed
 * with {@link System#nanoTime()}) so seeded or sibling rows never interfere with the assertions.
 *
 * <p>{@link MailService#sendScheduledMail(Integer)} is annotated
 * {@code @Transactional(REQUIRES_NEW)} and therefore COMMITS its own transaction — it is NOT rolled
 * back by {@link TestTransaction}. That test commits its fixture through
 * {@link Tx#requiringNew()} and removes it again in {@link #afterEach()}.
 *
 * <p>No real SMTP traffic is generated: the default {@code mailValidRecipients} setting is
 * {@code "LOG"}, which puts {@link MailService} in simulation mode, so a "send" merely sleeps and
 * marks the scheduled mail as HELD without contacting a mail server.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class ScheduledMailServiceITTest extends ITTest {

    @Autowired
    ScheduledMailService scheduledMailService;

    @Autowired
    MailService mailService;

    @Autowired
    SettingsService settingsService;

    /** The retention setting the purge reads */
    private static final String DELETE_AFTER_DAYS = "mailDeleteAfterDays";

    @PersistenceContext
    EntityManager em;

    /** Ids of rows COMMITTED outside the test transaction, to be cleaned up in {@link #afterEach()}. */
    private final List<Integer> committedMailIds = new ArrayList<>();

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /**
     * Builds a minimal, valid {@link ScheduledMail}: a unique subject, gzip-compressed HTML contents
     * (satisfying the {@code @NotNull contents} column) and a single TO recipient.
     */
    private ScheduledMail newMail(String subject, String recipient) {
        ScheduledMail mail = new ScheduledMail();
        mail.setSubject(subject);
        try {
            mail.setHtmlContents("<p>x</p>");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        mail.addRecipient(new ScheduledMailRecipient(RecipientType.TO, recipient));
        return mail;
    }

    /** Persists+flushes a scheduled mail inside the current (test) transaction. */
    private ScheduledMail persist(ScheduledMail mail) {
        em.persist(mail);
        em.flush();
        return mail;
    }

    @AfterEach
    void afterEach() {
        // Best-effort cleanup of any rows committed by REQUIRES_NEW tests.
        for (Integer id : committedMailIds) {
            try {
                Tx.requiringNew().run(() -> {
                    ScheduledMail m = em.find(ScheduledMail.class, id);
                    if (m != null) {
                        em.remove(m); // orphanRemoval + CascadeType.ALL removes the recipients too
                    }
                });
            } catch (Exception ignored) {
                // swallow - cleanup is best-effort
            }
        }
        committedMailIds.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // search() - by subject
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchBySubjectFindsExactlyTheCreatedMail() {
        String subject = "IT-MAIL-subject-" + System.nanoTime();
        persist(newMail(subject, "a@b.com"));

        PagedSearchResultVo<ScheduledMail> result = scheduledMailService.search(
                new ScheduledMailSearchParams().subject(subject));

        assertEquals(1L, result.getTotal(),
                "a unique subject must match exactly the one created mail");
        assertEquals(subject, result.getData().get(0).getSubject());
    }

    @Test
    @Transactional
    void searchBySubstringOfSubjectMatchesViaLike() {
        // search() uses a case-insensitive LIKE %value% on the subject.
        long n = System.nanoTime();
        String subject = "IT-MAIL-LikeNeedle-" + n + "-tail";
        persist(newMail(subject, "a@b.com"));

        PagedSearchResultVo<ScheduledMail> result = scheduledMailService.search(
                new ScheduledMailSearchParams().subject("likeneedle-" + n));

        assertEquals(1L, result.getTotal(),
                "a case-insensitive substring of the subject must match via LIKE");
        assertEquals(subject, result.getData().get(0).getSubject());
    }

    @Test
    @Transactional
    void searchBySubjectThatMatchesNothingReturnsEmpty() {
        String subject = "IT-MAIL-none-" + System.nanoTime();

        PagedSearchResultVo<ScheduledMail> result = scheduledMailService.search(
                new ScheduledMailSearchParams().subject(subject));

        assertEquals(0L, result.getTotal(), "an unmatched subject must yield no results");
        assertTrue(result.getData().isEmpty());
    }

    @Test
    @Transactional
    void searchWithDefaultMaxSizeAndHighPageDoesNotOverflow_MAIL5() {
        // MAIL5: PagedSearchParamsVo's default maxSize is Integer.MAX_VALUE, so for page > 1 the product
        // page * maxSize overflowed int and produced a negative first-result offset (which Hibernate rejects
        // with an IllegalArgumentException). The clamped (long) computation now yields a defined, in-range
        // offset, so a high page index with the default page size simply returns an empty page, not an error.
        ScheduledMailSearchParams params = new ScheduledMailSearchParams();
        params.page(2); // default maxSize = Integer.MAX_VALUE -> page * maxSize overflows int
        PagedSearchResultVo<ScheduledMail> result = scheduledMailService.search(params);
        assertTrue(result.getData().isEmpty(),
                "a high page index with the default (max) page size must return an empty page, not overflow");
    }

    // ------------------------------------------------------------------------------------------- //
    // search() - by recipient (recipients join)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByRecipientMatchesViaRecipientsJoin() {
        long n = System.nanoTime();
        String subject = "IT-MAIL-rcpt-" + n;
        String recipient = "it-rcpt-" + n + "@example.com";
        persist(newMail(subject, recipient));

        PagedSearchResultVo<ScheduledMail> result = scheduledMailService.search(
                new ScheduledMailSearchParams().recipient(recipient));

        assertEquals(1L, result.getTotal(),
                "a unique recipient address must match exactly one mail via the recipients join");
        assertEquals(subject, result.getData().get(0).getSubject());
    }

    @Test
    @Transactional
    void searchByUnknownRecipientReturnsEmpty() {
        long n = System.nanoTime();
        persist(newMail("IT-MAIL-rcpt-known-" + n, "known-" + n + "@example.com"));

        PagedSearchResultVo<ScheduledMail> result = scheduledMailService.search(
                new ScheduledMailSearchParams().recipient("absent-" + n + "@nowhere.test"));

        assertEquals(0L, result.getTotal(),
                "a recipient address present on no mail must yield no results");
    }

    // ------------------------------------------------------------------------------------------- //
    // search() - by sender
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchBySenderMatchesViaSenderColumn() {
        long n = System.nanoTime();
        String subject = "IT-MAIL-sender-" + n;
        String sender = "it-sender-" + n + "@example.com";
        ScheduledMail mail = newMail(subject, "a@b.com");
        mail.setSender(sender);
        persist(mail);

        PagedSearchResultVo<ScheduledMail> result = scheduledMailService.search(
                new ScheduledMailSearchParams().sender(sender));

        assertEquals(1L, result.getTotal(),
                "a unique sender must match exactly the one created mail");
        assertEquals(sender, result.getData().get(0).getSender());
        assertEquals(subject, result.getData().get(0).getSubject());
    }

    // ------------------------------------------------------------------------------------------- //
    // search() - by status (PENDING vs SENT)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchByStatusDistinguishesPendingFromSent() {
        long n = System.nanoTime();
        String prefix = "IT-MAIL-status-" + n;

        ScheduledMail pending = newMail(prefix + "-PENDING", "a@b.com");
        pending.setStatus(Status.PENDING);
        persist(pending);

        ScheduledMail sent = newMail(prefix + "-SENT", "a@b.com");
        sent.setStatus(Status.SENT);
        persist(sent);

        // Filter on subject prefix + PENDING.
        PagedSearchResultVo<ScheduledMail> pendingResult = scheduledMailService.search(
                new ScheduledMailSearchParams().subject(prefix).status(Status.PENDING));
        assertEquals(1L, pendingResult.getTotal(), "exactly one of the two mails is PENDING");
        assertEquals(Status.PENDING, pendingResult.getData().get(0).getStatus());
        assertEquals(prefix + "-PENDING", pendingResult.getData().get(0).getSubject());

        // Filter on subject prefix + SENT.
        PagedSearchResultVo<ScheduledMail> sentResult = scheduledMailService.search(
                new ScheduledMailSearchParams().subject(prefix).status(Status.SENT));
        assertEquals(1L, sentResult.getTotal(), "exactly one of the two mails is SENT");
        assertEquals(Status.SENT, sentResult.getData().get(0).getStatus());
        assertEquals(prefix + "-SENT", sentResult.getData().get(0).getSubject());

        // Without a status filter, both prefix mails are returned.
        PagedSearchResultVo<ScheduledMail> bothResult = scheduledMailService.search(
                new ScheduledMailSearchParams().subject(prefix));
        assertEquals(2L, bothResult.getTotal(),
                "with no status filter both the PENDING and SENT mail must match the prefix");
    }

    // ------------------------------------------------------------------------------------------- //
    // search() - created-desc ordering and paging
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void searchReturnsResultsNewestFirstByCreatedDate() {
        long n = System.nanoTime();
        String prefix = "IT-MAIL-order-" + n;

        // Three mails with distinct, controlled created timestamps (oldest -> newest).
        ScheduledMail oldest = newMail(prefix + "-1", "a@b.com");
        oldest.setCreated(new Date(1_000_000_000_000L));
        persist(oldest);

        ScheduledMail middle = newMail(prefix + "-2", "a@b.com");
        middle.setCreated(new Date(1_000_000_060_000L));
        persist(middle);

        ScheduledMail newest = newMail(prefix + "-3", "a@b.com");
        newest.setCreated(new Date(1_000_000_120_000L));
        persist(newest);

        PagedSearchResultVo<ScheduledMail> result = scheduledMailService.search(
                new ScheduledMailSearchParams().subject(prefix));

        assertEquals(3L, result.getTotal());
        List<String> subjects = result.getData().stream()
                .map(ScheduledMail::getSubject)
                .collect(Collectors.toList());
        assertEquals(List.of(prefix + "-3", prefix + "-2", prefix + "-1"), subjects,
                "search() must order results by created descending (newest first)");
    }

    @Test
    @Transactional
    void searchPagingSlicesResultsByMaxSizeAndPage() {
        long n = System.nanoTime();
        String prefix = "IT-MAIL-page-" + n;

        ScheduledMail oldest = newMail(prefix + "-1", "a@b.com");
        oldest.setCreated(new Date(2_000_000_000_000L));
        persist(oldest);

        ScheduledMail middle = newMail(prefix + "-2", "a@b.com");
        middle.setCreated(new Date(2_000_000_060_000L));
        persist(middle);

        ScheduledMail newest = newMail(prefix + "-3", "a@b.com");
        newest.setCreated(new Date(2_000_000_120_000L));
        persist(newest);

        // Page 0, size 2 -> the two newest, newest first.
        PagedSearchResultVo<ScheduledMail> page0 = scheduledMailService.search(
                (ScheduledMailSearchParams) new ScheduledMailSearchParams()
                        .subject(prefix).maxSize(2).page(0));
        assertEquals(3L, page0.getTotal(), "total must reflect all matching rows, not the page size");
        assertEquals(2, page0.getData().size(), "page 0 with maxSize 2 must contain two rows");
        assertEquals(List.of(prefix + "-3", prefix + "-2"),
                page0.getData().stream().map(ScheduledMail::getSubject).collect(Collectors.toList()),
                "page 0 must hold the two newest rows in created-desc order");

        // Page 1, size 2 -> the remaining (oldest) row.
        PagedSearchResultVo<ScheduledMail> page1 = scheduledMailService.search(
                (ScheduledMailSearchParams) new ScheduledMailSearchParams()
                        .subject(prefix).maxSize(2).page(1));
        assertEquals(3L, page1.getTotal());
        assertEquals(1, page1.getData().size(), "page 1 with maxSize 2 must contain the remaining row");
        assertEquals(prefix + "-1", page1.getData().get(0).getSubject(),
                "page 1 must hold the oldest row");
    }

    // ------------------------------------------------------------------------------------------- //
    // getScheduledMail(id)
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getScheduledMailReturnsPersistedEntityForKnownId() {
        String subject = "IT-MAIL-byid-" + System.nanoTime();
        ScheduledMail mail = persist(newMail(subject, "a@b.com"));
        Integer id = mail.getId();
        assertNotNull(id, "precondition: persisted mail must have a generated id");

        em.clear();

        ScheduledMail found = scheduledMailService.getScheduledMail(id);
        assertNotNull(found, "getScheduledMail must return the row for a known id");
        assertEquals(id, found.getId());
        assertEquals(subject, found.getSubject());
    }

    @Test
    @Transactional
    void getScheduledMailReturnsNullForUnknownId() {
        assertNull(scheduledMailService.getScheduledMail(Integer.MAX_VALUE),
                "getScheduledMail must return null for an id that does not exist");
    }

    // ------------------------------------------------------------------------------------------- //
    // getPendingMails()
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getPendingMailsIncludesPendingMailWithPastSendDate() {
        long n = System.nanoTime();
        ScheduledMail mail = newMail("IT-MAIL-pending-past-" + n, "a@b.com");
        mail.setStatus(Status.PENDING);
        mail.setSendDate(new Date(System.currentTimeMillis() - 60_000L)); // in the past
        persist(mail);
        Integer id = mail.getId();

        List<Integer> pendingIds = scheduledMailService.getPendingMails().stream()
                .map(ScheduledMail::getId)
                .collect(Collectors.toList());

        assertTrue(pendingIds.contains(id),
                "a PENDING mail whose sendDate has passed must be returned by getPendingMails()");
    }

    @Test
    @Transactional
    void getPendingMailsExcludesFutureAndSentMails() {
        long n = System.nanoTime();

        // PENDING but sendDate in the future -> excluded (named query requires sendDate <= now).
        ScheduledMail future = newMail("IT-MAIL-pending-future-" + n, "a@b.com");
        future.setStatus(Status.PENDING);
        future.setSendDate(new Date(System.currentTimeMillis() + 3_600_000L));
        persist(future);
        Integer futureId = future.getId();

        // SENT with a past sendDate -> excluded (named query requires status = PENDING).
        ScheduledMail sent = newMail("IT-MAIL-pending-sent-" + n, "a@b.com");
        sent.setStatus(Status.SENT);
        sent.setSendDate(new Date(System.currentTimeMillis() - 60_000L));
        persist(sent);
        Integer sentId = sent.getId();

        List<Integer> pendingIds = scheduledMailService.getPendingMails().stream()
                .map(ScheduledMail::getId)
                .collect(Collectors.toList());

        assertFalse(pendingIds.contains(futureId),
                "a PENDING mail with a future sendDate must NOT be returned by getPendingMails()");
        assertFalse(pendingIds.contains(sentId),
                "a SENT mail must NOT be returned by getPendingMails()");
    }

    // ------------------------------------------------------------------------------------------- //
    // MailService.sendMail(Mail) - validation
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void sendMailWithNoRecipientsThrowsNoValidRecipient() {
        // sendMail fills in a default sender/from, then rejects the mail because it has no recipients.
        Mail mail = new Mail().subject("IT-MAIL-norecipient-" + System.nanoTime());

        MessagingException ex = assertThrows(MessagingException.class,
                () -> mailService.sendMail(mail));
        assertEquals("No valid recipient", ex.getMessage(),
                "a mail with no recipients must be rejected with 'No valid recipient'");
    }

    // ------------------------------------------------------------------------------------------- //
    // MailService.sendScheduledMail(id) - COMMITTED (REQUIRES_NEW), cleaned up in @AfterEach
    // ------------------------------------------------------------------------------------------- //

    @Test
    void sendScheduledMailMarksPendingMailAsHeldUnderTheLogFilter() {
        String subject = "IT-MAIL-send-" + System.nanoTime();

        // Commit a PENDING mail with valid contents + a TO recipient in its own transaction, so the
        // REQUIRES_NEW sendScheduledMail can re-read it as a committed row.
        Integer id = Tx.requiringNew().call(() -> {
            ScheduledMail mail = newMail(subject, "recipient@example.com");
            mail.setStatus(Status.PENDING);
            em.persist(mail);
            em.flush();
            return mail.getId();
        });
        assertNotNull(id, "precondition: committed mail must have a generated id");
        committedMailIds.add(id);

        // Simulation mode ("LOG"): no SMTP contact; the mail is held, not marked SENT.
        ScheduledMail returned = mailService.sendScheduledMail(id);
        assertNotNull(returned, "sendScheduledMail must return the processed entity");
        assertEquals(Status.HELD, returned.getStatus());

        // Verify the COMMITTED database state from a fresh transaction.
        ScheduledMail reloaded = Tx.requiringNew()
                .call(() -> em.find(ScheduledMail.class, id));
        assertNotNull(reloaded, "the committed mail row must still exist");
        assertEquals(Status.HELD, reloaded.getStatus(),
                "a mail handled under the LOG filter must be persisted with status HELD");
        assertEquals(1, reloaded.getAttempts(),
                "registerMailHeld must record exactly one attempt");
        assertNull(reloaded.getLastError(),
                "a held mail carries no error");
    }

    // ------------------------------------------------------------------------------------------- //
    // sendPendingMails() - the @Scheduled(cron="24 * * * * ?") dispatch, invoked directly
    //
    // sendPendingMails fetches up to mailMaxPerMinute (default 10) mails that are PENDING with a sendDate
    // in the past, and submits each to MailService.sendScheduledMail (@Transactional REQUIRES_NEW, which
    // COMMITS). The fixtures must therefore be committed so the REQUIRES_NEW send path can re-read them; in
    // the default "LOG" simulation mode no SMTP traffic is generated, the mail is simply marked HELD. The
    // package-private method is reachable from this same-package test. Only the cron firing *time* and the
    // @Lock(WRITE) concurrency guard are not asserted (the Quarkus scheduler is not driven; a single test
    // thread cannot exercise the lock). See docs/untestable-code.md / docs/framework-coupled-tests.md.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void sendPendingMailsHandlesDuePendingMailsButLeavesFutureOnesPending() {
        long n = System.nanoTime();

        // A due PENDING mail (sendDate one day in the past) and a not-yet-due PENDING mail (one day ahead),
        // both COMMITTED outside the test transaction so the REQUIRES_NEW send path can re-read them.
        Integer dueId = Tx.requiringNew().call(() -> {
            ScheduledMail m = newMail("IT-MAIL-send-due-" + n, "due-" + n + "@example.com");
            m.setStatus(Status.PENDING);
            m.setSendDate(new Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000L));
            em.persist(m);
            em.flush();
            return m.getId();
        });
        Integer futureId = Tx.requiringNew().call(() -> {
            ScheduledMail m = newMail("IT-MAIL-send-future-" + n, "future-" + n + "@example.com");
            m.setStatus(Status.PENDING);
            m.setSendDate(new Date(System.currentTimeMillis() + 24L * 60 * 60 * 1000L));
            em.persist(m);
            em.flush();
            return m.getId();
        });
        committedMailIds.add(dueId);
        committedMailIds.add(futureId);

        scheduledMailService.sendPendingMails();

        ScheduledMail due = Tx.requiringNew().call(() -> em.find(ScheduledMail.class, dueId));
        ScheduledMail future = Tx.requiringNew().call(() -> em.find(ScheduledMail.class, futureId));
        assertEquals(Status.HELD, due.getStatus(),
                "a PENDING mail whose sendDate has passed must be handled (held, under LOG) by sendPendingMails");
        assertEquals(1, due.getAttempts(),
                "the dispatched mail must record exactly one send attempt");
        assertEquals(Status.PENDING, future.getStatus(),
                "a PENDING mail whose sendDate is still in the future must NOT be sent");
    }

    // ------------------------------------------------------------------------------------------- //
    // deleteExpiredMails() - the @Scheduled(cron="48 28 5 * * ?") cleanup, invoked directly
    //
    // deleteExpiredMails removes every ScheduledMail created before (now - mailDeleteAfterDays); the
    // setting defaults to 30. It is @Transactional(REQUIRES_NEW) so it COMMITS its deletions, hence the
    // fixtures are committed and asserted from a fresh transaction. Only the cron firing time and the
    // @Lock(WRITE) guard are not asserted. See docs/untestable-code.md / docs/framework-coupled-tests.md.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void deleteExpiredMailsDeletesMailsOlderThanRetentionButKeepsRecentOnes() {
        long n = System.nanoTime();

        // One mail created ~365 days ago (older than the 30-day retention -> deleted) and one created now
        // (kept). The year-sized margin beats any JVM/MySQL timezone skew on the created<=expiryDate query.
        Integer oldId = Tx.requiringNew().call(() -> {
            ScheduledMail m = newMail("IT-MAIL-old-" + n, "a@b.com");
            m.setCreated(new Date(System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000L));
            em.persist(m);
            em.flush();
            return m.getId();
        });
        Integer recentId = Tx.requiringNew().call(() -> {
            ScheduledMail m = newMail("IT-MAIL-recent-" + n, "a@b.com");
            m.setCreated(new Date());
            em.persist(m);
            em.flush();
            return m.getId();
        });
        committedMailIds.add(oldId);
        committedMailIds.add(recentId);

        scheduledMailService.deleteExpiredMails();

        ScheduledMail old = Tx.requiringNew().call(() -> em.find(ScheduledMail.class, oldId));
        ScheduledMail recent = Tx.requiringNew().call(() -> em.find(ScheduledMail.class, recentId));
        assertNull(old,
                "a scheduled mail created before (now - mailDeleteAfterDays) must be deleted by deleteExpiredMails");
        assertNotNull(recent,
                "a scheduled mail created within the retention window must be kept");
    }

    /**
     * The purge reads mailDeleteAfterDays live (SECOM-UI-1), the value the mail queue's page shows: a change
     * on the Settings page applies to the next run, without a restart. The setting commits, so it is put back.
     */
    @Test
    void deleteExpiredMailsUsesTheRetentionAsItIsNow() {
        long n = System.nanoTime();
        long day = 24L * 60 * 60 * 1000L;

        // Twenty days old: kept by the default of 30 days, deleted by a retention of 10
        Integer twentyDaysId = Tx.requiringNew().call(() -> {
            ScheduledMail m = newMail("IT-MAIL-twenty-days-" + n, "a@b.com");
            m.setCreated(new Date(System.currentTimeMillis() - 20 * day));
            em.persist(m);
            em.flush();
            return m.getId();
        });
        // A year old: deleted by any retention, kept when the purge is off
        Integer yearId = Tx.requiringNew().call(() -> {
            ScheduledMail m = newMail("IT-MAIL-year-" + n, "a@b.com");
            m.setCreated(new Date(System.currentTimeMillis() - 365 * day));
            em.persist(m);
            em.flush();
            return m.getId();
        });
        committedMailIds.add(twentyDaysId);
        committedMailIds.add(yearId);

        Object original = Tx.requiringNew().call(() -> em.find(Setting.class, DELETE_AFTER_DAYS).getValue());
        try {
            settingsService.set(DELETE_AFTER_DAYS, 0);
            scheduledMailService.deleteExpiredMails();
            assertNotNull(Tx.requiringNew().call(() -> em.find(ScheduledMail.class, yearId)),
                    "a retention of 0 deletes nothing, also without a restart");

            settingsService.set(DELETE_AFTER_DAYS, 10);
            scheduledMailService.deleteExpiredMails();
            assertNull(Tx.requiringNew().call(() -> em.find(ScheduledMail.class, twentyDaysId)),
                    "a retention of 10 days deletes a mail of 20 days at the next run");
            assertNull(Tx.requiringNew().call(() -> em.find(ScheduledMail.class, yearId)));
        } finally {
            settingsService.set(DELETE_AFTER_DAYS, original);
            settingsService.evictFromCache(DELETE_AFTER_DAYS);
        }
    }
}
