import { describe, expect, it } from 'vitest';

import { firstDayOfWeek, isValidDate, weekdayShortNames } from './date-locale';

describe('date-locale', () => {
  describe('isValidDate', () => {
    it('accepts a real date', () => {
      expect(isValidDate(new Date(2026, 4, 14))).toBe(true);
    });

    it('rejects an Invalid Date, null, and non-dates', () => {
      expect(isValidDate(new Date('nope'))).toBe(false);
      expect(isValidDate(null)).toBe(false);
      expect(isValidDate('2026-05-14')).toBe(false);
    });
  });

  describe('firstDayOfWeek', () => {
    it('is Monday (1) for Danish', () => {
      expect(firstDayOfWeek('da')).toBe(1);
    });

    it('returns a valid 0–6 day index for English', () => {
      expect(firstDayOfWeek('en-US')).toBeGreaterThanOrEqual(0);
      expect(firstDayOfWeek('en-US')).toBeLessThanOrEqual(6);
    });

    it('falls back to Monday for a malformed locale tag', () => {
      expect(firstDayOfWeek('not a locale!!')).toBe(1);
    });
  });

  describe('weekdayShortNames', () => {
    it('returns 7 names', () => {
      expect(weekdayShortNames('da')).toHaveLength(7);
    });

    it('orders from the locale first day (Danish starts Monday)', () => {
      const [first] = weekdayShortNames('da');
      // Danish Monday abbreviation is "man".
      expect(first.toLowerCase().startsWith('man')).toBe(true);
    });

    it('is localized — Danish differs from English', () => {
      expect(weekdayShortNames('da')).not.toEqual(weekdayShortNames('en-US'));
    });
  });
});
