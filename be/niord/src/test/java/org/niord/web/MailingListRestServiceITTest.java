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

import org.springframework.transaction.annotation.Transactional;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

/**
 * Integration tests for {@link MailingListRestService}: the HTTP contract and authorization of the
 * {@code /rest/mailing-lists} endpoints.
 *
 * <p><b>Authorization model under test.</b> The class default is {@code @RolesAllowed(ADMIN)} (search,
 * get-details, recipient users/contacts, update-status). Life-cycle writes (create/update/delete),
 * import upload and {@code test-execute-trigger} require {@code @RolesAllowed(SYSADMIN)}; reading the
 * report list requires {@code @RolesAllowed(USER)}. Two endpoints are declared {@code @PermitAll} but
 * enforce a role <em>programmatically</em> inside the handler: {@code /export} requires
 * {@code SYSADMIN} and {@code /execute-report/{id}.html} requires {@code USER} via
 * {@code userService.isCallerInRole(...)}. Because {@code @PermitAll} lets even an anonymous caller
 * reach the handler, those two reject the wrong identity with {@code 403} (the programmatic check
 * throws {@code NiordWebException(403)}) rather than the container's {@code 401}.
 *
 * <p><b>Tier oracle.</b> The role hierarchy is {@code sysadmin > admin > editor > user}
 * (see {@link org.niord.core.user.Roles}). The four seeded identities (see {@link KeycloakTestTokens})
 * give one token per tier. Every authenticated request carries {@code NiordDomain: niord-nw} so
 * the domain augmentor realizes the full role chain (the only source of the {@code user} role); see
 * {@link RoleAuthorizationMatrixITTest} for the augmentor rationale. A missing token yields the
 * container's {@code 401} on {@code @RolesAllowed} endpoints; an authenticated identity one tier too low
 * yields {@code 403}.
 *
 * <p><b>Seeded data oracle.</b> The seed contains five mailing lists — {@code audio-broadcast},
 * {@code navtex-baltico}, {@code navtex-rogaland}, {@code navwarn-overview}, {@code navwarn-update} — all
 * persisted with {@code active = false} (see db/niord-test-seed.sql). So {@code /search} returns at least
 * five rows and {@code audio-broadcast} reports {@code active == false}.
 *
 * <p><b>Create/update/delete oracle.</b> {@code addMailingList} and {@code updateMailingList} serialize
 * the result with {@code DataFilter.get()} (no {@code MailingList.details} component), so their response
 * bodies carry only {@code mailingListId} and {@code active} — never {@code descs}; the {@code descs} and
 * persisted {@code active} flag are therefore re-read through the ADMIN get-details endpoint (which uses
 * {@code LIST_DETAILS_FILTER}). {@code deleteMailingList} is a {@code void} handler, so a successful delete
 * returns {@code 204}; deleting an unknown id is a no-op that also returns {@code 204}
 * ({@code findByMailingListId} returns null and the service returns false silently).
 *
 * <p>HTTP requests run in the server's own transaction, which {@code @Transactional} cannot roll back,
 * so created lists are tracked by id and best-effort deleted as sysadmin in {@link #cleanup()} using
 * unique {@code IT-REST-ML-} ids that never collide with seeded rows.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MailingListRestServiceITTest extends ITTest {

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    private static final String SEARCH        = "/rest/mailing-lists/search";
    private static final String EXPORT        = "/rest/mailing-lists/export";
    private static final String REPORTS       = "/rest/mailing-lists/reports";
    private static final String MAILING_LIST  = "/rest/mailing-lists/mailing-list/";
    private static final String TRIGGER_TEST  = "/rest/mailing-lists/test-execute-trigger";

    /** A seeded mailing list (active == false). */
    private static final String SEEDED_ID = "audio-broadcast";

    /** A request carrying the given bearer token and the {@code NiordDomain} header (full role chain). */
    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    private final List<String> createdIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String id : createdIds) {
            try {
                as(accessToken()).when().delete(MAILING_LIST + id);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdIds.clear();
    }

    /** Body for a minimal, valid mailing list with the given id and active flag. */
    private static Map<String, Object> body(String id, boolean active) {
        return Map.of(
                "mailingListId", id,
                "active", active,
                "descs", List.of(Map.of("lang", "en", "name", "IT REST list")));
    }

    /** Creates a mailing list as sysadmin, asserts 200 + echoed id, and tracks it for cleanup. */
    private String createAsSysadmin(String id, boolean active) {
        as(accessToken()).contentType(ContentType.JSON).body(body(id, active))
                .when().post(MAILING_LIST)
                .then().statusCode(200)
                .body("mailingListId", is(id));
        createdIds.add(id);
        return id;
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /search — class default @RolesAllowed(ADMIN). user/editor below the bar -> 403;
    // admin/sysadmin allowed and see the >= 5 seeded lists; anonymous -> 401.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void search_rejectsAnonymousWith401() {
        given().when().get(SEARCH).then().statusCode(401);
    }

    @Test
    void search_forbidsUserWith403() {
        as(accessToken("user")).when().get(SEARCH).then().statusCode(403);
    }

    @Test
    void search_forbidsEditorWith403() {
        // KEY adjacent-tier case: an authenticated editor lacks 'admin'.
        as(accessToken("editor")).when().get(SEARCH).then().statusCode(403);
    }

    @Test
    void search_allowsAdminAndReturnsSeededLists() {
        as(accessToken("admin")).when().get(SEARCH)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(5))
                .body("mailingListId", org.hamcrest.Matchers.hasItem(SEEDED_ID));
    }

    @Test
    void search_allowsSysadmin() {
        as(accessToken()).when().get(SEARCH)
                .then().statusCode(200).body("size()", greaterThanOrEqualTo(5));
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /mailing-list/{id} — @RolesAllowed(ADMIN).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getDetails_allowsAdminAndReturnsInactiveSeededList() {
        as(accessToken("admin")).when().get(MAILING_LIST + SEEDED_ID)
                .then().statusCode(200)
                .body("mailingListId", is(SEEDED_ID))
                .body("active", is(false));
    }

    @Test
    void getDetails_rejectsAnonymousWith401() {
        given().when().get(MAILING_LIST + SEEDED_ID).then().statusCode(401);
    }

    @Test
    void getDetails_unknownIdReturns404() {
        as(accessToken("admin")).when().get(MAILING_LIST + "IT-REST-ML-does-not-exist")
                .then().statusCode(404);
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /mailing-list/ — @RolesAllowed(SYSADMIN). admin is one tier too low -> 403.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void create_rejectsAnonymousWith401() {
        given().contentType(ContentType.JSON).body(body("IT-REST-ML-" + System.nanoTime(), true))
                .when().post(MAILING_LIST).then().statusCode(401);
    }

    @Test
    void create_forbidsAdminWith403() {
        // KEY adjacent-tier case: an authenticated admin lacks 'sysadmin'.
        as(accessToken("admin")).contentType(ContentType.JSON).body(body("IT-REST-ML-" + System.nanoTime(), true))
                .when().post(MAILING_LIST).then().statusCode(403);
    }

    @Test
    void create_allowsSysadminAndEchoesId() {
        String id = "IT-REST-ML-" + System.nanoTime();
        createAsSysadmin(id, true);
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /mailing-list/{id} — @RolesAllowed(SYSADMIN). Mismatched path/body id -> 400.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void update_forbidsAdminWith403() {
        as(accessToken("admin")).contentType(ContentType.JSON).body(body(SEEDED_ID, true))
                .when().put(MAILING_LIST + SEEDED_ID).then().statusCode(403);
    }

    @Test
    void update_withMismatchedIdReturns400() {
        String id = createAsSysadmin("IT-REST-ML-" + System.nanoTime(), true);

        as(accessToken()).contentType(ContentType.JSON)
                .body(body(id + "-other", false))
                .when().put(MAILING_LIST + id)
                .then().statusCode(400);

        // The mismatched update must not have altered the original list.
        as(accessToken("admin")).when().get(MAILING_LIST + id)
                .then().statusCode(200).body("active", is(true));
    }

    @Test
    void update_consistentIdReturns200() {
        String id = createAsSysadmin("IT-REST-ML-" + System.nanoTime(), true);

        as(accessToken()).contentType(ContentType.JSON).body(body(id, false))
                .when().put(MAILING_LIST + id)
                .then().statusCode(200)
                .body("mailingListId", is(id));
    }

    // ------------------------------------------------------------------------------------------- //
    // DELETE /mailing-list/{id} — @RolesAllowed(SYSADMIN).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void delete_forbidsAdminWith403() {
        String id = createAsSysadmin("IT-REST-ML-" + System.nanoTime(), true);

        as(accessToken("admin")).when().delete(MAILING_LIST + id).then().statusCode(403);

        // The forbidden delete must not have removed the list.
        as(accessToken("admin")).when().get(MAILING_LIST + id).then().statusCode(200);
    }

    @Test
    void delete_asSysadminRemovesListThenGetReturns404() {
        String id = createAsSysadmin("IT-REST-ML-" + System.nanoTime(), true);

        as(accessToken()).when().delete(MAILING_LIST + id)
                .then().statusCode(is(204));
        createdIds.remove(id);

        as(accessToken("admin")).when().get(MAILING_LIST + id).then().statusCode(404);
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /mailing-list/{id}/users — @RolesAllowed(ADMIN).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getRecipientUsers_allowsAdmin() {
        as(accessToken("admin")).when().get(MAILING_LIST + SEEDED_ID + "/users")
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("selectedRecipients.size()", greaterThanOrEqualTo(0))
                .body("availableRecipients.size()", greaterThanOrEqualTo(0));
    }

    @Test
    void getRecipientUsers_rejectsAnonymousWith401() {
        given().when().get(MAILING_LIST + SEEDED_ID + "/users").then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /export — @PermitAll, but SYSADMIN enforced programmatically -> wrong identity is 403,
    // not 401 (the @PermitAll handler is reached, then throws NiordWebException(403)).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void export_rejectsAnonymousWith403() {
        given().when().get(EXPORT).then().statusCode(403);
    }

    @Test
    void export_forbidsAdminWith403() {
        as(accessToken("admin")).when().get(EXPORT).then().statusCode(403);
    }

    @Test
    void export_allowsSysadminAndReturnsJsonArray() {
        as(accessToken()).when().get(EXPORT)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(5));
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /reports — @RolesAllowed(USER). Lowest tier: any authenticated identity passes; anon -> 401.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void reports_rejectsAnonymousWith401() {
        given().when().get(REPORTS).then().statusCode(401);
    }

    @Test
    void reports_allowsUser() {
        as(accessToken("user")).when().get(REPORTS)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(0));
    }

    @Test
    void reports_allowsSysadmin() {
        as(accessToken()).when().get(REPORTS).then().statusCode(200);
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /execute-report/{id}.html — @PermitAll, but USER enforced programmatically. Anonymous is
    // rejected with 403 (the programmatic isCallerInRole(USER) check fails before any lookup).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void executeReport_rejectsAnonymousWith403() {
        given().when().get("/rest/mailing-lists/execute-report/999999999.html")
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /test-execute-trigger — @RolesAllowed(SYSADMIN). admin one tier too low -> 403; anon -> 401.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void testExecuteTrigger_rejectsAnonymousWith401() {
        given().contentType(ContentType.JSON).body(body(SEEDED_ID, false))
                .when().post(TRIGGER_TEST).then().statusCode(401);
    }

    @Test
    void testExecuteTrigger_forbidsAdminWith403() {
        as(accessToken("admin")).contentType(ContentType.JSON).body(body(SEEDED_ID, false))
                .when().post(TRIGGER_TEST).then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // CRUD persistence side-effects (as sysadmin): create -> read active=true; update active=false ->
    // read active=false; delete -> read 404. The active flag is re-read through the ADMIN get-details
    // endpoint (LIST_DETAILS_FILTER) since the create/update responses use DataFilter.get().
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createThenGetDetails_reportsActiveTrue() {
        String id = createAsSysadmin("IT-REST-ML-" + System.nanoTime(), true);

        as(accessToken("admin")).when().get(MAILING_LIST + id)
                .then().statusCode(200)
                .body("mailingListId", is(id))
                .body("active", is(true));
    }

    @Test
    void updateActiveFalseThenGetDetails_reportsActiveFalse() {
        String id = createAsSysadmin("IT-REST-ML-" + System.nanoTime(), true);

        as(accessToken("admin")).when().get(MAILING_LIST + id).then().statusCode(200).body("active", is(true));

        as(accessToken()).contentType(ContentType.JSON).body(body(id, false))
                .when().put(MAILING_LIST + id).then().statusCode(200);

        as(accessToken("admin")).when().get(MAILING_LIST + id)
                .then().statusCode(200).body("active", is(false));
    }

    @Test
    void deleteThenGetDetails_returns404() {
        String id = createAsSysadmin("IT-REST-ML-" + System.nanoTime(), true);

        as(accessToken("admin")).when().get(MAILING_LIST + id).then().statusCode(200);

        as(accessToken()).when().delete(MAILING_LIST + id).then().statusCode(is(204));
        createdIds.remove(id);

        as(accessToken("admin")).when().get(MAILING_LIST + id).then().statusCode(404);
    }
}
