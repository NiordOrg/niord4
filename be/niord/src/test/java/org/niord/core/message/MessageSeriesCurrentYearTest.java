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

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * The current year of a message series is the year in the zone the series is numbered in (its domain's), not the
 * JVM's. The message series list reads the next number of that year, so around New Year it shows the number a
 * message published now would take.
 */
class MessageSeriesCurrentYearTest {

    private static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");
    private static final ZoneId NUUK = ZoneId.of("America/Nuuk");
    private static final ZoneId KIRITIMATI = ZoneId.of("Pacific/Kiritimati");
    private static final ZoneId PAGO_PAGO = ZoneId.of("Pacific/Pago_Pago");

    @Test
    void halfAnHourBeforeNewYearInUtcIsTheNewYearInCopenhagen() {
        Instant instant = Instant.parse("2026-12-31T23:30:00Z");
        assertEquals(2027, MessageSeriesService.yearAt(instant, COPENHAGEN));
        assertEquals(2026, MessageSeriesService.yearAt(instant, ZoneOffset.UTC));
    }

    @Test
    void anHourAfterNewYearInUtcIsStillTheOldYearInNuuk() {
        Instant instant = Instant.parse("2027-01-01T01:00:00Z");
        assertEquals(2026, MessageSeriesService.yearAt(instant, NUUK));
        assertEquals(2027, MessageSeriesService.yearAt(instant, ZoneOffset.UTC));
    }

    @Test
    void theYearTurnsAtMidnightInTheZone() {
        Instant midnight = Instant.parse("2026-12-31T23:00:00Z"); // 2027-01-01T00:00 in Copenhagen (winter, +01:00)
        assertEquals(2026, MessageSeriesService.yearAt(midnight.minusMillis(1), COPENHAGEN));
        assertEquals(2027, MessageSeriesService.yearAt(midnight, COPENHAGEN));
    }

    @Test
    void currentYearReadsTheInstantInTheSeriesZoneEastOfUtc() {
        // 2026-12-31T11:00Z is 2027-01-01T01:00 in Pacific/Kiritimati (+14:00), and still 2026 in UTC and Copenhagen
        MessageSeries series = new MessageSeries("it-series8");
        MessageSeriesService service = spy(new MessageSeriesService());
        doReturn(KIRITIMATI).when(service).seriesZone(series);
        assertEquals(2027, service.currentYear(series, Instant.parse("2026-12-31T11:00:00Z")));
    }

    @Test
    void currentYearReadsTheInstantInTheSeriesZoneWestOfUtc() {
        // 2027-01-01T01:00Z is 2026-12-31T22:00 in America/Nuuk (-03:00) and 14:00 in Pacific/Pago_Pago (-11:00),
        // and already 2027 in UTC and Copenhagen
        MessageSeries series = new MessageSeries("it-series8");
        MessageSeriesService service = spy(new MessageSeriesService());
        Instant instant = Instant.parse("2027-01-01T01:00:00Z");
        for (ZoneId zone : new ZoneId[] { NUUK, PAGO_PAGO }) {
            doReturn(zone).when(service).seriesZone(series);
            assertEquals(2026, service.currentYear(series, instant), "the year in " + zone);
        }
    }

    @Test
    void currentYearReadsTheNowInTheSeriesZone() {
        // Pacific/Kiritimati (+14:00) and Pacific/Pago_Pago (-11:00): the series' zone, not the JVM's, decides
        MessageSeries series = new MessageSeries("it-series8");
        MessageSeriesService service = spy(new MessageSeriesService());
        for (ZoneId zone : new ZoneId[] { KIRITIMATI, PAGO_PAGO, COPENHAGEN }) {
            doReturn(zone).when(service).seriesZone(series);
            int before = MessageSeriesService.yearAt(Instant.now(), zone);
            int year = service.currentYear(series);
            int after = MessageSeriesService.yearAt(Instant.now(), zone);
            assertTrue(year == before || year == after,
                    "the current year in " + zone + " was " + year + ", expected " + before + " or " + after);
        }
    }
}
