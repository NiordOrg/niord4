import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import OlMapImpl from 'ol/Map';
import TileLayer from 'ol/layer/Tile';
import XYZ from 'ol/source/XYZ';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { MapShellService } from '../map-shell.service';
import { MAP_TILE_SOURCES, type MapTileSources } from '../tile-sources';
import { OlOpenSeaMapLayer } from './ol-openseamap-layer';

const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

@Component({
  template: `<app-ol-openseamap-layer [visible]="visible()" [url]="url()" />`,
  imports: [OlOpenSeaMapLayer],
  providers: [MapShellService],
})
class HostComponent {
  readonly visible = signal(true);
  readonly url = signal<string | undefined>(undefined);
}

describe('OlOpenSeaMapLayer', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
  let shell: MapShellService;

  beforeEach(() => {
    fixture = TestBed.createComponent(HostComponent);
    shell = fixture.debugElement.injector.get(MapShellService);
    shell.setMap(new OlMapImpl({}));
  });

  afterEach(() => fixture.destroy());

  it('registers an XYZ tile layer with the map shell', () => {
    fixture.detectChanges();
    const layers = shell.layers();
    expect(layers).toHaveLength(1);
    expect(layers[0]).toBeInstanceOf(TileLayer);
    expect((layers[0] as TileLayer).getSource()).toBeInstanceOf(XYZ);
  });

  it('reacts to visibility changes', () => {
    fixture.detectChanges();
    const layer = shell.layers()[0];
    fixture.componentInstance.visible.set(false);
    fixture.detectChanges();
    expect(layer.getVisible()).toBe(false);
  });

  it('tags itself for the layer switcher by default', () => {
    fixture.detectChanges();
    expect(shell.layers()[0].get('showInLayerSwitcher')).toBe(true);
  });
});

describe('OlOpenSeaMapLayer — the seamark address', () => {
  const PUBLIC_SERVER = 'https://t1.openseamap.org/seamark/{z}/{x}/{y}.png';
  const DEPLOYMENT = 'https://tiles.deployment.example/seamark/{z}/{x}/{y}.png';
  const HOST = 'https://tiles.host.example/{z}/{x}/{y}.png';

  /** The URL templates of the layer built for this host `url` and these tile sources. */
  function urlsFor(url: string | undefined, tileSources?: MapTileSources): readonly string[] {
    if (tileSources) {
      TestBed.configureTestingModule({
        providers: [{ provide: MAP_TILE_SOURCES, useValue: tileSources }],
      });
    }
    const fixture = TestBed.createComponent(HostComponent);
    const shell = fixture.debugElement.injector.get(MapShellService);
    shell.setMap(new OlMapImpl({}));
    fixture.componentInstance.url.set(url);
    fixture.detectChanges();
    const source = (shell.layers()[0] as TileLayer).getSource() as XYZ;
    const urls = source.getUrls() ?? [];
    fixture.destroy();
    return urls;
  }

  it('is the public tile server, over https, when neither the host nor the deployment names one', () => {
    expect(urlsFor(undefined)).toEqual([PUBLIC_SERVER]);
  });

  it("is the deployment's when the host passes none", () => {
    expect(urlsFor(undefined, { openSeaMapUrl: DEPLOYMENT })).toEqual([DEPLOYMENT]);
  });

  it("is the host's over the deployment's", () => {
    expect(urlsFor(HOST, { openSeaMapUrl: DEPLOYMENT })).toEqual([HOST]);
  });

  it("passes a blank host value over to the deployment's address", () => {
    expect(urlsFor('', { openSeaMapUrl: DEPLOYMENT })).toEqual([DEPLOYMENT]);
  });

  it('passes a blank deployment address over to the public tile server', () => {
    expect(urlsFor('', { openSeaMapUrl: '' })).toEqual([PUBLIC_SERVER]);
  });

  it('never takes the OpenStreetMap address', () => {
    expect(urlsFor(undefined, { osmUrl: DEPLOYMENT })).toEqual([PUBLIC_SERVER]);
  });
});
