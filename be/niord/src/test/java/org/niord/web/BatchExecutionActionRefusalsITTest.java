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

import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchData;
import org.niord.core.batch.BatchService;
import org.niord.test.BatchJobs;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stop, restart and abandon of a batch execution, {@code PUT /rest/batch/execution/{id}/stop|restart|abandon},
 * when the job operator refuses them (BATCH10): an execution that does not exist answers 404, and an action that the
 * execution's state does not allow answers 409 with a sentence that names the action and the state. Before, all of
 * these answered 500. JBeret refuses before it acts, so a refused action changes nothing: a running job still
 * completes. The actions the state allows still work.
 * <p>
 * The executions are {@code script-executor} jobs started through {@link BatchService#startBatchJobWithDataFile},
 * the path {@code POST /rest/batch/execute-javascript} takes (that endpoint answers only "OK", not the execution id).
 * A running execution is a script that waits until the test sets a system property of its own, so the test decides
 * when it ends; it gives up after 30 seconds by itself. Every test removes its job's row and folder.
 * <p>
 * This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchExecutionActionRefusalsITTest extends ITTest {

    private static final long UNKNOWN_EXECUTION = 999_999_999L;

    @Autowired
    BatchService batchService;

    @Autowired
    JobOperator jobOperator;

    @PersistenceContext
    EntityManager em;

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** Starts a script-executor job with the given script and returns its execution */
    private long startScript(String script) throws Exception {
        return batchService.startBatchJobWithDataFile("script-executor",
                new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)), "it-batch10.js", new HashMap<>());
    }

    /** A script that runs until the given system property is set, or 30 seconds have passed */
    private static String aScriptThatWaitsFor(String property) {
        return "var System = java.lang.System;\n"
                + "var deadline = System.currentTimeMillis() + 30000;\n"
                + "while (System.getProperty('" + property + "') == null && System.currentTimeMillis() < deadline) {\n"
                + "    java.lang.Thread.sleep(50);\n"
                + "}\n";
    }

    /** Waits until the execution has started */
    private void awaitStarted(long execution) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
                .until(() -> jobOperator.getJobExecution(execution).getBatchStatus() == BatchStatus.STARTED);
    }

    private BatchStatus status(long execution) {
        return jobOperator.getJobExecution(execution).getBatchStatus();
    }

    /** Sends the action as the system administrator and returns the answer's status code */
    private int put(long execution, String action) {
        return given().auth().oauth2(accessToken())
                .when().put("/rest/batch/execution/" + execution + "/" + action)
                .then().extract().statusCode();
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

    // ------------------------------------------------------------------------------------------- //
    // An execution that does not exist -> 404
    // ------------------------------------------------------------------------------------------- //

    @Test
    void anUnknownExecutionAnswers404ForStopRestartAndAbandon() {
        for (String action : List.of("stop", "restart", "abandon")) {
            given().auth().oauth2(accessToken())
                    .when().put("/rest/batch/execution/" + UNKNOWN_EXECUTION + "/" + action)
                    .then().statusCode(404)
                    .body("detail", containsString("No batch execution " + UNKNOWN_EXECUTION));
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // A completed execution: stop and restart -> 409; abandon is allowed
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aCompletedExecutionCannotBeStoppedOrRestartedButCanBeAbandoned() throws Exception {
        long execution = startScript("1 + 1;");
        try {
            assertEquals(BatchStatus.COMPLETED, BatchJobs.awaitCompletion(jobOperator, execution));

            given().auth().oauth2(accessToken())
                    .when().put("/rest/batch/execution/" + execution + "/stop")
                    .then().statusCode(409)
                    .body("detail", containsString("Batch execution " + execution + " cannot be stopped"))
                    .body("detail", containsString("COMPLETED"));
            given().auth().oauth2(accessToken())
                    .when().put("/rest/batch/execution/" + execution + "/restart")
                    .then().statusCode(409)
                    .body("detail", containsString("Batch execution " + execution + " cannot be restarted"));
            assertEquals(BatchStatus.COMPLETED, status(execution), "the refusals changed nothing");
            assertEquals(1, jobOperator.getJobExecutions(jobOperator.getJobInstance(execution)).size(),
                    "no execution was started");

            // The action the state allows still works
            assertEquals(204, put(execution, "abandon"));
            assertEquals(BatchStatus.ABANDONED, status(execution));
        } finally {
            removeTheJob(execution);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // A running execution: restart and abandon -> 409, and the job still completes; stop is allowed
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aRunningExecutionCannotBeRestartedOrAbandonedAndStillCompletes() throws Exception {
        String release = "it.batch10.release." + System.nanoTime();
        long execution = startScript(aScriptThatWaitsFor(release));
        try {
            awaitStarted(execution);

            given().auth().oauth2(accessToken())
                    .when().put("/rest/batch/execution/" + execution + "/restart")
                    .then().statusCode(409)
                    .body("detail", containsString("Batch execution " + execution + " cannot be restarted"));
            given().auth().oauth2(accessToken())
                    .when().put("/rest/batch/execution/" + execution + "/abandon")
                    .then().statusCode(409)
                    .body("detail", containsString("Batch execution " + execution + " cannot be abandoned"))
                    .body("detail", containsString("STARTED"));
            assertEquals(BatchStatus.STARTED, status(execution), "the refusals changed nothing");

            System.setProperty(release, "true");
            assertEquals(BatchStatus.COMPLETED, BatchJobs.awaitCompletion(jobOperator, execution),
                    "the job still completes");
            assertEquals(1, jobOperator.getJobExecutions(jobOperator.getJobInstance(execution)).size(),
                    "no execution was started");
        } finally {
            System.setProperty(release, "true");
            BatchJobs.awaitCompletion(jobOperator, execution);
            System.clearProperty(release);
            removeTheJob(execution);
        }
    }

    @Test
    void aRunningExecutionCanBeStopped() throws Exception {
        String release = "it.batch10.release." + System.nanoTime();
        long execution = startScript(aScriptThatWaitsFor(release));
        try {
            awaitStarted(execution);

            assertEquals(204, put(execution, "stop"));

            System.setProperty(release, "true");
            BatchStatus end = BatchJobs.awaitCompletion(jobOperator, execution);
            assertTrue(end == BatchStatus.STOPPED || end == BatchStatus.COMPLETED,
                    "the stopped job ends without failing; was " + end);
        } finally {
            System.setProperty(release, "true");
            BatchJobs.awaitCompletion(jobOperator, execution);
            System.clearProperty(release);
            removeTheJob(execution);
        }
    }
}
