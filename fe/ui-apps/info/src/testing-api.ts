/**
 * The public site's country API for specs, `@niord/info/api/testing`: the test helpers a
 * country's specs may import (fe-country-split-plan §4.3). A test helper belongs in no build,
 * and these are typed against Vitest's globals, so only a spec may import this entry, which lint
 * holds country code to, and the application's own tsconfig leaves it out. The same form and the
 * same compatibility policy as `@niord/info/api` (src/public-api.ts, country-kit/API-CHANGES.md).
 */

/**
 * Transloco for a spec, with the base product's English and the branding given per language
 * laid over it; and the site's configuration, with the fields given replaced.
 */
export { getTranslocoTestingModule, provideTestNiordConfig } from './app/testing/test-config';
