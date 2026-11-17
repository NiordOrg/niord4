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

package org.niord.core.message;

import org.springframework.stereotype.Service;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.NiordApp;
import org.niord.core.mail.Mail.MailRecipient;
import org.niord.core.mail.MailHtml;
import org.niord.core.mail.ScheduledMail;
import org.niord.core.mail.ScheduledMailRecipient;
import org.niord.core.mail.ScheduledMailRecipient.RecipientType;
import org.niord.core.script.FmTemplateService;
import org.niord.core.service.BaseService;
import org.niord.core.user.User;
import org.niord.core.user.UserService;
import org.niord.core.web.NiordWebException;
import org.niord.model.message.MessageVo;
import org.slf4j.Logger;

import jakarta.mail.internet.InternetAddress;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedList;
import java.util.List;

/**
 * Can be used for sending message-related emails
 */
@Service
public class MessageMailService extends BaseService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageMailService.class);

    private final FmTemplateService templateService;

    private final UserService userService;

    private final NiordApp app;

    MessageMailService(FmTemplateService templateService, UserService userService, NiordApp app) {
        this.templateService = templateService;
        this.userService = userService;
        this.app = app;
    }

    /**
     * Generates an email from the given mail template.
     * <p>
     * The texts of the template's messages are cleaned in place with {@link MailHtml#cleanMessage}, so pass
     * message value objects made for the mail.
     * @param mailTemplate the message mail template
     */
    @Transactional
    public void sendMessageMail(MessageMailTemplate mailTemplate) throws Exception {

        if (mailTemplate.getRecipients().isEmpty()) {
            throw new Exception("No mail recipient specified");
        }

        long t0 = System.currentTimeMillis();
        String mailSubject = StringUtils.defaultIfBlank(mailTemplate.getSubject(), "No subject");
        String mailMessage = StringUtils.defaultIfBlank(mailTemplate.getMailMessage(), "");

        User user = userService.currentUser();

        // This service normally sits behind @RolesAllowed, so the current user is non-null; but if a null
        // user reaches here (anonymous, no ticket) we reject the caller as unauthenticated (HTTP 401)
        // rather than dereference null below (user.getName() / user.computeInternetAddress()), which
        // surfaced an opaque HTTP 500 via a NullPointerException (MSGSVC1).
        if (user == null) {
            throw new NiordWebException("No current user", 401);
        }

        // The template prints the texts of the messages as HTML, unescaped, so they are cleaned: the message's
        // formatting stays, relative links are made absolute, and nothing active or foreign goes in. The
        // stored messages are untouched.
        if (mailTemplate.getMessages() != null) {
            mailTemplate.getMessages().forEach(message -> MailHtml.cleanMessage(message, app.getBaseUri()));
        }

        for (MailRecipient recipient : mailTemplate.getRecipients()) {
            try {

                String mailTo = recipient.getAddress().toString();
                if (recipient.getAddress() instanceof InternetAddress) {
                    mailTo = StringUtils.defaultIfBlank(((InternetAddress)recipient.getAddress()).getPersonal(), mailTo);
                }

                // The template prints both names into the mail as HTML, unescaped (the greeting and the
                // regards). The recipient's personal name is what the sender typed, in the mail dialog or in a
                // comment's addresses, so both are escaped here: that covers every copy of the template,
                // including one stored in the database.
                String mailContents =
                        templateService.newFmTemplateBuilder()
                                .templatePath(mailTemplate.getTemplatePath())
                                .data("messages", mailTemplate.getMessages())
                                .data("areaHeadings", false)
                                .data("mailTo", StringEscapeUtils.escapeHtml4(mailTo))
                                .data("mailMessage", mailMessage)
                                .data("mailSender", StringEscapeUtils.escapeHtml4(user.getName()))
                                .data("mapThumbnails", false)
                                .data("frontPage", false)
                                .data("link", true)
                                .dictionaryNames("message", "mail")
                                .language(mailTemplate.getLanguage())
                                .process();

                // A message-support.ftl stored in the database may build hash-routed message links
                mailContents = MessageLinks.rewriteHashLinks(mailContents, app.getBaseUri(), app.getMessageLinkBaseUri());

                ScheduledMail scheduledMail = new ScheduledMail();
                scheduledMail.setSubject(mailSubject);
                scheduledMail.setHtmlContents(mailContents);
                scheduledMail.addRecipient(new ScheduledMailRecipient(RecipientType.TO, recipient.getAddress().toString()));
                scheduledMail.setSender(user.computeInternetAddress());
                saveEntity(scheduledMail);

                String msg = "Mail scheduled to " + mailTo + " in " + (System.currentTimeMillis() - t0) + " ms";
                log.info(msg);
            } catch (Exception e) {
                log.error("Error generating PDF for messages", e);
                throw e;
            }
        }
    }

    /**
     * Helper class that encapsulates a message list-based mail
     */
    public static class MessageMailTemplate {

        String language;
        List<MailRecipient> recipients = new LinkedList<>();
        String subject;
        String mailMessage;
        List<MessageVo> messages;
        String templatePath;

        public String getLanguage() {
            return language;
        }

        public MessageMailTemplate language(String language) {
            this.language = language;
            return this;
        }

        public List<MailRecipient> getRecipients() {
            return recipients;
        }

        public MessageMailTemplate recipients(List<MailRecipient> recipients) {
            this.recipients = recipients;
            return this;
        }

        public String getSubject() {
            return subject;
        }

        public MessageMailTemplate subject(String subject) {
            this.subject = subject;
            return this;
        }

        public String getMailMessage() {
            return mailMessage;
        }

        public MessageMailTemplate mailMessage(String mailMessage) {
            this.mailMessage = mailMessage;
            return this;
        }

        public List<MessageVo> getMessages() {
            return messages;
        }

        public MessageMailTemplate messages(List<MessageVo> messages) {
            this.messages = messages;
            return this;
        }

        public String getTemplatePath() {
            return templatePath;
        }

        public MessageMailTemplate templatePath(String templatePath) {
            this.templatePath = templatePath;
            return this;
        }
    }

}
