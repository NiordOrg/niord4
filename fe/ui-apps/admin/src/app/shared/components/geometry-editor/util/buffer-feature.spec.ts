import { Feature } from 'ol';
import { LineString, MultiPoint, Point } from 'ol/geom';
import { fromLonLat } from 'ol/proj';
import { describe, expect, it } from 'vitest';

import {
  canAddBuffer,
  computeBufferGeometry,
  createBufferFeature,
  isBufferFeature,
} from './buffer-feature';

function point(lon: number, lat: number): Feature {
  const feature = new Feature(new Point(fromLonLat([lon, lat])));
  feature.setId(`p-${lon}-${lat}`); // no comma — parentFeatureIds is comma-joined
  return feature;
}

function lineFeature(id = 'line-1'): Feature {
  const feature = new Feature(new LineString([fromLonLat([10, 55]), fromLonLat([12, 57])]));
  feature.setId(id);
  return feature;
}

function isPolygonal(type: string | undefined): boolean {
  return type === 'Polygon' || type === 'MultiPolygon';
}

describe('buffer-feature', () => {
  describe('canAddBuffer', () => {
    it('radius needs ≥ 1 point, path ≥ 2, area ≥ 3', () => {
      const pts = [point(10, 55), point(11, 56), point(12, 57)];
      expect(canAddBuffer('radius', [pts[0]])).toBe(true);
      expect(canAddBuffer('radius', [])).toBe(false);
      expect(canAddBuffer('path', pts.slice(0, 1))).toBe(false);
      expect(canAddBuffer('path', pts.slice(0, 2))).toBe(true);
      expect(canAddBuffer('area', pts.slice(0, 2))).toBe(false);
      expect(canAddBuffer('area', pts)).toBe(true);
    });

    it('radius accepts any geometry (line/polygon); path & area still require points', () => {
      const line = lineFeature();
      expect(canAddBuffer('radius', [line])).toBe(true);
      expect(canAddBuffer('path', [line, lineFeature('line-2')])).toBe(false);
      expect(canAddBuffer('area', [line, lineFeature('l2'), lineFeature('l3')])).toBe(false);
    });
  });

  describe('createBufferFeature', () => {
    it('creates a radius buffer with affected + parent ids + a circle', () => {
      const parent = point(10, 55);
      const buffer = createBufferFeature([parent], 'radius', [parent]);
      expect(buffer).toBeDefined();
      expect(isBufferFeature(buffer!)).toBe(true);
      expect(buffer!.get('restriction')).toBe('affected');
      expect(buffer!.get('bufferType')).toBe('radius');
      expect(buffer!.get('parentFeatureIds')).toBe(parent.getId());
      expect(buffer!.get('bufferRadius')).toBe(1);
      expect(buffer!.getGeometry()?.getType()).toBe('Polygon');
    });

    it('creates a path (LineString) and an area (Polygon)', () => {
      const pts = [point(10, 55), point(11, 56), point(12, 57)];
      expect(createBufferFeature(pts, 'path', pts)!.getGeometry()?.getType()).toBe('LineString');
      expect(createBufferFeature(pts, 'area', pts)!.getGeometry()?.getType()).toBe('Polygon');
    });

    it('creates a radius buffer around a non-point parent (line → buffered polygon)', () => {
      const line = lineFeature();
      const buffer = createBufferFeature([line], 'radius', [line])!;
      expect(buffer.get('bufferType')).toBe('radius');
      expect(isPolygonal(buffer.getGeometry()?.getType())).toBe(true);
    });
  });

  describe('computeBufferGeometry (recompute + cascade)', () => {
    it('returns undefined when no parent remains — the cascade-delete trigger', () => {
      const parent = point(10, 55);
      const buffer = createBufferFeature([parent], 'radius', [parent])!;
      expect(computeBufferGeometry(buffer, [])).toBeUndefined();
    });

    it('round-trip: a radius buffer on a NON-point parent survives recompute (not cascade-deleted)', () => {
      // The fixed bug: a line/polygon parent was ignored on recompute → no parent "survived" →
      // undefined → the affected area was silently cascade-deleted.
      const line = lineFeature();
      const buffer = createBufferFeature([line], 'radius', [line])!;
      const recomputed = computeBufferGeometry(buffer, [line]);
      expect(recomputed).toBeDefined();
      expect(isPolygonal(recomputed!.getType())).toBe(true);
    });

    it('recenters the buffer when its parent moves', () => {
      const parent = point(10, 55);
      const buffer = createBufferFeature([parent], 'radius', [parent])!;
      const before = buffer.getGeometry()!.getExtent();
      parent.setGeometry(new Point(fromLonLat([15, 60])));
      const moved = computeBufferGeometry(buffer, [parent])!;
      expect(moved.getExtent()).not.toEqual(before);
    });

    it('keeps a bare Point (never undefined) when the radius is 0 — a zero radius never deletes the buffer', () => {
      const parent = point(10, 55);
      const buffer = createBufferFeature([parent], 'radius', [parent])!;
      buffer.set('bufferRadius', 0);
      expect(computeBufferGeometry(buffer, [parent])).toBeInstanceOf(Point);
    });

    it('keeps a bare MultiPoint for a radius-0 buffer spanning several parents', () => {
      const pts = [point(10, 55), point(11, 56)];
      const buffer = createBufferFeature(pts, 'radius', pts)!;
      buffer.set('bufferRadius', 0);
      expect(computeBufferGeometry(buffer, pts)).toBeInstanceOf(MultiPoint);
    });

    it('keeps a bare geometry when an area buffer drops below 3 parents, then rebuilds it', () => {
      const pts = [point(10, 55), point(11, 56), point(12, 57)];
      const buffer = createBufferFeature(pts, 'area', pts)!;
      expect(buffer.getGeometry()?.getType()).toBe('Polygon');
      // Delete one parent → 2 survivors (below the area minimum): keep, don't delete.
      expect(computeBufferGeometry(buffer, pts.slice(0, 2))).toBeInstanceOf(MultiPoint);
      // All three back → rebuilt as the declared Polygon.
      expect(computeBufferGeometry(buffer, pts)?.getType()).toBe('Polygon');
    });

    it('offsets a path/area buffer outward when a positive radius is set', () => {
      const pts = [point(10, 55), point(11, 56), point(12, 57)];

      const pathBuf = createBufferFeature(pts, 'path', pts)!;
      expect(computeBufferGeometry(pathBuf, pts)?.getType()).toBe('LineString'); // no radius → bare
      pathBuf.set('bufferRadius', 1);
      pathBuf.set('bufferRadiusType', 'nm');
      expect(isPolygonal(computeBufferGeometry(pathBuf, pts)?.getType())).toBe(true); // → corridor

      const areaBuf = createBufferFeature(pts, 'area', pts)!;
      expect(computeBufferGeometry(areaBuf, pts)?.getType()).toBe('Polygon'); // no radius → bare ring
      areaBuf.set('bufferRadius', 1);
      areaBuf.set('bufferRadiusType', 'nm');
      expect(isPolygonal(computeBufferGeometry(areaBuf, pts)?.getType())).toBe(true); // → expanded
    });

    it('orders path vertices by the stored selection order, not the source order', () => {
      const a = point(10, 55);
      const b = point(11, 56);
      const c = point(12, 57);
      // Selection order a→b→c is captured in parentFeatureIds at creation.
      const buffer = createBufferFeature([a, b, c], 'path', [a, b, c])!;
      // Recompute with `all` in a different (source) order — the path must still follow a→b→c.
      const line = computeBufferGeometry(buffer, [c, b, a]) as LineString;
      expect(line.getCoordinates()).toEqual([
        (a.getGeometry() as Point).getCoordinates(),
        (b.getGeometry() as Point).getCoordinates(),
        (c.getGeometry() as Point).getCoordinates(),
      ]);
    });
  });
});
