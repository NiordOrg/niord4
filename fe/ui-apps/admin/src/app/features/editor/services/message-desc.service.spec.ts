import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import type { MessageDescription } from '../../messages/models/message.model';
import { MessageDescService } from './message-desc.service';

function service(): MessageDescService {
  TestBed.configureTestingModule({ providers: [MessageDescService] });
  return TestBed.inject(MessageDescService);
}

describe('MessageDescService', () => {
  const en: MessageDescription = { lang: 'en', title: 'EN' };
  const da: MessageDescription = { lang: 'da', title: 'DA' };

  describe('descForLanguage', () => {
    it('returns the matching desc', () => {
      expect(service().descForLanguage([en, da], 'da')).toBe(da);
    });

    it('returns undefined for an absent language', () => {
      expect(service().descForLanguage([en], 'da')).toBeUndefined();
    });

    it('tolerates undefined descs', () => {
      expect(service().descForLanguage(undefined, 'en')).toBeUndefined();
    });
  });

  describe('descForLangOrDefault', () => {
    it('falls back to the first desc when the language is absent', () => {
      expect(service().descForLangOrDefault([en, da], 'fr')).toBe(en);
    });

    it('returns undefined for empty descs', () => {
      expect(service().descForLangOrDefault([], 'en')).toBeUndefined();
    });
  });

  describe('sortMessageDescs', () => {
    it('orders by the languages list without mutating the source', () => {
      const source: MessageDescription[] = [da, en];
      const sorted = service().sortMessageDescs(source, ['en', 'da']);

      expect(sorted.map((d) => d.lang)).toEqual(['en', 'da']);
      expect(source.map((d) => d.lang)).toEqual(['da', 'en']); // source untouched
      expect(sorted).not.toBe(source);
    });

    it('keeps unknown-language descs at the end', () => {
      const fr: MessageDescription = { lang: 'fr' };
      const sorted = service().sortMessageDescs([fr, en], ['en', 'da']);
      expect(sorted.map((d) => d.lang)).toEqual(['en', 'fr']);
    });
  });

  describe('checkDescs', () => {
    it('ensures one desc per language, reusing existing and creating the missing ones', () => {
      const result = service().checkDescs([en], ['en', 'da'], (lang) => ({ lang }));
      expect(result.map((d) => d.lang)).toEqual(['en', 'da']);
      expect(result[0]).toBe(en); // reused
      expect(result[1]).toEqual({ lang: 'da' }); // created
    });
  });
});
