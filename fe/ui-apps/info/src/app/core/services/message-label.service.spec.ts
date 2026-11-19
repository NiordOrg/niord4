import { TestBed } from '@angular/core/testing';
import { MessageLabelService } from './message-label.service';
import { provideTestNiordConfig, provideTranslocoSpy } from '../../testing/test-config';
import { Desc } from '../models/message.model';

describe('MessageLabelService', () => {
  let service: MessageLabelService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideTestNiordConfig(), provideTranslocoSpy()],
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
      expect(badge.shortId).toBe('XX-001');
      expect(badge.typeKey).toBeUndefined();
      expect(badge.mainTypeKey).toBeUndefined();
      expect(badge.status).toBeUndefined();
      expect(badge.suffix).toBeUndefined();
    });

    // Keys, not words: the badge translates them where it renders them, in the language on screen.
    it('names a message with no short id by the keys of its type and main type', () => {
      const badge = service.badge({ id: '1', mainType: 'NW', type: 'TEMPORARY_NOTICE' });
      expect(badge.shortId).toBeUndefined();
      expect(badge.typeKey).toBe('TYPE_TEMPORARY_NOTICE');
      expect(badge.mainTypeKey).toBe('MAIN_TYPE_NW');
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
      expect(badge.status).toEqual({ kind: 'EXPIRED', key: 'STATUS_EXPIRED' });
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
      expect(badge.status).toEqual({ kind: 'CANCELLED', key: 'STATUS_CANCELLED' });
    });

    it('leaves the status out unless the caller asks for it', () => {
      const badge = service.badge({ id: '1', mainType: 'NW', shortId: 'X', status: 'CANCELLED' });
      expect(badge.status).toBeUndefined();
    });
  });
});
