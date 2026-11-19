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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.niord.core.mailinglist.MailingListStatusChangeOutbox.Claim;
import org.niord.core.model.BaseEntity;
import org.niord.core.util.JmsTopicLoop;
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
 * at a time, in the order they were written, runs the status-change triggers of each that are still owed, and deletes
 * it once every trigger is settled: its mails queued, or its failure recorded in the Mail Queue, each marked on the row
 * in the same transaction ({@link MailingListStatusChangeOutbox#settle}). A trigger whose mails fail and whose failure
 * record fails too stays owed: the row is kept and a later take runs only the triggers still owed, so the lists that
 * got their mail do not get it again ({@link #drainOutbox}). It does so when the JMS status topic announces a status change (the topic's message is only a
 * prompt to look now; it is broadcast after the commit, so the row is visible), once a minute
 * ({@link #sweep}, {@code niord.mailinglist.status-change-sweep-cron}; {@code -} disables it), and once at start, so a
 * status change whose topic message was lost (a restart, a broker error, the listener subscribing again) is still
 * mailed. One drain runs at a time on a node; nodes sharing a database take different rows (see
 * {@link MailingListStatusChangeOutbox#claimNextDue}).
 * <p>
 * Every drain runs on the listener's own worker thread ({@code niord-mailinglist-drain}), never on the thread that asks
 * for it: the JMS poll loop and the shared scheduler (the thread pool every {@code @Scheduled} job runs on, among them
 * the publication and expiry of messages and the mail sender) only hand the drain to the worker and return at once. A
 * trigger that hangs, or a long backlog, therefore holds back only the status-change mails after it, on this node. At
 * most one drain waits behind the running one, however often it is asked for ({@link #requestDrain}), and the sweep
 * asks for none while a drain runs or waits.
 * <p>
 * The order holds on one node while no row fails. Nodes sharing a database take rows independently (the drain lock is
 * per node), so across nodes two status changes of one message may be mailed at once or in reverse with no failure at
 * all; the chart runs one replica. A row whose triggers could not be looked up, or that still owes a trigger, waits a
 * few minutes (one more for each time it was taken, at most its lease), and the passes meanwhile take the rows after
 * it (the pass that kept a row owing a trigger goes straight on to them): a later status change of the same message (CANCELLED after PUBLISHED) may then be mailed before it. A
 * row whose node stopped while running it waits out its lease. Such a row is tried again with an ERROR each time; its
 * {@code attempts} and {@code "lastError"} show how long it has failed. Once the context closes, the drain starts no
 * further trigger and takes no further row. A row taken more than {@link MailingListStatusChangeOutbox#MAX_ATTEMPTS}
 * times (its run failed, hung or stopped the server each time) is given up: each trigger it still owes leaves a "Not
 * sent" ERROR record in the Mail Queue, and the row is deleted once each is recorded. A record that cannot be saved is
 * tried on as many further takes; on the last ({@link #LAST_TAKE}) the row is deleted all the same, with one ERROR in
 * the server log naming each such trigger, its list and the message.
 * <p>
 * The topic is polled on a single-thread executor, started when the application is ready and stopped when the context
 * closes ({@link JmsTopicLoop}). When the context closes the drain takes no further row; a row it is running then is
 * finished if it ends within {@link #stopWaitMillis}, and otherwise keeps its lease and is taken again once that runs
 * out, by this node's next start or by another node.
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

    /** The name of the drain worker's thread */
    static final String DRAIN_THREAD_NAME = "niord-mailinglist-drain";

    /**
     * Where the drains run: by default a worker of the listener's own, one daemon thread named
     * {@value #DRAIN_THREAD_NAME}, shared with nothing else
     */
    private final Executor drainWorker;

    /** A drain was handed to the worker and has not started yet: no further one is handed over meanwhile */
    private final AtomicBoolean drainQueued = new AtomicBoolean();

    /** The context is closing: the drain takes no further row, and no drain is handed over */
    private volatile boolean stopping;

    /** How long the close waits for a drain in flight to end its row (tests set it low) */
    long stopWaitMillis = Duration.ofSeconds(10).toMillis();

    @Autowired
    MailingListMessageListener(MailingListService mailingListService, MailingListExecutionService mailingListExecutionService,
                               MailingListStatusChangeOutbox outbox, ConnectionFactory connectionFactory) {
        this(mailingListService, mailingListExecutionService, outbox, connectionFactory, newDrainWorker());
    }

    /** With the given worker for the drains (tests pass one that runs them on the caller's thread) */
    MailingListMessageListener(MailingListService mailingListService, MailingListExecutionService mailingListExecutionService,
                               MailingListStatusChangeOutbox outbox, ConnectionFactory connectionFactory,
                               Executor drainWorker) {
        this.mailingListService = mailingListService;
        this.mailingListExecutionService = mailingListExecutionService;
        this.outbox = outbox;
        this.connectionFactory = connectionFactory;
        this.drainWorker = drainWorker;
    }

    /**
     * One daemon thread, so a drain that hangs never holds the JVM, with a queue of one: {@link #requestDrain} hands
     * over at most one drain while another runs, and the queue refuses a second rather than grow
     */
    private static ExecutorService newDrainWorker() {
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, DRAIN_THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), threads);
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
     * listener received) at once, on the drain worker, and polls the topic. A row committed between this pass and the
     * subscription waits for the next {@link #sweep}.
     */
    @EventListener(ApplicationReadyEvent.class)
    void onStart() {
        requestDrain("at start");
        scheduler.submit(this);
    }

    /**
     * Stops the poll loop and the drain worker. The drain takes no further row; the one it runs, if any, is not
     * interrupted (an interrupted trigger would be recorded in the Mail Queue as failed) and is waited for up to
     * {@link #stopWaitMillis}. A row still running after that keeps its lease and is taken again once it runs out.
     */
    @EventListener(ContextClosedEvent.class)
    void onStop() {
        stopping = true;
        // shutdownNow interrupts the loop, also while it waits to subscribe again
        scheduler.shutdownNow();
        if (drainWorker instanceof ExecutorService worker) {
            worker.shutdown();
            try {
                if (!worker.awaitTermination(stopWaitMillis, TimeUnit.MILLISECONDS)) {
                    Running current = running;
                    log.warn("The status-change mail drain did not end within {} ms of the stop{}", stopWaitMillis,
                            current == null ? "" : "; the outbox row " + current.claim().id() + " of message "
                                    + current.claim().messageUid() + " is taken again when its lease runs out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void run() {
        JmsTopicLoop.consume(connectionFactory, messageStatusTopic, scheduler::isShutdown, reconnectDelayMillis,
                this::onMessage, log);
    }

    /**
     * A status change was committed: its outbox row is due, so a drain is handed to the worker now, and the poll loop
     * goes back to the topic at once. The topic's message is only the prompt; what is mailed comes from the rows. It is
     * broadcast after the producing transaction commits (see {@code MessageService.sendStatusUpdate}), so the row is
     * visible to the drain. While a drain runs, one more is handed over, which takes the rows committed after the
     * running one last looked.
     */
    public void onMessage(jakarta.jms.Message message) {
        log.debug("Received a message status update, draining the status-change outbox");
        requestDrain(PROMPT);
    }

    /** What asks for the drain on the topic's prompt: its drains are logged at DEBUG, one comes with every status change */
    private static final String PROMPT = "on the status topic's prompt";

    /**
     * Once a minute (second 23, before the mail sender's run at second 24 and clear of the scheduled triggers' run
     * at second 37): takes the rows the topic did not prompt for, and the rows whose lease ran out. It runs on the
     * shared scheduler, so it only hands the drain to the worker and returns at once. When a drain is already running
     * or waiting on this node it hands over none (that drain takes every due row, and the next minute's sweep the rows
     * that come due after it), and warns when the running drain has run one row for a long time
     * ({@link #warnIfStuck}).
     */
    @Scheduled(cron = "${niord.mailinglist.status-change-sweep-cron:23 * * * * ?}")
    void sweep() {
        if (drainLock.isLocked() || drainQueued.get()) {
            warnIfStuck();
            return;
        }
        requestDrain("by the sweep");
    }

    /**
     * Hands a drain to the worker, unless one is already waiting there: that one has not started, so it will take
     * every row committed before this call. So at most one drain waits behind the running one, however often a drain
     * is asked for. Returns at once.
     *
     * @param when for the log: what asked for the drain
     */
    void requestDrain(String when) {
        if (stopping || !drainQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            drainWorker.execute(() -> {
                drainQueued.set(false);
                drainLogged(when);
            });
        } catch (RejectedExecutionException e) {
            // The worker has stopped (the context is closing); the rows stay for the next start
            drainQueued.set(false);
            log.debug("No status-change outbox drain {}: the drain worker has stopped", when);
        }
    }

    /** Whether a drain waits on the worker and has not started yet (for the tests) */
    boolean drainQueued() {
        return drainQueued.get();
    }

    /**
     * {@link #drainOutbox}, with a failure logged rather than thrown: it runs on the worker, which a failure never
     * stops
     */
    private void drainLogged(String when) {
        try {
            int done = drainOutbox();
            if (done > 0 && PROMPT.equals(when)) {
                log.debug("Made the mailing lists' mails of {} status change(s) {}", done, when);
            } else if (done > 0) {
                log.info("Made the mailing lists' mails of {} status change(s) {}", done, when);
            }
        } catch (Throwable e) {
            log.error("Failed draining the status-change outbox " + when + "; its rows are tried again", e);
        }
    }

    /**
     * Takes the due rows of the status-change outbox one at a time, oldest first, runs the status-change triggers each
     * still owes ({@link #checkStatusChangeMailingListExecution}, where a failing trigger leaves an ERROR record in the
     * Mail Queue, an {@link Error} included) and deletes it once every trigger is settled. A row that still owes a
     * trigger (its mails failed and so did its failure record, or the context closed before it ran) is kept with its
     * error and tried again within minutes, running only the triggers still owed, and the pass goes on with the rows
     * after it. A row whose triggers cannot even be looked up (the database is down) is kept the same way, and the pass
     * stops there, as it does when a row cannot be kept or deleted. A row whose delete fails (the database lost for a
     * moment) is taken again when its lease runs out, and then only deleted: every trigger is settled. A row taken too
     * often is given up ({@link #giveUp}). One drain runs at a time on this node.
     *
     * @return the number of status changes whose triggers were run
     */
    public int drainOutbox() {
        drainLock.lock();
        try {
            int done = 0;
            while (!stopping) {
                // Once the context closes no further row is taken: a row taken now would wait out its lease after the
                // stop, rather than be taken by the next start's pass
                Optional<Claim> next = outbox.claimNextDue();
                if (next.isEmpty()) {
                    break;
                }
                Claim claim = next.get();
                running = new Running(claim, System.currentTimeMillis());
                boolean givenUp = claim.attempts() > MailingListStatusChangeOutbox.MAX_ATTEMPTS;
                List<Integer> owed;
                try {
                    owed = givenUp ? giveUp(claim) : checkStatusChangeMailingListExecution(claim);
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
                if (!owed.isEmpty()) {
                    // The row is out of this pass's reach (made due again in minutes), so the pass goes on with the
                    // rows after it; it stops only when the row could not even be kept
                    if (!keepOwed(claim, owed)) {
                        return done;
                    }
                    continue;
                }
                try {
                    outbox.complete(claim.id());
                } catch (Throwable e) {
                    log.error("Failed deleting the status-change outbox row " + claim.id() + " of message "
                            + claim.messageUid() + " in status " + claim.status() + " after every trigger was "
                            + "settled; it is deleted, without running a trigger again, when its lease runs out", e);
                    return done;
                }
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
     * Keeps a row that still owes triggers: writes why on it and makes it due again within minutes
     * ({@link MailingListStatusChangeOutbox#failed}), with one ERROR for this attempt. The attempt counts toward the
     * bound, after which the triggers still owed are given up ({@link #giveUp}).
     *
     * @return whether the row was kept so; false when that failed too (the database is lost), and the lease taken
     *         with the row makes it due again
     */
    private boolean keepOwed(Claim claim, List<Integer> owed) {
        String error = stopping
                ? "The server stopped before trigger(s) " + owed + " ran"
                : "Trigger(s) " + owed + " neither made their mails nor recorded their failure in the Mail Queue";
        log.error("Kept the status-change outbox row " + claim.id() + " of message " + claim.messageUid()
                + " in status " + claim.status() + " after attempt " + claim.attempts() + " of "
                + MailingListStatusChangeOutbox.MAX_ATTEMPTS + ": " + error + "; only those are run again, when the row "
                + "comes due" + (stopping ? "" : " within minutes (see the errors above)"));
        try {
            outbox.failed(claim.id(), error);
            return true;
        } catch (Throwable recordFailure) {
            // The lease taken with the row makes it due again all the same
            log.error("Failed recording the owed triggers in the status-change outbox row " + claim.id(),
                    recordFailure);
            return false;
        }
    }

    /**
     * Gives up a row taken more than {@link MailingListStatusChangeOutbox#MAX_ATTEMPTS} times: its run failed every
     * time, hung, or stopped the server. Its triggers are not run again: each trigger it still owes leaves its "Not
     * sent" ERROR record in the Mail Queue, naming its list, as a failing trigger does; the triggers settled before
     * leave none. A failure to look up the triggers propagates, so the row is kept and the records are made on a later
     * pass, and so is a trigger whose record cannot be saved: it stays owed, at most until the row's
     * {@link #LAST_TAKE}. There a record that still cannot be saved is given up too: the row is deleted, and one ERROR
     * in the server log, naming each such trigger, its list and the message, is all that is left of it.
     *
     * @return the triggers still owed: those whose record could not be saved, none on the last take
     */
    private List<Integer> giveUp(Claim claim) {
        String cause = "Given up after " + (claim.attempts() - 1) + " attempts to make the mails of the status change to "
                + claim.status() + " (each failed, hung or stopped the server; see the server log)";
        List<MailingListTrigger> triggers = mailingListService.findStatusChangeTriggers(claim.status()).stream()
                .filter(trigger -> !claim.settledTriggers().contains(trigger.getId()))
                .toList();
        log.error("Gave up the status-change outbox row " + claim.id() + " of message " + claim.messageUid()
                + " in status " + claim.status() + ": " + cause + "; its trigger(s) still owed "
                + triggers.stream().map(BaseEntity::getId).toList() + " are recorded as not sent");
        List<MailingListTrigger> unrecorded = new ArrayList<>();
        for (MailingListTrigger trigger : triggers) {
            if (!recordTriggerFailure(trigger.getId(), claim, new Exception(cause))) {
                unrecorded.add(trigger);
            }
        }
        if (!unrecorded.isEmpty() && claim.attempts() >= LAST_TAKE) {
            log.error("Deleted the status-change outbox row " + claim.id() + " of message " + claim.messageUid()
                    + " in status " + claim.status() + " after " + claim.attempts() + " takes: the mails of "
                    + unrecorded.stream().map(MailingListMessageListener::describe).collect(Collectors.joining(", "))
                    + " were not made, and the record of that could not be saved in the Mail Queue either "
                    + "(see the errors above); this line is the only record left");
            return List.of();
        }
        return unrecorded.stream().map(BaseEntity::getId).toList();
    }

    /**
     * The take on which a given-up row is deleted even when the record of a trigger still owed cannot be saved: the
     * records are tried on as many takes as the triggers were ({@link MailingListStatusChangeOutbox#MAX_ATTEMPTS}),
     * so a record that can never be saved (a list whose recipient the Mail Queue refuses) does not keep its row for
     * ever
     */
    static final int LAST_TAKE = 2 * MailingListStatusChangeOutbox.MAX_ATTEMPTS;

    /** A trigger and its list, for the log */
    private static String describe(MailingListTrigger trigger) {
        MailingList list = trigger.getMailingList();
        return "trigger " + trigger.getId() + (list == null ? "" : " of mailing list " + list.getMailingListId());
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
     * Handle mailing list execution for an outbox row: every status-change trigger on the row's status that the row
     * still owes is executed for its message, and settles on the row with its mails, or with the record of its failure.
     * A trigger settled by an earlier take is not run again. Once the context closes no further trigger is started.
     *
     * @param claim the outbox row taken
     * @return the triggers still owed: those whose mails and failure record both failed, or that the close left
     */
    public List<Integer> checkStatusChangeMailingListExecution(Claim claim) {

        long t0 = System.currentTimeMillis();
        String messageUid = claim.messageUid();

        List<Integer> triggerIds = mailingListService.findStatusChangeTriggers(claim.status()).stream()
                .map(BaseEntity::getId)
                .filter(id -> !claim.settledTriggers().contains(id))
                .collect(Collectors.toList());

        log.debug(String.format("Found %d status change triggers still owed for %s in status %s",
                triggerIds.size(),
                messageUid,
                claim.status()));

        List<Integer> owed = new ArrayList<>();
        for (Integer triggerId : triggerIds) {
            if (stopping) {
                owed.add(triggerId);
                continue;
            }
            try {
                // NB: This function requires a new transaction, in which the trigger is settled with its mails
                mailingListExecutionService.executeStatusChangeTrigger(triggerId, messageUid, claim.id());
            } catch (Throwable e) {
                // Throwable: a template or script can throw an Error (a StackOverflowError from a macro that
                // recurses without end); it is this trigger's failure, not the status change's
                log.error("Error executing status-change mailing-list trigger " + triggerId
                        + " for message " + messageUid, e);
                if (!recordTriggerFailure(triggerId, claim,
                        e instanceof Exception exception ? exception : new Exception(e.toString(), e))) {
                    owed.add(triggerId);
                }
            }
        }
        log.debug(String.format("Executed %d status change triggers for %s in status %s in %d ms, %d still owed",
                triggerIds.size(),
                messageUid,
                claim.status(),
                System.currentTimeMillis() - t0,
                owed.size()));
        return owed;
    }

    /**
     * Leaves a record in the Mail Queue, so the mail that was due does not vanish without a trace, and settles the
     * trigger on the row with it
     *
     * @return whether the record was saved (or the trigger is gone, with nothing to record): false leaves the trigger
     *         owed
     */
    private boolean recordTriggerFailure(Integer triggerId, Claim claim, Exception cause) {
        try {
            mailingListExecutionService.recordStatusChangeTriggerFailure(triggerId, claim.messageUid(), cause,
                    claim.id());
            return true;
        } catch (Throwable recordFailure) {
            log.error("Failed recording the failure of status-change mailing-list trigger " + triggerId
                    + " for message " + claim.messageUid() + "; the trigger stays owed and the status change is kept",
                    recordFailure);
            return false;
        }
    }

}
