import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import { MessageDescriptionService } from './message-description.service';

describe('MessageDescriptionService language fallback', () => {
  it('fills missing display fields from the configured default without changing stored descriptions', () => {
    TestBed.configureTestingModule({
      providers: [
        {
          provide: LanguageService,
          useValue: { defaultLanguage: 'da', selectedLanguage: () => 'kl' },
        },
      ],
    });
    const service = TestBed.inject(MessageDescriptionService);
    const descs = [
      { lang: 'en', title: 'English', description: 'English body' },
      { lang: 'da', title: 'Dansk', description: 'Dansk tekst' },
      { lang: 'kl', title: 'Kalaallisut', description: '' },
    ];
    const before = structuredClone(descs);
    expect(service.getDescription(descs)).toMatchObject({
      title: 'Kalaallisut',
      description: 'Dansk tekst',
    });
    expect(service.getTitle(descs, 'fo')).toBe('Dansk');
    expect(descs).toEqual(before);
  });
});
