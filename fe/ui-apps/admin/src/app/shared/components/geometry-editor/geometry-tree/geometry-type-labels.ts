import type { GeometryNodeType } from './geometry-tree-model';

/**
 * The geometry types named in words: the GeoJSON geometry types and the two rings of a polygon
 * that the geometry tree shows. Shared by the tree's rows, the feature cards and the
 * Insert-locations dialog, so a geometry reads with the same word everywhere.
 */
export const LABELLED_GEOMETRY_TYPES = [
  'Point',
  'MultiPoint',
  'LineString',
  'MultiLineString',
  'Polygon',
  'MultiPolygon',
  'GeometryCollection',
  'Exterior',
  'Interior',
] as const satisfies readonly GeometryNodeType[];

const KNOWN_GEOMETRY_TYPES: ReadonlySet<string> = new Set(LABELLED_GEOMETRY_TYPES);

/** What to show for a value: a text key to translate, or a value shown as it is. */
export type ShownLabel =
  | { readonly key: string; readonly text?: undefined }
  | { readonly key?: undefined; readonly text: string };

/**
 * The label of a geometry type: its text key for a type of the set above, else the type as it is
 * (a type a later geometry library may report has no text, and is never shown as a key).
 */
export function geometryTypeLabel(type: string): ShownLabel {
  return KNOWN_GEOMETRY_TYPES.has(type) ? { key: `geometry.type.${type}` } : { text: type };
}
