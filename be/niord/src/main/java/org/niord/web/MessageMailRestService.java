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

package org.niord.web;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.NiordApp;
import org.niord.core.domain.DomainService;
import org.niord.core.mail.Mail.MailRecipient;
import org.niord.core.message.MessageMailService;
import org.niord.core.message.MessageMailService.MessageMailTemplate;
import org.niord.core.message.MessageSearchParams;
import org.niord.core.user.Roles;
import org.niord.model.message.MessageVo;
import org.niord.model.search.PagedSearchResultVo;
import org.slf4j.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.security.RolesAllowed;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * REST interface for generating message e-mails.
 */
@RestController
@RequestMapping("/rest/message-mail")
@Transactional
@SuppressWarnings("unused")
public class MessageMailRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageMailRestService.class);

    private final NiordApp app;

    private final MessageSearchRestService messageSearchRestService;

    private final DomainService domainService;

    private final MessageMailService messageMailService;

    MessageMailRestService(NiordApp app, MessageSearchRestService messageSearchRestService, DomainService domainService, MessageMailService messageMailService) {
        this.app = app;
        this.messageSearchRestService = messageSearchRestService;
        this.domainService = domainService;
        this.messageMailService = messageMailService;
    }

    /**
     * The body of a message mail: the recipients, the subject and the text.
     *
     * @param mailTo      the recipients' addresses: at least one, and each one a mail address
     * @param mailSubject the subject; "No subject" when blank
     * @param mailMessage the text written above the messages, as plain text
     */
    public record MessageMailRequest(List<String> mailTo, String mailSubject, String mailMessage) {
    }

    /**
     * Generates and sends an e-mail for the message search result.
     *
     * The recipients, the subject and the text are the JSON body, so neither their length nor their content
     * reaches the request line (MAIL12). The search parameters are in the query, as for the message search, and
     * {@code lang} names the language the mail is written in.
     *
     * @see #send(List, String, String, HttpServletRequest)
     */
    @PostMapping(value = "/send", consumes = "application/json", produces = "text/plain")
    @RolesAllowed(Roles.EDITOR)
    public String sendMessageMailFromBody(@RequestBody MessageMailRequest body, HttpServletRequest request)
            throws Exception {
        return send(body.mailTo(), body.mailSubject(), body.mailMessage(), request);
    }

    /**
     * Generates and sends an e-mail for the message search result, with the recipients ({@code mailTo}), the
     * subject ({@code mailSubject}) and the text ({@code mailMessage}) in the query.
     *
     * Deprecated: it stays for one release, for a caller that has not moved to {@code POST
     * /rest/message-mail/send}, and each call logs a line that says so. The query puts the text and the
     * recipients in the request line, which a proxy logs and which a long text makes too long (MAIL12).
     *
     * @see #send(List, String, String, HttpServletRequest)
     */
    @GetMapping(value = "/send", produces = "text/plain")
    @RolesAllowed(Roles.EDITOR)
    public String sendMessageMail(HttpServletRequest request) throws Exception {
        log.warn("GET /rest/message-mail/send is deprecated and goes in the next release: "
                + "send the message mail with POST /rest/message-mail/send");
        String[] mailAddresses = request.getParameterValues("mailTo");
        return send(mailAddresses == null ? null : Arrays.asList(mailAddresses),
                request.getParameter("mailSubject"), request.getParameter("mailMessage"), request);
    }

    /**
     * Sends the message mail of both endpoints.
     *
     * The mail is written in the language the request asks for with {@code lang}, and in the default language
     * when it asks for none or for one the system does not have: the mail template prints in one language.
     * Every address is read before any mail is made, so a malformed one is the caller's error (400, naming the
     * address) and no mail is scheduled (MAIL8). The search parameters are read before the mail is made, so a
     * malformed one is the caller's error (400) and is not logged as a failure of the server.
     */
    private String send(List<String> mailAddresses, String mailSubject, String mailMessage,
                        HttpServletRequest request) throws Exception {

        long t0 = System.currentTimeMillis();

        List<InternetAddress> recipients = recipients(mailAddresses);
        mailSubject = StringUtils.defaultIfBlank(mailSubject, "No subject");
        mailMessage = StringEscapeUtils.escapeHtml4(StringUtils.defaultIfBlank(mailMessage, "")).replace("\n", "<br>");

        // Perform a search for at most 1000 messages, in the language the mail is written in
        MessageSearchParams params = MessageSearchParams.instantiate(domainService.currentDomain(), request);
        String language = app.getLanguage(params.getLanguage());
        params.language(language).maxSize(1000).page(0);

        try {
            PagedSearchResultVo<MessageVo> result = messageSearchRestService.searchMessages(params);

            // Send the e-mails
            StringBuilder str = new StringBuilder();
            for (int i = 0; i < recipients.size(); i++) {

                String mailTo = mailAddresses.get(i);
                MailRecipient recipient = new MailRecipient(RecipientType.TO, recipients.get(i));

                MessageMailTemplate mailTemplate = new MessageMailTemplate()
                        .subject(mailSubject)
                        .mailMessage(mailMessage)
                        .messages(result.getData())
                        .recipients(Collections.singletonList(recipient))
                        .language(language)
                        .templatePath("/templates/messages/message-mail.ftl");

                messageMailService.sendMessageMail(mailTemplate);

                String msg = "Message mail scheduled to " + mailTo + " in " + (System.currentTimeMillis() - t0) + " ms";
                log.info(msg);
                str.append(msg).append("\n");

            }
            return str.toString();

        } catch (Exception e) {
            log.error("Error sending e-mail for messages", e);
            throw e;
        }
    }

    /**
     * Reads every address before any mail is made. No address at all, a blank one, and one that is not a single
     * mail address with a domain (a group included) are refused with 400; the detail names the address.
     */
    static List<InternetAddress> recipients(List<String> mailAddresses) {
        if (mailAddresses == null || mailAddresses.isEmpty()) {
            throw new NiordWebException("A message mail needs at least one address", 400);
        }
        List<InternetAddress> recipients = new ArrayList<>(mailAddresses.size());
        for (String mailTo : mailAddresses) {
            if (StringUtils.isBlank(mailTo)) {
                throw new NiordWebException("A message mail cannot be sent to a blank address", 400);
            }
            InternetAddress address;
            try {
                address = new InternetAddress(mailTo, true);
            } catch (AddressException e) {
                address = null;
            }
            if (address == null || address.isGroup()) {
                throw new NiordWebException("'" + mailTo + "' is not a mail address", 400);
            }
            recipients.add(address);
        }
        return recipients;
    }

}
