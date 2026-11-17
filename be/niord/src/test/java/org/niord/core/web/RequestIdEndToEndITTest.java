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
package org.niord.core.web;

import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link RequestIdFilter} through the real servlet container and the real Spring Security
 * chain — the two things {@code RequestIdFilterTest} cannot cover with mock servlet plumbing.
 *
 * <p>The load-bearing case is {@link #echoesTheRequestIdOnASecurityRejection()}. The filter is
 * registered at {@code HIGHEST_PRECEDENCE}, ahead of the Spring Security filter chain, precisely so
 * that a request rejected before it ever reaches a controller still carries a correlation id in its
 * logs and its response. A 401 with no id is a request nobody can trace, and those are the ones
 * people come asking about.
 */
class RequestIdEndToEndITTest extends ITTest {

    /** Class-level {@code @RolesAllowed(SYSADMIN)}, and this method does not opt out with @PermitAll. */
    private static final String SYSADMIN_ONLY = "/rest/settings/setting/some-key";

    /** Anonymous and cheap — no authentication, no database work. */
    private static final String ANONYMOUS = "/rest/monitor/ping";

    @Test
    void generatesAndReturnsARequestIdWhenTheCallerSuppliesNone() {
        Response response = given().when().get(ANONYMOUS).then().statusCode(200).extract().response();

        String id = response.getHeader(RequestIdFilter.HEADER);
        assertNotNull(id, "every response must carry the id its log lines were written under");
        assertTrue(id.matches("[0-9a-f]{12}"), "expected a generated id, got: " + id);
    }

    @Test
    void joinsTheCallersTraceWhenTheSuppliedIdIsWellFormed() {
        String id = given().header(RequestIdFilter.HEADER, "edge-proxy.abc-123")
                .when().get(ANONYMOUS)
                .then().statusCode(200)
                .extract().header(RequestIdFilter.HEADER);

        assertEquals("edge-proxy.abc-123", id);
    }

    @Test
    void replacesAndDoesNotReflectAnUnusableSuppliedId() {
        // Spaces would split the req= field in the log pattern. Sent over a real socket, so this uses
        // a value a real HTTP client will actually transmit rather than a CR/LF payload.
        String hostile = "req=someone-elses-trace and more";

        String id = given().header(RequestIdFilter.HEADER, hostile)
                .when().get(ANONYMOUS)
                .then().statusCode(200)
                .extract().header(RequestIdFilter.HEADER);

        assertNotEquals(hostile, id, "an unusable value must not be reflected back to the caller");
        assertTrue(id.matches("[0-9a-f]{12}"), "expected a generated id, got: " + id);
    }

    @Test
    void replacesAnOverlongSuppliedId() {
        String id = given().header(RequestIdFilter.HEADER, "a".repeat(200))
                .when().get(ANONYMOUS)
                .then().statusCode(200)
                .extract().header(RequestIdFilter.HEADER);

        assertTrue(id.matches("[0-9a-f]{12}"), "expected a generated id, got: " + id);
    }

    /**
     * The ordering guarantee. An anonymous caller hitting a {@code @RolesAllowed(SYSADMIN)} endpoint
     * is rejected with 401 by Spring Security's {@code BearerTokenAuthenticationEntryPoint} — well
     * before any Niord code runs. If the filter were registered after the security chain (or in a
     * profile-gated configuration), this response would have no id.
     */
    @Test
    void echoesTheRequestIdOnASecurityRejection() {
        String id = given().header(RequestIdFilter.HEADER, "trace-denied")
                .when().get(SYSADMIN_ONLY)
                .then().statusCode(401)
                .extract().header(RequestIdFilter.HEADER);

        assertEquals("trace-denied", id, "a rejected request is exactly the one worth tracing");
    }

    @Test
    void issuesADistinctIdToEachCaller() {
        String first = given().when().get(ANONYMOUS).then().extract().header(RequestIdFilter.HEADER);
        String second = given().when().get(ANONYMOUS).then().extract().header(RequestIdFilter.HEADER);

        assertNotEquals(first, second, "ids must not repeat across requests");
    }
}
