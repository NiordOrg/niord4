/*
 * Copyright 2024 Danish Maritime Authority.
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.mailinglist.MailingListStatusChangeOutbox.Claim;
import org.niord.model.message.Status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for the dispatch logic of the status-change mails: the drain of the mailing lists'
 * outbox ({@link MailingListMessageListener#drainOutbox()}, run on the JMS topic's prompt, by the sweep and at start)
 * and {@link MailingListMessageListener#checkStatusChangeMailingListExecution(String, Status)}, which it runs for each
 * outbox row.
 *
 * <p>They pin the contract: every due row is taken in the outbox's order, its triggers are run and the row is
 * deleted; a row whose triggers cannot be looked up keeps its error and is left for the next pass; the listener looks
 * up the status-change triggers for the row's status, executes each of them for the row's message UID, and keeps going
 * when an individual trigger execution fails (the per-trigger error is logged and recorded in the Mail Queue, so one
 * bad trigger cannot block the rest, an {@link Error} included), and the row is then deleted all the same; a row taken
 * more often than the outbox's bound is given up with a record per trigger; the sweep warns while one row holds the
 * drain.
 *
 * <p>No broker or database is involved: the listener is exercised as a plain object with hand-written recording
 * {@link MailingListService} / {@link MailingListExecutionService} / {@link MailingListStatusChangeOutbox} doubles.
 * The live consumer loop is out of scope here (see docs/untestable-code.md); the real broker delivery is covered by
 * {@code MessageStatusTopicITTest} / {@code MessageStatusPublishITTest}, and the outbox on the database by
 * {@code MailingListDeliveryFaultITTest}.
 */
public class MailingListMessageListenerTest {

    private RecordingMailingListService mailingListService;
    private RecordingMailingListExecutionService executionService;
    private RecordingOutbox outbox;
    private MailingListMessageListener listener;

    @BeforeEach
    public void setUp() {
        mailingListService = new RecordingMailingListService();
        executionService = new RecordingMailingListExecutionService();
        outbox = new RecordingOutbox();
        // The listener's collaborators are wired via a package-private constructor. We pass the recording doubles
        // and `null` for the ConnectionFactory, which only the JMS consumer loop uses (out of scope here).
        listener = new MailingListMessageListener(mailingListService, executionService, outbox, null);
        // PORT: the listener's `log` is now `private static final` (Spring port) and can no longer
        // be replaced with a test logger. The per-trigger error is swallowed and only logged, so the
        // logger has no observable effect on the dispatch contract under test; we let the real logger
        // run. The "keep going after a failing trigger" behaviour is still asserted directly via
        // attemptedTriggerIds vs executedTriggerIds in aFailingTriggerDoesNotPreventTheOthersFromRunning.
    }

    @Test
    public void executesEachStatusChangeTriggerForTheMessageUid() {
        mailingListService.triggers = triggers(10, 20, 30);

        listener.checkStatusChangeMailingListExecution("uid-1", Status.PUBLISHED);

        assertEquals(Status.PUBLISHED, mailingListService.queriedStatus,
                "the lookup must use the broadcast status");
        assertEquals(Arrays.asList(10, 20, 30), executionService.executedTriggerIds,
                "every status-change trigger must be executed once");
        assertEquals(Arrays.asList("uid-1", "uid-1", "uid-1"), executionService.executedUids,
                "every execution must target the broadcast message UID");
    }

    @Test
    public void noTriggersResultsInNoExecutions() {
        mailingListService.triggers = triggers();

        listener.checkStatusChangeMailingListExecution("uid-2", Status.VERIFIED);

        assertEquals(Status.VERIFIED, mailingListService.queriedStatus);
        assertTrue(executionService.executedTriggerIds.isEmpty(),
                "with no matching triggers nothing must be executed");
    }

    @Test
    public void aFailingTriggerDoesNotPreventTheOthersFromRunning() {
        mailingListService.triggers = triggers(1, 2, 3);
        executionService.failForTriggerId = 2; // the middle trigger throws

        listener.checkStatusChangeMailingListExecution("uid-3", Status.PUBLISHED);

        // The exception from trigger 2 is recorded; triggers 1 and 3 must still have been executed.
        assertEquals(Arrays.asList(1, 2, 3), executionService.attemptedTriggerIds,
                "a failing trigger must not abort the remaining executions");
        assertEquals(Arrays.asList(1, 3), executionService.executedTriggerIds,
                "trigger 2 failed, so only 1 and 3 completed");
        assertEquals(List.of("2:uid-3:simulated trigger failure for 2"), executionService.recordedFailures,
                "the failure of trigger 2 is recorded in the Mail Queue with its message and cause");
    }

    @Test
    public void aFailureRecordThatFailsDoesNotPreventTheOthersFromRunning() {
        mailingListService.triggers = triggers(1, 2, 3);
        executionService.failForTriggerId = 2;
        executionService.failRecording = true;

        listener.checkStatusChangeMailingListExecution("uid-4", Status.PUBLISHED);

        assertEquals(Arrays.asList(1, 3), executionService.executedTriggerIds,
                "a record that cannot be stored is logged, and the remaining triggers still run");
    }

    // --------------------------------------------------------------------------------------------- //
    // The outbox drain
    // --------------------------------------------------------------------------------------------- //

    /**
     * The JMS topic's message is a prompt only: what is mailed comes from the outbox. Every due row is taken in the
     * outbox's order, its triggers run for its message and status, and the row is deleted; the topic's message itself
     * is not read (here it is null).
     */
    @Test
    public void aJmsPromptDrainsEveryDueStatusChangeInIdOrder() {
        mailingListService.triggers = triggers(10, 20);
        outbox.due.add(new Claim(1, "uid-a", Status.PUBLISHED));
        outbox.due.add(new Claim(2, "uid-b", Status.CANCELLED));

        listener.onMessage(null);

        assertEquals(List.of(Status.PUBLISHED, Status.CANCELLED), mailingListService.queriedStatuses,
                "each row's triggers are looked up for its own status, in the outbox's order");
        assertEquals(List.of("uid-a", "uid-a", "uid-b", "uid-b"), executionService.executedUids,
                "each row's triggers run for its own message, in the outbox's order");
        assertEquals(List.of(1L, 2L), outbox.completed, "each row is deleted once its triggers have run");
        assertTrue(outbox.due.isEmpty(), "and the drain took every due row");
        assertTrue(outbox.failures.isEmpty(), "with nothing failed: " + outbox.failures);
    }

    /**
     * A row whose triggers cannot be looked up (the database is down) is not deleted: it keeps its error, is tried
     * again when the lease taken with it runs out, and the pass stops, leaving the later rows for the next one.
     */
    @Test
    public void aStatusChangeWhoseTriggerLookupFailsIsLeftForTheNextPass() {
        mailingListService.failLookup = true;
        outbox.due.add(new Claim(1, "uid-a", Status.PUBLISHED));
        outbox.due.add(new Claim(2, "uid-b", Status.PUBLISHED));

        assertEquals(0, listener.drainOutbox(), "no row's triggers were run");

        assertEquals(1, outbox.failures.size(), "the failure is recorded on the row: " + outbox.failures);
        assertTrue(outbox.failures.get(0).startsWith("1:") && outbox.failures.get(0).contains("simulated lookup failure"),
                "on the row taken, with the cause: " + outbox.failures);
        assertTrue(outbox.completed.isEmpty(), "the row is not deleted, so it is tried again");
        assertEquals(1, outbox.due.size(), "and the pass stopped: the next row is left for the next pass");
    }

    /**
     * A trigger that fails is recorded in the Mail Queue (its own ERROR record), and the row is deleted all the same:
     * the record is the trigger's outcome, and taking the row again would repeat the mails of the triggers that worked.
     */
    @Test
    public void aTriggerFailureStillCompletesTheStatusChange() {
        mailingListService.triggers = triggers(1, 2);
        executionService.failForTriggerId = 2;
        outbox.due.add(new Claim(7, "uid-7", Status.PUBLISHED));

        assertEquals(1, listener.drainOutbox());

        assertEquals(List.of(1), executionService.executedTriggerIds);
        assertEquals(1, executionService.recordedFailures.size(), "trigger 2's failure is recorded in the Mail Queue");
        assertEquals(List.of(7L), outbox.completed, "and the row is deleted");
        assertTrue(outbox.failures.isEmpty(), "the row itself did not fail: " + outbox.failures);
    }

    /**
     * A trigger that throws an {@link Error} (a template whose macro recurses without end) is that trigger's failure:
     * it is recorded in the Mail Queue and the row is deleted. Would fail if the Error passed the per-trigger catch:
     * the row would then keep its lease with no error, and every lease would make the other triggers' mails again.
     */
    @Test
    public void aTriggerThrowingAnErrorIsRecordedAndTheRowStillCompleted() {
        mailingListService.triggers = triggers(1, 2, 3);
        executionService.errorForTriggerId = 2;
        outbox.due.add(new Claim(8, "uid-8", Status.PUBLISHED));

        assertEquals(1, listener.drainOutbox());

        assertEquals(List.of(1, 3), executionService.executedTriggerIds, "the triggers after it still run");
        assertEquals(List.of("2:uid-8:java.lang.StackOverflowError: simulated error for 2"),
                executionService.recordedFailures, "the Error is recorded in the Mail Queue");
        assertEquals(List.of(8L), outbox.completed, "and the row is deleted");
        assertTrue(outbox.failures.isEmpty(), "the row itself did not fail: " + outbox.failures);
    }

    /** An {@link Error} outside the triggers (at their lookup) is the row's failure: its error is written on the row */
    @Test
    public void aRowWhoseRunThrowsAnErrorKeepsItsError() {
        mailingListService.lookupError = true;
        outbox.due.add(new Claim(4, "uid-4", Status.PUBLISHED));

        assertEquals(0, listener.drainOutbox());

        assertEquals(List.of("4:java.lang.OutOfMemoryError: simulated lookup error"), outbox.failures,
                "the Error is written on the row");
        assertTrue(outbox.completed.isEmpty(), "and the row is kept");
    }

    /**
     * A row taken more often than the bound is given up: its triggers are not run again (each run made the mails of
     * the triggers before the faulty one again), each leaves a "Not sent" record, and the row is deleted.
     */
    @Test
    public void aStatusChangeTakenTooOftenIsGivenUpWithARecordForEachTrigger() {
        mailingListService.triggers = triggers(1, 2);
        outbox.due.add(new Claim(9, "uid-9", Status.PUBLISHED, MailingListStatusChangeOutbox.MAX_ATTEMPTS + 1));
        outbox.due.add(new Claim(10, "uid-10", Status.PUBLISHED, MailingListStatusChangeOutbox.MAX_ATTEMPTS));

        assertEquals(1, listener.drainOutbox(), "only the row within the bound counts as run");

        assertEquals(List.of(1, 2), executionService.executedTriggerIds, "the given-up row runs no trigger");
        assertEquals(List.of("uid-10", "uid-10"), executionService.executedUids, "the row at the bound still runs");
        assertEquals(2, executionService.recordedFailures.size(), "one record per trigger of the given-up row");
        for (String record : executionService.recordedFailures) {
            assertTrue(record.contains(":uid-9:Given up after " + MailingListStatusChangeOutbox.MAX_ATTEMPTS
                    + " attempts"), record);
        }
        assertEquals(List.of(9L, 10L), outbox.completed, "both rows are deleted");
    }

    /** A given-up row whose triggers cannot be looked up is kept, so its records are made on a later pass */
    @Test
    public void aGivenUpRowWhoseTriggersCannotBeLookedUpIsKept() {
        mailingListService.failLookup = true;
        outbox.due.add(new Claim(11, "uid-11", Status.PUBLISHED, MailingListStatusChangeOutbox.MAX_ATTEMPTS + 1));

        assertEquals(0, listener.drainOutbox());

        assertEquals(1, outbox.failures.size(), "the failure is written on the row: " + outbox.failures);
        assertTrue(outbox.completed.isEmpty(), "and the row is kept");
        assertTrue(executionService.recordedFailures.isEmpty());
    }

    /**
     * A trigger that hangs holds the drain lock, so the sweep cannot take a row; it warns instead, naming the row.
     * Would fail if the sweep returned silently, or the warning outlived the row.
     */
    @Test
    public void theSweepWarnsWhileOneRowHoldsTheDrain() throws Exception {
        mailingListService.triggers = triggers(1);
        executionService.entered = new CountDownLatch(1);
        executionService.release = new CountDownLatch(1);
        outbox.due.add(new Claim(12, "uid-12", Status.PUBLISHED));
        listener.stuckAfterMillis = 0;
        assertFalse(listener.warnIfStuck(), "no row runs yet");

        Thread drain = new Thread(listener::drainOutbox, "test-drain");
        drain.start();
        try {
            assertTrue(executionService.entered.await(10, TimeUnit.SECONDS), "the trigger runs");
            assertTrue(listener.warnIfStuck(), "the running row is reported");
            assertTimeoutPreemptively(Duration.ofSeconds(2), listener::sweep, "the sweep does not wait for it");
        } finally {
            executionService.release.countDown();
            drain.join(10_000);
        }
        assertFalse(drain.isAlive(), "the drain ended");
        assertEquals(List.of(12L), outbox.completed);
        assertFalse(listener.warnIfStuck(), "no row runs any more");
    }

    /**
     * Once the context closes, the drain takes no further row: a row taken then would wait out its lease after the
     * stop rather than be taken by the next start's pass.
     */
    @Test
    public void afterTheContextClosesTheDrainTakesNoFurtherRow() {
        mailingListService.triggers = triggers(1);
        outbox.due.add(new Claim(13, "uid-13", Status.PUBLISHED));

        listener.onStop();

        assertEquals(0, listener.drainOutbox());
        assertEquals(1, outbox.due.size(), "the row is left for the next start");
        assertTrue(executionService.attemptedTriggerIds.isEmpty());
    }

    /** The per-minute sweep takes the rows no prompt came for, such as a row committed while the broker was down */
    @Test
    public void theSweepDrainsTheRowsTheTopicDidNotPromptFor() {
        mailingListService.triggers = triggers(5);
        outbox.due.add(new Claim(3, "uid-3", Status.CANCELLED));

        listener.sweep();

        assertEquals(List.of("uid-3"), executionService.executedUids);
        assertEquals(List.of(3L), outbox.completed);
    }

    /**
     * The sweep returns at once while a drain runs on this node (that drain takes every due row), rather than wait for
     * it: the scheduler's thread is shared with the other scheduled jobs. Would fail if the sweep waited for the drain
     * lock (it would block until the drain ends) or took a row beside the running drain.
     */
    @Test
    public void theSweepReturnsAtOnceWhileADrainRuns() throws Exception {
        AtomicInteger claims = new AtomicInteger();
        CountDownLatch drainHasClaimed = new CountDownLatch(1);
        CountDownLatch releaseDrain = new CountDownLatch(1);
        MailingListStatusChangeOutbox slowOutbox = new MailingListStatusChangeOutbox() {
            @Override
            public Optional<Claim> claimNextDue() {
                if (claims.incrementAndGet() == 1) {
                    drainHasClaimed.countDown();
                    try {
                        releaseDrain.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return Optional.empty();
            }
        };
        MailingListMessageListener busy = new MailingListMessageListener(mailingListService, executionService,
                slowOutbox, null);
        Thread drain = new Thread(busy::drainOutbox, "test-drain");
        drain.start();
        try {
            assertTrue(drainHasClaimed.await(10, TimeUnit.SECONDS), "the drain is running");
            assertTimeoutPreemptively(Duration.ofSeconds(2), busy::sweep, "the sweep does not wait for the drain");
            assertEquals(1, claims.get(), "and takes no row itself");
        } finally {
            releaseDrain.countDown();
            drain.join(10_000);
        }
        assertFalse(drain.isAlive(), "the drain ended");
    }

    // --------------------------------------------------------------------------------------------- //

    private static List<MailingListTrigger> triggers(Integer... ids) {
        List<MailingListTrigger> list = new ArrayList<>();
        for (Integer id : ids) {
            MailingListTrigger trigger = new MailingListTrigger();
            trigger.setId(id);
            list.add(trigger);
        }
        return list;
    }

    /** Returns a preset trigger list and records the status it was queried with; can fail the lookup. */
    private static final class RecordingMailingListService extends MailingListService {
        List<MailingListTrigger> triggers = new ArrayList<>();
        Status queriedStatus;
        final List<Status> queriedStatuses = new ArrayList<>();
        boolean failLookup;
        boolean lookupError;

        // PORT: MailingListService now has only a constructor-injection constructor
        // (UserService, ContactService); the test never used those collaborators, so we
        // forward nulls — the overridden findStatusChangeTriggers never touches them.
        RecordingMailingListService() {
            super(null, null);
        }

        @Override
        public List<MailingListTrigger> findStatusChangeTriggers(Status status) {
            if (failLookup) {
                throw new IllegalStateException("simulated lookup failure");
            }
            if (lookupError) {
                throw new OutOfMemoryError("simulated lookup error");
            }
            this.queriedStatus = status;
            this.queriedStatuses.add(status);
            return triggers;
        }
    }

    /**
     * An outbox of preset due rows, taken in order, which records the rows deleted and the failures recorded. Safe to
     * drain on the listener's own thread while a test reads it ({@code MailingListDeliveryFaultTest}).
     */
    static final class RecordingOutbox extends MailingListStatusChangeOutbox {
        final Deque<Claim> due = new ConcurrentLinkedDeque<>();
        final List<Long> completed = new CopyOnWriteArrayList<>();
        final List<String> failures = new CopyOnWriteArrayList<>();

        @Override
        public void enqueue(String messageUid, Status status) {
            throw new UnsupportedOperationException("the listener never writes a row");
        }

        @Override
        public Optional<Claim> claimNextDue() {
            return Optional.ofNullable(due.poll());
        }

        @Override
        public void complete(long id) {
            completed.add(id);
        }

        @Override
        public void failed(long id, String error) {
            failures.add(id + ":" + error);
        }
    }

    /** Records every trigger execution (attempted and completed) and can fail one chosen trigger id. */
    private static final class RecordingMailingListExecutionService extends MailingListExecutionService {
        final List<Integer> attemptedTriggerIds = new ArrayList<>();
        final List<Integer> executedTriggerIds = new ArrayList<>();
        final List<String> executedUids = new ArrayList<>();
        final List<String> recordedFailures = new CopyOnWriteArrayList<>();
        Integer failForTriggerId;
        Integer errorForTriggerId;
        boolean failRecording;
        /** When set, each execution counts it down and waits for {@link #release} */
        CountDownLatch entered;
        CountDownLatch release;

        // PORT: MailingListExecutionService now has only a constructor-injection constructor
        // (NiordApp, MessageSearchService, MessageQueryService, FmTemplateService, JsResourceService,
        // DictionaryService, MessageSeriesService); the test never used those collaborators, so we forward
        // nulls — the overridden executeStatusChangeTrigger never touches them.
        RecordingMailingListExecutionService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public void executeStatusChangeTrigger(Integer triggerId, String messageUid) throws Exception {
            attemptedTriggerIds.add(triggerId);
            if (entered != null) {
                entered.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            if (failForTriggerId != null && failForTriggerId.equals(triggerId)) {
                throw new Exception("simulated trigger failure for " + triggerId);
            }
            if (errorForTriggerId != null && errorForTriggerId.equals(triggerId)) {
                throw new StackOverflowError("simulated error for " + triggerId);
            }
            executedTriggerIds.add(triggerId);
            executedUids.add(messageUid);
        }

        @Override
        public void recordStatusChangeTriggerFailure(Integer triggerId, String messageUid, Exception cause) {
            if (failRecording) {
                throw new IllegalStateException("simulated record failure for " + triggerId);
            }
            recordedFailures.add(triggerId + ":" + messageUid + ":" + cause.getMessage());
        }
    }
}
