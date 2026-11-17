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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.niord.core.report.vo.FmReportVo;
import org.niord.core.util.TimeUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Framework-neutral unit tests for {@link FmReportService#expandReportParams(FmReportVo)} and its private
 * {@code expandReportParamValue} helper.
 *
 * <p>{@code expandReportParams} is a pure function: it substitutes the four date tokens
 * <code>${year}</code>, <code>${year-2-digits}</code>, <code>${week}</code> and <code>${week-2-digits}</code>
 * inside the report's {@code params} string values, using the current year and ISO-8601 week computed via
 * {@link TimeUtils}. When a parameter names a week, ${year} is the ISO week-based year in every value (TZ3).
 * Today is read in the current domain's zone; with no {@code DomainService} it is the JVM's, so the service is
 * exercised here as a plain object via {@code new FmReportService(null)} — no container, database or CDI. The
 * fixed-date cases call the package-private {@code expandReportParams(report, today, zone)}.
 *
 * <p>The expected year/week oracle is computed with the SAME production calls on a {@link Date} captured in
 * the test, so the assertions stay correct regardless of when the suite runs.
 */
public class FmReportParamExpansionTest {

    private FmReportService service;

    @BeforeEach
    public void setUp() {
        service = new FmReportService(null);
    }

    /** Returns the current four-digit calendar year: what ${year} is in a report whose parameters name no week. */
    private int expectedYear() {
        return TimeUtils.getCalendarField(new Date(), Calendar.YEAR);
    }

    /** Returns the current ISO-8601 week-of-year exactly as the production code derives it. */
    private int expectedWeek() {
        return TimeUtils.getISO8601WeekOfYear(new Date());
    }

    /**
     * Returns the current ISO week-based year: what ${year} is in a report whose parameters name a week (TZ3).
     * It differs from the calendar year between 29 December and 3 January in some years.
     */
    private int expectedWeekBasedYear() {
        return TimeUtils.isoWeekBasedYear(new Date(), TimeZone.getDefault().toZoneId());
    }

    private static Date utc(LocalDateTime time) {
        return Date.from(time.atZone(ZoneId.of("UTC")).toInstant());
    }

    /** The parameters of the NM reports: the week and the year, each its own value. */
    private static FmReportVo nmReport() {
        FmReportVo report = new FmReportVo();
        Map<String, Object> params = new HashMap<>();
        params.put("year", "${year}");
        params.put("y2", "${year-2-digits}");
        params.put("week", "${week}");
        params.put("w2", "${week-2-digits}");
        report.setParams(params);
        return report;
    }

    /** A report whose parameters name no week. */
    private static FmReportVo yearOnlyReport() {
        FmReportVo report = new FmReportVo();
        Map<String, Object> params = new HashMap<>();
        params.put("year", "${year}");
        params.put("title", "Report ${year-2-digits}");
        report.setParams(params);
        return report;
    }

    /**
     * TZ3. On Monday 30 December 2024 the ISO week is week 1 of 2025: a report whose parameters name a week
     * prefills week 1 of 2025 in every value, not week 1 of 2024; on 1 January 2027 week 53 of 2026.
     */
    @Test
    public void expandReportParams_withAWeek_takesTheWeekBasedYearInEveryValue() {
        ZoneId utc = ZoneId.of("UTC");
        Map<String, Object> dec30 = service.expandReportParams(nmReport(),
                utc(LocalDateTime.of(2024, 12, 30, 12, 0)), utc).getParams();
        assertEquals(Map.of("year", "2025", "y2", "25", "week", "1", "w2", "01"), dec30);

        Map<String, Object> jan1 = service.expandReportParams(nmReport(),
                utc(LocalDateTime.of(2027, 1, 1, 12, 0)), utc).getParams();
        assertEquals(Map.of("year", "2026", "y2", "26", "week", "53", "w2", "53"), jan1);
    }

    /** A report whose parameters name no week keeps the calendar year. */
    @Test
    public void expandReportParams_withoutAWeek_keepsTheCalendarYear() {
        Map<String, Object> params = service.expandReportParams(yearOnlyReport(),
                utc(LocalDateTime.of(2024, 12, 30, 12, 0)), ZoneId.of("UTC")).getParams();
        assertEquals(Map.of("year", "2024", "title", "Report 24"), params);
    }

    /** Today is read in the domain's zone: 23:30 UTC on 31 December 2026 is already 2027 in Copenhagen. */
    @Test
    public void expandReportParams_readsTodayInTheGivenZone() {
        Date instant = utc(LocalDateTime.of(2026, 12, 31, 23, 30));
        assertEquals("2027", service.expandReportParams(yearOnlyReport(), instant,
                ZoneId.of("Europe/Copenhagen")).getParams().get("year"));
        assertEquals("2026", service.expandReportParams(yearOnlyReport(), instant,
                ZoneId.of("UTC")).getParams().get("year"));
    }

    /**
     * When {@code properties} is non-empty (the guard is satisfied), a param value containing the
     * <code>${year}</code> token is expanded to the current four-digit year.
     */
    @Test
    public void expandReportParams_nonEmptyProperties_expandsYearToken() {
        FmReportVo report = new FmReportVo();
        report.getProperties().put("draft", Boolean.TRUE);

        Map<String, Object> params = new HashMap<>();
        params.put("title", "Report ${year}");
        report.setParams(params);

        FmReportVo result = service.expandReportParams(report);

        assertEquals("Report " + expectedYear(), result.getParams().get("title"),
                "${year} must be replaced by the current four-digit year");
    }

    /**
     * All four supported tokens are expanded in a single value: {@code ${year}} -> four digits,
     * {@code ${year-2-digits}} -> last two digits of the year, {@code ${week}} -> unpadded week, and
     * {@code ${week-2-digits}} -> zero-padded two-digit week.
     */
    @Test
    public void expandReportParams_allFourTokensExpandCorrectly() {
        // The parameters name a week, so ${year} is the week-based year (TZ3)
        int year = expectedWeekBasedYear();
        int week = expectedWeek();

        FmReportVo report = new FmReportVo();
        report.getProperties().put("draft", Boolean.TRUE);

        Map<String, Object> params = new HashMap<>();
        params.put("y4", "${year}");
        params.put("y2", "${year-2-digits}");
        params.put("w", "${week}");
        params.put("w2", "${week-2-digits}");
        report.setParams(params);

        FmReportVo result = service.expandReportParams(report);

        assertEquals(String.valueOf(year), result.getParams().get("y4"),
                "${year} expands to the full four-digit year");
        assertEquals(String.valueOf(year).substring(2), result.getParams().get("y2"),
                "${year-2-digits} expands to the last two digits of the year");
        assertEquals(String.format("%d", week), result.getParams().get("w"),
                "${week} expands to the unpadded ISO-8601 week");
        assertEquals(String.format("%02d", week), result.getParams().get("w2"),
                "${week-2-digits} expands to the zero-padded two-digit ISO-8601 week");
    }

    /**
     * Regression test for fixed bug FMREP1: the {@code expandReportParams} guard originally checked
     * whether {@code PROPERTIES} was empty, yet the substitution it gates operates on {@code PARAMS}.
     * A report whose {@code params} carried valid tokens but whose {@code properties} map was empty
     * therefore had its {@code ${year}} token left verbatim. The guard now keys off {@code params}, so
     * the token is expanded regardless of whether {@code properties} is populated.
     */
    @Test
    public void expandReportParams_emptyPropertiesStillExpandsParamTokens_FMREP1() {
        FmReportVo report = new FmReportVo();
        // properties left at its default empty map -> must no longer suppress expansion

        Map<String, Object> params = new HashMap<>();
        params.put("title", "Report ${year}");
        report.setParams(params);

        FmReportVo result = service.expandReportParams(report);

        assertEquals("Report " + expectedYear(), result.getParams().get("title"),
                "With empty properties the ${year} token is still expanded (FMREP1 fixed)");
    }

    /**
     * A {@code null} param value must be preserved without crashing the expansion. Report params are an
     * optional {@code Map<String, Object>} and may legitimately carry {@code null} values; the expansion
     * must not be backed by {@code Collectors.toMap} (whose {@code Map.merge} rejects null values with a
     * {@code NullPointerException}). This is the key regression guard for the FMREP1 guard change, which
     * now routes empty-{@code properties} reports through the expansion branch too.
     */
    @Test
    public void expandReportParams_nullParamValueIsPreservedWithoutNpe() {
        FmReportVo report = new FmReportVo();
        // empty properties + a null param value -> previously bypassed the branch, must not crash now

        Map<String, Object> params = new HashMap<>();
        params.put("optional", null);
        params.put("title", "Report ${year}");
        report.setParams(params);

        FmReportVo result = service.expandReportParams(report);

        assertTrue(result.getParams().containsKey("optional"),
                "the null-valued param key must be retained");
        assertNull(result.getParams().get("optional"),
                "a null param value is passed through unchanged");
        assertEquals("Report " + expectedYear(), result.getParams().get("title"),
                "sibling String tokens are still expanded");
    }

    /**
     * A non-{@code String} param value (here a {@link Boolean}) is passed through unchanged by
     * {@code expandReportParamValue}, which only rewrites {@code String} values.
     */
    @Test
    public void expandReportParams_nonStringValuePassesThroughUnchanged() {
        FmReportVo report = new FmReportVo();
        report.getProperties().put("draft", Boolean.TRUE);

        Map<String, Object> params = new HashMap<>();
        params.put("flag", Boolean.TRUE);
        report.setParams(params);

        FmReportVo result = service.expandReportParams(report);

        assertEquals(Boolean.TRUE, result.getParams().get("flag"),
                "Non-String param values must be returned unchanged");
    }
}
