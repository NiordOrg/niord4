/*
 * Copyright 2024 Danish Maritime Authority.
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
package org.niord.core.util;

import org.junit.jupiter.api.Test;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TimeUtils}.
 *
 * <p>The class exposes static date/time helpers. To keep the assertions deterministic, dates are
 * built either from a {@link GregorianCalendar} in the JVM default time zone (for the
 * {@code Calendar}-based helpers, which themselves use {@code Calendar.getInstance()} in the default
 * zone) or from a {@link SimpleDateFormat} pinned to an explicit time zone (for the UTC/zone-aware
 * helpers).</p>
 */
public class TimeUtilsTest {

    // ------------------------------------------------------------------
    // Deterministic date builders
    // ------------------------------------------------------------------

    /** Builds a Date in the JVM default time zone from y/m(1-based)/d h:m:s.ms. */
    private static Date localDate(int year, int month1Based, int day,
                                  int hour, int minute, int second, int millis) {
        Calendar cal = new GregorianCalendar();
        cal.clear();
        cal.set(year, month1Based - 1, day, hour, minute, second);
        cal.set(Calendar.MILLISECOND, millis);
        return cal.getTime();
    }

    /** Builds a Date from "yyyy-MM-dd HH:mm:ss" interpreted in the given time zone. */
    private static Date dateInZone(String text, String tzId) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone(tzId));
        try {
            return sdf.parse(text);
        } catch (ParseException e) {
            throw new IllegalStateException("Bad test date: " + text, e);
        }
    }

    /** Reads a single Calendar field from a Date in the JVM default time zone. */
    private static int field(Date date, int calField) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        return cal.get(calField);
    }

    // ------------------------------------------------------------------
    // resetTime(Calendar)
    // ------------------------------------------------------------------

    @Test
    public void resetTimeCalendar_zeroesHourMinuteSecondMillis() {
        Calendar cal = new GregorianCalendar();
        cal.set(2020, Calendar.MARCH, 15, 13, 47, 33);
        cal.set(Calendar.MILLISECOND, 512);

        Calendar result = TimeUtils.resetTime(cal);

        assertEquals(0, result.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, result.get(Calendar.MINUTE));
        assertEquals(0, result.get(Calendar.SECOND));
        assertEquals(0, result.get(Calendar.MILLISECOND));
        // The date part must be preserved.
        assertEquals(2020, result.get(Calendar.YEAR));
        assertEquals(Calendar.MARCH, result.get(Calendar.MONTH));
        assertEquals(15, result.get(Calendar.DAY_OF_MONTH));
    }

    @Test
    public void resetTimeCalendar_nullReturnsNull() {
        assertNull(TimeUtils.resetTime((Calendar) null));
    }

    // ------------------------------------------------------------------
    // resetTime(Date)
    // ------------------------------------------------------------------

    @Test
    public void resetTimeDate_zeroesTimePartInDefaultZone() {
        Date input = localDate(2021, 7, 9, 18, 5, 42, 999);

        Date result = TimeUtils.resetTime(input);

        assertEquals(0, field(result, Calendar.HOUR_OF_DAY));
        assertEquals(0, field(result, Calendar.MINUTE));
        assertEquals(0, field(result, Calendar.SECOND));
        assertEquals(0, field(result, Calendar.MILLISECOND));
        assertEquals(2021, field(result, Calendar.YEAR));
        assertEquals(Calendar.JULY, field(result, Calendar.MONTH));
        assertEquals(9, field(result, Calendar.DAY_OF_MONTH));
    }

    @Test
    public void resetTimeDate_nullReturnsNull() {
        assertNull(TimeUtils.resetTime((Date) null));
    }

    // ------------------------------------------------------------------
    // resetTime(Date, TimeZone)
    // ------------------------------------------------------------------

    @Test
    public void resetTimeDateZone_resetsToMidnightInThatZone() {
        // 2017-04-12 13:00 UTC. In UTC, midnight of that day is 2017-04-12 00:00 UTC.
        Date input = dateInZone("2017-04-12 13:00:00", "UTC");

        Date result = TimeUtils.resetTime(input, TimeZone.getTimeZone("UTC"));

        assertEquals(dateInZone("2017-04-12 00:00:00", "UTC"), result);
    }

    @Test
    public void resetTimeDateZone_nullTimeZoneFallsBackToDefaultZoneReset() {
        Date input = localDate(2019, 11, 20, 22, 30, 15, 250);

        Date result = TimeUtils.resetTime(input, null);

        // With a null zone, the implementation delegates to resetTime(Date) (default zone).
        assertEquals(TimeUtils.resetTime(input), result);
    }

    @Test
    public void resetTimeDateZone_nullDateReturnsNull() {
        assertNull(TimeUtils.resetTime(null, TimeZone.getTimeZone("UTC")));
    }

    // ------------------------------------------------------------------
    // resetSeconds(Calendar) / resetSeconds(Date)
    // ------------------------------------------------------------------

    @Test
    public void resetSecondsCalendar_zeroesSecondsAndMillisButKeepsMinute() {
        Calendar cal = new GregorianCalendar();
        cal.set(2020, Calendar.MARCH, 15, 13, 47, 33);
        cal.set(Calendar.MILLISECOND, 512);

        Calendar result = TimeUtils.resetSeconds(cal);

        assertEquals(13, result.get(Calendar.HOUR_OF_DAY));
        assertEquals(47, result.get(Calendar.MINUTE));
        assertEquals(0, result.get(Calendar.SECOND));
        assertEquals(0, result.get(Calendar.MILLISECOND));
    }

    @Test
    public void resetSecondsCalendar_nullReturnsNull() {
        assertNull(TimeUtils.resetSeconds((Calendar) null));
    }

    @Test
    public void resetSecondsDate_zeroesSecondsAndMillisButKeepsHourMinute() {
        Date input = localDate(2022, 1, 31, 6, 22, 59, 888);

        Date result = TimeUtils.resetSeconds(input);

        assertEquals(6, field(result, Calendar.HOUR_OF_DAY));
        assertEquals(22, field(result, Calendar.MINUTE));
        assertEquals(0, field(result, Calendar.SECOND));
        assertEquals(0, field(result, Calendar.MILLISECOND));
    }

    @Test
    public void resetSecondsDate_nullReturnsNull() {
        assertNull(TimeUtils.resetSeconds((Date) null));
    }

    // ------------------------------------------------------------------
    // endOfDay(Date) / endOfDay(Date, TimeZone)
    // ------------------------------------------------------------------

    @Test
    public void endOfDay_setsTimeTo235959InDefaultZone() {
        Date input = localDate(2020, 6, 10, 9, 15, 0, 0);

        Date result = TimeUtils.endOfDay(input);

        assertEquals(23, field(result, Calendar.HOUR_OF_DAY));
        assertEquals(59, field(result, Calendar.MINUTE));
        assertEquals(59, field(result, Calendar.SECOND));
        // Same calendar day.
        assertEquals(2020, field(result, Calendar.YEAR));
        assertEquals(Calendar.JUNE, field(result, Calendar.MONTH));
        assertEquals(10, field(result, Calendar.DAY_OF_MONTH));
    }

    @Test
    public void endOfDay_nullReturnsNull() {
        assertNull(TimeUtils.endOfDay(null));
    }

    @Test
    public void endOfDayZone_setsTimeTo235959InThatZone() {
        Date input = dateInZone("2017-04-12 13:00:00", "UTC");

        Date result = TimeUtils.endOfDay(input, TimeZone.getTimeZone("UTC"));

        assertEquals(dateInZone("2017-04-12 23:59:59", "UTC"), result);
    }

    @Test
    public void endOfDayZone_nullTimeZoneFallsBackToDefaultZone() {
        Date input = localDate(2018, 2, 14, 7, 8, 9, 100);

        Date result = TimeUtils.endOfDay(input, null);

        assertEquals(TimeUtils.endOfDay(input), result);
    }

    @Test
    public void endOfDayZone_nullDateReturnsNull() {
        assertNull(TimeUtils.endOfDay(null, TimeZone.getTimeZone("UTC")));
    }

    // ------------------------------------------------------------------
    // getCalendarField(Date, int)
    // ------------------------------------------------------------------

    @Test
    public void getCalendarField_returnsYear() {
        Date input = localDate(2023, 9, 4, 12, 0, 0, 0);
        assertEquals(Integer.valueOf(2023), TimeUtils.getCalendarField(input, Calendar.YEAR));
    }

    @Test
    public void getCalendarField_returnsMonthZeroBased() {
        // September is month index 8 (zero-based).
        Date input = localDate(2023, 9, 4, 12, 0, 0, 0);
        assertEquals(Integer.valueOf(Calendar.SEPTEMBER), TimeUtils.getCalendarField(input, Calendar.MONTH));
    }

    @Test
    public void getCalendarField_returnsDayOfMonth() {
        Date input = localDate(2023, 9, 4, 12, 0, 0, 0);
        assertEquals(Integer.valueOf(4), TimeUtils.getCalendarField(input, Calendar.DAY_OF_MONTH));
    }

    @Test
    public void getCalendarField_nullDateReturnsNull() {
        assertNull(TimeUtils.getCalendarField(null, Calendar.YEAR));
    }

    // ------------------------------------------------------------------
    // add(Date, int, int)
    // ------------------------------------------------------------------

    @Test
    public void add_advancesOneDay() {
        Date input = localDate(2020, 1, 31, 10, 0, 0, 0);

        Date result = TimeUtils.add(input, Calendar.DATE, 1);

        // 31 Jan + 1 day = 1 Feb, time preserved.
        assertEquals(2020, field(result, Calendar.YEAR));
        assertEquals(Calendar.FEBRUARY, field(result, Calendar.MONTH));
        assertEquals(1, field(result, Calendar.DAY_OF_MONTH));
        assertEquals(10, field(result, Calendar.HOUR_OF_DAY));
    }

    @Test
    public void add_negativeAmountSubtracts() {
        Date input = localDate(2020, 3, 1, 0, 0, 0, 0);

        Date result = TimeUtils.add(input, Calendar.DATE, -1);

        // 1 Mar 2020 - 1 day = 29 Feb 2020 (leap year).
        assertEquals(Calendar.FEBRUARY, field(result, Calendar.MONTH));
        assertEquals(29, field(result, Calendar.DAY_OF_MONTH));
    }

    @Test
    public void add_yearFieldAdvancesYear() {
        Date input = localDate(2020, 5, 15, 8, 30, 0, 0);

        Date result = TimeUtils.add(input, Calendar.YEAR, 3);

        assertEquals(2023, field(result, Calendar.YEAR));
        assertEquals(Calendar.MAY, field(result, Calendar.MONTH));
        assertEquals(15, field(result, Calendar.DAY_OF_MONTH));
    }

    @Test
    public void add_nullDateReturnsNull() {
        assertNull(TimeUtils.add(null, Calendar.DATE, 1));
    }

    // ------------------------------------------------------------------
    // getDate(Integer, Integer, Integer)
    // ------------------------------------------------------------------

    @Test
    public void getDate_returnsMidnightOfGivenYearMonthDay() {
        // Calendar MONTH is zero-based, so 0 = January.
        Date result = TimeUtils.getDate(2020, 0, 1);

        assertEquals(2020, field(result, Calendar.YEAR));
        assertEquals(Calendar.JANUARY, field(result, Calendar.MONTH));
        assertEquals(1, field(result, Calendar.DAY_OF_MONTH));
        assertEquals(0, field(result, Calendar.HOUR_OF_DAY));
        assertEquals(0, field(result, Calendar.MINUTE));
        assertEquals(0, field(result, Calendar.SECOND));
        assertEquals(0, field(result, Calendar.MILLISECOND));
    }

    @Test
    public void getDate_respectsExplicitMonthAndDay() {
        // December (month index 11), day 25.
        Date result = TimeUtils.getDate(1999, 11, 25);

        assertEquals(1999, field(result, Calendar.YEAR));
        assertEquals(Calendar.DECEMBER, field(result, Calendar.MONTH));
        assertEquals(25, field(result, Calendar.DAY_OF_MONTH));
    }

    @Test
    public void getDate_nullYearKeepsCurrentYearButSetsMonthDay() {
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);

        Date result = TimeUtils.getDate(null, 0, 1);

        assertEquals(currentYear, field(result, Calendar.YEAR));
        assertEquals(Calendar.JANUARY, field(result, Calendar.MONTH));
        assertEquals(1, field(result, Calendar.DAY_OF_MONTH));
    }

    // ------------------------------------------------------------------
    // sameDate(Date, Date)
    // ------------------------------------------------------------------

    @Test
    public void sameDate_sameDayDifferentTimeIsTrue() {
        Date morning = localDate(2020, 4, 12, 1, 0, 0, 0);
        Date evening = localDate(2020, 4, 12, 23, 0, 0, 0);
        assertTrue(TimeUtils.sameDate(morning, evening));
    }

    @Test
    public void sameDate_differentDayIsFalse() {
        Date day1 = localDate(2020, 4, 12, 12, 0, 0, 0);
        Date day2 = localDate(2020, 4, 13, 12, 0, 0, 0);
        assertFalse(TimeUtils.sameDate(day1, day2));
    }

    @Test
    public void sameDate_firstNullIsFalse() {
        Date date = localDate(2020, 4, 12, 12, 0, 0, 0);
        assertFalse(TimeUtils.sameDate(null, date));
    }

    @Test
    public void sameDate_secondNullIsFalse() {
        Date date = localDate(2020, 4, 12, 12, 0, 0, 0);
        assertFalse(TimeUtils.sameDate(date, null));
    }

    @Test
    public void sameDate_bothNullIsFalse() {
        assertFalse(TimeUtils.sameDate(null, null));
    }

    // ------------------------------------------------------------------
    // sameDate(Date, Date, TimeZone)
    // ------------------------------------------------------------------

    @Test
    public void sameDateZone_sameLocalDayInZoneIsTrue() {
        // Both instants fall on 2017-04-12 in UTC.
        Date d1 = dateInZone("2017-04-12 00:30:00", "UTC");
        Date d2 = dateInZone("2017-04-12 23:30:00", "UTC");
        assertTrue(TimeUtils.sameDate(d1, d2, TimeZone.getTimeZone("UTC")));
    }

    @Test
    public void sameDateZone_zoneShiftSplitsTheCalendarDay() {
        // 2017-04-12 23:30 UTC is still 12 Apr in UTC, but already 13 Apr in UTC+2.
        Date base = dateInZone("2017-04-12 23:30:00", "UTC");
        Date other = dateInZone("2017-04-12 22:30:00", "UTC");

        // In UTC both are 12 Apr -> same date.
        assertTrue(TimeUtils.sameDate(base, other, TimeZone.getTimeZone("UTC")));
        // In UTC+2, base becomes 13 Apr 01:30 while other is 13 Apr 00:30 -> still same local day.
        assertTrue(TimeUtils.sameDate(base, other, TimeZone.getTimeZone("GMT+2")));
    }

    @Test
    public void sameDateZone_differentLocalDayInZoneIsFalse() {
        // 2017-04-12 23:30 UTC -> 13 Apr 01:30 in UTC+2.
        Date late = dateInZone("2017-04-12 23:30:00", "UTC");
        // 2017-04-12 20:30 UTC -> 12 Apr 22:30 in UTC+2.
        Date early = dateInZone("2017-04-12 20:30:00", "UTC");
        assertFalse(TimeUtils.sameDate(late, early, TimeZone.getTimeZone("GMT+2")));
    }

    @Test
    public void sameDateZone_nullTimeZoneFallsBackToDefaultZoneComparison() {
        Date morning = localDate(2020, 4, 12, 1, 0, 0, 0);
        Date evening = localDate(2020, 4, 12, 23, 0, 0, 0);
        assertTrue(TimeUtils.sameDate(morning, evening, null));
    }

    @Test
    public void sameDateZone_firstNullIsFalse() {
        Date date = dateInZone("2017-04-12 12:00:00", "UTC");
        assertFalse(TimeUtils.sameDate(null, date, TimeZone.getTimeZone("UTC")));
    }

    // ------------------------------------------------------------------
    // sameDateHourMinute(Date, Date)
    // ------------------------------------------------------------------

    @Test
    public void sameDateHourMinute_sameMinuteDifferentSecondsIsTrue() {
        Date a = localDate(2020, 4, 12, 14, 30, 5, 0);
        Date b = localDate(2020, 4, 12, 14, 30, 55, 999);
        assertTrue(TimeUtils.sameDateHourMinute(a, b));
    }

    @Test
    public void sameDateHourMinute_differentMinuteIsFalse() {
        Date a = localDate(2020, 4, 12, 14, 30, 0, 0);
        Date b = localDate(2020, 4, 12, 14, 31, 0, 0);
        assertFalse(TimeUtils.sameDateHourMinute(a, b));
    }

    @Test
    public void sameDateHourMinute_differentHourIsFalse() {
        Date a = localDate(2020, 4, 12, 14, 30, 0, 0);
        Date b = localDate(2020, 4, 12, 15, 30, 0, 0);
        assertFalse(TimeUtils.sameDateHourMinute(a, b));
    }

    @Test
    public void sameDateHourMinute_nullIsFalse() {
        Date a = localDate(2020, 4, 12, 14, 30, 0, 0);
        assertFalse(TimeUtils.sameDateHourMinute(a, null));
        assertFalse(TimeUtils.sameDateHourMinute(null, a));
    }

    // ------------------------------------------------------------------
    // getNumberOfWeeksInYear(int)
    // ------------------------------------------------------------------

    @Test
    public void getNumberOfWeeksInYear_matchesIso8601WeekCount() {
        // ISO-8601 years have either 52 or 53 weeks. 2020 and 2026 are 53-week years; the
        // surrounding years (e.g. 2021, 2025) have 52 weeks.
        assertEquals(53, TimeUtils.getNumberOfWeeksInYear(2020));
        assertEquals(52, TimeUtils.getNumberOfWeeksInYear(2021));
        assertEquals(52, TimeUtils.getNumberOfWeeksInYear(2025));
        assertEquals(53, TimeUtils.getNumberOfWeeksInYear(2026));
    }

    @Test
    public void getNumberOfWeeksInYear_matchesJavaTimeReferenceAcrossManyYears() {
        // Cross-check against an independent java.time reference. By ISO definition December
        // 28th is always in the last week of its own week-year, so its week number equals the
        // total number of weeks in that year. This also covers years whose Dec 31 spills into
        // week 1 of the next week-year (e.g. 2018), which the old implementation got wrong.
        for (int year = 2000; year <= 2040; year++) {
            int expected = LocalDate.of(year, 12, 28)
                    .get(WeekFields.ISO.weekOfWeekBasedYear());
            assertEquals(expected, TimeUtils.getNumberOfWeeksInYear(year), "weeks in " + year);
        }
    }

    // ------------------------------------------------------------------
    // getISO8601WeekOfYear(Date)
    // ------------------------------------------------------------------

    @Test
    public void getISO8601WeekOfYear_jan1_2021IsWeek53OfPriorYear() {
        // 1 Jan 2021 is a Friday; under ISO-8601 (Monday-based, min 4 days in first week) it
        // belongs to week 53 of 2020.
        Date jan1 = localDate(2021, 1, 1, 12, 0, 0, 0);
        assertEquals(53, TimeUtils.getISO8601WeekOfYear(jan1));
    }

    @Test
    public void getISO8601WeekOfYear_jan4IsAlwaysWeek1() {
        // ISO-8601 guarantees 4 January falls in week 1 of its year.
        Date jan4 = localDate(2021, 1, 4, 12, 0, 0, 0);
        assertEquals(1, TimeUtils.getISO8601WeekOfYear(jan4));
    }

    @Test
    public void getISO8601WeekOfYear_midYearWeek() {
        // 2 Jul 2020 lies in ISO week 27 of 2020.
        Date midYear = localDate(2020, 7, 2, 12, 0, 0, 0);
        assertEquals(27, TimeUtils.getISO8601WeekOfYear(midYear));
    }

    // ------------------------------------------------------------------
    // formatNavtexDate(Date)
    // ------------------------------------------------------------------

    @Test
    public void formatNavtexDate_formatsUtcInstantInNavtexLayout() {
        // 2017-04-12 13:00 UTC -> "121300 UTC Apr 17".
        Date date = dateInZone("2017-04-12 13:00:00", "UTC");
        assertEquals("121300 UTC Apr 17", TimeUtils.formatNavtexDate(date));
    }

    @Test
    public void formatNavtexDate_usesUtcRegardlessOfInstantZone() {
        // 2020-12-31 23:30 in UTC+2 == 2020-12-31 21:30 UTC -> "312130 UTC Dec 20".
        Date date = dateInZone("2020-12-31 23:30:00", "GMT+2");
        assertEquals("312130 UTC Dec 20", TimeUtils.formatNavtexDate(date));
    }

    @Test
    public void formatNavtexDate_nullReturnsEmptyString() {
        // The implementation swallows the NPE thrown by SimpleDateFormat.format(null)
        // and returns the empty string ("prefer robustness over correctness").
        assertEquals("", TimeUtils.formatNavtexDate(null));
    }
}
