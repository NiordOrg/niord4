import { Component, effect, input, viewChild } from '@angular/core';

import type { OlMapView } from '@niord/map/ol-map';
import { OlMap } from '@niord/map/ol-map';
import { OlTileLayer } from '@niord/map/layers/ol-tile-layer';
import { MessageMapTooltip } from '../../../messages/components/map-layers/map-tooltip';
import { OlMessageListLayer } from '../../../messages/components/map-layers/ol-message-list-layer';
import type { Message } from '../../../messages/models/message.model';

/**
 * The landing page's full-area map, split out of `HomePage` purely so it can be `@defer`red.
 *
 * Everything that reaches OpenLayers lives here, which is what lets the landing route's chunk
 * drop from ~443 kB to ~20 kB: the welcome panel and the logo (the LCP element) paint without
 * waiting on the map stack.
 *
 * The host is a flex COLUMN, not `display: block` — `<app-ol-map>` sizes itself with
 * `height: 100%`, so it needs a definite-height parent. Making the host a plain block would
 * leave the map 0px tall and the landing page blank.
 */
@Component({
  selector: 'app-home-map',
  imports: [OlMap, OlTileLayer, OlMessageListLayer, MessageMapTooltip],
  templateUrl: './home-map.html',
  host: { class: 'flex flex-1 flex-col' },
})
export class HomeMap {
  readonly view = input<OlMapView | undefined>(undefined);
  readonly messages = input<readonly Message[]>([]);

  private readonly listLayer = viewChild.required(OlMessageListLayer);
  private readonly tooltip = viewChild.required(MessageMapTooltip);

  constructor() {
    effect(() => {
      const tooltip = this.tooltip();
      const listLayer = this.listLayer();
      if (!tooltip || !listLayer) return;
      tooltip.setResolver((pixel) => listLayer.messagesAtPixel(pixel));
    });
  }
}
