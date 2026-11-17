import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { MapService } from './map.service';
import { provideTestNiordConfig } from '../../testing/test-config';
import Feature from 'ol/Feature';
import { Point, Polygon, MultiPolygon, LineString } from 'ol/geom';
import { fromLonLat } from 'ol/proj';

describe('MapService', () => {
  let service: MapService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideTestNiordConfig(),
        {
          provide: TranslocoService,
          useValue: { setActiveLang: vi.fn(), setAvailableLangs: vi.fn() },
        },
      ],
    });
    service = TestBed.inject(MapService);
  });

  describe('getGeometryCenter()', () => {
    it('should return coordinates for Point', () => {
      const point = new Point([12, 56]);
      expect(service.getGeometryCenter(point)).toEqual([12, 56]);
    });

    it('should return interior point for Polygon', () => {
      const poly = new Polygon([
        [
          [0, 0],
          [10, 0],
          [10, 10],
          [0, 10],
          [0, 0],
        ],
      ]);
      const center = service.getGeometryCenter(poly);
      expect(center).toBeDefined();
      expect(center!.length).toBeGreaterThanOrEqual(2);
    });

    it('should return interior of largest polygon for MultiPolygon', () => {
      const mp = new MultiPolygon([
        [
          [
            [0, 0],
            [1, 0],
            [1, 1],
            [0, 1],
            [0, 0],
          ],
        ],
        [
          [
            [0, 0],
            [10, 0],
            [10, 10],
            [0, 10],
            [0, 0],
          ],
        ],
      ]);
      const center = service.getGeometryCenter(mp);
      expect(center).toBeDefined();
    });

    it('should return extent center for LineString', () => {
      const line = new LineString([
        [0, 0],
        [10, 10],
      ]);
      const center = service.getGeometryCenter(line);
      expect(center).toBeDefined();
      expect(center![0]).toBeCloseTo(5, 0);
    });

    it('should return undefined for unknown geometry type', () => {
      const fakeGeom = { getType: () => 'UnknownType' } as never;
      expect(service.getGeometryCenter(fakeGeom)).toBeUndefined();
    });
  });

  describe('gjToOlFeature()', () => {
    it('should convert GeoJSON feature to OL Feature', () => {
      const gj = {
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [12, 56] },
        properties: {},
      };
      const olFeature = service.gjToOlFeature(gj);
      expect(olFeature).toBeInstanceOf(Feature);
      expect(olFeature.getGeometry()).toBeDefined();
    });

    it('should read positions as longitude and latitude and draw them in Web Mercator', () => {
      const gj = {
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [12, 56] },
        properties: {},
      };
      const point = service.gjToOlFeature(gj).getGeometry() as Point;
      expect(point.getCoordinates()).toEqual(fromLonLat([12, 56]));
    });
  });

  describe('serializeReadableCoordinates()', () => {
    it('should serialize Point Feature coordinates', () => {
      const feature = {
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [12.5, 56.3] },
        properties: {},
      };
      const coords: { lon: number; lat: number; index: number; name?: string }[] = [];
      service.serializeReadableCoordinates(feature, coords);
      expect(coords).toHaveLength(1);
      expect(coords[0].lon).toBe(12.5);
      expect(coords[0].lat).toBe(56.3);
    });

    it('should serialize Polygon interior ring (skip exterior/closing)', () => {
      const polygon = {
        type: 'Polygon',
        coordinates: [
          [
            [0, 0],
            [10, 0],
            [10, 10],
            [0, 10],
            [0, 0],
          ],
        ],
      };
      const coords: { lon: number; lat: number; index: number }[] = [];
      service.serializeReadableCoordinates(polygon, coords);
      // Interior ring: first 4 points included, last point (closing) excluded via Exterior flag
      expect(coords.length).toBe(4);
    });

    it('should serialize FeatureCollection', () => {
      const fc = {
        type: 'FeatureCollection',
        features: [
          { type: 'Feature', geometry: { type: 'Point', coordinates: [1, 2] }, properties: {} },
          { type: 'Feature', geometry: { type: 'Point', coordinates: [3, 4] }, properties: {} },
        ],
      };
      const coords: { lon: number; lat: number; index: number }[] = [];
      service.serializeReadableCoordinates(fc, coords);
      expect(coords).toHaveLength(2);
    });

    it('should serialize MultiPolygon', () => {
      const mp = {
        type: 'MultiPolygon',
        coordinates: [
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
              [2, 2],
              [3, 2],
              [3, 3],
              [2, 2],
            ],
          ],
        ],
      };
      const coords: { lon: number; lat: number; index: number }[] = [];
      service.serializeReadableCoordinates(mp, coords);
      expect(coords.length).toBeGreaterThan(0);
    });

    it('should exclude buffer features (parentFeatureIds)', () => {
      const feature = {
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [12, 56] },
        properties: { parentFeatureIds: 'some-id' },
      };
      const coords: { lon: number; lat: number; index: number }[] = [];
      service.serializeReadableCoordinates(feature, coords);
      expect(coords).toHaveLength(0);
    });

    it('should exclude affected area (restriction=affected)', () => {
      const feature = {
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [12, 56] },
        properties: { restriction: 'affected' },
      };
      const coords: { lon: number; lat: number; index: number }[] = [];
      service.serializeReadableCoordinates(feature, coords);
      expect(coords).toHaveLength(0);
    });
  });
});
