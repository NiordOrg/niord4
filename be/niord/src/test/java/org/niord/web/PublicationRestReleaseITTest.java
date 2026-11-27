/*
 * Copyright 2024 Danish Maritime Authority.
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
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.repo.RepositoryService;
import org.niord.core.util.UidUtils;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;

import org.niord.core.repo.RepoStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the publication <em>workflow</em> endpoints of {@link PublicationRestService}
 * that the sibling tests do not touch: {@code PUT /release-publication/{id}},
 * {@code POST /generate-publication-report/{folder}}, {@code POST /upload-publication-file/{folder}}
 * (the file-name containment of the last two) and the admin path of {@code POST /upload-publications}.
 *
 * <p>{@link PublicationRestServiceITTest} covers the read/write authorization matrix and the pure
 * validation cases of the basic CRUD endpoints, and {@link PublicationRestCrudITTest} covers the
 * create/update/delete/status happy-path lifecycle. Neither exercises the release pipeline, the report
 * generator or a successful (authenticated) upload. This class is the complement for those three
 * endpoints: it asserts the authorization oracle (anonymous {@code 401}, adjacent-tier editor
 * {@code 403}, admin reaches the handler) plus the reachable business outcomes — release rejection with
 * an HTTP status <em>and</em> the persisted publication status left unchanged, the report generator's
 * temp-folder validation, and the synchronous job-started response of the import endpoint.
 *
 * <p><b>Why the release/report happy paths are only partially exercised here.</b> A successful
 * {@code releasePublication} requires a {@code MESSAGE_REPORT} publication in {@code RECORDING} status
 * <em>with</em> an associated message tag and {@code printSettings.report}, and then performs a live
 * HTTP round-trip to {@code /rest/message-reports/report.pdf} that renders a PDF through Freemarker
 * templates. Building that fixture and the report-rendering infrastructure is out of scope for an
 * endpoint test; see {@code docs/untestable-code.md}. These tests therefore drive every
 * <em>reachable</em> branch up to (but not including) the report-rendering round-trip, which on the
 * release path means the four-way validation guard and on the report path means the temp-folder guard
 * — the guard runs <em>before</em> any HTTP connection is opened (see
 * {@code PublicationRestService.generatePublicationReport}). The one release carried through to
 * {@code ACTIVE} here is of a publication without any text, which opens no round-trip (it runs once per
 * description); a release with a report is driven by {@code PublicationsSweepGroup#publicationRelease}.
 *
 * <p><b>Authorization model.</b> {@code release-publication} and {@code generate-publication-report}
 * are {@code @RolesAllowed(ADMIN)}. The {@code admin} token from
 * {@link KeycloakTestTokens#adminAccessToken()} carries {@code admin} on {@code niord-client-master};
 * every authenticated request additionally carries {@code NiordDomain: niord-nw} so the
 * augmentor realizes the full role chain (mirrors the real front-end-to-back-end flow). A missing token
 * yields the container's {@code 401}; an authenticated {@code editor} (one tier too low) yields
 * {@code 403}.
 *
 * <p><b>Independence and cleanup.</b> HTTP requests run in the server's own transaction, which
 * {@code @Transactional} cannot roll back, so publications created by the validation tests are
 * committed. Each test uses a fresh random-UUID business id (seed-disjoint), tracks it and deletes it
 * best-effort in {@link #cleanup()}. Every test passes alone and inside the full suite.
 *
 * <p><b>Seeded category oracle.</b> New publications are bound to the seeded publication category
 * {@code dk-external-publications} so the {@code @NotNull category} constraint is satisfied (same oracle
 * used by {@link PublicationRestCrudITTest}).
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class PublicationRestReleaseITTest extends ITTest {

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    private static final String PUBLICATION      = "/rest/publications/publication/";
    private static final String RELEASE          = "/rest/publications/release-publication/";
    private static final String GENERATE_REPORT  = "/rest/publications/generate-publication-report/";
    private static final String UPLOAD           = "/rest/publications/upload-publications";
    private static final String UPLOAD_FILE      = "/rest/publications/upload-publication-file/";

    @Autowired
    RepositoryService repositoryService;

    @Autowired
    RepoStore store;

    /** A seeded publication category id used to satisfy the @NotNull category constraint on create. */
    private static final String SEEDED_CATEGORY = "dk-external-publications";

    // Tokens are minted once from the Keycloak container and reused across the suite.
    private static String editorToken;
    private static String adminToken;

    private static String editor() { return editorToken != null ? editorToken : (editorToken = KeycloakTestTokens.editorAccessToken()); }
    private static String admin()  { return adminToken  != null ? adminToken  : (adminToken  = KeycloakTestTokens.adminAccessToken()); }

    /** A request carrying the given bearer token and the {@code NiordDomain} header (full role chain). */
    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    /** Publications committed by tests, deleted best-effort in {@link #cleanup()}. */
    private final List<String> createdPublicationIds = new ArrayList<>();

    /**
     * Generates a fresh, seed-disjoint publication id. The id must be a valid UUID because the create
     * handler routes embedded links through {@code Publication.toPublicationRepo()}; a random UUID both
     * passes that validation and is unique per test.
     */
    private static String newPublicationId() {
        return UidUtils.newUid();
    }

    @AfterEach
    void cleanup() {
        for (String id : createdPublicationIds) {
            try {
                as(admin()).when().delete(PUBLICATION + id);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdPublicationIds.clear();
    }

    /**
     * A minimal but valid create body in the given type/status, bound to the seeded category.
     * {@code status} and {@code mainType} live on {@code SystemPublicationVo}; {@code type} and
     * {@code category} live on {@code PublicationVo}.
     */
    private static Map<String, Object> createBody(String id, String type, String status) {
        return Map.of(
                "publicationId", id,
                "mainType", "PUBLICATION",
                "type", type,
                "status", status,
                "category", Map.of("categoryId", SEEDED_CATEGORY));
    }

    /** Creates a publication as admin, asserts it persisted with the requested status, tracks it. */
    private String createPublicationAsAdmin(String id, String type, String status) {
        as(admin()).contentType(ContentType.JSON).body(createBody(id, type, status))
                .when().post(PUBLICATION)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("publicationId", is(id))
                .body("type", is(type))
                .body("status", is(status));
        createdPublicationIds.add(id);
        return id;
    }

    /** Asserts that the DB-backed lookup reports the given status for the publication. */
    private void assertPersistedStatus(String id, String expectedStatus) {
        as(admin()).when().get("/rest/publications/editable-publication/" + id)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("publicationId", is(id))
                .body("status", is(expectedStatus));
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /release-publication/{id} — @RolesAllowed(ADMIN). anon -> 401; adjacent-tier editor -> 403.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void releasePublication_rejectsAnonymousWith401() {
        given().contentType(ContentType.JSON)
                .when().put(RELEASE + "any-id")
                .then().statusCode(401);
    }

    @Test
    void releasePublication_forbidsEditorWith403() {
        // KEY adjacent-tier case: an authenticated editor lacks 'admin'.
        as(editor()).contentType(ContentType.JSON)
                .when().put(RELEASE + "any-id")
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /release-publication/{unknown} as admin: the pub==null guard throws
    // NiordWebException("Invalid publication ID ...", 400).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void releasePublication_unknownIdReturns400() {
        as(admin()).contentType(ContentType.JSON)
                .when().put(RELEASE + "no-such-publication")
                .then().statusCode(400);
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /release-publication/{id} on a LINK/DRAFT publication: fails the "type == MESSAGE_REPORT"
    // guard -> 400, and — crucially — leaves the persisted status unchanged (no partial mutation).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void releasePublication_nonMessageReportPublicationReturns400AndLeavesStatusUnchanged() {
        String id = createPublicationAsAdmin(newPublicationId(), "LINK", "DRAFT");

        as(admin()).contentType(ContentType.JSON)
                .when().put(RELEASE + id)
                .then().statusCode(400);

        // The release guard rejected the publication before touching it: status is still DRAFT.
        assertPersistedStatus(id, "DRAFT");
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /release-publication/{id} on a MESSAGE_REPORT that IS in RECORDING but has no message tag
    // and no printSettings: this exercises a DIFFERENT branch of the four-way release guard
    // (messageTag == null / printSettings.report == null) -> 400, again with no status mutation.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void releasePublication_recordingReportWithoutMessageTagReturns400AndLeavesStatusUnchanged() {
        // A POST sets RECORDING directly (createPublication persists the requested status verbatim and
        // does not run the updateStatus side effects), so we reach release with status==RECORDING but
        // messageTag==null and empty printSettings.
        String id = createPublicationAsAdmin(newPublicationId(), "MESSAGE_REPORT", "RECORDING");

        as(admin()).contentType(ContentType.JSON)
                .when().put(RELEASE + id)
                .then().statusCode(400);

        // Being in RECORDING is not sufficient; the missing message tag/print settings block release,
        // and the publication is left untouched in RECORDING.
        assertPersistedStatus(id, "RECORDING");
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /release-publication/{id} on a releasable MESSAGE_REPORT without any text: the mapper gives it no
    // list of descriptions at all, and the release has no report to make, so it releases the publication
    // without one (the report round-trip runs once per description, so none is opened here).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void releasePublication_aRecordingReportWithoutAnyTextIsReleasedWithoutAReport() {
        String tagId = as(admin()).contentType(ContentType.JSON)
                .body(Map.of("type", "PRIVATE", "name", "it-release-notext-" + System.nanoTime(), "locked", false))
                .when().post("/rest/tags/tag")
                .then().statusCode(200).extract().path("tagId");
        try {
            String id = newPublicationId();
            Map<String, Object> body = new LinkedHashMap<>(createBody(id, "MESSAGE_REPORT", "RECORDING"));
            body.put("messageTag", Map.of("tagId", tagId));
            body.put("printSettings", Map.of("report", "standard"));
            as(admin()).contentType(ContentType.JSON).body(body)
                    .when().post(PUBLICATION)
                    .then().statusCode(200);
            createdPublicationIds.add(id);

            as(admin()).contentType(ContentType.JSON)
                    .when().put(RELEASE + id)
                    .then().statusCode(200)
                    .body("status", is("ACTIVE"));

            assertPersistedStatus(id, "ACTIVE");
        } finally {
            cleanup();
            as(admin()).when().put("/rest/tags/unlock-tag/" + tagId);
            as(admin()).when().delete("/rest/tags/tag/" + tagId);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /generate-publication-report/{folder} — @RolesAllowed(ADMIN). anon -> 401; editor -> 403.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void generatePublicationReport_rejectsAnonymousWith401() {
        given().contentType(ContentType.JSON).body(Map.of())
                .when().post(GENERATE_REPORT + "temp/some-folder")
                .then().statusCode(401);
    }

    @Test
    void generatePublicationReport_forbidsEditorWith403() {
        as(editor()).contentType(ContentType.JSON).body(Map.of())
                .when().post(GENERATE_REPORT + "temp/some-folder")
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /generate-publication-report/{folder} as admin with a folder OUTSIDE the temp repo root:
    // checkCreateTempRepoPath -> validateTempRepoPath rejects it with 403 BEFORE any report rendering
    // (the guard runs before the HTTP connection to /rest/message-reports/report.pdf is opened).
    // This is the report generator's invalid-argument negative case.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void generatePublicationReport_invalidTempRepoFolderReturns403() {
        as(admin()).contentType(ContentType.JSON).body(Map.of())
                .when().post(GENERATE_REPORT + "not-a-temp-folder")
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // The file names of a publication file come from the request (the folder alone used to be checked).
    // A descriptor's fileName must be a bare name: one carrying a path is refused with 400 before anything
    // is written, and the report generator refuses it before it renders anything. An uploaded part's own
    // name, used when the descriptor names none, is reduced to its bare name inside the temp folder.
    // ------------------------------------------------------------------------------------------- //

    /** Uploads a publication file as admin into the given temp folder, naming it in the descriptor. */
    private io.restassured.response.ValidatableResponse uploadPublicationFile(String folder, String fileName) {
        String data = "{\"lang\":\"en\",\"fileName\":\"" + fileName.replace("\\", "\\\\") + "\"}";
        return as(admin())
                .multiPart("data", data)
                .multiPart("file", "upload.txt", "publication-file-body".getBytes(StandardCharsets.UTF_8))
                .when().post(UPLOAD_FILE + folder)
                .then();
    }

    @Test
    void uploadPublicationFile_bareNameIsWrittenIntoTheTempFolder() {
        String folder = "temp/it-pubfile-" + System.nanoTime();
        try {
            uploadPublicationFile(folder, "notice.txt")
                    .statusCode(200)
                    .body("fileName", is("notice.txt"));

            assertTrue(store.exists(folder + "/notice.txt"),
                    "a plain file name is written into the temp folder");
        } finally {
            store.deleteFolder(folder);
        }
    }

    @Test
    void uploadPublicationFile_descriptorNameWithAPathIsRefused() {
        String folder = "temp/it-pubfile-" + System.nanoTime();
        String relativeName = "it-escape-" + System.nanoTime() + ".txt";
        Path absoluteTarget = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()
                .resolve("it-absolute-" + System.nanoTime() + ".txt");
        try {
            // A parent-relative name, an absolute name, and a name with nothing usable left
            uploadPublicationFile(folder, "../../" + relativeName).statusCode(400);
            uploadPublicationFile(folder, absoluteTarget.toString()).statusCode(400);
            uploadPublicationFile(folder, "../..").statusCode(400);

            assertFalse(store.exists(relativeName),
                    "nothing may be written outside the temp folder");
            assertFalse(Files.exists(absoluteTarget), "nothing may be written at the absolute path");
            assertFalse(store.exists(folder + "/" + relativeName),
                    "a refused upload writes nothing, not even under the bare name");
        } finally {
            store.deleteFolder(folder);
            FileUtils.deleteQuietly(absoluteTarget.toFile());
        }
    }

    @Test
    void uploadPublicationFile_partNameWithAPathIsReducedToItsBareName() {
        // With no name in the descriptor the uploaded part's own name is used, and a client may send a
        // path there, so that name is reduced to its last segment inside the temp folder
        String folder = "temp/it-pubfile-" + System.nanoTime();
        String bareName = "it-part-" + System.nanoTime() + ".txt";
        try {
            as(admin())
                    .multiPart("data", "{\"lang\":\"en\"}")
                    .multiPart("file", "../../" + bareName, "part-body".getBytes(StandardCharsets.UTF_8))
                    .when().post(UPLOAD_FILE + folder)
                    .then().statusCode(200)
                    .body("fileName", is(bareName));

            assertTrue(store.exists(folder + "/" + bareName),
                    "the file is written under its bare name inside the temp folder");
            assertFalse(store.exists(bareName),
                    "nothing may be written outside the temp folder");
        } finally {
            store.deleteFolder(folder);
        }
    }

    @Test
    void generatePublicationReport_nameWithAPathIsRefusedBeforeRendering() {
        String folder = "temp/it-report-" + System.nanoTime();
        try {
            as(admin()).contentType(ContentType.JSON)
                    .body(Map.of("lang", "en", "fileName", "../../it-report-escape.pdf"))
                    .when().post(GENERATE_REPORT + folder)
                    .then().statusCode(400);

            assertFalse(store.exists("it-report-escape.pdf"),
                    "nothing may be written outside the temp folder");
        } finally {
            store.deleteFolder(folder);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /upload-publications — @RolesAllowed(ADMIN), multipart. A valid admin upload synchronously
    // starts the 'publication-import' batch job and returns 200 with a text/plain status line naming
    // the job (the actual import runs asynchronously; see docs/untestable-code.md). Complements the
    // sibling's anon -> 401 case with the authenticated happy-path response contract.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void uploadPublications_adminStartsBatchJobAndReturnsStatusText() {
        // A syntactically valid (if empty) publications JSON document; the endpoint hands the file to
        // the batch service and reports the job as started before any parsing/persistence happens.
        byte[] json = "[]".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        as(admin())
                .multiPart("file", "publications.json", json, MediaTypeText())
                .when().post(UPLOAD)
                .then().statusCode(200)
                .body(containsString("Started batch job 'publication-import'"))
                .body(containsString("publications.json"));
    }

    /** The content type used for the uploaded publications file part. */
    private static String MediaTypeText() {
        return "application/json";
    }
}
