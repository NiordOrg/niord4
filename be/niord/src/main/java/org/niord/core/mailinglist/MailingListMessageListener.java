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

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.niord.core.mailinglist.MailingListStatusChangeOutbox.Claim;
import org.niord.core.model.BaseEntity;
import org.niord.core.util.JmsTopicLoop;
import org.niord.model.message.Status;
import org.slf4j.Logger;

import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.jms.ConnectionFactory;

/**
 * Makes the status-change mails of the mailing lists, from the rows of their outbox
 * ({@link MailingListStatusChangeOutbox}).
 * <p>
 * Every status change of a message writes one outbox row in its own transaction. This listener takes the due rows one
 * at a time, in the order they were written, runs the status-change triggers of each and deletes it
 * ({@link #drainOutbox}). It does so when the JMS status topic announces a status change (the topic's message is only a
 * prompt to look now; it is broadcast after the commit, so the row is visible), once a minute
 * ({@link #sweep}, {@code niord.mailinglist.status-change-sweep-cron}; {@code -} disables it), and once at start, so a
 * status change whose topic message was lost (a restart, a broker error, the listener subscribing again) is still
 * mailed. One drain runs at a time on a node; nodes sharing a database take different rows (see
 * {@link MailingListStatusChangeOutbox#claimNextDue}).
 * <p>
 * The order holds while no row fails. A row whose triggers could not be looked up waits for its lease to end, and the
 * next passes take the rows after it meanwhile: a later status change of the same message (CANCELLED after PUBLISHED)
 * may then be mailed before it. Such a row is tried again when its lease runs out, with an ERROR each time; its
 * {@code attempts} and {@code "lastError"} show how long it has failed. A row taken more than
 * {@link MailingListStatusChangeOutbox#MAX_ATTEMPTS} times (its run failed, hung or stopped the server each time) is
 * given up: each of its status's triggers leaves a "Not sent" ERROR record in the Mail Queue, and the row is deleted,
 * so the mails its run made before the fault are not made again and again.
 * <p>
 * The topic is polled on a single-thread executor, started when the application is ready and stopped when the context
 * closes ({@link JmsTopicLoop}).
 */
@SuppressWarnings("unused")
@Component
public class MailingListMessageListener implements Runnable {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(MailingListMessageListener.class);

    private final MailingListService mailingListService;

    private final MailingListExecutionService mailingListExecutionService;

    private final MailingListStatusChangeOutbox outbox;

    private final ConnectionFactory connectionFactory;

    /** One drain at a time on this node, so its status changes are handled in the order they were written */
    private final ReentrantLock drainLock = new ReentrantLock();

    /** A row the running drain has taken, and since when */
    private record Running(Claim claim, long since) {
    }

    /** The row the drain of this node is running now, if any */
    private volatile Running running;

    /** How long one row may run before the sweep warns that it holds the drain (tests set it low) */
    long stuckAfterMillis = Duration.ofMinutes(2).toMillis();

    MailingListMessageListener(MailingListService mailingListService, MailingListExecutionService mailingListExecutionService,
                               MailingListStatusChangeOutbox outbox, ConnectionFactory connectionFactory) {
        this.mailingListService = mailingListService;
        this.mailingListExecutionService = mailingListExecutionService;
        this.outbox = outbox;
        this.connectionFactory = connectionFactory;
    }

    @Value("${niord.jms.topic.messagestatustopic:messageStatus}")
    String messageStatusTopic;

    // NOT covered by the MDC TaskDecorator (org.niord.core.conf.CorrelationConfig): this is a plain
    // java.util.concurrent pool, and it runs one long-lived poll loop rather than discrete submitted
    // tasks — so there is no calling context to inherit and nothing for a decorator to wrap. Giving
    // these lines a correlation id means minting one per RECEIVED JMS MESSAGE inside the loop, which
    // is a separate change. See org.niord.core.util.MdcContext.
    private final ExecutorService scheduler = Executors.newSingleThreadExecutor();

    /** How long the poll loop waits before it subscribes again after losing its subscription (tests set it low) */
    long reconnectDelayMillis = 5000;

    /**
     * Takes the rows committed before the start (a status change just before a restart, whose topic message no
     * listener received) at once, then polls the topic. A row committed between this pass and the subscription waits
     * for the next {@link #sweep}.
     */
    @EventListener(ApplicationReadyEvent.class)
    void onStart() {
        scheduler.submit(() -> drainLogged("at start"));
        scheduler.submit(this);
    }

    @EventListener(ContextClosedEvent.class)
    void onStop() {
        // shutdownNow interrupts the loop, also while it waits to subscribe again
        scheduler.shutdownNow();
    }

    @Override
    public void run() {
        JmsTopicLoop.consume(connectionFactory, messageStatusTopic, scheduler::isShutdown, reconnectDelayMillis,
                this::onMessage, log);
    }

    /**
     * A status change was committed: its outbox row is due, so the outbox is drained now. The topic's message is only
     * the prompt; what is mailed comes from the rows. It is broadcast after the producing transaction commits (see
     * {@code MessageService.sendStatusUpdate}), so the row and the message are visible here.
     */
    public void onMessage(jakarta.jms.Message message) {
        try {
            log.debug("Received a message status update, draining the status-change outbox");
            drainOutbox();
        } catch (Throwable e) {
            log.error("Failed draining the status-change outbox on JMS message " + message, e);
        }
    }

    /**
     * Once a minute (second 23, before the mail sender's run at second 24 and clear of the scheduled triggers' run
     * at second 37): takes the rows the topic did not prompt for, and the rows whose lease ran out. When a drain is
     * already running on this node it returns at once: that drain takes every due row. It warns when that drain has
     * run one row for a long time ({@link #warnIfStuck}).
     */
    @Scheduled(cron = "${niord.mailinglist.status-change-sweep-cron:23 * * * * ?}")
    void sweep() {
        if (!drainLock.tryLock()) {
            warnIfStuck();
            return;
        }
        try {
            drainLogged("by the sweep");
        } finally {
            drainLock.unlock();
        }
    }

    /** {@link #drainOutbox}, with a failure logged rather than thrown (the start and the sweep) */
    private void drainLogged(String when) {
        try {
            int done = drainOutbox();
            if (done > 0) {
                log.info("Made the mailing lists' mails of {} status change(s) {}", done, when);
            }
        } catch (Throwable e) {
            log.error("Failed draining the status-change outbox " + when + "; its rows are tried again", e);
        }
    }

    /**
     * Takes the due rows of the status-change outbox one at a time, oldest first, runs the status-change triggers of
     * each ({@link #checkStatusChangeMailingListExecution}, where a failing trigger leaves an ERROR record in the Mail
     * Queue, an {@link Error} included) and deletes it. A row whose triggers cannot even be looked up (the database is
     * down) keeps its error and is tried again when it comes due, and the pass stops there. A row whose triggers ran
     * but whose delete fails (the database lost for a moment) is taken again when its lease runs out, and its
     * triggers' mails are made again. A row taken too often is given up ({@link #giveUp}). One drain runs at a time on
     * this node.
     *
     * @return the number of status changes whose triggers were run
     */
    public int drainOutbox() {
        drainLock.lock();
        try {
            int done = 0;
            for (Optional<Claim> next = outbox.claimNextDue(); next.isPresent(); next = outbox.claimNextDue()) {
                Claim claim = next.get();
                running = new Running(claim, System.currentTimeMillis());
                boolean givenUp = claim.attempts() > MailingListStatusChangeOutbox.MAX_ATTEMPTS;
                try {
                    if (givenUp) {
                        giveUp(claim);
                    } else {
                        checkStatusChangeMailingListExecution(claim.messageUid(), claim.status());
                    }
                } catch (Throwable e) {
                    // Throwable: an Error (a template that recurses without end) must not leave the row without its
                    // error, to be taken again with no trace
                    log.error("Failed running the status-change triggers of message " + claim.messageUid() + " in status "
                            + claim.status() + "; tried again when its outbox row comes due", e);
                    try {
                        outbox.failed(claim.id(), e.toString());
                    } catch (Throwable recordFailure) {
                        // The lease taken with the row makes it due again all the same
                        log.error("Failed recording the failure in the status-change outbox row " + claim.id(),
                                recordFailure);
                    }
                    return done;
                } finally {
                    running = null;
                }
                outbox.complete(claim.id());
                if (!givenUp) {
                    done++;
                }
            }
            return done;
        } finally {
            drainLock.unlock();
        }
    }

    /**
     * Gives up a row taken more than {@link MailingListStatusChangeOutbox#MAX_ATTEMPTS} times: its run failed every
     * time, hung, or stopped the server, and every run made the mails of the triggers before the faulty one again.
     * Its triggers are not run again: each leaves its "Not sent" ERROR record in the Mail Queue, as a failing trigger
     * does, and the row is then deleted. A failure to look up the triggers propagates, so the row is kept and the
     * record is made on a later pass.
     */
    private void giveUp(Claim claim) {
        String cause = "Given up after " + (claim.attempts() - 1) + " attempts to make the mails of the status change to "
                + claim.status() + " (each failed, hung or stopped the server; see the server log)";
        log.error("Gave up the status-change outbox row " + claim.id() + " of message " + claim.messageUid()
                + " in status " + claim.status() + ": " + cause + "; its triggers are recorded as not sent");
        List<Integer> triggerIds = mailingListService.findStatusChangeTriggers(claim.status()).stream()
                .map(BaseEntity::getId)
                .collect(Collectors.toList());
        for (Integer triggerId : triggerIds) {
            recordTriggerFailure(triggerId, claim.messageUid(), new Exception(cause));
        }
    }

    /**
     * Once a minute, while a drain holds this node's lock: a WARN when the drain has run one row for longer than
     * {@link #stuckAfterMillis} (a trigger that hangs holds back every status-change mail of this node)
     *
     * @return whether it warned
     */
    boolean warnIfStuck() {
        Running current = running;
        if (current == null) {
            return false;
        }
        long millis = System.currentTimeMillis() - current.since();
        if (millis < stuckAfterMillis) {
            return false;
        }
        Claim claim = current.claim();
        log.warn("The status-change outbox row {} of message {} in status {} has run for {} s; no other status-change "
                        + "mail is made on this node until it ends", claim.id(), claim.messageUid(), claim.status(),
                millis / 1000);
        return true;
    }

    /**
     * Handle mailing list execution for the message: every status-change trigger on the status is executed for it.
     *
     * @param messageUid the UID of the message
     * @param status the message status
     */
    public void checkStatusChangeMailingListExecution(String messageUid, Status status) {

        long t0 = System.currentTimeMillis();

        List<Integer> triggerIds = mailingListService.findStatusChangeTriggers(status).stream()
                .map(BaseEntity::getId)
                .collect(Collectors.toList());

        log.debug(String.format("Found %d status change triggers for %s in status %s",
                triggerIds.size(),
                messageUid,
                status));

        for (Integer triggerId : triggerIds) {
            try {
                // NB: This function requires a new transaction
                mailingListExecutionService.executeStatusChangeTrigger(triggerId, messageUid);
            } catch (Throwable e) {
                // Throwable: a template or script can throw an Error (a StackOverflowError from a macro that
                // recurses without end); it is this trigger's failure, not the status change's
                log.error("Error executing status-change mailing-list trigger " + triggerId
                        + " for message " + messageUid, e);
                recordTriggerFailure(triggerId, messageUid,
                        e instanceof Exception exception ? exception : new Exception(e.toString(), e));
            }
        }
        log.debug(String.format("Executed %d status change triggers for %s in status %s in %d ms",
                triggerIds.size(),
                messageUid,
                status,
                System.currentTimeMillis() - t0));
    }

    /** Leaves a record in the Mail Queue, so the mail that was due does not vanish without a trace */
    private void recordTriggerFailure(Integer triggerId, String messageUid, Exception cause) {
        try {
            mailingListExecutionService.recordStatusChangeTriggerFailure(triggerId, messageUid, cause);
        } catch (Throwable recordFailure) {
            log.error("Failed recording the failure of status-change mailing-list trigger " + triggerId
                    + " for message " + messageUid, recordFailure);
        }
    }

}
