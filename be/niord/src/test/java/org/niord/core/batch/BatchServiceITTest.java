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

import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.niord.core.web.NiordWebException;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.niord.core.util.JsonUtils;
import org.niord.test.ITTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link BatchService} persistence and repository behaviour, run against the seeded
 * MySQL container of the {@link ITTest} infrastructure.
 *
 * <p>Each persistence test is wrapped in {@link TestTransaction} so writes roll back and the shared seed
 * database stays pristine. {@link BatchData} rows are created directly (no {@code start*BatchJob*} call) so
 * that the tests never launch a real asynchronous JBeret job — see docs/untestable-code.md for why launching
 * jobs is out of scope. Batch jobs use a unique {@code IT-BATCH-} job-name prefix that never occurs in the
 * seed data, keeping {@link BatchService#getJobNames()} assertions isolated.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchServiceITTest extends ITTest {

    @Autowired
    BatchService batchService;

    @PersistenceContext
    EntityManager em;

    /** Builds a detached BatchData with the mandatory non-null fields populated. */
    private BatchData newJob(String jobName, long jobNo, long instanceId) {
        BatchData job = new BatchData();
        job.setJobName(jobName);
        job.setJobNo(jobNo);
        job.setInstanceId(instanceId);
        return job;
    }

    private static Date date(int year, int monthZeroBased, int day) {
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(year, monthZeroBased, day, 12, 0, 0);
        return cal.getTime();
    }

    // ------------------------------------------------------------------------------------------- //
    // saveBatchJob
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void saveBatchJobPersistsRowWithGeneratedIdAndDefaultedCreated() {
        BatchData saved = batchService.saveBatchJob(newJob("IT-BATCH-save", 1L, 100_001L));
        Integer id = saved.getId();
        assertNotNull(id, "saveBatchJob must assign a generated id");
        assertNotNull(saved.getCreated(), "@PrePersist must default the created date");

        em.clear();

        BatchData reloaded = em.find(BatchData.class, id);
        assertNotNull(reloaded, "the row must exist in the database");
        assertEquals("IT-BATCH-save", reloaded.getJobName());
        assertEquals(Long.valueOf(1L), reloaded.getJobNo());
        assertEquals(Long.valueOf(100_001L), reloaded.getInstanceId());
    }

    @Test
    @Transactional
    void saveBatchJobRejectsNullJob() {
        assertThrows(NullPointerException.class, () -> batchService.saveBatchJob(null));
    }

    @Test
    @Transactional
    void saveBatchJobRejectsNullInstanceId() {
        BatchData job = new BatchData();
        job.setJobName("IT-BATCH-noinstance");
        job.setJobNo(1L);
        // instanceId left null
        assertThrows(NullPointerException.class, () -> batchService.saveBatchJob(job));
    }

    @Test
    @Transactional
    void saveBatchJobPersistsPropertiesMapRoundTrip() {
        BatchData job = newJob("IT-BATCH-props", 1L, 100_010L);
        job.getProperties().put("alpha", "one");
        job.getProperties().put("beta", "two");

        Integer id = batchService.saveBatchJob(job).getId();
        em.clear();

        Map<String, Object> reloaded = em.find(BatchData.class, id).getProperties();
        assertEquals("one", reloaded.get("alpha"));
        assertEquals("two", reloaded.get("beta"));
    }

    // ------------------------------------------------------------------------------------------- //
    // findByInstanceId / findByInstanceIds
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void findByInstanceIdReturnsMostRecentlyCreatedForInstance() {
        // Two BatchData rows share the same instanceId; findByInstanceId returns the one with the
        // latest created date.
        BatchData older = newJob("IT-BATCH-dup", 1L, 100_020L);
        older.setCreated(date(2020, Calendar.JANUARY, 1));
        BatchData newer = newJob("IT-BATCH-dup", 2L, 100_020L);
        newer.setCreated(date(2021, Calendar.JANUARY, 1));
        batchService.saveBatchJob(older);
        batchService.saveBatchJob(newer);
        em.flush();

        BatchData found = batchService.findByInstanceId(100_020L);
        assertNotNull(found);
        assertEquals(Long.valueOf(2L), found.getJobNo(), "the most recently created row must win");
    }

    @Test
    @Transactional
    void findByInstanceIdReturnsNullForUnknownInstance() {
        assertNull(batchService.findByInstanceId(999_999_999L));
    }

    @Test
    @Transactional
    void findByInstanceIdsReturnsAllMatchingRows() {
        batchService.saveBatchJob(newJob("IT-BATCH-multi", 1L, 100_030L));
        batchService.saveBatchJob(newJob("IT-BATCH-multi", 2L, 100_031L));
        em.flush();

        List<BatchData> result = batchService.findByInstanceIds(Set.of(100_030L, 100_031L));
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(b -> b.getInstanceId() == 100_030L));
        assertTrue(result.stream().anyMatch(b -> b.getInstanceId() == 100_031L));
    }

    @Test
    @Transactional
    void findByInstanceIdsReturnsEmptyForUnknownInstances() {
        assertTrue(batchService.findByInstanceIds(Set.of(888_000_001L, 888_000_002L)).isEmpty());
    }

    // ------------------------------------------------------------------------------------------- //
    // updateBatchJobProgress
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateBatchJobProgressPersistsProgress() {
        Integer id = batchService.saveBatchJob(newJob("IT-BATCH-progress", 1L, 100_040L)).getId();
        em.flush();

        batchService.updateBatchJobProgress(100_040L, 73);
        em.flush();
        em.clear();

        assertEquals(Integer.valueOf(73), em.find(BatchData.class, id).getProgress());
    }

    @Test
    @Transactional
    void updateBatchJobProgressForUnknownInstanceIsANoOp() {
        // No row exists for this instance; the call must simply do nothing (no exception).
        batchService.updateBatchJobProgress(777_000_001L, 50);
        assertNull(batchService.findByInstanceId(777_000_001L));
    }

    // ------------------------------------------------------------------------------------------- //
    // getJobNames
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getJobNamesReturnsDistinctNamesIncludingNewlySaved() {
        batchService.saveBatchJob(newJob("IT-BATCH-names-z", 1L, 100_050L));
        batchService.saveBatchJob(newJob("IT-BATCH-names-z", 2L, 100_051L)); // duplicate name
        batchService.saveBatchJob(newJob("IT-BATCH-names-a", 1L, 100_052L));
        em.flush();

        List<String> names = batchService.getJobNames();
        long zCount = names.stream().filter("IT-BATCH-names-z"::equals).count();
        assertEquals(1L, zCount, "duplicate job names must be collapsed (DISTINCT)");
        assertTrue(names.contains("IT-BATCH-names-a"));

        // The query orders by jobName, so the 'a' name precedes the 'z' name.
        assertTrue(names.indexOf("IT-BATCH-names-a") < names.indexOf("IT-BATCH-names-z"),
                "job names must be returned in ascending order");
    }

    // ------------------------------------------------------------------------------------------- //
    // computeBatchJobPath — path-traversal guard
    // ------------------------------------------------------------------------------------------- //

    @Test
    void computeBatchJobPathResolvesLocalPathUnderRoot() {
        Path local = Paths.get("IT-BATCH", "execution", "2020", "1", "1");
        Path resolved = batchService.computeBatchJobPath(local);
        assertTrue(resolved.startsWith(batchService.getBatchJobRoot().normalize()),
                "the resolved path must live under the batch job root");
        assertTrue(resolved.endsWith(local));
    }

    @Test
    void computeBatchJobPathRejectsPathTraversal() {
        // A path that escapes the batch job root via "../" must be rejected.
        Path escape = Paths.get("..", "..", "..", "etc", "passwd");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> batchService.computeBatchJobPath(escape));
        assertTrue(ex.getMessage().contains("Invalid path"),
                "the guard must report an invalid path");
    }

    // ------------------------------------------------------------------------------------------- //
    // startBatchJobWithDataFile — the data file stays in the job's own execution folder
    //
    // The data file name is usually an uploaded file's name. Below the batch-job root is not enough: the
    // root holds every other job's folders, so the name must resolve to a file directly inside the job's
    // own execution folder.
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void startBatchJobWithDataFileRefusesANameOutsideTheJobFolderBeforeWriting() throws Exception {
        String otherJob = "IT-BATCH-escape-" + System.nanoTime();
        Path target = batchService.getBatchJobRoot().resolve(otherJob).resolve("in").resolve("x.js");
        try {
            // [root]/IT-BATCH-upload/execution/[year]/[month]/[jobNo] is five levels below the root
            String name = "../../../../../" + otherJob + "/in/x.js";

            assertThrows(IllegalArgumentException.class, () -> batchService.startBatchJobWithDataFile(
                    "IT-BATCH-upload",
                    new java.io.ByteArrayInputStream("1 + 1;".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    name,
                    new HashMap<>()));

            assertFalse(Files.exists(target), "nothing may be written into another job's folder");
        } finally {
            FileUtils.deleteQuietly(batchService.getBatchJobRoot().resolve(otherJob).toFile());
        }
    }

    @Test
    @Transactional
    void getBatchJobDataFileIgnoresAStoredNameOutsideTheJobFolder() throws Exception {
        // A row whose data file name climbs out of its folder (e.g. stored before the upload names were
        // reduced) does not resolve to that file for the download or the readers.
        BatchData job = newJob("IT-BATCH-stored-escape", 1L, 100_090L);
        job.setCreated(date(2020, Calendar.MARCH, 15));
        batchService.saveBatchJob(job);
        Path jobFolder = batchService.computeBatchJobPath(job.computeBatchJobFolderPath());
        Path outside = jobFolder.getParent().resolve("outside.json");
        job.setDataFileName("../outside.json");
        em.flush();
        try {
            Files.createDirectories(outside.getParent());
            Files.writeString(outside, "{}");

            assertNull(batchService.getBatchJobDataFile(100_090L),
                    "a data file name that leaves the job folder must not resolve");
        } finally {
            FileUtils.deleteQuietly(batchService.getBatchJobRoot().resolve("IT-BATCH-stored-escape").toFile());
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // getBatchJobDataFile / readBatchJobJsonDataFile
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getBatchJobDataFileReturnsNullWhenNoDataFileNameIsSet() {
        batchService.saveBatchJob(newJob("IT-BATCH-nofile", 1L, 100_060L));
        em.flush();
        assertNull(batchService.getBatchJobDataFile(100_060L),
                "a job without a data file name has no data file");
    }

    @Test
    @Transactional
    void getBatchJobDataFileReturnsNullForUnknownInstance() {
        assertNull(batchService.getBatchJobDataFile(666_000_001L));
    }

    @Test
    @Transactional
    void readBatchJobJsonDataFileRoundTripsWrittenJson() throws Exception {
        // Persist a job with a data file name, write a JSON file at the computed repository path, then
        // read it back through the service. The file is written to the real batch-job repo on disk
        // (outside the rolled-back transaction), so it is removed in a finally block.
        BatchData job = newJob("IT-BATCH-json", 1L, 100_070L);
        job.setDataFileName("data.json");
        job.setCreated(date(2020, Calendar.MARCH, 15));
        batchService.saveBatchJob(job);
        em.flush();

        Path path = batchService.computeBatchJobPath(job.computeDataFilePath());
        Files.createDirectories(path.getParent());
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "value");
        payload.put("count", "5");
        try {
            JsonUtils.writeJson(payload, path);

            // The data file is now discoverable for the instance...
            assertEquals(path, batchService.getBatchJobDataFile(100_070L));

            // ...and reads back as the originally written content.
            Map<String, Object> read = batchService.readBatchJobJsonDataFile(
                    100_070L, new TypeReference<Map<String, Object>>() {});
            assertEquals("value", read.get("name"));
            assertEquals("5", read.get("count"));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    @Transactional
    void readBatchJobJsonDataFileReturnsNullWhenFileMissingOnDisk() throws Exception {
        // The job declares a data file name, but no file was written, so getBatchJobDataFile yields null
        // and the read returns null rather than throwing.
        BatchData job = newJob("IT-BATCH-json-missing", 1L, 100_071L);
        job.setDataFileName("absent.json");
        batchService.saveBatchJob(job);
        em.flush();

        assertNull(batchService.readBatchJobJsonDataFile(100_071L, Map.class));
    }

    // ------------------------------------------------------------------------------------------- //
    // getBatchJobLogFiles / getBatchJobLogFile — unknown-instance error paths
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getBatchJobLogFilesThrowsForUnknownInstance() {
        NiordWebException ex = assertThrows(NiordWebException.class,
                () -> batchService.getBatchJobLogFiles(555_000_001L));
        assertEquals(404, ex.getStatusCode().value());
        assertTrue(ex.getReason().contains("Invalid batch instance ID"));
    }

    @Test
    @Transactional
    void getBatchJobLogFileThrowsForUnknownInstance() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> batchService.getBatchJobLogFile(555_000_002L, "SomeLog.txt", null));
        assertTrue(ex.getMessage().contains("Invalid batch instance ID"));
    }

    @Test
    @Transactional
    void getBatchJobLogFilesReturnsEmptyWhenFolderDoesNotExist() throws Exception {
        // The instance exists but its execution folder was never created on disk, so listFiles returns
        // null and the service yields an empty list.
        batchService.saveBatchJob(newJob("IT-BATCH-nologs", 1L, 100_080L));
        em.flush();
        assertTrue(batchService.getBatchJobLogFiles(100_080L).isEmpty());
    }

    // ------------------------------------------------------------------------------------------- //
    // cleanUpExpiredBatchJobFiles - the @Scheduled(cron="30 42 */1 * * ?") cleanup, invoked directly
    //
    // The method walks every "[jobName]/execution" folder under the batch-job root, deletes files last
    // modified before (now - batchFileExpiryDays) and prunes the directories that this leaves empty.
    // batchFileExpiryDays defaults to 10 (seed setting). The package-private method is reachable from this
    // same-package test; only the cron firing *time* is not asserted (the Quarkus scheduler is not driven).
    // These are real file-system side effects (not rolled back), so the test removes its own batch-job
    // sub-tree in a finally block. See docs/untestable-code.md / docs/framework-coupled-tests.md.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void cleanUpExpiredBatchJobFilesDeletesOldFilesPrunesEmptyDirsAndKeepsRecentOnes() throws Exception {
        Path root = batchService.getBatchJobRoot();
        // An "IT-BATCH-" job-name prefix that never occurs in the seed keeps this isolated.
        String jobName = "IT-BATCH-cleanup-" + System.nanoTime();
        Path jobDir = root.resolve(jobName);

        // Two execution branches: one holding an expired file, one holding a fresh file.
        Path oldBranch = jobDir.resolve("execution").resolve("2020").resolve("1").resolve("1");
        Path recentBranch = jobDir.resolve("execution").resolve("2020").resolve("1").resolve("2");
        try {
            Files.createDirectories(oldBranch);
            Files.createDirectories(recentBranch);

            Path oldFile = oldBranch.resolve("old.log");
            Files.writeString(oldFile, "old");
            // 400 days back: far beyond the 10-day default, robust even against a larger expiry setting.
            assertTrue(oldFile.toFile().setLastModified(
                            System.currentTimeMillis() - 400L * 24 * 60 * 60 * 1000L),
                    "precondition: must be able to back-date the expired batch file");

            Path recentFile = recentBranch.resolve("recent.log");
            Files.writeString(recentFile, "recent");

            batchService.cleanUpExpiredBatchJobFiles();

            assertFalse(Files.exists(oldFile),
                    "an expired batch-job file must be deleted by cleanUpExpiredBatchJobFiles");
            assertFalse(Files.exists(oldBranch),
                    "the directory left empty after deleting the expired file must be pruned");
            assertTrue(Files.exists(recentFile),
                    "a batch-job file newer than the expiry threshold must be kept");
            assertTrue(Files.isDirectory(recentBranch),
                    "a directory that still holds a recent file must be kept");
        } finally {
            FileUtils.deleteQuietly(jobDir.toFile());
        }
    }
}
