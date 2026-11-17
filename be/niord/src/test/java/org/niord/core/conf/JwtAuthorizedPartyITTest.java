/*
 * Copyright 2026 Danish Maritime Authority.
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
package org.niord.core.conf;

import org.junit.jupiter.api.Test;
import org.niord.test.CountryBuildGroup;
import org.niord.test.ITTest;
import org.niord.test.NiordTestContainers;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Proves over HTTP that the resource server accepts only tokens issued to an allowed client — the
 * {@code authorizedPartyValidator} bean of {@link SecurityConfig} is part of the {@code JwtDecoder}
 * Spring Boot builds, not merely a bean nobody consults.
 *
 * <p>The foreign token comes from the test realm's {@code admin-cli} client: a public client with direct
 * access grants, so the same {@code sysadmin} user can sign in through it with no realm change. Its
 * token is correctly signed by the realm and carries the right issuer; only its {@code azp} differs from
 * the admin application's {@code niord-web}. Without the check it was accepted as an authenticated,
 * role-less caller ({@code 403} on a role-guarded endpoint); with it the token is refused as invalid
 * ({@code 401} with a {@code Bearer error="invalid_token"} challenge), before any role is looked at.
 *
 * <p>Every call is a read of a stable seeded setting or is refused before a handler runs, so nothing is
 * written and nothing needs cleaning up.
 */
@CountryBuildGroup
class JwtAuthorizedPartyITTest extends ITTest {

    /** A SYSADMIN-guarded, side-effect-free read (class-level {@code @RolesAllowed(SYSADMIN)}). */
    private static final String SYSADMIN_ENDPOINT = "/rest/settings/setting/documentationUrl";

    /** A password-grant token for the given user, issued to the given client of the test realm. */
    private static String tokenFromClient(String clientId, String username, String password) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "password")
                .formParam("client_id", clientId)
                .formParam("username", username)
                .formParam("password", password)
                .formParam("scope", "openid")
                .when()
                .post(NiordTestContainers.keycloakRealmUrl() + "/protocol/openid-connect/token")
                .then()
                .log().ifValidationFails()
                .statusCode(200)
                .extract().path("access_token");
    }

    @Test
    void aTokenIssuedToAnotherClientOfTheRealmIsRefusedAsInvalid() {
        String foreignToken = tokenFromClient("admin-cli", "sysadmin", "sysadmin");

        given().auth().oauth2(foreignToken)
                .when().get(SYSADMIN_ENDPOINT)
                .then().statusCode(401)
                .header("WWW-Authenticate", containsString("invalid_token"));
    }

    @Test
    void aForeignTokenIsRefusedOnAPublicEndpointToo() {
        // The check is part of token validation, not of any role rule: an invalid bearer token is refused
        // even where no role is required, rather than being treated as anonymous.
        String foreignToken = tokenFromClient("admin-cli", "sysadmin", "sysadmin");

        given().auth().oauth2(foreignToken)
                .when().get("/rest/dictionaries/names")
                .then().statusCode(401);
    }

    @Test
    void theSameUsersTokenFromTheAdminApplicationsClientIsAccepted() {
        // Positive control: same user, same realm, issued to niord-web.
        given().auth().oauth2(accessToken())
                .when().get(SYSADMIN_ENDPOINT)
                .then().statusCode(200);
    }
}
