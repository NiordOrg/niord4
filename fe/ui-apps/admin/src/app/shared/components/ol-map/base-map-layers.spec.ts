import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Map as OlMapImpl } from 'ol';
import type BaseLayer from 'ol/layer/Base';
import TileLayer from 'ol/layer/Tile';
import OSM from 'ol/source/OSM';
import TileWMS from 'ol/source/TileWMS';
import XYZ from 'ol/source/XYZ';
import { describe, expect, it } from 'vitest';

import type { AppConfig, MapConfig } from '../../../core/config/app-config.interface';
import { APP_CONFIG } from '../../../core/config/app-config.token';
import { provideFakeAuthFacade } from '../../testing/fake-auth';
import { provideTestTransloco } from '../../testing/transloco-testing';
import { MapShellService } from '@niord/map/map-shell.service';
import { BaseMapLayers } from './base-map-layers';

const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

@Component({
  template: `<app-base-map-layers [openSeaMap]="openSeaMap()" />`,
  imports: [BaseMapLayers],
  providers: [MapShellService],
})
class HostComponent {
  readonly openSeaMap = signal(true);
}

interface SetupOptions {
  readonly map?: MapConfig;
  readonly isAuthenticated?: boolean;
  readonly openSeaMap?: boolean;
}

function setup(opts: SetupOptions = {}) {
  TestBed.configureTestingModule({
    providers: [
      {
        provide: APP_CONFIG,
        useValue: {
          apiBaseUrl: '/rest',
          appName: 'Niord',
          helpUrls: {},
          map: opts.map,
        } as AppConfig,
      },
      provideFakeAuthFacade({ isAuthenticated: opts.isAuthenticated ?? false }),
      provideTestTransloco(),
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  const fixture = TestBed.createComponent(HostComponent);
  if (opts.openSeaMap === false) {
    fixture.componentInstance.openSeaMap.set(false);
  }
  const shell = fixture.debugElement.injector.get(MapShellService);
  shell.setMap(new OlMapImpl({}));
  fixture.detectChanges();
  return { fixture, shell };
}

/** The registered layer whose source matches, or undefined. OSM ⊂ XYZ, so OpenSeaMap = XYZ-not-OSM. */
function findLayer(
  layers: readonly BaseLayer[],
  kind: 'osm' | 'openSeaMap' | 'wms',
): TileLayer | undefined {
  return layers
    .filter((l): l is TileLayer => l instanceof TileLayer)
    .find((layer) => {
      const source = layer.getSource();
      switch (kind) {
        case 'osm':
          return source instanceof OSM;
        case 'openSeaMap':
          return source instanceof XYZ && !(source instanceof OSM);
        case 'wms':
          return source instanceof TileWMS;
      }
    });
}

const fullMapConfig: MapConfig = {
  openSeaMapLayerEnabled: true,
  wmsLayerEnabled: true,
  wmsLayerEnabledByDefault: true,
  wmsUrl: '/wms/',
};

describe('BaseMapLayers', () => {
  it('registers the OSM + OpenSeaMap + WMS trio when the config enables everything', () => {
    const { shell } = setup({ map: fullMapConfig });
    const layers = shell.layers();
    expect(layers).toHaveLength(3);

    const osm = findLayer(layers, 'osm');
    expect(osm?.getVisible()).toBe(true);
    expect(osm?.get('showInLayerSwitcher')).toBe(true);

    // OpenSeaMap starts hidden but is offered in the switcher when enabled.
    const openSeaMap = findLayer(layers, 'openSeaMap');
    expect(openSeaMap?.getVisible()).toBe(false);
    expect(openSeaMap?.get('showInLayerSwitcher')).toBe(true);

    // wmsLayerEnabledByDefault → the WMS overlay starts switched on.
    const wms = findLayer(layers, 'wms');
    expect(wms?.getVisible()).toBe(true);
    expect(wms?.get('showInLayerSwitcher')).toBe(true);
  });

  it('still registers OpenSeaMap (hidden from the switcher) when the config flag is off', () => {
    const { shell } = setup({ map: { ...fullMapConfig, openSeaMapLayerEnabled: false } });
    const openSeaMap = findLayer(shell.layers(), 'openSeaMap');
    expect(openSeaMap).toBeDefined();
    expect(openSeaMap?.getVisible()).toBe(false);
    expect(openSeaMap?.get('showInLayerSwitcher')).toBe(false);
  });

  it('omits OpenSeaMap entirely when the host opts out via [openSeaMap]="false"', () => {
    const { shell } = setup({ map: fullMapConfig, openSeaMap: false });
    expect(findLayer(shell.layers(), 'osm')).toBeDefined();
    expect(findLayer(shell.layers(), 'openSeaMap')).toBeUndefined();
    expect(findLayer(shell.layers(), 'wms')).toBeDefined();
  });

  it('starts the WMS layer unchecked when wmsLayerEnabledByDefault is not set', () => {
    const { shell } = setup({ map: { ...fullMapConfig, wmsLayerEnabledByDefault: false } });
    const wms = findLayer(shell.layers(), 'wms');
    expect(wms?.getVisible()).toBe(false);
    expect(wms?.get('showInLayerSwitcher')).toBe(true);
  });

  it('renders no WMS layer when wmsLayerEnabled is off', () => {
    const { shell } = setup({ map: { ...fullMapConfig, wmsLayerEnabled: false } });
    expect(findLayer(shell.layers(), 'wms')).toBeUndefined();
  });

  it('renders no WMS layer when the WMS URL is missing', () => {
    const { shell } = setup({ map: { ...fullMapConfig, wmsUrl: undefined } });
    expect(findLayer(shell.layers(), 'wms')).toBeUndefined();
  });

  it('hides a protected WMS from anonymous users', () => {
    const { shell } = setup({
      map: { ...fullMapConfig, wmsProtected: true },
      isAuthenticated: false,
    });
    expect(findLayer(shell.layers(), 'wms')).toBeUndefined();
  });

  it('offers a protected WMS to authenticated users', () => {
    const { shell } = setup({
      map: { ...fullMapConfig, wmsProtected: true },
      isAuthenticated: true,
    });
    expect(findLayer(shell.layers(), 'wms')).toBeDefined();
  });
});
