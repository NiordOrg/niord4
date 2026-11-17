import { Component, DestroyRef, effect, inject } from '@angular/core';
import { Overlay, type Map as OlMap, type MapBrowserEvent } from 'ol';
import type { EventsKey } from 'ol/events';
import { unByKey } from 'ol/Observable';

import { LanguageService } from '../../../core/main-shell/services/language.service';
import { MapProjectionService } from '@niord/map/map-projection.service';
import { formatLatLon } from '../../utils/coordinate-format';
import { MapShellService } from '@niord/map/map-shell.service';

const READOUT_CLASS =
  'pointer-events-none rounded border border-surface-300 bg-surface-0/90 px-1.5 py-0.5 font-mono text-[0.7rem] whitespace-nowrap shadow dark:border-surface-700 dark:bg-surface-900/90';

/**
 * A small always-on coordinate readout that follows the pointer and shows the cursor
 * position in DDM. Drop it inside an `app-ol-map`; it registers an OL `Overlay` on the
 * shell map and updates on pointermove,
 * hiding when the pointer leaves the map.
 */
@Component({
  selector: 'app-ol-coordinate-readout',
  host: { class: 'contents' },
  template: '',
})
export class OlCoordinateReadout {
  private readonly shell = inject(MapShellService);
  private readonly projection = inject(MapProjectionService);
  private readonly language = inject(LanguageService);
  private readonly destroyRef = inject(DestroyRef);

  private element: HTMLElement | undefined;
  private overlay: Overlay | undefined;
  private attachedMap: OlMap | undefined;
  private moveKey: EventsKey | undefined;
  private viewport: HTMLElement | undefined;
  private readonly hide = (): void => this.overlay?.setPosition(undefined);

  constructor() {
    effect(() => {
      const map = this.shell.map();
      this.teardown();
      if (!map) return;

      this.element = document.createElement('div');
      this.element.className = READOUT_CLASS;
      this.overlay = new Overlay({
        element: this.element,
        offset: [12, 0],
        positioning: 'center-left',
        stopEvent: false,
      });
      map.addOverlay(this.overlay);
      this.attachedMap = map;
      this.moveKey = map.on('pointermove', (event) => this.onMove(event));
      this.viewport = map.getViewport();
      this.viewport.addEventListener('pointerleave', this.hide);
    });

    this.destroyRef.onDestroy(() => this.teardown());
  }

  private onMove(event: MapBrowserEvent): void {
    const [lon, lat] = this.projection.toLonLat(event.coordinate);
    if (this.element) {
      this.element.textContent = formatLatLon(lat, lon, {
        locale: this.language.activeLocale(),
      });
    }
    this.overlay?.setPosition(event.coordinate);
  }

  private teardown(): void {
    if (this.moveKey) {
      unByKey(this.moveKey);
      this.moveKey = undefined;
    }
    this.viewport?.removeEventListener('pointerleave', this.hide);
    this.viewport = undefined;
    if (this.overlay) {
      this.attachedMap?.removeOverlay(this.overlay);
      this.overlay = undefined;
    }
    this.attachedMap = undefined;
    this.element = undefined;
  }
}
