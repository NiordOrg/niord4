import { Feature as OlFeature } from 'ol';
import type { Coordinate } from 'ol/coordinate';
import {
  GeometryCollection,
  LineString,
  MultiLineString,
  MultiPoint,
  MultiPolygon,
  Point,
  Polygon,
} from 'ol/geom';
import type Geometry from 'ol/geom/Geometry';

import { readCoordNames } from '../geometry-tree/coord-names';
import { geometryToTree } from '../geometry-tree/geometry-tree-model';

/**
 * The pure geometry operations — `merge` / `convert` / `split`. Deliberately only these three;
 * JSTS-style set operations (subtract / intersect / union) are not offered. Each op produces fresh
 * editor feature(s); the host swaps them into the layer (remove originals + add results).
 *
 * The load-bearing detail is the per-coordinate name bookkeeping. A coordinate name is keyed
 * `name:<idx>:<lang>`, where `<idx>` is the BREADTH-FIRST coordinate index (the same order the
 * tree and the on-map labels use). Every op that re-partitions coordinates must re-index those
 * names or the labels silently corrupt — so the offset math runs against `coordinateCount`, which
 * counts the reserved ring-closing vertices. Feature-level names (`name:<lang>`) and per-coordinate
 * names are handled per each function; non-name properties (restriction, …) are not carried.
 */

/** The geometry types the `convert` op can target. */
export type ConvertType = 'LineString' | 'Polygon' | 'MultiPoint';

/** Matches a feature-level name key: `name:<lang>`. */
const FEATURE_NAME_KEY = /^name:([a-zA-Z_]+)$/;

/**
 * Breadth-first coordinate count (counts the reserved ring-closing vertices). Reuses the tree
 * model so the traversal order is single-sourced with the tree + labels.
 */
function coordinateCount(geometry: Geometry): number {
  return geometryToTree(geometry).coordCount;
}

/** Reads the feature-level name properties (`name:<lang>`) of a feature. */
function readFeatureLevelNames(feature: OlFeature): { key: string; value: unknown }[] {
  const names: { key: string; value: unknown }[] = [];
  for (const key of feature.getKeys()) {
    if (FEATURE_NAME_KEY.test(key)) names.push({ key, value: feature.get(key) });
  }
  return names;
}

/** Creates a fresh editor feature with a transient id + the given (name) properties. */
function createFeature(geometry: Geometry, names: Record<string, unknown>): OlFeature {
  const feature = new OlFeature(geometry);
  feature.setId(crypto.randomUUID());
  feature.setProperties(names);
  return feature;
}

// ----------------------------------------------------------------------------------------
// MERGE
// ----------------------------------------------------------------------------------------

/** The merged type of two geometries. Same family → Multi*, else GC. */
function computeMergeType(acc: string | undefined, type: string): string {
  const a = acc ?? type;
  if (type.includes('Point') && a.includes('Point')) return 'MultiPoint';
  if (type.includes('LineString') && a.includes('LineString')) return 'MultiLineString';
  if (type.includes('Polygon') && a.includes('Polygon')) return 'MultiPolygon';
  return 'GeometryCollection';
}

/** An empty accumulator geometry of the computed merge type. */
function emptyMergeGeometry(type: string): Geometry {
  switch (type) {
    case 'MultiPoint':
      return new MultiPoint([]);
    case 'MultiLineString':
      return new MultiLineString([]);
    case 'MultiPolygon':
      return new MultiPolygon([]);
    default:
      return new GeometryCollection([]);
  }
}

/** Appends `g2`'s parts into the accumulator `g1` (parts are cloned). */
function mergeInto(g1: Geometry, g2: Geometry): void {
  if (g1 instanceof GeometryCollection) {
    const incoming = g2 instanceof GeometryCollection ? g2.getGeometries() : [g2];
    g1.setGeometries([...g1.getGeometries(), ...incoming.map((g) => g.clone())]);
  } else if (g1 instanceof MultiPoint) {
    const points = g2 instanceof Point ? [g2] : g2 instanceof MultiPoint ? g2.getPoints() : [];
    points.forEach((point) => g1.appendPoint(point.clone()));
  } else if (g1 instanceof MultiLineString) {
    const lines =
      g2 instanceof LineString ? [g2] : g2 instanceof MultiLineString ? g2.getLineStrings() : [];
    lines.forEach((line) => g1.appendLineString(line.clone()));
  } else if (g1 instanceof MultiPolygon) {
    const polygons =
      g2 instanceof Polygon ? [g2] : g2 instanceof MultiPolygon ? g2.getPolygons() : [];
    polygons.forEach((polygon) => g1.appendPolygon(polygon.clone()));
  }
}

/** Whether the selection can be merged — "Merge" requires more than one selected feature. */
export function canMerge(selected: readonly OlFeature[]): boolean {
  return selected.length >= 2;
}

/**
 * Merges ≥ 2 features into one. Same-family geometries collapse to a Multi*
 * (Point→MultiPoint, …); a mixed selection becomes a GeometryCollection. Per-coordinate names
 * are offset by each preceding feature's breadth-first coordinate count; feature-level names
 * are carried as-is (last selected wins on a key collision). Returns undefined for < 2.
 */
export function mergeFeatures(selected: readonly OlFeature[]): OlFeature | undefined {
  if (!canMerge(selected)) return undefined;

  let mergeType: string | undefined;
  for (const feature of selected) {
    const geometry = feature.getGeometry();
    if (geometry) mergeType = computeMergeType(mergeType, geometry.getType());
  }
  const merged = emptyMergeGeometry(mergeType ?? 'GeometryCollection');

  const names: Record<string, unknown> = {};
  let coordOffset = 0;
  for (const feature of selected) {
    const geometry = feature.getGeometry();
    if (!geometry) continue;
    for (const { key, value } of readFeatureLevelNames(feature)) names[key] = value;
    for (const { coordIndex, lang, value } of readCoordNames(feature)) {
      names[`name:${coordIndex + coordOffset}:${lang}`] = value;
    }
    mergeInto(merged, geometry);
    coordOffset += coordinateCount(geometry);
  }
  return createFeature(merged, names);
}

// ----------------------------------------------------------------------------------------
// CONVERT
// ----------------------------------------------------------------------------------------

/** Whether the single selected feature can be converted to `toType`. */
export function canConvert(feature: OlFeature, toType: ConvertType): boolean {
  const fromType = feature.getGeometry()?.getType();
  switch (toType) {
    case 'MultiPoint':
      return fromType === 'LineString' || fromType === 'Polygon';
    case 'LineString':
      return fromType === 'MultiPoint' || fromType === 'Polygon';
    case 'Polygon':
      return fromType === 'MultiPoint' || fromType === 'LineString';
  }
}

/** Every name property (feature-level + per-coordinate) of a feature, keys unchanged. */
function allNameProperties(feature: OlFeature): Record<string, unknown> {
  const props: Record<string, unknown> = {};
  for (const { key, value } of readFeatureLevelNames(feature)) props[key] = value;
  for (const { key, value } of readCoordNames(feature)) props[key] = value;
  return props;
}

/**
 * Converts the single feature to `toType`. MultiPoint ↔ LineString share
 * their coordinate list; Polygon adds/removes the closing ring vertex. The breadth-first index
 * of every real vertex is unchanged (the closing vertex carries no name), so names are carried
 * verbatim. Returns undefined when the conversion is not allowed.
 */
export function convertFeature(feature: OlFeature, toType: ConvertType): OlFeature | undefined {
  if (!canConvert(feature, toType)) return undefined;
  const from = feature.getGeometry();

  let coordinates: Coordinate[];
  if (from instanceof Polygon) {
    // Exterior ring without its closing (repeated first) vertex.
    coordinates = from.getCoordinates()[0].slice(0, -1);
  } else if (from instanceof LineString || from instanceof MultiPoint) {
    coordinates = from.getCoordinates();
  } else {
    return undefined;
  }
  // A degenerate (empty) source has nothing to convert — a no-op, never destructive (and the
  // Polygon branch would otherwise throw on the missing closing vertex).
  if (coordinates.length === 0) return undefined;

  let geometry: Geometry;
  if (toType === 'Polygon') {
    // Drop a trailing vertex that already equals the first: a source line drawn closed
    // (last == first) would otherwise re-close into a DOUBLED ring-closing vertex, which leaks
    // an extra repeated position downstream (e.g. the template position list). The Polygon
    // source branch above already strips its closing vertex, so this only bites closed lines.
    const first = coordinates[0];
    const last = coordinates[coordinates.length - 1];
    const ring =
      coordinates.length > 1 && last[0] === first[0] && last[1] === first[1]
        ? coordinates.slice(0, -1)
        : coordinates;
    geometry = new Polygon([[...ring, ring[0]]]);
  } else if (toType === 'LineString') {
    geometry = new LineString(coordinates);
  } else {
    geometry = new MultiPoint(coordinates);
  }
  return createFeature(geometry, allNameProperties(feature));
}

// ----------------------------------------------------------------------------------------
// SPLIT
// ----------------------------------------------------------------------------------------

/** The sub-geometries a geometry splits into, or undefined for a Point. */
function splitSubGeometries(geometry: Geometry): Geometry[] | undefined {
  if (geometry instanceof Point) return undefined;
  if (geometry instanceof LineString) {
    return geometry.getCoordinates().map((coord) => new Point(coord));
  }
  if (geometry instanceof Polygon) {
    // A vertex per ring coordinate, skipping each ring's repeated closing vertex.
    const points: Geometry[] = [];
    for (const ring of geometry.getCoordinates()) {
      for (let x = 0; x < ring.length - 1; x++) points.push(new Point(ring[x]));
    }
    return points;
  }
  if (geometry instanceof MultiPoint) return geometry.getPoints();
  if (geometry instanceof MultiLineString) return geometry.getLineStrings();
  if (geometry instanceof MultiPolygon) return geometry.getPolygons();
  if (geometry instanceof GeometryCollection) return geometry.getGeometries();
  return undefined;
}

/** Whether the selection can be split — at least one non-Point feature (a Point is a no-op). */
export function canSplit(selected: readonly OlFeature[]): boolean {
  return selected.some((feature) => {
    const type = feature.getGeometry()?.getType();
    return type !== undefined && type !== 'Point';
  });
}

/**
 * Splits one feature into its sub-geometries: a LineString/Polygon into its
 * vertices (Points), a Multi-geometry or GeometryCollection into its parts. A Point is left untouched
 * (returns undefined → the host keeps the original feature). Per-coordinate names are sliced to
 * each sub-geometry's breadth-first `[offset, offset + count)` range and re-based to zero;
 * feature-level names are dropped (only coordinate names are carried across a split).
 *
 * (For a Polygon, only the exterior ring carries names, and its real vertices come first with
 * indices 0..k-1 — so re-basing them is exact; later interior-ring vertices have no names, so
 * the running offset's divergence from the reserved-closing breadth-first index is harmless.)
 */
export function splitFeature(feature: OlFeature): OlFeature[] | undefined {
  const geometry = feature.getGeometry();
  if (!geometry) return undefined;
  const subGeometries = splitSubGeometries(geometry);
  // A Point (undefined) or a degenerate empty Multi-geometry / collection (`[]`) is a no-op —
  // the host keeps the original feature rather than removing it and adding nothing back.
  if (!subGeometries || subGeometries.length === 0) return undefined;

  const coordNames = readCoordNames(feature);
  const results: OlFeature[] = [];
  let coordOffset = 0;
  for (const sub of subGeometries) {
    const count = coordinateCount(sub);
    const names: Record<string, unknown> = {};
    for (const { coordIndex, lang, value } of coordNames) {
      if (coordIndex >= coordOffset && coordIndex < coordOffset + count) {
        names[`name:${coordIndex - coordOffset}:${lang}`] = value;
      }
    }
    coordOffset += count;
    results.push(createFeature(sub.clone(), names));
  }
  return results;
}
