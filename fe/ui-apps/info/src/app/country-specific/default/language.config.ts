import type { LanguageConfig } from '../../core/config/language-config';

/**
 * The base product's language configuration: English only. A country workspace replaces this
 * file through `fileReplacements` with the one its set-up generates from the manifest; the
 * `siteLanguages` ORDER is the order of the language switcher.
 */
export const COUNTRY_LANGUAGES: LanguageConfig = {
  languages: [
    { value: 'en', label: 'English', locale: 'en-GB', flagPath: 'assets/img/flags/en.png' },
  ],
  siteLanguages: ['en'],
  defaultLanguage: 'en',
  // The base build ships no branding file, so it asks for none.
  brandingLanguages: [],
};
