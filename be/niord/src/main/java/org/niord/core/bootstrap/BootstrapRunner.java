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
import org.niord.core.batch.BatchService;
import org.niord.core.batch.vo.BatchSetVo;
import org.niord.core.script.ScriptResource;
import org.niord.core.script.vo.ScriptResourceVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Applies the bootstrap archives to a new, empty database, once (docs/code-data-config-plan.md §7.2, D4).
 *
 * <p>It starts the existing import jobs through JBeret, one at a time, each as an upload does: with its data file in
 * the execution folder of its own {@code BatchData} row, so the jobs' handlers are unchanged. Starting a job returns
 * at once, so the runner waits for that job's execution to end before it starts the next; a job that does not end
 * {@code COMPLETED} fails the run. A job still running after {@link #JOB_TIMEOUT} is asked to stop, and the runner
 * waits on, without a deadline, until its execution has ended: only then is the run {@code failed}, so that
 * {@code failed} means nothing writes any more. The batch set's {@code delay}s are not read. Within an archive the
 * jobs run in the order of {@link #ORDERED_JOBS}, then the {@link #LATER_JOBS} in the order of its
 * {@code batch-set.json}; the archives run in the order given. Every archive has passed {@link BootstrapArchive}'s
 * checks, the script check of each template and script among them, before anything is written, and no later archive
 * restates a domain an earlier one gives, other than with its domainId and active flag only ({@link #claim}).
 *
 * <p>Two guards, both checked in the marker's claim, in one locked transaction committed before the first job starts
 * ({@link BootstrapMarker#claim}): the marker reads {@code pending}, and the database holds no data. The marker then
 * moves to {@code applied} after the last job, or to {@code failed} with the job, its file and its status, once the
 * last execution has ended. {@code failed} is final, and so is an {@code in-progress} that broke off: there is no
 * retry on the same database and no takeover, whatever the number of archives. The remedy is the database recreated
 * empty, the backend started, and the bootstrap run again with the same archives. While it waits, the runner touches
 * the marker every {@link BootstrapMarker#HEARTBEAT}, so that a refusal can tell a live run from one that broke off.
 * {@link #apply} runs only on a marker this run claimed.
 *
 * <p>The run is the one first-time deployment, and within it a later archive takes precedence ({@link BootstrapRun}):
 * for an entry an earlier archive of the run created, each value the later archive gives replaces the earlier one's,
 * a value it leaves out is kept, and what is new is added; nothing is removed (for a domain, the later archive may
 * give its active flag only). The runner begins the run's record before the first archive, gives every job the
 * property {@value BootstrapRun#PROPERTY} naming the run and the job's archive, and ends the record whatever the
 * outcome.
 *
 * <p>Not transactional: each job commits its own work, and the waits hold no transaction open.
 */
@Service
public class BootstrapRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    public static final String SCRIPT_RESOURCE_JOB = "script-resource-import";

    /** The jobs that run first, in this order, in every archive */
    public static final List<String> ORDERED_JOBS = List.of(
            SCRIPT_RESOURCE_JOB,
            "domain-import",
            "dictionary-import",
            "category-import",
            "param-type-import",
            "mailing-list-import",
            "report-import",
            "promulgation-type-import");

    /** The jobs that run after those, in the order of the archive's {@code batch-set.json} (here the sample's) */
    public static final List<String> LATER_JOBS = List.of(
            "chart-import",
            "area-import",
            "source-import",
            "publication-category-import",
            "publication-import");

    /**
     * Every job the bootstrap runs. Any other is refused, settings-import, script-executor, message-import,
     * msg-archive-import and publication-series-import among them: no settings, no messages, no code.
     */
    public static final List<String> ACCEPTED_JOBS =
            Stream.concat(ORDERED_JOBS.stream(), LATER_JOBS.stream()).toList();

    /** How long one job may run before the runner asks it to stop */
    static final Duration JOB_TIMEOUT = Duration.ofMinutes(10);

    /** {@link #JOB_TIMEOUT}, in a field so that a test can shorten it */
    Duration jobTimeout = JOB_TIMEOUT;

    /** How often the runner asks for the execution's status */
    Duration poll = Duration.ofMillis(250);

    /** How often the runner touches the marker while it waits */
    Duration heartbeat = BootstrapMarker.HEARTBEAT;

    /** How often a failing status read is logged while the runner keeps asking */
    Duration errorLogEvery = Duration.ofMinutes(1);

    /** The end of a run */
    public record Outcome(String state, String error) {

        /** Returns whether the run applied every job */
        public boolean applied() {
            return BootstrapMarker.APPLIED.equals(state);
        }
    }

    private final BootstrapMarker marker;
    private final BatchService batchService;
    private final JobOperator jobOperator;
    private final BootstrapRun bootstrapRun;

    BootstrapRunner(BootstrapMarker marker, BatchService batchService, JobOperator jobOperator,
                    BootstrapRun bootstrapRun) {
        this.marker = marker;
        this.batchService = batchService;
        this.jobOperator = jobOperator;
        this.bootstrapRun = bootstrapRun;
    }

    /**
     * Claims the marker and applies the archives, on the calling thread
     *
     * @param archives the archives, read and checked, in the order they are applied
     * @return the outcome
     * @throws BootstrapRefusedException when the marker cannot be claimed ({@link #claim})
     */
    public Outcome run(List<BootstrapArchive> archives) {
        claim(archives);
        return apply(archives);
    }

    /**
     * Claims the marker for a run of the archives: {@code pending} becomes {@code in-progress} when the database holds
     * no data ({@link BootstrapMarker#claim}). Before that, a later archive whose domain file gives a domainId an
     * earlier archive's also gives is refused, unless it gives that domain with its domainId and a boolean active only:
     * the domain import keeps an existing domain as it is, apart from that flag within the run, so the later archive's
     * other values for it would be lost.
     *
     * @param archives the archives, read and checked
     * @throws BootstrapRefusedException 400 when there is no archive or a later one restates a domain with more than
     *                                   its active flag; 409 when the marker reads anything but {@code pending} (the
     *                                   state, and the remedy, named) or the database holds data (the first table
     *                                   that holds a row named)
     */
    public void claim(List<BootstrapArchive> archives) {
        if (archives == null || archives.isEmpty()) {
            throw new BootstrapRefusedException("No archive to apply", BootstrapRefusedException.ARCHIVE);
        }
        refuseARestatedDomain(archives);
        BootstrapMarker.Claim claim = marker.claim(BootstrapArchive.runHash(archives));
        if (!claim.claimed()) {
            throw refusal(claim);
        }
        log.info("Bootstrap claimed for " + archives.stream().map(BootstrapArchive::name).toList());
    }

    /**
     * Refuses a later archive that gives a domainId an earlier archive of the run gives (400, before the claim), other
     * than with its domainId and a boolean active only ({@link BootstrapArchive#activeOnlyDomainIds()}): that flag is
     * the one value of a domain a later archive may replace (the owner's ruling of 2026-11-03)
     *
     * @param archives the run's archives, in order
     */
    static void refuseARestatedDomain(List<BootstrapArchive> archives) {
        Map<String, String> givenBy = new HashMap<>();
        for (BootstrapArchive archive : archives) {
            Set<String> ids = archive.domainIds();
            Set<String> activeOnly = archive.activeOnlyDomainIds();
            for (String id : ids) {
                String earlier = givenBy.get(id);
                if (earlier != null && !activeOnly.contains(id)) {
                    throw new BootstrapRefusedException("The archive '" + archive.name() + "' gives the domain '" + id
                            + "', which the earlier archive '" + earlier + "' gives: the domain import keeps an "
                            + "existing domain apart from its active flag within the run, so a later archive may "
                            + "restate an earlier archive's domain with its domainId and a boolean active only",
                            BootstrapRefusedException.ARCHIVE);
                }
            }
            ids.forEach(id -> givenBy.putIfAbsent(id, archive.name()));
        }
    }

    /** The refusal of a claim that did not claim the marker, naming the state or the table and the remedy */
    static BootstrapRefusedException refusal(BootstrapMarker.Claim claim) {
        String recreate = "recreate the database empty, start the backend, bootstrap again with the same archives (D4)";
        String state = claim.state();
        if (state == null) {
            return new BootstrapRefusedException("The bootstrap marker is not written yet: the backend is still "
                    + "starting; ask again in a moment", BootstrapRefusedException.STATE);
        }
        if (claim.dataTable() != null) {
            return new BootstrapRefusedException("The database holds data (a row in \"" + claim.dataTable()
                    + "\"): a bootstrap runs only on a new, empty database; " + recreate,
                    BootstrapRefusedException.DATA);
        }
        String message = switch (state) {
            case BootstrapMarker.EXISTING, BootstrapMarker.APPLIED -> "The bootstrap marker reads '" + state
                    + "': this database is deployed; a bootstrap never runs on it";
            case BootstrapMarker.FAILED -> "The bootstrap marker reads 'failed' (" + claim.error() + "); a failed "
                    + "bootstrap is never run again on the same database: recreate it empty, start the backend, "
                    + "bootstrap again with the same archives (D4)";
            case BootstrapMarker.IN_PROGRESS -> claim.stale()
                    ? "The bootstrap marker reads 'in-progress' with no sign of life for "
                    + BootstrapMarker.STALE_AFTER.toMinutes() + " minutes: a bootstrap broke off; " + recreate
                    : "The bootstrap marker reads 'in-progress': a bootstrap is running; wait, GET /rest/bootstrap "
                    + "shows its outcome";
            // Read pending under the lock and then not moved on: another claim changed the row meanwhile
            case BootstrapMarker.PENDING -> "The claim did not take effect although the marker reads 'pending' "
                    + "(another claim changed it meanwhile); ask again";
            default -> "The bootstrap marker reads '" + state + "'; a bootstrap runs only on a database whose "
                    + "marker reads 'pending'";
        };
        return new BootstrapRefusedException(message, BootstrapRefusedException.STATE);
    }

    /**
     * Applies the archives of a run already claimed, and ends the marker as {@code applied} or {@code failed}
     *
     * @param archives the archives, read and checked, in the order they are applied
     * @return the outcome
     * @throws BootstrapRefusedException when the marker is not one this run claimed ({@code in-progress} with the
     *                                   run's hash); nothing is written
     */
    public Outcome apply(List<BootstrapArchive> archives) {
        String runHash = BootstrapArchive.runHash(archives);
        Optional<BootstrapMarker.State> state = marker.read();
        if (state.isEmpty() || !BootstrapMarker.IN_PROGRESS.equals(state.get().state())
                || !runHash.equals(state.get().hash())) {
            throw new BootstrapRefusedException("apply runs only on a marker this run claimed: the marker reads "
                    + state.map(s -> "'" + s.state() + "' (run " + s.hash() + ")").orElse("nothing yet"),
                    BootstrapRefusedException.STATE);
        }
        String run = bootstrapRun.begin();
        try {
            for (int index = 0; index < archives.size(); index++) {
                BootstrapArchive archive = archives.get(index);
                for (BatchSetVo item : ordered(archive.items())) {
                    String error = runJob(archive, index, item, runHash, run);
                    if (error != null) {
                        log.error("Bootstrap failed: " + error);
                        marker.markFailed(error);
                        return new Outcome(BootstrapMarker.FAILED, error);
                    }
                }
            }
        } catch (Exception e) {
            String error = "The bootstrap stopped: " + e;
            log.error(error, e);
            marker.markFailed(error);
            return new Outcome(BootstrapMarker.FAILED, error);
        } finally {
            bootstrapRun.end(run);
        }
        marker.markApplied();
        log.info("Bootstrap applied: " + archives.stream().map(BootstrapArchive::name).toList());
        return new Outcome(BootstrapMarker.APPLIED, null);
    }

    /**
     * Returns the items in the order they run: the {@link #ORDERED_JOBS} in that order, then the rest as listed
     *
     * @param items the items of a {@code batch-set.json}
     * @return the items in the order they run
     */
    static List<BatchSetVo> ordered(List<BatchSetVo> items) {
        List<BatchSetVo> sorted = new ArrayList<>(items);
        // A stable sort: the later jobs share one rank and keep their order
        sorted.sort(Comparator.comparingInt(item -> {
            int rank = ORDERED_JOBS.indexOf(item.getJobName());
            return rank >= 0 ? rank : ORDERED_JOBS.size();
        }));
        return sorted;
    }

    /**
     * Starts one job and waits for its execution to end, touching the marker while it waits. A job still running at
     * the deadline is asked to stop, and the wait goes on until its execution has ended; a status that cannot be read
     * is asked for again, and a marker write that fails is logged and the wait goes on. An interruption does not end
     * the wait either: the flag is set again when the method returns.
     *
     * @param archive the archive
     * @param index   the archive's index in the run
     * @param item    the job and its file
     * @param runHash the run's hash, as the marker records it
     * @param run     the run id ({@link BootstrapRun#begin()})
     * @return null when it completed, else what went wrong: the job, its file and its status
     */
    String runJob(BootstrapArchive archive, int index, BatchSetVo item, String runHash, String run) throws Exception {
        String job = item.getJobName();
        String what = "job " + job + " (" + item.getFileName() + " in " + archive.name() + ")";
        Map<String, Object> properties = item.getProperties() != null
                ? new HashMap<>(item.getProperties())
                : new HashMap<>();
        // The runner's own, after the item's: an archive never sets it (BootstrapArchive refuses it)
        properties.put(BootstrapRun.PROPERTY, BootstrapRun.token(run, index));

        log.info("Bootstrap: starting " + what);
        long executionId;
        if (SCRIPT_RESOURCE_JOB.equals(job)) {
            executionId = batchService.startBatchJobWithJsonData(
                    job, scriptResources(archive), "script-resources.json", properties);
        } else {
            executionId = batchService.startBatchJobWithDataFile(
                    job, new ByteArrayInputStream(archive.file(item.getFileName())), item.getFileName(), properties);
        }

        long deadline = System.nanoTime() + jobTimeout.toNanos();
        long nextHeartbeat = System.nanoTime() + heartbeat.toNanos();
        long nextErrorLog = System.nanoTime();
        boolean interrupted = false;
        String timedOut = null;
        try {
            while (true) {
                JobExecution execution = null;
                try {
                    execution = jobOperator.getJobExecution(executionId);
                } catch (RuntimeException e) {
                    if (System.nanoTime() - nextErrorLog >= 0) {
                        log.warn("Bootstrap: cannot read the status of execution " + executionId + " of " + what
                                + "; asking again: " + e);
                        nextErrorLog = System.nanoTime() + errorLogEvery.toNanos();
                    }
                }
                BatchStatus status = execution != null ? execution.getBatchStatus() : null;
                if (status == BatchStatus.COMPLETED && timedOut == null) {
                    log.info("Bootstrap: " + what + " completed");
                    return null;
                }
                if (status == BatchStatus.COMPLETED || status == BatchStatus.FAILED
                        || status == BatchStatus.STOPPED || status == BatchStatus.ABANDONED) {
                    return timedOut != null
                            ? timedOut + "; it then ended " + status + " (exit status " + execution.getExitStatus()
                            + ")"
                            : what + " ended " + status + " (exit status " + execution.getExitStatus()
                            + "; execution " + executionId + ", its log in the batch job's folder)";
                }
                if (timedOut == null && System.nanoTime() - deadline > 0) {
                    timedOut = what + " did not end within " + minutes(jobTimeout) + " (status " + status
                            + "; execution " + executionId + ")";
                    String note = timedOut + "; a stop was requested; the run waits for execution " + executionId
                            + " to end";
                    log.error("Bootstrap: " + note);
                    nextErrorLog = touch(() -> marker.note(runHash, note), "note the stop on", what, nextErrorLog);
                    nextHeartbeat = System.nanoTime() + heartbeat.toNanos();
                    try {
                        jobOperator.stop(executionId);
                    } catch (RuntimeException e) {
                        // JobExecutionNotRunningException among them: the execution ended meanwhile
                        log.warn("Bootstrap: the stop of execution " + executionId + " was not accepted: " + e);
                    }
                }
                if (System.nanoTime() - nextHeartbeat >= 0) {
                    nextErrorLog = touch(() -> marker.heartbeat(runHash), "touch", what, nextErrorLog);
                    nextHeartbeat = System.nanoTime() + heartbeat.toNanos();
                }
                try {
                    Thread.sleep(poll.toMillis());
                } catch (InterruptedException e) {
                    // The execution may still write: the wait goes on, and the flag is set again at the end
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Writes to the marker while the runner waits. A write that fails (the database briefly unreachable) is logged,
     * at most once every {@link #errorLogEvery} together with the status reads, and the wait goes on: ending the wait
     * on it would mark the run failed while the execution may still write. A missed heartbeat matters only after
     * {@link BootstrapMarker#STALE_AFTER} of them.
     *
     * @return the time of the next error log
     */
    private long touch(Runnable write, String action, String what, long nextErrorLog) {
        try {
            write.run();
        } catch (RuntimeException e) {
            if (System.nanoTime() - nextErrorLog >= 0) {
                log.warn("Bootstrap: cannot " + action + " the marker while waiting for " + what
                        + "; the wait goes on: " + e);
                return System.nanoTime() + errorLogEvery.toNanos();
            }
        }
        return nextErrorLog;
    }

    /** A duration as the marker's error says it: whole minutes, or milliseconds below one minute (a test's) */
    private static String minutes(Duration duration) {
        return duration.toMinutes() > 0 ? duration.toMinutes() + " minutes" : duration.toMillis() + " ms";
    }

    /**
     * Turns the archive's {@code templates/} folder into the script-resource import's data: path
     * {@code templates/<path in the folder>}, FreeMarker for {@code *.ftl} and JavaScript for {@code *.js}
     */
    static List<ScriptResourceVo> scriptResources(BootstrapArchive archive) {
        List<ScriptResourceVo> resources = new ArrayList<>();
        archive.templates().forEach((path, content) -> {
            ScriptResourceVo vo = new ScriptResourceVo();
            vo.setType(path.endsWith(".js") ? ScriptResource.Type.JS : ScriptResource.Type.FM);
            vo.setPath(path);
            vo.setContent(new String(content, StandardCharsets.UTF_8));
            resources.add(vo);
        });
        return resources;
    }
}
