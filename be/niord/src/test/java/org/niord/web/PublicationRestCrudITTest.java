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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.repo.RepoStore;
import org.niord.core.repo.RepositoryService;
import org.niord.core.util.UidUtils;
import org.niord.test.ITTest;

import org.niord.test.RepoRows;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CRUD round-trip integration tests for {@link PublicationRestService}: the admin-only write endpoints
 * {@code POST/PUT/DELETE /publication}, {@code /update-status} and {@code /copy-publication-template}
 * of {@code /rest/publications}.
 *
 * <p>The sibling {@link PublicationRestServiceITTest} covers the authorization matrix and the pure
 * validation/negative cases (it performs no successful writes). This class is the complement: it drives
 * the full happy-path lifecycle of a publication — create, read-back, update, status transition and
 * delete — as an authenticated {@code admin}, and asserts the <em>persisted</em> outcome each time by
 * re-reading through the service's own DB-backed read endpoints.
 *
 * <p><b>Authorization model.</b> All write endpoints require {@code @RolesAllowed(ADMIN)}; the
 * {@code admin} token from {@code accessToken("admin")} carries {@code admin} on
 * {@code niord-client-master}. Every authenticated request additionally carries
 * {@code NiordDomain: niord-nw} so the augmentor realizes the full role chain (mirrors the real
 * front-end-to-back-end flow). Read-back of the created data uses the {@code @PermitAll}
 * {@code GET /publication/{ids}} endpoint and the {@code @RolesAllowed(ADMIN)}
 * {@code GET /editable-publication/{id}} endpoint, both of which re-query the database, so a successful
 * read-back is a genuine persistence assertion rather than an echo of the request body.
 *
 * <p><b>Independence and cleanup.</b> HTTP requests run in the server's own transaction, which
 * {@code @Transactional} cannot roll back, so created publications are committed. Each test uses a
 * unique {@code IT-PUB-<nano>} business id that never occurs in the seed data, tracks it, and deletes
 * it in {@link #cleanup()} (best effort). Every test therefore passes alone and inside the full suite.
 *
 * <p><b>Seeded data oracle.</b> The copy-template test uses a seeded publication as its source,
 * resolved at runtime from the anonymous {@code /all} endpoint, which serves the public tier (the seed
 * regenerates publication UUIDs on every rebuild). New publications are created against the seeded
 * category {@code dk-external-publications} so the {@code @NotNull category} constraint is satisfied.
 * An anonymous read-back of such a DRAFT still returns the row, without the address of its document.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class PublicationRestCrudITTest extends ITTest {

    @Autowired
    RepositoryService repositoryService;

    @Autowired
    RepoStore store;

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    private static final String ALL            = "/rest/publications/all";
    private static final String PUBLICATION   = "/rest/publications/publication/";
    private static final String EDITABLE       = "/rest/publications/editable-publication/";
    private static final String UPDATE_STATUS  = "/rest/publications/update-status";
    private static final String COPY_TEMPLATE  = "/rest/publications/copy-publication-template/";

    /**
     * The publicationId of a seeded publication, used as the source for the copy-template endpoint.
     * The seed regenerates publication UUIDs on every rebuild, so a real one is resolved at runtime
     * from the anonymous {@code /all} endpoint rather than hard-coded.
     */
    private String seededId;

    /** A seeded publication category id used to satisfy the @NotNull category constraint on create. */
    private static final String SEEDED_CATEGORY = "dk-external-publications";

    /** Edition assigned by {@code copyPublicationTemplate} (matches {@code Publication.DEFAULT_EDITION}). */
    private static final int DEFAULT_EDITION = 1;

    /** A request carrying the given bearer token and the {@code NiordDomain} header (full role chain). */
    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    /** Publications committed by tests, deleted best-effort in {@link #cleanup()}. */
    private final List<String> createdPublicationIds = new ArrayList<>();

    /**
     * Generates a fresh, seed-disjoint publication id. The id must be a valid UUID: the create/update
     * handlers route embedded links through {@code Publication.toPublicationRepo()}, which calls
     * {@code UidUtils.uidToHashedFolderPath} and throws {@code IllegalArgumentException("Invalid UID
     * format ...")} (surfacing as HTTP 500) for any non-UUID id. {@link UidUtils#newUid()} returns a
     * random UUID, which both passes that validation and is unique per test.
     */
    private static String newPublicationId() {
        return UidUtils.newUid();
    }

    @BeforeEach
    void resolveSeededPublicationId() {
        seededId = given().when().get(ALL)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(1))
                .extract().path("[0].publicationId");
    }

    @AfterEach
    void cleanup() {
        for (String id : createdPublicationIds) {
            try {
                as(accessToken("admin")).when().delete(PUBLICATION + id);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdPublicationIds.clear();
    }

    /**
     * A minimal but valid create body: a {@code LINK} publication in the given status, bound to the
     * seeded category. {@code status} and {@code mainType} live on {@code SystemPublicationVo};
     * {@code type} and {@code category} live on {@code PublicationVo}.
     */
    private static Map<String, Object> createBody(String id, String type, String status) {
        return Map.of(
                "publicationId", id,
                "mainType", "PUBLICATION",
                "type", type,
                "status", status,
                "category", Map.of("categoryId", SEEDED_CATEGORY));
    }

    /** Creates a publication as admin, asserts it persisted, tracks it for cleanup and returns its id. */
    private String createPublicationAsAdmin(String id, String type, String status) {
        as(accessToken("admin")).contentType(ContentType.JSON).body(createBody(id, type, status))
                .when().post(PUBLICATION)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("publicationId", is(id))
                .body("status", is(status));
        createdPublicationIds.add(id);
        return id;
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /publication/ then GET /publication/{id}: create persists and is read-back-able.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createThenGet_persistsAndReturnsSingleMatch() {
        String id = createPublicationAsAdmin(newPublicationId(), "LINK", "DRAFT");

        // Read-back through the @PermitAll DB-backed lookup: exactly one element echoing the new id.
        given().when().get(PUBLICATION + id)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("size()", is(1))
                .body("[0].publicationId", is(id))
                .body("[0].type", is("LINK"));
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /publication/ of a REPOSITORY publication: the files of its temp edit folder are copied into the
    // publication's own folder, derived from its id, whatever repoPath the request body names.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createPublication_syncsIntoItsOwnFolderWhateverRepoPathTheBodyNames() throws Exception {
        String id = newPublicationId();
        String ownRepoPath = UidUtils.uidToHashedFolderPath("publications", id);
        String foreignRepoPath = "publications/it-foreign-" + System.nanoTime();
        String editRepoPath = "temp/it-pub-edit-" + System.nanoTime();
        try {
            store.write(editRepoPath + "/doc.txt", "publication file".getBytes(StandardCharsets.UTF_8),
                    Instant.now());

            Map<String, Object> body = new HashMap<>(createBody(id, "REPOSITORY", "DRAFT"));
            body.put("repoPath", foreignRepoPath);
            body.put("editRepoPath", editRepoPath);
            as(accessToken("admin")).contentType(ContentType.JSON).body(body)
                    .when().post(PUBLICATION)
                    .then().statusCode(200)
                    .body("publicationId", is(id));
            createdPublicationIds.add(id);

            assertTrue(store.exists(ownRepoPath + "/doc.txt"),
                    "the file must land in the publication's own, id-derived folder");
            assertFalse(store.hasAny(foreignRepoPath),
                    "the folder the body named must not be created");
        } finally {
            store.deleteFolder(ownRepoPath);
            store.deleteFolder(foreignRepoPath);
            store.deleteFolder(editRepoPath);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /publication/ with the publicationId of an existing publication: refused with 400 before the
    // edit folder is copied, so the existing publication's folder (derived from that id) is left alone.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createPublication_withAnExistingPublicationIdIsRefusedAndLeavesItsFolderAlone() throws Exception {
        String id = createPublicationAsAdmin(newPublicationId(), "LINK", "DRAFT");
        String ownRepoPath = UidUtils.uidToHashedFolderPath("publications", id);
        String editRepoPath = "temp/it-pub-edit-" + System.nanoTime();
        // A file of the existing publication's revision 1, and an edit folder with a file of the same name
        String ownFile = ownRepoPath + "/1/doc.txt";
        try {
            store.write(ownFile, "the existing publication's file".getBytes(StandardCharsets.UTF_8),
                    Instant.now());
            store.write(editRepoPath + "/1/doc.txt", "a file from another edit folder".getBytes(StandardCharsets.UTF_8),
                    Instant.now());

            Map<String, Object> body = new HashMap<>(createBody(id, "REPOSITORY", "DRAFT"));
            body.put("revision", 1);
            body.put("editRepoPath", editRepoPath);
            as(accessToken("admin")).contentType(ContentType.JSON).body(body)
                    .when().post(PUBLICATION)
                    .then().statusCode(400);

            assertEquals("the existing publication's file",
                    RepoRows.readText(store, ownFile),
                    "a refused create must not copy anything into the existing publication's folder");
            // The existing publication itself is unchanged
            given().when().get(PUBLICATION + id)
                    .then().statusCode(200)
                    .body("size()", is(1))
                    .body("[0].type", is("LINK"));
        } finally {
            store.deleteFolder(ownRepoPath);
            store.deleteFolder(editRepoPath);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /publication/{id}: editing the description title is persisted (a subsequent GET reflects it).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updatePublication_changesDescTitleIsPersisted() {
        String id = createPublicationAsAdmin(newPublicationId(), "LINK", "DRAFT");

        // Update the publication, adding an English description with a link + title.
        Map<String, Object> updateBody = Map.of(
                "publicationId", id,
                "mainType", "PUBLICATION",
                "type", "LINK",
                "status", "DRAFT",
                "category", Map.of("categoryId", SEEDED_CATEGORY),
                "descs", List.of(Map.of(
                        "lang", "en",
                        "title", "IT-PUB Updated Title",
                        "link", "http://example.org/it-pub")));

        as(accessToken("admin")).contentType(ContentType.JSON).body(updateBody)
                .when().put(PUBLICATION + id)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("publicationId", is(id))
                .body("descs.find { it.lang == 'en' }.title", is("IT-PUB Updated Title"));

        // The change is persisted: re-reading through the DB-backed lookup reflects the new title.
        // Read back as a signed-in caller: an anonymous one is not handed the link of a DRAFT.
        as(accessToken("admin")).queryParam("lang", "en")
                .when().get(PUBLICATION + id)
                .then().statusCode(200)
                .body("[0].publicationId", is(id))
                .body("[0].descs.find { it.lang == 'en' }.title", is("IT-PUB Updated Title"))
                .body("[0].descs.find { it.lang == 'en' }.link", is("http://example.org/it-pub"));
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /publication/{id} of an unreleased publication: an anonymous caller still gets the row and
    // its title (a stored citation keeps rendering), but not the address of its document.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getPublication_anonymousIsNotHandedTheDocumentAddressOfADraft() {
        String id = createPublicationAsAdmin(newPublicationId(), "LINK", "DRAFT");
        as(accessToken("admin")).contentType(ContentType.JSON)
                .body(Map.of(
                        "publicationId", id,
                        "mainType", "PUBLICATION",
                        "type", "LINK",
                        "status", "DRAFT",
                        "category", Map.of("categoryId", SEEDED_CATEGORY),
                        "descs", List.of(Map.of(
                                "lang", "en",
                                "title", "IT-PUB Draft",
                                "link", "http://example.org/it-pub-draft"))))
                .when().put(PUBLICATION + id)
                .then().statusCode(200);

        given().queryParam("lang", "en")
                .when().get(PUBLICATION + id)
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].descs.find { it.lang == 'en' }.title", is("IT-PUB Draft"))
                .body("[0].descs.find { it.lang == 'en' }.link", nullValue())
                .body("[0].descs.find { it.lang == 'en' }.fileName", nullValue());

        as(accessToken("user")).queryParam("lang", "en")
                .when().get(PUBLICATION + id)
                .then().statusCode(200)
                .body("[0].descs.find { it.lang == 'en' }.link", is("http://example.org/it-pub-draft"));
    }

    // ------------------------------------------------------------------------------------------- //
    // DELETE /publication/{id} as admin: removes the row, a subsequent GET returns an empty list.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void deletePublication_asAdminRemovesItAndGetReturnsEmpty() {
        String id = createPublicationAsAdmin(newPublicationId(), "LINK", "DRAFT");

        // Confirm it exists before deletion.
        given().when().get(PUBLICATION + id)
                .then().statusCode(200)
                .body("size()", is(1))
                .body("[0].publicationId", is(id));

        as(accessToken("admin")).when().delete(PUBLICATION + id)
                .then().statusCode(204);

        // The publication is gone: the DB-backed lookup now returns an empty list.
        given().when().get(PUBLICATION + id)
                .then().statusCode(200)
                .body("size()", is(0));

        // Already deleted, so drop it from the cleanup list.
        createdPublicationIds.remove(id);
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /copy-publication-template/{seeded}: copies a seeded publication into a fresh DRAFT template.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void copyPublicationTemplate_returnsDraftRevisionZeroWithNewId() {
        String newId = as(accessToken("admin"))
                .queryParam("nextIssue", false)
                .when().get(COPY_TEMPLATE + seededId)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("status", is("DRAFT"))
                .body("revision", is(0))
                .body("edition", is(DEFAULT_EDITION))
                .body("publicationId", notNullValue())
                .body("publicationId", not(is(seededId)))
                .extract().path("publicationId");

        // The copy template is a transient editing aid: it is NOT persisted until POSTed, so the new
        // id must not be findable through the DB-backed lookup.
        given().when().get(PUBLICATION + newId)
                .then().statusCode(200)
                .body("size()", is(0));
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /copy-publication-template/{unknown}: an unknown source returns null -> HTTP 204 No Content.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void copyPublicationTemplate_unknownSourceReturns204() {
        as(accessToken("admin"))
                .queryParam("nextIssue", false)
                .when().get(COPY_TEMPLATE + "no-such-publication")
                .then().statusCode(204);
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /update-status: a legal DRAFT -> ACTIVE transition is applied and persisted. The transition
    // is unconditionally valid for a type=NONE publication (see PublicationService.updateStatus).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateStatus_legalTransitionIsAppliedAndPersisted() {
        String id = createPublicationAsAdmin(newPublicationId(), "NONE", "DRAFT");

        // DRAFT -> ACTIVE: returns the updated publication with the new status.
        as(accessToken("admin")).contentType(ContentType.JSON)
                .body(Map.of("publicationId", id, "status", "ACTIVE"))
                .when().put(UPDATE_STATUS)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("publicationId", is(id))
                .body("status", is("ACTIVE"));

        // The new status is persisted: the admin editable-publication view re-queries the DB.
        as(accessToken("admin")).when().get(EDITABLE + id)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("publicationId", is(id))
                .body("status", is("ACTIVE"));
    }

    /**
     * PUB1. A refused transition (ACTIVE to ACTIVE) answers 409 with its sentence, a body without a status 400 "A
     * status is required", and an unknown publication 400 with its sentence; none of them is logged at ERROR, and the
     * publication keeps its status. FAILS on the pre-fix code, which answered the refused transition 400, the missing
     * status 400 with the {@code NullPointerException}'s text, and logged each at ERROR with its stack.
     */
    @Test
    void updateStatus_refusalsAreAnsweredAsTheCallersErrorsWithoutAnErrorLine() {
        String id = createPublicationAsAdmin(newPublicationId(), "NONE", "ACTIVE");
        Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            as(accessToken("admin")).contentType(ContentType.JSON)
                    .body(Map.of("publicationId", id, "status", "ACTIVE"))
                    .when().put(UPDATE_STATUS)
                    .then().statusCode(409)
                    .body("detail", is("Invalid state transition ACTIVE -> ACTIVE"));
            as(accessToken("admin")).contentType(ContentType.JSON)
                    .body(Map.of("publicationId", id))
                    .when().put(UPDATE_STATUS)
                    .then().statusCode(400)
                    .body("detail", is("A status is required"));
            as(accessToken("admin")).contentType(ContentType.JSON)
                    .body(Map.of("publicationId", "no-such-publication", "status", "INACTIVE"))
                    .when().put(UPDATE_STATUS)
                    .then().statusCode(400)
                    .body("detail", is("Non-existing publication no-such-publication"));
        } finally {
            logger.detachAppender(appender);
        }

        // The requests' own lines: the server's request threads (a background job's line is not theirs)
        List<String> errors = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR && e.getThreadName().startsWith("http-"))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertEquals(List.of(), errors, "a refusal is the caller's error, not logged at ERROR");
        as(accessToken("admin")).when().get(EDITABLE + id)
                .then().statusCode(200)
                .body("status", is("ACTIVE"));
    }

    // ------------------------------------------------------------------------------------------- //
    // POST/PUT /publication: the message tag filter is run as script (F-X43). Only a sysadmin sets one
    // outside the known legacy filters; an admin may keep the stored one or send a known one.
    // ------------------------------------------------------------------------------------------- //

    /** A filter outside the known legacy filters; harmless as script */
    private static final String UNKNOWN_FILTER = "msg.status == Status.PUBLISHED && msg.uid != null";

    /** One of the known legacy filters */
    private static final String KNOWN_FILTER = "msg.status == Status.PUBLISHED";

    /** A create/update body with the given filter; the edition stands in for "some other field" */
    private static Map<String, Object> bodyWithFilter(String id, String messageTagFilter, int edition) {
        Map<String, Object> body = new HashMap<>(createBody(id, "LINK", "DRAFT"));
        body.put("messageTagFilter", messageTagFilter);
        body.put("edition", edition);
        return body;
    }

    @Test
    void create_adminCannotSetAnUnknownMessageTagFilter() {
        String id = newPublicationId();

        as(accessToken("admin")).contentType(ContentType.JSON).body(bodyWithFilter(id, UNKNOWN_FILTER, 1))
                .when().post(PUBLICATION)
                .then().statusCode(403);

        // Nothing was persisted
        given().when().get(PUBLICATION + id)
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void create_adminMaySetAKnownMessageTagFilter() {
        String id = newPublicationId();

        as(accessToken("admin")).contentType(ContentType.JSON).body(bodyWithFilter(id, KNOWN_FILTER, 1))
                .when().post(PUBLICATION)
                .then().statusCode(200)
                .body("messageTagFilter", is(KNOWN_FILTER));
        createdPublicationIds.add(id);
    }

    @Test
    void update_adminMayKeepASysadminFilterButNotChangeIt() {
        String id = newPublicationId();

        // A sysadmin sets a filter outside the known ones
        as(accessToken()).contentType(ContentType.JSON).body(bodyWithFilter(id, UNKNOWN_FILTER, 1))
                .when().post(PUBLICATION)
                .then().statusCode(200)
                .body("messageTagFilter", is(UNKNOWN_FILTER));
        createdPublicationIds.add(id);

        // An admin edits another field and sends the stored filter back unchanged
        as(accessToken("admin")).contentType(ContentType.JSON).body(bodyWithFilter(id, UNKNOWN_FILTER, 2))
                .when().put(PUBLICATION + id)
                .then().statusCode(200)
                .body("edition", is(2));

        // An admin changing it to another unknown filter is refused, and nothing changes
        as(accessToken("admin")).contentType(ContentType.JSON)
                .body(bodyWithFilter(id, UNKNOWN_FILTER + " && msg.id != null", 3))
                .when().put(PUBLICATION + id)
                .then().statusCode(403);

        as(accessToken("admin")).when().get(EDITABLE + id)
                .then().statusCode(200)
                .body("messageTagFilter", is(UNKNOWN_FILTER))
                .body("edition", is(2));
    }
}
