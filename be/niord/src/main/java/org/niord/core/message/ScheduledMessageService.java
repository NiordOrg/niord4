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
package org.niord.core.message;

import org.springframework.stereotype.Service;

import org.springframework.scheduling.annotation.Scheduled;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainService;
import org.niord.core.service.BaseService;
import org.niord.core.util.TimeUtils;
import org.niord.model.message.Status;

import jakarta.persistence.OptimisticLockException;
import org.hibernate.Session;
import org.hibernate.StaleStateException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.niord.core.message.MessageSearchParams.DateType.PUBLISH_FROM_DATE;
import static org.niord.core.message.MessageSearchParams.DateType.PUBLISH_TO_DATE;
import static org.niord.model.message.Status.PUBLISHED;
import static org.niord.model.message.Status.VERIFIED;

/**
 * This service sets up timers to perform regular message status checks:
 * <ul>
 *     <li>Checks for published messages that have passed the publishDateTo and expires these.</li>
 *     <li>Checks for verified messages with a publishDateFrom in the past and publishes these.</li>
 * </ul>
 *
 * <p>Both sweeps mutate committed message status on their own, so their schedules are configurable
 * rather than compile-time literals: {@code niord.schedule.auto-expire-cron} and
 * {@code niord.schedule.auto-publish-cron} default to the expressions shipped here and can be
 * re-tuned per environment, or set to {@code -} ({@link Scheduled#CRON_DISABLED}) to turn a sweep
 * off entirely. The integration-test suite disables both that way, because a tick landing mid-test
 * would publish or expire the test's own fixtures behind its back.
 *
 * <p>Each sweep reads in a transaction of its own and changes each due message in a transaction of that message's
 * own, through {@link MessageTxRunner}: a message whose change fails is rolled back alone and logged, and every other
 * due message of the minute is published or expired, committed and broadcast. A message whose publication fails is
 * set back to DRAFT, so it does not fail again every minute and a duty officer finds it among the drafts, with a DRAFT
 * entry in its history. A publication that fails because it crossed another transaction (a deadlock or a lock
 * timeout, a version conflict on the message or on a recording publication's tag, or no database connection in time)
 * is the exception: the message stays VERIFIED and the next minute tries it again, since a due warning must not end
 * among the drafts because two transactions crossed. A message whose expiry fails stays PUBLISHED and is
 * tried again the next minute.
 * <p>
 * Each unit waits at most {@code niord.schedule.lock-timeout} (20 seconds by default) for a lock another transaction
 * holds, such as a series' number row taken by a publication over REST that has not committed: the sweep runs on one
 * thread, and without the limit one such wait held up every due message of every domain after it.
 * <p>
 * Auto-publish runs at second 7 of every minute, a second no other per-minute job uses, so a warning due at a whole
 * minute is published before that minute's mail sender (second 24) and mailing-list run (second 37). The
 * scheduled jobs share a pool of four threads ({@code spring.task.scheduling.pool.size} in
 * {@code niord-core-defaults.yml}); a cron job is never started again on one node while its last run is running.
 */
@Service
@SuppressWarnings("unused")
public class ScheduledMessageService extends BaseService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ScheduledMessageService.class);

    /** The SQLStates of a lock conflict: a deadlock (40P01) and a lock timeout or NOWAIT refusal (55P03) */
    private static final Set<String> LOCK_CONFLICT_STATES = Set.of("40P01", "55P03");

    private final DomainService domainService;

    private final MessageService messageService;

    private final MessageSearchService messageSearchService;

    private final MessageTxRunner txRunner;

    /**
     * How long one message's unit waits for a lock another transaction holds (a series' number row taken by a
     * publication that has not committed, a message row) before it gives up with a lock timeout: the message is then
     * tried again the next minute, and the sweep goes on with the other due messages. PostgreSQL waits without a limit
     * unless told otherwise. Zero or less sets no limit.
     */
    @Value("${niord.schedule.lock-timeout:20s}")
    Duration lockTimeout = Duration.ofSeconds(20);

    ScheduledMessageService(DomainService domainService, MessageService messageService,
                            MessageSearchService messageSearchService, MessageTxRunner txRunner) {
        this.domainService = domainService;
        this.messageService = messageService;
        this.messageSearchService = messageSearchService;
        this.txRunner = txRunner;
    }

    /**
     * Called every minute to update expire published messages where publishDateTo is in the past
     */
    @Scheduled(cron = "${niord.schedule.auto-expire-cron:28 * * * * ?}")
    void checkForExpirablePublishedMessages() {
        expireDueMessages(null);
    }

    /**
     * Called every minute to publish messages with a VERIFIED status and a defined publishDateFrom in the past
     */
    @Scheduled(cron = "${niord.schedule.auto-publish-cron:7 * * * * ?}")
    void checkForPublishableMessages() {
        publishDueMessages(null);
    }

    /**
     * Expires the PUBLISHED messages of the given domains whose publish-to date has passed, each in a transaction of
     * its own. A message whose expiry fails stays PUBLISHED and is tried again by the next sweep.
     * <p>
     * The sweep's seam: the integration tests call it with domains of their own, never with {@code null}.
     *
     * @param domainIds the domains to sweep, or {@code null} for every active domain
     * @return the uids of the due messages whose expiry failed
     */
    List<String> expireDueMessages(Set<String> domainIds) {

        // We want to treat messages with timestamps within the same minute equally, so, reset the seconds
        Date now = TimeUtils.resetSeconds(new Date());

        Set<String> handled = new LinkedHashSet<>();
        List<String> failed = new ArrayList<>();

        // Domain by domain, in order to use the domain sort order. A domain is searched after the changes of the
        // previous one are committed, and a message whose series is in two domains is handled once
        for (Domain domain : domainsToSweep(domainIds)) {
            for (DueMessage m : searchMessages(domain, PUBLISHED, PUBLISH_TO_DATE, null, now)) {
                if (!handled.add(m.uid())) {
                    continue;
                }
                try {
                    log.info("System expiring message " + m.uid());
                    changeStatus(m.uid(), Status.EXPIRED);
                } catch (Exception ex) {
                    failed.add(m.uid());
                    log.error("Scheduled expiry of message " + m.label()
                            + " failed; it stays PUBLISHED and is tried again next minute", ex);
                }
            }
        }

        if (!failed.isEmpty()) {
            log.error("Expiry sweep: " + failed.size() + " of " + handled.size() + " due messages failed: " + failed);
        }
        return failed;
    }

    /**
     * Publishes the VERIFIED messages of the given domains whose publish-from date has passed, each in a transaction
     * of its own. A message whose publication fails is rolled back and then set back to DRAFT, in a transaction of
     * its own too; when that fails as well, it stays VERIFIED and is tried again by the next sweep. A publication
     * that fails because it crossed another transaction ({@link #isConcurrencyConflict}: a deadlock, a lock timeout,
     * a version conflict, no connection in time) is rolled back and not set back to DRAFT: the message
     * stays VERIFIED and the next sweep tries it again.
     * <p>
     * The sweep's seam: the integration tests call it with domains of their own, never with {@code null}.
     *
     * @param domainIds the domains to sweep, or {@code null} for every active domain
     * @return the uids of the due messages whose publication failed
     */
    List<String> publishDueMessages(Set<String> domainIds) {

        // We want to treat messages with timestamps within the same minute equally, so, reset the seconds
        Date now = TimeUtils.resetSeconds(new Date());

        Set<String> handled = new LinkedHashSet<>();
        List<String> failed = new ArrayList<>();

        // Domain by domain, in order to use the domain sort order. A domain is searched after the changes of the
        // previous one are committed, and a message whose series is in two domains is handled once: a second
        // attempt would be refused, and its DRAFT fallback would then run against a published message
        for (Domain domain : domainsToSweep(domainIds)) {
            for (DueMessage m : searchMessages(domain, VERIFIED, PUBLISH_FROM_DATE, null, now)) {
                if (!handled.add(m.uid())) {
                    continue;
                }
                try {
                    log.info("System publishing message " + m.uid());
                    changeStatus(m.uid(), PUBLISHED);
                } catch (Exception ex) {
                    failed.add(m.uid());

                    // Another transaction held a lock the publication needed, or changed a row it changes first:
                    // nothing is wrong with the message, so it stays VERIFIED and the next minute tries it again
                    if (isConcurrencyConflict(ex)) {
                        log.warn("Scheduled publication of message " + m.label() + " crossed another transaction;"
                                + " it stays VERIFIED and is tried again next minute", ex);
                        continue;
                    }
                    log.error("Scheduled publication of message " + m.label() + " failed; it is set back to DRAFT", ex);

                    // Change status to DRAFT so we do not fail on the same message every minute
                    try {
                        changeStatus(m.uid(), Status.DRAFT);
                    } catch (Exception e) {
                        log.error("Setting message " + m.label() + " back to DRAFT failed too; it stays VERIFIED"
                                + " and is tried again next minute", e);
                    }
                }
            }
        }

        if (!failed.isEmpty()) {
            log.error("Publication sweep: " + failed.size() + " of " + handled.size() + " due messages failed: "
                    + failed);
        }
        return failed;
    }

    /**
     * Whether the failure is two transactions crossing, which is no fault of the message: the next minute's unit reads
     * everything afresh and may well succeed. Found anywhere in the cause chain (Hibernate, JPA and Spring each wrap
     * the driver's exception, and keep it as a cause):
     * <ul>
     *     <li>PostgreSQL's deadlock ({@code 40P01}) or lock timeout ({@code 55P03}), as the SQLState of an
     *         {@link SQLException};</li>
     *     <li>a version conflict: another transaction committed a newer version of a row the unit changes (the message
     *         itself, saved by an editor or moved in the list while the unit waited for the series' number, or a
     *         recording publication's tag, changed by a publication in another series of its domain). It carries no
     *         SQLState: Hibernate's {@link StaleStateException}, JPA's {@link OptimisticLockException}, or Spring's
     *         {@link ConcurrencyFailureException} when the conflict comes at the commit;</li>
     *     <li>a connection the unit could not get in time ({@link CannotCreateTransactionException},
     *         {@link SQLTransientConnectionException}).</li>
     * </ul>
     *
     * @param failure the failure of a status change
     * @return whether it is two transactions crossing
     */
    static boolean isConcurrencyConflict(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof OptimisticLockException || t instanceof StaleStateException
                    || t instanceof ConcurrencyFailureException || t instanceof CannotCreateTransactionException
                    || t instanceof SQLTransientConnectionException) {
                return true;
            }
            if (t instanceof SQLException sql) {
                for (SQLException s = sql; s != null && (s == sql || seen.add(s)); s = s.getNextException()) {
                    // A driver's exception may carry no SQLState, and Set.of refuses to look a null up
                    if (s.getSQLState() != null && LOCK_CONFLICT_STATES.contains(s.getSQLState())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * One message's status change, in a transaction of its own: committed (and then broadcast) when it succeeds,
     * rolled back alone, for any exception, when it fails
     */
    private void changeStatus(String uid, Status status) throws Exception {
        txRunner.call(() -> {
            limitLockWaits();
            return messageService.updateStatus(uid, status);
        });
    }

    /**
     * Limits how long the unit's statements wait for another transaction's lock ({@link #lockTimeout}), for this
     * transaction only ({@code SET LOCAL}): the pooled connection is not changed, and REST requests and other jobs
     * keep the database's own setting. A wait that runs out fails with SQLState {@code 55P03}, a crossing of two
     * transactions ({@link #isConcurrencyConflict}).
     */
    private void limitLockWaits() {
        long millis = lockTimeout == null ? 0 : lockTimeout.toMillis();
        if (millis <= 0) {
            return;
        }
        em.unwrap(Session.class).doWork(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL lock_timeout = " + millis);
            }
        });
    }

    /** The active domains with message series, narrowed to the given domain ids when they are given */
    private List<Domain> domainsToSweep(Set<String> domainIds) {
        return domainService.getDomains().stream()
                .filter(domain -> domainIds == null || domainIds.contains(domain.getDomainId()))
                .filter(domain -> !domain.getMessageSeries().isEmpty())
                .collect(Collectors.toList());
    }

    /** A due message, read before any change: its uid, and the label its log lines name it by */
    private record DueMessage(String uid, String label) {
    }

    /**
     * Searches for messages with the given status and where the publish dates are within
     * the given interval.
     * Sort the messages by domain sort order.
     * <p>
     * Only the uids and labels leave the search: each change runs in a transaction of its own, which must not use
     * an entity of the sweep's persistence context.
     * <p>
     * The lenient search is deliberate here: a failed search is logged by it and the sweep does nothing this minute
     * and tries again the next, while a thrown failure would need a catch per domain so that one domain's failure
     * does not stop the others.
     *
     * @param domain the domain
     * @param status the message status
     * @param dateType the type of date interval to search by
     * @param from the publish-from date
     * @param to the publish-to date
     * @return the messages matching the search criteria
     */
    private List<DueMessage> searchMessages(Domain domain, Status status, MessageSearchParams.DateType dateType, Date from, Date to) {
        Set<String> seriesIds = domain.getMessageSeries().stream()
                .map(MessageSeries::getSeriesId)
                .collect(Collectors.toSet());

        MessageSearchParams params = new MessageSearchParams()
                .seriesIds(seriesIds)
                .statuses(status)
                .dateType(dateType)
                .from(from)
                .to(to)
                .checkSortByDomain(domain);

        return messageSearchService.search(params).getData().stream()
                .map(m -> new DueMessage(m.getUid(), m.getShortId() != null ? m.getShortId() : m.getUid()))
                .collect(Collectors.toList());
    }

}
