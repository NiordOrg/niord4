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

import org.springframework.transaction.annotation.Transactional;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Complementary integration tests for {@link org.niord.web.BatchRestService}, focused on the
 * <em>authorized happy paths</em> of the {@code /rest/batch} read endpoints and the
 * {@code execute-javascript} contract — the response bodies and side effects, not just the
 * 401/403 authorization gate already covered by {@link org.niord.web.BatchRestServiceITTest}.
 *
 * <p>What is asserted here, end to end against the real Keycloak token and the seeded database:
 * <ul>
 *   <li>{@code /job-names} returns the distinct job names persisted in {@code BatchData} (the seed
 *       database carries several import jobs), so the list is non-empty and every entry is non-blank.</li>
 *   <li>{@code /{jobName}/instances} for a job that has never executed in this JVM answers a 5xx — the
 *       documented {@code NoSuchJobException} bug (see below and docs/potential-errors.md), pinned so a
 *       later fix that returns an empty paged envelope deliberately updates the expectation.</li>
 *   <li>{@code /instance/{id}/logs} for an unknown instance answers a 500 — a second documented bug
 *       ({@code IllegalArgumentException} for a missing instance is not mapped to 404/200).</li>
 *   <li>{@code /instance/{id}/thumbnail.png} is a {@code @PermitAll} endpoint that redirects (HTTP 307)
 *       to the generic file-type icon for an unknown instance; the {@code Location} resolves (after JAX-RS
 *       absolutises the {@code ".."}-relative URI) to {@code .../img/filetypes/generic-32_32.png}.</li>
 *   <li>{@code /status} with an admin token reports a non-empty {@code types} list (one entry per
 *       distinct job name).</li>
 *   <li>{@code /execute-javascript} with a <em>blank</em> script returns {@code "OK"} (the
 *       {@code StringUtils.isNotBlank} guard starts no batch job).</li>
 * </ul>
 *
 * <p>Test independence: none of these tests depend on JBeret's accumulating per-JVM job-instance state.
 * The {@code /instances} bug-pinning test uses a freshly generated, guaranteed-never-run job name; the
 * blank-script test only asserts the HTTP contract of its own POST. No row or job is left behind that
 * another test relies on, so every test passes alone and in the full suite.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchRestGapITTest extends ITTest {

    private static final long UNKNOWN_INSTANCE_ID = 999999999L;

    // ------------------------------------------------------------------------------------------- //
    // /job-names — authorized read returns the seeded job names
    // ------------------------------------------------------------------------------------------- //

    @Test
    void jobNamesWithAdminTokenReturnsSeededJobNames() {
        // The seed database carries several import jobs (domain-import, chart-import, ...), so the
        // distinct job-name list backed by "SELECT DISTINCT jobName FROM BatchData" is non-empty.
        List<String> names = given().auth().oauth2(accessToken("admin"))
                .when().get("/rest/batch/job-names")
                .then().statusCode(200)
                .extract().jsonPath().getList(".", String.class);

        assertFalse(names.isEmpty(), "the seeded BatchData rows must yield at least one job name");
        // Every entry must be a real, non-blank job name (the column is @NotNull).
        names.forEach(n -> assertTrue(n != null && !n.isBlank(), "job names must be non-blank: " + names));
    }

    // ------------------------------------------------------------------------------------------- //
    // /{jobName}/instances — authorized read returns a paged envelope
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getJobInstancesForNeverRunJobReturnsEmptyPage() {
        // BatchService.getJobInstances(jobName, ...) calls jobOperator.getJobInstanceCount(jobName),
        // which throws JBeret's NoSuchJobException for any job name that has never executed in the current
        // JVM. The service now guards that call with try { ... } catch (NoSuchJobException ignored) {}
        // (mirroring getStatus()), so a valid-but-never-run job name yields an empty 0-total paged envelope
        // (HTTP 200) rather than a 5xx.
        //
        // We probe with a job name that is guaranteed never to have executed in this JVM (a unique,
        // non-existent name), so the assertion is deterministic regardless of suite ordering.
        String neverRunJob = "IT-never-run-" + System.nanoTime();

        given().auth().oauth2(accessToken())
                .when().get("/rest/batch/" + neverRunJob + "/instances")
                .then().statusCode(200)
                .body("total", equalTo(0))
                .body("size", equalTo(0))
                .body("data.size()", equalTo(0));
    }

    // ------------------------------------------------------------------------------------------- //
    // /instance/{id}/logs — authorized read of an unknown instance yields an empty list
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getLogFilesForUnknownInstanceWithSysadminTokenReturns404() {
        // BatchService.getBatchJobLogFiles(instanceId) calls findByInstanceId(instanceId) and, when that
        // returns null (an unknown instance), now throws NiordWebException("Invalid batch
        // instance ID ..."), which Spring renders as an RFC 9457 ProblemDetail with HTTP 404 (instead of the former 500).
        given().auth().oauth2(accessToken())
                .when().get("/rest/batch/instance/" + UNKNOWN_INSTANCE_ID + "/logs")
                .then().statusCode(404);
    }

    // ------------------------------------------------------------------------------------------- //
    // /instance/{id}/thumbnail.png — @PermitAll redirect to the generic file-type icon
    // ------------------------------------------------------------------------------------------- //

    @Test
    void thumbnailForUnknownInstanceRedirectsToGenericIcon() {
        // No data file exists for the unknown instance, so FileTypes resolves the generic icon and the
        // endpoint issues a temporary (307) redirect to it. RestAssured must not auto-follow the redirect.
        Response resp = given().auth().oauth2(accessToken("admin"))
                .redirects().follow(false)
                .when().get("/rest/batch/instance/" + UNKNOWN_INSTANCE_ID + "/thumbnail.png")
                .then().statusCode(307)
                .extract().response();

        String location = resp.getHeader("Location");
        assertTrue(location != null && !location.isBlank(), "a redirect must carry a Location header");
        // The endpoint prepends ".." to FileTypes.getIcon(...), which for a missing file resolves to the
        // generic file-types icon under /img/filetypes (a .png).
        assertTrue(location.contains("/img/filetypes/"),
                "Location must point at the file-types icon folder: " + location);
        assertTrue(location.endsWith(".png"), "Location must point at a PNG icon: " + location);
    }

    @Test
    void thumbnailIsReadableAnonymously() {
        // The endpoint is @PermitAll, so an anonymous caller also gets the 307 redirect (no auth needed).
        // The endpoint builds the redirect from ".." + FileTypes.getIcon(...), but JAX-RS resolves that
        // relative URI against the request base URI, so the emitted Location is an ABSOLUTE url like
        // "http://localhost:PORT/img/filetypes/generic-32_32.png" — not a "../"-relative path. Assert that
        // it resolves to the generic file-types PNG icon rather than asserting a literal "../" prefix.
        Response resp = given()
                .redirects().follow(false)
                .when().get("/rest/batch/instance/" + UNKNOWN_INSTANCE_ID + "/thumbnail.png")
                .then().statusCode(307)
                .extract().response();

        String location = resp.getHeader("Location");
        assertTrue(location != null && !location.isBlank(),
                "an anonymous caller must still receive a redirect Location header");
        assertTrue(location.endsWith("/img/filetypes/generic-32_32.png"),
                "Location must resolve to the generic file-types icon: " + location);
    }

    // ------------------------------------------------------------------------------------------- //
    // /status — authorized read reports the batch types
    // ------------------------------------------------------------------------------------------- //

    @Test
    void statusWithAdminTokenReportsNonEmptyTypes() {
        // With an admin token the real status is computed: one BatchTypeVo per distinct seeded job name,
        // so the "types" list is non-empty (unlike the empty BatchStatusVo returned to non-admins).
        given().auth().oauth2(accessToken("admin"))
                .when().get("/rest/batch/status")
                .then().statusCode(200)
                .body("runningExecutions", greaterThanOrEqualTo(0))
                .body("types.size()", greaterThanOrEqualTo(1))
                // Each reported type carries the job name it summarises.
                .body("types[0].name", notNullValue());
    }

    // ------------------------------------------------------------------------------------------- //
    // /execute-javascript — a blank script returns "OK" and starts no job
    // ------------------------------------------------------------------------------------------- //

    @Test
    void executeBlankJavaScriptReturnsOk() {
        // A blank script is a no-op in the endpoint: the StringUtils.isNotBlank(javaScript) guard means no
        // batch job is started, yet the method still returns the "OK" status body. We assert exactly that
        // (status 200 + body "OK"). We deliberately do NOT read the script-executor instance count to prove
        // "no job started": that read goes through BatchService.getJobInstances -> getJobInstanceCount,
        // which throws NoSuchJobException for a never-run job and poisons the surrounding transaction (a
        // RollbackException), so it is not a reliable side-effect oracle here. The isNotBlank guard is
        // unit-testable on its own; this IT pins the observable HTTP contract of the blank-script path.
        String body = given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("scriptName", "IT-noop.js", "javaScript", ""))
                .when().post("/rest/batch/execute-javascript")
                .then().statusCode(200)
                .extract().asString();

        assertEquals("OK", body.trim(), "a blank script must still return the 'OK' status body");
    }
}
