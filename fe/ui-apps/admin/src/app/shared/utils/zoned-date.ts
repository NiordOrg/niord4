/**
 * Date utilities that operate in a target IANA timezone (e.g. "America/Halifax",
 * "Europe/Copenhagen") rather than the browser's local timezone. Used to render and
 * interpret message dates in the active session domain's timezone.
 *
 * Pure functions, no DI, no external libraries — everything is built on
 * `Intl.DateTimeFormat.formatToParts` which is widely supported and IANA-correct.
 */

const PARTS_FORMATTER_CACHE = new Map<string, Intl.DateTimeFormat>();

function getPartsFormatter(timeZone: string): Intl.DateTimeFormat {
  let fmt = PARTS_FORMATTER_CACHE.get(timeZone);
  if (!fmt) {
    fmt = new Intl.DateTimeFormat('en-US', {
      timeZone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    });
    PARTS_FORMATTER_CACHE.set(timeZone, fmt);
  }
  return fmt;
}

export interface ZonedDateParts {
  readonly year: number;
  /** 0-indexed, matches `Date.getMonth()`. */
  readonly month: number;
  readonly day: number;
  readonly hour: number;
  readonly minute: number;
  readonly second: number;
}

/**
 * Return the wall-clock Y/M/D/h/m/s in `timeZone` for the given UTC instant.
 */
export function getZonedDateParts(instant: Date, timeZone: string): ZonedDateParts {
  const parts = getPartsFormatter(timeZone).formatToParts(instant);
  const map: Record<string, string> = {};
  for (const p of parts) {
    if (p.type !== 'literal') map[p.type] = p.value;
  }
  // Intl can emit '24' for midnight in some locales; normalize to 0.
  const hour = parseInt(map['hour'], 10) % 24;
  return {
    year: parseInt(map['year'], 10),
    month: parseInt(map['month'], 10) - 1,
    day: parseInt(map['day'], 10),
    hour,
    minute: parseInt(map['minute'], 10),
    second: parseInt(map['second'], 10),
  };
}

/**
 * Convert a wall-clock time (Y/M/D/h/m/s) — interpreted as a moment IN `timeZone` —
 * to the corresponding UTC `Date`.
 *
 * DST handling uses a two-pass approach: first guess assuming `timeZone` has the
 * naive offset, then refine using the actual offset at the first-guess instant. This
 * is correct everywhere except for two pathological DST cases:
 *   - Spring-forward "missing" hour (e.g. 02:30 on a spring-forward day in some zones
 *     doesn't exist). We return the post-transition instant matching the same
 *     wall-clock + 1 hour later.
 *   - Fall-back "ambiguous" hour (e.g. 01:30 occurs twice). We return the second
 *     (post-transition) occurrence, which matches Java/`luxon`/`date-fns-tz` convention.
 */
export function zonedDateToUtc(
  year: number,
  month: number,
  day: number,
  hour: number,
  minute: number,
  second: number,
  timeZone: string,
): Date {
  // First pass: pretend the wall-clock is UTC, then correct via the target-zone offset.
  const naiveUtc = Date.UTC(year, month, day, hour, minute, second);
  const offsetMs1 = zonedOffsetMs(new Date(naiveUtc), timeZone);
  const candidate1Ms = naiveUtc - offsetMs1;

  // Second pass: at the first-guess instant, recompute the offset. If it differs we
  // crossed a DST boundary — re-apply with the corrected offset.
  const offsetMs2 = zonedOffsetMs(new Date(candidate1Ms), timeZone);
  if (offsetMs2 === offsetMs1) {
    return new Date(candidate1Ms);
  }
  return new Date(naiveUtc - offsetMs2);
}

/**
 * Start-of-day (00:00:00) in the target zone, as a UTC `Date`, derived from the
 * Y/M/D of `date` as observed in the target zone.
 */
export function startOfDayInZone(date: Date, timeZone: string): Date {
  const parts = getZonedDateParts(date, timeZone);
  return zonedDateToUtc(parts.year, parts.month, parts.day, 0, 0, 0, timeZone);
}

/**
 * End-of-day in the target zone, as a UTC `Date`, derived from the Y/M/D of `date`
 * as observed in the target zone.
 *
 * The instant is the last millisecond of the day (23:59:59.**999** on an ordinary day,
 * the next day's start minus 1 ms on every day), not the last whole second. An inclusive upper bound that stopped at `.000` would exclude the
 * final second of the day, so anything stamped in it would fall outside a range the
 * user asked to include.
 */
export function endOfDayInZone(date: Date, timeZone: string): Date {
  const parts = getZonedDateParts(date, timeZone);
  return lastMillisecondOfZonedDay(parts.year, parts.month, parts.day, timeZone);
}

/**
 * Reinterpret the calendar day a date picker handed us as a day in `timeZone`.
 *
 * A picker Date carries the picked day as a BROWSER-local Y/M/D (that is what the
 * user saw on the calendar grid); its time part is meaningless. This takes that
 * Y/M/D and returns the instant at which the same calendar day starts (`'start'`,
 * 00:00:00.000) or ends (`'end'`, 23:59:59.999) in `timeZone`.
 *
 * Note the difference from {@link startOfDayInZone} / {@link endOfDayInZone}, which
 * decompose the *instant* by the zone's calendar and so re-bucket it — off by one
 * day whenever the zone lies west of the browser's.
 */
export function pickedDayToZone(picked: Date, timeZone: string, edge: 'start' | 'end'): Date {
  const year = picked.getFullYear();
  const month = picked.getMonth();
  const day = picked.getDate();
  return edge === 'start'
    ? zonedDateToUtc(year, month, day, 0, 0, 0, timeZone)
    : lastMillisecondOfZonedDay(year, month, day, timeZone);
}

/**
 * The zone's calendar day for `instant`, as a browser-local `Date` at local midnight.
 *
 * For feeding a calendar grid, which reads `getFullYear`/`getMonth`/`getDate` and so
 * always draws browser-local days: fabricating a browser-local Date with the zone's
 * Y/M/D makes the grid highlight the day the zone is actually on.
 */
export function dayInZoneToLocalDate(instant: Date, timeZone: string): Date {
  const { year, month, day } = getZonedDateParts(instant, timeZone);
  return new Date(year, month, day);
}

/**
 * Shift `instant` by whole days, keeping the same wall clock in `timeZone`.
 *
 * Adding 24h worth of milliseconds is not the same thing: across a DST transition
 * that lands an hour off the wall clock the user reasoned about. This moves the
 * zone's day number and re-resolves the offset, so "same time tomorrow" stays the
 * same time.
 */
export function shiftDaysInZone(instant: Date, days: number, timeZone: string): Date {
  const parts = getZonedDateParts(instant, timeZone);
  return zonedDateToUtc(
    parts.year,
    parts.month,
    parts.day + days,
    parts.hour,
    parts.minute,
    parts.second,
    timeZone,
  );
}

/**
 * A wall clock (`hour`/`minute`/`second`) on the calendar day a picker handed us,
 * resolved as an instant in `timeZone`.
 *
 * The Y/M/D is read browser-locally — it is the day drawn on the picker's grid — and
 * only the time of day is reinterpreted as belonging to `timeZone`.
 */
export function wallClockToInstant(
  picked: Date,
  hour: number,
  minute: number,
  second: number,
  timeZone: string,
): Date {
  return zonedDateToUtc(
    picked.getFullYear(),
    picked.getMonth(),
    picked.getDate(),
    hour,
    minute,
    second,
    timeZone,
  );
}

/**
 * The wall clock `instant` reads at in `timeZone`, as a browser-local `Date` a zone-less
 * `HH:mm` control can display — the inverse of {@link wallClockToInstant}.
 *
 * A time-of-day control carries a bare clock on a `Date` and converts nothing, so an owner
 * of a real instant hands it the clock rather than the instant. Only hours and minutes are
 * meaningful in the result; the date part is the fixed carrier day 2000-01-01, because a
 * real calendar day plus this clock could land in the *browser's* own spring-forward gap,
 * which rolls the `Date` an hour forward and would show a time nobody entered.
 */
export function zonedWallClockAsLocalDate(instant: Date, timeZone: string): Date {
  const { hour, minute } = getZonedDateParts(instant, timeZone);
  return new Date(WALL_CLOCK_CARRIER_YEAR, 0, 1, hour, minute, 0, 0);
}

/** The carrier day every bare-clock `Date` in the app sits on: 1 January 2000. */
const WALL_CLOCK_CARRIER_YEAR = 2000;

/** A time of day with no date and so no zone — hours and minutes, nothing else. */
export interface WallClockTime {
  readonly hour: number;
  readonly minute: number;
}

/**
 * The bare clock a carrier `Date` holds — the reader half of the contract
 * {@link zonedWallClockAsLocalDate} writes.
 *
 * A time-of-day control has no zone and takes none: its value is a browser-local `Date`
 * whose date part is meaningless. Reading its hours and minutes locally is the point, not
 * an oversight; going through this says so.
 */
export function wallClockOf(carrier: Date): WallClockTime {
  return { hour: carrier.getHours(), minute: carrier.getMinutes() };
}

/** Matches a 24-hour `HH:mm`, rejecting anything that is not a whole valid time of day. */
const WALL_CLOCK_TIME = /^([01]?\d|2[0-3]):([0-5]\d)$/;

/**
 * A stored `HH:mm` (24-hour) setting — a cut-off time, a scheduled send time — as the
 * carrier `Date` a time-of-day control binds to.
 *
 * Such a setting is deliberately zone-less: a clock with no date is not an instant, so
 * there is nothing for a zone to resolve, and the value goes to the backend as the same
 * `HH:mm` string it came from. A value the pattern does not recognise yields `null` rather
 * than a guess — a half-typed time is not a time, and inventing one would silently move
 * whatever the setting governs.
 */
export function parseWallClockTime(value: string | null | undefined): Date | null {
  const match = value ? WALL_CLOCK_TIME.exec(value.trim()) : null;
  if (!match) {
    return null;
  }
  return new Date(WALL_CLOCK_CARRIER_YEAR, 0, 1, Number(match[1]), Number(match[2]));
}

/**
 * The inverse of {@link parseWallClockTime}: the clock a carrier `Date` holds, as a
 * zero-padded 24-hour `HH:mm`. An absent or invalid value yields `undefined`, which a
 * caller sending a string field coerces to its own empty form.
 */
export function formatWallClockTime(value: Date | null | undefined): string | undefined {
  if (!value || Number.isNaN(value.getTime())) {
    return undefined;
  }
  const { hour, minute } = wallClockOf(value);
  return `${pad2(hour)}:${pad2(minute)}`;
}

function pad2(n: number): string {
  return String(n).padStart(2, '0');
}

/**
 * The picked day normalised to browser-local 12:00, the deliberate midpoint of a day.
 *
 * For a date that travels to a consumer which resolves the calendar day itself, from
 * a zone that may not be the browser's: noon yields the right day for any offset up
 * to ±12 hours, which covers every zone in use. Midnight would not — an hour of
 * eastward offset already tips it into the previous day.
 *
 * This is a browser-local anchor on purpose. Re-anchoring it to a target zone while
 * the picker still draws browser-local days would make what is displayed and what is
 * meant disagree, which is the subtler bug.
 */
export function noonOfLocalDay(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate(), 12, 0, 0, 0);
}

/**
 * The last millisecond of the given calendar day in `timeZone`: the next day's 00:00 in the
 * zone, minus 1 ms.
 *
 * Not the day's own 23:59:59.999. A zone that changes its clock late in the evening
 * (at 23:00 local, as a zone two hours west of UTC on the EU rule does) has no 23:59:59 on
 * its spring day and two of them on its
 * autumn day, and {@link zonedDateToUtc} resolves the second one to its first occurrence,
 * which leaves the repeated hour out of the day. `day + 1` past a month's end is normalised
 * by the `Date.UTC` inside {@link zonedDateToUtc}.
 */
function lastMillisecondOfZonedDay(
  year: number,
  month: number,
  day: number,
  timeZone: string,
): Date {
  return new Date(zonedDateToUtc(year, month, day + 1, 0, 0, 0, timeZone).getTime() - 1);
}

/**
 * The offset in milliseconds: how many ms the target zone's wall-clock is AHEAD of
 * UTC at the given instant. Europe/Copenhagen in winter is +3_600_000 (one hour).
 * America/Halifax in winter is -14_400_000 (negative four hours).
 */
function zonedOffsetMs(instant: Date, timeZone: string): number {
  const parts = getZonedDateParts(instant, timeZone);
  const wallClockAsUtc = Date.UTC(
    parts.year,
    parts.month,
    parts.day,
    parts.hour,
    parts.minute,
    parts.second,
  );
  return wallClockAsUtc - instant.getTime();
}
