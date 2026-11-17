/**
 * Whether a JSON form string is valid: empty is VALID (it serializes to `null` on save),
 * otherwise it must parse cleanly. Signal Forms editors gate Save on this via a `computed()`
 * (empty/parse handling matches {@link formStringToJsonObject}). Add a separate required-check
 * if non-empty JSON is mandatory.
 */
export function jsonStringValid(raw: string): boolean {
  if (raw === '') {
    return true;
  }
  try {
    JSON.parse(raw);
    return true;
  } catch {
    return false;
  }
}

/** Object → pretty-printed JSON string for the form control (empty string when nullish). */
export function jsonObjectToFormString(value: unknown): string {
  return value === undefined || value === null ? '' : JSON.stringify(value, null, 2);
}

/**
 * Form string → parsed object for the wire. Empty string parses to `null`. Safe
 * to call only when the control passes `jsonSyntaxValidator` (Save is gated on it).
 */
export function formStringToJsonObject(value: string): unknown {
  return JSON.parse(value === '' ? 'null' : value);
}
