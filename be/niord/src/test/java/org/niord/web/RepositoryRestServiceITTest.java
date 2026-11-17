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
package org.niord.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.repo.RepositoryService;
import org.niord.test.ITTest;

import org.niord.core.repo.RepoStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Integration tests for {@link org.niord.core.repo.RepositoryService}: the HTTP contract of the
 * {@code /rest/repo} endpoints, including authorization.
 *
 * <p>The service is mapped under {@code /rest/repo}. The reads/utility endpoints
 * ({@code GET /file}, {@code GET /thumb}, {@code GET /list}, {@code GET /new-temp-dir}) are
 * {@code @PermitAll}; the mutating endpoints ({@code DELETE /file}, {@code POST /upload},
 * {@code POST /upload-temp}) are {@code @RolesAllowed(EDITOR)}, and all three are confined to the
 * repository's "temp" root: a folder outside it is refused with 403 even for an editor. A bare
 * {@code sysadmin} token (composite role, includes {@code editor}) satisfies the role check, with no
 * {@code NiordDomain} header required.
 *
 * <p>A streamed file carries {@code nosniff}, every file but a PDF {@code Content-Security-Policy: sandbox},
 * and every file but a raster image, PDF, audio or video an attachment disposition, so an uploaded file never
 * runs as active content on the backend origin. A 404 is plain text and never echoes the requested path.
 *
 * <p>Uploads are committed to the server's repository file-system (HTTP requests run in the server's
 * own transaction/IO, which {@code @Transactional} cannot roll back). Created files are therefore
 * tracked and deleted in {@link #cleanup()} using the editor token. A file a test needs in a committed
 * folder, which no endpoint writes any more, is placed on disk through the {@link RepositoryService} bean.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class RepositoryRestServiceITTest extends ITTest {

    @Autowired
    RepositoryService repositoryService;

    @Autowired
    RepoStore store;

    /** Repo-relative file paths (e.g. {@code temp/<uuid>/file.txt}) created by tests, for cleanup. */
    private final List<String> createdRepoFilePaths = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String repoPath : createdRepoFilePaths) {
            try {
                given().auth().oauth2(accessToken())
                        .when().delete("/rest/repo/file/" + repoPath);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdRepoFilePaths.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // @PermitAll reads — no token
    // ------------------------------------------------------------------------------------------- //

    @Test
    void streamMissingFileReturns404() {
        String missing = "no-such-folder-" + System.nanoTime() + "/missing-" + System.nanoTime() + ".txt";
        given()
                .when().get("/rest/repo/file/" + missing)
                .then().statusCode(404);
    }

    @Test
    void listOfNonExistentFolderReturnsEmptyArray() {
        String missingFolder = "no-such-folder-" + System.nanoTime();
        given()
                .when().get("/rest/repo/list/" + missingFolder)
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void newTempDirDescribesAnUncreatedTempDirectory() {
        given()
                .when().get("/rest/repo/new-temp-dir")
                .then().statusCode(200)
                .body("directory", is(true))
                .body("name", not(is("")))
                .body("path", startsWith("temp"));
    }

    @Test
    void newTempDirReturnsAUniquePathEachCall() {
        String path1 = given().when().get("/rest/repo/new-temp-dir")
                .then().statusCode(200).extract().path("path");
        String path2 = given().when().get("/rest/repo/new-temp-dir")
                .then().statusCode(200).extract().path("path");

        // Each invocation mints a fresh UUID-based directory name.
        org.junit.jupiter.api.Assertions.assertNotEquals(path1, path2);
    }

    // ------------------------------------------------------------------------------------------- //
    // Negative security — protected mutations without a token return 401
    // ------------------------------------------------------------------------------------------- //

    @Test
    void deleteFileWithoutTokenIsUnauthorized() {
        given()
                .when().delete("/rest/repo/file/anything-" + System.nanoTime() + ".txt")
                .then().statusCode(401);
    }

    @Test
    void uploadWithoutTokenIsUnauthorized() {
        // A multipart part is included so the request body parses and reaches the auth layer
        // (rather than failing 415 before authentication is evaluated).
        given()
                .multiPart("file", "x.txt", "hi".getBytes(StandardCharsets.UTF_8))
                .when().post("/rest/repo/upload/anyfolder-" + System.nanoTime())
                .then().statusCode(401);
    }

    @Test
    void uploadTempWithoutTokenIsUnauthorized() {
        given()
                .multiPart("file", "x.txt", "hi".getBytes(StandardCharsets.UTF_8))
                .when().post("/rest/repo/upload-temp/temp/anyfolder-" + System.nanoTime())
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // Security — editor token, but a non-temp folder is rejected by validateTempRepoPath (403)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void uploadTempToNonTempFolderIsForbidden() {
        // validateTempRepoPath() throws NiordWebException(403) for any folder that does not
        // resolve under the repo "temp" root, even for an authenticated editor.
        String nonTempFolder = "notatemp-" + System.nanoTime();
        given().auth().oauth2(accessToken())
                .multiPart("file", "x.txt", "hi".getBytes(StandardCharsets.UTF_8))
                .when().post("/rest/repo/upload-temp/" + nonTempFolder)
                .then().statusCode(403);
    }

    @Test
    void deleteFileOutsideTempRootIsForbiddenAndLeavesFileIntact() throws Exception {
        // SECAUD-3 guard: DELETE /rest/repo/file is EDITOR-only, but the editor role is per-domain and
        // the repository is not domain-partitioned. Without a temp-root scope an editor could delete any
        // file — including another domain's committed message attachments. deleteFile now routes through
        // validateTempRepoPath(), so a delete outside the "temp" root is rejected with 403 and the file
        // is left untouched. (Legitimate temp-scoped deletes are covered by
        // uploadListStreamAndDeleteTempFileRoundTrip.)
        String folder = "messages/it-nontemp-" + System.nanoTime();
        String fileName = "keep-" + System.nanoTime() + ".txt";
        String expectedContent = "must-survive-the-delete-attempt";
        String storedRepoPath = folder + "/" + fileName;

        // No endpoint writes into a committed folder any more (POST /upload is confined to the temp root
        // too), so the committed file is written to the store directly, under a served root.
        try {
            store.write(storedRepoPath,
                    expectedContent.getBytes(StandardCharsets.UTF_8), java.time.Instant.now());

            // Attempting to delete it as an authenticated editor is rejected — not under the temp root.
            given().auth().oauth2(accessToken())
                    .when().delete("/rest/repo/file/" + storedRepoPath)
                    .then().statusCode(403);

            // The file is still there and unchanged.
            given()
                    .when().get("/rest/repo/file/" + storedRepoPath)
                    .then().statusCode(200)
                    .body(equalTo(expectedContent));
        } finally {
            store.deleteFolder(folder);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Positive end-to-end round trip — editor token
    // ------------------------------------------------------------------------------------------- //

    @Test
    void uploadListStreamAndDeleteTempFileRoundTrip() {
        // 1) Mint a fresh temp directory path: "temp/<uuid>".
        String tempPath = given()
                .when().get("/rest/repo/new-temp-dir")
                .then().statusCode(200)
                .body("path", startsWith("temp/"))
                .extract().path("path");

        String fileName = "itfile-" + System.nanoTime() + ".txt";
        byte[] content = ("repo-it-content-" + System.nanoTime()).getBytes(StandardCharsets.UTF_8);
        String expectedContent = new String(content, StandardCharsets.UTF_8);

        // 2) Upload into the temp folder. The endpoint returns the repo-relative stored path(s).
        Response uploadResp = given().auth().oauth2(accessToken())
                .multiPart("file", fileName, content)
                .when().post("/rest/repo/upload-temp/" + tempPath)
                .then().statusCode(200)
                // The stored path lives under the requested temp folder...
                .body("$", hasItem(startsWith(tempPath + "/")))
                // ...and (this is the file we sent, with a unique name) ends with our file name.
                .body("$", hasItem(endsWith("/" + fileName)))
                .extract().response();

        List<String> stored = uploadResp.jsonPath().getList("$");
        org.junit.jupiter.api.Assertions.assertEquals(1, stored.size(),
                "exactly one stored path expected for a single uploaded part");
        String storedRepoPath = stored.get(0);
        createdRepoFilePaths.add(storedRepoPath);

        // The stored file name (last segment) — getUniqueFile may have renamed it, so derive it.
        String storedFileName = storedRepoPath.substring(storedRepoPath.lastIndexOf('/') + 1);
        // For a fresh unique folder there is no collision, so the name is preserved verbatim.
        org.junit.jupiter.api.Assertions.assertEquals(fileName, storedFileName);

        // 3) The directory listing now contains exactly our file.
        given()
                .when().get("/rest/repo/list/" + tempPath)
                .then().statusCode(200)
                .body("size()", is(1))
                .body("name", hasItem(storedFileName))
                .body("find { it.name == '" + storedFileName + "' }.directory", is(false));

        // 4) Streaming the file returns its exact bytes.
        given()
                .when().get("/rest/repo/file/" + storedRepoPath)
                .then().statusCode(200)
                .body(equalTo(expectedContent));

        // 5) Deleting the file succeeds (200), and a follow-up read is 404.
        given().auth().oauth2(accessToken())
                .when().delete("/rest/repo/file/" + storedRepoPath)
                .then().statusCode(200);

        given()
                .when().get("/rest/repo/file/" + storedRepoPath)
                .then().statusCode(404);

        // Already deleted — drop it so @AfterEach does not attempt a (404) second delete.
        createdRepoFilePaths.remove(storedRepoPath);
    }

    @Test
    void deleteOfMissingFileWithTokenReturns404() {
        // deleteFile() returns 404 (not 401/403) for an authenticated editor when the file is absent.
        String missing = "temp/no-such-" + System.nanoTime() + "/missing-" + System.nanoTime() + ".txt";
        given().auth().oauth2(accessToken())
                .when().delete("/rest/repo/file/" + missing)
                .then().statusCode(404);
    }

    @Test
    void uploadTwiceIntoSameFolderYieldsDistinctStoredNames() {
        // getUniqueFile() de-duplicates within a folder: the second upload of the same file name is
        // stored under a numbered variant ("name 2.ext"), so both files coexist.
        String tempPath = given()
                .when().get("/rest/repo/new-temp-dir")
                .then().statusCode(200)
                .extract().path("path");

        String fileName = "dup-" + System.nanoTime() + ".txt";
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second".getBytes(StandardCharsets.UTF_8);

        String firstStored = given().auth().oauth2(accessToken())
                .multiPart("file", fileName, first)
                .when().post("/rest/repo/upload-temp/" + tempPath)
                .then().statusCode(200)
                .extract().jsonPath().getList("$").get(0).toString();
        createdRepoFilePaths.add(firstStored);

        String secondStored = given().auth().oauth2(accessToken())
                .multiPart("file", fileName, second)
                .when().post("/rest/repo/upload-temp/" + tempPath)
                .then().statusCode(200)
                .extract().jsonPath().getList("$").get(0).toString();
        createdRepoFilePaths.add(secondStored);

        // The two uploads are stored under different repo paths (no overwrite).
        org.junit.jupiter.api.Assertions.assertNotEquals(firstStored, secondStored);

        // Both are present in the listing, and the originals are independently retrievable.
        given()
                .when().get("/rest/repo/list/" + tempPath)
                .then().statusCode(200)
                .body("size()", is(2));

        given().when().get("/rest/repo/file/" + firstStored)
                .then().statusCode(200).body(equalTo("first"));
        given().when().get("/rest/repo/file/" + secondStored)
                .then().statusCode(200).body(equalTo("second"));
    }

    @Test
    void uploadToNonTempFolderIsForbidden() {
        // The general /upload endpoint used to write into any folder, so an editor of one domain could add
        // files to another domain's committed message and publication folders (the repository is not
        // domain-partitioned). It is now confined to the temp root, like /upload-temp, and a committed
        // folder is refused with 403 before anything is written. (This test replaces one that pinned the
        // old, unconfined behaviour.)
        String folder = "it-repo-upload-" + System.nanoTime();

        given().auth().oauth2(accessToken("editor"))
                .multiPart("file", "general-" + System.nanoTime() + ".txt",
                        "general-upload-body".getBytes(StandardCharsets.UTF_8))
                .when().post("/rest/repo/upload/" + folder)
                .then().statusCode(403);

        // Nothing was written: the folder lists empty.
        given()
                .when().get("/rest/repo/list/" + folder)
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void uploadThroughATempTraversalIsForbidden() {
        // A folder that starts under temp but climbs out of it is normalised before the check, so it is
        // refused like any other folder outside the temp root.
        String escaped = "it-repo-escape-" + System.nanoTime();

        given().auth().oauth2(accessToken("editor"))
                .multiPart("file", "x.txt", "hi".getBytes(StandardCharsets.UTF_8))
                .when().post("/rest/repo/upload/temp/../" + escaped)
                .then().statusCode(anyOf(is(400), is(403)));

        assertFalse(store.hasAny(escaped) || store.exists(escaped),
                "nothing may be written outside the temp root");
    }

    // ------------------------------------------------------------------------------------------- //
    // A repository file never runs as active content on the backend origin, and a 404 never echoes the
    // requested path. The files are placed on disk directly, so the tests do not depend on the upload.
    // ------------------------------------------------------------------------------------------- //

    /** Writes a file into a fresh temp folder of the store and returns its repo-relative path. */
    private String writeTempFile(String fileName, byte[] content) throws Exception {
        String folder = "temp/it-headers-" + System.nanoTime();
        String stored = folder + "/" + fileName;
        java.time.Instant now = java.time.Instant.now();
        store.write(stored, content, now);
        createdRepoFilePaths.add(stored);
        return stored;
    }

    @Test
    void streamFile_htmlIsASandboxedAttachment() throws Exception {
        String stored = writeTempFile("page.html",
                "<p>an uploaded page</p>".getBytes(StandardCharsets.UTF_8));

        given()
                .when().get("/rest/repo/file/" + stored)
                .then().statusCode(200)
                .contentType(containsString("text/html"))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .header("Content-Disposition", startsWith("attachment"));
    }

    @Test
    void streamFile_imageIsInlineButSandboxed() throws Exception {
        String stored = writeTempFile("chart.png", new byte[]{(byte) 0x89, 'P', 'N', 'G'});

        given()
                .when().get("/rest/repo/file/" + stored)
                .then().statusCode(200)
                .contentType(containsString("image/png"))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox")
                .header("Content-Disposition", startsWith("inline"));
    }

    @Test
    void streamFile_pdfIsInlineAndNotSandboxed() throws Exception {
        String stored = writeTempFile("notice.pdf", "%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.UTF_8));

        given()
                .when().get("/rest/repo/file/" + stored)
                .then().statusCode(200)
                .contentType(containsString("application/pdf"))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", nullValue())
                .header("Content-Disposition", startsWith("inline"));
    }

    @Test
    void streamFile_notModifiedCarriesTheSameSecurityHeaders() throws Exception {
        String stored = writeTempFile("page2.html", "<p>x</p>".getBytes(StandardCharsets.UTF_8));
        String etag = given().when().get("/rest/repo/file/" + stored)
                .then().statusCode(200).extract().header("ETag");

        given()
                .header("If-None-Match", etag)
                .when().get("/rest/repo/file/" + stored)
                .then().statusCode(304)
                .header("Content-Security-Policy", "sandbox")
                .header("Content-Disposition", startsWith("attachment"));
    }

    @Test
    void streamMissingFileAnswersPlainTextWithoutEchoingThePath() {
        String marker = "it-missing-marker-" + System.nanoTime();

        String body = given()
                .header("Accept", "text/html")
                .when().get("/rest/repo/file/" + marker + "/" + marker + ".html")
                .then().statusCode(404)
                .contentType(containsString("text/plain"))
                .header("X-Content-Type-Options", "nosniff")
                .extract().asString();

        org.junit.jupiter.api.Assertions.assertFalse(body.contains(marker),
                "the 404 body must not echo the requested path, was: " + body);
    }

    @Test
    void thumbnailOfMissingFileAnswersPlainTextWithoutEchoingThePath() {
        String marker = "it-missing-thumb-" + System.nanoTime();

        String body = given()
                .header("Accept", "text/html")
                .when().get("/rest/repo/thumb/" + marker + ".png")
                .then().statusCode(404)
                .contentType(containsString("text/plain"))
                .extract().asString();

        org.junit.jupiter.api.Assertions.assertFalse(body.contains(marker),
                "the 404 body must not echo the requested path, was: " + body);
    }

    // ------------------------------------------------------------------------------------------- //
    // Characterization tests for the Jersey→Spring-MVC migration. These pin behaviors the framework
    // currently mediates (weak-ETag conditional GET, Content-Type/Expires headers, a 307 Location
    // redirect, and the multi-segment {file:.+} path capture) so a future rewrite to @RestController
    // / ResponseEntity / ServletWebRequest.checkNotModified can be verified behavior-preserving.
    // See docs/framework-coupled-tests.md.
    // ------------------------------------------------------------------------------------------- //

    /** Uploads {@code content} as {@code fileName} into a fresh {@code temp/<uuid>} dir and returns the
     *  repo-relative stored path, registering it for {@link #cleanup()}. */
    private String uploadTempFile(String fileName, byte[] content) {
        String tempPath = given()
                .when().get("/rest/repo/new-temp-dir")
                .then().statusCode(200)
                .extract().path("path");
        String stored = given().auth().oauth2(accessToken())
                .multiPart("file", fileName, content)
                .when().post("/rest/repo/upload-temp/" + tempPath)
                .then().statusCode(200)
                .extract().jsonPath().getList("$").get(0).toString();
        createdRepoFilePaths.add(stored);
        return stored;
    }

    @Test
    void streamFile_carriesContentTypeExpiresAndWeakEtag() {
        String stored = uploadTempFile("etag-" + System.nanoTime() + ".txt",
                "etag-body".getBytes(StandardCharsets.UTF_8));

        given()
                .when().get("/rest/repo/file/" + stored)
                .then().statusCode(200)
                // Content type is derived from the file extension by FileTypes.getContentType.
                .contentType(containsString("text/plain"))
                // streamFile sets an Expires header (Response.expires(...)) — the actual cache directive
                // the current stack emits (the RESTEasy @NoCache annotations elsewhere are inert under Jersey).
                .header("Expires", notNullValue())
                // The validator is a WEAK ETag of the form W/"<lastModifiedMillis>_<size>"; the MVC port
                // (ServletWebRequest.checkNotModified) must reproduce this exact wire format.
                .header("ETag", matchesPattern("W/\"\\d+_\\d+\""));
    }

    @Test
    void streamFile_conditionalGetWithMatchingEtagReturns304AndNoBody() {
        String stored = uploadTempFile("cond-" + System.nanoTime() + ".txt",
                "conditional-body".getBytes(StandardCharsets.UTF_8));

        String etag = given()
                .when().get("/rest/repo/file/" + stored)
                .then().statusCode(200)
                .extract().header("ETag");

        // Re-requesting with the captured ETag in If-None-Match yields 304 Not Modified with no body —
        // the conditional-GET contract evaluatePreconditions() implements today.
        String body = given()
                .header("If-None-Match", etag)
                .when().get("/rest/repo/file/" + stored)
                .then().statusCode(304)
                .header("Expires", notNullValue())
                .extract().asString();
        org.junit.jupiter.api.Assertions.assertEquals("", body,
                "304 Not Modified must carry no entity body");
    }

    @Test
    void streamFile_conditionalGetWithNonMatchingEtagReturns200WithBody() {
        String stored = uploadTempFile("cond2-" + System.nanoTime() + ".txt",
                "fresh-body".getBytes(StandardCharsets.UTF_8));

        // A non-matching If-None-Match precondition does NOT short-circuit: the full 200 body is served.
        given()
                .header("If-None-Match", "W/\"0_0\"")
                .when().get("/rest/repo/file/" + stored)
                .then().statusCode(200)
                .body(equalTo("fresh-body"));
    }

    @Test
    void streamFile_multiSegmentAndMultiDotPathRoundTrips() {
        // The {file:.+} template must capture a path that spans multiple '/' segments AND a file name
        // with several dots. This is the single highest-risk case for the Spring PathPatternParser port
        // (default {x:.+} is single-segment there; the rewrite must use a {*file} capture-the-rest var).
        String dottedName = "chart.2024.final-" + System.nanoTime() + ".xml";
        byte[] content = "<chart/>".getBytes(StandardCharsets.UTF_8);
        String stored = uploadTempFile(dottedName, content);

        // Sanity: the stored path is genuinely multi-segment (temp/<uuid>/<dotted-name>).
        org.junit.jupiter.api.Assertions.assertTrue(
                stored.chars().filter(c -> c == '/').count() >= 2,
                "expected a multi-segment stored path, got: " + stored);
        org.junit.jupiter.api.Assertions.assertTrue(stored.endsWith("/" + dottedName));

        given()
                .when().get("/rest/repo/file/" + stored)
                .then().statusCode(200)
                // FileTypes.getContentType has no mapping for ".xml", so the repo streams unknown
                // extensions as application/octet-stream — pinned so the MVC port reproduces it.
                .contentType(containsString("octet-stream"))
                .body(equalTo("<chart/>"));
    }

    @Test
    void aListedFileWhoseNameHoldsAnApostropheAndAQuestionMark_streamsByItsListedPath() {
        // REPO4: an upload keeps a name with "'" and "?"; the listing's path (the link the editor uses) writes both
        // encoded, so the link does not end at the "?" (a browser's query string) or at the "'" (Niord's own link
        // reader), and the endpoint decodes it back to the stored name.
        for (String prefix : List.of("it's what?-", "it's-")) {
            String name = prefix + System.nanoTime() + ".txt";
            String stored = uploadTempFile(name, "both".getBytes(StandardCharsets.UTF_8));
            // The cleanup's DELETE would read a raw "?" as a query string; the store deletes it instead
            createdRepoFilePaths.remove(stored);
            try {
                org.junit.jupiter.api.Assertions.assertTrue(stored.endsWith("/" + name), stored);
                String folder = stored.substring(0, stored.lastIndexOf('/'));
                String listed = given()
                        .when().get("/rest/repo/list/" + folder)
                        .then().statusCode(200)
                        .body("size()", is(1))
                        .extract().path("[0].path");
                org.junit.jupiter.api.Assertions.assertEquals(folder + "/"
                        + prefix.replace("'", "%27").replace(" ", "%20").replace("?", "%3F")
                        + name.substring(prefix.length()), listed);
                if (name.contains("?") && !platformFileNameTakes(name)) {
                    // Windows only: RepositoryService.mediaTypeOf reads the name with Paths.get, which refuses a
                    // "?" there, so the stream answers 400 on a Windows workstation (not on Linux, where Niord runs)
                    continue;
                }
                given().urlEncodingEnabled(false)
                        .when().get("/rest/repo/file/" + listed)
                        .then().statusCode(200)
                        .body(equalTo("both"));
            } finally {
                store.delete(stored);
            }
        }
    }

    /** Whether this platform's file paths take the name (Windows refuses "?" in one) */
    private static boolean platformFileNameTakes(String name) {
        try {
            java.nio.file.Paths.get(name);
            return true;
        } catch (java.nio.file.InvalidPathException e) {
            return false;
        }
    }

    @Test
    void getThumbnail_forNonImageRedirectsWithLocationHeader() {
        String stored = uploadTempFile("thumb-" + System.nanoTime() + ".txt",
                "thumb-body".getBytes(StandardCharsets.UTF_8));

        // For a non-image file, getThumbnail falls back to a file-type icon and answers with a
        // temporary redirect (307) carrying the icon URI in the Location header. Disable RestAssured's
        // automatic redirect-following so the redirect itself is observable.
        //
        // NB: the handler builds a RELATIVE target ("../" + icon path), but Jersey resolves and
        // ABSOLUTIZES it against the request URI, so the wire Location is an absolute http URL with the
        // ".." normalized away. A naive Spring MVC port (ResponseEntity.location(URI.create("../..")))
        // would instead emit the relative value — this assertion pins the current absolute contract.
        given().redirects().follow(false)
                .when().get("/rest/repo/thumb/" + stored)
                .then().statusCode(307)
                // Must be an ABSOLUTE URL pointing straight at /img/filetypes/<icon> — NOT the request path
                // with the icon mis-appended (the old "../"-relative redirect resolved to
                // .../repo/thumb/...//img/filetypes/...). This pattern rejects that.
                .header("Location", startsWith("http"))
                .header("Location", matchesPattern("https?://[^/]+/img/filetypes/.*"))
                .header("Location", not(containsString("/thumb")));
    }

    // ------------------------------------------------------------------------------------------- //
    // Zero-segment {*folder} guard: Spring's capture-the-rest matches an empty path (JAX-RS {folder:.+}
    // required a non-empty one), so the migrated endpoints must keep rejecting a blank folder with 404 —
    // in particular POST /rest/repo/upload must not write into the publicly-served repository root.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void uploadToBlankFolderIsNotFound() {
        given().auth().oauth2(accessToken())
                .multiPart("file", "x.txt", "hi".getBytes(StandardCharsets.UTF_8))
                .when().post("/rest/repo/upload")
                .then().statusCode(404);
    }

    @Test
    void listOfBlankFolderIsNotFound() {
        given()
                .when().get("/rest/repo/list")
                .then().statusCode(404);
    }

    @Test
    void uploadTempToBlankFolderIsNotFound() {
        given().auth().oauth2(accessToken())
                .multiPart("file", "x.txt", "hi".getBytes(StandardCharsets.UTF_8))
                .when().post("/rest/repo/upload-temp")
                .then().statusCode(404);
    }
}
