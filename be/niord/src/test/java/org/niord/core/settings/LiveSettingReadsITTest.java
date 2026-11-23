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
package org.niord.core.settings;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.mail.Mail;
import org.niord.core.mail.MailService;
import org.niord.test.ITTest;
import org.niord.test.MailHog;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Settings that take effect at once when they are changed on the Settings page (SECOM-UI-1): the mail
 * recipients filter {@code mailValidRecipients} is read for every mail, so a sysadmin who narrows it in an incident
 * does not wait for a restart. Before, {@link MailService} read the value injected at start-up. The other live read,
 * {@code mailDeleteAfterDays}, is tested where its code is, in {@code ScheduledMailServiceITTest}. The settings held in
 * {@code @Setting} fields take effect on save too, by a refresh of the fields: {@code SettingRefreshITTest}.
 *
 * <p>The shared test application runs with the default {@code "LOG"}, which only simulates a send. A real send goes
 * to the test's MailHog. Every write commits, so each test puts the row back.
 */
class LiveSettingReadsITTest extends ITTest {

    private static final String VALID_RECIPIENTS = "mailValidRecipients";

    /** The one address the narrowed filter lets through; every other recipient is replaced by it */
    private static final String ALLOWED = "allowed@niord.test";
    private static final String STRANGER = "stranger@example.com";

    @Autowired
    SettingsService settingsService;

    @Autowired
    MailService mailService;

    @PersistenceContext
    EntityManager em;

    @Test
    void aNarrowedRecipientsFilterAppliesToTheNextMail() throws Exception {
        assertEquals("LOG", settingsService.getString(VALID_RECIPIENTS),
                "the shared test application simulates every send");
        Object original = rowValue(VALID_RECIPIENTS);
        String narrowed = "IT-LIVE-narrowed-" + System.nanoTime();
        String simulated = "IT-LIVE-simulated-" + System.nanoTime();
        try {
            settingsService.set(VALID_RECIPIENTS, ALLOWED);

            mailService.sendMail(mailTo(STRANGER, narrowed));

            MailHog.Message sent = MailHog.waitForOne(narrowed);
            assertNotNull(sent, "with the filter narrowed, the mail is sent at once, not simulated");
            List<String> envelope = sent.envelopeRecipients();
            assertEquals(List.of(ALLOWED), envelope, "and only to the allowed address");
            assertFalse(envelope.contains(STRANGER));
        } finally {
            settingsService.set(VALID_RECIPIENTS, original);
            settingsService.evictFromCache(VALID_RECIPIENTS);
        }

        // Put back, the next mail is simulated again. A real send has reached MailHog when sendMail returns
        mailService.sendMail(mailTo(STRANGER, simulated));
        assertTrue(MailHog.messagesWithSubject(simulated).isEmpty(), "the restored LOG only simulates the send");
    }

    @Test
    void anEmptiedRecipientsFilterSendsNothing() {
        Object original = rowValue(VALID_RECIPIENTS);
        String subject = "IT-LIVE-empty-" + System.nanoTime();
        try {
            settingsService.set(VALID_RECIPIENTS, "");

            MessagingException refused = assertThrows(MessagingException.class,
                    () -> mailService.sendMail(mailTo(STRANGER, subject)));
            assertTrue(refused.getMessage().contains(VALID_RECIPIENTS), refused.getMessage());
            assertTrue(MailHog.messagesWithSubject(subject).isEmpty(), "nothing reached the mail server");
        } finally {
            settingsService.set(VALID_RECIPIENTS, original);
            settingsService.evictFromCache(VALID_RECIPIENTS);
        }
    }

    private static Mail mailTo(String recipient, String subject) throws Exception {
        return new Mail()
                .from(new InternetAddress(ALLOWED))
                .subject(subject)
                .recipient(Message.RecipientType.TO, new InternetAddress(recipient))
                .htmlText("<html><body><p>live</p></body></html>");
    }

    private Object rowValue(String key) {
        return Tx.requiringNew().call(() -> em.find(Setting.class, key).getValue());
    }
}
