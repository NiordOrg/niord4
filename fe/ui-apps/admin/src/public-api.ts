/**
 * The administration app's country API, `@niord/admin/api`: everything of the base product a
 * country's files may import (fe-country-split-plan §4.3). Country code imports the base product
 * through this file alone, and lint holds it to that; a guarded override is the one exception.
 * Base product code never imports it.
 *
 * Each export is a contract kept across base product versions. A country need it does not meet
 * is a new export here, with its own comment; a change that breaks one is recorded in
 * country-kit/API-CHANGES.md, and a removal is deprecated first, in a statement of its own whose
 * comment carries a line `Deprecated since <version>: <what to use instead>`. Only `export { … }
 * from` and `export type { … } from` statements, one per module and kind, each under its own
 * comment: the kit reads this file.
 *
 * The Example country's fixture (country-kit/example/acceptance.mjs, fixtureZip) marks roleGuard
 * deprecated by adding lines to the end of the comment above its export: keep that comment's
 * closing line directly above `export { roleGuard }`.
 */

/**
 * The shape of the country's environment files (`admin/environments/environment.<name>.ts`,
 * the environment seams) and of a language configuration.
 */
export type { AppConfig, LanguageConfig } from './app/core/config/app-config.interface';

/**
 * The hook a country's `provideCountry()` adds a promulgation renderer to, for a service of its
 * own: `{ provide: PROMULGATION_RENDERERS, useValue: { serviceId, component }, multi: true }`.
 */
export { PROMULGATION_RENDERERS } from './app/features/messages/promulgation/promulgation-renderer.token';

/**
 * A renderer's registration, and the promulgation entry its component receives as its
 * `promulgation` input.
 */
export type {
  BasePromulgation,
  PromulgationRenderer,
} from './app/features/messages/promulgation/promulgation-renderer.token';

/** The hook a country's `provideCountry()` adds a promulgation editor to, as the renderers. */
export { PROMULGATION_EDITORS } from './app/features/editor/promulgation/promulgation-editor.token';

/**
 * An editor's registration, and the context its component receives as its `context` input
 * beside the promulgation entry.
 */
export type {
  PromulgationEditor,
  PromulgationEditorContext,
} from './app/features/editor/promulgation/promulgation-editor.token';

/** A message's status, which `PromulgationEditorContext.messageStatus` carries. */
export type { MessageStatus } from './app/features/messages/models/message.model';

/**
 * The component-slot registry: `provideSlot(slot, () => import(…))` in `provideCountry()` puts a
 * country component into a slot the base product's templates place.
 */
export { COMPONENT_SLOTS, provideSlot } from './app/shared/slots/component-slots';

/** The slots there are, and the shape of a registration. */
export type { SlotLoader, SlotName, SlotRegistration } from './app/shared/slots/component-slots';

/** An entry of the country's menu (`admin/app/country.menu.ts`), and the items it may follow. */
export type { CountryMenuEntry, MenuAnchor } from './app/core/main-shell/country-menu';

/**
 * The guard every top-level country route carries in `canMatch`, with a non-empty
 * `data.rolesRequired` (`admin/app/country.routes.ts`; fe-country-split-plan §4.11 rule 3).
 */
export { roleGuard } from './app/core/auth/guards/role.guard';

/** The roles of the base product's areas, for a country route's or menu entry's roles. */
export {
  ADMIN_AREA_ROLES,
  EDITOR_AREA_ROLES,
  EDITOR_VIEW_ROLES,
  SYSADMIN_AREA_ROLES,
} from './app/core/auth/area-roles';

/** A role a country route or menu entry names. */
export type { AuthRole } from './app/core/auth/facades/auth.facade';

/** Plain text shown as HTML: escaped, its line breaks kept. A country template's `[innerHTML]`. */
export { PlainTextToHtmlPipe } from './app/shared/pipes/plain-text-to-html.pipe';
