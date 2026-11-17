import type { Geometry } from 'geojson';
import { describe, expect, it } from 'vitest';

import type { GeometryFeature, GeometryFeatureCollection } from '../models/geometry.model';
import { geometryCollectionsEqual } from './geometry-equals';

/**
 * Properties of the geometry editor's "nothing changed" test, over generated collections from a
 * fixed seed (error hunt, phase 2): equality is reflexive and symmetric; the floating-point noise of
 * the editor's projection round trip is no change; a moved position, a renamed position, an added or
 * removed feature, and a changed type each are a change; the order of property keys and the editor's
 * `selected` flag are not.
 */

/** A small seeded generator (mulberry32): the same seed gives the same inputs on every run. */
function seeded(seed: number) {
  let state = seed >>> 0;
  const next = (): number => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  const int = (min: number, max: number): number => min + Math.floor(next() * (max - min + 1));
  const pick = <T>(items: readonly T[]): T => items[Math.floor(next() * items.length)];
  return { next, int, pick };
}

type Rng = ReturnType<typeof seeded>;

const position = (rng: Rng): number[] => [rng.next() * 360 - 180, rng.next() * 170 - 85];
const positions = (rng: Rng, n: number): number[][] =>
  Array.from({ length: n }, () => position(rng));

function geometry(rng: Rng): Geometry {
  switch (rng.int(0, 3)) {
    case 0:
      return { type: 'Point', coordinates: position(rng) };
    case 1:
      return { type: 'LineString', coordinates: positions(rng, rng.int(2, 5)) };
    case 2: {
      const ring = positions(rng, rng.int(3, 5));
      return { type: 'Polygon', coordinates: [[...ring, ring[0]]] };
    }
    default:
      return { type: 'MultiPoint', coordinates: positions(rng, rng.int(1, 4)) };
  }
}

function collection(rng: Rng): GeometryFeatureCollection {
  const features: GeometryFeature[] = Array.from({ length: rng.int(1, 4) }, (_, n) => ({
    type: 'Feature',
    id: `f${n}`,
    geometry: geometry(rng),
    properties: {
      'name:en': `pos ${rng.int(1, 99)}`,
      'name:0:da': `p ${n}`,
      restriction: rng.pick(['', 'affected']),
    },
  }));
  return { type: 'FeatureCollection', features };
}

/** The same collection with every number moved by `noise` and property keys in reverse order. */
function perturbed(
  c: GeometryFeatureCollection,
  noise: (v: number) => number,
): GeometryFeatureCollection {
  const walk = (value: unknown): unknown =>
    typeof value === 'number' ? noise(value) : Array.isArray(value) ? value.map(walk) : value;
  return {
    ...c,
    features: c.features.map((f) => ({
      ...f,
      geometry: {
        ...f.geometry,
        coordinates: walk((f.geometry as { coordinates: unknown }).coordinates),
      } as Geometry,
      properties: Object.fromEntries(Object.entries(f.properties ?? {}).reverse()),
    })),
  };
}

describe('geometryCollectionsEqual, properties over generated collections', () => {
  it('is reflexive and symmetric, and sees no change in projection noise, key order or the selected flag', () => {
    const rng = seeded(20261115);
    for (let i = 0; i < 3000; i++) {
      const a = collection(rng);
      const b = perturbed(a, (v) => v + (rng.next() - 0.5) * 2e-9);
      const selected: GeometryFeatureCollection = {
        ...b,
        features: b.features.map((f) => ({
          ...f,
          properties: { ...f.properties, selected: true },
        })),
      };
      expect(geometryCollectionsEqual(a, a)).toBe(true);
      expect(geometryCollectionsEqual(a, b)).toBe(true);
      expect(geometryCollectionsEqual(b, a)).toBe(true);
      expect(geometryCollectionsEqual(a, selected)).toBe(true);
    }
    expect(geometryCollectionsEqual(undefined, { type: 'FeatureCollection', features: [] })).toBe(
      true,
    );
  });

  it('sees a moved position, a renamed position, an added or removed feature and a changed type', () => {
    const rng = seeded(20261116);
    for (let i = 0; i < 3000; i++) {
      const a = collection(rng);
      const index = rng.int(0, a.features.length - 1);
      const target = a.features[index];
      let hit = false;
      const moved = perturbed(a, (v) => {
        if (hit) return v;
        hit = true;
        return v + rng.pick([1e-6, -1e-6, 1e-3, 1]);
      });
      const renamed: GeometryFeatureCollection = {
        ...a,
        features: a.features.map((f, n) =>
          n === index ? { ...f, properties: { ...f.properties, 'name:0:da': 'other' } } : f,
        ),
      };
      const fewer: GeometryFeatureCollection = {
        ...a,
        features: a.features.filter((_, n) => n !== index),
      };
      const more: GeometryFeatureCollection = { ...a, features: [...a.features, target] };
      const retyped: GeometryFeatureCollection = {
        ...a,
        features: a.features.map((f, n) =>
          n === index && f.geometry?.type === 'LineString'
            ? { ...f, geometry: { type: 'MultiPoint', coordinates: f.geometry.coordinates } }
            : f,
        ),
      };
      expect(geometryCollectionsEqual(a, moved)).toBe(false);
      expect(geometryCollectionsEqual(a, renamed)).toBe(false);
      expect(geometryCollectionsEqual(a, fewer)).toBe(false);
      expect(geometryCollectionsEqual(a, more)).toBe(false);
      expect(geometryCollectionsEqual(a, retyped)).toBe(target.geometry?.type !== 'LineString');
    }
  });
});
