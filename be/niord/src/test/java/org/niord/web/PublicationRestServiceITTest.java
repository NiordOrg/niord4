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

import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.util.UidUtils;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link PublicationRestService}: the HTTP contract and authorization of the
 * {@code /rest/publications} endpoints.
 *
 * <p><b>Authorization model under test.</b> The class is annotated {@code @PermitAll}, so by default
 * every method is open to anonymous callers. The read endpoints {@code /search}, {@code /all} and
 * {@code /publication/{ids}} keep that class default and require no authentication, but a caller
 * who is not a signed-in user of a domain reads the <em>public tier</em> of {@code /search} and
 * {@code /all}: ACTIVE publications of main type PUBLICATION in a publishing category, whatever the
 * request asks for. A {@code user} token with the {@code NiordDomain} header keeps the unfiltered
 * lists. Three reads tighten the gate with method-level {@code @RolesAllowed}: {@code /search-details},
 * {@code /editable-publication/{id}} and {@code /new-publication-template} require {@code ADMIN}.
 * The write endpoints {@code POST/PUT/DELETE /publication}, {@code /update-status}
 * and {@code /upload-publications} all require {@code ADMIN}. One read, {@code /export}, stays
 * {@code @PermitAll} but enforces {@code ADMIN} <em>programmatically</em> inside the handler via
 * {@code userService.isCallerInRole(ADMIN)}. Because {@code @PermitAll} lets even an anonymous caller
 * reach the handler, {@code /export} rejects the wrong identity with {@code 403} (the programmatic
 * check throws {@code NiordWebException(403)}) rather than the container's {@code 401}.
 *
 * <p><b>Tier oracle.</b> The role hierarchy is {@code sysadmin > admin > editor > user}
 * (see {@link org.niord.core.user.Roles}). The seeded identities (see {@link KeycloakTestTokens})
 * give one token per tier. Every authenticated request carries {@code NiordDomain: niord-nw} so
 * the domain augmentor realizes the full role chain (the only source of the {@code user} role). A
 * missing token yields the container's {@code 401} on {@code @RolesAllowed} endpoints; an authenticated
 * identity one tier too low yields {@code 403}. A token <em>without</em> the {@code NiordDomain} header
 * never gains the {@code user} role, so even an editor token is rejected with {@code 403} on the
 * {@code ADMIN}-tier {@code /search-details} endpoint.
 *
 * <p><b>Seeded data oracle.</b> The seed contains eight publications: ACTIVE {@code PUBLICATION}s in
 * the publishing category {@code dk-dma-publications}, others in the non-publishing categories
 * {@code dk-external-publications} and {@code dk-dma-internal-publications}, and two
 * {@code TEMPLATE}s. The seed regenerates publication UUIDs on every rebuild, so ids are resolved
 * at runtime: a public one from the anonymous {@code /all}, the status, main type and category of
 * every row from the admin {@code /export}. An unknown id on {@code /publication/<ids>} returns an
 * empty list (the lookup splits on commas and silently drops misses).
 *
 * <p><b>Write/validation oracle.</b> {@code PUT /publication/{id}} compares the path id against the
 * body's {@code publicationId} <em>before</em> any deserialization-dependent work and throws
 * {@code NiordWebException(400)} on mismatch. {@code PUT /update-status} catches any failure from
 * {@code updateStatus} (e.g. an unknown publication id) and rethrows it as {@code 400}.
 *
 * <p>HTTP requests run in the server's own transaction. The public-tier tests commit one DRAFT
 * publication through the admin endpoint, to show that status is gated, and delete it in
 * {@link #cleanup()}; nothing else is written and seeded data is never mutated.
 *
 * <p>This is a framework-coupled test - see docs/framework-coupled-tests.md.
 */
class PublicationRestServiceITTest extends ITTest {

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    private static final String SEARCH         = "/rest/publications/search";
    private static final String SEARCH_DETAILS = "/rest/publications/search-details";
    private static final String ALL            = "/rest/publications/all";
    private static final String PUBLICATION    = "/rest/publications/publication/";
    private static final String EDITABLE       = "/rest/publications/editable-publication/";
    private static final String NEW_TEMPLATE   = "/rest/publications/new-publication-template";
    private static final String UPDATE_STATUS  = "/rest/publications/update-status";
    private static final String UPLOAD         = "/rest/publications/upload-publications";
    private static final String EXPORT         = "/rest/publications/export";

    /** The seeded publishing category ({@code publish = true}). */
    private static final String PUBLISHING_CATEGORY = "dk-dma-publications";

    /**
     * The publicationId of a seeded publication (status ACTIVE, mainType PUBLICATION). The seed's
     * publication UUIDs are regenerated on every rebuild, so we resolve a real one at runtime from the
     * anonymous {@code /all} endpoint instead of hard-coding a UUID that drifts across regenerations.
     */
    private String seededId;

    @BeforeEach
    void resolveSeededPublicationId() {
        seededId = given().when().get(ALL)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(1))
                .extract().path("[0].publicationId");
    }

    /** A request carrying the given bearer token and the {@code NiordDomain} header (full role chain). */
    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    /** A minimal publication body with the given id and mainType. */
    private static Map<String, Object> body(String id, String mainType) {
        return Map.of("publicationId", id, "mainType", mainType);
    }

    /** Publications committed by a test, deleted best-effort in {@link #cleanup()}. */
    private final List<String> createdPublicationIds = new ArrayList<>();

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
     * Commits a DRAFT publication in the PUBLISHING category, so the only thing keeping it from the
     * public tier is its status.
     */
    private String createDraftInAPublishingCategory() {
        String id = UidUtils.newUid();
        as(accessToken("admin")).contentType(ContentType.JSON)
                .body(Map.of(
                        "publicationId", id,
                        "mainType", "PUBLICATION",
                        "type", "LINK",
                        "status", "DRAFT",
                        "category", Map.of("categoryId", PUBLISHING_CATEGORY)))
                .when().post(PUBLICATION)
                .then().statusCode(200)
                .body("status", is("DRAFT"));
        createdPublicationIds.add(id);
        return id;
    }

    /**
     * The ids of the legacy rows OUTSIDE the public tier -- not ACTIVE, not a PUBLICATION, or in a
     * category that does not publish -- read from the admin export, which carries status and main
     * type. Asserted non-empty with a template and an internal-category row among them, so a test
     * built on it cannot pass over nothing.
     */
    private Set<String> idsOutsideThePublicTier() {
        List<Map<String, Object>> rows = as(accessToken("admin")).when().get(EXPORT)
                .then().statusCode(200).extract().jsonPath().getList("$");
        Set<String> outside = new HashSet<>();
        boolean template = false;
        boolean internal = false;
        for (Map<String, Object> row : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> category = (Map<String, Object>) row.get("category");
            boolean publishes = category != null && Boolean.TRUE.equals(category.get("publish"));
            boolean isTemplate = "TEMPLATE".equals(row.get("mainType"));
            template |= isTemplate;
            internal |= !publishes;
            if (!"ACTIVE".equals(row.get("status")) || isTemplate || !publishes) {
                outside.add((String) row.get("publicationId"));
            }
        }
        assertTrue(template && internal,
                "the seed no longer holds a template and a row in a non-publishing category; "
                        + "the public-tier assertions below would prove nothing");
        return outside;
    }

    /** The publicationIds of a JSON array response. */
    private static Set<String> ids(List<Map<String, Object>> rows) {
        Set<String> out = new HashSet<>();
        rows.forEach(r -> out.add((String) r.get("publicationId")));
        return out;
    }

    /** Every row of an anonymous response sits in a publishing category, as the wire says. */
    private static void assertAllInAPublishingCategory(List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> category = (Map<String, Object>) row.get("category");
            assertTrue(category != null && Boolean.TRUE.equals(category.get("publish")),
                    "an anonymous caller was served " + row.get("publicationId")
                            + ", which is not in a publishing category");
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /search — @PermitAll. Anonymous reads the public tier; a signed-in user reads everything.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void search_anonymousReturnsPagedResult() {
        given().when().get(SEARCH)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("data", notNullValue())
                .body("total", greaterThanOrEqualTo(1));
    }

    /**
     * The request cannot widen the anonymous search: the status, the main type and the publish flag
     * are the public tier's whatever the query string says.
     */
    @Test
    void search_anonymousReadsOnlyThePublicTierWhateverItAsksFor() {
        String draft = createDraftInAPublishingCategory();
        Set<String> outside = idsOutsideThePublicTier();

        List<Map<String, List<String>>> queries = List.of(
                Map.of(),
                Map.of("status", List.of("DRAFT", "RECORDING", "INACTIVE", "ACTIVE")),
                Map.of("mainType", List.of("TEMPLATE")),
                Map.of("category", List.of("dk-external-publications")));
        for (Map<String, List<String>> query : queries) {
            RequestSpecification request = given().queryParam("maxSize", 1000);
            query.forEach((name, values) -> request.queryParam(name, values.toArray()));
            List<Map<String, Object>> rows = request.when().get(SEARCH)
                    .then().statusCode(200).extract().jsonPath().getList("data");
            assertAllInAPublishingCategory(rows);
            Set<String> leaked = ids(rows);
            leaked.retainAll(outside);
            assertTrue(leaked.isEmpty(), "anonymous /search " + query
                    + " served rows outside the public tier: " + leaked);
            assertFalse(ids(rows).contains(draft), "anonymous /search " + query + " served a DRAFT");
        }
    }

    /**
     * The anonymous page size is the caller's, kept within the ceiling of the message searches: a size
     * below 1 is one row, not the whole list.
     */
    @Test
    void search_anonymousPageSizeIsKeptWithinTheCeiling() {
        JsonPath page = given().queryParam("maxSize", 0).when().get(SEARCH)
                .then().statusCode(200).extract().jsonPath();
        assertTrue(page.getLong("total") >= 2,
                "the seed no longer holds two public-tier rows; the assertion below would prove nothing");
        // FAILS on the pre-fix code (a size below 1 served every matching row).
        assertEquals(1, page.getList("data").size());
    }

    /** A signed-in user's picker keeps the unfiltered search, drafts and internal categories included. */
    @Test
    void search_userStillReadsDraftsAndInternalCategories() {
        String draft = createDraftInAPublishingCategory();

        List<Map<String, Object>> drafts = as(accessToken("user")).queryParam("status", "DRAFT")
                .queryParam("maxSize", 1000)
                .when().get(SEARCH)
                .then().statusCode(200).extract().jsonPath().getList("data");
        assertTrue(ids(drafts).contains(draft), "a signed-in user lost the DRAFT row the picker offers");

        List<Map<String, Object>> internal = as(accessToken("user"))
                .queryParam("category", "dk-dma-internal-publications")
                .when().get(SEARCH)
                .then().statusCode(200).extract().jsonPath().getList("data");
        assertFalse(internal.isEmpty(), "a signed-in user lost the internal-category rows");
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /all — @PermitAll. Anonymous reads the public tier; a signed-in user reads every row.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void all_anonymousReturnsSeededPublications() {
        given().when().get(ALL)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(1));
    }

    /**
     * Anonymous /all holds only the public tier -- no template, no internal-category row, no draft --
     * while a signed-in user's /all still holds all of them.
     */
    @Test
    void all_anonymousReadsOnlyThePublicTierAndAUserReadsEverything() {
        String draft = createDraftInAPublishingCategory();
        Set<String> outside = idsOutsideThePublicTier();

        List<Map<String, Object>> anonymous = given().when().get(ALL)
                .then().statusCode(200).extract().jsonPath().getList("$");
        assertAllInAPublishingCategory(anonymous);
        Set<String> leaked = ids(anonymous);
        leaked.retainAll(outside);
        assertTrue(leaked.isEmpty(), "anonymous /all served rows outside the public tier: " + leaked);

        Set<String> signedIn = ids(as(accessToken("user")).when().get(ALL)
                .then().statusCode(200).extract().jsonPath().getList("$"));
        assertTrue(signedIn.containsAll(outside),
                "a signed-in user's /all lost rows outside the public tier; it is meant to be unchanged");
        assertTrue(signedIn.contains(draft));
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /publication/{ids} — @PermitAll. Open to anonymous; known id -> one element, unknown -> none.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getPublication_knownIdReturnsSingleMatch() {
        given().when().get(PUBLICATION + seededId)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("size()", is(1))
                .body("[0].publicationId", is(seededId));
    }

    @Test
    void getPublication_unknownIdReturnsEmptyList() {
        given().when().get(PUBLICATION + "no-such-id")
                .then().statusCode(200)
                .body("size()", is(0));
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /search-details — @RolesAllowed(ADMIN), like /publication-series/search-details: the system
    // shape carries every domain's administration configuration. anon -> 401; editor token WITHOUT
    // the NiordDomain header -> 403; user and editor WITH it -> 403; admin -> 200 + data list.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchDetails_rejectsAnonymousWith401() {
        given().when().get(SEARCH_DETAILS).then().statusCode(401);
    }

    @Test
    void searchDetails_withoutDomainHeaderForbidsEditorWith403() {
        // No NiordDomain header -> the augmentor never grants 'user', so even an editor is below the bar.
        given().auth().oauth2(accessToken("editor")).when().get(SEARCH_DETAILS).then().statusCode(403);
    }

    @Test
    void searchDetails_forbidsUserAndEditorWith403() {
        as(accessToken("user")).when().get(SEARCH_DETAILS).then().statusCode(403);
        as(accessToken("editor")).when().get(SEARCH_DETAILS).then().statusCode(403);
    }

    @Test
    void searchDetails_allowsAdminAndReturnsPagedResult() {
        as(accessToken("admin")).when().get(SEARCH_DETAILS)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("data", notNullValue())
                .body("total", greaterThanOrEqualTo(0));
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /editable-publication/{id} — @RolesAllowed(ADMIN). anon -> 401; editor one tier too low -> 403;
    // admin -> 200 with the seeded publication's id.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void editablePublication_rejectsAnonymousWith401() {
        given().when().get(EDITABLE + seededId).then().statusCode(401);
    }

    @Test
    void editablePublication_forbidsEditorWith403() {
        // KEY adjacent-tier case: an authenticated editor lacks 'admin'.
        as(accessToken("editor")).when().get(EDITABLE + seededId).then().statusCode(403);
    }

    @Test
    void editablePublication_allowsAdminAndReturnsSeededPublication() {
        as(accessToken("admin")).when().get(EDITABLE + seededId)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("publicationId", is(seededId));
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /new-publication-template — @RolesAllowed(ADMIN). anon -> 401; editor -> 403; admin -> 200.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void newTemplate_rejectsAnonymousWith401() {
        given().when().get(NEW_TEMPLATE + "?mainType=PUBLICATION").then().statusCode(401);
    }

    @Test
    void newTemplate_forbidsEditorWith403() {
        as(accessToken("editor")).when().get(NEW_TEMPLATE + "?mainType=PUBLICATION").then().statusCode(403);
    }

    @Test
    void newTemplate_allowsAdmin() {
        as(accessToken("admin")).when().get(NEW_TEMPLATE + "?mainType=PUBLICATION")
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("mainType", is("PUBLICATION"));
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /publication/ — @RolesAllowed(ADMIN). anon -> 401; editor one tier too low -> 403.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createPublication_rejectsAnonymousWith401() {
        given().contentType(ContentType.JSON).body(body("IT-PUB", "PUBLICATION"))
                .when().post(PUBLICATION).then().statusCode(401);
    }

    @Test
    void createPublication_forbidsEditorWith403() {
        as(accessToken("editor")).contentType(ContentType.JSON).body(body("IT-PUB", "PUBLICATION"))
                .when().post(PUBLICATION).then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /publication/{id} — @RolesAllowed(ADMIN). A path id that does not match the body's
    // publicationId is rejected with 400 before any deserialization-dependent work runs.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updatePublication_withMismatchedIdReturns400() {
        as(accessToken("admin")).contentType(ContentType.JSON).body(body("IT-PUB-B", "PUBLICATION"))
                .when().put(PUBLICATION + "IT-PUB-A")
                .then().statusCode(400);
    }

    // ------------------------------------------------------------------------------------------- //
    // DELETE /publication/{id} — @RolesAllowed(ADMIN). anon -> 401; editor one tier too low -> 403.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void deletePublication_rejectsAnonymousWith401() {
        given().when().delete(PUBLICATION + "IT-PUB-X").then().statusCode(401);
    }

    @Test
    void deletePublication_forbidsEditorWith403() {
        as(accessToken("editor")).when().delete(PUBLICATION + "IT-PUB-X").then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /update-status — @RolesAllowed(ADMIN). anon -> 401; editor -> 403; admin on an unknown
    // publication id -> 400 (updateStatus throws, is caught and rethrown as 400).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void updateStatus_rejectsAnonymousWith401() {
        given().contentType(ContentType.JSON).body(Map.of("publicationId", "x", "status", "ACTIVE"))
                .when().put(UPDATE_STATUS).then().statusCode(401);
    }

    @Test
    void updateStatus_forbidsEditorWith403() {
        as(accessToken("editor")).contentType(ContentType.JSON).body(Map.of("publicationId", "x", "status", "ACTIVE"))
                .when().put(UPDATE_STATUS).then().statusCode(403);
    }

    @Test
    void updateStatus_adminUnknownPublicationReturns400() {
        as(accessToken("admin")).contentType(ContentType.JSON)
                .body(Map.of("publicationId", "no-such-pub", "status", "ACTIVE"))
                .when().put(UPDATE_STATUS)
                .then().statusCode(400);
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /upload-publications — @RolesAllowed(ADMIN), multipart. anon -> 401.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void uploadPublications_rejectsAnonymousWith401() {
        given().multiPart("file", "x.json", new byte[]{1, 2, 3})
                .when().post(UPLOAD).then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /export — @PermitAll, but ADMIN enforced programmatically -> wrong identity is 403, not 401
    // (the @PermitAll handler is reached, then throws NiordWebException(403)). admin -> 200 + array.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void export_rejectsAnonymousWith403() {
        given().when().get(EXPORT).then().statusCode(403);
    }

    @Test
    void export_forbidsEditorWith403() {
        as(accessToken("editor")).when().get(EXPORT).then().statusCode(403);
    }

    @Test
    void export_allowsAdminAndReturnsJsonArray() {
        as(accessToken("admin")).when().get(EXPORT)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(0));
    }
}
