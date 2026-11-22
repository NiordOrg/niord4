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
package org.niord.test;

import static io.restassured.RestAssured.given;

/**
 * Obtains real OIDC access tokens from the Keycloak test container, so integration tests can drive
 * the application's secured REST endpoints as an authenticated user.
 *
 * <p>Tokens are requested through the public {@code niord-web} client via a direct-access (password)
 * grant — exactly how the Niord front-end authenticates. {@code niord-web} is the public, browser-facing
 * client (no client secret); the application's own OIDC client {@code niord-client-master} is
 * <em>bearer-only</em> and cannot issue tokens, so it must not be used here. Because {@code niord-web}
 * uses full scope, the issued token carries each user's client roles under
 * {@code resource_access.<client>.roles} — including {@code resource_access.niord-client-master.roles},
 * which is exactly where the application reads roles from
 * ({@code quarkus.oidc.roles.role-claim-path}). This mirrors the real front-end-to-back-end flow.
 *
 * <h2>Role/identity matrix</h2>
 *
 * <p>The realm seeds four human users (passwords match the documented Niord bootstrap convention),
 * giving one identity per role tier so that authorization can be tested across <em>adjacent</em>
 * tiers rather than only "all roles" vs "no token":
 *
 * <table>
 *   <caption>Seeded users and their effective roles</caption>
 *   <tr><th>User / password</th><th>{@code niord-client-master}</th><th>{@code niord-nw}</th></tr>
 *   <tr><td>{@code sysadmin}/{@code sysadmin}</td><td>{@code sysadmin,admin,editor,user}</td><td>{@code sysadmin,admin,editor,user}</td></tr>
 *   <tr><td>{@code testadmin}/{@code test}</td><td>{@code admin,editor,user}</td><td>{@code admin,editor,user}</td></tr>
 *   <tr><td>{@code testeditor}/{@code test}</td><td>{@code editor,user}</td><td>{@code editor,user}</td></tr>
 *   <tr><td>{@code testuser}/{@code test}</td><td>(none)</td><td>{@code user}</td></tr>
 * </table>
 *
 * <p>Two more users hold their roles on a domain client only (the endpoint sweep's identities):
 * {@code testdomaineditor} ({@code editor, user} on {@code niord-nw}) and {@code testadminelsewhere} ({@code admin} on
 * {@code niord-nm}, {@code user} on {@code niord-nw}); neither holds a {@code niord-client-master} role.
 *
 * <p>Two features of the realm shape the matrix:
 * <ul>
 *   <li>{@code niord-client-master} has the same ladder as every domain client: {@code editor} holds
 *       {@code user}, {@code admin} holds {@code editor}, {@code sysadmin} holds {@code admin}. A base role
 *       counts in every domain, so {@code testeditor}, {@code testadmin} and {@code sysadmin} hold {@code user}
 *       with any {@code NiordDomain} header or none; {@code testuser} and the two domain-only users hold it
 *       only through the augmentor (a {@code NiordDomain: niord-nw} header, which adds
 *       {@code resource_access.niord-nw.roles}).</li>
 *   <li>{@code testuser} holds no {@code niord-client-master} role at all, so its bare token is
 *       authenticated but role-less: it is issued to {@code niord-web}, a client the application accepts
 *       tokens from, so it is rejected with {@code 403} on protected endpoints, never {@code 401}. (A
 *       token issued to any other client of the realm is refused with {@code 401} whatever its roles —
 *       see {@code JwtAuthorizedPartyITTest}.)</li>
 * </ul>
 *
 * <p>Because the augmentor only <em>adds</em> the {@code NiordDomain} client's roles to whatever the
 * base {@code niord-client-master} claim already carried, no identity can be elevated beyond its tier:
 * {@code testeditor} never gains {@code admin}, {@code testadmin} never gains {@code sysadmin}. That is
 * what makes "authenticated wrong-role -&gt; 403" between adjacent tiers testable.
 *
 * <p>Intentionally HTTP-only (no Keycloak client library) to stay close to the wire and easy to reason
 * about during a future framework migration.
 */
public final class KeycloakTestTokens {

    private KeycloakTestTokens() {
    }

    /**
     * Returns a bearer access token for the {@code sysadmin} user, valid against the running
     * application's OIDC configuration. The token's {@code niord-client-master} roles are
     * {@code {sysadmin, admin, editor, user}}.
     */
    public static String sysadminAccessToken() {
        return accessToken("sysadmin", "sysadmin");
    }

    /**
     * Returns a bearer access token for the {@code testadmin} user: {@code admin} (hence {@code editor})
     * on {@code niord-client-master}, but <em>not</em> {@code sysadmin}. Use it to assert that an
     * authenticated admin is rejected ({@code 403}) by {@code @RolesAllowed(SYSADMIN)} endpoints.
     */
    public static String adminAccessToken() {
        return accessToken("testadmin", "test");
    }

    /**
     * Returns a bearer access token for the {@code testeditor} user: {@code editor} on
     * {@code niord-client-master}, but <em>not</em> {@code admin}/{@code sysadmin}. Use it to assert that
     * an authenticated editor is rejected ({@code 403}) by {@code @RolesAllowed(ADMIN)} endpoints.
     */
    public static String editorAccessToken() {
        return accessToken("testeditor", "test");
    }

    /**
     * Returns a bearer access token for the {@code testuser} user, which carries no
     * {@code niord-client-master} role at all. The token is authenticated but role-less until a
     * {@code NiordDomain: niord-nw} header lets the augmentor grant the {@code user} role; even
     * then it never carries {@code editor}/{@code admin}/{@code sysadmin}. Use it to assert that an
     * authenticated plain user is rejected ({@code 403}) by EDITOR/ADMIN/SYSADMIN endpoints.
     */
    public static String userAccessToken() {
        return accessToken("testuser", "test");
    }

    /**
     * Returns a bearer access token for the {@code testdomaineditor} user: {@code editor} (hence {@code user}) on
     * {@code niord-nw} only, nothing on {@code niord-client-master}. Unlike {@code testeditor}, whose editor role sits
     * on the base client and holds in every domain, it is an editor only with a {@code NiordDomain: niord-nw} header.
     */
    public static String domainEditorAccessToken() {
        return accessToken("testdomaineditor", "test");
    }

    /**
     * Returns a bearer access token for the {@code testadminelsewhere} user: {@code admin} on {@code niord-nm} and
     * {@code user} on {@code niord-nw}, nothing on {@code niord-client-master}: an administrator of another domain,
     * a plain user in {@code niord-nw}.
     */
    public static String adminElsewhereAccessToken() {
        return accessToken("testadminelsewhere", "test");
    }

    /**
     * Requests a direct-access (password) grant token for the given user through the public
     * {@code niord-web} client. Fails the calling test if Keycloak does not return {@code 200}.
     */
    private static String accessToken(String username, String password) {
        String tokenUrl = NiordTestContainers.keycloakRealmUrl() + "/protocol/openid-connect/token";

        return given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", "password")
                .formParam("client_id", "niord-web")
                .formParam("username", username)
                .formParam("password", password)
                .formParam("scope", "openid")
                .when()
                .post(tokenUrl)
                .then()
                .log().ifValidationFails()
                .statusCode(200)
                .extract().path("access_token");
    }
}
