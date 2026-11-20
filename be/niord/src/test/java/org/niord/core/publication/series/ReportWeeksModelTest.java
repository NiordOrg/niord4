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
 * {@code group.weeks}, so that both say the weeks the issue's name says: "36+37" for a pair, "36-38" for three weeks
 * or more. Before, the model carried the first and the closing week only, and the templates had no way to tell a
 * pair from a range across a year's end.
 */
class ReportWeeksModelTest {

    private static PublicationIssue issue(Integer week, Integer weekTo, Integer year, String weekLabel) {
        PublicationIssue issue = new PublicationIssue();
        issue.setWeek(week);
        issue.setWeekTo(weekTo);
        issue.setYear(year);
        issue.setWeekLabel(weekLabel);
        return issue;
    }

    @Test
    void theIssuesWeeksAreTheSpanItsNameSays() {
        assertEquals("36", PrintedNumbering.printedWeeks(issue(36, null, 2026, null)));
        assertEquals("36+37", PrintedNumbering.printedWeeks(issue(36, 37, 2026, null)));
        assertEquals("36-38", PrintedNumbering.printedWeeks(issue(36, 38, 2026, null)));
        assertEquals("52-2", PrintedNumbering.printedWeeks(issue(52, 2, 2027, null)));
        assertNull(PrintedNumbering.printedWeeks(issue(null, null, null, null)));
        assertNull(PrintedNumbering.printedWeeks(null));
    }

    /** A week label holds the whole span as the publication writes it, so it is printed alone. */
    @Test
    void aWeekLabelIsPrintedWhole() {
        assertEquals("36 og 37", PrintedNumbering.printedWeeks(issue(36, 37, 2026, " 36 og 37 ")));
        assertEquals("36+37", PrintedNumbering.printedWeeks(issue(36, 37, 2026, "  ")),
                "a blank label is no label");
    }

    @Test
    void eachSourceOfTheAccumulatedReportCarriesItsWeeks() {
        List<IssueRenderService.RenderGroup> groups = List.of(
                new IssueRenderService.RenderGroup("a", "NtM 36+37", 36, 37, 2026, new Date(), null, List.of(), false),
                new IssueRenderService.RenderGroup("b", "NtM 38-40", 38, 40, 2026, new Date(), null, List.of(), false),
                new IssueRenderService.RenderGroup("c", "NtM 41", 41, null, 2026, new Date(), null, List.of(), false),
                new IssueRenderService.RenderGroup(null, null, null, null, null, null, null, List.of(), true));

        List<Map<String, Object>> model = IssueRenderService.modelGroups(groups, List.of());

        assertEquals("36+37", model.get(0).get("weeks"));
        assertEquals("38-40", model.get(1).get("weeks"));
        assertEquals("41", model.get(2).get("weeks"));
        assertNull(model.get(3).get("weeks"), "the section added by hand names no week");
    }
}
