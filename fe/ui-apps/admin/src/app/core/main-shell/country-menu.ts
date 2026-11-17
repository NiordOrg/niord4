import { InjectionToken } from '@angular/core';

import type { AuthRole } from '../auth/facades/auth.facade';

/** The base menu items a country entry can follow, in the menu's own order. */
export type MenuAnchor = 'messages' | 'editor' | 'schedule' | 'admin' | 'sysadmin';

/** One entry a country adds to the main menu. */
export interface CountryMenuEntry {
  /** The base item it follows; it keeps its place when that item is hidden from the user. */
  readonly after: MenuAnchor;
  /** Its label, a key of the country's own `country.*` texts. */
  readonly labelKey: `country.${string}`;
  /**
   * A registered `@ng-icons` name. A country registers its own glyphs with `provideIcons` in its
   * country.providers.ts.
   */
  readonly icon: string;
  /** The page it opens, normally one of the country's own routes. */
  readonly routerLink: string;
  /** The roles that see it, any one of them; an empty list shows it to everyone, anonymous included. */
  readonly roles: readonly AuthRole[];
}

/** The country's menu entries, in the order its country.menu.ts lists them. */
export const COUNTRY_MENU = new InjectionToken<readonly CountryMenuEntry[]>('COUNTRY_MENU', {
  providedIn: 'root',
  factory: () => [],
});
