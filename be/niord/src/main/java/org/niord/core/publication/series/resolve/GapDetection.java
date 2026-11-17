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

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Gap detection, UPCOMING, and dormancy.
 *
 * All of it is GATED, and the gate answers two questions. WHETHER a series is
 * expected to keep producing is a matter of the calendar alone: a cadence, an
 * ACTIVE status and a series that has not gone dormant. That is what a missing
 * period means -- a cadence period nothing was released for -- and it means the
 * same whatever the content rule, which is why a weekly in-force publication
 * that stopped is late in exactly the way a weekly tiling one is.
 *
 * HOW coverage between two releases is read is where the content rule matters.
 * Issues of a PUBLISHED_IN_INTERVAL series tile -- one ends, the next begins --
 * and a hole between them is read off their intervals. Issues of an
 * IN_FORCE_AT_CUTOFF series each carry everything in force at their cut-off and
 * have no lower bound at all: the 2026 and 2027 firing-areas issues share 31 of
 * their 32 members. Two consecutive releases of such a series leave nothing
 * between them by definition, however far apart their cut-offs -- a double week
 * is a double week, not a hole -- so a period between them is missing only
 * where a release was cut off in it and later withdrawn. The gate carries which
 * of the two readings applies, so the one synthesizer serves both.
 *
 * Dormancy is DERIVED, never stored. "We deliberately stopped" is RETIRED, which
 * is a decision and belongs in a column. "Nobody got round to it" is an
 * observation about the calendar, and an observation that is stored goes stale
 * the moment the calendar moves on.
 */
public final class GapDetection {

    /** Missed cadence periods before a series is considered dormant. */
    public static final int DORMANCY_PERIODS = 3;

    /**
     * Why gap detection did or did not run, as something an API can render.
     *
     * The prose says it once, for a human reading a log or a diagnostic. The code
     * is what a caller switches on and what a UI translates -- a client matching
     * on the sentence would break the first time the sentence is improved.
     */
    public enum Reason {
        /** The gate is open: an active series with a cadence. */
        CADENCED_SERIES,
        /** A one-off has no period to be missing. */
        NO_CADENCE,
        /** Only an ACTIVE series is expected to keep producing. */
        SERIES_NOT_ACTIVE,
        /** Already flagged dormant; per-period warnings would bury that. */
        SERIES_DORMANT,
        /** A cadence whose period length could not be computed. */
        CADENCE_PERIOD_UNKNOWN
    }

    /**
     * Why gap detection did or did not run, and how coverage is read when it did.
     *
     * Carried so the answer is explainable, and so the synthesizer reads coverage
     * the way the series' content rule says rather than deciding for itself.
     * tiling is true for a PUBLISHED_IN_INTERVAL series, whose issues carry a
     * lower bound and can leave a hole between them; false for everything else,
     * whose consecutive releases cover the stretch between their cut-offs by
     * definition.
     */
    public record Gate(boolean enabled, Reason code, String reason, boolean tiling) {
    }

    /** A period with no issue. */
    public record Gap(Date from, Date to, int index) {
    }

    private GapDetection() {
    }

    /**
     * Whether gap detection runs at all.
     *
     * @param relation the series' time relation
     * @param cadence the cadence, or null for a one-off
     * @param active whether the series is ACTIVE
     * @param dormant whether it is dormant, derived
     */
    public static Gate gate(TimeRelation relation, String cadence, boolean active, boolean dormant) {
        // The relation never closes the gate. It only says how coverage between
        // two releases is read once the gate is open: an in-force series with a
        // cadence is expected to keep releasing exactly as a tiling one is, and a
        // cadence-less series has no relation worth asking about (S-1 leaves it
        // with none), so NO_CADENCE has to be the first answer.
        boolean tiling = relation != null && relation.tiles();
        if (cadence == null || "NONE".equals(cadence)) {
            return new Gate(false, Reason.NO_CADENCE,
                    "this publication has no cadence, so there is no period it can be missing", tiling);
        }
        if (!active) {
            return new Gate(false, Reason.SERIES_NOT_ACTIVE,
                    "only an ACTIVE series is expected to keep producing issues", tiling);
        }
        if (dormant) {
            return new Gate(false, Reason.SERIES_DORMANT,
                    "a dormant series is already flagged as such; warning about every period since would "
                            + "bury the one fact that matters", tiling);
        }
        return new Gate(true, Reason.CADENCED_SERIES, "an active series with a cadence", tiling);
    }

    /**
     * The same gate, refusing to run when the cadence has no computable period.
     *
     * Not defensive padding. Adding a cadence constant without teaching
     * periodMillisOf about it yields a period of zero, and a zero period makes the
     * synthesizer return an empty list -- which a caller reporting the count alone
     * renders as "0 gaps" for a series nothing examined. That reads exactly like a
     * clean one, and it is the same confusion the reason code exists to prevent, so
     * the branch is here rather than left to whoever adds the constant.
     */
    public static Gate gate(TimeRelation relation, String cadence, boolean active, boolean dormant,
                            long periodMillis) {
        Gate gate = gate(relation, cadence, active, dormant);
        if (gate.enabled() && periodMillis <= 0) {
            return new Gate(false, Reason.CADENCE_PERIOD_UNKNOWN,
                    "the length of one " + cadence + " period could not be computed, so there is no "
                            + "period to call missing", gate.tiling());
        }
        return gate;
    }

    /**
     * The periods between consecutive issues where one is missing, read off the
     * cut-offs alone.
     *
     * The fallback for a tiling issue whose content period has no recorded
     * opening, where coverage cannot be proved and the release slots stand in.
     * Tiled from the previous cut-off exactly as uncovered() tiles, because a
     * cut-off CLOSES its period: with cut-offs on the Fridays of weeks 34 and 37,
     * the missing periods are the ones closing on the Fridays of weeks 35 and 36.
     * Anchored one period later, the slots were named after weeks 36 and 37 --
     * the second being the next issue's own week, so the strip showed a missing
     * cell beside the real one and a retro-create that overlapped it.
     *
     * Each period is stepped in the series' zone from its own start (stepOf), so
     * every missing week closes at the series' cut-off clock, also the one that
     * holds a clock change and the ones after it.
     *
     * Returns empty whenever the gate is closed, so a caller that forgets to
     * check still cannot produce a pseudo-row for an overlapping series.
     */
    public static List<Gap> gaps(Gate gate, List<Date> cutoffsAscending, String cadence, ZoneId zone) {
        return gaps(gate, cutoffsAscending, stepOf(cadence, zone));
    }

    /**
     * The same, every period the given fixed length. Right only where the length
     * is: UTC, or a cadence whose periods hold no clock change. A series' gap rows
     * are tiled in its zone by the overload that takes the cadence.
     */
    public static List<Gap> gaps(Gate gate, List<Date> cutoffsAscending, long periodMillis) {
        return gaps(gate, cutoffsAscending, fixedStep(periodMillis));
    }

    static List<Gap> gaps(Gate gate, List<Date> cutoffsAscending, UnaryOperator<Date> step) {
        List<Gap> out = new ArrayList<>();
        if (!gate.enabled() || step == null || cutoffsAscending == null || cutoffsAscending.size() < 2) {
            return out;
        }

        for (int i = 1; i < cutoffsAscending.size(); i++) {
            // A period and a half of slack: real releases drift by hours, and a
            // tighter bound would report a gap every time somebody published late.
            // The last period is the current issue's own.
            List<Date> bounds = periods(cutoffsAscending.get(i - 1), cutoffsAscending.get(i), step);
            for (int k = 0; k < bounds.size() - 2; k++) {
                out.add(new Gap(bounds.get(k), bounds.get(k + 1), out.size()));
            }
        }
        return out;
    }

    /**
     * The periods left UNCOVERED between one issue's close and the next issue's
     * open -- which is what "a period with no issue" actually means.
     *
     * Counting release slots between consecutive cut-offs reported every
     * double-week issue as one week present and one missing: the next issue
     * had carried both weeks, and its interval said so. Coverage is read off
     * the interval. A next issue that opens where the previous closed leaves
     * nothing uncovered however long its window; one that opens later leaves
     * the stretch in between, tiled into periods, with half a period of slack
     * for a release that drifted by hours.
     *
     * Returns empty whenever the gate is closed.
     */
    public static List<Gap> uncovered(Gate gate, Date previousClose, Date nextOpen, String cadence, ZoneId zone) {
        return uncovered(gate, previousClose, nextOpen, stepOf(cadence, zone));
    }

    /** The same, every period the given fixed length (see gaps). */
    public static List<Gap> uncovered(Gate gate, Date previousClose, Date nextOpen, long periodMillis) {
        return uncovered(gate, previousClose, nextOpen, fixedStep(periodMillis));
    }

    static List<Gap> uncovered(Gate gate, Date previousClose, Date nextOpen, UnaryOperator<Date> step) {
        List<Gap> out = new ArrayList<>();
        if (!gate.enabled() || previousClose == null || nextOpen == null || step == null) {
            return out;
        }
        List<Date> bounds = periods(previousClose, nextOpen, step);
        for (int k = 0; k < bounds.size() - 1; k++) {
            out.add(new Gap(bounds.get(k), bounds.get(k + 1), out.size()));
        }
        return out;
    }

    /**
     * The periods between two consecutive releases of a series whose issues do
     * NOT tile, where the only release cut off in the period was withdrawn.
     *
     * An in-force release carries everything in force at its cut-off, so two
     * consecutive releases cover the whole stretch between them whatever its
     * length: a fortnight between cut-offs is a double week, not a missing one,
     * and this is what lets a holiday issue stand without a warning. What a
     * stretch CAN hold is a release that was later withdrawn -- and withdrawing
     * is the statement that what went out for that period should not stand, so
     * the period is uncovered again and comes back as a MISSING row, exactly as
     * it does on a tiling series.
     *
     * The stretch is tiled from the previous release's cut-off, as uncovered()
     * tiles it, with the same rounding slack for a release that drifted by
     * hours; the last slot is the next release's own and is never a candidate.
     *
     * @param withdrawnCutoffs the cut-offs of every RETIRED issue of the series;
     *                         those outside the stretch are ignored
     */
    public static List<Gap> withdrawn(Gate gate, Date previousCutoff, Date nextCutoff,
                                      List<Date> withdrawnCutoffs, String cadence, ZoneId zone) {
        return withdrawn(gate, previousCutoff, nextCutoff, withdrawnCutoffs, stepOf(cadence, zone));
    }

    /** The same, every period the given fixed length (see gaps). */
    public static List<Gap> withdrawn(Gate gate, Date previousCutoff, Date nextCutoff,
                                      List<Date> withdrawnCutoffs, long periodMillis) {
        return withdrawn(gate, previousCutoff, nextCutoff, withdrawnCutoffs, fixedStep(periodMillis));
    }

    static List<Gap> withdrawn(Gate gate, Date previousCutoff, Date nextCutoff,
                               List<Date> withdrawnCutoffs, UnaryOperator<Date> step) {
        List<Gap> out = new ArrayList<>();
        if (!gate.enabled() || previousCutoff == null || nextCutoff == null || step == null
                || withdrawnCutoffs == null || withdrawnCutoffs.isEmpty()) {
            return out;
        }
        List<Date> bounds = periods(previousCutoff, nextCutoff, step);
        for (int k = 0; k < bounds.size() - 2; k++) {
            long from = bounds.get(k).getTime();
            long to = bounds.get(k + 1).getTime();
            // A cut-off belongs to the period it closes: strictly after the slot
            // opens, at or before it closes -- the same half-open reading as
            // membership at a shared cut-off.
            boolean withdrawnHere = withdrawnCutoffs.stream()
                    .anyMatch(c -> c != null && c.getTime() > from && c.getTime() <= to);
            if (withdrawnHere) {
                out.add(new Gap(new Date(from), new Date(to), out.size()));
            }
        }
        return out;
    }

    /**
     * Whether a series is dormant: nothing published for DORMANCY_PERIODS
     * cadence periods.
     *
     * For a weekly series that is three missed weeks -- long enough that a
     * holiday or a one-week slip does not raise it, short enough that a genuinely
     * abandoned series surfaces within a month.
     */
    public static boolean isDormant(Date lastIssueCutoff, Date now, long periodMillis) {
        if (lastIssueCutoff == null || now == null || periodMillis <= 0) {
            return false;
        }
        return now.getTime() - lastIssueCutoff.getTime() > DORMANCY_PERIODS * periodMillis;
    }

    /** The next nominal cut-off after the last issue: the UPCOMING row. */
    public static Date nextCutoff(Date lastIssueCutoff, long periodMillis) {
        return lastIssueCutoff == null ? null : new Date(lastIssueCutoff.getTime() + periodMillis);
    }

    /**
     * The cut-off one period of the cadence after from, counted in the zone (UTC
     * when null): the same local clock a day, a week, a month or a year later,
     * whatever clock change lies between. null when from is null or the cadence
     * has no period.
     */
    public static Date nextCutoff(Date from, String cadence, ZoneId zone) {
        UnaryOperator<Date> step = stepOf(cadence, zone);
        return from == null || step == null ? null : step.apply(from);
    }

    /**
     * One period of the cadence in the zone, as a step from a cut-off to the next;
     * null for a cadence with no period.
     *
     * Each period is stepped from its OWN start rather than tiled with one length:
     * a week or a day that holds a clock change is an hour shorter or longer than
     * its neighbours, and a month or a year depends on which, so a single length
     * measured anywhere -- at the newest issue, at the first gap -- moves every
     * slot it is applied to that is not the one it was measured at. Stepping from
     * each start is also what a draft opened on the slot does (IssueDraftService
     * measures one period from its anchor), so the row and the issue made from it
     * describe the same interval.
     */
    static UnaryOperator<Date> stepOf(String cadence, ZoneId zone) {
        if (cadence == null) {
            return null;
        }
        ZoneId z = zone == null ? ZoneId.of("UTC") : zone;
        return switch (cadence) {
            case "DAILY" -> from -> Date.from(ZonedDateTime.ofInstant(from.toInstant(), z).plusDays(1).toInstant());
            case "WEEKLY" -> from -> Date.from(ZonedDateTime.ofInstant(from.toInstant(), z).plusWeeks(1).toInstant());
            case "MONTHLY" -> from -> Date.from(ZonedDateTime.ofInstant(from.toInstant(), z).plusMonths(1).toInstant());
            case "YEARLY" -> from -> Date.from(ZonedDateTime.ofInstant(from.toInstant(), z).plusYears(1).toInstant());
            default -> null;
        };
    }

    /** Every period the same fixed length; null for a length that is not positive. */
    static UnaryOperator<Date> fixedStep(long periodMillis) {
        return periodMillis <= 0 ? null : from -> new Date(from.getTime() + periodMillis);
    }

    /**
     * The period bounds from from toward until: from, then each cut-off after it
     * whose period has at least half its length before until. The count of periods
     * is the stretch rounded to whole periods, half a period of slack either way
     * for a release that drifted by hours -- with one fixed length exactly the
     * Math.round(stretch / length) it replaces.
     */
    private static List<Date> periods(Date from, Date until, UnaryOperator<Date> step) {
        List<Date> bounds = new ArrayList<>();
        bounds.add(from);
        Date start = from;
        while (true) {
            Date end = step.apply(start);
            long middle = start.getTime() + (end.getTime() - start.getTime()) / 2;
            if (middle > until.getTime()) {
                return bounds;
            }
            bounds.add(end);
            start = end;
        }
    }

    /**
     * Milliseconds in one period of the given cadence, starting at around (now
     * when null) and measured in the zone (UTC when null).
     */
    public static long periodMillisOf(String cadence, ZoneId zone, Date around) {
        if (cadence == null) {
            return 0L;
        }
        return switch (cadence) {
            case "DAILY" -> dayMillis(zone, around);
            case "WEEKLY" -> weekMillis(zone, around);
            case "MONTHLY" -> monthMillis(zone, around);
            case "YEARLY" -> yearMillis(zone, around);
            default -> 0L;
        };
    }

    /* Days, weeks, months and years are not fixed lengths in the series' zone --
     * a day or a week that holds a clock change is an hour shorter or longer, a
     * month or a year depends on which -- so each is measured around the date in
     * question, and the next cut-off keeps the series' local clock. */
    private static long dayMillis(ZoneId zone, Date around) {
        ZonedDateTime t = at(zone, around);
        return t.plusDays(1).toInstant().toEpochMilli() - t.toInstant().toEpochMilli();
    }

    private static long weekMillis(ZoneId zone, Date around) {
        ZonedDateTime t = at(zone, around);
        return t.plusWeeks(1).toInstant().toEpochMilli() - t.toInstant().toEpochMilli();
    }

    private static long monthMillis(ZoneId zone, Date around) {
        ZonedDateTime t = at(zone, around);
        return t.plusMonths(1).toInstant().toEpochMilli() - t.toInstant().toEpochMilli();
    }

    private static long yearMillis(ZoneId zone, Date around) {
        ZonedDateTime t = at(zone, around);
        return t.plusYears(1).toInstant().toEpochMilli() - t.toInstant().toEpochMilli();
    }

    private static ZonedDateTime at(ZoneId zone, Date around) {
        return (around == null ? ZonedDateTime.now(zone == null ? ZoneId.of("UTC") : zone)
                : ZonedDateTime.ofInstant(around.toInstant(), zone == null ? ZoneId.of("UTC") : zone));
    }
}
