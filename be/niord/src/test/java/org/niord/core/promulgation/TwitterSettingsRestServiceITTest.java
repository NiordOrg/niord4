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
package org.niord.core.promulgation;

import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * HTTP-contract integration tests for {@link TwitterSettingsRestService}: the authorization contract of
 * the {@code /rest/promulgation/twitter-settings} endpoints, which previously had no direct HTTP coverage.
 *
 * <p>The whole resource is annotated {@code @RolesAllowed(Roles.SYSADMIN)} at the class level, so every
 * endpoint requires the top {@code sysadmin} tier. This suite pins that decision — the most important
 * thing for the Jersey→Spring-MVC migration to preserve — across the read ({@code GET /{typeId}}),
 * create ({@code POST /}) and update ({@code PUT /{typeId}}) endpoints:
 * <ul>
 *   <li>no token → {@code 401};</li>
 *   <li>an authenticated {@code admin} (one tier below {@code sysadmin}) → {@code 403};</li>
 *   <li>a {@code sysadmin} → authorized (reaches the handler, i.e. NOT a {@code 401}/{@code 403}).</li>
 * </ul>
 *
 * <p>The probes use a {@code typeId} that does not exist in the seed, so the {@code sysadmin} read
 * reaches the handler and returns the no-content / empty result of {@code getSettings(...)} rather than
 * mutating anything; the meaningful assertion is the authorization outcome, not the handler payload.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class TwitterSettingsRestServiceITTest extends ITTest {

    private static final String BASE = "/rest/promulgation/twitter-settings/";
    private static final String UNKNOWN_TYPE = "no-such-promulgation-type";

    // ------------------------------------------------------------------------------------------- //
    // GET /{typeId} — @RolesAllowed(SYSADMIN)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getSettingsWithoutTokenIsUnauthorized() {
        given()
                .when().get(BASE + UNKNOWN_TYPE)
                .then().statusCode(401);
    }

    @Test
    void getSettingsWithAdminTokenIsForbidden() {
        // admin is exactly one tier below sysadmin -> authenticated but lacking the required role -> 403.
        given().auth().oauth2(accessToken("admin"))
                .when().get(BASE + UNKNOWN_TYPE)
                .then().statusCode(403);
    }

    @Test
    void getSettingsWithSysadminTokenIsAuthorized() {
        // sysadmin satisfies @RolesAllowed(SYSADMIN): the request reaches the handler (which returns
        // 200 with the settings, or 204 for an unknown type) — the point is it is NOT rejected 401/403.
        given().auth().oauth2(accessToken("sysadmin"))
                .when().get(BASE + UNKNOWN_TYPE)
                .then().statusCode(not(anyOf(is(401), is(403))));
    }

    // ------------------------------------------------------------------------------------------- //
    // POST / and PUT /{typeId} — same class-level @RolesAllowed(SYSADMIN)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void createSettingsWithoutTokenIsUnauthorized() {
        given()
                .contentType("application/json")
                .body("{}")
                .when().post(BASE)
                .then().statusCode(401);
    }

    /**
     * The original JAX-RS method {@code @Path("/")} matched the create endpoint both with and without a
     * trailing slash; Spring's PathPatternParser does not, so the method is dual-mapped {@code value={"","/"}}.
     * This pins the slash-less spelling: security runs before the body, so a missing token yields {@code 401}
     * (route matched), not {@code 404} (route missing).
     */
    @Test
    void createSettingsWithoutTrailingSlashRoutesToHandler() {
        // BASE has a trailing slash; strip it to hit the slash-less base path.
        String slashLessBase = BASE.substring(0, BASE.length() - 1);
        given()
                .contentType("application/json")
                .body("{}")
                .when().post(slashLessBase)
                .then().statusCode(401);
    }

    @Test
    void updateSettingsWithAdminTokenIsForbidden() {
        given().auth().oauth2(accessToken("admin"))
                .contentType("application/json")
                .body("{}")
                .when().put(BASE + UNKNOWN_TYPE)
                .then().statusCode(403);
    }

    /**
     * The path {@code typeId} must match the body's promulgation-type id, mirroring the guards in
     * {@code SafetyNetPromulgationRestService.updateArea} and {@code NavtexPromulgationRestService.updateTransmitter}.
     * A mismatch is rejected with {@code 400} before any settings are mutated (PROM3). The guard runs inside
     * the handler, so a {@code sysadmin} token is required to reach it (an authorization rejection would
     * short-circuit with {@code 401}/{@code 403} first). The outcome is independent of the seed because the
     * guard fires before the service is invoked.
     */
    @Test
    void updateSettingsWithMismatchedTypeIdIsBadRequest() {
        given().auth().oauth2(accessToken("sysadmin"))
                .contentType("application/json")
                .body("{\"promulgationType\":{\"typeId\":\"type-b\"}}")
                .when().put(BASE + "type-a")
                .then().statusCode(400);
    }
}
