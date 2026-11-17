import { afterEach, describe, expect, it, vi } from 'vitest';

import type { MainType } from '../../../../../shared/models/main-type.model';
import type { NumberSequenceType } from '../../../models/admin-message-series.model';
import {
  buildSeriesPayload,
  currentYearIn,
  definesShortFormat,
  numberAssigned,
  RESTRICT_TYPES,
  type SeriesFormValue,
} from './series-rules';

const series = (numberSequenceType?: NumberSequenceType) => ({ numberSequenceType });

describe('currentYearIn', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('names the year the given zone is on, not the one UTC is on', () => {
    vi.useFakeTimers();
    // 00:30 UTC on New Year's Day: Halifax (UTC-4) is still on 31 December of the old year.
    vi.setSystemTime(new Date('2027-01-01T00:30:00Z'));
    expect(currentYearIn('UTC')).toBe(2027);
    expect(currentYearIn('America/Halifax')).toBe(2026);
    expect(currentYearIn('Pacific/Auckland')).toBe(2027);
  });

  it('names the year the given zone is on when the zone is ahead of UTC', () => {
    vi.useFakeTimers();
    // 23:30 UTC on New Year's Eve: Auckland (UTC+13 in January) is already in the new year.
    vi.setSystemTime(new Date('2026-12-31T23:30:00Z'));
    expect(currentYearIn('UTC')).toBe(2026);
    expect(currentYearIn('Pacific/Auckland')).toBe(2027);
  });

  it('degrades to the year the administrator is on when no domain names a zone', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-06-15T12:00:00Z'));
    // Mid-June: every zone agrees on the year, so this asserts the fallback fires at all
    // without asserting the runner's own offset.
    expect(currentYearIn(undefined)).toBe(2026);
  });
});

describe('numberAssigned', () => {
  it('is true for the auto-numbered sequence types (YEARLY, CONTINUOUS)', () => {
    expect(numberAssigned(series('YEARLY'))).toBe(true);
    expect(numberAssigned(series('CONTINUOUS'))).toBe(true);
  });

  it('is false for MANUAL and NONE', () => {
    expect(numberAssigned(series('MANUAL'))).toBe(false);
    expect(numberAssigned(series('NONE'))).toBe(false);
  });

  it('is false when the sequence type is unset', () => {
    expect(numberAssigned(series())).toBe(false);
  });
});

describe('definesShortFormat', () => {
  it('is true for every sequence type except NONE', () => {
    expect(definesShortFormat(series('YEARLY'))).toBe(true);
    expect(definesShortFormat(series('CONTINUOUS'))).toBe(true);
    expect(definesShortFormat(series('MANUAL'))).toBe(true);
  });

  it('is false for NONE', () => {
    expect(definesShortFormat(series('NONE'))).toBe(false);
  });

  it('is false when the sequence type is unset', () => {
    expect(definesShortFormat(series())).toBe(false);
  });
});

describe('buildSeriesPayload (the save-normalization rules)', () => {
  const ALL_SEQUENCE_TYPES: readonly NumberSequenceType[] = [
    'YEARLY',
    'CONTINUOUS',
    'MANUAL',
    'NONE',
  ];
  const MAIN_TYPES: readonly MainType[] = ['NW', 'NM'];

  const value = (overrides: Partial<SeriesFormValue> = {}): SeriesFormValue => ({
    seriesId: 'dma-nw',
    mainType: 'NW',
    numberSequenceType: 'YEARLY',
    shortFormat: 'NW-${number}-${year}',
    navtexFormat: 'DANISH NAV. WARNING ${short-id}',
    excludeFromMessageIndex: false,
    ...overrides,
  });

  /** Check maps ticked for the given types (both mainType maps are always present). */
  const checksFor = (...ticked: string[]): Record<MainType, Record<string, boolean>> => ({
    NW: Object.fromEntries(RESTRICT_TYPES.NW.map((t) => [t, ticked.includes(t)])),
    NM: Object.fromEntries(RESTRICT_TYPES.NM.map((t) => [t, ticked.includes(t)])),
  });

  it(
    'covers the full matrix: 4 sequence types × NW/NM — shortFormat only when defined, ' +
      'types only from the current main type',
    () => {
      for (const numberSequenceType of ALL_SEQUENCE_TYPES) {
        for (const mainType of MAIN_TYPES) {
          // One check ticked in EACH map — only the current main type's may survive.
          const checks = checksFor('COASTAL_WARNING', 'PERMANENT_NOTICE');
          const payload = buildSeriesPayload(value({ mainType, numberSequenceType }), checks, [
            'charts',
          ]);

          expect(payload.seriesId).toBe('dma-nw');
          expect(payload.mainType).toBe(mainType);
          expect(payload.numberSequenceType).toBe(numberSequenceType);
          expect(payload.types).toEqual(
            mainType === 'NW' ? ['COASTAL_WARNING'] : ['PERMANENT_NOTICE'],
          );
          if (numberSequenceType === 'NONE') {
            // Stripped from the payload — the working model keeps the text.
            expect('shortFormat' in payload).toBe(false);
          } else {
            expect(payload.shortFormat).toBe('NW-${number}-${year}');
          }
          // NAVTEX survives every sequence type (incl. NONE) and both main types.
          expect(payload.navtexFormat).toBe('DANISH NAV. WARNING ${short-id}');
          expect(payload.editorFields).toEqual(['charts']);
        }
      }
    },
  );

  it('keeps sending types: [] when nothing is checked — empty means UNRESTRICTED', () => {
    const payload = buildSeriesPayload(value(), checksFor(), []);
    expect(payload.types).toEqual([]);
  });

  it('drops checks belonging to the other main type silently', () => {
    // NW checks made earlier in the session; the series is saved as NM.
    const checks = checksFor('LOCAL_WARNING', 'NAVAREA_WARNING', 'TEMPORARY_NOTICE');
    const payload = buildSeriesPayload(value({ mainType: 'NM' }), checks, []);
    expect(payload.types).toEqual(['TEMPORARY_NOTICE']);
  });

  it('collects multiple checks in checkbox order', () => {
    const checks = checksFor('NAVAREA_WARNING', 'LOCAL_WARNING');
    const payload = buildSeriesPayload(value(), checks, []);
    expect(payload.types).toEqual(['LOCAL_WARNING', 'NAVAREA_WARNING']);
  });

  it('sends an empty shortFormat as "" for defining sequence types', () => {
    const payload = buildSeriesPayload(value({ shortFormat: '' }), checksFor(), []);
    expect(payload.shortFormat).toBe('');
  });

  it('omits an empty navtexFormat (matches the add-mode payload)', () => {
    const payload = buildSeriesPayload(value({ navtexFormat: '' }), checksFor(), []);
    expect('navtexFormat' in payload).toBe(false);
  });

  it('omits editorFields when none are selected (the shipped area/category rule)', () => {
    const payload = buildSeriesPayload(value(), checksFor(), []);
    expect('editorFields' in payload).toBe(false);
  });

  it('passes excludeFromMessageIndex through both ways', () => {
    expect(
      buildSeriesPayload(value({ excludeFromMessageIndex: true }), checksFor(), [])
        .excludeFromMessageIndex,
    ).toBe(true);
    expect(
      buildSeriesPayload(value({ excludeFromMessageIndex: false }), checksFor(), [])
        .excludeFromMessageIndex,
    ).toBe(false);
  });
});
