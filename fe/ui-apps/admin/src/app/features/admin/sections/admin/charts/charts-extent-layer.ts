import { Component, DestroyRef, effect, inject, input } from '@angular/core';
import { Feature } from 'ol';
import VectorLayer from 'ol/layer/Vector';
import VectorSource from 'ol/source/Vector';
import { Fill, Stroke, Style, Text } from 'ol/style';

import { MapShellService } from '@niord/map/map-shell.service';
import { GeometryConversionService } from '../../../../../shared/services/geometry-conversion.service';
import { readCssVar } from '@niord/map/css-tokens';
import type { Chart } from '../../../services/api/charts-admin-api.service';

// Country-locked Layer-2 tokens (tokens.base.css) — the dashed-red
// chart-coverage visual. Read via readCssVar (canvas styles need literals).
const EXTENT_STROKE_FALLBACK = 'rgba(255, 50, 0, 0.8)';
const LABEL_FILL_FALLBACK = 'rgba(255, 50, 0, 0.8)';
const LABEL_STROKE_FALLBACK = 'rgba(255, 255, 255, 0.9)';

/** Fit-extent zoom cap for the charts overview layer. */
const FIT_MAX_ZOOM = 12;

/**
 * Read-only outline layer for the Charts overview map: one dashed outline +
 * corner label per chart on the CURRENT page slice (the map shows the visible
 * page, not the whole catalog), re-fitting the view to the
 * rendered extents whenever the slice changes.
 */
@Component({
  selector: 'app-charts-extent-layer',
  host: { class: 'contents' },
  template: '',
})
export class ChartsExtentLayer {
  private readonly shell = inject(MapShellService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly conversion = inject(GeometryConversionService);

  /** The current page slice — only these charts' extents are outlined. */
  readonly charts = input.required<readonly Chart[]>();

  private readonly colors = {
    stroke: readCssVar('--niord-chart-extent-stroke', EXTENT_STROKE_FALLBACK),
    labelFill: readCssVar('--niord-chart-extent-label-fill', LABEL_FILL_FALLBACK),
    labelStroke: readCssVar('--niord-chart-extent-label-stroke', LABEL_STROKE_FALLBACK),
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
      this.refreshFeatures(this.charts());
      this.fitToExtents();
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

  private refreshFeatures(charts: readonly Chart[]): void {
    this.source.clear();
    for (const chart of charts) {
      if (!chart.geometry) continue;
      const feature = new Feature(this.conversion.gjToOlGeometry(chart.geometry));
      feature.set('chartLabel', this.labelFor(chart));
      this.source.addFeature(feature);
    }
  }

  /** Fits the view to the rendered extents (no persisted view state). */
  private fitToExtents(): void {
    const map = this.shell.map();
    const extent = this.source.getExtent();
    if (!map || this.source.isEmpty() || !extent) return;
    map.getView().fit(extent, {
      maxZoom: FIT_MAX_ZOOM,
      padding: [24, 24, 24, 24],
    });
  }

  private labelFor(chart: Chart): string {
    return chart.internationalNumber !== undefined
      ? `${chart.chartNumber} (INT ${chart.internationalNumber})`
      : chart.chartNumber;
  }

  private styleFor(feature: Feature): Style {
    const label = (feature.get('chartLabel') as string) ?? '';
    return new Style({
      stroke: new Stroke({ color: this.colors.stroke, width: 2, lineDash: [4, 4] }),
      text: new Text({
        text: label,
        font: '12px sans-serif',
        fill: new Fill({ color: this.colors.labelFill }),
        stroke: new Stroke({ color: this.colors.labelStroke, width: 3 }),
        textAlign: 'left',
        textBaseline: 'top',
      }),
    });
  }
}
