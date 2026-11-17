import { describe, expect, it } from 'vitest';

import {
  dayInZoneToLocalDate,
  endOfDayInZone,
  getZonedDateParts,
  noonOfLocalDay,
  pickedDayToZone,
  shiftDaysInZone,
  startOfDayInZone,
  formatWallClockTime,
  parseWallClockTime,
  wallClockOf,
  wallClockToInstant,
  zonedDateToUtc,
  zonedWallClockAsLocalDate,
} from './zoned-date';

// Offset-specific assertions use timezones whose historical rules are stable enough
// for tests to remain valid across IANA tzdb updates:
//   - Europe/Copenhagen — EU DST rules (last Sun Mar to last Sun Oct), unchanged for
//     decades. Winter UTC+1, summer UTC+2.
//   - Asia/Tokyo — UTC+9 year-round, no DST, no historical changes within test scope.
//   - America/New_York — US DST rules (since 2007), stable.
//   - America/Halifax — west of UTC (UTC-4, UTC-3 in summer), North American DST rules
//     (since 2007), stable.
//
// America/Halifax is also the zone of the **contract-property** tests (round-trip +
// wall-clock invariants), which state what must hold in any zone west of UTC rather
// than an offset.

describe('zoned-date', () => {
  describe('getZonedDateParts', () => {
    it('decomposes a UTC instant in Europe/Copenhagen winter (UTC+1)', () => {
      const parts = getZonedDateParts(new Date('2026-01-15T10:30:00Z'), 'Europe/Copenhagen');
      expect(parts).toEqual({ year: 2026, month: 0, day: 15, hour: 11, minute: 30, second: 0 });
    });

    it('decomposes a UTC instant in Europe/Copenhagen summer (UTC+2)', () => {
      const parts = getZonedDateParts(new Date('2026-07-15T10:30:00Z'), 'Europe/Copenhagen');
      expect(parts).toEqual({ year: 2026, month: 6, day: 15, hour: 12, minute: 30, second: 0 });
    });

    it('crosses forward into the next day for a zone well ahead of UTC', () => {
      // 2026-01-15T23:00:00Z → 2026-01-16 08:00 Asia/Tokyo (UTC+9)
      const parts = getZonedDateParts(new Date('2026-01-15T23:00:00Z'), 'Asia/Tokyo');
      expect(parts).toEqual({ year: 2026, month: 0, day: 16, hour: 8, minute: 0, second: 0 });
    });

    it('crosses backward into the previous day for a zone well behind UTC', () => {
      // 2026-01-15T02:00:00Z → 2026-01-14 21:00 America/New_York winter (UTC-5)
      const parts = getZonedDateParts(new Date('2026-01-15T02:00:00Z'), 'America/New_York');
      expect(parts).toEqual({ year: 2026, month: 0, day: 14, hour: 21, minute: 0, second: 0 });
    });
  });

  describe('zonedDateToUtc', () => {
    it('produces correct UTC for Copenhagen midnight in winter (UTC+1)', () => {
      // 2026-01-15 00:00 Copenhagen → 2026-01-14T23:00:00Z
      const utc = zonedDateToUtc(2026, 0, 15, 0, 0, 0, 'Europe/Copenhagen');
      expect(utc.toISOString()).toBe('2026-01-14T23:00:00.000Z');
    });

    it('produces correct UTC for Copenhagen summer offset (UTC+2)', () => {
      // 2026-07-15 12:00 Copenhagen (summer DST) → 2026-07-15T10:00:00Z
      const utc = zonedDateToUtc(2026, 6, 15, 12, 0, 0, 'Europe/Copenhagen');
      expect(utc.toISOString()).toBe('2026-07-15T10:00:00.000Z');
    });

    it('produces correct UTC for Tokyo midnight (UTC+9, no DST)', () => {
      // 2026-01-15 00:00 Tokyo → 2026-01-14T15:00:00Z
      const utc = zonedDateToUtc(2026, 0, 15, 0, 0, 0, 'Asia/Tokyo');
      expect(utc.toISOString()).toBe('2026-01-14T15:00:00.000Z');
    });

    it('produces correct UTC for New York midnight in winter (UTC-5)', () => {
      // 2026-01-15 00:00 New York → 2026-01-15T05:00:00Z
      const utc = zonedDateToUtc(2026, 0, 15, 0, 0, 0, 'America/New_York');
      expect(utc.toISOString()).toBe('2026-01-15T05:00:00.000Z');
    });

    it('produces correct UTC for New York summer offset (UTC-4)', () => {
      // 2026-07-15 12:00 New York (EDT, UTC-4) → 2026-07-15T16:00:00Z
      const utc = zonedDateToUtc(2026, 6, 15, 12, 0, 0, 'America/New_York');
      expect(utc.toISOString()).toBe('2026-07-15T16:00:00.000Z');
    });

    it('handles DST spring-forward post-transition wall-clock (Copenhagen)', () => {
      // 2026 spring-forward in Europe/Copenhagen: last Sunday of March → 2026-03-29.
      // Clocks jump from 02:00 (UTC+1) to 03:00 (UTC+2). 03:30 wall-clock is post-DST.
      // 03:30 Copenhagen (UTC+2) → 01:30 UTC.
      const utc = zonedDateToUtc(2026, 2, 29, 3, 30, 0, 'Europe/Copenhagen');
      expect(utc.toISOString()).toBe('2026-03-29T01:30:00.000Z');
    });

    it('handles DST fall-back ambiguous wall-clock by picking the post-transition instant', () => {
      // 2026 fall-back in Europe/Copenhagen: last Sunday of October → 2026-10-25.
      // Clocks fall from 03:00 (UTC+2) to 02:00 (UTC+1). 02:30 wall-clock occurs twice.
      // Pre-transition: 02:30 UTC+2 = 00:30 UTC. Post-transition: 02:30 UTC+1 = 01:30 UTC.
      // Convention: pick post-transition (matches Java ZonedDateTime, luxon, date-fns-tz).
      const utc = zonedDateToUtc(2026, 9, 25, 2, 30, 0, 'Europe/Copenhagen');
      expect(utc.toISOString()).toBe('2026-10-25T01:30:00.000Z');
    });
  });

  describe('startOfDayInZone / endOfDayInZone — contract properties', () => {
    it('startOfDayInZone returns an instant whose wall-clock in the zone is 00:00:00', () => {
      const input = new Date('2026-01-15T15:30:00Z');
      const start = startOfDayInZone(input, 'America/Halifax');
      const parts = getZonedDateParts(start, 'America/Halifax');
      expect(parts.hour).toBe(0);
      expect(parts.minute).toBe(0);
      expect(parts.second).toBe(0);
    });

    it('endOfDayInZone returns an instant whose wall-clock in the zone is 23:59:59', () => {
      const input = new Date('2026-01-15T15:30:00Z');
      const end = endOfDayInZone(input, 'America/Halifax');
      const parts = getZonedDateParts(end, 'America/Halifax');
      expect(parts.hour).toBe(23);
      expect(parts.minute).toBe(59);
      expect(parts.second).toBe(59);
    });

    it('startOfDayInZone preserves the Y/M/D as seen in the zone', () => {
      const input = new Date('2026-01-15T15:30:00Z');
      const expectedDay = getZonedDateParts(input, 'America/Halifax');
      const start = startOfDayInZone(input, 'America/Halifax');
      const startParts = getZonedDateParts(start, 'America/Halifax');
      expect(startParts.year).toBe(expectedDay.year);
      expect(startParts.month).toBe(expectedDay.month);
      expect(startParts.day).toBe(expectedDay.day);
    });

    it('respects the Y/M/D as seen in the zone, not the browser', () => {
      // 2026-01-15T23:30:00Z is Jan 16 08:30 in Tokyo. startOfDayInZone should anchor
      // to Jan 16 00:00 Tokyo (= Jan 15 15:00 UTC), NOT Jan 15 00:00 Tokyo.
      const input = new Date('2026-01-15T23:30:00Z');
      const start = startOfDayInZone(input, 'Asia/Tokyo');
      expect(start.toISOString()).toBe('2026-01-15T15:00:00.000Z');
    });

    it('endOfDayInZone ends on the last millisecond, not the last whole second', () => {
      const end = endOfDayInZone(new Date('2026-01-15T15:30:00Z'), 'UTC');
      expect(end.toISOString()).toBe('2026-01-15T23:59:59.999Z');
    });
  });

  describe('the end of a day on the days the clocks change', () => {
    // The end of a day is the next day's start minus 1 ms. Nuuk changes its clocks at 23:00 local: its
    // spring day has no 23:00-23:59, and its autumn day has that hour twice, so a day ending at "23:59:59" resolved to the first occurrence left out the repeated hour.
    it.each([
      ['America/Nuuk', '2026-03-28T14:00:00Z', 2026, 2, 28, '2026-03-29T00:59:59.999Z'],
      ['America/Nuuk', '2026-10-24T13:00:00Z', 2026, 9, 24, '2026-10-25T01:59:59.999Z'],
      ['Europe/Copenhagen', '2026-03-29T10:00:00Z', 2026, 2, 29, '2026-03-29T21:59:59.999Z'],
      ['Europe/Copenhagen', '2026-10-25T11:00:00Z', 2026, 9, 25, '2026-10-25T22:59:59.999Z'],
    ])(
      '%s: the day of %s ends at the next day’s start',
      (zone, noon, year, month, day, expected) => {
        expect(endOfDayInZone(new Date(noon), zone).toISOString()).toBe(expected);
        expect(pickedDayToZone(new Date(year, month, day), zone, 'end').toISOString()).toBe(
          expected,
        );
      },
    );

    it('spans the whole 25-hour autumn day in Nuuk', () => {
      const picked = new Date(2026, 9, 24);
      const start = pickedDayToZone(picked, 'America/Nuuk', 'start');
      const end = pickedDayToZone(picked, 'America/Nuuk', 'end');
      expect(end.getTime() - start.getTime()).toBe(25 * 60 * 60 * 1000 - 1);
    });

    it('ends the last day of a month and of a year before the next one starts', () => {
      expect(pickedDayToZone(new Date(2026, 3, 30), 'Europe/Copenhagen', 'end').toISOString()).toBe(
        '2026-04-30T21:59:59.999Z',
      );
      expect(pickedDayToZone(new Date(2026, 11, 31), 'America/Nuuk', 'end').toISOString()).toBe(
        '2027-01-01T01:59:59.999Z',
      );
    });
  });

  describe('pickedDayToZone', () => {
    // `new Date(y, m, d)` is browser-local midnight in every runner zone, so its Y/M/D is
    // fixed regardless of where the suite runs — exactly what a picker hands over.
    const picked = new Date(2026, 4, 14); // 14 May 2026, as drawn on a calendar grid

    it('opens the picked day at 00:00:00.000 in a fixed-offset zone', () => {
      expect(pickedDayToZone(picked, 'UTC', 'start').toISOString()).toBe(
        '2026-05-14T00:00:00.000Z',
      );
    });

    it('closes the picked day at 23:59:59.999 in a fixed-offset zone', () => {
      expect(pickedDayToZone(picked, 'UTC', 'end').toISOString()).toBe('2026-05-14T23:59:59.999Z');
    });

    it('resolves the picked day against a zone well ahead of UTC', () => {
      // 14 May 00:00 Tokyo (UTC+9) → 13 May 15:00 UTC.
      expect(pickedDayToZone(picked, 'Asia/Tokyo', 'start').toISOString()).toBe(
        '2026-05-13T15:00:00.000Z',
      );
    });

    it('keeps the picked calendar day in a zone west of UTC', () => {
      // Contract assertion rather than an offset: whatever the zone's current rules,
      // the bounds must read back as the 14th, which is the day that was clicked.
      const start = getZonedDateParts(
        pickedDayToZone(picked, 'America/Halifax', 'start'),
        'America/Halifax',
      );
      const end = getZonedDateParts(
        pickedDayToZone(picked, 'America/Halifax', 'end'),
        'America/Halifax',
      );
      expect([start.year, start.month, start.day, start.hour, start.minute, start.second]).toEqual([
        2026, 4, 14, 0, 0, 0,
      ]);
      expect([end.year, end.month, end.day, end.hour, end.minute, end.second]).toEqual([
        2026, 4, 14, 23, 59, 59,
      ]);
    });

    it('spans exactly one day between the two edges', () => {
      const start = pickedDayToZone(picked, 'Europe/Copenhagen', 'start');
      const end = pickedDayToZone(picked, 'Europe/Copenhagen', 'end');
      expect(end.getTime() - start.getTime()).toBe(24 * 60 * 60 * 1000 - 1);
    });
  });

  describe('dayInZoneToLocalDate', () => {
    it('carries the zone day forward across the date line', () => {
      // 2026-01-15T23:30:00Z is already Jan 16 in Tokyo.
      const local = dayInZoneToLocalDate(new Date('2026-01-15T23:30:00Z'), 'Asia/Tokyo');
      expect([local.getFullYear(), local.getMonth(), local.getDate()]).toEqual([2026, 0, 16]);
      expect([local.getHours(), local.getMinutes()]).toEqual([0, 0]);
    });

    it('holds the zone day back for a zone behind UTC', () => {
      // 2026-01-15T02:00:00Z is still Jan 14 in New York (UTC-5).
      const local = dayInZoneToLocalDate(new Date('2026-01-15T02:00:00Z'), 'America/New_York');
      expect([local.getFullYear(), local.getMonth(), local.getDate()]).toEqual([2026, 0, 14]);
    });
  });

  describe('shiftDaysInZone', () => {
    it('keeps the wall clock across a spring-forward transition', () => {
      // 2026-03-28 12:00 Copenhagen (UTC+1) = 11:00Z. The clocks go forward overnight,
      // so the same wall clock the next day is UTC+2 — 23 hours later, not 24.
      const before = new Date('2026-03-28T11:00:00Z');
      const after = shiftDaysInZone(before, 1, 'Europe/Copenhagen');
      expect(after.toISOString()).toBe('2026-03-29T10:00:00.000Z');
      expect(after.getTime() - before.getTime()).toBe(23 * 60 * 60 * 1000);
    });

    it('keeps the wall clock across a fall-back transition', () => {
      // 2026-10-24 12:00 Copenhagen (UTC+2) = 10:00Z; the next day is UTC+1 — 25 hours.
      const after = shiftDaysInZone(new Date('2026-10-24T10:00:00Z'), 1, 'Europe/Copenhagen');
      expect(after.toISOString()).toBe('2026-10-25T11:00:00.000Z');
    });

    it('steps backwards across a month boundary', () => {
      // 2026-03-01 00:30 Tokyo → 2026-02-28 00:30 Tokyo.
      const after = shiftDaysInZone(new Date('2026-02-28T15:30:00Z'), -1, 'Asia/Tokyo');
      expect(after.toISOString()).toBe('2026-02-27T15:30:00.000Z');
    });

    it('is a no-op for zero days', () => {
      const instant = new Date('2026-07-15T10:30:45Z');
      expect(shiftDaysInZone(instant, 0, 'Europe/Copenhagen').toISOString()).toBe(
        '2026-07-15T10:30:45.000Z',
      );
    });
  });

  describe('wallClockToInstant', () => {
    const picked = new Date(2026, 2, 5); // 5 March 2026, as drawn on a calendar grid

    it('resolves the typed clock in a fixed-offset zone', () => {
      expect(wallClockToInstant(picked, 12, 0, 0, 'UTC').toISOString()).toBe(
        '2026-03-05T12:00:00.000Z',
      );
    });

    it('resolves the typed clock in a zone ahead of UTC', () => {
      // 5 March 12:00 Tokyo (UTC+9) → 03:00Z.
      expect(wallClockToInstant(picked, 12, 0, 0, 'Asia/Tokyo').toISOString()).toBe(
        '2026-03-05T03:00:00.000Z',
      );
    });

    it('resolves a wall clock on the spring-forward day', () => {
      // 03:30 on 2026-03-29 in Copenhagen is post-transition (UTC+2) → 01:30Z.
      const springForward = new Date(2026, 2, 29);
      expect(wallClockToInstant(springForward, 3, 30, 0, 'Europe/Copenhagen').toISOString()).toBe(
        '2026-03-29T01:30:00.000Z',
      );
    });

    it('ignores the time part of the picked date, using only its calendar day', () => {
      const pickedWithNoise = new Date(2026, 2, 5, 18, 42, 17, 123);
      expect(wallClockToInstant(pickedWithNoise, 9, 15, 0, 'UTC').toISOString()).toBe(
        '2026-03-05T09:15:00.000Z',
      );
    });
  });

  describe('zonedWallClockAsLocalDate', () => {
    it('reads the clock in the target zone, not the runner’s', () => {
      // 2026-03-15T23:30:00Z is 08:30 on the 16th in Tokyo.
      const carrier = zonedWallClockAsLocalDate(new Date('2026-03-15T23:30:00Z'), 'Asia/Tokyo');
      expect([carrier.getHours(), carrier.getMinutes()]).toEqual([8, 30]);
    });

    it('reads the clock in a zone west of UTC', () => {
      // 2026-03-15T01:00:00Z is 22:00 the previous evening in Halifax (UTC-3 since 8 March).
      const carrier = zonedWallClockAsLocalDate(
        new Date('2026-03-15T01:00:00Z'),
        'America/Halifax',
      );
      expect([carrier.getHours(), carrier.getMinutes()]).toEqual([22, 0]);
    });

    it('reads a half-hour-offset zone, which no whole-hour runner can fake', () => {
      // Asia/Kolkata is UTC+5:30 year-round — the minutes discriminate on their own.
      const carrier = zonedWallClockAsLocalDate(new Date('2026-03-15T12:00:00Z'), 'Asia/Kolkata');
      expect([carrier.getHours(), carrier.getMinutes()]).toEqual([17, 30]);
    });

    it('parks the clock on the fixed carrier day, discarding the instant’s own date', () => {
      const carrier = zonedWallClockAsLocalDate(new Date('2026-03-15T12:00:00Z'), 'UTC');
      expect([carrier.getFullYear(), carrier.getMonth(), carrier.getDate()]).toEqual([2000, 0, 1]);
      expect([carrier.getSeconds(), carrier.getMilliseconds()]).toEqual([0, 0]);
    });

    it('survives a clock that falls in the browser’s own spring-forward gap', () => {
      // 02:30 does not exist on a European spring-forward morning. On the carrier day it
      // is an ordinary time, so the clock the zone reads is the clock that comes back —
      // whatever gap the runner's own calendar has that year.
      const carrier = zonedWallClockAsLocalDate(new Date('2026-03-29T02:30:00Z'), 'UTC');
      expect([carrier.getHours(), carrier.getMinutes()]).toEqual([2, 30]);
    });

    it('round-trips a wall clock through wallClockToInstant', () => {
      const zone = 'America/Halifax';
      const day = new Date(2026, 6, 15); // a picker day, browser-local
      const instant = wallClockToInstant(day, 16, 45, 0, zone);
      const carrier = zonedWallClockAsLocalDate(instant, zone);
      expect([carrier.getHours(), carrier.getMinutes()]).toEqual([16, 45]);
    });
  });

  describe('the bare HH:mm carrier', () => {
    it('round-trips a stored time through the carrier and back', () => {
      expect(formatWallClockTime(parseWallClockTime('09:05'))).toBe('09:05');
      expect(formatWallClockTime(parseWallClockTime('23:59'))).toBe('23:59');
      expect(formatWallClockTime(parseWallClockTime('0:00'))).toBe('00:00');
    });

    it('reads the typed clock straight off the carrier, unconverted', () => {
      expect(wallClockOf(parseWallClockTime('14:30')!)).toEqual({ hour: 14, minute: 30 });
    });

    it('refuses anything that is not a whole valid time of day', () => {
      for (const bad of ['', '  ', '24:00', '12:60', '9', '9:5', 'noon', '12:30:45', undefined]) {
        expect(parseWallClockTime(bad)).toBeNull();
      }
    });

    it('tolerates surrounding whitespace', () => {
      expect(formatWallClockTime(parseWallClockTime('  07:45  '))).toBe('07:45');
    });

    it('yields undefined for an absent or invalid carrier', () => {
      expect(formatWallClockTime(null)).toBeUndefined();
      expect(formatWallClockTime(undefined)).toBeUndefined();
      expect(formatWallClockTime(new Date('nonsense'))).toBeUndefined();
    });
  });

  describe('noonOfLocalDay', () => {
    it('normalises to browser-local noon, keeping the calendar day', () => {
      const noon = noonOfLocalDay(new Date(2026, 4, 14, 23, 45, 30, 500));
      expect([noon.getFullYear(), noon.getMonth(), noon.getDate()]).toEqual([2026, 4, 14]);
      expect([
        noon.getHours(),
        noon.getMinutes(),
        noon.getSeconds(),
        noon.getMilliseconds(),
      ]).toEqual([12, 0, 0, 0]);
    });

    it('sits 12 hours clear of both ends of the local day — the slack it exists for', () => {
      const dayStart = new Date(2026, 4, 14).getTime(); // no DST transition in mid-May anywhere
      const dayEnd = new Date(2026, 4, 15).getTime();
      const noon = noonOfLocalDay(new Date(2026, 4, 14, 0, 5)).getTime();
      expect(noon - dayStart).toBe(12 * 60 * 60 * 1000);
      expect(dayEnd - noon).toBe(12 * 60 * 60 * 1000);
    });
  });

  describe('round-trip — idempotence of parts ↔ utc', () => {
    it.each([
      ['Europe/Copenhagen', 2026, 0, 15, 10, 30, 45], // Winter
      ['Europe/Copenhagen', 2026, 6, 15, 10, 30, 45], // Summer DST
      ['Asia/Tokyo', 2026, 0, 15, 10, 30, 45],
      ['Asia/Tokyo', 2026, 6, 15, 10, 30, 45],
      ['America/New_York', 2026, 0, 15, 10, 30, 45], // EST
      ['America/New_York', 2026, 6, 15, 10, 30, 45], // EDT
      ['America/Halifax', 2026, 0, 15, 10, 30, 45], // AST
      ['America/Halifax', 2026, 6, 15, 10, 30, 45], // ADT
      ['UTC', 2026, 0, 15, 10, 30, 45],
    ])(
      'parts → utc → parts is idempotent for %s on %d-%d-%d %d:%d:%d',
      (tz, year, month, day, hour, minute, second) => {
        const utc = zonedDateToUtc(year, month, day, hour, minute, second, tz);
        const roundTrip = getZonedDateParts(utc, tz);
        expect(roundTrip).toEqual({ year, month, day, hour, minute, second });
      },
    );
  });
});
