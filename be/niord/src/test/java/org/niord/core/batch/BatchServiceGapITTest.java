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
package org.niord.core.batch;

import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchStatus;
import org.apache.commons.io.FileUtils;
import org.niord.core.batch.vo.BatchInstanceVo;
import org.niord.core.bootstrap.BootstrapRun;
import org.niord.test.BatchJobs;
import org.niord.test.Tx;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gap-coverage integration tests for {@link BatchService}, complementing {@link BatchServiceITTest}.
 *
 * <p>These tests target the batch-job <em>log</em> and <em>binary data</em> file APIs that the sibling
 * {@link BatchServiceITTest} does not exercise:
 * <ul>
 *   <li>{@link BatchService#getBatchJobDataFile(Long)} for a binary data file — the byte round-trip and the
 *       absent-file null path.</li>
 *   <li>{@link BatchService#getBatchJobLogFiles(Long)} — the {@code .+Log\.txt(\.\d+)?} filter, which keeps
 *       only log files (ignoring e.g. {@code data.json}) and returns them sorted.</li>
 *   <li>{@link BatchService#getBatchJobLogFile(Long, String, Integer)} — newline joining, the
 *       {@code fromLineNo} skip, and the {@code "Invalid batch log file"} error path for a missing log name.</li>
 *   <li>{@link BatchService#getJobInstances(String, int, int)} — the bootstrap runner's token left out of the
 *       instances' properties, while the row keeps it (a real, empty {@code domain-import} job, committed and
 *       removed again); and, where more than one row holds an instance id, the listed job's own newest row.</li>
 * </ul>
 *
 * <p>Each test persists a {@link BatchData} row inside a {@link TestTransaction} (rolled back to keep the
 * shared seed database pristine) but the log / data files themselves live on the real batch-job repository on
 * disk, outside the rolled-back transaction, so every test removes its own files in a {@code finally} block.
 * Batch jobs use a unique {@code IT-BATCH-GAP-} job-name prefix that never occurs in the seed data and unique
 * instance / job numbers so the on-disk execution folders never collide with other tests or seed data.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchServiceGapITTest extends ITTest {

    @Autowired
    BatchService batchService;

    @Autowired
    JobOperator jobOperator;

    @PersistenceContext
    EntityManager em;

    /** Builds a detached BatchData with the mandatory non-null fields populated and a fixed created date. */
    private BatchData newJob(String jobName, long jobNo, long instanceId) {
        BatchData job = new BatchData();
        job.setJobName(jobName);
        job.setJobNo(jobNo);
        job.setInstanceId(instanceId);
        // A fixed created date keeps the computed execution-folder path deterministic.
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(2020, Calendar.MARCH, 15, 12, 0, 0);
        job.setCreated(new Date(cal.getTimeInMillis()));
        return job;
    }

    /** Writes a file (creating parent directories) to a path inside the batch-job repository. */
    private void writeFile(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    // ------------------------------------------------------------------------------------------- //
    // getBatchJobDataFile — a binary (zip) data file
    //
    // These two tests exercised readBatchJobDeflatedDataFile, a Java-deserialization reader of batch data
    // files that no production code called. It was removed (with its writer, startBatchJobWithDeflatedData)
    // so that no upload can ever reach an ObjectInputStream; the tests now pin the file lookup they relied on.
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getBatchJobDataFileFindsABinaryDataFileWithItsBytesIntact() throws Exception {
        // Persist a job that declares a zip data file, then write bytes to the exact path the service
        // computes, and find them again through the instance.
        BatchData job = newJob("IT-BATCH-GAP-binary", 1L, 200_001L);
        job.setDataFileName("batch-data.zip");
        batchService.saveBatchJob(job);
        em.flush();

        Path path = batchService.computeBatchJobPath(job.computeDataFilePath());
        Files.createDirectories(path.getParent());

        byte[] payload = new byte[] { 'P', 'K', 3, 4, 0, (byte) 0xFF, 42 };
        try {
            Files.write(path, payload);

            // The data file is discoverable for the instance and holds the bytes written.
            assertEquals(path, batchService.getBatchJobDataFile(200_001L));
            assertArrayEquals(payload, Files.readAllBytes(batchService.getBatchJobDataFile(200_001L)),
                    "the data file must hold exactly the bytes that were written");
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    @Transactional
    void getBatchJobDataFileReturnsNullWhenBinaryFileMissingOnDisk() throws Exception {
        // The job declares a zip data file name, but no file was written on disk, so the lookup must
        // return null rather than throw.
        BatchData job = newJob("IT-BATCH-GAP-binary-missing", 1L, 200_002L);
        job.setDataFileName("absent.zip");
        batchService.saveBatchJob(job);
        em.flush();

        assertNull(batchService.getBatchJobDataFile(200_002L),
                "a declared-but-missing data file must resolve to null");
    }

    // ------------------------------------------------------------------------------------------- //
    // getBatchJobLogFiles — log-file filter, sorting, and non-log exclusion
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getBatchJobLogFilesReturnsOnlySortedLogNamesIgnoringDataFile() throws Exception {
        // Three files share the execution folder: two log files and a non-log data.json. Only the log files
        // (matching ".+Log\.txt(\.\d+)?") must be returned, sorted by name.
        BatchData job = newJob("IT-BATCH-GAP-logs", 1L, 200_010L);
        batchService.saveBatchJob(job);
        em.flush();

        Path folder = batchService.computeBatchJobPath(job.computeBatchJobFolderPath());
        Path log0 = folder.resolve("ReaderLog.txt");
        Path log1 = folder.resolve("ReaderLog.txt.1");
        Path nonLog = folder.resolve("data.json");
        try {
            writeFile(log0, "current log");
            writeFile(log1, "rolled-over log");
            writeFile(nonLog, "{\"some\":\"data\"}");

            List<String> logs = batchService.getBatchJobLogFiles(200_010L);

            assertEquals(List.of("ReaderLog.txt", "ReaderLog.txt.1"), logs,
                    "only files matching the log-name pattern must be returned, sorted, with data.json ignored");
        } finally {
            Files.deleteIfExists(log0);
            Files.deleteIfExists(log1);
            Files.deleteIfExists(nonLog);
            Files.deleteIfExists(folder);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // getBatchJobLogFile — newline joining and fromLineNo skip
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getBatchJobLogFileJoinsLinesWithNewlineAndSkipsFromLineNo() throws Exception {
        // The log file has four lines. Read in full it is re-joined by '\n'; with fromLineNo=2 the first
        // two lines are skipped.
        BatchData job = newJob("IT-BATCH-GAP-logfile", 1L, 200_020L);
        batchService.saveBatchJob(job);
        em.flush();

        Path folder = batchService.computeBatchJobPath(job.computeBatchJobFolderPath());
        Path log = folder.resolve("ReaderLog.txt");
        try {
            writeFile(log, "line-1\nline-2\nline-3\nline-4\n");

            String full = batchService.getBatchJobLogFile(200_020L, "ReaderLog.txt", null);
            assertEquals("line-1\nline-2\nline-3\nline-4", full,
                    "the full log must be the file lines re-joined by a single newline");

            String fromTwo = batchService.getBatchJobLogFile(200_020L, "ReaderLog.txt", 2);
            assertEquals("line-3\nline-4", fromTwo,
                    "fromLineNo=2 must skip the first two lines");
        } finally {
            Files.deleteIfExists(log);
            Files.deleteIfExists(folder);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // getJobInstances — the bootstrap runner's token is left out of the instances' properties
    //
    // The token names the live bootstrap run (docs/code-data-config-plan.md §7.2); GET /rest/batch/{job}/instances
    // answers these properties, so a sysadmin could otherwise copy it into an upload or a batch set.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getJobInstancesLeavesOutTheBootstrapRunnersTokenAndTheRowKeepsIt() throws Exception {
        Map<String, Object> properties = new HashMap<>();
        properties.put(BootstrapRun.PROPERTY, "x/0");
        properties.put("other", "y");
        // An empty domain import, started again until no other row shares its instance id: the job repository is in
        // memory (BATCH5 in be/docs/potential-errors.md), so its ids restart at 1 in each JVM, and rows of other runs
        // may carry the same ids. The instances are listed most recent first, so the first page is this job.
        List<Integer> rows = new java.util.ArrayList<>();
        long instanceId;
        int attempts = 0;
        do {
            long execution = batchService.startBatchJobWithJsonData("domain-import", List.of(),
                    "it-gap-token-" + System.nanoTime() + ".json", properties);
            assertEquals(BatchStatus.COMPLETED, BatchJobs.awaitCompletion(jobOperator, execution));
            long id = jobOperator.getJobInstance(execution).getInstanceId();
            instanceId = id;
            rows.addAll(Tx.requiringNew().call(() -> em.createQuery(
                            "select b.id from BatchData b where b.instanceId = :i and b.dataFileName like 'it-gap-token-%'",
                            Integer.class).setParameter("i", id).getResultList()));
        } while (sharedInstanceId(instanceId) && ++attempts < 40);
        long theInstance = instanceId;
        BatchData row = Tx.requiringNew().call(() -> em.find(BatchData.class, rows.get(rows.size() - 1)));
        try {
            BatchInstanceVo instance = batchService.getJobInstances("domain-import", 0, 1).getData().stream()
                    .filter(i -> i.getInstanceId() == theInstance)
                    .findFirst()
                    .orElseThrow();

            assertEquals("y", instance.getProperties().get("other"), "the other properties are answered");
            assertFalse(instance.getProperties().containsKey(BootstrapRun.PROPERTY),
                    "the token is left out: " + instance.getProperties());
            Map<String, Object> stored = Tx.requiringNew().call(() ->
                    new HashMap<>(em.find(BatchData.class, row.getId()).getProperties()));
            assertEquals("x/0", stored.get(BootstrapRun.PROPERTY), "the row keeps the token");
            assertEquals("y", stored.get("other"));
        } finally {
            for (Integer id : rows) {
                Tx.requiringNew().run(() -> {
                    BatchData found = em.find(BatchData.class, id);
                    if (found != null) {
                        FileUtils.deleteQuietly(
                                batchService.computeBatchJobPath(found.computeBatchJobFolderPath()).toFile());
                        em.remove(found);
                    }
                });
            }
        }
    }

    /** Whether a row other than this test's own carries the instance id */
    private boolean sharedInstanceId(long instanceId) {
        return Tx.requiringNew().call(() -> em.createQuery("select count(b) from BatchData b "
                        + "where b.instanceId = :i and (b.dataFileName is null or b.dataFileName not like 'it-gap-token-%')",
                Long.class).setParameter("i", instanceId).getSingleResult()) > 0;
    }

    // ------------------------------------------------------------------------------------------- //
    // getJobInstances — an instance id that more than one row holds
    //
    // The job repository is in memory, so its instance ids start at 1 again in every JVM, while the BatchData rows
    // stay: after a restart a new instance shares its id with rows of earlier runs, of any job. The list takes the
    // listed job's own rows only, and of those the newest by created, as findByInstanceId does.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getJobInstancesAnswersTheJobsOwnRowWhereOtherJobsRowsHoldItsInstanceId() throws Exception {
        String fileName = "it-gap-shared-" + System.nanoTime() + ".json";
        BatchData own = runEmptyDomainImport(fileName);
        List<Integer> fixtures = new java.util.ArrayList<>();
        try {
            // A row of another job with the same instance id, as an earlier JVM leaves it, and one dated after the
            // job's own: neither is the job's
            BatchData older = newJob("IT-BATCH-GAP-other", 1L, own.getInstanceId());
            older.setDataFileName("it-gap-other-older.json");
            fixtures.add(commit(older));
            BatchData newer = newJob("IT-BATCH-GAP-other", 2L, own.getInstanceId());
            newer.setCreated(new Date(own.getCreated().getTime() + 60_000L));
            newer.setDataFileName("it-gap-other-newer.json");
            fixtures.add(commit(newer));

            BatchInstanceVo instance = listedInstance(own.getInstanceId());

            assertEquals(fileName, instance.getFileName(), "the instance carries its own job's file name");
            assertEquals(own.getJobNo(), instance.getJobNo());
            assertEquals("domain-import", instance.getJobName());
        } finally {
            removeRows(own, fixtures);
        }
    }

    @Test
    void getJobInstancesTakesTheNewestOfTheJobsRowsWithOneInstanceId() throws Exception {
        String fileName = "it-gap-newest-" + System.nanoTime() + ".json";
        BatchData own = runEmptyDomainImport(fileName);
        List<Integer> fixtures = new java.util.ArrayList<>();
        try {
            // Two more rows of the same job with the same instance id: an older one and one dated after the job's own,
            // whose job number no run has, so that no folder is ever made or removed for it
            BatchData older = newJob("domain-import", 900_001L, own.getInstanceId());
            older.setDataFileName("it-gap-same-older.json");
            fixtures.add(commit(older));
            BatchData newer = newJob("domain-import", 900_002L, own.getInstanceId());
            newer.setCreated(new Date(own.getCreated().getTime() + 60_000L));
            newer.setDataFileName("it-gap-same-newer.json");
            fixtures.add(commit(newer));

            BatchInstanceVo instance = listedInstance(own.getInstanceId());

            assertEquals("it-gap-same-newer.json", instance.getFileName(), "the newest row by created wins");
            assertEquals(900_002L, instance.getJobNo());
        } finally {
            removeRows(own, fixtures);
        }
    }

    /** Runs an empty domain import with the data file name and answers its committed row */
    private BatchData runEmptyDomainImport(String fileName) throws Exception {
        long execution = batchService.startBatchJobWithJsonData("domain-import", List.of(), fileName, null);
        assertEquals(BatchStatus.COMPLETED, BatchJobs.awaitCompletion(jobOperator, execution));
        long instanceId = jobOperator.getJobInstance(execution).getInstanceId();
        return Tx.requiringNew().call(() -> em.createQuery(
                        "select b from BatchData b where b.instanceId = :i and b.dataFileName = :f", BatchData.class)
                .setParameter("i", instanceId).setParameter("f", fileName).getSingleResult());
    }

    /** Commits the row and answers its id */
    private Integer commit(BatchData job) {
        return Tx.requiringNew().call(() -> batchService.saveBatchJob(job).getId());
    }

    /** The domain import's instance with the id, as the Batch Jobs page lists it */
    private BatchInstanceVo listedInstance(long instanceId) throws Exception {
        return batchService.getJobInstances("domain-import", 0, 100).getData().stream()
                .filter(i -> i.getInstanceId() == instanceId)
                .findFirst()
                .orElseThrow();
    }

    /** Removes the job's own row with its folder, and the committed rows, which have no folder */
    private void removeRows(BatchData own, List<Integer> fixtures) {
        Tx.requiringNew().run(() -> {
            BatchData found = em.find(BatchData.class, own.getId());
            if (found != null) {
                FileUtils.deleteQuietly(batchService.computeBatchJobPath(found.computeBatchJobFolderPath()).toFile());
                em.remove(found);
            }
            for (Integer id : fixtures) {
                BatchData fixture = em.find(BatchData.class, id);
                if (fixture != null) {
                    em.remove(fixture);
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------- //
    // getBatchJobLogFile — "Invalid batch log file" error path for a missing log name
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getBatchJobLogFileThrowsForNonExistentLogNameOnKnownInstance() throws Exception {
        // The instance exists (so the "Invalid batch instance ID" guard passes) but the requested log file
        // does not exist on disk, so the service must reject it with "Invalid batch log file".
        BatchData job = newJob("IT-BATCH-GAP-nolog", 1L, 200_030L);
        batchService.saveBatchJob(job);
        em.flush();

        Path folder = batchService.computeBatchJobPath(job.computeBatchJobFolderPath());
        // Create the execution folder (but not the log file) to prove the failure is about the file, not the
        // instance lookup.
        Files.createDirectories(folder);
        try {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> batchService.getBatchJobLogFile(200_030L, "DoesNotExistLog.txt", null));
            assertTrue(ex.getMessage().contains("Invalid batch log file"),
                    "a missing log file on a known instance must report 'Invalid batch log file'");
        } finally {
            Files.deleteIfExists(folder);
        }
    }
}
