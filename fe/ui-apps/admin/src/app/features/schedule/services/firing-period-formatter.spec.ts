import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { DomainService } from '../../../core/domain/services/domain.service';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../core/main-shell/services/locale-format.service';
import { FiringPeriodFormatter } from './firing-period-formatter';

const COPENHAGEN = { sessionDomain: () => ({ timeZone: 'Europe/Copenhagen' }) };
const ENGLISH = { activeLocale: () => 'en-GB' };

function setup() {
  // Echo the iso so we can assert the COMPOSITION (from – to / single / empty)
  // without coupling to locale/timezone-specific output. The date echoes the iso's
  // calendar part, so two ends on one day compare equal and two days differ.
  const formatTime = vi.fn((iso: string) => `<${iso}>`);
  const formatDate = vi.fn((iso: string) => `[${iso.slice(0, 10)}]`);
  TestBed.configureTestingModule({
    providers: [
      FiringPeriodFormatter,
      {
        provide: LocaleFormatService,
        useValue: {
          formatTime,
          formatDate,
          uses12HourClock: () => false,
          sessionTimeZone: () => 'Europe/Copenhagen',
        },
      },
      { provide: DomainService, useValue: COPENHAGEN },
      { provide: LanguageService, useValue: ENGLISH },
    ],
  });
  return { formatter: TestBed.inject(FiringPeriodFormatter), formatTime };
}

/** The real formatting service, in English and the domain's zone, for the labels themselves. */
function setupFormatted() {
  TestBed.configureTestingModule({
    providers: [
      FiringPeriodFormatter,
      LocaleFormatService,
      { provide: DomainService, useValue: COPENHAGEN },
      { provide: LanguageService, useValue: ENGLISH },
    ],
  });
  return TestBed.inject(FiringPeriodFormatter);
}

const A = '2026-06-15T08:00';
const B = '2026-06-15T12:00';
const NEXT_DAY = '2026-06-16T02:00';

describe('FiringPeriodFormatter', () => {
  it('formats a from–to interval on one day as times only', () => {
    const { formatter } = setup();
    expect(formatter.format({ fromDate: A, toDate: B })).toBe(`<${A}> - <${B}>`);
  });

  it('formats a period across midnight with the date on both ends', () => {
    const { formatter } = setup();
    expect(formatter.format({ fromDate: A, toDate: NEXT_DAY })).toBe(
      `[2026-06-15], <${A}> - [2026-06-16], <${NEXT_DAY}>`,
    );
  });

  /**
   * An older period stored with its end before its start, both on one day: 22:00 to 02:00 of the
   * 15th is listed on the 15th and, for its early hours, on the 16th. Times only would read as
   * tonight on the 16th, so the stored dates are printed on both ends.
   */
  it('formats a period stored with its end before its start on one day with the date on both ends', () => {
    const { formatter } = setup();
    const from = '2026-06-15T22:00';
    const to = '2026-06-15T02:00';
    expect(formatter.format({ fromDate: from, toDate: to })).toBe(
      `[2026-06-15], <${from}> - [2026-06-15], <${to}>`,
    );
  });

  it('formats a period whose end equals its start with the date on both ends', () => {
    const { formatter } = setup();
    expect(formatter.format({ fromDate: A, toDate: A })).toBe(
      `[2026-06-15], <${A}> - [2026-06-15], <${A}>`,
    );
  });

  it('formats a from-only or to-only period as the single time', () => {
    const { formatter } = setup();
    expect(formatter.format({ fromDate: A })).toBe(`<${A}>`);
    expect(formatter.format({ toDate: B })).toBe(`<${B}>`);
  });

  it('returns empty for a period with neither end', () => {
    const { formatter } = setup();
    expect(formatter.format({})).toBe('');
  });

  it('requests the timezone abbreviation only on the to-end', () => {
    const { formatter, formatTime } = setup();
    formatter.format({ fromDate: A, toDate: B });
    formatter.format({ fromDate: A, toDate: NEXT_DAY });
    const calls = formatTime.mock.calls as unknown as readonly (readonly [
      string,
      { withZoneName?: boolean },
    ])[];
    expect(calls.map((c) => c[1].withZoneName)).toEqual([false, true, false, true]);
  });
});

/**
 * Each end of a period is read in its own zone. One label at the end serves both while the two
 * ends share it; a period across a clock change carries one on each end, since the end's label on
 * the start would put the start an hour off.
 */
describe('FiringPeriodFormatter, the zone labels', () => {
  it('keeps one label, on the end, when both ends share a zone', () => {
    const formatter = setupFormatted();
    expect(
      formatter.format({ fromDate: '2026-06-15T06:00:00Z', toDate: '2026-06-15T10:00:00Z' }),
    ).toBe('8:00 AM - 12:00 PM CEST');
  });

  it('labels each end across the spring change', () => {
    const formatter = setupFormatted();
    // 1:30 before the clocks go forward, 3:30 after.
    expect(
      formatter.format({ fromDate: '2026-03-29T00:30:00Z', toDate: '2026-03-29T01:30:00Z' }),
    ).toBe('1:30 AM CET - 3:30 AM CEST');
  });

  it('labels each end across the autumn change, where both print the same time', () => {
    const formatter = setupFormatted();
    // 2:30 twice: before the clocks go back, and an hour later.
    expect(
      formatter.format({ fromDate: '2026-10-25T00:30:00Z', toDate: '2026-10-25T01:30:00Z' }),
    ).toBe('2:30 AM CEST - 2:30 AM CET');
  });

  it('formats one end on its own with its date, time and zone', () => {
    const formatter = setupFormatted();
    expect(formatter.formatInstant('2026-11-24T22:00:00Z')).toBe('24 Nov 2026, 11:00 PM CET');
  });

  it('labels each end of a period across midnight and a clock change', () => {
    const formatter = setupFormatted();
    // From 11 PM on the Saturday, before the change, to 4 AM on the Sunday, after it.
    expect(
      formatter.format({ fromDate: '2026-03-28T22:00:00Z', toDate: '2026-03-29T02:00:00Z' }),
    ).toBe('28 Mar 2026, 11:00 PM CET - 29 Mar 2026, 4:00 AM CEST');
  });

  it('prints the stored dates of a period stored as 22:00 to 02:00 of one day', () => {
    const formatter = setupFormatted();
    // 22:00 and 02:00 CET of 24 Nov: not 22:00 tonight to 02:00 tomorrow.
    expect(
      formatter.format({ fromDate: '2026-11-24T21:00:00Z', toDate: '2026-11-24T01:00:00Z' }),
    ).toBe('24 Nov 2026, 10:00 PM - 24 Nov 2026, 2:00 AM CET');
  });
});
