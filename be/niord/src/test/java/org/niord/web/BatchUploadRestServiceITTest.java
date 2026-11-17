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

import org.apache.commons.io.FileUtils;
import org.niord.core.batch.BatchData;
import org.niord.core.batch.BatchService;
import org.niord.test.Tx;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.chart.Chart;
import org.niord.core.source.Source;
import org.niord.test.ITTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the asynchronous batch-import upload endpoints: they verify the FULL contract
 * (HTTP upload &rarr; asynchronous job &rarr; terminal completion &rarr; database state), not merely the
 * 401/403 authorization gate that {@link org.niord.web.BatchRestServiceITTest} and the role matrix already cover.
 *
 * <p>An upload endpoint ({@code AbstractBatchableRestService}) parses the multipart file, stores it, and starts the
 * named batch job via {@code BatchService.startBatchJobWithDataFile}; the HTTP response only returns a log string,
 * not an execution id. So each happy-path test uploads a JSON file with a UNIQUE natural key and then polls the
 * database (in fresh transactions) until the imported row appears — proving the reader/processor/writer pipeline
 * ran to completion behind the REST layer. Imports commit on a background thread, so cleanup runs in a fresh
 * transaction afterwards to keep the shared seed database pristine and the tests independent.
 *
 * <p>A {@code sysadmin} bearer token carries the {@code admin} role required by the area/chart/source uploads
 * (no {@code NiordDomain} header needed). Authorization negatives are asserted too: no token &rarr; 401, and an
 * authenticated {@code editor} &rarr; 403 on the {@code SYSADMIN}-only category upload.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class BatchUploadRestServiceITTest extends ITTest {

    @PersistenceContext
    EntityManager em;

    @Autowired
    BatchService batchService;

    // ------------------------------------------------------------------------------------------- //
    // Happy path: upload -> job completion -> DB state
    // ------------------------------------------------------------------------------------------- //

    @Test
    void uploadChartsRunsImportToCompletionAndPersists() throws Exception {
        String chartNumber = "IT-UP-CH-" + UUID.randomUUID();
        String json = "[{\"chartNumber\":\"" + chartNumber + "\",\"name\":\"REST upload chart\",\"scale\":55555}]";
        try {
            given().auth().oauth2(accessToken())
                    .multiPart("file", "charts.json", json.getBytes(StandardCharsets.UTF_8), "application/json")
                    .when().post("/rest/charts/upload-charts")
                    .then().statusCode(200);

            assertTrue(awaitTrue(() -> chartExists(chartNumber)),
                    "the uploaded chart must be imported and persisted by the chart-import job");

            // Confirm the imported field mapping survived the whole pipeline.
            Chart c = Tx.requiringNew().call(() ->
                    em.createQuery("select c from Chart c where c.chartNumber = :n", Chart.class)
                            .setParameter("n", chartNumber).getResultStream().findFirst().orElse(null));
            org.junit.jupiter.api.Assertions.assertNotNull(c);
            org.junit.jupiter.api.Assertions.assertEquals("REST upload chart", c.getName());
        } finally {
            Tx.requiringNew().run(() ->
                    em.createQuery("delete from Chart where chartNumber = :n")
                            .setParameter("n", chartNumber).executeUpdate());
        }
    }

    /**
     * An upload carrying the bootstrap runner's own token as a form field is refused before any job starts
     * (docs/code-data-config-plan.md §7.2): every upload endpoint goes through AbstractBatchableRestService
     */
    @Test
    void anUploadCarryingTheBootstrapRunnersTokenIsRefusedAndStartsNoJob() throws Exception {
        String chartNumber = "IT-UP-TOKEN-" + UUID.randomUUID();
        String json = "[{\"chartNumber\":\"" + chartNumber + "\",\"name\":\"Token upload chart\",\"scale\":55555}]";
        long before = chartImportJobs();

        given().auth().oauth2(accessToken())
                .multiPart("file", "charts.json", json.getBytes(StandardCharsets.UTF_8), "application/json")
                .multiPart(org.niord.core.bootstrap.BootstrapRun.PROPERTY, "any-run/1")
                .when().post("/rest/charts/upload-charts")
                .then().statusCode(400);

        assertEquals(before, chartImportJobs(), "no chart-import job was started");
        assertFalse(chartExists(chartNumber), "nothing was imported");
    }

    private long chartImportJobs() throws Exception {
        return Tx.requiringNew().call(() -> em.createQuery(
                        "select count(b) from BatchData b where b.jobName = 'chart-import'", Long.class)
                .getSingleResult());
    }

    @Test
    void uploadSourcesRunsImportToCompletionAndPersists() throws Exception {
        String name = "IT Upload Source " + UUID.randomUUID();
        String json = "[{\"active\":true,\"descs\":[{\"lang\":\"en\",\"name\":\"" + name
                + "\",\"abbreviation\":\"IUS\"}]}]";
        try {
            given().auth().oauth2(accessToken())
                    .multiPart("file", "sources.json", json.getBytes(StandardCharsets.UTF_8), "application/json")
                    .when().post("/rest/sources/upload-sources")
                    .then().statusCode(200);

            assertTrue(awaitTrue(() -> sourceExists(name)),
                    "the uploaded source must be imported and persisted by the source-import job");
        } finally {
            Tx.requiringNew().run(() -> {
                List<Source> sources = em.createQuery(
                                "select distinct s from Source s join s.descs d where d.lang = 'en' and d.name = :n",
                                Source.class)
                        .setParameter("n", name).getResultList();
                sources.forEach(em::remove);
            });
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // The uploaded file's name: reduced to a bare name, so the data file stays in the job's own folder
    //
    // The name is the client's. The batch-job root holds every other job's folders, so the name must
    // resolve to a file directly inside the job's own execution folder.
    // ------------------------------------------------------------------------------------------- //

    @Test
    void uploadWithATraversingFileNameStoresTheFileInTheJobsOwnFolder() throws Exception {
        String chartNumber = "IT-UP-TRAV-" + UUID.randomUUID();
        String json = "[{\"chartNumber\":\"" + chartNumber + "\",\"name\":\"Traversal upload chart\",\"scale\":55555}]";
        String otherJob = "IT-UP-escape-" + System.nanoTime();
        String bareName = "it-trav-" + System.nanoTime() + ".json";
        Path otherJobFolder = batchService.getBatchJobRoot().resolve(otherJob);
        try {
            // [root]/chart-import/execution/[year]/[month]/[jobNo] is five levels below the root
            given().auth().oauth2(accessToken())
                    .multiPart("file", "../../../../../" + otherJob + "/in/" + bareName,
                            json.getBytes(StandardCharsets.UTF_8), "application/json")
                    .when().post("/rest/charts/upload-charts")
                    .then().statusCode(200);

            assertTrue(awaitTrue(() -> chartExists(chartNumber)),
                    "the upload must still be imported, from the job's own folder");
            assertFalse(Files.exists(otherJobFolder), "nothing may be written into another job's folders");

            BatchData data = Tx.requiringNew().call(() ->
                    em.createQuery("select b from BatchData b where b.dataFileName = :n", BatchData.class)
                            .setParameter("n", bareName).getResultStream().findFirst().orElse(null));
            assertNotNull(data, "the job must record the bare file name");
            Path dataFile = batchService.computeBatchJobPath(data.computeDataFilePath());
            assertTrue(Files.isRegularFile(dataFile), "the data file must be in the job's execution folder");
            assertEquals(batchService.computeBatchJobPath(data.computeBatchJobFolderPath()), dataFile.getParent());
        } finally {
            FileUtils.deleteQuietly(otherJobFolder.toFile());
            Tx.requiringNew().run(() ->
                    em.createQuery("delete from Chart where chartNumber = :n")
                            .setParameter("n", chartNumber).executeUpdate());
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // Authorization gate
    // ------------------------------------------------------------------------------------------- //

    @Test
    void uploadChartsWithoutTokenIsUnauthorized() {
        given()
                .multiPart("file", "charts.json", "[]".getBytes(StandardCharsets.UTF_8), "application/json")
                .when().post("/rest/charts/upload-charts")
                .then().statusCode(401);
    }

    @Test
    void uploadCategoriesAsEditorIsForbidden() {
        // /categories/upload-categories is @RolesAllowed(SYSADMIN); an authenticated editor must be rejected.
        given().auth().oauth2(accessToken("editor"))
                .header("NiordDomain", "niord-nw")
                .multiPart("file", "categories.json", "[]".getBytes(StandardCharsets.UTF_8), "application/json")
                .when().post("/rest/categories/upload-categories")
                .then().statusCode(403);
    }

    // ------------------------------------------------------------------------------------------- //
    // helpers
    // ------------------------------------------------------------------------------------------- //

    private boolean chartExists(String chartNumber) {
        return Tx.requiringNew().call(() ->
                !em.createQuery("select c from Chart c where c.chartNumber = :n", Chart.class)
                        .setParameter("n", chartNumber).getResultList().isEmpty());
    }

    private boolean sourceExists(String name) {
        return Tx.requiringNew().call(() ->
                !em.createQuery("select distinct s from Source s join s.descs d where d.lang = 'en' and d.name = :n",
                                Source.class)
                        .setParameter("n", name).getResultList().isEmpty());
    }

    /** Polls the supplier until it returns true or ~60s elapses. */
    private boolean awaitTrue(java.util.function.BooleanSupplier check) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < deadline) {
            if (check.getAsBoolean()) {
                return true;
            }
            Thread.sleep(200);
        }
        return check.getAsBoolean();
    }
}
