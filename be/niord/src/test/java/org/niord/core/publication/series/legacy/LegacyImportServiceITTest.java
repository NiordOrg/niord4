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

package org.niord.core.publication.series.legacy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.publication.PublicationCategoryService;
import org.niord.core.publication.Publication;
import org.niord.core.publication.PublicationCategory;
import org.niord.core.publication.PublicationDesc;
import org.niord.core.publication.TestIds;
import org.niord.core.publication.vo.PublicationMainType;
import org.niord.core.publication.vo.PublicationStatus;
import org.niord.core.publication.series.ContentMode;
import org.niord.core.publication.series.PublicationSeries;
import org.niord.core.publication.series.PublicationSeriesDesc;
import org.niord.core.publication.series.SeriesCadence;
import org.niord.core.publication.series.PublicationIssue;
import org.niord.core.publication.series.SeriesStatus;
import org.niord.core.publication.series.resolve.TimeRelation;
import org.niord.core.report.FmReport;
import org.niord.model.publication.PublicationType;
import org.niord.test.ITTest;
import org.niord.test.Tx;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dry run writes nothing, and one bad row aborts the whole import.
 *
 * An integration test with a database, because the two claims being made are both
 * about the database: that S18 does not touch it, and that S19 leaves it untouched
 * when it refuses. Neither can be shown without one.
 *
 * WHY THE FIXTURE HELPERS BELOW OPEN THEIR OWN TRANSACTIONS BY HAND. They used to
 * carry a method-level {@code @Transactional} and be called by self-invocation --
 * which the previous container intercepted through a generated subclass and
 * committed. Spring's is proxy-based and a test instance is not a proxy, so the
 * same annotation on a helper of this class is a silent no-op and every
 * {@code em.persist} in it would run with no transaction at all. Each helper now
 * says which of the two it needs: {@link Tx#requiringNew()} where the rows must be
 * COMMITTED for a later service call that opens its own transaction to see them,
 * and nothing at all where the helper is only ever called from a
 * {@code @Transactional} test and should simply join it.
 */
public class LegacyImportServiceITTest extends ITTest {

    @Autowired
    LegacyImportService importService;

    @Autowired
    PublicationCategoryService categoryService;

    @PersistenceContext
    EntityManager em;

    /**
     * Removes the probe rows that were COMMITTED rather than rolled back.
     *
     * The tests that carry no transaction of their own already remove their offender in
     * a finally block. The three {@code @Transactional} ones do not, and cannot: their
     * probe template is written through {@link Tx#requiringNew()} because the importer
     * has to see it from a transaction of its own, so the test's rollback does not reach
     * it. Without this sweep every run leaves four probe publications behind in a
     * database the whole suite shares. The {@code import-probe} category is left alone --
     * it is found-or-created, one row, and the probes hold a foreign key into it.
     */
    @AfterEach
    void removeCommittedProbeRows() {
        // PORT: end (roll back) the still-open test-managed transaction first — it may hold key-share
        // locks on the probe rows, and the committed DELETE below would otherwise wait on it forever.
        if (TestTransaction.isActive()) {
            TestTransaction.flagForRollback();
            TestTransaction.end();
        }
        Tx.requiringNew().run(() -> {
            // Publications before templates: a publication holds a foreign key to the
            // template it was cut from, and the constraint is checked per row.
            for (PublicationMainType mainType
                    : List.of(PublicationMainType.PUBLICATION, PublicationMainType.TEMPLATE)) {
                em.createQuery("SELECT p FROM Publication p "
                                + "WHERE p.repoPath LIKE 'publications/test/%' AND p.mainType = :mt",
                                Publication.class)
                        .setParameter("mt", mainType)
                        .getResultList()
                        .forEach(em::remove);
                em.flush();
            }
        });
    }

    /** Row counts of everything the importer can write. */
    private Map<String, Long> census() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String entity : List.of("PublicationSeries", "PublicationIssue", "IssueMember",
                "PublicationCategory", "IssueOverride", "IssueAuditEntry")) {
            out.put(entity, em.createQuery("SELECT COUNT(e) FROM " + entity + " e", Long.class)
                    .getSingleResult());
        }
        return out;
    }

    // COMMITTED: the probe row has to be visible to dryRun()/run(), which suppress the
    // ambient transaction and open one of their own; a row written in the test's own
    // (rolled-back) transaction would be invisible to them.
    Publication legacyTemplate(String title, String filter) {
        return Tx.requiringNew().call(() -> {
            Publication p = new Publication();
            p.setPublicationId(UUID.randomUUID().toString());
            p.setMainType(PublicationMainType.TEMPLATE);
            p.setType(PublicationType.NONE);
            p.setStatus(PublicationStatus.ACTIVE);
            p.setRepoPath("publications/test/" + UUID.randomUUID());
            p.setMessageTagFilter(filter);
            // A series without a category is refused: the category decides which
            // section of the public page it lands in, and whether it lands at all.
            p.setCategory(probeCategory());

            PublicationDesc d = new PublicationDesc();
            d.setLang("en");
            d.setTitle(title);
            p.setDescs(new java.util.ArrayList<>(List.of(d)));
            d.setEntity(p);

            em.persist(p);
            em.flush();
            return p;
        });
    }

    /** A category the probe rows can belong to, created once. */
    // Joins the caller's transaction, whichever it is: legacyTemplate's own committed
    // one, or the test's for legacyPublication. The row it finds or creates is looked
    // up by categoryId, so it resolves the committed one from either.
    PublicationCategory probeCategory() {
        PublicationCategory existing = em.createQuery(
                        "SELECT c FROM PublicationCategory c WHERE c.categoryId = :id",
                        PublicationCategory.class)
                .setParameter("id", "import-probe")
                .getResultStream().findFirst().orElse(null);
        if (existing != null) {
            return existing;
        }
        PublicationCategory c = new PublicationCategory();
        c.setCategoryId("import-probe");
        c.setPriority(999);
        c.setPublish(false);
        em.persist(c);
        em.flush();
        return c;
    }

    // Joins the caller's transaction: only ever called from a @Transactional test,
    // whose rollback is what removes the row again.
    Publication legacyPublication(String title, Publication template) {
        Publication p = new Publication();
        p.setPublicationId(UUID.randomUUID().toString());
        p.setMainType(PublicationMainType.PUBLICATION);
        p.setType(PublicationType.NONE);
        p.setStatus(PublicationStatus.ACTIVE);
        p.setRepoPath("publications/test/" + UUID.randomUUID());
        p.setTemplate(em.find(Publication.class, template.getId()));
        // PORT: the probe row used to be written with NO category. Publication.category
        // is @NotNull and this schema says so, so the insert is now refused mid-flush
        // with a constraint violation naming category_id -- the previous database
        // tolerated the null. Every real legacy publication holds a category by foreign
        // key (that is why planCategories never creates one against a real estate), so
        // the probe carries the same one its template does rather than none.
        p.setCategory(probeCategory());

        PublicationDesc d = new PublicationDesc();
        d.setLang("en");
        d.setTitle(title);
        p.setDescs(new java.util.ArrayList<>(List.of(d)));
        d.setEntity(p);

        em.persist(p);
        em.flush();
        return p;
    }

    /**
     * Removes EVERYTHING the import wrote, not just this test's own rows.
     *
     * run() imports the whole estate by design, so a cleanup scoped to the two
     * probe rows leaves every other imported issue behind -- and the next run
     * then refuses with ALREADY_IMPORTED, which is the guard working correctly
     * against a mess this test made. Identified by legacyPublicationId /
     * legacyTemplateId, which only the importer sets.
     *
     * Deleted in foreign-key order: the child rows first, or the database refuses --
     * a foreign key that is not DEFERRABLE is checked per row and not per statement.
     */
    // Joins the caller's transaction: only ever called from a @Transactional test, so
    // it sees that test's own flushed rows and its rollback backs the whole lot out.
    void cleanUpImported() {
        // Detach first: a bulk DELETE bypasses the persistence context, so anything
        // still managed here would be flushed back afterwards as a stale update.
        em.flush();
        em.clear();
        // The annuals the import OPENED rather than copied first, because they
        // carry no legacyPublicationId and every delete below is scoped by one. An
        // issue left behind in a series this method goes on to delete fails the
        // series delete on its foreign key, which reads as a defect in the import
        // and is a defect in the cleanup.
        em.createQuery("DELETE FROM IssueAuditEntry a WHERE a.issue IN "
                        + "(SELECT i FROM PublicationIssue i WHERE i.legacyPublicationId IS NULL "
                        + "AND i.series.importSource IS NOT NULL)")
                .executeUpdate();
        em.createQuery("DELETE FROM PublicationIssueDesc d WHERE d.entity IN "
                        + "(SELECT i FROM PublicationIssue i WHERE i.legacyPublicationId IS NULL "
                        + "AND i.series.importSource IS NOT NULL)")
                .executeUpdate();
        em.createQuery("DELETE FROM PublicationIssue i WHERE i.legacyPublicationId IS NULL "
                        + "AND i.series.importSource IS NOT NULL")
                .executeUpdate();
        em.createQuery("DELETE FROM IssueMember m WHERE m.issue.legacyPublicationId IS NOT NULL")
                .executeUpdate();
        em.createQuery("DELETE FROM IssueAuditEntry a WHERE a.issue.legacyPublicationId IS NOT NULL")
                .executeUpdate();
        em.createQuery("DELETE FROM PublicationIssueDesc d WHERE d.entity.legacyPublicationId IS NOT NULL")
                .executeUpdate();
        em.createQuery("DELETE FROM PublicationIssue i WHERE i.legacyPublicationId IS NOT NULL")
                .executeUpdate();
        em.createQuery("DELETE FROM PublicationSeriesDesc d WHERE d.entity.legacyTemplateId IS NOT NULL")
                .executeUpdate();
        // A compiled series points at another row of the same table, and the
        // constraint is RESTRICT -- so the delete below fails or succeeds on the
        // order the database happens to walk the rows in unless the column is
        // cleared first.
        em.createQuery("UPDATE PublicationSeries s SET s.sourceSeries = NULL "
                        + "WHERE s.legacyTemplateId IS NOT NULL")
                .executeUpdate();
        em.createQuery("DELETE FROM PublicationSeries s WHERE s.legacyTemplateId IS NOT NULL")
                .executeUpdate();
        em.flush();
    }

    // COMMITTED: it removes a row legacyTemplate committed, and it is called from tests
    // that carry no transaction of their own.
    void remove(String publicationId) {
        Tx.requiringNew().run(() -> {
            em.createQuery("SELECT p FROM Publication p WHERE p.publicationId = :id", Publication.class)
                    .setParameter("id", publicationId)
                    .getResultStream().findFirst().ifPresent(em::remove);
            em.flush();
        });
    }

    /**
     * S18 leaves the database exactly as it found it.
     *
     * Asserted as a census of every table the importer can write, taken before
     * and after. The dry run never opens a write -- it is not a transaction that
     * rolls back -- so this is a check that the design held, not that the rollback
     * worked.
     */
    @Test
    public void theDryRunWritesNothing() {
        Map<String, Long> before = census();

        LegacyImportReportVo report = importService.dryRun();

        assertTrue(report.isDryRun());
        assertEquals(before, census(),
                "S18 must leave the database byte-identical; an admin asking what WOULD happen has not "
                        + "consented to anything happening");
    }

    /**
     * The dry run and the real run agree about what would happen.
     *
     * Not "the report is non-empty" -- that passes on a placeholder. Two runs
     * back to back must reach the same verdict and name the same offenders, which
     * is the only property that makes a preview worth reading. Compared as sets
     * because order is not part of the promise.
     */
    @Test
    public void theDryRunAgreesWithTheRealRunAboutWhatWouldHappen() {
        Publication offender = legacyTemplate("Agreement Probe", "msg.type == Type.NOPE");
        try {
            LegacyImportReportVo dry = importService.dryRun();
            LegacyImportReportVo real = importService.run();

            assertTrue(dry.isDryRun());
            assertFalse(real.isDryRun());
            assertEquals(dry.isWouldSucceed(), real.isWouldSucceed(),
                    "a preview that disagrees with the thing it previews is worse than no preview");
            assertEquals(
                    dry.getProblems().stream().map(x -> x.getCode() + "/" + x.getPublicationId())
                            .collect(java.util.stream.Collectors.toSet()),
                    real.getProblems().stream().map(x -> x.getCode() + "/" + x.getPublicationId())
                            .collect(java.util.stream.Collectors.toSet()),
                    "both runs must name the same offenders");
        } finally {
            remove(offender.getPublicationId());
        }
    }

    /**
     * One unknown filter aborts the WHOLE run, and nothing is written.
     *
     * The offending row is a template carrying a filter no publication in the
     * estate stores. A partial import is the failure this prevents: the rows that
     * landed would look correct, nothing would mark them as a partial set, and
     * finding the gap means comparing 1,077 rows by hand.
     */
    @Test
    public void oneUnknownFilterAbortsEverythingAndWritesNothing() {
        Publication offender = legacyTemplate("Import Abort Probe",
                "msg.status == Status.DRAFT && msg.type == Type.NONEXISTENT");
        try {
            Map<String, Long> before = census();

            LegacyImportReportVo report = importService.run();

            assertFalse(report.isWouldSucceed(), "an unknown filter must refuse the whole run");
            assertEquals(before, census(), "a refused import writes nothing at all");

            assertTrue(report.getProblems().stream()
                            .anyMatch(p -> offender.getPublicationId().equals(p.getPublicationId())),
                    "the report must name the publication it refused");
        } finally {
            remove(offender.getPublicationId());
        }
    }

    /**
     * The report names the id, the title and the verbatim filter.
     *
     * All three, because an admin has to find the row (title),
     * be certain which one it is (id), and know what to do about it (the filter,
     * spelled exactly as stored -- an abbreviated echo would send them looking
     * for a string that is not there).
     */
    @Test
    public void theReportNamesTheIdTheTitleAndTheVerbatimFilter() {
        String filter = "msg.status == Status.DRAFT && data.phase == 'nope'";
        Publication offender = legacyTemplate("Verbatim Probe", filter);
        try {
            LegacyImportReportVo report = importService.dryRun();

            LegacyImportReportVo.ProblemVo problem = report.getProblems().stream()
                    .filter(p -> offender.getPublicationId().equals(p.getPublicationId()))
                    .findFirst().orElseThrow(() -> new AssertionError("the offender was not reported"));

            assertEquals("Verbatim Probe", problem.getTitle());
            assertTrue(problem.getDetail().contains(filter),
                    "the filter must be echoed verbatim: " + problem.getDetail());
        } finally {
            remove(offender.getPublicationId());
        }
    }

    /**
     * Every offender is reported, not just the first.
     *
     * An admin fixing them one build at a time is the failure mode a full report
     * exists to prevent.
     */
    @Test
    public void everyOffenderIsReportedNotJustTheFirst() {
        Publication a = legacyTemplate("Probe A", "msg.status == Status.DRAFT");
        Publication b = legacyTemplate("Probe B", "msg.type == Type.NOPE");
        try {
            LegacyImportReportVo report = importService.dryRun();

            long named = report.getProblems().stream()
                    .filter(p -> a.getPublicationId().equals(p.getPublicationId())
                            || b.getPublicationId().equals(p.getPublicationId()))
                    .count();
            assertEquals(2, named, "both offenders must appear in one report");
        } finally {
            remove(a.getPublicationId());
            remove(b.getPublicationId());
        }
    }

    /**
     * The WRITE path: apply() actually persists a series, its issue and its category.
     *
     * Planned from a CONTROLLED pair of rows rather than the whole estate. This
     * database is shared with every other suite, and its legacy Publication rows
     * are their fixtures -- most carry no repoPath -- so an estate-wide plan over
     * it can never come back clean, and the write path would never be reached.
     * Production always plans the whole estate; that is what makes "fail rather
     * than import a partial estate" mean anything.
     */
    @Test
    @Transactional
    public void applyWritesTheSeriesItsIssueAndItsCategory() {
        // The probe template names no domain, so the series falls to the default owner
        // and the plan refuses outright when that domain is absent. The seeded estate
        // here carries the niord-client-* desks and none of the ones the rulings name.
        ensureDomain(LegacyTemplateRulings.DEFAULT_DOMAIN);
        Publication template = legacyTemplate("Write Path Probe", null);
        Publication issueRow = legacyPublication("Write Path Probe 1", template);
        try {
            LegacyImportService.Plan plan =
                    importService.planFrom(List.of(template), List.of(issueRow));

            assertTrue(plan.isClean(), "the controlled pair should plan cleanly; problems: "
                    + plan.report().getProblems().stream()
                            .map(x -> x.getCode() + " " + x.getDetail()).toList());

            importService.apply(plan);
            em.flush();

            PublicationSeries written = em.createQuery(
                            "SELECT s FROM PublicationSeries s WHERE s.legacyTemplateId = :id",
                            PublicationSeries.class)
                    .setParameter("id", template.getPublicationId())
                    .getSingleResult();
            assertEquals("write-path-probe", written.getSeriesId(),
                    "the seriesId is authored from the title, never adopted from the legacy UUID");
            assertEquals(SeriesStatus.DRAFT, written.getStatus(),
                    "an imported series is a translation, reviewed before it is active");
            assertEquals("import-probe", written.getCategory().getCategoryId(),
                    "the series must carry its category -- the column is NOT NULL and the category "
                            + "decides which section of the public page it lands in");

            // The nominal schedule S-5 and S-7 require, read off the issues just
            // imported. Without it every imported series with a cadence is correct in
            // every other respect and cannot be activated, on two fields the legacy
            // model had nothing to copy from and nobody could fill in by hand.
            if (written.getCadence() != null && written.getCadence() != SeriesCadence.NONE) {
                assertNotNull(written.getNominalCutoffTime(),
                        "a series with a cadence and no nominal time fails S-7 and cannot activate");
            }
            if (written.getCadence() == SeriesCadence.WEEKLY) {
                assertNotNull(written.getNominalCutoffDay(),
                        "a weekly series with no weekday fails S-5 and cannot activate");
            }

            PublicationIssue issue = em.createQuery(
                            "SELECT i FROM PublicationIssue i WHERE i.publicId = :id",
                            PublicationIssue.class)
                    .setParameter("id", issueRow.getPublicationId())
                    .getSingleResult();
            assertEquals(issueRow.getPublicationId(), issue.getLegacyPublicationId(),
                    "id-space continuity: the legacy id IS the publicId");
            assertEquals(issueRow.getRepoPath(), issue.getRepoPath(), "paths travel verbatim (R6)");
            assertNotNull(issue.getSnapshotTimeRelation(), "the per-issue snapshot header must be written");
            assertNotNull(issue.getCutoffStampedAt(), "the recovery cascade must have recovered a cut-off");
        } finally {
            cleanUpImported();
        }
    }

    /**
     * The conversion ruling, at the only level that proves it: after apply(),
     * the accumulated annual COMPILES the weekly.
     *
     * The ruling is the reason the conversion is an import step rather than an
     * edit on a deployed database -- so what has to hold is that a run produces
     * the compiled series, not merely that the table names it. The branch that
     * would hide a regression is silent by design: a source series the plan does
     * not carry is noted and the series imports unconverted, which is the right
     * behaviour for an estate that has no such series and indistinguishable from
     * a rename that broke the ruling.
     *
     * Planned from a CONTROLLED pair whose titles author the two ruled ids, for
     * the same reason the write-path probe above is: an estate-wide plan over a
     * shared database can never come back clean, so the write path would never be
     * reached.
     */
    @Test
    @Transactional
    public void applyConvertsTheAccumulatedAnnualIntoACompilationOfTheWeekly() {
        ensureDomain("niord-nm");
        ensureDomain(LegacyTemplateRulings.DEFAULT_DOMAIN);

        LegacyTemplateRulings.CompilationShape shape =
                LegacyTemplateRulings.compilationFor("accumulated-yearly-ntm");
        assertNotNull(shape, "the ruling this case is about is gone from the table");
        // The ruled report has to exist, or the plan refuses (the case below).
        ensureReport(shape.reportId());

        Publication weeklyTemplate = legacyTemplate("Weekly NTM", null);
        Publication annualTemplate = legacyTemplate("Accumulated Yearly NTM", null);
        Publication weeklyIssue = legacyPublication("Weekly NTM 1", weeklyTemplate);
        Publication annualIssue = legacyPublication("Accumulated Yearly NTM 1", annualTemplate);
        try {
            LegacyImportService.Plan plan = importService.planFrom(
                    List.of(weeklyTemplate, annualTemplate), List.of(weeklyIssue, annualIssue));

            assertTrue(plan.isClean(), "the controlled pair should plan cleanly; problems: "
                    + plan.report().getProblems().stream()
                            .map(x -> x.getCode() + " " + x.getDetail()).toList());

            // What the imported issue looked like before the conversion ran. The
            // ruling converts the SERIES and leaves the documents somebody
            // assembled by hand exactly as the import wrote them.
            PublicationIssue planned = plan.issues().get(annualIssue.getPublicationId());
            assertNotNull(planned, "the annual's own edition is not in the plan");
            Object provenanceBefore = planned.getMembershipProvenance();
            String repoPathBefore = planned.getRepoPath();

            importService.apply(plan);
            em.flush();

            PublicationSeries annual = seriesById("accumulated-yearly-ntm");
            assertEquals(ContentMode.GENERATED_FROM_QUERY, annual.getContentMode(),
                    "the annual is still the uploaded-document series legacy held; every future "
                            + "issue of it would be a file somebody assembles by hand");
            assertEquals(TimeRelation.COMPILED_FROM_SOURCE, annual.getTimeRelation());
            assertNotNull(annual.getSourceSeries(), "the converted series names no source at all");
            assertEquals("weekly-ntm", annual.getSourceSeries().getSeriesId(),
                    "the annual compiles the wrong series");
            assertNull(annual.getCriteria(),
                    "a compilation runs no query, and a leftover document is a second answer to "
                            + "the membership question sitting beside the one that is used");
            assertNull(annual.getAliveAtCutoff(),
                    "liveness was judged at each source issue's own cut-off; a flag here is a "
                            + "second answer S-24 refuses");
            assertEquals(shape.reportId(), annual.getReportId());
            assertEquals(shape.cutoffDefault(), annual.getCutoffDefault(),
                    "the annual closes when the year does, so the period's own end is its cut-off");
            assertEquals(shape.firstIssueStartsAt(), annual.getFirstIssueStartsAt(),
                    "the compiled chain opens where the source series' frozen rows begin to be a "
                            + "complete record of what went out");

            assertTrue(annual.isLanguageSpecific(),
                    "the annual still says one document serves every language, which was a fact "
                            + "about the PDF somebody assembled by hand; a compiled series renders "
                            + "one document per language and a reader would be handed whichever "
                            + "one rendered");
            for (String lang : annual.getLanguages()) {
                String ruled = shape.fileNamePatterns().get(lang);
                if (ruled == null) {
                    continue;
                }
                String pattern = annual.getDescs().stream()
                        .filter(d -> lang.equals(d.getLang()))
                        .map(PublicationSeriesDesc::getFileNamePattern)
                        .findFirst().orElse(null);
                assertEquals(ruled, pattern,
                        "the " + lang + " document has no file-name pattern, so it is filed under "
                                + "the issue's public id -- the same path every language writes to, "
                                + "and the last render wins the public link");
            }

            // The weekly it compiles is untouched: a compilation of a compilation
            // is refused by S-25, and the source is an ordinary series.
            PublicationSeries weekly = seriesById("weekly-ntm");
            assertNull(weekly.getSourceSeries(), "the source series was itself converted");

            PublicationIssue imported = em.createQuery(
                            "SELECT i FROM PublicationIssue i WHERE i.publicId = :id",
                            PublicationIssue.class)
                    .setParameter("id", annualIssue.getPublicationId())
                    .getSingleResult();
            assertEquals(provenanceBefore, imported.getMembershipProvenance(),
                    "the conversion re-decided an issue somebody produced by hand years ago");
            assertEquals(repoPathBefore, imported.getRepoPath(), "the document moved (R6)");
            assertEquals(0L, (long) em.createQuery(
                            "SELECT COUNT(m) FROM IssueMember m WHERE m.issue = :i", Long.class)
                    .setParameter("i", imported).getSingleResult(),
                    "the conversion resolved members for an issue that is an uploaded file");

            // A decision about a planned SERIES is recorded against its seriesId,
            // which is what a row with no legacy publication behind it has.
            List<String> codes = plan.report().getNotes().stream()
                    .filter(n -> "accumulated-yearly-ntm".equals(n.getPublicationId()))
                    .map(LegacyImportReportVo.ProblemVo::getCode)
                    .toList();
            assertTrue(codes.contains("SERIES_COMPILED_FROM_SOURCE"),
                    "the report does not say the series was converted, so an admin reading it "
                            + "cannot tell why the strip shows uncovered years: " + codes);
            assertFalse(codes.contains("COMPILATION_SOURCE_MISSING"),
                    "the ruling could not find its source series, and the import went ahead with "
                            + "the unconverted series: " + codes);
        } finally {
            cleanUpImported();
        }
    }

    /**
     * The report a compilation ruling assigns is checked at PLAN time.
     *
     * The ruling sets the report at apply time, after every other check, and
     * neither the activation nor the publish checklist asks whether a named report
     * exists. A database without it would import and activate cleanly, and the
     * series' first release would have no report to render.
     */
    @Test
    @Transactional
    public void aplanRefusesWhenTheReportACompilationRulingAssignsDoesNotExist() {
        ensureDomain("niord-nm");
        ensureDomain(LegacyTemplateRulings.DEFAULT_DOMAIN);

        LegacyTemplateRulings.CompilationShape shape =
                LegacyTemplateRulings.compilationFor("accumulated-yearly-ntm");
        assertNotNull(shape, "the ruling this case is about is gone from the table");
        assertEquals(0L, (long) em.createQuery(
                        "SELECT COUNT(r) FROM FmReport r WHERE r.reportId = :id", Long.class)
                .setParameter("id", shape.reportId()).getSingleResult(),
                "the fixture carries the built-in reports only, as a database restored from "
                        + "the old system does");

        Publication weeklyTemplate = legacyTemplate("Weekly NTM", null);
        Publication annualTemplate = legacyTemplate("Accumulated Yearly NTM", null);
        Publication weeklyIssue = legacyPublication("Weekly NTM 1", weeklyTemplate);
        Publication annualIssue = legacyPublication("Accumulated Yearly NTM 1", annualTemplate);

        LegacyImportService.Plan plan = importService.planFrom(
                List.of(weeklyTemplate, annualTemplate), List.of(weeklyIssue, annualIssue));

        List<String> refused = plan.report().getProblems().stream()
                .filter(p -> "REPORT_NOT_FOUND".equals(p.getCode()))
                .map(p -> p.getTitle() + ": " + p.getDetail())
                .toList();
        assertEquals(1, refused.size(), "the ruled report '" + shape.reportId() + "' is missing and "
                + "the plan does not say so: " + plan.report().getProblems().stream()
                        .map(p -> p.getCode() + " " + p.getDetail()).toList());
        assertTrue(refused.getFirst().startsWith("accumulated-yearly-ntm:")
                        && refused.getFirst().contains("'" + shape.reportId() + "'"),
                "the problem names neither the series nor the report: " + refused);
        assertFalse(plan.isClean());

        // A ruling whose source series this estate does not carry does not apply, so
        // its report is not asked for: the series imports unconverted.
        LegacyImportService.Plan alone = importService.planFrom(
                List.of(annualTemplate), List.of(annualIssue));
        assertTrue(alone.report().getProblems().stream()
                        .noneMatch(p -> "REPORT_NOT_FOUND".equals(p.getCode())),
                "a ruling that will not apply refused the import: " + alone.report().getProblems()
                        .stream().map(p -> p.getCode() + " " + p.getDetail()).toList());
    }

    /** A report the rulings name, created on first use. */
    // Joins the caller's transaction, as ensureDomain does, so the row is rolled back.
    void ensureReport(String reportId) {
        if (em.createQuery("SELECT COUNT(r) FROM FmReport r WHERE r.reportId = :id", Long.class)
                .setParameter("id", reportId).getSingleResult() > 0) {
            return;
        }
        FmReport r = new FmReport();
        r.setReportId(reportId);
        r.setName(reportId);
        r.setTemplatePath("/templates/messages/message-list-pdf.ftl");
        em.persist(r);
        em.flush();
    }

    /** A domain the rulings name, created on first use. */
    // Joins the caller's transaction: only ever called from a @Transactional test, so the
    // desk it invents is rolled back rather than left in a database every suite shares.
    void ensureDomain(String domainId) {
        if (em.createQuery("SELECT COUNT(d) FROM Domain d WHERE d.domainId = :id", Long.class)
                .setParameter("id", domainId).getSingleResult() > 0) {
            return;
        }
        Domain d = new Domain();
        d.setDomainId(domainId);
        d.setName(domainId);
        d.setTimeZone("Europe/Copenhagen");
        em.persist(d);
        em.flush();
    }

    private PublicationSeries seriesById(String seriesId) {
        return em.createQuery(
                        "SELECT s FROM PublicationSeries s WHERE s.seriesId = :id",
                        PublicationSeries.class)
                .setParameter("id", seriesId)
                .getSingleResult();
    }

    /**
     * A second plan over rows already imported refuses rather than colliding.
     *
     * Without this the re-run fails on the publicId unique constraint PART WAY
     * THROUGH, and the admin sees a raw constraint violation instead of a sentence
     * saying the estate is already there.
     */
    @Test
    @Transactional
    public void asecondImportRefusesInsteadOfCollidingOnTheUniqueKey() {
        // As above: the probe names no domain, so the default owner has to exist or the
        // FIRST plan refuses and there is nothing for the second one to collide with.
        ensureDomain(LegacyTemplateRulings.DEFAULT_DOMAIN);
        Publication template = legacyTemplate("Rerun Probe", null);
        Publication issueRow = legacyPublication("Rerun Probe 1", template);
        try {
            importService.apply(importService.planFrom(List.of(template), List.of(issueRow)));
            em.flush();

            LegacyImportService.Plan again =
                    importService.planFrom(List.of(template), List.of(issueRow));

            assertFalse(again.isClean(), "a re-run must refuse");
            assertTrue(again.report().getProblems().stream()
                            .anyMatch(x -> "ALREADY_IMPORTED".equals(x.getCode())),
                    "and must say the estate is already imported");
        } finally {
            cleanUpImported();
        }
    }

    // There is deliberately no "the estate in this database plans cleanly" test.
    // The legacy Publication rows here belong to other suites and most carry no
    // repoPath, so such a test would assert a property of ambient fixtures rather
    // than of the importer -- and would fail whenever another suite added a row.

    // ------------------------------------------------- the transaction budget

    /**
     * run() must carry @Transactional(NOT_SUPPORTED) -- not no annotation at all.
     *
     * The import needs about 250 seconds and the previous deployment's platform
     * default was 240, so it failed twice at 240.2s and 240.8s -- a timeout that
     * reads exactly like a data defect. The transaction is opened through a
     * separate bean with an explicit budget, because one method cannot both
     * suppress the ambient transaction and open one of its own.
     *
     * LEAVING THE METHOD BARE DOES NOT LEAVE IT UNTRANSACTED, which is what this
     * test asserted first and what cost a second go-live rehearsal. The service
     * extends a base class annotated @Transactional at class level, and Spring's
     * attribute lookup walks the superclass chain: a method with no binding of its
     * own is wrapped by the REQUIRED interceptor, invisibly. On the previous
     * deployment that ambient transaction carried the container's default budget,
     * was reaped long before the import returned, and its commit surfaced as a 500
     * over a database holding the whole archive. Nothing sets a default transaction
     * timeout here, so that exact failure cannot fire today -- which is precisely
     * why the binding has to be asserted rather than observed: introducing one
     * later would revive it silently.
     *
     * NOT_SUPPORTED is the binding that suppresses the inherited one. Removing it
     * restores the failure in a form nobody would recognise, because the code
     * would still SAY 1800 seconds.
     */
    @Test
    public void runOpensItsOwnTransactionSoItCanCarryATimeout() throws Exception {
        java.lang.reflect.Method run = LegacyImportService.class.getMethod("run");

        Transactional tx = run.getAnnotation(Transactional.class);
        assertNotNull(tx, "a bare run() inherits REQUIRED from the base class, on whatever the "
                + "ambient budget turns out to be -- see IMPORT_TIMEOUT_SECONDS");
        assertEquals(Propagation.NOT_SUPPORTED, tx.propagation(),
                "anything but NOT_SUPPORTED leaves an ambient transaction around a 784-second "
                        + "operation");

        assertTrue(LegacyImportService.IMPORT_TIMEOUT_SECONDS > 240,
                "a budget at or under the platform default is the bug this replaced");
    }

    // ------------------------------------------------ I-18: one current issue

    /**
     * The three grouped series must serve exactly one current issue each.
     *
     * This is the violation the first successful import actually produced, over
     * real rows: 4 Danish List of Lights editions, 7 NCAGS annexes and 4
     * ice-service annexes all carrying publicTo IS NULL at once. Legacy never set
     * an end date because each publication stood alone; grouping them is what
     * made four current editions of one series possible.
     *
     * Driven over the captured estate rather than a fixture pair, because the
     * shape that broke it -- same-day duplicates, and a legacy end date on some
     * rows but not others -- does not occur in anything hand-built.
     *
     * COUNTED OVER RELEASED ROWS. An unreleased issue carries no public window at
     * either end, because nothing has published it: it is on no public list and
     * serves nobody, so it is not one of the current editions this is about.
     * Counting it makes every series with a next edition in preparation read as a
     * fork, which is every healthy cadenced series. The publish path states the
     * invariant the same way -- it counts open-ended windows among PUBLISHED rows.
     */
    @Test
    public void everyImportedSeriesServesExactlyOneCurrentIssue() {
        LegacyImportService.Plan plan = importService.planFrom(
                LegacyEstateFixture.templates(), LegacyEstateFixture.publications());

        Map<String, Integer> open = new java.util.LinkedHashMap<>();
        for (PublicationIssue issue : plan.issues().values()) {
            if (issue.getStatus() == org.niord.core.publication.series.IssueStatus.OPEN) {
                continue;
            }
            if (issue.getPublicTo() == null && issue.getSeries() != null) {
                open.merge(issue.getSeries().getSeriesId(), 1, Integer::sum);
            }
        }

        List<String> forking = open.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(e -> e.getKey() + " has " + e.getValue())
                .toList();

        assertTrue(forking.isEmpty(),
                "these series serve more than one current issue: " + forking);
    }

    /**
     * A real legacy end date is never overwritten.
     *
     * The 2017 NCAGS edition ended on 23 December and the next opened on 1
     * January. That nine-day gap is recorded data, not an artefact to normalise
     * away, and the ruling is explicit that chaining fills in only what legacy
     * left empty (Rasmus, 2026-08-24).
     *
     * OVER RELEASED ROWS. An unreleased row loses its window WHOLE -- both ends,
     * and its start with them -- because the window it carries is the one its
     * edition is expected to occupy rather than one it ever occupied; see
     * UnreleasedIssueWindowTest. That is a different rule from this one, which is
     * about the supersession pass never writing over an end that is data. Keeping
     * such a row here would let this test assert half of the other rule's opposite.
     */
    @Test
    public void chainingFillsGapsAndNeverOverwritesARecordedEndDate() {
        LegacyImportService.Plan plan = importService.planFrom(
                LegacyEstateFixture.templates(), LegacyEstateFixture.publications());

        for (Publication legacy : LegacyEstateFixture.publications()) {
            if (legacy.getPublishDateTo() == null) {
                continue;
            }
            PublicationIssue issue = plan.issues().get(legacy.getPublicationId());
            if (issue == null
                    || issue.getStatus() == org.niord.core.publication.series.IssueStatus.OPEN) {
                continue;
            }
            assertEquals(legacy.getPublishDateTo(), issue.getPublicTo(),
                    "publication " + legacy.getPublicationId() + " had a recorded end date");
        }

        // The 2017 NCAGS annex, named because it is the row the ruling turned on.
        PublicationIssue y2017 = plan.issues().get("5c05b168-7045-4f65-b3c4-9217cd319bc2");
        assertNotNull(y2017);
        assertNotNull(y2017.getPublicTo());
        PublicationIssue y2018 = plan.issues().get("5219c871-d627-4c27-bf9e-d6b263bc47f1");
        assertTrue(y2017.getPublicTo().before(y2018.getPublicFrom()),
                "the nine-day gap between the 2017 and 2018 editions is real and survives");
    }

    /**
     * Of two annexes released the same day, the one legacy marks ACTIVE stays open.
     *
     * Two NCAGS rows share 2026-01-07. Ordering them by publicationId would leave
     * c8d4c4b5 open, which is the INACTIVE one; ordering by the legacy updated
     * stamp leaves 1037bb70 open, which is the ACTIVE one. The middle sort key is
     * doing real work here, and this is the row that proves it.
     */
    @Test
    public void theSameDayTieIsBrokenTowardsTheEditionLegacyStillCallsActive() {
        LegacyImportService.Plan plan = importService.planFrom(
                LegacyEstateFixture.templates(), LegacyEstateFixture.publications());

        PublicationIssue active = plan.issues().get("1037bb70-8c08-4346-b483-ef3027bdb29b");
        PublicationIssue superseded = plan.issues().get("c8d4c4b5-ea2e-4525-9d9f-d638e371c135");

        assertNotNull(active);
        assertNotNull(superseded);
        assertNull(active.getPublicTo(), "the ACTIVE 2026 edition is the current one");
        assertNotNull(superseded.getPublicTo(),
                "the INACTIVE edition released the same day is closed, not left current");
    }

    // ----------------------------------------------------------- the categories

    /**
     * All five live category ids round-trip, carrying their real flags.
     *
     * Two of the five are publish = false and their four publications must stay
     * off /public/v1, so the flags are the point of this step -- the ids alone
     * would be satisfied by five bare rows that hide 1,085 publications.
     *
     * Note what "created" means in each setting, and that the flag is read from
     * wherever the id ended up. Against the DEPLOYED system the report says
     * categoriesCreated: 0, because a legacy publication holds its category by
     * foreign key and findByCategoryId resolves the existing row. THIS database is
     * the third case and the one this used to get wrong: the publications are
     * deserialised fixtures, but the seeded estate already carries four of the five
     * ids, so exactly one is planned for creation and the other four resolve. That
     * is the planner behaving correctly, not a category going missing -- so each id
     * is looked up in the plan FIRST and in the database SECOND, and the flag
     * asserted on whichever answered. Reading only categoriesToCreate asserts a
     * property of the seed rather than of the import.
     */
    @Test
    public void allFiveLiveCategoriesRoundTripWithTheirFlags() {
        LegacyImportService.Plan plan = importService.planFrom(
                LegacyEstateFixture.templates(), LegacyEstateFixture.publications());

        assertEquals(5, plan.report().getCategoriesSeen(),
                "the estate references exactly five categories");

        Map<String, Boolean> publishById = new java.util.LinkedHashMap<>();
        for (String id : List.of("dk-dma-internal-publications", "dk-external-publications",
                "dk-dma-weekly-nm-publications", "dk-dma-publications", "dk-dma-nm-annex")) {
            PublicationCategory planned = plan.categoriesToCreate().get(id);
            PublicationCategory carried =
                    planned != null ? planned : categoryService.findByCategoryId(id);
            assertNotNull(carried, id + " is neither planned for creation nor already a row here, "
                    + "so the estate's category would reach the import as nothing at all");
            publishById.put(id, carried.isPublish());
        }

        assertEquals(5, publishById.size());
        assertEquals(Boolean.FALSE, publishById.get("dk-dma-internal-publications"));
        assertEquals(Boolean.FALSE, publishById.get("dk-external-publications"));
        assertEquals(Boolean.TRUE, publishById.get("dk-dma-weekly-nm-publications"));
        assertEquals(Boolean.TRUE, publishById.get("dk-dma-publications"));
        assertEquals(Boolean.TRUE, publishById.get("dk-dma-nm-annex"));

        assertEquals(2, publishById.values().stream().filter(v -> !v).count(),
                "exactly two categories are withheld from the public site");
    }

    /**
     * A conjured category copies publish and priority rather than defaulting them.
     *
     * A bare PublicationCategory defaults publish to FALSE. Two of the five live
     * categories really are false, so defaulting would be right by accident for
     * those two and silently wrong for the other three -- and "silently" is the
     * whole problem: the failure is 1,085 publications missing from the public
     * site, with nothing anywhere saying why.
     *
     * Driven through apply() rather than by reading the planner, because the bug
     * this replaces was in apply(): planCategories was correct and the doc comment
     * claimed the copy happened, while the code built a bare entity.
     */
    @Test
    public void aConjuredCategoryKeepsThePublishFlagItWasSeenWith() {
        String categoryId = TestIds.category();

        PublicationCategory source = new PublicationCategory();
        source.setCategoryId(categoryId);
        source.setPriority(77);
        source.setPublish(true);

        LegacyImportService.Plan plan = new LegacyImportService.Plan();
        plan.categoriesToCreate().put(categoryId, source);
        importService.apply(plan);

        PublicationCategory created = categoryService.findByCategoryId(categoryId);
        assertNotNull(created, "the category was created");
        assertTrue(created.isPublish(),
                "publish was seen as true and a bare entity would have defaulted it to false");
        assertEquals(77, created.getPriority(), "priority travels verbatim too");
    }
}
