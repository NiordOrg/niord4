import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { MessageLabelService } from './message-label.service';
import { provideTestNiordConfig } from '../../testing/test-config';
import { Desc } from '../models/message.model';

describe('MessageLabelService', () => {
  let service: MessageLabelService;
  let translocoSpy: {
    translate: ReturnType<typeof vi.fn>;
    setActiveLang: ReturnType<typeof vi.fn>;
    setAvailableLangs: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    translocoSpy = {
      translate: vi.fn((key: string) => key),
      setActiveLang: vi.fn(),
      setAvailableLangs: vi.fn(),
    };

    TestBed.configureTestingModule({
      providers: [provideTestNiordConfig(), { provide: TranslocoService, useValue: translocoSpy }],
    });
    service = TestBed.inject(MessageLabelService);
  });

  describe('desc()', () => {
    it('should return desc matching language', () => {
      const descs: Desc[] = [
        { lang: 'da', title: 'Danish' },
        { lang: 'en', title: 'English' },
      ];
      expect(service.desc({ descs }, 'en')?.title).toBe('English');
    });

    it('should fall back to first desc when no lang match', () => {
      const descs: Desc[] = [{ lang: 'da', title: 'Danish' }];
      expect(service.desc({ descs }, 'fr')?.title).toBe('Danish');
    });

    it('should return undefined for empty descs', () => {
      expect(service.desc({ descs: [] })).toBeUndefined();
    });

    it('should return undefined for undefined input', () => {
      expect(service.desc(undefined)).toBeUndefined();
    });

    it('should use LanguageService default when no lang passed', () => {
      const descs: Desc[] = [
        { lang: 'en', title: 'English' },
        { lang: 'da', title: 'Danish' },
      ];
      expect(service.desc({ descs })?.title).toBe('English');
    });
  });

  describe('badge()', () => {
    it('names the message by its short id', () => {
      const badge = service.badge({ id: '1', mainType: 'NW', shortId: 'XX-001' });
      expect(badge.label).toBe('XX-001');
      expect(badge.status).toBeUndefined();
      expect(badge.suffix).toBeUndefined();
    });

    it('composes type and main type when the message has no short id', () => {
      const badge = service.badge({ id: '1', mainType: 'NW', type: 'TEMPORARY_NOTICE' });
      expect(translocoSpy.translate).toHaveBeenCalledWith('TYPE_TEMPORARY_NOTICE');
      expect(translocoSpy.translate).toHaveBeenCalledWith('MAIN_TYPE_NW');
      expect(badge.label).toBe('TYPE_TEMPORARY_NOTICE MAIN_TYPE_NW');
    });

    it('marks a temporary notice (T) and a preliminary one (P)', () => {
      expect(
        service.badge({ id: '1', mainType: 'NW', shortId: 'X', type: 'TEMPORARY_NOTICE' }).suffix,
      ).toBe('(T)');
      expect(
        service.badge({ id: '1', mainType: 'NW', shortId: 'X', type: 'PRELIMINARY_NOTICE' }).suffix,
      ).toBe('(P)');
    });

    it('names an expired notice when the caller asks for the status', () => {
      const badge = service.badge(
        { id: '1', mainType: 'NW', shortId: 'X', status: 'EXPIRED', type: 'TEMPORARY_NOTICE' },
        true,
      );
      expect(badge.status).toEqual({ kind: 'EXPIRED', label: 'STATUS_EXPIRED' });
    });

    // A permanent notice that has run out is the ordinary end of its life.
    it('does not name an expired permanent notice', () => {
      const badge = service.badge(
        { id: '1', mainType: 'NW', shortId: 'X', status: 'EXPIRED', type: 'PERMANENT_NOTICE' },
        true,
      );
      expect(badge.status).toBeUndefined();
    });

    it('names a cancellation', () => {
      const badge = service.badge(
        { id: '1', mainType: 'NW', shortId: 'X', status: 'CANCELLED' },
        true,
      );
      expect(badge.status).toEqual({ kind: 'CANCELLED', label: 'STATUS_CANCELLED' });
    });

    it('leaves the status out unless the caller asks for it', () => {
      const badge = service.badge({ id: '1', mainType: 'NW', shortId: 'X', status: 'CANCELLED' });
      expect(badge.status).toBeUndefined();
    });
  });
});
