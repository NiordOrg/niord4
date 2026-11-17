import { Feature } from 'ol';
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
import { describe, expect, it } from 'vitest';

import {
  canConvert,
  canMerge,
  canSplit,
  convertFeature,
  mergeFeatures,
  splitFeature,
} from './geometry-ops';

function feat(geometry: Geometry, names: Record<string, unknown> = {}): Feature {
  const feature = new Feature(geometry);
  feature.setId(`f-${Math.round(geometry.getExtent()[0] * 1000)}-${Object.keys(names).length}`);
  feature.setProperties(names);
  return feature;
}

describe('geometry-ops', () => {
  describe('merge', () => {
    it('collapses a same-family selection to a Multi* and a mixed one to a GeometryCollection', () => {
      const twoPoints = mergeFeatures([
        feat(new Point([0, 0])),
        feat(new Point([1, 1])),
      ])!.getGeometry();
      expect(twoPoints).toBeInstanceOf(MultiPoint);
      expect((twoPoints as MultiPoint).getPoints()).toHaveLength(2);

      const twoLines = mergeFeatures([
        feat(
          new LineString([
            [0, 0],
            [1, 1],
          ]),
        ),
        feat(
          new LineString([
            [2, 2],
            [3, 3],
          ]),
        ),
      ])!.getGeometry();
      expect(twoLines).toBeInstanceOf(MultiLineString);

      const mixed = mergeFeatures([
        feat(new Point([0, 0])),
        feat(
          new LineString([
            [1, 1],
            [2, 2],
          ]),
        ),
      ])!.getGeometry();
      expect(mixed).toBeInstanceOf(GeometryCollection);
      expect((mixed as GeometryCollection).getGeometries()).toHaveLength(2);
    });

    it('offsets per-coordinate names by each preceding feature’s breadth-first count', () => {
      const a = feat(
        new LineString([
          [0, 0],
          [1, 1],
        ]),
        { 'name:0:en': 'a0', 'name:1:en': 'a1' },
      );
      const b = feat(
        new LineString([
          [2, 2],
          [3, 3],
          [4, 4],
        ]),
        { 'name:0:en': 'b0' },
      );
      const merged = mergeFeatures([a, b])!;
      // a contributes indices 0,1; b's index 0 shifts past a's 2 coords → index 2.
      expect(merged.get('name:0:en')).toBe('a0');
      expect(merged.get('name:1:en')).toBe('a1');
      expect(merged.get('name:2:en')).toBe('b0');
    });

    it('reserves the polygon ring-closing index when offsetting a later part', () => {
      // Triangle A: 3 real vertices + 1 reserved closing index → next part starts at 4.
      const a = feat(
        new Polygon([
          [
            [0, 0],
            [1, 0],
            [1, 1],
            [0, 0],
          ],
        ]),
        { 'name:0:en': 'a0' },
      );
      const b = feat(
        new Polygon([
          [
            [5, 5],
            [6, 5],
            [6, 6],
            [5, 5],
          ],
        ]),
        { 'name:0:en': 'b0' },
      );
      const merged = mergeFeatures([a, b])!;
      expect(merged.getGeometry()).toBeInstanceOf(MultiPolygon);
      expect(merged.get('name:0:en')).toBe('a0');
      expect(merged.get('name:4:en')).toBe('b0');
    });

    it('carries feature-level names (last selected wins) and needs ≥ 2 features', () => {
      const merged = mergeFeatures([
        feat(new Point([0, 0]), { 'name:en': 'first' }),
        feat(new Point([1, 1]), { 'name:en': 'second' }),
      ])!;
      expect(merged.get('name:en')).toBe('second');

      expect(canMerge([feat(new Point([0, 0]))])).toBe(false);
      expect(mergeFeatures([feat(new Point([0, 0]))])).toBeUndefined();
    });
  });

  describe('convert', () => {
    it('honours the from→to conversion matrix', () => {
      const line = feat(
        new LineString([
          [0, 0],
          [1, 1],
        ]),
      );
      expect(canConvert(line, 'Polygon')).toBe(true);
      expect(canConvert(line, 'MultiPoint')).toBe(true);
      expect(canConvert(line, 'LineString')).toBe(false);

      const poly = feat(
        new Polygon([
          [
            [0, 0],
            [1, 0],
            [1, 1],
            [0, 0],
          ],
        ]),
      );
      expect(canConvert(poly, 'LineString')).toBe(true);
      expect(canConvert(poly, 'MultiPoint')).toBe(true);
      expect(canConvert(poly, 'Polygon')).toBe(false);

      const multi = feat(
        new MultiPoint([
          [0, 0],
          [1, 1],
        ]),
      );
      expect(canConvert(multi, 'LineString')).toBe(true);
      expect(canConvert(multi, 'Polygon')).toBe(true);
      expect(canConvert(multi, 'MultiPoint')).toBe(false);
    });

    it('closes the ring to a Polygon and opens it back, preserving names', () => {
      const line = feat(
        new LineString([
          [0, 0],
          [1, 0],
          [1, 1],
        ]),
        {
          'name:0:en': 'c0',
          'name:en': 'Line',
        },
      );
      const poly = convertFeature(line, 'Polygon')!;
      expect(poly.getGeometry()).toBeInstanceOf(Polygon);
      // 3 vertices + the repeated closing vertex.
      expect((poly.getGeometry() as Polygon).getCoordinates()[0]).toHaveLength(4);
      expect(poly.get('name:0:en')).toBe('c0');
      expect(poly.get('name:en')).toBe('Line');

      const reopened = convertFeature(
        feat(
          new Polygon([
            [
              [0, 0],
              [1, 0],
              [1, 1],
              [0, 0],
            ],
          ]),
          { 'name:0:en': 'v0' },
        ),
        'LineString',
      )!;
      expect(reopened.getGeometry()).toBeInstanceOf(LineString);
      expect((reopened.getGeometry() as LineString).getCoordinates()).toHaveLength(3);
      expect(reopened.get('name:0:en')).toBe('v0');
    });

    it('does not double the closing vertex when converting a CLOSED line to a Polygon', () => {
      // A line drawn closed already ends where it starts; re-closing must not repeat the vertex.
      const closedLine = feat(
        new LineString([
          [0, 0],
          [1, 0],
          [1, 1],
          [0, 0],
        ]),
      );
      const ring = (
        convertFeature(closedLine, 'Polygon')!.getGeometry() as Polygon
      ).getCoordinates()[0];
      // 3 distinct vertices + ONE closing vertex — not 5 (the doubled-vertex bug).
      expect(ring).toHaveLength(4);
      expect(ring[0]).toEqual(ring[ring.length - 1]);
    });

    it('returns undefined for a disallowed conversion', () => {
      expect(
        convertFeature(
          feat(
            new LineString([
              [0, 0],
              [1, 1],
            ]),
          ),
          'LineString',
        ),
      ).toBeUndefined();
    });
  });

  describe('split', () => {
    it('splits a LineString into Points and leaves a Point untouched', () => {
      const parts = splitFeature(
        feat(
          new LineString([
            [0, 0],
            [1, 1],
            [2, 2],
          ]),
        ),
      )!;
      expect(parts).toHaveLength(3);
      expect(parts.every((p) => p.getGeometry() instanceof Point)).toBe(true);

      expect(splitFeature(feat(new Point([0, 0])))).toBeUndefined();
    });

    it('splits a Polygon into a Point per real vertex and a Multi* into its parts', () => {
      const polyParts = splitFeature(
        feat(
          new Polygon([
            [
              [0, 0],
              [1, 0],
              [1, 1],
              [0, 0],
            ],
          ]),
        ),
      )!;
      expect(polyParts).toHaveLength(3); // closing vertex excluded
      expect(polyParts.every((p) => p.getGeometry() instanceof Point)).toBe(true);

      const collParts = splitFeature(
        feat(
          new GeometryCollection([
            new Point([0, 0]),
            new LineString([
              [1, 1],
              [2, 2],
            ]),
          ]),
        ),
      )!;
      expect(collParts).toHaveLength(2);
      expect(collParts[0].getGeometry()).toBeInstanceOf(Point);
      expect(collParts[1].getGeometry()).toBeInstanceOf(LineString);
    });

    it('slices + re-bases per-coordinate names onto the right sub-feature', () => {
      const parts = splitFeature(
        feat(
          new LineString([
            [0, 0],
            [1, 1],
            [2, 2],
          ]),
          {
            'name:0:en': 'p0',
            'name:2:en': 'p2',
            'name:en': 'dropped',
          },
        ),
      )!;
      expect(parts[0].get('name:0:en')).toBe('p0');
      expect(parts[1].get('name:0:en')).toBeUndefined();
      expect(parts[2].get('name:0:en')).toBe('p2'); // index 2 re-based to 0
      // Feature-level names are dropped on split.
      expect(parts.every((p) => p.get('name:en') === undefined)).toBe(true);
    });

    it('canSplit needs a non-Point feature', () => {
      expect(canSplit([feat(new Point([0, 0]))])).toBe(false);
      expect(
        canSplit([
          feat(
            new LineString([
              [0, 0],
              [1, 1],
            ]),
          ),
        ]),
      ).toBe(true);
      expect(
        canSplit([
          feat(new Point([0, 0])),
          feat(
            new LineString([
              [0, 0],
              [1, 1],
            ]),
          ),
        ]),
      ).toBe(true);
    });

    it('re-bases per-coordinate names across a MultiPolygon split (ring-closing reserved)', () => {
      const mp = new MultiPolygon([
        [
          [
            [0, 0],
            [1, 0],
            [1, 1],
            [0, 0],
          ],
        ],
        [
          [
            [5, 5],
            [6, 5],
            [6, 6],
            [5, 5],
          ],
        ],
      ]);
      const parts = splitFeature(feat(mp, { 'name:0:en': 'a0', 'name:4:en': 'b0' }))!;
      expect(parts).toHaveLength(2);
      expect(parts[0].getGeometry()).toBeInstanceOf(Polygon);
      expect(parts[0].get('name:0:en')).toBe('a0');
      // Triangle A spans breadth-first indices 0..3 (incl. reserved closing 3), so B starts at 4.
      expect(parts[1].get('name:0:en')).toBe('b0');
    });
  });

  describe('degenerate inputs are a no-op, never destructive', () => {
    it('split of an empty Multi-geometry / collection returns undefined (keeps the feature)', () => {
      expect(splitFeature(feat(new MultiPoint([])))).toBeUndefined();
      expect(splitFeature(feat(new GeometryCollection([])))).toBeUndefined();
    });

    it('convert of an empty source returns undefined instead of throwing', () => {
      expect(convertFeature(feat(new MultiPoint([])), 'Polygon')).toBeUndefined();
      expect(convertFeature(feat(new LineString([])), 'MultiPoint')).toBeUndefined();
      expect(canConvert(new Feature(), 'Polygon')).toBe(false);
    });

    it('merge skips a feature whose geometry is undefined', () => {
      const noGeom = new Feature();
      noGeom.setId('no-geom');
      const merged = mergeFeatures([noGeom, feat(new Point([1, 1]))])!;
      expect(merged.getGeometry()).toBeInstanceOf(MultiPoint);
      expect((merged.getGeometry() as MultiPoint).getPoints()).toHaveLength(1);
    });
  });
});
