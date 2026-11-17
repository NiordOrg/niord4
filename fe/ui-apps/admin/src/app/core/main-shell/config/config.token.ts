import { COUNTRY_LANGUAGES } from '../../../country-specific/default/language.config';
import type { LanguageConfig } from '../../config/app-config.interface';

export type { LanguageConfig } from '../../config/app-config.interface';

/**
 * The build's language configuration: the country-specific `language.config.ts` that
 * `angular.json` fileReplacements swap in per build configuration. The base product's own
 * build is English only; a country's file names its languages, their ORDER (every
 * per-language surface renders in list order), the UI bundles and branding it ships.
 */
export const LANGUAGES: LanguageConfig = COUNTRY_LANGUAGES;
