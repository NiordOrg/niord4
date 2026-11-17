import { Feature } from 'ol';
import { GeoJSON } from 'ol/format';
import { GeometryCollection, LineString, Point, Polygon } from 'ol/geom';
import { fromLonLat, transform } from 'ol/proj';
import { describe, expect, it } from 'vitest';

import { hasPositionOffTheMap, shiftOntoGlobe, wholeTurnsOffGlobe } from './longitude-turns';

/**
 * The lon/lat of a map coordinate as it is, without wrapping: `toLonLat` would wrap the
 * longitude into [-180, 180] and hide a position off the globe (the backend gets it unwrapped).
 */
function lonLatOf(coordinate: number[]): number[] {
  return transform(coordinate, 'EPSG:3857', 'EPSG:4326');
}

/** The lon/lat of every vertex of a line or a polygon's outer ring, rounded to 6 decimals. */
function lonLats(coordinates: number[][]): number[][] {
  return coordinates.map((coordinate) =>
    lonLatOf(coordinate).map((n) => Math.round(n * 1e6) / 1e6),
  );
}

function line(...points: [number, number][]): LineString {
  return new LineString(points.map((point) => fromLonLat(point)));
}

/** A feature as OL's drag-and-drop reads it from a GeoJSON file: into the map's projection. */
function readGeoJson(geometry: object): Feature {
  const [feature] = new GeoJSON().readFeatures(
    { type: 'Feature', geometry, properties: {} },
    { dataProjection: 'EPSG:4326', featureProjection: 'EPSG:3857' },
  );
  return feature as Feature;
}

describe('longitude-turns', () => {
  describe('shiftOntoGlobe', () => {
    it('brings a point drawn on the world copy to the east back by one turn: 190 E becomes 170 W', () => {
      const point = new Point(fromLonLat([190, 55]));
      expect(shiftOntoGlobe(point)).toBe(true);
      const [lon, lat] = lonLatOf(point.getCoordinates());
      expect(lon).toBeCloseTo(-170, 9);
      expect(lat).toBeCloseTo(55, 9);
    });

    it('brings a point on the world copy to the west forward by one turn: 190 W becomes 170 E', () => {
      const point = new Point(fromLonLat([-190, 55]));
      expect(shiftOntoGlobe(point)).toBe(true);
      expect(lonLatOf(point.getCoordinates())[0]).toBeCloseTo(170, 9);
    });

    it('takes back several whole turns at once (a position ten turns west of its place)', () => {
      const point = new Point(fromLonLat([12 - 3600, 55]));
      expect(shiftOntoGlobe(point)).toBe(true);
      expect(lonLatOf(point.getCoordinates())[0]).toBeCloseTo(12, 6);
    });

    it('moves a whole geometry by the same turns, so its shape is kept', () => {
      const geometry = line([365, 54], [372, 56]);
      expect(shiftOntoGlobe(geometry)).toBe(true);
      expect(lonLats(geometry.getCoordinates())).toEqual([
        [5, 54],
        [12, 56],
      ]);
    });

    it('keeps a geometry that crosses the antimeridian whole, when its middle lies on the globe', () => {
      // From 170 E eastwards to 185 E (= 175 W): the middle, 177.5 E, is on the globe.
      const geometry = line([170, 60], [185, 60]);
      expect(shiftOntoGlobe(geometry)).toBe(false);
      expect(lonLats(geometry.getCoordinates())).toEqual([
        [170, 60],
        [185, 60],
      ]);
    });

    it('turns a geometry across the antimeridian whole when its middle is off the globe', () => {
      // From 178 E to 190 E: the middle, 184 E, is off the globe; one turn west keeps the shape.
      const geometry = line([178, 60], [190, 60]);
      expect(shiftOntoGlobe(geometry)).toBe(true);
      expect(lonLats(geometry.getCoordinates())).toEqual([
        [-182, 60],
        [-170, 60],
      ]);
    });

    it('puts a middle on the antimeridian itself at 180 W, as the backend does ([-180, 180))', () => {
      const point = new Point(fromLonLat([180, 0]));
      expect(shiftOntoGlobe(point)).toBe(true);
      expect(lonLatOf(point.getCoordinates())[0]).toBeCloseTo(-180, 9);
      const west = new Point(fromLonLat([-180, 0]));
      expect(shiftOntoGlobe(west)).toBe(false);
    });

    it('leaves a geometry on the globe as it is', () => {
      const polygon = new Polygon([
        [fromLonLat([10, 55]), fromLonLat([12, 55]), fromLonLat([12, 57]), fromLonLat([10, 55])],
      ]);
      const before = polygon.getCoordinates();
      const revision = polygon.getRevision();
      expect(shiftOntoGlobe(polygon)).toBe(false);
      expect(polygon.getCoordinates()).toEqual(before);
      expect(polygon.getRevision()).toBe(revision);
    });

    it('shifts a geometry collection as one geometry', () => {
      const collection = new GeometryCollection([
        new Point(fromLonLat([370, 55])),
        line([365, 54], [372, 56]),
      ]);
      expect(shiftOntoGlobe(collection)).toBe(true);
      const [point, path] = collection.getGeometries() as [Point, LineString];
      expect(lonLatOf(point.getCoordinates())[0]).toBeCloseTo(10, 9);
      expect(lonLats(path.getCoordinates())).toEqual([
        [5, 54],
        [12, 56],
      ]);
    });

    it('brings a dropped GeoJSON file with a longitude beyond 180 onto the globe', () => {
      const feature = readGeoJson({ type: 'Point', coordinates: [190, 55] });
      const point = feature.getGeometry() as Point;
      expect(lonLatOf(point.getCoordinates())[0]).toBeCloseTo(190, 9); // OL reads it as it is
      expect(shiftOntoGlobe(point)).toBe(true);
      expect(lonLatOf(point.getCoordinates())[0]).toBeCloseTo(-170, 9);
    });
  });

  describe('wholeTurnsOffGlobe', () => {
    it('counts the whole turns by the middle of the longitude extent', () => {
      expect(wholeTurnsOffGlobe(new Point(fromLonLat([10, 0])))).toBe(0);
      expect(wholeTurnsOffGlobe(new Point(fromLonLat([190, 0])))).toBe(1);
      expect(wholeTurnsOffGlobe(new Point(fromLonLat([-190, 0])))).toBe(-1);
      expect(wholeTurnsOffGlobe(new Point(fromLonLat([12 - 3600, 0])))).toBe(-10);
    });

    it('counts no turn for a geometry without a position (an empty or unreadable one)', () => {
      expect(wholeTurnsOffGlobe(new LineString([]))).toBe(0);
      expect(wholeTurnsOffGlobe(new Point([Number.NaN, 0]))).toBe(0);
    });
  });

  describe('hasPositionOffTheMap', () => {
    it('finds a latitude beyond 90 in a dropped file: the map projection reads it as no number', () => {
      const feature = readGeoJson({
        type: 'LineString',
        coordinates: [
          [10, 55],
          [12, 95],
        ],
      });
      expect(hasPositionOffTheMap(feature.getGeometry()!)).toBe(true);
      expect(
        hasPositionOffTheMap(readGeoJson({ type: 'Point', coordinates: [10, -95] }).getGeometry()!),
      ).toBe(true);
    });

    it('looks into every member of a geometry collection', () => {
      const collection = new GeometryCollection([
        new Point(fromLonLat([10, 55])),
        new Point([0, Number.NaN]),
      ]);
      expect(hasPositionOffTheMap(collection)).toBe(true);
    });

    it('finds nothing in a geometry on the globe, nor in one only off by whole turns', () => {
      expect(hasPositionOffTheMap(line([10, 55], [12, 90]))).toBe(false);
      expect(hasPositionOffTheMap(new Point(fromLonLat([190, 55])))).toBe(false);
    });
  });
});
