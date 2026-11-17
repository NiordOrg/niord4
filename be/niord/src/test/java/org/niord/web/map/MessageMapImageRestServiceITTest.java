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
package org.niord.web.map;

import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;

import static io.restassured.RestAssured.given;

/**
 * Integration tests for {@link MessageMapImageRestService}: the HTTP contract and authorization of the
 * {@code /rest/message-map-image} endpoints.
 *
 * <p><b>Authorization model under test.</b> The class is declared {@code @PermitAll}, so the read
 * endpoint {@code GET /{uid}.png} is reachable anonymously: it auto-generates (or redirects to) a map
 * thumbnail for the message with the given UID, and yields {@code 404} for an unknown UID. The two write
 * endpoints that share the {@code @Path("/{folder:.+}")} mapping — {@code PUT} (a JSON base-64 data URI)
 * and {@code POST} (a multipart file upload) — are {@code @RolesAllowed(EDITOR)}. Because they are
 * container-secured, a request with no token yields {@code 401} and an authenticated identity below the
 * EDITOR tier (a plain {@code user}) yields {@code 403}, before any handler logic runs.
 *
 * <p><b>Tier oracle.</b> The role hierarchy is {@code sysadmin > admin > editor > user}
 * (see {@link org.niord.core.user.Roles}). The seeded identities (see {@link KeycloakTestTokens}) give one
 * token per tier. Every authenticated request carries {@code NiordDomain: niord-nw} so the domain
 * augmentor realizes the full role chain. A missing token yields the container's {@code 401} on the
 * EDITOR-secured endpoints; an authenticated {@code user} one tier too low yields {@code 403}.
 *
 * <p><b>Validation oracle (PUT).</b> Once an EDITOR is authenticated, the handler first calls
 * {@code repositoryService.validateTempRepoPath(path)}, which only accepts a path that resolves under
 * {@code <repoRoot>/temp} and otherwise throws {@code NiordWebException(403)}. A {@code temp/...}
 * path therefore passes validation, after which the body must begin with the
 * {@code data:image/png;base64,} prefix; a non-data-URI body fails that check with {@code 400}.
 *
 * <p><b>Covered elsewhere.</b> The {@code GET} paths of a message that has geometry (which image is served, when
 * one is drawn, and the placeholder) are covered by {@link MessageMapImageRestGapITTest} with a stand-in for the
 * generator; the drawing itself by the generator's own tests. Here only the unknown-message {@code 404}, the
 * EDITOR authorization boundaries, and the data-URI validation are covered.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MessageMapImageRestServiceITTest extends ITTest {

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    private static final String BASE = "/rest/message-map-image";

    /** A UID that no seeded message uses, so the read endpoint reports not-found. */
    private static final String UNKNOWN_IMAGE = BASE + "/NO-SUCH-UID.png";

    /** A fixed, valid temporary-repository folder path used by the authorization checks. */
    private static final String TEMP_FOLDER = BASE + "/temp/it-map";

    /** A request carrying the given bearer token and the {@code NiordDomain} header (full role chain). */
    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /{uid}.png — @PermitAll. Reachable anonymously; an unknown UID maps to 404.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getMapImage_unknownUidReturns404() {
        // @PermitAll: the anonymous caller reaches the handler. messageService.findByUid returns null,
        // so the handler returns SC_NOT_FOUND. redirects().follow(false) keeps RestAssured from
        // chasing the redirect to an image file that a known message's image is answered with.
        given().redirects().follow(false)
                .when().get(UNKNOWN_IMAGE)
                .then().statusCode(404);
    }

    // ------------------------------------------------------------------------------------------- //
    // PUT /{folder:.+} — @RolesAllowed(EDITOR). Anonymous -> 401; a plain user one tier too low -> 403.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void putMapImage_rejectsAnonymousWith401() {
        given().contentType(ContentType.JSON).body("\"x\"")
                .when().put(TEMP_FOLDER)
                .then().statusCode(401);
    }

    @Test
    void putMapImage_forbidsUserWith403() {
        // KEY adjacent-tier case: an authenticated user lacks 'editor'.
        as(accessToken("user")).contentType(ContentType.JSON).body("\"x\"")
                .when().put(TEMP_FOLDER)
                .then().statusCode(403);
    }

    @Test
    void putMapImage_asEditorWithNonDataUriBodyReturns400() {
        // A unique temp path passes validateTempRepoPath (it resolves under <repoRoot>/temp), so the
        // handler proceeds to the data-URI prefix check. The JSON string literal body
        // "not-a-data-uri" does not start with data:image/png;base64, -> NiordWebException(400).
        String folder = BASE + "/temp/it-map-" + System.nanoTime();
        as(accessToken("editor")).contentType(ContentType.JSON).body("\"not-a-data-uri\"")
                .when().put(folder)
                .then().statusCode(400);
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /{folder:.+} — @RolesAllowed(EDITOR), multipart. Anonymous -> 401; a plain user -> 403.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void uploadMapImage_rejectsAnonymousWith401() {
        given().multiPart("file", "x.png", new byte[]{1, 2, 3})
                .when().post(TEMP_FOLDER)
                .then().statusCode(401);
    }

    @Test
    void uploadMapImage_forbidsUserWith403() {
        // KEY adjacent-tier case: an authenticated user lacks 'editor'.
        as(accessToken("user")).multiPart("file", "x.png", new byte[]{1, 2, 3})
                .when().post(TEMP_FOLDER)
                .then().statusCode(403);
    }
}
