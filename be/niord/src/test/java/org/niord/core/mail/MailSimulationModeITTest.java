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

import org.springframework.transaction.annotation.Transactional;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.mail.Message;
import jakarta.mail.internet.InternetAddress;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.mail.ScheduledMail.Status;
import org.niord.core.mail.ScheduledMailRecipient.RecipientType;
import org.niord.test.ITTest;
import org.niord.test.MailHog;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-level confirmation of the <em>default</em> {@code MailService} behaviour: with
 * {@code mailValidRecipients = "LOG"} (the production default, and the configuration the rest of the
 * integration suite runs under) a "send" is only <em>simulated</em> — the scheduled mail is marked
 * {@code SENT}, but nothing is handed to {@code Transport.send(...)} and nothing reaches the SMTP
 * server.
 *
 * <p>This class deliberately uses only {@link NiordContainersResource} (no {@code mailValidRecipients}
 * override), so it runs in the same default {@code LOG} mode as the production default and asserts the
 * <em>absence</em> of any SMTP traffic via {@link MailHog}. It is the complement of
 * {@code MailDeliveryITTest} (real send) and turns the previously implicit "LOG means no wire traffic"
 * assumption into an explicit, enforced contract.
 *
 * <p>{@code sendScheduledMail} is {@code @Transactional(REQUIRES_NEW)} (it commits), so its fixture is
 * committed via {@link Tx#requiringNew()} and removed in {@link #afterEach()}. Mailhog
 * is cleared before each test, and unique subjects keep the assertions independent.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MailSimulationModeITTest extends ITTest {

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
        return "IT-SIM-" + tag + "-" + System.nanoTime();
    }

    // ------------------------------------------------------------------------------------------- //
    // sendScheduledMail: SENT in the DB, but never delivered over SMTP
    // ------------------------------------------------------------------------------------------- //

    @Test
    void sendScheduledMailIsSimulatedAndNeverReachesTheWire() {
        String subject = unique("scheduled");

        Integer id = Tx.requiringNew().call(() -> {
            ScheduledMail mail = new ScheduledMail();
            mail.setSubject(subject);
            try {
                mail.setHtmlContents("<html><body><p>simulated</p></body></html>");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            mail.setStatus(Status.PENDING);
            mail.addRecipient(new ScheduledMailRecipient(RecipientType.TO, "recipient@example.com"));
            em.persist(mail);
            em.flush();
            return mail.getId();
        });
        assertNotNull(id);
        committedMailIds.add(id);

        ScheduledMail returned = mailService.sendScheduledMail(id);
        assertNotNull(returned);
        assertEquals(Status.SENT, returned.getStatus(),
                "in simulation mode the mail is still marked SENT");

        ScheduledMail reloaded = Tx.requiringNew()
                .call(() -> em.find(ScheduledMail.class, id));
        assertNotNull(reloaded);
        assertEquals(Status.SENT, reloaded.getStatus());
        assertEquals(1, reloaded.getAttempts());
        assertNull(reloaded.getLastError());

        // The defining assertion: sendScheduledMail returns synchronously after marking SENT, and in
        // simulation mode Transport.send(...) is never invoked, so nothing reaches the SMTP server.
        assertTrue(MailHog.messagesWithSubject(subject).isEmpty(),
                "LOG/simulation mode must mark the mail SENT WITHOUT delivering it over SMTP");
    }

    // ------------------------------------------------------------------------------------------- //
    // sendMail(Mail): a fully valid mail is silently simulated, not delivered
    // ------------------------------------------------------------------------------------------- //

    @Test
    void sendMailObjectIsSimulatedAndNeverReachesTheWire() throws Exception {
        String subject = unique("mailobj");

        Mail mail = new Mail()
                .from(new InternetAddress("niord@e-navigation.net"))
                .subject(subject)
                .recipient(Message.RecipientType.TO, new InternetAddress("recipient@example.com"))
                .htmlText("<html><body><p>simulated</p></body></html>");

        // No exception: a valid mail in LOG mode is accepted and simulated.
        mailService.sendMail(mail);

        assertTrue(MailHog.messagesWithSubject(subject).isEmpty(),
                "a valid mail in LOG/simulation mode must not be delivered over SMTP");
    }
}
