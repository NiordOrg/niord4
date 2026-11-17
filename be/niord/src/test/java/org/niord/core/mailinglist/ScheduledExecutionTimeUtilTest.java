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

package org.niord.core.mailinglist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Unit tests that pin the behaviour of {@link ScheduledExecutionTimeUtil}, the helper used to
 * compute the next firing time of a scheduled mailing-list trigger.
 * <p>
 * Both overloads of {@code computeNextExecutionTime} are exercised:
 * <ul>
 *   <li>the explicit {@code (baseTime, scheduleType, time, zone)} overload, which is fully
 *       deterministic and therefore the primary subject under test, and</li>
 *   <li>the {@code (MailingListTrigger)} overload, which delegates to the explicit overload using
 *       {@code new Date()} ("now") and is therefore tested only for its validation contract and
 *       for the fact that it produces a future-ish, non-null result.</li>
 * </ul>
 * <p>
 * Non-obvious oracles pinned here:
 * <ul>
 *   <li><b>Strict "before" comparison.</b> The code rolls forward only when the candidate time is
 *       strictly {@code isBefore} the base instant. An instant equal to the base therefore stays on
 *       the same day / same week.</li>
 *   <li><b>ISO-week weekday semantics.</b> Weekday schedule types use
 *       {@code ZonedDateTime.with(DayOfWeek)}, which moves the date to that weekday <em>within the
 *       same Mon..Sun ISO week</em>. A weekday earlier in the week than the base therefore produces a
 *       candidate in the past, which then rolls forward a full week.</li>
 *   <li><b>DST crossing.</b> The reference dates straddle the 2017-03-26 European DST change
 *       (CET&rarr;CEST), so wall-clock-preserving arithmetic shifts the underlying instant. The
 *       formatted zone abbreviation (CET vs CEST) is asserted to lock this in.</li>
 *   <li><b>Strict full-string validation.</b> The time is validated with
 *       {@link java.util.regex.Matcher#matches()}, so the WHOLE input must be a legal HH:MM;
 *       malformed inputs such as {@code "25:00"} or {@code "junk 08:45 trailing"} are rejected with
 *       an {@code IllegalArgumentException} (docs/potential-errors.md ML1).</li>
 * </ul>
 * The Europe/Copenhagen time zone and the {@code "yyyy-MM-dd HH:mm:ss z"} format are used (matching
 * the existing {@code MailingListTest}) so that the expected strings are human-readable and
 * DST-sensitive.
 */
public class ScheduledExecutionTimeUtilTest {

    private static final String TZ = "Europe/Copenhagen";

    private SimpleDateFormat sdf;
    private SimpleDateFormat hhmm;

    @BeforeEach
    public void setUp() {
        sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
        sdf.setTimeZone(TimeZone.getTimeZone(TZ));
        hhmm = new SimpleDateFormat("HH:mm");
        hhmm.setTimeZone(TimeZone.getTimeZone(TZ));
    }

    private Date parse(String s) throws Exception {
        return sdf.parse(s);
    }

    /*****************************/
    /** DAILY schedule          **/
    /*****************************/

    @Test
    public void dailyBaseEarlierSameDayKeepsSameDay() throws Exception {
        Date next = ScheduledExecutionTimeUtil.computeNextExecutionTime(
                parse("2017-03-25 15:00:00 +0100"),
                ScheduleType.DAILY,
                "15:55",
                TZ);

        assertEquals("2017-03-25 15:55:00 CET", sdf.format(next),
                "Target time is later today, so it fires today");
    }

    @Test
    public void dailyBaseLaterSameDayRollsToNextDayAcrossDst() throws Exception {
        // Base 15:56 is past the 15:55 target, so it rolls to the next day.
        // 2017-03-26 is the DST switch day in Europe, so the abbreviation flips CET -> CEST.
        Date next = ScheduledExecutionTimeUtil.computeNextExecutionTime(
                parse("2017-03-25 15:56:00 +0100"),
                ScheduleType.DAILY,
                "15:55",
                TZ);

        assertEquals("2017-03-26 15:55:00 CEST", sdf.format(next),
                "Target already passed today, so it fires tomorrow (DST began 2017-03-26)");
    }

    @Test
    public void dailyBaseExactlyAtTargetKeepsSameDay() throws Exception {
        // The base instant equals the candidate instant (seconds=00). Because the code uses a
        // strict isBefore() check, an equal instant is NOT before, so it stays on the same day.
        Date next = ScheduledExecutionTimeUtil.computeNextExecutionTime(
                parse("2017-03-25 15:55:00 +0100"),
                ScheduleType.DAILY,
                "15:55",
                TZ);

        assertEquals("2017-03-25 15:55:00 CET", sdf.format(next),
                "Equal instant is not strictly before base, so it stays today");
    }

    @Test
    public void dailyBaseOneSecondBeforeTargetKeepsSameDay() throws Exception {
        // 15:54:59 is before the 15:55:00 target -> fires today.
        Date next = ScheduledExecutionTimeUtil.computeNextExecutionTime(
                parse("2017-03-25 15:54:59 +0100"),
                ScheduleType.DAILY,
                "15:55",
                TZ);

        assertEquals("2017-03-25 15:55:00 CET", sdf.format(next));
    }

    @Test
    public void dailyMidnightTargetIsHonoured() throws Exception {
        // "00:00" parses hour=0 minute=0. Base is just after midnight -> rolls to next midnight.
        Date next = ScheduledExecutionTimeUtil.computeNextExecutionTime(
                parse("2017-03-25 00:01:00 +0100"),
                ScheduleType.DAILY,
                "00:00",
                TZ);

        assertEquals("2017-03-26 00:00:00 CET", sdf.format(next));
    }

    /*****************************/
    /** Weekday schedule        **/
    /*****************************/

    @Test
    public void saturdayFromSaturdayBaseAfterTimeRollsOneWeek() throws Exception {
        // 2017-03-25 is a Saturday. Base 15:56 is past the 15:55 target on that same Saturday,
        // so the Saturday candidate is in the past and rolls forward exactly one week.
        Date next = ScheduledExecutionTimeUtil.computeNextExecutionTime(
                parse("2017-03-25 15:56:00 +0100"),
                ScheduleType.SATURDAY,
                "15:55",
                TZ);

        assertEquals("2017-04-01 15:55:00 CEST", sdf.format(next));
    }

    @Test
    public void saturdayFromSaturdayBaseBeforeTimeStaysSameDay() throws Exception {
        // Same Saturday, but base 15:00 is before the 15:55 target -> fires today.
        Date next = ScheduledExecutionTimeUtil.computeNextExecutionTime(
                parse("2017-03-25 15:00:00 +0100"),
                ScheduleType.SATURDAY,
                "15:55",
                TZ);

        assertEquals("2017-03-25 15:55:00 CET", sdf.format(next));
    }

    @Test
    public void weekdayEarlierInIsoWeekRollsToFollowingWeek() throws Exception {
        // Base is a Wednesday (2017-03-29). Scheduling MONDAY targets a day earlier in the same
        // ISO week (Mon..Sun), so the candidate lands in the past and rolls forward a full week
        // to the FOLLOWING Monday, 2017-04-03.
        Date base = parse("2017-03-29 12:00:00 +0200");
        Date next = ScheduledExecutionTimeUtil.computeNextExecutionTime(
                base,
                ScheduleType.MONDAY,
                "12:00",
                TZ);

        assertEquals("2017-04-03 12:00:00 CEST", sdf.format(next),
                "Monday is earlier in the ISO week than a Wednesday base -> next week's Monday");

        assertTrue(next.after(base), "Result must be strictly after the base");
        assertEquals(Calendar.MONDAY, dayOfWeek(next), "Result lands on a Monday");
    }

    @Test
    public void weekdayLaterInIsoWeekStaysSameWeek() throws Exception {
        // Base is a Monday (2017-03-27). Scheduling FRIDAY at a later wall time targets a day
        // later in the same ISO week, so it fires that same week's Friday (2017-03-31).
        Date base = parse("2017-03-27 12:00:00 +0200");
        Date next = ScheduledExecutionTimeUtil.computeNextExecutionTime(
                base,
                ScheduleType.FRIDAY,
                "14:00",
                TZ);

        assertEquals("2017-03-31 14:00:00 CEST", sdf.format(next),
                "Friday is later in the same ISO week than a Monday base -> this week's Friday");

        assertTrue(next.after(base), "Result must be strictly after the base");
        assertEquals(Calendar.FRIDAY, dayOfWeek(next), "Result lands on a Friday");
        long deltaDays = (next.getTime() - base.getTime()) / (24L * 60 * 60 * 1000);
        assertTrue(deltaDays < 7, "Same-week Friday is within 7 days of the Monday base");
    }

    @Test
    public void weekdaySameDayLaterTimeStaysSameDay() throws Exception {
        // Base is a Friday (2017-03-31) at 13:00, scheduling FRIDAY at 14:00 -> same Friday.
        Date base = parse("2017-03-31 13:00:00 +0200");
        Date next = ScheduledExecutionTimeUtil.computeNextExecutionTime(
                base,
                ScheduleType.FRIDAY,
                "14:00",
                TZ);

        assertEquals("2017-03-31 14:00:00 CEST", sdf.format(next));
        assertEquals(Calendar.FRIDAY, dayOfWeek(next), "Result lands on a Friday");
    }

    @Test
    public void weekdaySameDayEarlierTimeRollsOneWeek() throws Exception {
        // Base is a Friday (2017-03-31) at 15:00, scheduling FRIDAY at 14:00 -> candidate is
        // earlier today and therefore in the past, so it rolls a full week to 2017-04-07.
        Date base = parse("2017-03-31 15:00:00 +0200");
        Date next = ScheduledExecutionTimeUtil.computeNextExecutionTime(
                base,
                ScheduleType.FRIDAY,
                "14:00",
                TZ);

        assertEquals("2017-04-07 14:00:00 CEST", sdf.format(next));
        assertEquals(Calendar.FRIDAY, dayOfWeek(next), "Result lands on a Friday");
        long deltaDays = (next.getTime() - base.getTime()) / (24L * 60 * 60 * 1000);
        assertTrue(deltaDays >= 6 && deltaDays <= 7, "Rolled forward roughly one week");
    }

    /*****************************/
    /** Time-zone behaviour     **/
    /*****************************/

    @Test
    public void differentZonesYieldDifferentInstantsForSameWallTime() throws Exception {
        // Same baseTime instant, same "12:00" wall target, but two zones.
        // 12:00 Copenhagen and 12:00 UTC are different instants, so the results differ.
        Date base = parse("2017-06-15 09:00:00 +0200"); // mid-summer, unambiguous

        Date inCph = ScheduledExecutionTimeUtil.computeNextExecutionTime(
                base, ScheduleType.DAILY, "12:00", "Europe/Copenhagen");
        Date inUtc = ScheduledExecutionTimeUtil.computeNextExecutionTime(
                base, ScheduleType.DAILY, "12:00", "UTC");

        assertTrue(!inCph.equals(inUtc),
                "12:00 in Copenhagen and 12:00 in UTC are not the same instant");
        // In summer Copenhagen is UTC+2, so 12:00 CEST happens 2h before 12:00 UTC.
        assertEquals(2L * 60 * 60 * 1000, inUtc.getTime() - inCph.getTime(),
                "Difference between the two zones is two hours");
    }

    @Test
    public void blankZoneFallsBackToSystemDefaultAndStillComputes() throws Exception {
        // A null/blank zone falls back to the system default zone. The exact instant depends on
        // the host zone, but the result must still be a same-or-future daily firing on the
        // requested wall time. We assert via the system-default-zoned wall clock.
        Date base = parse("2017-06-15 09:00:00 +0200");

        Date nextNull = ScheduledExecutionTimeUtil.computeNextExecutionTime(
                base, ScheduleType.DAILY, "23:30", null);
        Date nextBlank = ScheduledExecutionTimeUtil.computeNextExecutionTime(
                base, ScheduleType.DAILY, "23:30", "   ");

        assertEquals(nextNull, nextBlank,
                "null and blank zone are treated identically (system default)");

        SimpleDateFormat sysFmt = new SimpleDateFormat("HH:mm");
        sysFmt.setTimeZone(TimeZone.getDefault());
        assertEquals("23:30", sysFmt.format(nextNull),
                "Fires at the requested wall time in the system default zone");
        assertTrue(!nextNull.before(base), "Computed firing is not before the base");
    }

    /*****************************/
    /** Validation - explicit overload **/
    /*****************************/

    @Test
    public void nullBaseTimeThrows() {
        try {
            ScheduledExecutionTimeUtil.computeNextExecutionTime(null, ScheduleType.DAILY, "12:00", TZ);
            fail("Expected IllegalArgumentException for null baseTime");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid scheduled execution parameters", e.getMessage());
        }
    }

    @Test
    public void nullScheduleTypeThrows() throws Exception {
        try {
            ScheduledExecutionTimeUtil.computeNextExecutionTime(
                    parse("2017-03-25 15:00:00 +0100"), null, "12:00", TZ);
            fail("Expected IllegalArgumentException for null scheduleType");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid scheduled execution parameters", e.getMessage());
        }
    }

    @Test
    public void emptyTimeThrows() throws Exception {
        try {
            ScheduledExecutionTimeUtil.computeNextExecutionTime(
                    parse("2017-03-25 15:00:00 +0100"), ScheduleType.DAILY, "", TZ);
            fail("Expected IllegalArgumentException for empty time");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid scheduled execution parameters", e.getMessage());
        }
    }

    @Test
    public void blankTimeThrows() throws Exception {
        try {
            ScheduledExecutionTimeUtil.computeNextExecutionTime(
                    parse("2017-03-25 15:00:00 +0100"), ScheduleType.DAILY, "   ", TZ);
            fail("Expected IllegalArgumentException for blank time");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid scheduled execution parameters", e.getMessage());
        }
    }

    @Test
    public void unparseableTimeThrows() throws Exception {
        // "abc" is not a legal HH:MM, so Matcher.matches() returns false.
        try {
            ScheduledExecutionTimeUtil.computeNextExecutionTime(
                    parse("2017-03-25 15:00:00 +0100"), ScheduleType.DAILY, "abc", TZ);
            fail("Expected IllegalArgumentException for unparseable time");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid scheduled execution time format abc", e.getMessage());
        }
    }

    @Test
    public void timeWithoutColonThrows() throws Exception {
        // "1200" has no colon, so it is not a legal HH:MM and Matcher.matches() returns false.
        try {
            ScheduledExecutionTimeUtil.computeNextExecutionTime(
                    parse("2017-03-25 15:00:00 +0100"), ScheduleType.DAILY, "1200", TZ);
            fail("Expected IllegalArgumentException for colon-less time");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid scheduled execution time format 1200", e.getMessage());
        }
    }

    /*****************************/
    /** Strict full-string validation **/
    /*****************************/

    @Test
    public void outOfRangeHourIsRejectedNotSilentlyParsed() throws Exception {
        // Fixed (docs/potential-errors.md ML1): validation now anchors the whole string via
        // Matcher.matches(), so an out-of-range value like "25:00" is REJECTED rather than having
        // its trailing "5:00" substring silently parsed as 05:00.
        try {
            ScheduledExecutionTimeUtil.computeNextExecutionTime(
                    parse("2017-03-29 01:00:00 +0200"),
                    ScheduleType.DAILY,
                    "25:00",
                    TZ);
            fail("Expected IllegalArgumentException for out-of-range hour '25:00'");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid scheduled execution time format 25:00", e.getMessage());
        }
    }

    @Test
    public void timeWithSurroundingJunkIsRejected() throws Exception {
        // The whole input must match: surrounding junk around a valid HH:MM no longer parses.
        try {
            ScheduledExecutionTimeUtil.computeNextExecutionTime(
                    parse("2017-03-29 01:00:00 +0200"),
                    ScheduleType.DAILY,
                    "junk 08:45 trailing",
                    TZ);
            fail("Expected IllegalArgumentException for time with surrounding junk");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid scheduled execution time format junk 08:45 trailing", e.getMessage());
        }
    }

    @Test
    public void validBoundaryTimesStillParse() throws Exception {
        // Sanity: the full-string match still accepts every legal HH:MM, including the boundaries
        // and the single-digit-hour form, so the stricter validation did not narrow valid input.
        for (String t : new String[]{"00:00", "9:30", "23:59", "15:55"}) {
            Date next = ScheduledExecutionTimeUtil.computeNextExecutionTime(
                    parse("2017-06-15 00:00:01 +0200"), ScheduleType.DAILY, t, TZ);
            String expected = t.length() == 4 ? "0" + t : t; // "9:30" -> wall clock "09:30"
            assertEquals(expected, hhmm.format(next), "Valid time " + t + " must still parse");
        }
    }

    @Test
    public void whitespacePaddedButValidTimeStillParses() throws Exception {
        // The value is trimmed before matching, so a surrounding-whitespace-but-otherwise-valid time
        // keeps parsing as it did under the old find()-based check (only genuinely invalid values
        // such as "25:00" / embedded junk now throw).
        Date next = ScheduledExecutionTimeUtil.computeNextExecutionTime(
                parse("2017-06-15 00:00:01 +0200"), ScheduleType.DAILY, "  08:45  ", TZ);
        assertEquals("08:45", hhmm.format(next));
    }

    /*****************************/
    /** Validation - trigger overload **/
    /*****************************/

    @Test
    public void triggerOverloadNullTriggerThrows() {
        try {
            ScheduledExecutionTimeUtil.computeNextExecutionTime((MailingListTrigger) null);
            fail("Expected IllegalArgumentException for null trigger");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid scheduled mailing list trigger", e.getMessage());
        }
    }

    @Test
    public void triggerOverloadNonScheduledTypeThrows() {
        MailingListTrigger t = new MailingListTrigger();
        t.setType(TriggerType.STATUS_CHANGE);
        t.setScheduleType(ScheduleType.DAILY);
        t.setScheduledExecutionTime("15:55");
        try {
            ScheduledExecutionTimeUtil.computeNextExecutionTime(t);
            fail("Expected IllegalArgumentException for non-SCHEDULED trigger type");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid scheduled mailing list trigger", e.getMessage());
        }
    }

    @Test
    public void triggerOverloadNullScheduleTypeThrows() {
        MailingListTrigger t = new MailingListTrigger();
        t.setType(TriggerType.SCHEDULED);
        t.setScheduleType(null);
        t.setScheduledExecutionTime("15:55");
        try {
            ScheduledExecutionTimeUtil.computeNextExecutionTime(t);
            fail("Expected IllegalArgumentException for null scheduleType on scheduled trigger");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid scheduled mailing list trigger", e.getMessage());
        }
    }

    @Test
    public void triggerOverloadBlankExecutionTimeThrows() {
        MailingListTrigger t = new MailingListTrigger();
        t.setType(TriggerType.SCHEDULED);
        t.setScheduleType(ScheduleType.DAILY);
        t.setScheduledExecutionTime("   ");
        try {
            ScheduledExecutionTimeUtil.computeNextExecutionTime(t);
            fail("Expected IllegalArgumentException for blank scheduledExecutionTime");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid scheduled mailing list trigger", e.getMessage());
        }
    }

    @Test
    public void triggerOverloadNullExecutionTimeThrows() {
        MailingListTrigger t = new MailingListTrigger();
        t.setType(TriggerType.SCHEDULED);
        t.setScheduleType(ScheduleType.DAILY);
        t.setScheduledExecutionTime(null);
        try {
            ScheduledExecutionTimeUtil.computeNextExecutionTime(t);
            fail("Expected IllegalArgumentException for null scheduledExecutionTime");
        } catch (IllegalArgumentException e) {
            assertEquals("Invalid scheduled mailing list trigger", e.getMessage());
        }
    }

    @Test
    public void triggerOverloadValidScheduledTriggerReturnsFutureRelativeToNow() {
        // The trigger overload computes relative to new Date() ("now"). Capture a lower bound just
        // before the call: the returned firing must be at or after that bound (it is either later
        // today or rolled to tomorrow, never in the past).
        MailingListTrigger t = new MailingListTrigger();
        t.setType(TriggerType.SCHEDULED);
        t.setScheduleType(ScheduleType.DAILY);
        t.setScheduledExecutionTime("15:55");
        t.setScheduledExecutionTimeZone(TZ);

        Date lowerBound = new Date();
        Date next = ScheduledExecutionTimeUtil.computeNextExecutionTime(t);

        assertTrue(next != null, "A valid scheduled trigger must yield a non-null firing time");
        assertTrue(!next.before(lowerBound),
                "The next firing must not be before the moment the call started");
        // A DAILY trigger always fires within the next 24h of "now".
        long horizon = lowerBound.getTime() + 24L * 60 * 60 * 1000 + 60_000; // +1min slack
        assertTrue(next.getTime() <= horizon, "A daily trigger fires within the next ~24 hours");
    }

    @Test
    public void triggerOverloadResultMatchesExplicitOverloadSemantics() {
        // The trigger overload must compute the same value as the explicit overload would for the
        // same "now". We can't share the exact internal Date, but a DAILY 23:59 trigger and a
        // recomputed explicit call must agree on the firing wall-clock minute.
        MailingListTrigger t = new MailingListTrigger();
        t.setType(TriggerType.SCHEDULED);
        t.setScheduleType(ScheduleType.DAILY);
        t.setScheduledExecutionTime("23:59");
        t.setScheduledExecutionTimeZone(TZ);

        Date next = ScheduledExecutionTimeUtil.computeNextExecutionTime(t);

        assertEquals("23:59", hhmm.format(next),
                "DAILY trigger fires exactly at the requested wall-clock minute");
    }

    /*****************************/
    /** Helpers                 **/
    /*****************************/

    private int dayOfWeek(Date date) {
        Calendar cal = new GregorianCalendar(TimeZone.getTimeZone(TZ));
        cal.setTime(date);
        return cal.get(Calendar.DAY_OF_WEEK);
    }
}
