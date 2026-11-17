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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;

/**
 * A small, framework-neutral deterministic clock for tests.
 *
 * <p>The Niord production code reads wall-clock time directly via {@code new Date()} and has no
 * injectable {@link Clock}, so this helper does not (and cannot, without changing production code)
 * control the time observed by the application. Instead it gives tests a single, immutable, fully
 * deterministic time source for two purposes:
 * <ul>
 *   <li>building reproducible time-based <em>fixtures</em> (e.g. an AtoN timestamp, a message publish
 *       date) and computing the corresponding <em>oracles</em>, and</li>
 *   <li>driving production logic that accepts an explicit time argument (e.g.
 *       {@code ScheduledExecutionTimeUtil.computeNextExecutionTime(Date, ...)}) so the result is
 *       stable across runs and hosts.</li>
 * </ul>
 *
 * <p>Instances are immutable; the {@code plus*}/{@code minus*}/{@code at}/{@code zone} methods return
 * new instances. {@link #clock()} exposes a fixed {@link Clock} for code that is {@code Clock}-aware.
 * It also exposes the {@link #pastDate()}/{@link #futureDate()} constants that consolidate the
 * "far-past publish date" / "far-future date" pattern reproduced ad-hoc across the integration tests.
 *
 * <p>Lives in the niord test tree and reaches the niord-dk ITs through the niord-base
 * test-jar, like the rest of {@code org.niord.test}.
 */
public final class TestClock {

    /** A deterministic far-past instant (well before any seeded message/number sequence). */
    private static final Instant PAST = Instant.parse("2001-01-01T00:00:00Z");

    /** A deterministic far-future instant (well after any plausible test "now"). */
    private static final Instant FUTURE = Instant.parse("2099-12-31T00:00:00Z");

    private final Instant instant;
    private final ZoneId zone;

    private TestClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    /** A clock fixed at the given ISO-8601 instant (e.g. {@code "2020-06-15T12:30:00Z"}), zone UTC. */
    public static TestClock at(String iso8601Instant) {
        return new TestClock(Instant.parse(iso8601Instant), ZoneOffset.UTC);
    }

    /** A clock fixed at the given epoch-millisecond instant, zone UTC. */
    public static TestClock atEpochMilli(long epochMilli) {
        return new TestClock(Instant.ofEpochMilli(epochMilli), ZoneOffset.UTC);
    }

    /** The same instant, re-zoned (affects only {@link #clock()} / {@link #zone()}). */
    public TestClock withZone(ZoneId zone) {
        return new TestClock(instant, zone);
    }

    /** A new clock advanced by the given duration. */
    public TestClock plus(Duration duration) {
        return new TestClock(instant.plus(duration), zone);
    }

    /** A new clock advanced by the given number of whole days. */
    public TestClock plusDays(long days) {
        return new TestClock(instant.plus(Duration.ofDays(days)), zone);
    }

    /** A new clock moved back by the given number of whole days. */
    public TestClock minusDays(long days) {
        return new TestClock(instant.minus(Duration.ofDays(days)), zone);
    }

    /** A new clock advanced by the given number of whole hours. */
    public TestClock plusHours(long hours) {
        return new TestClock(instant.plus(Duration.ofHours(hours)), zone);
    }

    /** The fixed instant. */
    public Instant instant() {
        return instant;
    }

    /** The fixed instant as a {@link Date}. */
    public Date date() {
        return Date.from(instant);
    }

    /** The fixed instant as epoch milliseconds. */
    public long epochMilli() {
        return instant.toEpochMilli();
    }

    /** The zone of this clock. */
    public ZoneId zone() {
        return zone;
    }

    /** A {@link Clock} fixed at this instant/zone, for {@code Clock}-aware production code. */
    public Clock clock() {
        return Clock.fixed(instant, zone);
    }

    /** A deterministic far-past date, for publish-date / created-date fixtures. */
    public static Date pastDate() {
        return Date.from(PAST);
    }

    /** A deterministic far-future date, for expiry / publish-to fixtures. */
    public static Date futureDate() {
        return Date.from(FUTURE);
    }
}
