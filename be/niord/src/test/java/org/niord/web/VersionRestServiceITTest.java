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
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Integration tests for {@link VersionRestService}: the HTTP contract of the single
 * {@code GET /rest/version/build-version} endpoint.
 *
 * <p>The endpoint is {@code @PermitAll} (it carries no {@code @RolesAllowed}) and returns the build
 * version as {@code text/plain}. Its {@code Cache-Control: no-cache} directive is supplied by Spring
 * Security's default header writer — the legacy RESTEasy {@code @NoCache} annotation was a no-op under
 * the port and has been removed (see RST1). The build version is read from the
 * Maven-filtered {@code /version.properties} resource ({@code niord.version}), so its concrete value is
 * environment-dependent; the tests therefore assert the stable observable contract — HTTP status,
 * {@code text/plain} content type, the {@code no-cache} response directive, a non-blank body, and
 * stability across repeated/authenticated calls — rather than an exact version string.
 *
 * <p>Because the endpoint is unsecured, these tests create no data and need no cleanup. The negative
 * cases assert that {@code @PermitAll} truly means "no token required" (a token neither helps nor
 * breaks it) and that the JAX-RS routing/method contract holds (unknown sub-path -&gt; 404, wrong
 * method -&gt; 405).
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class VersionRestServiceITTest extends ITTest {

    private static final String BUILD_VERSION_PATH = "/rest/version/build-version";

    // ------------------------------------------------------------------------------------------- //
    // Happy path — @PermitAll read, no token required
    // ------------------------------------------------------------------------------------------- //

    @Test
    void buildVersionWithoutTokenReturns200() {
        given()
                .when().get(BUILD_VERSION_PATH)
                .then().statusCode(200);
    }

    @Test
    void buildVersionBodyIsNonBlank() {
        String body = given()
                .when().get(BUILD_VERSION_PATH)
                .then().statusCode(200)
                .extract().asString();

        assertNotNull(body, "build-version body must not be null");
        assertFalse(body.trim().isEmpty(), "build-version body must not be blank");
    }

    @Test
    void buildVersionIsNeverTheLiteralUndefinedFallback() {
        // VersionRestService delegates to NiordApp.getBuildVersion(), which only returns the literal
        // "undefined" when /version.properties cannot be loaded. Since the resource is packaged on the
        // classpath, the running application must report a real version, not the fallback.
        String body = given()
                .when().get(BUILD_VERSION_PATH)
                .then().statusCode(200)
                .extract().asString().trim();

        assertNotNull(body);
        assertFalse("undefined".equals(body),
                "build version should be resolved from version.properties, not the 'undefined' fallback");
    }

    @Test
    void buildVersionContentTypeIsTextPlain() {
        given()
                .when().get(BUILD_VERSION_PATH)
                .then().statusCode(200)
                .contentType(ContentType.TEXT);
    }

    @Test
    void buildVersionResponseIsNotCacheable() {
        // The Cache-Control: no-cache header is supplied by Spring Security's default header writer
        // (not a RESTEasy @NoCache annotation, which was inert under the port and has been removed — RST1).
        given()
                .when().get(BUILD_VERSION_PATH)
                .then().statusCode(200)
                .header("Cache-Control", notNullValue())
                .header("Cache-Control", org.hamcrest.Matchers.containsString("no-cache"));
    }

    // ------------------------------------------------------------------------------------------- //
    // @PermitAll semantics — a token neither breaks nor is required by the endpoint
    // ------------------------------------------------------------------------------------------- //

    @Test
    void buildVersionWithValidTokenAlsoReturns200() {
        // A valid bearer token must not turn a @PermitAll endpoint into a 4xx; it should behave
        // identically to the anonymous call.
        given().auth().oauth2(accessToken())
                .when().get(BUILD_VERSION_PATH)
                .then().statusCode(200)
                .contentType(ContentType.TEXT);
    }

    @Test
    void buildVersionIsIdenticalWithAndWithoutToken() {
        String anonymous = given()
                .when().get(BUILD_VERSION_PATH)
                .then().statusCode(200)
                .extract().asString().trim();

        String authenticated = given().auth().oauth2(accessToken())
                .when().get(BUILD_VERSION_PATH)
                .then().statusCode(200)
                .extract().asString().trim();

        assertEquals(anonymous, authenticated,
                "build version must be the same whether or not the caller is authenticated");
    }

    @Test
    void buildVersionWithMalformedBearerTokenIsRejectedWith401() {
        // Even though the endpoint is @PermitAll (no token is required — see
        // buildVersionWithoutTokenReturns200), Quarkus OIDC EAGERLY validates a *present* bearer token
        // during authentication, before authorization runs. An unparsable JWT therefore fails token
        // validation and the request is rejected with 401 rather than being ignored. This is a
        // framework-coupled behaviour (Quarkus OIDC) — see docs/framework-coupled-tests.md.
        given().header("Authorization", "Bearer not-a-real-jwt")
                .when().get(BUILD_VERSION_PATH)
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // Determinism — value is stable across repeated reads
    // ------------------------------------------------------------------------------------------- //

    @Test
    void buildVersionIsStableAcrossRepeatedCalls() {
        String first = given()
                .when().get(BUILD_VERSION_PATH)
                .then().statusCode(200)
                .extract().asString().trim();

        String second = given()
                .when().get(BUILD_VERSION_PATH)
                .then().statusCode(200)
                .extract().asString().trim();

        assertEquals(first, second, "build version must be deterministic across calls");
    }

    // ------------------------------------------------------------------------------------------- //
    // Routing / method contract — negative cases proving the path & verb are exactly as declared
    // ------------------------------------------------------------------------------------------- //

    @Test
    void unknownSubPathUnderVersionReturns404() {
        given()
                .when().get("/rest/version/does-not-exist")
                .then().statusCode(404);
    }

    @Test
    void versionRootWithoutBuildVersionSegmentReturns404() {
        // Only /build-version is mapped under @Path("/version"); the bare resource path is not.
        given()
                .when().get("/rest/version")
                .then().statusCode(404);
    }

    @Test
    void postToBuildVersionReturns405() {
        // The endpoint declares @GET only, so a POST to the same path is a method-not-allowed.
        given().contentType(ContentType.JSON).body("{}")
                .when().post(BUILD_VERSION_PATH)
                .then().statusCode(405);
    }

    @Test
    void putToBuildVersionReturns405() {
        given().contentType(ContentType.JSON).body("{}")
                .when().put(BUILD_VERSION_PATH)
                .then().statusCode(405);
    }

    @Test
    void deleteOnBuildVersionReturns405() {
        given()
                .when().delete(BUILD_VERSION_PATH)
                .then().statusCode(405);
    }

    @Test
    void headOnBuildVersionReturns200WithNoBody() {
        // JAX-RS auto-derives HEAD from the @GET method; it must succeed and carry no entity body.
        String body = given()
                .when().head(BUILD_VERSION_PATH)
                .then().statusCode(200)
                .extract().asString();

        assertEquals("", body, "HEAD response must not carry an entity body");
    }

    @Test
    void buildVersionMatchesGetEntityShapeOnRepeatedRead() {
        // Couple status + content-type + non-blank body in a single assertion to lock the full contract.
        given()
                .when().get(BUILD_VERSION_PATH)
                .then().statusCode(200)
                .contentType(ContentType.TEXT)
                .body(not(is("")))
                .body(notNullValue());
    }
}
