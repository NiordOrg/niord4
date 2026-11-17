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
import jakarta.persistence.EntityManager;
import org.apache.commons.io.FileUtils;
import org.niord.core.batch.BatchData;
import org.niord.core.batch.BatchService;
import org.niord.test.BatchJobs;
import org.niord.test.Tx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.niord.web.sweep.SystemSweepFixture.BATCH_SCRIPT;
import static org.niord.web.sweep.SystemSweepFixture.BATCH_SCRIPT_FILE;
import static org.niord.web.sweep.SystemSweepFixture.BATCH_SCRIPT_TEXT;
import static org.niord.web.sweep.SystemSweepFixture.UNKNOWN;
import static org.niord.web.sweep.SystemSweepFixture.map;

/**
 * The cases of {@code BatchRestService}: the job names, instances, status, data file, thumbnail and logs of the
 * fixture's batch instance (a script run through {@code execute-javascript}), and the actions on executions of the
 * case's own: a script that waits until the case releases it (a system property of the run, as
 * {@code BatchExecutionActionRefusalsITTest} does), so the case decides when it ends. Every job a case starts is
 * removed afterwards, its row and folder, as that test does: no endpoint removes a batch instance. The batch set and
 * the repository import act on everything of a kind: global, refused cells only, and a refusal probe.
 */
final class SystemSweepBatch {

    /** A job instance and its (latest) execution */
    record Job(long instance, long execution) {
    }

    private static final String SCRIPT_EXECUTOR = "script-executor";

    private SystemSweepBatch() {
    }

    // ------------------------------------------------------------------------------------------- //
    // Cases
    // ------------------------------------------------------------------------------------------- //

    static void cases(SweepCases cases, SystemSweepFixture fixture) {
        cases.add("getJobNames", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/batch/job-names"))
                .sweep((ctx, as) -> {
                    SweepResponse names = ctx.ok(as, SweepRequest.get("/rest/batch/job-names"));
                    if (names.is2xx()) {
                        ctx.check(names.json().getList("$").contains(SCRIPT_EXECUTOR),
                                "the job names hold " + SCRIPT_EXECUTOR + ", the fixture's job", names);
                    }
                }));

        cases.add("getJobInstances", SweepCase
                .of((ctx, row) -> instances(SCRIPT_EXECUTOR))
                .variants((ctx, as) -> {
                    SweepResponse one = ctx.ok(as, SweepRequest.get("/rest/batch/{jobName}/instances")
                            .var("jobName", SCRIPT_EXECUTOR).query("page", 0).query("pageSize", 1));
                    ctx.checkEquals(1, one.json().getList("data").size(), "the instances on a page of one", one);
                    SweepResponse none = ctx.ok(as, instances("sweep-system-no-such-job"));
                    ctx.checkEquals(0, none.json().getInt("total"), "the total of a job name that never ran", none);
                })
                .probes((ctx, as) -> ctx.call(as.name(), SweepRequest.get("/rest/batch/{jobName}/instances")
                        .var("jobName", SCRIPT_EXECUTOR).query("page", "abc").note("malformed page"),
                        Cell.status(400, "Failed to convert 'page' with value: 'abc'"))));

        cases.add("getStatus", SweepCase
                .of((ctx, row) -> SweepRequest.get("/rest/batch/status"))
                .as("admin")
                .sweep((ctx, as) -> {
                    // An admin sees every job type; anyone else the empty status, never a refusal
                    SweepResponse admin = ctx.ok(as, SweepRequest.get("/rest/batch/status"));
                    if (admin.is2xx()) {
                        ctx.check(admin.json().getList("types.name").contains(SCRIPT_EXECUTOR),
                                "an admin's status names the job type " + SCRIPT_EXECUTOR, admin);
                    }
                    for (String identity : List.of("anonymous", "user", "editor", "adminElsewhere",
                            "userOtherDomain")) {
                        SweepResponse narrowed = ctx.ok(identity, SweepRequest.get("/rest/batch/status"));
                        if (narrowed.is2xx()) {
                            ctx.checkEquals(List.of(), narrowed.json().getList("types"),
                                    "the job types " + identity + " sees", narrowed);
                        }
                    }
                    SweepResponse ticket = ctx.ok("ticket(admin:admin)", SweepRequest.get("/rest/batch/status"));
                    if (ticket.is2xx()) {
                        ctx.check(!ticket.json().getList("types").isEmpty(),
                                "an admin ticket's status names the job types", ticket);
                    }
                }));

        cases.add("downloadBatchDataFile", SweepCase
                .of((ctx, row) -> download(ctx.row(BATCH_SCRIPT), BATCH_SCRIPT_FILE))
                .as("sysadmin")
                .sweep((ctx, as) -> {
                    SweepResponse file = ctx.ok(as, download(ctx.row(BATCH_SCRIPT), BATCH_SCRIPT_FILE));
                    if (file.is2xx()) {
                        ctx.checkEquals(BATCH_SCRIPT_TEXT, file.text(), "the data file's content", file);
                        ctx.checkEquals("nosniff", file.header("X-Content-Type-Options"),
                                "the X-Content-Type-Options header", file);
                    }
                })
                .probes((ctx, as) -> {
                    SweepResponse unknown = ctx.send(as, download(UNKNOWN, "sweep-system-unknown.js")
                            .note("unknown-id probe"));
                    ctx.check(unknown.status() == 404 && unknown.text().equals("Batch data file not found"),
                            "404 \"Batch data file not found\" (plain text) for an unknown instance", unknown);
                }));

        cases.add("getBatchDataFileThumbnail", SweepCase
                .of((ctx, row) -> thumbnail(ctx.row(BATCH_SCRIPT)))
                .sweep((ctx, as) -> {
                    SweepResponse icon = ctx.call(as.name(), thumbnail(ctx.row(BATCH_SCRIPT)), Cell.status(307, null));
                    String location = icon.header("Location");
                    ctx.check(location != null && location.contains("/img/filetypes/") && location.endsWith(".png"),
                            "a redirect to a file-type icon (/img/filetypes/...png)", icon);
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), thumbnail(ctx.row(BATCH_SCRIPT)).query("size", "abc").note("malformed size"),
                            Cell.status(400, "Failed to convert 'size' with value: 'abc'"));
                    SweepResponse unknown = ctx.call(as.name(), thumbnail(UNKNOWN).note("unknown-id probe"),
                            Cell.status(307, null));
                    ctx.check(unknown.header("Location") != null,
                            "an unknown instance redirects to the generic icon", unknown);
                }));

        cases.add("getBatchJobLogFiles", SweepCase
                .of((ctx, row) -> logs(ctx.row(BATCH_SCRIPT)))
                .sweep((ctx, as) -> {
                    SweepResponse names = ctx.ok(as, logs(ctx.row(BATCH_SCRIPT)));
                    if (names.is2xx()) {
                        List<String> list = names.json().getList("$", String.class);
                        ctx.check(!list.isEmpty() && list.stream().allMatch(n -> n.matches(".+Log\\.txt(\\.\\d+)?")),
                                "the fixture's instance has log files, each named ...Log.txt", names);
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), logs(UNKNOWN).note("unknown-id probe"),
                            Cell.status(404, "Invalid batch instance ID " + UNKNOWN));
                    ctx.call(as.name(), logs("abc").note("malformed-id probe"),
                            Cell.status(400, "Failed to convert 'instanceId' with value: 'abc'"));
                }));

        cases.add("getBatchJobLogFileContent", SweepCase
                .of((ctx, row) -> logFile(ctx.row(BATCH_SCRIPT), fixture.firstLogName(ctx)))
                .variants((ctx, as) -> {
                    SweepResponse all = ctx.ok(as, logFile(ctx.row(BATCH_SCRIPT), fixture.firstLogName(ctx)));
                    SweepResponse rest = ctx.ok(as, logFile(ctx.row(BATCH_SCRIPT), fixture.firstLogName(ctx))
                            .query("fromLineNo", 1));
                    ctx.check(rest.text().length() < all.text().length(),
                            "fromLineNo=1 leaves out the first line", rest);
                })
                .probes((ctx, as) -> {
                    // An unknown instance is a 400 here, while the list of the log files answers 404
                    ctx.call(as.name(), logFile(UNKNOWN, fixture.firstLogName(ctx)).note("unknown-id probe"),
                            Cell.status(400, "Invalid batch instance ID " + UNKNOWN));
                    ctx.call(as.name(), logFile(ctx.row(BATCH_SCRIPT), "sweep-systemLog.txt")
                            .note("unknown log file"), Cell.status(400, "Invalid batch log file sweep-systemLog.txt"));
                }));

        cases.add("executeJavaScript", SweepCase
                .of((ctx, row) -> javaScript(ctx.name("refused-" + ctx.unique()) + ".js", "1 + 1;"))
                .sweep((ctx, as) -> {
                    String file = ctx.name("script-" + ctx.unique()) + ".js";
                    String script = "var sweep = '" + file + "';\n2 + 2;\n";
                    long from = System.currentTimeMillis();
                    SweepResponse answer = ctx.ok(as, javaScript(file, script));
                    if (!answer.is2xx()) {
                        return;
                    }
                    ctx.checkEquals("OK", answer.text(), "the answer", answer);
                    Job job = awaitFind(ctx, file);
                    try {
                        ctx.checkEquals(BatchStatus.COMPLETED, awaitEnd(ctx, job.execution()),
                                "the script's job status", answer);
                        ctx.backgroundWindow(from);
                        // Read back: the script is the instance's data file, under the name sent
                        SweepResponse data = ctx.ok("sysadmin", download(job.instance(), file));
                        ctx.checkEquals(script, data.text(), "the script the job ran (its data file)", data);
                        SweepResponse page = ctx.ok("sysadmin", instances(SCRIPT_EXECUTOR));
                        ctx.checkEquals(file, page.json().getString("data.find { it.instanceId == "
                                + job.instance() + " }.fileName"), "the instance's file name (scriptName)", page);
                        String log = ctx.jobs().log(ctx, job.instance());
                        ctx.check(log.lines().noneMatch(l -> l.startsWith("SEVERE")),
                                "the job's log holds no SEVERE line", page);
                    } finally {
                        removeAndReadGone(ctx, job.instance());
                    }
                }));

        cases.add("stopExecution", SweepCase
                .of((ctx, row) -> action(UNKNOWN, "stop"))
                .sweep((ctx, as) -> {
                    String release = "sweep.system.release." + ctx.unique();
                    Job job = runScript(ctx, as.name(), ctx.name("stop-" + ctx.unique()) + ".js", waitingScript(release));
                    try {
                        awaitStarted(ctx, job.execution());
                        ctx.ok(as, action(job.execution(), "stop"));
                        System.setProperty(release, "true");
                        BatchStatus end = awaitEnd(ctx, job.execution());
                        // Read back through the instances: the execution was stopped
                        SweepResponse page = ctx.ok(as, instances(SCRIPT_EXECUTOR));
                        ctx.checkEquals("STOPPED", status(page, job.execution()), "the stopped execution's status"
                                + " (the operator's: " + end + ")", page);
                    } finally {
                        System.setProperty(release, "true");
                        awaitEnd(ctx, job.execution());
                        System.clearProperty(release);
                        removeAndReadGone(ctx, job.instance());
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), action(UNKNOWN, "stop").note("unknown-id probe"),
                            Cell.status(404, "No batch execution " + UNKNOWN));
                    ctx.call(as.name(), action(fixture.batchExecution, "stop").note("a completed execution"),
                            Cell.status(409, "Batch execution " + fixture.batchExecution + " cannot be stopped"));
                    ctx.call(as.name(), action("abc", "stop").note("malformed-id probe"),
                            Cell.status(400, "Failed to convert 'executionId' with value: 'abc'"));
                }));

        cases.add("restartExecution", SweepCase
                .of((ctx, row) -> action(UNKNOWN, "restart"))
                .sweep((ctx, as) -> {
                    String release = "sweep.system.release." + ctx.unique();
                    Job job = runScript(ctx, as.name(), ctx.name("restart-" + ctx.unique()) + ".js",
                            waitingScript(release));
                    Long restarted = null;
                    try {
                        awaitStarted(ctx, job.execution());
                        ctx.ok(as, action(job.execution(), "stop"));
                        System.setProperty(release, "true");
                        awaitEnd(ctx, job.execution());
                        // The stopped execution restarts: the answer is the new execution's id
                        SweepResponse answer = ctx.ok(as, action(job.execution(), "restart"));
                        if (!answer.is2xx()) {
                            return;
                        }
                        restarted = Long.parseLong(answer.text().trim());
                        ctx.checkEquals(BatchStatus.COMPLETED, awaitEnd(ctx, restarted),
                                "the restarted execution's operator status", answer);
                        SweepResponse page = ctx.ok(as, instances(SCRIPT_EXECUTOR));
                        ctx.checkEquals("COMPLETED", status(page, restarted), "the restarted execution's status",
                                page);
                        ctx.checkEquals("STOPPED", status(page, job.execution()),
                                "the first execution's status after the restart", page);
                    } finally {
                        System.setProperty(release, "true");
                        awaitEnd(ctx, job.execution());
                        if (restarted != null) {
                            awaitEnd(ctx, restarted);
                        }
                        System.clearProperty(release);
                        removeAndReadGone(ctx, job.instance());
                    }
                })
                .probes((ctx, as) -> {
                    ctx.call(as.name(), action(UNKNOWN, "restart").note("unknown-id probe"),
                            Cell.status(404, "No batch execution " + UNKNOWN));
                    ctx.call(as.name(), action(fixture.batchExecution, "restart").note("a completed execution"),
                            Cell.status(409, "Batch execution " + fixture.batchExecution + " cannot be restarted"));
                }));

        cases.add("abandonExecution", SweepCase
                .of((ctx, row) -> action(UNKNOWN, "abandon"))
                .sweep((ctx, as) -> {
                    Job job = runScript(ctx, as.name(), ctx.name("abandon-" + ctx.unique()) + ".js", "3 + 3;");
                    try {
                        BatchStatus end = awaitEnd(ctx, job.execution());
                        SweepResponse answer = ctx.ok(as, action(job.execution(), "abandon"));
                        ctx.checkEquals(BatchStatus.COMPLETED, end, "the script's job status before the abandon",
                                answer);
                        SweepResponse page = ctx.ok(as, instances(SCRIPT_EXECUTOR));
                        ctx.checkEquals("ABANDONED", status(page, job.execution()),
                                "the abandoned execution's status", page);
                    } finally {
                        removeAndReadGone(ctx, job.instance());
                    }
                })
                .probes((ctx, as) -> ctx.call(as.name(), action(UNKNOWN, "abandon").note("unknown-id probe"),
                        Cell.status(404, "No batch execution " + UNKNOWN))));

        cases.add("executeBatchSet", SweepCase
                // An empty batch set: had a gate let it through, it would schedule nothing
                .of((ctx, row) -> batchSet(ctx, "[]"))
                .probes((ctx, as) -> {
                    // A batch set naming a job no batch set may run is refused whole; the answer ends with the refusal
                    SweepResponse refused = ctx.send(as, batchSet(ctx,
                            "[{\"jobName\": \"script-executor\", \"fileName\": \"x.js\"}]").note("a forbidden job"));
                    ctx.check(refused.status() == 200 && refused.text().contains("which a batch set may not run"),
                            "200 with the refusal \"which a batch set may not run\"", refused);
                }));

        cases.add("repoImport", SweepCase
                // No folder: had a gate let it through, the handler would refuse it before any job
                .of((ctx, row) -> SweepRequest.post("/rest/batch/repo-import").json(Map.of()))
                .probes((ctx, as) -> {
                    ctx.call(as.name(), SweepRequest.post("/rest/batch/repo-import").json(Map.of()).note("no folder"),
                            Cell.status(400, "The folder to import, dir, is missing"));
                    ctx.call(as.name(), SweepRequest.post("/rest/batch/repo-import")
                                    .json(map("dir", "/sweep-system-outside-the-home")).note("a folder outside the home"),
                            Cell.status(400, "sweep-system-outside-the-home"));
                }));
    }

    // ------------------------------------------------------------------------------------------- //
    // Requests
    // ------------------------------------------------------------------------------------------- //

    static SweepRequest instances(String jobName) {
        return SweepRequest.get("/rest/batch/{jobName}/instances").var("jobName", jobName)
                .query("page", 0).query("pageSize", 50);
    }

    static SweepRequest download(Object instance, String fileName) {
        return SweepRequest.get("/rest/batch/instance/{instanceId}/download/{fileName:.*}")
                .var("instanceId", instance).var("fileName", fileName);
    }

    static SweepRequest thumbnail(Object instance) {
        return SweepRequest.get("/rest/batch/instance/{instanceId}/thumbnail.png").var("instanceId", instance);
    }

    static SweepRequest logs(Object instance) {
        return SweepRequest.get("/rest/batch/instance/{instanceId}/logs").var("instanceId", instance);
    }

    static SweepRequest logFile(Object instance, String name) {
        return SweepRequest.get("/rest/batch/instance/{instanceId}/logs/{logFileName}")
                .var("instanceId", instance).var("logFileName", name);
    }

    static SweepRequest action(Object execution, String action) {
        return SweepRequest.put("/rest/batch/execution/{executionId}/" + action).var("executionId", execution);
    }

    static SweepRequest javaScript(String scriptName, String script) {
        return SweepRequest.post("/rest/batch/execute-javascript").json(map("scriptName", scriptName,
                "javaScript", script));
    }

    /** A batch-set archive holding only its batch-set.json */
    static SweepRequest batchSet(SweepContext ctx, String batchSetJson) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("batch-set.json"));
            zip.write(batchSetJson.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return SweepRequest.post("/rest/batch/execute-batch-set")
                .file("file", ctx.name("batch-set-" + ctx.unique()) + ".zip", bytes.toByteArray(), "application/zip");
    }

    /** A script that runs until the system property is set, or 30 seconds have passed */
    static String waitingScript(String property) {
        return "var System = java.lang.System;\n"
                + "var deadline = System.currentTimeMillis() + 30000;\n"
                + "while (System.getProperty('" + property + "') == null && System.currentTimeMillis() < deadline) {\n"
                + "    java.lang.Thread.sleep(50);\n"
                + "}\n";
    }

    // ------------------------------------------------------------------------------------------- //
    // Jobs
    // ------------------------------------------------------------------------------------------- //

    /** Runs the script through execute-javascript as the identity and finds its job */
    static Job runScript(SweepContext ctx, String identity, String fileName, String script) {
        SweepResponse answer = ctx.ok(identity, javaScript(fileName, script));
        if (!answer.is2xx()) {
            throw new IllegalStateException("execute-javascript answered " + answer.status() + ": " + answer.preview());
        }
        return awaitFind(ctx, fileName);
    }

    /** Finds the script-executor job of the data file name, waiting up to 30 s for it to appear */
    static Job awaitFind(SweepContext ctx, String fileName) {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            Job job = find(ctx, fileName);
            if (job != null) {
                return job;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IllegalStateException("No script-executor instance of " + fileName + " appeared within 30 s");
    }

    /** The script-executor job of the data file name (its latest execution), or null */
    static Job find(SweepContext ctx, String fileName) {
        SweepResponse page = ctx.ok("sysadmin", instances(SCRIPT_EXECUTOR));
        List<Map<String, Object>> data = page.json().getList("data");
        for (Map<String, Object> instance : data == null ? List.<Map<String, Object>>of() : data) {
            if (!fileName.equals(instance.get("fileName"))) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> executions = (List<Map<String, Object>>) instance.get("executions");
            if (executions == null || executions.isEmpty()) {
                return null;
            }
            long execution = executions.stream().mapToLong(e -> ((Number) e.get("executionId")).longValue())
                    .max().orElseThrow();
            return new Job(((Number) instance.get("instanceId")).longValue(), execution);
        }
        return null;
    }

    /** The batch status of the execution in a page of instances, or null */
    static String status(SweepResponse page, long execution) {
        return page.json().getString("data.executions.flatten().find { it.executionId == " + execution
                + " }.batchStatus");
    }

    static BatchStatus awaitEnd(SweepContext ctx, long execution) throws InterruptedException {
        return BatchJobs.awaitCompletion(ctx.bean(JobOperator.class), execution, 60_000L);
    }

    static void awaitStarted(SweepContext ctx, long execution) throws InterruptedException {
        JobOperator operator = ctx.bean(JobOperator.class);
        long deadline = System.currentTimeMillis() + 30_000L;
        while (operator.getJobExecution(execution).getBatchStatus() != BatchStatus.STARTED) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("Execution " + execution + " did not start within 30 s: "
                        + operator.getJobExecution(execution).getBatchStatus());
            }
            Thread.sleep(50);
        }
    }

    /**
     * Removes the job's row and folder (no endpoint removes a batch instance) and reads it gone: its log list then
     * answers 404. True when it is gone.
     */
    static boolean removeAndReadGone(SweepContext ctx, long instance) {
        BatchService batchService = ctx.bean(BatchService.class);
        EntityManager em = SystemSweepFixture.em(ctx);
        BatchData job = batchService.findByInstanceId(instance);
        if (job != null) {
            FileUtils.deleteQuietly(batchService.computeBatchJobPath(job.computeBatchJobFolderPath()).toFile());
            Tx.requiringNew().run(() -> {
                BatchData row = em.find(BatchData.class, job.getId());
                if (row != null) {
                    em.remove(row);
                }
            });
        }
        SweepResponse read = ctx.send("sysadmin", logs(instance).note("read after the job's removal"));
        return SweepEngine.matches(Cell.status(404, "Invalid batch instance ID " + instance), read);
    }
}
