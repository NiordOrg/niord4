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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;
import org.niord.test.Tx;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Third and final layer of integration tests for {@link org.niord.web.BatchRestService}, complementing
 * {@link org.niord.web.BatchRestServiceITTest} (the no-token {@code 401}/{@code 403}/{@code 404} gate) and
 * {@link org.niord.web.BatchRestGapITTest} (the authorized read happy paths and the documented 5xx bugs).
 *
 * <p>The gap this class closes is the <em>authenticated-but-wrong-role</em> middle of the batch
 * authorization matrix. The two existing classes only ever assert the two easy corners — no token
 * ({@code 401}) and a fully privileged {@code sysadmin}/{@code admin} token ({@code 200}/{@code 404}). The
 * security-critical case they never exercise is an authenticated caller whose role is one tier too low:
 * such a caller must be rejected with {@code 403}, not silently allowed. These are deterministic decisions
 * made by the security layer (or, for {@code /download}, by an explicit programmatic admin check) before any
 * JBeret job runtime is touched, so they are safe and repeatable. The stop, restart and abandon of real
 * executions are driven by {@code BatchExecutionActionRefusalsITTest}.
 *
 * <p>What is asserted here, end to end against the real Keycloak tokens (see {@link KeycloakTestTokens}):
 * <ul>
 *   <li>The instance and execution endpoints — {@code /{jobName}/instances}, {@code /instance/{id}/logs},
 *       {@code /instance/{id}/logs/{name}}, {@code /execution/{id}/stop|restart|abandon} — are
 *       {@code @RolesAllowed(sysadmin)}, and so is the programmatic check of the data-file download. An
 *       authenticated {@code admin} is rejected with {@code 403}, with or without a {@code NiordDomain}
 *       header: instances and executions are addressed by id and are not scoped to a domain, while the
 *       ADMIN role is granted per domain. (They were ADMIN; the admin application only ever reached them
 *       from its sysadmin area.) An {@code editor} is rejected likewise.</li>
 *   <li>{@code @RolesAllowed(sysadmin)} writes — {@code /execute-javascript} and {@code /execute-batch-set} —
 *       reject an authenticated {@code admin} with {@code 403}. This pins the boundary the source explicitly
 *       calls out ("Important to only let sysadmin access this operation"): an admin must not be able to
 *       schedule arbitrary server-side JavaScript or import an arbitrary batch-set archive.</li>
 *   <li>{@code /instance/{id}/download/...} is {@code @PermitAll} but its body runs a programmatic sysadmin
 *       check ({@code throw new NiordWebException(403)}). An authenticated {@code editor} or {@code admin} is
 *       rejected with {@code 403} — proving the check rejects an authenticated non-sysadmin, a code path
 *       distinct from both the anonymous {@code 403} and the sysadmin {@code 404} already covered.</li>
 *   <li>{@code /status} is {@code @PermitAll} but returns an <em>empty</em> {@code BatchStatusVo} to any
 *       non-admin caller. The existing suite covers only an anonymous caller; here an <em>authenticated</em>
 *       {@code editor} (role-less for {@code admin}) also gets {@code runningExecutions == 0} and an empty
 *       {@code types} list — the {@code !isCallerInRole(admin)} branch for an authenticated identity.</li>
 *   <li>{@code /execute-batch-set} as a sysadmin refuses a batch set that names a job outside the allow-list
 *       ({@code BatchSetService.BATCH_SET_JOBS}), here the dictionary import, whose own page shows a preview: the
 *       answer names the refusal, nothing is scheduled, and no {@code dictionary-import} job starts.</li>
 * </ul>
 *
 * <p>Test independence: every test reaches a {@code 403}/{@code 200} decision before any handler logic runs
 * (the {@code 403}s short-circuit in the security layer or the programmatic guard; {@code /status} for a
 * non-admin returns a fresh empty VO without touching the job runtime; the allow-list case is refused before anything
 * is scheduled and compares the {@code dictionary-import} job count before and after), so no test mutates state,
 * depends on JBeret's per-JVM instance state, or relies on suite ordering. Each passes alone and in the full suite.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchRestActionITTest extends ITTest {

    @PersistenceContext
    EntityManager em;

    private static String cachedEditor;
    private static String cachedAdmin;

    /** Lazily obtains (and caches) an editor (testeditor) bearer token: editor, but not admin/sysadmin. */
    private static String editorToken() {
        if (cachedEditor == null) {
            cachedEditor = KeycloakTestTokens.editorAccessToken();
        }
        return cachedEditor;
    }

    /** Lazily obtains (and caches) an admin (testadmin) bearer token: admin/editor, but not sysadmin. */
    private static String adminToken() {
        if (cachedAdmin == null) {
            cachedAdmin = KeycloakTestTokens.adminAccessToken();
        }
        return cachedAdmin;
    }

    // ------------------------------------------------------------------------------------------- //
    // @RolesAllowed(sysadmin) reads — an authenticated editor is two tiers too low -> 403 (not 401, not 200)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getJobInstancesAsEditorIsForbidden() {
        // /{jobName}/instances is @RolesAllowed(sysadmin). An authenticated editor (role chain = {editor})
        // lacks it, so the security layer rejects it with 403 before BatchService is ever invoked —
        // i.e. before the never-run-job NoSuchJobException bug could surface. The 403 (not the documented
        // 5xx) confirms authorization is evaluated ahead of the handler.
        given().auth().oauth2(editorToken())
                .when().get("/rest/batch/IT-BATCH-no-such-job/instances")
                .then().statusCode(403);
    }

    @Test
    void getLogFilesAsEditorIsForbidden() {
        // /instance/{id}/logs is @RolesAllowed(sysadmin); an authenticated editor is rejected with 403.
        given().auth().oauth2(editorToken())
                .when().get("/rest/batch/instance/1/logs")
                .then().statusCode(403);
    }

    @Test
    void getLogFileContentAsEditorIsForbidden() {
        // /instance/{id}/logs/{logFileName} is @RolesAllowed(sysadmin); an authenticated editor -> 403.
        given().auth().oauth2(editorToken())
                .when().get("/rest/batch/instance/1/logs/SomeLog.txt")
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // Instance and execution endpoints are sysadmin only — an authenticated admin -> 403
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getJobInstancesAsAdminIsForbidden() {
        given().auth().oauth2(adminToken())
                .when().get("/rest/batch/IT-BATCH-no-such-job/instances")
                .then().statusCode(403);
    }

    @Test
    void getJobInstancesAsDomainAdminIsForbidden() {
        // The NiordDomain header adds testadmin's roles on niord-nw - admin, not sysadmin.
        given().auth().oauth2(adminToken())
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/batch/IT-BATCH-no-such-job/instances")
                .then().statusCode(403);
    }

    @Test
    void getLogFilesAsAdminIsForbidden() {
        given().auth().oauth2(adminToken())
                .when().get("/rest/batch/instance/1/logs")
                .then().statusCode(403);
    }

    @Test
    void getLogFileContentAsAdminIsForbidden() {
        given().auth().oauth2(adminToken())
                .when().get("/rest/batch/instance/1/logs/SomeLog.txt")
                .then().statusCode(403);
    }

    @Test
    void stopExecutionAsAdminIsForbidden() {
        given().auth().oauth2(adminToken())
                .when().put("/rest/batch/execution/1/stop")
                .then().statusCode(403);
    }

    @Test
    void restartExecutionAsAdminIsForbidden() {
        given().auth().oauth2(adminToken())
                .when().put("/rest/batch/execution/1/restart")
                .then().statusCode(403);
    }

    @Test
    void abandonExecutionAsAdminIsForbidden() {
        given().auth().oauth2(adminToken())
                .when().put("/rest/batch/execution/1/abandon")
                .then().statusCode(403);
    }

    @Test
    void downloadAsAdminIsForbiddenByProgrammaticSysadminCheck() {
        // @PermitAll, so the admin reaches the body, whose sysadmin check rejects it with 403 before any
        // data file is looked up (a sysadmin asking for an unknown instance gets 404 instead).
        given().auth().oauth2(adminToken())
                .header("NiordDomain", "niord-nw")
                .when().get("/rest/batch/instance/999999999/download/data.json")
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // @RolesAllowed(sysadmin) writes — an authenticated admin is one tier too low -> 403
    // The source notes "Important to only let sysadmin access this operation": an admin must NOT be able
    // to schedule arbitrary server-side JavaScript or import an arbitrary batch-set archive.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void executeJavaScriptAsAdminIsForbidden() {
        // /execute-javascript is @RolesAllowed(sysadmin). An authenticated admin (role chain =
        // {admin, editor}) lacks 'sysadmin', so the request is rejected with 403 before any batch job could
        // be scheduled — the key privilege boundary for arbitrary server-side script execution.
        given().auth().oauth2(adminToken())
                .contentType(ContentType.JSON)
                .body(Map.of("scriptName", "x.js", "javaScript", "1 + 1;"))
                .when().post("/rest/batch/execute-javascript")
                .then().statusCode(403);
    }

    @Test
    void executeBatchSetAsAdminIsForbidden() {
        // /execute-batch-set is @RolesAllowed(sysadmin). A multipart part is supplied so the request reaches
        // the security layer (an empty multipart body would be rejected with 415 first). An authenticated
        // admin lacks 'sysadmin' -> 403, so no archive is extracted or executed.
        given().auth().oauth2(adminToken())
                .multiPart("archive", "batch-set.zip", new byte[]{1, 2, 3})
                .when().post("/rest/batch/execute-batch-set")
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // /execute-batch-set as a sysadmin — a set naming a job outside the allow-list is refused whole
    // The dictionary import runs from its own page, with the preview; inside a batch set it would bypass it.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void executeBatchSetNamingTheDictionaryImportIsRefusedAndStartsNoJob() throws Exception {
        long before = dictionaryImportJobs();
        String manifest = "[{\"jobName\": \"domain-import\", \"fileName\": \"domains.json\", \"delay\": 100},"
                + " {\"jobName\": \"dictionary-import\", \"fileName\": \"dict.json\", \"delay\": 100}]";
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(zip)) {
            for (Map.Entry<String, String> entry : Map.of("batch-set.json", manifest, "domains.json", "[]",
                    "dict.json", "[]").entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }

        String answer = given().auth().oauth2(KeycloakTestTokens.sysadminAccessToken())
                .multiPart("archive", "batch-set.zip", zip.toByteArray(), "application/zip")
                .when().post("/rest/batch/execute-batch-set")
                .then().statusCode(200)
                .extract().asString();

        assertTrue(answer.contains("'dictionary-import'") && answer.contains("nothing was scheduled"), answer);
        assertFalse(answer.contains("Scheduling batch job"), answer);
        // Held for well past the manifest's delay of 100 ms, when a scheduled item would have started its job
        await().during(Duration.ofMillis(700)).atMost(Duration.ofSeconds(5))
                .until(() -> dictionaryImportJobs() == before);
        assertEquals(before, dictionaryImportJobs(), "no dictionary-import job started");
    }

    /** The number of dictionary-import jobs ever started */
    private long dictionaryImportJobs() {
        return Tx.requiringNew().call(() -> em.createQuery(
                "select count(b) from BatchData b where b.jobName = 'dictionary-import'", Long.class).getSingleResult());
    }

    // ------------------------------------------------------------------------------------------- //
    // /instance/{id}/download/... — @PermitAll guarded by a PROGRAMMATIC sysadmin check
    // ------------------------------------------------------------------------------------------- //

    @Test
    void downloadAsEditorIsForbiddenByProgrammaticSysadminCheck() {
        // The endpoint is @PermitAll, so the security layer admits the authenticated editor; the body then
        // runs `if (!isCallerInRole(sysadmin)) throw new NiordWebException(403)`. An editor is not a
        // sysadmin, so the programmatic check rejects it with 403 — a code path distinct from the anonymous
        // 403 (BatchRestServiceITTest) and the sysadmin 404 (the check passes, then no data file exists).
        given().auth().oauth2(editorToken())
                .when().get("/rest/batch/instance/1/download/data.json")
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // /status — @PermitAll, but returns an empty BatchStatusVo to a non-admin (here an AUTHENTICATED one)
    // ------------------------------------------------------------------------------------------- //

    @Test
    void statusAsAuthenticatedEditorReturnsEmptyEnvelope() {
        // /status is @PermitAll; its body returns a fresh empty BatchStatusVo whenever the caller is not an
        // admin, so the 10-second front-end poll does not require admin auth. The existing suite asserts this
        // only for an anonymous caller; this pins the same empty-envelope contract for an AUTHENTICATED
        // non-admin (editor) — exercising the !isCallerInRole(admin) branch for a real identity, not just for
        // the unauthenticated case. The empty result also means no JBeret runtime call is made.
        given().auth().oauth2(editorToken())
                .when().get("/rest/batch/status")
                .then().statusCode(200)
                .body("runningExecutions", is(0))
                .body("types.size()", is(0));
    }
}
