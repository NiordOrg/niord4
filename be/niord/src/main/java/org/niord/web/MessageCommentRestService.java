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

import org.apache.commons.lang3.StringUtils;
import org.niord.core.NiordApp;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainService;
import org.niord.core.mail.Mail.MailRecipient;
import org.niord.core.mail.MailHtml;
import org.niord.core.message.*;
import org.niord.core.message.vo.CommentVo;
import org.niord.core.user.Roles;
import org.niord.core.user.User;
import org.niord.core.user.UserService;
import org.niord.model.message.MessageVo;
import org.slf4j.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.transaction.annotation.Transactional;
import org.niord.core.web.NiordWebException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * REST interface for managing message comments.
 */
@RestController
@RequestMapping("/rest/messages")
@Transactional
@PermitAll
@SuppressWarnings("unused")
public class MessageCommentRestService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageCommentRestService.class);

    /** The most e-mail addresses, each a single mailbox, one comment can be sent to */
    static final int MAX_COMMENT_MAIL_RECIPIENTS = 20;

    private final NiordApp app;

    private final MessageService messageService;

    private final UserService userService;

    private final CommentService commentService;

    private final MessageMailService messageMailService;

    private final DictionaryService dictionaryService;

    private final DomainService domainService;

    MessageCommentRestService(NiordApp app, MessageService messageService, UserService userService, CommentService commentService, MessageMailService messageMailService, DictionaryService dictionaryService, DomainService domainService) {
        this.app = app;
        this.messageService = messageService;
        this.userService = userService;
        this.commentService = commentService;
        this.messageMailService = messageMailService;
        this.dictionaryService = dictionaryService;
        this.domainService = domainService;
    }

    /**
     * Returns the comments for the given message ID
     * @param messageId the message ID or message series ID
     * @return the message comments
     */
    @GetMapping(value = "/message/{messageId}/comments", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.USER)
    public List<CommentVo> getComments(@PathVariable("messageId") String messageId) {

        // Get the message id
        Message message = messageService.resolveMessage(messageId);
        if (message == null) {
            return Collections.emptyList();
        }

        // Comments are internal to the message's own domain, whatever the message's status.
        checkCommentDomainAccess(message);

        User user = userService.currentUser();
        return commentService.getComments(message.getUid()).stream()
                .map(c -> toReadVo(c, user))
                .collect(Collectors.toList());
    }

    /**
     * Creates a comments for the given message ID
     * @param messageId the message ID or message series ID
     * @param comment the template comment to create
     * @return the persisted comment
     */
    @PostMapping(value = "/message/{messageId}/comment",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.USER)
    public CommentVo createComment(
            @PathVariable("messageId") String messageId,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestBody CommentVo comment) {

        // Get the message by message id
        Message message = messageService.resolveMessage(messageId);
        if (message == null) {
            throw new IllegalArgumentException("Invalid message ID " + messageId);
        }

        // Commenting - and mailing the message with the comment - is for the message's own domain
        checkCommentDomainAccess(message);
        checkCommentText(comment);
        List<InternetAddress> mailboxes = commentMailboxes(comment.getEmailAddresses());

        comment =  commentService
                .createComment(new Comment(comment, message, userService.currentUser()))
                .toVo();

        // Check if we need to send the comment as e-mail
        checkSendCommentEmails(comment, mailboxes, message, lang);

        return comment;
    }

    /**
     * Updates a comments for the given message ID
     * @param messageId the message ID or message series ID
     * @param commentId the message ID or message series ID
     * @param comment the template comment to updated
     * @return the message comments
     */
    @PutMapping(value = "/message/{messageId}/comment/{commentId}",
            consumes = "application/json;charset=UTF-8",
            produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.USER)
    public CommentVo updateComment(
            @PathVariable("messageId") String messageId,
            @PathVariable("commentId") Integer commentId,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestBody CommentVo comment) {
        // Enforce REST PUT pattern
        if (!Objects.equals(commentId, comment.getId())) {
            throw new NiordWebException(400);
        }
        // Get the message by message id
        Message message = messageService.resolveMessage(messageId);
        Comment original = (message == null) ? null : message.getComments().stream()
                .filter(c -> c.getId().equals(commentId))
                .findFirst()
                .orElse(null);
        if (original == null) {
            throw new IllegalArgumentException("Invalid message or comment ID " + messageId);
        }

        // The domain gate comes first: the admin role below is held per domain, so an admin of another
        // domain must not pass as one here.
        checkCommentDomainAccess(message);

        // SECAUD-5: only the comment's own author (or an admin) may edit a comment. Without this check any
        // USER who can resolve the message UID could overwrite another user's comment (and, since editing
        // clears the acknowledge state, silently reset everyone's acknowledgement). This is the same
        // own-comment-or-admin rule the editor applies before it offers editing.
        User user = userService.currentUser();
        if (!isCommentAuthor(original, user) && !userService.isCallerInRole(Roles.ADMIN)) {
            throw new NiordWebException(403);
        }
        checkCommentText(comment);
        List<InternetAddress> mailboxes = commentMailboxes(comment.getEmailAddresses());

        comment = commentService
                .updateComment(new Comment(comment, message, user))
                .toVo();

        // Check if we need to send the comment as e-mail
        checkSendCommentEmails(comment, mailboxes, message, lang);

        return comment;
    }

    /**
     * Acknowledges a comments for the given message ID
     * @param messageId the message ID or message series ID
     * @param commentId the message ID or message series ID
     * @return the message comment
     */
    @PutMapping(value = "/message/{messageId}/comment/{commentId}/ack", produces = "application/json;charset=UTF-8")
    @RolesAllowed(Roles.USER)
    public CommentVo acknowledgeComment(@PathVariable("messageId") String messageId, @PathVariable("commentId") Integer commentId) {
        // Get the message by message id
        Message message = messageService.resolveMessage(messageId);
        if (message == null || message.getComments().stream().noneMatch(c -> c.getId().equals(commentId))) {
            throw new IllegalArgumentException("Invalid message or comment ID " + messageId);
        }

        // SECAUD-5: acknowledging another user's comment is a legitimate workflow action, but only within
        // the message's own domain. Without this check any USER who can resolve a UID could acknowledge a
        // comment on a cross-domain message. Mirrors the domain gate of MessageRestService.checkMessageEditingAccess.
        checkCommentDomainAccess(message);

        User user = userService.currentUser();
        return toReadVo(commentService.acknowledgeComment(user, commentId), user);
    }

    /** Returns whether the given user is the author of the comment. */
    private boolean isCommentAuthor(Comment comment, User user) {
        return user != null && comment.getUser() != null
                && Objects.equals(comment.getUser().getId(), user.getId());
    }

    /**
     * Converts a comment for a caller who reads it. Its e-mail recipients are shown to its author and to an
     * admin only - the two who may edit it, and the edit form is the only place the recipients appear.
     * @param comment the comment
     * @param user the caller
     * @return the comment as a value object
     */
    private CommentVo toReadVo(Comment comment, User user) {
        CommentVo vo = comment.toVo(user);
        if (!isCommentAuthor(comment, user) && !userService.isCallerInRole(Roles.ADMIN)) {
            vo.setEmailAddresses(null);
        }
        return vo;
    }

    /**
     * Checks that the caller's current domain owns the message's message series.
     * Mirrors the domain gate of {@code MessageRestService.checkMessageEditingAccess}.
     * @param message the message whose domain the caller must belong to
     */
    private void checkCommentDomainAccess(Message message) {
        Domain domain = domainService.currentDomain();
        if (domain == null || !domain.containsMessageSeries(message.getMessageSeries())) {
            throw new NiordWebException(403);
        }
    }

    /**
     * Refuses a comment without a text, or with a text of blanks only, with a 400, before anything is saved or
     * mailed. Such a comment says nothing, yet it would wait in the message's comment list as unacknowledged.
     * Comments already stored without a text are left as they are and can still be acknowledged.
     * @param comment the comment from the request body
     */
    private static void checkCommentText(CommentVo comment) {
        if (StringUtils.isBlank(comment.getComment())) {
            throw new NiordWebException("A comment must have a text", 400);
        }
    }

    /**
     * Parses the e-mail addresses of a comment into the mailboxes it is mailed to - each mailbox gets a mail
     * of its own with the full message attached - and refuses them with a 400, before the comment is saved,
     * when the list holds more than {@link #MAX_COMMENT_MAIL_RECIPIENTS} entries, or an entry that is a group
     * or more than one address.
     * <p>
     * The cap is on mailboxes, not strings: an RFC 822 group ({@code name: a@x, b@y;}) is one string, and the
     * mail server sends to every mailbox in it. Each entry is parsed strictly, as the mail is addressed; a
     * group, or an entry of more than one address, is refused whatever its size. Every entry that is kept is
     * one mailbox, so the mail goes to at most {@link #MAX_COMMENT_MAIL_RECIPIENTS} mailboxes. An entry that
     * is not an address at all is left out of the mail, as before.
     * @param emailAddresses the e-mail addresses from the request body, or null
     * @return the mailboxes to mail the comment to, in the order given
     */
    static List<InternetAddress> commentMailboxes(List<String> emailAddresses) {
        if (emailAddresses == null || emailAddresses.isEmpty()) {
            return Collections.emptyList();
        }
        if (emailAddresses.size() > MAX_COMMENT_MAIL_RECIPIENTS) {
            throw new NiordWebException("A comment can be mailed to at most "
                    + MAX_COMMENT_MAIL_RECIPIENTS + " addresses", 400);
        }

        List<InternetAddress> mailboxes = new ArrayList<>();
        for (String email : emailAddresses) {
            InternetAddress[] parsed;
            try {
                parsed = InternetAddress.parse(StringUtils.defaultString(email), true);
            } catch (AddressException e) {
                log.warn("Skipping comment e-mail for address " + email + ": " + e.getMessage());
                continue;
            }
            if (parsed.length > 1 || Arrays.stream(parsed).anyMatch(InternetAddress::isGroup)) {
                throw new NiordWebException("A comment can be mailed to single addresses only, "
                        + "not to a group or a list of addresses", 400);
            }
            mailboxes.addAll(Arrays.asList(parsed));
        }
        return mailboxes;
    }

    /**
     * Mails the comment to the given mailboxes, if any
     * @param comment the comment
     * @param mailboxes the mailboxes from {@link #commentMailboxes(List)}
     * @param message the message associated with the comment
     */
    private void checkSendCommentEmails(CommentVo comment, List<InternetAddress> mailboxes, Message message, String language) {
        if (!mailboxes.isEmpty()) {

            try {
                String lang = app.getLanguage(language);

                List<MailRecipient> recipients = mailboxes.stream()
                        .map(mailbox -> new MailRecipient(RecipientType.TO, mailbox))
                        .collect(Collectors.toList());

                MessageVo messageVo = message.toVo(MessageVo.class, Message.MESSAGE_DETAILS_FILTER.lang(lang));

                String subject = dictionaryService.value("mail", lang, "mail.comment.subject")
                        + " " + comment.getUser();

                // The mail template prints the comment as HTML, unescaped, so only harmless markup goes in:
                // the formatting of the comment editor, with relative links made absolute, and no classes or
                // images. The stored comment is untouched; only the mail body is cleaned. MessageMailService
                // cleans the message, which keeps its own formatting.
                String mailMessage = MailHtml.cleanComment(comment.getComment(), app.getBaseUri());

                // Send the e-mail
                MessageMailService.MessageMailTemplate mailTemplate = new MessageMailService.MessageMailTemplate()
                        .subject(subject)
                        .mailMessage(mailMessage)
                        .messages(Collections.singletonList(messageVo))
                        .recipients(recipients)
                        .language(lang)
                        .templatePath("/templates/messages/message-mail.ftl");
                messageMailService.sendMessageMail(mailTemplate);

            } catch (Exception e) {
                log.error("Failed sending comment e-mail to users " + comment.getEmailAddresses()
                        + ": " + e.getMessage());
            }

        }
    }
}
