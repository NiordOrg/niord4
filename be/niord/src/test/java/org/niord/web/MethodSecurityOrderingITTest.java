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

import org.junit.jupiter.api.Test;
import org.niord.test.CountryBuildGroup;
import org.niord.test.ITTest;

import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;

/**
 * Characterization test for how authorization is ordered relative to Spring MVC argument binding under the
 * pure-Spring REST stack.
 *
 * <p><b>The contract.</b> {@code @RolesAllowed} is enforced by Spring method security
 * ({@code @EnableMethodSecurity(jsr250Enabled = true)}), whose AOP advice fires at controller-method
 * <em>invocation</em> — i.e. <em>after</em> Spring MVC has resolved the handler arguments (path variables,
 * query parameters, deserialized {@code @RequestBody}, multipart request wrappers). Therefore, on a
 * {@code @RolesAllowed} endpoint:
 * <ul>
 *   <li>a <b>well-formed</b> body that an unauthorized caller submits is rejected by the authorization
 *       decision — {@code 401} for an anonymous caller, {@code 403} for an authenticated caller lacking the
 *       role — before the handler body runs (so no state is mutated); but</li>
 *   <li>invalid MVC input, such as a <b>malformed</b> body or an unconvertible path variable, is rejected
 *       with {@code 400} <em>before</em> the authorization advice runs, so the {@code 400} surfaces
 *       regardless of the caller's identity.</li>
 * </ul>
 * The {@code 401}/{@code 403} mapping of the method-security {@code AccessDeniedException} is owned by
 * Spring Security's {@code ExceptionTranslationFilter} with the resource-server defaults: anonymous callers
 * get {@code 401} + {@code WWW-Authenticate: Bearer} ({@code BearerTokenAuthenticationEntryPoint}),
 * authenticated callers lacking the role get {@code 403} ({@code BearerTokenAccessDeniedHandler}).
 *
 * <p><b>Deliberate change from the Jersey port.</b> The original Quarkus/Jersey stack (and an early
 * Spring shim, {@code RolesAllowedHandlerInterceptor}) evaluated {@code @RolesAllowed} <em>before</em> the
 * entity and argument binding ran, so malformed/invalid inputs returned {@code 401}/{@code 403} rather
 * than {@code 400}. That parity shim has been removed: authorization is now enforced solely by method
 * security, and Spring MVC binding errors simply yield the more conventional {@code 400}. This test pins
 * the new behavior. (A content-type {@code consumes=} mismatch is still answered with {@code 415} at
 * handler mapping, before method security — that ordering is unchanged.)
 *
 * <p>The probe endpoints are on {@link org.niord.web.AreaRestService}:
 * {@code POST /rest/areas/area/} ({@code consumes} json, {@code @RolesAllowed(ADMIN)}) and
 * {@code POST /rest/areas/upload-areas} ({@code consumes} multipart/form-data, {@code @RolesAllowed(ADMIN)}).
 * The well-formed probe body is {@code {}} — valid JSON that binds to an empty {@code SystemAreaVo} but
 * never reaches the handler, because the unauthorized caller is denied at method invocation; so no area is
 * ever created.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
@CountryBuildGroup
class MethodSecurityOrderingITTest extends ITTest {

    /** A body that is definitively NOT valid JSON, used to reach the message-converter 400 path. */
    private static final String MALFORMED_JSON = "{ this is : not valid json ]";

    /** Valid JSON that binds to an (empty) SystemAreaVo — used to reach the authorization decision. */
    private static final String WELL_FORMED_JSON = "{}";

    // ------------------------------------------------------------------------------------------- //
    // Authorization IS enforced (method-security AOP) for a well-formed body, before the handler runs.
    // JSON-body @RolesAllowed(ADMIN) endpoint: POST /rest/areas/area/
    // ------------------------------------------------------------------------------------------- //

    @Test
    void wellFormedBodyWithoutTokenIsUnauthorized() {
        // Anonymous: {} binds to an empty SystemAreaVo, then @RolesAllowed(ADMIN) denies the call with 401
        // at method invocation — the handler body never runs, so no area is created.
        given()
                .contentType("application/json")
                .body(WELL_FORMED_JSON)
                .when().post("/rest/areas/area/")
                .then().statusCode(401);
    }

    @Test
    void wellFormedBodyWithWrongRoleIsForbidden() {
        // Authenticated as 'editor' (a valid token lacking the ADMIN role): the authorization decision is 403.
        given().auth().oauth2(accessToken("editor"))
                .contentType("application/json")
                .body(WELL_FORMED_JSON)
                .when().post("/rest/areas/area/")
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // MVC argument binding now runs BEFORE authorization: malformed inputs are 400 regardless of the caller.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void malformedBodyWithoutTokenIsBadRequest() {
        // No token: the malformed body is rejected by the message converter (400) before method security
        // runs. Under the old security-before-body shim this was 401.
        given()
                .contentType("application/json")
                .body(MALFORMED_JSON)
                .when().post("/rest/areas/area/")
                .then().statusCode(400);
    }

    @Test
    void malformedBodyWithWrongRoleIsBadRequest() {
        // Authenticated 'editor' (wrong role): still 400 — the body fails to parse before the role check.
        // Under the old security-before-body shim this was 403.
        given().auth().oauth2(accessToken("editor"))
                .contentType("application/json")
                .body(MALFORMED_JSON)
                .when().post("/rest/areas/area/")
                .then().statusCode(400);
    }

    @Test
    void malformedBodyWithAdminTokenIsBadRequest() {
        // Control: an admin (authorized) sees 400 too — proving the body is genuinely malformed, so the
        // 400s above are due to converter-before-authorization ordering, not a coincidental security reject.
        given().auth().oauth2(accessToken("admin"))
                .contentType("application/json")
                .body(MALFORMED_JSON)
                .when().post("/rest/areas/area/")
                .then().statusCode(400);
    }

    @Test
    void invalidPathVariableWithoutTokenIsBadRequest() {
        // No token: the route matches DELETE /area/{areaId}, but "not-an-id" cannot bind to Integer.
        // That MVC conversion error happens before method security, so it is 400 rather than 401.
        given()
                .when().delete("/rest/areas/area/not-an-id")
                .then().statusCode(400);
    }

    @Test
    void invalidPathVariableWithWrongRoleIsBadRequest() {
        // Authenticated 'editor' (wrong role): still 400, because path-variable conversion fails before
        // @RolesAllowed(ADMIN) runs. Under the old security-before-binding shim this was 403.
        given().auth().oauth2(accessToken("editor"))
                .when().delete("/rest/areas/area/not-an-id")
                .then().statusCode(400);
    }

    // ------------------------------------------------------------------------------------------- //
    // Content-type MATCHING still precedes authorization: a consumes= mismatch is 415 at handler mapping,
    // before method security runs — so anonymous and wrong-role callers both see 415, not 401/403.
    // (The genuine multipart auth case — a correct multipart body with no token -> 401 — is covered by
    // AreaRestServiceITTest.uploadAreasWithoutTokenIsUnauthorized.)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void wrongContentTypeToMultipartEndpointWithoutTokenIs415() {
        given()
                .contentType("application/json")
                .body("{}".getBytes(StandardCharsets.UTF_8))
                .when().post("/rest/areas/upload-areas")
                .then().statusCode(415);
    }

    @Test
    void wrongContentTypeToMultipartEndpointWithWrongRoleIsAlso415() {
        given().auth().oauth2(accessToken("editor"))
                .contentType("application/json")
                .body("{}".getBytes(StandardCharsets.UTF_8))
                .when().post("/rest/areas/upload-areas")
                .then().statusCode(415);
    }
}
