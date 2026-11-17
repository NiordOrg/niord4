import { TestBed } from '@angular/core/testing';
import type { FeatureCollection, Geometry } from 'geojson';
import { beforeEach, describe, expect, it } from 'vitest';

import type { Message } from '../models/message.model';
import { MessageGeometryService } from './message-geometry.service';

function pointFeature(
  coord: [number, number],
  properties: Record<string, unknown> = {},
): FeatureCollection<Geometry, Record<string, unknown>>['features'][number] {
  return {
    type: 'Feature',
    geometry: { type: 'Point', coordinates: coord },
    properties,
  };
}

function withGeometry(
  features: FeatureCollection<Geometry, Record<string, unknown>>['features'],
): Message {
  return {
    id: 'msg-1',
    parts: [
      {
        geometry: {
          type: 'FeatureCollection',
          features,
        },
      },
    ],
  };
}

describe('MessageGeometryService', () => {
  let service: MessageGeometryService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(MessageGeometryService);
  });

  describe('getFeatures', () => {
    it('walks parts[].geometry.features and returns the flat list', () => {
      const msg = withGeometry([pointFeature([12, 55]), pointFeature([13, 56])]);
      expect(service.getFeatures(msg)).toHaveLength(2);
    });

    it('returns an empty list when message has no parts', () => {
      expect(service.getFeatures({ id: 'msg-empty' })).toEqual([]);
    });

    it('returns an empty list when parts have no geometry', () => {
      expect(service.getFeatures({ id: 'msg-empty', parts: [{}] })).toEqual([]);
    });

    it('filters features tagged restriction=affected when excludeAffectedAreas is true', () => {
      const msg = withGeometry([
        pointFeature([12, 55]),
        pointFeature([13, 56], { restriction: 'affected' }),
      ]);
      expect(service.getFeatures(msg, { excludeAffectedAreas: true })).toHaveLength(1);
      expect(service.getFeatures(msg)).toHaveLength(2);
    });
  });

  describe('serializeReadableCoordinates', () => {
    it('returns an empty list when properties.parentFeatureIds is set (buffered geometry)', () => {
      const feature = pointFeature([12, 55], { parentFeatureIds: 'fid-1' });
      expect(service.serializeReadableCoordinates(feature)).toEqual([]);
    });

    it('returns an empty list when properties.restriction=affected', () => {
      const feature = pointFeature([12, 55], { restriction: 'affected' });
      expect(service.serializeReadableCoordinates(feature)).toEqual([]);
    });

    it('serializes a Point feature as a single coord', () => {
      const result = service.serializeReadableCoordinates(pointFeature([12, 55]));
      expect(result).toHaveLength(1);
      expect(result[0]).toEqual({ lonLat: [12, 55], index: 0, name: undefined });
    });

    it('omits the closing coord of a Polygon linear ring', () => {
      const polygon: FeatureCollection<Geometry, Record<string, unknown>>['features'][number] = {
        type: 'Feature',
        geometry: {
          type: 'Polygon',
          coordinates: [
            [
              [0, 0],
              [1, 0],
              [1, 1],
              [0, 0],
            ],
          ],
        },
        properties: {},
      };
      const result = service.serializeReadableCoordinates(polygon);
      expect(result).toHaveLength(3);
      expect(result.map((r) => r.lonLat)).toEqual([
        [0, 0],
        [1, 0],
        [1, 1],
      ]);
    });

    it('skips interior rings of a Polygon', () => {
      const polygon: FeatureCollection<Geometry, Record<string, unknown>>['features'][number] = {
        type: 'Feature',
        geometry: {
          type: 'Polygon',
          coordinates: [
            [
              [0, 0],
              [10, 0],
              [10, 10],
              [0, 10],
              [0, 0],
            ],
            [
              [2, 2],
              [4, 2],
              [4, 4],
              [2, 2],
            ],
          ],
        },
        properties: {},
      };
      const result = service.serializeReadableCoordinates(polygon);
      expect(result).toHaveLength(4);
    });

    it('picks up per-coord name labels via name:<idx>:<lang> keys', () => {
      const line: FeatureCollection<Geometry, Record<string, unknown>>['features'][number] = {
        type: 'Feature',
        geometry: {
          type: 'LineString',
          coordinates: [
            [0, 0],
            [1, 1],
          ],
        },
        properties: {
          'name:0:en': 'Start',
          'name:1:en': 'End',
        },
      };
      const result = service.serializeReadableCoordinates(line);
      expect(result.map((r) => r.name)).toEqual(['Start', 'End']);
    });

    describe('language fallback', () => {
      function bilingualLine(
        extras: Record<string, unknown> = {},
      ): FeatureCollection<Geometry, Record<string, unknown>>['features'][number] {
        return {
          type: 'Feature',
          geometry: {
            type: 'LineString',
            coordinates: [
              [0, 0],
              [1, 1],
            ],
          },
          properties: {
            'name:0:da': 'kyst',
            'name:0:en': 'coast',
            'name:1:da': 'gul stage',
            'name:1:en': 'yellow buoy',
            ...extras,
          },
        };
      }

      it('returns translations in the requested language when present', () => {
        const result = service.serializeReadableCoordinates(bilingualLine(), 'en');
        expect(result.map((r) => r.name)).toEqual(['coast', 'yellow buoy']);
      });

      it("falls back to the feature's `language` property when the requested language has no translation", () => {
        // Simulates a fresh language ('fr') being added to the system after
        // this message was authored — the message only has da/en translations
        // and explicitly declares `language: 'da'`. The labels must keep
        // working in `da` rather than dropping to undefined.
        const result = service.serializeReadableCoordinates(
          bilingualLine({ language: 'da' }),
          'fr',
        );
        expect(result.map((r) => r.name)).toEqual(['kyst', 'gul stage']);
      });

      it('falls back to any available name when neither requested nor feature language matches', () => {
        // No `language` property on the feature, and the requested 'fr' has
        // no translations either. The label must still resolve to *something*
        // — picks the first matching `name:<idx>:*` key (the only one here).
        const result = service.serializeReadableCoordinates(
          {
            type: 'Feature',
            geometry: { type: 'Point', coordinates: [0, 0] },
            properties: { 'name:0:en': 'Start' },
          },
          'fr',
        );
        expect(result[0].name).toBe('Start');
      });

      it('returns undefined when no name keys exist at all', () => {
        const result = service.serializeReadableCoordinates(
          {
            type: 'Feature',
            geometry: { type: 'Point', coordinates: [0, 0] },
            properties: { language: 'da' },
          },
          'fr',
        );
        expect(result[0].name).toBeUndefined();
      });
    });
  });
});
