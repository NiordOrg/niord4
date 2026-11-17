import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import OlMapImpl from 'ol/Map';
import TileLayer from 'ol/layer/Tile';
import OSM from 'ol/source/OSM';
import XYZ from 'ol/source/XYZ';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { MapShellService } from '../map-shell.service';
import { MAP_TILE_SOURCES, type MapTileSources } from '../tile-sources';
import { OlTileLayer } from './ol-tile-layer';

const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

@Component({
  template: `<app-ol-tile-layer
    [source]="source()"
    [url]="url()"
    [visible]="visible()"
    [name]="name()"
    [showInLayerSwitcher]="showInLayerSwitcher()"
    [opacity]="opacity()"
    [attributions]="attributions()"
  />`,
  imports: [OlTileLayer],
  providers: [MapShellService],
})
class HostComponent {
  readonly source = signal<'OSM' | 'XYZ'>('OSM');
  readonly url = signal<string | undefined>(undefined);
  readonly visible = signal(true);
  readonly name = signal('OSM');
  readonly showInLayerSwitcher = signal(true);
  readonly opacity = signal(1);
  readonly attributions = signal<readonly string[] | undefined>(undefined);
}

/** The credits a source reports, as OpenLayers' attribution control would collect them. */
function creditsOf(layer: TileLayer): readonly string[] {
  const attributions = layer.getSource()?.getAttributions();
  if (!attributions) return [];
  const collected = attributions({} as Parameters<typeof attributions>[0]);
  return typeof collected === 'string' ? [collected] : (collected ?? []);
}

describe('OlTileLayer', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
  let shell: MapShellService;

  beforeEach(() => {
    fixture = TestBed.createComponent(HostComponent);
    shell = fixture.debugElement.injector.get(MapShellService);
    shell.setMap(new OlMapImpl({}));
  });

  afterEach(() => fixture.destroy());

  it('registers an OSM tile layer with the map shell', () => {
    fixture.detectChanges();
    const layers = shell.layers();
    expect(layers).toHaveLength(1);
    expect(layers[0]).toBeInstanceOf(TileLayer);
    expect((layers[0] as TileLayer).getSource()).toBeInstanceOf(OSM);
  });

  it('uses an XYZ source when source=XYZ and url is provided', () => {
    fixture.componentInstance.source.set('XYZ');
    fixture.componentInstance.url.set('https://example.test/{z}/{x}/{y}.png');
    fixture.detectChanges();
    const layer = shell.layers()[0] as TileLayer;
    expect(layer.getSource()).toBeInstanceOf(XYZ);
  });

  it('propagates name + showInLayerSwitcher to the layer properties', () => {
    fixture.componentInstance.name.set('Charts BG');
    fixture.componentInstance.showInLayerSwitcher.set(true);
    fixture.detectChanges();
    const layer = shell.layers()[0];
    expect(layer.get('name')).toBe('Charts BG');
    expect(layer.get('showInLayerSwitcher')).toBe(true);
  });

  it('reacts to visibility and opacity input changes', () => {
    fixture.detectChanges();
    const layer = shell.layers()[0];
    fixture.componentInstance.visible.set(false);
    fixture.componentInstance.opacity.set(0.5);
    fixture.detectChanges();
    expect(layer.getVisible()).toBe(false);
    expect(layer.getOpacity()).toBeCloseTo(0.5, 5);
  });

  it('leaves the source its own credits when no attributions are passed', () => {
    fixture.detectChanges();
    const credits = creditsOf(shell.layers()[0] as TileLayer);
    expect(credits.join(' ')).toContain('openstreetmap.org/copyright');
  });

  it('reports the host list instead, and follows it when the host re-words it', () => {
    fixture.componentInstance.attributions.set(['&copy; The authority', 'OpenStreetMap']);
    fixture.detectChanges();
    const layer = shell.layers()[0] as TileLayer;
    expect(creditsOf(layer)).toEqual(['&copy; The authority', 'OpenStreetMap']);

    fixture.componentInstance.attributions.set(['&copy; Myndigheden', 'OpenStreetMap']);
    fixture.detectChanges();
    expect(creditsOf(layer)).toEqual(['&copy; Myndigheden', 'OpenStreetMap']);
  });

  it('unregisters from the shell on destroy', () => {
    fixture.detectChanges();
    expect(shell.layers()).toHaveLength(1);
    fixture.destroy();
    expect(shell.layers()).toHaveLength(0);
  });
});

describe('OlTileLayer — the OpenStreetMap address', () => {
  const OL_DEFAULT = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
  const DEPLOYMENT = 'https://tiles.deployment.example/osm/{z}/{x}/{y}.png';
  const HOST = 'https://tiles.host.example/{z}/{x}/{y}.png';

  /** The URL templates of the OSM layer built for this host `url` and these tile sources. */
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
    const source = (shell.layers()[0] as TileLayer).getSource() as OSM;
    const urls = source.getUrls() ?? [];
    fixture.destroy();
    return urls;
  }

  it("is OpenLayers' own when neither the host nor the deployment names one", () => {
    expect(urlsFor(undefined)).toEqual([OL_DEFAULT]);
  });

  it("is the deployment's when the host passes none", () => {
    expect(urlsFor(undefined, { osmUrl: DEPLOYMENT })).toEqual([DEPLOYMENT]);
  });

  it("is the host's over the deployment's", () => {
    expect(urlsFor(HOST, { osmUrl: DEPLOYMENT })).toEqual([HOST]);
  });

  it("passes a blank host value over to the deployment's address", () => {
    expect(urlsFor('', { osmUrl: DEPLOYMENT })).toEqual([DEPLOYMENT]);
  });

  it("passes a blank deployment address over to OpenLayers' own", () => {
    expect(urlsFor('', { osmUrl: '' })).toEqual([OL_DEFAULT]);
  });

  it('keeps the credits of OpenStreetMap whatever host serves the tiles', () => {
    TestBed.configureTestingModule({
      providers: [{ provide: MAP_TILE_SOURCES, useValue: { osmUrl: DEPLOYMENT } }],
    });
    const fixture = TestBed.createComponent(HostComponent);
    const shell = fixture.debugElement.injector.get(MapShellService);
    shell.setMap(new OlMapImpl({}));
    fixture.detectChanges();
    expect(creditsOf(shell.layers()[0] as TileLayer).join(' ')).toContain(
      'openstreetmap.org/copyright',
    );
    fixture.destroy();
  });
});
