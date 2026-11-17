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

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.niord.core.util.JsonUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What the export-import-export round-trip ITs ({@code *ImportRoundTripITTest}) share, on top of
 * {@link RoundTripSupport}: an upload as a role, with a file name unique to the upload, and the wait for the batch
 * job it started, read through the batch REST interface ({@code GET /rest/batch/{job}/instances}, the instance found
 * by the uploaded file's name) with its log ({@code GET /rest/batch/instance/{id}/logs/...}).
 *
 * <p>The round trip each test walks: build rows with two or three of everything and non-default values, export them
 * through the REST endpoint the administration app opens, (a) remove them and import the export, then compare field
 * by field; (b) import the same export again over the rows, which must change, add and lose nothing; (c) import an
 * export with one field changed, one child added and one removed, and say what the import does with each; (d) export
 * again and compare with the first export. Every job's end is read: FAILED, or COMPLETED while rows were skipped
 * without a word, is a finding.
 *
 * <p>Not a test class itself (abstract, no {@code Test} suffix). This is a framework-coupled test, see
 * docs/framework-coupled-tests.md.
 */
abstract class ImportRoundTripSupport extends RoundTripSupport {

    /** The end of a batch job: its status and its log, the log files one after the other */
    protected record JobEnd(String status, String log, long instanceId) {

        boolean completed() {
            return "COMPLETED".equals(status);
        }
    }

    /** Names the characters an import must keep: a comma, a quote, a non-ASCII letter and a trailing space */
    protected static String awkward(String name) {
        return name + ", \"Ærø\" ";
    }

    // ------------------------------------------------------------------------------------------- //
    // Upload
    // ------------------------------------------------------------------------------------------- //

    /** A multipart upload of the bytes under the file name, with the text fields, as the role; the raw answer */
    protected static Response upload(String role, String path, String fileName, byte[] content,
                                     Map<String, String> fields) {
        RequestSpecification request = role == null ? given() : given().auth().oauth2(accessToken(role));
        request = request.header("NiordDomain", "niord-nw")
                .multiPart("file", fileName, content, "application/json");
        for (Map.Entry<String, String> field : fields.entrySet()) {
            request = request.multiPart(field.getKey(), field.getValue());
        }
        return request.when().post(path);
    }

    /**
     * Uploads the rows as JSON as the role and waits for the job; asserts the answer names a started job (it is 200
     * either way: an upload whose job cannot start answers 200 with an error text)
     */
    protected JobEnd importRows(String role, String path, String job, Object rows) {
        return importBytes(role, path, job, json(rows).getBytes(StandardCharsets.UTF_8), Map.of());
    }

    protected JobEnd importBytes(String role, String path, String job, byte[] content, Map<String, String> fields) {
        String fileName = "it-import-" + unique() + "-" + System.nanoTime() + ".json";
        Response answer = upload(role, path, fileName, content, fields);
        assertEquals(200, answer.statusCode(), "the upload to " + path + ": " + answer.asString());
        assertTrue(answer.asString().startsWith("Started batch job"), "the upload started a job: " + answer.asString());
        return awaitJob(job, fileName);
    }

    /** The number of jobs of the name that were ever started, read from their BatchData rows */
    protected long jobsStarted(String job) {
        return count("SELECT count(*) FROM public.\"BatchData\" WHERE \"jobName\" = ?1", job);
    }

    /**
     * Waits for the job of the uploaded file to end, through the batch REST interface, as the system administrator;
     * answers its terminal status and its log. The instance is the one whose data file has the uploaded name.
     */
    protected JobEnd awaitJob(String job, String fileName) {
        long deadline = System.currentTimeMillis() + 90_000L;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> page = as("sysadmin").when().get("/rest/batch/" + job + "/instances?page=0&pageSize=200")
                    .then().statusCode(200).extract().jsonPath().getMap("");
            for (Map<String, Object> instance : listOf(page, "data")) {
                if (!fileName.equals(instance.get("fileName"))) {
                    continue;
                }
                long instanceId = ((Number) instance.get("instanceId")).longValue();
                for (Map<String, Object> execution : listOf(instance, "executions")) {
                    String status = String.valueOf(execution.get("batchStatus"));
                    if (Set.of("COMPLETED", "FAILED", "STOPPED", "ABANDONED").contains(status)) {
                        return new JobEnd(status, jobLog(instanceId), instanceId);
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
        fail("the " + job + " job of " + fileName + " did not end within 90 s");
        return null;
    }

    /** The job's log files, one after the other */
    protected static String jobLog(long instanceId) {
        StringBuilder log = new StringBuilder();
        List<String> names = as("sysadmin").when().get("/rest/batch/instance/" + instanceId + "/logs")
                .then().statusCode(200).extract().jsonPath().getList("$", String.class);
        for (String name : names) {
            log.append(as("sysadmin").when().get("/rest/batch/instance/" + instanceId + "/logs/" + name)
                    .then().statusCode(200).extract().asString()).append('\n');
        }
        return log.toString();
    }

    // ------------------------------------------------------------------------------------------- //
    // Comparison
    // ------------------------------------------------------------------------------------------- //

    protected static String json(Object value) {
        try {
            return JsonUtils.toJson(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A deep copy of a JSON value, with the keys of every object sorted and the named keys left out at any depth */
    @SuppressWarnings("unchecked")
    protected static Object without(Object value, Collection<String> keys) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new TreeMap<>();
            ((Map<String, Object>) m).forEach((k, v) -> {
                if (!keys.contains(k)) {
                    out.put(k, without(v, keys));
                }
            });
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(v -> out.add(without(v, keys)));
            return out;
        }
        return value;
    }

    /** The value as the REST client reads it back: written as JSON and parsed again (numbers as the reads give them) */
    protected static Object asRead(Object value) {
        return io.restassured.path.json.JsonPath.from(json(value)).get("");
    }

    /** A mutable deep copy of a JSON object */
    @SuppressWarnings("unchecked")
    protected static Map<String, Object> copy(Map<String, Object> value) {
        Map<String, Object> out = new LinkedHashMap<>();
        value.forEach((k, v) -> out.put(k, copyValue(v)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object copyValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            return copy((Map<String, Object>) m);
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(e -> out.add(copyValue(e)));
            return out;
        }
        return v;
    }
}
