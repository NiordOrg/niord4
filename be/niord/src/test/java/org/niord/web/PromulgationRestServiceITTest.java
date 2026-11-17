/*
 * Copyright 2017 Danish Maritime Authority.
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link PromulgationRestService}: the HTTP contract of the
 * {@code /rest/promulgations} endpoints, exercised against a real Keycloak token (see
 * {@link KeycloakTestTokens}) and the three seeded promulgation types ({@code navtex},
 * {@code audio}, {@code twitter}).
 *
 * <p>The endpoints split into three authorization tiers:
 * <ul>
 *   <li>{@code /promulgation-services/all}, the create/update/delete CRUD endpoints —
 *       {@code @RolesAllowed(SYSADMIN)} — so a missing token yields 401.</li>
 *   <li>{@code /promulgation-type/{typeId}} and {@code /generate/{typeId}} —
 *       {@code @RolesAllowed(EDITOR)} — also 401 without a token. The cached sysadmin token
 *       carries {editor, admin, sysadmin} and therefore satisfies both EDITOR and SYSADMIN.</li>
 *   <li>{@code /promulgation-types/all} — {@code @PermitAll} but with a <em>programmatic</em>
 *       SYSADMIN check that throws {@code NiordWebException(403)} for a non-sysadmin caller;
 *       an anonymous request is therefore <strong>allowed in</strong> and then rejected with 403
 *       (not 401).</li>
 *   <li>{@code /public-promulgation-type/{typeIds}} and {@code /search-public-promulgation-type} —
 *       {@code @PermitAll} anonymous projections that expose only {typeId, name, active}.</li>
 * </ul>
 *
 * <p>A key assertion is the public-projection LEAK check: {@code PublicPromulgationTypeVo} omits the
 * internal {@code serviceId} and {@code language} fields, so the anonymous projection responses must
 * not carry them (they serialize as absent/null).
 *
 * <p>The CRUD round-trip runs through the server's own transaction, which is committed (not
 * {@code @Transactional}-rolled-back), so the created {@code it-rest-promulg} row is removed in
 * {@link #cleanup()} (best-effort) to keep the shared seed database pristine and tests independent.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class PromulgationRestServiceITTest extends ITTest {

    /** The typeId created and torn down by the CRUD round-trip test. */
    private static final String CRUD_TYPE_ID = "it-rest-promulg";

    /** Best-effort removal of the CRUD test row so a half-failed test never leaks committed state. */
    @AfterEach
    void cleanup() {
        try {
            given().auth().oauth2(accessToken())
                    .when().delete("/rest/promulgations/promulgation-type/" + CRUD_TYPE_ID);
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // (1) GET /promulgation-services/all  [@RolesAllowed(SYSADMIN)]
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getPromulgationServicesWithoutTokenIsUnauthorized() {
        given()
                .when().get("/rest/promulgations/promulgation-services/all")
                .then().statusCode(401);
    }

    @Test
    void getPromulgationServicesReturnsRegisteredServices() {
        // The plug-in promulgation services registered with the PromulgationManager include at least
        // navtex, safetynet and twitter.
        given().auth().oauth2(accessToken())
                .when().get("/rest/promulgations/promulgation-services/all")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("serviceId", hasItems("navtex", "safetynet", "twitter"));
    }

    // ------------------------------------------------------------------------------------------- //
    // (2) GET /promulgation-types/all  [@PermitAll + programmatic SYSADMIN check -> 403]
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getPromulgationTypesWithoutTokenIsForbiddenNotUnauthorized() {
        // The endpoint is @PermitAll, so an anonymous request is admitted and then rejected by the
        // programmatic SYSADMIN check with 403 (not 401).
        given()
                .when().get("/rest/promulgations/promulgation-types/all")
                .then().statusCode(403);
    }

    @Test
    void getPromulgationTypesAsSysadminReturnsAllSeededTypes() {
        given().auth().oauth2(accessToken())
                .when().get("/rest/promulgations/promulgation-types/all")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("typeId", hasItems("navtex", "audio", "twitter"));
    }

    // ------------------------------------------------------------------------------------------- //
    // (3) GET /promulgation-type/{typeId}  [@RolesAllowed(EDITOR)]
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getPromulgationTypeWithoutTokenIsUnauthorized() {
        given()
                .when().get("/rest/promulgations/promulgation-type/navtex")
                .then().statusCode(401);
    }

    @Test
    void getPromulgationTypeReturnsSeededNavtexType() {
        given().auth().oauth2(accessToken())
                .when().get("/rest/promulgations/promulgation-type/navtex")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("typeId", is("navtex"))
                .body("serviceId", is("navtex"))
                .body("name", is("NAVTEX"));
    }

    // ------------------------------------------------------------------------------------------- //
    // (4) GET /public-promulgation-type/{typeIds}  [@PermitAll] — anonymous projection + LEAK check
    // ------------------------------------------------------------------------------------------- //

    @Test
    void publicPromulgationTypesAnonymousReturnsProjectionWithoutInternalFields() {
        // Anonymous access (no token) is allowed; the public projection exposes only typeId/name/active.
        given()
                .when().get("/rest/promulgations/public-promulgation-type/navtex,twitter")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", hasSize(2))
                .body("typeId", hasItems("navtex", "twitter"))
                // LEAK check: internal fields serviceId/language are absent from the public projection.
                .body("serviceId", everyItem(nullValue()))
                .body("language", everyItem(nullValue()));
    }

    @Test
    void publicPromulgationTypeSingleReturnsOneEntry() {
        given()
                .when().get("/rest/promulgations/public-promulgation-type/navtex")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", hasSize(1))
                .body("[0].typeId", is("navtex"))
                .body("[0].name", is("NAVTEX"))
                // LEAK check on the single-entry projection.
                .body("[0].serviceId", nullValue())
                .body("[0].language", nullValue());
    }

    // ------------------------------------------------------------------------------------------- //
    // (5) GET /search-public-promulgation-type?type=  [@PermitAll]
    // ------------------------------------------------------------------------------------------- //

    @Test
    void searchPublicPromulgationTypeWithBlankTypeReturnsEmptyArray() {
        given()
                .queryParam("type", "")
                .when().get("/rest/promulgations/search-public-promulgation-type")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", hasSize(0));
    }

    @Test
    void searchPublicPromulgationTypeMatchesByPrefix() {
        given()
                .queryParam("type", "nav")
                .when().get("/rest/promulgations/search-public-promulgation-type")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("typeId", hasItem("navtex"))
                // LEAK check carries through the search projection too.
                .body("serviceId", everyItem(nullValue()));
    }

    @Test
    void searchPublicPromulgationTypeNoMatchReturnsEmptyArray() {
        given()
                .queryParam("type", "ZZZ-no-match")
                .when().get("/rest/promulgations/search-public-promulgation-type")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", hasSize(0));
    }

    // ------------------------------------------------------------------------------------------- //
    // (6) Authorization boundaries — mutating endpoints without a token return 401
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createPromulgationTypeWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of())
                .when().post("/rest/promulgations/promulgation-type/")
                .then().statusCode(401);
    }

    @Test
    void updatePromulgationTypeWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of())
                .when().put("/rest/promulgations/promulgation-type/navtex")
                .then().statusCode(401);
    }

    @Test
    void deletePromulgationTypeWithoutTokenIsUnauthorized() {
        given()
                .when().delete("/rest/promulgations/promulgation-type/navtex")
                .then().statusCode(401);
    }

    @Test
    void generateMessagePromulgationWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of())
                .when().post("/rest/promulgations/generate/navtex")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // (7) Full create -> read-public -> delete round-trip as sysadmin (persistence side effects)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createReadPublicAndDeletePromulgationTypeRoundTrip() {
        // Create through the SYSADMIN-protected POST endpoint, attaching to the seeded navtex service.
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "typeId", CRUD_TYPE_ID,
                        "serviceId", "navtex",
                        "name", "IT REST Promulg",
                        "priority", 42,
                        "active", true))
                .when().post("/rest/promulgations/promulgation-type/")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("typeId", is(CRUD_TYPE_ID))
                .body("name", is("IT REST Promulg"))
                .body("serviceId", is("navtex"))
                .body("priority", is(42));

        // Side-effect: the created type is now retrievable via the anonymous public projection.
        given()
                .when().get("/rest/promulgations/public-promulgation-type/" + CRUD_TYPE_ID)
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", hasSize(1))
                .body("[0].typeId", is(CRUD_TYPE_ID))
                .body("[0].name", is("IT REST Promulg"));

        // Delete (RESTEasy void DELETE returns 204; tolerate 200 as well).
        int deleteStatus = given().auth().oauth2(accessToken())
                .when().delete("/rest/promulgations/promulgation-type/" + CRUD_TYPE_ID)
                .then().extract().statusCode();
        assertTrue(deleteStatus == 204 || deleteStatus == 200,
                "void DELETE must return a 2xx no-content/ok status, was " + deleteStatus);

        // Side-effect: the row is gone — the public projection no longer returns it.
        given()
                .when().get("/rest/promulgations/public-promulgation-type/" + CRUD_TYPE_ID)
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", hasSize(0))
                .body("typeId", not(hasItem(CRUD_TYPE_ID)));
    }
}
