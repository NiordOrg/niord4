import { TestBed } from '@angular/core/testing';
import { Injectable, signal } from '@angular/core';
import { provideTransloco, Translation, TranslocoLoader } from '@jsverse/transloco';
import { Observable, of } from 'rxjs';

import { APP_CONFIG } from '../../../core/config/app-config.token';
import { DomainService } from '../../../core/domain/services/domain.service';
import type { Domain } from '../../../core/domain/models/domain.model';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import { MessageDateFormatterService } from './message-date-formatter.service';
import { provideTestLanguages } from '../../../shared/testing/language-testing';

@Injectable({ providedIn: 'root' })
class TwoLanguageStubLoader implements TranslocoLoader {
  getTranslation(): Observable<Translation> {
    return of<Translation>({});
  }
}

describe('MessageDateFormatterService', () => {
  let service: MessageDateFormatterService;

  beforeEach(() => {
    try {
      localStorage.removeItem('niord.language');
    } catch {
      // ignore
    }
    TestBed.configureTestingModule({
      providers: [
        provideTestLanguages(),
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
        // LocaleFormatService (used transitively) injects DomainService for the session-domain
        // timezone. Stub it with a writable signal so the timezone tests can drive it, and so the
        // real DomainService's HttpClient/API chain never needs to be provided here.
        {
          provide: DomainService,
          useFactory: () => ({ sessionDomain: signal<Domain | undefined>(undefined) }),
        },
        provideTransloco({
          config: { availableLangs: ['en', 'da'], defaultLang: 'en' },
          loader: TwoLanguageStubLoader,
        }),
      ],
    });
    service = TestBed.inject(MessageDateFormatterService);
  });

  describe('formatDateInterval', () => {
    it('should format from and to dates', () => {
      const result = service.formatDateInterval({
        fromDate: '2025-01-15T10:00:00Z',
        toDate: '2025-01-20T18:00:00Z',
      });
      expect(result).toContain('–');
      expect(result).toContain('2025');
    });

    it('should format only from date', () => {
      const result = service.formatDateInterval({ fromDate: '2025-03-01T08:00:00Z' });
      expect(result).toContain('2025');
    });

    it('should format only to date', () => {
      const result = service.formatDateInterval({ toDate: '2025-06-15T12:00:00Z' });
      expect(result).toContain('2025');
    });

    it('should return empty for no dates', () => {
      expect(service.formatDateInterval({})).toBe('');
    });

    it('should return empty for invalid date string', () => {
      expect(service.formatDateInterval({ fromDate: 'not-a-date' })).toBe('');
    });

    it('produces different output for en vs da locales', () => {
      const interval = { fromDate: '2025-06-15T10:00:00Z' };
      const englishOutput = service.formatDateInterval(interval);

      const language = TestBed.inject(LanguageService);
      language.selectLanguage('da');
      TestBed.tick();

      const danishOutput = service.formatDateInterval(interval);

      expect(englishOutput).not.toBe('');
      expect(danishOutput).not.toBe('');
      expect(englishOutput).not.toBe(danishOutput);
    });
  });

  describe('formatEventDates', () => {
    it('should format multiple intervals', () => {
      const result = service.formatEventDates([
        { fromDate: '2025-01-01T00:00:00Z', toDate: '2025-01-02T00:00:00Z' },
        { fromDate: '2025-02-01T00:00:00Z' },
      ]);
      expect(result).toContain(',');
    });

    it('should return empty for undefined', () => {
      expect(service.formatEventDates(undefined)).toBe('');
    });

    it('should return empty for empty array', () => {
      expect(service.formatEventDates([])).toBe('');
    });
  });

  describe('formatForColumnType', () => {
    it('DATE flattens eventDates across all parts', () => {
      const result = service.formatForColumnType(
        {
          parts: [
            { eventDates: [{ fromDate: '2025-01-01T00:00:00Z' }] },
            { eventDates: [{ fromDate: '2025-02-01T00:00:00Z' }] },
          ],
        } as never,
        'DATE',
        false,
      );
      expect(result).toContain(',');
      expect(result).toContain('2025');
    });

    it('DATE with firstLine shows only the first interval', () => {
      const result = service.formatForColumnType(
        {
          parts: [
            {
              eventDates: [
                { fromDate: '2025-01-15T00:00:00Z' },
                { fromDate: '2025-08-20T00:00:00Z' },
              ],
            },
          ],
        } as never,
        'DATE',
        true,
      );
      // Only the first interval (Jan) renders — the second (Aug) is dropped.
      expect(result).toContain('Jan');
      expect(result).not.toContain('Aug');
    });

    it('PUBLISH_DATE builds an interval from the publish fields, empty when absent', () => {
      expect(
        service.formatForColumnType(
          {
            publishDateFrom: '2025-03-01T08:00:00Z',
            publishDateTo: '2025-03-05T08:00:00Z',
          } as never,
          'PUBLISH_DATE',
          false,
        ),
      ).toContain('–');
      expect(service.formatForColumnType({} as never, 'PUBLISH_DATE', false)).toBe('');
    });

    it('FOLLOW_UP_DATE builds an all-day interval, empty when absent', () => {
      expect(
        service.formatForColumnType(
          { followUpDate: '2025-06-15T00:00:00Z' } as never,
          'FOLLOW_UP_DATE',
          false,
        ),
      ).toContain('2025');
      expect(service.formatForColumnType({} as never, 'FOLLOW_UP_DATE', false)).toBe('');
    });
  });

  describe('isOverdue', () => {
    it('should return true for past date', () => {
      expect(service.isOverdue('2020-01-01T00:00:00Z')).toBe(true);
    });

    it('should return false for future date', () => {
      expect(service.isOverdue('2099-12-31T00:00:00Z')).toBe(false);
    });

    it('should return false for undefined', () => {
      expect(service.isOverdue(undefined)).toBe(false);
    });
  });

  describe('domain timezone', () => {
    it('renders the date in the active session domain timezone', async () => {
      const domain = TestBed.inject(DomainService);
      // 2026-01-15T23:30:00Z is "Jan 16 08:30" in Tokyo (UTC+9), Jan 15 in UTC.
      domain.sessionDomain.set({ domainId: 'tokyo-test', timeZone: 'Asia/Tokyo' } as Domain);

      const result = service.formatDateInterval({ fromDate: '2026-01-15T23:30:00Z' });
      expect(result).toContain('16'); // Jan 16 in Tokyo
      expect(result).toContain('8:30 AM'); // 23:30Z + 9h, en-GB 12h clock
    });

    it('falls back to browser timezone when domain has no timeZone', () => {
      const domain = TestBed.inject(DomainService);
      domain.sessionDomain.set({ domainId: 'no-tz' } as Domain);

      const result = service.formatDateInterval({ fromDate: '2026-01-15T12:00:00Z' });
      expect(result).not.toBe('');
    });

    it('produces different output for the same instant in two domains with different timezones', () => {
      const domain = TestBed.inject(DomainService);
      const iso = '2026-01-15T12:00:00Z';

      domain.sessionDomain.set({ domainId: 'cph', timeZone: 'Europe/Copenhagen' } as Domain);
      const cph = service.formatDateInterval({ fromDate: iso });

      domain.sessionDomain.set({ domainId: 'nyc', timeZone: 'America/New_York' } as Domain);
      const nyc = service.formatDateInterval({ fromDate: iso });

      expect(cph).not.toBe(nyc);
    });
  });
});
