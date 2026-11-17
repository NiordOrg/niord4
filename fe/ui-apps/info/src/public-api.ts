/**
 * The public site's country API, `@niord/info/api`: everything of the base product a country's
 * files may import (fe-country-split-plan §4.3). Country code imports the base product through
 * this file alone, and lint holds it to that; a guarded override is the one exception. Base
 * product code never imports it.
 *
 * Each export is a contract kept across base product versions. A country need it does not meet
 * is a new export here, with its own comment; a change that breaks one is recorded in
 * country-kit/API-CHANGES.md, and a removal is deprecated first, in a statement of its own whose
 * comment carries a line `Deprecated since <version>: <what to use instead>`. Only `export { … }
 * from` and `export type { … } from` statements, one per module and kind, each under its own
 * comment: the kit reads this file.
 */

/**
 * The shape of the country's `info/app/info.config.ts` (the info.config seam): its time zone,
 * root areas, footer component and the rest, and a root area's.
 */
export type { InfoConfig, RootAreaSpec } from './app/niord-info.config';

/** The shape of a language configuration. */
export type { LanguageConfig } from './app/core/config/language-config';

/**
 * The component-slot registry: `provideSlot(slot, () => import(…))` in the country's
 * `info/app/country.providers.ts` puts a country component into a slot the base product's
 * templates place.
 */
export { COMPONENT_SLOTS, provideSlot } from './app/shared/slots/component-slots';

/** The slots there are, and the shape of a registration. */
export type { SlotLoader, SlotName, SlotRegistration } from './app/shared/slots/component-slots';

/**
 * The site's configuration as it runs: the build's values, overwritten once by the deployment's
 * settings. A country footer reads the time zone and the accessibility statement from it.
 */
export { AppConfigService } from './app/core/services/app-config.service';

/** Opens one of the site's dialogs, fetching it first; a country footer opens the legal dialog. */
export { openInfoDialog } from './app/shared/components/dialog/open-dialog';

/** The calendar year in the deployment's time zone, for a copyright line. */
export { currentYearInZone } from './app/shared/utils/current-year';

/**
 * The legal dialog's loader, for `openInfoDialog(injector, loadLegalDialog, { documentId })`:
 * the country's legal documents (`info/assets/legal/<document>.<lang>.html`) in the site's
 * dialog, fetched when a reader opens one.
 */
export { loadLegalDialog } from './app/shared/components/legal-dialog/load-legal-dialog';

/**
 * The names the site stores in a reader's browser, each under the prefix: every cookie statement
 * a country ships (`info/assets/legal/cookies.<lang>.html`) names each of them, and set-up holds
 * it to that. For a country component that tells a reader what the site stores.
 */
export { PREFERENCE_KEYS, PREFERENCE_PREFIX } from './app/core/services/preferences.service';
