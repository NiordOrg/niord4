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
package org.niord.core.message;

import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import org.hibernate.StaleObjectStateException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.LockAcquisitionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which failures of a scheduled publication count as two transactions crossing (the orchestrator's decision 15, and
 * the review's finding R2): those leave the message VERIFIED for the next minute, every other failure sets it back to
 * DRAFT. Besides a deadlock and a lock timeout, a version conflict (another transaction changed the message or a
 * recording publication's tag first) and a connection the unit could not get in time are crossings too: none of them
 * is a fault of the message, and the next minute's unit reads everything afresh.
 */
class ScheduledMessageServiceTest {

    @Test
    void aDeadlockAndALockTimeoutAreConcurrencyConflicts() {
        assertTrue(ScheduledMessageService.isConcurrencyConflict(new PersistenceException("deadlock",
                new LockAcquisitionException("x", new SQLException("ERROR: deadlock detected", "40P01")))));
        assertTrue(ScheduledMessageService.isConcurrencyConflict(new PersistenceException("timeout",
                new SQLException("ERROR: canceling statement due to lock timeout", "55P03"))));
    }

    @Test
    void aVersionConflictIsAConcurrencyConflict() {
        // What em.flush() throws when another transaction committed a newer version of the message or of a tag
        assertTrue(ScheduledMessageService.isConcurrencyConflict(new OptimisticLockException("Row was updated",
                new StaleObjectStateException("org.niord.core.publication.MessageTag", 1))));
        // What Spring throws when the same conflict comes at the commit
        assertTrue(ScheduledMessageService.isConcurrencyConflict(
                new ObjectOptimisticLockingFailureException("org.niord.core.message.Message", 1)));
        assertTrue(ScheduledMessageService.isConcurrencyConflict(new CannotAcquireLockException("lock")));
    }

    @Test
    void aConnectionNotGotInTimeIsAConcurrencyConflict() {
        assertTrue(ScheduledMessageService.isConcurrencyConflict(new CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out"))));
    }

    @Test
    void anyOtherFailureIsNot() {
        assertFalse(ScheduledMessageService.isConcurrencyConflict(new PersistenceException("duplicate key",
                new ConstraintViolationException("x",
                        new SQLException("ERROR: duplicate key value violates unique constraint", "23505"), "c"))));
        assertFalse(ScheduledMessageService.isConcurrencyConflict(new IllegalStateException("Invalid status transition")));
        assertFalse(ScheduledMessageService.isConcurrencyConflict(new RuntimeException("a promulgation hook failed")));
    }
}
