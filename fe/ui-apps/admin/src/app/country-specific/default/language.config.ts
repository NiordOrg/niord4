import type { LanguageConfig } from '../../core/config/app-config.interface';

/**
 * The base product's language configuration: English only, for no country. It ships the
 * English UI bundle and no branding, so its build requests no branding file.
 *
 * A country workspace swaps this file through fileReplacements for the language configuration
 * its set-up generates from the manifest. The list ORDER is the canonical model-language order —
 * every per-language field group, preview pill row and desc padding renders in it.
 */
export const COUNTRY_LANGUAGES: LanguageConfig = {
  availableLanguages: [
    {
      label: 'English',
      value: 'en',
      locale: 'en-GB',
      flagPath: 'assets/img/flags/en.png',
    },
  ],
  uiLanguages: ['en'],
  defaultLanguage: 'en',
};
