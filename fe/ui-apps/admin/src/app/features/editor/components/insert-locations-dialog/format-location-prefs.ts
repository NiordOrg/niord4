/**
 * Persistence of the Insert-Locations dialog's parameters in the
 * `localStorage['formatLocationSettings']` JSON. Shape is `{ "template": string, "format": string }`.
 *
 * A stored `template` of `'list'` is rewritten to `'numbered-list'` on read — the template
 * identifier was renamed, and older stored values must not resurrect the dead identifier.
 */

const STORAGE_KEY = 'formatLocationSettings';

/** The old template identifier that is rewritten to `numbered-list` on read. */
const RENAMED_TEMPLATE = 'list';
const RENAMED_TEMPLATE_TARGET = 'numbered-list';

export interface FormatLocationSettings {
  /** numbered-list | bullet-list | table | from-to-table | text */
  readonly template: string;
  /** dec | dec-2 | dec-1 | sec | navtex */
  readonly format: string;
}

const DEFAULTS: FormatLocationSettings = { template: 'numbered-list', format: 'dec' };

/**
 * Reads the persisted settings, applying the `'list' → 'numbered-list'` rewrite
 * and falling back to the defaults on any error or missing field.
 */
export function readFormatLocationSettings(): FormatLocationSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return DEFAULTS;
    }
    const parsed = JSON.parse(raw) as Partial<FormatLocationSettings>;
    let template = typeof parsed.template === 'string' ? parsed.template : DEFAULTS.template;
    if (template === RENAMED_TEMPLATE) {
      template = RENAMED_TEMPLATE_TARGET;
    }
    const format = typeof parsed.format === 'string' ? parsed.format : DEFAULTS.format;
    return { template, format };
  } catch {
    return DEFAULTS;
  }
}

/** Persists the settings (written on Insert). Storage errors are swallowed. */
export function writeFormatLocationSettings(settings: FormatLocationSettings): void {
  try {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ template: settings.template, format: settings.format }),
    );
  } catch {
    // Ignore storage errors — the choice still applies for the current session.
  }
}
