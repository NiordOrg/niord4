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
package org.niord.secom.service;

import jakarta.persistence.LockModeType;
import org.grad.secomv2.core.exceptions.SecomNotFoundException;
import org.grad.secomv2.core.models.enums.AckTypeEnum;
import org.grad.secomv2.core.models.enums.ContainerTypeEnum;
import org.grad.secomv2.core.models.enums.NackTypeEnum;
import org.grad.secomv2.core.models.enums.SubscriptionEventEnum;
import org.hibernate.Timeouts;
import org.hibernate.jpa.SpecHints;
import org.niord.core.service.BaseService;
import org.niord.core.settings.annotation.Setting;
import org.niord.core.util.TimeUtils;
import org.niord.secom.model.S124Dataset;
import org.niord.secom.model.SecomOutboxEntry;
import org.niord.secom.model.SecomSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.niord.core.settings.Setting.Type;
import static org.niord.secom.service.SecomCertificateAlertService.alertMail;
import static org.niord.secom.service.SecomCertificateAlertService.escapeHtml;

/**
 * The durable SECOM outbox: enqueues dataset uploads and subscription notifications as
 * {@link SecomOutboxEntry} rows and owns their state transitions (dispatch bookkeeping,
 * backoff scheduling, acknowledgement application). The scheduled dispatch itself lives in
 * {@code SecomOutboxDispatcher}. Modeled on the {@code ScheduledMail} queue.
 */
@Service
@ConditionalOnProperty(name = "niord.secom.enabled", havingValue = "true")
@SuppressWarnings("unused")
public class SecomOutboxService extends BaseService {

    private static final Logger log = LoggerFactory.getLogger(SecomOutboxService.class);

    @Setting(value = "secomOutboxMaxAttempts", defaultValue = "8", type = Type.Integer,
            description = "Max delivery attempts for a SECOM outbox entry before it is marked FAILED.")
    volatile Integer maxAttempts;

    @Setting(value = "secomOutboxBackoffMinutes", defaultValue = "1,5,30,120,720",
            description = "Comma-separated backoff delays (minutes) between SECOM delivery attempts; the last value"
                    + " repeats.")
    volatile String backoffMinutes;

    @Setting(value = "secomAlertEmails", defaultValue = "",
            description = "Comma-separated recipients alerted by mail when a SECOM delivery fails terminally"
                    + " (empty = no alerts).")
    volatile String alertEmails;

    /**
     * How long a claimed entry stays out of every other run's reach ({@link #claimNextDue}). A
     * delivery is at most a registry search and the POST, each bounded by the outbound client's
     * 30-second deadline, so the lease outlasts the longest delivery several times over; it is
     * also how long an entry whose run died after the claim waits before it is due again.
     * Package-private so the ITs can shorten it.
     */
    Duration claimLease = Duration.ofMinutes(5);

    private final S124ExchangeSetService exchangeSetService;

    public SecomOutboxService(S124ExchangeSetService exchangeSetService) {
        this.exchangeSetService = exchangeSetService;
    }

    /***************************************/
    /** Enqueueing                        **/
    /***************************************/

    /**
     * Enqueues a push of the given dataset to the given subscription. The payload container
     * follows the subscription: the design-mandated S-100 exchange set, unless the
     * subscription explicitly asked for the bare {@code S100_DataSet} (legacy peers).
     */
    @Transactional
    public SecomOutboxEntry enqueueUpload(SecomSubscription subscription, S124Dataset dataset) {
        String containerType = subscription.getContainerType() != null
                ? subscription.getContainerType()
                : ContainerTypeEnum.S100_ExchangeSet.name();
        byte[] payload = ContainerTypeEnum.S100_DataSet.name().equals(containerType)
                ? exchangeSetService.datasetFile(dataset)
                : exchangeSetService.buildExchangeSet(dataset);

        SecomOutboxEntry entry = new SecomOutboxEntry();
        entry.setNode(subscription.getNode());
        entry.setSubscriptionUuid(subscription.getUuid());
        entry.setOperation(SecomOutboxEntry.Operation.UPLOAD);
        entry.setDataset(dataset);
        entry.setContainerType(containerType);
        entry.setPayload(payload);
        entry = saveEntity(entry);
        log.info("Enqueued S-124 upload of {} to {} as {} (tx {})",
                dataset.getMrn(), subscription.getNode().getMrn(), containerType, entry.getTransactionId());
        return entry;
    }

    /** Enqueues a subscription created/removed notification to the subscription's node */
    @Transactional
    public SecomOutboxEntry enqueueNotification(SecomSubscription subscription, SubscriptionEventEnum event) {
        SecomOutboxEntry entry = new SecomOutboxEntry();
        entry.setNode(subscription.getNode());
        entry.setSubscriptionUuid(subscription.getUuid());
        entry.setOperation(SecomOutboxEntry.Operation.SUBSCRIPTION_NOTIFICATION);
        entry.setNotificationEvent(event.name());
        entry = saveEntity(entry);
        log.info("Enqueued {} notification for subscription {} to {}",
                event, subscription.getUuid(), subscription.getNode().getMrn());
        return entry;
    }

    /***************************************/
    /** Dispatch bookkeeping              **/
    /***************************************/

    /** Detached snapshot of an outbox entry, safe to use outside a persistence session */
    public record DispatchTask(Integer entryId, UUID transactionId, String nodeMrn, SecomOutboxEntry.Operation operation,
                               UUID subscriptionUuid, String notificationEvent, byte[] payload, String containerType) {
    }

    /**
     * Claims the next entry due by the given time (the oldest PENDING entry whose
     * {@code nextAttemptAt} has passed it) and returns it as a detached dispatch task, or nothing
     * when no entry is left.
     * <p>
     * The claim is committed in a short transaction of its own, before anything is sent. The row
     * is read {@code FOR UPDATE SKIP LOCKED}, so a run claiming at the same moment (a second
     * replica's) takes another entry instead of waiting for this one, and its {@code nextAttemptAt}
     * is moved to the end of the {@link #claimLease lease}, so no other run sees it as due while
     * it is delivered. {@link #markSent} or {@link #markFailedAttempt} then records the outcome; an
     * entry whose run never records one (the pod was killed mid-send) is due again when the lease
     * runs out.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<DispatchTask> claimNextDue(Date dueBy) {
        return em.createNamedQuery("SecomOutboxEntry.findDue", SecomOutboxEntry.class)
                .setParameter("date", dueBy)
                .setMaxResults(1)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setHint(SpecHints.HINT_SPEC_LOCK_TIMEOUT, Timeouts.SKIP_LOCKED_MILLI)
                .getResultList().stream()
                .findFirst()
                .map(e -> {
                    e.setNextAttemptAt(Date.from(Instant.now().plus(claimLease)));
                    return new DispatchTask(e.getId(), e.getTransactionId(), e.getNode().getMrn(), e.getOperation(),
                            e.getSubscriptionUuid(), e.getNotificationEvent(), e.getPayload(), e.getContainerType());
                });
    }

    /** Marks the entry successfully sent */
    @Transactional
    public void markSent(Integer entryId) {
        SecomOutboxEntry entry = lockedEntry(entryId);
        entry.setSentAt(new Date());
        entry.setAttempts(entry.getAttempts() + 1);
        // The remote node acknowledges (or NACKs) on its own connection and may beat this
        // bookkeeping transaction - never downgrade such a state back to SENT
        if (entry.getStatus() == SecomOutboxEntry.Status.PENDING) {
            entry.setStatus(SecomOutboxEntry.Status.SENT);
            entry.setLastError(null);
        }
    }

    /**
     * Records a failed delivery attempt: schedules the next attempt per the backoff list,
     * or marks the entry FAILED once the max attempt count is reached.
     * <p>
     * An entry that left PENDING while the attempt was in flight - acknowledged or NACKed by
     * the peer, or cancelled by an admin - keeps that state: only the attempt is counted, so a
     * NACKed entry is not alerted twice and a cancelled one is not turned into a failure.
     */
    @Transactional
    public void markFailedAttempt(Integer entryId, String error) {
        SecomOutboxEntry entry = lockedEntry(entryId);
        entry.setAttempts(entry.getAttempts() + 1);
        if (entry.getStatus() != SecomOutboxEntry.Status.PENDING) {
            log.info("SECOM outbox entry {} attempt {} failed after the entry became {}; its state stands: {}",
                    entry.getTransactionId(), entry.getAttempts(), entry.getStatus(), error);
            return;
        }
        entry.setLastError(error);
        if (entry.getAttempts() >= maxAttempts) {
            entry.setStatus(SecomOutboxEntry.Status.FAILED);
            log.error("SECOM outbox entry {} FAILED after {} attempts: {}", entry.getTransactionId(), entry.getAttempts(), error);
            alertTerminalFailure(entry, error);
        } else {
            entry.setNextAttemptAt(nextAttemptTime(entry.getAttempts()));
            log.warn("SECOM outbox entry {} attempt {} failed, retrying at {}: {}",
                    entry.getTransactionId(), entry.getAttempts(), entry.getNextAttemptAt(), error);
        }
    }

    /** Computes the next attempt time after the given number of failed attempts */
    Date nextAttemptTime(int attempts) {
        int[] delays = Arrays.stream(backoffMinutes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .mapToInt(Integer::parseInt)
                .toArray();
        int delay = delays.length == 0 ? 1 : delays[Math.min(attempts - 1, delays.length - 1)];
        return TimeUtils.add(new Date(), Calendar.MINUTE, delay);
    }

    /**
     * Loads the entry row-locked, like {@link #applyAcknowledgement}, so the dispatch
     * bookkeeping and an acknowledgement arriving on the peer's own connection apply one after
     * the other and each sees the state the other left
     */
    private SecomOutboxEntry lockedEntry(Integer entryId) {
        return em.find(SecomOutboxEntry.class, entryId, LockModeType.PESSIMISTIC_WRITE);
    }

    /**
     * Resets a PENDING or FAILED entry for immediate dispatch with a fresh schedule (admin retry):
     * due now, no attempts counted, and no last error or NACK type. So an entry that ran out of
     * attempts gets the whole backoff again, not one try and a second alert.
     * <p>
     * The row is loaded row-locked, like the dispatch bookkeeping and the acknowledgements, so a
     * retry sees the state they left. An entry in any other status (sent, acknowledged, opened or
     * cancelled) is refused with an {@link IllegalStateException} (409 over REST): a delivered
     * entry is sent again by republishing its dataset, not by retrying its transaction.
     *
     * @throws IllegalArgumentException for an unknown entry id
     */
    @Transactional
    public SecomOutboxEntry retryNow(Integer entryId) {
        SecomOutboxEntry entry = requireLockedEntry(entryId);
        if (entry.getStatus() != SecomOutboxEntry.Status.PENDING && entry.getStatus() != SecomOutboxEntry.Status.FAILED) {
            throw new IllegalStateException("Outbox entry " + entryId + " is " + entry.getStatus()
                    + "; only a PENDING or FAILED entry can be retried");
        }
        entry.setStatus(SecomOutboxEntry.Status.PENDING);
        entry.setNextAttemptAt(new Date());
        entry.setAttempts(0);
        entry.setLastError(null);
        entry.setNackType(null);
        return entry;
    }

    /**
     * Cancels a PENDING entry (admin operation). The row is loaded row-locked, as in
     * {@link #retryNow}; an entry in any other status is refused with an
     * {@link IllegalStateException} (409 over REST).
     *
     * @throws IllegalArgumentException for an unknown entry id
     */
    @Transactional
    public SecomOutboxEntry cancel(Integer entryId) {
        SecomOutboxEntry entry = requireLockedEntry(entryId);
        if (entry.getStatus() != SecomOutboxEntry.Status.PENDING) {
            throw new IllegalStateException("Outbox entry " + entryId + " is " + entry.getStatus()
                    + "; only a PENDING entry can be cancelled");
        }
        entry.setStatus(SecomOutboxEntry.Status.CANCELLED);
        return entry;
    }

    /** {@link #lockedEntry}, refusing an unknown id with an {@link IllegalArgumentException} (400 over REST) */
    private SecomOutboxEntry requireLockedEntry(Integer entryId) {
        SecomOutboxEntry entry = entryId == null ? null : lockedEntry(entryId);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown outbox entry " + entryId);
        }
        return entry;
    }

    /***************************************/
    /** Failure alerting                  **/
    /***************************************/

    /**
     * Queues an alert mail to the configured {@code secomAlertEmails} recipients when an
     * entry fails terminally — the failure-notification half of S-124R008. Disabled when the
     * setting is empty (the default). A mail problem must never break the outbox transition,
     * so failures are logged and swallowed.
     */
    private void alertTerminalFailure(SecomOutboxEntry entry, String error) {
        if (alertEmails == null || alertEmails.isBlank()) {
            return;
        }
        try {
            saveEntity(alertMail("SECOM delivery to " + entry.getNode().getMrn()
                            + " FAILED (" + entry.getOperation() + ")",
                    "<p>A SECOM outbox entry failed terminally and will not be retried automatically.</p>"
                    + "<ul>"
                    + "<li>Peer: " + escapeHtml(entry.getNode().getMrn()) + "</li>"
                    + "<li>Transaction: " + entry.getTransactionId() + "</li>"
                    + "<li>Operation: " + entry.getOperation()
                    + (entry.getNotificationEvent() != null ? " (" + escapeHtml(entry.getNotificationEvent()) + ")" : "") + "</li>"
                    + (entry.getDataset() != null ? "<li>Dataset: " + escapeHtml(entry.getDataset().getMrn()) + "</li>" : "")
                    + "<li>Attempts: " + entry.getAttempts() + "</li>"
                    + "<li>Error: " + escapeHtml(error) + "</li>"
                    + "</ul>"
                    + "<p>Inspect and retry the entry on the SECOM sysadmin page.</p>",
                    alertEmails));
            log.info("Queued SECOM failure alert for entry {} to {}", entry.getTransactionId(), alertEmails);
        } catch (Exception e) {
            log.error("Failed to queue the SECOM failure alert mail: {}", e.getMessage());
        }
    }

    /***************************************/
    /** Acknowledgements                  **/
    /***************************************/

    /**
     * Applies an acknowledgement received on {@code POST /v2/acknowledgement}: the
     * transaction must exist and belong to the acknowledging node.
     * <p>
     * An entry that has ended - FAILED (by a NACK or by running out of attempts) or CANCELLED
     * by an admin - stays ended whatever the peer sends afterwards: a positive acknowledgement
     * records its time but leaves the status, and a NACK is logged and otherwise ignored. Only
     * an admin retry reopens a FAILED entry; a cancelled one is not reopened. So one transaction raises at most one failure alert
     * (until an admin retries it), whatever the peer sends and in whatever order, and no
     * acknowledgement undoes an admin's cancellation.
     */
    @Transactional
    public void applyAcknowledgement(String mrn, UUID transactionIdentifier, AckTypeEnum ackType, NackTypeEnum nackType) {
        SecomOutboxEntry entry = em.createNamedQuery("SecomOutboxEntry.findByTransactionId", SecomOutboxEntry.class)
                .setParameter("transactionId", transactionIdentifier)
                // Row-locked, so concurrent acknowledgements of one transaction apply one after
                // the other and the ended check below holds for acknowledgements sent in parallel too
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList().stream()
                .findFirst()
                .orElseThrow(() -> new SecomNotFoundException("A transaction with UUID not found, UUID=" + transactionIdentifier));

        if (!entry.getNode().getMrn().equals(mrn)) {
            throw new SecomNotFoundException("A transaction was found, but does not belong to the remote node's MRN, UUID="
                    + transactionIdentifier + ", mrn=" + mrn);
        }

        // Were a positive acknowledgement allowed to reopen an ended entry, a peer alternating
        // acks and NACKs would pass the NACK check below every other time
        boolean ended = entry.getStatus() == SecomOutboxEntry.Status.FAILED
                || entry.getStatus() == SecomOutboxEntry.Status.CANCELLED;
        switch (ackType) {
            case DELIVERED_ACK -> {
                entry.setAckedAt(new Date());
                // Acknowledgements can arrive out of order - OPENED outranks ACKED
                if (!ended && entry.getStatus() != SecomOutboxEntry.Status.OPENED) {
                    entry.setStatus(SecomOutboxEntry.Status.ACKED);
                }
            }
            case OPENED_ACK -> {
                entry.setOpenedAt(new Date());
                if (!ended) {
                    entry.setStatus(SecomOutboxEntry.Status.OPENED);
                }
            }
            case ERROR -> {
                if (ended) {
                    log.info("Ignored {} NACK for SECOM transaction {}: the entry is already {}",
                            nackType, transactionIdentifier, entry.getStatus());
                    return;
                }
                entry.setNackType(nackType != null ? nackType.name() : null);
                entry.setLastError("NACK: " + nackType);
                entry.setStatus(SecomOutboxEntry.Status.FAILED);
                alertTerminalFailure(entry, "NACK: " + nackType);
            }
        }
        log.info("Applied {} acknowledgement to SECOM transaction {}, now {}", ackType, transactionIdentifier, entry.getStatus());
    }

    /***************************************/
    /** Queries                           **/
    /***************************************/

    /** Returns recent outbox entries, optionally filtered by status */
    public List<SecomOutboxEntry> search(SecomOutboxEntry.Status status, int limit) {
        if (status != null) {
            return em.createNamedQuery("SecomOutboxEntry.findByStatus", SecomOutboxEntry.class)
                    .setParameter("status", status)
                    .setMaxResults(limit)
                    .getResultList();
        }
        return em.createNamedQuery("SecomOutboxEntry.findRecent", SecomOutboxEntry.class)
                .setMaxResults(limit)
                .getResultList();
    }

    /**
     * {@return the number of outbox entries of each status, over every row} A status no entry
     * has is left out. The same group-by the actuator gauges ({@code SecomMetrics}) count with.
     */
    public Map<String, Long> countByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : em.createQuery(
                        "select e.status, count(e) from SecomOutboxEntry e group by e.status order by e.status",
                        Object[].class)
                .getResultList()) {
            counts.put(((SecomOutboxEntry.Status) row[0]).name(), (Long) row[1]);
        }
        return counts;
    }
}
