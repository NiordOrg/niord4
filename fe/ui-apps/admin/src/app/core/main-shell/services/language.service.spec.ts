import { TestBed } from '@angular/core/testing';
import { Injectable } from '@angular/core';
import {
  provideTransloco,
  Translation,
  TranslocoLoader,
  TranslocoService,
} from '@jsverse/transloco';
import { Observable, of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { LanguageService } from './language.service';
import { provideTestLanguages, TEST_LANGUAGES } from '../../../shared/testing/language-testing';
import type { LanguageConfig } from '../../config/app-config.interface';

@Injectable({ providedIn: 'root' })
class StubTranslocoLoader implements TranslocoLoader {
  getTranslation(): Observable<Translation> {
    return of<Translation>({});
  }
}

function setup(languages: LanguageConfig = TEST_LANGUAGES): {
  service: LanguageService;
  transloco: TranslocoService;
} {
  TestBed.configureTestingModule({
    providers: [
      provideTransloco({
        config: {
          availableLangs: [...languages.uiLanguages],
          defaultLang: languages.defaultLanguage,
        },
        loader: StubTranslocoLoader,
      }),
      provideTestLanguages(languages),
    ],
  });
  // Injecting the service runs its constructor, which sets the active language to the
  // persisted/default choice ('en' here) BEFORE any spy is installed below.
  const service = TestBed.inject(LanguageService);
  const transloco = TestBed.inject(TranslocoService);
  return { service, transloco };
}

describe('LanguageService — bundle-load error branch', () => {
  afterEach(() => vi.restoreAllMocks());

  it('offers Transloco the configured languages only, with no fallback language', () => {
    const { service, transloco } = setup({
      availableLanguages: [
        { label: 'Dansk', value: 'da', locale: 'da' },
        { label: 'Kalaallisut', value: 'kl', locale: 'kl' },
      ],
      uiLanguages: ['da'],
      defaultLanguage: 'da',
    });
    service.configure();
    expect(transloco.getAvailableLangs()).toEqual(['da', 'kl']);
    expect(transloco.getDefaultLang()).toBe('da');
    expect(transloco.config.fallbackLang ?? []).toEqual([]);
    // Every shipped bundle is complete; a key one lacks is not filled from another language.
    transloco.setTranslation({ greeting: 'Dansk' }, 'da');
    transloco.setActiveLang('da');
    expect(transloco.translate('onlyEnglish')).toBe('onlyEnglish');
  });

  it('leaves the active language unchanged and logs when the bundle load errors', () => {
    const { service, transloco } = setup();
    expect(service.selectedLanguage()).toBe('en');

    const setActiveLang = vi.spyOn(transloco, 'setActiveLang');
    const load = vi
      .spyOn(transloco, 'load')
      .mockReturnValue(throwError(() => new Error('boom')) as never);
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);

    service.selectLanguage('da');

    // The load was attempted for the requested language…
    expect(load).toHaveBeenCalledWith('da');
    // …but its failure is surfaced (not swallowed) and the switch is abandoned:
    expect(consoleError).toHaveBeenCalledWith(expect.stringContaining('"da"'), expect.any(Error));
    expect(setActiveLang).not.toHaveBeenCalled();
    expect(service.selectedLanguage()).toBe('en');
    // A failed load must not persist the (unapplied) choice.
    expect(localStorage.getItem('niord.language')).toBeNull();
  });

  it('does not attempt a load for an unknown language (guarded before the pipeline)', () => {
    const { service, transloco } = setup();
    const load = vi.spyOn(transloco, 'load');

    service.selectLanguage('zz');

    expect(load).not.toHaveBeenCalled();
    expect(service.selectedLanguage()).toBe('en');
  });

  it('control: a successful load flips the language and persists it (proves the error path is what blocks the switch)', () => {
    const { service } = setup();
    expect(service.selectedLanguage()).toBe('en');

    // No load spy — the stub loader resolves synchronously, so the switch applies.
    service.selectLanguage('da');
    TestBed.tick();

    expect(service.selectedLanguage()).toBe('da');
    expect(localStorage.getItem('niord.language')).toBe('da');
  });
});
