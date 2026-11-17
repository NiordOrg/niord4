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
package org.niord.web.sweep;

import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchStatus;
import org.niord.test.BatchJobs;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The batch jobs an upload starts. An upload answers as soon as its job is started (with a text, not the job's id),
 * so the sweep finds the job by its name and the uploaded file's name (each upload of the sweep names its file
 * {@code sweep-<group>-<unique>.json}), through the batch interface ({@code GET /rest/batch/{jobName}/instances}),
 * awaits the execution with {@link BatchJobs#awaitCompletion}, and reads its log files through
 * {@code GET /rest/batch/instance/{id}/logs}.
 */
public final class SweepJobs {

    /** How a job ended: its status, the text of its log files, its instance and execution */
    public record JobEnd(String status, String log, long instanceId, long executionId) {
    }

    private final JobOperator jobOperator;
    private long lastUploadMillis;

    SweepJobs(JobOperator jobOperator) {
        this.jobOperator = jobOperator;
    }

    /**
     * Uploads the content as a file part named {@code file} to the upload handler, as the identity, and checks the
     * answer: 200, a text that names the started job, and no "Error executing batch job".
     *
     * @return the file name, to await the job with
     */
    public String upload(SweepContext ctx, String identity, SweepRequest request, String jsonContent) {
        String fileName = ctx.name(ctx.unique()) + ".json";
        lastUploadMillis = System.currentTimeMillis();
        SweepResponse answer = ctx.send(identity, request.file("file", fileName,
                jsonContent.getBytes(StandardCharsets.UTF_8), "application/json"));
        ctx.expectOk(answer);
        ctx.check(answer.text().contains("Started batch job"), "the answer names the started batch job", answer);
        ctx.check(!answer.text().contains("Error executing batch job"),
                "the answer holds no \"Error executing batch job\"", answer);
        return fileName;
    }

    /**
     * Finds the job the upload of the file started, awaits it, and reads its log; checks it ended COMPLETED with no
     * SEVERE line in its log, and holds the server log of its run to the rule of the current entry.
     */
    public JobEnd awaitClean(SweepContext ctx, String jobName, String fileName) throws InterruptedException {
        long start = lastUploadMillis > 0 ? lastUploadMillis : System.currentTimeMillis();
        JobEnd end = await(ctx, jobName, fileName);
        ctx.backgroundWindow(start);
        if (end == null) {
            return null;
        }
        if (!"COMPLETED".equals(end.status())) {
            ctx.fail("The " + jobName + " job of " + fileName + " (instance " + end.instanceId() + ") ended "
                    + end.status() + ", not COMPLETED. Its log:\n" + end.log() + "\n" + SweepFailure.ADVICE);
        }
        List<String> severe = end.log().lines().filter(l -> l.startsWith("SEVERE")).toList();
        if (!severe.isEmpty()) {
            ctx.fail("The log of the " + jobName + " job of " + fileName + " (instance " + end.instanceId()
                    + ") holds SEVERE lines:\n    " + String.join("\n    ", severe) + "\n" + SweepFailure.ADVICE);
        }
        return end;
    }

    /** Finds the job instance of the uploaded file and awaits its execution; null (with a failure) if none appears */
    public JobEnd await(SweepContext ctx, String jobName, String fileName) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            SweepResponse page = ctx.ok("sysadmin", SweepRequest.get("/rest/batch/{jobName}/instances")
                    .var("jobName", jobName).query("page", 0).query("pageSize", 50));
            List<Map<String, Object>> instances = page.json().getList("data");
            for (Map<String, Object> instance : instances == null ? List.<Map<String, Object>>of() : instances) {
                if (!fileName.equals(instance.get("fileName"))) {
                    continue;
                }
                long instanceId = ((Number) instance.get("instanceId")).longValue();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> executions = (List<Map<String, Object>>) instance.get("executions");
                if (executions == null || executions.isEmpty()) {
                    break;
                }
                long executionId = executions.stream()
                        .mapToLong(e -> ((Number) e.get("executionId")).longValue()).max().orElseThrow();
                BatchStatus status = BatchJobs.awaitCompletion(jobOperator, executionId, 90_000L);
                return new JobEnd(status.name(), log(ctx, instanceId), instanceId, executionId);
            }
            Thread.sleep(200);
        }
        ctx.fail("No " + jobName + " job of the uploaded file " + fileName + " appeared within 30 s");
        return null;
    }

    /** The job's log files, one after the other, read through the batch interface */
    public String log(SweepContext ctx, long instanceId) {
        StringBuilder log = new StringBuilder();
        SweepResponse names = ctx.ok("sysadmin", SweepRequest.get("/rest/batch/instance/{instanceId}/logs")
                .var("instanceId", instanceId));
        for (String name : names.json().getList("$", String.class)) {
            log.append(ctx.ok("sysadmin", SweepRequest.get("/rest/batch/instance/{instanceId}/logs/{logFileName}")
                    .var("instanceId", instanceId).var("logFileName", name)).text()).append('\n');
        }
        return log.toString();
    }
}
