/**
 * Persistence of the Insert-Time dialog's parameters in the
 * `localStorage['formatTimeSettings']` JSON. The only persisted parameter is the
 * "include time zone" toggle; the shape is `{ "tz": boolean }`.
 */

const STORAGE_KEY = 'formatTimeSettings';

export interface FormatTimeSettings {
  /** Append the time-zone abbreviation to each formatted interval. */
  readonly tz: boolean;
}

const DEFAULTS: FormatTimeSettings = { tz: true };

/** Reads the persisted settings, falling back to the defaults on any error. */
export function readFormatTimeSettings(): FormatTimeSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return DEFAULTS;
    }
    const parsed = JSON.parse(raw) as Partial<FormatTimeSettings>;
    return { tz: typeof parsed.tz === 'boolean' ? parsed.tz : DEFAULTS.tz };
  } catch {
    return DEFAULTS;
  }
}

/** Persists the settings (written on Insert). Storage errors are swallowed. */
export function writeFormatTimeSettings(settings: FormatTimeSettings): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ tz: settings.tz }));
  } catch {
    // Ignore storage errors — the choice still applies for the current session.
  }
}
