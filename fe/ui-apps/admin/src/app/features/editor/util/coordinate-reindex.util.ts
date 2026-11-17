import type { GeometryFeature } from '../../../shared/models/geometry.model';
import type { MessagePart } from '../../messages/models/message.model';

/**
 * Stamp `startCoordIndex` on every coordinate-bearing feature of every part, threading ONE running
 * counter (seeded at 1) across the whole message so coordinate numbering stays continuous across
 * parts. A feature that contributes zero readable coordinates (a buffered feature, an
 * `affected`-restriction feature, or an empty geometry) is neither stamped nor advances the counter.
 *
 * `countReadableCoords` returns a feature's readable-coordinate count — pass
 * `MessageGeometryService.serializeReadableCoordinates(feature).length` so the skip rules (interior
 * rings, closing ring coordinates, buffered / affected features) stay the single source of truth.
 *
 * Pure and immutable: returns a fresh parts array; only parts/features that actually gain a stamp
 * are rebuilt (with fresh `properties`) and the input graph is never mutated.
 */
export function reindexStartCoordIndices(
  parts: readonly MessagePart[],
  countReadableCoords: (feature: GeometryFeature) => number,
): MessagePart[] {
  let startCoordIndex = 1;
  return parts.map((part) => {
    const collection = part.geometry;
    if (!collection || collection.features.length === 0) {
      return part;
    }
    const features = collection.features.map((feature) => {
      const count = countReadableCoords(feature);
      if (count <= 0) {
        return feature;
      }
      const stamped: GeometryFeature = {
        ...feature,
        properties: { ...(feature.properties ?? {}), startCoordIndex },
      };
      startCoordIndex += count;
      return stamped;
    });
    return { ...part, geometry: { ...collection, features } };
  });
}
