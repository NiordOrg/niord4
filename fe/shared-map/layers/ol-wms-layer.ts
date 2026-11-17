import { HttpClient } from '@angular/common/http';
import { Component, DestroyRef, effect, inject, input, untracked } from '@angular/core';
import type ImageTile from 'ol/ImageTile';
import TileLayer from 'ol/layer/Tile';
import { unByKey } from 'ol/Observable';
import TileWMS from 'ol/source/TileWMS';
import TileState from 'ol/TileState';
import { firstValueFrom, Subject, takeUntil } from 'rxjs';

import { MapShellService } from '../map-shell.service';

const DEFAULT_WMS_PARAMS = { LAYERS: 'cells', TRANSPARENT: 'TRUE' } as const;

/**
 * When `wmsProtected` mode is on, the layer's maxResolution is capped to 1000 m/px
 * (≈ zoom 7 in Web Mercator) so opening the world view doesn't spawn dozens of
 * authenticated XHRs.
 */
const PROTECTED_MAX_RESOLUTION = 1000;

/**
 * Tile cache size (entries). Large enough that the coarse parent tiles seen
 * before a deep zoom-in + pan survive in the LRU: when the view zooms back out,
 * the renderer paints those cached lower-resolution tiles as a fallback while
 * the correct-zoom tiles load, instead of showing a blank layer. OL's built-in
 * default (512) only spans about two zoom levels, so it gets evicted after
 * panning at high zoom and the fallback disappears.
 */
const TILE_CACHE_SIZE = 2048;

@Component({
  selector: 'app-ol-wms-layer',
  host: { class: 'contents' },
  template: '',
})
export class OlWmsLayer {
  private readonly shell = inject(MapShellService);
  private readonly destroyRef = inject(DestroyRef);
  /**
   * Only `protectedMode` needs it: a plain-image layer is loaded by OpenLayers itself, which is
   * what an anonymous site uses. Optional so the kit states that dependency truthfully and
   * degrades for a host that withholds the client instead of failing to draw a map at all.
   */
  private readonly http = inject(HttpClient, { optional: true });

  readonly url = input.required<string>();
  readonly visible = input<boolean>(false);
  readonly name = input<string>('WMS');
  readonly opacity = input<number>(1);
  readonly showInLayerSwitcher = input<boolean>(true);
  /** Extra WMS GetMap params merged on top of the defaults (LAYERS, TRANSPARENT). */
  readonly params = input<Record<string, string> | undefined>(undefined);
  /**
   * When true, tiles load through the host's HttpClient, so the host's interceptors can add
   * the credentials a protected chart server asks for.
   */
  readonly protectedMode = input<boolean>(false);

  private layer: TileLayer | undefined;

  /** Fires when a new gesture starts, cancelling in-flight protected-tile XHRs. */
  private readonly abort$ = new Subject<void>();

  constructor() {
    effect(() => {
      if (!this.layer) {
        this.layer = untracked(() => this.buildLayer());
        this.shell.registerLayer(this.layer);
      }
      this.layer.setVisible(this.visible());
      this.layer.setOpacity(this.opacity());
      this.layer.set('name', this.name());
      this.layer.set('showInLayerSwitcher', this.showInLayerSwitcher());
      // Switcher-list order (NOT render order). Smaller numbers appear earlier
      // in the layer switcher panel. WMS sits at the top of the list per project
      // convention; layers without an explicit order fall through to a default
      // priority defined on the switcher itself.
      this.layer.set('switcherOrder', 10);
    });

    // Cancel in-flight protected-mode tile requests when the view starts moving
    // again. Authenticated WMS tiles are server-rendered and share the API
    // host's connection budget, so letting obsolete ones run to completion
    // wastes bandwidth and delays the tiles the settled view actually needs.
    // Plain <img> loads (unprotected mode) can't be cancelled, so this only
    // applies in protected mode. The cancelled tile is reset to IDLE inside the
    // loader itself (see loadProtectedTile) — no moveend bookkeeping needed.
    effect((onCleanup) => {
      const map = this.shell.map();
      if (!map || !untracked(() => this.protectedMode())) return;
      const key = map.on('movestart', () => this.abort$.next());
      onCleanup(() => unByKey(key));
    });

    this.destroyRef.onDestroy(() => {
      if (this.layer) this.shell.unregisterLayer(this.layer);
      this.abort$.complete();
    });
  }

  /**
   * Built once on the first effect run. `url`, `params` and `protectedMode` are read once, when
   * the layer is built; a later change to any of them is ignored. A host that needs to switch
   * one at runtime re-creates the component, for example behind an `@if`.
   */
  private buildLayer(): TileLayer {
    const source = new TileWMS({
      url: this.url(),
      params: { ...DEFAULT_WMS_PARAMS, ...(this.params() ?? {}) },
      // crossOrigin '' (anonymous) is the chosen setting. If the WMS host
      // doesn't return `Access-Control-Allow-Origin` the response will paint
      // but taint the canvas — no breakage here since we never read pixels
      // back (no canvas export). Document the CORS expectation in any deploy
      // notes so a new deployment doesn't break canvas sampling features
      // added later.
      crossOrigin: '',
    });

    // Protected mode routes tiles through the host's HttpClient, so the host's
    // interceptors can add credentials; unprotected mode uses OL's default image
    // loader. Loading during gestures is held back at the map level by the
    // tile-load gate (see installTileLoadGate).
    const http = this.http;
    if (this.protectedMode() && http) {
      source.setTileLoadFunction((tile, src) =>
        this.loadProtectedTile(http, tile as ImageTile, src),
      );
    } else if (this.protectedMode()) {
      // Falling back rather than throwing: the layer still draws, and the missing header
      // shows up as a 401 from the tile server instead of a blank map with no explanation.
      console.warn(
        '[OlWmsLayer] protectedMode needs provideHttpClient(); falling back to plain image loading',
      );
    }

    const layer = new TileLayer({ source, cacheSize: TILE_CACHE_SIZE });
    if (this.protectedMode()) {
      layer.setMaxResolution(PROTECTED_MAX_RESOLUTION);
    }
    return layer;
  }

  private async loadProtectedTile(http: HttpClient, tile: ImageTile, src: string): Promise<void> {
    const image = tile.getImage() as HTMLImageElement;
    try {
      // takeUntil(abort$) cancels the XHR when a new gesture starts; the
      // `defaultValue` resolves to undefined on that completion instead of
      // throwing, so we can tell an abort apart from a real fetch error.
      const bytes = await firstValueFrom(
        http.get(src, { responseType: 'arraybuffer' }).pipe(takeUntil(this.abort$)),
        { defaultValue: undefined },
      );
      if (bytes === undefined) {
        // A new gesture cancelled this fetch. Reset the tile straight back to
        // IDLE (via ERROR — OL forbids a direct LOADING→IDLE transition) so OL
        // re-requests it for the settled view. Doing it synchronously here,
        // rather than deferring to moveend, avoids a race on an *instant* view
        // change (e.g. fit-on-open), which fires movestart+moveend in the same
        // frame before this async cancel resolves — a moveend re-arm would then
        // run before the tile is parked and leave it stuck (blank centre tiles).
        if (tile.getState() === TileState.LOADING) {
          tile.setState(TileState.ERROR);
          tile.setState(TileState.IDLE);
        }
        return;
      }
      const blob = new Blob([bytes], { type: 'image/png' });
      const objectUrl = URL.createObjectURL(blob);
      // Revoke as soon as the browser has decoded the image — at that point the
      // tile's bitmap is cached internally and the Blob is no longer needed.
      // Keeping a Set + destroying on unmount (the previous shape) leaked one
      // Blob per tile fetch for the component's lifetime; on a long pan/zoom
      // session that adds hundreds of MB of orphaned blobs.
      const release = (): void => URL.revokeObjectURL(objectUrl);
      image.addEventListener('load', release, { once: true });
      image.addEventListener('error', release, { once: true });
      image.src = objectUrl;
    } catch (err) {
      // OL falls back to interim tiles when the image errors. Logged so a
      // 403/500 leaves a console breadcrumb without spamming toasts.
      console.warn('[OlWmsLayer] tile load failed', err);
      image.src = '';
    }
  }
}
