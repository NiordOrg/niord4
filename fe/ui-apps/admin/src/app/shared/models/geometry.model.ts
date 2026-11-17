import type { Feature, FeatureCollection, Geometry } from 'geojson';

export type GeoJsonProperties = Record<string, unknown>;

export type GeometryFeature = Feature<Geometry, GeoJsonProperties>;

export type GeometryFeatureCollection = FeatureCollection<Geometry, GeoJsonProperties>;

/** A fresh empty FeatureCollection. A factory (not a shared constant) so each caller owns its `features` array. */
export function emptyFeatureCollection(): GeometryFeatureCollection {
  return { type: 'FeatureCollection', features: [] };
}
