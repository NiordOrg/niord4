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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link NiordIntegrationRestService}: the HTTP contract of the
 * {@code /rest/niord-integrations} endpoints, including authorization.
 *
 * <p>The whole resource is annotated {@code @RolesAllowed(SYSADMIN)} at class level, so every endpoint
 * (the {@code /all} list, the {@code /niord-integration/...} CRUD operations, {@code /execute/{id}}) is
 * sysadmin-only, like the admin application's page for it. An integration imports into the message
 * series it names by id, in any domain, while the ADMIN role is granted per domain, so an admin — with
 * or without a {@code NiordDomain} header — is refused with 403. The negative-security cases expect
 * HTTP 401 with no token and 403 with the {@code testadmin} token. The positive cases use a real
 * Keycloak sysadmin token (see {@code KeycloakTestTokens}), whose base roles carry {@code sysadmin}, so
 * no {@code NiordDomain} header is needed.
 *
 * <p>HTTP requests run in the server's own transaction, which {@code @Transactional} cannot roll
 * back, so created integrations are committed. Each test uses a unique {@code http://it-rest-...}
 * URL (suffixed with {@link System#nanoTime()}) and the created ids are deleted in {@link #cleanup()}.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class NiordIntegrationRestServiceITTest extends ITTest {

    private final List<Integer> createdIntegrationIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Integer id : createdIntegrationIds) {
            try {
                given().auth().oauth2(accessToken())
                        .delete("/rest/niord-integrations/niord-integration/" + id);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdIntegrationIds.clear();
    }

    /** A unique integration URL per test so committed rows never collide between runs. */
    private static String uniqueUrl() {
        return "http://it-rest-" + System.nanoTime();
    }

    /**
     * Creates an integration with a single defined message-series mapping as sysadmin, asserts the
     * create response, tracks the id for cleanup and returns it.
     */
    private int createIntegrationAsSysadmin(String url) {
        int id = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "url", url,
                        "active", true,
                        "assignNewUids", false,
                        "createBaseData", false,
                        "messageSeriesMappings", List.of(Map.of(
                                "sourceSeriesId", "a",
                                "targetSeriesId", "b"))))
                .when().post("/rest/niord-integrations/niord-integration/")
                .then().statusCode(200)
                .body("url", is(url))
                // createNiordIntegration() always computes nextScheduledExecution (~60 min ahead).
                .body("nextScheduledExecution", notNullValue())
                .extract().path("id");
        createdIntegrationIds.add(id);
        return id;
    }

    // ------------------------------------------------------------------------------------------- //
    // Negative security — every endpoint is @RolesAllowed(SYSADMIN), so no token -> 401
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getAllWithoutTokenIsUnauthorized() {
        given()
                .when().get("/rest/niord-integrations/all")
                .then().statusCode(401);
    }

    @Test
    void createWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of(
                        "url", uniqueUrl(),
                        "active", true,
                        "assignNewUids", false,
                        "createBaseData", false))
                .when().post("/rest/niord-integrations/niord-integration/")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // Negative security — an admin (of the base client or of a domain) is not a sysadmin -> 403
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getAllAsAdminIsForbidden() {
        given().auth().oauth2(accessToken("admin"))
                .when().get("/rest/niord-integrations/all")
                .then().statusCode(403);
    }

    @Test
    void createAsDomainAdminIsForbidden() {
        // The NiordDomain header adds testadmin's roles on niord-nw - admin, not sysadmin. The
        // request is refused before the handler runs, so nothing is created.
        given().auth().oauth2(accessToken("admin"))
                .header("NiordDomain", "niord-nw")
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "url", uniqueUrl(),
                        "active", true,
                        "assignNewUids", false,
                        "createBaseData", false,
                        "messageSeriesMappings", List.of(Map.of(
                                "sourceSeriesId", "a",
                                "targetSeriesId", "b"))))
                .when().post("/rest/niord-integrations/niord-integration/")
                .then().statusCode(403);
    }

    @Test
    void executeAsAdminIsForbidden() {
        int id = createIntegrationAsSysadmin(uniqueUrl());

        given().auth().oauth2(accessToken("admin"))
                .header("NiordDomain", "niord-nw")
                .when().put("/rest/niord-integrations/execute/" + id)
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // URL validation — the write-time half of the SSRF guard (SECAUD-9)
    // ------------------------------------------------------------------------------------------- //

    /**
     * The importer only ever speaks HTTP to another Niord server, so a foreign scheme could only be an
     * attempt to reach something else. Rejected at create time, before a row that can never fetch is
     * persisted.
     */
    @Test
    void createWithNonHttpSchemeIsBadRequest() {
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "url", "file:///etc/passwd",
                        "active", true,
                        "assignNewUids", false,
                        "createBaseData", false))
                .when().post("/rest/niord-integrations/niord-integration/")
                .then().statusCode(400);
    }

    /** Credentials embedded in the URL would be sent to the remote server and logged with the URL. */
    @Test
    void createWithCredentialsInTheUrlIsBadRequest() {
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "url", "http://user:pw@it-rest-" + System.nanoTime(),
                        "active", true,
                        "assignNewUids", false,
                        "createBaseData", false))
                .when().post("/rest/niord-integrations/niord-integration/")
                .then().statusCode(400);
    }

    /**
     * The syntax check must NOT resolve DNS: an integration point is routinely defined before the
     * remote server exists. This is the shape every other test in this class uses, so a guard that
     * started resolving would break them all — pin it explicitly.
     */
    @Test
    void createWithAnUnresolvableButWellFormedHostIsAccepted() {
        int id = createIntegrationAsSysadmin(uniqueUrl());
        assertTrue(id > 0, "a well-formed http URL is accepted even though its host does not resolve");
    }

    // ------------------------------------------------------------------------------------------- //
    // Positive role-based — sysadmin with a real token
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getAllAsAdminReturnsJsonArray() {
        // The resource returns a JSON list; with a fresh DB it may be empty, so assert >= 0 size,
        // which still proves the body is a JSON array (size() only resolves on a list).
        given().auth().oauth2(accessToken())
                .when().get("/rest/niord-integrations/all")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(0));
    }

    @Test
    void createPersistsAndIsReadableById() {
        String url = uniqueUrl();
        int id = createIntegrationAsSysadmin(url);

        given().auth().oauth2(accessToken())
                .when().get("/rest/niord-integrations/niord-integration/" + id)
                .then().statusCode(200)
                .body("id", is(id))
                .body("url", is(url))
                .body("active", is(true))
                .body("assignNewUids", is(false))
                .body("createBaseData", is(false));
    }

    @Test
    void getUnknownIdReturns404() {
        // INT1: a GET of an unknown/deleted integration id no longer NPEs into a 500; the handler
        // null-checks the lookup and throws NiordWebException(404), mapped to HTTP 404.
        given().auth().oauth2(accessToken())
                .when().get("/rest/niord-integrations/niord-integration/" + Integer.MAX_VALUE)
                .then().statusCode(404);
    }

    @Test
    void createPersistsTheDefinedMessageSeriesMapping() {
        String url = uniqueUrl();
        int id = createIntegrationAsSysadmin(url);

        given().auth().oauth2(accessToken())
                .when().get("/rest/niord-integrations/niord-integration/" + id)
                .then().statusCode(200)
                .body("messageSeriesMappings.size()", is(1))
                .body("messageSeriesMappings[0].sourceSeriesId", is("a"))
                .body("messageSeriesMappings[0].targetSeriesId", is("b"));
    }

    @Test
    void createDropsBlankMessageSeriesMappings() {
        // NiordIntegration(NiordIntegrationVo) filters out mappings via MessageSeriesMappingVo.mappingDefined(),
        // which requires both ids non-blank. A mapping missing its targetSeriesId is therefore not persisted.
        String url = uniqueUrl();
        int id = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "url", url,
                        "active", true,
                        "assignNewUids", false,
                        "createBaseData", false,
                        "messageSeriesMappings", List.of(Map.of(
                                "sourceSeriesId", "only-source"))))
                .when().post("/rest/niord-integrations/niord-integration/")
                .then().statusCode(200)
                .extract().path("id");
        createdIntegrationIds.add(id);

        given().auth().oauth2(accessToken())
                .when().get("/rest/niord-integrations/niord-integration/" + id)
                .then().statusCode(200)
                .body("messageSeriesMappings.size()", is(0));
    }

    @Test
    void getByIdReturnsTheCreatedIntegration() {
        String url = uniqueUrl();
        int id = createIntegrationAsSysadmin(url);

        given().auth().oauth2(accessToken())
                .when().get("/rest/niord-integrations/niord-integration/" + id)
                .then().statusCode(200)
                .body("id", is(id))
                .body("url", is(url));
    }

    @Test
    void updateChangesUrlAndActiveFlag() {
        String url = uniqueUrl();
        int id = createIntegrationAsSysadmin(url);

        String newUrl = uniqueUrl();
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "id", id,
                        "url", newUrl,
                        "active", false,
                        "assignNewUids", false,
                        "createBaseData", false,
                        "messageSeriesMappings", List.of(Map.of(
                                "sourceSeriesId", "a",
                                "targetSeriesId", "b"))))
                .when().put("/rest/niord-integrations/niord-integration/" + id)
                .then().statusCode(200)
                .body("id", is(id))
                .body("url", is(newUrl))
                .body("active", is(false));

        // The change is persisted and visible on a fresh read.
        given().auth().oauth2(accessToken())
                .when().get("/rest/niord-integrations/niord-integration/" + id)
                .then().statusCode(200)
                .body("url", is(newUrl))
                .body("active", is(false));
    }

    @Test
    void deleteRemovesTheIntegration() {
        String url = uniqueUrl();
        int id = createIntegrationAsSysadmin(url);

        // The endpoint returns void -> HTTP 204 with no body.
        given().auth().oauth2(accessToken())
                .when().delete("/rest/niord-integrations/niord-integration/" + id)
                .then().statusCode(204)
                .body(is(""));

        // findById() returns null after deletion; toVo() on null throws -> not a 200 read anymore.
        given().auth().oauth2(accessToken())
                .when().get("/rest/niord-integrations/niord-integration/" + id)
                .then().statusCode(greaterThanOrEqualTo(400));

        // Already gone — drop it so @AfterEach does not attempt a second delete.
        createdIntegrationIds.remove((Integer) id);
    }

    @Test
    void crudRoundTrip() {
        // POST
        String url = uniqueUrl();
        int id = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "url", url,
                        "active", true,
                        "assignNewUids", false,
                        "createBaseData", false,
                        "messageSeriesMappings", List.of(Map.of(
                                "sourceSeriesId", "a",
                                "targetSeriesId", "b"))))
                .when().post("/rest/niord-integrations/niord-integration/")
                .then().statusCode(200)
                .body("url", is(url))
                .body("nextScheduledExecution", notNullValue())
                .extract().path("id");
        createdIntegrationIds.add(id);

        // GET
        given().auth().oauth2(accessToken())
                .when().get("/rest/niord-integrations/niord-integration/" + id)
                .then().statusCode(200)
                .body("id", is(id))
                .body("url", is(url));

        // PUT
        String updatedUrl = uniqueUrl();
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "id", id,
                        "url", updatedUrl,
                        "active", false,
                        "assignNewUids", true,
                        "createBaseData", true,
                        "messageSeriesMappings", List.of(Map.of(
                                "sourceSeriesId", "a",
                                "targetSeriesId", "b"))))
                .when().put("/rest/niord-integrations/niord-integration/" + id)
                .then().statusCode(200)
                .body("url", is(updatedUrl))
                .body("active", is(false))
                .body("assignNewUids", is(true))
                .body("createBaseData", is(true));

        // DELETE
        given().auth().oauth2(accessToken())
                .when().delete("/rest/niord-integrations/niord-integration/" + id)
                .then().statusCode(204)
                .body(is(""));
        createdIntegrationIds.remove((Integer) id);
    }

    @Test
    void updateWithMismatchedIdReturns400() {
        String url = uniqueUrl();
        int id = createIntegrationAsSysadmin(url);

        // updateNiordIntegration throws new NiordWebException(400) when the path id differs
        // from the body id.
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "id", id + 1,
                        "url", uniqueUrl(),
                        "active", true,
                        "assignNewUids", false,
                        "createBaseData", false,
                        "messageSeriesMappings", List.of(Map.of(
                                "sourceSeriesId", "a",
                                "targetSeriesId", "b"))))
                .when().put("/rest/niord-integrations/niord-integration/" + id)
                .then().statusCode(400);

        // The mismatch was rejected before any persistence, so the original is unchanged.
        given().auth().oauth2(accessToken())
                .when().get("/rest/niord-integrations/niord-integration/" + id)
                .then().statusCode(200)
                .body("url", is(url));
    }
}
