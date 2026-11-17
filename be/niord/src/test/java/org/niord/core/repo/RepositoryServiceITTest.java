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
package org.niord.core.repo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.util.WebUtils;
import org.niord.core.web.NiordWebException;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import org.niord.test.RepoRows;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the non-REST methods of {@link RepositoryService} and for {@link RepoStore}, run
 * against the {@link ITTest} infrastructure.
 *
 * <p>The repository's files are rows of the store, so these tests exercise the string path API
 * ({@code getRepoUri}, {@code getNewTempDir}, {@code validateTempRepoPath}, {@code resolveInRepo},
 * {@code updateRepoFolderFromTempEditFolder}, {@code createTempEditRepoFolder}), the anonymous read handlers
 * ({@code streamFile}, {@code getThumbnail}, {@code listFiles}) called directly, so their own path containment
 * is tested without the HTTP firewall in front of them, the hourly sweep, and the store's own operations: the
 * upsert, the whole-row read, the folder copy and the overlapping saves the upsert exists for.
 *
 * <p>The store's writes commit in transactions of their own, so {@code @Transactional} is deliberately NOT used.
 * Each test creates only uniquely-named folders (suffixed with {@link System#nanoTime()}) under the served roots
 * and deletes their rows in {@link #cleanUp()}, keeping the tests independent and order-independent.
 */
class RepositoryServiceITTest extends ITTest {

    @Autowired
    RepositoryService repo;

    @Autowired
    RepoStore store;

    @Autowired
    ThumbnailService thumbnails;

    @Autowired
    RepoCache cache;

    @Autowired
    JdbcTemplate jdbc;

    /** Folders created by a test, whose rows are deleted after it. */
    private final List<String> createdFolders = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String folder : createdFolders) {
            try {
                store.deleteFolder(folder);
            } catch (Exception ignored) {
                // best-effort cleanup only
            }
        }
        createdFolders.clear();
    }

    /** Registers a folder for deletion after the test. */
    private String track(String folder) {
        createdFolders.add(folder);
        return folder;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    // ------------------------------------------------------------------------------------------- //
    // The store's write of a stream whose length is not known
    // ------------------------------------------------------------------------------------------- //

    /**
     * A report fetched over HTTP arrives without a length and is spooled before its row is written: the source is
     * read outside any transaction, so a slow renderer holds no connection of the pool, and the row is the whole
     * stream nonetheless.
     */
    @Test
    void aStreamOfUnknownLengthIsSpooledOutsideATransactionAndWrittenWhole() throws IOException {
        String folder = track("publications/it-spool-" + System.nanoTime());
        boolean[] transactionWhileRead = {false};
        InputStream source = new ByteArrayInputStream(bytes("a rendered report")) {
            @Override
            public int read(byte[] b, int off, int len) {
                transactionWhileRead[0] |= TransactionSynchronizationManager.isActualTransactionActive();
                return super.read(b, off, len);
            }
        };

        RepoFileInfo written = store.write(folder + "/report.pdf", source, now());

        assertFalse(transactionWhileRead[0], "the source is read into the spool with no transaction open");
        assertEquals("a rendered report".length(), written.size());
        assertEquals("a rendered report", RepoRows.readText(store, folder + "/report.pdf"));
    }

    // ------------------------------------------------------------------------------------------- //
    // getRepoUri
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getRepoUriProducesRestUrlWithEncodedRelativePath() {
        String expected = "/rest/repo/file/" + WebUtils.encodeURI("a/b.txt");
        assertEquals(expected, repo.getRepoUri("a/b.txt"));
        // encodeURI preserves '/', so the relative path stays human-readable.
        assertEquals("/rest/repo/file/a/b.txt", repo.getRepoUri("a/b.txt"),
                "encodeURI must keep '/' un-encoded in the relative path");
    }

    @Test
    void getRepoUriEncodesSpacesInFileNames() {
        // A space must be percent-encoded by encodeURI (space -> %20), while '/' is preserved.
        assertEquals("/rest/repo/file/a/my%20file.txt", repo.getRepoUri("a/my file.txt"));
    }

    // ------------------------------------------------------------------------------------------- //
    // getNewTempDir
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getNewTempDirReturnsUuidDirectoryUnderTemp() {
        RepoFileVo dir = repo.getNewTempDir();

        assertNotNull(dir.getName(), "a new temp dir must carry a name");
        assertFalse(dir.getName().trim().isEmpty(), "the name (a UUID) must not be blank");
        assertTrue(dir.isDirectory(), "a new temp dir VO must be flagged as a directory");
        assertTrue(dir.getPath().startsWith("temp/"),
                "the path must be rooted at the temp sub-root: " + dir.getPath());
        // The name is a UUID, so the path is exactly temp/<name>.
        assertEquals("temp/" + dir.getName(), dir.getPath());
    }

    @Test
    void getNewTempDirReturnsDistinctNamesOnEachCall() {
        RepoFileVo a = repo.getNewTempDir();
        RepoFileVo b = repo.getNewTempDir();
        assertNotEquals(a.getName(), b.getName(),
                "each call must mint a fresh unique directory name");
    }

    // ------------------------------------------------------------------------------------------- //
    // validateTempRepoPath
    // ------------------------------------------------------------------------------------------- //

    @Test
    void validateTempRepoPathResolvesPathsUnderTempRoot() {
        String rel = "temp/sub" + System.nanoTime();

        assertEquals(rel, repo.validateTempRepoPath(rel), "a valid temp path is returned normalised");
        assertEquals(rel, repo.validateTempRepoPath(rel + "/"), "a slash at the end is dropped");
        assertThrows(NiordWebException.class, () -> repo.validateTempRepoPath("/" + rel),
                "a leading slash makes an absolute path, which names nothing in the repository");
        assertEquals(rel, repo.validateTempRepoPath("temp/./x/../sub" + rel.substring("temp/sub".length())),
                "a '.' and a harmless '..' are collapsed");
    }

    @Test
    void validateTempRepoPathRejectsPathOutsideTempRootWith403() {
        String rel = "outside-" + System.nanoTime() + "/x";

        NiordWebException ex = assertThrows(NiordWebException.class,
                () -> repo.validateTempRepoPath(rel),
                "a path not under the temp root must be rejected");
        assertEquals(403, ex.getStatusCode().value(),
                "rejection must surface as HTTP 403");
    }

    @Test
    void validateTempRepoPathRejectsDotDotTraversalEscapeWith403() {
        // "temp/../escaped-..." normalises to "escaped-...", a sibling of (outside) the temp root: the guard
        // normalises before the boundary check, so the ".." segment is collapsed and the escape is rejected.
        String rel = "temp/../escaped-" + System.nanoTime();

        NiordWebException ex = assertThrows(NiordWebException.class,
                () -> repo.validateTempRepoPath(rel),
                "a '..' traversal that escapes the temp root must be rejected, not accepted");
        assertEquals(403, ex.getStatusCode().value(),
                "the traversal escape must surface as HTTP 403, the same as any other out-of-root path");
    }

    // ------------------------------------------------------------------------------------------- //
    // resolveInRepo and the anonymous read handlers (file / thumb / list)
    //
    // The handlers are called directly, so the HTTP firewall in front of them is not involved: these
    // tests pin the handlers' own containment check. A path that leaves the repository answers like a
    // missing file (404), and so does a path outside the served roots.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void resolveInRepoRejectsDotDotEscapeAndAbsolutePath() {
        assertNull(repo.resolveInRepo("../outside.txt"),
                "a '..' escape out of the repository must be refused");
        assertNull(repo.resolveInRepo("temp/../../outside.txt"),
                "a '..' escape through a sub-folder must be refused");
        assertNull(repo.resolveInRepo("/etc/passwd"),
                "an absolute path must be refused");
        assertNull(repo.resolveInRepo("C:\\repo\\x.txt"),
                "a Windows absolute path must be refused");
        assertNull(repo.resolveInRepo(null), "a null path resolves to nothing");
    }

    @Test
    void resolveInRepoAcceptsHarmlessDotDotInsideTheRoot() {
        assertEquals("it-inroot/b.txt", repo.resolveInRepo("it-inroot/a/../b.txt"),
                "a '..' that stays inside the repository must resolve to the normalised path");
        assertEquals("messages/a/b.txt", repo.resolveInRepo("messages\\a\\b.txt"),
                "backslashes are slashes");
    }

    @Test
    void streamFileAnswersAnEscapeLikeAMissingFileWithoutEchoingThePath() throws IOException {
        String escape = "/../outside-" + System.nanoTime() + ".txt";

        ResponseEntity<?> response = repo.streamFile(escape,
                new MockHttpServletRequest("GET", "/rest/repo/file" + escape), new MockHttpServletResponse());

        assertEquals(404, response.getStatusCode().value(), "a file outside the repository must not be streamed");
        assertEquals(MediaType.TEXT_PLAIN, response.getHeaders().getContentType(),
                "the 404 body must be plain text, never negotiated to HTML");
        assertEquals("File not found", response.getBody(), "the 404 body must not echo the requested path");
    }

    @Test
    void streamFileRefusesAnAbsolutePath() throws IOException {
        ResponseEntity<?> response = repo.streamFile("//etc/passwd",
                new MockHttpServletRequest("GET", "/rest/repo/file"), new MockHttpServletResponse());

        assertEquals(404, response.getStatusCode().value(), "an absolute path must not be streamed");
    }

    @Test
    void streamFileRefusesAFileOutsideTheServedRoots() throws IOException {
        String folder = track("archive/it-stream-" + System.nanoTime());
        store.write(folder + "/b.txt", bytes("archived"), now());

        ResponseEntity<?> response = repo.streamFile("/" + folder + "/b.txt",
                new MockHttpServletRequest("GET", "/rest/repo/file/" + folder + "/b.txt"), new MockHttpServletResponse());

        assertEquals(404, response.getStatusCode().value(), "the archive is never served by the repository");
        assertEquals("File not found", response.getBody());
    }

    @Test
    void streamFileStreamsAnInRootPathWithAHarmlessDotDot() throws IOException {
        String folder = track("messages/it-stream-" + System.nanoTime());
        RepoFileInfo written = store.write(folder + "/b.txt", bytes("inside"), now());

        ResponseEntity<?> response = repo.streamFile("/" + folder + "/a/../b.txt",
                new MockHttpServletRequest("GET", "/rest/repo/file/" + folder + "/b.txt"), new MockHttpServletResponse());

        assertEquals(200, response.getStatusCode().value(), "a path that stays inside the repository is streamed");
        RepoFileResource body = (RepoFileResource) response.getBody();
        assertNotNull(body);
        assertEquals(folder + "/b.txt", body.file().path());
        assertEquals(written.etag(), response.getHeaders().getETag(), "the ETag is the row's time and size");
        try (InputStream in = body.getInputStream()) {
            assertArrayEquals(bytes("inside"), in.readAllBytes());
        }
    }

    @Test
    void getThumbnailAnswersAnEscapeLikeAMissingFile() throws Exception {
        ResponseEntity<?> response = repo.getThumbnail("/../outside-" + System.nanoTime() + ".png", 64,
                new MockHttpServletRequest("GET", "/rest/repo/thumb"), new MockHttpServletResponse());

        assertEquals(404, response.getStatusCode().value(), "no thumbnail of a file outside the repository");
        assertEquals("File not found", response.getBody(), "the 404 body must not echo the requested path");
    }

    @Test
    void listFilesRefusesAFolderOutsideTheRepositoryOrTheServedRoots() {
        NiordWebException escape = assertThrows(NiordWebException.class, () -> repo.listFiles("/.."),
                "the folder holding the repository root must not be listed");
        assertEquals(404, escape.getStatusCode().value());

        // The archive is never served: a folder of it lists as an empty folder does, whatever rows it holds
        String archived = "archive/publications/it-list-" + System.nanoTime();
        RepoRows.writeText(store, archived + "/edition.pdf", "superseded");
        try {
            assertEquals(List.of(), repo.listFiles(archived), "the archive must not be listed");
        } finally {
            store.deleteFolder(archived);
        }
    }

    /** Every row is an upload, named like a thumbnail or a map image or not; the custom map thumbnail is not listed */
    @Test
    void listFilesListsTheUploadsDirectlyInAFolder() {
        String folder = track("messages/it-list-" + System.nanoTime());
        store.write(folder + "/chart.pdf", bytes("pdf"), now());
        store.write(folder + "/chart_thumb_64.pdf", bytes("thumb"), now());
        store.write(folder + "/map_256.png", bytes("map"), now());
        store.write(folder + "/custom_thumb_256.png", bytes("custom"), now());
        store.write(folder + "/sub/nested.txt", bytes("nested"), now());

        List<RepoFileVo> listed = repo.listFiles("/" + folder);

        assertEquals(List.of("chart.pdf", "chart_thumb_64.pdf", "map_256.png"),
                listed.stream().map(RepoFileVo::getName).toList(),
                "the files directly in the folder, by name, less the custom map thumbnail");
        assertEquals(WebUtils.encodeURI(folder + "/chart.pdf"), listed.get(0).getPath());
        assertEquals(3L, listed.get(0).getSize());
    }

    // ------------------------------------------------------------------------------------------- //
    // updateRepoFolderFromTempEditFolder: both folders arrive with the request body
    // ------------------------------------------------------------------------------------------- //

    /** A folder under a served root holding one file, tracked for cleanup. */
    private String sourceFolderOutsideTemp() {
        String rel = track("messages/it-sync-src-" + System.nanoTime());
        store.write(rel + "/secret.txt", bytes("not an edit folder"), now());
        return rel;
    }

    /** A temp edit folder holding one file in revision 1, tracked for cleanup. */
    private String tempEditFolder() {
        String rel = track("temp/it-sync-edit-" + System.nanoTime());
        store.write(rel + "/1/attachment.txt", bytes("uploaded while editing"), now());
        return rel;
    }

    @Test
    void updateRepoFolderRefusesAnEditFolderOutsideTempAndCopiesNothing() {
        String dst = track("messages/it-sync-dst-" + System.nanoTime());

        // The temp root itself holds every user's edit folders, so it is no edit folder either
        for (String editRepoPath : List.of(sourceFolderOutsideTemp(), "temp/../" + sourceFolderOutsideTemp(),
                "temp", "temp/.", "temp/x/..")) {
            TestRepoBackedVo vo = new TestRepoBackedVo();
            vo.setRepoPath(dst);
            vo.setEditRepoPath(editRepoPath);

            NiordWebException ex = assertThrows(NiordWebException.class,
                    () -> repo.updateRepoFolderFromTempEditFolder(vo),
                    "an edit folder outside the temp root must be refused: " + editRepoPath);
            assertEquals(403, ex.getStatusCode().value());
            assertFalse(store.hasAny(dst), "nothing may be copied from " + editRepoPath);
        }
    }

    @Test
    void updateRepoFolderRefusesADestinationOutsideTheRepositoryOrInsideTemp() {
        String intoTemp = track("temp/it-sync-dst-" + System.nanoTime());
        String edit = tempEditFolder();

        for (String repoPath : List.of("../escaped-" + System.nanoTime(), intoTemp, "it-sync-x/..", "/etc")) {
            TestRepoBackedVo vo = new TestRepoBackedVo();
            vo.setRepoPath(repoPath);
            vo.setEditRepoPath(edit);

            NiordWebException ex = assertThrows(NiordWebException.class,
                    () -> repo.updateRepoFolderFromTempEditFolder(vo),
                    "a destination that is not a committed repository folder must be refused: " + repoPath);
            assertEquals(403, ex.getStatusCode().value());
        }
        assertFalse(store.hasAny(intoTemp), "nothing may be copied into another temp folder");
        assertFalse(store.exists("attachment.txt"), "nothing may be copied into the repository root itself");
    }

    @Test
    void updateRepoFolderIsANoOpWhenTheEditFolderIsBlank() {
        TestRepoBackedVo vo = new TestRepoBackedVo();
        vo.setRepoPath("../it-never-" + System.nanoTime());
        vo.setEditRepoPath(" ");

        // No path is looked at when there is nothing to sync, so not even a bad destination is an error
        repo.updateRepoFolderFromTempEditFolder(vo);
    }

    @Test
    void updateRepoFolderCopiesTheWholeEditFolderIntoANewFolder() {
        String edit = tempEditFolder();
        String dst = track("messages/it-sync-new-" + System.nanoTime());
        TestRepoBackedVo vo = new TestRepoBackedVo();
        vo.setRepoPath(dst);
        vo.setEditRepoPath(edit);

        repo.updateRepoFolderFromTempEditFolder(vo);

        Optional<RepoFileInfo> copied = store.stat(dst + "/1/attachment.txt");
        assertTrue(copied.isPresent(), "a new folder gets every file of the edit folder");
        assertNull(copied.get().expires(), "a committed copy does not expire");
        assertEquals(dst + "/1", copied.get().folder(), "the folder column is recomputed for the new path");
        assertTrue(store.exists(edit + "/1/attachment.txt"), "the edit folder's rows stay until they expire");
    }

    @Test
    void updateRepoFolderCopiesOnlyTheRevisionFolderIntoAnExistingFolder() {
        String edit = tempEditFolder();
        store.write(edit + "/0/older.txt", bytes("an older revision"), now());
        String dst = track("messages/it-sync-existing-" + System.nanoTime());
        store.write(dst + "/0/committed.txt", bytes("already there"), now());
        TestRepoBackedVo vo = new TestRepoBackedVo();
        vo.setRepoPath(dst);
        vo.setEditRepoPath(edit);

        repo.updateRepoFolderFromTempEditFolder(vo);

        assertTrue(store.exists(dst + "/1/attachment.txt"), "the revision's files are copied");
        assertFalse(store.exists(dst + "/0/older.txt"), "another revision's files are not");
        assertTrue(store.exists(dst + "/0/committed.txt"), "what the folder held stays");
    }

    @Test
    void updateRepoFolderReplacesAFileAtTheSamePathLastWriteWins() {
        String edit = tempEditFolder();
        String dst = track("messages/it-sync-replace-" + System.nanoTime());
        store.write(dst + "/1/attachment.txt", bytes("the first save"), now());
        TestRepoBackedVo vo = new TestRepoBackedVo();
        vo.setRepoPath(dst);
        vo.setEditRepoPath(edit);

        repo.updateRepoFolderFromTempEditFolder(vo);

        assertEquals("uploaded while editing", RepoRows.readText(store, dst + "/1/attachment.txt"),
                "a same-named file is replaced, as the copy overwrote a file");
    }

    @Test
    void twoOverlappingSavesIntoOneRevisionFolderBothSucceed() throws Exception {
        String dst = track("messages/it-sync-overlap-" + System.nanoTime());
        String editA = tempEditFolder();
        String editB = tempEditFolder();
        store.write(editB + "/1/attachment.txt", bytes("the second editor's upload"), now());

        // Both sessions save into the same revision folder at the same time. Under READ COMMITTED a delete
        // followed by an insert would end in a primary-key violation for the second; the upsert ends in
        // last-write-wins, as the copy on disk did.
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> saves = new ArrayList<>();
            for (String edit : List.of(editA, editB)) {
                saves.add(pool.submit(() -> {
                    TestRepoBackedVo vo = new TestRepoBackedVo();
                    vo.setRepoPath(dst);
                    vo.setEditRepoPath(edit);
                    start.await();
                    repo.updateRepoFolderFromTempEditFolder(vo);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> save : saves) {
                save.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        String content = RepoRows.readText(store, dst + "/1/attachment.txt");
        assertTrue(content.equals("uploaded while editing") || content.equals("the second editor's upload"),
                "one of the two saves wins, and neither fails: " + content);
    }

    // ------------------------------------------------------------------------------------------- //
    // createTempEditRepoFolder
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createTempEditRepoFolderAssignsTempEditPath() {
        TestRepoBackedVo vo = new TestRepoBackedVo();
        assertNull(vo.getEditRepoPath());

        repo.createTempEditRepoFolder(vo, false);

        String editPath = vo.getEditRepoPath();
        assertNotNull(editPath, "createTempEditRepoFolder must assign an edit repo path");
        assertTrue(editPath.startsWith("temp/"),
                "the edit repo path must be a temp sub-folder: " + editPath);
        // copyToTemp=false: no copy and no rewrite must take place.
        assertFalse(vo.rewriteInvoked,
                "rewriteRepoPath must NOT be invoked when copyToTemp=false");
    }

    @Test
    void createTempEditRepoFolderAssignsDistinctPathsPerCall() {
        TestRepoBackedVo first = new TestRepoBackedVo();
        TestRepoBackedVo second = new TestRepoBackedVo();

        repo.createTempEditRepoFolder(first, false);
        repo.createTempEditRepoFolder(second, false);

        assertNotEquals(first.getEditRepoPath(), second.getEditRepoPath(),
                "each value object must receive its own unique temp edit folder");
    }

    @Test
    void createTempEditRepoFolderCopiesTheFolderIntoTempWhenAsked() {
        String src = track("messages/it-copy-src-" + System.nanoTime());
        Instant old = now().minus(30, ChronoUnit.DAYS);
        store.write(src + "/1/old.txt", bytes("an old attachment"), old);
        TestRepoBackedVo vo = new TestRepoBackedVo();
        vo.setRepoPath(src);

        repo.createTempEditRepoFolder(vo, true);

        track(vo.getEditRepoPath());
        Optional<RepoFileInfo> copy = store.stat(vo.getEditRepoPath() + "/1/old.txt");
        assertTrue(copy.isPresent(), "the folder's files are copied into the edit folder");
        assertEquals(old, copy.get().updated(), "a copy keeps its file's time");
        assertNotNull(copy.get().expires(), "the copy expires with the edit session, whatever its file's time");
        assertTrue(copy.get().expires().isAfter(Instant.now()), "the copy expires a day from now, not in the past");
        assertTrue(vo.rewriteInvoked, "the links are pointed at the edit folder");
    }

    // ------------------------------------------------------------------------------------------- //
    // cleanUpTempRoot - the @Scheduled(cron="50 22 * * * ?") sweep, invoked directly
    // ------------------------------------------------------------------------------------------- //

    @Test
    void cleanUpTempRootDeletesExpiredRowsAndKeepsTheRest() {
        String folder = track("temp/it-clean-" + System.nanoTime());
        store.write(folder + "/old.txt", bytes("old"), now());
        store.write(folder + "/young.txt", bytes("young"), now());
        String committed = track("messages/it-clean-" + System.nanoTime());
        store.write(committed + "/kept.txt", bytes("kept"), now().minus(400, ChronoUnit.DAYS));
        // A temp row expires a day after its write: an hour ago is the clock moved, not an expiry a caller chose
        jdbc.update("UPDATE \"RepoFile\" SET expires = now() - interval '1 hour' WHERE path = ?", folder + "/old.txt");

        repo.cleanUpTempRoot();

        assertFalse(store.exists(folder + "/old.txt"), "an expired temp row is deleted");
        assertTrue(store.exists(folder + "/young.txt"), "a temp row that has not expired is kept");
        assertTrue(store.exists(committed + "/kept.txt"), "a committed row never expires, whatever its time");
    }

    // ------------------------------------------------------------------------------------------- //
    // RepoStore: the upsert, the whole-row read and the folder operations
    // ------------------------------------------------------------------------------------------- //

    @Test
    void writeIsAnUpsertThatKeepsCreatedAndReplacesTheRest() {
        String folder = track("messages/it-store-" + System.nanoTime());
        String path = folder + "/file.txt";
        Instant first = now().minus(2, ChronoUnit.HOURS);
        Instant second = now();

        RepoFileInfo a = store.write(path, bytes("first"), first);
        RepoFileInfo b = store.write(path, bytes("second, longer"), second);

        assertEquals(5L, a.size());
        assertEquals(14L, b.size());
        assertEquals(a.created(), b.created(), "created is kept across a replacement");
        assertEquals(second, b.updated(), "updated is the new write's time");
        assertEquals(folder, b.folder());
        assertEquals("file.txt", b.name());
        assertEquals(64, b.sha256().length(), "the hash is computed as the bytes stream through");
        assertNotEquals(a.sha256(), b.sha256());
        assertEquals("second, longer", RepoRows.readText(store, path));
    }

    @Test
    void readAllReadsTheWholeFileInOneQuery() throws IOException {
        String folder = track("messages/it-read-" + System.nanoTime());
        byte[] big = new byte[3 * (1 << 20) + 17];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) (i * 31);
        }
        RepoFileInfo file = store.write(folder + "/big.bin", big, now());

        assertArrayEquals(big, store.readAll(file), "the whole file, in one read");
    }

    @Test
    void aReadOfAReplacedRowEndsShort() throws IOException {
        String folder = track("messages/it-replaced-" + System.nanoTime());
        RepoFileInfo before = store.write(folder + "/file.txt", bytes("the first version"), now().minus(1, ChronoUnit.HOURS));
        store.write(folder + "/file.txt", bytes("the second version"), now());

        assertThrows(IOException.class, () -> store.readAll(before), "the pinned time no longer matches a row");
        assertEquals("the second version", RepoRows.readText(store, folder + "/file.txt"), "the row as it is now");
    }

    /** A file above the cap is refused before a byte is sent: a download holds a file whole */
    @Test
    void aFileAboveTheCapIsRefused() {
        String folder = track("messages/it-cap-" + System.nanoTime());
        assertThrows(IllegalArgumentException.class,
                () -> store.write(folder + "/huge.bin", InputStream.nullInputStream(), RepoStore.MAX_SIZE + 1, now()));
        assertFalse(store.hasAny(folder));
    }

    @Test
    void copyFolderCopiesTheSubtreeAndDeleteFolderRemovesIt() {
        String from = track("messages/it-copy-from-" + System.nanoTime());
        String to = track("messages/it-copy-to-" + System.nanoTime());
        store.write(from + "/a.txt", bytes("a"), now());
        store.write(from + "/1/b.txt", bytes("b"), now());
        store.write(from + "-sibling/c.txt", bytes("not below from"), now());
        track(from + "-sibling");

        int copied = store.copyFolder(from, to);

        assertEquals(2, copied, "the rows strictly below the folder, not its sibling with the same prefix");
        assertEquals(to, store.stat(to + "/a.txt").orElseThrow().folder());
        assertEquals(to + "/1", store.stat(to + "/1/b.txt").orElseThrow().folder());
        assertFalse(store.exists(to + "-sibling/c.txt"));

        assertEquals(2, store.deleteFolder(from));
        assertFalse(store.hasAny(from));
        assertTrue(store.exists(from + "-sibling/c.txt"), "a prefix match stops at the folder's slash");
    }

    /** The store keys every row on the normalised path, so a caller's spelling and a request's meet at one row */
    @Test
    void theStoreKeysEveryPathNormalised() {
        String folder = track("messages/it-key-" + System.nanoTime());
        RepoFileInfo written = store.write(folder + "//1/./a.pdf", bytes("a"), now());

        assertEquals(folder + "/1/a.pdf", written.path());
        assertTrue(store.exists(folder + "/1/a.pdf"));
        assertTrue(store.exists(folder + "/1//a.pdf"), "a lookup normalises as the write did");
        assertFalse(store.exists("/" + folder + "/1/a.pdf"), "an absolute path names no row");
        assertFalse(store.exists(null));
        assertEquals(1, store.listTree(folder + "/").size(), "a folder with a trailing slash is the folder");
        assertThrows(IllegalArgumentException.class, () -> store.write("../outside.txt", bytes("x"), now()),
                "a path that climbs out of the repository is refused, not written somewhere");
    }

    /** The archive's copy never replaces a row: a second edition at the same stamp is refused, not overwritten */
    @Test
    void copyFileNeverReplacesTheTarget() {
        String folder = track("messages/it-copy-" + System.nanoTime());
        store.write(folder + "/a.pdf", bytes("first"), now());
        store.write(folder + "/b.pdf", bytes("second"), now());

        assertTrue(store.copyFile(folder + "/a.pdf", folder + "/archived.pdf"));
        assertFalse(store.copyFile(folder + "/b.pdf", folder + "/archived.pdf"), "a row at the target: no copy");
        assertEquals("first", RepoRows.readText(store, folder + "/archived.pdf"));
        assertFalse(store.copyFile(folder + "/missing.pdf", folder + "/other.pdf"), "no source: no copy");
    }

    /** The upload's name is the database's decision: a second write at a taken path writes nothing */
    @Test
    void writeNewLeavesARowAtThePathAlone() {
        String folder = track("temp/it-new-" + System.nanoTime());
        assertTrue(store.writeNew(folder + "/chart.png", new ByteArrayInputStream(bytes("first")), 5, now()).isPresent());
        assertTrue(store.writeNew(folder + "/chart.png", new ByteArrayInputStream(bytes("second")), 6, now()).isEmpty(),
                "the path has a row: nothing is written");
        assertEquals("first", RepoRows.readText(store, folder + "/chart.png"));
    }

    /**
     * A thumbnail is named by its row's content: a file written again under its name gets another thumbnail, and
     * the old one goes with the row
     */
    @Test
    void aThumbnailIsItsContentsAndGoesWithTheRow() throws Exception {
        String folder = track("messages/it-thumb-" + System.nanoTime());
        String path = folder + "/picture.png";
        store.write(path, png(200, 200, java.awt.Color.RED), now());

        Path first = thumbnailFile(path);
        assertTrue(Files.isRegularFile(first), "the thumbnail is drawn into the cache tree");
        assertTrue(first.getFileName().toString().matches("picture_thumb_64_[0-9a-f]{16}\\.png"),
                "named by the content's hash: " + first.getFileName());

        store.write(path, png(200, 100, java.awt.Color.BLUE), now());
        Path second = thumbnailFile(path);
        assertNotEquals(first, second, "other bytes, another thumbnail");
        assertEquals(32, javax.imageio.ImageIO.read(second.toFile()).getHeight(), "drawn from the new bytes");

        // What the delete endpoint does for a row: the row, then every thumbnail of the path, whatever its content
        store.delete(path);
        thumbnails.deleteThumbnails(path);
        assertFalse(Files.exists(first) || Files.exists(second), "a deleted row's thumbnails go with it");
    }

    /**
     * An image no reader decodes is refused for good: a marker beside its thumbnail's place says so, and the row
     * is not read and decoded again on the next request. (A failure of the drawing itself leaves no marker: see
     * ThumbnailServiceCreateThumbnailTest.)
     */
    @Test
    void anUndecodableImageLeavesARefusalMarker() throws Exception {
        String folder = track("messages/it-refused-" + System.nanoTime());
        String path = folder + "/notes.png";
        RepoFileInfo file = store.write(path, bytes("not an image"), now());
        Path thumb = cache.thumbnailFor(file, IconSize.SIZE_64);
        Path marker = thumb.resolveSibling(thumb.getFileName() + ".refused");

        // The icon answer is a redirect built from the current request, which the holder supplies outside a servlet
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/rest/repo/thumb");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        ResponseEntity<?> response;
        try {
            response = repo.getThumbnail("/" + path, 64, request, new MockHttpServletResponse());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        assertNotEquals(200, response.getStatusCode().value(), "no thumbnail of a file no reader decodes");
        assertFalse(Files.exists(thumb), "nothing is drawn");
        assertTrue(Files.isRegularFile(marker), "the refusal is remembered beside the thumbnail's place: " + marker);
        assertNull(thumbnails.createThumbnail(file, "image/png", IconSize.SIZE_64),
                "the marker answers the next request");
    }

    /** The thumbnail the endpoint serves for the path, as a file of the cache tree */
    private Path thumbnailFile(String path) throws Exception {
        ResponseEntity<?> response = repo.getThumbnail("/" + path, 64,
                new MockHttpServletRequest("GET", "/rest/repo/thumb"), new MockHttpServletResponse());
        assertEquals(200, response.getStatusCode().value(), "the thumbnail is served, not the icon");
        return ((org.springframework.core.io.FileSystemResource) response.getBody()).getFile().toPath();
    }

    private static byte[] png(int w, int h, java.awt.Color color) throws IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, w, h);
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void likePrefixEscapesTheWildcards() {
        assertEquals("temp/a!_b!%c/%", RepoPaths.likePrefix("temp/a_b%c"));
        String folder = track("temp/it-like-" + System.nanoTime());
        store.write(folder + "_x/file.txt", bytes("under the underscore sibling"), now());
        track(folder + "_x");

        assertFalse(store.hasAny(folder), "an underscore in the folder is a character, not a wildcard");
    }


    /**
     * Minimal {@link IRepoBackedVo} for exercising {@link RepositoryService#createTempEditRepoFolder}.
     * rewriteRepoPath only records that it was called, so tests can assert it is skipped when
     * copyToTemp=false.
     */
    private static final class TestRepoBackedVo implements IRepoBackedVo {
        private String repoPath;
        private String editRepoPath;
        private int revision = 1;
        private boolean rewriteInvoked;

        @Override
        public int getRevision() {
            return revision;
        }

        @Override
        public String getRepoPath() {
            return repoPath;
        }

        public void setRepoPath(String repoPath) {
            this.repoPath = repoPath;
        }

        @Override
        public String getEditRepoPath() {
            return editRepoPath;
        }

        @Override
        public void setEditRepoPath(String editRepoPath) {
            this.editRepoPath = editRepoPath;
        }

        @Override
        public void rewriteRepoPath(String repoPath1, String repoPath2) {
            this.rewriteInvoked = true;
        }
    }
}
