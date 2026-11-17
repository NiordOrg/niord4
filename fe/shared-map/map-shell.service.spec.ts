import { TestBed } from '@angular/core/testing';
import OlMapImpl from 'ol/Map';
import TileLayer from 'ol/layer/Tile';
import { beforeEach, describe, expect, it } from 'vitest';

import { MapShellService } from './map-shell.service';

// jsdom lacks ResizeObserver, which ol.Map's constructor references.
const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

describe('MapShellService', () => {
  let service: MapShellService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [MapShellService] });
    service = TestBed.inject(MapShellService);
  });

  it('starts with no map and an empty layer list', () => {
    expect(service.map()).toBeUndefined();
    expect(service.layers()).toEqual([]);
  });

  it('setMap exposes the map via the read-only signal', () => {
    const map = new OlMapImpl({});
    service.setMap(map);
    expect(service.map()).toBe(map);
  });

  it('registerLayer appends to the layer list and adds to the map', () => {
    const map = new OlMapImpl({});
    service.setMap(map);
    const layer = new TileLayer();
    service.registerLayer(layer);
    expect(service.layers()).toEqual([layer]);
    expect(map.getLayers().getArray()).toContain(layer);
  });

  it('registerLayer is idempotent', () => {
    const map = new OlMapImpl({});
    service.setMap(map);
    const layer = new TileLayer();
    service.registerLayer(layer);
    service.registerLayer(layer);
    expect(service.layers()).toEqual([layer]);
    expect(map.getLayers().getLength()).toBe(1);
  });

  it('unregisterLayer removes from both list and map', () => {
    const map = new OlMapImpl({});
    service.setMap(map);
    const layer = new TileLayer();
    service.registerLayer(layer);
    service.unregisterLayer(layer);
    expect(service.layers()).toEqual([]);
    expect(map.getLayers().getArray()).not.toContain(layer);
  });

  it('clearMap resets both map and layer list', () => {
    const map = new OlMapImpl({});
    service.setMap(map);
    service.registerLayer(new TileLayer());
    service.clearMap();
    expect(service.map()).toBeUndefined();
    expect(service.layers()).toEqual([]);
  });

  it('registers layers without a map (will be attached later)', () => {
    const layer = new TileLayer();
    service.registerLayer(layer);
    expect(service.layers()).toEqual([layer]);
  });

  it('flushes pre-registered layers onto the map when setMap is called', () => {
    const layerA = new TileLayer();
    const layerB = new TileLayer();
    service.registerLayer(layerA);
    service.registerLayer(layerB);
    const map = new OlMapImpl({});
    service.setMap(map);
    const attached = map.getLayers().getArray();
    expect(attached).toContain(layerA);
    expect(attached).toContain(layerB);
  });
});
