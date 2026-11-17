import { describe, expect, it } from 'vitest';

import { forceAllDayBounds } from './event-date-bounds.util';

/**
 * Properties of the all-day event dates, over generated instants in the domains' zones from a fixed
 * seed (error hunt, phase 2): an all-day interval runs from the first to the last millisecond of the
 * day each end falls on in the domain's zone, on every day of the year the clocks change on
 * included; it holds the instants it was made from; and making it all-day again changes nothing.
 */

/** A small seeded generator (mulberry32): the same seed gives the same inputs on every run. */
function seeded(seed: number) {
  let state = seed >>> 0;
  const next = (): number => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  const int = (min: number, max: number): number => min + Math.floor(next() * (max - min + 1));
  const pick = <T>(items: readonly T[]): T => items[Math.floor(next() * items.length)];
  return { next, int, pick };
}

const ZONES = [
  'Europe/Copenhagen',
  'America/Halifax',
  'Atlantic/Faroe',
  'UTC',
  'America/St_Johns',
  'Pacific/Auckland',
];

const FORMATS = new Map(
  ZONES.map((timeZone) => [
    timeZone,
    new Intl.DateTimeFormat('en-CA', {
      timeZone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }),
  ]),
);

/** The calendar day of an instant in a zone, as `YYYY-MM-DD`. */
function dayIn(epoch: number, timeZone: string): string {
  return FORMATS.get(timeZone)?.format(epoch) ?? '';
}

/** Days around the 2026 clock changes of the zones above (UTC midnight of the day before). */
const CLOCK_CHANGES = [
  Date.UTC(2026, 2, 7),
  Date.UTC(2026, 2, 27),
  Date.UTC(2026, 2, 28),
  Date.UTC(2026, 3, 4),
  Date.UTC(2026, 8, 26),
  Date.UTC(2026, 9, 23),
  Date.UTC(2026, 9, 24),
  Date.UTC(2026, 9, 31),
];

describe('forceAllDayBounds, properties over generated instants', () => {
  it('spans the whole zone day of each end, holds the instants it came from, and is stable', () => {
    const rng = seeded(20261115);
    const start = Date.UTC(2025, 0, 1);
    const end = Date.UTC(2029, 0, 1);
    for (let i = 0; i < 6000; i++) {
      const timeZone = rng.pick(ZONES);
      const from =
        rng.next() < 0.4
          ? rng.pick(CLOCK_CHANGES) + rng.int(0, 3 * 86_400_000)
          : rng.int(start, end);
      const to = from + rng.int(0, 40) * 86_400_000 + rng.int(0, 86_399_999);
      const interval = {
        fromDate: new Date(from).toISOString(),
        toDate: new Date(to).toISOString(),
      };
      const allDay = forceAllDayBounds(interval, timeZone);
      const f = Date.parse(allDay.fromDate ?? '');
      const t = Date.parse(allDay.toDate ?? '');
      const label = `${timeZone} ${interval.fromDate} – ${interval.toDate} → ${allDay.fromDate} – ${allDay.toDate}`;

      expect(f, label).toBeLessThanOrEqual(from);
      expect(t, label).toBeGreaterThanOrEqual(to);
      expect(dayIn(f, timeZone), label).toBe(dayIn(from, timeZone));
      expect(dayIn(f - 1, timeZone), label).not.toBe(dayIn(from, timeZone));
      expect(dayIn(t, timeZone), label).toBe(dayIn(to, timeZone));
      expect(dayIn(t + 1, timeZone), label).not.toBe(dayIn(to, timeZone));
      expect(forceAllDayBounds(allDay, timeZone), label).toEqual(allDay);
    }
  }, 60_000);

  it('leaves a missing or unreadable end as it was', () => {
    for (const timeZone of ZONES) {
      expect(forceAllDayBounds({ fromDate: undefined, toDate: 'not a date' }, timeZone)).toEqual({
        fromDate: undefined,
        toDate: 'not a date',
      });
    }
  });
});
