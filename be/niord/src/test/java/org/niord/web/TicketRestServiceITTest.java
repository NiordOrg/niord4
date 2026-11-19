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

import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import java.util.HashSet;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Integration tests for {@link TicketRestService}: the HTTP contract of {@code GET /rest/tickets/ticket}.
 *
 * <p>The endpoint is {@code @PermitAll}, so it is reachable without a token and — when no {@code role}
 * query parameter is supplied — always issues a fresh, non-blank UUID ticket tied to the current
 * domain/user (which may be {@code null}). When one or more {@code role} parameters are supplied, the
 * endpoint requires the <em>current caller</em> to hold every requested role
 * ({@link org.niord.core.user.UserService#isCallerInRole(String)}); otherwise it rejects the request
 * programmatically — {@code 401} for a caller presenting no credentials at all, {@code 403} for an
 * authenticated caller lacking a requested role.
 *
 * <p>The authorization matrix mirrors the rest of the suite (see {@code KeycloakTestTokens}): the bare
 * {@code sysadmin} token carries its base-client ladder {@code {sysadmin, admin, editor, user}}, while the bare
 * {@code testuser} token carries no base role, so its ticket scoped to {@code role=user} only succeeds once a
 * {@code NiordDomain} header lets the Niord augmentor grant the domain's roles. Tickets are short-lived, in-memory cache entries (1 minute
 * expiry) with no database rows, so no cleanup is required.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class TicketRestServiceITTest extends ITTest {

    /** A domain whose Keycloak client grants the sysadmin user the full {user,editor,admin,sysadmin} chain. */
    private static final String NW_DOMAIN = "niord-nw";

    // ------------------------------------------------------------------------------------------- //
    // Roleless tickets — @PermitAll, always issued
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getTicketWithoutTokenAndWithoutRolesReturnsNonBlankTicket() {
        // @PermitAll: an anonymous caller still gets a ticket (domain/user are null, but a UUID is issued).
        String ticket = given()
                .when().get("/rest/tickets/ticket")
                .then().statusCode(200)
                .contentType(containsString("text/plain"))
                .extract().asString().trim();

        assertFalse(ticket.isEmpty(), "a roleless ticket request must yield a non-blank ticket");
    }

    @Test
    void getTicketWithSysadminTokenAndWithoutRolesReturnsNonBlankTicket() {
        String ticket = given().auth().oauth2(accessToken())
                .when().get("/rest/tickets/ticket")
                .then().statusCode(200)
                .contentType(containsString("text/plain"))
                .extract().asString().trim();

        assertFalse(ticket.isEmpty(), "an authenticated roleless ticket request must yield a non-blank ticket");
    }

    @Test
    void getTicketLooksLikeAUuid() {
        // The ticket is a java.util.UUID, so the issued value must parse as one (5 dash-separated groups).
        String ticket = given().auth().oauth2(accessToken())
                .when().get("/rest/tickets/ticket")
                .then().statusCode(200)
                .extract().asString().trim();

        String[] groups = ticket.split("-");
        assertEquals(5, groups.length, "a ticket must be a UUID with five dash-separated groups, but was: " + ticket);
        assertEquals(36, ticket.length(), "a UUID ticket must be 36 characters long, but was: " + ticket);
    }

    @Test
    void getTicketIssuesAFreshTicketEachCall() {
        // Each call issues a brand new one-time ticket, so two consecutive calls must differ.
        String first = given().auth().oauth2(accessToken())
                .when().get("/rest/tickets/ticket")
                .then().statusCode(200)
                .extract().asString().trim();

        String second = given().auth().oauth2(accessToken())
                .when().get("/rest/tickets/ticket")
                .then().statusCode(200)
                .extract().asString().trim();

        assertNotEquals(first, second, "consecutive ticket requests must yield distinct tickets");
    }

    @Test
    void getTicketWithDomainHeaderButNoRolesReturnsTicket() {
        // A NiordDomain header without any role parameter still takes the roleless path: a ticket tied to
        // the resolved domain/user is issued without any role check.
        String ticket = given().auth().oauth2(accessToken())
                .header("NiordDomain", NW_DOMAIN)
                .when().get("/rest/tickets/ticket")
                .then().statusCode(200)
                .extract().asString().trim();

        assertFalse(ticket.isEmpty(), "a domain-scoped roleless request must still yield a non-blank ticket");
    }

    // ------------------------------------------------------------------------------------------- //
    // Role-scoped tickets — caller must hold every requested role
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getTicketForEditorRoleWithSysadminTokenReturnsTicket() {
        // The bare sysadmin token carries the 'editor' role, so an editor-scoped ticket is issued.
        String ticket = given().auth().oauth2(accessToken())
                .queryParam("role", "editor")
                .when().get("/rest/tickets/ticket")
                .then().statusCode(200)
                .extract().asString().trim();

        assertFalse(ticket.isEmpty(), "an editor-scoped ticket must be issued for a caller that holds 'editor'");
    }

    @Test
    void getTicketForAdminRoleWithSysadminTokenReturnsTicket() {
        String ticket = given().auth().oauth2(accessToken())
                .queryParam("role", "admin")
                .when().get("/rest/tickets/ticket")
                .then().statusCode(200)
                .extract().asString().trim();

        assertFalse(ticket.isEmpty(), "an admin-scoped ticket must be issued for a caller that holds 'admin'");
    }

    @Test
    void getTicketForSysadminRoleWithSysadminTokenReturnsTicket() {
        String ticket = given().auth().oauth2(accessToken())
                .queryParam("role", "sysadmin")
                .when().get("/rest/tickets/ticket")
                .then().statusCode(200)
                .extract().asString().trim();

        assertFalse(ticket.isEmpty(), "a sysadmin-scoped ticket must be issued for a caller that holds 'sysadmin'");
    }

    @Test
    void getTicketForMultipleHeldRolesReturnsTicket() {
        // The caller holds both 'editor' and 'admin', so a ticket scoped to both is issued.
        String ticket = given().auth().oauth2(accessToken())
                .queryParam("role", "editor")
                .queryParam("role", "admin")
                .when().get("/rest/tickets/ticket")
                .then().statusCode(200)
                .extract().asString().trim();

        assertFalse(ticket.isEmpty(), "a ticket scoped to multiple held roles must be issued");
    }

    @Test
    void getTicketForUserRoleWithBareTokenIsForbidden() {
        // testuser holds 'user' only on niord-nw's client: without a NiordDomain header to trigger the
        // augmentation its bare token carries no role, so it is rejected programmatically with 403.
        given().auth().oauth2(accessToken("user"))
                .queryParam("role", "user")
                .when().get("/rest/tickets/ticket")
                .then().statusCode(403);
    }

    @Test
    void getTicketForUserRoleWithBareSysadminTokenReturnsTicket() {
        // The base client's ladder holds 'user' (sysadmin > admin > editor > user), so the bare sysadmin
        // token is a user without any NiordDomain header.
        String ticket = given().auth().oauth2(accessToken())
                .queryParam("role", "user")
                .when().get("/rest/tickets/ticket")
                .then().statusCode(200)
                .extract().asString().trim();

        assertFalse(ticket.isEmpty(), "a user-scoped ticket must be issued for a base sysadmin");
    }

    @Test
    void getTicketForUserRoleWithDomainHeaderReturnsTicket() {
        // With a NiordDomain header the augmentor grants testuser the 'user' role of that domain, so the
        // user-scoped ticket request now succeeds.
        String ticket = given().auth().oauth2(accessToken("user"))
                .header("NiordDomain", NW_DOMAIN)
                .queryParam("role", "user")
                .when().get("/rest/tickets/ticket")
                .then().statusCode(200)
                .extract().asString().trim();

        assertFalse(ticket.isEmpty(), "a user-scoped ticket must be issued once the domain header grants 'user'");
    }

    @Test
    void getTicketForUnknownRoleIsForbidden() {
        // The authenticated caller does not hold a fabricated role, so the role check fails with 403.
        given().auth().oauth2(accessToken())
                .queryParam("role", "IT-REST-no-such-role")
                .when().get("/rest/tickets/ticket")
                .then().statusCode(403);
    }

    @Test
    void getTicketForMixedHeldAndUnheldRolesIsForbidden() {
        // The bare testeditor token holds 'editor' but not 'admin', and the endpoint requires ALL requested
        // roles, so the whole request is rejected with 403.
        given().auth().oauth2(accessToken("editor"))
                .queryParam("role", "editor")
                .queryParam("role", "admin")
                .when().get("/rest/tickets/ticket")
                .then().statusCode(403);
    }

    @Test
    void getTicketRoleCheckIsCaseSensitive() {
        // isCallerInRole / hasRole use an exact string match; the authenticated caller holds 'editor'
        // (lower-case) but not 'EDITOR', so an upper-case role request is rejected with 403.
        given().auth().oauth2(accessToken())
                .queryParam("role", "EDITOR")
                .when().get("/rest/tickets/ticket")
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // Role-scoped tickets without a token — anonymous caller holds no roles
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getTicketForEditorRoleWithoutTokenIsUnauthorized() {
        // @PermitAll lets the anonymous request through, but the anonymous caller holds no roles, so the
        // programmatic role check fails with 401.
        given()
                .queryParam("role", "editor")
                .when().get("/rest/tickets/ticket")
                .then().statusCode(401);
    }

    @Test
    void getTicketForUserRoleWithoutTokenIsUnauthorized() {
        given()
                .queryParam("role", "user")
                .when().get("/rest/tickets/ticket")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // Method / routing contract
    // ------------------------------------------------------------------------------------------- //

    @Test
    void postToTicketEndpointIsNotAllowed() {
        // Only GET is mapped on /tickets/ticket, so a POST must be rejected as method-not-allowed.
        given().auth().oauth2(accessToken())
                .when().post("/rest/tickets/ticket")
                .then().statusCode(405);
    }

    @Test
    void issuedTicketsAreUnique() {
        // Stronger uniqueness check across several issued tickets — the ticket UUIDs must never collide.
        Set<String> tickets = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            String ticket = given().auth().oauth2(accessToken())
                    .when().get("/rest/tickets/ticket")
                    .then().statusCode(200)
                    .extract().asString().trim();
            tickets.add(ticket);
        }
        assertEquals(5, tickets.size(), "all issued tickets must be unique");
    }
}
