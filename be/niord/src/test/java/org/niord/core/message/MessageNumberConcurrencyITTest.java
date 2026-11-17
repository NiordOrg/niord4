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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.promulgation.PromulgationException;
import org.niord.core.promulgation.PromulgationManager;
import org.niord.model.message.Status;
import org.niord.test.DeliveryFixtures;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.niord.test.DeliveryFixtures.uniq;

/**
 * Message numbers taken by transactions at the same moment (SERIES5 and SERIES6 in be/docs/potential-errors.md).
 * A number is taken with one statement on the series' number row inside the publication's own transaction, and the
 * row stays locked until that transaction ends: two publications in one series take turns and get two numbers, the
 * first number of a year is taken twice without a failure, a sysadmin's reset waits for a publication that holds the
 * number and is kept (refused when it goes below the number that publication took, SERIES-RESET), a publication
 * that fails gives its number back, and a cancellation that issues a cancellation
 * warning locks the number before it changes anything, as a publication does.
 *
 * <p>Every case runs its transactions on threads of its own, with no transaction of the test around them, so what
 * they do is committed. The series are this class's own (and a domain, where the publication sweep needs one), made
 * with {@link DeliveryFixtures}; the messages and series are removed after each test, and so are the series' number
 * rows, which the series' delete leaves behind (SERIES7). A case that waits on a lock asserts that the waiting call
 * has not finished after a second, and then lets the holder end.
 *
 * <p>The promulgation spy gives this class an application context of its own; {@link DirtiesContext} closes it after
 * the class, as {@code DeliveryFaultInjectionITTest} does.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MessageNumberConcurrencyITTest extends ITTest {

    /** How long a call that must wait on a lock is given before the test asserts that it still waits */
    private static final long WAITS_SECONDS = 1;

    /** How long a call that must finish is given */
    private static final long FINISHES_SECONDS = 60;

    @MockitoSpyBean
    PromulgationManager promulgationManager;

    @Autowired
    MessageService messageService;

    @Autowired
    MessageSeriesService messageSeriesService;

    @Autowired
    ScheduledMessageService scheduledMessageService;

    @Autowired
    ApplicationContext context;

    @PersistenceContext
    EntityManager em;

    private DeliveryFixtures fixtures;
    private final List<String> seriesIds = new ArrayList<>();
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        fixtures = new DeliveryFixtures(context, em);
        pool = Executors.newFixedThreadPool(3);
    }

    @AfterEach
    void tearDown() throws Exception {
        pool.shutdownNow();
        pool.awaitTermination(FINISHES_SECONDS, TimeUnit.SECONDS);
        fixtures.removeAll();
        for (String seriesId : seriesIds) {
            try {
                Tx.requiringNew().run(() -> em.createNativeQuery("DELETE FROM \"Sequence\" WHERE name LIKE ?1")
                        .setParameter(1, "MESSAGE_SERIES_NUMBER_" + seriesId + "%")
                        .executeUpdate());
            } catch (Exception e) {
                System.err.println("MessageNumberConcurrencyITTest: number rows of " + seriesId + " left: " + e);
            }
        }
        seriesIds.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // Fixtures
    // ------------------------------------------------------------------------------------------- //

    /** A committed YEARLY numbered NW series of this test, in no domain: it numbers in the JVM's zone */
    private String series() {
        String seriesId = fixtures.createYearlyNwSeries("it-num-" + uniq(), "NC");
        seriesIds.add(seriesId);
        return seriesId;
    }

    /** Noon of 15 June of the given year */
    private static Date dateIn(int year) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, Calendar.JUNE, 15, 12, 0, 0);
        return c.getTime();
    }

    /** The current year in the JVM's zone, the year a cancellation warning of a series in no domain numbers in */
    private static int currentYear() {
        return Calendar.getInstance().get(Calendar.YEAR);
    }

    /** Publishes the message in a committed transaction of its own and returns its number */
    private Integer publish(String uid) {
        return Tx.requiringNew().call(() -> messageService.updateStatus(uid, Status.PUBLISHED).getNumber());
    }

    /** The committed next number of the series in the year */
    private int nextNumber(String seriesId, int year) {
        return Tx.requiringNew().call(() -> messageSeriesService.getNextMessageNumber(seriesId, year));
    }

    /** The number of the committed cancellation warning that cancels the message */
    private Integer cancellationWarningNumber(String uid) {
        return Tx.requiringNew().call(() -> em.createQuery("select m.number from Message m join m.references r "
                        + "where m.kind = :kind and r.messageId = :uid", Integer.class)
                .setParameter("kind", MessageKind.CANCELLATION)
                .setParameter("uid", uid)
                .getSingleResult());
    }

    /** Takes a number of the series in a transaction that stays open until the release, then commits */
    private Future<Integer> takeAndHold(String seriesId, int year, CountDownLatch taken, CountDownLatch release) {
        return pool.submit(() -> Tx.requiringNew().call(() -> {
            Integer number = messageSeriesService.newMessageNumber(seriesId, year);
            taken.countDown();
            assertTrue(release.await(FINISHES_SECONDS, TimeUnit.SECONDS), "the holder was released");
            return number;
        }));
    }

    /** Asserts that the call has not finished within a second: it waits on a lock */
    private static void assertWaits(Future<?> call, String message) {
        assertThrows(TimeoutException.class, () -> call.get(WAITS_SECONDS, TimeUnit.SECONDS), message);
    }

    private static List<Integer> sorted(Integer... numbers) {
        return Stream.of(numbers).sorted().toList();
    }

    // ------------------------------------------------------------------------------------------- //
    // (1) Two publications at one moment
    // ------------------------------------------------------------------------------------------- //

    /**
     * Two VERIFIED warnings of one series are published at the same moment, each in a transaction of its own. They
     * get two numbers, 1 and 2, and the series' next number is 3. Before the fix both could read the row before
     * either committed and take the same number.
     */
    @Test
    void twoPublicationsInOneSeriesAtOneMomentTakeTwoNumbers() throws Exception {
        String s = series();
        String a = fixtures.verifiedDue(s, "IT num A " + uniq());
        String b = fixtures.verifiedDue(s, "IT num B " + uniq());
        CyclicBarrier barrier = new CyclicBarrier(2);

        Future<Integer> fa = pool.submit(() -> {
            barrier.await(FINISHES_SECONDS, TimeUnit.SECONDS);
            return publish(a);
        });
        Future<Integer> fb = pool.submit(() -> {
            barrier.await(FINISHES_SECONDS, TimeUnit.SECONDS);
            return publish(b);
        });

        assertEquals(List.of(1, 2), sorted(fa.get(FINISHES_SECONDS, TimeUnit.SECONDS),
                fb.get(FINISHES_SECONDS, TimeUnit.SECONDS)), "two numbers, one each");
        assertEquals(3, fixtures.nextNumber(s), "the series' next number is the one after both");
    }

    // ------------------------------------------------------------------------------------------- //
    // (2) The first number of a year
    // ------------------------------------------------------------------------------------------- //

    /**
     * The first number of a year, which has no number row yet, taken twice: the first transaction creates the row
     * and holds it, the second waits for it instead of failing on the row's key, and then takes 2.
     */
    @Test
    void theFirstNumberOfAYearTakenTwiceGivesOneAndTwoAndNeitherFails() throws Exception {
        String s = series();
        int year = 2002;
        CountDownLatch taken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<Integer> first = takeAndHold(s, year, taken, release);
        assertTrue(taken.await(FINISHES_SECONDS, TimeUnit.SECONDS), "the first took its number");
        Future<Integer> second = pool.submit(() ->
                Tx.requiringNew().call(() -> messageSeriesService.newMessageNumber(s, year)));
        assertWaits(second, "the second waits for the first's new row");

        release.countDown();
        assertEquals(1, first.get(FINISHES_SECONDS, TimeUnit.SECONDS), "the first takes 1");
        assertEquals(2, second.get(FINISHES_SECONDS, TimeUnit.SECONDS), "the second takes 2, without a failure");
        assertEquals(3, nextNumber(s, year));
    }

    /** The first number of a year taken by a transaction that rolls back goes to the one that waited for it. */
    @Test
    void theFirstNumberOfAYearGivenBackGoesToTheNextTaker() throws Exception {
        String s = series();
        int year = 2004;
        CountDownLatch taken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<Integer> failing = pool.submit(() -> Tx.requiringNew().call(() -> {
            messageSeriesService.newMessageNumber(s, year);
            taken.countDown();
            assertTrue(release.await(FINISHES_SECONDS, TimeUnit.SECONDS), "the holder was released");
            throw new IllegalStateException("IT: the first publication of the year fails");
        }));
        assertTrue(taken.await(FINISHES_SECONDS, TimeUnit.SECONDS), "the first took its number");
        Future<Integer> next = pool.submit(() ->
                Tx.requiringNew().call(() -> messageSeriesService.newMessageNumber(s, year)));
        assertWaits(next, "the next waits for the first");

        release.countDown();
        assertThrows(Exception.class, () -> failing.get(FINISHES_SECONDS, TimeUnit.SECONDS), "the first failed");
        assertEquals(1, next.get(FINISHES_SECONDS, TimeUnit.SECONDS), "the next takes the number given back");
        assertEquals(2, nextNumber(s, year));
    }

    // ------------------------------------------------------------------------------------------- //
    // (3) A reset while a publication holds the number
    // ------------------------------------------------------------------------------------------- //

    /**
     * A publication has taken a number and not yet committed when a sysadmin sets the next number to 50. The reset
     * waits for the publication, is then written, and is kept: the next publication takes 50. Before the fix a
     * publication that had read the row before the reset could write its own value over it.
     */
    @Test
    void aResetWaitsForAPublicationThatHoldsTheNumberAndIsKept() throws Exception {
        String s = series();
        int year = 2003;
        CountDownLatch taken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<Integer> publication = takeAndHold(s, year, taken, release);
        assertTrue(taken.await(FINISHES_SECONDS, TimeUnit.SECONDS), "the publication took its number");
        Future<?> reset = pool.submit(() ->
                Tx.requiringNew().run(() -> messageSeriesService.setNextMessageNumber(s, year, 50)));
        assertWaits(reset, "the reset waits for the publication that holds the series' number");

        release.countDown();
        assertEquals(1, publication.get(FINISHES_SECONDS, TimeUnit.SECONDS));
        reset.get(FINISHES_SECONDS, TimeUnit.SECONDS);
        assertEquals(50, nextNumber(s, year), "the reset is kept");

        String m = fixtures.verified(s, "IT num after reset " + uniq(), dateIn(year));
        assertEquals(50, publish(m), "the next publication takes the reset number");
    }

    /**
     * A publication has taken number 1 and not yet committed when a sysadmin sets the next number to 1 (the number
     * the popover showed before the publication took it). The reset waits for the publication, then reads the numbers
     * the series has used, the publication's among them, and is refused: the next number stays 2. Before SERIES-RESET
     * was fixed the reset was kept, and the next publication took 1 again.
     */
    @Test
    void aResetToTheNumberAPublicationInFlightTakesWaitsForItAndIsRefused() throws Exception {
        String s = series();
        String a = fixtures.verifiedDue(s, "IT num in flight " + uniq());
        CountDownLatch taken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<Integer> publication = pool.submit(() -> Tx.requiringNew().call(() -> {
            Integer number = messageService.updateStatus(a, Status.PUBLISHED).getNumber();
            taken.countDown();
            assertTrue(release.await(FINISHES_SECONDS, TimeUnit.SECONDS), "the publication was released");
            return number;
        }));
        assertTrue(taken.await(FINISHES_SECONDS, TimeUnit.SECONDS), "the publication took its number");
        Future<?> reset = pool.submit(() -> Tx.requiringNew().run(() ->
                messageSeriesService.setNextMessageNumber(s, DeliveryFixtures.PAST_YEAR, 1)));
        assertWaits(reset, "the reset waits for the publication that holds the series' number");

        release.countDown();
        assertEquals(1, publication.get(FINISHES_SECONDS, TimeUnit.SECONDS));
        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> reset.get(FINISHES_SECONDS, TimeUnit.SECONDS), "the reset is refused");
        assertInstanceOf(IllegalArgumentException.class, refused.getCause());
        assertEquals(2, fixtures.nextNumber(s), "the series' next number is the one after the publication's");
    }

    // ------------------------------------------------------------------------------------------- //
    // (4) A publication that fails gives its number back
    // ------------------------------------------------------------------------------------------- //

    /**
     * A publication fails after it has taken its number (its transaction rolls back): the warning is still VERIFIED
     * with no number, the series' next number is unchanged, and the next publication takes it. Before the fix the
     * number was committed in a transaction of its own and the series had a gap.
     */
    @Test
    void aPublicationThatFailsAfterTakingItsNumberGivesItBack() {
        String s = series();
        String a = fixtures.verifiedDue(s, "IT num failing " + uniq());

        assertThrows(IllegalStateException.class, () -> Tx.requiringNew().run(() -> {
            assertEquals(1, messageService.updateStatus(a, Status.PUBLISHED).getNumber());
            throw new IllegalStateException("IT: the publication fails after taking its number");
        }));

        assertEquals(Status.VERIFIED, fixtures.statusOf(a), "the publication is rolled back");
        assertNull(fixtures.numberOf(a), "and keeps no number");
        assertEquals(1, fixtures.nextNumber(s), "the number is given back to the series");
        assertEquals(1, publish(a), "and the next publication takes it");
    }

    /**
     * The publication sweep: a due warning's publication fails in the promulgation, after it took its number, and
     * the sweep sets it back to DRAFT. Its unit rolled back, so its number is given back, and the next due warning
     * of the series is published with it. Before the fix the sweep left a gap.
     */
    @Test
    void aScheduledPublicationThatFailsGivesItsNumberBack() throws Exception {
        String s = series();
        String d = fixtures.createDomain("it-num-d-" + uniq(), s);
        String a = fixtures.verifiedDue(s, "IT num sweep A " + uniq());
        doThrow(new PromulgationException("IT: the promulgation of A failed"))
                .when(promulgationManager)
                .onUpdateMessageStatus(argThat((Message m) -> m != null && a.equals(m.getUid())
                        && m.getStatus() == Status.PUBLISHED));

        assertEquals(List.of(a), scheduledMessageService.publishDueMessages(Set.of(d)), "A's publication failed");
        assertEquals(Status.DRAFT, fixtures.statusOf(a), "A is set back to DRAFT");
        assertNull(fixtures.numberOf(a), "A keeps no number");
        assertEquals(1, fixtures.nextNumber(s), "A's number is given back to the series");

        String b = fixtures.verifiedDue(s, "IT num sweep B " + uniq());
        assertTrue(scheduledMessageService.publishDueMessages(Set.of(d)).isEmpty(), "nothing fails the next minute");
        assertEquals(Integer.valueOf(1), fixtures.numberOf(b), "B is published with the number A gave back");
    }

    // ------------------------------------------------------------------------------------------- //
    // (5) A cancellation with its cancellation warning, and a publication
    // ------------------------------------------------------------------------------------------- //

    /**
     * A published warning is cancelled, which issues its cancellation warning numbered in the current year, while
     * another warning of the series dated this year is published: two different numbers, 1 and 2.
     */
    @Test
    void aCancellationWithItsWarningAndAPublicationInOneSeriesTakeTwoNumbers() throws Exception {
        String s = series();
        int year = currentYear();
        String p = fixtures.published(s, "IT num cancelled " + uniq(), null);
        String v = fixtures.verified(s, "IT num published " + uniq(),
                new Date(System.currentTimeMillis() - 120_000L));
        CyclicBarrier barrier = new CyclicBarrier(2);

        Future<?> cancel = pool.submit(() -> {
            barrier.await(FINISHES_SECONDS, TimeUnit.SECONDS);
            return Tx.requiringNew().call(() -> messageService.cancelMessage(p).getUid());
        });
        Future<Integer> publication = pool.submit(() -> {
            barrier.await(FINISHES_SECONDS, TimeUnit.SECONDS);
            return publish(v);
        });
        cancel.get(FINISHES_SECONDS, TimeUnit.SECONDS);
        Integer published = publication.get(FINISHES_SECONDS, TimeUnit.SECONDS);

        assertEquals(Status.CANCELLED, fixtures.statusOf(p));
        assertEquals(List.of(1, 2), sorted(published, cancellationWarningNumber(p)),
                "the cancellation warning and the publication take two numbers");
        assertEquals(3, nextNumber(s, year));
    }

    // ------------------------------------------------------------------------------------------- //
    // (6) The lock order of a cancellation
    // ------------------------------------------------------------------------------------------- //

    /**
     * Another transaction holds the series' number of the current year. A cancellation of a published warning of the
     * series, which will issue a cancellation warning, waits for that number before it changes anything: the
     * cancelled warning's row is not locked while it waits, and it is still PUBLISHED. When the holder commits, the
     * cancellation ends with its cancellation warning numbered after the holder's number. A cancellation that
     * changed the warning first and took the number last could wait for a publication that waits for the warning's
     * recording tag, and the database would roll one of them back.
     */
    @Test
    void aCancellationLocksTheSeriesNumberBeforeItChangesTheWarning() throws Exception {
        String s = series();
        int year = currentYear();
        String p = fixtures.published(s, "IT num lock order " + uniq(), null);
        CountDownLatch taken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<Integer> holder = takeAndHold(s, year, taken, release);
        assertTrue(taken.await(FINISHES_SECONDS, TimeUnit.SECONDS), "the holder took the series' number");
        Future<?> cancel = pool.submit(() -> Tx.requiringNew().call(() -> messageService.cancelMessage(p).getUid()));
        assertWaits(cancel, "the cancellation waits for the series' number");

        // A third connection can lock the warning's row at once: the waiting cancellation has not changed it
        Tx.requiringNew().run(() -> em.createNativeQuery("SELECT id FROM \"Message\" WHERE uid = ?1 FOR UPDATE NOWAIT")
                .setParameter(1, p)
                .getResultList());
        assertEquals(Status.PUBLISHED, fixtures.statusOf(p), "the warning is in force while the cancellation waits");

        release.countDown();
        int held = holder.get(FINISHES_SECONDS, TimeUnit.SECONDS);
        cancel.get(FINISHES_SECONDS, TimeUnit.SECONDS);
        assertEquals(Status.CANCELLED, fixtures.statusOf(p));
        assertEquals(held + 1, cancellationWarningNumber(p), "the cancellation warning is numbered after the holder");
    }
}
