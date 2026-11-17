import {
  endOfZonedDayMs,
  formatInZone,
  getZonedDateParts,
  nextCalendarDay,
  startOfZonedDayMs,
  zonedWallClockToInstantMs,
  type DateNames,
} from './zoned-date';

const COPENHAGEN = 'Europe/Copenhagen';
/**
 * UTC−2 on the EU summer rule: its spring gap ends a local day, as no zone outside Greenland's
 * does. The day-boundary, gap and overlap cases below need it.
 */
const NUUK = 'America/Nuuk';
/** West of UTC all year, for the cases that need only that. */
const HALIFAX = 'America/Halifax';

const DANISH: DateNames = {
  monthsWide: [
    'januar',
    'februar',
    'marts',
    'april',
    'maj',
    'juni',
    'juli',
    'august',
    'september',
    'oktober',
    'november',
    'december',
  ],
  monthsAbbreviated: [
    'jan.',
    'feb.',
    'mar.',
    'apr.',
    'maj',
    'jun.',
    'jul.',
    'aug.',
    'sep.',
    'okt.',
    'nov.',
    'dec.',
  ],
  daysWide: ['søndag', 'mandag', 'tirsdag', 'onsdag', 'torsdag', 'fredag', 'lørdag'],
  daysShort: ['sø', 'ma', 'ti', 'on', 'to', 'fr', 'lø'],
};

const ENGLISH: DateNames = {
  monthsWide: [
    'January',
    'February',
    'March',
    'April',
    'May',
    'June',
    'July',
    'August',
    'September',
    'October',
    'November',
    'December',
  ],
  monthsAbbreviated: [
    'Jan',
    'Feb',
    'Mar',
    'Apr',
    'May',
    'Jun',
    'Jul',
    'Aug',
    'Sep',
    'Oct',
    'Nov',
    'Dec',
  ],
  daysWide: ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'],
  daysShort: ['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa'],
};

const EMPTY_NAMES: DateNames = {
  monthsWide: [],
  monthsAbbreviated: [],
  daysWide: [],
  daysShort: [],
};

const HOUR_MS = 3_600_000;

/**
 * The day boundaries of every case that can move one: both daylight-saving directions in a zone
 * east of UTC and one west of it, the year boundaries the publications year shortcuts send, and
 * the base build's UTC. Europe/Copenhagen and America/Nuuk change clocks at the same instant,
 * 01:00 UTC, but on DIFFERENT local days (Nuuk's falls on the Saturday evening), so a spec that
 * copied the Copenhagen days across would put Nuuk's short and long days a day late.
 *
 * The numbers are literals on purpose: reproducing the implementation in the expectation would
 * assert only that the code is self-consistent.
 */
const DAY_BOUNDARIES: readonly (readonly [string, string, number, number, number])[] = [
  [COPENHAGEN, '2024-01-15', 1705273200000, 1705359599999, 24],
  [COPENHAGEN, '2024-03-30', 1711753200000, 1711839599999, 24],
  [COPENHAGEN, '2024-03-31', 1711839600000, 1711922399999, 23],
  [COPENHAGEN, '2024-10-26', 1729893600000, 1729979999999, 24],
  [COPENHAGEN, '2024-10-27', 1729980000000, 1730069999999, 25],
  [COPENHAGEN, '2024-01-01', 1704063600000, 1704149999999, 24],
  [COPENHAGEN, '2024-12-31', 1735599600000, 1735685999999, 24],
  [NUUK, '2024-01-15', 1705284000000, 1705370399999, 24],
  [NUUK, '2024-03-30', 1711764000000, 1711846799999, 23],
  [NUUK, '2024-03-31', 1711846800000, 1711933199999, 24],
  [NUUK, '2024-10-26', 1729904400000, 1729994399999, 25],
  [NUUK, '2024-10-27', 1729994400000, 1730080799999, 24],
  [NUUK, '2024-12-31', 1735610400000, 1735696799999, 24],
  ['UTC', '2024-06-15', 1718409600000, 1718495999999, 24],
];

describe('zoned-date', () => {
  describe('getZonedDateParts', () => {
    // The unit suite runs in America/St_Johns, which is neither of these and half an hour off
    // both, so a reading that came from the runner's clock cannot pass either assertion.
    it('reads the wall clock of the named zone, not the runner’s', () => {
      const instant = new Date('2024-01-01T01:00:00Z');
      expect(getZonedDateParts(instant, COPENHAGEN)).toEqual({
        year: 2024,
        month: 1,
        day: 1,
        hour: 2,
        minute: 0,
        second: 0,
        weekday: 1,
      });
      expect(getZonedDateParts(instant, HALIFAX)).toEqual({
        year: 2023,
        month: 12,
        day: 31,
        hour: 21,
        minute: 0,
        second: 0,
        weekday: 0,
      });
    });

    it('normalises a midnight reported as hour 24 to hour 0', () => {
      const midnight = startOfZonedDayMs('2024-06-15', COPENHAGEN)!;
      expect(getZonedDateParts(new Date(midnight), COPENHAGEN).hour).toBe(0);
    });
  });

  describe('day boundaries', () => {
    it.each(DAY_BOUNDARIES)('%s %s', (zone, day, start, end, hours) => {
      expect(startOfZonedDayMs(day, zone)).toBe(start);
      expect(endOfZonedDayMs(day, zone)).toBe(end);
      expect((end + 1 - start) / HOUR_MS).toBe(hours);
    });

    it('round-trips every boundary back through the zone’s own calendar', () => {
      for (const [zone, day, start] of DAY_BOUNDARIES) {
        const parts = getZonedDateParts(new Date(start), zone);
        expect([zone, day, parts.hour, parts.minute, parts.second]).toEqual([zone, day, 0, 0, 0]);
        expect(
          zonedWallClockToInstantMs(
            parts.year,
            parts.month,
            parts.day,
            parts.hour,
            parts.minute,
            parts.second,
            zone,
          ),
        ).toBe(start);
      }
    });
  });

  // The backend resolves a wall clock with Java's `ZonedDateTime.of`, and so does this: in a gap
  // the offset before the transition moves the clock forward by the gap, and in an overlap the
  // offset before the transition picks the first occurrence. Nuuk is west of UTC, where a
  // resolution that only works east of it comes out an hour off in the other direction.
  describe('zonedWallClockToInstantMs', () => {
    it('resolves a clock in the spring-forward gap an hour later, as Java does', () => {
      // 02:30 does not exist on 31 March in Copenhagen: 02:00 CET becomes 03:00 CEST.
      const resolved = zonedWallClockToInstantMs(2024, 3, 31, 2, 30, 0, COPENHAGEN);
      expect(resolved).toBe(1711848600000);
      expect(getZonedDateParts(new Date(resolved), COPENHAGEN)).toMatchObject({
        day: 31,
        hour: 3,
        minute: 30,
      });
    });

    it('resolves a clock in Nuuk’s spring-forward gap an hour later, not earlier', () => {
      // 23:30 does not exist on 30 March in Nuuk: 23:00 at UTC−2 becomes 00:00 at UTC−1.
      const resolved = zonedWallClockToInstantMs(2024, 3, 30, 23, 30, 0, NUUK);
      expect(resolved).toBe(1711848600000);
      expect(getZonedDateParts(new Date(resolved), NUUK)).toMatchObject({
        day: 31,
        hour: 0,
        minute: 30,
      });
    });

    it('resolves a clock in the fall-back hour to its first occurrence, as Java does', () => {
      // 02:30 happens twice on 27 October in Copenhagen: at 00:30 UTC (CEST) and 01:30 (CET).
      const resolved = zonedWallClockToInstantMs(2024, 10, 27, 2, 30, 0, COPENHAGEN);
      expect(resolved).toBe(1729989000000);
      expect(getZonedDateParts(new Date(resolved + HOUR_MS), COPENHAGEN)).toMatchObject({
        day: 27,
        hour: 2,
        minute: 30,
      });
    });

    it('resolves a clock in Nuuk’s fall-back hour to its first occurrence', () => {
      // 23:30 happens twice on 26 October in Nuuk: at 00:30 UTC (UTC−1) and 01:30 (UTC−2).
      const resolved = zonedWallClockToInstantMs(2024, 10, 26, 23, 30, 0, NUUK);
      expect(resolved).toBe(1729989000000);
      expect(getZonedDateParts(new Date(resolved + HOUR_MS), NUUK)).toMatchObject({
        day: 26,
        hour: 23,
        minute: 30,
      });
    });

    it('resolves a clock beside a transition by the offset it really has', () => {
      // An hour before the Nuuk gap and an hour after it, both on the same side as ever.
      expect(zonedWallClockToInstantMs(2024, 3, 30, 22, 30, 0, NUUK)).toBe(1711845000000);
      expect(zonedWallClockToInstantMs(2024, 3, 31, 1, 30, 0, NUUK)).toBe(1711852200000);
    });
  });

  describe('nextCalendarDay', () => {
    it('crosses a leap day, a year and a daylight-saving day alike', () => {
      expect(nextCalendarDay('2024-02-28')).toBe('2024-02-29');
      expect(nextCalendarDay('2024-12-31')).toBe('2025-01-01');
      expect(nextCalendarDay('2024-03-30')).toBe('2024-03-31');
    });
  });

  describe('malformed input', () => {
    it('yields undefined rather than a guess', () => {
      expect(startOfZonedDayMs('', COPENHAGEN)).toBeUndefined();
      expect(startOfZonedDayMs('2024-1-1', COPENHAGEN)).toBeUndefined();
      expect(startOfZonedDayMs('not a date', COPENHAGEN)).toBeUndefined();
      expect(endOfZonedDayMs('2024-1-1', COPENHAGEN)).toBeUndefined();
      // A shape test alone would let Date.UTC roll these over into real days months away.
      expect(nextCalendarDay('2024-13-40')).toBeUndefined();
      expect(startOfZonedDayMs('2024-13-40', COPENHAGEN)).toBeUndefined();
      expect(startOfZonedDayMs('2023-02-29', COPENHAGEN)).toBeUndefined();
      expect(startOfZonedDayMs('2024-02-29', COPENHAGEN)).toBeDefined();
    });
  });

  describe('formatInZone', () => {
    const NEW_YEAR = new Date('2024-01-01T01:00:00Z');

    it('renders the Danish print pattern in each zone', () => {
      const pattern = "d. MMMM yyyy 'kl' HH:mm";
      expect(formatInZone(NEW_YEAR, pattern, COPENHAGEN, DANISH)).toBe('1. januar 2024 kl 02:00');
      expect(formatInZone(NEW_YEAR, pattern, HALIFAX, DANISH)).toBe('31. december 2023 kl 21:00');
    });

    it('falls back to the numeric month when the words are missing', () => {
      expect(formatInZone(NEW_YEAR, 'd. MMMM yyyy', COPENHAGEN, EMPTY_NAMES)).toBe('1. 1 2024');
      expect(formatInZone(NEW_YEAR, 'MMM', COPENHAGEN, EMPTY_NAMES)).toBe('1');
      expect(formatInZone(NEW_YEAR, 'EEEE', COPENHAGEN, EMPTY_NAMES)).toBe('');
    });

    it('treats a quoted run as a literal and copies an unknown token through', () => {
      expect(formatInZone(NEW_YEAR, "'at' HH", COPENHAGEN, ENGLISH)).toBe('at 02');
      expect(formatInZone(NEW_YEAR, "d '' MMMM", COPENHAGEN, DANISH)).toBe("1 ' januar");
      expect(formatInZone(NEW_YEAR, 'QQQ yyyy', COPENHAGEN, ENGLISH)).toBe('QQQ 2024');
    });

    it('renders every shipped pattern, pinned', () => {
      const rendered = [
        formatInZone(NEW_YEAR, 'd MMMM yyyy', COPENHAGEN, ENGLISH),
        formatInZone(NEW_YEAR, 'd MMMM yyyy', HALIFAX, ENGLISH),
        formatInZone(NEW_YEAR, 'd MMMM yyyy', 'UTC', ENGLISH),
        formatInZone(NEW_YEAR, "d MMMM yyyy 'at' HH:mm", COPENHAGEN, ENGLISH),
        formatInZone(NEW_YEAR, "d MMMM yyyy 'at' HH:mm", HALIFAX, ENGLISH),
        formatInZone(NEW_YEAR, "d MMMM yyyy 'at' HH:mm", 'UTC', ENGLISH),
      ];
      expect(rendered).toEqual([
        '1 January 2024',
        '31 December 2023',
        '1 January 2024',
        '1 January 2024 at 02:00',
        '31 December 2023 at 21:00',
        '1 January 2024 at 01:00',
      ]);
    });

    it('renders the remaining tokens the pattern language allows', () => {
      const instant = new Date('2024-07-04T22:45:09Z');
      expect(formatInZone(instant, 'EEEE d. MMM yy H:m:s', COPENHAGEN, DANISH)).toBe(
        'fredag 5. jul. 24 0:45:9',
      );
      expect(formatInZone(instant, 'EEE dd/MM/yyyy HH:mm:ss', COPENHAGEN, DANISH)).toBe(
        'fr 05/07/2024 00:45:09',
      );
    });
  });
});
