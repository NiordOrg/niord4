import { describe, expect, it } from 'vitest';

import {
  formatDate,
  formatDateTime,
  formatTime,
  formatWeekdayDateTime,
  zoneAbbreviation,
} from './date-time-format';

/**
 * The one config drives every date/time in the app, so its output IS the contract:
 * Danish 24h period "09.35", English 12h uppercase bare-hour "9:35 AM", locale-native dates.
 */
describe('date-time-format (single source of truth)', () => {
  const am = new Date(2026, 0, 5, 9, 35);
  const pm = new Date(2026, 0, 5, 14, 5);

  it('Danish time: 24h, 2-digit, period separator', () => {
    expect(formatTime(am, 'da')).toBe('09.35');
    expect(formatTime(pm, 'da')).toBe('14.05');
  });

  it('English time: 12h, bare hour, uppercase AM/PM, colon separator', () => {
    expect(formatTime(am, 'en-GB')).toBe('9:35 AM');
    expect(formatTime(pm, 'en-GB')).toBe('2:05 PM');
  });

  it('dates are locale-native (medium)', () => {
    expect(formatDate(am, 'da')).toBe('5. jan. 2026');
    expect(formatDate(am, 'en-GB')).toBe('5 Jan 2026');
  });

  it('date-time = date + ", " + time', () => {
    expect(formatDateTime(am, 'da')).toBe('5. jan. 2026, 09.35');
    expect(formatDateTime(am, 'en-GB')).toBe('5 Jan 2026, 9:35 AM');
  });

  it('renders the wall-clock in the given timezone', () => {
    const utc0835 = new Date(Date.UTC(2026, 0, 5, 8, 35));
    expect(formatTime(utc0835, 'da', 'Europe/Copenhagen')).toBe('09.35'); // CET = UTC+1 in January
    expect(formatTime(utc0835, 'da', 'UTC')).toBe('08.35');
  });

  it('zoneAbbreviation returns the short zone name', () => {
    expect(zoneAbbreviation(am, 'en-GB', 'UTC')).toBe('UTC');
  });

  /**
   * The weekday form names the day and drops the year.
   *
   * It is used for a deadline days away, where "Wednesday" is what a reader
   * plans around and the year is a fact they already have.
   */
  it('weekday date-time: weekday + day/month + time, no year', () => {
    expect(formatWeekdayDateTime(am, 'da')).toBe('man. 5. jan., 09.35');
    expect(formatWeekdayDateTime(am, 'en-GB')).toBe('Mon 5 Jan, 9:35 AM');
    expect(formatWeekdayDateTime(am, 'en-GB')).not.toContain('2026');
  });

  it('the weekday form obeys the given timezone like every other form', () => {
    const utc2335 = new Date(Date.UTC(2026, 0, 5, 23, 35));
    // Past midnight in Copenhagen, so both the weekday and the clock move.
    expect(formatWeekdayDateTime(utc2335, 'da', 'Europe/Copenhagen')).toBe('tirs. 6. jan., 00.35');
    expect(formatWeekdayDateTime(utc2335, 'da', 'UTC')).toBe('man. 5. jan., 23.35');
  });

  it('empty / invalid values render an empty string', () => {
    expect(formatTime('', 'da')).toBe('');
    expect(formatDate('not a date', 'en-GB')).toBe('');
    expect(formatDateTime(NaN, 'da')).toBe('');
    expect(formatWeekdayDateTime('not a date', 'da')).toBe('');
  });
});
