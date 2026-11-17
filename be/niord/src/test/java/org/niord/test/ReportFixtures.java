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
package org.niord.test;

import jakarta.persistence.EntityManager;
import org.niord.core.domain.Domain;
import org.niord.core.report.FmReport;
import org.niord.core.report.FmReportService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Danish report rows the suite renders and lists, which neither the base product's bootstrap nor the sample
 * carries: {@code nm-report} and {@code nm-tp-report}, each linked to the domain {@code niord-nm}. They are DK's
 * bootstrap data (docs/code-data-config-plan.md §7.3), and since C4b the fixture holds only the base product's three
 * reports ({@code standard}, {@code draft}, {@code public}); the rows below are the old IT seed's, with the domain ID
 * the sample uses. Their templates, {@code messages/nm-report-pdf.ftl} and {@code messages/nm-tp-report-pdf.ftl},
 * are base product templates, so the fixture holds them as rows. A test that names one of the reports ensures it
 * here, as {@link ScriptResourceFixtures} does for the test templates, and like those the rows are committed and stay
 * for the rest of the JVM: from the first test that ensures one on (FmReportServiceITTest,
 * MessageReportRestServiceITTest and IssueRenderRealITTest call it before each test), the shared database holds five
 * reports instead of the fixture's three, by class order, so no IT may assert the fixture's report count exactly.
 * Needs {@link Tx}, which {@link ITTest} binds before each test.
 *
 * <p>It also plants an entry in the missing-path report, which the fixture leaves empty since C4b: a script path
 * named by category 2 that no row has ({@link #plantCategoryPath}, {@link #removeCategoryPath}).
 */
public final class ReportFixtures {

    /** The fixture's category whose script paths a planted entry joins */
    public static final int PLANTED_PATH_CATEGORY = 2;

    /** The NM report: its front page, the volume and the ISSN, linked to the NM domain */
    public static final String NM_REPORT = "nm-report";

    /** The NM T&amp;P report: its week parameter, linked to the NM domain */
    public static final String NM_TP_REPORT = "nm-tp-report";

    /** The domain the two reports are linked to */
    public static final String NM_DOMAIN = "niord-nm";

    private ReportFixtures() {
    }

    /**
     * Creates, in a transaction of its own, each of the given reports that has no row; a report with a row is left as
     * it is. The rows are committed and never removed: they stay for every later test of the JVM (see the class
     * comment)
     *
     * @param reportService the report service
     * @param reportIds     the reports, among {@link #NM_REPORT} and {@link #NM_TP_REPORT}
     */
    public static void ensure(FmReportService reportService, String... reportIds) {
        Tx.requiringNew().run(() -> {
            for (String reportId : reportIds) {
                if (reportService.findByReportId(reportId) == null) {
                    reportService.createReport(report(reportId));
                }
            }
        });
    }

    /**
     * Adds, in a committed transaction of its own, a script path to category {@value #PLANTED_PATH_CATEGORY}'s list,
     * at the index after its last. The index column is {@code Category.scriptResourcePaths}' {@code @OrderColumn}: a
     * row at a fixed far index (999) would make Hibernate load the list padded with nulls up to it while the row
     * exists, and two tests planting at the same index would share a primary key. {@link #removeCategoryPath} takes
     * the row away again.
     *
     * @param em   the entity manager
     * @param path the path to plant
     */
    public static void plantCategoryPath(EntityManager em, String path) {
        Tx.requiringNew().run(() -> em.createNativeQuery("INSERT INTO public.\"Category_scriptResourcePaths\" "
                        + "(\"Category_id\", \"scriptResourcePaths\", \"indexNo\") VALUES (:category, :path, "
                        + "(SELECT coalesce(max(\"indexNo\"), -1) + 1 FROM public.\"Category_scriptResourcePaths\" "
                        + "WHERE \"Category_id\" = :category))")
                .setParameter("category", PLANTED_PATH_CATEGORY)
                .setParameter("path", path)
                .executeUpdate());
    }

    /**
     * Removes, in a committed transaction of its own, a path {@link #plantCategoryPath} planted
     *
     * @param em   the entity manager
     * @param path the planted path
     */
    public static void removeCategoryPath(EntityManager em, String path) {
        Tx.requiringNew().run(() -> em.createNativeQuery("DELETE FROM public.\"Category_scriptResourcePaths\" "
                        + "WHERE \"Category_id\" = :category AND \"scriptResourcePaths\" = :path")
                .setParameter("category", PLANTED_PATH_CATEGORY)
                .setParameter("path", path)
                .executeUpdate());
    }

    private static FmReport report(String reportId) {
        FmReport report = new FmReport();
        report.setReportId(reportId);
        switch (reportId) {
            case NM_REPORT -> {
                report.setName("NM report");
                report.setSortOrder(2);
                report.setTemplatePath("templates/messages/nm-report-pdf.ftl");
                report.getParams().putAll(Map.of("year", "${year}", "week", "${week}"));
                report.getProperties().putAll(Map.of("volume", "133", "mapThumbnails", false, "ISSN", "1397-999X"));
            }
            case NM_TP_REPORT -> {
                report.setName("NM T&P report");
                report.setSortOrder(3);
                report.setTemplatePath("templates/messages/nm-tp-report-pdf.ftl");
                report.getParams().putAll(Map.of("year", "${year}", "week", "${week}"));
                report.getProperties().put("mapThumbnails", false);
            }
            default -> throw new IllegalArgumentException("No report fixture " + reportId);
        }
        Domain domain = new Domain();
        domain.setDomainId(NM_DOMAIN);
        List<Domain> domains = new ArrayList<>();
        domains.add(domain);
        report.setDomains(domains);
        return report;
    }
}
