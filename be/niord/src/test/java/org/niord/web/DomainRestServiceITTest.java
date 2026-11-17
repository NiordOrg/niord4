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

import org.springframework.transaction.annotation.Transactional;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

/**
 * Integration tests for {@link DomainRestService}: the HTTP contract of the {@code /rest/domains}
 * endpoints, including authorization. The {@code GET /all} read is {@code @PermitAll}; every write
 * endpoint (create / update / delete / keycloak / upload) requires the {@code sysadmin} role,
 * exercised with a real Keycloak token (see {@link KeycloakTestTokens}).
 *
 * <p>Reads and negative-security cases need no token. Positive {@code sysadmin} cases create data that
 * is committed (HTTP requests run in the server's own transaction, which {@code @Transactional}
 * cannot roll back), so created domains are tracked by {@code domainId} and deleted in
 * {@link #cleanup()} using unique {@code IT-REST-} identifiers that never occur in the seed data.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class DomainRestServiceITTest extends ITTest {

    private final List<String> createdDomainIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String domainId : createdDomainIds) {
            try {
                given().auth().oauth2(accessToken()).delete("/rest/domains/domain/" + domainId);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdDomainIds.clear();
    }

    /** A minimal valid domain request body. {@code domainId} and {@code name} are the required fields. */
    private Map<String, Object> domainBody(String domainId, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("domainId", domainId);
        body.put("name", name);
        body.put("active", true);
        return body;
    }

    /** Creates a domain as sysadmin, tracks it for cleanup and returns its domainId. */
    private String createDomainAsSysadmin(String domainId, String name) {
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(domainBody(domainId, name))
                .when().post("/rest/domains/domain/")
                .then().statusCode(200)
                .body("domainId", is(domainId))
                .body("name", is(name));
        createdDomainIds.add(domainId);
        return domainId;
    }

    // ------------------------------------------------------------------------------------------- //
    // Unauthenticated read (@PermitAll on GET /all via the class-level annotation)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getAllDomainsWithoutTokenReturnsTheSeededDomainsTheMasterIncluded() {
        given()
                .when().get("/rest/domains/all")
                .then().statusCode(200)
                // The fixture's 6 domains are all active: the sample's 5 and the master domain, which the base
                // product's bootstrap creates active, so the default view returns them all
                .body("size()", greaterThanOrEqualTo(6))
                .body("domainId", hasItem("niord-nw"))
                .body("domainId", hasItem("niord-client-master"));
    }

    @Test
    void getAllDomainsExposesStableSeededDomainNw() {
        given()
                .when().get("/rest/domains/all")
                .then().statusCode(200)
                .body("find { it.domainId == 'niord-nw' }.name", is("NW"))
                .body("find { it.domainId == 'niord-nw' }.active", is(true))
                .body("find { it.domainId == 'niord-nw' }.timeZone", is("Europe/Copenhagen"));
    }

    @Test
    void getAllDomainsIncludeInactiveReturnsAllSixSeededDomains() {
        // Requesting inactive domains as well still returns the fixture's 6 domains, the master among them; that
        // an inactive domain is listed only then is proven by
        // inactiveDomainIsHiddenFromDefaultListingButVisibleWithInactiveFlag
        given()
                .queryParam("inactive", true)
                .when().get("/rest/domains/all")
                .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(6))
                .body("domainId", hasItem("niord-client-master"))
                .body("domainId", hasItem("niord-almanac"))
                .body("domainId", hasItem("niord-annex"));
    }

    @Test
    void getAllDomainsWithoutKeycloakStateOmitsInKeycloakFlag() {
        // keycloakState=false skips the Keycloak round-trip; toVo() then leaves inKeycloak null,
        // so the field is absent from the JSON for every returned domain.
        given()
                .queryParam("keycloakState", false)
                .when().get("/rest/domains/all")
                .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(5))
                .body("findAll { it.inKeycloak != null }.size()", is(0));
    }

    // ------------------------------------------------------------------------------------------- //
    // Negative security — sysadmin-only writes without a token return 401
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createDomainWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(domainBody("IT-REST-noauth", "IT-REST-noauth"))
                .when().post("/rest/domains/domain/")
                .then().statusCode(401);
    }

    @Test
    void updateDomainWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(domainBody("niord-nw", "NW"))
                .when().put("/rest/domains/domain/niord-nw")
                .then().statusCode(401);
    }

    @Test
    void deleteDomainWithoutTokenIsUnauthorized() {
        given().when().delete("/rest/domains/domain/niord-nw")
                .then().statusCode(401);
    }

    @Test
    void createDomainInKeycloakWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(domainBody("niord-nw", "NW"))
                .when().post("/rest/domains/keycloak")
                .then().statusCode(401);
    }

    @Test
    void uploadDomainsWithoutTokenIsUnauthorized() {
        given().multiPart("data", "domains.json", "[]".getBytes())
                .when().post("/rest/domains/upload-domains")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // Positive sysadmin flows — create / read-back / update / delete round-trips
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createDomainAsSysadminPersistsAndIsReadableViaGetAll() {
        String domainId = "IT-REST-create-" + System.nanoTime();
        String name = "IT-REST-Create";

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(domainBody(domainId, name))
                .when().post("/rest/domains/domain/")
                .then().statusCode(200)
                .body("domainId", is(domainId))
                .body("name", is(name))
                .body("active", is(true));
        createdDomainIds.add(domainId);

        // Persistence side-effect: the new domain now appears in the unauthenticated /all listing.
        given()
                .when().get("/rest/domains/all")
                .then().statusCode(200)
                .body("find { it.domainId == '" + domainId + "' }.name", is(name));
    }

    @Test
    void createDomainWithPropertiesRoundTripsThoseProperties() {
        String domainId = "IT-REST-props-" + System.nanoTime();
        Map<String, Object> body = domainBody(domainId, "IT-REST-Props");
        body.put("sortOrder", 42);
        body.put("timeZone", "Europe/Copenhagen");
        body.put("color", "#abcdef");
        body.put("publish", true);
        body.put("atons", false);
        body.put("messageSortOrder", "ID ASC");

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/rest/domains/domain/")
                .then().statusCode(200)
                .body("domainId", is(domainId))
                .body("sortOrder", is(42))
                .body("timeZone", is("Europe/Copenhagen"))
                .body("color", is("#abcdef"))
                .body("publish", is(true))
                .body("atons", is(false))
                .body("messageSortOrder", is("ID ASC"));
        createdDomainIds.add(domainId);

        given()
                .queryParam("inactive", true)
                .when().get("/rest/domains/all")
                .then().statusCode(200)
                .body("find { it.domainId == '" + domainId + "' }.color", is("#abcdef"))
                .body("find { it.domainId == '" + domainId + "' }.sortOrder", is(42));
    }

    @Test
    void createDomainWithDuplicateDomainIdFails() {
        String domainId = createDomainAsSysadmin("IT-REST-dup-" + System.nanoTime(), "IT-REST-Dup");

        // The service rejects a duplicate domainId by throwing, which surfaces as a 5xx server error
        // (no graceful 4xx mapping exists), so the duplicate is not silently created twice.
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(domainBody(domainId, "IT-REST-Dup-2"))
                .when().post("/rest/domains/domain/")
                .then().statusCode(greaterThanOrEqualTo(400));

        // The original domain is unchanged: still exactly one entry with that domainId and the first name.
        given()
                .queryParam("inactive", true)
                .when().get("/rest/domains/all")
                .then().statusCode(200)
                .body("findAll { it.domainId == '" + domainId + "' }.size()", is(1))
                .body("find { it.domainId == '" + domainId + "' }.name", is("IT-REST-Dup"));
    }

    @Test
    void updateDomainAsSysadminPersistsNewValues() {
        String domainId = createDomainAsSysadmin("IT-REST-upd-" + System.nanoTime(), "IT-REST-Upd");

        Map<String, Object> body = domainBody(domainId, "IT-REST-Upd-Renamed");
        body.put("color", "#123456");

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/rest/domains/domain/" + domainId)
                .then().statusCode(200)
                .body("domainId", is(domainId))
                .body("name", is("IT-REST-Upd-Renamed"))
                .body("color", is("#123456"));

        // Persistence side-effect: the rename is visible in the listing.
        given()
                .queryParam("inactive", true)
                .when().get("/rest/domains/all")
                .then().statusCode(200)
                .body("find { it.domainId == '" + domainId + "' }.name", is("IT-REST-Upd-Renamed"))
                .body("find { it.domainId == '" + domainId + "' }.color", is("#123456"));
    }

    @Test
    void updateDomainWithMismatchedIdReturns400() {
        String domainId = createDomainAsSysadmin("IT-REST-mismatch-" + System.nanoTime(), "IT-REST-Mismatch");

        // Path domainId differs from the body domainId -> guard in updateDomain() throws 400.
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(domainBody(domainId + "-other", "IT-REST-Mismatch"))
                .when().put("/rest/domains/domain/" + domainId)
                .then().statusCode(400);

        // The original domain is untouched by the rejected update.
        given()
                .queryParam("inactive", true)
                .when().get("/rest/domains/all")
                .then().statusCode(200)
                .body("find { it.domainId == '" + domainId + "' }.name", is("IT-REST-Mismatch"));
    }

    @Test
    void updateNonExistingDomainFails() {
        String domainId = "IT-REST-missing-" + System.nanoTime();

        // updateDomain() throws IllegalArgumentException for a non-existing domain -> server error,
        // not a silent success.
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(domainBody(domainId, "IT-REST-Missing"))
                .when().put("/rest/domains/domain/" + domainId)
                .then().statusCode(greaterThanOrEqualTo(400));

        // Nothing was created as a side effect of the failed update.
        given()
                .queryParam("inactive", true)
                .when().get("/rest/domains/all")
                .then().statusCode(200)
                .body("findAll { it.domainId == '" + domainId + "' }.size()", is(0));
    }

    @Test
    void deleteDomainAsSysadminRemovesItFromListing() {
        String domainId = "IT-REST-del-" + System.nanoTime();
        // Create directly (not via helper) so we control cleanup ourselves after the delete succeeds.
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(domainBody(domainId, "IT-REST-Del"))
                .when().post("/rest/domains/domain/")
                .then().statusCode(200);

        given().auth().oauth2(accessToken())
                .when().delete("/rest/domains/domain/" + domainId)
                .then().statusCode(204);

        // Persistence side-effect: the domain is gone, even from the include-inactive listing.
        given()
                .queryParam("inactive", true)
                .when().get("/rest/domains/all")
                .then().statusCode(200)
                .body("findAll { it.domainId == '" + domainId + "' }.size()", is(0));
    }

    @Test
    void deleteNonExistingDomainAsSysadminSucceedsAsNoOp() {
        String domainId = "IT-REST-del-missing-" + System.nanoTime();

        // deleteDomain() returns false for a missing domain but the endpoint returns void, so a
        // delete of something that does not exist is a successful no-op (no exception, 2xx).
        given().auth().oauth2(accessToken())
                .when().delete("/rest/domains/domain/" + domainId)
                .then().statusCode(204);
    }

    @Test
    void createDomainAsSysadminThenDeleteIsFullRoundTrip() {
        String domainId = "IT-REST-roundtrip-" + System.nanoTime();

        // Create
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(domainBody(domainId, "IT-REST-RoundTrip"))
                .when().post("/rest/domains/domain/")
                .then().statusCode(200)
                .body("domainId", is(domainId));

        // Visible
        given().queryParam("inactive", true)
                .when().get("/rest/domains/all")
                .then().statusCode(200)
                .body("domainId", hasItem(domainId));

        // Delete
        given().auth().oauth2(accessToken())
                .when().delete("/rest/domains/domain/" + domainId)
                .then().statusCode(204);

        // No longer visible
        given().queryParam("inactive", true)
                .when().get("/rest/domains/all")
                .then().statusCode(200)
                .body("findAll { it.domainId == '" + domainId + "' }.size()", is(0));
    }

    @Test
    void inactiveDomainIsHiddenFromDefaultListingButVisibleWithInactiveFlag() {
        String domainId = "IT-REST-inactive-" + System.nanoTime();
        Map<String, Object> body = domainBody(domainId, "IT-REST-Inactive");
        body.put("active", false);

        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/rest/domains/domain/")
                .then().statusCode(200)
                .body("active", is(false));
        createdDomainIds.add(domainId);

        // Default (active-only) listing must NOT include the inactive domain ...
        given()
                .when().get("/rest/domains/all")
                .then().statusCode(200)
                .body("findAll { it.domainId == '" + domainId + "' }.size()", is(0));

        // ... but the include-inactive listing must.
        given()
                .queryParam("inactive", true)
                .when().get("/rest/domains/all")
                .then().statusCode(200)
                .body("find { it.domainId == '" + domainId + "' }.active", is(false));
    }
}
