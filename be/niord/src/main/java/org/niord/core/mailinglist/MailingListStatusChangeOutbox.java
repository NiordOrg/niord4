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
package org.niord.core.mailinglist;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.query.NativeQuery;
import org.niord.model.message.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The mailing lists' outbox of status changes (table {@code "MailingListStatusChange"}, changeset V012): the durable
 * record that a message reached a status, so its status-change triggers are due.
 * <p>
 * {@link #enqueue} writes one row in the status change's own transaction ({@code MessageService.updateStatus}, and
 * the cancellation warning it makes): the row commits with the status change, or rolls back with it, and a failure to
 * write it fails the status change. The JMS status topic that announces the change is not stored, and a restart, a
 * broker error or a listener subscribing again loses its message; the row is not lost. {@code MailingListMessageListener}
 * takes the due rows one at a time ({@link #claimNextDue}), runs their triggers and deletes them ({@link #complete}),
 * when the topic prompts it, once a minute and once at start.
 * <p>
 * Each row records which of its status's triggers are settled ({@code "settledTriggers"}): a trigger is settled when its
 * mails are queued, or when its failure is recorded in the Mail Queue, and {@link #settle} writes the mark in that
 * same transaction, so the mark commits exactly when the mails or the record do. A row is deleted only once every
 * trigger is settled; a run that leaves a trigger owed (its mails failed and so did its failure record) keeps the row,
 * and a later run runs only the triggers still owed. So a node stopped in the middle of a row, or a failed delete,
 * makes no trigger's mails twice, and no trigger is dropped unsettled. One duplicate is left: two nodes running the
 * same trigger of a row at once, which takes a trigger that runs longer than the row's {@link #claimLease lease} (the
 * other node then takes the row again); the chart runs one node. A row is run at most {@link #MAX_ATTEMPTS} times: a
 * row whose run keeps failing, hangs or stops the server is then given up, and each trigger still owed leaves a record
 * in the Mail Queue; a row whose record cannot be saved either stays, tried seldom ({@link #failedLastingly}). A row
 * whose status is not a {@link Status} has no such bound (its triggers never run).
 * <p>
 * Native SQL only, like {@code DefaultsLedger}: the table has no entity, and its identity id draws on no shared
 * sequence. It depends on nothing in {@code org.niord.core.message}, so {@code MessageService} uses it without a cycle.
 */
@Service
public class MailingListStatusChangeOutbox {

    /**
     * A row taken for its triggers: its id, the message and status of the status change, how many times the row has
     * been taken, this time included, and the ids of the triggers already settled by an earlier take
     */
    public record Claim(long id, String messageUid, Status status, int attempts, Set<Integer> settledTriggers) {

        public Claim {
            settledTriggers = Set.copyOf(settledTriggers);
        }

        /** A row taken for the given time, no trigger settled */
        public Claim(long id, String messageUid, Status status, int attempts) {
            this(id, messageUid, status, attempts, Set.of());
        }

        /** A row taken for the first time */
        public Claim(long id, String messageUid, Status status) {
            this(id, messageUid, status, 1);
        }
    }

    /**
     * How many times a row's triggers are run at most. A row taken more often than this (its run failed every time,
     * hung, or stopped the server) is given up: {@code MailingListMessageListener} records each trigger still owed in
     * the Mail Queue and deletes it, without running its triggers again.
     */
    public static final int MAX_ATTEMPTS = 5;

    private static final Logger log = LoggerFactory.getLogger(MailingListStatusChangeOutbox.class);

    /** The table, as Hibernate's query space */
    static final String TABLE = "MailingListStatusChange";

    /**
     * How long a taken row stays out of every other node's reach ({@link #claimNextDue}): the time the triggers of
     * one status change may take, many times over (each trigger renders its template and queues its mails in one
     * transaction). It is also how long a row whose node died while running it, or still ran it when the stop ended
     * its wait, waits before it is tried again; a row whose run failed, or that a stop left owing triggers, is
     * recorded ({@link #failed}) and tried again sooner. Package-private so the ITs can shorten it.
     */
    Duration claimLease = Duration.ofMinutes(10);

    @PersistenceContext
    EntityManager em;

    /**
     * Records the status change of the given message, in the caller's transaction: the row commits or rolls back with
     * the status change, and a failure here fails the status change. Called once per status change.
     *
     * @param messageUid the UID of the message
     * @param status the status the message changed to
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void enqueue(String messageUid, Status status) {
        em.createNativeQuery("INSERT INTO public.\"MailingListStatusChange\" "
                        + "(\"messageUid\", status, created, \"dueAt\") VALUES (:uid, :status, now(), now())")
                // Names the one table it writes, so Hibernate flushes nothing else of the status change's session
                // for it: an insert into a table no entity maps, with no lock on any other row
                .unwrap(NativeQuery.class)
                .addSynchronizedQuerySpace(TABLE)
                .setParameter("uid", messageUid)
                .setParameter("status", status.name())
                .executeUpdate();
    }

    /**
     * Takes the oldest due row, if any, in a short transaction of its own, before any trigger runs. The row is read
     * {@code FOR UPDATE SKIP LOCKED}, so a node taking a row at the same moment takes another one instead of waiting,
     * and its {@code "dueAt"} is moved to the end of the {@link #claimLease lease}, so no other node sees it as due
     * while its triggers run. {@link #complete} deletes it afterwards; a row whose run never ends (its node stopped) is
     * due again when the lease runs out, and a row whose run failed sooner ({@link #failed}).
     * <p>
     * A row whose status is not a {@link Status} (a status renamed or removed since the row was written, or a row
     * written by hand) cannot be run. It keeps the lease, its {@code "lastError"} says why, it is logged as an ERROR,
     * and the next due row is taken instead, so it never holds back the status changes after it.
     *
     * @return the row taken, or nothing when no row is due
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Claim> claimNextDue() {
        while (true) {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery("SELECT id, \"messageUid\", status, attempts, "
                            + "array_to_string(\"settledTriggers\", ',') "
                            + "FROM public.\"MailingListStatusChange\" WHERE \"dueAt\" <= now() "
                            + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED")
                    .getResultList();
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            Object[] row = rows.get(0);
            long id = ((Number) row[0]).longValue();
            String messageUid = (String) row[1];
            String status = (String) row[2];
            int attempts = ((Number) row[3]).intValue() + 1;
            Set<Integer> settled = parseTriggerIds((String) row[4]);
            // The lease first: a row whose status cannot be read is then no longer due, and the next pass of this
            // loop takes the row after it
            write("UPDATE public.\"MailingListStatusChange\" "
                            + "SET \"dueAt\" = now() + make_interval(secs => :seconds), attempts = attempts + 1 "
                            + "WHERE id = :id")
                    .setParameter("seconds", claimLease.toMillis() / 1000.0)
                    .setParameter("id", id)
                    .executeUpdate();
            Optional<Status> known = knownStatus(status);
            if (known.isPresent()) {
                return Optional.of(new Claim(id, messageUid, known.get(), attempts, settled));
            }
            String error = "Unknown message status '" + status + "'";
            log.error("Skipped the status-change outbox row {} of message {}: {}; tried again when its lease runs out",
                    id, messageUid, error);
            recordError(id, error);
        }
    }

    /** The trigger ids of {@code array_to_string("settledTriggers", ',')}: none for an empty or null text */
    private static Set<Integer> parseTriggerIds(String ids) {
        Set<Integer> parsed = new LinkedHashSet<>();
        if (ids != null && !ids.isBlank()) {
            for (String id : ids.split(",")) {
                parsed.add(Integer.valueOf(id.trim()));
            }
        }
        return parsed;
    }

    /**
     * Marks a trigger of the row as settled: its mails are queued, or its failure is recorded in the Mail Queue (or
     * the trigger is gone, and there is nothing to make). It MUST run in the transaction that queues those mails or
     * saves that record ({@code MailingListExecutionService.executeStatusChangeTrigger} and
     * {@code recordStatusChangeTriggerFailure}), so the mark commits or rolls back with them: a later take of the row
     * then runs this trigger again exactly when its mails or its record were not committed.
     *
     * @param id the id of the row taken
     * @param triggerId the id of the trigger settled
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settle(long id, int triggerId) {
        write("UPDATE public.\"MailingListStatusChange\" "
                        + "SET \"settledTriggers\" = array_append(\"settledTriggers\", :trigger) "
                        + "WHERE id = :id AND NOT (:trigger = ANY (\"settledTriggers\"))")
                .setParameter("trigger", triggerId)
                .setParameter("id", id)
                .executeUpdate();
    }

    /** The status of the given name, or nothing when no status has it */
    private static Optional<Status> knownStatus(String name) {
        for (Status status : Status.values()) {
            if (status.name().equals(name)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }

    /**
     * Deletes the row once every one of its triggers is settled ({@link #settle}): each made its mails or recorded its
     * failure in the Mail Queue.
     *
     * @param id the id of the row taken
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(long id) {
        write("DELETE FROM public.\"MailingListStatusChange\" WHERE id = :id")
                .setParameter("id", id)
                .executeUpdate();
    }

    /**
     * Records why the row is kept: its triggers could not be looked up (the database was down), or some of them are
     * still owed (neither their mails nor their failure record could be saved), or the server stopped before it ran
     * them all. Nothing runs the row any more, so it does not wait out the lease it was taken with: it is due again
     * after as many minutes as it has been taken (at most the ten of the lease), and the next sweep takes it then.
     *
     * @param id the id of the row taken
     * @param error what failed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(long id, String error) {
        write("UPDATE public.\"MailingListStatusChange\" SET \"lastError\" = :error, "
                        + "\"dueAt\" = now() + make_interval(mins => LEAST(attempts, :maxMinutes)) WHERE id = :id")
                .setParameter("error", error)
                .setParameter("maxMinutes", (int) Math.max(1, claimLease.toMinutes()))
                .setParameter("id", id)
                .executeUpdate();
    }

    /**
     * How long a row waits between takes once {@link #failedLastingly} keeps it: the "Not sent" record of a trigger it
     * still owes could not be saved on take after take, so it is tried seldom, and the row is never deleted without it
     */
    public static final Duration SLOW_RETRY = Duration.ofHours(1);

    /**
     * Records why a row given up long ago is still kept: the record of a trigger it owes cannot be saved. As
     * {@link #failed}, but the row is due again only after {@link #SLOW_RETRY}.
     *
     * @param id the id of the row taken
     * @param error what failed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failedLastingly(long id, String error) {
        write("UPDATE public.\"MailingListStatusChange\" SET \"lastError\" = :error, "
                        + "\"dueAt\" = now() + make_interval(secs => :seconds) WHERE id = :id")
                .setParameter("error", error)
                .setParameter("seconds", SLOW_RETRY.toMillis() / 1000.0)
                .setParameter("id", id)
                .executeUpdate();
    }

    /**
     * A native write to the table, naming it as its query space: a native write that names no table empties the
     * whole second-level cache when it runs (see be/docs/message-read-benchmark.md)
     */
    @SuppressWarnings("rawtypes")
    private NativeQuery write(String sql) {
        return em.createNativeQuery(sql).unwrap(NativeQuery.class).addSynchronizedQuerySpace(TABLE);
    }

    /** Writes the row's last error, in the caller's transaction */
    private void recordError(long id, String error) {
        write("UPDATE public.\"MailingListStatusChange\" SET \"lastError\" = :error WHERE id = :id")
                .setParameter("error", error)
                .setParameter("id", id)
                .executeUpdate();
    }
}
