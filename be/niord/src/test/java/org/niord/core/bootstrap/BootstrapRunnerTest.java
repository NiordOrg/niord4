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
package org.niord.core.bootstrap;

import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchStatus;
import jakarta.batch.runtime.JobExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.niord.core.batch.BatchService;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The bootstrap runner's wait for a job (docs/code-data-config-plan.md §7.2), with the marker, the batch service and
 * the job operator mocked and a deadline of milliseconds. A first review found that a job past its
 * deadline left the run {@code failed}, and so open to a retry, while its execution could still write: the runner now
 * asks it to stop and marks the run failed only once the execution has ended, touching the marker meanwhile. A
 * later review found that a retry with more than one archive applies a later archive's entries to the failed
 * attempt's rows as whole updates: the claim then allowed a retry with one archive only. The owner's ruling of
 * 2026-10-24 (docs/code-data-config-plan.md D4) removed every retry: a bootstrap runs once, on a new, empty database;
 * a failed run, or one that broke off, is refused whatever the number of archives, and the remedy is a database
 * recreated empty. The claim's refusals, the pre-claim refusal of a domain a later archive restates with more than its
 * active flag, and the restatement of its active flag alone, and the apply's refusal of a marker this run did not
 * claim are pinned here.
 */
class BootstrapRunnerTest {

    static final long EXECUTION = 7L;

    BootstrapMarker marker;
    BatchService batchService;
    JobOperator jobOperator;
    BootstrapRun bootstrapRun;
    BootstrapRunner runner;
    BootstrapArchive archive;

    /** How often the job operator has been asked for the execution's status */
    final AtomicInteger polls = new AtomicInteger();

    /** The poll that first answered an ended status, or 0 */
    final AtomicInteger firstEndedPoll = new AtomicInteger();

    /** The number of polls when markFailed was called, or 0 */
    final AtomicInteger failedAfterPoll = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        marker = mock(BootstrapMarker.class);
        batchService = mock(BatchService.class);
        jobOperator = mock(JobOperator.class);
        when(batchService.startBatchJobWithDataFile(anyString(), any(InputStream.class), anyString(), anyMap()))
                .thenReturn(EXECUTION);
        doAnswer(i -> {
            failedAfterPoll.set(polls.get());
            return null;
        }).when(marker).markFailed(anyString());

        bootstrapRun = spy(new BootstrapRun());
        runner = new BootstrapRunner(marker, batchService, jobOperator, bootstrapRun);
        runner.jobTimeout = Duration.ofMillis(50);
        runner.poll = Duration.ofMillis(5);
        runner.heartbeat = Duration.ofMillis(10);

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("batch-set.json", "[{\"jobName\": \"domain-import\", \"fileName\": \"domains.json\"}]"
                .getBytes(StandardCharsets.UTF_8));
        entries.put("domains.json", "[]".getBytes(StandardCharsets.UTF_8));
        archive = BootstrapArchiveTest.read("test.zip", BootstrapArchiveTest.zip(entries));
        claimedBy(List.of(archive));
    }

    /** Stubs the marker as the claim of a run of the archives leaves it: in-progress, with the run's hash */
    private void claimedBy(List<BootstrapArchive> archives) {
        when(marker.read()).thenReturn(Optional.of(new BootstrapMarker.State(BootstrapMarker.IN_PROGRESS,
                BootstrapArchive.runHash(archives), null, "4.0.0", OffsetDateTime.now())));
    }

    @AfterEach
    void clearTheFlag() {
        Thread.interrupted();
    }

    private static JobExecution execution(BatchStatus status) {
        JobExecution execution = mock(JobExecution.class);
        when(execution.getBatchStatus()).thenReturn(status);
        when(execution.getExitStatus()).thenReturn(status.name());
        return execution;
    }

    /**
     * Answers STARTED until a stop has been requested and some more polls past it, then STOPPED, and records the poll
     * that first answered STOPPED
     */
    private void startedUntilStoppedAndAfter(int pollsAfterTheStop, Runnable atEveryPoll) {
        AtomicBoolean stopRequested = new AtomicBoolean();
        AtomicInteger afterTheStop = new AtomicInteger();
        doAnswer(i -> stopRequested.getAndSet(true)).when(jobOperator).stop(EXECUTION);
        when(jobOperator.getJobExecution(EXECUTION)).thenAnswer(i -> {
            int poll = polls.incrementAndGet();
            atEveryPoll.run();
            if (stopRequested.get() && afterTheStop.incrementAndGet() > pollsAfterTheStop) {
                firstEndedPoll.compareAndSet(0, poll);
                return execution(BatchStatus.STOPPED);
            }
            return execution(BatchStatus.STARTED);
        });
    }

    /** Asserts that the run asked for a stop, then polled, then was marked failed, and only after a STOPPED answer */
    private void assertFailedOnlyOnceStopped() {
        InOrder order = inOrder(jobOperator, marker);
        order.verify(jobOperator).stop(EXECUTION);
        order.verify(jobOperator, atLeastOnce()).getJobExecution(EXECUTION);
        order.verify(marker).markFailed(anyString());
        assertTrue(firstEndedPoll.get() > 0, "the execution never answered STOPPED");
        assertTrue(failedAfterPoll.get() >= firstEndedPoll.get(), "the run was marked failed after poll "
                + failedAfterPoll.get() + ", before the execution first answered STOPPED at poll "
                + firstEndedPoll.get());
    }

    @Test
    void aJobPastItsDeadlineIsStoppedAndTheRunFailsOnlyOnceItsExecutionHasEnded() {
        startedUntilStoppedAndAfter(10, () -> { });

        BootstrapRunner.Outcome outcome = runner.apply(List.of(archive));

        assertFailedOnlyOnceStopped();
        verify(jobOperator, times(1)).stop(EXECUTION);
        verify(marker, atLeastOnce()).heartbeat(BootstrapArchive.runHash(List.of(archive)));
        verify(marker).note(eq(BootstrapArchive.runHash(List.of(archive))), any());
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(marker, times(1)).markFailed(error.capture());
        verify(marker, never()).markApplied();
        assertEquals(BootstrapMarker.FAILED, outcome.state());
        assertTrue(error.getValue().contains("job domain-import (domains.json in test.zip) did not end within 50 ms")
                && error.getValue().contains("execution " + EXECUTION)
                && error.getValue().contains("it then ended STOPPED"), error.getValue());
    }

    @Test
    void anInterruptionDoesNotEndTheWaitAndTheFlagIsSetAgain() {
        startedUntilStoppedAndAfter(5, () -> {
            if (polls.get() == 2) {
                Thread.currentThread().interrupt();
            }
        });

        BootstrapRunner.Outcome outcome = runner.apply(List.of(archive));

        assertTrue(Thread.interrupted(), "the interrupt flag is set again once the wait has ended");
        assertFailedOnlyOnceStopped();
        verify(jobOperator, times(1)).stop(EXECUTION);
        verify(marker, times(1)).markFailed(anyString());
        assertEquals(BootstrapMarker.FAILED, outcome.state());
    }

    @Test
    void aMarkerWriteThatFailsWhileWaitingDoesNotEndTheWait() {
        AtomicInteger heartbeats = new AtomicInteger();
        doAnswer(i -> {
            if (heartbeats.incrementAndGet() <= 2) {
                throw new IllegalStateException("the database is not reachable");
            }
            return null;
        }).when(marker).heartbeat(anyString());
        doThrow(new IllegalStateException("the database is not reachable")).when(marker).note(anyString(), anyString());
        startedUntilStoppedAndAfter(10, () -> { });

        BootstrapRunner.Outcome outcome = runner.apply(List.of(archive));

        assertTrue(heartbeats.get() > 2, "the heartbeat was not tried again after it failed");
        assertFailedOnlyOnceStopped();
        verify(jobOperator, times(1)).stop(EXECUTION);
        verify(marker, times(1)).markFailed(anyString());
        verify(marker, never()).markApplied();
        assertEquals(BootstrapMarker.FAILED, outcome.state());
        assertTrue(outcome.error().contains("it then ended STOPPED"), outcome.error());
    }

    @Test
    void aStatusThatCannotBeReadIsAskedForAgain() {
        when(jobOperator.getJobExecution(EXECUTION)).thenAnswer(i -> {
            if (polls.incrementAndGet() <= 2) {
                throw new IllegalStateException("the batch repository is not reachable");
            }
            return execution(BatchStatus.COMPLETED);
        });

        BootstrapRunner.Outcome outcome = runner.apply(List.of(archive));

        assertTrue(outcome.applied(), outcome.toString());
        verify(marker).markApplied();
        verify(marker, never()).markFailed(anyString());
        verify(jobOperator, never()).stop(EXECUTION);
    }

    /** Returns the run id the runner began, read from its call to end() */
    private String endedRun() {
        ArgumentCaptor<String> run = ArgumentCaptor.forClass(String.class);
        verify(bootstrapRun, times(1)).end(run.capture());
        return run.getValue();
    }

    /**
     * Every job of a run carries the runner's token: the run's id and its archive's index, put after the item's own
     * properties so that an item cannot set it (the archive refuses it as well, {@link BootstrapArchiveTest})
     */
    @Test
    @SuppressWarnings("unchecked")
    void everyJobCarriesTheRunsTokenWithItsArchivesIndex() throws Exception {
        when(batchService.startBatchJobWithJsonData(anyString(), any(), anyString(), anyMap())).thenReturn(EXECUTION);
        when(jobOperator.getJobExecution(EXECUTION)).thenAnswer(i -> execution(BatchStatus.COMPLETED));
        BootstrapArchive second = BootstrapArchiveTest.read("second.zip",
                BootstrapArchiveTest.zipFolder(BootstrapArchiveTest.FIXTURES.resolve("minimal")));
        // Read and checked, then given a forged token, as no archive file can carry one
        archive.items().get(0).setProperties(new LinkedHashMap<>(Map.of(BootstrapRun.PROPERTY, "forged/0",
                "other", "x")));
        claimedBy(List.of(archive, second));

        BootstrapRunner.Outcome outcome = runner.apply(List.of(archive, second));

        assertTrue(outcome.applied(), outcome.toString());
        verify(bootstrapRun, times(1)).begin();
        String run = endedRun();
        ArgumentCaptor<Map<String, Object>> properties = ArgumentCaptor.forClass(Map.class);
        verify(batchService, times(2)).startBatchJobWithDataFile(eq("domain-import"), any(InputStream.class),
                eq("domains.json"), properties.capture());
        verify(batchService).startBatchJobWithJsonData(eq(BootstrapRunner.SCRIPT_RESOURCE_JOB), any(),
                anyString(), properties.capture());
        List<Map<String, Object>> started = properties.getAllValues();
        assertEquals(3, started.size(), "test.zip's domain job, then second.zip's two jobs: " + started);
        assertEquals(run + "/0", started.get(0).get(BootstrapRun.PROPERTY), "the item's own value is overridden");
        assertEquals("x", started.get(0).get("other"), "the item's other properties are kept");
        assertEquals(run + "/1", started.get(1).get(BootstrapRun.PROPERTY));
        assertEquals(run + "/1", started.get(2).get(BootstrapRun.PROPERTY));
    }

    @Test
    void theRunEndsWhenItIsApplied() {
        when(jobOperator.getJobExecution(EXECUTION)).thenAnswer(i -> execution(BatchStatus.COMPLETED));

        assertTrue(runner.apply(List.of(archive)).applied());

        InOrder order = inOrder(bootstrapRun);
        order.verify(bootstrapRun).begin();
        order.verify(bootstrapRun).end(anyString());
        assertTrue(endedRun().length() > 0);
    }

    @Test
    void theRunEndsWhenAJobFails() {
        when(jobOperator.getJobExecution(EXECUTION)).thenAnswer(i -> execution(BatchStatus.FAILED));

        assertEquals(BootstrapMarker.FAILED, runner.apply(List.of(archive)).state());

        verify(bootstrapRun, times(1)).begin();
        assertTrue(endedRun().length() > 0);
    }

    @Test
    void theRunEndsWhenAJobCannotStart() throws Exception {
        when(batchService.startBatchJobWithDataFile(anyString(), any(InputStream.class), anyString(), anyMap()))
                .thenThrow(new IllegalStateException("the batch folder is not writable"));

        BootstrapRunner.Outcome outcome = runner.apply(List.of(archive));

        assertEquals(BootstrapMarker.FAILED, outcome.state());
        assertTrue(outcome.error().contains("the batch folder is not writable"), outcome.error());
        verify(bootstrapRun, times(1)).begin();
        assertTrue(endedRun().length() > 0);
    }

    // ------------------------------------------------------------------------------------------- //
    // The claim: a bootstrap runs once, on a new, empty database (§7.2; D4 as amended on 2026-10-24)
    // ------------------------------------------------------------------------------------------- //

    /** Stubs what the marker's claim finds */
    private void theClaimFinds(BootstrapMarker.Claim claim) {
        when(marker.claim(anyString())).thenReturn(claim);
    }

    private BootstrapArchive second() {
        return BootstrapArchiveTest.read("second.zip",
                BootstrapArchiveTest.zipFolder(BootstrapArchiveTest.FIXTURES.resolve("minimal")));
    }

    /** The claim's refusal of a run of the archives */
    private BootstrapRefusedException refusal(List<BootstrapArchive> archives) {
        return assertThrows(BootstrapRefusedException.class, () -> runner.claim(archives));
    }

    @Test
    void aFailedMarkerIsRefusedWithOneArchiveOrMore() {
        theClaimFinds(new BootstrapMarker.Claim(false, BootstrapMarker.FAILED, "job domain-import ended FAILED",
                false, null));

        for (List<BootstrapArchive> archives : List.of(List.of(archive), List.of(archive, second()))) {
            BootstrapRefusedException e = refusal(archives);
            assertEquals(409, e.getStatus());
            assertTrue(e.getMessage().contains("'failed' (job domain-import ended FAILED)")
                    && e.getMessage().contains("never run again on the same database")
                    && e.getMessage().contains("recreate it empty"), e.getMessage());
        }
        verify(marker, times(2)).claim(anyString());
    }

    @Test
    void anInProgressMarkerIsRefusedLiveOrStale() {
        for (List<BootstrapArchive> archives : List.of(List.of(archive), List.of(archive, second()))) {
            theClaimFinds(new BootstrapMarker.Claim(false, BootstrapMarker.IN_PROGRESS, null, true, null));
            BootstrapRefusedException stale = refusal(archives);
            assertEquals(409, stale.getStatus());
            assertTrue(stale.getMessage().contains("'in-progress' with no sign of life for 5 minutes")
                    && stale.getMessage().contains("broke off")
                    && stale.getMessage().contains("recreate the database empty"), stale.getMessage());

            theClaimFinds(new BootstrapMarker.Claim(false, BootstrapMarker.IN_PROGRESS, null, false, null));
            BootstrapRefusedException live = refusal(archives);
            assertEquals(409, live.getStatus());
            assertTrue(live.getMessage().contains("'in-progress'") && live.getMessage().contains("wait"),
                    live.getMessage());
            assertFalse(live.getMessage().contains("recreate"), "a live run is never called broken off: "
                    + live.getMessage());
        }
    }

    @Test
    void aDeployedMarkerIsRefused() {
        for (String state : List.of(BootstrapMarker.EXISTING, BootstrapMarker.APPLIED)) {
            theClaimFinds(new BootstrapMarker.Claim(false, state, null, false, null));
            BootstrapRefusedException e = refusal(List.of(archive));
            assertEquals(BootstrapRefusedException.STATE, e.getStatus());
            assertTrue(e.getMessage().contains("'" + state + "'") && e.getMessage().contains("deployed"),
                    e.getMessage());
        }
    }

    @Test
    void aPendingMarkerOverDataIsRefusedNamingTheTable() {
        theClaimFinds(new BootstrapMarker.Claim(false, BootstrapMarker.PENDING, null, false, "Domain"));

        BootstrapRefusedException e = refusal(List.of(archive));

        assertEquals(BootstrapRefusedException.DATA, e.getStatus());
        assertTrue(e.getMessage().contains("a row in \"Domain\"")
                && e.getMessage().contains("a new, empty database")
                && e.getMessage().contains("recreate the database empty"), e.getMessage());
    }

    /** A pending marker the claim read but did not move on: the internal race, told to ask again */
    @Test
    void aPendingMarkerTheClaimDidNotMoveOnIsRefusedAsARace() {
        BootstrapRefusedException e = BootstrapRunner.refusal(
                new BootstrapMarker.Claim(false, BootstrapMarker.PENDING, null, false, null));

        assertEquals(BootstrapRefusedException.STATE, e.getStatus());
        assertEquals("The claim did not take effect although the marker reads 'pending' (another claim changed it "
                + "meanwhile); ask again", e.getMessage());
    }

    @Test
    void aMarkerNotWrittenYetIsRefused() {
        theClaimFinds(new BootstrapMarker.Claim(false, null, null, false, null));

        BootstrapRefusedException e = refusal(List.of(archive));

        assertEquals(BootstrapRefusedException.STATE, e.getStatus());
        assertTrue(e.getMessage().contains("not written yet"), e.getMessage());
    }

    @Test
    void aPendingMarkerIsClaimedWithTwoArchives() {
        theClaimFinds(new BootstrapMarker.Claim(true, BootstrapMarker.IN_PROGRESS, null, false, null));

        runner.claim(List.of(archive, second()));

        verify(marker).claim(BootstrapArchive.runHash(List.of(archive, second())));
    }

    /** An archive whose domain-import file is the given content */
    private static BootstrapArchive withDomains(String name, String domains) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("batch-set.json", "[{\"jobName\": \"domain-import\", \"fileName\": \"domains.json\"}]"
                .getBytes(StandardCharsets.UTF_8));
        entries.put("domains.json", domains.getBytes(StandardCharsets.UTF_8));
        return BootstrapArchiveTest.read(name, BootstrapArchiveTest.zip(entries));
    }

    /**
     * The domain import keeps an existing domain as it is, apart from its active flag within the first bootstrap, so a
     * later archive's other values for a domain an earlier one gives would be lost: a restatement that gives more than
     * the active flag is refused before the marker is claimed
     */
    @Test
    void aLaterArchiveRestatingADomainIdIsRefusedBeforeTheMarkerIsClaimed() {
        BootstrapArchive earlier = withDomains("earlier.zip", "[{\"domainId\": \"it-a\", \"name\": \"A\"}]");
        BootstrapArchive later = withDomains("later.zip",
                "[{\"domainId\": \"it-b\", \"name\": \"B\"}, {\"domainId\": \"it-a\", \"name\": \"A again\"}]");

        BootstrapRefusedException e = refusal(List.of(earlier, later));

        assertEquals(BootstrapRefusedException.ARCHIVE, e.getStatus());
        assertTrue(e.getMessage().contains("'later.zip'") && e.getMessage().contains("'it-a'")
                && e.getMessage().contains("'earlier.zip'"), e.getMessage());
        verify(marker, never()).claim(anyString());
    }

    /**
     * A later archive may restate an earlier archive's domain with its domainId and active flag only, so that a country
     * may leave the base product's master domain inactive (the owner's ruling of 2026-11-03): the run reaches the claim
     */
    @Test
    void aLaterArchiveMayRestateADomainWithItsActiveFlagOnly() {
        theClaimFinds(new BootstrapMarker.Claim(true, BootstrapMarker.IN_PROGRESS, null, false, null));
        BootstrapArchive earlier = withDomains("earlier.zip", "[{\"domainId\": \"it-a\", \"name\": \"A\"}]");
        BootstrapArchive later = withDomains("later.zip",
                "[{\"domainId\": \"it-a\", \"active\": false}, {\"domainId\": \"it-b\", \"name\": \"B\"}]");

        runner.claim(List.of(earlier, later));

        verify(marker).claim(anyString());
    }

    /**
     * A restatement that gives anything but the domainId and a JSON boolean active is refused before the claim: a name
     * besides the flag, no flag, the flag as a string or null (the import's primitive would read them as false), and
     * an active-only row the same archive gives again with a name
     */
    @Test
    void aRestatementGivingMoreOrLessThanABooleanActiveIsRefused() {
        BootstrapArchive earlier = withDomains("earlier.zip", "[{\"domainId\": \"it-a\", \"name\": \"A\"}]");
        for (String domains : List.of(
                "[{\"domainId\": \"it-a\", \"active\": false, \"name\": \"A\"}]",
                "[{\"domainId\": \"it-a\"}]",
                "[{\"domainId\": \"it-a\", \"active\": \"false\"}]",
                "[{\"domainId\": \"it-a\", \"active\": null}]",
                "[{\"domainId\": \"it-a\", \"active\": false}, {\"domainId\": \"it-a\", \"name\": \"A again\"}]")) {
            BootstrapRefusedException e = refusal(List.of(earlier, withDomains("later.zip", domains)));

            assertEquals(BootstrapRefusedException.ARCHIVE, e.getStatus(), domains);
            assertTrue(e.getMessage().contains("'later.zip'") && e.getMessage().contains("'it-a'")
                    && e.getMessage().contains("'earlier.zip'"), e.getMessage());
        }
        verify(marker, never()).claim(anyString());
    }

    /** A domain file that is not JSON gives no domain, so the run reaches the claim and the domain import fails it */
    @Test
    void aDomainFileThatIsNotJsonGivesNoDomain() {
        theClaimFinds(new BootstrapMarker.Claim(true, BootstrapMarker.IN_PROGRESS, null, false, null));
        BootstrapArchive broken = withDomains("broken.zip", "This file is not JSON");

        runner.claim(List.of(withDomains("earlier.zip", "[{\"domainId\": \"it-a\"}]"), broken));

        assertEquals(List.of(), List.copyOf(broken.domainIds()));
        verify(marker).claim(anyString());
    }

    /** apply runs only on a marker this run claimed, and otherwise writes nothing */
    @Test
    void applyRefusesAMarkerThisRunDidNotClaim() {
        String hash = BootstrapArchive.runHash(List.of(archive));
        List<Optional<BootstrapMarker.State>> markers = List.of(
                Optional.empty(),
                Optional.of(new BootstrapMarker.State(BootstrapMarker.PENDING, null, null, "4.0.0", null)),
                Optional.of(new BootstrapMarker.State(BootstrapMarker.IN_PROGRESS, "another-run", null, "4.0.0",
                        OffsetDateTime.now())),
                Optional.of(new BootstrapMarker.State(BootstrapMarker.FAILED, hash, "it failed", "4.0.0",
                        OffsetDateTime.now())));
        for (Optional<BootstrapMarker.State> state : markers) {
            when(marker.read()).thenReturn(state);

            BootstrapRefusedException e = assertThrows(BootstrapRefusedException.class,
                    () -> runner.apply(List.of(archive)));

            assertEquals(BootstrapRefusedException.STATE, e.getStatus());
            assertTrue(e.getMessage().contains("apply runs only on a marker this run claimed"), e.getMessage());
        }
        verify(bootstrapRun, never()).begin();
        verifyNoInteractions(batchService, jobOperator);
        verify(marker, never()).markFailed(anyString());
        verify(marker, never()).markApplied();
    }

    @Test
    void aJobThatFailsBeforeTheDeadlineFailsTheRunAtOnce() {
        JobExecution failed = execution(BatchStatus.FAILED);
        when(jobOperator.getJobExecution(EXECUTION)).thenReturn(failed);

        BootstrapRunner.Outcome outcome = runner.apply(List.of(archive));

        assertEquals(BootstrapMarker.FAILED, outcome.state());
        assertTrue(outcome.error().contains("job domain-import (domains.json in test.zip) ended FAILED"),
                outcome.error());
        verify(jobOperator, never()).stop(EXECUTION);
    }
}
