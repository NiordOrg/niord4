import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it } from 'vitest';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import { ModelLanguageSelection } from './model-language-selection';

function setup() {
  const ui = signal('en');
  TestBed.configureTestingModule({
    providers: [
      ModelLanguageSelection,
      {
        provide: LanguageService,
        useValue: {
          selectedLanguage: ui,
          defaultLanguage: 'da',
          modelLanguages: [
            { value: 'da', label: 'Dansk' },
            { value: 'en', label: 'English' },
            { value: 'kl', label: 'Kalaallisut' },
          ],
        },
      },
    ],
  });
  return { selection: TestBed.inject(ModelLanguageSelection), ui };
}

describe('ModelLanguageSelection', () => {
  it('starts with the UI language and keeps an explicit model choice independent of it', () => {
    const { selection, ui } = setup();
    expect(selection.value()).toBe('en');
    selection.select('kl');
    expect(ui()).toBe('en');
    ui.set('da');
    expect(selection.value()).toBe('kl');
    selection.select('unknown');
    expect(selection.value()).toBe('kl');
  });

  it('uses default text for missing KL fields without changing the editable descriptions', () => {
    const { selection } = setup();
    const descs = [
      { lang: 'en', name: 'English' },
      { lang: 'da', name: 'Dansk' },
      { lang: 'kl', name: '' },
    ];
    selection.select('kl');
    expect(selection.text(descs, 'name')).toEqual({ value: 'Dansk', lang: 'da' });
    expect(descs[2].name).toBe('');
  });

  it('identifies each field by its own source language rather than the merged description language', () => {
    const { selection } = setup();
    selection.select('kl');
    const descs = [
      { lang: 'en', name: 'English', abbreviation: 'EN' },
      { lang: 'da', name: 'Dansk', abbreviation: '' },
      { lang: 'kl', name: ' ', abbreviation: 'KL' },
    ];
    expect(selection.text(descs, 'name')).toEqual({ value: 'Dansk', lang: 'da' });
    expect(selection.text(descs, 'abbreviation')).toEqual({ value: 'KL', lang: 'kl' });
    expect(selection.text(descs.slice(0, 1), 'name')).toEqual({ value: 'English', lang: 'en' });
    expect(selection.text([], 'name')).toEqual({ value: '', lang: undefined });
  });

  it('uses the default when the UI language is outside the model languages', () => {
    const { selection, ui } = setup();
    ui.set('fr');
    expect(selection.value()).toBe('da');
  });
});
