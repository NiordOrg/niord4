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
package org.niord.test;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Self-tests for the {@link TestClock} deterministic-time helper. These pin the helper's date
 * arithmetic to exact epoch values so a regression in the convenience does not silently corrupt the
 * fixtures/oracles of the tests that depend on it.
 */
public class TestClockTest {

    private static final long Y2020_NOON_UTC = Instant.parse("2020-06-15T12:00:00Z").toEpochMilli();

    @Test
    public void atParsesTheInstantAndExposesItAsDateAndMillis() {
        TestClock clock = TestClock.at("2020-06-15T12:00:00Z");

        assertEquals(Y2020_NOON_UTC, clock.epochMilli());
        assertEquals(Y2020_NOON_UTC, clock.date().getTime());
        assertEquals(Instant.parse("2020-06-15T12:00:00Z"), clock.instant());
    }

    @Test
    public void atEpochMilliRoundTrips() {
        assertEquals(Y2020_NOON_UTC, TestClock.atEpochMilli(Y2020_NOON_UTC).epochMilli());
    }

    @Test
    public void plusAndMinusReturnNewShiftedInstancesWithoutMutating() {
        TestClock base = TestClock.at("2020-06-15T12:00:00Z");

        TestClock plusDay = base.plusDays(1);
        TestClock plusTwoHours = base.plusHours(2);
        TestClock minusDay = base.minusDays(1);
        TestClock plusDuration = base.plus(Duration.ofMinutes(30));

        assertEquals(Y2020_NOON_UTC + 86_400_000L, plusDay.epochMilli());
        assertEquals(Y2020_NOON_UTC + 2 * 3_600_000L, plusTwoHours.epochMilli());
        assertEquals(Y2020_NOON_UTC - 86_400_000L, minusDay.epochMilli());
        assertEquals(Y2020_NOON_UTC + 1_800_000L, plusDuration.epochMilli());

        // Immutability: the base is untouched by the derivations above.
        assertEquals(Y2020_NOON_UTC, base.epochMilli());
    }

    @Test
    public void fixedClockReportsTheSetInstantAndZone() {
        TestClock clock = TestClock.at("2020-06-15T12:00:00Z").withZone(ZoneId.of("Europe/Copenhagen"));

        assertEquals(Instant.parse("2020-06-15T12:00:00Z"), clock.clock().instant());
        assertEquals(ZoneId.of("Europe/Copenhagen"), clock.clock().getZone());
    }

    @Test
    public void pastDateIsStrictlyBeforeFutureDateAndBothAreStable() {
        assertTrue(TestClock.pastDate().before(TestClock.futureDate()),
                "the far-past fixture must precede the far-future fixture");
        assertEquals(Instant.parse("2001-01-01T00:00:00Z").toEpochMilli(),
                TestClock.pastDate().getTime());
        assertEquals(Instant.parse("2099-12-31T00:00:00Z").toEpochMilli(),
                TestClock.futureDate().getTime());
    }
}
