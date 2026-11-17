import type { BasePromulgation } from '../../messages/models/message.model';

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Recursively overlay `incoming` onto `existing`: two plain objects merge key-by-key (so a field
 * the response omits survives), arrays overwrite wholesale (never element-merged), and any scalar
 * or type change overwrites. Pure — a new object graph is returned, the inputs are never mutated.
 */
function mergeValue(existing: unknown, incoming: unknown): unknown {
  if (Array.isArray(incoming)) {
    return incoming;
  }
  if (isPlainObject(incoming) && isPlainObject(existing)) {
    const result: Record<string, unknown> = { ...existing };
    for (const key of Object.keys(incoming)) {
      result[key] = mergeValue(existing[key], incoming[key]);
    }
    return result;
  }
  return incoming;
}

/**
 * Selectively deep-merge a `generate/{typeId}` response into the existing promulgation entry:
 * present response fields overwrite recursively, arrays overwrite wholesale, ABSENT fields survive,
 * and the Jackson `@class` discriminator + `type.typeId` (the merge key) ALWAYS survive from the
 * existing entry. Immutable — the existing entry is never mutated, so sibling entries a caller maps
 * over keep their own references untouched.
 */
export function mergeGeneratedPromulgation(
  existing: BasePromulgation,
  generated: BasePromulgation,
): BasePromulgation {
  const merged = mergeValue(existing, generated) as Record<string, unknown>;
  // `@class` must round-trip untouched — the working copy's discriminator is authoritative; fall
  // back to the response only if the working copy somehow lacks one.
  const existingClass = existing['@class'];
  if (existingClass !== undefined) {
    merged['@class'] = existingClass;
  }
  // `type.typeId` is the match key — force the existing value so a malformed response cannot re-key.
  const mergedType = (merged['type'] ?? {}) as Record<string, unknown>;
  merged['type'] = { ...mergedType, typeId: existing.type.typeId };
  // `generate` derives `promulgate` from whether it produced content — an empty body (e.g. a
  // brand-new message) makes the backend return `promulgate: false` for an OPTIONAL channel. But
  // generate only ever runs on a channel the user has already switched ON, so the response MUST
  // NOT silently switch it back off (which greys out the editor + Generate button and makes
  // Generate-all skip the channel). OR the two: generate may turn a channel ON but never OFF.
  merged['promulgate'] = existing.promulgate || generated.promulgate;
  return merged as unknown as BasePromulgation;
}
