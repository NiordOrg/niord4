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

package org.niord.core.publication.series;

import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * RENDER11: the weeks a report names an issue for, as the report's model carries them. The weekly reports print the
 * issue's {@code weeks} in their heading and page header, and the accumulated report each source's
 * {@code group.weeks}: the weeks as the series' name pattern in the report's language writes them, in the name the
 * series suggests for the issue, expanded as that name is ("36" under "Uge ${week}, ${year}", "36 og 37" under
 * "Uge ${week} og ${weekTo}", "36+37" or "36-38" under the pair form, and the pair form where the pattern writes no
 * week), or the week label whole. A source of the accumulated report is in the pair form where its pattern cannot
 * say its span.
 */
class ReportWeeksModelTest {

    /** A weekly in two languages, whose patterns write the weeks two different ways. */
    private static PublicationSeries series() {
        PublicationSeries series = new PublicationSeries();
        series.createDesc("da").setNameSuggestionPattern("Efterretninger uge ${week} og ${weekTo}, ${year}");
        series.createDesc("en").setNameSuggestionPattern("Notices week ${week}, ${year}");
        series.createDesc("kl").setNameSuggestionPattern("  ");
        return series;
    }

    private static PublicationIssue issue(PublicationSeries series, Integer week, Integer weekTo, Integer year,
                                          String weekLabel) {
        PublicationIssue issue = new PublicationIssue();
        issue.setSeries(series);
        issue.setWeek(week);
        issue.setWeekTo(weekTo);
        issue.setYear(year);
        issue.setWeekLabel(weekLabel);
        return issue;
    }

    /**
     * Each language's document says the weeks its own name says. FAILS on the rule that printed the pair form in
     * every language, "36+37" where the Danish name says "36 og 37" and the English one "36".
     */
    @Test
    void theIssuesWeeksAreTheWeeksOfItsNameInTheReportsLanguage() {
        PublicationSeries series = series();
        assertEquals("36 og 37", PrintedNumbering.printedWeeks(issue(series, 36, 37, 2026, null), "da"));
        assertEquals("36", PrintedNumbering.printedWeeks(issue(series, 36, 37, 2026, null), "en"));
        assertEquals("36 og 38", PrintedNumbering.printedWeeks(issue(series, 36, 38, 2026, null), "da"));
        assertEquals("52 og 2", PrintedNumbering.printedWeeks(issue(series, 52, 2, 2027, null), "da"));
    }

    /** A language with no pattern, a blank one, or no series at all: the pair form. */
    @Test
    void withoutAPatternTheWeeksAreThePairForm() {
        PublicationSeries series = series();
        for (String lang : java.util.Arrays.asList("kl", "de", null)) {
            assertEquals("36", PrintedNumbering.printedWeeks(issue(series, 36, null, 2026, null), lang));
            assertEquals("36+37", PrintedNumbering.printedWeeks(issue(series, 36, 37, 2026, null), lang));
            assertEquals("36-38", PrintedNumbering.printedWeeks(issue(series, 36, 38, 2026, null), lang));
            assertEquals("52-2", PrintedNumbering.printedWeeks(issue(series, 52, 2, 2027, null), lang));
        }
        assertEquals("36-38", PrintedNumbering.printedWeeks(issue(null, 36, 38, 2026, null), "da"));
        assertNull(PrintedNumbering.printedWeeks(issue(series, null, null, null, null), "da"));
        assertNull(PrintedNumbering.printedWeeks(null, "da"));
    }

    /** A week label holds the whole span as the publication writes it, so it is printed alone, in every language. */
    @Test
    void aWeekLabelIsPrintedWhole() {
        PublicationSeries series = series();
        assertEquals("36 & 37", PrintedNumbering.printedWeeks(issue(series, 36, 37, 2026, " 36 & 37 "), "da"));
        assertEquals("36 & 37", PrintedNumbering.printedWeeks(issue(series, 36, 37, 2026, "36 & 37"), "en"));
        assertEquals("36 og 37", PrintedNumbering.printedWeeks(issue(series, 36, 37, 2026, "  "), "da"),
                "a blank label is no label");
    }

    /**
     * A source of the accumulated report is named by every week it covered: as the weekly's heading where its
     * pattern says the span (the Danish "36 og 37"), in the pair form where it names the first week alone (the
     * English "36+37" and "36-38", where the weekly is headed "36"), its label whole. FAILS on the rule that named
     * the English source "36", dropping its closing week.
     */
    @Test
    void aSourceIsNamedByEveryWeekItCovered() {
        PublicationSeries series = series();
        assertEquals("36 og 37", PrintedNumbering.printedSourceWeeks(issue(series, 36, 37, 2026, null), "da"));
        assertEquals("36+37", PrintedNumbering.printedSourceWeeks(issue(series, 36, 37, 2026, null), "en"));
        assertEquals("36-38", PrintedNumbering.printedSourceWeeks(issue(series, 36, 38, 2026, null), "en"));
        assertEquals("36", PrintedNumbering.printedWeeks(issue(series, 36, 37, 2026, null), "en"),
                "the weekly's own heading is as its name");
        assertEquals("36", PrintedNumbering.printedSourceWeeks(issue(series, 36, null, 2026, null), "en"));
        assertEquals("36-38", PrintedNumbering.printedSourceWeeks(issue(series, 36, 38, 2026, null), "kl"));
        assertEquals("36 & 37", PrintedNumbering.printedSourceWeeks(issue(series, 36, 37, 2026, "36 & 37"), "en"));
        assertNull(PrintedNumbering.printedSourceWeeks(null, "en"));
    }

    /** The accumulated report's sections carry the weeks the release worked out for each source, as they are. */
    @Test
    void eachSourceOfTheAccumulatedReportCarriesItsWeeks() {
        List<IssueRenderService.RenderGroup> groups = List.of(
                new IssueRenderService.RenderGroup("a", "NtM 36+37", 36, 37, "36 og 37", 2026, new Date(), null,
                        List.of(), false),
                new IssueRenderService.RenderGroup("b", "NtM 38-40", 38, 40, "38-40", 2026, new Date(), null,
                        List.of(), false),
                new IssueRenderService.RenderGroup("c", "NtM 41", 41, null, "41", 2026, new Date(), null,
                        List.of(), false),
                new IssueRenderService.RenderGroup(null, null, null, null, null, null, null, null, List.of(), true));

        List<Map<String, Object>> model = IssueRenderService.modelGroups(groups, List.of());

        assertEquals("36 og 37", model.get(0).get("weeks"));
        assertEquals("38-40", model.get(1).get("weeks"));
        assertEquals("41", model.get(2).get("weeks"));
        assertNull(model.get(3).get("weeks"), "the section added by hand names no week");
    }
}
