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
package org.niord.core.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The zone-aware day boundaries of {@link TimeUtils} over every day of 2026 to 2028 in the three zones the
 * deployments use, and the ISO week at New Year. {@code resetTime(date, zone)} and {@code endOfDay(date, zone)}
 * bound a day for the message search ({@code MessageSearchParams.adjustDateInterval}) and the firing areas of
 * a day ({@code FiringScheduleService.searchFiringAreaPeriods}).
 * <p>
 * The property: the start of a day is the zone's first instant of that day, and its end is the last second
 * before the next day starts (the method's contract is "23:59:59" of the day).
 */
public class TimeUtilsZoneBoundaryTest {

    private static final List<String> ZONES = List.of("UTC", "Europe/Copenhagen", "America/Nuuk");

    /** Noon of the day in the zone: the instant a caller hands in. */
    private static Date noon(LocalDate day, ZoneId zone) {
        return Date.from(day.atTime(12, 0).atZone(zone).toInstant());
    }

    @Test
    public void resetTime_isTheFirstInstantOfTheZonesDay_everyDay() {
        List<String> failures = new ArrayList<>();
        for (String zone : ZONES) {
            ZoneId z = ZoneId.of(zone);
            for (LocalDate d = LocalDate.of(2026, 1, 1); d.getYear() <= 2028; d = d.plusDays(1)) {
                Instant expected = d.atStartOfDay(z).toInstant();
                Instant actual = TimeUtils.resetTime(noon(d, z), TimeZone.getTimeZone(zone)).toInstant();
                if (!expected.equals(actual)) {
                    failures.add(zone + " " + d + ": " + actual + ", expected " + expected);
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " wrong: " + failures);
    }

    @Test
    public void endOfDay_isTheLastSecondBeforeTheNextDay_everyDay() {
        // America/Nuuk follows the EU rule at UTC-2 since 2023, so its clocks change at 01:00 UTC, which is
        // 23:00 local on the Saturday: in spring 23:00 becomes 00:00 (23:59:59 does not exist that day), in
        // autumn 00:00 becomes 23:00 again (23:59:59 exists twice). A wall clock set to 23:59:59 would land
        // on the next day in spring and an hour before the day ends in autumn; the end of a day is the next
        // day's start minus one second, on every day of every zone.
        List<String> failures = new ArrayList<>();
        for (String zone : ZONES) {
            ZoneId z = ZoneId.of(zone);
            for (LocalDate d = LocalDate.of(2026, 1, 1); d.getYear() <= 2028; d = d.plusDays(1)) {
                Instant expected = d.plusDays(1).atStartOfDay(z).toInstant().minusSeconds(1);
                Instant actual = TimeUtils.endOfDay(noon(d, z), TimeZone.getTimeZone(zone)).toInstant();
                if (!expected.equals(actual)) {
                    failures.add(zone + " " + d + " " + actual.atZone(z).toLocalDateTime());
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " wrong: " + failures);
    }

    @Test
    public void endOfDay_inNuukOnTheChangeDays_isTheLastSecondOfThatDay() {
        ZoneId nuuk = ZoneId.of("America/Nuuk");
        TimeZone tz = TimeZone.getTimeZone(nuuk);

        // Spring: 28 March 2026 runs at UTC-2 until 23:00, which becomes 00:00 UTC-1 on 29 March.
        // Its last second is 22:59:59 UTC-2, not 00:59:59 on 29 March.
        Date spring = TimeUtils.endOfDay(noon(LocalDate.of(2026, 3, 28), nuuk), tz);
        assertEquals(Instant.parse("2026-03-29T00:59:59Z"), spring.toInstant());
        assertEquals(LocalDateTime.of(2026, 3, 28, 22, 59, 59), spring.toInstant().atZone(nuuk).toLocalDateTime());

        // Autumn: 24 October 2026 runs at UTC-1 until 00:00, which becomes 23:00 UTC-2 again. Its last
        // second is the second 23:59:59 (UTC-2), not the first (UTC-1).
        Date autumn = TimeUtils.endOfDay(noon(LocalDate.of(2026, 10, 24), nuuk), tz);
        assertEquals(Instant.parse("2026-10-25T01:59:59Z"), autumn.toInstant());
        assertEquals(LocalDateTime.of(2026, 10, 24, 23, 59, 59), autumn.toInstant().atZone(nuuk).toLocalDateTime());

        // An instant in the day's last hour (the repeated one in autumn) still ends at the same second.
        Date lateAutumn = TimeUtils.endOfDay(Date.from(Instant.parse("2026-10-25T01:30:00Z")), tz);
        assertEquals(Instant.parse("2026-10-25T01:59:59Z"), lateAutumn.toInstant());
    }

    @Test
    public void sameDate_inAZone_agreesWithJavaTimeAcrossTheClockChanges() {
        List<String> failures = new ArrayList<>();
        for (String zone : ZONES) {
            ZoneId z = ZoneId.of(zone);
            Instant from = Instant.parse("2026-03-27T00:00:00Z");
            for (Instant a = from; a.isBefore(Instant.parse("2026-03-31T00:00:00Z")); a = a.plusSeconds(1800)) {
                Instant b = a.plusSeconds(3600);
                boolean expected = a.atZone(z).toLocalDate().equals(b.atZone(z).toLocalDate());
                boolean actual = TimeUtils.sameDate(Date.from(a), Date.from(b), TimeZone.getTimeZone(zone));
                if (expected != actual) {
                    failures.add(zone + " " + a);
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " wrong: " + failures);
    }

    @Test
    public void isoWeek_ofEveryDayAroundNewYear_matchesJavaTime_inTheJvmZone() {
        // getISO8601WeekOfYear reads the JVM's zone; the dates are built in it, so this checks the week rule
        // alone. 2026 has 53 ISO weeks: 1 January 2027 is in week 53, 4 January 2027 in week 1.
        List<String> failures = new ArrayList<>();
        ZoneId jvm = ZoneId.systemDefault();
        for (int year = 2024; year <= 2030; year++) {
            for (LocalDate d = LocalDate.of(year, 12, 25); d.isBefore(LocalDate.of(year + 1, 1, 8)); d = d.plusDays(1)) {
                int expected = d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
                int actual = TimeUtils.getISO8601WeekOfYear(noon(d, jvm));
                if (expected != actual) {
                    failures.add(d + ": " + actual + ", expected " + expected);
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " wrong: " + failures);
    }
}
