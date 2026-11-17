import type { Provider } from '@angular/core';

import type { LanguageConfig } from '../../core/config/app-config.interface';
import { LANGUAGE_CONFIG } from '../../core/i18n/language-settings.service';

/**
 * An English-and-Danish build. The base product's own build is English only, so a spec that
 * switches to Danish, or formats in a Danish locale, gives the language settings this one.
 */
export const TEST_LANGUAGES: LanguageConfig = {
  availableLanguages: [
    { label: 'English', value: 'en', locale: 'en-GB', flagPath: 'assets/img/flags/en.png' },
    { label: 'Dansk', value: 'da', locale: 'da', flagPath: 'assets/img/flags/da.png' },
  ],
  uiLanguages: ['en', 'da'],
  defaultLanguage: 'en',
};

export function provideTestLanguages(config: LanguageConfig = TEST_LANGUAGES): Provider {
  return { provide: LANGUAGE_CONFIG, useValue: config };
}
