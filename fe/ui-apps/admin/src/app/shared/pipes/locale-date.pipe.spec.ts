import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import {
  FormatOptions,
  LocaleFormatService,
} from '../../core/main-shell/services/locale-format.service';
import { LocaleDatePipe } from './locale-date.pipe';

function setup() {
  const formatDate = vi.fn(
    (value: Date | string | number, options?: FormatOptions) =>
      `date(${String(value)}|${options?.timeZone ?? 'default'})`,
  );
  const formatTime = vi.fn(
    (value: Date | string | number, options?: FormatOptions) =>
      `time(${String(value)}|${options?.timeZone ?? 'default'})`,
  );
  const formatDateTime = vi.fn(
    (value: Date | string | number, options?: FormatOptions) =>
      `dateTime(${String(value)}|${options?.timeZone ?? 'default'})`,
  );
  TestBed.configureTestingModule({
    providers: [
      {
        provide: LocaleFormatService,
        useValue: { formatDate, formatTime, formatDateTime, uses12HourClock: () => false },
      },
    ],
  });
  const pipe = TestBed.runInInjectionContext(() => new LocaleDatePipe());
  return { pipe, formatDate, formatTime, formatDateTime };
}

describe('LocaleDatePipe', () => {
  it('defaults to the date+time format', () => {
    const { pipe, formatDate, formatTime, formatDateTime } = setup();
    expect(pipe.transform('2026-01-05T09:35:00Z')).toBe('dateTime(2026-01-05T09:35:00Z|default)');
    expect(formatDateTime).toHaveBeenCalledOnce();
    expect(formatDate).not.toHaveBeenCalled();
    expect(formatTime).not.toHaveBeenCalled();
  });

  it("renders date only for the 'date' preset", () => {
    const { pipe, formatDate } = setup();
    expect(pipe.transform('2026-01-05T09:35:00Z', 'date')).toBe(
      'date(2026-01-05T09:35:00Z|default)',
    );
    expect(formatDate).toHaveBeenCalledOnce();
  });

  it("renders time only for the 'time' preset", () => {
    const { pipe, formatTime } = setup();
    expect(pipe.transform('2026-01-05T09:35:00Z', 'time')).toBe(
      'time(2026-01-05T09:35:00Z|default)',
    );
    expect(formatTime).toHaveBeenCalledOnce();
  });

  it('forwards format options through to the service', () => {
    const { pipe, formatDateTime } = setup();
    const options: FormatOptions = { timeZone: 'Europe/Copenhagen', local: true };
    pipe.transform('2026-01-05T09:35:00Z', 'dateTime', options);
    expect(formatDateTime).toHaveBeenCalledWith('2026-01-05T09:35:00Z', options);
  });

  it('returns an empty string for a null/empty value', () => {
    const { pipe } = setup();
    expect(pipe.transform(null)).toBe('');
    expect(pipe.transform(undefined)).toBe('');
    expect(pipe.transform('')).toBe('');
  });
});
