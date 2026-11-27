import { Component, DestroyRef, effect, inject, input } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import { Overlay, type Map as OlMap } from 'ol';
import type { EventsKey } from 'ol/events';
import type { LineString } from 'ol/geom';
import { Draw } from 'ol/interaction';
import type { DrawEvent } from 'ol/interaction/Draw';
import VectorLayer from 'ol/layer/Vector';
import { unByKey } from 'ol/Observable';
import VectorSource from 'ol/source/Vector';
import { getLength } from 'ol/sphere';
import { Circle, Fill, Stroke, Style } from 'ol/style';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { MapShellService } from '@niord/map/map-shell.service';
import { readCssVar } from '@niord/map/css-tokens';
import { formatDistance } from '../../../utils/distance-format';

const FEATURE_PROJECTION = 'EPSG:3857';
/** WGS-84 equatorial radius. OL's getLength otherwise defaults to the mean radius (6371008.8 m),
 * reading ~0.11% short, so pass this explicitly. */
const WGS84_RADIUS = 6378137;
const TOOLTIP_CLASS =
  'pointer-events-none rounded border border-surface-300 bg-surface-0/95 px-1.5 py-0.5 text-xs whitespace-nowrap shadow dark:border-surface-700 dark:bg-surface-900/95';
/** Frozen (finished) measurement — solid amber chip, distinct from the live tooltip. */
const STATIC_TOOLTIP_CLASS =
  'pointer-events-none rounded border border-white bg-[var(--niord-measure-stroke)] px-1.5 py-0.5 text-xs font-medium whitespace-nowrap text-black shadow';

let measureStyleCache: Style | undefined;
function measureStyle(): Style {
  if (measureStyleCache) return measureStyleCache;
  const stroke = readCssVar('--niord-measure-stroke', '#ffcc33');
  const fill = readCssVar('--niord-measure-fill', 'rgba(255, 255, 255, 0.2)');
  measureStyleCache = new Style({
    fill: new Fill({ color: fill }),
    stroke: new Stroke({ color: stroke, width: 2, lineDash: [8, 8] }),
    image: new Circle({ radius: 5, fill: new Fill({ color: stroke }) }),
  });
  return measureStyleCache;
}

/**
 * The measure tooltip's text for a length in metres: metres · kilometres · nautical miles, in the
 * locale's numbers, each unit written as the language's text for it (`common.distanceUnit.<unit>`).
 */
export function measureLabel(meters: number, locale: string, transloco: TranslocoService): string {
  const distance = formatDistance(meters, locale, (unit) =>
    transloco.translate(`common.distanceUnit.${unit}`),
  );
  return `${distance.meters} · ${distance.kilometers} · ${distance.nauticalMiles}`;
}

/**
 * The measure tool. When `[active]`
 * (the editor's `drawMode === 'measure'`), a LineString draw is enabled and a live OL
 * `Overlay` tooltip shows the path length in m / km / nm (geodesic, via `ol/sphere`
 * getLength). Each completed measurement freezes its tooltip; deactivating clears them.
 *
 * Registers via `MapShellService` (same content-projection pattern as the other layers);
 * the draw interaction + overlays are added straight to the shell map.
 */
@Component({
  selector: 'app-ol-measure-layer',
  host: { class: 'contents' },
  template: '',
})
export class OlMeasureLayer {
  private readonly shell = inject(MapShellService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly language = inject(LanguageService);
  private readonly transloco = inject(TranslocoService);

  readonly active = input<boolean>(false);

  private readonly source = new VectorSource({ wrapX: false });
  private layer: VectorLayer | undefined;
  private draw: Draw | undefined;
  private built = false;
  private lastActive: boolean | undefined;

  private liveTooltipElement: HTMLElement | undefined;
  private liveTooltip: Overlay | undefined;
  private readonly overlays: Overlay[] = [];
  private sketchListener: EventsKey | undefined;
  private readonly drawListeners: EventsKey[] = [];

  constructor() {
    effect(() => {
      const map = this.shell.map();
      if (map) this.ensure(map);
      this.syncActive(this.active());
    });

    this.destroyRef.onDestroy(() => this.teardown());
  }

  private ensure(map: OlMap): void {
    if (this.built) return;
    this.built = true;

    this.layer = new VectorLayer({ source: this.source, zIndex: 70, style: measureStyle() });
    this.layer.setVisible(false);
    this.shell.registerLayer(this.layer);

    this.draw = new Draw({ source: this.source, type: 'LineString', style: measureStyle() });
    this.draw.setActive(false);
    map.addInteraction(this.draw);
    this.drawListeners.push(
      this.draw.on('drawstart', (event) => this.onDrawStart(event)),
      this.draw.on('drawend', () => this.onDrawEnd()),
    );
  }

  private syncActive(active: boolean): void {
    if (!this.built || active === this.lastActive) return;
    this.lastActive = active;
    this.layer?.setVisible(active);
    this.draw?.setActive(active);
    this.clear();
    if (active) this.createLiveTooltip();
  }

  private onDrawStart(event: DrawEvent): void {
    const geometry = event.feature.getGeometry() as LineString;
    this.sketchListener = geometry.on('change', () => {
      const length = getLength(geometry, { projection: FEATURE_PROJECTION, radius: WGS84_RADIUS });
      if (this.liveTooltipElement) {
        this.liveTooltipElement.textContent = measureLabel(
          length,
          this.language.activeLocale(),
          this.transloco,
        );
      }
      this.liveTooltip?.setPosition(geometry.getLastCoordinate());
    });
  }

  private onDrawEnd(): void {
    // Freeze the just-finished tooltip in place (distinct static style + tighter offset)
    // and open a fresh one for the next path.
    if (this.sketchListener) {
      unByKey(this.sketchListener);
      this.sketchListener = undefined;
    }
    if (this.liveTooltipElement) {
      this.liveTooltipElement.className = STATIC_TOOLTIP_CLASS;
    }
    this.liveTooltip?.setOffset([0, -7]);
    this.liveTooltip = undefined;
    this.liveTooltipElement = undefined;
    this.createLiveTooltip();
  }

  private createLiveTooltip(): void {
    const map = this.shell.map();
    if (!map) return;
    const element = document.createElement('div');
    element.className = TOOLTIP_CLASS;
    const overlay = new Overlay({
      element,
      offset: [0, -12],
      positioning: 'bottom-center',
      stopEvent: false,
    });
    map.addOverlay(overlay);
    this.overlays.push(overlay);
    this.liveTooltipElement = element;
    this.liveTooltip = overlay;
  }

  private clear(): void {
    this.source.clear();
    const map = this.shell.map();
    for (const overlay of this.overlays) {
      map?.removeOverlay(overlay);
    }
    this.overlays.length = 0;
    this.liveTooltip = undefined;
    this.liveTooltipElement = undefined;
    if (this.sketchListener) {
      unByKey(this.sketchListener);
      this.sketchListener = undefined;
    }
  }

  private teardown(): void {
    const map = this.shell.map();
    for (const key of this.drawListeners) {
      unByKey(key);
    }
    this.drawListeners.length = 0;
    if (this.draw) {
      map?.removeInteraction(this.draw);
      this.draw.dispose();
      this.draw = undefined;
    }
    if (this.layer) {
      this.shell.unregisterLayer(this.layer);
      this.layer.dispose();
    }
    this.clear();
    this.source.dispose();
  }
}
