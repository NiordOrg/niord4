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

import org.springframework.beans.factory.annotation.Autowired;
import jakarta.mail.Message;
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
import org.niord.test.Tx;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level security test for the {@code mailValidRecipients} allow-list — the production safety
 * mechanism that stops a non-production Niord instance from emailing real recipients.
 *
 * <p>When {@code mailValidRecipients} is a concrete address list (neither {@code "ALL"} nor
 * {@code "LOG"}), {@code ValidMailRecipients} keeps recipients that are on the list and
 * <em>substitutes</em> every other recipient with the first allow-listed address — preserving the
 * original address only in the display name. This test boots the application with the list set to a
 * single address ({@link #ALLOWED}) and asserts, at the SMTP wire (via {@link MailHog}), that a mail
 * addressed to a stranger is actually delivered only to the allow-listed address and never to the
 * stranger.
 *
 * <p><b>Allow-list mode wiring (the port of {@code MailRecipientsAllowlistResource}).</b>
 * {@code MailService} reads {@code mailValidRecipients} through {@code SettingsService.get(...)} for every
 * mail, which checks {@link System#getProperty(String)} <em>before</em> the cache/DB. The
 * {@link #allowlistMode(DynamicPropertyRegistry)} {@code @DynamicPropertySource} method sets the system
 * property during context preparation, so every send of this class reads the single allow-listed
 * address. The registry also publishes a distinct marker property, so Spring caches a <em>separate</em>
 * application context for this class (mirroring how Quarkus booted a separate app for
 * {@code restrictToAnnotatedClass = true}); {@link DirtiesContext} evicts that context after the class
 * and {@link #clearAllowlistMode()} clears the system property, so the shared default-{@code LOG}
 * application the rest of the suite relies on is never polluted.
 *
 * <p>This closes the gap where the recipient filter could previously only be unit-tested in isolation
 * ({@code ValidMailRecipientsTest}) but never verified end-to-end against a real send.
 *
 * <p>The list names a second address ({@link #ALSO_ALLOWED}) too. A scheduled mail the sender handles under the list
 * ends SENT only when the list names every one of its recipients (TO, CC and BCC); when a recipient was redirected,
 * the filter kept the mail from it, and the mail ends HELD. These mails are committed, as the send runs in a
 * transaction of its own, and removed after each test.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MailRecipientFilterITTest extends ITTest {

    /** The first allow-listed recipient; every address the list does not name is substituted with this at send time. */
    private static final String ALLOWED = "allowed@niord.test";
    /** The second allow-listed recipient */
    private static final String ALSO_ALLOWED = "also-allowed@niord.test";
    /** The setting: the two listed addresses */
    private static final String LIST = ALLOWED + ", " + ALSO_ALLOWED;
    private static final String STRANGER = "stranger@example.com";

    /**
     * Port of {@code MailRecipientsAllowlistResource}: put {@code MailService} into allow-list mode by
     * setting {@code mailValidRecipients} to a single safe address before the context (and the
     * {@code MailService} bean) is created. The marker property forces a distinct cached context.
     */
    @DynamicPropertySource
    static void allowlistMode(DynamicPropertyRegistry registry) {
        System.setProperty("mailValidRecipients", LIST);
        registry.add("niord.test.mailValidRecipients", () -> LIST);
    }

    /** Counterpart of {@code MailRecipientsAllowlistResource.stop()}: never pollute the default-LOG suite. */
    @AfterAll
    static void clearAllowlistMode() {
        System.clearProperty("mailValidRecipients");
    }

    @Autowired
    MailService mailService;

    @PersistenceContext
    EntityManager em;

    /** Ids of the scheduled mails the tests committed, removed in {@link #afterEach()} */
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
        return "IT-FILTER-" + tag + "-" + System.nanoTime();
    }

    // ------------------------------------------------------------------------------------------- //
    // A non-allow-listed recipient is substituted with the allow-listed address at the wire
    // ------------------------------------------------------------------------------------------- //

    @Test
    void strangerRecipientIsSubstitutedWithAllowlistedAddressAtTheWire() {
        String subject = unique("substitute");

        // Address the mail ONLY to a stranger — not on the allow-list.
        Mail mail = new Mail()
                .from(internet(ALLOWED))
                .subject(subject)
                .recipient(Message.RecipientType.TO, internet(STRANGER))
                .htmlText("<html><body><p>filtered</p></body></html>");

        sendQuietly(mail);

        MailHog.Message m = MailHog.waitForOne(subject);
        assertNotNull(m, "the filtered mail must still be delivered (to the substitute address)");

        // The actual SMTP envelope + parsed To must be the allow-listed address, NOT the stranger.
        List<String> envelope = m.envelopeRecipients();
        assertTrue(envelope.contains(ALLOWED),
                "the substitute (allow-listed) address must be the delivered recipient, was " + envelope);
        assertFalse(envelope.contains(STRANGER),
                "the stranger address must NOT be a delivered SMTP recipient, was " + envelope);

        List<String> to = m.toAddresses();
        assertTrue(to.contains(ALLOWED),
                "the parsed To address must be the allow-listed address, was " + to);
        assertFalse(to.contains(STRANGER),
                "the stranger must never appear as a deliverable To address, was " + to);
    }

    // ------------------------------------------------------------------------------------------- //
    // An allow-listed recipient passes through unchanged; a stranger alongside it is substituted
    // ------------------------------------------------------------------------------------------- //

    @Test
    void allowlistedRecipientPassesThroughAndStrangerIsSubstituted() {
        String subject = unique("mixed");

        Mail mail = new Mail()
                .from(internet(ALLOWED))
                .subject(subject)
                .recipient(Message.RecipientType.TO, internet(ALLOWED))
                .recipient(Message.RecipientType.TO, internet(STRANGER))
                .htmlText("<html><body><p>mixed</p></body></html>");

        sendQuietly(mail);

        MailHog.Message m = MailHog.waitForOne(subject);
        assertNotNull(m);

        List<String> envelope = m.envelopeRecipients();
        // Both recipients resolve to the allow-listed address (one direct, one substituted), and the
        // stranger never appears as an envelope recipient.
        assertFalse(envelope.contains(STRANGER),
                "no envelope recipient may be the stranger, was " + envelope);
        assertTrue(envelope.contains(ALLOWED),
                "the allow-listed address must be an envelope recipient, was " + envelope);
        assertEquals(envelope.size(), envelope.stream().filter(ALLOWED::equals).count(),
                "every delivered recipient must be the allow-listed address, was " + envelope);
    }

    // ------------------------------------------------------------------------------------------- //
    // A scheduled mail is SENT only when the list names every recipient; otherwise it is HELD
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aScheduledMailToListedRecipientsOnlyIsSent() {
        String subject = unique("all-listed");
        Integer id = commitMail(subject, Status.PENDING, 0, ALLOWED, ALSO_ALLOWED);

        mailService.sendScheduledMail(id);

        MailHog.Message m = MailHog.waitForOne(subject);
        assertNotNull(m, "the mail reaches the mail server");
        assertTrue(m.envelopeRecipients().containsAll(List.of(ALLOWED, ALSO_ALLOWED)),
                "was " + m.envelopeRecipients());
        ScheduledMail after = reload(id);
        assertEquals(Status.SENT, after.getStatus(), "every recipient is listed, so the mail reached them all");
        assertEquals(1, after.getAttempts());
    }

    @Test
    void aScheduledMailWithAnUnlistedRecipientIsHeldAndItsCopyGoesToTheFirstListedAddress() {
        String subject = unique("one-unlisted");
        Integer id = commitMail(subject, Status.PENDING, 0, STRANGER, ALSO_ALLOWED);

        mailService.sendScheduledMail(id);

        MailHog.Message m = MailHog.waitForOne(subject);
        assertNotNull(m, "the mail is still handed to the mail server");
        List<String> envelope = m.envelopeRecipients();
        assertTrue(envelope.contains(ALLOWED), "the stranger's copy goes to the first listed address, was " + envelope);
        assertTrue(envelope.contains(ALSO_ALLOWED), "the listed recipient gets it, was " + envelope);
        assertFalse(envelope.contains(STRANGER), "was " + envelope);
        ScheduledMail after = reload(id);
        assertEquals(Status.HELD, after.getStatus(), "the filter kept the mail from one of its recipients");
        assertEquals(1, after.getAttempts());
        assertNull(after.getLastError());
    }

    @Test
    void aScheduledMailWithAListedToAndAnUnlistedCcIsHeld() {
        String subject = unique("unlisted-cc");
        Integer id = commitMail(subject, Status.PENDING, 0, ALLOWED, STRANGER);

        mailService.sendScheduledMail(id);

        assertNotNull(MailHog.waitForOne(subject), "the mail is handed to the mail server");
        assertEquals(Status.HELD, reload(id).getStatus(), "the CC was redirected, so the mail is held");
    }

    /**
     * Commits a scheduled mail with the given status and attempts, to a TO recipient and a CC recipient. It is due
     * tomorrow, so no minute sender takes up a pending one before the test sends it.
     */
    private Integer commitMail(String subject, Status status, int attempts, String to, String cc) {
        Integer id = Tx.requiringNew().call(() -> {
            ScheduledMail mail = new ScheduledMail();
            mail.setSubject(subject);
            mail.setHtmlContents("<html><body><p>" + subject + "</p></body></html>");
            mail.setStatus(status);
            mail.setAttempts(attempts);
            mail.setSendDate(new Date(System.currentTimeMillis() + 24L * 60 * 60 * 1000L));
            mail.addRecipient(new ScheduledMailRecipient(RecipientType.TO, to));
            mail.addRecipient(new ScheduledMailRecipient(RecipientType.CC, cc));
            em.persist(mail);
            em.flush();
            return mail.getId();
        });
        committedMailIds.add(id);
        return id;
    }

    private ScheduledMail reload(Integer id) {
        return Tx.requiringNew().call(() -> em.find(ScheduledMail.class, id));
    }

    private static InternetAddress internet(String addr) {
        try {
            return new InternetAddress(addr);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void sendQuietly(Mail mail) {
        try {
            mailService.sendMail(mail);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
