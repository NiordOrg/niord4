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

import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.message.vo.SystemMessageVo;
import org.niord.core.repo.RepositoryService;
import org.niord.model.message.AttachmentVo;
import org.niord.model.message.MessagePartDescVo;
import org.niord.model.message.MessagePartType;
import org.niord.model.message.MessagePartVo;
import org.niord.model.search.PagedSearchResultVo;
import org.niord.test.ITTest;

import org.niord.core.repo.RepoStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link MessageExportService#export}, which serialises a paged message search
 * result into a Zip archive containing {@code messages.json}, {@code messages-preview.html} and the
 * attachment / custom-thumbnail files of each message that carries attachments.
 *
 * <p>These tests do not write to the database (no {@code @Transactional} is needed). Instead they
 * The attachments the tests stage are rows of the repository store, written under the message's own folder and
 * deleted by folder in {@code cleanup()}, so the shared store is left as it was.
 *
 * <p>This is a framework-coupled test — it needs the Quarkus CDI container to obtain the wired
 * {@link MessageExportService} (which injects the {@code @TextResource} preview-html template and the
 * {@link RepositoryService}); see docs/framework-coupled-tests.md.
 */
class MessageExportServiceITTest extends ITTest {

    @Autowired
    MessageExportService exportService;

    @Autowired
    RepositoryService repositoryService;

    @Autowired
    RepoStore store;

    @PersistenceContext
    EntityManager em; // unused: these tests stage files, they do not touch the database

    /** Folders staged in the store whose rows must be deleted after each test. */
    private final List<String> stagedDirs = new ArrayList<>();

    @AfterEach
    void cleanup() {
        stagedDirs.forEach(f -> store.deleteFolder(f));
        stagedDirs.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Wraps the given messages in a paged search result with matching total/size. */
    private PagedSearchResultVo<SystemMessageVo> resultOf(SystemMessageVo... msgs) {
        PagedSearchResultVo<SystemMessageVo> r = new PagedSearchResultVo<>();
        r.setData(new ArrayList<>(List.of(msgs)));
        r.setTotal((long) msgs.length);
        r.setSize(msgs.length);
        return r;
    }

    /** Reads every Zip entry into a name -> bytes map. */
    private Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                out.put(e.getName(), zis.readAllBytes());
                zis.closeEntry();
            }
        }
        return out;
    }

    /** Runs the export into an in-memory buffer and returns the raw Zip bytes. */
    private byte[] exportToBytes(PagedSearchResultVo<SystemMessageVo> r) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        exportService.export(r, baos);
        return baos.toByteArray();
    }

    /** Builds a fresh message VO with a unique repo path and no attachments. */
    private SystemMessageVo newMessage(String repoPath) {
        SystemMessageVo m = new SystemMessageVo();
        m.setId("M-" + UUID.randomUUID());
        m.setRepoPath(repoPath);
        return m;
    }

    /** A unique repo path rooted under "messages/it/<uuid>" so tests never collide. */
    private String uniqueRepoPath() {
        return "messages/it/" + UUID.randomUUID();
    }

    /** Writes a file into the store at the given repository path, tracking the message folder for cleanup. */
    private void stage(String messageRepoPath, String repoRelativePath, byte[] bytes) {
        if (!stagedDirs.contains(messageRepoPath)) {
            stagedDirs.add(messageRepoPath);
        }
        store.write(repoRelativePath, bytes, java.time.Instant.now());
    }

    /** Decodes a Zip entry's bytes as UTF-8 text. */
    private String text(Map<String, byte[]> entries, String name) {
        return new String(entries.get(name), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------------------------- //
    // messages.json + messages-preview.html
    // ------------------------------------------------------------------------------------------- //

    @Test
    void exportProducesMessagesJsonAndPreviewHtml() throws Exception {
        String repoPath = uniqueRepoPath();
        SystemMessageVo m = newMessage(repoPath);

        byte[] bytes = exportToBytes(resultOf(m));
        Map<String, byte[]> entries = unzip(bytes);

        assertTrue(entries.containsKey("messages.json"),
                "the archive must contain the serialised messages.json");
        assertTrue(entries.containsKey("messages-preview.html"),
                "the archive must contain the messages-preview.html preview file");

        // The serialised result must round-trip through Jackson and carry the message's repo path.
        String json = text(entries, "messages.json");
        ObjectMapper mapper = new ObjectMapper();
        PagedSearchResultVo<?> parsed = mapper.readValue(json, PagedSearchResultVo.class);
        assertEquals(1L, parsed.getTotal(), "the serialised result must report a total of 1");
        assertEquals(1, parsed.getData().size(), "the serialised result must contain one message");
        assertTrue(json.contains(repoPath),
                "messages.json must contain the message's repo path: " + repoPath);
    }

    // ------------------------------------------------------------------------------------------- //
    // Description-link rewriting
    // ------------------------------------------------------------------------------------------- //

    @Test
    void exportRewritesRepoFileLinkPrefix() throws Exception {
        String repoPath = uniqueRepoPath();
        SystemMessageVo m = newMessage(repoPath);

        // One DETAILS part whose English description embeds a link to an attachment under the
        // "/rest/repo/file/<repoPath>" context root. export() must strip that "/rest/repo/file/"
        // prefix, leaving a bare "<repoPath>/img.jpg" link.
        MessagePartVo part = new MessagePartVo();
        part.setType(MessagePartType.DETAILS);
        MessagePartDescVo desc = part.createDesc("en");
        desc.setDetails("<a href=\"/rest/repo/file/" + repoPath + "/img.jpg\">x</a>");
        m.setParts(new ArrayList<>(List.of(part)));

        byte[] bytes = exportToBytes(resultOf(m));
        Map<String, byte[]> entries = unzip(bytes);

        String json = text(entries, "messages.json");
        // The "/rest/repo/file/" prefix in front of this repo path must be gone, leaving the bare link.
        assertTrue(json.contains("\"" + repoPath + "/img.jpg"),
                "the rewritten link must keep the bare repo path: \"" + repoPath + "/img.jpg");
        assertFalse(json.contains("/rest/repo/file/" + repoPath),
                "the '/rest/repo/file/<repoPath>' prefix must be removed from description links");
    }

    // ------------------------------------------------------------------------------------------- //
    // Attachment files
    // ------------------------------------------------------------------------------------------- //

    @Test
    void exportIncludesAttachmentFile() throws Exception {
        String repoPath = uniqueRepoPath();
        SystemMessageVo m = newMessage(repoPath);

        // Stage <repoPath>/1/photo.jpg with known bytes.
        byte[] pixels = "PIXELS".getBytes(StandardCharsets.UTF_8);
        stage(repoPath, repoPath + "/1/photo.jpg", pixels);

        AttachmentVo att = new AttachmentVo();
        att.setPath(repoPath + "/1/photo.jpg");
        att.setFileName("photo.jpg");
        m.setAttachments(new ArrayList<>(List.of(att)));

        byte[] bytes = exportToBytes(resultOf(m));
        Map<String, byte[]> entries = unzip(bytes);

        String entryName = repoPath + "/1/photo.jpg";
        assertTrue(entries.containsKey(entryName),
                "the staged attachment file must be embedded in the archive at " + entryName);
        assertArrayEquals(pixels, entries.get(entryName),
                "the embedded attachment bytes must match the staged file verbatim");
    }

    @Test
    void exportSkipsAttachmentWithPathOutsideRepoPath() throws Exception {
        String repoPath = uniqueRepoPath();
        SystemMessageVo m = newMessage(repoPath);

        // The attachment path does NOT start with the message's repo path -> it must be skipped.
        AttachmentVo att = new AttachmentVo();
        att.setPath("some/other/path/x.jpg");
        att.setFileName("x.jpg");
        m.setAttachments(new ArrayList<>(List.of(att)));

        byte[] bytes = exportToBytes(resultOf(m));
        Map<String, byte[]> entries = unzip(bytes);

        assertTrue(entries.containsKey("messages.json"),
                "the archive must still contain messages.json");
        assertFalse(entries.containsKey("some/other/path/x.jpg"),
                "an attachment whose path is outside the message repo path must be skipped");
    }

    @Test
    void exportSkipsAttachmentWhenFileMissing() throws Exception {
        String repoPath = uniqueRepoPath();
        SystemMessageVo m = newMessage(repoPath);

        // The path is under the repo path, but no file (and no folder) is staged for it.
        AttachmentVo att = new AttachmentVo();
        att.setPath(repoPath + "/1/missing.jpg");
        att.setFileName("missing.jpg");
        m.setAttachments(new ArrayList<>(List.of(att)));

        byte[] bytes = exportToBytes(resultOf(m));
        Map<String, byte[]> entries = unzip(bytes);

        assertTrue(entries.containsKey("messages.json"),
                "the archive must still contain messages.json");
        assertFalse(entries.containsKey(repoPath + "/1/missing.jpg"),
                "an attachment with no backing file must be skipped");
    }

    // ------------------------------------------------------------------------------------------- //
    // Custom thumbnail
    // ------------------------------------------------------------------------------------------- //

    @Test
    void exportIncludesCustomThumbnail() throws Exception {
        String repoPath = uniqueRepoPath();
        SystemMessageVo m = newMessage(repoPath);

        // A custom thumbnail and no attachment: the message is exported for the thumbnail alone
        byte[] thumbBytes = "THUMB".getBytes(StandardCharsets.UTF_8);
        stage(repoPath, repoPath + "/1/custom_thumb.png", thumbBytes);
        m.setThumbnailPath(repoPath + "/1/custom_thumb.png");

        byte[] bytes = exportToBytes(resultOf(m));
        Map<String, byte[]> entries = unzip(bytes);

        String thumbEntry = repoPath + "/1/custom_thumb.png";
        assertTrue(entries.containsKey(thumbEntry),
                "the custom thumbnail file must be embedded in the archive at " + thumbEntry);
        assertArrayEquals(thumbBytes, entries.get(thumbEntry),
                "the embedded thumbnail bytes must match the staged file verbatim");
    }
}
