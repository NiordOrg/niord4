/**
 * Dates in the deployment's configured IANA zone, never in the browser's. Pure functions, no
 * DI, no date library: everything is built on `Intl.DateTimeFormat.formatToParts`, which is
 * IANA-correct in every supported browser.
 *
 * It is a copy of the administration application's zone helpers, reduced to what an anonymous,
 * read-only site needs. The copy is deliberate: the public site imports no administration code,
 * which ESLint fails at the keyboard and the separation check (`npm run separation:info`) fails
 * in every build's module graph.
 */
import tokens from './date-pattern-tokens.json';

/** The month and weekday words of one language, from the four DATE_* translation keys. */
export interface DateNames {
  /** 12 entries, January first. */
  readonly monthsWide: readonly string[];
  /** 12 entries, January first. */
  readonly monthsAbbreviated: readonly string[];
  /** 7 entries, Sunday first — the order `Date.getUTCDay()` numbers them in. */
  readonly daysWide: readonly string[];
  /** 7 entries, Sunday first. */
  readonly daysShort: readonly string[];
}

export interface ZonedDateParts {
  readonly year: number;
  /** 1-based, as a calendar reads it. */
  readonly month: number;
  readonly day: number;
  readonly hour: number;
  readonly minute: number;
  readonly second: number;
  /** 0 = Sunday. */
  readonly weekday: number;
}

const PARTS_FORMATTER_CACHE = new Map<string, Intl.DateTimeFormat>();

// en-US with an explicit `timeZone` is a pure decomposition: the locale decides only how the
// numbers are spelled, and every field asked for here is numeric or a fixed English weekday
// abbreviation. The zone is what carries meaning, and it is never omitted.
function partsFormatter(timeZone: string): Intl.DateTimeFormat {
  let formatter = PARTS_FORMATTER_CACHE.get(timeZone);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat('en-US', {
      timeZone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      weekday: 'short',
      hour12: false,
    });
    PARTS_FORMATTER_CACHE.set(timeZone, formatter);
  }
  return formatter;
}

const WEEKDAY_INDEX: Readonly<Record<string, number>> = {
  Sun: 0,
  Mon: 1,
  Tue: 2,
  Wed: 3,
  Thu: 4,
  Fri: 5,
  Sat: 6,
};

/** The wall clock `instant` reads at in `timeZone`. */
export function getZonedDateParts(instant: Date, timeZone: string): ZonedDateParts {
  const fields: Record<string, string> = {};
  for (const part of partsFormatter(timeZone).formatToParts(instant)) {
    if (part.type !== 'literal') fields[part.type] = part.value;
  }
  return {
    year: Number(fields['year']),
    month: Number(fields['month']),
    day: Number(fields['day']),
    // Some ICU builds emit '24' for midnight under hour12:false; normalise to 0.
    hour: Number(fields['hour']) % 24,
    minute: Number(fields['minute']),
    second: Number(fields['second']),
    weekday: WEEKDAY_INDEX[fields['weekday']] ?? 0,
  };
}

/**
 * How many milliseconds `timeZone`'s wall clock is ahead of UTC at `instantMs`.
 * Europe/Copenhagen is +3_600_000 in winter, America/Halifax is -14_400_000.
 */
function zoneOffsetMs(instantMs: number, timeZone: string): number {
  const parts = getZonedDateParts(new Date(instantMs), timeZone);
  const wallClockAsUtc = Date.UTC(
    parts.year,
    parts.month - 1,
    parts.day,
    parts.hour,
    parts.minute,
    parts.second,
  );
  return wallClockAsUtc - instantMs;
}

const DAY_MS = 86_400_000;

/**
 * The instant at which the given wall clock occurs IN `timeZone`, as epoch milliseconds.
 *
 * A zone changes its offset at most once in two days, so the wall clock can only be read with
 * the offset the zone has a day before it or the one it has a day after it. Each gives a
 * candidate instant, and a candidate holds when the zone has that very offset at that instant.
 * Away from a clock change the two offsets are the same, and so is the answer.
 *
 * Near a clock change the rule is the one Java's `ZonedDateTime.of` applies, so a wall clock
 * resolves here to the instant the backend resolves it to: where exactly one candidate holds,
 * it is the answer; otherwise the offset in force before the change is kept. A clock in a
 * spring-forward gap does not exist — 02:30 on the day Copenhagen's 02:00 becomes 03:00, 23:30
 * in a zone whose 23:00 becomes 00:00, as UTC−2 does on the EU rule — and resolves to the
 * instant the clock reads an hour later, 03:30 and 00:30. A clock in a fall-back hour exists
 * twice and resolves to its first occurrence, the one before the clocks go back. This
 * application's own callers resolve midnight, and midnight falls in no clock change of a zone on
 * the North American rule or on the EU rule, save at UTC−1, where the EU rule skips the hour from
 * 00:00 in spring and repeats it in autumn. Both cases are defined so that such a zone, or a
 * hand-written pattern, cannot produce a silent surprise.
 */
export function zonedWallClockToInstantMs(
  year: number,
  /** 1-based. */
  month: number,
  day: number,
  hour: number,
  minute: number,
  second: number,
  timeZone: string,
): number {
  const wallClock = Date.UTC(year, month - 1, day, hour, minute, second);
  const offsetBefore = zoneOffsetMs(wallClock - DAY_MS, timeZone);
  const offsetAfter = zoneOffsetMs(wallClock + DAY_MS, timeZone);
  const withOffsetBefore = wallClock - offsetBefore;
  if (offsetBefore === offsetAfter) return withOffsetBefore;

  const withOffsetAfter = wallClock - offsetAfter;
  const beforeHolds = zoneOffsetMs(withOffsetBefore, timeZone) === offsetBefore;
  const afterHolds = zoneOffsetMs(withOffsetAfter, timeZone) === offsetAfter;
  return afterHolds && !beforeHolds ? withOffsetAfter : withOffsetBefore;
}

/** The shape of a `YYYY-MM-DD`; matching it is necessary but not sufficient — see below. */
const CALENDAR_DAY = /^(\d{4})-(\d{2})-(\d{2})$/;

/** A calendar day as its three numbers, 1-based month. */
interface CalendarDay {
  readonly year: number;
  readonly month: number;
  readonly day: number;
}

/**
 * The three numbers of a `YYYY-MM-DD`, or `undefined` when the value is not a day that exists.
 *
 * The shape test alone is not enough: `Date.UTC` rolls an out-of-range field over silently, so
 * '2024-13-40' would become 9 February 2025 and a filter bound would land four months from
 * where it was typed. Building the day and reading it back is what rejects it — a day that
 * exists is the only one that survives the round trip unchanged.
 */
function calendarDay(value: string): CalendarDay | undefined {
  const match = CALENDAR_DAY.exec(value);
  if (!match) return undefined;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const utc = new Date(Date.UTC(year, month - 1, day));
  if (
    utc.getUTCFullYear() !== year ||
    utc.getUTCMonth() !== month - 1 ||
    utc.getUTCDate() !== day
  ) {
    return undefined;
  }
  return { year, month, day };
}

/**
 * Midnight of a `YYYY-MM-DD` calendar day IN `timeZone`, as epoch milliseconds — the lower
 * bound of a day-range filter. A malformed value yields `undefined` rather than a guess: a
 * half-typed date is not a date, and inventing one would silently move the filter.
 */
export function startOfZonedDayMs(day: string, timeZone: string): number | undefined {
  const parsed = calendarDay(day);
  if (!parsed) return undefined;
  return zonedWallClockToInstantMs(parsed.year, parsed.month, parsed.day, 0, 0, 0, timeZone);
}

/**
 * The calendar day after `day`, as `YYYY-MM-DD`.
 *
 * Pure UTC arithmetic: `Date.UTC` builds the day at UTC midnight, so no browser zone can shift
 * it, and adding 86 400 000 ms to a UTC midnight always lands on the next UTC midnight — UTC has
 * no daylight saving. The result is re-resolved in the target zone by the caller, which is where
 * daylight saving is handled.
 */
export function nextCalendarDay(day: string): string | undefined {
  const parsed = calendarDay(day);
  if (!parsed) return undefined;
  const utc = Date.UTC(parsed.year, parsed.month - 1, parsed.day);
  return new Date(utc + 86_400_000).toISOString().slice(0, 10);
}

/**
 * The last millisecond of a `YYYY-MM-DD` calendar day IN `timeZone` — the inclusive upper bound
 * of a day-range filter.
 *
 * It is the start of the NEXT day minus one, not "start + 24 h - 1": on a spring-forward day the
 * zone's day is 23 hours long and on a fall-back day it is 25, and a fixed 24 would include an
 * hour of the neighbouring day or drop an hour of this one.
 */
export function endOfZonedDayMs(day: string, timeZone: string): number | undefined {
  const next = nextCalendarDay(day);
  if (next === undefined) return undefined;
  const start = startOfZonedDayMs(next, timeZone);
  return start === undefined ? undefined : start - 1;
}

// Single-quoted runs are literals ('' inside them is one apostrophe, the CLDR convention);
// everything else is a token. A character that matches no token is copied.
const PATTERN_TOKEN = /'([^']*)'|(y{4}|y{2}|M{1,4}|d{1,2}|E{3,4}|H{1,2}|m{1,2}|s{1,2})/g;

const pad2 = (value: number): string => String(value).padStart(2, '0');

/**
 * Render `instant` in `timeZone` using a translation-driven pattern.
 *
 * The numeric fields come from `Intl` with an explicit zone; the month and weekday WORDS come
 * from `names`, which the caller reads out of the four DATE_* translation keys. `Intl` is not
 * trusted for the words because browsers ship reduced locale data and fall back to English
 * without an error — which would render, say, Kalaallisut month names in English.
 *
 * The supported token set is deliberately small: an unknown token is copied through verbatim,
 * so a mistyped pattern shows itself instead of throwing.
 */
export function formatInZone(
  instant: Date,
  pattern: string,
  timeZone: string,
  names: DateNames,
): string {
  const parts = getZonedDateParts(instant, timeZone);
  return pattern.replace(PATTERN_TOKEN, (match, quoted: string | undefined, token: string) => {
    if (quoted !== undefined) return quoted === '' ? "'" : quoted;
    switch (token) {
      case 'yyyy':
        return String(parts.year);
      case 'yy':
        return pad2(parts.year % 100);
      case 'MMMM':
        return names.monthsWide[parts.month - 1] ?? String(parts.month);
      case 'MMM':
        return names.monthsAbbreviated[parts.month - 1] ?? String(parts.month);
      case 'MM':
        return pad2(parts.month);
      case 'M':
        return String(parts.month);
      case 'dd':
        return pad2(parts.day);
      case 'd':
        return String(parts.day);
      case 'EEEE':
        return names.daysWide[parts.weekday] ?? '';
      case 'EEE':
        return names.daysShort[parts.weekday] ?? '';
      case 'HH':
        return pad2(parts.hour);
      case 'H':
        return String(parts.hour);
      case 'mm':
        return pad2(parts.minute);
      case 'm':
        return String(parts.minute);
      case 'ss':
        return pad2(parts.second);
      case 's':
        return String(parts.second);
      default:
        return match;
    }
  });
}

/**
 * Every token `formatInZone` understands. The list is data, `date-pattern-tokens.json`, because
 * the language catalog (`country-kit/catalog.mjs`) reads the same file: the pack check then holds
 * every pack's date patterns to the renderer of the version it builds against.
 */
export const SUPPORTED_PATTERN_TOKENS: readonly string[] = tokens;
