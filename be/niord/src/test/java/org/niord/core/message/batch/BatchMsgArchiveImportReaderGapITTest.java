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
package org.niord.core.message.batch;

import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.niord.core.batch.BatchData;
import org.niord.core.message.batch.BatchMsgArchiveImportReader.ExtractedArchiveMessageVo;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.model.message.MessageSeriesVo;
import org.niord.model.search.PagedSearchResultVo;
import org.niord.test.ITTest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the JSR-352 batch message-archive import <em>reader</em>
 * ({@link BatchMsgArchiveImportReader}), exercised against the seeded MySQL container of the
 * {@link ITTest} infrastructure.
 *
 * <p>{@code BatchMsgArchiveImportReader} is a {@code @Dependent}/{@code @Named} JBeret chunk reader. In
 * production the batch runtime injects the {@code JobContext}, the {@code BatchService}, the
 * {@code RepositoryService} and the per-instance {@link BatchData} ({@code job}), then drives
 * {@code open()/readItem()/checkpointInfo()}. Its {@code open()} resolves the uploaded zip via
 * {@code batchService.getBatchJobDataFile(jobContext.getInstanceId())}, unpacks it under a
 * {@code repositoryService.getNewTempDir()} path, reads the root {@code messages.json}
 * ({@link PagedSearchResultVo}{@code <}{@link SystemMessageVo}{@code >}) and wraps every entry as an
 * {@link ExtractedArchiveMessageVo} whose {@code editRepoPath} is
 * {@code <tempArchiveRepoPath> + "/" + message.repoPath}.
 *
 * <p>To exercise that observable behaviour without the batch runtime — following the same
 * subclass-and-wire pattern already used by {@link BatchMessageImportReaderWriterITTest} and
 * {@link BatchMsgArchiveImportProcessorITTest} — this suite uses a small {@link TestableArchiveReader}
 * subclass declared in this same package that:
 * <ul>
 *   <li>overrides {@code getLog()} to return a parent-less no-op logger, so the batch-folder
 *       {@code FileHandler} (which targets a non-existent batch job folder) is never opened;</li>
 *   <li>overrides {@code updateProgress(Integer)} to record the progress callbacks instead of routing
 *       them through the (unwired) {@code BatchService};</li>
 *   <li>overrides the protected {@code extractMessageArchive()} hook to run the production body,
 *       {@code readArchive(Path, String, RepoStore)} — the zip unpack ({@code extractEntries}), the
 *       {@code messages.json} guard, the Jackson {@link PagedSearchResultVo}{@code <}{@link SystemMessageVo}{@code >}
 *       parse and the {@code editRepoPath = tempRepoDir + "/" + repoPath} mapping — on a zip and a
 *       {@code tempRepoDir} the test fully controls (a {@link TempDir}), bypassing the
 *       {@code JobContext}/{@code BatchService}/{@code RepositoryService} wiring that {@code open()} would
 *       otherwise require.</li>
 * </ul>
 *
 * <p>Behaviour covered: the {@code open()} {@code seriesId} guard (missing and blank), the
 * archive→{@link ExtractedArchiveMessageVo} extraction (ordering, carried {@code number}/{@code repoPath}
 * and the {@code editRepoPath} composition), the missing-{@code messages.json} and malformed-JSON failure
 * paths, the {@code open()}→{@code readItem()} progression with null-on-exhaustion, the
 * {@code checkpointInfo()} cursor (which must not over-run), and resuming from a previous checkpoint.
 *
 * <p>The reader does not write to the database, so no {@code @Transactional} cleanup is strictly
 * required; the suite still annotates each test with {@link TestTransaction} for consistency with the
 * sibling reader/processor ITs and to guarantee independence. All archive I/O happens under a per-test
 * {@link TempDir} that JUnit removes afterwards.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchMsgArchiveImportReaderGapITTest extends ITTest {

    @org.springframework.beans.factory.annotation.Autowired
    org.niord.core.repo.RepoStore store;

    /** The temp folders of the store the readers extracted into, deleted after each test */
    private final List<String> stagedTempDirs = new ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void deleteExtractedRows() {
        stagedTempDirs.forEach(store::deleteFolder);
        stagedTempDirs.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // Reader scaffolding
    // ------------------------------------------------------------------------------------------- //

    /**
     * A {@link BatchMsgArchiveImportReader} that can be driven outside the JBeret batch runtime.
     *
     * <p>It unpacks a test-supplied zip into a test-supplied {@code tempRepoDir} using the very same
     * algorithm as the production reader, so {@code open()} never touches the
     * {@code JobContext}/{@code BatchService}/{@code RepositoryService}. Progress callbacks are recorded in
     * {@link #progressCalls} instead of being routed through the unwired {@code BatchService}, and a quiet
     * parent-less logger is used so no batch-job log file is created.
     */
    static class TestableArchiveReader extends BatchMsgArchiveImportReader {
        private final java.util.logging.Logger noop = java.util.logging.Logger.getLogger("it-reader-arch");
        { noop.setUseParentHandlers(false); }

        /** The zip archive to read (set by the test). */
        final Path archiveZip;
        /** The (relative) temp repo path that editRepoPath values are composed from. */
        final String tempRepoDir;
        /** The store the archive is unpacked into, as temp rows below tempRepoDir. */
        final org.niord.core.repo.RepoStore store;

        final List<Integer> progressCalls = new ArrayList<>();

        TestableArchiveReader(BatchData job, Path archiveZip, String tempRepoDir, org.niord.core.repo.RepoStore store) {
            this.job = job;
            this.archiveZip = archiveZip;
            this.tempRepoDir = tempRepoDir;
            this.store = store;
        }

        @Override protected java.util.logging.Logger getLog() { return noop; }
        @Override protected void updateProgress(Integer progress) { progressCalls.add(progress); }

        /** The production body, driven from a test-controlled zip and temp folder instead of the batch wiring */
        @Override
        protected List<ExtractedArchiveMessageVo> extractMessageArchive() throws Exception {
            return readArchive(archiveZip, tempRepoDir, store);
        }

    }

    // ------------------------------------------------------------------------------------------- //
    // Fixture helpers
    // ------------------------------------------------------------------------------------------- //

    /** Builds a {@link BatchData} whose properties carry the given {@code seriesId} (or none when null). */
    private BatchData jobWithSeriesId(String seriesId) {
        BatchData job = new BatchData();
        if (seriesId != null) {
            job.getProperties().put("seriesId", seriesId);
        }
        return job;
    }

    /**
     * Produces the JSON for a {@link PagedSearchResultVo}{@code <}{@link SystemMessageVo}{@code >} carrying
     * the supplied messages, serialised exactly as the export side would.
     */
    private String messagesJson(List<SystemMessageVo> data) throws Exception {
        PagedSearchResultVo<SystemMessageVo> result = new PagedSearchResultVo<>();
        result.setData(data);
        result.setTotal(data.size());
        result.setSize(data.size());
        return new ObjectMapper().writeValueAsString(result);
    }

    /** Builds a {@link SystemMessageVo} carrying a number, a repoPath and a seriesId (the export shape). */
    private SystemMessageVo archiveMessage(int number, String repoPath, String seriesId) {
        SystemMessageVo vo = new SystemMessageVo();
        vo.setNumber(number);
        vo.setRepoPath(repoPath);
        MessageSeriesVo series = new MessageSeriesVo();
        series.setSeriesId(seriesId);
        vo.setMessageSeries(series);
        return vo;
    }

    /** Writes a zip containing the given raw {@code messages.json} bytes at the archive root. */
    private Path zipWithMessagesJson(Path dir, String jsonContent) throws IOException {
        return zipWith(dir, "messages.json", jsonContent.getBytes(StandardCharsets.UTF_8));
    }

    /** Writes a zip containing a single named entry with the given content (used for the no-json case). */
    private Path zipWith(Path dir, String entryName, byte[] content) throws IOException {
        Path zip = dir.resolve("archive-" + UUID.randomUUID() + ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry(entryName));
            try (InputStream in = new ByteArrayInputStream(content)) {
                in.transferTo(zos);
            }
            zos.closeEntry();
        }
        return zip;
    }

    /** Builds a wired reader over the given zip, allocating a unique temp folder of the store for it. */
    private TestableArchiveReader readerFor(BatchData job, Path zip, Path root) throws IOException {
        String tempRepoDir = "temp/" + UUID.randomUUID();
        stagedTempDirs.add(tempRepoDir);
        return new TestableArchiveReader(job, zip, tempRepoDir, store);
    }

    // ------------------------------------------------------------------------------------------- //
    // open() - seriesId guard
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void openThrowsWhenSeriesIdMissing(@TempDir Path tmp) throws Exception {
        Path zip = zipWithMessagesJson(tmp,
                messagesJson(List.of(archiveMessage(1, "messages/aa/01", "dma-nm"))));
        TestableArchiveReader reader = readerFor(jobWithSeriesId(null), zip, tmp);

        Exception ex = assertThrows(Exception.class, () -> reader.open(null));
        assertEquals("Missing seriesId batch property", ex.getMessage(),
                "open() must reject a job with no 'seriesId' property");
    }

    @Test
    @Transactional
    void openThrowsWhenSeriesIdBlank(@TempDir Path tmp) throws Exception {
        BatchData job = new BatchData();
        job.getProperties().put("seriesId", "   "); // blank, not just missing
        assertTrue(StringUtils.isBlank((String) job.getProperties().get("seriesId")),
                "precondition: the seriesId property is blank");

        Path zip = zipWithMessagesJson(tmp,
                messagesJson(List.of(archiveMessage(1, "messages/aa/01", "dma-nm"))));
        TestableArchiveReader reader = readerFor(job, zip, tmp);

        Exception ex = assertThrows(Exception.class, () -> reader.open(null));
        assertEquals("Missing seriesId batch property", ex.getMessage(),
                "open() must reject a blank 'seriesId' property as well as a missing one");
    }

    // ------------------------------------------------------------------------------------------- //
    // extractMessageArchive() - happy path
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void extractsArchiveMessagesInFileOrderWithComposedEditRepoPath(@TempDir Path tmp) throws Exception {
        // A valid messages.json with two entries, in a deliberate (non-sorted) order.
        SystemMessageVo first = archiveMessage(1276, "messages/d6/3e/4ce1-first", "dma-nm");
        SystemMessageVo second = archiveMessage(880, "messages/aa/bb/cc-second", "dma-nw");
        Path zip = zipWithMessagesJson(tmp, messagesJson(List.of(first, second)));

        TestableArchiveReader reader = readerFor(jobWithSeriesId("dma-nm"), zip, tmp);
        reader.open(null);

        // open() resolved the archive into two ExtractedArchiveMessageVo, in file order.
        assertEquals(2, reader.messages.size(), "both archive entries must be extracted");

        ExtractedArchiveMessageVo e0 = reader.messages.get(0);
        ExtractedArchiveMessageVo e1 = reader.messages.get(1);

        // Numbers are carried through, in the original file order.
        assertEquals(Integer.valueOf(1276), e0.getMessage().getNumber(),
                "the first extracted message must carry the first entry's number");
        assertEquals(Integer.valueOf(880), e1.getMessage().getNumber(),
                "the second extracted message must carry the second entry's number");

        // repoPath is carried through unchanged.
        assertEquals("messages/d6/3e/4ce1-first", e0.getMessage().getRepoPath());
        assertEquals("messages/aa/bb/cc-second", e1.getMessage().getRepoPath());

        // editRepoPath == tempRepoDir + "/" + message.repoPath, for each message.
        assertEquals(reader.tempRepoDir + "/" + "messages/d6/3e/4ce1-first", e0.getEditRepoPath(),
                "editRepoPath must be the temp repo dir joined with the message repoPath");
        assertEquals(reader.tempRepoDir + "/" + "messages/aa/bb/cc-second", e1.getEditRepoPath(),
                "editRepoPath must be composed per-message from its own repoPath");
    }

    // ------------------------------------------------------------------------------------------- //
    // extractMessageArchive() - failure paths
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void openThrowsWhenArchiveHasNoMessagesJson(@TempDir Path tmp) throws Exception {
        // A zip that contains a file, but NOT messages.json at the root.
        Path zip = zipWith(tmp, "attachments/readme.txt", "no manifest here".getBytes(StandardCharsets.UTF_8));
        TestableArchiveReader reader = readerFor(jobWithSeriesId("dma-nm"), zip, tmp);

        Exception ex = assertThrows(Exception.class, () -> reader.open(null));
        assertEquals("No valid messages.json file found in the archive", ex.getMessage(),
                "an archive missing messages.json must fail with the dedicated message");
    }

    @Test
    @Transactional
    void openThrowsWhenMessagesJsonIsMalformed(@TempDir Path tmp) throws Exception {
        // messages.json exists but is not valid JSON for PagedSearchResultVo<SystemMessageVo>.
        Path zip = zipWithMessagesJson(tmp, "{ this is : not valid json ]");
        TestableArchiveReader reader = readerFor(jobWithSeriesId("dma-nm"), zip, tmp);

        Exception ex = assertThrows(Exception.class, () -> reader.open(null));
        assertEquals("Invalid messages.json file", ex.getMessage(),
                "a corrupt messages.json must fail with the 'Invalid messages.json file' message");
    }

    @Test
    @Transactional
    void openRefusesAnArchiveWhoseMessageNamesAFolderOutsideIt(@TempDir Path tmp) throws Exception {
        // The second message's folder climbs out of the archive folder into a sibling temp folder
        Path zip = zipWithMessagesJson(tmp, messagesJson(List.of(
                archiveMessage(1, "messages/aa/01", "dma-nm"),
                archiveMessage(2, "../other-archive/messages/aa/02", "dma-nm"))));
        TestableArchiveReader reader = readerFor(jobWithSeriesId("dma-nm"), zip, tmp);

        // FAILS on the pre-fix code (open() succeeded, and the edit folder pointed at the other archive).
        Exception ex = assertThrows(Exception.class, () -> reader.open(null));
        assertTrue(ex.getMessage().contains("outside the archive"), ex.getMessage());
        assertNull(reader.messages, "no message of a refused archive may reach the writer");
    }

    // ------------------------------------------------------------------------------------------- //
    // readItem() progression & exhaustion
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void readsEveryItemInOrderThenNull(@TempDir Path tmp) throws Exception {
        Path zip = zipWithMessagesJson(tmp, messagesJson(List.of(
                archiveMessage(1, "messages/aa/01", "dma-nm"),
                archiveMessage(2, "messages/aa/02", "dma-nm"))));
        TestableArchiveReader reader = readerFor(jobWithSeriesId("dma-nm"), zip, tmp);
        reader.open(null);

        Object item0 = reader.readItem();
        Object item1 = reader.readItem();

        // The two extracted messages are returned in order, by identity.
        assertSame(reader.messages.get(0), item0, "first readItem must return the first extracted message");
        assertSame(reader.messages.get(1), item1, "second readItem must return the second extracted message");

        // Once exhausted, readItem must signal end-of-stream with null, repeatably.
        assertNull(reader.readItem(), "readItem past the end must return null");
        assertNull(reader.readItem(), "readItem must keep returning null after exhaustion");
    }

    @Test
    @Transactional
    void emptyArchiveReturnsNullImmediately(@TempDir Path tmp) throws Exception {
        // A valid messages.json with an empty data array.
        Path zip = zipWithMessagesJson(tmp, messagesJson(List.of()));
        TestableArchiveReader reader = readerFor(jobWithSeriesId("dma-nm"), zip, tmp);
        reader.open(null);

        assertEquals(0, reader.messages.size(), "an empty data array yields no extracted messages");
        assertNull(reader.readItem(), "an empty archive must yield null on the very first read");
    }

    // ------------------------------------------------------------------------------------------- //
    // checkpointInfo round-trip
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void checkpointInfoTracksReadCursorWithoutOverRunning(@TempDir Path tmp) throws Exception {
        Path zip = zipWithMessagesJson(tmp, messagesJson(List.of(
                archiveMessage(1, "messages/aa/01", "dma-nm"),
                archiveMessage(2, "messages/aa/02", "dma-nm"))));
        TestableArchiveReader reader = readerFor(jobWithSeriesId("dma-nm"), zip, tmp);
        reader.open(null);

        assertEquals(0, reader.checkpointInfo(), "before any read the checkpoint cursor is 0");
        reader.readItem();
        assertEquals(1, reader.checkpointInfo(), "after one read the checkpoint cursor is 1");
        reader.readItem();
        assertEquals(2, reader.checkpointInfo(), "after two reads the checkpoint cursor is 2");

        // Reading past the end must not advance the cursor beyond the list size.
        reader.readItem();
        assertEquals(2, reader.checkpointInfo(),
                "a fully-read reader's checkpoint must equal the list size and not over-run");
    }

    @Test
    @Transactional
    void resumesFromPreviousCheckpoint(@TempDir Path tmp) throws Exception {
        Path zip = zipWithMessagesJson(tmp, messagesJson(List.of(
                archiveMessage(1, "messages/aa/01", "dma-nm"),
                archiveMessage(2, "messages/aa/02", "dma-nm"),
                archiveMessage(3, "messages/aa/03", "dma-nm"))));
        TestableArchiveReader reader = readerFor(jobWithSeriesId("dma-nm"), zip, tmp);

        // Resume from checkpoint 2: the first two items were already processed in a prior run.
        reader.open((Serializable) Integer.valueOf(2));

        assertEquals(2, reader.checkpointInfo(),
                "open(prevCheckpointInfo) must restore the read cursor from the checkpoint");

        Object resumed = reader.readItem();
        assertSame(reader.messages.get(2), resumed,
                "a resumed reader must continue from the checkpointed index, skipping processed items");
        assertEquals(Integer.valueOf(3), ((ExtractedArchiveMessageVo) resumed).getMessage().getNumber(),
                "the resumed item must be the third archive entry");
        assertNull(reader.readItem(), "and then be exhausted");
    }

    @Test
    @Transactional
    void progressReportedAtFirstItem(@TempDir Path tmp) throws Exception {
        // updateProgress fires for messageNo % 5 == 0; with a small archive that is only index 0.
        Path zip = zipWithMessagesJson(tmp, messagesJson(List.of(
                archiveMessage(1, "messages/aa/01", "dma-nm"),
                archiveMessage(2, "messages/aa/02", "dma-nm"),
                archiveMessage(3, "messages/aa/03", "dma-nm"))));
        TestableArchiveReader reader = readerFor(jobWithSeriesId("dma-nm"), zip, tmp);
        reader.open(null);

        reader.readItem(); // index 0 -> progress 0
        reader.readItem(); // index 1 -> no progress
        reader.readItem(); // index 2 -> no progress

        assertEquals(List.of(0), reader.progressCalls,
                "progress must be reported exactly once, at the first item, as 0%%");
        assertNotNull(reader.progressCalls, "progress callbacks must have been recorded");
    }
}
