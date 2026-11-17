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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The publication naming tokens ({@link IssueNaming#derive}) over every cut-off day of 2024 to 2030, at the
 * first and last minute of the day, in the three zones the deployments use: the week, the week-based year
 * (2026 has 53 ISO weeks), the calendar year, the month and the day are the cut-off's own in the series' zone.
 * {@code IssueNamingTest} holds the individual cases; this is the property over the grid.
 */
public class IssueNamingGridTest {

    private static final List<String> ZONES = List.of("UTC", "Europe/Copenhagen", "America/Nuuk");

    @Test
    public void everyCutoff_isNamedForItsOwnWeekYearMonthAndDay_inTheSeriesZone() {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (String zone : ZONES) {
            ZoneId z = ZoneId.of(zone);
            for (LocalDate d = LocalDate.of(2024, 1, 1); d.getYear() <= 2030; d = d.plusDays(1)) {
                for (LocalTime time : new LocalTime[] { LocalTime.of(0, 0), LocalTime.of(23, 59) }) {
                    ZonedDateTime cutoff = ZonedDateTime.of(d, time, z);
                    Date at = Date.from(cutoff.toInstant());
                    IssueNaming.Numbers iso = IssueNaming.derive(at, null, z, null, IssueNaming.YearBasis.ISO_WEEK_YEAR);
                    IssueNaming.Numbers cal = IssueNaming.derive(at, null, z, null, IssueNaming.YearBasis.CALENDAR_YEAR);
                    LocalDate local = cutoff.toLocalDate();
                    if (iso.week() != local.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
                            || iso.year() != local.get(IsoFields.WEEK_BASED_YEAR)
                            || cal.year() != local.getYear()
                            || cal.week() != iso.week()
                            || iso.month() != local.getMonthValue()
                            || iso.day() != local.getDayOfMonth()) {
                        failures.add(zone + " " + cutoff + ": " + iso + " / " + cal);
                    }
                    checked++;
                }
            }
        }
        assertTrue(checked > 15_000, "the grid is smaller than intended: " + checked);
        assertTrue(failures.isEmpty(), failures.size() + " wrong: " + failures.subList(0, Math.min(10, failures.size())));
    }

    @Test
    public void weekFiftyThree_isNamedOnlyInAYearThatHasIt() {
        // 2026 has 53 ISO weeks, 2027 has 52: a cut-off on Thursday 31 December 2026 is week 53 of 2026, one on
        // Friday 31 December 2027 is week 52 of 2027.
        ZoneId dk = ZoneId.of("Europe/Copenhagen");
        IssueNaming.Numbers y2026 = IssueNaming.derive(Date.from(ZonedDateTime.of(2026, 12, 31, 10, 0, 0, 0, dk).toInstant()),
                null, dk, null, IssueNaming.YearBasis.ISO_WEEK_YEAR);
        IssueNaming.Numbers y2027 = IssueNaming.derive(Date.from(ZonedDateTime.of(2027, 12, 31, 10, 0, 0, 0, dk).toInstant()),
                null, dk, null, IssueNaming.YearBasis.ISO_WEEK_YEAR);
        assertEquals("53/2026", y2026.week() + "/" + y2026.year());
        assertEquals("52/2027", y2027.week() + "/" + y2027.year());
    }

    @Test
    public void theCutoffInstant_isReadInTheSeriesZone_notInUtc() {
        // 23:30 UTC on Sunday 3 January 2027 is already Monday 4 January (ISO week 1 of 2027) in Copenhagen,
        // and still Sunday (week 53 of 2026) in UTC and in Nuuk.
        Date at = Date.from(Instant.parse("2027-01-03T23:30:00Z"));
        assertEquals("1/2027", show(IssueNaming.derive(at, null, ZoneId.of("Europe/Copenhagen"), null,
                IssueNaming.YearBasis.ISO_WEEK_YEAR)));
        assertEquals("53/2026", show(IssueNaming.derive(at, null, ZoneId.of("UTC"), null,
                IssueNaming.YearBasis.ISO_WEEK_YEAR)));
        assertEquals("53/2026", show(IssueNaming.derive(at, null, ZoneId.of("America/Nuuk"), null,
                IssueNaming.YearBasis.ISO_WEEK_YEAR)));
    }

    private static String show(IssueNaming.Numbers n) {
        return n.week() + "/" + n.year();
    }
}
