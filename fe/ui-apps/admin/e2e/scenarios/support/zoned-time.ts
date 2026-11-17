/*
 * Wall clocks in a domain's time zone. The editor's date and time controls work in the session
 * domain's zone, and the backend stores instants, so a scenario that picks "10 December, 8:30"
 * on the screen expects the instant of 8:30 in that zone, whatever the browser's own zone is
 * (the suite runs the browser in America/St_Johns on purpose).
 */

/** A day and a time of day, as a clock in some zone shows them. Months count from 1. */
export interface WallClock {
  readonly year: number;
  readonly month: number;
  readonly day: number;
  readonly hour: number;
  readonly minute: number;
}

const MONTHS = ['jan', 'feb', 'mar', 'apr', 'may', 'jun', 'jul', 'aug', 'sep', 'oct', 'nov', 'dec'];

/** The wall clock of `instant` in `zone`. */
export function wallClockIn(instant: Date | string | number, zone: string): WallClock {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: zone,
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
    minute: 'numeric',
    hourCycle: 'h23',
  }).formatToParts(new Date(instant));
  const part = (type: string): number => Number(parts.find((p) => p.type === type)?.value);
  return {
    year: part('year'),
    month: part('month'),
    day: part('day'),
    hour: part('hour') % 24,
    minute: part('minute'),
  };
}

/** The instant at which `zone`'s clock shows `clock` (seconds 0). */
export function instantAt(clock: WallClock, zone: string): Date {
  const wanted = Date.UTC(clock.year, clock.month - 1, clock.day, clock.hour, clock.minute);
  let guess = wanted;
  // Two rounds settle the zone's offset, also across a change of summer time.
  for (let round = 0; round < 2; round++) {
    const shown = wallClockIn(guess, zone);
    const shownUtc = Date.UTC(shown.year, shown.month - 1, shown.day, shown.hour, shown.minute);
    guess += wanted - shownUtc;
  }
  return new Date(guess);
}

/** The day `days` after `clock`'s day, at the same time of day. */
export function addDays(clock: WallClock, days: number): WallClock {
  const date = new Date(Date.UTC(clock.year, clock.month - 1, clock.day + days));
  return {
    ...clock,
    year: date.getUTCFullYear(),
    month: date.getUTCMonth() + 1,
    day: date.getUTCDate(),
  };
}

/**
 * The day a date control shows, in either English order ("10 Dec 2026", "Dec 10, 2026"), or
 * undefined when the text holds none.
 */
export function shownDay(text: string): Pick<WallClock, 'year' | 'month' | 'day'> | undefined {
  const dayFirst = /(\d{1,2})\s+([A-Za-z]{3})[A-Za-z]*\.?,?\s+(\d{4})/.exec(text);
  const monthFirst = /([A-Za-z]{3})[A-Za-z]*\.?\s+(\d{1,2}),?\s+(\d{4})/.exec(text);
  const [day, month, year] = dayFirst
    ? [dayFirst[1], dayFirst[2], dayFirst[3]]
    : monthFirst
      ? [monthFirst[2], monthFirst[1], monthFirst[3]]
      : [];
  const monthIndex = MONTHS.indexOf((month ?? '').toLowerCase());
  if (!day || monthIndex < 0) return undefined;
  return { year: Number(year), month: monthIndex + 1, day: Number(day) };
}

/** The time a time control shows ("8:30 AM", "16.15", "16:15"), or undefined. */
export function shownTime(text: string): Pick<WallClock, 'hour' | 'minute'> | undefined {
  const match = /(\d{1,2})[:.](\d{2})\s*([AaPp][Mm])?/.exec(text);
  if (!match) return undefined;
  let hour = Number(match[1]);
  const meridiem = match[3]?.toLowerCase();
  if (meridiem === 'am' && hour === 12) hour = 0;
  if (meridiem === 'pm' && hour < 12) hour += 12;
  return { hour, minute: Number(match[2]) };
}

/** The English month name of a wall clock, as the calendar's header and day names spell it. */
export function monthName(clock: Pick<WallClock, 'month'>): string {
  return new Intl.DateTimeFormat('en-GB', { month: 'long', timeZone: 'UTC' }).format(
    new Date(Date.UTC(2000, clock.month - 1, 1)),
  );
}
