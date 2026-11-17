/**
 * The administration app's country API for specs, `@niord/admin/api/testing`: the test helpers a
 * country's specs may import (fe-country-split-plan §4.3). A test helper belongs in no build, so
 * only a spec may import this entry, which lint holds country code to, and the application's own
 * tsconfig leaves it out. The same form and the same compatibility policy as `@niord/admin/api`
 * (src/public-api.ts, country-kit/API-CHANGES.md).
 */

/** Transloco for a spec, in two languages and with no translations: a component renders its keys. */
export { provideTestTransloco } from './app/shared/testing/transloco-testing';

/**
 * The base product's rule for a top-level country route, as a list of problems: a country's
 * `country.routes.spec.ts` asserts that it is empty, so no route of its own is left out.
 */
export { countryRouteProblems } from './app/core/country/country-routes';
