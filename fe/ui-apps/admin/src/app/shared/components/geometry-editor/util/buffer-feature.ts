import { Feature as OlFeature } from 'ol';
import type Geometry from 'ol/geom/Geometry';
import { Point } from 'ol/geom';

import {
  areaGeometry,
  barePointGeometry,
  bufferGeometries,
  type BufferType,
  mergeGeometries,
  pathGeometry,
  pointRadiusGeometry,
  type RadiusUnit,
  toMeters,
} from './ol-buffer';

// The keys + `isBufferFeature` live in their own module so a consumer that only needs to
// RECOGNISE a buffer feature doesn't drag in `ol-buffer` → @turf/buffer → JSTS. Re-exported so
// every existing importer of this module keeps working unchanged.
export * from './buffer-feature-keys';
import {
  BUFFER_RADIUS,
  BUFFER_RADIUS_TYPE,
  BUFFER_TYPE,
  PARENT_FEATURE_IDS,
  RESTRICTION,
} from './buffer-feature-keys';

/** Default affected radius in nautical miles. */
const DEFAULT_RADIUS_NM = 1.0;

function parentIds(feature: OlFeature): string[] {
  const ids = feature.get(PARENT_FEATURE_IDS);
  return typeof ids === 'string' && ids.length > 0 ? ids.split(',') : [];
}

function allPoints(features: readonly OlFeature[]): boolean {
  return features.length > 0 && features.every((f) => f.getGeometry()?.getType() === 'Point');
}

/**
 * Whether a buffer of `type` can be added for the current selection. A `radius` buffer is an
 * outward offset of ANY geometry (≥1 feature of any type); `path`/`area` build
 * from POINT parents only (path ≥2, area ≥3 points).
 */
export function canAddBuffer(type: BufferType, selected: readonly OlFeature[]): boolean {
  if (type === 'radius') return selected.length >= 1;
  if (!allPoints(selected)) return false;
  if (type === 'path') return selected.length >= 2;
  return selected.length >= 3;
}

/**
 * Recomputes a buffer feature's geometry from its (still-present) parents.
 *
 * Returns `undefined` ONLY when no parent survives — the sole cascade-delete trigger. Every
 * other degenerate-but-recoverable state (a non-positive radius, too few parents for the
 * declared path/area) falls back to the bare merged parent geometry of the survivors, so a
 * transient edit (clearing the radius field, deleting one of three area vertices) keeps the
 * buffer alive and rebuilds it as its declared type once the value is valid again. The base is
 * always the merged parent geometry; the radius offset is applied only when `bufferRadius > 0`.
 *
 * `radius` is hybrid: an all-point selection uses the exact geodesic circle(s); any other
 * geometry (line / polygon / mixed) is offset with `@turf` via `bufferGeometries`.
 */
export function computeBufferGeometry(
  feature: OlFeature,
  all: readonly OlFeature[],
): Geometry | undefined {
  // Honour the stored parent order (captured in parentFeatureIds), not the VectorSource order,
  // so path/area vertices follow what the user selected. Resolve ALL surviving parents (any
  // geometry type) — not only Points — so a line/polygon parent survives recompute.
  const geometryById = new Map<string, Geometry>();
  for (const candidate of all) {
    const geometry = candidate.getGeometry();
    if (geometry) {
      geometryById.set(String(candidate.getId()), geometry);
    }
  }
  const parents = parentIds(feature)
    .map((id) => geometryById.get(id))
    .filter((geometry): geometry is Geometry => geometry !== undefined);
  if (parents.length === 0) return undefined;

  const pointCoords = parents
    .filter((geometry): geometry is Point => geometry instanceof Point)
    .map((geometry) => geometry.getCoordinates());
  const allPointParents = pointCoords.length === parents.length;

  const type = (feature.get(BUFFER_TYPE) as BufferType | undefined) ?? 'radius';

  if (type === 'radius') {
    const radius = Number(feature.get(BUFFER_RADIUS) ?? DEFAULT_RADIUS_NM);
    const unit = (feature.get(BUFFER_RADIUS_TYPE) as RadiusUnit | undefined) ?? 'nm';
    const meters = toMeters(radius, unit);
    if (allPointParents) {
      return meters > 0 ? pointRadiusGeometry(pointCoords, meters) : barePointGeometry(pointCoords);
    }
    return bufferGeometries(parents, meters);
  }

  // path / area build a LineString / Polygon from point parents, then optionally offset that
  // result outward by the radius (path → corridor, area → expanded ring) when a positive radius
  // is set. If no point survives (only via degenerate data), keep the buffer alive as the merged
  // parent geometry.
  if (pointCoords.length === 0) return mergeGeometries(parents);
  let base: Geometry;
  if (type === 'path') {
    base = pointCoords.length >= 2 ? pathGeometry(pointCoords) : barePointGeometry(pointCoords);
  } else {
    base = pointCoords.length >= 3 ? areaGeometry(pointCoords) : barePointGeometry(pointCoords);
  }
  const radius = Number(feature.get(BUFFER_RADIUS) ?? 0);
  const unit = (feature.get(BUFFER_RADIUS_TYPE) as RadiusUnit | undefined) ?? 'nm';
  const meters = toMeters(radius, unit);
  return meters > 0 ? bufferGeometries([base], meters) : base;
}

/** Creates an affected-area buffer feature for the given parents, or undefined if invalid. */
export function createBufferFeature(
  parents: readonly OlFeature[],
  type: BufferType,
  all: readonly OlFeature[],
): OlFeature | undefined {
  const feature = new OlFeature();
  feature.setId(crypto.randomUUID());
  feature.set(PARENT_FEATURE_IDS, parents.map((f) => String(f.getId())).join(','));
  feature.set(RESTRICTION, 'affected');
  feature.set(BUFFER_TYPE, type);
  if (type === 'radius') {
    feature.set(BUFFER_RADIUS, DEFAULT_RADIUS_NM);
    feature.set(BUFFER_RADIUS_TYPE, 'nm');
  }
  const geometry = computeBufferGeometry(feature, all);
  if (!geometry) return undefined;
  feature.setGeometry(geometry);
  return feature;
}
