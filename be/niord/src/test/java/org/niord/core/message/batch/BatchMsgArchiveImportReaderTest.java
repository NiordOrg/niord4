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
package org.niord.core.message.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.niord.core.message.batch.BatchMsgArchiveImportReader.ExtractedArchiveMessageVo;
import org.niord.core.message.vo.SystemMessageVo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BatchMsgArchiveImportReader#extractEntries}: an uploaded message archive is
 * extracted into a temporary repository folder, and no entry may be written outside it.
 * {@link BatchMsgArchiveImportReader#toExtractedMessages}: nor may a message of the archive name a folder
 * outside it as the one its files are copied from.
 */
class BatchMsgArchiveImportReaderTest {

    /** Writes a zip archive holding the given entries, in order (a name ending in '/' is a folder) */
    private static Path zip(Path dir, Map<String, String> entries) throws IOException {
        Path zip = dir.resolve("archive.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                if (!e.getKey().endsWith("/")) {
                    zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                }
                zos.closeEntry();
            }
        }
        return zip;
    }

    /** The temp folder an archive is extracted to, as RepositoryService.getNewTempDir names one */
    private static final String TEMP = "temp/f823b7d5-9559-4a76-b3a3-6d32f2bf55f2";

    /** A store that records every write as path -> content; the extraction writes rows, never files */
    private static Map<String, String> recordingStore(org.niord.core.repo.RepoStore store) throws IOException {
        Map<String, String> written = new LinkedHashMap<>();
        org.mockito.Mockito.when(store.write(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(java.io.InputStream.class),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(0);
                    java.io.InputStream in = invocation.getArgument(1);
                    written.put(path, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    return null;
                });
        return written;
    }

    @Test
    void anExportedArchiveExtractsToItsRelativePaths(@TempDir Path tmp) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("messages.json", "{\"data\":[]}");
        entries.put("messages/", "");
        entries.put("messages/a/68/uid/", "");
        entries.put("messages/a/68/uid/4/Buoy_seal.jpg", "PIXELS");
        Path zip = zip(tmp, entries);
        org.niord.core.repo.RepoStore store = org.mockito.Mockito.mock(org.niord.core.repo.RepoStore.class);
        Map<String, String> written = recordingStore(store);

        BatchMsgArchiveImportReader.extractEntries(zip, TEMP, store);

        assertEquals(Set.of(TEMP + "/messages/a/68/uid/4/Buoy_seal.jpg"), written.keySet(),
                "the files are written below the temp folder; the folder entries and the index are not rows");
        assertEquals("PIXELS", written.get(TEMP + "/messages/a/68/uid/4/Buoy_seal.jpg"));
    }

    @Test
    void anEntryThatReturnsThroughASubFolderIsAccepted(@TempDir Path tmp) throws Exception {
        Path zip = zip(tmp, Map.of("a/../b.txt", "inside"));
        org.niord.core.repo.RepoStore store = org.mockito.Mockito.mock(org.niord.core.repo.RepoStore.class);
        Map<String, String> written = recordingStore(store);

        BatchMsgArchiveImportReader.extractEntries(zip, TEMP, store);

        assertEquals("inside", written.get(TEMP + "/b.txt"));
    }

    @Test
    void anEntryWithParentSegmentsFailsTheExtractionAndWritesNothingOutside(@TempDir Path tmp) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("messages.json", "{\"data\":[]}");
        entries.put("../../escaped.txt", "outside");
        Path zip = zip(tmp, entries);
        org.niord.core.repo.RepoStore store = org.mockito.Mockito.mock(org.niord.core.repo.RepoStore.class);
        Map<String, String> written = recordingStore(store);

        IOException e = assertThrows(IOException.class,
                () -> BatchMsgArchiveImportReader.extractEntries(zip, TEMP, store));

        assertTrue(e.getMessage().contains("../../escaped.txt"), "the refusal names the entry");
        assertEquals(Set.of(), written.keySet(),
                "only the entries before the refused one are written, and the index is never a row");
    }

    @Test
    void anAbsoluteEntryNameFailsTheExtractionAndWritesNothingOutside(@TempDir Path tmp) throws Exception {
        Path outside = tmp.resolve("outside.txt");
        Path zip = zip(tmp, Map.of(outside.toAbsolutePath().toString(), "outside"));
        org.niord.core.repo.RepoStore store = org.mockito.Mockito.mock(org.niord.core.repo.RepoStore.class);
        Map<String, String> written = recordingStore(store);

        assertThrows(IOException.class, () -> BatchMsgArchiveImportReader.extractEntries(zip, TEMP, store));

        assertTrue(written.isEmpty(), "an absolute entry must not be written where it points");
        assertFalse(Files.exists(outside), "and nothing reaches the disk");
    }

    // ------------------------------------------------------------------ toExtractedMessages

    /** The repository path of an extracted archive's folder, as RepositoryService.getNewTempDir names it */
    private static final String ARCHIVE_FOLDER = "temp/0b7e2c1a-archive";

    private static SystemMessageVo message(String repoPath) {
        SystemMessageVo vo = new SystemMessageVo();
        vo.setRepoPath(repoPath);
        return vo;
    }

    @Test
    void aMessageFolderInsideTheArchiveBecomesTheEditFolder() {
        List<ExtractedArchiveMessageVo> extracted = BatchMsgArchiveImportReader.toExtractedMessages(
                List.of(message("messages/a/68/uid-1"), message("messages/b/12/uid-2")), ARCHIVE_FOLDER);

        assertEquals(List.of(ARCHIVE_FOLDER + "/messages/a/68/uid-1", ARCHIVE_FOLDER + "/messages/b/12/uid-2"),
                extracted.stream().map(ExtractedArchiveMessageVo::getEditRepoPath).toList());
    }

    @Test
    void aMessageFolderOutsideTheArchiveRefusesTheArchive() {
        // The folder sync confines the edit folder to the temp root only, which holds every user's uploads and
        // extracted archives. FAILS on the pre-fix code (the path was taken as it came).
        for (String repoPath : new String[] {
                "../other-archive/messages/a", "messages/../../other-archive", "..", "messages/..", "." }) {
            assertThrows(IllegalArgumentException.class,
                    () -> BatchMsgArchiveImportReader.toExtractedMessages(
                            List.of(message("messages/a/68/uid-1"), message(repoPath)), ARCHIVE_FOLDER),
                    "'" + repoPath + "' must be refused");
        }
    }

    @Test
    void aMessageWithoutAFolderHasNoFilesToCopy() {
        List<ExtractedArchiveMessageVo> extracted = BatchMsgArchiveImportReader.toExtractedMessages(
                List.of(message(null), message(" ")), ARCHIVE_FOLDER);

        assertNull(extracted.get(0).getEditRepoPath());
        assertNull(extracted.get(1).getEditRepoPath());
    }
}
