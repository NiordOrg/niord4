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
 * and {@link MailingListMessageListener#checkStatusChangeMailingListExecution(Claim)}, which it runs for each
 * outbox row.
 *
 * <p>They pin the contract: every due row is taken in the outbox's order, its triggers are run and the row is
 * deleted; a row whose triggers cannot be looked up keeps its error and is left for the next pass; the listener looks
 * up the status-change triggers for the row's status, executes each of them for the row's message UID, and keeps going
 * when an individual trigger execution fails (the per-trigger error is logged and recorded in the Mail Queue, so one
 * bad trigger cannot block the rest, an {@link Error} included), and the row is then deleted all the same; a row taken
 * more often than the outbox's bound is given up with a record per trigger; the sweep warns while one row holds the
 * drain; the sweep and the JMS prompt only hand the drain to the listener's own worker, and return while a trigger
 * hangs, with at most one drain waiting behind the running one.
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
        outbox = new RecordingOutbox();
        executionService = new RecordingMailingListExecutionService(outbox);
        // The listener's collaborators are wired via a package-private constructor. We pass the recording doubles
        // and `null` for the ConnectionFactory, which only the JMS consumer loop uses (out of scope here). The drains
        // the prompt and the sweep hand over run on the caller's thread here, so a test sees their outcome when the
        // call returns; the tests of the listener's own worker build a listener with it (newWithItsOwnWorker).
        listener = new MailingListMessageListener(mailingListService, executionService, outbox, null, Runnable::run);
        // PORT: the listener's `log` is now `private static final` (Spring port) and can no longer
        // be replaced with a test logger. The per-trigger error is swallowed and only logged, so the
        // logger has no observable effect on the dispatch contract under test; we let the real logger
        // run. The "keep going after a failing trigger" behaviour is still asserted directly via
        // attemptedTriggerIds vs executedTriggerIds in aFailingTriggerDoesNotPreventTheOthersFromRunning.
    }

    @Test
    public void executesEachStatusChangeTriggerForTheMessageUid() {
        mailingListService.triggers = triggers(10, 20, 30);

        listener.checkStatusChangeMailingListExecution(new Claim(1, "uid-1", Status.PUBLISHED));

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

        listener.checkStatusChangeMailingListExecution(new Claim(2, "uid-2", Status.VERIFIED));

        assertEquals(Status.VERIFIED, mailingListService.queriedStatus);
        assertTrue(executionService.executedTriggerIds.isEmpty(),
                "with no matching triggers nothing must be executed");
    }

    @Test
    public void aFailingTriggerDoesNotPreventTheOthersFromRunning() {
        mailingListService.triggers = triggers(1, 2, 3);
        executionService.failForTriggerId = 2; // the middle trigger throws

        listener.checkStatusChangeMailingListExecution(new Claim(3, "uid-3", Status.PUBLISHED));

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

        List<Integer> owed = listener.checkStatusChangeMailingListExecution(new Claim(4, "uid-4", Status.PUBLISHED));

        assertEquals(Arrays.asList(1, 3), executionService.executedTriggerIds,
                "a record that cannot be stored is logged, and the remaining triggers still run");
        assertEquals(List.of(2), owed, "and the trigger with neither its mails nor its record stays owed");
        assertEquals(List.of("4:1", "4:3"), outbox.settled, "the others are settled on the row");
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
     * the record is the trigger's outcome, which settles it as its mails would have.
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
     * the row would then keep its lease with no error, and the triggers after it would not run.
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
     * A row taken more often than the bound is given up: its triggers are not run again, each leaves a "Not sent"
     * record, and the row is deleted.
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

    /**
     * A row whose triggers ran but whose delete fails is logged with its own row, message and status (not the topic's
     * prompt), and the pass stops there; the row's lease makes it due again.
     */
    @Test
    public void aRowWhoseDeleteFailsStopsThePass() {
        mailingListService.triggers = triggers(1);
        outbox.failComplete = true;
        outbox.due.add(new Claim(14, "uid-14", Status.PUBLISHED));
        outbox.due.add(new Claim(15, "uid-15", Status.PUBLISHED));

        assertEquals(0, listener.drainOutbox(), "the failed delete is caught, not thrown");

        assertEquals(List.of("uid-14"), executionService.executedUids, "the first row's triggers ran");
        assertEquals(1, outbox.due.size(), "and the pass stopped there");
        assertTrue(outbox.failures.isEmpty(), "the run did not fail, so no error is written on the row");
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
    // Settled and owed triggers
    // --------------------------------------------------------------------------------------------- //

    /**
     * The case that used to lose the mail: a trigger's mails fail, and so does the record of its failure (the database
     * lost for a moment). The row used to be deleted all the same, and the mail was gone without a record or a retry.
     * Now the trigger stays owed: the row is kept with its error, and the next take settles it. FAILS on the code that
     * deleted the row whatever its triggers' outcome.
     */
    @Test
    public void aTriggerWithNeitherItsMailsNorItsFailureRecordKeepsTheRowAndTheNextTakeSettlesIt() {
        mailingListService.triggers = triggers(1);
        executionService.failForTriggerId = 1;
        executionService.failRecording = true;
        outbox.dueAgain = true;
        outbox.due.add(new Claim(51, "uid-51", Status.PUBLISHED));

        assertEquals(0, listener.drainOutbox(), "the status change is not done");
        assertTrue(outbox.completed.isEmpty(), "its row is NOT deleted");
        assertEquals(1, outbox.failures.size(), "it keeps its error: " + outbox.failures);
        assertTrue(outbox.failures.get(0).startsWith("51:Trigger(s) [1] neither made their mails nor recorded"),
                "naming the trigger still owed: " + outbox.failures);
        assertTrue(outbox.settled.isEmpty(), "nothing is settled");

        // The database is back by the next take
        executionService.failForTriggerId = null;
        executionService.failRecording = false;
        outbox.comeDue();
        assertEquals(1, listener.drainOutbox(), "the next take settles it");
        assertEquals(List.of(1), executionService.executedTriggerIds, "its mails are made");
        assertEquals(List.of("51:1"), outbox.settled);
        assertEquals(List.of(51L), outbox.completed, "and the row is deleted");
    }

    /**
     * Two triggers, the first settled and the second owed: the row is kept, and the retry runs only the second. The
     * first list does not get its mail again. FAILS on a design that kept the row and ran every trigger again.
     */
    @Test
    public void aRetryRunsOnlyTheTriggersStillOwed() {
        mailingListService.triggers = triggers(1, 2);
        executionService.failForTriggerId = 2;
        executionService.failRecording = true;
        outbox.dueAgain = true;
        outbox.due.add(new Claim(61, "uid-61", Status.PUBLISHED));

        assertEquals(0, listener.drainOutbox());
        assertEquals(List.of(1), executionService.executedTriggerIds, "the first list got its mail");
        assertEquals(List.of("61:1"), outbox.settled, "and its trigger is settled");
        assertTrue(outbox.completed.isEmpty(), "the row is kept for the second");

        executionService.failForTriggerId = null;
        executionService.failRecording = false;
        outbox.comeDue();
        assertEquals(1, listener.drainOutbox());
        assertEquals(List.of(1, 2, 2), executionService.attemptedTriggerIds,
                "the retry ran the owed trigger only, not the first again");
        assertEquals(List.of(1, 2), executionService.executedTriggerIds, "each list got one mail");
        assertEquals(List.of(61L), outbox.completed);
    }

    /**
     * A row whose triggers are all settled but whose delete failed (the database lost for a moment) is taken again
     * when its lease runs out, and then only deleted: no trigger runs again. It used to make every trigger's mails
     * again. The same holds for a node that stopped after a trigger's transaction committed and before the row was
     * deleted: the mark is committed with the mails (see {@code MailingListDeliveryFaultITTest}).
     */
    @Test
    public void aRowWhoseDeleteFailedIsDeletedOnTheNextTakeWithoutRunningATriggerAgain() {
        mailingListService.triggers = triggers(1, 2);
        outbox.failComplete = true;
        outbox.dueAgain = true;
        outbox.due.add(new Claim(62, "uid-62", Status.PUBLISHED));

        assertEquals(0, listener.drainOutbox(), "the delete fails");
        assertEquals(List.of(1, 2), executionService.executedTriggerIds);

        outbox.failComplete = false;
        outbox.comeDue();
        assertEquals(1, listener.drainOutbox(), "the next take");
        assertEquals(List.of(1, 2), executionService.attemptedTriggerIds, "runs no trigger again");
        assertEquals(List.of(62L), outbox.completed, "and deletes the row");
    }

    /**
     * A row given up after the bound leaves a "Given up" record only for the triggers it still owes, each naming its
     * list (the record names the list of its trigger); the triggers settled before leave none, and the row is deleted.
     */
    @Test
    public void aGivenUpRowRecordsOnlyTheTriggersStillOwed() {
        mailingListService.triggers = triggers(1, 2, 3);
        outbox.settle(71, 1);
        outbox.due.add(new Claim(71, "uid-71", Status.PUBLISHED, MailingListStatusChangeOutbox.MAX_ATTEMPTS + 1));

        assertEquals(0, listener.drainOutbox(), "a given-up row does not count as run");

        assertTrue(executionService.attemptedTriggerIds.isEmpty(), "no trigger runs");
        assertEquals(List.of(
                        "2:uid-71:Given up after " + MailingListStatusChangeOutbox.MAX_ATTEMPTS
                                + " attempts to make the mails of the status change to PUBLISHED (each failed, hung or "
                                + "stopped the server; see the server log)",
                        "3:uid-71:Given up after " + MailingListStatusChangeOutbox.MAX_ATTEMPTS
                                + " attempts to make the mails of the status change to PUBLISHED (each failed, hung or "
                                + "stopped the server; see the server log)"),
                executionService.recordedFailures, "a record for each trigger still owed, none for the settled one");
        assertEquals(List.of(71L), outbox.completed, "and the row is deleted");
    }

    /**
     * A given-up row whose record cannot be saved for a trigger is not deleted: that trigger stays owed, and the next
     * take records it. The record would otherwise be lost as the mail was. It counts the attempts that ran the
     * triggers, not the takes that only tried to save the record (it said "after 6 attempts" here).
     */
    @Test
    public void aGivenUpRowWhoseRecordFailsIsKeptUntilTheRecordIsSaved() {
        mailingListService.triggers = triggers(1);
        executionService.failRecording = true;
        outbox.dueAgain = true;
        outbox.due.add(new Claim(72, "uid-72", Status.PUBLISHED, MailingListStatusChangeOutbox.MAX_ATTEMPTS + 1));

        assertEquals(0, listener.drainOutbox());
        assertTrue(outbox.completed.isEmpty(), "the row is kept");
        assertEquals(1, outbox.failures.size(), "with its error: " + outbox.failures);

        executionService.failRecording = false;
        outbox.comeDue();
        assertEquals(0, listener.drainOutbox());
        assertEquals(1, executionService.recordedFailures.size(), "the next take records it");
        assertTrue(executionService.recordedFailures.get(0).startsWith("1:uid-72:Given up after "
                + MailingListStatusChangeOutbox.MAX_ATTEMPTS + " attempts"), executionService.recordedFailures.get(0));
        assertEquals(List.of(72L), outbox.completed, "and deletes the row");
        assertTrue(executionService.attemptedTriggerIds.isEmpty(), "no trigger ran");
    }

    /**
     * A row kept because a trigger stays owed is out of the pass's reach (due again in minutes), so the pass goes on
     * with the rows after it: the status changes of other lists are mailed at once. FAILS on the code that ended the
     * pass at every kept row: the second row then waited for the next pass, up to a minute later, and behind a few
     * such rows the other lists' mails fell further and further behind.
     */
    @Test
    public void aRowThatStaysOwedDoesNotStopThePass() {
        mailingListService.triggersByStatus.put(Status.PUBLISHED, triggers(1));
        mailingListService.triggersByStatus.put(Status.CANCELLED, triggers(2));
        executionService.failForTriggerId = 1;
        executionService.failRecordingForTriggerId = 1;
        outbox.due.add(new Claim(81, "uid-81", Status.PUBLISHED));
        outbox.due.add(new Claim(82, "uid-82", Status.CANCELLED));

        assertEquals(1, listener.drainOutbox(), "the second row is run in the same pass");

        assertEquals(List.of("uid-82"), executionService.executedUids, "its list got its mail");
        assertEquals(List.of(82L), outbox.completed, "and it is deleted");
        assertEquals(1, outbox.failures.size(), "the first row is kept with its error: " + outbox.failures);
        assertTrue(outbox.failures.get(0).startsWith("81:"), outbox.failures.get(0));
        assertTrue(outbox.due.isEmpty(), "and no due row was left behind");
    }

    /**
     * A trigger whose mails AND failure record fail on every take (a recipient the Mail Queue refuses, say) runs on
     * the first {@link MailingListStatusChangeOutbox#MAX_ATTEMPTS} takes, its "Given up" record is tried on as many
     * more, and on the last take the row is deleted all the same, with an ERROR in the log as the only record. FAILS
     * on the code that kept such a row for ever, given up again every few minutes.
     */
    @Test
    public void aRecordThatCanNeverBeSavedEndsItsRowAtTheLastTake() {
        mailingListService.triggers = triggers(1);
        executionService.failForTriggerId = 1;
        executionService.failRecording = true;
        outbox.dueAgain = true;
        outbox.due.add(new Claim(91, "uid-91", Status.PUBLISHED));

        listener.drainOutbox();
        for (int take = 2; take <= MailingListMessageListener.LAST_TAKE; take++) {
            assertTrue(outbox.completed.isEmpty(), "the row is kept before take " + take);
            outbox.comeDue();
            listener.drainOutbox();
        }

        assertEquals(List.of(91L), outbox.completed, "the last take deletes the row");
        assertEquals(MailingListStatusChangeOutbox.MAX_ATTEMPTS, executionService.attemptedTriggerIds.size(),
                "the trigger ran on the first takes only: " + executionService.attemptedTriggerIds);
        assertEquals(MailingListMessageListener.LAST_TAKE, executionService.recordAttempts.get(),
                "and its record was tried on every take");
        outbox.comeDue();
        assertEquals(0, listener.drainOutbox());
        assertTrue(outbox.due.isEmpty(), "nothing is left to take");
    }

    /**
     * Once the context closes, the drain starts no further trigger of the row it is running: the triggers left stay
     * owed and the row is kept, so they run at the next take, and the ones that ran are not run again.
     */
    @Test
    public void theCloseLeavesTheTriggersNotYetRunOwed() throws Exception {
        mailingListService.triggers = triggers(1, 2);
        executionService.entered = new CountDownLatch(1);
        executionService.release = new CountDownLatch(1);
        outbox.due.add(new Claim(73, "uid-73", Status.PUBLISHED));
        MailingListMessageListener own = newWithItsOwnWorker();
        own.stopWaitMillis = 50;
        try {
            own.sweep();
            assertTrue(executionService.entered.await(10, TimeUnit.SECONDS), "the first trigger runs");
            own.onStop(); // the close comes while the first trigger runs
        } finally {
            executionService.release.countDown();
            own.stopWaitMillis = 10_000;
            own.onStop(); // waits for the drain in flight to end
        }
        assertEquals(List.of(1), executionService.attemptedTriggerIds, "the second trigger is not started");
        assertEquals(List.of("73:1"), outbox.settled, "the first is settled");
        assertTrue(outbox.completed.isEmpty(), "the row is kept");
        assertEquals(List.of("73:The server stopped before trigger(s) [2] ran"), outbox.failures);
    }

    // --------------------------------------------------------------------------------------------- //
    // The listener's own drain worker
    // --------------------------------------------------------------------------------------------- //

    /** A listener on the recording doubles with its real drain worker, which every test stops at its end */
    private MailingListMessageListener newWithItsOwnWorker() {
        return new MailingListMessageListener(mailingListService, executionService, outbox, null);
    }

    /**
     * The sweep runs on the scheduler every {@code @Scheduled} job shares (the publication and expiry of messages, the
     * mail sender): it hands the drain to the listener's own worker and returns at once, also while a trigger of that
     * drain hangs, and sweeps meanwhile hand over no further drain. FAILS on the code that ran the drain in the sweep
     * itself: the first sweep then waits for the hanging trigger.
     */
    @Test
    public void theSweepReturnsAtOnceWhileATriggerOfItsDrainHangsAndQueuesNoFurtherDrain() throws Exception {
        mailingListService.triggers = triggers(1);
        executionService.entered = new CountDownLatch(1);
        executionService.release = new CountDownLatch(1);
        outbox.drainEnded = new CountDownLatch(1);
        outbox.due.add(new Claim(21, "uid-21", Status.PUBLISHED));
        MailingListMessageListener own = newWithItsOwnWorker();
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), own::sweep, "the sweep returns while the trigger hangs");
            assertTrue(executionService.entered.await(10, TimeUnit.SECONDS), "the trigger runs");
            assertEquals(List.of(MailingListMessageListener.DRAIN_THREAD_NAME), executionService.threadNames,
                    "on the listener's own worker, not on the caller's thread");

            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                own.sweep();
                own.sweep();
            }, "later sweeps return at once too");
            assertFalse(own.drainQueued(), "and hand over no further drain while one runs");
            assertEquals(0, outbox.emptyClaims.get(), "the running drain has not ended");

            executionService.release.countDown();
            assertTrue(outbox.drainEnded.await(10, TimeUnit.SECONDS), "the drain ends once the trigger does");
            assertEquals(List.of(21L), outbox.completed, "and deletes its row");
        } finally {
            executionService.release.countDown();
            own.onStop();
        }
        assertEquals(1, outbox.emptyClaims.get(), "one drain ran: the sweeps during it piled up no work");
        assertFalse(executionService.interrupted, "the trigger was not interrupted");
    }

    /**
     * The JMS poll loop hands its drains to the worker too, and is back at the topic at once while a trigger hangs.
     * However many status changes are announced meanwhile, ONE drain waits behind the running one (it takes the rows
     * committed after the running drain last looked), not one per prompt.
     */
    @Test
    public void promptsWhileADrainRunsQueueExactlyOneFurtherDrain() throws Exception {
        mailingListService.triggers = triggers(1);
        executionService.entered = new CountDownLatch(1);
        executionService.release = new CountDownLatch(1);
        outbox.drainEnded = new CountDownLatch(2);
        outbox.due.add(new Claim(22, "uid-22", Status.PUBLISHED));
        MailingListMessageListener own = newWithItsOwnWorker();
        try {
            own.onMessage(null);
            assertTrue(executionService.entered.await(10, TimeUnit.SECONDS), "the prompt's drain runs");

            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                own.onMessage(null);
                own.onMessage(null);
                own.onMessage(null);
            }, "the poll loop is not held by the hanging trigger");
            assertTrue(own.drainQueued(), "one further drain waits");
            assertTimeoutPreemptively(Duration.ofSeconds(2), own::sweep, "and the sweep adds none beside it");

            executionService.release.countDown();
            assertTrue(outbox.drainEnded.await(10, TimeUnit.SECONDS), "the running drain and the waiting one end");
            assertFalse(own.drainQueued());
        } finally {
            executionService.release.countDown();
            own.onStop();
        }
        assertEquals(2, outbox.emptyClaims.get(), "two drains ran, not one per prompt");
        assertEquals(List.of(22L), outbox.completed);
    }

    /** A drain that fails (here the outbox cannot be read) is logged and leaves the worker able to run the next one */
    @Test
    public void aDrainThatFailsLeavesTheWorkerRunning() throws Exception {
        mailingListService.triggers = triggers(1);
        outbox.failNextClaims.set(1);
        outbox.claimFailed = new CountDownLatch(1);
        outbox.drainEnded = new CountDownLatch(1);
        outbox.due.add(new Claim(23, "uid-23", Status.PUBLISHED));
        MailingListMessageListener own = newWithItsOwnWorker();
        try {
            own.sweep();
            assertTrue(outbox.claimFailed.await(10, TimeUnit.SECONDS), "the first drain fails");
            own.onMessage(null);
            assertTrue(outbox.drainEnded.await(10, TimeUnit.SECONDS), "the next drain runs");
        } finally {
            own.onStop();
        }
        assertEquals(List.of(23L), outbox.completed, "and takes the row");
        assertEquals(List.of(MailingListMessageListener.DRAIN_THREAD_NAME), executionService.threadNames);
    }

    /**
     * The close does not interrupt a drain in flight (an interrupted trigger would be recorded as failed) and waits
     * for it only a short while; the drain takes no further row after it, so that row is left for the next start.
     */
    @Test
    public void theCloseNeitherInterruptsADrainInFlightNorLetsItTakeAFurtherRow() throws Exception {
        mailingListService.triggers = triggers(1);
        executionService.entered = new CountDownLatch(1);
        executionService.release = new CountDownLatch(1);
        outbox.due.add(new Claim(24, "uid-24", Status.PUBLISHED));
        outbox.due.add(new Claim(25, "uid-25", Status.PUBLISHED));
        MailingListMessageListener own = newWithItsOwnWorker();
        own.stopWaitMillis = 50;
        try {
            own.sweep();
            assertTrue(executionService.entered.await(10, TimeUnit.SECONDS), "the trigger runs");
            assertTimeoutPreemptively(Duration.ofSeconds(2), own::onStop, "the close waits only a short while");
            assertFalse(executionService.interrupted, "and does not interrupt the trigger");
            own.requestDrain("after the close");
            assertFalse(own.drainQueued(), "no drain is handed over after the close");
        } finally {
            executionService.release.countDown();
            own.stopWaitMillis = 10_000;
            own.onStop(); // waits for the drain in flight to end
        }
        assertEquals(List.of(24L), outbox.completed, "the row in flight ran to its end");
        assertEquals(List.of(25L), outbox.due.stream().map(Claim::id).toList(),
                "and the next row was left for the next start");
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
        /** When it holds the status looked up, its triggers rather than {@link #triggers} */
        final java.util.Map<Status, List<MailingListTrigger>> triggersByStatus = new java.util.EnumMap<>(Status.class);
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
            return triggersByStatus.getOrDefault(status, triggers);
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

        /** How many claims found no due row: one at the end of every drain that ran to its end */
        final AtomicInteger emptyClaims = new AtomicInteger();
        /** When set, counted down at every claim that finds no due row */
        volatile CountDownLatch drainEnded;
        /** How many of the next claims throw */
        final AtomicInteger failNextClaims = new AtomicInteger();
        /** When set, counted down at every claim that throws */
        volatile CountDownLatch claimFailed;

        /** The triggers settled, as "row:trigger", in the order they were settled */
        final List<String> settled = new CopyOnWriteArrayList<>();
        /**
         * When set, a row kept ({@link #failed}) or whose delete failed is due again, as the real outbox makes it due
         * again within minutes or when its lease runs out: not in the pass that kept it, but once the test calls
         * {@link #comeDue}, as its next take
         */
        boolean dueAgain;
        /** The rows {@link #dueAgain} made due again later, which {@link #comeDue} makes due */
        private final Deque<Claim> later = new ConcurrentLinkedDeque<>();

        /** The minutes pass: the rows kept or whose delete failed are due again */
        void comeDue() {
            for (Claim claim = later.poll(); claim != null; claim = later.poll()) {
                due.add(claim);
            }
        }
        /** The rows taken and not yet deleted, by id: what {@link #dueAgain} puts back */
        private final java.util.Map<Long, Claim> taken = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public Optional<Claim> claimNextDue() {
            if (failNextClaims.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                if (claimFailed != null) {
                    claimFailed.countDown();
                }
                throw new IllegalStateException("simulated outbox read failure");
            }
            Optional<Claim> next = Optional.ofNullable(due.poll())
                    // As the real take does: the row carries the triggers settled by its earlier takes
                    .map(c -> new Claim(c.id(), c.messageUid(), c.status(), c.attempts(), settledOn(c.id())));
            next.ifPresent(c -> taken.put(c.id(), c));
            if (next.isEmpty()) {
                emptyClaims.incrementAndGet();
                if (drainEnded != null) {
                    drainEnded.countDown();
                }
            }
            return next;
        }

        /** The triggers settled on the given row */
        java.util.Set<Integer> settledOn(long id) {
            return settled.stream()
                    .filter(s -> s.startsWith(id + ":"))
                    .map(s -> Integer.valueOf(s.substring(s.indexOf(':') + 1)))
                    .collect(java.util.stream.Collectors.toSet());
        }

        boolean failComplete;

        @Override
        public void complete(long id) {
            if (failComplete) {
                putBack(id);
                throw new IllegalStateException("simulated delete failure of " + id);
            }
            taken.remove(id);
            completed.add(id);
        }

        @Override
        public void failed(long id, String error) {
            failures.add(id + ":" + error);
            putBack(id);
        }

        @Override
        public void settle(long id, int triggerId) {
            settled.add(id + ":" + triggerId);
        }

        /** With {@link #dueAgain}: the row taken is due again, as its next take */
        private void putBack(long id) {
            Claim claim = taken.remove(id);
            if (dueAgain && claim != null) {
                later.add(new Claim(claim.id(), claim.messageUid(), claim.status(), claim.attempts() + 1));
            }
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
        /** When set, only this trigger's failure record fails */
        Integer failRecordingForTriggerId;
        /** How many failure records were tried, saved or not */
        final AtomicInteger recordAttempts = new AtomicInteger();
        /** When set, each execution counts it down and waits for {@link #release} */
        CountDownLatch entered;
        CountDownLatch release;
        /** The threads the executions ran on */
        final List<String> threadNames = new CopyOnWriteArrayList<>();
        /** Whether an execution was interrupted while it waited for {@link #release} */
        volatile boolean interrupted;

        /** The outbox the triggers settle on, as the real service settles them in its own transaction */
        private final MailingListStatusChangeOutbox outbox;

        // PORT: MailingListExecutionService now has only a constructor-injection constructor
        // (NiordApp, MessageSearchService, MessageQueryService, FmTemplateService, JsResourceService,
        // DictionaryService, MessageSeriesService, MailingListStatusChangeOutbox); the test never used those
        // collaborators, so we forward nulls — the overridden methods never touch them.
        RecordingMailingListExecutionService(MailingListStatusChangeOutbox outbox) {
            super(null, null, null, null, null, null, null, null);
            this.outbox = outbox;
        }

        @Override
        public void executeStatusChangeTrigger(Integer triggerId, String messageUid, long statusChangeId)
                throws Exception {
            attemptedTriggerIds.add(triggerId);
            threadNames.add(Thread.currentThread().getName());
            if (entered != null) {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                    throw e;
                }
            }
            if (failForTriggerId != null && failForTriggerId.equals(triggerId)) {
                throw new Exception("simulated trigger failure for " + triggerId);
            }
            if (errorForTriggerId != null && errorForTriggerId.equals(triggerId)) {
                throw new StackOverflowError("simulated error for " + triggerId);
            }
            executedTriggerIds.add(triggerId);
            executedUids.add(messageUid);
            outbox.settle(statusChangeId, triggerId);
        }

        @Override
        public void recordStatusChangeTriggerFailure(Integer triggerId, String messageUid, Exception cause,
                                                     long statusChangeId) {
            recordAttempts.incrementAndGet();
            if (failRecording || triggerId.equals(failRecordingForTriggerId)) {
                throw new IllegalStateException("simulated record failure for " + triggerId);
            }
            recordedFailures.add(triggerId + ":" + messageUid + ":" + cause.getMessage());
            outbox.settle(statusChangeId, triggerId);
        }
    }
}
