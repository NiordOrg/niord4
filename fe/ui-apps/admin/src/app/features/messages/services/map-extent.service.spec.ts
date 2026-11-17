import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { MapExtentService } from './map-extent.service';

describe('MapExtentService', () => {
  let service: MapExtentService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [MapExtentService] });
    service = TestBed.inject(MapExtentService);
  });

  it('starts with undefined extent', () => {
    expect(service.extent()).toBeUndefined();
  });

  it('setFromMap writes zoom + center + extent into the signal', () => {
    service.setFromMap(8, [12, 55], [10, 54, 14, 56]);
    expect(service.extent()).toEqual({
      zoom: 8,
      center: [12, 55],
      extent: [10, 54, 14, 56],
    });
  });

  it('setFromUrl assigns the value as-is (or undefined)', () => {
    service.setFromUrl({ zoom: 7, center: [13, 56] });
    expect(service.extent()).toEqual({ zoom: 7, center: [13, 56] });
    service.setFromUrl(undefined);
    expect(service.extent()).toBeUndefined();
  });

  it('clear resets to undefined', () => {
    service.setFromMap(8, [12, 55], [10, 54, 14, 56]);
    service.clear();
    expect(service.extent()).toBeUndefined();
  });
});
