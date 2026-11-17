/*
 * Copyright 2017 Danish Maritime Authority.
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
package org.niord.core.report;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainService;
import org.niord.core.web.NiordWebException;
import org.niord.test.ITTest;
import org.niord.test.ReportFixtures;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link FmReportService}, exercised against the seeded MySQL container of the
 * {@link ITTest} infrastructure.
 *
 * <p>The fixture holds the base product's 3 reports: the public {@code standard} report (sortOrder 0, no domains),
 * the public {@code draft} report (properties carry {@code draft=true}) and the {@code public} report. Before each
 * test {@link ReportFixtures} adds two of DK's domain-linked reports, {@code nm-report} and {@code nm-tp-report}
 * (linked to {@code niord-nm}), which the IT seed carried until C4b, with three more ({@code fa-list},
 * {@code nm-almanac}, {@code nm-annex}) that no test names. None of these reports are
 * linked to the {@code niord-nw} domain, so that domain only sees the public reports until a domain-specific
 * report is created — which test {@link #getReportsFiltersByCurrentDomain()} relies on.
 *
 * <p>The interesting business logic is the current-domain seam in {@link FmReportService#getReports()}: it always
 * returns the public reports and, when a current domain is set on the thread, additionally returns that domain's
 * reports. The seam is driven through {@link DomainService#setDomainForCurrentThread(String)} /
 * {@link DomainService#removeDomainForCurrentThread()}; the thread-local is cleared after every test in
 * {@link #clearCurrentDomain()} so tests stay independent.
 *
 * <p>Mutating tests ({@code createReport}/{@code updateReport}/{@code deleteReport}) are {@code @Transactional(REQUIRED)}
 * and join the {@link TestTransaction}, so every write rolls back and the shared seed database stays pristine.
 *
 * <p>This is a framework-coupled test — see docs/framework-coupled-tests.md.
 */
class FmReportServiceITTest extends ITTest {

    static final String NW_DOMAIN = "niord-nw";

    @Autowired
    FmReportService fmReportService;

    @Autowired
    DomainService domainService;

    @PersistenceContext
    EntityManager em;

    /** DK's two domain-linked reports, which the base product's bootstrap and the sample do not carry */
    @BeforeEach
    void ensureTheNmReports() {
        ReportFixtures.ensure(fmReportService, ReportFixtures.NM_REPORT, ReportFixtures.NM_TP_REPORT);
    }

    /** Guarantee the current-domain thread-local never leaks between tests, even on failure. */
    @AfterEach
    void clearCurrentDomain() {
        domainService.removeDomainForCurrentThread();
    }

    // ------------------------------------------------------------------------------------------- //
    // Helpers
    // ------------------------------------------------------------------------------------------- //

    /** A unique report id so create/update/delete tests never collide with the seed or each other. */
    private static String uniqueReportId() {
        return "it-rpt-" + UUID.randomUUID();
    }

    /** Builds a detached template report (no domains) ready to be passed to createReport. */
    private static FmReport templateReport(String reportId) {
        FmReport report = new FmReport();
        report.setReportId(reportId);
        report.setName("IT Report");
        report.setSortOrder(500);
        report.setTemplatePath("templates/messages/it-report.ftl");
        return report;
    }

    private static List<String> reportIds(List<FmReport> reports) {
        return reports.stream().map(FmReport::getReportId).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------------------------------- //
    // (1) Standard report
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getStandardReportReturnsSeededPublicStandard() {
        // @Transactional keeps a session open so the lazy @ManyToMany 'domains' collection can be read.
        FmReport report = fmReportService.getStandardReport();

        assertEquals("standard", report.getReportId(), "the standard report must have reportId 'standard'");
        assertEquals("Standard", report.getName(), "the seeded standard report is named 'Standard'");
        assertEquals(Integer.valueOf(0), report.getSortOrder(), "the standard report sorts first (sortOrder 0)");
        assertTrue(report.getDomains().isEmpty(), "the standard report is public, so it has no linked domains");
    }

    // ------------------------------------------------------------------------------------------- //
    // (2) Draft report
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getDraftReportCarriesDraftProperty() {
        FmReport report = fmReportService.getDraftReport();

        assertEquals("draft", report.getReportId(), "the draft report must have reportId 'draft'");
        assertEquals(Boolean.TRUE, report.getProperties().get("draft"),
                "the seeded draft report must carry the property draft=true");
    }

    @Test
    @Transactional
    void thePublicReportIsTheStandardTemplateWithoutItsFrontPage() {
        // The base product's bootstrap creates it, so the fixture holds it
        FmReport report = fmReportService.getPublicReport();

        assertEquals(FmReportService.PUBLIC_REPORT_ID, report.getReportId());
        assertTrue(report.isPersisted(), "the report must exist before the public site names it");
        assertEquals(fmReportService.getStandardReport().getTemplatePath(), report.getTemplatePath(),
                "the public report prints the messages as the standard report does");
        assertEquals(Boolean.FALSE, report.getProperties().get("frontPage"),
                "the front page - heading, search criteria, count and contents - is the editors'");
        assertTrue(report.getDomains().isEmpty(), "an anonymous caller has no domain, so the report has none");
        assertTrue(reportIds(fmReportService.getReports()).contains(FmReportService.PUBLIC_REPORT_ID),
                "with no current domain the report is listed among the public ones");
    }

    // ------------------------------------------------------------------------------------------- //
    // (3) findByReportId
    // ------------------------------------------------------------------------------------------- //

    @Test
    void findByReportIdResolvesKnownAndReturnsNullForUnknown() {
        FmReport standard = fmReportService.findByReportId("standard");
        assertNotNull(standard, "findByReportId must resolve the seeded 'standard' report");
        assertEquals("standard", standard.getReportId());

        assertNull(fmReportService.findByReportId("no-such-" + UUID.randomUUID()),
                "findByReportId must return null when no report has the given id");
    }

    // ------------------------------------------------------------------------------------------- //
    // (4) getReport fallback behaviour
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getReportFallsBackToStandardForBlankOrUnknownIds() {
        assertEquals("standard", fmReportService.getReport("").getReportId(),
                "a blank report id must fall back to the standard report");
        assertEquals("standard", fmReportService.getReport(null).getReportId(),
                "a null report id must fall back to the standard report");
        assertEquals("standard", fmReportService.getReport("ghost-" + UUID.randomUUID()).getReportId(),
                "an unknown report id must fall back to the standard report");

        assertEquals("draft", fmReportService.getReport("draft").getReportId(),
                "a known report id must resolve to that exact report");
    }

    // ------------------------------------------------------------------------------------------- //
    // (5) getAllReports
    // ------------------------------------------------------------------------------------------- //

    @Test
    void getAllReportsReturnsEverySeededReport() {
        List<FmReport> all = fmReportService.getAllReports();

        // The fixture's 3 (standard, draft, public) and ReportFixtures' 2 (nm-report, nm-tp-report): at least 5
        assertTrue(all.size() >= 5, "the fixture and ReportFixtures give at least 5 reports, was: " + all.size());
        List<String> ids = reportIds(all);
        assertTrue(ids.contains(FmReportService.PUBLIC_REPORT_ID), "getAllReports must include the 'public' report");
        assertTrue(ids.contains("standard"), "getAllReports must include the public 'standard' report");
        assertTrue(ids.contains("draft"), "getAllReports must include the public 'draft' report");
        assertTrue(ids.contains("nm-report"), "getAllReports must include the domain-linked 'nm-report'");
    }

    // ------------------------------------------------------------------------------------------- //
    // (6) Domain filtering - the key business logic
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void getReportsFiltersByCurrentDomain() {
        // Create a report linked to the niord-nw domain. createReport substitutes the template
        // domain with the persisted one via domainService.persistedDomains.
        String reportId = uniqueReportId();
        FmReport template = templateReport(reportId);
        Domain nwTemplate = new Domain();
        nwTemplate.setDomainId(NW_DOMAIN);
        template.setDomains(new java.util.ArrayList<>(List.of(nwTemplate)));

        FmReport created = fmReportService.createReport(template);
        assertEquals(1, created.getDomains().size(),
                "createReport must resolve the template domain to the single persisted niord-nw domain");
        assertEquals(NW_DOMAIN, created.getDomains().get(0).getDomainId());
        em.flush();

        // With NO current domain set, getReports returns only public reports - never the nw-only report.
        List<String> withoutDomain = reportIds(fmReportService.getReports());
        assertTrue(withoutDomain.contains("standard"),
                "public reports must be visible with no current domain");
        assertTrue(withoutDomain.contains("draft"),
                "public reports must be visible with no current domain");
        assertFalse(withoutDomain.contains(reportId),
                "a domain-only report must NOT be visible without the matching current domain");

        // Once niord-nw is the current domain, its report joins the public reports.
        domainService.setDomainForCurrentThread(NW_DOMAIN);
        try {
            List<String> withDomain = reportIds(fmReportService.getReports());
            assertTrue(withDomain.contains(reportId),
                    "the nw-linked report must become visible when niord-nw is the current domain");
            assertTrue(withDomain.contains("standard"),
                    "public reports stay visible when a current domain is set");
        } finally {
            domainService.removeDomainForCurrentThread();
        }
    }

    // ------------------------------------------------------------------------------------------- //
    // (7) createReport duplicate id
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void createReportRejectsDuplicateReportId() {
        FmReport dup = templateReport("standard"); // 'standard' already exists in the seed

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> fmReportService.createReport(dup));
        assertTrue(ex.getMessage().toLowerCase().contains("duplicate"),
                "creating a report with an existing id must fail mentioning the duplicate, was: " + ex.getMessage());
    }

    // ------------------------------------------------------------------------------------------- //
    // (8) updateReport
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void updateReportReplacesScalarsPropertiesAndParams() {
        // Create a report carrying an old property and an old param.
        String reportId = uniqueReportId();
        FmReport template = templateReport(reportId);
        template.getProperties().put("oldProp", "oldValue");
        template.getParams().put("oldParam", "oldParamValue");
        FmReport created = fmReportService.createReport(template);
        Integer id = created.getId();
        em.flush();
        em.clear();

        // Update with new scalars and entirely new property/param maps.
        FmReport update = new FmReport();
        update.setReportId(reportId);
        update.setName("Renamed Report");
        update.setSortOrder(777);
        update.setTemplatePath("templates/messages/renamed.ftl");
        update.getProperties().put("newProp", "newValue");
        update.getParams().put("newParam", "newParamValue");

        fmReportService.updateReport(update);
        em.flush();
        em.clear();

        FmReport reloaded = em.find(FmReport.class, id);
        assertNotNull(reloaded, "the updated report row must still exist");
        assertEquals("Renamed Report", reloaded.getName(), "updateReport must overwrite the name");
        assertEquals(Integer.valueOf(777), reloaded.getSortOrder(), "updateReport must overwrite the sortOrder");
        assertEquals("templates/messages/renamed.ftl", reloaded.getTemplatePath(),
                "updateReport must overwrite the templatePath");

        assertEquals("newValue", reloaded.getProperties().get("newProp"),
                "updateReport must persist the new property");
        assertFalse(reloaded.getProperties().containsKey("oldProp"),
                "updateReport clears properties before putAll, so the old property key must be gone");

        assertEquals("newParamValue", reloaded.getParams().get("newParam"),
                "updateReport must persist the new param");
        assertFalse(reloaded.getParams().containsKey("oldParam"),
                "updateReport clears params before putAll, so the old param key must be gone");
    }

    @Test
    @Transactional
    void updateReportRejectsNonExistingReportId() {
        FmReport ghost = templateReport("no-such-" + UUID.randomUUID());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> fmReportService.updateReport(ghost));
        assertTrue(ex.getMessage().toLowerCase().contains("non-existing"),
                "updating an unknown report must fail mentioning it is non-existing, was: " + ex.getMessage());
    }

    // ------------------------------------------------------------------------------------------- //
    // (9) deleteReport
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void deleteReportRemovesExistingAndReturnsFalseForUnknown() {
        String reportId = uniqueReportId();
        fmReportService.createReport(templateReport(reportId));
        em.flush();
        assertNotNull(fmReportService.findByReportId(reportId),
                "precondition: the freshly created report must exist before deletion");

        assertTrue(fmReportService.deleteReport(reportId),
                "deleteReport must return true when it removes an existing report");
        em.flush();
        em.clear();
        assertNull(fmReportService.findByReportId(reportId),
                "after deleteReport the report row must no longer be found");

        assertFalse(fmReportService.deleteReport("no-such-" + UUID.randomUUID()),
                "deleteReport must return false when no report has the given id");
    }

    // ------------------------------------------------------------------------------------------- //
    // (10) The built-in reports are data (C4.1): a deleted one stays deleted
    // ------------------------------------------------------------------------------------------- //

    @Test
    @Transactional
    void aDeletedBuiltInReportIsNotCreatedAgainAndItsGetterSaysSo() {
        assertTrue(fmReportService.deleteReport(FmReportService.DRAFT_REPORT_ID));
        em.flush();
        em.clear();

        NiordWebException e = assertThrows(NiordWebException.class, fmReportService::getDraftReport);
        assertEquals(404, e.getStatusCode().value(), "a missing built-in report is a 404, not a server error");
        assertTrue(e.getMessage().contains("'draft'") && e.getMessage().contains("bootstrap"),
                "the error names the report and where it comes from: " + e.getMessage());
        assertNull(fmReportService.findByReportId(FmReportService.DRAFT_REPORT_ID),
                "the getter is a plain lookup: it does not create the report again");
    }
}
