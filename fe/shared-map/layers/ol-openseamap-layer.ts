import { Component, DestroyRef, effect, inject, input } from '@angular/core';
import TileLayer from 'ol/layer/Tile';
import XYZ from 'ol/source/XYZ';

import { MapShellService } from '../map-shell.service';
import { MAP_TILE_SOURCES } from '../tile-sources';

/** The public OpenSeaMap seamark tile server, over https so no page asks for http images. */
const DEFAULT_OPEN_SEA_MAP_URL = 'https://t1.openseamap.org/seamark/{z}/{x}/{y}.png';

@Component({
  selector: 'app-ol-openseamap-layer',
  host: { class: 'contents' },
  template: '',
})
export class OlOpenSeaMapLayer {
  private readonly shell = inject(MapShellService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly tileSources = inject(MAP_TILE_SOURCES);

  readonly visible = input<boolean>(false);
  readonly name = input<string>('OpenSeaMap');
  readonly showInLayerSwitcher = input<boolean>(true);
  /**
   * Tile URL template. Overrides the deployment's address (`MAP_TILE_SOURCES`); unset or blank
   * leaves that address, or the public tile server without one.
   */
  readonly url = input<string | undefined>(undefined);

  private layer: TileLayer | undefined;

  constructor() {
    effect(() => {
      if (!this.layer) {
        // No crossOrigin — the public OpenSeaMap tile server doesn't send
        // Access-Control-Allow-Origin, so requesting CORS-mode fetch blocks every
        // tile. Tiles render fine as standard <img> loads; the resulting canvas is
        // "tainted" (no pixel readback) but we never export it. Loading is
        // throttled at the map level by the tile-load gate. A blank address is no
        // address: XYZ would take an empty template literally and load nothing.
        this.layer = new TileLayer({
          source: new XYZ({
            url: this.url() || this.tileSources.openSeaMapUrl || DEFAULT_OPEN_SEA_MAP_URL,
          }),
        });
        this.shell.registerLayer(this.layer);
      }
      this.layer.setVisible(this.visible());
      this.layer.set('name', this.name());
      this.layer.set('showInLayerSwitcher', this.showInLayerSwitcher());
      // Switcher-list order (NOT render order). OpenSeaMap sits second in the
      // layer switcher panel, just below WMS. See OlLayerSwitcher.
      this.layer.set('switcherOrder', 20);
    });
    this.destroyRef.onDestroy(() => {
      if (this.layer) this.shell.unregisterLayer(this.layer);
    });
  }
}
