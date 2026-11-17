import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import type { LanguageOption } from '../../../../shared/models/main.models';
import { LanguageService } from '../../services/language.service';
import { LanguageMenuService } from './language-menu.service';

function menuFor(uiLanguages: readonly LanguageOption[], selected: string) {
  TestBed.configureTestingModule({
    providers: [
      {
        provide: LanguageService,
        useValue: {
          uiLanguages,
          selectedLanguage: signal(selected),
          selectLanguage: () => undefined,
        },
      },
    ],
  });
  return TestBed.inject(LanguageMenuService).menuItem();
}

describe('LanguageMenuService', () => {
  it('shows each language by its flag', () => {
    const item = menuFor(
      [{ value: 'da', label: 'Dansk', locale: 'da', flagPath: 'assets/img/flags/da.png' }],
      'da',
    );
    expect(item.iconStyle).toEqual({ 'background-image': "url('assets/img/flags/da.png')" });
    expect(item.items?.[0].icon).toBeTruthy();
  });

  it('sets no flag style for a language the build ships no flag for', () => {
    const item = menuFor(
      [
        { value: 'en', label: 'English', locale: 'en-GB', flagPath: 'assets/img/flags/en.png' },
        { value: 'fo', label: 'FO', locale: 'fo' },
      ],
      'fo',
    );
    expect(item.label).toBe('FO');
    expect(item.icon).toBeUndefined();
    expect(item.iconStyle).toBeNull();
    expect(item.items?.[1]).toMatchObject({ label: 'FO', icon: undefined, iconStyle: null });
    expect(item.items?.[0].iconStyle).toEqual({
      'background-image': "url('assets/img/flags/en.png')",
    });
  });
});
