import type { Geometry, GeometryCollection } from 'geojson';

import type {
  GeoJsonProperties,
  GeometryFeature,
  GeometryFeatureCollection,
} from '../models/geometry.model';

/**
 * Largest positional difference (in degrees) still treated as "no change". The
 * geometry editor round-trips coordinates through EPSG:3857 and back (see
 * {@link GeometryConversionService}), which perturbs lon/lat by ~1e-13° of
 * floating-point noise even when nothing was edited. A *meaningful* edit — even
 * a single-pixel vertex nudge at maximum zoom — moves far more (>1e-6°). 1e-7°
 * (~1 cm at the equator) sits comfortably between the two.
 */
const COORD_EPSILON = 1e-7;

/** Transient editor-only view flag; never a real geometry difference. */
const TRANSIENT_PROP = 'selected';

/**
 * Structural equality for two GeoJSON `FeatureCollection`s that tolerates the
 * floating-point coordinate noise the editor's OL round-trip introduces, so
 * committing an *un-edited* geometry doesn't register as a change on the host.
 *
 * Compares feature count + order, each geometry (type + coordinates within
 * {@link COORD_EPSILON}), and properties (order-insensitive, ignoring the
 * transient `selected` flag). An `undefined` collection equals an empty one.
 */
export function geometryCollectionsEqual(
  a: GeometryFeatureCollection | undefined,
  b: GeometryFeatureCollection | undefined,
): boolean {
  const fa = a?.features ?? [];
  const fb = b?.features ?? [];
  if (fa.length !== fb.length) return false;
  return fa.every((feature, i) => featuresEqual(feature, fb[i]));
}

function featuresEqual(a: GeometryFeature, b: GeometryFeature): boolean {
  return geometriesEqual(a.geometry, b.geometry) && propertiesEqual(a.properties, b.properties);
}

function geometriesEqual(a: Geometry | null, b: Geometry | null): boolean {
  if (a === null || b === null) return a === b;
  if (a.type !== b.type) return false;
  if (a.type === 'GeometryCollection') {
    const others = (b as GeometryCollection).geometries;
    return (
      a.geometries.length === others.length &&
      a.geometries.every((g, i) => geometriesEqual(g, others[i]))
    );
  }
  // Every non-collection geometry type exposes a (nested) `coordinates` array.
  return coordinatesEqual(a.coordinates, (b as Exclude<Geometry, GeometryCollection>).coordinates);
}

function coordinatesEqual(a: unknown, b: unknown): boolean {
  if (typeof a === 'number' && typeof b === 'number') {
    return Math.abs(a - b) <= COORD_EPSILON;
  }
  if (Array.isArray(a) && Array.isArray(b)) {
    return a.length === b.length && a.every((value, i) => coordinatesEqual(value, b[i]));
  }
  return false;
}

function propertiesEqual(a: GeoJsonProperties | null, b: GeoJsonProperties | null): boolean {
  return canonicalProps(a) === canonicalProps(b);
}

/** Key-order-independent serialization of properties, minus the transient flag. */
function canonicalProps(props: GeoJsonProperties | null): string {
  if (!props) return '[]';
  const entries = Object.entries(props)
    .filter(([key]) => key !== TRANSIENT_PROP)
    .sort(([x], [y]) => (x < y ? -1 : x > y ? 1 : 0));
  return JSON.stringify(entries);
}
