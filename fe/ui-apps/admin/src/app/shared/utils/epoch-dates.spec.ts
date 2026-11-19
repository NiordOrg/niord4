import { describe, expect, it } from 'vitest';

import { epochMillisOf, withEpochDates } from './epoch-dates';

/** The exact string the backend sends for the end of 2019 in Copenhagen. */
const ISO_END_OF_2019 = '2019-12-31T22:59:59.999+00:00';
const EPOCH_END_OF_2019 = 1577833199999;

describe('withEpochDates', () => {
  it('converts a declared date field carrying an ISO instant with an offset', () => {
    expect(withEpochDates({ intervalTo: ISO_END_OF_2019 })).toEqual({
      intervalTo: EPOCH_END_OF_2019,
    });
    expect(withEpochDates({ cutoff: '2020-01-01T00:00:00Z' })).toEqual({
      cutoff: Date.parse('2020-01-01T00:00:00Z'),
    });
    expect(withEpochDates({ publishedAt: '2020-06-01T12:30:00.123456+02:00' })).toEqual({
      publishedAt: Date.parse('2020-06-01T12:30:00.123456+02:00'),
    });
  });

  it('converts declared fields nested at any depth, through arrays', () => {
    const payload = {
      issue: { intervalTo: ISO_END_OF_2019, descs: [{ lang: 'da', name: 'Akkumuleret EfS' }] },
      members: [
        { frozenPublishDateFrom: ISO_END_OF_2019, sourceIssue: { cutoff: ISO_END_OF_2019 } },
      ],
    };

    expect(withEpochDates(payload)).toEqual({
      issue: { intervalTo: EPOCH_END_OF_2019, descs: [{ lang: 'da', name: 'Akkumuleret EfS' }] },
      members: [
        { frozenPublishDateFrom: EPOCH_END_OF_2019, sourceIssue: { cutoff: EPOCH_END_OF_2019 } },
      ],
    });
  });

  it('leaves a value shaped like an instant alone when its field is not a declared date', () => {
    const payload = {
      name: ISO_END_OF_2019,
      seriesId: '2026-09-15T12:00:00Z',
      descs: [{ name: '2020-01-01T00:00:00Z' }],
    };

    expect(withEpochDates(payload)).toBe(payload);
  });

  it('never descends into the free-form containers', () => {
    const payload = {
      intervalTo: ISO_END_OF_2019,
      reportParams: { since: '2026-09-15T12:00:00Z', cutoff: ISO_END_OF_2019 },
      detail: { created: ISO_END_OF_2019, note: 'kept' },
      detailParams: { at: ISO_END_OF_2019 },
      criteria: [{ kind: 'DATE', values: ['2020-01-01T00:00:00Z'], intervalFrom: ISO_END_OF_2019 }],
      names: { da: ISO_END_OF_2019 },
    };

    const out = withEpochDates(payload);

    expect(out.intervalTo).toBe(EPOCH_END_OF_2019);
    expect(out.reportParams).toBe(payload.reportParams);
    expect(out.detail).toBe(payload.detail);
    expect(out.detailParams).toBe(payload.detailParams);
    expect(out.criteria).toBe(payload.criteria);
    expect(out.names).toBe(payload.names);
  });

  it('leaves a declared date field with no offset alone — it names a wall clock, not an instant', () => {
    expect(withEpochDates({ intervalTo: '2019-12-31T22:59:59.999' })).toEqual({
      intervalTo: '2019-12-31T22:59:59.999',
    });
    expect(withEpochDates({ intervalTo: '2019-12-31' })).toEqual({ intervalTo: '2019-12-31' });
  });

  it('leaves a declared date field holding an impossible date as it arrived', () => {
    expect(withEpochDates({ intervalTo: '2019-13-45T22:59:59.999+00:00' })).toEqual({
      intervalTo: '2019-13-45T22:59:59.999+00:00',
    });
  });

  it('passes numbers, booleans, null, undefined and bare strings straight through', () => {
    expect(withEpochDates(EPOCH_END_OF_2019)).toBe(EPOCH_END_OF_2019);
    expect(withEpochDates(ISO_END_OF_2019)).toBe(ISO_END_OF_2019);
    expect(withEpochDates(false)).toBe(false);
    expect(withEpochDates(null)).toBeNull();
    expect(withEpochDates(undefined)).toBeUndefined();
    expect(withEpochDates({ sortKey: EPOCH_END_OF_2019, viewedAt: null, cutoff: 12 })).toEqual({
      sortKey: EPOCH_END_OF_2019,
      viewedAt: null,
      cutoff: 12,
    });
  });

  it('never mutates its input', () => {
    const payload = { issue: { intervalTo: ISO_END_OF_2019 }, rows: [{ cutoff: ISO_END_OF_2019 }] };
    const before = structuredClone(payload);

    withEpochDates(payload);

    expect(payload).toEqual(before);
  });

  it('returns what it was given where there is nothing to convert', () => {
    const payload = { seriesId: 'weekly-ntm', criteria: [{ values: ['dk', 'gl'] }], intervalTo: 5 };

    expect(withEpochDates(payload)).toBe(payload);
  });

  it('leaves an object with a prototype of its own untouched', () => {
    const date = new Date(EPOCH_END_OF_2019);

    expect(withEpochDates({ viewedAt: date }).viewedAt).toBe(date);
  });
});

describe('epochMillisOf', () => {
  it('reads an ISO instant with an offset to the millisecond, and a number as it came', () => {
    expect(epochMillisOf(ISO_END_OF_2019)).toBe(EPOCH_END_OF_2019);
    expect(epochMillisOf('2026-11-19T09:27:20.142+00:00')).toBe(
      Date.UTC(2026, 10, 19, 9, 27, 20, 142),
    );
    expect(epochMillisOf(EPOCH_END_OF_2019)).toBe(EPOCH_END_OF_2019);
  });

  it('gives undefined for no value, a wall clock without an offset and an impossible date', () => {
    expect(epochMillisOf(undefined)).toBeUndefined();
    expect(epochMillisOf(null)).toBeUndefined();
    expect(epochMillisOf(Number.NaN)).toBeUndefined();
    expect(epochMillisOf('2019-12-31T22:59:59.999')).toBeUndefined();
    expect(epochMillisOf('2019-13-45T22:59:59.999+00:00')).toBeUndefined();
  });
});
