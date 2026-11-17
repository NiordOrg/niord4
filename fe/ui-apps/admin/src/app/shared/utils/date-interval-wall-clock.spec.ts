import { describe, expect, it } from 'vitest';

import { formatDate, formatTime } from '../../core/main-shell/services/date-time-format';
import { DATE_INTERVAL_KEYS, translateDateInterval } from './translate-date-interval';
import { getZonedDateParts } from './zoned-date';

/**
 * The wall clock the administration app prints for an instant in a domain's zone, at the boundaries: New
 * Year, both clock changes of 2026 in Copenhagen and in Nuuk (whose changes fall at 23:00 and 00:00
 * local), a leap day and the turn of a month. The rows are the backend's
 * `be/niord/src/test/java/org/niord/core/util/NavWarnDateFormatterZoneGridTest.java` `SHARED_WALL_CLOCKS`,
 * which the backend's date formatter pins: change both together.
 */
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

const MONTHS = [
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sept',
  'Oct',
  'Nov',
  'Dec',
];
const pad = (n: number): string => String(n).padStart(2, '0');

describe('the wall clock of an instant in a zone (shared with the backend)', () => {
  it('decomposes every shared instant to the backend’s wall clock', () => {
    for (const [instant, zone, wallClock] of SHARED_WALL_CLOCKS) {
      const p = getZonedDateParts(new Date(instant), zone);
      expect(
        `${p.year}-${pad(p.month + 1)}-${pad(p.day)} ${pad(p.hour)}:${pad(p.minute)}`,
        `${instant} ${zone}`,
      ).toBe(wallClock);
    }
  });

  it('prints the date and the 24-hour time of every shared instant as the backend reads them', () => {
    for (const [instant, zone, wallClock] of SHARED_WALL_CLOCKS) {
      const [date, time] = wallClock.split(' ');
      const [y, m, d] = date.split('-').map(Number);
      const printedDate = formatDate(instant, 'en-GB', zone);
      // en-GB "medium" is "31 Dec 2026"; September is "Sept" in current ICU and "Sep" in older builds.
      expect(printedDate.replace('Sep ', 'Sept '), `${instant} ${zone}`).toBe(
        `${d} ${MONTHS[m - 1]} ${y}`,
      );
      expect(formatTime(instant, 'da', zone), `${instant} ${zone}`).toBe(time.replace(':', '.'));
    }
  });
});

describe('translateDateInterval at the clock changes', () => {
  const COPENHAGEN = { locale: 'en-GB', timeZone: 'Europe/Copenhagen' } as const;

  it('labels each end with its own zone when an interval crosses a clock change', () => {
    // 28 March 2026 12:00 CET to 2 April 2026 12:00 CEST. The Insert time dialog puts this text into the
    // message body, so it reaches mariners. With one label taken from the start, the end read "12:00 PM CET",
    // which is 13:00 summer time: an hour later than the warning ends.
    const result = translateDateInterval(
      { fromDate: '2026-03-28T11:00:00Z', toDate: '2026-04-02T10:00:00Z' },
      COPENHAGEN,
    );
    expect(result).toEqual({
      key: DATE_INTERVAL_KEYS.fromTo,
      params: { from: '28 Mar 2026, 12:00 PM CET', to: '2 Apr 2026, 12:00 PM CEST' },
    });
  });

  it('keeps both ends of an interval inside the repeated hour, each with its zone', () => {
    // 25 October 2026, 02:30 CEST to 02:30 CET is one hour. The two ends print alike, and the interval used to
    // collapse to one moment. The backend's formatter pins the same case
    // (NavWarnDateFormatterZoneGridTest.timedInterval_ofOneHourInTheRepeatedHour_namesBothEnds_withTheirZones).
    const result = translateDateInterval(
      { fromDate: '2026-10-25T00:30:00Z', toDate: '2026-10-25T01:30:00Z' },
      COPENHAGEN,
    );
    expect(result).toEqual({
      key: DATE_INTERVAL_KEYS.sameDateRange,
      params: { date: '25 Oct 2026', fromTime: '2:30 AM CEST', toTime: '2:30 AM CET' },
    });
  });

  it('labels both times of a night across the spring change on one date', () => {
    // 29 March 2026, 01:30 CET to 03:30 CEST is one hour; "1:30 AM - 3:30 AM CET" read as two.
    const result = translateDateInterval(
      { fromDate: '2026-03-29T00:30:00Z', toDate: '2026-03-29T01:30:00Z' },
      COPENHAGEN,
    );
    expect(result).toEqual({
      key: DATE_INTERVAL_KEYS.sameDateRange,
      params: { date: '29 Mar 2026', fromTime: '1:30 AM CET', toTime: '3:30 AM CEST' },
    });
  });

  it('keeps one label, at the end, when both ends share a zone', () => {
    expect(
      translateDateInterval(
        { fromDate: '2026-04-02T10:00:00Z', toDate: '2026-04-03T10:00:00Z' },
        COPENHAGEN,
      ),
    ).toEqual({
      key: DATE_INTERVAL_KEYS.fromTo,
      params: { from: '2 Apr 2026, 12:00 PM', to: '3 Apr 2026, 12:00 PM CEST' },
    });
    // The first 01:00 to 01:45 on the autumn change day: both ends still summer time.
    expect(
      translateDateInterval(
        { fromDate: '2026-10-24T23:00:00Z', toDate: '2026-10-24T23:45:00Z' },
        COPENHAGEN,
      ),
    ).toEqual({
      key: DATE_INTERVAL_KEYS.sameDateRange,
      params: { date: '25 Oct 2026', fromTime: '1:00 AM', toTime: '1:45 AM CEST' },
    });
  });

  it('prints no label at all across a change when the zone is turned off', () => {
    const noZone = { ...COPENHAGEN, includeTimeZone: false };
    expect(
      translateDateInterval(
        { fromDate: '2026-03-28T11:00:00Z', toDate: '2026-04-02T10:00:00Z' },
        noZone,
      ),
    ).toEqual({
      key: DATE_INTERVAL_KEYS.fromTo,
      params: { from: '28 Mar 2026, 12:00 PM', to: '2 Apr 2026, 12:00 PM' },
    });
    // Without labels the repeated hour still prints as a range of two ends, never as one moment.
    expect(
      translateDateInterval(
        { fromDate: '2026-10-25T00:30:00Z', toDate: '2026-10-25T01:30:00Z' },
        noZone,
      ),
    ).toEqual({
      key: DATE_INTERVAL_KEYS.sameDateRange,
      params: { date: '25 Oct 2026', fromTime: '2:30 AM', toTime: '2:30 AM' },
    });
  });

  it('labels each end of an all-day interval across a change with its own zone', () => {
    const result = translateDateInterval(
      { fromDate: '2026-03-27T23:00:00Z', toDate: '2026-04-02T21:59:59Z', allDay: true },
      COPENHAGEN,
    );
    expect(result).toEqual({
      key: DATE_INTERVAL_KEYS.fromTo,
      params: { from: '28 Mar 2026 CET', to: '2 Apr 2026 CEST' },
    });
  });

  it('prints an all-day interval on the autumn change day as that one day', () => {
    // 25 October 2026 in Copenhagen starts in summer time and ends in winter time; a day is still one day.
    const result = translateDateInterval(
      { fromDate: '2026-10-24T22:00:00Z', toDate: '2026-10-25T22:59:59Z', allDay: true },
      COPENHAGEN,
    );
    expect(result).toEqual({
      key: DATE_INTERVAL_KEYS.single,
      params: { value: '25 Oct 2026 CEST' },
    });
  });

  it('prints an all-day interval in Nuuk on the zone’s own day', () => {
    // 28 March 2026 in Nuuk runs from 02:00Z to 00:59:59Z on the 29th (its 23:00 becomes 00:00).
    const result = translateDateInterval(
      { fromDate: '2026-03-28T02:00:00Z', toDate: '2026-03-29T00:59:59Z', allDay: true },
      { locale: 'en-GB', timeZone: 'America/Nuuk', includeTimeZone: false },
    );
    expect(result).toEqual({ key: DATE_INTERVAL_KEYS.single, params: { value: '28 Mar 2026' } });
  });

  it('keeps both years of an interval across New Year', () => {
    const result = translateDateInterval(
      { fromDate: '2026-12-31T22:00:00Z', toDate: '2027-01-01T01:00:00Z' },
      { ...COPENHAGEN, includeTimeZone: false },
    );
    expect(result).toEqual({
      key: DATE_INTERVAL_KEYS.fromTo,
      params: { from: '31 Dec 2026, 11:00 PM', to: '1 Jan 2027, 2:00 AM' },
    });
  });
});
