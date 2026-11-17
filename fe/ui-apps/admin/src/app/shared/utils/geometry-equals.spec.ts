import type { Geometry } from 'geojson';
import { describe, expect, it } from 'vitest';

import type { GeoJsonProperties, GeometryFeatureCollection } from '../models/geometry.model';
import { geometryCollectionsEqual } from './geometry-equals';

function fc(
  ...features: { geometry: Geometry; properties?: GeoJsonProperties | null }[]
): GeometryFeatureCollection {
  return {
    type: 'FeatureCollection',
    features: features.map((f) => ({
      type: 'Feature',
      properties: f.properties ?? {},
      geometry: f.geometry,
    })),
  };
}

const point = (lon: number, lat: number): Geometry => ({ type: 'Point', coordinates: [lon, lat] });

describe('geometryCollectionsEqual', () => {
  it('treats undefined and an empty collection as equal', () => {
    expect(geometryCollectionsEqual(undefined, undefined)).toBe(true);
    expect(geometryCollectionsEqual(undefined, fc())).toBe(true);
    expect(geometryCollectionsEqual(fc(), undefined)).toBe(true);
  });

  it('returns true for identical geometries', () => {
    expect(
      geometryCollectionsEqual(fc({ geometry: point(12, 55) }), fc({ geometry: point(12, 55) })),
    ).toBe(true);
  });

  it('tolerates sub-centimetre coordinate noise from the 3857 round-trip', () => {
    expect(
      geometryCollectionsEqual(
        fc({ geometry: point(12, 55) }),
        fc({ geometry: point(12 + 5e-9, 55 - 3e-9) }),
      ),
    ).toBe(true);
  });

  it('detects a real position change (beyond the epsilon)', () => {
    expect(
      geometryCollectionsEqual(
        fc({ geometry: point(12, 55) }),
        fc({ geometry: point(12.001, 55) }),
      ),
    ).toBe(false);
  });

  it('is order-sensitive on features', () => {
    const a = fc({ geometry: point(12, 55) }, { geometry: point(13, 56) });
    const b = fc({ geometry: point(13, 56) }, { geometry: point(12, 55) });
    expect(geometryCollectionsEqual(a, b)).toBe(false);
  });

  it('detects a differing feature count', () => {
    expect(geometryCollectionsEqual(fc({ geometry: point(12, 55) }), fc())).toBe(false);
  });

  it('detects a differing geometry type', () => {
    const line: Geometry = {
      type: 'LineString',
      coordinates: [
        [12, 55],
        [13, 56],
      ],
    };
    expect(geometryCollectionsEqual(fc({ geometry: point(12, 55) }), fc({ geometry: line }))).toBe(
      false,
    );
  });

  it('compares nested LineString / Polygon coordinates', () => {
    const polyA: Geometry = {
      type: 'Polygon',
      coordinates: [
        [
          [0, 0],
          [1, 0],
          [1, 1],
          [0, 0],
        ],
      ],
    };
    const polyB: Geometry = {
      type: 'Polygon',
      coordinates: [
        [
          [0, 0],
          [1, 0],
          [1, 1],
          [0, 0],
        ],
      ],
    };
    const polyC: Geometry = {
      type: 'Polygon',
      coordinates: [
        [
          [0, 0],
          [1, 0],
          [1, 2],
          [0, 0],
        ],
      ],
    };
    expect(geometryCollectionsEqual(fc({ geometry: polyA }), fc({ geometry: polyB }))).toBe(true);
    expect(geometryCollectionsEqual(fc({ geometry: polyA }), fc({ geometry: polyC }))).toBe(false);
  });

  it('compares GeometryCollection members', () => {
    const gcA: Geometry = {
      type: 'GeometryCollection',
      geometries: [point(12, 55), point(13, 56)],
    };
    const gcB: Geometry = {
      type: 'GeometryCollection',
      geometries: [point(12, 55), point(13, 56)],
    };
    const gcC: Geometry = { type: 'GeometryCollection', geometries: [point(12, 55)] };
    expect(geometryCollectionsEqual(fc({ geometry: gcA }), fc({ geometry: gcB }))).toBe(true);
    expect(geometryCollectionsEqual(fc({ geometry: gcA }), fc({ geometry: gcC }))).toBe(false);
  });

  it('ignores property key order', () => {
    const a = fc({ geometry: point(12, 55), properties: { 'name:en': 'x', restriction: 'none' } });
    const b = fc({ geometry: point(12, 55), properties: { restriction: 'none', 'name:en': 'x' } });
    expect(geometryCollectionsEqual(a, b)).toBe(true);
  });

  it('detects a changed property value', () => {
    const a = fc({ geometry: point(12, 55), properties: { 'name:en': 'x' } });
    const b = fc({ geometry: point(12, 55), properties: { 'name:en': 'y' } });
    expect(geometryCollectionsEqual(a, b)).toBe(false);
  });

  it('ignores the transient `selected` view flag', () => {
    const a = fc({ geometry: point(12, 55), properties: { 'name:en': 'x', selected: true } });
    const b = fc({ geometry: point(12, 55), properties: { 'name:en': 'x' } });
    expect(geometryCollectionsEqual(a, b)).toBe(true);
  });

  it('treats null and empty properties as equal', () => {
    const a = fc({ geometry: point(12, 55), properties: null });
    const b = fc({ geometry: point(12, 55), properties: {} });
    expect(geometryCollectionsEqual(a, b)).toBe(true);
  });
});
