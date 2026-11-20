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

package org.niord.core.publication.series.resolve;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Naming and numbering. Pure, no database. */
public class IssueNamingTest {

    private static final ZoneId DK = ZoneId.of("Europe/Copenhagen");
    /** The week-numbered basis, which is what every weekly case below is named on. */
    private static final IssueNaming.YearBasis ISO = IssueNaming.YearBasis.ISO_WEEK_YEAR;

    private static Date at(int year, int month, int day, int hour, int minute) {
        return Date.from(ZonedDateTime.of(year, month, day, hour, minute, 0, 0, DK).toInstant());
    }

    // ------------------------------------------------------- the year boundary

    /**
     * The ISO week-YEAR, not the calendar year.
     *
     * A cut-off on 31 December 2025 belongs to ISO week 1 of 2026. Legacy pairs
     * the correct week with the calendar year and produces "EfS uge 1 - 2025",
     * which names the issue for a year it is not in.
     */
    @Test
    public void aCutoffInIsoWeekOneOfTheNextYearTakesTheNextYear() {
        IssueNaming.Numbers n = IssueNaming.derive(at(2025, 12, 31, 10, 0), null, DK, null, ISO);
        assertEquals(1, n.week(), "31.12.2025 is in ISO week 1");
        assertEquals(2026, n.year(),
                "the ISO week-year is 2026; taking the calendar year gives the legacy 'uge 1 - 2025' bug");
    }

    /** And the other direction: early January belonging to the previous week-year. */
    @Test
    public void aCutoffInEarlyJanuaryCanBelongToThePreviousWeekYear() {
        // 1 January 2027 falls in ISO week 53 of 2026.
        IssueNaming.Numbers n = IssueNaming.derive(at(2027, 1, 1, 10, 0), null, DK, null, ISO);
        assertEquals(53, n.week());
        assertEquals(2026, n.year(), "1.1.2027 is in ISO week 53 of 2026");
    }

    @Test
    public void weekFiftyTwoIsOrdinary() {
        IssueNaming.Numbers n = IssueNaming.derive(at(2026, 12, 24, 10, 0), null, DK, null, ISO);
        assertEquals(52, n.week());
        assertEquals(2026, n.year());
    }

    // ----------------------------------------------------------- the multi-week case

    /**
     * A window spanning two cadence PERIODS -- the double week over a holiday --
     * is named for both weeks it closed. The from-week is the first week no
     * other issue closed, not the week the previous cut-off happened to fall in.
     */
    @Test
    public void aWindowSpanningTwoWeeksCarriesBothNumbers() {
        Date from = at(2026, 1, 1, 10, 0);   // the previous cut-off, in week 1
        Date to = at(2026, 1, 15, 10, 0);    // two weeks later, week 3
        IssueNaming.Numbers n = IssueNaming.derive(to, from, DK, null, ISO);

        assertEquals(2, n.week(), "the first week this issue closed");
        assertEquals(3, n.weekTo(), "the cut-off week");
        assertEquals("Uge 2+3, 2026", "Uge " + n.week() + "+" + n.weekTo() + ", " + n.year());
    }

    /**
     * An ORDINARY week straddles two ISO weeks too -- every Wednesday-to-Wednesday
     * window does -- and is named for the one it closed in, with no second number.
     * Naming it "2+3" was the defect: it made every week of the year read as a
     * double week.
     */
    @Test
    public void anOrdinaryWeekStraddlingTwoIsoWeeksIsNotADoubleWeek() {
        Date from = at(2026, 1, 8, 10, 0);   // the previous cut-off, in week 2
        Date to = at(2026, 1, 15, 10, 0);    // one period later, week 3
        IssueNaming.Numbers n = IssueNaming.derive(to, from, DK, null, ISO);

        assertEquals(3, n.week(), "named for the week it closed in");
        assertNull(n.weekTo(), "a single period carries no second number");
    }

    /**
     * The issue is named for the END of the window, never the start.
     *
     * Naming from the start produces "Uge 26" where production says "uge 27".
     */
    @Test
    public void aSingleWeekWindowIsNamedForItsCutoffNotItsStart() {
        Date from = at(2026, 6, 29, 10, 0);
        Date to = at(2026, 7, 3, 10, 0);     // still week 27
        IssueNaming.Numbers n = IssueNaming.derive(to, from, DK, null, ISO);

        assertEquals(27, n.week());
        assertNull(n.weekTo(), "a single-week window carries no second number");
    }

    // ----------------------------------------------------- the token vocabulary

    /** One expansion case per token. A token with no case means the menu can offer something untested. */
    @Test
    public void everyDeclaredTokenExpands() {
        // Week 7, so the padded variants are visibly different from the plain ones.
        IssueNaming.Numbers n = IssueNaming.derive(at(2026, 2, 12, 9, 5), null, DK, 3, ISO);
        Map<String, String> values = IssueNaming.valuesOf(n);

        assertEquals(IssueNaming.TOKENS, values.keySet(),
                "the value map and the declared vocabulary disagree; one of them is a second source of truth");

        assertEquals("7", values.get("week"));
        assertEquals("07", values.get("week-2-digits"), "the padded variant must pad");
        assertEquals("2026", values.get("year"));
        assertEquals("26", values.get("year-2-digits"));
        assertEquals("2", values.get("month"));
        assertEquals("02", values.get("month-2-digits"));
        assertEquals("12", values.get("day"));
        assertEquals("12", values.get("day-2-digits"));
        assertEquals("3", values.get("edition"));

        for (String token : IssueNaming.TOKENS) {
            String expanded = IssueNaming.expand("x${" + token + "}x", n);
            assertFalse(expanded.contains("${"), token + " did not expand");
        }
    }

    /** The padded variants are IN, and production proves they are used: nm-w01-2026. */
    @Test
    public void theZeroPaddedVariantsAreAvailable() {
        IssueNaming.Numbers week1 = IssueNaming.derive(at(2026, 1, 2, 10, 0), null, DK, null, ISO);
        assertEquals("nm-w01-2026",
                IssueNaming.expand("nm-w${week-2-digits}-${year}", week1),
                "without the padded token, a pattern that wants 'w01' is inexpressible");
    }

    /** A DAILY series must be nameable, which is why the day tokens exist. */
    @Test
    public void aDailySeriesCanBeNamed() {
        IssueNaming.Numbers n = IssueNaming.derive(at(2026, 3, 5, 8, 0), null, DK, null, ISO);
        assertEquals("Dagens EfS 05.03.2026",
                IssueNaming.expand("Dagens EfS ${day-2-digits}.${month-2-digits}.${year}", n),
                "shipping a DAILY cadence with no way to name its issues is worse than either alternative");
    }

    // ------------------------------------------------------------------- S-14

    /**
     * Nothing of the form ${...} may survive expansion.
     *
     * Production serves a real PDF at .../Skydeomraader-%24%7Byear%7D.pdf today,
     * because an unexpanded token reached a file name and then a URL.
     */
    @Test
    public void anUnknownTokenFailsRatherThanSurvivingIntoTheOutput() {
        IssueNaming.Numbers n = IssueNaming.derive(at(2026, 2, 12, 9, 5), null, DK, null, ISO);

        IssueNaming.UnknownTokenException e = assertThrows(IssueNaming.UnknownTokenException.class,
                () -> IssueNaming.expand("Skydeomraader-${yeer}.pdf", n));
        assertEquals("yeer", e.token());
        assertEquals("UNKNOWN_TOKEN", e.code());

        assertFalse(IssueNaming.isExpandable("Skydeomraader-${yeer}.pdf"));
        assertTrue(IssueNaming.isExpandable("Skydeomraader-${year}.pdf"));
    }

    @Test
    public void aFullyExpandedPatternKeepsItsLiteralText() {
        IssueNaming.Numbers n = IssueNaming.derive(at(2026, 7, 8, 10, 0), null, DK, null, ISO);
        assertEquals("EfS-Uge-28-2026.pdf",
                IssueNaming.expand("EfS-Uge-${week}-${year}.pdf", n));
        assertEquals("EfS uge 28", IssueNaming.expand("EfS uge ${week}", n));
    }

    // ------------------------------------------------------------- the timezone

    /**
     * Derived in the series' timezone, never the JVM default. Around midnight the
     * two disagree about which day -- and therefore which week -- a cut-off is in.
     */
    @Test
    public void derivationUsesTheSeriesTimezone() {
        // 22:30 UTC on a Sunday is already Monday in Copenhagen, so the two zones
        // put this cut-off in different ISO weeks.
        Date cutoff = Date.from(ZonedDateTime.of(2026, 1, 4, 23, 30, 0, 0, ZoneId.of("UTC")).toInstant());

        IssueNaming.Numbers utc = IssueNaming.derive(cutoff, null, ZoneId.of("UTC"), null, ISO);
        IssueNaming.Numbers dk = IssueNaming.derive(cutoff, null, DK, null, ISO);

        assertEquals(1, utc.week(), "4.1.2026 23:30 UTC is still ISO week 1");
        assertEquals(2, dk.week(), "the same instant is 5.1 in Copenhagen, which is ISO week 2");
    }
    // ============================================ the deferred citation token

    /**
     * A citation format may carry ${parameters}; a file name may not.
     *
     * These are two different questions and one answer cannot serve both. Reject
     * ${parameters} for citations and no citable series can be saved at all --
     * S-14 fails the format and S-13 requires one. Accept it for file names and
     * the token reaches a public URL, which is how production came to serve a PDF
     * at .../Skydeomraader-%24%7Byear%7D.pdf.
     */
    @Test
    public void theCitationVocabularyAdmitsParametersAndTheStrictOneDoesNot() {
        String citation = "EfS ${week}/${year} ${parameters}";

        assertTrue(IssueNaming.isCitationExpandable(citation),
                "the canonical citation format was rejected; with S-13 also requiring a reference "
                        + "format, no citable series could be configured at all");
        assertFalse(IssueNaming.isExpandable(citation),
                "${parameters} was accepted by the strict vocabulary, so it can reach a file name "
                        + "and then a public URL");
    }

    /** Both vocabularies still reject a token that is in neither. */
    @Test
    public void neitherVocabularyAdmitsAnUnknownToken() {
        assertFalse(IssueNaming.isExpandable("EfS ${nope}"));
        assertFalse(IssueNaming.isCitationExpandable("EfS ${nope}"));
    }

    /** And expansion leaves the deferred token for the citation layer, expanding the rest. */
    @Test
    public void expandCitationLeavesOnlyTheDeferredToken() {
        IssueNaming.Numbers n = new IssueNaming.Numbers(33, null, 2017, 8, 15, null);

        assertEquals("EfS 33/2017 ${parameters}",
                IssueNaming.expandCitation("EfS ${week}/${year} ${parameters}", n));
    }

    /**
     * A single week released LATE is still one week. Production released several
     * weeks two to five days after their nominal close -- a window of ten or
     * twelve days -- and each is one issue named for the week it closed. Only a
     * window that swallowed most of a second period is a double week.
     */
    @Test
    public void aSingleWeekReleasedLateIsNotADoubleWeek() {
        Date from = at(2026, 7, 8, 9, 0);     // Wednesday, week 28 opened
        Date lateClose = at(2026, 7, 20, 9, 0); // released the Monday after next: twelve days
        IssueNaming.Numbers late = IssueNaming.derive(lateClose, from, DK, null, ISO);
        assertEquals(30, late.week(), "named for the week it closed in");
        assertNull(late.weekTo(), "twelve days is one late week, not two");

        Date doubleClose = at(2026, 7, 22, 9, 0); // a full second period: fourteen days
        IssueNaming.Numbers two = IssueNaming.derive(doubleClose, from, DK, null, ISO);
        assertEquals(29, two.week(), "the first week no other issue closed");
        assertEquals(30, two.weekTo());
    }

    // ------------------------------------------- NAMING-1: the weeks a gap closed

    /** A gap of one week, released a few hours early: the cut-off week alone. */
    @Test
    public void aGapOfOneWeekNamesTheCutoffWeek() {
        IssueNaming.Numbers n = IssueNaming.derive(at(2026, 9, 16, 6, 51), at(2026, 9, 9, 10, 21), DK, null, ISO);
        assertEquals(38, n.week());
        assertNull(n.weekTo());
    }

    /** A gap of two weeks, released a few hours early: both weeks after the previous cut-off's. */
    @Test
    public void aGapOfTwoWeeksNamesBothWeeks() {
        // The previous cut-off in week 35, this one in week 37, 13.85 days apart.
        IssueNaming.Numbers n = IssueNaming.derive(at(2026, 9, 9, 6, 51), at(2026, 8, 26, 10, 21), DK, null, ISO);
        assertEquals(36, n.week());
        assertEquals(37, n.weekTo());
    }

    /**
     * A gap of three weeks released a few hours early, as on the local rehearsal:
     * the window runs from week 35 into week 38, and weeks 36 and 37 lie wholly
     * inside it. Nineteen whole days floored to two periods and named the issue
     * 37+38, so week 36 was named by no issue.
     */
    @Test
    public void aGapOfThreeWeeksNamesEveryWeekItClosed() {
        IssueNaming.Numbers n = IssueNaming.derive(at(2026, 9, 15, 6, 51), at(2026, 8, 26, 10, 21), DK, null, ISO);
        assertEquals(36, n.week(), "week 36 lies wholly inside the window and no other issue closed it");
        assertEquals(38, n.weekTo());

        IssueNaming.Numbers onTime = IssueNaming.derive(at(2026, 9, 16, 10, 21), at(2026, 8, 26, 10, 21), DK,
                null, ISO);
        assertEquals(36, onTime.week());
        assertEquals(38, onTime.weekTo());
    }

    /**
     * The day count and the week count disagree: thirteen days and 22 hours, two
     * periods by the day count, but from Monday early of week 35 to Sunday late of
     * week 36. The previous issue closed week 35, so this one closed week 36 alone
     * and is named for it; counted in days it was named 35+36, a week the issue
     * before it had already been named for.
     */
    @Test
    public void twoPeriodsByDaysButOneIsoWeekClosedNamesTheCutoffWeekAlone() {
        IssueNaming.Numbers n = IssueNaming.derive(at(2026, 9, 6, 23, 0), at(2026, 8, 24, 0, 30), DK, null, ISO);
        assertEquals(36, n.week());
        assertNull(n.weekTo(), "week 35 was closed by the issue before this one");
    }

    /**
     * Across a year's end: 2026 has 53 ISO weeks. From week 52 of 2026 into week 2
     * of 2027 the issue closed weeks 53, 1 and 2, and takes the cut-off's week-year.
     */
    @Test
    public void aGapAcrossTheYearsEndNamesTheWeeksOfBothYears() {
        IssueNaming.Numbers three = IssueNaming.derive(at(2027, 1, 12, 6, 0), at(2026, 12, 23, 10, 0), DK, null,
                ISO);
        assertEquals(53, three.week(), "week 53 of 2026 lies wholly inside the window");
        assertEquals(2, three.weekTo());
        assertEquals(2027, three.year());

        IssueNaming.Numbers two = IssueNaming.derive(at(2027, 1, 6, 10, 0), at(2026, 12, 23, 10, 0), DK, null, ISO);
        assertEquals(53, two.week());
        assertEquals(1, two.weekTo());
        assertEquals(2027, two.year());
    }

    // ------------------------------- the printed span: a pair with "+", three weeks or more as a range

    private static final String NAME = "Uge ${week}+${weekTo}, ${year}";

    /**
     * An issue that closed three weeks prints its first and its last week as a range, "36-38". Joined with "+" it
     * read "36+38", which the domain rules define as a pair: a mariner reads weeks 36 and 38. Only the "+" between
     * the two week tokens is written as a hyphen, in the name, the file name (either variant of the tokens) and the
     * citation format alike, with or without spaces around it.
     */
    @Test
    public void anIssueOfThreeWeeksPrintsARange() {
        IssueNaming.Numbers n = IssueNaming.derive(at(2026, 9, 15, 6, 51), at(2026, 8, 26, 10, 21), DK, null, ISO);
        assertEquals(3, IssueNaming.weeksNamed(n));

        assertEquals("Uge 36-38, 2026", IssueNaming.expand(NAME, n));
        assertEquals("nm-w36-38-2026.pdf",
                IssueNaming.expand("nm-w${week-2-digits}+${weekTo-2-digits}-${year}.pdf", n));
        assertEquals("EfS uge 36 - 38/2026 ${parameters}",
                IssueNaming.expandCitation("EfS uge ${week} + ${weekTo}/${year} ${parameters}", n));
        assertEquals("1+2 36-38", IssueNaming.expand("1+2 ${week}+${weekTo}", n),
                "a '+' anywhere else in the pattern is printed as written");

        // A stored issue, read back by its numbers alone, prints the same; four weeks are a range as well.
        assertEquals("Uge 36-38, 2026", IssueNaming.expand(NAME, new IssueNaming.Numbers(36, 38, 2026, 9, 15, null)));
        assertEquals("Uge 35-38, 2026", IssueNaming.expand(NAME, new IssueNaming.Numbers(35, 38, 2026, 9, 15, null)));
    }

    /** A pair keeps "+", and a single week is unchanged. */
    @Test
    public void aPairKeepsThePlusAndASingleWeekIsUnchanged() {
        IssueNaming.Numbers pair = IssueNaming.derive(at(2026, 9, 9, 6, 51), at(2026, 8, 26, 10, 21), DK, null, ISO);
        assertEquals(2, IssueNaming.weeksNamed(pair));
        assertEquals("Uge 36+37, 2026", IssueNaming.expand(NAME, pair));
        assertEquals("EfS uge 36+37/2026 ${parameters}",
                IssueNaming.expandCitation("EfS uge ${week}+${weekTo}/${year} ${parameters}", pair));

        IssueNaming.Numbers single = IssueNaming.derive(at(2026, 9, 16, 6, 51), at(2026, 9, 9, 10, 21), DK, null,
                ISO);
        assertEquals(1, IssueNaming.weeksNamed(single));
        assertEquals("Uge 38, 2026", IssueNaming.expand("Uge ${week}, ${year}", single));
    }

    /**
     * Across a year's end the range runs from the last week of the earlier year to the closing week and is named
     * for the later year: weeks 52 of 2025, 1 and 2 of 2026 print "52-2" beside 2026. Two weeks across it stay a
     * pair, "52+1". The count knows that 2026 has a week 53: weeks 52 of 2026 to 1 of 2027 are three weeks, "52-1",
     * and 53 of 2026 to 2 of 2027 as well, "53-2".
     */
    @Test
    public void aRangeAcrossTheYearsEndNamesTheLaterYear() {
        IssueNaming.Numbers range = IssueNaming.derive(at(2026, 1, 7, 10, 0), at(2025, 12, 17, 10, 0), DK, null,
                ISO);
        assertEquals(52, range.week());
        assertEquals(2, range.weekTo());
        assertEquals(2026, range.year());
        assertEquals(3, IssueNaming.weeksNamed(range));
        assertEquals("Uge 52-2, 2026", IssueNaming.expand(NAME, range));

        IssueNaming.Numbers pair = IssueNaming.derive(at(2025, 12, 31, 10, 0), at(2025, 12, 17, 10, 0), DK, null,
                ISO);
        assertEquals(52, pair.week());
        assertEquals(1, pair.weekTo());
        assertEquals(2026, pair.year());
        assertEquals("Uge 52+1, 2026", IssueNaming.expand(NAME, pair));

        IssueNaming.Numbers overWeek53 = IssueNaming.derive(at(2027, 1, 6, 10, 0), at(2026, 12, 16, 10, 0), DK,
                null, ISO);
        assertEquals(52, overWeek53.week());
        assertEquals(1, overWeek53.weekTo());
        assertEquals(2027, overWeek53.year());
        assertEquals(3, IssueNaming.weeksNamed(overWeek53), "2026 has 53 ISO weeks: 52, 53 and 1");
        assertEquals("Uge 52-1, 2027", IssueNaming.expand(NAME, overWeek53));

        IssueNaming.Numbers fromWeek53 = IssueNaming.derive(at(2027, 1, 12, 6, 0), at(2026, 12, 23, 10, 0), DK,
                null, ISO);
        assertEquals("Uge 53-2, 2027", IssueNaming.expand(NAME, fromWeek53));
        assertEquals("Uge 53+1, 2027", IssueNaming.expand(NAME, new IssueNaming.Numbers(53, 1, 2027, 1, 6, null)));
    }

    // ------------------------------------------------------------- ${year}

    /**
     * A publication that is NOT numbered by week takes the calendar year.
     *
     * The ISO answer is right for a weekly issue and wrong for everything else,
     * and the annual editions are where it bites. The accumulated list closes at
     * 31 December 23:59 and is the edition FOR that year; the in-force edition
     * opens at 1 January and is the edition FOR that year. Under the ISO
     * week-year the first is named for the January after it, and the year is part
     * of the file name and therefore of the public download link -- so the
     * January-2027 edition would collide with the real 2026 one.
     */
    @Test
    public void anAnnualEditionIsNamedForTheYearItCloses() {
        Date newYearsEve = at(2025, 12, 31, 23, 59);
        assertEquals(2026, IssueNaming.derive(newYearsEve, null, DK, null,
                        IssueNaming.YearBasis.ISO_WEEK_YEAR).year(),
                "a week-numbered issue closing on 31.12.2025 is in ISO week-year 2026");
        assertEquals(2025, IssueNaming.derive(newYearsEve, null, DK, null,
                        IssueNaming.YearBasis.CALENDAR_YEAR).year(),
                "an annual edition closing on 31.12.2025 is the 2025 edition, not the 2026 one");

        // And the mirror, which is the file-name collision the review found: a
        // period-start cut-off on 1 January 2027 falls in ISO week 53 of 2026.
        Date newYearsDay = at(2027, 1, 1, 0, 0);
        assertEquals(2026, IssueNaming.derive(newYearsDay, null, DK, null,
                        IssueNaming.YearBasis.ISO_WEEK_YEAR).year(),
                "1.1.2027 falls in ISO week-year 2026");
        assertEquals(2027, IssueNaming.derive(newYearsDay, null, DK, null,
                        IssueNaming.YearBasis.CALENDAR_YEAR).year(),
                "the edition in force from 1.1.2027 is the 2027 edition");
    }

    /** The week is the ISO week either way; only the year token moves. */
    @Test
    public void theYearBasisDoesNotMoveTheWeek() {
        Date d = at(2025, 12, 31, 10, 0);
        assertEquals(IssueNaming.derive(d, null, DK, null, IssueNaming.YearBasis.ISO_WEEK_YEAR).week(),
                IssueNaming.derive(d, null, DK, null, IssueNaming.YearBasis.CALENDAR_YEAR).week());
    }

    /**
     * An unstated basis still pairs with a week, which is the safe half of the
     * pair -- but no caller may reach it by omission.
     *
     * The overload that defaulted it is gone. It existed, and four naming paths
     * used it without ever saying which year they meant, so an annual period
     * closing on 31 December 2025 was named "2026" by each of them while the
     * issue's own stored year said 2025. A null passed deliberately still behaves;
     * a caller that simply forgot no longer compiles.
     */
    @Test
    public void anUnstatedBasisStillPairsWithTheWeek() {
        Date d = at(2025, 12, 31, 10, 0);
        assertEquals(IssueNaming.derive(d, null, DK, null, ISO).year(),
                IssueNaming.derive(d, null, DK, null, null).year());
    }

    // ------------------------------- the weeks the reports print: the week part of the name

    /** A pattern that names the first week alone. */
    private static final String FIRST_WEEK_ONLY = "Uge ${week}, ${year}";

    /** A pattern that writes a pair with a word between the weeks. */
    private static final String PAIR_IN_WORDS = "Uge ${week} og ${weekTo}, ${year}";

    /** The name a pattern gives the issue of these weeks, by the expansion the issue's name is made with */
    private static String nameOf(String pattern, int week, Integer weekTo, int year) {
        return IssueNaming.expand(pattern, new IssueNaming.Numbers(week, weekTo, year, 9, 15, null));
    }

    /** The week part of a pattern is the stretch from its first week token to its last, and nothing beyond it. */
    @Test
    public void theWeekPartOfAPatternRunsFromItsFirstWeekTokenToItsLast() {
        assertEquals("${week}+${weekTo}", IssueNaming.weekSpanOf(NAME));
        assertEquals("${week}", IssueNaming.weekSpanOf(FIRST_WEEK_ONLY));
        assertEquals("${week} og ${weekTo}", IssueNaming.weekSpanOf(PAIR_IN_WORDS));
        assertEquals("${week-2-digits}+${weekTo-2-digits}",
                IssueNaming.weekSpanOf("nm-w${week-2-digits}+${weekTo-2-digits}-${year}.pdf"));
        // In another order, and with a token twice: taken whole.
        assertEquals("${weekTo} back to ${week}", IssueNaming.weekSpanOf("NtM ${weekTo} back to ${week}"));
        assertEquals("${week} (${week-2-digits}", IssueNaming.weekSpanOf("Week ${week} (${week-2-digits}) ${year}"));
        // A week token after another token is not part of the weeks, which would then print the year.
        assertEquals("${week}", IssueNaming.weekSpanOf("Uge ${week}, ${year} (${weekTo})"));
        // No week at all.
        assertNull(IssueNaming.weekSpanOf("Efterretninger for søfarende ${year}"));
        assertNull(IssueNaming.weekSpanOf("A name without a token"));
        assertNull(IssueNaming.weekSpanOf(null));
    }

    /**
     * The weeks the report templates print are the weeks of the issue's name, as the series' own pattern writes
     * them, the same characters: a series named "Uge ${week}, ${year}" names an issue of weeks 36 to 38
     * "Uge 36, 2026" and is headed "36"; one named "Uge ${week} og ${weekTo}" is headed "36 og 37" for a pair; the
     * pair form "36+37" and "36-38". FAILS on the rule that printed the pair form whatever the pattern said, which
     * headed the first two "36-38" and "36+37".
     */
    @Test
    public void theWeeksTextIsTheWeekPartOfTheIssuesName() {
        assertEquals("36", IssueNaming.weeksText(FIRST_WEEK_ONLY, 36, 38, 2026));
        assertEquals("36", IssueNaming.weeksText(FIRST_WEEK_ONLY, 36, 37, 2026));
        assertEquals("36 og 37", IssueNaming.weeksText(PAIR_IN_WORDS, 36, 37, 2026));
        assertEquals("36 og 38", IssueNaming.weeksText(PAIR_IN_WORDS, 36, 38, 2026),
                "only the pair form's '+' prints as a range; a word between the weeks is printed as written");
        assertEquals("36+37", IssueNaming.weeksText(NAME, 36, 37, 2026));
        assertEquals("36-38", IssueNaming.weeksText(NAME, 36, 38, 2026));
        assertEquals("07+08", IssueNaming.weeksText("nm-w${week-2-digits}+${weekTo-2-digits}", 7, 8, 2026));
        assertEquals("37 back to 36", IssueNaming.weeksText("NtM ${weekTo} back to ${week}", 36, 37, 2026));
        assertEquals("36", IssueNaming.weeksText("Uge ${week}, ${year} (${weekTo})", 36, 38, 2026));

        // The same characters as the name, for every kind of pattern and span.
        for (String pattern : List.of(NAME, FIRST_WEEK_ONLY, PAIR_IN_WORDS)) {
            for (Integer weekTo : Arrays.asList(null, 37, 38)) {
                String name = nameOf(pattern, 36, weekTo, 2026);
                String prefix = pattern.substring(0, pattern.indexOf("${"));
                String suffix = ", 2026";
                assertEquals(name, prefix + IssueNaming.weeksText(pattern, 36, weekTo, 2026) + suffix,
                        "the weeks of " + pattern + " are not the name's");
            }
        }
    }

    /**
     * Across a year's end the year decides the range, as in the name: 2025 has 52 ISO weeks, so weeks 52 to 1 of
     * an issue named for 2026 are a pair, while 2026 has a week 53, so weeks 52 to 1 of 2027 are three. A pattern
     * that writes the weeks in words prints them as written.
     */
    @Test
    public void theWeeksAcrossTheYearsEndAreTheNamesWeeks() {
        assertEquals("52+1", IssueNaming.weeksText(NAME, 52, 1, 2026));
        assertEquals("52-2", IssueNaming.weeksText(NAME, 52, 2, 2026));
        assertEquals("52-1", IssueNaming.weeksText(NAME, 52, 1, 2027));
        assertEquals("52 og 2", IssueNaming.weeksText(PAIR_IN_WORDS, 52, 2, 2026));
        assertEquals("52", IssueNaming.weeksText(FIRST_WEEK_ONLY, 52, 2, 2026));
        assertEquals("Uge 52-2, 2026", nameOf(NAME, 52, 2, 2026));
    }

    /**
     * A pattern that writes no week, or none at all, gives the pair form, so the report is still headed by all the
     * issue's weeks: the week alone, "36+37" for a pair, "36-38" for three weeks or more. So does a week part that
     * expands to nothing, a lone ${weekTo} for a single week. A closing week equal to the first is one week.
     */
    @Test
    public void aPatternWithoutAWeekGivesThePairForm() {
        for (String pattern : Arrays.asList(null, "", "Efterretninger for søfarende ${year}")) {
            assertEquals("36", IssueNaming.weeksText(pattern, 36, null, 2026));
            assertEquals("36", IssueNaming.weeksText(pattern, 36, 36, 2026),
                    "a closing week equal to the first is one week");
            assertEquals("36+37", IssueNaming.weeksText(pattern, 36, 37, 2026));
            assertEquals("36-38", IssueNaming.weeksText(pattern, 36, 38, 2026));
            assertEquals("52+1", IssueNaming.weeksText(pattern, 52, 1, 2026));
            assertEquals("52-2", IssueNaming.weeksText(pattern, 52, 2, 2026));
        }
        assertEquals("36", IssueNaming.weeksText("Uge ${weekTo}", 36, null, 2026),
                "a week part that expands to nothing names no week");
        assertEquals("37", IssueNaming.weeksText("Uge ${weekTo}", 36, 37, 2026),
                "a lone closing week that expands is printed as the name prints it");
        assertNull(IssueNaming.weeksText(NAME, null, 37, 2026));
        assertNull(IssueNaming.weeksText(null, null, null, 2026));
    }
}
