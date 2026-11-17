import { HttpClient, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import OlMapImpl from 'ol/Map';
import BaseEvent from 'ol/events/Event';
import type ImageTile from 'ol/ImageTile';
import TileLayer from 'ol/layer/Tile';
import TileState from 'ol/TileState';
import TileWMS from 'ol/source/TileWMS';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { MapShellService } from '../map-shell.service';
import { OlWmsLayer } from './ol-wms-layer';

const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

/** Flush pending microtasks (the async tile-loader continuations). */
const flush = (): Promise<void> => new Promise((r) => setTimeout(r, 0));

/**
 * A tile that enforces OL's setState rule: a backwards transition (LOADING→IDLE)
 * throws unless the tile is currently in ERROR (see node_modules/ol/Tile.js).
 * Starts in LOADING — the state OL puts a tile in before calling the loader.
 */
function makeStatefulTile(): { tile: ImageTile; state: () => number } {
  const image = document.createElement('img');
  const ref = { state: TileState.LOADING };
  const tile = {
    getImage: () => image,
    getState: () => ref.state,
    setState: (next: number) => {
      if (ref.state !== TileState.ERROR && ref.state > next) {
        throw new Error('Tile load sequence violation');
      }
      ref.state = next;
    },
  } as unknown as ImageTile;
  return { tile, state: () => ref.state };
}

@Component({
  template: `<app-ol-wms-layer
    [url]="url()"
    [visible]="visible()"
    [protectedMode]="protectedMode()"
  />`,
  imports: [OlWmsLayer],
  providers: [MapShellService],
})
class HostComponent {
  readonly url = signal('/wms/');
  readonly visible = signal(false);
  readonly protectedMode = signal(false);
}

describe('OlWmsLayer', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
  let shell: MapShellService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    fixture = TestBed.createComponent(HostComponent);
    shell = fixture.debugElement.injector.get(MapShellService);
    httpMock = TestBed.inject(HttpTestingController);
    shell.setMap(new OlMapImpl({}));
  });

  afterEach(() => {
    fixture.destroy();
    // ignoreCancelled: the abort test intentionally leaves a cancelled request.
    httpMock.verify({ ignoreCancelled: true });
  });

  it('registers a TileWMS-backed TileLayer with the shell', () => {
    fixture.detectChanges();
    const layers = shell.layers();
    expect(layers).toHaveLength(1);
    expect(layers[0]).toBeInstanceOf(TileLayer);
    expect((layers[0] as TileLayer).getSource()).toBeInstanceOf(TileWMS);
  });

  it('merges DEFAULT_WMS_PARAMS (LAYERS + TRANSPARENT) into the request params', () => {
    fixture.detectChanges();
    const source = (shell.layers()[0] as TileLayer).getSource() as TileWMS;
    const params = source.getParams() as Record<string, string>;
    expect(params['LAYERS']).toBe('cells');
    expect(params['TRANSPARENT']).toBe('TRUE');
  });

  it('omits a maxResolution cap when protectedMode is off', () => {
    fixture.detectChanges();
    // OL returns Infinity for the default (no cap).
    expect((shell.layers()[0] as TileLayer).getMaxResolution()).toBe(Infinity);
  });

  it('applies the protected-mode maxResolution cap (1000 m/px) when protectedMode is on', () => {
    fixture.componentInstance.protectedMode.set(true);
    fixture.detectChanges();
    expect((shell.layers()[0] as TileLayer).getMaxResolution()).toBe(1000);
  });

  it('loads tiles via HttpClient in protected mode and sets the image to a blob URL', async () => {
    fixture.componentInstance.protectedMode.set(true);
    fixture.detectChanges();
    const source = (shell.layers()[0] as TileLayer).getSource() as TileWMS;
    const loader = source.getTileLoadFunction();
    // Real HTMLImageElement — the loader now attaches load/error listeners
    // to revoke the Blob URL once the browser has the bitmap, so a plain
    // object mock without addEventListener would throw inside the try/catch
    // and trigger the error fallback instead of the happy path.
    const image = document.createElement('img');
    const tile = { getImage: () => image } as unknown as ImageTile;
    const createSpy = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fake-object-url');

    loader(tile, '/wms/?REQUEST=GetMap&BBOX=…');
    const req = httpMock.expectOne('/wms/?REQUEST=GetMap&BBOX=…');
    req.flush(new ArrayBuffer(8));
    await Promise.resolve();

    expect(image.src).toBe('blob:fake-object-url');
    createSpy.mockRestore();
  });

  it('revokes the Blob URL once the image finishes loading (no per-tile leak)', async () => {
    fixture.componentInstance.protectedMode.set(true);
    fixture.detectChanges();
    const source = (shell.layers()[0] as TileLayer).getSource() as TileWMS;
    const loader = source.getTileLoadFunction();
    const image = document.createElement('img');
    const tile = { getImage: () => image } as unknown as ImageTile;
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fake-object-url');
    const revokeSpy = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined);

    loader(tile, '/wms/?REQUEST=GetMap&BBOX=A');
    httpMock.expectOne('/wms/?REQUEST=GetMap&BBOX=A').flush(new ArrayBuffer(8));
    await Promise.resolve();
    image.dispatchEvent(new Event('load'));

    expect(revokeSpy).toHaveBeenCalledWith('blob:fake-object-url');
    revokeSpy.mockRestore();
  });

  it('logs when a protected-mode fetch errors', async () => {
    fixture.componentInstance.protectedMode.set(true);
    fixture.detectChanges();
    const source = (shell.layers()[0] as TileLayer).getSource() as TileWMS;
    const loader = source.getTileLoadFunction();
    const image = document.createElement('img');
    const tile = { getImage: () => image } as unknown as ImageTile;
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);

    loader(tile, '/wms/?REQUEST=GetMap&BBOX=err');
    httpMock.expectOne('/wms/?REQUEST=GetMap&BBOX=err').error(new ProgressEvent('error'), {
      status: 403,
    });
    await Promise.resolve();
    await Promise.resolve();

    expect(warnSpy).toHaveBeenCalled();
    warnSpy.mockRestore();
  });

  it('cancels an in-flight protected tile on movestart and resets it to IDLE for reload', async () => {
    fixture.componentInstance.protectedMode.set(true);
    fixture.detectChanges();
    const source = (shell.layers()[0] as TileLayer).getSource() as TileWMS;
    const loader = source.getTileLoadFunction();
    const t = makeStatefulTile();

    loader(t.tile, '/wms/?REQUEST=GetMap&BBOX=abort');
    const req = httpMock.expectOne('/wms/?REQUEST=GetMap&BBOX=abort');

    // A new gesture starts before the tile responds → the XHR is cancelled and
    // the tile is reset straight to IDLE (synchronously, no moveend needed) so
    // OL re-requests it for the settled view.
    shell.map()!.dispatchEvent(new BaseEvent('movestart'));
    await flush();
    expect(req.cancelled).toBe(true);
    expect(t.state()).toBe(TileState.IDLE);
  });

  it('tags the layer for the switcher by default and exposes the name', () => {
    fixture.detectChanges();
    const layer = shell.layers()[0];
    expect(layer.get('showInLayerSwitcher')).toBe(true);
    expect(layer.get('name')).toBe('WMS');
  });

  it('reacts to visibility changes from the host', () => {
    fixture.detectChanges();
    expect(shell.layers()[0].getVisible()).toBe(false);
    fixture.componentInstance.visible.set(true);
    fixture.detectChanges();
    expect(shell.layers()[0].getVisible()).toBe(true);
  });
});

/** OpenLayers hands every untouched source the same module-level loader. */
const olsOwnLoader = (): unknown => new TileWMS({ url: '/wms/', params: {} }).getTileLoadFunction();

describe('OlWmsLayer in an anonymous host', () => {
  it('draws through OpenLayers own image loader when nothing provides an HTTP client', () => {
    // The shape a public site mounts: no HTTP providers configured, protectedMode left at its
    // default. Tiles are plain <img> loads, so no token and no interceptor are involved.
    const fixture = TestBed.createComponent(HostComponent);
    const shell = fixture.debugElement.injector.get(MapShellService);
    shell.setMap(new OlMapImpl({}));
    fixture.detectChanges();

    const source = (shell.layers()[0] as TileLayer).getSource() as TileWMS;
    expect(shell.layers()).toHaveLength(1);
    expect(source.getTileLoadFunction()).toBe(olsOwnLoader());
    fixture.destroy();
  });

  it('falls back to that loader, with a warning, when the client is withheld', () => {
    // HttpClient is root-provided, so it resolves even where nobody called provideHttpClient();
    // withholding it explicitly is what a host does to prove the layer degrades rather than
    // breaking the whole map.
    TestBed.configureTestingModule({ providers: [{ provide: HttpClient, useValue: null }] });
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const fixture = TestBed.createComponent(HostComponent);
    const shell = fixture.debugElement.injector.get(MapShellService);
    shell.setMap(new OlMapImpl({}));
    fixture.componentInstance.protectedMode.set(true);
    fixture.detectChanges();

    const source = (shell.layers()[0] as TileLayer).getSource() as TileWMS;
    expect(source.getTileLoadFunction()).toBe(olsOwnLoader());
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('provideHttpClient'));

    warnSpy.mockRestore();
    fixture.destroy();
  });
});
