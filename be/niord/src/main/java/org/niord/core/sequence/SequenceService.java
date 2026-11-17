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
package org.niord.core.sequence;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Provides an interface for managing sequences.
 * <p>
 * Every value is read and changed by one SQL statement on the sequence's row, never by reading the row into the
 * persistence context and writing an absolute value back: two callers at the same moment cannot both take one value,
 * and a reset is never overwritten by a value read before it. A statement that changes the row (take, lock, reset)
 * holds the row's lock until the transaction it ran in ends, so another caller of the same sequence waits for that
 * commit or rollback and then works on the value it left. The statements run through the session's JDBC connection
 * ({@code Session.doWork}): they neither flush the session nor put a {@link SequenceEntity} in it.
 */
@Service
public class SequenceService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SequenceService.class);

    /** Takes the next value and returns it; the first take creates the row at the initial value + 1 */
    private static final String TAKE_NEXT_VALUE =
            "INSERT INTO \"Sequence\" (name, \"nextValue\") VALUES (?, ?) "
            + "ON CONFLICT (name) DO UPDATE SET \"nextValue\" = \"Sequence\".\"nextValue\" + 1 "
            + "RETURNING \"nextValue\" - 1";

    /** Locks the row without changing its value; a missing row is created at the initial value */
    private static final String LOCK_NEXT_VALUE =
            "INSERT INTO \"Sequence\" (name, \"nextValue\") VALUES (?, ?) "
            + "ON CONFLICT (name) DO UPDATE SET \"nextValue\" = \"Sequence\".\"nextValue\"";

    /** Sets the next value, creating the row when it is missing */
    private static final String SET_NEXT_VALUE =
            "INSERT INTO \"Sequence\" (name, \"nextValue\") VALUES (?, ?) "
            + "ON CONFLICT (name) DO UPDATE SET \"nextValue\" = EXCLUDED.\"nextValue\"";

    private static final String PEEK_NEXT_VALUE = "SELECT \"nextValue\" FROM \"Sequence\" WHERE name = ?";

    @Inject
    protected EntityManager em;

    /**
     * Peeks the next value of the given sequence. Joins the caller's transaction, so it sees what that transaction
     * took or set; a value another transaction holds is read as last committed.
     *
     * @param sequence the sequence
     * @return the next value
     */
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public long peekNextValue(Sequence sequence) {
        Long value = em.unwrap(Session.class).doReturningWork(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(PEEK_NEXT_VALUE)) {
                ps.setString(1, sequence.getName());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : null;
                }
            }
        });
        return value != null ? value : sequence.initialValue();
    }

    /**
     * Resets the next value of the given sequence to the initial value. Joins the caller's transaction: the reset
     * waits for a transaction that holds the sequence (one that took a value and has not ended), and a value taken
     * after it is taken from the reset value.
     *
     * @param sequence the sequence
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void resetNextValue(Sequence sequence) {
        execute(SET_NEXT_VALUE, sequence.getName(), sequence.initialValue());
    }

    /**
     * Takes the next value of the given sequence inside the caller's transaction, which must exist. The sequence's
     * row stays locked until that transaction ends: another caller of the same sequence waits for it, and when it
     * rolls back the value is given back.
     *
     * @param sequence the sequence
     * @return the value taken
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long takeNextValue(Sequence sequence) {
        return take(sequence);
    }

    /**
     * Locks the given sequence's row until the caller's transaction ends, which must exist, without changing its
     * next value (a missing row is created at the initial value, which is the value a peek reports for it). A
     * caller that will take a value later in the transaction, after it has changed other rows, takes this lock
     * first, so it never waits for the sequence while it holds rows that a caller holding the sequence wants next.
     *
     * @param sequence the sequence
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockNextValue(Sequence sequence) {
        execute(LOCK_NEXT_VALUE, sequence.getName(), sequence.initialValue());
    }

    /**
     * Returns and bumps the next value of the given sequence in a transaction of its own, committed at once: a value
     * taken here is never given back. For numbers that need no give-back, such as the batch jobs'.
     *
     * @param sequence the sequence
     * @return the next value
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long nextValue(Sequence sequence) {
        return take(sequence);
    }

    /** The one statement that takes a value, in the current transaction */
    private long take(Sequence sequence) {
        return em.unwrap(Session.class).doReturningWork(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(TAKE_NEXT_VALUE)) {
                ps.setString(1, sequence.getName());
                ps.setLong(2, sequence.initialValue() + 1);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    /** Runs an upsert of the sequence's row with the given value, in the current transaction */
    private void execute(String sql, String name, long value) {
        em.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setLong(2, value);
                ps.executeUpdate();
            }
        });
    }
}
