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

import io.restassured.http.ContentType;
import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchStatus;
import jakarta.batch.runtime.JobInstance;
import io.restassured.response.Response;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchData;
import org.niord.core.batch.BatchService;
import org.niord.core.repo.RepoCache;
import org.niord.core.bootstrap.BootstrapRun;
import org.niord.test.BatchJobs;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration tests for {@link org.niord.web.BatchRestService}: the HTTP contract and authorization matrix
 * of the {@code /rest/batch} endpoints, exercised against a real Keycloak token (see {@link KeycloakTestTokens}).
 *
 * <p>Authorization summary under test:
 * <ul>
 *   <li>{@code /job-names} and {@code /status} are {@code @PermitAll}; {@code /status} additionally returns an
 *       <em>empty</em> result for non-admin callers (so the 10-second polling front-end does not flood the log
 *       with auth failures).</li>
 *   <li>{@code /execution/{id}/stop|restart|abandon}, {@code /{jobName}/instances} and the {@code /logs}
 *       endpoints are {@code @RolesAllowed(sysadmin)} → 401 without a token (the admin 403s are in
 *       {@code BatchRestActionITTest}).</li>
 *   <li>{@code /instance/{id}/download/...} is {@code @PermitAll} but performs a programmatic sysadmin
 *       check → 403 for an anonymous caller, 404 for a sysadmin asking for an unknown instance, and for a
 *       known one the data file as an {@code application/octet-stream} attachment.</li>
 *   <li>{@code /execute-batch-set} and {@code /execute-javascript} are {@code @RolesAllowed(sysadmin)} →
 *       401 without a token.</li>
 * </ul>
 *
 * <p>The restart of a failed execution is driven here for two cases: a bootstrap job's, which carries the bootstrap
 * runner's token ({@link BootstrapRun#PROPERTY}), is refused with 409 and starts no execution, since a bootstrap runs
 * once (docs/code-data-config-plan.md §7.2, D4 as amended on 2026-10-24); a sysadmin import's is accepted, as before.
 * The other action endpoints' happy paths are driven elsewhere: stop, restart and abandon of real executions, and
 * the refusals by the execution's state, by {@code BatchExecutionActionRefusalsITTest}; the instances listing and a
 * blank javascript by {@code BatchRestGapITTest}. Only their authorization boundaries are asserted here.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchRestServiceITTest extends ITTest {

    @Autowired
    BatchService batchService;

    @Autowired
    RepoCache repoCache;

    @Autowired
    JobOperator jobOperator;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // @PermitAll reads
    // ------------------------------------------------------------------------------------------- //

    @Test
    void jobNamesIsReadableAnonymously() {
        given()
                .when().get("/rest/batch/job-names")
                .then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(0));
    }

    @Test
    void statusIsReadableAnonymouslyButReturnsEmptyForNonAdmin() {
        // @PermitAll, but a non-admin caller gets an empty BatchStatusVo (no running executions, no types)
        // so the frequent polling does not require admin auth.
        given()
                .when().get("/rest/batch/status")
                .then().statusCode(200)
                .body("runningExecutions", is(0))
                .body("types.size()", is(0));
    }

    @Test
    void statusWithAdminTokenIsAuthorized() {
        // With an admin token the real status is computed (its content depends on the JBeret runtime, so we
        // assert only that the request is authorized and well-formed).
        given().auth().oauth2(accessToken())
                .when().get("/rest/batch/status")
                .then().statusCode(200)
                .body("runningExecutions", greaterThanOrEqualTo(0));
    }

    // ------------------------------------------------------------------------------------------- //
    // @RolesAllowed(sysadmin) — unauthenticated requests are rejected
    // ------------------------------------------------------------------------------------------- //

    @Test
    void stopExecutionWithoutTokenIsUnauthorized() {
        given()
                .when().put("/rest/batch/execution/1/stop")
                .then().statusCode(401);
    }

    @Test
    void restartExecutionWithoutTokenIsUnauthorized() {
        given()
                .when().put("/rest/batch/execution/1/restart")
                .then().statusCode(401);
    }

    @Test
    void abandonExecutionWithoutTokenIsUnauthorized() {
        given()
                .when().put("/rest/batch/execution/1/abandon")
                .then().statusCode(401);
    }

    @Test
    void getJobInstancesWithoutTokenIsUnauthorized() {
        given()
                .when().get("/rest/batch/IT-BATCH-no-such-job/instances")
                .then().statusCode(401);
    }

    @Test
    void getLogFilesWithoutTokenIsUnauthorized() {
        given()
                .when().get("/rest/batch/instance/1/logs")
                .then().statusCode(401);
    }

    @Test
    void getLogFileContentWithoutTokenIsUnauthorized() {
        given()
                .when().get("/rest/batch/instance/1/logs/SomeLog.txt")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // restart — a bootstrap job's is refused, a sysadmin import's accepted
    // ------------------------------------------------------------------------------------------- //

    /** Starts a domain import of a file that is not JSON, waits for it to fail, and returns its execution */
    private long aFailedDomainImport(Map<String, Object> properties) throws Exception {
        byte[] notJson = Files.readAllBytes(Path.of("src/test/resources/bootstrap-fixtures/failing/domains.json"));
        long execution = batchService.startBatchJobWithDataFile("domain-import",
                new ByteArrayInputStream(notJson), "domains.json", new HashMap<>(properties));
        assertEquals(BatchStatus.FAILED, BatchJobs.awaitCompletion(jobOperator, execution), "the import fails");
        return execution;
    }

    /** Removes the job's row and folder, which the seeded database shares with the other tests */
    private void removeTheJob(long execution) {
        BatchData job = batchService.findByInstanceId(jobOperator.getJobInstance(execution).getInstanceId());
        if (job == null) {
            return;
        }
        FileUtils.deleteQuietly(batchService.computeBatchJobPath(job.computeBatchJobFolderPath()).toFile());
        Tx.requiringNew().run(() -> {
            BatchData row = em.find(BatchData.class, job.getId());
            if (row != null) {
                em.remove(row);
            }
        });
    }

    @Test
    void aBootstrapJobsRestartIsRefused() throws Exception {
        long execution = aFailedDomainImport(Map.of(BootstrapRun.PROPERTY, "it-run/0"));
        try {
            JobInstance instance = jobOperator.getJobInstance(execution);
            int executions = jobOperator.getJobExecutions(instance).size();

            given().auth().oauth2(accessToken())
                    .when().put("/rest/batch/execution/" + execution + "/restart")
                    .then().statusCode(409)
                    .body("detail", containsString("A bootstrap job is never restarted"));

            assertEquals(executions, jobOperator.getJobExecutions(instance).size(), "no execution was started");
        } finally {
            removeTheJob(execution);
        }
    }

    @Test
    void aSysadminImportsRestartIsAccepted() throws Exception {
        long execution = aFailedDomainImport(Map.of());
        try {
            long restarted = Long.parseLong(given().auth().oauth2(accessToken())
                    .when().put("/rest/batch/execution/" + execution + "/restart")
                    .then().statusCode(200)
                    .extract().asString().trim());

            assertNotEquals(execution, restarted, "a new execution of the same instance");
            assertEquals(jobOperator.getJobInstance(execution).getInstanceId(),
                    jobOperator.getJobInstance(restarted).getInstanceId());
            // The data file still fails: await the end, so that nothing runs past the test
            assertEquals(BatchStatus.FAILED, BatchJobs.awaitCompletion(jobOperator, restarted));
        } finally {
            removeTheJob(execution);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // download — @PermitAll with a programmatic sysadmin check
    // ------------------------------------------------------------------------------------------- //

    @Test
    void downloadAnonymouslyIsForbiddenByProgrammaticSysadminCheck() {
        // The endpoint is @PermitAll, but its body rejects non-sysadmin callers with a 403.
        given()
                .when().get("/rest/batch/instance/1/download/data.json")
                .then().statusCode(403);
    }

    @Test
    void downloadAsSysadminForUnknownInstanceReturnsNotFound() {
        // The sysadmin check passes; there is no data file for the unknown instance, so it is a 404, in plain
        // text that does not repeat the file name from the address.
        given().auth().oauth2(accessToken())
                .when().get("/rest/batch/instance/999999999/download/data.json")
                .then().statusCode(404)
                .contentType(containsString("text/plain"))
                .body(not(containsString("data.json")));
    }

    @Test
    void downloadAsSysadminServesTheDataFileAsAnOpaqueAttachment() throws Exception {
        // Whatever an administrator uploaded, markup included, is downloaded rather than rendered on this
        // origin: application/octet-stream, an attachment named after the stored file, nosniff.
        long instanceId = 300_000_000L + (System.nanoTime() % 1_000_000L);
        String jobName = "IT-BATCH-download-" + System.nanoTime();
        BatchData job = new BatchData();
        job.setJobName(jobName);
        job.setJobNo(1L);
        job.setInstanceId(instanceId);
        job.setDataFileName("x.html");
        Integer id = Tx.requiringNew().call(() -> batchService.saveBatchJob(job).getId());
        Path file = batchService.computeBatchJobPath(job.computeDataFilePath());
        String html = "<html><body><script>document.title = 'it';</script></body></html>";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, html);

            Response resp = given().auth().oauth2(accessToken())
                    .when().get("/rest/batch/instance/" + instanceId + "/download/x.html")
                    .then().statusCode(200)
                    .extract().response();

            assertTrue(resp.getContentType().startsWith("application/octet-stream"),
                    "the data file must not be served with a type taken from its extension; was " + resp.getContentType());
            String disposition = resp.getHeader("Content-Disposition");
            assertTrue(disposition != null && disposition.startsWith("attachment") && disposition.contains("x.html"),
                    "the data file must be an attachment named after the stored file; was " + disposition);
            assertEquals("nosniff", resp.getHeader("X-Content-Type-Options"));
            assertEquals(html, resp.asString(), "the body is the stored file, byte for byte");
        } finally {
            FileUtils.deleteQuietly(batchService.getBatchJobRoot().resolve(jobName).toFile());
            Tx.requiringNew().run(() -> {
                BatchData row = em.find(BatchData.class, id);
                if (row != null) {
                    em.remove(row);
                }
            });
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // @RolesAllowed(sysadmin) — unauthenticated requests are rejected
    // ------------------------------------------------------------------------------------------- //

    @Test
    void executeBatchSetWithoutTokenIsUnauthorized() {
        // A multipart part is supplied so the request reaches the security layer (an empty multipart body
        // would be rejected with 415 before authorization is evaluated).
        given().multiPart("archive", "batch-set.zip", new byte[]{1, 2, 3})
                .when().post("/rest/batch/execute-batch-set")
                .then().statusCode(401);
    }

    @Test
    void executeJavaScriptWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of("scriptName", "x.js", "javaScript", "1 + 1;"))
                .when().post("/rest/batch/execute-javascript")
                .then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //
    // POST /repo-import — @RolesAllowed(sysadmin): the repository import of a folder below the home
    // ------------------------------------------------------------------------------------------- //

    @Test
    void repoImportWithoutTokenIsUnauthorized() {
        given().contentType(ContentType.JSON)
                .body(Map.of("dir", "/tmp"))
                .when().post("/rest/batch/repo-import")
                .then().statusCode(401);
    }

    /** A folder that does not lie below the home is refused: the job reads what it is given into the database */
    @Test
    void repoImportOfAFolderOutsideTheHomeIsRefused() {
        Path outside = repoCache.home().toAbsolutePath().normalize().getParent();
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("dir", outside.toString()))
                .when().post("/rest/batch/repo-import")
                .then().statusCode(400);

        // The home itself is not below the home either, and a missing dir is a 400 too
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("dir", repoCache.home().toString()))
                .when().post("/rest/batch/repo-import")
                .then().statusCode(400);
        given().auth().oauth2(accessToken())
                .contentType(ContentType.JSON)
                .body(Map.of("verify", true))
                .when().post("/rest/batch/repo-import")
                .then().statusCode(400);
    }

    /**
     * An empty folder below the home starts the job, which has nothing to import and verifies the references. The
     * answer names the job it started, so that a script can follow it to its end and read its log, and still holds
     * the status line it answered before.
     */
    @Test
    void repoImportOfAFolderBelowTheHomeStartsTheJobAndAnswersItsIds() throws Exception {
        Path dir = Files.createDirectories(repoCache.home().resolve("it-repo-import-empty-" + System.nanoTime()));
        try {
            Response answer = given().auth().oauth2(accessToken())
                    .contentType(ContentType.JSON)
                    .body(Map.of("dir", dir.toString(), "verify", true))
                    .when().post("/rest/batch/repo-import")
                    .then().statusCode(200)
                    .contentType(ContentType.JSON)
                    .body(containsString("Started batch job 'repo-import' of "))
                    .extract().response();
            assertEquals("repo-import", answer.path("jobName"));
            assertEquals("Started batch job 'repo-import' of " + dir, answer.path("message"));
            long executionId = answer.jsonPath().getLong("executionId");
            long instanceId = answer.jsonPath().getLong("instanceId");

            assertEquals(instanceId, jobOperator.getJobInstance(executionId).getInstanceId(),
                    "the instance id is the started execution's");
            assertEquals("COMPLETED", awaitTheEndOverRest(instanceId, executionId),
                    "a script follows the job to its end through the instances listing");
            given().auth().oauth2(accessToken())
                    .when().get("/rest/batch/instance/" + instanceId + "/logs")
                    .then().statusCode(200)
                    .body("size()", greaterThanOrEqualTo(1));
        } finally {
            Files.deleteIfExists(dir);
        }
    }

    /**
     * Waits for a repo-import job the way a script does, since no endpoint reads one execution by its id: pages
     * {@code GET /rest/batch/repo-import/instances}, finds the row of the instance id the start answered, and reads
     * the batch status of its execution until it is terminal. Answers that status.
     */
    private String awaitTheEndOverRest(long instanceId, long executionId) throws InterruptedException {
        Set<String> terminal = Set.of("COMPLETED", "FAILED", "STOPPED", "ABANDONED");
        long deadline = System.currentTimeMillis() + 90_000L;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = given().auth().oauth2(accessToken())
                    .when().get("/rest/batch/repo-import/instances?page=0&pageSize=200")
                    .then().statusCode(200)
                    .extract().jsonPath().getList("data");
            for (Map<String, Object> row : rows) {
                if (((Number) row.get("instanceId")).longValue() != instanceId) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> executions = (List<Map<String, Object>>) row.get("executions");
                for (Map<String, Object> execution : executions) {
                    String status = String.valueOf(execution.get("batchStatus"));
                    if (((Number) execution.get("executionId")).longValue() == executionId
                            && terminal.contains(status)) {
                        return status;
                    }
                }
            }
            Thread.sleep(250);
        }
        fail("the repo-import job of instance " + instanceId + " did not end within 90 s");
        return null;
    }
}
