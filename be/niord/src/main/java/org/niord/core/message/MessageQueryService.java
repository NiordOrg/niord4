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

import jakarta.persistence.NoResultException;
import org.niord.core.service.BaseService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * Read-only lookups of {@link Message} entities.
 * <p>
 * Extracted from {@code MessageService} as a dependency-free leaf (only the {@code EntityManager}
 * inherited from {@link BaseService}). The message indexers and the promulgation services need to
 * load messages without depending on the full {@code MessageService} — which orchestrates them on
 * save — so these reads live here to keep the bean graph acyclic.
 */
@Service
public class MessageQueryService extends BaseService {

    /**
     * Returns the message with the given uid
     *
     * @param uid the id of the message
     * @return the message with the given id or null if not found
     * @throws jakarta.persistence.PersistenceException if the lookup fails: a failure is not reported as "not found"
     */
    public Message findByUid(String uid) {
        try {
            return em.createNamedQuery("Message.findByUid", Message.class)
                    .setParameter("uid", uid)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Returns all messages updated after the given date
     *
     * @param date     the date
     * @param maxCount the max number of entries to return
     * @return all messages updated after the given date
     */
    public List<Message> findUpdatedMessages(Date date, int maxCount) {
        return em
                .createNamedQuery("Message.findUpdateMessages", Message.class)
                .setParameter("date", date)
                .setMaxResults(maxCount)
                .getResultList();
    }

    /**
     * Returns the messages after the given one in the order of their {@code updated} time and id: those
     * updated later, and those updated at the same time with a greater id. Unlike
     * {@link #findUpdatedMessages(Date, int)}, a page that ends inside a run of messages sharing one
     * {@code updated} time (a bulk load writes thousands) leaves the rest of the run for the next page.
     *
     * @param date     the {@code updated} time of the last message already read
     * @param id       the id of the last message already read
     * @param maxCount the max number of entries to return
     * @return the messages after the given one, oldest first
     */
    public List<Message> findUpdatedMessagesAfter(Date date, int id, int maxCount) {
        return em
                .createNamedQuery("Message.findUpdatedMessagesAfter", Message.class)
                .setParameter("date", date)
                .setParameter("id", id)
                .setMaxResults(maxCount)
                .getResultList();
    }

    /**
     * How far behind its marker a free-text index pass looks for messages it missed. A message's
     * {@code updated} time is stamped when its transaction writes it, and transactions commit in another order:
     * a message stamped before a pass read past its time, and committed after that pass, lies below the marker,
     * and the pass after reads only what comes after the marker (LUCENE-6). Each pass therefore also indexes the
     * messages of this window behind the marker that it has not indexed as they are now. A transaction that takes
     * longer than this from writing a message to its commit is still missed.
     */
    public static final Duration INDEX_LOOK_BACK = Duration.ofMinutes(5);

    /**
     * The {@code updated} time and the id of a message
     *
     * @param id      the id of the message
     * @param updated its {@code updated} time, as the database stores it
     */
    public record UpdatedStamp(int id, Date updated) {
    }

    /**
     * Returns the {@code updated} time and the id of the messages after the first given one and up to and
     * including the second, in the order of their {@code updated} time and id: the window behind an index
     * pass's marker, read without loading the messages. Called again with the last stamp it returned, it
     * reads the next page.
     *
     * @param afterDate the {@code updated} time the window starts after (with {@code Integer.MAX_VALUE} as
     *                  {@code afterId}, every message of that time is left out)
     * @param afterId   the id the window starts after, among the messages of {@code afterDate}
     * @param toDate    the {@code updated} time of the last message of the window
     * @param toId      the id of the last message of the window
     * @param maxCount  the max number of entries to return
     * @return the stamps, oldest first
     */
    public List<UpdatedStamp> findUpdatedStampsBetween(Date afterDate, int afterId, Date toDate, int toId,
                                                       int maxCount) {
        List<Object[]> rows = em.createQuery(
                        "select msg.id, msg.updated from Message msg "
                                + "where (msg.updated > :afterDate or (msg.updated = :afterDate and msg.id > :afterId)) "
                                + "and (msg.updated < :toDate or (msg.updated = :toDate and msg.id <= :toId)) "
                                + "order by msg.updated asc, msg.id asc", Object[].class)
                .setParameter("afterDate", afterDate)
                .setParameter("afterId", afterId)
                .setParameter("toDate", toDate)
                .setParameter("toId", toId)
                .setMaxResults(maxCount)
                .getResultList();
        List<UpdatedStamp> stamps = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            stamps.add(new UpdatedStamp(((Number) row[0]).intValue(), (Date) row[1]));
        }
        return stamps;
    }

    /**
     * Returns the messages with the given ids, in the order of their {@code updated} time and id
     *
     * @param ids the ids of the messages
     * @return the messages that exist, oldest first
     */
    public List<Message> findByIds(Collection<Integer> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return em.createQuery("select msg from Message msg where msg.id in :ids "
                        + "order by msg.updated asc, msg.id asc", Message.class)
                .setParameter("ids", ids)
                .getResultList();
    }
}
