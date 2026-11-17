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

import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.keycloak.KeycloakIntegrationService;
import org.niord.test.ITTest;
import org.niord.test.http.HttpStub;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for {@link UserRestService}: the HTTP contract of the {@code /rest/users} endpoints,
 * with the authorization matrix exercised against a real Keycloak token.
 *
 * <p>The endpoints split into two role tiers:
 * <ul>
 *   <li>{@code /search} and {@code /search-emails} require the {@code user} role: they back the user and
 *       e-mail autocomplete fields. Their search is bounded (see {@code UserService.searchUsers}): a name
 *       shorter than two characters matches nothing, LIKE wildcards in it match only themselves, and at
 *       most 25 users are returned.</li>
 *   <li>{@code /all} (the whole user directory), {@code /kc-users}, {@code /kc-groups} and the
 *       {@code /kc-user/...} mutators require {@code admin}.</li>
 * </ul>
 *
 * <p>The cached sysadmin token carries {editor, admin, sysadmin} but <em>not</em> {@code user}, so the
 * USER-protected endpoints are rejected (403) with the bare token and only succeed once a {@code NiordDomain}
 * header lets the augmentor grant the full role chain. The ADMIN-protected endpoints are reachable with the
 * bare token directly.
 *
 * <p>The {@code search-emails} endpoint echoes the {@code name} query parameter back as the first entry when
 * it matches {@code UserService.EMAIL_PATTERN}, before appending any matched users. No users are seeded, so
 * that echo is the only deterministic, seed-independent assertion available for that endpoint.
 *
 * <p>The {@code kc-users}/{@code kc-groups} endpoints reach out to the Keycloak <em>admin</em> API, which is
 * unreachable from the test environment; the service swallows the resulting error and returns an empty list,
 * so those endpoints answer 200 with an empty body even though no admin call succeeded (the old system's
 * behaviour, kept). A group join or leave does not swallow it: the tests at the end point the service at an
 * {@link HttpStub} that refuses or fails, and the call answers 403, 404 or 502 with a sentence.
 *
 * <p>None of the reachable code paths exercised here persist rows, so no database cleanup is required; the
 * {@link #cleanup()} hook remains as a best-effort guard for any user ids accidentally created. All data
 * tokens use an {@code IT-REST-} prefix that never occurs in the seed data, keeping tests independent.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class UserRestServiceITTest extends ITTest {

    /** Keycloak user ids accidentally created via REST, removed best-effort in {@link #cleanup()}. */
    private final List<String> createdUserIds = new ArrayList<>();

    @Autowired
    KeycloakIntegrationService keycloakIntegrationService;

    /** The service's own Keycloak URL while a test points it at a stub; put back in {@link #cleanup()} */
    private Object originalAuthServerUrl;

    @AfterEach
    void cleanup() {
        if (originalAuthServerUrl != null) {
            ReflectionTestUtils.setField(keycloakTarget(), "authServerUrl", originalAuthServerUrl);
            originalAuthServerUrl = null;
        }
        for (String userId : createdUserIds) {
            try {
                given().auth().oauth2(accessToken())
                        .delete("/rest/users/kc-user/" + userId);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
        createdUserIds.clear();
    }

    // ------------------------------------------------------------------------------------------- //
    // USER endpoints — no token -> 401
    // ------------------------------------------------------------------------------------------- //

    @Test
    void allWithoutTokenIsUnauthorized() {
        given()
                .when().get("/rest/users/all")
                .then().statusCode(401);
    }

    @Test
    void searchWithoutTokenIsUnauthorized() {
        given()
                .queryParam("name", "IT-REST-anything")
                .when().get("/rest/users/search")
                .then().statusCode(401);
    }

    @Test
    void searchEmailsWithoutTokenIsUnauthorized() {
        given()
                .queryParam("name", "it-rest-anything@example.com")
                .when().get("/rest/users/search-emails")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // USER endpoints — authenticated-but-insufficient-role (bare sysadmin token, no 'user') -> 403
    // ------------------------------------------------------------------------------------------- //

    @Test
    void allAsPlainUserIsForbidden() {
        // /all lists every user's name and e-mail across all domains, so it is ADMIN, not USER: the
        // testuser identity holds 'user' (granted through the NiordDomain header) and nothing above it.
        given().auth().oauth2(accessToken("user"))
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/users/all")
                .then().statusCode(403);
    }

    @Test
    void allAsEditorIsForbidden() {
        given().auth().oauth2(accessToken("editor"))
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/users/all")
                .then().statusCode(403);
    }

    @Test
    void allAsAdminReturnsJsonArray() {
        // The bare testadmin token carries 'admin' on the base client, which is all /all requires.
        given().auth().oauth2(accessToken("admin"))
                .when().get("/rest/users/all")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(0));
    }

    @Test
    void searchEmailsWithBareTokenButNoDomainIsForbidden() {
        // search-emails is @RolesAllowed("user"). The bare sysadmin token carries {editor, admin, sysadmin}
        // but NOT 'user', so without a NiordDomain header to trigger the role-chain augmentor the
        // authenticated request is rejected as forbidden.
        given().auth().oauth2(accessToken())
                .queryParam("name", "it-rest-forbidden@example.com")
                .when().get("/rest/users/search-emails")
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // USER endpoints — succeed with token + NiordDomain header (augmentor grants the role chain)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void allWithDomainReturnsJsonArray() {
        // The sysadmin token carries 'admin', so the ADMIN-protected endpoint is reachable (with or without
        // the header). No users are seeded and other tests may create rows, so only the shape (a JSON array)
        // is asserted, not an exact count.
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/users/all")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(0));
    }

    @Test
    void searchEmailsWithEmailNameEchoesNameVerbatim() {
        // searchEmails adds the 'name' verbatim as the first entry when it matches UserService.EMAIL_PATTERN,
        // before appending any matched users. No users are seeded, so the echoed email is the deterministic,
        // seed-independent outcome here.
        String email = "it-rest-echo@example.com";
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .queryParam("name", email)
                .when().get("/rest/users/search-emails")
                .then().statusCode(200)
                .body("$", hasItem(email));
    }

    @Test
    void searchEmailsWithNonEmailNameReturnsEmpty() {
        // A non-email name does not match EMAIL_PATTERN, so it is not echoed; with no seeded user matching
        // it either, the result is an empty list and must not contain the supplied string.
        String nonEmail = "notanemail";
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .queryParam("name", nonEmail)
                .when().get("/rest/users/search-emails")
                .then().statusCode(200)
                .body("size()", is(0))
                .body("$", not(hasItem(nonEmail)));
    }

    @Test
    void searchWithNoMatchingNameReturnsEmpty() {
        // No user matches the unique IT-REST token, so the search returns an empty array.
        given().auth().oauth2(accessToken())
                .header("NiordDomain", "niord-nw")
                .queryParam("name", "IT-REST-NoSuchUser")
                .when().get("/rest/users/search")
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void searchWithAOneCharacterNameReturnsEmpty() {
        // Below the two-character minimum the search matches nothing, however many users contain the
        // character. The name is echoed only when it is an e-mail address, which one character is not.
        given().auth().oauth2(accessToken("user"))
                .header("NiordDomain", "niord-nw")
                .queryParam("name", "e")
                .when().get("/rest/users/search")
                .then().statusCode(200)
                .body("size()", is(0));
    }

    @Test
    void searchWithWildcardsOnlyMatchesThemLiterally() {
        // '%' and '_' are matched as text, so a name made of wildcards is not a way to list every user.
        for (String wildcards : List.of("%%", "__", "%_")) {
            given().auth().oauth2(accessToken("user"))
                    .header("NiordDomain", "niord-nw")
                    .queryParam("name", wildcards)
                    .when().get("/rest/users/search")
                    .then().statusCode(200)
                    .body("size()", is(0));
        }
    }

    @Test
    void searchEmailsWithWildcardsOnlyReturnsEmpty() {
        given().auth().oauth2(accessToken("user"))
                .header("NiordDomain", "niord-nw")
                .queryParam("name", "%%")
                .when().get("/rest/users/search-emails")
                .then().statusCode(200)
                .body("size()", is(0));
    }

    // ------------------------------------------------------------------------------------------- //
    // ADMIN endpoints — no token -> 401
    // ------------------------------------------------------------------------------------------- //

    @Test
    void kcUsersWithoutTokenIsUnauthorized() {
        given()
                .when().get("/rest/users/kc-users")
                .then().statusCode(401);
    }

    @Test
    void kcGroupsWithoutTokenIsUnauthorized() {
        given()
                .when().get("/rest/users/kc-groups")
                .then().statusCode(401);
    }

    @Test
    void kcUserGroupsWithoutTokenIsUnauthorized() {
        given()
                .when().get("/rest/users/kc-user/IT-REST-no-such-user/kc-groups")
                .then().statusCode(401);
    }

    @Test
    void addKcUserWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of("username", "IT-REST-unauth-user"))
                .when().post("/rest/users/kc-user/")
                .then().statusCode(401);
    }

    @Test
    void updateKcUserWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of("keycloakId", "IT-REST-unauth", "username", "IT-REST-unauth-user"))
                .when().put("/rest/users/kc-user/IT-REST-unauth")
                .then().statusCode(401);
    }

    @Test
    void deleteKcUserWithoutTokenIsUnauthorized() {
        given()
                .when().delete("/rest/users/kc-user/IT-REST-unauth")
                .then().statusCode(401);
    }

    @Test
    void joinKcGroupWithoutTokenIsUnauthorized() {
        given()
                .when().put("/rest/users/kc-user/IT-REST-unauth/kc-groups/IT-REST-group")
                .then().statusCode(401);
    }

    @Test
    void leaveKcGroupWithoutTokenIsUnauthorized() {
        given()
                .when().delete("/rest/users/kc-user/IT-REST-unauth/kc-groups/IT-REST-group")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // ADMIN endpoints — reachable with the bare sysadmin token (admin role present)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void kcUsersWithBareTokenReturnsEmptyList() {
        // The bare sysadmin token carries the 'admin' role, so authorization passes. The Keycloak ADMIN
        // API is unreachable from the test, so searchKeycloakUsers swallows the error and returns an empty
        // list -> 200 with an empty JSON array.
        given().auth().oauth2(accessToken())
                .when().get("/rest/users/kc-users")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", is(0));
    }

    @Test
    void kcGroupsWithBareTokenReturnsEmptyList() {
        // Same as kc-users: admin role passes authorization, but the unreachable Keycloak ADMIN API makes
        // getKeycloakGroups swallow the error and return an empty list -> 200 with an empty JSON array.
        given().auth().oauth2(accessToken())
                .when().get("/rest/users/kc-groups")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("size()", is(0));
    }

    @Test
    void updateKcUserWithMismatchedIdReturns400() {
        // updateKeycloakUser throws NiordWebException(400) when the path userId differs from the body's
        // keycloakId, BEFORE any Keycloak call, so this is deterministically assertable with the bare token.
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("keycloakId", "IT-REST-ID-B", "username", "IT-REST-mismatch-user"))
                .when().put("/rest/users/kc-user/IT-REST-ID-A")
                .then().statusCode(400);
    }

    // ------------------------------------------------------------------------------------------- //
    // Group join and leave — a Keycloak that refuses or fails is reported, never taken as done
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aGroupJoinOrLeaveThatKeycloakRefusesAnswers403WithItsReason() throws Exception {
        try (HttpStub keycloak = HttpStub.start()) {
            keycloak.respondStatus(403);
            pointKeycloakAt(keycloak.baseUrl());

            membership("PUT").statusCode(403)
                    .body("detail", is("Keycloak refused to add the user to the group, so the user's groups are "
                            + "unchanged"));
            membership("DELETE").statusCode(403)
                    .body("detail", is("Keycloak refused to remove the user from the group, so the user's groups "
                            + "are unchanged"));
            assertEquals(List.of("PUT", "DELETE"), keycloak.requests().stream().map(HttpStub.RecordedRequest::method)
                    .toList(), "one Keycloak call each");
        }
    }

    @Test
    void aGroupJoinOrLeaveOfAnUnknownUserOrGroupAnswers404() throws Exception {
        try (HttpStub keycloak = HttpStub.start()) {
            keycloak.respondStatus(404);
            pointKeycloakAt(keycloak.baseUrl());

            membership("PUT").statusCode(404)
                    .body("detail", is("Keycloak has no such user or group, so the user's groups are unchanged"));
            membership("DELETE").statusCode(404)
                    .body("detail", is("Keycloak has no such user or group, so the user's groups are unchanged"));
        }
    }

    @Test
    void aGroupJoinOrLeaveThatKeycloakFailsAnswers502WithItsStatus() throws Exception {
        try (HttpStub keycloak = HttpStub.start()) {
            keycloak.respondStatus(503);
            pointKeycloakAt(keycloak.baseUrl());

            membership("PUT").statusCode(502)
                    .body("detail", is("Keycloak failed to add the user to the group (it answered 503)"));
            membership("DELETE").statusCode(502)
                    .body("detail", is("Keycloak failed to remove the user from the group (it answered 503)"));
        }
    }

    @Test
    void aGroupJoinOrLeaveWhileKeycloakCannotBeReachedAnswers502() throws Exception {
        // A port that was just closed: the connection is refused
        String gone;
        try (HttpStub keycloak = HttpStub.start()) {
            gone = keycloak.baseUrl();
        }
        pointKeycloakAt(gone);

        membership("PUT").statusCode(502)
                .body("detail", containsString("Keycloak could not be reached to add the user to the group"));
        membership("DELETE").statusCode(502)
                .body("detail", containsString("Keycloak could not be reached to remove the user from the group"));
    }

    private ValidatableResponse membership(String verb) {
        var request = given().auth().oauth2(accessToken()).header("NiordDomain", "niord-nw");
        String path = "/rest/users/kc-user/IT-REST-kc-user/kc-groups/IT-REST-kc-group";
        return (verb.equals("PUT") ? request.when().put(path) : request.when().delete(path)).then();
    }

    private void pointKeycloakAt(String url) {
        if (originalAuthServerUrl == null) {
            originalAuthServerUrl = ReflectionTestUtils.getField(keycloakTarget(), "authServerUrl");
        }
        ReflectionTestUtils.setField(keycloakTarget(), "authServerUrl", url);
    }

    private KeycloakIntegrationService keycloakTarget() {
        return AopTestUtils.getTargetObject(keycloakIntegrationService);
    }
}
