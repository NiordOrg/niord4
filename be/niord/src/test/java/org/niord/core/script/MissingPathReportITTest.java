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
package org.niord.core.script;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.restassured.specification.RequestSpecification;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.niord.core.bootstrap.BootstrapMarker;
import org.niord.core.publication.PublicationCategory;
import org.niord.core.publication.TestIds;
import org.niord.core.publication.series.ContentMode;
import org.niord.core.publication.series.IssueStatus;
import org.niord.core.publication.series.NextIssueCreation;
import org.niord.core.publication.series.NumberingScheme;
import org.niord.core.publication.series.PublicationIssue;
import org.niord.core.publication.series.PublicationSeries;
import org.niord.core.publication.series.ReleaseMode;
import org.niord.core.publication.series.SeriesCadence;
import org.niord.core.publication.series.SeriesStatus;
import org.niord.core.publication.series.TestOwnerDomain;
import org.niord.core.publication.series.resolve.TimeRelation;
import org.niord.core.publication.vo.MessagePublication;
import org.niord.core.script.MissingPathReport.Entry;
import org.niord.core.script.MissingPathReport.Kind;
import org.niord.test.ITTest;
import org.niord.test.ReportFixtures;
import org.niord.test.Tx;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The missing-path report (docs/code-data-config-plan.md §6.6), on the fixture, a database bootstrapped with the base
 * product's archive and loaded with the sample, which misses nothing: what it lists, what names each entry, that the
 * generated read-only SQL of the release notes gives the same answer as the Java, that only a sysadmin reads it,
 * that it writes nothing, and that a report that cannot be made never stops a start. A test that needs an entry in
 * a committed report plants one of its own ({@link #plantAMissingPath()}).
 */
class MissingPathReportITTest extends ITTest {

    private static final String NW_DOMAIN = "niord-nw";
    private static final String MISSING_PATHS = "/rest/script-resources/missing-paths";

    @Autowired
    MissingPathReport missingPathReport;

    @Autowired
    ScriptResourceService resourceService;

    @Autowired
    BootstrapMarker marker;

    @PersistenceContext
    EntityManager em;

    private static RequestSpecification as(String token) {
        return given().auth().oauth2(token).header("NiordDomain", NW_DOMAIN);
    }

    private static Map<String, Entry> byPath(List<Entry> entries) {
        return entries.stream().collect(Collectors.toMap(Entry::path, Function.identity()));
    }

    /** The base product's paths: what the bootstrap and the release files carry, and what the code names */
    private static Set<String> baseProductPaths() {
        Set<String> paths = new HashSet<>(MissingPathReport.expectedPaths());
        paths.addAll(RequiredTemplates.PATHS);
        return paths;
    }

    @Test
    @Transactional
    void theFixtureMissesNothing() {
        // As on a deployment bootstrapped with the base product's archive and loaded with the sample: the sample
        // carries the two Danish scripts its first mailing-list trigger names (until C4b the IT seed did not, SEED1)
        assertEquals(List.of(), missingPathReport.report());
        assertTrue(missingPathReport.report(MissingPathReport.expectedPaths()).stream().map(Entry::path)
                        .noneMatch(baseProductPaths()::contains),
                "nothing of the base product is missing");
    }

    @Test
    @Transactional
    void everyKindOfNameIsReportedWithWhatNamesIt() {
        // A required row deleted
        ScriptResource support = resourceService.findByPath("templates/messages/message-support.ftl");
        assertNotNull(support);
        resourceService.deleteScriptResource(support.getId());
        // A category naming an absent path, with the leading slash the code also writes
        em.createNativeQuery("INSERT INTO public.\"Category_scriptResourcePaths\" "
                + "(\"Category_id\", \"scriptResourcePaths\", \"indexNo\") VALUES (2, '/templates/tmpl/it-absent.ftl', 98), "
                + "(2, 'templates/it/mpr/includer.ftl', 99)").executeUpdate();
        // Includes: a sibling, one folder up, from the root, and one that is not literal
        create("templates/it/mpr/includer.ftl", """
                <#include "it-sibling.ftl"/>
                <#include '../it-up.ftl'>
                <#include "/templates/it/it-root.ftl">
                <#include "./it-dot.ftl">
                <#include "${name}.ftl">
                <#include "includer.ftl">
                """);
        create("templates/it/mpr/loader.js", "load('niord:templates/it/mpr/absent.js'); load(\"niord:templates/it/mpr/loader.js\");");
        // A built-in report deleted
        em.createNativeQuery("DELETE FROM public.\"FmReport\" WHERE \"reportId\" = 'draft'").executeUpdate();
        em.flush();

        List<String> expected = List.of("templates/it/mpr/includer_da.ftl", "templates/it/mpr/unnamed_da.ftl");
        List<Entry> report = missingPathReport.report(expected);
        Map<String, Entry> missing = byPath(report);

        assertNamedBy(missing, "templates/messages/message-support.ftl", MissingPathReport.CODE,
                "include in templates/messages/message-list-pdf.ftl", "include in templates/messages/message-mail.ftl");
        assertNamedBy(missing, "templates/tmpl/it-absent.ftl", "Category 2");
        assertNamedBy(missing, "templates/it/mpr/it-sibling.ftl", "include in templates/it/mpr/includer.ftl");
        assertNamedBy(missing, "templates/it/it-up.ftl", "include in templates/it/mpr/includer.ftl");
        assertNamedBy(missing, "templates/it/it-root.ftl", "include in templates/it/mpr/includer.ftl");
        assertNamedBy(missing, "templates/it/mpr/it-dot.ftl", "include in templates/it/mpr/includer.ftl");
        assertNamedBy(missing, "templates/it/mpr/absent.js", "load in templates/it/mpr/loader.js");
        assertNamedBy(missing, "templates/it/mpr/includer_da.ftl", "variant of templates/it/mpr/includer.ftl");
        assertNull(missing.get("templates/it/mpr/unnamed_da.ftl"), "a variant whose base nothing names");
        assertNull(missing.get("templates/it/mpr/includer.ftl"), "a named path with a row");
        assertNull(missing.get("templates/it/mpr/loader.js"), "a named path with a row");
        assertTrue(missing.keySet().stream().noneMatch(p -> p.contains("${")), "an interpolated include is not literal");
        Entry draft = missing.get("draft");
        assertNotNull(draft, "a missing built-in report");
        assertEquals(Kind.REPORT, draft.kind());
        assertEquals(List.of(MissingPathReport.BUILT_IN_REPORT), draft.namedBy());

        // The release notes' SQL gives the same answer, in the same order
        assertEquals(asRows(report), runSql(expected));
    }

    /**
     * A report id a publication series or issue names is a missing entry when it has no row, as a built-in one is: a
     * database restored from another system can name a report it lacks, and a release of that series fails.
     */
    @Test
    @Transactional
    void aReportASeriesOrIssueNamesIsReportedWhenItHasNoRow() {
        PublicationSeries series = series("it-mpr-series-report");
        PublicationIssue issue = issue(series, "it-mpr-issue-report");
        // Named twice: by an issue of its own and by a series; one entry lists both
        PublicationSeries other = series("it-mpr-issue-report");
        // A report that has its row is not reported
        series("standard");
        em.flush();

        List<String> expected = MissingPathReport.expectedPaths();
        List<Entry> report = missingPathReport.report(expected);
        Map<String, Entry> missing = byPath(report);

        Entry bySeries = missing.get("it-mpr-series-report");
        assertNotNull(bySeries, "a report a series names: " + missing.keySet());
        assertEquals(Kind.REPORT, bySeries.kind());
        assertEquals(List.of(MissingPathReport.SERIES + " " + series.getSeriesId()), bySeries.namedBy());
        Entry byIssue = missing.get("it-mpr-issue-report");
        assertNotNull(byIssue, "a report an issue names: " + missing.keySet());
        assertEquals(Kind.REPORT, byIssue.kind());
        assertEquals(List.of(MissingPathReport.ISSUE + " " + issue.getPublicId(),
                MissingPathReport.SERIES + " " + other.getSeriesId()), byIssue.namedBy());
        assertNull(missing.get("standard"), "a named report with a row");

        // The release notes' SQL gives the same answer, in the same order
        assertEquals(asRows(report), runSql(expected));
    }

    private PublicationSeries series(String reportId) {
        PublicationCategory c = new PublicationCategory();
        c.setCategoryId(TestIds.category());
        c.setPriority(100);
        c.setPublish(true);
        em.persist(c);

        PublicationSeries s = new PublicationSeries();
        s.setSeriesId(TestIds.series());
        s.setStatus(SeriesStatus.DRAFT);
        s.setContentMode(ContentMode.GENERATED_FROM_QUERY);
        s.setReportId(reportId);
        s.setCadence(SeriesCadence.WEEKLY);
        s.setNumberingScheme(NumberingScheme.ISO_WEEK_YEAR);
        s.setTimeRelation(TimeRelation.PUBLISHED_IN_INTERVAL);
        s.setMessagePublication(MessagePublication.NONE);
        s.setReleaseMode(ReleaseMode.MANUAL_GATE);
        s.setNextIssueCreation(NextIssueCreation.MANUAL);
        s.setCategory(c);
        s.setDomain(TestOwnerDomain.of(em));
        s.getLanguages().add("en");
        s.createDesc("en").setName("Missing-path report series");
        em.persist(s);
        return s;
    }

    private PublicationIssue issue(PublicationSeries s, String reportId) {
        PublicationIssue i = new PublicationIssue();
        i.setSeries(s);
        i.setPublicId(UUID.randomUUID().toString());
        i.setRepoPath("publications/" + i.getPublicId());
        i.setStatus(IssueStatus.OPEN);
        i.setReportId(reportId);
        i.createDesc("en").setName("Missing-path report issue");
        em.persist(i);
        return i;
    }

    @Test
    @Transactional
    void theSqlGivesTheJavasAnswerOnTheSeededDatabase() {
        List<String> expected = MissingPathReport.expectedPaths();
        assertEquals(asRows(missingPathReport.report(expected)), runSql(expected));
    }

    @Test
    void includesResolveAsFreeMarkerResolvesThem() {
        assertEquals("templates/a/b.ftl", MissingPathReport.resolve("templates/a/x.ftl", "b.ftl"));
        assertEquals("templates/b.ftl", MissingPathReport.resolve("templates/a/x.ftl", "../b.ftl"));
        assertEquals("b.ftl", MissingPathReport.resolve("templates/a/x.ftl", "../../b.ftl"));
        assertEquals("../b.ftl", MissingPathReport.resolve("templates/x.ftl", "../../b.ftl"));
        assertEquals("templates/a/b.ftl", MissingPathReport.resolve("templates/a/x.ftl", "./b.ftl"));
        assertEquals("/templates/b.ftl", MissingPathReport.resolve("templates/a/x.ftl", "/templates/b.ftl"));
    }

    @Test
    void theEndpointAnswersASysadminOnlyAndTheReportWritesNothing() {
        String absent = plantAMissingPath();
        try {
            Map<String, Long> before = counts();

            as(accessToken()).when().get(MISSING_PATHS)
                    .then().statusCode(200)
                    .body("path", hasItem(absent))
                    .body("find { it.path == '" + absent + "' }.kind", is("TEMPLATE"));
            as(accessToken("admin")).when().get(MISSING_PATHS).then().statusCode(403);
            given().when().get(MISSING_PATHS).then().statusCode(401);
            missingPathReport.report();
            missingPathReport.logAtStart();

            assertEquals(before, counts(), "the report writes no row");
        } finally {
            removeTheMissingPath(absent);
        }
    }

    /**
     * What the start writes: on the seeded database ({@code existing}) one warning naming the missing paths and the
     * endpoint; while a bootstrap is still to come ({@code pending}, {@code in-progress}, {@code failed}) nothing,
     * since the bootstrap brings the rows. The marker is set and restored in
     * committed transactions, as the report reads it in one of its own, and so is the planted missing path.
     */
    @Test
    void theStartLogsTheMissingPathsOnlyOnADatabaseWithItsData() {
        Logger logger = (Logger) LoggerFactory.getLogger(MissingPathReport.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        String absent = plantAMissingPath();
        try {
            missingPathReport.logAtStart();
            List<ILoggingEvent> warnings = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
            assertEquals(1, warnings.size(), appender.list.toString());
            String text = warnings.getFirst().getFormattedMessage();
            for (String named : List.of(absent, "GET /rest/script-resources/missing-paths")) {
                assertTrue(text.contains(named), "the warning names " + named + ": " + text);
            }

            String seeded = marker.read().orElseThrow().state();
            try {
                for (String state : List.of(BootstrapMarker.PENDING, BootstrapMarker.IN_PROGRESS,
                        BootstrapMarker.FAILED)) {
                    setMarker(state);
                    appender.list.clear();
                    missingPathReport.logAtStart();
                    assertEquals(List.of(), appender.list, "nothing is logged while the marker reads " + state);
                }
            } finally {
                setMarker(seeded);
            }
        } finally {
            removeTheMissingPath(absent);
            logger.detachAppender(appender);
        }
    }

    /**
     * A template path no row has, named by the fixture's category 2 in a committed row: the report's one entry, since
     * the fixture misses nothing. {@link #removeTheMissingPath} takes the row away again.
     */
    private String plantAMissingPath() {
        String absent = "templates/it/mpr-absent-" + System.nanoTime() + ".ftl";
        ReportFixtures.plantCategoryPath(em, absent);
        return absent;
    }

    private void removeTheMissingPath(String absent) {
        ReportFixtures.removeCategoryPath(em, absent);
    }

    private void setMarker(String state) {
        Tx.requiringNew().run(() -> em.createNativeQuery(
                        "UPDATE public.\"BootstrapState\" SET state = :state WHERE id = 1")
                .setParameter("state", state).executeUpdate());
    }

    @Test
    void aReportThatCannotBeMadeNeverStopsAStart() {
        assertEquals(BootstrapMarker.EXISTING, marker.read().orElseThrow().state(), "the seeded database's marker");
        MissingPathReport failing = new MissingPathReport(marker) {
            @Override
            public List<Entry> report() {
                throw new IllegalStateException("it: the report cannot be made");
            }
        };

        assertDoesNotThrow(failing::logAtStart);
    }

    private void create(String path, String content) {
        ScriptResource resource = new ScriptResource();
        resource.setPath(path);
        resource.setContent(content);
        resourceService.createScriptResource(resource);
    }

    private static void assertNamedBy(Map<String, Entry> missing, String path, String... namedBy) {
        Entry entry = missing.get(path);
        assertNotNull(entry, path + " is reported: " + missing.keySet());
        assertEquals(Kind.TEMPLATE, entry.kind());
        for (String by : namedBy) {
            assertTrue(entry.namedBy().contains(by), path + " is named by " + by + ": " + entry.namedBy());
        }
    }

    /** The entries as the SQL's rows: kind, path and the names joined with "; " */
    private static List<List<String>> asRows(Collection<Entry> entries) {
        return entries.stream()
                .map(e -> List.of(e.kind().name(), e.path(), String.join("; ", e.namedBy())))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<List<String>> runSql(List<String> expected) {
        List<Object[]> rows = em.createNativeQuery(MissingPathReport.sql(expected)).getResultList();
        return rows.stream().map(r -> List.of((String) r[0], (String) r[1], (String) r[2])).toList();
    }

    private Map<String, Long> counts() {
        return Tx.requiringNew().call(() -> Map.of(
                "ScriptResource", count("ScriptResource"),
                "ScriptResourceHistory", count("ScriptResourceHistory"),
                "FmReport", count("FmReport")));
    }

    private long count(String table) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM public.\"" + table + "\"").getSingleResult())
                .longValue();
    }
}
