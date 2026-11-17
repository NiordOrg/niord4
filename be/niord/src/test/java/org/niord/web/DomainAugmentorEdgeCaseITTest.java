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

import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;

import static io.restassured.RestAssured.given;

/**
 * Security edge-case coverage for the role augmentation of {@link org.niord.core.web.NiordDomainFilter}.
 *
 * <p>{@code RoleAuthorizationMatrixITTest} proves the {@code @RolesAllowed} hierarchy is enforced when each
 * identity is driven with its <em>own</em> domain ({@code NiordDomain: niord-nw}). This test attacks
 * the augmentation itself, exercising the cases that matrix does <em>not</em>: it verifies that the filter
 * can only ever <em>add</em> the roles the bearer token actually carries on the exact domain named in the
 * {@code NiordDomain} header, and can never be abused to escalate a caller beyond their tier or to mint roles
 * by naming an unrelated or unknown domain.
 *
 * <p>The filter augments only an <em>authenticated</em> bearer token, and only when the {@code NiordDomain}
 * header is present and names a stored, active Niord domain; it then adds exactly
 * {@code resource_access[NiordDomain].roles} from the verified JWT — nothing else. Three consequences follow:
 * an <em>unknown</em> client name resolves to no entry in {@code resource_access} and yields an empty role
 * list (fail-closed, no roles added); a token that does not already carry a role on the named domain cannot
 * acquire it (no escalation); and roles the token does carry on a Keycloak client that is <em>not</em> a Niord
 * domain — the SPA client {@code niord-web} defines the same role names — are not added either.
 *
 * <p>Role matrix the assertions are reasoned from (see {@link KeycloakTestTokens}):
 * <table>
 *   <caption>Effective client roles per seeded identity</caption>
 *   <tr><th>identity</th><th>{@code niord-client-master}</th><th>{@code niord-nw}</th></tr>
 *   <tr><td>sysadmin</td><td>{@code sysadmin,admin,editor}</td><td>{@code sysadmin,admin,editor,user}</td></tr>
 *   <tr><td>testadmin (admin)</td><td>{@code admin,editor}</td><td>{@code admin,editor,user}</td></tr>
 *   <tr><td>testeditor (editor)</td><td>{@code editor}</td><td>{@code editor,user}</td></tr>
 *   <tr><td>testuser (user)</td><td>(none)</td><td>{@code user}</td></tr>
 * </table>
 *
 * <p>Representative side-effect-free endpoints, one per {@code @RolesAllowed} tier (identical to
 * {@code RoleAuthorizationMatrixITTest}, so the only variable here is the augmentation): USER reads are
 * {@code GET /rest/users/search} ({@code @RolesAllowed(USER)}, with a name no user has), ADMIN reads are
 * {@code GET /rest/users/kc-users}
 * ({@code @RolesAllowed(ADMIN)}), and SYSADMIN reads are {@code GET /rest/settings/setting/documentationUrl}
 * (class-level {@code @RolesAllowed(SYSADMIN)}). {@code 403} and {@code 401} are decided before any handler
 * runs, and the allowed reads create no data, so no cleanup is needed.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class DomainAugmentorEdgeCaseITTest extends ITTest {

    /** The domain whose role chain the augmentor legitimately grants when named in the header. */
    private static final String NW_DOMAIN = "niord-nw";
    /** The base bearer-only client; defines no {@code user} role, so it never grants USER on its own. */
    private static final String MASTER_DOMAIN = "niord-client-master";
    /** The SPA client: it defines user/editor/admin/sysadmin roles of its own but is not a Niord domain. */
    private static final String SPA_CLIENT = "niord-web";
    /** A client that does not exist in any token's {@code resource_access} — the augmentor must add nothing. */
    private static final String UNKNOWN_DOMAIN = "no-such-domain-xyz";

    /** Representative endpoint for each @RolesAllowed tier. */
    private static final String USER_ENDPOINT  = "/rest/users/search?name=IT-REST-none";
    private static final String ADMIN_ENDPOINT = "/rest/users/kc-users";
    private static final String SYSADMIN_ENDPOINT = "/rest/settings/setting/documentationUrl";

    /** A request carrying the given bearer token and an explicit {@code NiordDomain} header. */
    private static RequestSpecification as(String token, String domain) {
        return given().auth().oauth2(token).header("NiordDomain", domain);
    }

    // ========================================================================================== //
    // NO ESCALATION — the augmentor adds only the roles the token holds on the named domain; it
    // can never invent a higher tier than the token already carries there.
    // ========================================================================================== //

    @Test
    void editorNamingNwDomain_cannotReachAdminEndpoint_403() {
        // niord-nw grants testeditor {editor, user} — NOT admin. The augmentor adds exactly
        // those roles, so the ADMIN-tier endpoint must still reject with 403.
        as(accessToken("editor"), NW_DOMAIN).when().get(ADMIN_ENDPOINT).then().statusCode(403);
    }

    @Test
    void editorNamingMasterDomain_cannotReachAdminEndpoint_403() {
        // niord-client-master grants testeditor only {editor}. Naming the base client adds nothing the
        // token did not already carry, so admin remains out of reach -> 403.
        as(accessToken("editor"), MASTER_DOMAIN).when().get(ADMIN_ENDPOINT).then().statusCode(403);
    }

    @Test
    void adminNamingNwDomain_cannotReachSysadminEndpoint_403() {
        // niord-nw grants testadmin {admin, editor, user} — NOT sysadmin. An admin cannot become
        // sysadmin via the augmentor, so the SYSADMIN-tier endpoint must reject with 403.
        as(accessToken("admin"), NW_DOMAIN).when().get(SYSADMIN_ENDPOINT).then().statusCode(403);
    }

    @Test
    void userNamingNwDomain_cannotReachAdminEndpoint_403() {
        // testuser's only role anywhere is {user} (on niord-nw). It is two tiers below ADMIN -> 403.
        as(accessToken("user"), NW_DOMAIN).when().get(ADMIN_ENDPOINT).then().statusCode(403);
    }

    // ========================================================================================== //
    // FAIL CLOSED ON UNKNOWN / UNRELATED DOMAIN — an unknown client name resolves to no entry in
    // resource_access, so getRoles() returns empty and the augmentor adds nothing.
    // ========================================================================================== //

    @Test
    void userNamingUnknownDomain_getsNoRoles_userEndpoint403() {
        // testuser holds no niord-client-master role at all; an unknown domain adds nothing, so the
        // identity stays role-less and the USER endpoint rejects with 403 (authenticated but unauthorized).
        as(accessToken("user"), UNKNOWN_DOMAIN).when().get(USER_ENDPOINT).then().statusCode(403);
    }

    @Test
    void userNamingNwDomain_getsUserRole_userEndpoint200() {
        // Positive contrast to the unknown-domain case: naming the RIGHT domain grants the {user} role,
        // so the very same token now passes the USER endpoint.
        as(accessToken("user"), NW_DOMAIN).when().get(USER_ENDPOINT).then().statusCode(200);
    }

    @Test
    void sysadminNamingUnknownDomain_getsNoRoles_userEndpoint403() {
        // sysadmin's niord-client-master roles are {sysadmin, admin, editor} — notably NOT user. Because
        // niord-client-master defines no user role, the only way to USER is via the augmentor; an unknown
        // domain adds nothing, so even the all-powerful token is rejected by the USER endpoint -> 403.
        as(accessToken(), UNKNOWN_DOMAIN).when().get(USER_ENDPOINT).then().statusCode(403);
    }

    @Test
    void sysadminNamingTheSpaClient_getsNoRolesFromIt_userEndpoint403() {
        // The token is issued through niord-web, so it carries resource_access.niord-web roles, whose
        // composites include user. niord-web is a Keycloak client but not a Niord domain, so naming it in
        // the header must add nothing: the base roles {sysadmin, admin, editor} hold no user -> 403.
        as(accessToken(), SPA_CLIENT).when().get(USER_ENDPOINT).then().statusCode(403);
    }

    @Test
    void sysadminNamingNwDomain_getsUserRole_userEndpoint200() {
        // Positive contrast: naming niord-nw adds {user} to the sysadmin token -> USER endpoint 200.
        as(accessToken(), NW_DOMAIN).when().get(USER_ENDPOINT).then().statusCode(200);
    }

    // ========================================================================================== //
    // NO HEADER — the augmentor needs the NiordDomain header to act at all. Without it, only the base
    // niord-client-master roles apply (which never include user).
    // ========================================================================================== //

    @Test
    void userWithoutDomainHeader_getsNoUserRole_userEndpoint403() {
        // Same testuser token, but no NiordDomain header: the augmentor does not run, base master roles
        // are empty, so the USER endpoint rejects with 403.
        given().auth().oauth2(accessToken("user")).when().get(USER_ENDPOINT).then().statusCode(403);
    }

    @Test
    void sysadminWithoutDomainHeader_getsNoUserRole_userEndpoint403() {
        // Even the sysadmin token cannot reach the USER endpoint without the NiordDomain header, because
        // niord-client-master defines no user role and the augmentor is the only source of it.
        given().auth().oauth2(accessToken()).when().get(USER_ENDPOINT).then().statusCode(403);
    }

    // ========================================================================================== //
    // ADDITIVE-ONLY SANITY — the augmentor still grants the roles a token legitimately holds, and
    // requires authentication: the header alone (no token) does nothing.
    // ========================================================================================== //

    @Test
    void adminNamingNwDomain_reachesAdminEndpoint_200() {
        // niord-nw grants testadmin {admin, editor, user}; admin is exactly what the endpoint needs.
        as(accessToken("admin"), NW_DOMAIN).when().get(ADMIN_ENDPOINT).then().statusCode(200);
    }

    @Test
    void anonymousWithDomainHeaderOnly_userEndpoint401() {
        // The augmentor requires the Authorization header to add anything; a NiordDomain header on an
        // unauthenticated request grants no roles, so the call is rejected as unauthorized (401, not 403).
        given().header("NiordDomain", NW_DOMAIN).when().get(USER_ENDPOINT).then().statusCode(401);
    }
}
