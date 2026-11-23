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

package org.niord.core.mailinglist;

import org.springframework.stereotype.Service;

import freemarker.template.TemplateException;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.NiordApp;
import org.niord.core.dictionary.DictionaryService;
import org.niord.core.mail.IMailable;
import org.niord.core.mail.MailHtml;
import org.niord.core.mail.ScheduledMail;
import org.niord.core.mail.ScheduledMailRecipient;
import org.niord.core.mailinglist.vo.MailingListReportVo;
import org.niord.core.message.IndexIncompleteNotice;
import org.niord.core.message.Message;
import org.niord.core.message.MessageLuceneIndex;
import org.niord.core.message.MessageQueryService;
import org.niord.core.message.MessageSearchParams;
import org.niord.core.message.MessageSearchService;
import org.niord.core.message.MessageSeriesService;
import org.niord.core.message.MessageTokenExpander;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.model.BaseEntity;
import org.niord.core.model.DescEntity;
import org.niord.core.script.FmTemplateService;
import org.niord.core.script.JsResourceService;
import org.niord.core.script.ScriptResource;
import org.niord.core.service.BaseService;
import org.niord.core.user.Contact;
import org.niord.core.user.User;
import org.niord.core.util.TimeUtils;
import org.niord.model.search.PagedSearchResultVo;
import org.slf4j.Logger;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.util.HtmlUtils;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

import static org.niord.core.mail.ScheduledMailRecipient.RecipientType.TO;
import static org.niord.core.script.ScriptResource.Type.FM;

/**
 * Handles execution of mailing list triggers
 */
@Service
public class MailingListExecutionService extends BaseService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MailingListExecutionService.class);

    /** How long a failed scheduled trigger waits at most before it is tried again **/
    static final long FAILED_TRIGGER_RETRY_MILLIS = 60L * 60L * 1000L;

    /** A scheduled trigger that keeps failing leaves at most one record in the Mail Queue in this interval **/
    static final long FAILED_TRIGGER_RECORD_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L;

    /**
     * How long after the start, or after the index began to be built again while the server runs, a scheduled trigger
     * whose free-text search was answered by an incomplete index waits for the index, rather than mail what was found
     * so far
     **/
    static final Duration INCOMPLETE_INDEX_WAIT = Duration.ofMinutes(30);

    /**
     * How often a scheduled trigger that waits for the free-text index is tried again, so its mail follows soon after
     * the index is complete rather than at the end of {@link #INCOMPLETE_INDEX_WAIT}
     **/
    static final Duration INCOMPLETE_INDEX_RETRY = Duration.ofMinutes(2);

    /**
     * When this backend started, as near as this service knows: when it was made, during the start. The free-text
     * index is emptied at the start and filled again; {@link #INCOMPLETE_INDEX_WAIT} counts from here, or from a later
     * rebuild of the index ({@link MessageLuceneIndex#rebuildStartedAt()}).
     */
    volatile Instant startedAt = Instant.now();

    private final NiordApp app;

    private final MessageSearchService messageSearchService;

    private final MessageLuceneIndex messageLuceneIndex;

    private final MessageQueryService messageQueryService;

    private final FmTemplateService templateService;

    private final JsResourceService javaScriptService;

    private final DictionaryService dictionaryService;

    private final MessageSeriesService messageSeriesService;

    private final MailingListStatusChangeOutbox statusChangeOutbox;

    private final IndexIncompleteNotice indexIncompleteNotice;

    MailingListExecutionService(NiordApp app, MessageSearchService messageSearchService, MessageLuceneIndex messageLuceneIndex, MessageQueryService messageQueryService, FmTemplateService templateService, JsResourceService javaScriptService, DictionaryService dictionaryService, MessageSeriesService messageSeriesService, MailingListStatusChangeOutbox statusChangeOutbox, IndexIncompleteNotice indexIncompleteNotice) {
        this.app = app;
        this.messageSearchService = messageSearchService;
        this.messageLuceneIndex = messageLuceneIndex;
        this.messageQueryService = messageQueryService;
        this.templateService = templateService;
        this.javaScriptService = javaScriptService;
        this.dictionaryService = dictionaryService;
        this.messageSeriesService = messageSeriesService;
        this.statusChangeOutbox = statusChangeOutbox;
        this.indexIncompleteNotice = indexIncompleteNotice;
    }

    /***************************************/
    /** Status change triggers            **/
    /***************************************/

    /**
     * Executes the status change mailing list trigger for the given message, for a row of the mailing lists' outbox of
     * status changes: its mails are queued and the trigger is marked settled on the row
     * ({@link MailingListStatusChangeOutbox#settle}) in this one transaction, so the row knows exactly when the mails
     * were committed. Any failure rolls back both.
     *
     * @param triggerId the ID of the status change mailing list trigger to execute
     * @param messageUid the message UID to execute the trigger for
     * @param statusChangeId the id of the outbox row the trigger is executed for
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void executeStatusChangeTrigger(Integer triggerId, String messageUid, long statusChangeId) throws Exception {

        // Look up the trigger
        MailingListTrigger trigger = getByPrimaryKey(MailingListTrigger.class, triggerId);
        if (trigger == null || trigger.getType() != TriggerType.STATUS_CHANGE) {
            throw new IllegalArgumentException("Invalid trigger " + triggerId);
        }

        // Look up the message
        Message message = messageQueryService.findByUid(messageUid);
        if (message == null) {
            throw new IllegalArgumentException("Invalid message " + messageUid);
        }

        executeStatusChangeTrigger(trigger, message, true);

        // Last, in the transaction of the mails: the trigger is settled exactly when its mails are committed
        statusChangeOutbox.settle(statusChangeId, triggerId);
    }

    /**
     * Records that a status-change trigger failed for a message, as one mail in the Mail Queue in status ERROR, and
     * marks the trigger settled on the outbox row ({@link MailingListStatusChangeOutbox#settle}) in the same
     * transaction: a record that cannot be saved leaves the trigger owed, and the row is kept and run again.
     * <p>
     * The mail is never sent: only PENDING mails are sent, and nothing turns an ERROR mail PENDING. It holds the
     * recipients of the trigger's mailing list, a subject naming the list and the message, a short note naming the
     * trigger, the message and the cause, and the cause as its last error, so the Mail Queue shows the mail that was
     * due and why it was not made. The daily expiry of old mails removes it like any other mail. A trigger or list that
     * is gone gets no record, and is settled: there is nothing left to make.
     *
     * @param triggerId the ID of the status-change trigger that failed
     * @param messageUid the UID of the message the trigger was executed for
     * @param cause the failure
     * @param statusChangeId the id of the outbox row the trigger was executed for
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordStatusChangeTriggerFailure(Integer triggerId, String messageUid, Exception cause,
                                                 long statusChangeId) throws IOException {

        MailingListTrigger trigger = triggerId == null ? null : getByPrimaryKey(MailingListTrigger.class, triggerId);
        if (trigger == null || trigger.getMailingList() == null) {
            log.warn("No failure record for status-change trigger " + triggerId + ": the trigger or its list is gone");
            if (triggerId != null) {
                statusChangeOutbox.settle(statusChangeId, triggerId);
            }
            return;
        }
        MailingList mailingList = trigger.getMailingList();

        Message message = StringUtils.isBlank(messageUid) ? null : messageQueryService.findByUid(messageUid);
        String messageName = message != null && StringUtils.isNotBlank(message.getShortId())
                ? message.getShortId()
                : messageUid;
        String error = describeFailure(cause);

        saveFailureRecord(mailingList,
                "Not sent: mailing list " + mailingList.getMailingListId() + ", message " + messageName,
                "<p>The status-change trigger " + trigger.getId() + " of the mailing list "
                        + HtmlUtils.htmlEscape(mailingList.getMailingListId()) + " failed for the message "
                        + HtmlUtils.htmlEscape(String.valueOf(messageName))
                        + (message != null && !Objects.equals(messageName, messageUid)
                                ? " (" + HtmlUtils.htmlEscape(messageUid) + ")" : "")
                        + ", so its mail was not made and is not sent.</p>",
                error);

        // Last, in the transaction of the record: the trigger is settled exactly when its record is committed
        statusChangeOutbox.settle(statusChangeId, triggerId);
    }

    /**
     * Records that a scheduled trigger's run failed, as one mail in the Mail Queue in status ERROR, as
     * {@link #recordStatusChangeTriggerFailure} does for a status-change trigger: never sent, with the recipients of
     * the trigger's mailing list, a subject naming the list and the trigger, a short note and the cause as its last
     * error. A trigger that keeps failing is tried again every hour ({@link #postponeFailedScheduledTrigger}), but
     * leaves at most one record in {@link #FAILED_TRIGGER_RECORD_INTERVAL_MILLIS}.
     *
     * @param triggerId the ID of the scheduled trigger whose run failed
     * @param cause the failure
     * @return whether a record was made
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordScheduledTriggerFailure(Integer triggerId, Exception cause) throws IOException {

        MailingListTrigger trigger = triggerId == null ? null : getByPrimaryKey(MailingListTrigger.class, triggerId);
        if (trigger == null || trigger.getType() != TriggerType.SCHEDULED || trigger.getMailingList() == null) {
            log.warn("No failure record for scheduled trigger " + triggerId + ": the trigger or its list is gone");
            return false;
        }
        MailingList mailingList = trigger.getMailingList();

        String subject = StringUtils.abbreviate(
                "Not sent: mailing list " + mailingList.getMailingListId() + ", scheduled trigger " + trigger.getId(),
                255);
        if (hasRecentRecord(subject)) {
            log.debug("Scheduled trigger " + triggerId + " already has a failure record in the Mail Queue");
            return false;
        }

        saveFailureRecord(mailingList, subject,
                "<p>The scheduled trigger " + trigger.getId() + " of the mailing list "
                        + HtmlUtils.htmlEscape(mailingList.getMailingListId())
                        + " failed, so its mail was not made and is not sent. It is tried again within the hour;"
                        + " while it keeps failing, this record is made at most once a day.</p>",
                describeFailure(cause));
        return true;
    }

    /**
     * Records that a scheduled trigger waits for the free-text index ({@link IncompleteIndexException}), as one mail
     * in the Mail Queue in status ERROR, never sent. It is not a failure, so it is kept apart from the records of
     * {@link #recordScheduledTriggerFailure}: its subject starts "Delayed:", it says that the mail follows, and it does
     * not count toward their limit (nor theirs toward its), so a run that fails for a real cause after the wait is
     * still recorded. At most one such record in {@link #FAILED_TRIGGER_RECORD_INTERVAL_MILLIS}, however often the
     * trigger is tried again while it waits.
     *
     * @param triggerId the ID of the scheduled trigger that waits
     * @param wait the wait, with the time by which the mail is made
     * @return whether a record was made
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordScheduledTriggerWait(Integer triggerId, IncompleteIndexException wait) throws IOException {

        MailingListTrigger trigger = triggerId == null ? null : getByPrimaryKey(MailingListTrigger.class, triggerId);
        if (trigger == null || trigger.getType() != TriggerType.SCHEDULED || trigger.getMailingList() == null) {
            log.warn("No record of the wait of scheduled trigger " + triggerId + ": the trigger or its list is gone");
            return false;
        }
        MailingList mailingList = trigger.getMailingList();

        String subject = StringUtils.abbreviate(
                "Delayed: mailing list " + mailingList.getMailingListId() + ", scheduled trigger " + trigger.getId(),
                255);
        if (hasRecentRecord(subject)) {
            log.debug("Scheduled trigger " + triggerId + " already has a record of its wait in the Mail Queue");
            return false;
        }

        saveFailureRecord(mailingList, subject,
                "<p>The scheduled trigger " + trigger.getId() + " of the mailing list "
                        + HtmlUtils.htmlEscape(mailingList.getMailingListId())
                        + " waits for the text search index, which is being built again after a restart or a change of"
                        + " its settings, so its"
                        + " mail is not made yet. It is tried again every few minutes, and its mail is made once"
                        + " the index is complete, by " + wait.getWaitUntil().toInstant() + " at the latest."
                        + " This record is not sent.</p>",
                describeFailure(wait));
        return true;
    }

    /** Whether the Mail Queue holds a record (status ERROR) with the subject, made in the last day **/
    private boolean hasRecentRecord(String subject) {
        return em.createQuery("select count(m) from ScheduledMail m where m.subject = :s "
                        + "and m.status = :st and m.created > :since", Long.class)
                .setParameter("s", subject)
                .setParameter("st", ScheduledMail.Status.ERROR)
                .setParameter("since", new Date(System.currentTimeMillis() - FAILED_TRIGGER_RECORD_INTERVAL_MILLIS))
                .getSingleResult() > 0;
    }

    /**
     * Saves the record of a failed trigger: a mail in status ERROR, never sent, to the recipients of the list, with
     * the given subject, the note (HTML) followed by the cause, and the cause as its last error. A recipient with
     * neither a name nor an e-mail address is left out (the Mail Queue holds no recipient without an address), as it
     * is from the trigger's own mails.
     */
    private void saveFailureRecord(MailingList mailingList, String subject, String noteHtml, String error)
            throws IOException {
        ScheduledMail mail = new ScheduledMail();
        mail.setStatus(ScheduledMail.Status.ERROR);
        mail.setSubject(StringUtils.abbreviate(subject, 255));
        mail.setLastError(StringUtils.abbreviate(error, 255));
        List<IMailable> recipients = new ArrayList<>();
        recipients.addAll(mailingList.getUsers());
        recipients.addAll(mailingList.getContacts());
        recipients.stream()
                .map(IMailable::computeInternetAddress)
                .filter(Objects::nonNull)
                .forEach(address -> mail.addRecipient(new ScheduledMailRecipient(TO, address)));
        mail.setHtmlContents("<html><body>"
                + noteHtml
                + "<p>Cause: " + HtmlUtils.htmlEscape(error) + "</p>"
                + "</body></html>");
        saveEntity(mail);
    }

    /** The failure as a short text: its message, or its class when it has none **/
    private static String describeFailure(Throwable cause) {
        if (cause == null) {
            return "unknown";
        }
        return StringUtils.isNotBlank(cause.getMessage())
                ? cause.getMessage()
                : cause.getClass().getSimpleName();
    }

    /**
     * Executes the status change mailing list trigger for the given message
     *
     * The trigger may be an actual persisted entity or a template trigger user for testing.
     * In the latter case, set "persist" to false to prevent the mails being persisted.
     *
     * @param trigger the status change mailing list trigger to execute
     * @param message the message to execute the trigger for
     * @param persist whether to persist the mails or not
     */
    public List<ScheduledMail> executeStatusChangeTrigger(MailingListTrigger trigger, Message message, boolean persist) throws Exception {

        if (trigger.getType() != TriggerType.STATUS_CHANGE) {
            throw new IllegalAccessException("Must be a status-change trigger");
        }

        List<ScheduledMail> mails = new ArrayList<>();

        // Check that one or more language variants have been defined
        List<String> languages = trigger.getDescs().stream()
                .map(DescEntity::getLang)
                .collect(Collectors.toList());
        if (languages.isEmpty()) {
            return mails;
        }

        // In no Freemarker script resources have been defined, bail
        if (trigger.getScriptResourcePaths().isEmpty() ||
                trigger.getScriptResourcePaths().stream().noneMatch(p -> ScriptResource.path2type(p) == FM)) {
            log.debug("No Freemarker template defined for trigger " + trigger.getId());
            return mails;
        }

        // If a message filter is defined for the trigger, see if the message qualifies
        if (StringUtils.isNotBlank(trigger.getMessageFilter())) {
            MessageFilter filter = MessageFilter.getInstance(trigger.getMessageFilter());
            if (!filter.matches(message)) {
                log.debug(String.format("Message %s not matching trigger message filter: %s",
                        message.getUid(),
                        trigger.getMessageFilter()));
                return mails;
            }
        }

        // Create mails language by language
        for (String language : languages) {
            ScheduledMail mail = createMailTemplate(trigger, language, languages, message);

            // First check if there are any recipients for the language
            if (mail.getRecipients().isEmpty()) {
                continue;
            }

            String html = executeScriptResources(trigger, Collections.singletonList(message), language);
            mail.setHtmlContents(html);

            // All a copy of the mail for each recipient
            mails.addAll(mail.splitByRecipient());
        }

        // Persist the mails
        if (persist) {
            persistMails(mails);
        }
        return mails;
    }

    /***************************************/
    /** Scheduled triggers                **/
    /***************************************/

    /**
     * Executes the scheduled mailing list trigger with the given ID, and moves its next execution to its next
     * regular time.
     * <p>
     * The next execution is computed first, and the mails and the new time commit together or not at all: a trigger
     * whose next time cannot be computed (an invalid stored time) fails before any mail is made, and a run that fails
     * (its search or its template) leaves the trigger as it was. The scheduler then postpones the failed trigger with
     * {@link #postponeFailedScheduledTrigger(Integer)}.
     * <p>
     * A run whose free-text search was answered while the index was still being filled after the start fails the same
     * way, with an {@link IncompleteIndexException}, while the backend has been up for less than
     * {@link #INCOMPLETE_INDEX_WAIT}, and is tried again every {@link #INCOMPLETE_INDEX_RETRY}; after that the mail is
     * made from what the index found, and a warning naming the trigger is logged.
     *
     * @param triggerId the ID of the scheduled mailing list trigger to execute
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void executeScheduledTrigger(Integer triggerId) throws Exception {

        MailingListTrigger trigger = getByPrimaryKey(MailingListTrigger.class, triggerId);
        if (trigger == null || trigger.getType() != TriggerType.SCHEDULED) {
            throw new IllegalArgumentException("Invalid trigger " + triggerId);
        }

        // Throws for a trigger without a schedule type or with a time that cannot be read
        trigger.setNextScheduledExecution(ScheduledExecutionTimeUtil.computeNextExecutionTime(trigger));
        saveEntity(trigger);

        executeScheduledTrigger(trigger, true, true);
    }

    /**
     * Postpones a scheduled trigger whose run failed, so it is not run again every minute: its next execution
     * becomes the earlier of its next regular time and one hour from now, or one hour from now when the regular time
     * cannot be computed (an invalid stored time). A failure that clears within the hour still sends the mail, late;
     * a lasting one is tried once an hour. The scheduler also records the failure in the Mail Queue
     * ({@link #recordScheduledTriggerFailure}).
     *
     * @param triggerId the ID of the scheduled mailing list trigger that failed
     * @return the next execution set, or null when there is no such scheduled trigger
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Date postponeFailedScheduledTrigger(Integer triggerId) {
        return postponeFailedScheduledTrigger(triggerId, null);
    }

    /**
     * Postpones a scheduled trigger whose run failed, as {@link #postponeFailedScheduledTrigger(Integer)} does, but
     * to no later than the given time: a trigger that waits for the free-text index is tried again a few minutes on
     * ({@link IncompleteIndexException#getRetryAt()}).
     *
     * @param triggerId the ID of the scheduled mailing list trigger that failed
     * @param notAfter the latest next execution, or null for none
     * @return the next execution set, or null when there is no such scheduled trigger
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Date postponeFailedScheduledTrigger(Integer triggerId, Date notAfter) {

        MailingListTrigger trigger = getByPrimaryKey(MailingListTrigger.class, triggerId);
        if (trigger == null || trigger.getType() != TriggerType.SCHEDULED) {
            return null;
        }

        Date next = new Date(System.currentTimeMillis() + FAILED_TRIGGER_RETRY_MILLIS);
        if (notAfter != null && notAfter.before(next)) {
            next = notAfter;
        }
        try {
            Date regular = ScheduledExecutionTimeUtil.computeNextExecutionTime(trigger);
            if (regular.before(next)) {
                next = regular;
            }
        } catch (Exception e) {
            log.debug("No regular next execution for trigger " + triggerId + ": " + e.getMessage());
        }
        trigger.setNextScheduledExecution(next);
        saveEntity(trigger);
        return next;
    }

    /**
     * Computes the next scheduled execution of the given trigger
     * @param triggerId the ID of the scheduled mailing list trigger to execute
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void computeNextScheduledExecution(Integer triggerId) {

        MailingListTrigger trigger = getByPrimaryKey(MailingListTrigger.class, triggerId);
        if (trigger != null && trigger.getType() == TriggerType.SCHEDULED) {
            try {
                trigger.checkComputeNextScheduledExecution();
                saveEntity(trigger);
            } catch (Exception e) {
                log.error("Failed computing next scheduled execution for trigger " + triggerId, e);
            }
        }
    }

    /**
     * Executes the scheduled mailing list trigger for the given message.
     *
     * The trigger may be an actual persisted entity or a template trigger user for testing.
     * In the latter case, set "persist" to false to prevent the mails being persisted.
     *
     * @param trigger the scheduled mailing list trigger to execute
     * @param persist whether to persist the mails or not
     */
    @Transactional
    public List<ScheduledMail> executeScheduledTrigger(MailingListTrigger trigger, boolean persist) throws Exception {
        return executeScheduledTrigger(trigger, persist, false);
    }

    /**
     * Executes the scheduled mailing list trigger, as {@link #executeScheduledTrigger(MailingListTrigger, boolean)}
     * does. A scheduled run (not a test of the trigger) waits for the free-text index: when its search was answered
     * while the index was still being filled after the start ({@link PagedSearchResultVo#getIndexIncomplete()}), it
     * fails with an {@link IncompleteIndexException} while the backend has been up for less than
     * {@link #INCOMPLETE_INDEX_WAIT}, and after that makes its mails from what the index found and logs a warning.
     *
     * @param trigger the scheduled mailing list trigger to execute
     * @param persist whether to persist the mails or not
     * @param scheduledRun whether this is the scheduler's run of the trigger
     */
    private List<ScheduledMail> executeScheduledTrigger(MailingListTrigger trigger, boolean persist,
                                                        boolean scheduledRun) throws Exception {

        if (trigger.getType() != TriggerType.SCHEDULED) {
            throw new IllegalAccessException("Must be a scheduled trigger");
        }

        List<ScheduledMail> mails = new ArrayList<>();

        // Check that one or more language variants have been defined
        List<String> languages = trigger.getDescs().stream()
                .map(DescEntity::getLang)
                .collect(Collectors.toList());
        if (languages.isEmpty()) {
            return mails;
        }

        // In no Freemarker script resources have been defined, bail
        if (trigger.getScriptResourcePaths().isEmpty() ||
                trigger.getScriptResourcePaths().stream().noneMatch(p -> ScriptResource.path2type(p) == FM)) {
            log.debug("No Freemarker template defined for trigger " + trigger.getId());
            return mails;
        }

        // If no message query is defined for the trigger
        if (StringUtils.isBlank(trigger.getMessageQuery())) {
            log.debug("No mail query defined for trigger " + trigger.getId());
            return mails;
        }

        // Perform the message search
        MessageSearchParams params = MessageSearchParams.instantiate(null, trigger.getMessageQuery());
        params.maxSize(Integer.MAX_VALUE);
        // A failed search fails the trigger, which the scheduler logs and postpones, rather than mail an empty list
        PagedSearchResultVo<Message> messageResult = messageSearchService.searchOrFail(params);

        // A search answered while the free-text index is still being filled after the start, or after a rebuild that
        // a change of its settings began, may miss messages, and a mail cannot say so: in the half hour after the
        // later of the two the run waits for the index, as for one that cannot be read; after it, a refill that
        // stalls does not stop the mail
        if (scheduledRun && Boolean.TRUE.equals(messageResult.getIndexIncomplete())) {
            Instant rebuildStartedAt = messageLuceneIndex.rebuildStartedAt();
            Instant fillStartedAt = rebuildStartedAt.isAfter(startedAt) ? rebuildStartedAt : startedAt;
            Instant waitUntil = fillStartedAt.plus(INCOMPLETE_INDEX_WAIT);
            Instant now = Instant.now();
            if (now.isBefore(waitUntil)) {
                // Tried again every few minutes, so the mail follows soon after the index is complete
                Instant retryAt = now.plus(INCOMPLETE_INDEX_RETRY);
                throw new IncompleteIndexException(Date.from(retryAt.isBefore(waitUntil) ? retryAt : waitUntil),
                        Date.from(waitUntil));
            }
            log.warn("Scheduled mailing list trigger " + trigger.getId() + " is mailed although the free-text index "
                    + "is still not complete " + INCOMPLETE_INDEX_WAIT.toMinutes() + " minutes after it began to be "
                    + "built again: "
                    + "its mail may list too few messages");
        }

        // Create mails language by language
        for (String language : languages) {
            ScheduledMail mail = createMailTemplate(trigger, language, languages, null);

            // First check if there are any recipients for the language
            if (mail.getRecipients().isEmpty()) {
                continue;
            }

            String html = executeScriptResources(trigger, messageResult.getData(), language);
            mail.setHtmlContents(html);

            // All a copy of the mail for each recipient
            mails.addAll(mail.splitByRecipient());
        }

        // Persist the mails
        if (persist) {
            persistMails(mails);
        }
        return mails;
    }

    /***************************************/
    /** Mailing List Reports              **/
    /***************************************/

    /**
     * Returns the list of mailing list reports, i.e. the scheduled triggers that can
     * be executed by end-users as reports.
     *
     * @param lang the language
     * @return the list of mailing list reports
     */
    public List<MailingListReportVo> getMailingListReports(String lang) {

        return em.createNamedQuery("MailingListTrigger.findMailingListReports", MailingListTrigger.class)
                .getResultList()
                .stream()
                .map(t -> new MailingListReportVo(t, app.getLanguage(lang)))
                .collect(Collectors.toList());
    }

    /**
     * Executes the given mailing list report, i.e. the scheduled triggers that can
     * be executed by end-users as reports.
     * <p>
     * A free-text search answered while the index was still being filled after a start may miss messages; the
     * report is still made, with a line at its top that says so ({@link IndexIncompleteNotice}). A scheduled run
     * waits for the index instead ({@link #executeScheduledTrigger(Integer)}).
     *
     * @param triggerId the ID of the trigger (mailing list report) to execute
     * @param lang the language
     * @return the resulting HTML
     */
    public String executeMailingListReport(Integer triggerId, String lang) throws Exception {

        MailingListTrigger trigger = getByPrimaryKey(MailingListTrigger.class, triggerId);
        if (trigger == null || trigger.getType() != TriggerType.SCHEDULED || trigger.getPublicReport() != Boolean.TRUE) {
            throw new IllegalArgumentException("Trigger " + triggerId + " cannot be used as a public report");
        }

        // Perform the message search
        MessageSearchParams params = MessageSearchParams.instantiate(null, trigger.getMessageQuery());
        params.maxSize(Integer.MAX_VALUE);
        PagedSearchResultVo<Message> messageResult = messageSearchService.searchOrFail(params);

        // Make sure that the language is defined for the trigger
        lang = app.getLanguage(lang);
        Set<String> languages = trigger.getDescs().stream()
                .map(DescEntity::getLang)
                .collect(Collectors.toSet());
        if (!languages.contains(lang) && !languages.isEmpty()) {
            lang = languages.iterator().next();
        }

        // Execute the script resources associated with the trigger, with the line that says the report may miss
        // messages when the search was answered from an incomplete index
        String html = executeScriptResources(
                trigger,
                messageResult.getData(),
                lang);
        return FmTemplateService.withTopNotice(html, indexIncompleteNotice.forResult(messageResult, lang));
    }

    /***************************************/
    /** Common for all trigger types      **/
    /***************************************/

    /**
     * Creates a template mail for the mailing list trigger, with instantiated recipients and subject
     *
     * @param trigger the mailing list trigger to create a mail template for
     * @param language the current language
     * @param languages all language versions
     * @param message optionally, a message when the mails is created for a single message
     * @return the mail template
     **/
    private ScheduledMail createMailTemplate(MailingListTrigger trigger, String language, List<String> languages, Message message) {

        // Compute the default language
        String defaultLanguage = app.getLanguage("en");
        if (languages.size() == 1 || (languages.size() > 1 && !languages.contains(defaultLanguage))) {
            defaultLanguage = app.getLanguage(languages.getFirst());
        }
        boolean isDefaultLanguage = defaultLanguage.equals(language);

        ScheduledMail mail = new ScheduledMail();

        // Create the mail subject
        MailingListTriggerDesc desc = trigger.getDesc(language);
        String subject = StringUtils.defaultString(desc.getSubject());

        // Check if we need to replace any tokens in the subject, like "${short-id}", "${title}", etc.
        subject = expandSubject(trigger, message, language, languages, subject);
        mail.setSubject(subject);

        // Get the recipients of this language's mail: those who prefer it, and on the default-language mail those
        // whose language the trigger lacks or who have none
        List<IMailable> recipients = new ArrayList<>();
        recipients.addAll(trigger.getMailingList().getUsers().stream()
                .filter(r -> receives(r.getLanguage(), language, languages, isDefaultLanguage))
                .collect(Collectors.toList()));
        recipients.addAll(trigger.getMailingList().getContacts().stream()
                .filter(r -> receives(r.getLanguage(), language, languages, isDefaultLanguage))
                .collect(Collectors.toList()));

        // A recipient with neither a name nor an e-mail address has no address, which the Mail Queue refuses: it is
        // left out, so it does not stop the mail of everyone else on the list
        for (IMailable r : recipients) {
            String address = r.computeInternetAddress();
            if (address == null) {
                log.warn("Mailing list " + trigger.getMailingList().getMailingListId() + ": "
                        + describeRecipient(r) + " has no e-mail address and is left out of the mail of trigger "
                        + trigger.getId());
                continue;
            }
            mail.addRecipient(new ScheduledMailRecipient(TO, address));
        }

        return mail;
    }

    /** A recipient by its kind and id, never by name or address, for the log: "contact 12", "user 7" **/
    private static String describeRecipient(IMailable recipient) {
        String kind = recipient instanceof User ? "user" : recipient instanceof Contact ? "contact" : "recipient";
        Object id = recipient instanceof BaseEntity<?> entity ? entity.getId() : null;
        return kind + " " + id;
    }

    /**
     * Whether a recipient with the given preferred language receives the mail in the given language: the mail in its
     * own language when the trigger has one; else, as when it has no preferred language, the default-language mail.
     * With one trigger language, everyone receives that one.
     *
     * @param recipientLanguage the recipient's preferred language, or null
     * @param language the language of the mail
     * @param languages the languages of the trigger
     * @param isDefaultLanguage whether the mail is the trigger's default-language mail
     */
    private static boolean receives(String recipientLanguage, String language, List<String> languages,
                                    boolean isDefaultLanguage) {
        if (languages.size() == 1) {
            return true;
        }
        boolean triggerHasIt = recipientLanguage != null
                && languages.stream().anyMatch(l -> l.equalsIgnoreCase(recipientLanguage));
        return triggerHasIt ? language.equalsIgnoreCase(recipientLanguage) : isDefaultLanguage;
    }

    /**
     * Check if we need to replace any tokens in the subject, like "${short-id}", "${title}", etc.
     * <p>
     * {@code ${date-short}} and {@code ${date-long}} are today's date in the trigger's zone
     * ({@link #subjectZone(MailingListTrigger, Message)}), with the month words of the mail's language.
     **/
    private String expandSubject(MailingListTrigger trigger, Message message, String language, List<String> languages, String subject) {

        // A trigger may be written in a language the mail texts lack: fall back to the default language
        String shortFormat = dictionaryService.value("mail", language, "mail.date_format.short");
        String longFormat = dictionaryService.value("mail", language, "mail.date_format.long");
        Date today = new Date();
        Locale locale = Locale.forLanguageTag(language);
        TimeZone zone = TimeZone.getTimeZone(subjectZone(trigger, message));

        return MessageTokenExpander.getInstance(message, languages, language)
                .token("${date-short}", formatDate(shortFormat, locale, zone, today))
                .token("${date-long}", formatDate(longFormat, locale, zone, today))
                .expandTokens(subject);
    }

    /**
     * The zone the subject's date is read in: a scheduled trigger's own zone, the one its schedule runs in; for a
     * status-change trigger, which has none, the zone of the message's series; else, or when the zone cannot be
     * read, the JVM's zone.
     */
    private ZoneId subjectZone(MailingListTrigger trigger, Message message) {
        if (trigger.getType() == TriggerType.SCHEDULED && StringUtils.isNotBlank(trigger.getScheduledExecutionTimeZone())) {
            return TimeUtils.zoneOrDefault(trigger.getScheduledExecutionTimeZone());
        }
        if (message != null && message.getMessageSeries() != null) {
            return messageSeriesService.seriesZone(message.getMessageSeries());
        }
        return TimeUtils.zoneOrDefault(null);
    }

    /** Formats the date with the pattern, in the language's words and the zone's day **/
    private static String formatDate(String pattern, Locale locale, TimeZone zone, Date date) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, locale);
        format.setTimeZone(zone);
        return format.format(date);
    }

    /** Saves the list of scheduled mail templates **/
    private void persistMails(List<ScheduledMail> mails) {
        for (int x = 0; x < mails.size(); x++) {
            saveEntity(mails.get(x));
            if ((x + 1) % 20 == 0) {
                em.flush();
            }
        }
    }

    /***************************************/
    /** Template Execution                **/
    /***************************************/

    /**
     * Executes the script resources associated with a mailing list trigger and return the resulting HTML.
     * <p>
     * The list of script resources should be defined by an optional list of JavaScript resources
     * and a single freemarker template that actually generates the HTML for the mail.
     * <p>
     * The texts of the messages reach the scripts and the template cleaned with {@link MailHtml#cleanMessage}.
     *
     * @param trigger the mailing list trigger to execute
     * @param messages  the messages to apply the template to
     * @param language the language of the template execution
     * @return the resulting html generated in the templates
     */
    public String executeScriptResources(MailingListTrigger trigger, List<Message> messages, String language) throws Exception {

        // Sanity check
        if (trigger.getScriptResourcePaths().isEmpty()) {
            return null;
        }

        // Adjust the message prior to executing the template. The templates print the texts of the messages as
        // HTML, unescaped, so they are cleaned the way a comment mail is: formatting only, relative links made
        // absolute. The stored messages are untouched.
        String baseUri = app.getBaseUri();
        List<SystemMessageVo> msgs = messages.stream()
                .map(m -> m.toVo(SystemMessageVo.class, Message.MESSAGE_DETAILS_AND_PROMULGATIONS_FILTER))
                .map(m -> MailHtml.cleanMessage(m, baseUri))
                .collect(Collectors.toList());
        msgs.forEach(m -> m.sort(language));

        // Create context data to use with Freemarker templates and JavaScript updates
        Map<String, Object> contextData = new HashMap<>();
        contextData.put("languages", app.getLanguages());
        contextData.put("language", language);
        contextData.put("messages", msgs);
        if (msgs.size() == 1) {
            contextData.put("message", msgs.getFirst());
        }
        contextData.put("params", new HashMap<>());

        StringBuilder result = new StringBuilder();

        // Execute the script resources one by one
        for (String scriptResourcePath : trigger.getScriptResourcePaths()) {

            ScriptResource.Type type = ScriptResource.path2type(scriptResourcePath);
            if (type == ScriptResource.Type.JS) {
                // JavaScript update
                evalJavaScriptResource(contextData, scriptResourcePath);
            } else if (type == FM) {
                // Freemarker Template update
                String html = applyFreemarkerTemplate(contextData, scriptResourcePath, language);
                result.append(html);
            }
        }

        return result.toString();
    }

    /**
     * Executes the Freemarker template at the given path and updates the message accordingly
     *
     * @param contextData        the context data to use in the Freemarker template
     * @param scriptResourcePath the path to the Freemarker template
     * @param language the language of the template execution
     */
    private String applyFreemarkerTemplate(
            Map<String, Object> contextData,
            String scriptResourcePath,
            String language) throws IOException, TemplateException {

        // Run the associated Freemarker template to get a result in the "FieldTemplates" format
        return templateService.newFmTemplateBuilder()
                .templatePath(scriptResourcePath)
                .data(contextData)
                .language(language)
                .dictionaryNames("message", "mail")
                .process();
    }

    /**
     * Evaluates the JavaScript resource at the given path
     *
     * @param contextData        the context data to use in the Freemarker template
     * @param scriptResourcePath the path to the Freemarker template
     */
    private void evalJavaScriptResource(Map<String, Object> contextData, String scriptResourcePath) throws Exception {

        javaScriptService.newJsResourceBuilder()
                .resourcePath(scriptResourcePath)
                .data(contextData)
                .evaluate();
    }
}
