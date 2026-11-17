/**
 * Resolves the i18n key for a message-part type label. `details` and `time` reuse the shared
 * field labels under the flat `msg.field.*` keyspace; the other KNOWN part types keep their own
 * `messages.field.part.*` label (the Danish wording differs — e.g. `signals` → "Skydesignaler").
 *
 * The part type is free-form backend data, so an UNKNOWN type resolves to `undefined` rather
 * than a dynamic key — translating a made-up key would render the raw key string in the UI.
 * Callers show the raw type text as the fallback.
 */
const SHARED_FIELD_LABEL: Readonly<Record<string, string>> = {
  details: 'msg.field.details',
  time: 'msg.field.time',
};

/** The part types with a `messages.field.part.*` label in the bundles. */
const PART_LABEL_TYPES: ReadonlySet<string> = new Set([
  'positions',
  'note',
  'prohibition',
  'signals',
]);

export function partTypeLabelKey(type: string): string | undefined {
  const t = type.toLowerCase();
  if (SHARED_FIELD_LABEL[t]) {
    return SHARED_FIELD_LABEL[t];
  }
  return PART_LABEL_TYPES.has(t) ? `messages.field.part.${t}` : undefined;
}
