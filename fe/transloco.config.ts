import { TranslocoGlobalConfig } from '@jsverse/transloco-utils';

/**
 * Tooling-only configuration: it points the Transloco keys-manager CLI at the administration
 * app's English bundle in the `en` language pack, the bundle every key is written in first. Every
 * other language is a pack of its own under `languages/<lang>/` and is never listed here. The
 * languages a build offers are not set here either: a country workspace takes them from its
 * manifest, `fe/niord-country.json`, and the base product's own builds from each application's
 * `country-specific/<country>/language.config.ts`. Never point this file at the staged copies
 * under `.niord/build/`: the staging step rewrites them on every build.
 */
const config: TranslocoGlobalConfig = {
  rootTranslationsPath: 'languages/en/i18n/admin/',
  langs: ['en'],
  keysManager: {},
};

export default config;
