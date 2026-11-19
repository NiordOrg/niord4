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
package org.niord.web;

import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Integration tests for {@link TicketServletFilter} — the consuming half of the ticket flow, over real
 * HTTP.
 *
 * <p>{@link TicketRestServiceITTest} covers issuing; this covers what a {@code ?ticket=} on a
 * subsequent, token-less request actually does. The probe is the ticket endpoint itself, because it
 * reflects both halves of the resolved identity: {@code UserService.isCallerInRole(String)} decides
 * whether a role-scoped request is granted, and — since a resolved ticket makes the caller no longer
 * "anonymous" ({@code TicketRestService.getTicket}) — the rejection code distinguishes
 * <em>the ticket did not resolve</em> ({@code 401}) from <em>it resolved but grants nothing</em>
 * ({@code 403}). So a single endpoint proves the filter ran, proves it bound the right roles, and
 * proves the fail-closed treatment of a role-less ticket, with no extra fixture.
 *
 * <p>Tickets are short-lived in-memory cache entries with no database rows, so no cleanup is required;
 * every test mints its own, so the tests are order-independent.
 */
class TicketServletFilterITTest extends ITTest {

    /**
     * Mints a one-time ticket for the given roles as the authenticated sysadmin — who holds
     * {@code {sysadmin, admin, editor, user}} on the bare token. Passing no roles takes
     * {@code TicketRestService}'s role-less path, the one the publication-report self-call uses.
     */
    private String issueTicket(String... roles) {
        RequestSpecification request = given().auth().oauth2(accessToken());
        for (String role : roles) {
            request = request.queryParam("role", role);
        }
        return request
                .when().get("/rest/tickets/ticket")
                .then().statusCode(200)
                .extract().asString().trim();
    }

    @Test
    void ticketInTheQueryStringAuthenticatesAnOtherwiseAnonymousRequest() {
        // The same request without the ticket is a 401 — the ticket is the only credential here.
        String ticket = issueTicket("editor");

        String issued = given()
                .queryParam("role", "editor")
                .queryParam("ticket", ticket)
                .when().get("/rest/tickets/ticket")
                .then().statusCode(200)
                .contentType(containsString("text/plain"))
                .extract().asString().trim();

        assertFalse(issued.isEmpty(),
                "a request bearing only a valid editor ticket must be treated as an editor");
    }

    @Test
    void ticketGrantsOnlyTheRolesItWasIssuedFor() {
        // 403, not 401: the ticket resolved (so the caller is not anonymous) but carries no 'sysadmin'.
        String ticket = issueTicket("editor");

        given()
                .queryParam("role", "sysadmin")
                .queryParam("ticket", ticket)
                .when().get("/rest/tickets/ticket")
                .then().statusCode(403);
    }

    @Test
    void aRoleLessTicketAuthenticatesButGrantsNoRole() {
        // TICKET1: a ticket issued with no 'role' param carries identity (domain + user) and nothing
        // else, so it must grant no role at all. Again 403 rather than 401 — the ticket did resolve.
        String ticket = issueTicket();

        given()
                .queryParam("role", "editor")
                .queryParam("ticket", ticket)
                .when().get("/rest/tickets/ticket")
                .then().statusCode(403);
    }

    @Test
    void anUnknownTicketLeavesTheRequestAnonymous() {
        // By design: the ticket is an additive credential, so one the cache does not know is not an
        // error — the request simply stays anonymous and the role check then fails closed with 401.
        given()
                .queryParam("role", "editor")
                .queryParam("ticket", "IT-this-ticket-was-never-issued")
                .when().get("/rest/tickets/ticket")
                .then().statusCode(401);
    }

    @Test
    void aTicketIsConsumedByTheRequestThatPresentsIt() {
        String ticket = issueTicket("editor");

        given()
                .queryParam("role", "editor")
                .queryParam("ticket", ticket)
                .when().get("/rest/tickets/ticket")
                .then().statusCode(200);

        // One-time use: the second presentation resolves nothing, so the caller is anonymous again.
        given()
                .queryParam("role", "editor")
                .queryParam("ticket", ticket)
                .when().get("/rest/tickets/ticket")
                .then().statusCode(401);
    }
}
