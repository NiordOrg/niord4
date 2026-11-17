import { buffer as turfBuffer } from '@turf/buffer';
import type {
  Feature as GeoJsonFeature,
  FeatureCollection as GeoJsonFeatureCollection,
  Geometry as GeoJsonGeometry,
} from 'geojson';
import type { Coordinate } from 'ol/coordinate';
import GeoJSON from 'ol/format/GeoJSON';
import type Geometry from 'ol/geom/Geometry';
import { GeometryCollection, LineString, MultiPoint, MultiPolygon, Point, Polygon } from 'ol/geom';
import { circular } from 'ol/geom/Polygon';
import { toLonLat } from 'ol/proj';

/**
 * The affected-area buffer geometry. Buffer features mark the area affected by a message's
 * geometry:
 *
 * - `radius`: an outward buffer of the parent geometry. An all-point selection uses a geodesic
 *   circle per point (`ol.geom.Polygon.circular` — exact, no `1/cos(lat)` hack); any other
 *   geometry (line → corridor, polygon → expanded ring, mixed) is buffered with `@turf/buffer`.
 * - `path`: a LineString through the parent points (≥ 2).
 * - `area`: a closed Polygon of the parent points (≥ 3).
 *
 * Inputs/outputs are in the map projection (EPSG:3857); `@turf` works in WGS84, so non-point
 * radius buffers round-trip through GeoJSON. Deliberately NO `1/cos(lat)` latitude correction:
 * `circular` is geodesic and `@turf` buffers on the sphere, so the radius is already exact.
 */
export type BufferType = 'radius' | 'path' | 'area';
export type RadiusUnit = 'm' | 'km' | 'nm';

const METERS_PER_UNIT: Record<RadiusUnit, number> = { m: 1, km: 1000, nm: 1852 };
const DATA_PROJECTION = 'EPSG:4326';
const FEATURE_PROJECTION = 'EPSG:3857';
const CIRCLE_SEGMENTS = 64;

/** Converts a radius value in m/km/nm to metres. */
export function toMeters(value: number, unit: RadiusUnit): number {
  return value * (METERS_PER_UNIT[unit] ?? 1);
}

/** Geodesic circle(s) (EPSG:3857) around each point, of `radiusMeters`. */
export function pointRadiusGeometry(points: readonly Coordinate[], radiusMeters: number): Geometry {
  const circles = points.map((point) => {
    const circle = circular(toLonLat(point), radiusMeters, CIRCLE_SEGMENTS);
    circle.transform(DATA_PROJECTION, FEATURE_PROJECTION);
    return circle;
  });
  if (circles.length === 1) return circles[0];
  return new MultiPolygon(circles.map((circle) => circle.getCoordinates()));
}

/** A LineString through the parent points. */
export function pathGeometry(points: readonly Coordinate[]): LineString {
  return new LineString(points.map((point) => [...point]));
}

/** A closed Polygon of the parent points (first vertex repeated to close the ring). */
export function areaGeometry(points: readonly Coordinate[]): Polygon {
  const ring = points.map((point) => [...point]);
  ring.push([...points[0]]);
  return new Polygon([ring]);
}

/**
 * The bare point geometry of the parent coordinates — a Point for one, a MultiPoint for
 * several. This is the degenerate fallback a buffer keeps when it cannot (yet) be drawn as its
 * declared type — a non-positive radius, or too few surviving parents for a path/area — so a
 * transient edit never destroys the buffer feature.
 */
export function barePointGeometry(points: readonly Coordinate[]): Geometry {
  return points.length === 1
    ? new Point([...points[0]])
    : new MultiPoint(points.map((point) => [...point]));
}

const geoJson = new GeoJSON();

/**
 * Outward buffer ("affected area") of the parent geometries by `radiusMeters`, via
 * `@turf/buffer`. Used for the non-all-point `radius` case (line → corridor, polygon →
 * expanded ring, mixed selections); the all-point case keeps the exact `pointRadiusGeometry`
 * circle. Each parent is round-tripped EPSG:3857 ↔ 4326 (turf works in WGS84), buffered, and the
 * resulting polygons are combined into one Polygon / MultiPolygon. A non-positive radius — or a
 * turf result that yields no polygon — falls back to the bare merged parent geometry so a
 * transient edit (clearing the radius) never destroys the buffer.
 */
export function bufferGeometries(geometries: readonly Geometry[], radiusMeters: number): Geometry {
  if (radiusMeters <= 0) {
    return mergeGeometries(geometries);
  }
  const features: GeoJsonFeature[] = geometries.map((geometry) => ({
    type: 'Feature',
    properties: {},
    geometry: geoJson.writeGeometryObject(geometry, {
      featureProjection: FEATURE_PROJECTION,
      dataProjection: DATA_PROJECTION,
    }) as GeoJsonGeometry,
  }));
  const collection: GeoJsonFeatureCollection = { type: 'FeatureCollection', features };
  const buffered = turfBuffer(collection, radiusMeters, { units: 'meters' });

  const polygons: Coordinate[][][] = [];
  for (const feature of buffered?.features ?? []) {
    if (!feature?.geometry) {
      continue;
    }
    const olGeometry = geoJson.readGeometry(feature.geometry, {
      dataProjection: DATA_PROJECTION,
      featureProjection: FEATURE_PROJECTION,
    });
    if (olGeometry instanceof Polygon) {
      polygons.push(olGeometry.getCoordinates());
    } else if (olGeometry instanceof MultiPolygon) {
      polygons.push(...olGeometry.getCoordinates());
    }
  }
  if (polygons.length === 0) {
    return mergeGeometries(geometries);
  }
  return polygons.length === 1 ? new Polygon(polygons[0]) : new MultiPolygon(polygons);
}

/** Combines parent geometries into one — a clone for a single parent, else a GeometryCollection. */
export function mergeGeometries(geometries: readonly Geometry[]): Geometry {
  return geometries.length === 1
    ? geometries[0].clone()
    : new GeometryCollection(geometries.map((geometry) => geometry.clone()));
}
