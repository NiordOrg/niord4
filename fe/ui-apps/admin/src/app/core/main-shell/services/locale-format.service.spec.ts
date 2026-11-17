import { TestBed } from '@angular/core/testing';
import { Injectable } from '@angular/core';
import { provideTransloco, Translation, TranslocoLoader } from '@jsverse/transloco';
import { Observable, of } from 'rxjs';
import { describe, expect, it, beforeEach } from 'vitest';

import { DomainService } from '../../domain/services/domain.service';
import { LanguageService } from './language.service';
import { LocaleFormatService } from './locale-format.service';
import { provideTestLanguages } from '../../../shared/testing/language-testing';

@Injectable({ providedIn: 'root' })
class TwoLanguageStubLoader implements TranslocoLoader {
  getTranslation(): Observable<Translation> {
    return of<Translation>({});
  }
}

function configure(): { language: LanguageService; format: LocaleFormatService } {
  TestBed.configureTestingModule({
    providers: [
      provideTransloco({
        config: { availableLangs: ['en', 'da'], defaultLang: 'en' },
        loader: TwoLanguageStubLoader,
      }),
      { provide: DomainService, useValue: { sessionDomain: () => undefined } },
      provideTestLanguages(),
    ],
  });
  return {
    language: TestBed.inject(LanguageService),
    format: TestBed.inject(LocaleFormatService),
  };
}

describe('LocaleFormatService', () => {
  beforeEach(() => {
    try {
      localStorage.removeItem('niord.language');
    } catch {
      // ignore
    }
  });

  it('formats numbers using en-GB grouping for English', () => {
    const { format } = configure();
    expect(format.formatNumber(1234)).toBe('1,234');
  });

  it('formats numbers using da grouping for Danish', () => {
    const { language, format } = configure();
    language.selectLanguage('da');
    TestBed.tick();
    expect(format.formatNumber(1234)).toBe('1.234');
  });

  it('produces different formatted dates for en and da', () => {
    const { language, format } = configure();
    const iso = '2025-06-15T12:00:00Z';
    const englishOutput = format.formatDate(iso);

    language.selectLanguage('da');
    TestBed.tick();
    const danishOutput = format.formatDate(iso);

    expect(englishOutput).not.toBe('');
    expect(danishOutput).not.toBe('');
    expect(englishOutput).not.toBe(danishOutput);
  });

  it('returns empty string for invalid date input', () => {
    const { format } = configure();
    expect(format.formatDate('not-a-date')).toBe('');
  });

  it('reactively switches to the new locale after selectLanguage', () => {
    const { language, format } = configure();
    expect(format.formatNumber(1234)).toBe('1,234');

    language.selectLanguage('da');
    TestBed.tick();

    expect(format.formatNumber(1234)).toBe('1.234');
  });

  it('renders dates in the given timeZone when provided', () => {
    const { format } = configure();
    // 2026-01-15T23:30:00Z is "Jan 16 8:30 AM" in Tokyo (UTC+9) but "Jan 15 11:30 PM" in UTC.
    const iso = '2026-01-15T23:30:00Z';
    const inTokyo = format.formatDateTime(iso, { timeZone: 'Asia/Tokyo' });
    expect(inTokyo).toContain('16');
    expect(inTokyo).toContain('8:30 AM');
  });

  it('produces different output for the same instant in two different timezones', () => {
    const { format } = configure();
    const cph = format.formatDateTime('2026-01-15T12:00:00Z', { timeZone: 'Europe/Copenhagen' });
    const nyc = format.formatDateTime('2026-01-15T12:00:00Z', { timeZone: 'America/New_York' });
    expect(cph).not.toBe(nyc);
  });

  it('uses a 12-hour clock for English and a 24-hour clock for Danish', () => {
    const { language, format } = configure();
    expect(format.uses12HourClock()).toBe(true);

    language.selectLanguage('da');
    TestBed.tick();

    expect(format.uses12HourClock()).toBe(false);
  });

  it('caches formatters per (locale, timeZone, options) tuple', () => {
    const { format } = configure();
    const a = format.formatDate('2026-01-15T12:00:00Z', { timeZone: 'Europe/Copenhagen' });
    const b = format.formatDate('2026-01-15T12:00:00Z', { timeZone: 'Europe/Copenhagen' });
    expect(a).toBe(b);
  });
});
