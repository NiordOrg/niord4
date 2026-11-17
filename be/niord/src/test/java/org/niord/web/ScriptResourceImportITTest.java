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

import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchStatus;
import jakarta.batch.runtime.JobExecution;
import jakarta.batch.runtime.JobInstance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.script.ScriptResourceImportService;
import org.niord.test.ITTest;
import org.niord.test.KeycloakTestTokens;
import org.niord.test.ReportFixtures;
import org.niord.test.Tx;
import org.niord.core.util.JsonUtils;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Script resources import as the sysadmin sees it (docs/code-data-config-plan.md §6.4): the preview of what a
 * file would do, which writes nothing; the import, which adds missing paths, overwrites an existing path only when
 * the sysadmin ticks it, and refuses a file the script check refuses, naming its reasons in the job log; and the
 * history, which records the importing user and the source {@code import}, or {@code editor} for the page's own
 * create and update.
 *
 * <p>Every path is under a prefix unique to the test, and the rows are deleted afterwards. The jobs run
 * asynchronously, so each upload carries a unique file name and the test waits for that job's instance to end.
 *
 * <p>This is a framework-coupled test - see docs/framework-coupled-tests.md.
 */
class ScriptResourceImportITTest extends ITTest {

    private static final String NW_DOMAIN = "niord-nw";

    private static final String ALL = "/rest/script-resources/all";
    private static final String SCRIPT_RESOURCE = "/rest/script-resources/script-resource/";
    private static final String PREVIEW = "/rest/script-resources/import-preview";
    private static final String UPLOAD = "/rest/script-resources/upload-script-resources";
    private static final String JOB = "script-resource-import";

    /**
     * A path the missing-path report lists, since the fixture's category 2 names it in a committed row the preview
     * test plants and removes (the fixture itself misses nothing since C4b); only ever previewed here, never imported
     */
    private final String listed = "templates/mailinglist/it-listed-" + System.nanoTime() + ".ftl";

    private static final String JAVA_TYPE = "var r = Java.type('java.lang.Runtime');";
    private static final String EXECUTE = "<#assign ex = \"freemarker.template.utility.Execute\"?new()>${ex(\"id\")}";

    @PersistenceContext
    EntityManager em;

    @Autowired
    JobOperator jobOperator;

    private final String prefix = "templates/it-import/" + System.nanoTime() + "/";

    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    @AfterEach
    void cleanup() {
        for (Map<String, Object> row : all()) {
            if (String.valueOf(row.get("path")).startsWith(prefix)) {
                as(accessToken()).when().delete(SCRIPT_RESOURCE + row.get("id"));
            }
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // The preview
    // ------------------------------------------------------------------------------------------- //

    @Test
    void thePreviewListsWhatTheImportWouldDoAndWritesNothing() {
        ReportFixtures.plantCategoryPath(em, listed);
        try {
            previewListsWhatTheImportWouldDoAndWritesNothing();
        } finally {
            ReportFixtures.removeCategoryPath(em, listed);
        }
    }

    private void previewListsWhatTheImportWouldDoAndWritesNothing() {
        create(prefix + "existing.js", "var a = 1;");
        create(prefix + "unchanged.js", "var u = 1;");
        long resources = count("ScriptResource");
        long history = count("ScriptResourceHistory");

        JsonPath preview = as(accessToken())
                .multiPart("file", "preview.json", file(
                        resource(prefix + "new.js", "var n = 1;"),
                        resource(prefix + "existing.js", "var a = 2;"),
                        resource(prefix + "unchanged.js", "var u = 1;"),
                        resource(prefix + "bad.js", JAVA_TYPE),
                        resource(prefix + "bad.ftl", EXECUTE),
                        resource(listed, "<#-- IT -->")), "application/json")
                .when().post(PREVIEW)
                .then().statusCode(200).contentType(ContentType.JSON)
                .extract().jsonPath();

        assertEquals(List.of(prefix + "new.js", listed), preview.getList("add.path"));
        assertEquals(List.of(false, true), preview.getList("add.listed"), "the path the missing-path report lists");
        assertEquals(List.of("JS", "FM"), preview.getList("add.type"));
        assertEquals("var n = 1;", preview.getString("add[0].content"));
        assertEquals(List.of(prefix + "existing.js"), preview.getList("overwrite.path"));
        assertEquals("var a = 1;", preview.getString("overwrite[0].current"));
        assertEquals("var a = 2;", preview.getString("overwrite[0].incoming"));
        assertEquals(List.of(prefix + "unchanged.js"), preview.getList("unchanged"));
        assertEquals(List.of(prefix + "bad.ftl", prefix + "bad.js"), preview.getList("refused.path"));
        assertTrue(preview.getString("refused[0].reason").contains("freemarker.template.utility.Execute"),
                preview.getString("refused[0].reason"));
        assertTrue(preview.getString("refused[1].reason").contains("Java.type"), preview.getString("refused[1].reason"));

        assertEquals(resources, count("ScriptResource"), "the preview writes no row");
        assertEquals(history, count("ScriptResourceHistory"), "the preview writes no history");
        assertNull(id(listed));
    }

    @Test
    void aMalformedFileIsRefusedWith400() {
        as(accessToken())
                .multiPart("file", "broken.json", "{not json".getBytes(StandardCharsets.UTF_8), "application/json")
                .when().post(PREVIEW)
                .then().statusCode(400).body("detail", containsString("broken.json"));
    }

    @Test
    void thePreviewIsForASysadminOnly() {
        byte[] file = file(resource(prefix + "x.js", "var x;"));
        given().multiPart("file", "x.json", file, "application/json")
                .when().post(PREVIEW).then().statusCode(401);
        as(KeycloakTestTokens.adminAccessToken()).multiPart("file", "x.json", file, "application/json")
                .when().post(PREVIEW).then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // The import
    // ------------------------------------------------------------------------------------------- //

    @Test
    void theImportAddsMissingPathsKeepsExistingRowsAndRefusesWithReasons() {
        Integer existing = create(prefix + "existing.js", "var a = 1;");
        String sysadmin = historyOf(existing).getString("[0].user");
        assertEquals("editor", historyOf(existing).getString("[0].source"), "the page's create");

        String log = importFile(null, null,
                resource(prefix + "new.js", "var n = 1;"),
                resource(prefix + "existing.js", "var a = 2;"),
                resource(prefix + "bad.js", JAVA_TYPE),
                resource(prefix + "bad.ftl", EXECUTE));

        Integer added = id(prefix + "new.js");
        assertNotNull(added, "the missing path is added");
        assertEquals(List.of(sysadmin), historyOf(added).getList("user"), "the history names the importing user");
        assertEquals(List.of("import"), historyOf(added).getList("source"));

        assertEquals("var a = 1;", content(existing), "an existing row is kept unless ticked");
        assertEquals(1, historyOf(existing).getList("user").size(), "and gets no history row");

        assertNull(id(prefix + "bad.js"));
        assertNull(id(prefix + "bad.ftl"));
        assertTrue(log.contains("Refused " + prefix + "bad.js") && log.contains("Java.type"), log);
        assertTrue(log.contains("Refused " + prefix + "bad.ftl") && log.contains("freemarker.template.utility.Execute"),
                log);
        assertTrue(log.contains("Kept the deployment's row (not ticked) " + prefix + "existing.js"), log);
    }

    @Test
    void theImportOverwritesOnlyTheTickedPaths() {
        Integer ticked = create(prefix + "ticked.js", "var t = 1;");
        Integer unticked = create(prefix + "unticked.js", "var u = 1;");
        String sysadmin = historyOf(ticked).getString("[0].user");

        importFile(null, List.of(prefix + "ticked.js"),
                resource(prefix + "ticked.js", "var t = 2;"),
                resource(prefix + "unticked.js", "var u = 2;"));

        assertEquals("var t = 2;", content(ticked), "the ticked path is overwritten");
        assertEquals("var u = 1;", content(unticked), "the unticked path is kept");
        JsonPath history = historyOf(ticked);
        assertEquals(2, history.getList("user").size());
        assertEquals("import", history.getString("[0].source"), "the newest row is the import's");
        assertEquals(sysadmin, history.getString("[0].user"));
        assertEquals("editor", history.getString("[1].source"));
    }

    @Test
    void theImportAddsOnlyTheTickedMissingPaths() {
        importFile(List.of(), null, resource(prefix + "none.js", "var n = 1;"));
        assertNull(id(prefix + "none.js"), "an empty add adds nothing");

        importFile(List.of(prefix + "b.js"), null,
                resource(prefix + "a.js", "var a = 1;"),
                resource(prefix + "b.js", "var b = 1;"));
        assertNull(id(prefix + "a.js"), "an unticked missing path is not added");
        assertNotNull(id(prefix + "b.js"), "a ticked missing path is added");
    }

    @Test
    void thePagesCreateAndUpdateRecordTheSourceEditor() {
        Integer id = create(prefix + "edited.js", "var e = 1;");
        as(accessToken()).contentType(ContentType.JSON)
                .body(Map.of("id", id, "type", "JS", "path", prefix + "edited.js", "content", "var e = 2;"))
                .when().put(SCRIPT_RESOURCE + id)
                .then().statusCode(200);

        assertEquals(List.of("editor", "editor"), historyOf(id).getList("source"));
    }

    /**
     * The loader and the job find a row by its path case-insensitively, so the preview compares so too: a file's path
     * that differs from a row's only in case is that row's overwrite, never an add (the second review of PR #179)
     */
    @Test
    void aPathThatDiffersOnlyInCaseIsTheRowsOverwrite() {
        Integer row = create(prefix + "Case.ftl", "<#-- the row -->");

        JsonPath preview = as(accessToken())
                .multiPart("file", "case.json", file(resource(prefix + "case.ftl", "<#-- the file -->")),
                        "application/json")
                .when().post(PREVIEW)
                .then().statusCode(200).extract().jsonPath();
        assertEquals(List.of(), preview.getList("add"), "never an add");
        assertEquals(List.of(prefix + "case.ftl"), preview.getList("overwrite.path"));
        assertEquals("<#-- the row -->", preview.getString("overwrite[0].current"));

        String log = importFile(null, null, resource(prefix + "case.ftl", "<#-- the file -->"));
        assertEquals("<#-- the row -->", content(row), "unticked, the row is kept");
        assertTrue(log.contains("Kept the deployment's row (not ticked) " + prefix + "case.ftl"), log);

        importFile(null, List.of(prefix + "case.ftl"), resource(prefix + "case.ftl", "<#-- the file -->"));
        assertEquals("<#-- the file -->", content(row), "ticked, the row's content is replaced");
        assertEquals(1, all().stream().filter(r -> String.valueOf(r.get("path")).equalsIgnoreCase(prefix + "case.ftl"))
                .count(), "still one row");
    }

    /**
     * A tick carries the fingerprint of the row the preview showed: a row changed between the preview and the job is
     * kept, so the job never overwrites content nobody previewed (the second review of PR #179). A bare path tick
     * overwrites as before.
     */
    @Test
    void aTickedRowChangedSinceThePreviewIsKept() {
        String path = prefix + "race.js";
        Integer row = create(path, "var r = 1;");
        String basedOn = preview(resource(path, "var r = 3;")).getString("overwrite[0].basedOn");
        assertEquals(ScriptResourceImportService.fingerprint("var r = 1;"), basedOn);

        // Another sysadmin edits the row on the page after the preview
        as(accessToken()).contentType(ContentType.JSON)
                .body(Map.of("id", row, "type", "JS", "path", path, "content", "var r = 2;"))
                .when().put(SCRIPT_RESOURCE + row)
                .then().statusCode(200);

        String log = importFile(null, List.of(Map.of("path", path, "basedOn", basedOn)), resource(path, "var r = 3;"));
        assertEquals("var r = 2;", content(row), "the edit made after the preview is kept");
        assertTrue(log.contains("Kept (changed since the preview) " + path), log);

        String current = preview(resource(path, "var r = 3;")).getString("overwrite[0].basedOn");
        importFile(null, List.of(Map.of("path", path, "basedOn", current)), resource(path, "var r = 3;"));
        assertEquals("var r = 3;", content(row), "a tick on the row as previewed overwrites it");

        importFile(null, List.of(path), resource(path, "var r = 4;"));
        assertEquals("var r = 4;", content(row), "a bare path tick overwrites as before");
    }

    @Test
    void aTickFieldThatIsNoArrayOfPathsIsRefusedWith400BeforeAnyJob() {
        for (String overwrite : List.of("not json", "[1]", "{\"path\": \"x\"}", "[{\"path\": 1}]",
                "[{\"path\": \"x\", \"other\": \"y\"}]")) {
            as(accessToken())
                    .multiPart("file", "x.json", file(resource(prefix + "x.js", "var x;")), "application/json")
                    .multiPart("overwrite", overwrite)
                    .when().post(UPLOAD)
                    .then().statusCode(400).body("detail", containsString("overwrite"));
        }
        given().multiPart("file", "x.json", "[]".getBytes(StandardCharsets.UTF_8))
                .when().post(UPLOAD).then().statusCode(401);
    }

    // ------------------------------------------------------------------------------------------- //

    /** Uploads the resources with the ticks (null: the field is not sent), waits for the job; returns its log */
    @SafeVarargs
    private final String importFile(List<String> add, List<?> overwrite, Map<String, Object>... resources) {
        String fileName = "it-import-" + System.nanoTime() + ".json";
        RequestSpecification request = as(accessToken())
                .multiPart("file", fileName, file(resources), "application/json");
        if (add != null) {
            request = request.multiPart("add", json(add));
        }
        if (overwrite != null) {
            request = request.multiPart("overwrite", json(overwrite));
        }
        request.when().post(UPLOAD).then().statusCode(200).body(containsString("Started batch job"));
        return jobLog(awaitJob(em, jobOperator, JOB, fileName));
    }

    /**
     * Waits for the job of the uploaded file to complete; returns its instance id. The instance is found through its
     * BatchData row, by the file name, and its executions through the job operator.
     */
    static long awaitJob(EntityManager em, JobOperator jobOperator, String job, String fileName) {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            List<Long> ids = Tx.requiringNew().call(() -> em.createQuery(
                            "select b.instanceId from BatchData b where b.dataFileName = :f and b.instanceId is not null",
                            Long.class)
                    .setParameter("f", fileName)
                    .getResultList());
            if (!ids.isEmpty()) {
                long instanceId = ids.get(0);
                for (JobInstance instance : jobOperator.getJobInstances(job, 0, 10_000)) {
                    if (instance.getInstanceId() != instanceId) {
                        continue;
                    }
                    for (JobExecution execution : jobOperator.getJobExecutions(instance)) {
                        BatchStatus status = execution.getBatchStatus();
                        if (status == BatchStatus.COMPLETED) {
                            return instanceId;
                        }
                        if (status == BatchStatus.FAILED || status == BatchStatus.STOPPED
                                || status == BatchStatus.ABANDONED) {
                            fail("the job of " + fileName + " ended " + status);
                        }
                    }
                }
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
        fail("the job of " + fileName + " did not complete within 60 s");
        return -1;
    }

    /** The job's log files, one after the other */
    static String jobLog(long instanceId) {
        StringBuilder log = new StringBuilder();
        List<String> names = as(accessToken()).when().get("/rest/batch/instance/" + instanceId + "/logs")
                .then().statusCode(200).extract().jsonPath().getList("$", String.class);
        for (String name : names) {
            log.append(as(accessToken()).when().get("/rest/batch/instance/" + instanceId + "/logs/" + name)
                    .then().statusCode(200).extract().asString()).append('\n');
        }
        return log.toString();
    }

    @SafeVarargs
    private JsonPath preview(Map<String, Object>... resources) {
        return as(accessToken())
                .multiPart("file", "preview-" + System.nanoTime() + ".json", file(resources), "application/json")
                .when().post(PREVIEW)
                .then().statusCode(200).extract().jsonPath();
    }

    private Integer create(String path, String content) {
        return as(accessToken()).contentType(ContentType.JSON)
                .body(Map.of("type", path.endsWith(".js") ? "JS" : "FM", "path", path, "content", content))
                .when().post(SCRIPT_RESOURCE)
                .then().statusCode(200)
                .extract().path("id");
    }

    private List<Map<String, Object>> all() {
        return as(accessToken()).when().get(ALL).then().statusCode(200).extract().jsonPath().getList("$");
    }

    private Integer id(String path) {
        return all().stream().filter(r -> path.equals(r.get("path"))).map(r -> (Integer) r.get("id"))
                .findFirst().orElse(null);
    }

    private String content(Integer id) {
        return all().stream().filter(r -> id.equals(r.get("id"))).map(r -> (String) r.get("content"))
                .findFirst().orElseThrow();
    }

    private JsonPath historyOf(Integer id) {
        return as(accessToken()).when().get(SCRIPT_RESOURCE + id + "/history")
                .then().statusCode(200).extract().jsonPath();
    }

    private long count(String table) {
        return Tx.requiringNew().call(() -> ((Number) em.createNativeQuery(
                "SELECT count(*) FROM public.\"" + table + "\"").getSingleResult()).longValue());
    }

    private static Map<String, Object> resource(String path, String content) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("type", path.endsWith(".js") ? "JS" : "FM");
        resource.put("path", path);
        resource.put("content", content);
        return resource;
    }

    @SafeVarargs
    private static byte[] file(Map<String, Object>... resources) {
        return json(new ArrayList<>(List.of(resources))).getBytes(StandardCharsets.UTF_8);
    }

    private static String json(Object value) {
        try {
            return JsonUtils.toJson(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
