import { Component, inject, input } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';

import { AuthFacade } from '../../../core/auth/facades/auth.facade';
import { APP_CONFIG } from '../../../core/config/app-config.token';
import { OlOpenSeaMapLayer } from '@niord/map/layers/ol-openseamap-layer';
import { OlTileLayer } from '@niord/map/layers/ol-tile-layer';
import { OlWmsLayer } from '@niord/map/layers/ol-wms-layer';
import { wmsLayerVisibility } from './wms-layer-visibility';

/**
 * The standard base-layer set for an `<app-ol-map>`: the OSM tile layer plus
 * the optional OpenSeaMap and WMS overlays, all wired to `AppConfig.map` and
 * registered with the surrounding map shell (so they appear in the layer
 * switcher). The two tile addresses reach the kit's layers through
 * `MAP_TILE_SOURCES`, provided in `app.config.ts`, so no binding carries them.
 * Project it as the FIRST content inside `<app-ol-map>` so the base layers
 * render underneath the host's feature layers.
 *
 * - OSM is always present, visible, and offered in the layer switcher.
 * - OpenSeaMap is always registered (initially hidden) but only offered in
 *   the switcher when `openSeaMapLayerEnabled` is set; hosts whose map never
 *   offers OpenSeaMap opt out entirely via `[openSeaMap]="false"`.
 * - WMS renders only when the deployment enables it (`wmsLayerEnabled` +
 *   `wmsUrl`) and — for a protected WMS — the user is authenticated (see
 *   {@link wmsLayerVisibility}); it starts visible per `wmsLayerEnabledByDefault`.
 */
@Component({
  selector: 'app-base-map-layers',
  host: { class: 'contents' },
  imports: [OlOpenSeaMapLayer, OlTileLayer, OlWmsLayer],
  template: `
    <app-ol-tile-layer
      source="OSM"
      [name]="osmName()"
      [visible]="true"
      [showInLayerSwitcher]="true"
    />
    @if (openSeaMap()) {
      <app-ol-openseamap-layer
        [name]="openSeaMapName()"
        [visible]="false"
        [showInLayerSwitcher]="openSeaMapEnabled"
      />
    }
    @if (wmsLayerVisible() && wmsUrl; as url) {
      <app-ol-wms-layer
        [url]="url"
        [name]="wmsName()"
        [visible]="wmsEnabledByDefault"
        [showInLayerSwitcher]="true"
        [protectedMode]="wmsProtected"
      />
    }
  `,
})
export class BaseMapLayers {
  private readonly config = inject(APP_CONFIG);
  private readonly auth = inject(AuthFacade);

  /** Whether the OpenSeaMap overlay belongs to this map at all (default yes). */
  readonly openSeaMap = input<boolean>(true);

  protected readonly openSeaMapEnabled = this.config.map?.openSeaMapLayerEnabled === true;
  // WMS is only rendered when both an URL and the enable flag are set.
  // Missing URL → no point in showing the switcher entry; nothing would load.
  protected readonly wmsUrl = this.config.map?.wmsUrl;
  protected readonly wmsProtected = this.config.map?.wmsProtected === true;
  protected readonly wmsEnabledByDefault = this.config.map?.wmsLayerEnabledByDefault === true;
  protected readonly wmsLayerVisible = wmsLayerVisibility(this.config.map, this.auth);

  protected readonly osmName = translateSignal('shared.map.layers.osm');
  protected readonly openSeaMapName = translateSignal('shared.map.layers.openSeaMap');
  protected readonly wmsName = translateSignal('shared.map.layers.wms');
}
