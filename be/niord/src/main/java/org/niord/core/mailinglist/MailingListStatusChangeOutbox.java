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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

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
 * Delivery is at least once: a node stopped after some of a row's triggers made their mails, and before the row was
 * deleted, makes those mails again when the row's lease runs out. A duplicate mail is rare (a node stopped in the
 * middle of a status change's triggers) and far safer for a navigational warning than a lost one.
 * <p>
 * Native SQL only, like {@code DefaultsLedger}: the table has no entity, and its identity id draws on no shared
 * sequence. It depends on nothing in {@code org.niord.core.message}, so {@code MessageService} uses it without a cycle.
 */
@Service
public class MailingListStatusChangeOutbox {

    /** A row taken for its triggers: its id, and the message and status of the status change */
    public record Claim(long id, String messageUid, Status status) {
    }

    /** The table, as Hibernate's query space */
    static final String TABLE = "MailingListStatusChange";

    /**
     * How long a taken row stays out of every other node's reach ({@link #claimNextDue}): the time the triggers of
     * one status change may take, many times over (each trigger renders its template and queues its mails in one
     * transaction). It is also how long a row whose run failed, or whose node stopped, waits before it is tried
     * again. Package-private so the ITs can shorten it.
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
     * while its triggers run. {@link #complete} deletes it afterwards; a row whose run fails or never ends is due
     * again when the lease runs out.
     *
     * @return the row taken, or nothing when no row is due
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Claim> claimNextDue() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("SELECT id, \"messageUid\", status "
                        + "FROM public.\"MailingListStatusChange\" WHERE \"dueAt\" <= now() "
                        + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED")
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        long id = ((Number) row[0]).longValue();
        em.createNativeQuery("UPDATE public.\"MailingListStatusChange\" "
                        + "SET \"dueAt\" = now() + make_interval(secs => :seconds), attempts = attempts + 1 "
                        + "WHERE id = :id")
                .setParameter("seconds", claimLease.toMillis() / 1000.0)
                .setParameter("id", id)
                .executeUpdate();
        return Optional.of(new Claim(id, (String) row[1], Status.valueOf((String) row[2])));
    }

    /**
     * Deletes the row once its triggers have run, each of them made its mails or recorded its failure in the Mail
     * Queue.
     *
     * @param id the id of the row taken
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(long id) {
        em.createNativeQuery("DELETE FROM public.\"MailingListStatusChange\" WHERE id = :id")
                .setParameter("id", id)
                .executeUpdate();
    }

    /**
     * Records why the row's triggers could not be run (the triggers could not be looked up, the database was down).
     * The row keeps its {@code "dueAt"} at the end of the lease it was taken with, and is tried again then.
     *
     * @param id the id of the row taken
     * @param error what failed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(long id, String error) {
        em.createNativeQuery("UPDATE public.\"MailingListStatusChange\" SET \"lastError\" = :error WHERE id = :id")
                .setParameter("error", error)
                .setParameter("id", id)
                .executeUpdate();
    }
}
