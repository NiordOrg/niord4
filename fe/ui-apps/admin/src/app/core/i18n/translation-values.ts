import type { Translation } from '@jsverse/transloco';

/**
 * Flatten a translation file to `dotted.key → text`, so files merge key by key: one translated
 * group cannot hide the keys of another file within that group. Blank values are left out, so a
 * blank text never covers a filled one.
 */
export function translationValues(bundle: Translation, prefix = ''): Record<string, string> {
  const values: Record<string, string> = {};
  for (const [key, value] of Object.entries(bundle)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof value === 'string') {
      if (value.trim()) values[path] = value;
    } else if (value && typeof value === 'object' && !Array.isArray(value)) {
      Object.assign(values, translationValues(value, path));
    }
  }
  return values;
}
