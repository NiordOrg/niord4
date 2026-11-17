import { describe, expect, it } from 'vitest';

import { DATE_INTERVAL_KEYS, translateDateInterval } from './translate-date-interval';

// All cases pin `timeZone: 'UTC'` so the formatted wall-clock is deterministic and
// independent of the machine running the suite.
const EN = { locale: 'en-GB', timeZone: 'UTC' } as const;

describe('translateDateInterval', () => {
  describe('same-date / same-time collapse', () => {
    it('collapses an all-day interval on one date to a single date value', () => {
      const result = translateDateInterval(
        { fromDate: '2026-07-04T00:00:00Z', toDate: '2026-07-04T23:59:59Z', allDay: true },
        { ...EN, includeTimeZone: false },
      );
      expect(result).toEqual({
        key: DATE_INTERVAL_KEYS.single,
        params: { value: '4 Jul 2026' },
      });
    });

    it('collapses an identical from/to date-time to a single value', () => {
      const result = translateDateInterval(
        { fromDate: '2026-07-04T14:30:00Z', toDate: '2026-07-04T14:30:00Z' },
        { ...EN, includeTimeZone: false },
      );
      expect(result).toEqual({
        key: DATE_INTERVAL_KEYS.single,
        params: { value: '4 Jul 2026, 2:30 PM' },
      });
    });

    it('renders same-date, different-time as a date + time range', () => {
      const result = translateDateInterval(
        { fromDate: '2026-07-04T14:30:00Z', toDate: '2026-07-04T17:00:00Z' },
        { ...EN, includeTimeZone: false },
      );
      expect(result).toEqual({
        key: DATE_INTERVAL_KEYS.sameDateRange,
        params: { date: '4 Jul 2026', fromTime: '2:30 PM', toTime: '5:00 PM' },
      });
    });
  });

  describe('all-day', () => {
    it('renders a multi-day all-day interval as date-only from/to', () => {
      const result = translateDateInterval(
        { fromDate: '2026-07-04T00:00:00Z', toDate: '2026-07-06T00:00:00Z', allDay: true },
        { ...EN, includeTimeZone: false },
      );
      expect(result).toEqual({
        key: DATE_INTERVAL_KEYS.fromTo,
        params: { from: '4 Jul 2026', to: '6 Jul 2026' },
      });
    });

    it('renders a multi-day timed interval as date-time from/to', () => {
      const result = translateDateInterval(
        { fromDate: '2026-07-04T14:30:00Z', toDate: '2026-07-06T09:00:00Z' },
        { ...EN, includeTimeZone: false },
      );
      expect(result).toEqual({
        key: DATE_INTERVAL_KEYS.fromTo,
        params: { from: '4 Jul 2026, 2:30 PM', to: '6 Jul 2026, 9:00 AM' },
      });
    });
  });

  describe('open-ended intervals', () => {
    it('emits the from-date key when only a start is present', () => {
      const result = translateDateInterval(
        { fromDate: '2026-07-04T14:30:00Z' },
        { ...EN, includeTimeZone: false },
      );
      expect(result).toEqual({
        key: DATE_INTERVAL_KEYS.fromDate,
        params: { fromDate: '4 Jul 2026, 2:30 PM' },
      });
    });

    it('emits the to-date key when only an end is present', () => {
      const result = translateDateInterval(
        { toDate: '2026-07-04T14:30:00Z' },
        { ...EN, includeTimeZone: false },
      );
      expect(result).toEqual({
        key: DATE_INTERVAL_KEYS.toDate,
        params: { toDate: '4 Jul 2026, 2:30 PM' },
      });
    });

    it('uses date-only formatting for an all-day open-ended start', () => {
      const result = translateDateInterval(
        { fromDate: '2026-07-04T14:30:00Z', allDay: true },
        { ...EN, includeTimeZone: false },
      );
      expect(result).toEqual({
        key: DATE_INTERVAL_KEYS.fromDate,
        params: { fromDate: '4 Jul 2026' },
      });
    });
  });

  describe('until further notice', () => {
    it('emits the until-further-notice key for an interval with no dates', () => {
      expect(translateDateInterval({}, EN)).toEqual({
        key: DATE_INTERVAL_KEYS.untilFurtherNotice,
        params: {},
      });
    });

    it('emits the until-further-notice key for a null interval', () => {
      expect(translateDateInterval(null, EN)).toEqual({
        key: DATE_INTERVAL_KEYS.untilFurtherNotice,
        params: {},
      });
    });
  });

  describe('time-zone suffix', () => {
    it('appends the zone abbreviation when includeTimeZone defaults on', () => {
      const result = translateDateInterval({ fromDate: '2026-07-04T14:30:00Z' }, EN);
      expect(result).toEqual({
        key: DATE_INTERVAL_KEYS.fromDate,
        params: { fromDate: '4 Jul 2026, 2:30 PM UTC' },
      });
    });

    it('omits the zone abbreviation when includeTimeZone is false', () => {
      const result = translateDateInterval(
        { fromDate: '2026-07-04T14:30:00Z' },
        { ...EN, includeTimeZone: false },
      );
      expect(result.params['fromDate']).toBe('4 Jul 2026, 2:30 PM');
    });

    it('appends the zone once, after the to-side of a range', () => {
      const result = translateDateInterval(
        { fromDate: '2026-07-04T14:30:00Z', toDate: '2026-07-06T09:00:00Z' },
        EN,
      );
      expect(result.params).toEqual({
        from: '4 Jul 2026, 2:30 PM',
        to: '6 Jul 2026, 9:00 AM UTC',
      });
    });
  });

  describe('Danish locale', () => {
    it('formats dates in the da locale', () => {
      const result = translateDateInterval(
        { fromDate: '2026-07-04T14:30:00Z', toDate: '2026-07-04T17:00:00Z' },
        { locale: 'da', timeZone: 'UTC', includeTimeZone: false },
      );
      expect(result).toEqual({
        key: DATE_INTERVAL_KEYS.sameDateRange,
        params: { date: '4. jul. 2026', fromTime: '14.30', toTime: '17.00' },
      });
    });
  });

  describe('multi-interval', () => {
    it('maps a list of intervals to per-interval results', () => {
      const intervals = [{ fromDate: '2026-07-04T14:30:00Z', toDate: '2026-07-04T14:30:00Z' }, {}];
      const results = intervals.map((interval) =>
        translateDateInterval(interval, { ...EN, includeTimeZone: false }),
      );
      expect(results[0].key).toBe(DATE_INTERVAL_KEYS.single);
      expect(results[0].params['value']).toBe('4 Jul 2026, 2:30 PM');
      expect(results[1].key).toBe(DATE_INTERVAL_KEYS.untilFurtherNotice);
    });
  });
});
