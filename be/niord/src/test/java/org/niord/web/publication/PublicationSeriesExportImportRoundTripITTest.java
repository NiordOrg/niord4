/*
 * Copyright 2026 Danish Emergency Management Agency.
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

package org.niord.web.publication;

import io.restassured.response.Response;
import jakarta.batch.operations.JobOperator;
import jakarta.batch.runtime.BatchStatus;
import jakarta.batch.runtime.JobExecution;
import jakarta.batch.runtime.JobInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.batch.BatchService;
import org.niord.core.publication.TestIds;
import org.niord.core.publication.series.StubIssueRenderService;
import org.niord.test.BatchJobs;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Series definitions exported and imported back, and a compilation (a series compiled from another series' released
 * issues) created, published and exported with its source.
 *
 * <p>The export is {@code GET /rest/publication-series/export}; the import is the JSON upload, which runs as a batch
 * job. A source series and a compilation of it are exported, deleted, imported into the emptied slot from the
 * exported rows, and exported again: the two exports must carry the same fields. The import is domain-scoped per row,
 * so a row uploaded from another desk is skipped.
 */
class PublicationSeriesExportImportRoundTripITTest extends PublicationHttpFixture {

    private static final String BASE = "/rest/publication-series";
    private static final String ISSUES = "/rest/publication-issues";

    private static final long SEP_1 = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli();
    private static final long SEP_30 = Instant.parse("2026-09-30T12:00:00Z").toEpochMilli();
    private static final long OCT_31 = Instant.parse("2026-10-31T12:00:00Z").toEpochMilli();

    /** The fields an export carries that the import does not restore: the row's own revision and timestamps. */
    private static final Set<String> ROW_FIELDS = Set.of("version", "created", "updated");

    private String categoryId;

    @BeforeEach
    void seedTheCategory() {
        categoryId = newCategory();
        StubIssueRenderService.reset();
    }

    // ------------------------------------------------------------------------------------------- //
    // Export, empty, import, export again
    // ------------------------------------------------------------------------------------------- //

    /**
     * The export lists a source before its compilation so that an import finds the source first. The import writes
     * each row before it reads the next (a chunk of one row), so the compilation finds the source the same file
     * wrote just before it, and the one upload imports both (BATCH8: with chunks of ten rows the compilation was
     * processed before its source was written, and skipped). Then the round trip itself: the export after the import
     * carries what the first export did, and a re-import of the compilation alone updates it in place.
     */
    @Test
    void aSourceAndItsCompilationInOneFileImportTogether() throws Exception {
        String source = track(TestIds.series());
        String compilation = track(TestIds.series());
        object(as("admin").body(querySeries(source)).post(BASE + "/series"));
        object(as("admin").body(compilationOf(compilation, source)).post(BASE + "/series"));

        List<Map<String, Object>> first = exported(source, compilation);
        assertEquals(List.of(source, compilation), first.stream().map(s -> (String) s.get("seriesId")).toList(),
                "the export lists the source before its compilation");

        // Emptied: the compilation first, since the source is its operand
        refusedWith(as("admin").delete(BASE + "/series/" + source), 409, "SERIES_IS_COMPILED",
                "the delete of a series another one compiles");
        as("admin").delete(BASE + "/series/" + compilation).then().statusCode(204);
        as("admin").delete(BASE + "/series/" + source).then().statusCode(204);
        assertEquals(List.of(), exported(source, compilation), "both gone");

        String answer = upload(first, DOMAIN);
        assertTrue(answer.startsWith("Started batch job"), "the upload reports a started job: " + answer);
        assertEquals(BatchStatus.COMPLETED, lastImport(), "the import job completes");
        assertEquals(List.of(source, compilation), exported(source, compilation).stream()
                        .map(s -> (String) s.get("seriesId")).toList(),
                "the one upload imports the source and its compilation");
        assertFalse(importLog().contains("import the source first"),
                "no row was skipped for a missing source: " + importLog());

        List<Map<String, Object>> second = exported(source, compilation);
        for (int i = 0; i < 2; i++) {
            assertEquals(withoutRowFields(first.get(i)), withoutRowFields(second.get(i)),
                    "the export of " + first.get(i).get("seriesId") + " after the import");
        }
        assertEquals("DRAFT", second.get(0).get("status"), "an imported series arrives DRAFT");
        assertEquals(2, count("SELECT count(*) FROM public.\"PublicationSeriesDesc\" d JOIN public.\"PublicationSeries\""
                + " s ON s.id = d.entity_id WHERE s.\"seriesId\" = :s", "s", source), "the source's two desc rows");
        assertEquals(List.of("da", "en"), rows("SELECT l.languages FROM public.\"PublicationSeries_languages\" l JOIN"
                + " public.\"PublicationSeries\" s ON s.id = l.\"PublicationSeries_id\" WHERE s.\"seriesId\" = :s"
                + " ORDER BY l.\"indexNo\"", "s", source).stream().map(r -> r[0]).toList(), "the source's languages");

        // A re-import of the compilation alone updates it in place and changes nothing of what it exports
        upload(List.of(first.get(1)), DOMAIN);
        assertEquals(BatchStatus.COMPLETED, lastImport(), "the re-import of the compilation alone completes");
        List<Map<String, Object>> third = exported(source, compilation);
        assertEquals(2, third.size(), "still the source and its compilation");
        assertEquals(withoutRowFields(first.get(1)), withoutRowFields(third.get(1)),
                "the export of the compilation after its re-import");
    }

    /**
     * A file that lists a compilation before its source cannot be resolved row by row: the compilation is skipped
     * with a sentence that says to import the source first, the source is imported, and the job completes.
     */
    @Test
    void aCompilationListedBeforeItsSourceIsSkippedWithASentenceAndTheSourceIsImported() throws Exception {
        String source = track(TestIds.series());
        String compilation = track(TestIds.series());

        String answer = upload(List.of(compilationOf(compilation, source), querySeries(source)), DOMAIN);
        assertTrue(answer.startsWith("Started batch job"), "the upload reports a started job: " + answer);
        assertEquals(BatchStatus.COMPLETED, lastImport(), "the import job completes");
        assertEquals(List.of(source), exported(source, compilation).stream().map(s -> (String) s.get("seriesId"))
                .toList(), "only the source arrived");
        String log = importLog();
        assertTrue(log.contains("Skipping series " + compilation) && log.contains("import the source first"),
                "the job log names the skipped compilation and what to do: " + log);
    }

    /**
     * The import is an upsert by seriesId ("re-importing a file updates the series it describes"). The processor runs
     * on the batch thread outside a transaction, so the series it finds must come with the collections the update
     * touches (its descs, languages and sharing list) already loaded; otherwise {@code updateFromVo} throws
     * {@code LazyInitializationException} and the whole job fails (BATCH7). The existing series is ACTIVE, so the
     * activation rules the processor applies to an active series read it as well.
     */
    @Test
    void reImportingAnExistingSeriesUpdatesItAndImportsTheNewRowBesideIt() throws Exception {
        String existing = track(TestIds.series());
        String fresh = track(TestIds.series());
        object(as("admin").body(querySeries(existing)).post(BASE + "/series"));
        activate(existing);
        Map<String, Object> before = object(as("admin").get(BASE + "/editable-series/" + existing));
        assertEquals("ACTIVE", before.get("status"), "the existing series is active");

        // The exported row with only the two names changed
        Map<String, Object> renamed = new LinkedHashMap<>(exported(existing).getFirst());
        List<Map<String, Object>> descs = new ArrayList<>();
        for (Object d : (List<?>) renamed.get("descs")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> desc = new LinkedHashMap<>((Map<String, Object>) d);
            desc.put("name", "da".equals(desc.get("lang")) ? "IT kilde, omdøbt" : "IT source, renamed");
            descs.add(desc);
        }
        renamed.put("descs", descs);
        String answer = upload(List.of(querySeries(fresh), renamed), DOMAIN);
        assertTrue(answer.startsWith("Started batch job"), "the upload reports a started job: " + answer);

        assertEquals(BatchStatus.COMPLETED, lastImport(), "the job completes");
        assertEquals(List.of(fresh), exported(fresh).stream().map(s -> (String) s.get("seriesId")).toList(),
                "the new series beside it is imported");
        Map<String, Object> after = object(as("admin").get(BASE + "/editable-series/" + existing));
        Map<String, String> names = new LinkedHashMap<>();
        for (Object d : (List<?>) after.get("descs")) {
            Map<?, ?> desc = (Map<?, ?>) d;
            names.put((String) desc.get("lang"), (String) desc.get("name"));
        }
        assertEquals(Map.of("da", "IT kilde, omdøbt", "en", "IT source, renamed"), names,
                "the existing series carries the renamed descs");
        assertEquals("ACTIVE", after.get("status"), "and keeps its status");
        Map<String, Object> beforeRest = withoutRowFields(before);
        Map<String, Object> afterRest = withoutRowFields(after);
        beforeRest.remove("descs");
        afterRest.remove("descs");
        assertEquals(beforeRest, afterRest, "and nothing else of it changed");
    }

    @Test
    void aRowUploadedFromAnotherDeskIsSkippedAndTheStoredSeriesIsUntouched() throws Exception {
        String seriesId = track(TestIds.series());
        object(as("admin").body(querySeries(seriesId)).post(BASE + "/series"));
        Map<String, Object> before = object(as("admin").get(BASE + "/editable-series/" + seriesId));

        Map<String, Object> changed = new LinkedHashMap<>(before);
        changed.put("descs", List.of(Map.of("lang", "da", "name", "Overtaget"), Map.of("lang", "en", "name", "Taken")));
        String answer = upload(List.of(changed), OTHER_DOMAIN);
        assertTrue(answer.startsWith("Started batch job"), "the upload started: " + answer);
        assertEquals(BatchStatus.COMPLETED, lastImport(), "the job completes, skipping the row");
        assertEquals(before, object(as("admin").get(BASE + "/editable-series/" + seriesId)),
                "a row from another desk does not change the series");
    }

    // ------------------------------------------------------------------------------------------- //
    // A compilation, published from its source's released issue
    // ------------------------------------------------------------------------------------------- //

    @Test
    void aCompilationIsPublishedWithTheFrozenMembersOfItsSourcesReleasedIssue() {
        String source = track(TestIds.series());
        String compilation = track(TestIds.series());
        object(as("admin").body(querySeries(source)).post(BASE + "/series"));
        activate(source);
        String weekly = issue(source, SEP_1, SEP_30);
        Map<String, Object> released = publish(weekly, SEP_30);
        Set<String> sourceMembers = members(weekly);
        assertEquals(intOf(released, "memberCount"), sourceMembers.size(), "the source issue's members");
        assertFalse(sourceMembers.isEmpty(), "the source issue holds the seed's September notices");

        object(as("admin").body(compilationOf(compilation, source)).post(BASE + "/series"));
        activate(compilation);
        String annual = issue(compilation, SEP_1, OCT_31);
        Map<String, Object> compiled = publish(annual, OCT_31);
        assertEquals(sourceMembers, members(annual), "the compilation holds what its source issue printed");
        assertEquals(sourceMembers.size(), intOf(compiled, "memberCount"), "the compilation's member count");
        Map<String, Object> issue = object(as("admin").get(ISSUES + "/editable-issue/" + annual));
        assertEquals(source, issue.get("snapshotSourceSeriesId"), "the snapshot names the source series");
        assertTrue(((List<?>) issue.get("snapshotSourceIssueIds")).contains(weekly),
                "and the source issue it compiled: " + issue.get("snapshotSourceIssueIds"));
        for (Map<String, Object> m : array(as("admin").get(ISSUES + "/issue/" + annual + "/members?lang=en"))) {
            assertEquals("COMPILED", m.get("source"), "each member is compiled: " + m);
            assertEquals("FROM_SOURCE_ISSUE", m.get("reasonCode"), "from the source issue: " + m);
            assertEquals(weekly, mapOf(m, "sourceIssue").get("publicId"), "the source issue it came from: " + m);
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // An imported criteria operand that names nothing
    // ------------------------------------------------------------------------------------------- //

    /**
     * A criteria operand that names no message series is refused by the form's create and save (C-4), and the
     * import applies the same check: the row is skipped with a sentence that names the series and says what is
     * wrong, as an unknown category is, and the job completes (PUBSERIES1).
     */
    @Test
    void anImportedSeriesWithAnOperandThatNamesNothingIsSkipped() throws Exception {
        String seriesId = track(TestIds.series());
        Map<String, Object> dangling = querySeries(seriesId);
        String missing = "no-such-series-" + TestIds.suffix();
        dangling.put("criteria", criteria(node("messageSeries", missing)));

        refusedWith(as("admin").body(dangling).post(BASE + "/series"), 400, "SERIES_INVALID",
                "the form's create refuses the operand that names nothing");

        String answer = upload(List.of(dangling), DOMAIN);
        assertTrue(answer.startsWith("Started batch job"), "the upload reports a started job: " + answer);
        assertEquals(BatchStatus.COMPLETED, lastImport(), "the import completes, skipping the row");
        refusedWith(as("admin").get(BASE + "/editable-series/" + seriesId), 404, "SERIES_NOT_FOUND",
                "the series with the operand that names nothing");
        String log = importLog();
        assertTrue(log.contains("Skipping series " + seriesId) && log.contains("name nothing")
                        && log.contains(missing),
                "the job log names the skipped series, the operand and what is wrong with it: " + log);
    }

    /**
     * A re-import of a stored series that would give it an operand naming nothing is skipped as well, and the stored
     * series is left exactly as it was: the row is refused after the document has been applied to the series found,
     * and nothing of that reaches the database.
     */
    @Test
    void aReImportThatGivesAStoredSeriesAnOperandThatNamesNothingLeavesItUntouched() throws Exception {
        String seriesId = track(TestIds.series());
        object(as("admin").body(querySeries(seriesId)).post(BASE + "/series"));
        Map<String, Object> before = object(as("admin").get(BASE + "/editable-series/" + seriesId));

        Map<String, Object> dangling = new LinkedHashMap<>(exported(seriesId).getFirst());
        String missing = "no-such-series-" + TestIds.suffix();
        dangling.put("criteria", criteria(node("messageSeries", missing)));
        dangling.put("descs", List.of(Map.of("lang", "da", "name", "Omdøbt"), Map.of("lang", "en", "name", "Renamed")));
        upload(List.of(dangling), DOMAIN);

        assertEquals(BatchStatus.COMPLETED, lastImport(), "the import completes, skipping the row");
        assertEquals(before, object(as("admin").get(BASE + "/editable-series/" + seriesId)),
                "the stored series is untouched: neither the operand nor the new names arrived");
        String log = importLog();
        assertTrue(log.contains("Skipping series " + seriesId) && log.contains("name nothing"),
                "the job log names the skipped series: " + log);
    }

    /**
     * Activation asks the same question the save asks: a series whose operand names nothing is refused ACTIVE with
     * 400 SERIES_INVALID naming C-4, and stays DRAFT. The series is stored through the form with a valid operand,
     * which is then pointed at a message series that does not exist directly in the database: that is what an
     * operand that went dangling later looks like, and the save offers no other way to store one (PUBSERIES1).
     */
    @Test
    void activatingASeriesWhoseOperandNamesNothingIsRefusedWithC4() {
        String seriesId = track(TestIds.series());
        object(as("admin").body(querySeries(seriesId)).post(BASE + "/series"));
        String missing = "no-such-series-" + TestIds.suffix();
        execute("UPDATE public.\"PublicationSeries\" SET criteria = replace(criteria, '\"dma-nm\"', '\""
                + missing + "\"') WHERE \"seriesId\" = :s", "s", seriesId);
        Map<String, Object> stored = object(as("admin").get(BASE + "/editable-series/" + seriesId));
        assertTrue(String.valueOf(stored.get("criteria")).contains(missing), "the operand that names nothing");

        Response r = as("admin").body("\"ACTIVE\"")
                .put(BASE + "/series/" + seriesId + "/status?version=" + intOf(stored, "version"));
        refusedWith(r, 400, "SERIES_INVALID", "the activation of a series whose operand names nothing");
        assertTrue(r.asString().contains("C-4") && r.asString().contains(missing),
                "the refusal names C-4 and the operand: " + r.asString());
        assertEquals("DRAFT", object(as("admin").get(BASE + "/editable-series/" + seriesId)).get("status"),
                "the series stays DRAFT");
    }

    /**
     * A series scoped by a VALID domain operand imports. The C-4 check resolves a domain operand by reading the
     * domain's message series, a lazy collection; the processor runs on the batch thread outside a transaction, so
     * asked there the read threw {@code LazyInitializationException} and the whole job ended FAILED, although the
     * form's create accepts the same series. The import asks it through the service, inside a transaction.
     */
    @Test
    void aNewSeriesScopedByAValidDomainImports() throws Exception {
        String formSaved = track(TestIds.series());
        String imported = track(TestIds.series());
        Map<String, Object> byForm = querySeries(formSaved);
        byForm.put("criteria", criteria(node("domain", OTHER_DOMAIN), node("messageMainType", "NM")));
        object(as("admin").body(byForm).post(BASE + "/series"));

        Map<String, Object> row = querySeries(imported);
        row.put("criteria", criteria(node("domain", OTHER_DOMAIN), node("messageMainType", "NM")));
        String answer = upload(List.of(row), DOMAIN);
        assertTrue(answer.startsWith("Started batch job"), "the upload reports a started job: " + answer);
        assertEquals(BatchStatus.COMPLETED, lastImport(), "the import of a series scoped by a domain completes");
        List<Map<String, Object>> rows = exported(imported);
        assertEquals(1, rows.size(), "the series scoped by a domain is stored");
        String stored = String.valueOf(rows.getFirst().get("criteria"));
        assertTrue(stored.contains("kind=domain") && stored.contains(OTHER_DOMAIN),
                "with the domain operand the file named: " + stored);
        assertFalse(importLog().contains("Skipping series " + imported), "the row was not skipped: " + importLog());
    }

    /**
     * A re-import over an ACTIVE series whose criteria name a domain updates it. An active series is checked against
     * every activation rule, C-4 with this installation's operands included, and that check reads the domain's lazy
     * message series as well; it runs inside the service's transaction, so the row is applied and the job completes.
     */
    @Test
    void aReImportOverAnActiveSeriesScopedByADomainUpdatesIt() throws Exception {
        String seriesId = track(TestIds.series());
        Map<String, Object> byForm = querySeries(seriesId);
        byForm.put("criteria", criteria(node("domain", OTHER_DOMAIN), node("messageMainType", "NM")));
        object(as("admin").body(byForm).post(BASE + "/series"));
        activate(seriesId);
        assertEquals("ACTIVE", object(as("admin").get(BASE + "/editable-series/" + seriesId)).get("status"),
                "the series scoped by a domain is active");

        Map<String, Object> renamed = new LinkedHashMap<>(exported(seriesId).getFirst());
        List<Map<String, Object>> descs = new ArrayList<>();
        for (Object d : (List<?>) renamed.get("descs")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> desc = new LinkedHashMap<>((Map<String, Object>) d);
            desc.put("name", "da".equals(desc.get("lang")) ? "IT domæne, omdøbt" : "IT domain, renamed");
            descs.add(desc);
        }
        renamed.put("descs", descs);
        upload(List.of(renamed), DOMAIN);

        assertEquals(BatchStatus.COMPLETED, lastImport(), "the re-import over the active series completes");
        Map<String, Object> after = object(as("admin").get(BASE + "/editable-series/" + seriesId));
        Map<String, String> names = new LinkedHashMap<>();
        for (Object d : (List<?>) after.get("descs")) {
            Map<?, ?> desc = (Map<?, ?>) d;
            names.put((String) desc.get("lang"), (String) desc.get("name"));
        }
        assertEquals(Map.of("da", "IT domæne, omdøbt", "en", "IT domain, renamed"), names,
                "the active series carries the renamed descs");
        assertEquals("ACTIVE", after.get("status"), "and keeps its status");
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** A weekly, query-backed series of the NM notices in two languages. */
    private Map<String, Object> querySeries(String seriesId) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("seriesId", seriesId);
        s.put("categoryId", categoryId);
        s.put("domainId", DOMAIN);
        s.put("sortOrder", 3);
        s.put("contentMode", "GENERATED_FROM_QUERY");
        s.put("cadence", "WEEKLY");
        s.put("cutoffDefault", "RELEASE_MOMENT");
        s.put("nominalCutoffDay", "WEDNESDAY");
        s.put("nominalCutoffTime", "12:00");
        s.put("numberingScheme", "ISO_WEEK_YEAR");
        s.put("timeRelation", "PUBLISHED_IN_INTERVAL");
        s.put("aliveAtCutoff", false);
        s.put("firstIssueStartsAt", SEP_1);
        s.put("criteria", criteria(node("messageSeries", "dma-nm"), node("messageMainType", "NM")));
        s.put("availability", "SELECTED_DOMAINS");
        s.put("availableDomainIds", new ArrayList<>(List.of("niord-fa", "niord-nm")));
        s.put("languages", List.of("da", "en"));
        s.put("reportId", "standard");
        s.put("pageSize", "A5");
        s.put("pageOrientation", "PORTRAIT");
        s.put("mapThumbnails", true);
        s.put("messageSortBy", "ID");
        s.put("messageSortOrder", "ASC");
        s.put("messagePublication", "INTERNAL");
        s.put("releaseMode", "MANUAL_GATE");
        s.put("nextIssueCreation", "AUTO_ON_PUBLISH");
        s.put("reportParams", new LinkedHashMap<>(Map.of("subtitle", "Kildeserie")));
        s.put("descs", List.of(
                Map.of("lang", "da", "name", "IT kilde", "nameSuggestionPattern", "Uge ${week} ${year}",
                        "fileNamePattern", "kilde-${year}-${week}-da", "messageReferenceFormat", "Kilde ${week}"),
                Map.of("lang", "en", "name", "IT source", "nameSuggestionPattern", "Week ${week} ${year}",
                        "fileNamePattern", "source-${year}-${week}-en", "messageReferenceFormat", "Source ${week}")));
        return s;
    }

    /** An unscheduled compilation of the source's released issues, in two languages. */
    private Map<String, Object> compilationOf(String seriesId, String source) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("seriesId", seriesId);
        s.put("categoryId", categoryId);
        s.put("domainId", DOMAIN);
        s.put("kind", "UNSCHEDULED");
        s.put("contentMode", "GENERATED_FROM_QUERY");
        s.put("cadence", "NONE");
        s.put("cutoffDefault", "RELEASE_MOMENT");
        s.put("numberingScheme", "NONE");
        s.put("nextIssueCreation", "MANUAL");
        s.put("timeRelation", "COMPILED_FROM_SOURCE");
        s.put("sourceSeriesId", source);
        s.put("firstIssueStartsAt", SEP_1);
        s.put("availability", "OWNER_ONLY");
        s.put("languages", List.of("da", "en"));
        s.put("reportId", "standard");
        s.put("pageSize", "A4");
        s.put("pageOrientation", "LANDSCAPE");
        s.put("mapThumbnails", false);
        s.put("messagePublication", "NONE");
        s.put("releaseMode", "MANUAL_GATE");
        s.put("descs", List.of(
                Map.of("lang", "da", "name", "IT samling", "fileNamePattern", "samling-da"),
                Map.of("lang", "en", "name", "IT compilation", "fileNamePattern", "compilation-en")));
        return s;
    }

    @SafeVarargs
    private static Map<String, Object> criteria(Map<String, Object>... nodes) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("schemaVersion", 1);
        c.put("match", "ALL");
        c.put("criteria", new ArrayList<>(List.of(nodes)));
        return c;
    }

    private static Map<String, Object> node(String kind, String... values) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("kind", kind);
        n.put("operator", "IN");
        n.put("values", new ArrayList<>(List.of(values)));
        return n;
    }

    /** The export's rows for these series, in the export's order. */
    private List<Map<String, Object>> exported(String... seriesIds) {
        Set<String> wanted = Set.of(seriesIds);
        return array(as("admin").get(BASE + "/export")).stream()
                .filter(s -> wanted.contains((String) s.get("seriesId"))).toList();
    }

    private String upload(List<Map<String, Object>> rows, String domain) throws Exception {
        String json = JsonMapper.builder().build().writeValueAsString(rows);
        return given().auth().oauth2(accessToken("admin")).header("NiordDomain", domain)
                .multiPart("file", "series.json", json.getBytes(StandardCharsets.UTF_8), "application/json")
                .post(BASE + "/upload-series").then().statusCode(200).extract().asString();
    }

    private static Map<String, Object> withoutRowFields(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        ROW_FIELDS.forEach(out::remove);
        // The sharing list has no order column; compared as a set
        Object shared = out.get("availableDomainIds");
        if (shared instanceof List<?> l) {
            out.put("availableDomainIds", new TreeSet<>(l.stream().map(String::valueOf).toList()));
        }
        return out;
    }

    @Autowired
    JobOperator jobOperator;

    /**
     * The terminal status of the newest execution of the series import, waited for. The upload has started it by
     * the time the HTTP answer arrives, so the newest instance is the one this test started.
     */
    private BatchStatus lastImport() throws InterruptedException {
        JobInstance instance = jobOperator.getJobInstances(IMPORT_JOB, 0, 1).getFirst();
        List<JobExecution> executions = jobOperator.getJobExecutions(instance);
        long executionId = executions.stream().mapToLong(JobExecution::getExecutionId).max().orElseThrow();
        return BatchJobs.awaitCompletion(jobOperator, executionId);
    }

    private static final String IMPORT_JOB = "publication-series-import";

    @Autowired
    BatchService batchService;

    /** The log files of the newest series import, one after the other; read after {@link #lastImport()}. */
    private String importLog() throws IOException {
        long instanceId = jobOperator.getJobInstances(IMPORT_JOB, 0, 1).getFirst().getInstanceId();
        StringBuilder log = new StringBuilder();
        for (String file : batchService.getBatchJobLogFiles(instanceId)) {
            log.append(batchService.getBatchJobLogFile(instanceId, file, null)).append('\n');
        }
        return log.toString();
    }

    private void activate(String seriesId) {
        int v = intOf(object(as("admin").get(BASE + "/editable-series/" + seriesId)), "version");
        object(as("admin").body("\"ACTIVE\"").put(BASE + "/series/" + seriesId + "/status?version=" + v));
    }

    private String issue(String seriesId, long from, long to) {
        return (String) object(as("admin").body(Map.of("seriesId", seriesId, "intervalFrom", from, "intervalTo", to))
                .post(ISSUES + "/issue")).get("publicId");
    }

    private Map<String, Object> publish(String publicId, long cutoff) {
        Map<String, Object> rail = object(as("admin").get(ISSUES + "/issue/" + publicId
                + "/publish-checklist?cutoff=" + cutoff));
        List<String> acknowledged = new ArrayList<>();
        for (Map<String, Object> row : listOf(rail, "rows")) {
            if (Boolean.TRUE.equals(row.get("acknowledgeable")) && !Boolean.TRUE.equals(row.get("passed"))
                    && row.get("acknowledgeCode") != null) {
                acknowledged.add((String) row.get("acknowledgeCode"));
            }
        }
        int v = intOf(object(as("admin").get(ISSUES + "/editable-issue/" + publicId)), "version");
        Response r = as("admin").body(Map.of("acknowledgedWarnings", acknowledged, "cutoff", cutoff, "version", v))
                .put(ISSUES + "/issue/" + publicId + "/publish");
        assertEquals(200, r.statusCode(), "the publish of " + publicId + ": " + r.asString() + " rail " + rail);
        return r.jsonPath().getMap("");
    }

    private Set<String> members(String publicId) {
        Set<String> out = new TreeSet<>();
        array(as("admin").get(ISSUES + "/issue/" + publicId + "/members?lang=en"))
                .forEach(m -> out.add((String) m.get("messageUid")));
        return out;
    }
}
