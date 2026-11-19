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

import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

/**
 * Cross-tier authorization matrix for the Niord REST API.
 *
 * <p>Every secured endpoint is guarded by {@code @RolesAllowed} against the role hierarchy
 * {@code sysadmin > admin > editor > user} (see {@link org.niord.core.user.Roles}). Per-resource
 * integration tests already cover the two easy corners of that hierarchy — no token ({@code 401}) and the
 * all-powerful {@code sysadmin} token ({@code 200}). What they could <em>not</em> cover, while only a
 * sysadmin identity existed, is the security-critical middle: an <em>authenticated</em> caller whose role
 * is one tier too low must be rejected with {@code 403}, not allowed.
 *
 * <p>This test closes that gap. It uses the four seeded identities (see {@link KeycloakTestTokens}) — one
 * per tier — and drives one representative endpoint at each tier:
 *
 * <table>
 *   <caption>Endpoint per tier and the expected outcome for each identity</caption>
 *   <tr><th>Tier / endpoint</th><th>user</th><th>editor</th><th>admin</th><th>sysadmin</th><th>no token</th></tr>
 *   <tr><td>USER — {@code GET /rest/users/search}</td><td>200</td><td>200</td><td>200</td><td>200</td><td>401</td></tr>
 *   <tr><td>EDITOR — {@code POST /rest/areas/intersecting-areas}</td><td><b>403</b></td><td>200</td><td>200</td><td>200</td><td>401</td></tr>
 *   <tr><td>ADMIN — {@code GET /rest/users/kc-users}</td><td><b>403</b></td><td><b>403</b></td><td>200</td><td>200</td><td>401</td></tr>
 *   <tr><td>SYSADMIN — {@code GET /rest/settings/setting/{key}}</td><td><b>403</b></td><td><b>403</b></td><td><b>403</b></td><td>200</td><td>401</td></tr>
 * </table>
 *
 * <p>The three bold cells on the diagonal are the adjacent-tier rejections that prove the hierarchy is
 * enforced: a {@code user} cannot edit, an {@code editor} cannot administer, an {@code admin} cannot act as
 * sysadmin. Because the deeper-than-adjacent cells are also asserted, the full lower-left triangle of the
 * matrix is {@code 403}.
 *
 * <p><b>Why the {@code NiordDomain} header is sent on every authenticated request.</b> The application's
 * base roles come from {@code resource_access.niord-client-master.roles}; the
 * {@link org.niord.core.web.NiordDomainFilter} then <em>adds</em> the roles the
 * caller holds on the domain named in the {@code NiordDomain} header. Sending
 * {@code NiordDomain: niord-nw} therefore realizes each identity's full role chain (for {@code testuser}, whose
 * roles are on {@code niord-nw} alone, it is the only way to the {@code user} role). Crucially,
 * the augmentor only <em>adds</em> the domain's roles — it never escalates beyond them — so the
 * {@code 403}s below also verify that the augmentor cannot be used to jump a tier.
 *
 * <p>The endpoints are deliberately chosen to be side-effect-free at the reachable status codes, so the
 * test creates no data and needs no cleanup: USER {@code /search} and ADMIN {@code /kc-users} are reads,
 * EDITOR {@code intersecting-areas} returns an empty result for an empty input, and SYSADMIN reads a stable
 * seeded setting. Forbidden ({@code 403}) and unauthorized ({@code 401}) calls are rejected before any
 * handler logic runs, so they cannot mutate state either.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class RoleAuthorizationMatrixITTest extends ITTest {

    /** Domain whose role chain the augmentor grants when named in the {@code NiordDomain} header. */
    private static final String NW_DOMAIN = "niord-nw";

    /** Representative endpoint for each @RolesAllowed tier. */
    private static final String USER_ENDPOINT     = "/rest/users/search?name=IT-REST-none";
    private static final String EDITOR_ENDPOINT   = "/rest/areas/intersecting-areas";
    private static final String ADMIN_ENDPOINT    = "/rest/users/kc-users";
    private static final String SYSADMIN_ENDPOINT = "/rest/settings/setting/documentationUrl";

    /**
     * Stable value of the SYSADMIN-protected setting read above: the test tree's niord.json (documentationUrl), which
     * the IT start writes as a setting; the fixture holds no Setting row.
     */
    private static final String SEED_SETTING_VALUE = "http://docs.niord.org";

    /** An empty GeoJSON FeatureCollection — a valid EDITOR-endpoint body that yields an empty result. */
    private static final Map<String, Object> EMPTY_FEATURE_COLLECTION =
            Map.of("type", "FeatureCollection", "features", List.of());

    /** A request carrying the given bearer token and the {@code NiordDomain} header (full role chain). */
    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    // ------------------------------------------------------------------------------------------- //
    // USER tier — GET /rest/users/search. Every authenticated identity carries 'user' (via the augmentor),
    // so all four are allowed; only the anonymous caller is rejected.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void userEndpoint_allowsUser() {
        as(accessToken("user")).when().get(USER_ENDPOINT)
                .then().statusCode(200).contentType(ContentType.JSON)
                .body("size()", greaterThanOrEqualTo(0));
    }

    @Test
    void userEndpoint_allowsEditor() {
        as(accessToken("editor")).when().get(USER_ENDPOINT).then().statusCode(200);
    }

    @Test
    void userEndpoint_allowsAdmin() {
        as(accessToken("admin")).when().get(USER_ENDPOINT).then().statusCode(200);
    }

    @Test
    void userEndpoint_allowsSysadmin() {
        as(accessToken()).when().get(USER_ENDPOINT).then().statusCode(200);
    }

    @Test
    void userEndpoint_rejectsAnonymousWith401() {
        given().when().get(USER_ENDPOINT).then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // EDITOR tier — POST /rest/areas/intersecting-areas. A plain 'user' is one tier too low -> 403;
    // editor and above are allowed and get an empty result for the empty feature collection.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void editorEndpoint_forbidsUserWith403() {
        // KEY adjacent-tier case: an authenticated user (role chain = {user}) lacks 'editor'.
        as(accessToken("user")).contentType(ContentType.JSON).body(EMPTY_FEATURE_COLLECTION)
                .when().post(EDITOR_ENDPOINT)
                .then().statusCode(403);
    }

    @Test
    void editorEndpoint_allowsEditor() {
        as(accessToken("editor")).contentType(ContentType.JSON).body(EMPTY_FEATURE_COLLECTION)
                .when().post(EDITOR_ENDPOINT)
                .then().statusCode(200).body("size()", is(0));
    }

    @Test
    void editorEndpoint_allowsAdmin() {
        as(accessToken("admin")).contentType(ContentType.JSON).body(EMPTY_FEATURE_COLLECTION)
                .when().post(EDITOR_ENDPOINT)
                .then().statusCode(200).body("size()", is(0));
    }

    @Test
    void editorEndpoint_allowsSysadmin() {
        as(accessToken()).contentType(ContentType.JSON).body(EMPTY_FEATURE_COLLECTION)
                .when().post(EDITOR_ENDPOINT)
                .then().statusCode(200).body("size()", is(0));
    }

    @Test
    void editorEndpoint_rejectsAnonymousWith401() {
        given().contentType(ContentType.JSON).body(EMPTY_FEATURE_COLLECTION)
                .when().post(EDITOR_ENDPOINT)
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // ADMIN tier — GET /rest/users/kc-users. user and editor are below the bar -> 403; admin and
    // sysadmin are allowed. (The endpoint reaches an unreachable Keycloak admin API and degrades to an
    // empty list, so the allowed outcome is a 200 with an empty array.)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void adminEndpoint_forbidsUserWith403() {
        as(accessToken("user")).when().get(ADMIN_ENDPOINT).then().statusCode(403);
    }

    @Test
    void adminEndpoint_forbidsEditorWith403() {
        // KEY adjacent-tier case: an authenticated editor (role chain = {editor, user}) lacks 'admin'.
        as(accessToken("editor")).when().get(ADMIN_ENDPOINT).then().statusCode(403);
    }

    @Test
    void adminEndpoint_allowsAdmin() {
        as(accessToken("admin")).when().get(ADMIN_ENDPOINT)
                .then().statusCode(200).contentType(ContentType.JSON).body("size()", is(0));
    }

    @Test
    void adminEndpoint_allowsSysadmin() {
        as(accessToken()).when().get(ADMIN_ENDPOINT)
                .then().statusCode(200).contentType(ContentType.JSON).body("size()", is(0));
    }

    @Test
    void adminEndpoint_rejectsAnonymousWith401() {
        given().when().get(ADMIN_ENDPOINT).then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // SYSADMIN tier — GET /rest/settings/setting/{key}. user, editor and admin are all below the bar
    // -> 403; only sysadmin is allowed and reads the seeded value.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void sysadminEndpoint_forbidsUserWith403() {
        as(accessToken("user")).when().get(SYSADMIN_ENDPOINT).then().statusCode(403);
    }

    @Test
    void sysadminEndpoint_forbidsEditorWith403() {
        as(accessToken("editor")).when().get(SYSADMIN_ENDPOINT).then().statusCode(403);
    }

    @Test
    void sysadminEndpoint_forbidsAdminWith403() {
        // KEY adjacent-tier case: an authenticated admin (role chain = {admin, editor, user}) lacks 'sysadmin'.
        as(accessToken("admin")).when().get(SYSADMIN_ENDPOINT).then().statusCode(403);
    }

    @Test
    void sysadminEndpoint_allowsSysadmin() {
        as(accessToken()).when().get(SYSADMIN_ENDPOINT)
                .then().statusCode(200).body("value", is(SEED_SETTING_VALUE));
    }

    @Test
    void sysadminEndpoint_rejectsAnonymousWith401() {
        given().when().get(SYSADMIN_ENDPOINT).then().statusCode(401);
    }
}
