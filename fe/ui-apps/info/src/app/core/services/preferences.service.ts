import { Injectable } from '@angular/core';
import preferenceKeys from './preference-keys.json';

/** Everything the site remembers between visits sits under this prefix. */
export const PREFERENCE_PREFIX: string = preferenceKeys.prefix;

/**
 * The previous deployment wrote these same four names without the prefix, so each is read once
 * from there and removed as it is carried over, and a returning reader keeps the filter and the
 * language they set. Naming them as a type is what keeps the carry-over to the four: a fifth
 * preference must not pick up a bare entry that an unrelated page on the same origin left behind.
 */
type CarriedKey = 'language' | 'rootAreaId' | 'NW' | 'NM';

/**
 * What the site remembers, and all of it. The list lives in preference-keys.json so that the
 * country kit reads the same list: a country's set-up fails a cookie statement that does not name
 * each key with its prefix (country-kit/lib/branding.mjs). A further key is therefore an entry in
 * country-kit/API-CHANGES.md telling every country to name it in its statements.
 */
export const PREFERENCE_KEYS = preferenceKeys.keys as readonly CarriedKey[];

/**
 * What the public site remembers about a reader: the language they read in, the root area they
 * last chose, and which of the two message families they asked for.
 *
 * The prefix matters on an origin that may one day serve something else as well — `NW`, `NM`
 * and `language` are as collidable as names get — and it is cheap. `localStorage` throws in a
 * locked-down browser, so every access degrades to "no preference" rather than failing a render.
 */
@Injectable({ providedIn: 'root' })
export class PreferencesService {
  language(): string | undefined {
    return this.read('language') ?? undefined;
  }

  setLanguage(language: string): void {
    this.write('language', language);
  }

  rootAreaId(): string | undefined {
    return this.read('rootAreaId') ?? undefined;
  }

  setRootAreaId(id: string): void {
    this.write('rootAreaId', id);
  }

  /** Both families are on unless the reader turned one off — the previous deployment's meaning. */
  mainTypes(): { readonly NW: boolean; readonly NM: boolean } {
    return { NW: this.read('NW') !== 'false', NM: this.read('NM') !== 'false' };
  }

  setMainTypes(types: { readonly NW: boolean; readonly NM: boolean }): void {
    this.write('NW', String(types.NW));
    this.write('NM', String(types.NM));
  }

  private read(key: CarriedKey): string | null {
    try {
      const stored = localStorage.getItem(PREFERENCE_PREFIX + key);
      if (stored !== null) return stored;
      const carried = localStorage.getItem(key);
      if (carried === null) return null;
      localStorage.setItem(PREFERENCE_PREFIX + key, carried);
      localStorage.removeItem(key);
      return carried;
    } catch {
      return null;
    }
  }

  private write(key: CarriedKey, value: string): void {
    try {
      localStorage.setItem(PREFERENCE_PREFIX + key, value);
    } catch {
      // A browser with storage switched off remembers nothing between visits. That is the
      // whole effect: everything below still has a default to fall back on.
    }
  }
}
