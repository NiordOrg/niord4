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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchService;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every JSON upload of the import pages, sent by a role that may not (nothing started), as a file that is not JSON,
 * as an empty list, and with a row that names a reference the installation does not have: the answer, the job's end
 * and what the file wrote.
 *
 * <p>This is a framework-coupled test, see docs/framework-coupled-tests.md.
 */
class UploadRefusalsImportRoundTripITTest extends ImportRoundTripSupport {

    /** An upload: its path, its job, the role that may and a role one tier below that may not */
    private record Upload(String path, String job, String role, String refused) {
    }

    private static final List<Upload> UPLOADS = List.of(
            new Upload("/rest/areas/upload-areas", "area-import", "admin", "editor"),
            new Upload("/rest/charts/upload-charts", "chart-import", "admin", "editor"),
            new Upload("/rest/sources/upload-sources", "source-import", "admin", "editor"),
            new Upload("/rest/publications/upload-publications", "publication-import", "admin", "editor"),
            new Upload("/rest/publication-categories/upload-publication-categories", "publication-category-import", "admin", "editor"),
            new Upload("/rest/publication-series/upload-series", "publication-series-import", "admin", "editor"),
            new Upload("/rest/categories/upload-categories", "category-import", "sysadmin", "admin"),
            new Upload("/rest/templates/upload-param-types", "param-type-import", "sysadmin", "admin"),
            new Upload("/rest/domains/upload-domains", "domain-import", "sysadmin", "admin"),
            new Upload("/rest/dictionaries/upload-dictionaries", "dictionary-import", "sysadmin", "admin"),
            new Upload("/rest/mailing-lists/upload-mailing-lists", "mailing-list-import", "sysadmin", "admin"),
            new Upload("/rest/promulgations/upload-promulgation-types", "promulgation-type-import", "sysadmin", "admin"),
            new Upload("/rest/message-reports/upload-reports", "report-import", "sysadmin", "admin"),
            new Upload("/rest/script-resources/upload-script-resources", "script-resource-import", "sysadmin", "admin"),
            new Upload("/rest/settings/upload-settings", "settings-import", "sysadmin", "admin"));

    @Autowired
    BatchService batchService;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void removeWhatTheTestMade() {
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            try {
                cleanups.get(i).run();
            } catch (RuntimeException | AssertionError ignored) {
                // best effort
            }
        }
        cleanups.clear();
    }

    @Test
    void anUploadByARoleThatMayNotIsRefusedAndStartsNoJob() {
        byte[] file = "[]".getBytes(StandardCharsets.UTF_8);
        Map<String, String> outcome = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (Upload u : UPLOADS) {
            long jobs = jobsStarted(u.job());
            int anonymous = upload(null, u.path(), "refused.json", file, Map.of()).statusCode();
            int refused = upload(u.refused(), u.path(), "refused.json", file, Map.of()).statusCode();
            outcome.put(u.path(), anonymous + "/" + refused + "/" + (jobsStarted(u.job()) - jobs));
            expected.put(u.path(), "401/403/0");
        }
        assertEquals(expected, outcome, "anonymous / " + "the role below / jobs started");
    }

    /**
     * A file that is not JSON is refused by each of the fifteen uploads with 400, naming the file, and no job
     * starts. It used to be answered 200 "Started batch job", and the job then ended FAILED in its reader with a log
     * that said nothing of why; the import dialog showed the answer as a success.
     */
    @Test
    void aFileThatIsNotJsonIsRefusedBeforeAJobStarts() {
        byte[] file = "{not json, \"Ærø\"".getBytes(StandardCharsets.UTF_8);
        Map<String, String> outcome = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (Upload u : UPLOADS) {
            String fileName = "it-notjson-" + unique() + ".json";
            long jobs = jobsStarted(u.job());
            Response answer = upload(u.role(), u.path(), fileName, file, Map.of());
            outcome.put(u.job(), answer.statusCode() + ", names the file " + answer.asString().contains(fileName)
                    + ", jobs started " + (jobsStarted(u.job()) - jobs));
            expected.put(u.job(), "400, names the file true, jobs started 0");
        }
        assertEquals(expected, outcome, "the answer and the jobs started, for a file that is not JSON");
    }

    /**
     * A file that is JSON but not the list the reader reads starts its job, and the job ends FAILED with a
     * log that names the failure: how the job ended, and the step's exception ({@code BatchJobListener.afterJob}).
     * A job that completes gets no such line.
     */
    @Test
    void aJobThatFailsAfterItStartsSaysWhyInItsLog() {
        byte[] file = "{\"a\": 1}".getBytes(StandardCharsets.UTF_8);
        Upload domains = UPLOADS.stream().filter(u -> u.job().equals("domain-import")).findFirst().orElseThrow();
        List<Upload> failing = new ArrayList<>(UPLOADS.subList(0, 3));
        failing.add(domains);
        Map<String, String> outcome = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (Upload u : failing) {
            JobEnd end = importBytes(u.role(), u.path(), u.job(), file, Map.of());
            String log = logWithEnd(end);
            outcome.put(u.job(), end.status() + (silent(log) ? ", the log is silent" : ", the log names the error")
                    + ", the job's end " + log.contains("The job ended FAILED")
                    + ", the step's exception " + log.contains("MismatchedInputException"));
            expected.put(u.job(), "FAILED, the log names the error, the job's end true, the step's exception true");
        }
        assertEquals(expected, outcome, "the job's end and its log, for a file that is JSON of the wrong shape");

        // A job that completes writes no end. Read on disk, as sizes before and after: a job's folder is named by its
        // job number, which starts again in each fresh database while NIORD_HOME is shared, so the folder may already
        // hold an end written by an earlier test run
        Map<String, Long> before = endLogSizes(domains.job());
        JobEnd completed = importBytes(domains.role(), domains.path(), domains.job(), "[]".getBytes(StandardCharsets.UTF_8), Map.of());
        assertEquals("COMPLETED", completed.status(), completed.log());
        try {
            Thread.sleep(1_000); // the listener runs after the status is set, as above
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertEquals(before, endLogSizes(domains.job()), "no end written for a job that completed");
    }

    /**
     * The job's log once it holds the job's end: the status a failed step gives the job can be read before the job's
     * listener has written the end, so the log is read again for up to ten seconds
     */
    private static String logWithEnd(JobEnd end) {
        String log = end.log();
        long deadline = System.currentTimeMillis() + 10_000L;
        while (!log.contains("The job ended") && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            log = jobLog(end.instanceId());
        }
        return log;
    }

    /** The size of every end log ({@code BatchJobLog.txt}) in the job's folders, by path */
    private Map<String, Long> endLogSizes(String job) {
        Map<String, Long> sizes = new java.util.TreeMap<>();
        java.nio.file.Path folder = batchService.getBatchJobRoot().resolve(job);
        if (!java.nio.file.Files.isDirectory(folder)) {
            return sizes;
        }
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(folder)) {
            for (java.nio.file.Path file : files.filter(f -> f.getFileName().toString().equals("BatchJobLog.txt")).toList()) {
                sizes.put(file.toString(), java.nio.file.Files.size(file));
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return sizes;
    }

    /**
     * Whether a job log says nothing of a failure. Not only "no exception": a fix that writes the failure in its own
     * words ("Job FAILED: Unexpected character ...", a SEVERE or WARNING line of the job's java.util.logging handler)
     * counts, so any word a failure line would carry counts.
     */
    private static boolean silent(String log) {
        String text = log.toLowerCase(java.util.Locale.ROOT);
        return java.util.stream.Stream.of("exception", "error", "fail", "severe", "warning", "invalid", "unexpected",
                "cannot", "could not").noneMatch(text::contains);
    }

    /**
     * The tables the fifteen imports write their rows to. {@code Setting} is left out: a setting's row may be
     * created by its first read anywhere in the running system, and the settings import is checked value by value
     * in {@code AdminDataImportRoundTripITTest}.
     */
    private static final List<String> IMPORTED_TABLES = List.of("Area", "Chart", "Source", "Publication",
            "PublicationCategory", "PublicationSeries", "Category", "ParamType", "Domain", "DictionaryEntry",
            "MailingList", "PromulgationType", "FmReport", "ScriptResource");

    @Test
    void anEmptyListCompletesItsJobAndWritesNothing() {
        Map<String, Long> rowsBefore = importedRows();
        Map<String, String> outcome = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (Upload u : UPLOADS) {
            JobEnd end = importBytes(u.role(), u.path(), u.job(), "[]".getBytes(StandardCharsets.UTF_8), Map.of());
            outcome.put(u.job(), end.status());
            expected.put(u.job(), "COMPLETED");
        }
        assertEquals(expected, outcome);
        // "Writes nothing": the name claimed it, and nothing checked it
        assertEquals(rowsBefore, importedRows(), "the rows of the tables the imports write");
    }

    private Map<String, Long> importedRows() {
        Map<String, Long> rows = new LinkedHashMap<>();
        IMPORTED_TABLES.forEach(t -> rows.put(t, count("SELECT count(*) FROM public.\"" + t + "\"")));
        return rows;
    }

    // ------------------------------------------------------------------------------------------- //
    // Rows that name what the installation does not have
    // ------------------------------------------------------------------------------------------- //

    /**
     * CATEGORY3: a category file whose sub-category names a domain this Niord does not have, beside one it has, is
     * imported without the unknown domain, and the job's log names it. Domain ids differ between deployments, and the
     * sysadmin manual presents the category import as the way to move categories between Niord systems. The job
     * FAILED and stored nothing of its chunk, the root included: {@code CategoryService.importCategory} kept the
     * unknown domain as the file's transient {@code Domain} ({@code .orElse(domain)}), and {@code createCategory}
     * added the new sub-category to its parent's children before it looked the domains up, so the lookup's
     * auto-flush met the transient domain. A root category with the same domains is imported the same way.
     */
    @Test
    void aSubCategoryThatNamesAnUnknownDomainIsImportedWithoutIt() {
        String u = unique();
        String mrn = "urn:mrn:iho:it-imp-ref:" + u;
        Map<String, Object> file = map("type", "CATEGORY", "mrn", mrn, "active", true, "siblingSortOrder", 0.0,
                "descs", list(map("lang", "en", "name", "IT-imp ref root " + u)),
                "children", list(map("type", "TEMPLATE", "active", true, "siblingSortOrder", 0.0,
                        "descs", list(map("lang", "en", "name", "IT-imp ref child " + u)),
                        "domains", list(map("domainId", "niord-nw"), map("domainId", "no-such-domain-" + u)))));
        cleanups.add(() -> ints("SELECT id FROM public.\"Category\" WHERE mrn = ?1", mrn)
                .forEach(id -> delete("sysadmin", "/rest/categories/category/" + id)));
        JobEnd job = importRows("sysadmin", "/rest/categories/upload-categories", "category-import", List.of(file));
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("job", job.status());
        outcome.put("child domains", strings("SELECT d.\"domainId\" FROM public.\"Category_Domain\" j"
                + " JOIN public.\"Domain\" d ON d.id = j.domains_id JOIN public.\"CategoryDesc\" c ON c.entity_id = j.\"Category_id\""
                + " WHERE c.name = ?1", "IT-imp ref child " + u));
        outcome.put("log names it", job.log().contains("no-such-domain-" + u));
        outcome.put("root stored", count("SELECT count(*) FROM public.\"Category\" WHERE mrn = ?1", mrn));
        assertEquals(List.of("COMPLETED", List.of("niord-nw"), true, 1L), new ArrayList<>(outcome.values()),
                "the job, the domains stored, whether the log names the unknown domain, the root: " + outcome + "\n" + job.log());
        assertTrue(job.log().contains("'IT-imp ref child " + u + "': left out the domain no-such-domain-" + u
                + ", which this Niord does not have"), job.log());

        // The same domains on a root category: imported, without the unknown one, and the log names it
        String rootMrn = mrn + ":root";
        cleanups.add(() -> ints("SELECT id FROM public.\"Category\" WHERE mrn = ?1", rootMrn)
                .forEach(id -> delete("sysadmin", "/rest/categories/category/" + id)));
        JobEnd root = importRows("sysadmin", "/rest/categories/upload-categories", "category-import", List.of(
                map("type", "TEMPLATE", "mrn", rootMrn, "active", true, "siblingSortOrder", 0.0,
                        "descs", list(map("lang", "en", "name", "IT-imp ref lone " + u)),
                        "domains", list(map("domainId", "niord-nw"), map("domainId", "no-such-domain-" + u)))));
        assertEquals(List.of("COMPLETED", List.of("niord-nw"), true), List.of(root.status(), strings("SELECT d.\"domainId\""
                + " FROM public.\"Category_Domain\" j JOIN public.\"Domain\" d ON d.id = j.domains_id JOIN public.\"Category\" c"
                + " ON c.id = j.\"Category_id\" WHERE c.mrn = ?1", rootMrn), root.log().contains("no-such-domain-" + u)),
                root.log());

        // The same file again: the root is found by its MRN and updated, still without the unknown domain, and the
        // log names it again
        JobEnd again = importRows("sysadmin", "/rest/categories/upload-categories", "category-import", List.of(
                map("type", "TEMPLATE", "mrn", rootMrn, "active", true, "siblingSortOrder", 0.0,
                        "descs", list(map("lang", "en", "name", "IT-imp ref lone " + u)),
                        "domains", list(map("domainId", "niord-nw"), map("domainId", "no-such-domain-" + u)))));
        assertEquals(List.of("COMPLETED", List.of("niord-nw"), true), List.of(again.status(), strings("SELECT d.\"domainId\""
                + " FROM public.\"Category_Domain\" j JOIN public.\"Domain\" d ON d.id = j.domains_id JOIN public.\"Category\" c"
                + " ON c.id = j.\"Category_id\" WHERE c.mrn = ?1", rootMrn), again.log().contains("no-such-domain-" + u)),
                again.log());
    }

    /**
     * CATEGORY3, a parent: the import's items are the leaf categories, and a parent comes in through each leaf's
     * parent chain. A parent (non-leaf) category that names a domain this Niord does not have is imported without it,
     * and the job's log names it once, though the parent is imported again for each of its two leaves.
     */
    @Test
    void aParentCategoryThatNamesAnUnknownDomainIsImportedWithoutItAndLoggedOnce() {
        String u = unique();
        String mrn = "urn:mrn:iho:it-imp-ref-parent:" + u;
        Map<String, Object> file = map("type", "CATEGORY", "mrn", mrn, "active", true, "siblingSortOrder", 0.0,
                "descs", list(map("lang", "en", "name", "IT-imp ref parent " + u)),
                "domains", list(map("domainId", "niord-nw"), map("domainId", "no-such-domain-" + u)),
                "children", list(
                        map("type", "TEMPLATE", "active", true, "siblingSortOrder", 0.0,
                                "descs", list(map("lang", "en", "name", "IT-imp ref leaf one " + u))),
                        map("type", "TEMPLATE", "active", true, "siblingSortOrder", 1.0,
                                "descs", list(map("lang", "en", "name", "IT-imp ref leaf two " + u)))));
        cleanups.add(() -> ints("SELECT id FROM public.\"Category\" WHERE mrn = ?1", mrn)
                .forEach(id -> delete("sysadmin", "/rest/categories/category/" + id)));
        JobEnd job = importRows("sysadmin", "/rest/categories/upload-categories", "category-import", List.of(file));
        String line = "'IT-imp ref parent " + u + "': left out the domain no-such-domain-" + u
                + ", which this Niord does not have";
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("job", job.status());
        outcome.put("parent domains", strings("SELECT d.\"domainId\" FROM public.\"Category_Domain\" j"
                + " JOIN public.\"Domain\" d ON d.id = j.domains_id JOIN public.\"Category\" c ON c.id = j.\"Category_id\""
                + " WHERE c.mrn = ?1", mrn));
        outcome.put("lines naming it", job.log().split(java.util.regex.Pattern.quote(line), -1).length - 1);
        outcome.put("leaves stored", count("SELECT count(*) FROM public.\"Category\" c JOIN public.\"Category\" p"
                + " ON p.id = c.parent_id WHERE p.mrn = ?1", mrn));
        assertEquals(List.of("COMPLETED", List.of("niord-nw"), 1, 2L), new ArrayList<>(outcome.values()),
                "the job, the parent's domains, the log lines naming the unknown domain, the leaves: " + outcome
                        + "\n" + job.log());
    }

    /** A promulgation type and a print report naming an unknown domain beside a known one */
    @Test
    void aPromulgationTypeAndAReportThatNameAnUnknownDomainAreImportedWithoutIt() {
        String u = unique();
        String typeId = "it-imp-ref-" + u;
        String reportId = "it-imp-ref-" + u;
        cleanups.add(() -> delete("sysadmin", "/rest/promulgations/promulgation-type/" + typeId));
        cleanups.add(() -> delete("sysadmin", "/rest/message-reports/report/" + reportId));
        List<Object> domains = list(map("domainId", "niord-nw"), map("domainId", "no-such-domain-" + u));

        JobEnd type = importRows("sysadmin", "/rest/promulgations/upload-promulgation-types", "promulgation-type-import",
                List.of(map("typeId", typeId, "serviceId", "navtex", "name", "IT ref", "active", true, "priority", 1,
                        "requirement", "OPTIONAL", "language", "en", "domains", domains)));
        JobEnd report = importRows("sysadmin", "/rest/message-reports/upload-reports", "report-import",
                List.of(map("reportId", reportId, "name", "IT ref", "sortOrder", 1, "templatePath", "templates/it/x.ftl",
                        "domains", domains)));
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("type job", type.status());
        outcome.put("type domains", strings("SELECT d.\"domainId\" FROM public.\"PromulgationType_Domain\" j"
                + " JOIN public.\"Domain\" d ON d.id = j.domains_id JOIN public.\"PromulgationType\" p"
                + " ON p.id = j.\"PromulgationType_id\" WHERE p.\"typeId\" = ?1", typeId));
        outcome.put("report job", report.status());
        outcome.put("report domains", strings("SELECT d.\"domainId\" FROM public.\"FmReport_Domain\" j"
                + " JOIN public.\"Domain\" d ON d.id = j.domains_id JOIN public.\"FmReport\" r"
                + " ON r.id = j.\"FmReport_id\" WHERE r.\"reportId\" = ?1", reportId));
        assertEquals(List.of("COMPLETED", List.of("niord-nw"), "COMPLETED", List.of("niord-nw")),
                new ArrayList<>(outcome.values()), outcome + "\n" + type.log() + "\n" + report.log());
    }

    /** A list parameter type naming a dictionary key no dictionary has: the sysadmin manual says it is left out */
    @Test
    void aListTypeThatNamesAnUnknownKeyIsImportedWithoutIt() {
        String u = unique();
        String name = "IT-imp ref list " + u;
        String known = strings("SELECT dict_key FROM public.\"DictionaryEntry\" GROUP BY dict_key HAVING count(*) = 1"
                + " ORDER BY 1 LIMIT 1").getFirst();
        cleanups.add(() -> ints("SELECT id FROM public.\"ParamType\" WHERE name = ?1", name)
                .forEach(id -> delete("sysadmin", "/rest/templates/parameter-type/" + id)));
        JobEnd job = importRows("sysadmin", "/rest/templates/upload-param-types", "param-type-import",
                List.of(map("type", "LIST", "name", name, "values", list(map("key", "no.such.key." + u), map("key", known)))));
        List<String> keys = strings("SELECT e.dict_key FROM public.\"ParamType_DictionaryEntry\" j JOIN public.\"DictionaryEntry\" e"
                + " ON e.id = j.values_id JOIN public.\"ParamType\" p ON p.id = j.\"ListParamType_id\" WHERE p.name = ?1"
                + " ORDER BY j.\"indexNo\"", name);
        assertEquals(List.of("COMPLETED", List.of(known)), List.of(job.status(), keys), job.log());
    }

    /** A settings file naming a key no catalog declares: skipped, with a line in the job's log (the manual) */
    @Test
    void aSettingsFileNamingAnUnknownKeyIsSkippedWithALine() {
        String key = "itImpUnknown" + unique();
        JobEnd job = importRows("sysadmin", "/rest/settings/upload-settings", "settings-import",
                List.of(map("key", key, "type", "String", "value", "x")));
        assertEquals("COMPLETED", job.status(), job.log());
        assertTrue(job.log().contains("Skipped " + key + ": no catalog declares it"), job.log());
        assertEquals(0L, count("SELECT count(*) FROM public.\"Setting\" WHERE setting_key = ?1", key));
    }
}
