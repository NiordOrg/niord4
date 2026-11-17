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
package org.niord.core.publication.series.resolve;

import org.junit.jupiter.api.Test;
import org.niord.core.publication.series.IntervalBoundSource;
import org.niord.core.publication.series.resolve.GapDetection.Gap;
import org.niord.core.publication.series.resolve.GapDetection.Gate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The length of one publication period, measured where a weekly or daily series' next cut-off is proposed from it
 * ({@code IssueDraftService} 186-203, {@code IssuePublishService} 1596-1601, the gap rows of {@code GapSynthesis}),
 * across the clock changes of the series' cut-off zone (the zone and date-boundary matrix). Europe/Copenhagen and
 * America/Nuuk, both changes of 2027, and UTC as the control; New Year, a leap day and the ISO week 53 of 2026 for
 * the months and years, which are measured in the zone.
 */
class GapDetectionClockChangeTest {

    private static final ZoneId CPH = ZoneId.of("Europe/Copenhagen");
    private static final ZoneId NUUK = ZoneId.of("America/Nuuk");
    private static final ZoneId UTC = ZoneId.of("UTC");

    private static Date at(LocalDateTime local, ZoneId zone) {
        return Date.from(local.atZone(zone).toInstant());
    }

    private static LocalDateTime local(Date date, ZoneId zone) {
        return ZonedDateTime.ofInstant(date.toInstant(), zone).toLocalDateTime();
    }

    /** The next cut-off one period after the anchor, as the draft and the release propose it, in local time */
    private static LocalDateTime nextCutoff(String cadence, LocalDateTime anchor, ZoneId zone) {
        Date from = at(anchor, zone);
        long period = GapDetection.periodMillisOf(cadence, zone, from);
        return local(GapDetection.nextCutoff(from, period), zone);
    }

    @Test
    void aWeeklyOrDailyPeriod_inUtc_keepsTheCutoffsClock() {
        assertEquals(LocalDateTime.of(2027, 4, 2, 12, 0), nextCutoff("WEEKLY", LocalDateTime.of(2027, 3, 26, 12, 0), UTC));
        assertEquals(LocalDateTime.of(2027, 3, 29, 12, 0), nextCutoff("DAILY", LocalDateTime.of(2027, 3, 28, 12, 0), UTC));
    }

    @Test
    void aWeeklyPeriod_awayFromAClockChange_keepsTheCutoffsClock() {
        assertEquals(LocalDateTime.of(2027, 5, 14, 12, 0), nextCutoff("WEEKLY", LocalDateTime.of(2027, 5, 7, 12, 0), CPH));
        assertEquals(LocalDateTime.of(2027, 5, 14, 12, 0), nextCutoff("WEEKLY", LocalDateTime.of(2027, 5, 7, 12, 0), NUUK));
    }

    /** Months and years are measured in the zone: the cut-off keeps its clock and its day */
    @Test
    void aMonthlyOrYearlyPeriod_keepsTheCutoffsClock_acrossClockChangesNewYearAndALeapDay() {
        assertEquals(LocalDateTime.of(2027, 4, 15, 12, 0), nextCutoff("MONTHLY", LocalDateTime.of(2027, 3, 15, 12, 0), CPH));
        assertEquals(LocalDateTime.of(2027, 11, 15, 12, 0), nextCutoff("MONTHLY", LocalDateTime.of(2027, 10, 15, 12, 0), CPH));
        assertEquals(LocalDateTime.of(2027, 1, 31, 23, 30), nextCutoff("MONTHLY", LocalDateTime.of(2026, 12, 31, 23, 30), CPH));
        assertEquals(LocalDateTime.of(2028, 2, 29, 12, 0), nextCutoff("MONTHLY", LocalDateTime.of(2028, 1, 29, 12, 0), NUUK));
        assertEquals(LocalDateTime.of(2029, 2, 28, 12, 0), nextCutoff("YEARLY", LocalDateTime.of(2028, 2, 29, 12, 0), CPH));
        assertEquals(LocalDateTime.of(2028, 1, 1, 0, 30), nextCutoff("YEARLY", LocalDateTime.of(2027, 1, 1, 0, 30), NUUK));
    }

    /** Thursday 31 December 2026 is in ISO week 53; a weekly cut-off there moves on to week 1 of 2027 */
    @Test
    void aWeeklyPeriod_fromWeek53_reachesWeek1_atTheSameClock() {
        assertEquals(LocalDateTime.of(2027, 1, 7, 12, 0), nextCutoff("WEEKLY", LocalDateTime.of(2026, 12, 31, 12, 0), CPH));
        assertEquals(LocalDateTime.of(2027, 1, 7, 23, 30), nextCutoff("WEEKLY", LocalDateTime.of(2026, 12, 31, 23, 30), NUUK));
    }

    /**
     * TZ6 (2026-11-12): a week or a day that holds a clock change is 167 or 169 hours, 23 or 25
     * hours long, so its length is measured in the series' zone as months and years are. The next cut-off that a new
     * weekly or daily issue (the draft, or the one a release opens) is proposed with keeps the series' cut-off clock:
     * a Friday 12:00 Copenhagen cut-off before the spring change proposes the next at 12:00, not 13:00; before the
     * autumn change at 12:00, not 11:00; Nuuk the same.
     */
    @Test
    void aWeeklyOrDailyPeriodAcrossAClockChange_keepsTheCutoffsClock() {
        assertEquals(LocalDateTime.of(2027, 4, 2, 12, 0), nextCutoff("WEEKLY", LocalDateTime.of(2027, 3, 26, 12, 0), CPH));
        assertEquals(LocalDateTime.of(2027, 11, 5, 12, 0), nextCutoff("WEEKLY", LocalDateTime.of(2027, 10, 29, 12, 0), CPH));
        assertEquals(LocalDateTime.of(2027, 3, 28, 12, 0), nextCutoff("DAILY", LocalDateTime.of(2027, 3, 27, 12, 0), CPH),
                "a daily cut-off across the spring change");
        assertEquals(LocalDateTime.of(2027, 10, 31, 12, 0), nextCutoff("DAILY", LocalDateTime.of(2027, 10, 30, 12, 0), CPH),
                "a daily cut-off across the autumn change");
        assertEquals(LocalDateTime.of(2027, 4, 2, 12, 0), nextCutoff("WEEKLY", LocalDateTime.of(2027, 3, 26, 12, 0), NUUK));
        assertEquals(LocalDateTime.of(2027, 11, 5, 12, 0), nextCutoff("WEEKLY", LocalDateTime.of(2027, 10, 29, 12, 0), NUUK));
    }

    /**
     * TZ6: the gap rows are stepped week by week in the zone, so a missing week that holds the spring change,
     * between two weekly issues released at 12:00 Copenhagen time, is shown from Friday 12:00 to the next Friday
     * 12:00, the series' own cut-off clock. No length is measured anywhere: the cadence and the zone are passed.
     */
    @Test
    void aMissingWeekAcrossTheSpringChange_closesAtTheSeriesClock() {
        Gate gate = GapDetection.gate(null, "WEEKLY", true, false);
        List<Date> cutoffs = new ArrayList<>(List.of(
                at(LocalDateTime.of(2027, 3, 26, 12, 0), CPH),
                at(LocalDateTime.of(2027, 4, 9, 12, 0), CPH)));
        List<Gap> gaps = GapDetection.gaps(gate, cutoffs, "WEEKLY", CPH);
        assertEquals(1, gaps.size(), "one week is missing");
        assertEquals(LocalDateTime.of(2027, 3, 26, 12, 0), local(gaps.get(0).from(), CPH));
        assertEquals(LocalDateTime.of(2027, 4, 2, 12, 0), local(gaps.get(0).to(), CPH),
                "the missing week closes at 12:00, where the series closes its weeks");
    }

    /** A run of missing weeks across the autumn change: each closes at 12:00, the ones after the change too */
    @Test
    void aRunOfMissingWeeksAcrossTheAutumnChange_closeAtTheSeriesClock() {
        Gate gate = GapDetection.gate(null, "WEEKLY", true, false);
        List<Gap> gaps = GapDetection.gaps(gate, List.of(
                at(LocalDateTime.of(2027, 10, 15, 12, 0), CPH),
                at(LocalDateTime.of(2027, 11, 12, 12, 0), CPH)), "WEEKLY", CPH);
        assertEquals(List.of(LocalDateTime.of(2027, 10, 22, 12, 0), LocalDateTime.of(2027, 10, 29, 12, 0),
                        LocalDateTime.of(2027, 11, 5, 12, 0)),
                gaps.stream().map(g -> local(g.to(), CPH)).toList(), "three weeks are missing, each closing at 12:00");
        assertEquals(local(gaps.get(1).to(), CPH), local(gaps.get(2).from(), CPH), "the rows tile");
    }

    // ------------------------------------------------------------------ the issue list's rows (GapSynthesis)

    private static final IssueNaming.YearBasis ISO = IssueNaming.YearBasis.ISO_WEEK_YEAR;

    private static GapSynthesis.Issue released(String id, LocalDateTime cutoff) {
        return new GapSynthesis.Issue(id, at(cutoff, CPH), IntervalBoundSource.STAMPED);
    }

    private static GapSynthesis.Issue chained(String id, LocalDateTime openedAt, LocalDateTime cutoff) {
        return new GapSynthesis.Issue(id, at(cutoff, CPH), IntervalBoundSource.STAMPED, at(openedAt, CPH), false);
    }

    /**
     * The rows of a Copenhagen series as the issue list makes them: IssueListService.synthesize hands the
     * synthesizer the series' cadence and cut-off zone, and the synthesizer steps each period from its own start.
     * Before the repair it handed one length, measured around the NEWEST issue's cut-off, and every row was tiled with
     * it.
     */
    private static List<GapSynthesis.Row> rowsAsTheIssueListMakesThem(TimeRelation relation, String cadence,
                                                                     List<GapSynthesis.Issue> issues,
                                                                     LocalDateTime now) {
        Gate gate = GapDetection.gate(relation, cadence, true, false);
        return GapSynthesis.synthesize(gate, "series", issues, cadence, CPH, ISO, Map.of(), null, at(now, CPH));
    }

    private static List<String> spans(List<GapSynthesis.Row> rows) {
        return rows.stream()
                .map(r -> r.kind() + " " + local(r.intervalFrom(), CPH) + " to " + local(r.intervalTo(), CPH))
                .toList();
    }

    /**
     * Probe 1: weekly issues closing 19-26 March and 2-9 April 2027, newest 9 April. The missing week holds the
     * spring change and closes on Friday 2 April at 12:00. The fixed week, and the week measured at the newest issue
     * (an ordinary 168 hours), both showed it ending at 13:00.
     */
    @Test
    void probe1_aMissingWeekAcrossTheSpringChange_onTheIssueList_closesAt12() {
        List<GapSynthesis.Row> rows = rowsAsTheIssueListMakesThem(TimeRelation.PUBLISHED_IN_INTERVAL, "WEEKLY",
                List.of(chained("w12", LocalDateTime.of(2027, 3, 19, 12, 0), LocalDateTime.of(2027, 3, 26, 12, 0)),
                        chained("w14", LocalDateTime.of(2027, 4, 2, 12, 0), LocalDateTime.of(2027, 4, 9, 12, 0))),
                LocalDateTime.of(2027, 4, 10, 9, 0));
        assertEquals(List.of(
                        "MISSING 2027-03-26T12:00 to 2027-04-02T12:00",
                        "UPCOMING 2027-04-09T12:00 to 2027-04-16T12:00"),
                spans(rows));
    }

    /**
     * Probe 2: weekly issues closing 5, 19 and 26 March 2027, newest 26 March, whose own week holds the spring change
     * (167 hours). The missing ordinary week closes on 12 March at 12:00, where the next issue opens. Tiled with the
     * newest issue's 167 hours it was shown ending at 11:00, an hour before; the forward row from the newest issue is
     * its own week and also closes at 12:00.
     */
    @Test
    void probe2_anOrdinaryMissingWeek_isNotShortenedByTheNewestIssuesClockChange() {
        List<GapSynthesis.Row> rows = rowsAsTheIssueListMakesThem(TimeRelation.PUBLISHED_IN_INTERVAL, "WEEKLY",
                List.of(released("w9", LocalDateTime.of(2027, 3, 5, 12, 0)),
                        released("w11", LocalDateTime.of(2027, 3, 19, 12, 0)),
                        released("w12", LocalDateTime.of(2027, 3, 26, 12, 0))),
                LocalDateTime.of(2027, 3, 27, 9, 0));
        assertEquals(List.of(
                        "MISSING 2027-03-05T12:00 to 2027-03-12T12:00",
                        "UPCOMING 2027-03-26T12:00 to 2027-04-02T12:00"),
                spans(rows));

        // The same with the issues chained, read off their intervals rather than their cut-offs.
        List<GapSynthesis.Row> chainedRows = rowsAsTheIssueListMakesThem(TimeRelation.PUBLISHED_IN_INTERVAL, "WEEKLY",
                List.of(chained("w9", LocalDateTime.of(2027, 2, 26, 12, 0), LocalDateTime.of(2027, 3, 5, 12, 0)),
                        chained("w11", LocalDateTime.of(2027, 3, 12, 12, 0), LocalDateTime.of(2027, 3, 19, 12, 0)),
                        chained("w12", LocalDateTime.of(2027, 3, 19, 12, 0), LocalDateTime.of(2027, 3, 26, 12, 0))),
                LocalDateTime.of(2027, 3, 27, 9, 0));
        assertEquals(spans(rows), spans(chainedRows));
    }

    /**
     * Probe 3: a daily series whose newest issue closed on 27 March 2027 at 12:00, the day before the spring change,
     * seen on 29 March at 13:00. Every forward row closes at 12:00. The fixed day put them an hour late (28 March
     * 13:00, 29 March 13:00, 30 March 13:00); the day measured at the newest issue (23 hours) drifted an hour per row
     * (12:00, 11:00, 10:00).
     */
    @Test
    void probe3_dailyForwardRowsAcrossTheSpringChange_closeAt12() {
        List<GapSynthesis.Row> rows = rowsAsTheIssueListMakesThem(TimeRelation.PUBLISHED_IN_INTERVAL, "DAILY",
                List.of(released("d86", LocalDateTime.of(2027, 3, 27, 12, 0))),
                LocalDateTime.of(2027, 3, 29, 13, 0));
        assertEquals(List.of(
                        "MISSING 2027-03-27T12:00 to 2027-03-28T12:00",
                        "MISSING 2027-03-28T12:00 to 2027-03-29T12:00",
                        "UPCOMING 2027-03-29T12:00 to 2027-03-30T12:00"),
                spans(rows));
    }

    /**
     * A withdrawn week of an in-force weekly series, holding the spring change: the row that brings it back closes at
     * 12:00, as the release that was withdrawn did.
     */
    @Test
    void aWithdrawnWeekAcrossTheSpringChange_isMissingUntil12() {
        GapSynthesis.Issue withdrawn = new GapSynthesis.Issue("w13", at(LocalDateTime.of(2027, 4, 2, 12, 0), CPH),
                IntervalBoundSource.STAMPED, null, false, true);
        List<GapSynthesis.Row> rows = rowsAsTheIssueListMakesThem(TimeRelation.IN_FORCE_AT_CUTOFF, "WEEKLY",
                List.of(released("w12", LocalDateTime.of(2027, 3, 26, 12, 0)), withdrawn,
                        released("w14", LocalDateTime.of(2027, 4, 9, 12, 0))),
                LocalDateTime.of(2027, 4, 10, 9, 0));
        assertEquals(List.of(
                        "MISSING 2027-03-26T12:00 to 2027-04-02T12:00",
                        "UPCOMING 2027-04-09T12:00 to 2027-04-16T12:00"),
                spans(rows));
    }

    /** The one-step cut-off by cadence agrees with the period length the draft and the release measure. */
    @Test
    void theNextCutoffByCadence_isTheCutoffPlusTheMeasuredPeriod() {
        for (String cadence : List.of("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) {
            for (LocalDateTime t : List.of(LocalDateTime.of(2027, 3, 27, 12, 0), LocalDateTime.of(2027, 10, 30, 12, 0),
                    LocalDateTime.of(2027, 1, 31, 12, 0), LocalDateTime.of(2028, 2, 29, 12, 0))) {
                Date from = at(t, CPH);
                assertEquals(GapDetection.nextCutoff(from, GapDetection.periodMillisOf(cadence, CPH, from)),
                        GapDetection.nextCutoff(from, cadence, CPH), cadence + " from " + t);
            }
        }
        assertNull(GapDetection.nextCutoff(at(LocalDateTime.of(2027, 3, 27, 12, 0), CPH), "NONE", CPH));
    }
}
