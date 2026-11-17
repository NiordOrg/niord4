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
package org.niord.core.message;

import org.junit.jupiter.api.Test;
import org.niord.core.NiordApp;
import org.niord.core.message.MessageSeriesService.FormatResolver;
import org.niord.model.message.MainType;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The message short id and the year a message is numbered in, at the turn of the year. The Compute button
 * of a Continuous series counts every year since SERIES1 was fixed ({@code MessageSeriesServiceITTest}); this
 * covers what is beside it: the tokens a short-id
 * format expands ({@link FormatResolver}) and the year {@link Message#checkUpdateYear(ZoneId)} hands the number
 * sequence, both read in the zone of the series' domain (TZ2; {@code MessageSeriesYearZoneITTest} runs the same
 * through the publishing path).
 * <p>
 * Every test that reads the JVM's zone sets it to UTC, the zone the deployments run in (TZ1, decided
 * 2026-10-25), and restores it.
 */
public class MessageShortIdYearBoundaryTest {

    private static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");
    private static final ZoneId NUUK = ZoneId.of("America/Nuuk");

    private static NiordApp app() {
        NiordApp app = mock(NiordApp.class);
        when(app.getCountry()).thenReturn("DK");
        return app;
    }

    private static Message published(Date publishDate) {
        Message msg = new Message();
        msg.setPublishDateFrom(publishDate);
        msg.setNumber(1);
        msg.setMainType(MainType.NW);
        return msg;
    }

    private static <T> T inJvmZone(String zone, java.util.function.Supplier<T> body) {
        TimeZone saved = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            return body.get();
        } finally {
            TimeZone.setDefault(saved);
        }
    }

    @Test
    public void weekAndYearTokens_atNewYear_nameTheWeeksOwnYear() {
        // TZ3. ${week} is the ISO week, so in a format that names a week ${year} is the ISO week-based year, as
        // the publications' own naming (IssueNaming) pairs them: 1 January 2027 is in ISO week 53 of 2026 and
        // prints "53/2026" (not "53/2027"), 30 December 2024 is in week 1 of 2025 and prints "01/2025" (not
        // "01/2024"). ${week-2-digits} and ${year-2-digits} follow the same rule.
        List<String> printed = inJvmZone("UTC", () -> {
            List<String> out = new ArrayList<>();
            for (LocalDate d : List.of(LocalDate.of(2027, 1, 1), LocalDate.of(2024, 12, 30))) {
                Date noon = Date.from(d.atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant());
                FormatResolver resolver = new FormatResolver(published(noon), app());
                out.add(resolver.resolveFormat("${week}/${year}"));
                out.add(resolver.resolveFormat("W${week-2-digits}-${year-2-digits}"));
            }
            return out;
        });
        assertEquals(List.of("53/2026", "W53-26", "01/2025", "W01-25"), printed);
    }

    @Test
    public void aFormatWithoutAWeek_keepsTheCalendarYear() {
        // A format that names no week keeps the calendar year: 30 December 2024 is numbered in 2024, although
        // its ISO week belongs to 2025.
        String shortId = inJvmZone("UTC", () -> {
            Date noon = Date.from(LocalDate.of(2024, 12, 30).atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant());
            return new FormatResolver(published(noon), app()).resolveFormat("NW-${number-3-digits}-${year-2-digits}");
        });
        assertEquals("NW-001-24", shortId);
    }

    @Test
    public void weekAndYearTokens_areReadInTheSeriesZone() {
        // 23:30 UTC on Sunday 3 January 2027 is 00:30 on Monday 4 January in Copenhagen: week 1 of 2027 there,
        // week 53 of 2026 in UTC.
        Date instant = Date.from(LocalDateTime.of(2027, 1, 3, 23, 30).atZone(ZoneId.of("UTC")).toInstant());
        List<String> printed = inJvmZone("UTC", () -> List.of(
                new FormatResolver(published(instant), app(), COPENHAGEN).resolveFormat("${week}/${year}"),
                new FormatResolver(published(instant), app()).resolveFormat("${week}/${year}")));
        assertEquals(List.of("01/2027", "53/2026"), printed);
    }

    @Test
    public void weekAndYearTokens_agreeWithTheWeekBasedYear_onEveryOtherDay() {
        // The property the fix keeps: away from the turn of the year, ${week}/${year} is the ISO week and its
        // week-based year. Every day of 2025 to 2028 whose week-based year is its calendar year.
        List<String> failures = inJvmZone("UTC", () -> {
            List<String> out = new ArrayList<>();
            for (LocalDate d = LocalDate.of(2025, 1, 1); d.getYear() <= 2028; d = d.plusDays(1)) {
                if (d.get(IsoFields.WEEK_BASED_YEAR) != d.getYear()) {
                    continue;
                }
                Date noon = Date.from(d.atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant());
                String expected = String.format("%02d/%d", d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), d.getYear());
                String actual = new FormatResolver(published(noon), app()).resolveFormat("${week}/${year}");
                if (!expected.equals(actual)) {
                    out.add(d + ": " + actual);
                }
            }
            return out;
        });
        assertTrue(failures.isEmpty(), failures.size() + " wrong: " + failures);
    }

    @Test
    public void messagePublishedAtLocalNewYear_isNumberedInTheSeriesZonesYear_onAUtcServer() {
        // TZ2. A warning published at 00:30 on 1 January 2027 in Copenhagen is 23:30 on 31 December 2026 in UTC.
        // Read in the series' domain zone, it is a 2027 warning on a UTC server too: it takes its number from the
        // 2027 sequence and its short id ends in "-27".
        Date halfPastMidnight = Date.from(
                LocalDateTime.of(2027, 1, 1, 0, 30).atZone(COPENHAGEN).toInstant());
        List<Object> result = inJvmZone("UTC", () -> {
            Message msg = published(halfPastMidnight);
            Integer year = msg.checkUpdateYear(COPENHAGEN);
            String shortId = new FormatResolver(msg, app(), COPENHAGEN)
                    .resolveFormat("NW-${number-3-digits}-${year-2-digits}");
            return List.of(year, shortId);
        });
        assertEquals(List.of(2027, "NW-001-27"), result);
    }

    @Test
    public void messagePublishedOnNewYearsEveInNuuk_isNumberedInTheOldYear_onAUtcServer() {
        // The mirror west of UTC: 22:30 on 31 December 2026 in Nuuk is 00:30 on 1 January 2027 in UTC.
        Date newYearsEve = Date.from(LocalDateTime.of(2026, 12, 31, 22, 30).atZone(NUUK).toInstant());
        List<Object> result = inJvmZone("UTC", () -> {
            Message msg = published(newYearsEve);
            Integer year = msg.checkUpdateYear(NUUK);
            String shortId = new FormatResolver(msg, app(), NUUK)
                    .resolveFormat("NW-${number-3-digits}-${year-2-digits}");
            return List.of(year, shortId);
        });
        assertEquals(List.of(2026, "NW-001-26"), result);
    }

    @Test
    public void theSaveCallbackKeepsTheZonedYear_untilThePublishDateChanges() {
        Date halfPastMidnight = Date.from(
                LocalDateTime.of(2027, 1, 1, 0, 30).atZone(COPENHAGEN).toInstant());
        List<Object> result = inJvmZone("UTC", () -> {
            List<Object> out = new ArrayList<>();
            Message msg = published(halfPastMidnight);
            msg.checkUpdateYear(COPENHAGEN);

            // The save callback keeps the year read in the series' zone
            msg.onPersist();
            out.add(msg.year);

            // So does it for a loaded message, whose publish date is a java.sql.Timestamp
            Message loaded = published(new java.sql.Timestamp(halfPastMidnight.getTime()));
            loaded.year = 2027;
            loaded.onLoad();
            loaded.onPersist();
            out.add(loaded.year);

            // A changed publish date is read again (in the JVM's zone, until the series numbers it)
            msg.setPublishDateFrom(new Date(halfPastMidnight.getTime() + 60_000));
            msg.onPersist();
            out.add(msg.year);

            // A cleared publish date clears the year
            msg.setPublishDateFrom(null);
            msg.onPersist();
            out.add(msg.year == null ? "null" : msg.year);
            return out;
        });
        assertEquals(List.of(2027, 2027, 2026, "null"), result);
    }

    @Test
    public void messagePublishedAtLocalNewYear_inACopenhagenJvm_isNumberedInTheNewYear() {
        // The same instant in a JVM that runs in the domain's zone: what the fix makes true on any server.
        Date halfPastMidnight = Date.from(
                LocalDateTime.of(2027, 1, 1, 0, 30).atZone(ZoneId.of("Europe/Copenhagen")).toInstant());
        List<Object> result = inJvmZone("Europe/Copenhagen", () -> {
            Message msg = published(halfPastMidnight);
            Integer year = msg.checkUpdateYear();
            String shortId = new FormatResolver(msg, app()).resolveFormat("NW-${number-3-digits}-${year-2-digits}");
            return List.of(year, shortId);
        });
        assertEquals(List.of(2027, "NW-001-27"), result);
    }
}
