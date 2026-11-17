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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;
import org.niord.core.repo.RepositoryService;
import org.niord.test.ITTest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link BatchSetService#extractAndExecuteBatchSetArchive(InputStream, StringBuilder)}:
 * the unzip-then-read-manifest logic that turns an uploaded batch-set zip archive into scheduled batch jobs.
 *
 * <p>This requires the {@code RepositoryService} (it unzips into a temp repo folder), so it runs as a
 * Quarkus integration test. Only the manifest-parsing paths are asserted: an <em>empty</em> manifest (which
 * schedules nothing), a <em>missing</em> manifest, an <em>invalid</em> manifest, and the allow-list
 * ({@link BatchSetService#BATCH_SET_JOBS}): a manifest naming another job is refused whole and schedules nothing, and
 * one of allowed jobs is scheduled. Archives that contain real batch-set items are deliberately not executed here,
 * because each item is dispatched on a {@link java.util.Timer} and would launch a real asynchronous JBeret job — the
 * allowed manifest's item is scheduled ten minutes out and names a file the archive does not hold, so it starts no
 * job when it fires, and only its scheduling is asserted; see
 * docs/untestable-code.md.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchSetServiceITTest extends ITTest {

    @Autowired
    BatchSetService batchSetService;

    @Autowired
    RepositoryService repositoryService;

    @Autowired
    BatchService batchService;

    /** The work folder a batch-set archive is unpacked into, below which an entry must land */
    private Path batchSetRoot() {
        return batchService.getBatchJobRoot().resolve("batch-set").resolve("execution");
    }

    /** Builds an in-memory zip archive with the given entries (name -> content). */
    private InputStream zip(java.util.Map<String, String> entries) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (java.util.Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return new ByteArrayInputStream(bos.toByteArray());
    }

    @Test
    void emptyManifestSchedulesNoBatchJobs() throws Exception {
        // A valid but empty batch-set.json ("[]") parses successfully and schedules nothing, so the
        // status log accumulates no "Scheduling batch job ..." lines.
        InputStream archive = zip(java.util.Map.of("batch-set.json", "[]"));
        StringBuilder txt = new StringBuilder();

        batchSetService.extractAndExecuteBatchSetArchive(archive, txt);

        assertFalse(txt.toString().contains("Scheduling batch job"),
                "an empty manifest must not schedule any batch jobs");
    }

    @Test
    void missingManifestThrowsDescriptiveException() throws Exception {
        // The archive contains some other file but no batch-set.json.
        InputStream archive = zip(java.util.Map.of("readme.txt", "not a manifest"));
        StringBuilder txt = new StringBuilder();

        Exception ex = assertThrows(Exception.class,
                () -> batchSetService.extractAndExecuteBatchSetArchive(archive, txt));
        assertEquals("Batch set did not contain a batch-set.json file", ex.getMessage());
    }

    @Test
    void invalidManifestJsonThrows() throws Exception {
        // batch-set.json present but not valid JSON -> JSON parsing fails.
        InputStream archive = zip(java.util.Map.of("batch-set.json", "this is not json"));
        StringBuilder txt = new StringBuilder();

        assertThrows(Exception.class,
                () -> batchSetService.extractAndExecuteBatchSetArchive(archive, txt));
    }

    // ------------------------------------------------------------------------------------------- //
    // The allow-list: a batch set names only the jobs of BATCH_SET_JOBS
    //
    // The script-resource, dictionary and settings imports run from their own pages, with the preview, and
    // script-executor runs code; a batch set naming any of them is refused whole, before anything is scheduled.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aBatchSetNamingAJobOutsideTheAllowListIsRefusedWholeAndSchedulesNothing() throws Exception {
        for (String job : java.util.List.of("script-resource-import", "dictionary-import", "settings-import",
                "script-executor")) {
            String manifest = "[{\"jobName\": \"domain-import\", \"fileName\": \"domains.json\", \"delay\": 600000},"
                    + " {\"jobName\": \"" + job + "\", \"fileName\": \"data.json\", \"delay\": 600000}]";
            StringBuilder txt = new StringBuilder();

            Exception e = assertThrows(IllegalArgumentException.class, () -> batchSetService
                    .extractAndExecuteBatchSetArchive(zip(java.util.Map.of("batch-set.json", manifest,
                            "domains.json", "[]", "data.json", "[]")), txt));

            assertTrue(e.getMessage().contains("'" + job + "'"), "the refusal names the job: " + e.getMessage());
            assertTrue(e.getMessage().contains("nothing was scheduled"), e.getMessage());
            assertFalse(txt.toString().contains("Scheduling batch job"), "nothing is scheduled for " + job);
        }
    }

    @Test
    void aBatchSetWithANamelessItemIsRefusedWholeAndSchedulesNothing() throws Exception {
        for (String item : java.util.List.of("{\"fileName\": \"x.json\"}", "null")) {
            String manifest = "[{\"jobName\": \"domain-import\", \"fileName\": \"domains.json\", \"delay\": 600000},"
                    + " " + item + "]";
            StringBuilder txt = new StringBuilder();

            Exception e = assertThrows(IllegalArgumentException.class, () -> batchSetService
                    .extractAndExecuteBatchSetArchive(zip(java.util.Map.of("batch-set.json", manifest,
                            "domains.json", "[]", "x.json", "[]")), txt));

            assertTrue(e.getMessage() != null && e.getMessage().contains("names a job without a name"),
                    "the refusal says why: " + e.getMessage());
            assertTrue(e.getMessage().contains("nothing was scheduled"), e.getMessage());
            assertFalse(txt.toString().contains("Scheduling batch job"), "nothing is scheduled for " + item);
        }
    }

    @Test
    void aBatchSetWhoseItemCarriesTheBootstrapRunnersTokenIsRefusedWholeAndSchedulesNothing() throws Exception {
        // The token is the bootstrap runner's own (docs/code-data-config-plan.md §7.2): a batch set may not copy it
        // into a job, as an archive item may not (BootstrapArchive)
        String manifest = "[{\"jobName\": \"domain-import\", \"fileName\": \"domains.json\", \"delay\": 600000,"
                + " \"properties\": {\"bootstrapRun\": \"x/0\"}}]";
        StringBuilder txt = new StringBuilder();

        Exception e = assertThrows(IllegalArgumentException.class, () -> batchSetService
                .extractAndExecuteBatchSetArchive(zip(java.util.Map.of("batch-set.json", manifest,
                        "domains.json", "[]")), txt));

        assertTrue(e.getMessage().contains("'" + org.niord.core.bootstrap.BootstrapRun.PROPERTY + "'")
                && e.getMessage().contains("'domain-import'"), "the refusal names the property and the job: "
                + e.getMessage());
        assertTrue(e.getMessage().contains("nothing was scheduled"), e.getMessage());
        assertFalse(txt.toString().contains("Scheduling batch job"), "nothing is scheduled");
    }

    @Test
    void aBatchSetOfAllowedJobsIsScheduled() throws Exception {
        // The item names a file the archive does not hold, so when its timer fires, a few hundred ms later and
        // within this test, it logs "did not exist" and starts no job
        String manifest = "[{\"jobName\": \"domain-import\", \"fileName\": \"absent.json\", \"delay\": 300}]";
        StringBuilder txt = new StringBuilder();
        Logger logger = (Logger) LoggerFactory.getLogger(BatchSetService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            batchSetService.extractAndExecuteBatchSetArchive(
                    zip(java.util.Map.of("batch-set.json", manifest)), txt);

            await().atMost(Duration.ofSeconds(30)).until(() -> java.util.List.copyOf(appender.list).stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("domain-import did not exist")
                            && e.getFormattedMessage().contains("absent.json")));
        } finally {
            logger.detachAppender(appender);
        }

        assertTrue(txt.toString().contains("Scheduling batch job domain-import"), txt.toString());
        assertTrue(BatchSetService.BATCH_SET_JOBS.contains("domain-import"));
        for (String job : java.util.List.of("script-resource-import", "dictionary-import", "settings-import",
                "script-executor")) {
            assertFalse(BatchSetService.BATCH_SET_JOBS.contains(job), job + " is not in the allow-list");
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Every entry is extracted inside the temporary folder
    //
    // The archive is extracted into a fresh folder below the repository's temp root; an entry that would
    // land anywhere else fails the extraction, before the manifest is read.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anEntryWithParentSegmentsFailsTheExtractionAndWritesNothingOutside() throws Exception {
        String escapedName = "IT-escaped-" + System.nanoTime() + ".txt";
        // One level up from batch-set/execution/<uuid> is the execution folder itself
        Path escaped = batchSetRoot().resolve(escapedName);
        java.util.Map<String, String> entries = new java.util.LinkedHashMap<>();
        entries.put("batch-set.json", "[]");
        entries.put("../" + escapedName, "outside");
        StringBuilder txt = new StringBuilder();
        try {
            IOException e = assertThrows(IOException.class,
                    () -> batchSetService.extractAndExecuteBatchSetArchive(zip(entries), txt));

            assertTrue(e.getMessage().contains(escapedName), "the refusal names the entry");
            assertFalse(Files.exists(escaped), "nothing may be written outside the extraction folder");
            assertFalse(txt.toString().contains("Scheduling batch job"), "nothing is scheduled");
        } finally {
            Files.deleteIfExists(escaped);
        }
    }

    @Test
    void anAbsoluteEntryNameFailsTheExtractionAndWritesNothingOutside() throws Exception {
        // Absolute in the form of the platform the test runs on ("/..." or "C:\...")
        Path outside = batchSetRoot().resolve("IT-absolute-" + System.nanoTime() + ".txt")
                .toAbsolutePath();
        StringBuilder txt = new StringBuilder();
        try {
            assertThrows(IOException.class, () -> batchSetService.extractAndExecuteBatchSetArchive(
                    zip(java.util.Map.of(outside.toString(), "outside")), txt));

            assertFalse(Files.exists(outside), "an absolute entry must not be written where it points");
        } finally {
            Files.deleteIfExists(outside);
        }
    }
}
