/*
 * Copyright 2016 Danish Maritime Authority.
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

import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.NiordApp;
import org.niord.core.service.BaseService;
import org.niord.core.settings.SettingsService;
import org.niord.core.settings.annotation.Setting;
import org.slf4j.Logger;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.persistence.LockModeType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Interface for sending emails
 */
@Service
@SuppressWarnings("unused")
public class MailService extends BaseService {

    private final Session mailSession;
    
        @Setting(value = "mailSender", defaultValue = "niord@e-navigation.net",
            description = "The sender e-mail address. Read at start-up.")
    String mailSender;

    static final String VALID_RECIPIENTS_KEY = "mailValidRecipients";

    /**
     * The field only declares the setting. {@link #sendMail(Mail)} reads it through {@link SettingsService} for
     * every mail, so a change on the Settings page applies to the next mail without a restart.
     */
        @Setting(value = "mailValidRecipients", defaultValue = "LOG",
            description = "Comma-separated list of valid mail recipients, or 'ALL' for all recipients, or 'LOG' for simulation")
    String validRecipients;

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MailService.class);

    private final MailAttachmentCache mailAttachmentCache;

    private final NiordApp app;

    private final SettingsService settingsService;

    MailService(Session mailSession, MailAttachmentCache mailAttachmentCache, NiordApp app,
                SettingsService settingsService) {
        this.mailSession = mailSession;
        this.mailAttachmentCache = mailAttachmentCache;
        this.app = app;
        this.settingsService = settingsService;
    }

    /**
     * Sends an email
     * @param content the HTML content
     * @param title the title of the email
     * @param recipients the list of recipients
     */
    public void sendMail(String content, String title, String... recipients) throws Exception {
        try {
            String baseUri = app.getBaseUri();

            Mail mail = HtmlMail.fromHtml(content, baseUri, HtmlMail.StyleHandling.INLINE_STYLES, true)
                    .sender(new InternetAddress(mailSender))
                    .from(new InternetAddress(mailSender))
                    .subject(title);

            for (String recipient : recipients) {
                mail.recipient(Message.RecipientType.TO, new InternetAddress(recipient));
            }

            sendMail(mail);

        } catch (Exception e) {
            log.error("error sending email: " + title, e);
            throw e;
        }
    }

    /**
     * Sends the given mail synchronously
     * @param mail the mail to send
     */
    public void sendMail(Mail mail) throws MessagingException {
        deliver(mail);
    }

    /**
     * Sends the given mail synchronously, under the mail filter ({@code mailValidRecipients}) as it is now
     * @param mail the mail to send
     * @return true when the mail reached all of its own recipients: it was handed to the mail server and the filter
     *         redirected none of them. False when the filter kept it from its recipients: the filter is LOG, so
     *         nothing left the server, or it is a list of addresses that does not name every recipient, so their
     *         copies went to the first listed address ({@link ValidMailRecipients#redirects})
     */
    boolean deliver(Mail mail) throws MessagingException {
        try {
            long t0 = System.currentTimeMillis();

            if (mail.getSender() == null) {
                mail.setSender(new InternetAddress(mailSender));
            }
            if (mail.getFrom() == null || mail.getFrom().isEmpty()) {
                mail.from(new InternetAddress(mailSender));
            }

            // Validate the mail recipients against the setting as it is now. An emptied setting names no valid
            // recipient, so nothing is sent
            String recipientsSetting = settingsService.getString(VALID_RECIPIENTS_KEY);
            if (StringUtils.isBlank(recipientsSetting)) {
                throw new MessagingException("No valid recipient: the setting " + VALID_RECIPIENTS_KEY + " is empty");
            }
            ValidMailRecipients recipientHandling = new ValidMailRecipients(recipientsSetting);
            List<Address> redirected = mail.filterRecipients(recipientHandling);

            // Check if any recipients are defined
            if (mail.getRecipients().isEmpty()) {
                throw new MessagingException("No valid recipient");
            }

            // Check if we only simulate sending emails
            boolean simulated = recipientHandling.simulate();
            if (simulated) {

                // Make the simulation realistic - sleep 0.5 - 1 seconds
                try {
                    Thread.sleep(500L + (long)(500.0 * Math.random()));
                } catch (InterruptedException ignored) {
                }

            } else {
                // Send the message
                log.debug("Composing mail");
                Message message = mail.compose(mailSession, mailAttachmentCache.getCache());
                log.debug("Sending...");
                Transport.send(message);

            }

            String recipients = mail.getRecipients().stream()
                    .filter(r -> r.getAddress() != null)
                    .map(r -> r.getAddress().toString())
                    .collect(Collectors.joining(", "));
            if (simulated) {
                log.info("Held, not sent (" + VALID_RECIPIENTS_KEY + " is LOG): mail to " + recipients + " in "
                        + (System.currentTimeMillis() - t0) + " ms");
            } else if (!redirected.isEmpty()) {
                log.info("Held, sent only to listed addresses (" + VALID_RECIPIENTS_KEY + " redirected "
                        + redirected.stream().map(Address::toString).collect(Collectors.joining(", "))
                        + " to the first listed address): mail to " + recipients + " in "
                        + (System.currentTimeMillis() - t0) + " ms");
            } else {
                log.info("Sent email to " + recipients + " in " + (System.currentTimeMillis() - t0) + " ms");
            }
            return !simulated && redirected.isEmpty();

        } catch (MessagingException e) {
            log.error("Failed sending mail for " + mail.getFrom(), e);
            throw e;
        }
    }

    /**
     * Sends the scheduled mail with the given ID and updates the status of the scheduled mail entity
     * @param scheduledMailId the ID of the scheduled mail to send
     * @return the updated mail entity
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ScheduledMail sendScheduledMail(Integer scheduledMailId) {
        try {
            // Claim the mail with a pessimistic write lock (SELECT ... FOR UPDATE). This turns the
            // "still pending" re-check below into a cluster-safe claim: when several schedulers race
            // (e.g. more than one application replica), the losers block until the winner commits the
            // status change and then re-read the latest committed row as SENT, so each mail is
            // delivered exactly once.
            ScheduledMail scheduledMail = em.find(ScheduledMail.class, scheduledMailId, LockModeType.PESSIMISTIC_WRITE);

            // Double-check that the scheduled mail is still pending
            if (scheduledMail != null && scheduledMail.getStatus() == ScheduledMail.Status.PENDING) {

                try {
                    Mail mail = scheduledMail.toMail(app.getBaseUri(), HtmlMail.StyleHandling.INLINE_STYLES, false);

                    // If undefined, set reply-to to the first to-recipient
                    if (mail.getReplyTo().isEmpty()) {
                        mail.getRecipients().stream()
                                .filter(r -> r.getType() == Message.RecipientType.TO)
                                .limit(1)
                                .forEach(r -> mail.replyTo(r.getAddress()));
                    }

                    // Send the mail, and register that it was sent, or held: the mail filter kept it from its
                    // recipients (LOG, or a list that redirected one of them)
                    if (deliver(mail)) {
                        scheduledMail.registerMailSent();
                    } else {
                        scheduledMail.registerMailHeld();
                    }

                } catch (Exception e) {

                    // Register that the mail failed being sent
                    scheduledMail.registerMailErrorAttempt(e.getMessage());
                    log.error("Error sending mail " + scheduledMailId + ", attempt " + scheduledMail.getAttempts(), e);
                }
                saveEntity(scheduledMail);
            }

            return scheduledMail;

        } catch (Exception e) {
            log.error("Error finding scheduled mail " + scheduledMailId, e);
            return null;
        }
    }

}
