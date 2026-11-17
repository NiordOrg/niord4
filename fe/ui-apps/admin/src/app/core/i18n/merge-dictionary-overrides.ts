import { isDevMode } from '@angular/core';
import type { Translation } from '@jsverse/transloco';

/** The `{{token}}` interpolation placeholders referenced in a translation value. */
function placeholders(value: string): Set<string> {
  return new Set([...value.matchAll(/{{\s*([\w.]+)\s*}}/g)].map((m) => m[1]));
}

/**
 * Resolve a flat, dot-separated key against the compiled bundle, which mixes flat keys
 * (`"editor.section.message_part": "…"`) with genuinely nested objects. Tries the flat key first,
 * then walks the nested path.
 */
function resolveBaseValue(base: Translation, key: string): unknown {
  const record = base as Record<string, unknown>;
  if (key in record) {
    return record[key];
  }
  return key
    .split('.')
    .reduce<unknown>(
      (node, part) =>
        node && typeof node === 'object' ? (node as Record<string, unknown>)[part] : undefined,
      base,
    );
}

/** Whether two sets of placeholders are the same. */
function samePlaceholders(a: Set<string>, b: Set<string>): boolean {
  return a.size === b.size && [...a].every((token) => b.has(token));
}

/**
 * Merge sysadmin Dictionary overrides onto the compiled i18n bundle — but REFUSE any override whose
 * interpolation placeholders are not exactly those of the compiled value.
 *
 * Dictionary entries predate this front end, and many use its keys with other parameters. Some use
 * other token names than the app passes (`{{fromMessageNo}}` where the app passes `{{from}}`), some
 * are plain labels with no token at all (`Published` vs the app's `Published {{date}}`) — and some
 * carry a token where the app's label has none, because the old application used the key for
 * something else (`Remove from "{{tag}}"` over the app's `Remove from…`, a submenu heading). When
 * such an override wins, the app's `translate(key, params)` call either has nowhere to put a value,
 * which silently vanishes — the load-more button rendered "Load  -  of messages" — or has no value
 * for a token, which renders empty — the heading read 'Remove from ""'.
 *
 * The rule: an override replaces the compiled value only if it references exactly the placeholders
 * the compiled value does — the parameters the app passes for that key. Otherwise the complete
 * compiled value is kept, so what the app passes always renders and nothing renders empty. An admin
 * can still re-word any label; they cannot drop its data, or ask for data the app does not give it.
 * A key the compiled bundle does not have is taken as it is: there is nothing to hold it to.
 */
export function mergeDictionaryOverrides(
  base: Translation,
  overrides: Record<string, string>,
): Translation {
  const kept: Record<string, string> = {};
  const refused: string[] = [];

  for (const [key, value] of Object.entries(overrides)) {
    const baseValue = resolveBaseValue(base, key);
    if (
      typeof baseValue === 'string' &&
      !samePlaceholders(placeholders(baseValue), placeholders(value))
    ) {
      refused.push(key);
      continue;
    }
    kept[key] = value;
  }

  if (refused.length > 0 && isDevMode()) {
    console.warn(
      `[i18n] Ignored ${refused.length} Dictionary override(s) whose interpolation placeholders ` +
        `differ from the compiled value's (a parameter would vanish, or render empty): ` +
        `${refused.join(', ')}. Align the parameters in the sysadmin Dictionary with the compiled ` +
        `bundle to re-enable them.`,
    );
  }

  return { ...base, ...kept } as Translation;
}
