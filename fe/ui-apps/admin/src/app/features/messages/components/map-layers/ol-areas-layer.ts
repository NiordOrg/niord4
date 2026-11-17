import { Component, DestroyRef, effect, inject, input } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Feature } from 'ol';
import VectorLayer from 'ol/layer/Vector';
import VectorSource from 'ol/source/Vector';
import { Fill, Stroke, Style, Text } from 'ol/style';

import { MapShellService } from '@niord/map/map-shell.service';
import { readCssVar } from '@niord/map/css-tokens';
import type { AreaRef } from '../../models/messages-filter-params.model';
import { AreasApiService } from '../../services/api/areas-api.service';
import { MessageGeometryService } from '../../services/message-geometry.service';

// Layer-2 token fallbacks — the area overlay is themeable (`--niord-area-*` in
// tokens.default.css, or a country's tokens.<cc>.css), read at runtime via readCssVar since a
// canvas style needs literal strings.
const AREA_FILL_FALLBACK = 'rgba(100, 100, 255, 0.4)';
const AREA_STROKE_FALLBACK = 'rgba(40, 40, 100, 1)';
const AREA_LABEL_FILL_FALLBACK = 'rgba(40, 40, 100, 1)';
const AREA_LABEL_STROKE_FALLBACK = 'rgba(255, 255, 255, 0.9)';
// Shared hover-highlight blue (same token as the message map's hover halo) so an
// area highlighted from a side table reads identically to a hovered message.
const AREA_HOVER_HALO_FALLBACK = 'rgba(0, 120, 215, 0.95)';

@Component({
  selector: 'app-ol-areas-layer',
  host: { class: 'contents' },
  template: '',
})
export class OlAreasLayer {
  private readonly shell = inject(MapShellService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly geometry = inject(MessageGeometryService);
  private readonly api = inject(AreasApiService);

  readonly areas = input.required<readonly AreaRef[]>();
  readonly visible = input<boolean>(true);
  readonly name = input<string>('Areas');
  readonly showInLayerSwitcher = input<boolean>(true);
  readonly color = input<string>(readCssVar('--niord-area-fill', AREA_FILL_FALLBACK));
  /** When true, fit the map view to the rendered areas' extent. */
  readonly fitExtent = input<boolean>(false);
  /** Area id to highlight with a hover halo (e.g. hovered in a side table); undefined = none. */
  readonly hovered = input<number | undefined>(undefined);

  // Token colours resolved once at construction (the stylesheet is applied before
  // Angular instantiates any component).
  private readonly colors = {
    stroke: readCssVar('--niord-area-stroke', AREA_STROKE_FALLBACK),
    labelFill: readCssVar('--niord-area-label-fill', AREA_LABEL_FILL_FALLBACK),
    labelStroke: readCssVar('--niord-area-label-stroke', AREA_LABEL_STROKE_FALLBACK),
    hoverHalo: readCssVar('--niord-msg-hover-halo', AREA_HOVER_HALO_FALLBACK),
  };

  private readonly source = new VectorSource({ wrapX: false });
  private layer: VectorLayer | undefined;

  constructor() {
    effect(() => {
      if (!this.layer) {
        this.layer = new VectorLayer({
          source: this.source,
          zIndex: 50,
          style: (feature) => this.styleFor(feature as Feature),
        });
        this.shell.registerLayer(this.layer);
      }
      this.layer.setVisible(this.visible());
      this.layer.set('name', this.name());
      this.layer.set('showInLayerSwitcher', this.showInLayerSwitcher());
    });

    effect(() => {
      const areas = this.areas();
      this.refreshFeatures(areas);
    });

    // Re-render when the highlighted area changes so the hover halo updates.
    effect(() => {
      this.hovered();
      this.layer?.changed();
    });

    this.destroyRef.onDestroy(() => {
      if (this.layer) {
        this.shell.unregisterLayer(this.layer);
        this.layer.dispose();
      }
      this.source.clear();
      this.source.dispose();
    });
  }

  private refreshFeatures(areas: readonly AreaRef[]): void {
    this.source.clear();
    if (areas.length === 0) return;
    const missingGeometry = areas.filter((a) => a.geometry === undefined);
    const ready = areas.filter((a) => a.geometry !== undefined);
    for (const area of ready) {
      this.addAreaFeature(area);
    }
    if (missingGeometry.length === 0) {
      this.fitIfRequested();
      return;
    }
    // Fit to the already-geometried subset now; re-fit once the rest arrives.
    this.fitIfRequested();
    const ids = missingGeometry.map((a) => a.id);
    this.api
      .getByIds(ids, { includeGeometry: true })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((enriched) => {
        const stillCurrent = this.areas();
        const stillCurrentIds = new Set(stillCurrent.map((a) => a.id));
        for (const area of enriched) {
          if (!stillCurrentIds.has(area.id)) continue;
          this.addAreaFeature(area);
        }
        this.fitIfRequested();
      });
  }

  /** Fits the view to the rendered areas when `fitExtent` is set (no persisted view state). */
  private fitIfRequested(): void {
    if (!this.fitExtent()) return;
    const map = this.shell.map();
    const extent = this.source.getExtent();
    if (!map || this.source.isEmpty() || !extent) return;
    map.getView().fit(extent, { maxZoom: 12, padding: [24, 24, 24, 24] });
  }

  private addAreaFeature(area: AreaRef): void {
    if (!area.geometry) return;
    const olGeom = this.geometry.gjToOlGeometry(area.geometry);
    const feature = new Feature(olGeom);
    feature.set('areaId', area.id);
    feature.set('areaLabel', area.descs?.[0]?.name ?? '');
    this.source.addFeature(feature);
  }

  private styleFor(feature: Feature): Style | Style[] {
    const label = (feature.get('areaLabel') as string) ?? '';
    const base = new Style({
      fill: new Fill({ color: this.color() }),
      stroke: new Stroke({ color: this.colors.stroke, width: 1 }),
      text: new Text({
        text: label,
        font: '12px sans-serif',
        fill: new Fill({ color: this.colors.labelFill }),
        stroke: new Stroke({ color: this.colors.labelStroke, width: 3 }),
      }),
    });
    const hovered = this.hovered();
    if (hovered === undefined || feature.get('areaId') !== hovered) {
      return base;
    }
    // Hover halo: a wider stroke drawn beneath the base so it rings the area.
    const halo = new Style({ stroke: new Stroke({ color: this.colors.hoverHalo, width: 6 }) });
    return [halo, base];
  }
}
