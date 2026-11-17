import { Feature } from 'ol';
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
import { describe, expect, it } from 'vitest';

import { readCoordNames } from '../geometry-tree/coord-names';
import {
  geometryToTree,
  positionCoordinatesByIndex,
  treeToGeometry,
} from '../geometry-tree/geometry-tree-model';
import { convertFeature, mergeFeatures, splitFeature } from './geometry-ops';

/**
 * Properties of the geometry editor's merge, split and convert, over generated geometries from a
 * fixed seed. A position's name is stored as `name:<index>:<lang>` against
 * its breadth-first index, so every operation that re-partitions positions must move each name
 * with its position: the names a message gives its positions are printed beside them in the
 * message text and drawn on the map.
 *
 * One documented limit is pinned: a split of a polygon carries the names of the exterior ring only,
 * so names written at a hole's indexes do not stay with the hole's vertices (PF-1 in the hunt's
 * record, refuted as a fault). Nothing in the application can name a hole's vertex: the tree offers
 * a name field under an exterior ring only, the text editor refuses a polygon with a hole, and the
 * backend prints no name of a hole (see the note on `splitFeature`).
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

/** A coordinate in the map projection, distinct from every other one the generator makes. */
function coord(rng: Rng): Coordinate {
  return [Math.round(rng.next() * 4e7 - 2e7) + rng.next(), Math.round(rng.next() * 3e7 - 1.5e7)];
}

function coords(rng: Rng, min: number, max: number): Coordinate[] {
  return Array.from({ length: rng.int(min, max) }, () => coord(rng));
}

function ring(rng: Rng): Coordinate[] {
  const vertices = coords(rng, 3, 6);
  return [...vertices, vertices[0]];
}

function polygon(rng: Rng, holes: number): Polygon {
  return new Polygon([ring(rng), ...Array.from({ length: holes }, () => ring(rng))]);
}

type Kind =
  | 'Point'
  | 'LineString'
  | 'Polygon'
  | 'MultiPoint'
  | 'MultiLineString'
  | 'MultiPolygon'
  | 'GeometryCollection';

const KINDS: readonly Kind[] = [
  'Point',
  'LineString',
  'Polygon',
  'MultiPoint',
  'MultiLineString',
  'MultiPolygon',
  'GeometryCollection',
];

/** A geometry of `kind`; `maxHoles` caps the holes of every polygon in it. */
function geometry(rng: Rng, kind: Kind, maxHoles: number): Geometry {
  switch (kind) {
    case 'Point':
      return new Point(coord(rng));
    case 'LineString':
      return new LineString(coords(rng, 2, 6));
    case 'Polygon':
      return polygon(rng, rng.int(0, maxHoles));
    case 'MultiPoint':
      return new MultiPoint(coords(rng, 1, 4));
    case 'MultiLineString':
      return new MultiLineString(Array.from({ length: rng.int(1, 3) }, () => coords(rng, 2, 4)));
    case 'MultiPolygon':
      return new MultiPolygon(
        Array.from({ length: rng.int(1, 3) }, () =>
          polygon(rng, rng.int(0, maxHoles)).getCoordinates(),
        ),
      );
    case 'GeometryCollection':
      return new GeometryCollection(
        Array.from({ length: rng.int(1, 3) }, () =>
          geometry(rng, rng.pick(KINDS.slice(0, 6)), maxHoles),
        ),
      );
  }
}

let nameCounter = 0;

/** A feature of the geometry, naming about half of its positions in one or two languages. */
function namedFeature(rng: Rng, geom: Geometry): Feature {
  const feature = new Feature(geom);
  feature.setId(`f-${nameCounter++}`);
  for (const index of positionCoordinatesByIndex(geometryToTree(geom)).keys()) {
    if (rng.next() < 0.5) feature.set(`name:${index}:en`, `n${nameCounter++}`);
    if (rng.next() < 0.2) feature.set(`name:${index}:da`, `d${nameCounter++}`);
  }
  if (rng.next() < 0.3) feature.set('name:en', `whole ${nameCounter++}`);
  return feature;
}

const key = (c: Coordinate): string => `${c[0]},${c[1]}`;

/**
 * Every position name of the features as `coordinate|lang|name`, read the way the tree and the map
 * labels read them: the name at a position's breadth-first index. A name whose index is no position
 * is listed as `orphan|lang|name`.
 */
function namedPositions(features: readonly Feature[]): string[] {
  const out: string[] = [];
  for (const feature of features) {
    const geom = feature.getGeometry();
    if (!geom) continue;
    const positions = positionCoordinatesByIndex(geometryToTree(geom));
    for (const { coordIndex, lang, value } of readCoordNames(feature)) {
      const position = positions.get(coordIndex);
      out.push(`${position ? key(position) : 'orphan'}|${lang}|${String(value)}`);
    }
  }
  return out.sort();
}

function hasHole(geom: Geometry): boolean {
  return geom instanceof Polygon && geom.getLinearRingCount() > 1;
}

describe('geometry-ops, properties over generated geometries', () => {
  it('rebuilds every geometry from its tree with the same coordinates', () => {
    const rng = seeded(20261115);
    for (let i = 0; i < 2000; i++) {
      const geom = geometry(rng, rng.pick(KINDS), 2);
      const tree = geometryToTree(geom);
      const rebuilt = treeToGeometry(tree);
      expect(rebuilt.getType()).toBe(geom.getType());
      expect(JSON.stringify(geometryToTree(rebuilt))).toBe(JSON.stringify(tree));
    }
  });

  it('keeps every position name on its position through a split, for every geometry but a polygon with a hole', () => {
    const rng = seeded(20261116);
    let checked = 0;
    for (let i = 0; i < 3000; i++) {
      const geom = geometry(rng, rng.pick(KINDS.slice(1)), 0);
      const feature = namedFeature(rng, geom);
      const parts = splitFeature(feature);
      if (!parts) continue;
      expect(namedPositions(parts), geom.getType()).toEqual(namedPositions([feature]));
      checked++;
    }
    expect(checked).toBeGreaterThan(2500);
  });

  it('keeps every position name through a split of a multi-polygon or collection whose polygons have holes', () => {
    const rng = seeded(20261117);
    for (let i = 0; i < 1500; i++) {
      const geom = geometry(rng, rng.pick(['MultiPolygon', 'GeometryCollection'] as const), 2);
      const feature = namedFeature(rng, geom);
      const parts = splitFeature(feature);
      expect(parts).toBeDefined();
      expect(namedPositions(parts ?? [])).toEqual(namedPositions([feature]));
    }
  });

  it('carries only the exterior ring names through a split of a polygon — a documented limit, nothing names a hole vertex (PF-1)', () => {
    // The smallest case: a triangle with a triangular hole, each hole vertex named by hand (no editor
    // can do it). The exterior's closing vertex keeps index 3, so the hole's vertices are named 4, 5
    // and 6; the split counts one index per vertex point and hands out 3, 4 and 5.
    const outer: Coordinate[] = [
      [0, 0],
      [100, 0],
      [0, 100],
      [0, 0],
    ];
    const hole: Coordinate[] = [
      [10, 10],
      [20, 10],
      [10, 20],
      [10, 10],
    ];
    const feature = new Feature(new Polygon([outer, hole]));
    feature.set('name:4:en', 'A');
    feature.set('name:5:en', 'B');
    feature.set('name:6:en', 'C');

    const parts = splitFeature(feature) ?? [];
    expect(parts).toHaveLength(6);
    const nameAt = (point: Feature): string | undefined => point.get('name:0:en') as string;
    // The hole's names are not kept on their vertices: A lands on the hole's second vertex, B on its
    // third, C on nothing.
    expect(parts.slice(3).map(nameAt)).toEqual([undefined, 'A', 'B']);

    // And over generated polygons: every one with a hand-named hole vertex loses or moves a name.
    const rng = seeded(20261118);
    let named = 0;
    let moved = 0;
    for (let i = 0; i < 1000; i++) {
      const geom = polygon(rng, rng.int(1, 2));
      const generated = namedFeature(rng, geom);
      const exteriorSlots = geom.getLinearRing(0)?.getCoordinates().length ?? 0;
      if (!readCoordNames(generated).some(({ coordIndex }) => coordIndex >= exteriorSlots)) {
        continue;
      }
      named++;
      const split = splitFeature(generated) ?? [];
      if (JSON.stringify(namedPositions(split)) !== JSON.stringify(namedPositions([generated]))) {
        moved++;
      }
      expect(hasHole(geom)).toBe(true);
    }
    expect(named).toBeGreaterThan(900);
    expect(moved).toBe(named);
  });

  it('keeps every position name on its position through a merge', () => {
    const rng = seeded(20261119);
    for (let i = 0; i < 2000; i++) {
      const features = Array.from({ length: rng.int(2, 4) }, () =>
        namedFeature(rng, geometry(rng, rng.pick(KINDS), 2)),
      );
      const merged = mergeFeatures(features);
      expect(merged).toBeDefined();
      expect(namedPositions(merged ? [merged] : [])).toEqual(namedPositions(features));
    }
  });

  it('gives back each feature with its own position names when a merge is split again', () => {
    // A selection of one single type merges into a Multi*, a selection of several families into a
    // collection that keeps each geometry whole; a split of either hands the parts back.
    const rng = seeded(20261120);
    for (let i = 0; i < 1500; i++) {
      const sameType = rng.next() < 0.5;
      const single = rng.pick(['Point', 'LineString', 'Polygon'] as const);
      const features = Array.from({ length: rng.int(2, 4) }, (_, n) => {
        const kind = sameType ? single : n === 0 ? 'Point' : rng.pick(KINDS.slice(1, 6));
        return namedFeature(rng, geometry(rng, kind, 2));
      });
      // A mixed selection of one family (a point and a multi-point) merges flat into a Multi*.
      const families = new Set(
        features.map((f) => f.getGeometry()?.getType().replace('Multi', '')),
      );
      if (!sameType && families.size === 1) continue;
      const parts = splitFeature(mergeFeatures(features) as Feature) ?? [];
      expect(parts).toHaveLength(features.length);
      parts.forEach((part, n) => {
        const coordNames = (f: Feature) =>
          readCoordNames(f)
            .map(({ key: k, value }) => `${k}=${String(value)}`)
            .sort();
        expect(coordNames(part)).toEqual(coordNames(features[n]));
        expect(part.getGeometry()?.getType()).toBe(features[n].getGeometry()?.getType());
      });
    }
  });

  it('keeps every position name through a convert between line, points and a polygon without a hole', () => {
    const rng = seeded(20261121);
    for (let i = 0; i < 2000; i++) {
      const from = rng.pick(['LineString', 'MultiPoint', 'Polygon'] as const);
      const geom =
        from === 'Polygon'
          ? polygon(rng, 0)
          : from === 'LineString'
            ? new LineString(coords(rng, 3, 6))
            : new MultiPoint(coords(rng, 3, 6));
      const feature = namedFeature(rng, geom);
      for (const to of ['LineString', 'MultiPoint', 'Polygon'] as const) {
        if (to === from) continue;
        const converted = convertFeature(feature, to);
        expect(converted, `${from} to ${to}`).toBeDefined();
        expect(namedPositions(converted ? [converted] : [])).toEqual(namedPositions([feature]));
        // And back: the same coordinates in the same order.
        const back = converted ? convertFeature(converted, from) : undefined;
        const coordinatesOf = (g: Geometry | undefined) =>
          JSON.stringify((g as LineString | MultiPoint | Polygon | undefined)?.getCoordinates());
        expect(coordinatesOf(back?.getGeometry())).toBe(coordinatesOf(geom));
      }
    }
  });
});
