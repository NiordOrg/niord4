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

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.apache.commons.lang3.StringUtils;
import org.niord.core.db.CriteriaHelper;
import org.niord.core.mail.vo.SendAgainRequestVo;
import org.niord.core.mail.vo.SendAgainResultVo;
import org.niord.core.model.BaseEntity;
import org.niord.core.service.BaseService;
import org.niord.core.settings.SettingsService;
import org.niord.core.settings.annotation.Setting;
import org.niord.core.util.MdcContext;
import org.niord.core.util.TimeUtils;
import org.niord.core.web.NiordWebException;
import org.niord.model.search.PagedSearchResultVo;
import org.slf4j.Logger;

import jakarta.mail.internet.InternetAddress;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.niord.core.settings.Setting.Type.Integer;

/**
 * Interface for handling schedule mails
 */
@Service
@SuppressWarnings("unused")
public class ScheduledMailService extends BaseService {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(ScheduledMailService.class);

    static final String DELETE_AFTER_DAYS_KEY = "mailDeleteAfterDays";

    /** The most mails one Send again may name */
    public static final int SEND_AGAIN_MAX = 500;

    /** The answer (409) to a Send again while the mail filter is LOG */
    static final String SEND_AGAIN_REFUSED_LOG = "The mail filter (the setting " + MailService.VALID_RECIPIENTS_KEY
            + ") is LOG, so the mails would only be held again. Set it to ALL on the Settings page first.";

    /** The answer (409) to a Send again while the mail filter is empty */
    static final String SEND_AGAIN_REFUSED_EMPTY = "The mail filter (the setting " + MailService.VALID_RECIPIENTS_KEY
            + ") is empty, so no mail can be sent. Set it on the Settings page first.";

    private final MailService mailService;

    private final SettingsService settingsService;

    ScheduledMailService(MailService mailService, SettingsService settingsService) {
        this.mailService = mailService;
        this.settingsService = settingsService;
    }

        @Setting(value = "mailMaxPerMinute", defaultValue = "10", type = Integer,
            description = "The max number of mails to send per minute.")
    volatile Integer maxMailsPerMinute;

    /**
     * The field only declares the setting. {@link #deleteExpiredMails()} reads it through {@link SettingsService}
     * at each run, as the mail queue's page shows it, so a change on the Settings page applies to the next run
     * without a restart.
     */
        @Setting(value = "mailDeleteAfterDays", defaultValue = "30", type = Integer,
            description = "Delete scheduled mails older than the given value. A non-positive value means never.")
    volatile Integer mailDeleteAfterDays;

    /**
     * NB: Niord defines its own managed executor service to limit the number of threads,
     * and thus, the number of concurrent SMTP connections.
     *
     * Since moving to quarkus, we can use the default managed executor
     */
    // PORT: was org.eclipse.microprofile.context.ManagedExecutor (§16); replaced with a
    // bounded java.util.concurrent.ExecutorService to preserve the limited-concurrency
    // (SMTP connection) behavior. invokeAll(...) is unchanged (ExecutorService method).
    final ExecutorService managedExecutor = Executors.newFixedThreadPool(5);

    /**
     * The longest a run of {@link #sendPendingMails()} waits for its sender threads. Well above the worst honest
     * run: at most {@code mailMaxPerMinute} mails on five threads, each exchange with the SMTP server bounded by the
     * session's time limits ({@link MailConfig}). A test sets it shorter.
     */
    Duration sendRunLimit = Duration.ofMinutes(10);

    /**
     * Searches the filtered set of scheduled mails
     * @param params the search parameters
     * @return the search result
     */
    public PagedSearchResultVo<ScheduledMail> search(ScheduledMailSearchParams params) {

        long t0 = System.currentTimeMillis();

        PagedSearchResultVo<ScheduledMail> result = new PagedSearchResultVo<>();

        {
            CriteriaBuilder cb = em.getCriteriaBuilder();

            // First compute the total number of matching mails
            CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
            Root<ScheduledMail> countMailRoot = countQuery.from(ScheduledMail.class);

            // No ORDER BY on the count query: ordering a single-row aggregate is meaningless, and PostgreSQL
            // rejects ORDER BY on a non-grouped, non-aggregate column in an aggregate query (the paged query
            // below keeps the created ordering). MySQL tolerated it.
            countQuery.select(cb.count(countMailRoot)).where(buildQueryPredicates(cb, countQuery, countMailRoot, params));

            result.setTotal(em.createQuery(countQuery).getSingleResult());

        }

        {
            CriteriaBuilder cb = em.getCriteriaBuilder();

            // First compute the total number of matching mails
            CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);

            // Then, extract the current page of matches
            CriteriaQuery<ScheduledMail> query = cb.createQuery(ScheduledMail.class);
            Root<ScheduledMail> mailRoot = query.from(ScheduledMail.class);
            query.select(mailRoot).where(buildQueryPredicates(cb, query, mailRoot, params)).orderBy(cb.desc(mailRoot.get("created")));

            List<ScheduledMail> mails = em.createQuery(query).setMaxResults(params.getMaxSize())
                    .setFirstResult((int) Math.min((long) params.getPage() * params.getMaxSize(), java.lang.Integer.MAX_VALUE))
                    .getResultList();
            result.setData(mails);
            result.updateSize();

        }
        log.info("Search [" + params + "] returned " + result.getSize() + " of " + result.getTotal() + " in "
                + (System.currentTimeMillis() - t0) + " ms");

        return result;
    }

    /** Helper function that translates the search parameters into predicates */
    private <T> Predicate[] buildQueryPredicates(CriteriaBuilder cb, CriteriaQuery<T> query, Root<ScheduledMail> mailRoot, ScheduledMailSearchParams params) {

       // Build the predicate
        CriteriaHelper<T> criteriaHelper = new CriteriaHelper<>(cb, query);

        // Match the recipient
        if (StringUtils.isNotBlank(params.getRecipient())) {
            Join<ScheduledMail, ScheduledMailRecipient> recipients = mailRoot.join("recipients", JoinType.LEFT);
            criteriaHelper.like(recipients.get("address"), params.getRecipient());
        }

        // Match sender
        criteriaHelper.like(mailRoot.get("sender"), params.getSender());

        // Match subject
        criteriaHelper.like(mailRoot.get("subject"), params.getSubject());

        // Match status
        criteriaHelper = criteriaHelper.equals(mailRoot.get("status"), params.getStatus());

        // Match date interval
        criteriaHelper.between(mailRoot.get("created"), params.getFrom(), params.getTo());

        return criteriaHelper.where();
    }

    /**
     * Returns the mail with the given ID and null if undefined
     * @param id the ID of the mail
     * @return the mail with the given ID and null if undefined
     */
    public ScheduledMail getScheduledMail(Integer id) {
        return getByPrimaryKey(ScheduledMail.class, id);
    }

    /**
     * Returns the mails queued with the given sender since the given date, newest first.
     * <p>
     * The sender is the address the mail service recorded when the mail was queued
     * ({@code IMailable.computeInternetAddress()} of the user who sent it), matched exactly. A blank sender matches
     * nothing, so a user without a name and an e-mail address never sees the mails that carry no sender (the
     * mailing lists' mails).
     *
     * @param sender the sender address to match
     * @param since the earliest creation date to include
     * @param max the maximum number of mails to return
     * @return the mails queued by the sender since the date
     */
    public List<ScheduledMail> findBySender(String sender, Date since, int max) {
        if (StringUtils.isBlank(sender)) {
            return List.of();
        }
        return em.createNamedQuery("ScheduledMail.findBySender", ScheduledMail.class)
                .setParameter("sender", sender)
                .setParameter("since", since)
                .setMaxResults(max)
                .getResultList();
    }

    /**
     * Returns the pending scheduled mails
     * @return the pending scheduled mails
     */
    public List<ScheduledMail> getPendingMails() {
        return em.createNamedQuery("ScheduledMail.findPendingMails", ScheduledMail.class)
                .setParameter("date", new Date())
                .getResultList();
    }

    /**
     * Sends the given mails again, as a system administrator's Send again of the Mail Queue: each held, sent or
     * failed mail is put back in the queue as it is ({@link ScheduledMail#sendAgain(Date, String)}), and the sender
     * job sends it within a minute or two, after the mails the system made, under the mail filter in force then.
     * <p>
     * Refused as a whole (400) for a request that is not well formed, and (409) while the mail filter
     * ({@code mailValidRecipients}) is LOG or empty, so no mail is held or failed again by it. Otherwise each mail is
     * claimed in ascending id order with the lock the sender takes, and refused alone, with its reason, when it is
     * gone, is a record of a failure, or no longer has the status and the last Send again the page showed (a double
     * click, a second administrator, a stale page, or a mail the sender has handled since). While the filter is a list
     * of addresses, a sent mail with a recipient the list does not name is refused too (FILTER): the list would
     * redirect that recipient, the mail would end held, and a later Send again of the held mails would reach its
     * recipients a second time. A held or failed mail is accepted under a list, and ends sent or held again by what
     * the list lets through when it is sent. A refusal leaves the others queued; an exception rolls the whole request
     * back.
     *
     * @param items the mails to send again, as the page showed them
     * @param username the user name of the system administrator, recorded on each mail
     * @return the ids of the mails queued, and the mails refused with their reasons
     */
    public SendAgainResultVo sendAgain(List<SendAgainRequestVo.Item> items, String username) {
        checkSendAgainRequest(items);

        // The filter as the sender reads it for every mail (MailService.deliver)
        String filter = settingsService.getString(MailService.VALID_RECIPIENTS_KEY);
        if (StringUtils.isBlank(filter)) {
            throw new NiordWebException(SEND_AGAIN_REFUSED_EMPTY, 409);
        }
        ValidMailRecipients validRecipients = new ValidMailRecipients(filter);
        if (validRecipients.simulate()) {
            throw new NiordWebException(SEND_AGAIN_REFUSED_LOG, 409);
        }

        Date now = new Date();
        SendAgainResultVo result = new SendAgainResultVo();
        Map<ScheduledMail.Status, java.lang.Integer> wereBefore = new EnumMap<>(ScheduledMail.Status.class);
        List<SendAgainRequestVo.Item> inIdOrder = items.stream()
                .sorted(Comparator.comparing(SendAgainRequestVo.Item::getId))
                .toList();
        for (SendAgainRequestVo.Item item : inIdOrder) {
            // The claim the sender takes (MailService.sendScheduledMail), so a mail being sent is not changed under it
            ScheduledMail mail = em.find(ScheduledMail.class, item.getId(), LockModeType.PESSIMISTIC_WRITE);
            SendAgainResultVo.Reason refusal = null;
            if (mail == null) {
                refusal = SendAgainResultVo.Reason.GONE;
            } else if (mail.isRecord()) {
                refusal = SendAgainResultVo.Reason.RECORD;
            } else if (mail.getStatus() != item.getStatus()
                    || !Objects.equals(millis(mail.getSentAgainAt()), millis(item.getSentAgainAt()))) {
                refusal = SendAgainResultVo.Reason.CHANGED;
            } else if (mail.getStatus() == ScheduledMail.Status.SENT && redirectsARecipient(mail, validRecipients)) {
                refusal = SendAgainResultVo.Reason.FILTER;
            }
            if (refusal != null) {
                result.getRefused().add(new SendAgainResultVo.Refusal(item.getId(), refusal));
                continue;
            }
            wereBefore.merge(mail.getStatus(), 1, java.lang.Integer::sum);
            mail.sendAgain(now, username);
            result.getQueued().add(mail.getId());
        }

        log.info("Send again by " + username + ": queued " + result.getQueued().size() + " mails "
                + result.getQueued() + " (were HELD " + wereBefore.getOrDefault(ScheduledMail.Status.HELD, 0)
                + ", SENT " + wereBefore.getOrDefault(ScheduledMail.Status.SENT, 0)
                + ", ERROR " + wereBefore.getOrDefault(ScheduledMail.Status.ERROR, 0) + "); refused "
                + result.getRefused().stream().map(r -> r.getId() + "=" + r.getReason())
                        .collect(Collectors.joining(", ", "[", "]")));
        return result;
    }

    /** Refuses (400) a Send again that names no mail, too many, a mail without its id or status, or one twice */
    private static void checkSendAgainRequest(List<SendAgainRequestVo.Item> items) {
        if (items == null || items.isEmpty()) {
            throw new NiordWebException("No mail to send again", 400);
        }
        if (items.size() > SEND_AGAIN_MAX) {
            throw new NiordWebException("At most " + SEND_AGAIN_MAX + " mails can be sent again at a time, not "
                    + items.size(), 400);
        }
        Set<java.lang.Integer> ids = new HashSet<>();
        for (SendAgainRequestVo.Item item : items) {
            if (item == null || item.getId() == null || item.getStatus() == null) {
                throw new NiordWebException("Every mail to send again needs its id and its status", 400);
            }
            if (item.getStatus() == ScheduledMail.Status.PENDING) {
                throw new NiordWebException("Only a held, sent or failed mail can be sent again, not the pending mail "
                        + item.getId(), 400);
            }
            if (!ids.add(item.getId())) {
                throw new NiordWebException("The mail " + item.getId() + " is named twice", 400);
            }
        }
    }

    /**
     * Whether the mail filter would redirect one of the mail's stored recipients to the first listed address, by the
     * rule the sender applies ({@link ValidMailRecipients#redirects}, as {@link Mail#filterRecipients} asks it)
     */
    private static boolean redirectsARecipient(ScheduledMail mail, ValidMailRecipients validRecipients) {
        return mail.getRecipients().stream()
                .map(ScheduledMailRecipient::toMailRecipient)
                .filter(Objects::nonNull)
                .anyMatch(r -> r.getAddress() instanceof InternetAddress address && validRecipients.redirects(address));
    }

    /** The date as epoch milliseconds, or null */
    private static Long millis(Date date) {
        return date == null ? null : date.getTime();
    }

    /**
     * Called every minute to process scheduled mails
     * <p>
     * The job reads the pending ids and hands them to the sender threads without a transaction of its own, so the
     * scheduler thread holds no database connection while the mails are sent: each sender thread holds one, in its
     * mail's own transaction ({@code MailService.sendScheduledMail}), so a slow SMTP server costs at most as many
     * connections as there are sender threads. The class inherits a transaction for every method from
     * {@code BaseService}, so this one says it runs without.
     * <p>
     * The run waits at most {@link #sendRunLimit} for its sender threads, and each exchange with the SMTP server is
     * bounded by the session's time limits ({@link MailConfig}), so a server that stops answering cannot stop the
     * mail queue for good.
     * <p>
     * The schedule is the property {@code niord.schedule.mail-send-cron}, with the shipped value as its default;
     * {@code -} ({@link Scheduled#CRON_DISABLED}) switches the job off, so that a copy of another deployment's data
     * can be started without its pending mails being sent and marked sent.
     */
    @Scheduled(cron = "${niord.schedule.mail-send-cron:24 * * * * ?}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sendPendingMails() {

        // Send at most "maxMailsPerMinute" mails at a time
        List<Integer> scheduledMailIds = getPendingMails().stream()
                .limit(maxMailsPerMinute)
                .map(BaseEntity::getId)
                .collect(Collectors.toList());

        if (!scheduledMailIds.isEmpty()) {

            log.info("Processing " + scheduledMailIds.size() + " pending scheduled mails");

            List<MailSenderTask> tasks = scheduledMailIds.stream()
                    .map(id -> new MailSenderTask(mailService, id))
                    .collect(Collectors.toList());

            try {
                List<Integer> unfinished = runSenders(scheduledMailIds, tasks);
                if (!unfinished.isEmpty()) {
                    log.error("The run of pending scheduled mails passed its limit of " + sendRunLimit.toMinutes()
                            + " minutes; the mails still being sent or not started, " + unfinished
                            + ", stay pending and are tried again by a later run");
                }
            } catch (InterruptedException e) {
                log.error("Error sending scheduled emails: " + scheduledMailIds, e);
            }
        }
    }

    /**
     * Runs the sender tasks on the sender threads and waits at most {@link #sendRunLimit} for them. A task not
     * finished by then is cancelled: one not yet started is dropped, and its mail stays pending for a later run; a
     * running one is interrupted, and the session's time limits end its exchange with the SMTP server.
     *
     * @param ids the ids of the mails, in the order of the tasks
     * @param tasks the sender tasks
     * @return the ids of the mails whose task did not finish within the limit
     */
    List<Integer> runSenders(List<Integer> ids, List<? extends Callable<ScheduledMail>> tasks)
            throws InterruptedException {
        // managedExecutor is a plain java.util.concurrent pool, outside Spring's MDC
        // TaskDecorator. The calling thread is a @Scheduled sweep and so already carries a
        // job id; wrapAll captures it once here so each mail's log lines name the sweep
        // that sent it.
        List<Future<ScheduledMail>> futures = managedExecutor.invokeAll(
                MdcContext.wrapAll(tasks), sendRunLimit.toMillis(), TimeUnit.MILLISECONDS);
        List<Integer> unfinished = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            if (futures.get(i).isCancelled()) {
                unfinished.add(ids.get(i));
            }
        }
        return unfinished;
    }

    /**
     * Called every day to delete old scheduled mails: those created more than {@code mailDeleteAfterDays} days ago,
     * whatever their status, but for a pending mail, which is never deleted (it may have been sent again and wait to
     * be sent). Each mail is loaded with the lock the sender and Send again take, and skipped if it is pending then.
     *
     * Note to self: It would have been faster to execute a "delete from ScheduledMail where..." statement.
     * However, this will fail because of a missing mail - recipient "delete on cascade" FK constraint.
     * Using JPAs CascadeType.ALL for the relation does NOT work in this case.
     */
    @Scheduled(cron="48 28 5 * * ?")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void deleteExpiredMails() {

        // The setting as it is now. If it is 0 (actually, non-positive), or has no value, never delete mails
        java.lang.Integer deleteAfterDays = settingsService.getInteger(DELETE_AFTER_DAYS_KEY);
        if (deleteAfterDays == null || deleteAfterDays <= 0) {
            return;
        }

        Date expiryDate = TimeUtils.add(new Date(), Calendar.DATE, -deleteAfterDays);

        List<Integer> ids = em.createNamedQuery("ScheduledMail.findExpiredMails", Integer.class)
                .setParameter("expiryDate", expiryDate)
                .getResultList();

        if (!ids.isEmpty()) {
            long t0 = System.currentTimeMillis();
            try {
                int deleted = 0;
                for (int x = 0; x < ids.size(); x++) {
                    ScheduledMail mail = em.find(ScheduledMail.class, ids.get(x), LockModeType.PESSIMISTIC_WRITE);
                    if (mail != null && mail.getStatus() != ScheduledMail.Status.PENDING) {
                        em.remove(mail);
                        deleted++;
                        if ((x % 50) == 0) {
                            em.flush();
                        }
                    }
                }

                log.info("Deleted " + deleted + " scheduled mails older than " + expiryDate
                            + " in " + (System.currentTimeMillis() - t0) + " ms");

            } catch (Exception e) {
                log.error("Failed deleting scheduled mails older than " + expiryDate);
            }
        }
    }

    /**
     * The task that actually sends the e-mail
     */
    final static class MailSenderTask implements Callable<ScheduledMail> {

        final Integer scheduledMailId;
        final MailService mailService;

        /** Constructor **/
        public MailSenderTask(MailService mailService, Integer scheduledMailId) {
            this.mailService = mailService;
            this.scheduledMailId = scheduledMailId;
        }

        /** {@inheritDoc} **/
        @Transactional
        @Override
        public ScheduledMail call() {
            try {
                //MailService mailService = CdiUtils.getBean(MailService.class);
                return  mailService.sendScheduledMail(scheduledMailId);
            } catch (Exception e) {
                log.error("Error sending scheduled mail " + scheduledMailId, e);
                return null;
            }
        }
    }
}
