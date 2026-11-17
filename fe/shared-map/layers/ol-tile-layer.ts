import { Component, DestroyRef, effect, inject, input, untracked } from '@angular/core';
import TileLayer from 'ol/layer/Tile';
import OSM from 'ol/source/OSM';
import XYZ from 'ol/source/XYZ';

import { MapShellService } from '../map-shell.service';
import { MAP_TILE_SOURCES } from '../tile-sources';

export type OlTileLayerSource = 'OSM' | 'XYZ';

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
  selector: 'app-ol-tile-layer',
  host: { class: 'contents' },
  template: '',
})
export class OlTileLayer {
  private readonly shell = inject(MapShellService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly tileSources = inject(MAP_TILE_SOURCES);

  readonly source = input<OlTileLayerSource>('OSM');
  /**
   * Tile URL template. Required for `XYZ`; for `OSM` it overrides the deployment's address
   * (`MAP_TILE_SOURCES`), and unset or blank leaves that address, or OL's own without one.
   */
  readonly url = input<string | undefined>(undefined);
  readonly visible = input<boolean>(true);
  readonly name = input<string>('Tiles');
  readonly showInLayerSwitcher = input<boolean>(false);
  readonly opacity = input<number>(1);
  /**
   * Attribution HTML for the base layer, rendered by OpenLayers' attribution control. Unset
   * leaves the source's own default; a host that must name a second authority — a public site
   * showing the site owner's copyright and accessibility statement beside OpenStreetMap's —
   * passes its own list, and passing a list built from translations keeps the credits in the
   * reader's language when they switch it.
   */
  readonly attributions = input<readonly string[] | undefined>(undefined);

  private layer: TileLayer | undefined;

  constructor() {
    effect(() => {
      // Build the layer lazily on the first effect run — by then inputs are bound.
      if (!this.layer) {
        this.layer = untracked(() => this.buildLayer());
        this.shell.registerLayer(this.layer);
      }
      const attributions = this.attributions();
      if (attributions) this.layer.getSource()?.setAttributions([...attributions]);
      this.layer.setVisible(this.visible());
      this.layer.setOpacity(this.opacity());
      this.layer.set('name', this.name());
      this.layer.set('showInLayerSwitcher', this.showInLayerSwitcher());
    });
    this.destroyRef.onDestroy(() => {
      if (this.layer) this.shell.unregisterLayer(this.layer);
    });
  }

  private buildLayer(): TileLayer {
    const source = this.source();
    // Loading is throttled at the map level by the tile-load gate (see
    // installTileLoadGate), so the sources use OL's default image loader.
    if (source === 'XYZ') {
      const url = this.url();
      if (!url) {
        throw new Error('OlTileLayer: XYZ source requires url input');
      }
      return new TileLayer({
        source: new XYZ({ url, crossOrigin: 'anonymous' }),
        cacheSize: TILE_CACHE_SIZE,
      });
    }
    // The host's `url` first (a map that needs another source), then the deployment's
    // address, then OL's built-in `https://tile.openstreetmap.org`. A blank value is no
    // address at either step: OL would take an empty template literally and load nothing.
    const osmUrl = this.url() || this.tileSources.osmUrl;
    return new TileLayer({
      source: new OSM(osmUrl ? { url: osmUrl } : {}),
      cacheSize: TILE_CACHE_SIZE,
    });
  }
}
