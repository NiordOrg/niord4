import { describe, expect, it } from 'vitest';

import {
  endOfZonedDayMs,
  formatInZone,
  getZonedDateParts,
  nextCalendarDay,
  startOfZonedDayMs,
} from './zoned-date';

/**
 * The public site's zone helpers over every day of 2026 to 2028 in the three zones the deployments use,
 * and the wall-clock table the backend's date formatter pins
 * (`be/niord/src/test/java/org/niord/core/util/NavWarnDateFormatterZoneGridTest.java`, `SHARED_WALL_CLOCKS`;
 * the administration app pins it in `date-interval-wall-clock.spec.ts`). Change all three together.
 */
const ZONES = ['UTC', 'Europe/Copenhagen', 'America/Nuuk'];

const SHARED_WALL_CLOCKS: readonly [string, string, string][] = [
  ['2026-12-31T22:59:00Z', 'Europe/Copenhagen', '2026-12-31 23:59'],
  ['2026-12-31T23:00:00Z', 'Europe/Copenhagen', '2027-01-01 00:00'],
  ['2026-12-31T23:00:00Z', 'UTC', '2026-12-31 23:00'],
  ['2027-01-01T01:30:00Z', 'America/Nuuk', '2026-12-31 23:30'],
  ['2027-01-01T02:00:00Z', 'America/Nuuk', '2027-01-01 00:00'],
  ['2026-03-29T00:59:00Z', 'Europe/Copenhagen', '2026-03-29 01:59'],
  ['2026-03-29T01:00:00Z', 'Europe/Copenhagen', '2026-03-29 03:00'],
  ['2026-03-29T00:59:00Z', 'America/Nuuk', '2026-03-28 22:59'],
  ['2026-03-29T01:00:00Z', 'America/Nuuk', '2026-03-29 00:00'],
  ['2026-10-25T00:30:00Z', 'Europe/Copenhagen', '2026-10-25 02:30'],
  ['2026-10-25T01:30:00Z', 'Europe/Copenhagen', '2026-10-25 02:30'],
  ['2026-10-25T00:30:00Z', 'America/Nuuk', '2026-10-24 23:30'],
  ['2026-10-25T01:30:00Z', 'America/Nuuk', '2026-10-24 23:30'],
  ['2028-02-29T22:59:00Z', 'Europe/Copenhagen', '2028-02-29 23:59'],
  ['2028-02-29T23:00:00Z', 'Europe/Copenhagen', '2028-03-01 00:00'],
  ['2026-04-30T22:00:00Z', 'Europe/Copenhagen', '2026-05-01 00:00'],
];

const NO_WORDS = { monthsWide: [], monthsAbbreviated: [], daysWide: [], daysShort: [] };

function days(): string[] {
  const result: string[] = [];
  for (let d: string | undefined = '2026-01-01'; d && d < '2029-01-01'; d = nextCalendarDay(d)) {
    result.push(d);
  }
  return result;
}

describe('zoned-date over every day of 2026 to 2028', () => {
  it('starts every day at the zone’s first instant of that day', () => {
    const failures: string[] = [];
    for (const zone of ZONES) {
      for (const day of days()) {
        const start = startOfZonedDayMs(day, zone)!;
        const parts = getZonedDateParts(new Date(start), zone);
        const before = getZonedDateParts(new Date(start - 1), zone);
        const ymd = `${parts.year}-${String(parts.month).padStart(2, '0')}-${String(parts.day).padStart(2, '0')}`;
        const beforeYmd = `${before.year}-${String(before.month).padStart(2, '0')}-${String(before.day).padStart(2, '0')}`;
        if (ymd !== day || beforeYmd === day)
          failures.push(`${zone} ${day}: starts at ${new Date(start).toISOString()}`);
      }
    }
    expect(days().length).toBe(1096);
    expect(failures).toEqual([]);
  });

  it('ends every day one millisecond before the next one starts, on the same day', () => {
    const failures: string[] = [];
    for (const zone of ZONES) {
      for (const day of days()) {
        const end = endOfZonedDayMs(day, zone)!;
        const next = startOfZonedDayMs(nextCalendarDay(day)!, zone)!;
        const parts = getZonedDateParts(new Date(end), zone);
        const ymd = `${parts.year}-${String(parts.month).padStart(2, '0')}-${String(parts.day).padStart(2, '0')}`;
        if (end + 1 !== next || ymd !== day)
          failures.push(`${zone} ${day}: ends at ${new Date(end).toISOString()}`);
      }
    }
    expect(failures).toEqual([]);
  });

  it('prints the wall clock the backend prints for every shared instant', () => {
    for (const [instant, zone, wallClock] of SHARED_WALL_CLOCKS) {
      expect(
        formatInZone(new Date(instant), 'yyyy-MM-dd HH:mm', zone, NO_WORDS),
        `${instant} ${zone}`,
      ).toBe(wallClock);
    }
  });
});
