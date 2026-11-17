import { GeometryCollection, LineString, MultiPoint, MultiPolygon, Point, Polygon } from 'ol/geom';
import { fromLonLat, toLonLat } from 'ol/proj';
import { getArea } from 'ol/sphere';
import { describe, expect, it } from 'vitest';

import {
  areaGeometry,
  barePointGeometry,
  bufferGeometries,
  mergeGeometries,
  pathGeometry,
  pointRadiusGeometry,
  toMeters,
} from './ol-buffer';

describe('toMeters', () => {
  it('converts m / km / nm', () => {
    expect(toMeters(5, 'm')).toBe(5);
    expect(toMeters(2, 'km')).toBe(2000);
    expect(toMeters(1, 'nm')).toBe(1852);
  });
});

describe('pointRadiusGeometry', () => {
  it('makes a single geodesic circle around one point with ~the requested radius', () => {
    const center = fromLonLat([10, 56]);
    const circle = pointRadiusGeometry([center], 1852) as Polygon;
    expect(circle.getType()).toBe('Polygon');
    // Geodesic area ≈ π r² for a 1852 m circle (within a few %).
    const area = getArea(circle, { projection: 'EPSG:3857' });
    expect(area).toBeGreaterThan(Math.PI * 1852 * 1852 * 0.9);
    expect(area).toBeLessThan(Math.PI * 1852 * 1852 * 1.1);
  });

  it('makes a MultiPolygon for several points', () => {
    const geom = pointRadiusGeometry([fromLonLat([10, 56]), fromLonLat([11, 57])], 1000);
    expect(geom).toBeInstanceOf(MultiPolygon);
    expect((geom as MultiPolygon).getPolygons()).toHaveLength(2);
  });
});

describe('pathGeometry / areaGeometry', () => {
  it('builds a LineString through the points', () => {
    const a = fromLonLat([10, 55]);
    const b = fromLonLat([12, 57]);
    expect(pathGeometry([a, b]).getCoordinates()).toEqual([a, b]);
  });

  it('builds a closed Polygon ring from the points', () => {
    const pts = [fromLonLat([10, 55]), fromLonLat([12, 55]), fromLonLat([12, 57])];
    const ring = areaGeometry(pts).getCoordinates()[0];
    expect(ring).toHaveLength(4); // 3 points + closing vertex
    expect(toLonLat(ring[0])[0]).toBeCloseTo(toLonLat(ring[3])[0], 6);
  });
});

describe('barePointGeometry', () => {
  it('is a Point for one parent and a MultiPoint for several', () => {
    expect(barePointGeometry([fromLonLat([10, 55])])).toBeInstanceOf(Point);
    const multi = barePointGeometry([fromLonLat([10, 55]), fromLonLat([12, 57])]);
    expect(multi).toBeInstanceOf(MultiPoint);
    expect((multi as MultiPoint).getPoints()).toHaveLength(2);
  });
});

describe('bufferGeometries (@turf, arbitrary geometry)', () => {
  const area = (g: import('ol/geom/Geometry').default) =>
    getArea(g as Polygon | MultiPolygon, { projection: 'EPSG:3857' });

  it('buffers a LineString into a polygonal corridor with area > 0', () => {
    const line = new LineString([fromLonLat([10, 55]), fromLonLat([12, 57])]);
    const buffered = bufferGeometries([line], 1852);
    expect(buffered.getType()).toMatch(/Polygon$/);
    expect(area(buffered)).toBeGreaterThan(0);
  });

  it('expands a Polygon — the buffered area exceeds the original', () => {
    const ring = [
      fromLonLat([10, 55]),
      fromLonLat([12, 55]),
      fromLonLat([12, 57]),
      fromLonLat([10, 55]),
    ];
    const poly = new Polygon([ring]);
    const buffered = bufferGeometries([poly], 5000);
    expect(area(buffered)).toBeGreaterThan(area(poly));
  });

  it('returns the bare merged parent geometry for a non-positive radius', () => {
    const line = new LineString([fromLonLat([10, 55]), fromLonLat([12, 57])]);
    expect(bufferGeometries([line], 0)).toBeInstanceOf(LineString);
  });

  it('combines several parents into one buffered geometry', () => {
    const a = new LineString([fromLonLat([10, 55]), fromLonLat([11, 55])]);
    const b = new LineString([fromLonLat([13, 58]), fromLonLat([14, 58])]);
    expect(area(bufferGeometries([a, b], 1000))).toBeGreaterThan(0);
  });
});

describe('mergeGeometries', () => {
  it('clones a single parent (does not mutate the original)', () => {
    const line = new LineString([fromLonLat([10, 55]), fromLonLat([12, 57])]);
    const merged = mergeGeometries([line]);
    expect(merged).toBeInstanceOf(LineString);
    expect(merged).not.toBe(line);
  });

  it('wraps multiple parents in a GeometryCollection', () => {
    const merged = mergeGeometries([
      new Point(fromLonLat([10, 55])),
      new LineString([fromLonLat([10, 55]), fromLonLat([11, 56])]),
    ]);
    expect(merged).toBeInstanceOf(GeometryCollection);
    expect((merged as GeometryCollection).getGeometries()).toHaveLength(2);
  });
});
