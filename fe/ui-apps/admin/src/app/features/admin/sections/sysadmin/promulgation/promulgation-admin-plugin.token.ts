import { InjectionToken, Type } from '@angular/core';

/**
 * A registered per-service promulgation ADMIN plugin — the type drawer's
 * service-specific config section (NAVTEX transmitters, SafetyNET areas,
 * Twitter settings). Dispatched by the promulgation type's `serviceId`; a
 * serviceId with NO registered entry renders no plugin section at all — a
 * first-class case, not an error (some services, e.g. a country-added mailing
 * list, have no admin-side config). Mirrors `PROMULGATION_EDITORS` /
 * `PROMULGATION_RENDERERS` in shape, but unlike them it is not a country seam:
 * core registers every admin plugin and the country API does not export this
 * token (fe/docs/country-deployments.md, Configuration and behaviour).
 *
 * Provide the token at the app environment injector ONLY: multi-providers do
 * not merge across injector levels, so a route/component-level provider would
 * silently shadow the core registrations made there.
 */
export interface PromulgationAdminPlugin {
  readonly serviceId: string;
  readonly component: Type<unknown>;
}

export const PROMULGATION_ADMIN_PLUGINS = new InjectionToken<readonly PromulgationAdminPlugin[]>(
  'PROMULGATION_ADMIN_PLUGINS',
);

/**
 * The inputs every admin plugin component receives (`NgComponentOutlet` binds
 * inputs only): the persisted type's immutable `typeId` — the key of every
 * nested plugin endpoint — and its display `typeName` (for dialog titles).
 * The plugin section renders for persisted types only, so both are always
 * non-empty.
 */
export interface PromulgationAdminPluginInputs {
  readonly typeId: string;
  readonly typeName: string;
}
