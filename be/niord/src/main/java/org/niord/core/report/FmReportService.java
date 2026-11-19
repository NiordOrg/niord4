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

import org.springframework.stereotype.Service;

import jakarta.persistence.NoResultException;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.niord.core.bootstrap.BootstrapRun;
import org.niord.core.domain.Domain;
import org.niord.core.domain.DomainService;
import org.niord.core.report.vo.FmReportVo;
import org.niord.core.service.BaseService;
import org.niord.core.util.TimeUtils;
import org.niord.core.web.NiordWebException;
import org.slf4j.Logger;

import org.springframework.transaction.annotation.Transactional;
import java.time.ZoneId;
import java.util.*;

/**
 * Main interface for accessing and processing Freemarker reports
 */
@Service
@SuppressWarnings("unused")
public class FmReportService extends BaseService {

    static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FmReportService.class);

    /** The id of the report a message list or a message is printed with when the request names none */
    public static final String STANDARD_REPORT_ID = "standard";

    /** The id of the report that prints every language variant of a message, for the editors */
    public static final String DRAFT_REPORT_ID = "draft";

    /** The id of the report the public site names on its printed message lists */
    public static final String PUBLIC_REPORT_ID = "public";

    /** The ids of the built-in reports, which the base product's bootstrap carries */
    public static final List<String> BUILT_IN_REPORT_IDS = List.of(STANDARD_REPORT_ID, DRAFT_REPORT_ID, PUBLIC_REPORT_ID);

    private final DomainService domainService;

    FmReportService(DomainService domainService) {
        this.domainService = domainService;
    }

    /***************************************/
    /** Report methods                    **/
    /***************************************/

    /*
     * The built-in reports standard, draft and public are data, like every report (docs/code-data-config-plan.md
     * R-CDC9, S2): a fresh deployment gets them from the bootstrap, and nothing creates one at start or on use, so
     * a report an operator deleted stays deleted. Until C4.1 the getters below persisted a missing one.
     */

    /**
     * Returns the standard report used for generating PDFs from message lists
     * @return the standard report used for generating PDFs from message lists
     * @throws NiordWebException 404 when the deployment has no standard report
     */
    public FmReport getStandardReport() {
        return builtInReport(STANDARD_REPORT_ID);
    }

    /**
     * Returns the draft report used for generating PDFs from message lists
     * @return the draft report used for generating PDFs from message lists
     * @throws NiordWebException 404 when the deployment has no draft report
     */
    public FmReport getDraftReport() {
        return builtInReport(DRAFT_REPORT_ID);
    }

    /**
     * Returns the report the public site prints its message lists with: the standard template without
     * its front page. The front page is written for the editors — a heading, the search criteria with
     * their internal ids, the number of messages and a table of contents, in English whatever the
     * language of the list — and the public sheet opens with the messages instead.
     * @return the report the public site prints its message lists with
     * @throws NiordWebException 404 when the deployment has no public report
     */
    public FmReport getPublicReport() {
        return builtInReport(PUBLIC_REPORT_ID);
    }

    /**
     * Returns the built-in report with the given id. A missing one is a state of the deployment's data (not yet
     * bootstrapped, or deleted by an operator), not a fault of the code, so a print request answers 404 with the
     * reason rather than a server error.
     * @param reportId the id of a built-in report
     * @return the report
     * @throws NiordWebException 404 when the deployment does not have it
     */
    private FmReport builtInReport(String reportId) {
        FmReport report = findByReportId(reportId);
        if (report == null) {
            throw new NiordWebException("The report '" + reportId + "' does not exist. The built-in reports "
                    + BUILT_IN_REPORT_IDS + " come with the bootstrap of a new database, or with an import of "
                    + "the Print reports page", 404);
        }
        return report;
    }

    /**
     * Returns the report with the given report ID, or null if not found
     * @param reportId the report ID
     * @return the report with the given report ID, or null if not found
     * @throws jakarta.persistence.PersistenceException if the look-up fails: a failure is not reported as "not found"
     */
    public FmReport findByReportId(String reportId) {
        try {
            return em.createNamedQuery("FmReport.findByReportId", FmReport.class)
                    .setParameter("reportId", reportId)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Returns the report with the given report ID, or the standard report if not found
     * @param reportId the report ID
     * @return the report with the given report ID, or the standard report if not found
     */
    public FmReport getReport(String reportId) {
        FmReport report = (StringUtils.isNotBlank(reportId)) ? findByReportId(reportId) : null;
        if (report == null) {
            report = getStandardReport();
        }
        return report;
    }

    /**
     * Returns the reports available in the current domain
     * @return the reports available in the current domain
     */
    public List<FmReport> getReports() {
        List<FmReport> reports = new ArrayList<>();

        // First get the public reports
        reports.addAll(em.createNamedQuery("FmReport.findPublicReports", FmReport.class)
                .getResultList());

        // If a current domain is defined, add the domain specific reports
        Domain domain = domainService.currentDomain();
        if (domain != null) {
            reports.addAll(em.createNamedQuery("FmReport.findReportsByDomain", FmReport.class)
                    .setParameter("domain", domain)
                    .getResultList());

        }

        Collections.sort(reports);

        return reports;
    }

    /**
     * Returns all reports
     * @return all reports
     */
    public List<FmReport> getAllReports() {
        return em.createNamedQuery("FmReport.findAll", FmReport.class)
                .getResultList();
    }

    /**
     * Expand report parameter values with the format ${week}, ${year}, etc., for today in the current domain's zone.
     * <p>
     * A report's parameters are separate values printed together (the NM reports default "week" to "${week}" and
     * "year" to "${year}"), so when any parameter value names a week, ${year} and ${year-2-digits} are the ISO
     * week-based year in all of them: on Monday 30 December 2024 week 1 and year 2025. A report whose parameters
     * name no week keeps the calendar year.
     *
     * @param report the report to expand parameter values for
     * @return the updated report
     */
    public FmReportVo expandReportParams(FmReportVo report) {
        return expandReportParams(report, new Date(), null);
    }

    /**
     * Expand report parameter values for the given day, see {@link #expandReportParams(FmReportVo)}
     * @param report the report to expand parameter values for
     * @param today the moment the values are read from
     * @param zone the zone they are read in, or null for the current domain's zone (the JVM's without one)
     * @return the updated report
     */
    FmReportVo expandReportParams(FmReportVo report, Date today, ZoneId zone) {
        if (report.getParams() != null && !report.getParams().isEmpty()) {
            ZoneId z = zone != null ? zone : currentZone();
            boolean namesAWeek = report.getParams().values().stream()
                    .anyMatch(v -> v instanceof String s && s.contains("${week"));
            int year = namesAWeek ? TimeUtils.isoWeekBasedYear(today, z) : TimeUtils.year(today, z);
            int week = TimeUtils.isoWeek(today, z);

            Map<String, String> replacementValues = new HashMap<>();
            replacementValues.put("${year-2-digits}", String.valueOf(year).substring(2));
            replacementValues.put("${year}", String.valueOf(year));
            replacementValues.put("${week}", String.format("%d", week));
            replacementValues.put("${week-2-digits}", String.format("%02d", week));

            // NB: build the map explicitly rather than via Collectors.toMap, which is backed by
            // Map.merge and throws NullPointerException on a null value — report params are optional
            // and may legitimately be null.
            Map<String, Object> expandedParams = new HashMap<>();
            report.getParams().forEach((key, value) ->
                    expandedParams.put(key, expandReportParamValue(value, replacementValues)));
            report.setParams(expandedParams);
        }
        return report;
    }

    /** Returns the current domain's zone, or the JVM's when there is no current domain or its zone cannot be read */
    private ZoneId currentZone() {
        Domain domain = domainService != null ? domainService.currentDomain() : null;
        return TimeUtils.zoneOrDefault(domain != null ? domain.getTimeZone() : null);
    }

    /** Expands the given parameter value if it has the format ${week}, ${year}, etc. **/
    private Object expandReportParamValue(Object value, Map<String, String> replacementValues) {
        if (value instanceof String str) {
            for (Map.Entry<String, String> e : replacementValues.entrySet()) {
                str = str.replace(e.getKey(), e.getValue()).trim();
            }
            return str;
        }
        return value;
    }

    /**
     * Creates a new report based on the report parameter
     * @param report the report to create
     * @return the created report
     */
    @Transactional
    public FmReport createReport(FmReport report) {
        FmReport original = findByReportId(report.getReportId());
        if (original != null) {
            throw new IllegalArgumentException("Cannot create report with duplicate id " + report.getReportId());
        }

        // Replace template domains with persisted ones
        report.setDomains(domainService.persistedDomains(report.getDomains()));

        return saveEntity(report);
    }

    /**
     * Updates the report data from the report parameter
     * @param report the report to update
     * @return the updated report
     */
    @Transactional
    public FmReport updateReport(FmReport report) {
        FmReport original = findByReportId(report.getReportId());
        if (original == null) {
            throw new IllegalArgumentException("Cannot update non-existing report " + report.getReportId());
        }

        // Copy the report data
        original.setSortOrder(report.getSortOrder());
        original.setName(report.getName());
        original.setTemplatePath(report.getTemplatePath());
        original.getProperties().clear();
        original.getProperties().putAll(report.getProperties());
        original.getParams().clear();
        original.getParams().putAll(report.getParams());
        original.setDomains(domainService.persistedDomains(report.getDomains()));

        return saveEntity(original);
    }

    /**
     * Replaces in a report an earlier archive of the bootstrap run created the values the later archive's report
     * gives, within the first bootstrap's rule (docs/code-data-config-plan.md §7.2): the name, the sort order and the
     * template path, when the file gives them (not null) and they differ; the properties and the parameters, merged by
     * key, a key's value from the file replacing the row's and a key the file leaves out kept; and the domains it is
     * not linked to are added, each once. Nothing is removed, and the report is saved only when something changed. The
     * outcome is returned for the job log: what was added, what was replaced, and each domain no row carries.
     *
     * @param original the report, as an earlier archive of the run created it
     * @param from     the later archive's report
     * @return what was added, replaced and kept
     */
    @Transactional
    public BootstrapRun.Additions replaceValues(FmReport original, FmReport from) {
        FmReport report = findByReportId(original.getReportId());
        if (report == null) {
            throw new IllegalArgumentException("Cannot replace in non-existing report " + original.getReportId());
        }
        List<String> added = new ArrayList<>();
        List<String> replaced = new ArrayList<>();
        if (StringUtils.isNotBlank(from.getName()) && !Objects.equals(report.getName(), from.getName())) {
            replaced.add(BootstrapRun.Additions.change("the name", report.getName(), from.getName()));
            report.setName(from.getName());
        }
        if (from.getSortOrder() != null && !Objects.equals(report.getSortOrder(), from.getSortOrder())) {
            replaced.add(BootstrapRun.Additions.change("the sort order", report.getSortOrder(), from.getSortOrder()));
            report.setSortOrder(from.getSortOrder());
        }
        if (StringUtils.isNotBlank(from.getTemplatePath())
                && !Objects.equals(report.getTemplatePath(), from.getTemplatePath())) {
            replaced.add(BootstrapRun.Additions.change("the template path", report.getTemplatePath(),
                    from.getTemplatePath()));
            report.setTemplatePath(from.getTemplatePath());
        }
        mergeByKey("property", report.getProperties(), from.getProperties(), added, replaced);
        mergeByKey("parameter", report.getParams(), from.getParams(), added, replaced);
        // Checked against the links as they grow, so a domain the file lists twice is added once
        for (Domain domain : domainService.persistedDomains(from.getDomains())) {
            if (report.getDomains().stream().noneMatch(o -> Objects.equals(o.getDomainId(), domain.getDomainId()))) {
                report.getDomains().add(domain);
                added.add("domain " + domain.getDomainId());
            }
        }
        List<String> kept = new ArrayList<>();
        domainService.missingDomainIds(from.getDomains()).forEach(id -> kept.add("domain " + id + " not found"));
        if (!added.isEmpty() || !replaced.isEmpty()) {
            saveEntity(report);
        }
        return new BootstrapRun.Additions("Report " + report.getReportId(), added, replaced, kept);
    }

    /** Merges the file's map into the row's by key: a new key added, a differing value replaced, the rest kept */
    private static void mergeByKey(String what, Map<String, Object> row, Map<String, Object> file, List<String> added,
                                   List<String> replaced) {
        if (file == null || file.isEmpty()) {
            return;
        }
        file.forEach((key, value) -> {
            if (!row.containsKey(key)) {
                row.put(key, value);
                added.add(what + " " + key);
            } else if (!Objects.equals(row.get(key), value)) {
                replaced.add(BootstrapRun.Additions.change(what + " " + key, row.get(key), value));
                row.put(key, value);
            }
        });
    }

    /**
     * Deletes the report with the given ID
     * @param reportId the ID of the report to delete
     */
    @Transactional
    public boolean deleteReport(String reportId) {

        FmReport report = findByReportId(reportId);
        if (report != null) {
            remove(report);
            return true;
        }
        return false;
    }

}
