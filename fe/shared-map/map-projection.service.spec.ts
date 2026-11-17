import { TestBed } from '@angular/core/testing';
import { describe, expect, it, beforeEach } from 'vitest';

import { MapProjectionService } from './map-projection.service';

describe('MapProjectionService', () => {
  let service: MapProjectionService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(MapProjectionService);
  });

  describe('fromLonLat / toLonLat', () => {
    it('round-trips a point through 4326 → 3857 → 4326 within tolerance', () => {
      const original: [number, number] = [12.5683, 55.6761]; // Copenhagen
      const projected = service.fromLonLat(original);
      const restored = service.toLonLat(projected);
      expect(restored[0]).toBeCloseTo(original[0], 6);
      expect(restored[1]).toBeCloseTo(original[1], 6);
    });

    it('projects the origin (0, 0) close to (0, 0)', () => {
      const projected = service.fromLonLat([0, 0]);
      expect(projected[0]).toBeCloseTo(0, 4);
      expect(projected[1]).toBeCloseTo(0, 4);
    });

    it('moves a non-equatorial latitude away from the corresponding y at the equator', () => {
      const eq = service.fromLonLat([0, 0]);
      const north = service.fromLonLat([0, 60]);
      expect(north[1]).toBeGreaterThan(eq[1]);
    });
  });

  describe('fromLonLatExtent / toLonLatExtent', () => {
    it('round-trips an extent through 4326 → 3857 → 4326 within tolerance', () => {
      const original: [number, number, number, number] = [10, 54, 14, 57];
      const projected = service.fromLonLatExtent(original);
      const restored = service.toLonLatExtent(projected);
      expect(restored[0]).toBeCloseTo(original[0], 6);
      expect(restored[1]).toBeCloseTo(original[1], 6);
      expect(restored[2]).toBeCloseTo(original[2], 6);
      expect(restored[3]).toBeCloseTo(original[3], 6);
    });
  });

  describe('round', () => {
    it('rounds to 4 decimals by default', () => {
      expect(service.round(12.345678)).toBe(12.3457);
    });

    it('honours an explicit decimals argument', () => {
      expect(service.round(12.345678, 2)).toBe(12.35);
      expect(service.round(12.345678, 0)).toBe(12);
    });

    it('handles negative values', () => {
      expect(service.round(-12.345678, 4)).toBe(-12.3457);
    });
  });
});
