import { TestBed } from '@angular/core/testing';
import type { Geometry } from 'geojson';
import { Point, Polygon } from 'ol/geom';
import { beforeEach, describe, expect, it } from 'vitest';

import type { GeometryFeature } from '../models/geometry.model';
import { GeometryConversionService } from './geometry-conversion.service';

function pointFeature(
  coord: [number, number],
  properties: Record<string, unknown> = {},
): GeometryFeature {
  return {
    type: 'Feature',
    geometry: { type: 'Point', coordinates: coord },
    properties,
  };
}

describe('GeometryConversionService', () => {
  let service: GeometryConversionService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(GeometryConversionService);
  });

  describe('olToGjFeature / olFeaturesToCollection (write-side, geometry-editor commit path)', () => {
    it('round-trips a Point feature back to 4326 GeoJSON', () => {
      const ol = service.gjToOlFeature(pointFeature([12, 55]));
      const gj = service.olToGjFeature(ol);
      expect(gj.geometry.type).toBe('Point');
      const [lon, lat] = (gj.geometry as unknown as { coordinates: [number, number] }).coordinates;
      expect(lon).toBeCloseTo(12, 6);
      expect(lat).toBeCloseTo(55, 6);
    });

    it('round-trips a GeometryCollection (OL v10 needs no ol.format.GeoJSON workaround)', () => {
      const ol = service.gjToOlFeature({
        type: 'Feature',
        properties: {},
        geometry: {
          type: 'GeometryCollection',
          geometries: [
            { type: 'Point', coordinates: [12, 55] },
            {
              type: 'LineString',
              coordinates: [
                [10, 55],
                [12, 57],
              ],
            },
          ],
        },
      });
      const gj = service.olToGjFeature(ol);
      expect(gj.geometry.type).toBe('GeometryCollection');
      const geoms = (gj.geometry as unknown as { geometries: { type: string }[] }).geometries;
      expect(geoms.map((g) => g.type)).toEqual(['Point', 'LineString']);
    });

    it('writes a FeatureCollection from a list of OL features', () => {
      const fc = service.olFeaturesToCollection([
        service.gjToOlFeature(pointFeature([12, 55])),
        service.gjToOlFeature(pointFeature([13, 56])),
      ]);
      expect(fc.type).toBe('FeatureCollection');
      expect(fc.features).toHaveLength(2);
    });

    it('strips the transient `selected` view flag so it never persists into saved geometry', () => {
      const ol = service.gjToOlFeature(pointFeature([12, 55]));
      ol.set('selected', true); // the editor sets this to drive selection styling
      const gj = service.olToGjFeature(ol);
      expect(gj.properties).not.toHaveProperty('selected');
    });

    it('normalizes null properties to {} (OL writes null for a propertyless feature; the backend NPEs on null)', () => {
      // A freshly-drawn point carries no non-geometry properties → OL's GeoJSON
      // writer emits `properties: null`, which 500s templates/execute server-side.
      const ol = service.gjToOlFeature(pointFeature([12, 55]));
      const gj = service.olToGjFeature(ol);
      expect(gj.properties).toEqual({});
    });
  });

  describe('gjToOlFeature / gjToOlGeometry', () => {
    it('reads a GeoJSON Point feature in 4326 and produces a feature in 3857', () => {
      const olFeature = service.gjToOlFeature(pointFeature([0, 0]));
      const geom = olFeature.getGeometry();
      expect(geom).toBeDefined();
      const coords = (geom as Point).getCoordinates();
      expect(coords[0]).toBeCloseTo(0, 4);
      expect(coords[1]).toBeCloseTo(0, 4);
    });

    it('produces a Geometry instance for a raw GeoJSON Polygon', () => {
      const polygon: Geometry = {
        type: 'Polygon',
        coordinates: [
          [
            [0, 0],
            [1, 0],
            [1, 1],
            [0, 1],
            [0, 0],
          ],
        ],
      };
      expect(service.gjToOlGeometry(polygon)).toBeInstanceOf(Polygon);
    });
  });

  describe('getGeometryCenter', () => {
    it('returns identity for a Point', () => {
      const point = new Point([100, 200]);
      expect(service.getGeometryCenter(point)).toEqual([100, 200]);
    });

    it('returns an interior point for a Polygon', () => {
      const polygon = new Polygon([
        [
          [0, 0],
          [10, 0],
          [10, 10],
          [0, 10],
          [0, 0],
        ],
      ]);
      const center = service.getGeometryCenter(polygon);
      expect(center[0]).toBeGreaterThan(0);
      expect(center[0]).toBeLessThan(10);
      expect(center[1]).toBeGreaterThan(0);
      expect(center[1]).toBeLessThan(10);
    });
  });
});
