import type { Feature as OlFeature } from 'ol';

/**
 * The buffer-feature property keys and the one predicate that reads them, split out of
 * `buffer-feature.ts` so a consumer can identify a buffer feature without pulling in the
 * geometry MATH.
 *
 * `buffer-feature.ts` imports `ol-buffer.ts`, which imports `@turf/buffer` → JSTS (~301 kB raw).
 * The preview layer only ever needed `isBufferFeature`, so importing it from here keeps JSTS out
 * of every route that merely renders geometry instead of editing it.
 *
 * Type-only OL import: nothing here touches the OL runtime.
 */

export const PARENT_FEATURE_IDS = 'parentFeatureIds';
export const RESTRICTION = 'restriction';
export const BUFFER_TYPE = 'bufferType';
export const BUFFER_RADIUS = 'bufferRadius';
export const BUFFER_RADIUS_TYPE = 'bufferRadiusType';

/** A buffer ("affected area") feature is one that references parent features. */
export function isBufferFeature(feature: OlFeature): boolean {
  return typeof feature.get(PARENT_FEATURE_IDS) === 'string';
}
