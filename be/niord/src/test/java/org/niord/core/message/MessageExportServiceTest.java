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
package org.niord.core.message;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.repo.RepoFileInfo;
import org.niord.core.repo.RepoStore;
import org.niord.model.message.AttachmentVo;
import org.niord.model.search.PagedSearchResultVo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MessageExportService}: a message's attachments and custom thumbnail are exported only when they name a
 * row inside the message's own repository folder.
 *
 * <p>The attachment path and the thumbnail path are stored as an editor sent them, and the export is anonymous
 * once the message is published, so a path that climbs out of the folder, into another message's folder or out of
 * the repository, must be skipped. The store is a mock holding three rows: the message's own file, another
 * message's file and the message's custom thumbnail; what the export resolves is what it streams.
 */
class MessageExportServiceTest {

    private static final byte[] OWN = "OWN-MESSAGE-PIXELS".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER = "OTHER-MESSAGE-SECRET".getBytes(StandardCharsets.UTF_8);
    private static final byte[] THUMB = "THUMB".getBytes(StandardCharsets.UTF_8);

    private MessageExportService service;
    private final String repoPath = "messages/a/ab/own-uid";
    private final String otherRepoPath = "messages/c/cd/other-uid";

    /** The rows the mocked store holds, by path */
    private final Map<String, byte[]> rows = new HashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        rows.put(repoPath + "/1/photo.jpg", OWN);
        rows.put(otherRepoPath + "/1/secret.txt", OTHER);
        rows.put(repoPath + "/1/custom_thumb_256.png", THUMB);

        RepoStore store = mock(RepoStore.class);
        when(store.stat(anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            byte[] bytes = rows.get(path);
            if (bytes == null) {
                return Optional.empty();
            }
            int slash = path.lastIndexOf('/');
            return Optional.of(new RepoFileInfo(path, path.substring(0, slash), path.substring(slash + 1),
                    bytes.length, null, Instant.now(), Instant.now(), null));
        });
        doAnswer(invocation -> {
            RepoFileInfo file = invocation.getArgument(0);
            OutputStream out = invocation.getArgument(1);
            out.write(rows.get(file.path()));
            return null;
        }).when(store).copyTo(any(RepoFileInfo.class), any(OutputStream.class));

        service = new MessageExportService(store);
        service.messagesPreviewHtmlFile = "<html><<MESSAGES-JSON>></html>";
    }

    private SystemMessageVo message(AttachmentVo... attachments) {
        SystemMessageVo m = new SystemMessageVo();
        m.setId("own-uid");
        m.setRepoPath(repoPath);
        m.setAttachments(new ArrayList<>(List.of(attachments)));
        return m;
    }

    private static AttachmentVo attachment(String path, String fileName) {
        AttachmentVo att = new AttachmentVo();
        att.setPath(path);
        att.setFileName(fileName);
        return att;
    }

    /** Exports the message and returns every zip entry as name -> bytes */
    private Map<String, byte[]> export(SystemMessageVo message) throws IOException {
        PagedSearchResultVo<SystemMessageVo> result = new PagedSearchResultVo<>();
        result.setData(new ArrayList<>(List.of(message)));
        result.setTotal(1L);
        result.setSize(1);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        service.export(result, bytes);

        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            for (ZipEntry e = zis.getNextEntry(); e != null; e = zis.getNextEntry()) {
                entries.put(e.getName(), zis.readAllBytes());
            }
        }
        return entries;
    }

    /** Whether any entry holds the given bytes */
    private static boolean anyEntryHolds(Map<String, byte[]> entries, byte[] content) {
        return entries.values().stream().anyMatch(v -> Arrays.equals(v, content));
    }

    @Test
    void anAttachmentInTheMessageFolderIsExportedUnderItsRepositoryPath() throws Exception {
        Map<String, byte[]> entries = export(message(attachment(repoPath + "/1/photo.jpg", "photo.jpg")));

        assertArrayEquals(OWN, entries.get(repoPath + "/1/photo.jpg"));
        assertTrue(entries.containsKey(repoPath + "/1/"), "the folders leading to the file are added");
    }

    @Test
    void aFileNameWithParentSegmentsIntoAnotherMessageIsSkipped() throws Exception {
        Map<String, byte[]> entries = export(message(
                attachment(repoPath + "/1/secret.txt", "../../../../c/cd/other-uid/1/secret.txt")));

        assertFalse(anyEntryHolds(entries, OTHER), "another message's file must not be exported");
        assertTrue(entries.containsKey("messages.json"), "the rest of the export is unaffected");
    }

    @Test
    void aFileNameThatLeavesTheRepositoryIsSkipped() throws Exception {
        Map<String, byte[]> entries = export(message(
                attachment(repoPath + "/1/outside.txt", "../../../../../../outside.txt"),
                attachment(repoPath + "/1/outside.txt", "/etc/outside.txt")));

        assertFalse(entries.keySet().stream().anyMatch(n -> n.contains("..")), "no entry name climbs");
        assertFalse(entries.keySet().stream().anyMatch(n -> n.contains("outside")), "nothing outside is exported");
    }

    @Test
    void aPathWhoseFolderClimbsOutOfTheMessageFolderIsSkipped() throws Exception {
        // The path starts with the message's repoPath, which is all the export checked before
        Map<String, byte[]> entries = export(message(
                attachment(repoPath + "/../../../c/cd/other-uid/1/secret.txt", "secret.txt")));

        assertFalse(anyEntryHolds(entries, OTHER), "another message's file must not be exported");
    }

    @Test
    void aThumbnailPathThatClimbsOutOfTheMessageFolderIsSkipped() throws Exception {
        SystemMessageVo m = message(attachment(repoPath + "/1/photo.jpg", "photo.jpg"));
        m.setThumbnailPath(repoPath + "/1/../../../../c/cd/other-uid/1/secret.txt");

        Map<String, byte[]> entries = export(m);

        assertFalse(anyEntryHolds(entries, OTHER), "another message's file must not be exported as a thumbnail");
        assertArrayEquals(OWN, entries.get(repoPath + "/1/photo.jpg"), "the genuine attachment still is");
    }

    @Test
    void aThumbnailInTheMessageFolderIsExported() throws Exception {
        SystemMessageVo m = message(attachment(repoPath + "/1/photo.jpg", "photo.jpg"));
        m.setThumbnailPath(repoPath + "/1/custom_thumb_256.png");

        assertArrayEquals(THUMB, export(m).get(repoPath + "/1/custom_thumb_256.png"));
    }

    @Test
    void theMessageFolderItselfIsNotAFile() throws Exception {
        Map<String, byte[]> entries = export(message(attachment(repoPath + "/1/x", "..")));

        assertEquals(List.of(), entries.keySet().stream().filter(n -> n.startsWith("messages/")).toList(),
                "nothing of the message folder is exported for a name that names no file");
    }
}
