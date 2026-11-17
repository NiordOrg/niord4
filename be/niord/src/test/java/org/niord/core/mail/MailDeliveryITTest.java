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
package org.niord.core.mail;

import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.mail.ScheduledMail.Status;
import org.niord.core.mail.ScheduledMailRecipient.RecipientType;
import org.niord.test.ITTest;
import org.niord.test.MailHog;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level integration tests for {@link MailService}: they assert what actually reaches the SMTP
 * server (the Mailhog container), not merely that a scheduled mail's database status flips to
 * {@code SENT}.
 *
 * <p>By default {@code MailService} runs in simulation mode ({@code mailValidRecipients = "LOG"}), so
 * no mail ever reaches SMTP — that branch is covered by {@code MailSimulationModeITTest}. This class
 * instead activates the <em>real send</em> path by setting the {@code mailValidRecipients} setting to
 * {@code "ALL"} (so {@code ValidMailRecipients.simulate()} is {@code false}). The mail is then
 * composed and handed to {@code Transport.send(...)}, which delivers it over SMTP to Mailhog; the
 * test reads it back through Mailhog's HTTP API ({@link MailHog}).
 *
 * <p><b>Real-send mode wiring (the port of {@code MailRecipientsAllResource}).</b> {@code MailService}
 * reads {@code mailValidRecipients} through {@code SettingsService.get(...)} for every mail, which checks
 * {@link System#getProperty(String)} <em>before</em> the cache/DB (and before the {@code "LOG"}
 * default). The {@link #realSendMode(DynamicPropertyRegistry)} {@code @DynamicPropertySource} method
 * sets the system property during context preparation, so every send of this class reads
 * {@code "ALL"}. The registry also publishes a distinct marker property, so Spring caches a
 * <em>separate</em> application context for this class (mirroring how Quarkus booted a separate app
 * for {@code restrictToAnnotatedClass = true}); {@link DirtiesContext} evicts that context after the
 * class and {@link #clearRealSendMode()} clears the system property, so the shared default-{@code LOG}
 * application the rest of the suite relies on is never polluted.
 *
 * <p>{@code MailService.sendScheduledMail(Integer)} is {@code @Transactional(REQUIRES_NEW)} and
 * therefore commits, so that fixture is committed via {@link Tx#requiringNew()} and
 * removed again in {@link #afterEach()}. {@link #beforeEach()} clears Mailhog so each test asserts on
 * the mail it produced; every test also uses a unique subject so the assertions are robust even if a
 * sibling test left mail behind.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MailDeliveryITTest extends ITTest {

    private static final String DEFAULT_SENDER = "niord@e-navigation.net";

    /**
     * Port of {@code MailRecipientsAllResource}: put {@code MailService} into real-send mode by
     * setting {@code mailValidRecipients = "ALL"} before the context (and the {@code MailService}
     * bean) is created. The marker property forces a distinct cached context for this class.
     */
    @DynamicPropertySource
    static void realSendMode(DynamicPropertyRegistry registry) {
        System.setProperty("mailValidRecipients", "ALL");
        registry.add("niord.test.mailValidRecipients", () -> "ALL");
    }

    /** Counterpart of {@code MailRecipientsAllResource.stop()}: never pollute the default-LOG suite. */
    @AfterAll
    static void clearRealSendMode() {
        System.clearProperty("mailValidRecipients");
    }

    @Autowired
    MailService mailService;

    @PersistenceContext
    EntityManager em;

    /** Ids of mails committed by REQUIRES_NEW sends, removed in {@link #afterEach()}. */
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
                        em.remove(m); // orphanRemoval + CascadeType.ALL removes the recipients too
                    }
                });
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        committedMailIds.clear();
    }

    private static String unique(String tag) {
        return "IT-WIRE-" + tag + "-" + System.nanoTime();
    }

    // ------------------------------------------------------------------------------------------- //
    // sendMail(content, title, recipients...) — HTML helper, multipart/alternative
    // ------------------------------------------------------------------------------------------- //

    @Test
    void sendMailHtmlHelperDeliversToWire() throws Exception {
        String subject = unique("html");
        String marker = "WIREBODY" + System.nanoTime();
        String html = "<html><body><p>" + marker + "</p></body></html>";

        mailService.sendMail(html, subject, "recipient@example.com");

        MailHog.Message m = MailHog.waitForOne(subject);
        assertNotNull(m, "the HTML mail must be delivered to the SMTP server (Mailhog)");
        assertEquals(subject, m.subject(), "the Subject header must round-trip to the wire");
        assertTrue(m.toAddresses().contains("recipient@example.com"),
                "the To address must be the requested recipient, was " + m.toAddresses());
        assertTrue(m.envelopeRecipients().contains("recipient@example.com"),
                "the SMTP envelope recipient must be the requested recipient, was " + m.envelopeRecipients());
        assertEquals(DEFAULT_SENDER, m.fromAddress(),
                "the From address must default to the configured mailSender");
        assertTrue(m.header("Content-Type").startsWith("multipart/alternative"),
                "the HTML helper includes a plain-text alternative, so the body is multipart/alternative; was "
                        + m.header("Content-Type"));
        assertTrue(m.rawData().contains(marker),
                "the HTML body content must be present in the delivered message");
    }

    // ------------------------------------------------------------------------------------------- //
    // sendMail(Mail) — explicit HTML-only mail, default sender/from filled in by MailService
    // ------------------------------------------------------------------------------------------- //

    @Test
    void sendMailObjectDeliversHtmlOnlyPartWithDefaultFrom() throws Exception {
        String subject = unique("mailobj");
        String marker = "WIREHTML" + System.nanoTime();

        // No sender/from set: MailService must fill them in with the configured mailSender default.
        Mail mail = new Mail()
                .subject(subject)
                .recipient(Message.RecipientType.TO, new InternetAddress("to@niord.test"))
                .htmlText("<html><body><p>" + marker + "</p></body></html>");

        mailService.sendMail(mail);

        MailHog.Message m = MailHog.waitForOne(subject);
        assertNotNull(m, "the mail object must be delivered to the SMTP server");
        assertEquals(subject, m.subject());
        assertTrue(m.toAddresses().contains("to@niord.test"),
                "the To address must be the recipient set on the Mail, was " + m.toAddresses());
        assertEquals(DEFAULT_SENDER, m.fromAddress(),
                "sendMail must default an absent From to the configured mailSender");
        assertTrue(m.header("Content-Type").startsWith("text/html"),
                "an HTML-only Mail with no inline parts must be sent as text/html; was "
                        + m.header("Content-Type"));
        assertTrue(m.rawData().contains(marker), "the HTML body must reach the wire");
    }

    // ------------------------------------------------------------------------------------------- //
    // sendMail(Mail) — with an attachment, multipart/mixed carrying the file name
    // ------------------------------------------------------------------------------------------- //

    @Test
    void sendMailWithAttachmentDeliversMultipartMixedWithFilename() throws Exception {
        String subject = unique("attach");
        String marker = "WIREATT" + System.nanoTime();
        String attachmentName = "wire-attachment.txt";

        Mail mail = new Mail()
                .from(new InternetAddress(DEFAULT_SENDER))
                .subject(subject)
                .recipient(Message.RecipientType.TO, new InternetAddress("attach@niord.test"))
                .htmlText("<html><body><p>" + marker + "</p></body></html>")
                .attachment(AttachmentMailPart.fromContent(
                        "attachment-bytes".getBytes(StandardCharsets.UTF_8), "text/plain", attachmentName));

        mailService.sendMail(mail);

        MailHog.Message m = MailHog.waitForOne(subject);
        assertNotNull(m, "a mail with an attachment must be delivered to the SMTP server");
        assertTrue(m.header("Content-Type").startsWith("multipart/mixed"),
                "a mail with an attachment must be sent as multipart/mixed; was " + m.header("Content-Type"));
        assertTrue(m.rawData().contains(attachmentName),
                "the attachment file name must appear in the delivered MIME message");
    }

    // ------------------------------------------------------------------------------------------- //
    // sendScheduledMail(id) — REAL send: reaches the wire AND is persisted as SENT
    // ------------------------------------------------------------------------------------------- //

    @Test
    void sendScheduledMailRealModeDeliversToWireAndMarksSent() {
        String subject = unique("scheduled");

        Integer id = Tx.requiringNew().call(() -> {
            ScheduledMail mail = new ScheduledMail();
            mail.setSubject(subject);
            try {
                mail.setHtmlContents("<html><body><p>scheduled wire body</p></body></html>");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            mail.setStatus(Status.PENDING);
            mail.addRecipient(new ScheduledMailRecipient(RecipientType.TO, "scheduled@example.com"));
            em.persist(mail);
            em.flush();
            return mail.getId();
        });
        assertNotNull(id, "precondition: committed mail must have a generated id");
        committedMailIds.add(id);

        ScheduledMail returned = mailService.sendScheduledMail(id);
        assertNotNull(returned, "sendScheduledMail must return the processed entity");
        assertEquals(Status.SENT, returned.getStatus(), "a real send to a reachable SMTP server must succeed");

        // The committed row must reflect the successful send.
        ScheduledMail reloaded = Tx.requiringNew()
                .call(() -> em.find(ScheduledMail.class, id));
        assertNotNull(reloaded);
        assertEquals(Status.SENT, reloaded.getStatus());
        assertEquals(1, reloaded.getAttempts(), "a successful send records exactly one attempt");
        assertNull(reloaded.getLastError(), "a successful send must clear lastError");

        // And — the point of this test — the mail actually reached the SMTP wire.
        MailHog.Message m = MailHog.waitForOne(subject);
        assertNotNull(m, "the scheduled mail must be delivered over SMTP, not just marked SENT");
        assertEquals(subject, m.subject());
        assertTrue(m.toAddresses().contains("scheduled@example.com"),
                "the scheduled mail's recipient must reach the wire, was " + m.toAddresses());
        // sendScheduledMail sets Reply-To to the first TO recipient when none is defined.
        assertNotNull(m.header("Reply-To"), "sendScheduledMail must set a Reply-To header");
        assertTrue(m.header("Reply-To").contains("scheduled@example.com"),
                "Reply-To must default to the first TO recipient, was " + m.header("Reply-To"));
    }

    // ------------------------------------------------------------------------------------------- //
    // sendScheduledMail(id) — concurrent senders claim the mail atomically and deliver it ONCE
    // ------------------------------------------------------------------------------------------- //

    /**
     * MAIL6 regression guard. {@code sendScheduledMail} claims the row with a pessimistic write lock
     * ({@code SELECT … FOR UPDATE}) before its "still pending" re-check, so when several schedulers race
     * for the same pending mail — the shape that occurs the moment the application is scaled beyond one
     * replica — exactly one delivers it and the losers observe the committed {@code SENT} status and
     * skip.
     *
     * <p>Two threads invoke {@code sendScheduledMail(id)} for the same mail simultaneously (released
     * together by a {@link CyclicBarrier}). The oracle is the number of messages that reach the SMTP
     * wire (Mailhog): it must be exactly one. The {@code attempts} counter is deliberately <em>not</em>
     * used as the oracle — two unsynchronized senders would each read {@code attempts == 0} and both
     * write {@code 1}, so a double send is invisible in that field; only the delivered-message count
     * distinguishes the race. Without the lock both senders load {@code PENDING} and deliver, producing
     * two messages.
     */
    @Test
    void concurrentSendScheduledMailDeliversExactlyOnce() throws Exception {
        String subject = unique("concurrent");

        Integer id = Tx.requiringNew().call(() -> {
            ScheduledMail mail = new ScheduledMail();
            mail.setSubject(subject);
            try {
                mail.setHtmlContents("<html><body><p>claimed-once body</p></body></html>");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            mail.setStatus(Status.PENDING);
            mail.addRecipient(new ScheduledMailRecipient(RecipientType.TO, "concurrent@example.com"));
            em.persist(mail);
            em.flush();
            return mail.getId();
        });
        assertNotNull(id, "precondition: committed pending mail must have a generated id");
        committedMailIds.add(id);

        int senders = 2;
        CyclicBarrier startLine = new CyclicBarrier(senders);
        ExecutorService pool = Executors.newFixedThreadPool(senders);
        try {
            List<Future<ScheduledMail>> futures = new ArrayList<>();
            for (int i = 0; i < senders; i++) {
                futures.add(pool.submit(() -> {
                    startLine.await(10, TimeUnit.SECONDS); // release both senders together
                    return mailService.sendScheduledMail(id);
                }));
            }
            for (Future<ScheduledMail> f : futures) {
                f.get(30, TimeUnit.SECONDS); // must not deadlock; each sender returns the entity
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "sender pool must terminate");
        }

        // The point of the test: the mail reached the SMTP wire exactly once despite two racing senders.
        MailHog.waitForOne(subject); // ensure the (single) delivery has arrived before counting
        assertEquals(1, MailHog.messagesWithSubject(subject).size(),
                "two concurrent senders must deliver the pending mail exactly once");

        // The committed row is SENT with a single recorded attempt.
        ScheduledMail reloaded = Tx.requiringNew().call(() -> em.find(ScheduledMail.class, id));
        assertNotNull(reloaded);
        assertEquals(Status.SENT, reloaded.getStatus(), "the claimed mail must end up SENT");
        assertEquals(1, reloaded.getAttempts(), "exactly one send attempt must be recorded");
        assertNull(reloaded.getLastError(), "a successful send must leave no error");
    }

    // ------------------------------------------------------------------------------------------- //
    // sendMail(Mail) — no recipients is rejected BEFORE any SMTP traffic, even in real-send mode
    // ------------------------------------------------------------------------------------------- //

    @Test
    void sendMailWithNoRecipientsThrowsAndDeliversNothing() {
        String subject = unique("norcpt");
        Mail mail = new Mail().subject(subject);

        MessagingException ex = assertThrows(MessagingException.class, () -> mailService.sendMail(mail));
        assertEquals("No valid recipient", ex.getMessage(),
                "a mail with no recipients must be rejected with 'No valid recipient'");

        assertTrue(MailHog.messagesWithSubject(subject).isEmpty(),
                "a rejected mail must never reach the SMTP wire");
    }
}
