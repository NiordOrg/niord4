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

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PessimisticLockException;
import org.hibernate.Session;
import org.hibernate.exception.LockAcquisitionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.model.message.Status;
import org.niord.test.DeliveryFixtures;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.niord.test.DeliveryFixtures.uniq;

/**
 * The auto-publish sweep and a lock conflict (SCHED3, the orchestrator's decision 15): when one message's publication
 * fails because it met another transaction's lock, a deadlock ({@code 40P01}) or a lock timeout ({@code 55P03}), the
 * message stays VERIFIED and the next minute's sweep publishes it. Any other failure still sets the message back to
 * DRAFT. A due warning must not end among the drafts because two transactions crossed.
 *
 * <p>The sweep is called through its seam ({@code publishDueMessages}) with this test's own domain, and the fixtures
 * are committed with {@link DeliveryFixtures} and removed after each test. The fault is started inside the message's
 * own unit: the spy on {@link MessageQueryService} acts on the first load of the message in a transaction after it is
 * armed, which is the load at the start of {@code MessageService.updateStatus}. The spy gives this class an application
 * context of its own; {@link DirtiesContext} closes it after the class.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "niord.schedule.lock-timeout=2s")
class ScheduledPublishLockConflictITTest extends ITTest {

    @MockitoSpyBean
    MessageQueryService messageQueryService;

    @Autowired
    ScheduledMessageService scheduledMessageService;

    @Autowired
    DataSource dataSource;

    @Autowired
    ApplicationContext context;

    @PersistenceContext
    EntityManager em;

    private DeliveryFixtures fixtures;

    private String series;

    private String domain;

    @BeforeEach
    void ownSeriesAndDomain() {
        fixtures = new DeliveryFixtures(context, em);
        String n = uniq();
        series = fixtures.createYearlyNwSeries("it-spl-" + n, "SPL");
        domain = fixtures.createDomain("it-spl-d-" + n, series);
    }

    @AfterEach
    void removeTheFixtures() {
        fixtures.removeAll();
    }

    /** What the armed spy does in the unit, before the message is loaded */
    @FunctionalInterface
    private interface Fault {
        void run() throws Exception;
    }

    /**
     * Arms the spy: the next load of the message inside a transaction runs the fault first, once. Armed after the
     * fixtures and the preconditions, which load the message too.
     */
    private void onTheNextLoadOf(String uid, Fault fault) {
        AtomicBoolean armed = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (uid.equals(invocation.getArgument(0))
                    && TransactionSynchronizationManager.isActualTransactionActive()
                    && armed.compareAndSet(true, false)) {
                fault.run();
            }
            return invocation.callRealMethod();
        }).when(messageQueryService).findByUid(anyString());
    }

    /**
     * Arms the spy the other way round: the next load of the message inside a transaction loads it first and then
     * runs the fault, once, so the unit holds the message as it was before the fault.
     */
    private void afterTheNextLoadOf(String uid, Fault fault) {
        AtomicBoolean armed = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object loaded = invocation.callRealMethod();
            if (uid.equals(invocation.getArgument(0))
                    && TransactionSynchronizationManager.isActualTransactionActive()
                    && armed.compareAndSet(true, false)) {
                fault.run();
            }
            return loaded;
        }).when(messageQueryService).findByUid(anyString());
    }

    /** The exception chain a deadlock reaches the sweep with: JPA's, around Hibernate's, around the driver's */
    private static PessimisticLockException deadlock() {
        return new PessimisticLockException("IT: deadlock detected", new LockAcquisitionException(
                "IT: could not execute statement", new SQLException("ERROR: deadlock detected", "40P01")));
    }

    /**
     * A deadlock: the publication of A is chosen as the deadlock's victim. A stays VERIFIED, with no new DRAFT entry
     * in its history, and B beside it is published. The next minute publishes A.
     *
     * <p>Before the fix A was set back to DRAFT, where only a duty officer would find it.
     */
    @Test
    void aWarningWhosePublicationLosesADeadlockStaysVerifiedAndIsPublishedTheNextMinute() {
        String a = fixtures.verifiedDue(series, "IT spl A " + uniq());
        String b = fixtures.verifiedDue(series, "IT spl B " + uniq());
        onTheNextLoadOf(a, () -> {
            throw deadlock();
        });

        assertEquals(List.of(a), scheduledMessageService.publishDueMessages(Set.of(domain)),
                "A's publication failed this minute");
        assertEquals(Status.PUBLISHED, fixtures.statusOf(b), "B is published");
        assertEquals(Status.VERIFIED, fixtures.statusOf(a), "A stays VERIFIED: a deadlock is no fault of the message");
        assertEquals(Status.VERIFIED, fixtures.historyStatuses(a).get(0),
                "A's newest history entry is still VERIFIED: it was never set back to DRAFT");

        assertTrue(scheduledMessageService.publishDueMessages(Set.of(domain)).isEmpty(),
                "the next minute publishes A");
        assertEquals(Status.PUBLISHED, fixtures.statusOf(a), "A is published the next minute");
        assertNotNull(fixtures.numberOf(a), "A is numbered when it is published");
    }

    /**
     * A real lock timeout from PostgreSQL: another transaction holds A's row ({@code SELECT ... FOR UPDATE}) while the
     * sweep publishes A, and A's unit waits for it at most one second ({@code SET LOCAL lock_timeout}). The driver's
     * {@code 55P03} reaches the sweep through Hibernate and JPA, and A stays VERIFIED. When the other transaction has
     * ended, the next minute publishes A.
     *
     * <p>The sweep runs on a thread of its own, as on the scheduler. Before the fix the DRAFT fallback then waited for
     * the same row, with no time limit: the test ends the other transaction after eight seconds, the fallback goes
     * through, and A is DRAFT.
     */
    @Test
    void aWarningWhosePublicationTimesOutOnAnotherTransactionsLockStaysVerified() throws Exception {
        String a = fixtures.verifiedDue(series, "IT spl lock " + uniq());
        String b = fixtures.verifiedDue(series, "IT spl free " + uniq());

        ExecutorService sweepThread = Executors.newSingleThreadExecutor();
        Connection holder = dataSource.getConnection();
        try {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT 1 FROM \"Message\" WHERE \"uid\" = ? FOR UPDATE")) {
                lock.setString(1, a);
                try (ResultSet rs = lock.executeQuery()) {
                    assertTrue(rs.next(), "precondition: the other transaction holds A's row");
                }
            }

            onTheNextLoadOf(a, () -> em.unwrap(Session.class).doWork(connection -> {
                try (Statement s = connection.createStatement()) {
                    s.execute("SET LOCAL lock_timeout = '1s'");
                }
            }));

            Future<List<String>> sweep = sweepThread.submit(
                    () -> scheduledMessageService.publishDueMessages(Set.of(domain)));
            List<String> failed;
            try {
                failed = sweep.get(8, TimeUnit.SECONDS);
            } catch (TimeoutException stillWaiting) {
                // Something else of A's waits for the row without a time limit: end the other transaction
                holder.rollback();
                failed = sweep.get(60, TimeUnit.SECONDS);
            }
            holder.rollback();

            assertEquals(List.of(a), failed, "A's publication failed this minute");
            assertEquals(Status.PUBLISHED, fixtures.statusOf(b), "B is published");
            assertEquals(Status.VERIFIED, fixtures.statusOf(a),
                    "A stays VERIFIED: a lock timeout is no fault of the message");
            assertEquals(Status.VERIFIED, fixtures.historyStatuses(a).get(0),
                    "A's newest history entry is still VERIFIED: it was never set back to DRAFT");
        } finally {
            holder.rollback();
            holder.close();
            sweepThread.shutdownNow();
        }

        assertTrue(scheduledMessageService.publishDueMessages(Set.of(domain)).isEmpty(),
                "the next minute publishes A, now that the other transaction has ended");
        assertEquals(Status.PUBLISHED, fixtures.statusOf(a), "A is published the next minute");
    }

    /**
     * A real version conflict (the review's finding R2): A's unit has loaded A when another transaction commits a
     * change of A (a drag in the list, an editor's save), so the unit's flush updates no row and Hibernate throws an
     * optimistic-lock failure, which carries no SQLState. That is two transactions crossing as much as a deadlock is:
     * A stays VERIFIED, with no DRAFT entry, and the next minute publishes it. The same failure comes when two
     * publications in two series of one recording publication's domain change its tag at once.
     *
     * <p>Before the fix A was set back to DRAFT.
     */
    @Test
    void aWarningWhosePublicationMeetsANewerVersionStaysVerifiedAndIsPublishedTheNextMinute() {
        String a = fixtures.verifiedDue(series, "IT spl stale " + uniq());
        afterTheNextLoadOf(a, () -> {
            try (Connection other = dataSource.getConnection();
                 PreparedStatement bump = other.prepareStatement(
                         "UPDATE \"Message\" SET \"version\" = \"version\" + 1 WHERE \"uid\" = ?")) {
                other.setAutoCommit(true);
                bump.setString(1, a);
                assertEquals(1, bump.executeUpdate(), "precondition: another transaction changes A");
            }
        });

        assertEquals(List.of(a), scheduledMessageService.publishDueMessages(Set.of(domain)),
                "A's publication failed this minute");
        assertEquals(Status.VERIFIED, fixtures.statusOf(a),
                "A stays VERIFIED: a newer version written by another transaction is no fault of the message");
        assertEquals(Status.VERIFIED, fixtures.historyStatuses(a).get(0),
                "A's newest history entry is still VERIFIED: it was never set back to DRAFT");

        assertTrue(scheduledMessageService.publishDueMessages(Set.of(domain)).isEmpty(),
                "the next minute publishes A");
        assertEquals(Status.PUBLISHED, fixtures.statusOf(a), "A is published the next minute");
    }

    /**
     * The sweep waits for a series' number at most its lock timeout (the review's finding R3, with P1-3): another
     * transaction has taken the next number of A's series and not committed (a publication over REST that stalls
     * before its commit). A's unit waits for the number row; after {@code niord.schedule.lock-timeout} (two seconds
     * in this class) it fails with a lock timeout, A stays VERIFIED, and the sweep goes on: B, due in another series
     * and another domain, is published. When the other transaction has ended, the next minute publishes A.
     *
     * <p>Before the fix nothing limited the wait: the sweep's one thread stood still on A, and no due warning of any
     * domain was published while the other transaction lasted. The test ends the other transaction after fifteen
     * seconds when the sweep still waits.
     */
    @Test
    void aSweepThatWaitsForASeriesNumberGivesUpAfterItsLockTimeoutAndGoesOn() throws Exception {
        String otherSeries = fixtures.createYearlyNwSeries("it-spl2-" + uniq(), "SPM");
        String otherDomain = fixtures.createDomain("it-spl2-d-" + uniq(), otherSeries);
        String a = fixtures.verifiedDue(series, "IT spl number held " + uniq());
        String b = fixtures.verifiedDue(otherSeries, "IT spl number free " + uniq());

        ExecutorService sweepThread = Executors.newSingleThreadExecutor();
        Connection holder = dataSource.getConnection();
        boolean waitedWithoutLimit = false;
        List<String> failed;
        try {
            holder.setAutoCommit(false);
            try (PreparedStatement take = holder.prepareStatement("INSERT INTO \"Sequence\" (name, \"nextValue\")"
                    + " VALUES (?, 2) ON CONFLICT (name) DO UPDATE SET \"nextValue\" = \"Sequence\".\"nextValue\" + 1")) {
                take.setString(1, String.format(MessageSeriesService.NUMBER_SEQUENCE_TYPE_YEARLY, series,
                        DeliveryFixtures.PAST_YEAR));
                take.executeUpdate();
            }

            Future<List<String>> sweep = sweepThread.submit(
                    () -> scheduledMessageService.publishDueMessages(Set.of(domain, otherDomain)));
            try {
                failed = sweep.get(15, TimeUnit.SECONDS);
            } catch (TimeoutException stillWaiting) {
                waitedWithoutLimit = true;
                holder.rollback();
                failed = sweep.get(60, TimeUnit.SECONDS);
            }
            holder.rollback();
        } finally {
            holder.rollback();
            holder.close();
            sweepThread.shutdownNow();
        }

        assertFalse(waitedWithoutLimit, "the sweep must not wait for a series' number without a limit");
        assertEquals(List.of(a), failed, "A's publication failed this minute");
        assertEquals(Status.PUBLISHED, fixtures.statusOf(b), "B, in another series and domain, is published");
        assertEquals(Status.VERIFIED, fixtures.statusOf(a), "A stays VERIFIED");
        assertEquals(Status.VERIFIED, fixtures.historyStatuses(a).get(0),
                "A's newest history entry is still VERIFIED: it was never set back to DRAFT");

        assertTrue(scheduledMessageService.publishDueMessages(Set.of(domain, otherDomain)).isEmpty(),
                "the next minute publishes A, now that the other transaction has ended");
        assertEquals(Status.PUBLISHED, fixtures.statusOf(a), "A is published the next minute");
    }

    /**
     * Any other database failure keeps the DRAFT fallback: a unique-key violation ({@code 23505}) is a fault of the
     * message, which would fail again every minute. A is set back to DRAFT, with a DRAFT entry in its history.
     */
    @Test
    void aWarningWhosePublicationFailsForAnotherDatabaseReasonIsSetBackToDraft() {
        String a = fixtures.verifiedDue(series, "IT spl dup " + uniq());
        onTheNextLoadOf(a, () -> {
            throw new PersistenceException("IT: duplicate key", new org.hibernate.exception.ConstraintViolationException(
                    "IT: could not execute statement",
                    new SQLException("ERROR: duplicate key value violates unique constraint", "23505"), "IT"));
        });

        assertEquals(List.of(a), scheduledMessageService.publishDueMessages(Set.of(domain)),
                "A's publication failed");
        assertEquals(Status.DRAFT, fixtures.statusOf(a), "A is set back to DRAFT, where a duty officer finds it");
        assertEquals(Status.DRAFT, fixtures.historyStatuses(a).get(0), "A's history records the DRAFT change");
    }
}
