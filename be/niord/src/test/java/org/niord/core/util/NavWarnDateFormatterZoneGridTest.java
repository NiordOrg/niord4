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
import org.niord.core.util.NavWarnDateFormatter.Format;
import org.niord.model.message.DateIntervalVo;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NavWarnDateFormatter}, the formatter behind every date and date interval a template prints for
 * mariners (the {@code DateIntervalDirective}), over a grid of zones and moments: the three zones the
 * deployments use (UTC, Europe/Copenhagen, America/Nuuk), every half hour across New Year, both clock changes
 * of 2026 in Copenhagen and in Nuuk (whose changes fall at 23:00 and 00:00 local, not in the night), a leap
 * day and the turn of a month.
 * <p>
 * The property: the day, its suffix, the month, the year and the time printed are the wall clock of the
 * instant in the zone the formatter was given, as {@code java.time} reads it. An all-day interval built on
 * the zone's own day (its first instant to its last second) prints that day and no other.
 * <p>
 * The table {@link #SHARED_WALL_CLOCKS} is the same table the administration app's
 * {@code date-interval-wall-clock.spec.ts} pins, so a difference between the two sides shows on both.
 */
public class NavWarnDateFormatterZoneGridTest {

    private static final ResourceBundle BUNDLE =
            ResourceBundle.getBundle("defaults/base/texts/en/template", Locale.ROOT);
    private static final String[] DAY_SUFFIX = BUNDLE.getString("navwarn.date.day_suffix").split(",");

    private static final List<String> ZONES = List.of("UTC", "Europe/Copenhagen", "America/Nuuk");

    /** Windows of UTC instants around the boundaries, each walked in half-hour steps. */
    private static final String[][] WINDOWS = {
            { "2026-12-31T18:00:00Z", "2027-01-01T06:00:00Z" },   // New Year
            { "2026-03-28T18:00:00Z", "2026-03-29T06:00:00Z" },   // spring forward (Copenhagen 01:00Z, Nuuk 01:00Z)
            { "2026-10-24T18:00:00Z", "2026-10-25T06:00:00Z" },   // fall back
            { "2028-02-28T18:00:00Z", "2028-03-01T06:00:00Z" },   // leap day
            { "2026-04-30T18:00:00Z", "2026-05-01T06:00:00Z" },   // end of a 30-day month
    };

    private static NavWarnDateFormatter plain(String zone) {
        return NavWarnDateFormatter.newDateFormatter(BUNDLE, Format.PLAIN, Locale.ENGLISH, zone, zone, false);
    }

    private static String dayText(ZonedDateTime t) {
        return t.getDayOfMonth() + DAY_SUFFIX[t.getDayOfMonth()];
    }

    private static String monthText(ZonedDateTime t) {
        return t.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private static List<Instant> gridInstants() {
        List<Instant> result = new ArrayList<>();
        for (String[] w : WINDOWS) {
            for (Instant i = Instant.parse(w[0]); !i.isAfter(Instant.parse(w[1])); i = i.plusSeconds(1800)) {
                result.add(i);
            }
        }
        return result;
    }

    private static DateIntervalVo interval(Instant from, Instant to, boolean allDay) {
        DateIntervalVo di = new DateIntervalVo();
        di.setFromDate(from == null ? null : Date.from(from));
        di.setToDate(to == null ? null : Date.from(to));
        di.setAllDay(allDay);
        return di;
    }

    // ------------------------------------------------------------------
    // A single date, timed
    // ------------------------------------------------------------------

    @Test
    public void everyDate_printsTheWallClockOfItsZone() {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (String zone : ZONES) {
            NavWarnDateFormatter f = plain(zone);
            for (Instant i : gridInstants()) {
                ZonedDateTime t = i.atZone(ZoneId.of(zone));
                String expected = "the " + dayText(t) + " " + monthText(t) + " " + t.getYear()
                        + " at " + t.format(DateTimeFormatter.ofPattern("HHmm"));
                String printed = f.formatDate(Date.from(i));
                if (!expected.equals(printed)) {
                    failures.add(zone + " " + i + ": \"" + printed + "\", expected \"" + expected + "\"");
                }
                checked++;
            }
        }
        assertTrue(checked > 500, "the grid is smaller than intended: " + checked);
        assertTrue(failures.isEmpty(), failures.size() + " wrong: " + failures);
    }

    @Test
    public void everyNavtexDate_printsUtc_whateverTheZone() {
        List<String> failures = new ArrayList<>();
        for (String zone : ZONES) {
            NavWarnDateFormatter f = NavWarnDateFormatter.newDateFormatter(
                    BUNDLE, Format.NAVTEX, Locale.ENGLISH, zone, zone, false);
            for (Instant i : gridInstants()) {
                String expected = i.atZone(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("ddHHmm 'UTC' MMM yy", Locale.US)).toUpperCase();
                String printed = f.formatDate(Date.from(i));
                if (!expected.equals(printed)) {
                    failures.add(zone + " " + i + ": \"" + printed + "\", expected \"" + expected + "\"");
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.size() + " wrong: " + failures);
    }

    /**
     * The month is named in the language the formatter is given, whatever the JVM's default locale: the default is
     * Danish on the workstations and English on CI and in the containers, so a month named in the default locale
     * passes on one of them and prints "maj" in an English text (or "May" in a Danish one) on the other. The grid
     * above runs in the default locale; this sets it against the language (error hunt 2026-11-12, detector check).
     */
    @Test
    public void theMonth_isNamedInTheFormattersLanguage_whateverTheJvmLocale() {
        List<String> failures = new ArrayList<>();
        Locale saved = Locale.getDefault();
        try {
            for (Locale jvm : List.of(Locale.forLanguageTag("da-DK"), Locale.US, Locale.GERMANY)) {
                Locale.setDefault(jvm);
                for (Locale lang : List.of(Locale.ENGLISH, Locale.forLanguageTag("da"))) {
                    NavWarnDateFormatter f = NavWarnDateFormatter.newDateFormatter(
                            BUNDLE, Format.PLAIN, lang, "Europe/Copenhagen", "Europe/Copenhagen", false);
                    for (int month = 1; month <= 12; month++) {
                        ZonedDateTime t = ZonedDateTime.of(2026, month, 15, 12, 0, 0, 0, ZoneId.of("Europe/Copenhagen"));
                        String name = t.getMonth().getDisplayName(TextStyle.FULL, lang);
                        String printed = f.formatDate(Date.from(t.toInstant()));
                        if (!printed.contains(" " + name + " ")) {
                            failures.add("JVM " + jvm + ", " + lang + ": \"" + printed + "\" lacks " + name);
                        }
                    }
                }
            }
        } finally {
            Locale.setDefault(saved);
        }
        assertTrue(failures.isEmpty(), failures.size() + " wrong: " + failures);
    }

    // ------------------------------------------------------------------
    // All-day intervals: the zone's own day, first instant to last second
    // ------------------------------------------------------------------

    /** Every local day touched by the windows, in each zone. */
    @Test
    public void allDayInterval_onTheZonesOwnDay_printsThatDayOnly() {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (String zone : ZONES) {
            ZoneId z = ZoneId.of(zone);
            NavWarnDateFormatter f = plain(zone);
            for (String[] w : WINDOWS) {
                LocalDate first = Instant.parse(w[0]).atZone(z).toLocalDate().minusDays(1);
                LocalDate last = Instant.parse(w[1]).atZone(z).toLocalDate().plusDays(1);
                for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
                    Instant start = d.atStartOfDay(z).toInstant();
                    Instant end = d.plusDays(1).atStartOfDay(z).toInstant().minusSeconds(1);
                    ZonedDateTime t = start.atZone(z);
                    String expected = "the " + dayText(t) + " " + monthText(t) + " " + t.getYear();
                    String printed = f.formatDateInterval(interval(start, end, true));
                    if (!expected.equals(printed)) {
                        failures.add(zone + " " + d + ": \"" + printed + "\", expected \"" + expected + "\"");
                    }
                    checked++;
                }
            }
        }
        assertTrue(checked > 30, "the grid is smaller than intended: " + checked);
        assertTrue(failures.isEmpty(), failures.size() + " wrong: " + failures);
    }

    @Test
    public void allDayInterval_acrossNewYear_keepsBothYears() {
        ZoneId z = ZoneId.of("Europe/Copenhagen");
        Instant from = LocalDate.of(2026, 12, 30).atStartOfDay(z).toInstant();
        Instant to = LocalDate.of(2027, 1, 2).plusDays(1).atStartOfDay(z).toInstant().minusSeconds(1);
        assertEquals("from the 30th December 2026 to the 2nd January 2027",
                plain("Europe/Copenhagen").formatDateInterval(interval(from, to, true)));
    }

    @Test
    public void allDayInterval_overTheLeapDay_endsOnThe29th() {
        ZoneId z = ZoneId.of("America/Nuuk");
        Instant from = LocalDate.of(2028, 2, 27).atStartOfDay(z).toInstant();
        Instant to = LocalDate.of(2028, 3, 1).atStartOfDay(z).toInstant().minusSeconds(1);
        assertEquals("from the 27th to the 29th February 2028",
                plain("America/Nuuk").formatDateInterval(interval(from, to, true)));
    }

    // ------------------------------------------------------------------
    // Timed intervals at the boundaries
    // ------------------------------------------------------------------

    @Test
    public void timedInterval_acrossMidnightAndNewYear_printsBothDates() {
        ZoneId z = ZoneId.of("Europe/Copenhagen");
        Instant from = LocalDateTime.of(2026, 12, 31, 23, 0).atZone(z).toInstant();
        Instant to = LocalDateTime.of(2027, 1, 1, 1, 0).atZone(z).toInstant();
        assertEquals("from the 31st December 2026 at 2300 to the 1st January 2027 at 0100",
                plain("Europe/Copenhagen").formatDateInterval(interval(from, to, false)));
    }

    @Test
    public void timedInterval_overTheSpringGap_printsTheClockAfterTheChange_withBothZones() {
        // Copenhagen 29 March 2026: 02:00 becomes 03:00. 01:30 to 03:30 local is one hour; without the zones it
        // reads as two. Each time carries its own short zone name, as the administration app's Insert time does.
        ZoneId z = ZoneId.of("Europe/Copenhagen");
        Instant from = LocalDateTime.of(2026, 3, 29, 1, 30).atZone(z).toInstant();
        Instant to = from.plusSeconds(3600);
        assertEquals("the 29th March 2026 from 0130 CET to 0330 CEST",
                plain("Europe/Copenhagen").formatDateInterval(interval(from, to, false)));
    }

    @Test
    public void timedInterval_ofOneHourInTheRepeatedHour_namesBothEnds_withTheirZones() {
        // Copenhagen 25 October 2026: 03:00 CEST becomes 02:00 CET, so 02:30 happens twice. An interval from the
        // first 02:30 to the second is one hour long. The formatter used to compare the printed wall clocks only
        // ("0230" = "0230") and print it as a single moment (TZ5, error hunt 2026-11-12).
        ZoneId z = ZoneId.of("Europe/Copenhagen");
        Instant from = Instant.parse("2026-10-25T00:30:00Z");   // 02:30 CEST
        Instant to = Instant.parse("2026-10-25T01:30:00Z");     // 02:30 CET
        assertEquals(from.atZone(z).toLocalDateTime(), to.atZone(z).toLocalDateTime());
        assertEquals("the 25th October 2026 from 0230 CEST to 0230 CET",
                plain("Europe/Copenhagen").formatDateInterval(interval(from, to, false)));
    }

    @Test
    public void timedInterval_whoseEndsShareAnOffset_onTheAutumnChangeDay_printsAsBefore() {
        // 01:00 to 01:45 CEST on 25 October 2026, before the change: no zone names, one range.
        ZoneId z = ZoneId.of("Europe/Copenhagen");
        Instant from = LocalDateTime.of(2026, 10, 25, 1, 0).atZone(z).toInstant();
        Instant to = from.plusSeconds(45 * 60);
        assertEquals("the 25th October 2026 from 0100 to 0145",
                plain("Europe/Copenhagen").formatDateInterval(interval(from, to, false)));
        // The same instant at both ends stays a single moment.
        assertEquals("the 25th October 2026 at 0100",
                plain("Europe/Copenhagen").formatDateInterval(interval(from, from, false)));
    }

    @Test
    public void timedInterval_ofOneHourInNuuksRepeatedHour_namesTwoDifferentZones() {
        // Nuuk 24 October 2026: 00:00 (UTC-1) on the 25th becomes 23:00 (UTC-2) on the 24th, so 23:30 happens
        // twice. The short names come from Java's zone data; the test asserts that they are there and differ.
        Instant from = Instant.parse("2026-10-25T00:30:00Z");   // 23:30 UTC-1
        Instant to = Instant.parse("2026-10-25T01:30:00Z");     // 23:30 UTC-2
        String printed = plain("America/Nuuk").formatDateInterval(interval(from, to, false));
        Matcher m = Pattern.compile("the 24th October 2026 from 2330 (\\S+) to 2330 (\\S+)").matcher(printed);
        assertTrue(m.matches(), printed);
        assertNotEquals(m.group(1), m.group(2), printed);
    }

    @Test
    public void timedInterval_inNuuk_overTheEveningClockChange_printsTheNextDay() {
        // Nuuk 28 March 2026: 23:00 (UTC-2) becomes 00:00 on the 29th (UTC-1). 22:30 plus one hour is 00:30.
        ZoneId z = ZoneId.of("America/Nuuk");
        Instant from = LocalDateTime.of(2026, 3, 28, 22, 30).atZone(z).toInstant();
        Instant to = from.plusSeconds(3600);
        assertEquals("from the 28th March 2026 at 2230 to the 29th March 2026 at 0030",
                plain("America/Nuuk").formatDateInterval(interval(from, to, false)));
    }

    // ------------------------------------------------------------------
    // The table both sides pin
    // ------------------------------------------------------------------

    /**
     * {@code instant | zone | wall clock "yyyy-MM-dd HH:mm"}. The same rows are in the administration app's
     * {@code fe/ui-apps/admin/src/app/shared/utils/date-interval-wall-clock.spec.ts}; change both together.
     */
    static final String[][] SHARED_WALL_CLOCKS = {
            { "2026-12-31T22:59:00Z", "Europe/Copenhagen", "2026-12-31 23:59" },
            { "2026-12-31T23:00:00Z", "Europe/Copenhagen", "2027-01-01 00:00" },
            { "2026-12-31T23:00:00Z", "UTC", "2026-12-31 23:00" },
            { "2027-01-01T01:30:00Z", "America/Nuuk", "2026-12-31 23:30" },
            { "2027-01-01T02:00:00Z", "America/Nuuk", "2027-01-01 00:00" },
            { "2026-03-29T00:59:00Z", "Europe/Copenhagen", "2026-03-29 01:59" },
            { "2026-03-29T01:00:00Z", "Europe/Copenhagen", "2026-03-29 03:00" },
            { "2026-03-29T00:59:00Z", "America/Nuuk", "2026-03-28 22:59" },
            { "2026-03-29T01:00:00Z", "America/Nuuk", "2026-03-29 00:00" },
            { "2026-10-25T00:30:00Z", "Europe/Copenhagen", "2026-10-25 02:30" },
            { "2026-10-25T01:30:00Z", "Europe/Copenhagen", "2026-10-25 02:30" },
            { "2026-10-25T00:30:00Z", "America/Nuuk", "2026-10-24 23:30" },
            { "2026-10-25T01:30:00Z", "America/Nuuk", "2026-10-24 23:30" },
            { "2028-02-29T22:59:00Z", "Europe/Copenhagen", "2028-02-29 23:59" },
            { "2028-02-29T23:00:00Z", "Europe/Copenhagen", "2028-03-01 00:00" },
            { "2026-04-30T22:00:00Z", "Europe/Copenhagen", "2026-05-01 00:00" },
    };

    @Test
    public void sharedTable_theFormatterPrintsTheSameWallClockAsTheAdministrationApp() {
        DateTimeFormatter wallClock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        for (String[] row : SHARED_WALL_CLOCKS) {
            Instant instant = Instant.parse(row[0]);
            ZonedDateTime t = LocalDateTime.parse(row[2], wallClock).atZone(ZoneId.of(row[1]));
            // The table itself is java.time's reading of the instant.
            assertEquals(row[2], instant.atZone(ZoneId.of(row[1])).format(wallClock), "table row " + row[0]);
            String expected = "the " + dayText(t) + " " + monthText(t) + " " + t.getYear()
                    + " at " + t.format(DateTimeFormatter.ofPattern("HHmm"));
            assertEquals(expected, plain(row[1]).formatDate(Date.from(instant)), row[0] + " in " + row[1]);
        }
    }
}
