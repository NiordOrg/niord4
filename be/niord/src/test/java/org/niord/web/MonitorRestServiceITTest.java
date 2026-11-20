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
import org.niord.core.message.MessageLuceneIndex;
import org.niord.core.message.MessagePostgresIndex;
import org.niord.test.ITTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Integration tests for {@link MonitorRestService}: the HTTP contract of the unauthenticated
 * {@code /rest/monitor} liveness/readiness probes.
 *
 * <p><b>Authorization model under test.</b> The service is class-level {@code @PermitAll}: the two
 * probes are reachable anonymously, which is the whole point of a dead-or-alive monitor: an external
 * watchdog must be able to probe Niord without credentials, so their tests issue plain (token-less,
 * header-less) requests. {@code GET /rest/monitor/message-index} is {@code @RolesAllowed(SYSADMIN)}:
 * anonymous is refused with 401, a lower role with 403.
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
    private static final String MESSAGE_INDEX = "/rest/monitor/message-index";

    @Autowired
    MessageLuceneIndex messageLuceneIndex;

    @Autowired
    MessagePostgresIndex messagePostgresIndex;

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

    // ------------------------------------------------------------------------------------------- //
    // GET /message-index — whether both message search indexes are complete, for system
    // administrators only (method-level @RolesAllowed over the class's @PermitAll)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void messageIndex_anonymousIsUnauthorized() {
        given()
                .when().get(MESSAGE_INDEX)
                .then().statusCode(401);
    }

    @Test
    void messageIndex_aLowerRoleIsForbidden() {
        for (String role : new String[] {"user", "editor", "admin"}) {
            given().auth().oauth2(accessToken(role))
                    .when().get(MESSAGE_INDEX)
                    .then().statusCode(403);
        }
    }

    /**
     * After both indexes are filled again (a re-index, as after a start), the answer says each is complete, and both
     * together; it reads the indexes' state as it is at the call. A re-index of the PostgreSQL table that has not yet
     * committed would say that index is not complete; the unit tests hold that.
     */
    @Test
    void messageIndex_sysadminReadsEachIndexAndTheWhole() throws Exception {
        messageLuceneIndex.recreateIndex();
        messagePostgresIndex.recreateIndex();

        given().auth().oauth2(accessToken())
                .when().get(MESSAGE_INDEX)
                .then().statusCode(200)
                .contentType(containsString("application/json"))
                .body("lucene", is(true))
                .body("postgres", is(true))
                .body("complete", is(true))
                .body("size()", is(3));

        boolean was = messageLuceneIndex.allMessagesIndexed();
        Object lucene = AopTestUtils.getUltimateTargetObject(messageLuceneIndex);
        ReflectionTestUtils.setField(lucene, "allMessagesIndexed", false);
        try {
            given().auth().oauth2(accessToken())
                    .when().get(MESSAGE_INDEX)
                    .then().statusCode(200)
                    .body("lucene", is(false))
                    .body("postgres", is(true))
                    .body("complete", is(false));
        } finally {
            ReflectionTestUtils.setField(lucene, "allMessagesIndexed", was);
        }
    }
}
