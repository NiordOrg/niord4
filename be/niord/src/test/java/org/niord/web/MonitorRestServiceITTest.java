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
import org.niord.test.ITTest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Integration tests for {@link MonitorRestService}: the HTTP contract of the unauthenticated
 * {@code /rest/monitor} liveness/readiness probes.
 *
 * <p><b>Authorization model under test.</b> The service carries <em>no</em> security annotations —
 * neither a class-level {@code @RolesAllowed} nor any programmatic role check. Both endpoints are
 * therefore reachable anonymously, which is the whole point of a dead-or-alive monitor: an external
 * watchdog must be able to probe Niord without credentials. Consequently there is no 401/403 tier
 * oracle to exercise here, and these tests issue plain (token-less, header-less) requests.
 *
 * <p><b>Behaviour oracle.</b> {@code GET /rest/monitor/ping} is a pure liveness check that always
 * returns the literal {@code text/plain} body {@code "pong"} (HTTP 200). {@code GET /rest/monitor/db}
 * is a readiness check that runs {@code SELECT 1} against the database and returns the literal body
 * {@code "success"} (HTTP 200) when the connection is healthy; only a broken DB connection would make
 * it throw {@code NiordWebException(500)}. Because {@link NiordContainersResource} brings up the
 * test database container before the suite runs, the {@code SELECT 1} succeeds and {@code /db} reports
 * {@code "success"}.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class MonitorRestServiceITTest extends ITTest {

    private static final String PING = "/rest/monitor/ping";
    private static final String DB   = "/rest/monitor/db";

    // ------------------------------------------------------------------------------------------- //
    // GET /ping — anonymous liveness probe, always returns the literal "pong" as text/plain.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void ping_anonymousReturnsPongAsPlainText() {
        String body = given()
                .when().get(PING)
                .then().statusCode(200)
                .contentType(containsString("text/plain"))
                .extract().asString();

        org.junit.jupiter.api.Assertions.assertEquals("pong", body.trim());
    }

    // ------------------------------------------------------------------------------------------- //
    // GET /db — anonymous readiness probe; the test DB container is up so SELECT 1 succeeds and the
    // handler returns the literal "success" (a broken connection would instead yield 500).
    // ------------------------------------------------------------------------------------------- //

    @Test
    void db_anonymousReturnsSuccessWhenDatabaseReachable() {
        String body = given()
                .when().get(DB)
                .then().statusCode(200)
                .extract().asString();

        org.junit.jupiter.api.Assertions.assertEquals("success", body.trim());
    }
}
