import { Component, DestroyRef, effect, inject, input } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Feature } from 'ol';
import VectorLayer from 'ol/layer/Vector';
import VectorSource from 'ol/source/Vector';
import { Fill, Stroke, Style, Text } from 'ol/style';

import { MapShellService } from '@niord/map/map-shell.service';
import { readCssVar } from '@niord/map/css-tokens';
import type { ChartRef } from '../../models/messages-filter-params.model';
import { ChartsApiService } from '../../services/api/charts-api.service';
import { MessageGeometryService } from '../../services/message-geometry.service';

// Layer-2 token fallbacks — the chart overlay is themeable (`--niord-chart-*` in
// tokens.default.css, or a country's tokens.<cc>.css), read at runtime via readCssVar since a
// canvas style needs literal strings.
const CHART_STROKE_FALLBACK = 'rgba(0, 102, 204, 0.9)';
const CHART_LABEL_FILL_FALLBACK = 'rgba(0, 51, 102, 1)';
const CHART_LABEL_STROKE_FALLBACK = 'rgba(255, 255, 255, 0.9)';

@Component({
  selector: 'app-ol-charts-layer',
  host: { class: 'contents' },
  template: '',
})
export class OlChartsLayer {
  private readonly shell = inject(MapShellService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly geometry = inject(MessageGeometryService);
  private readonly api = inject(ChartsApiService);

  readonly charts = input.required<readonly ChartRef[]>();
  readonly visible = input<boolean>(true);
  readonly name = input<string>('Charts');
  readonly showInLayerSwitcher = input<boolean>(true);

  // Token colours resolved once at construction (the stylesheet is applied before
  // Angular instantiates any component).
  private readonly colors = {
    stroke: readCssVar('--niord-chart-stroke', CHART_STROKE_FALLBACK),
    labelFill: readCssVar('--niord-chart-label-fill', CHART_LABEL_FILL_FALLBACK),
    labelStroke: readCssVar('--niord-chart-label-stroke', CHART_LABEL_STROKE_FALLBACK),
  };

  private readonly source = new VectorSource({ wrapX: false });
  private layer: VectorLayer | undefined;

  constructor() {
    effect(() => {
      if (!this.layer) {
        this.layer = new VectorLayer({
          source: this.source,
          // Mid-tier overlay — above raster (WMS at zIndex 0), below the
          // message-list layer (zIndex 100).
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
      const charts = this.charts();
      this.refreshFeatures(charts);
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

  private refreshFeatures(charts: readonly ChartRef[]): void {
    this.source.clear();
    if (charts.length === 0) return;
    const missingGeometry = charts.filter((c) => c.geometry === undefined);
    const ready = charts.filter((c) => c.geometry !== undefined);
    for (const chart of ready) {
      this.addChartFeature(chart);
    }
    if (missingGeometry.length === 0) return;
    const numbers = missingGeometry.map((c) => c.chartNumber);
    this.api
      .getByChartNumbers(numbers, { includeGeometry: true })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((enriched) => {
        const stillCurrent = this.charts();
        const stillCurrentNumbers = new Set(stillCurrent.map((c) => c.chartNumber));
        for (const chart of enriched) {
          if (!stillCurrentNumbers.has(chart.chartNumber)) continue;
          this.addChartFeature(chart);
        }
      });
  }

  private addChartFeature(chart: ChartRef): void {
    if (!chart.geometry) return;
    const olGeom = this.geometry.gjToOlGeometry(chart.geometry);
    const feature = new Feature(olGeom);
    feature.set('chartLabel', this.labelFor(chart));
    this.source.addFeature(feature);
  }

  private labelFor(chart: ChartRef): string {
    return chart.internationalNumber !== undefined
      ? `${chart.chartNumber} (${chart.internationalNumber})`
      : chart.chartNumber;
  }

  private styleFor(feature: Feature): Style {
    const label = (feature.get('chartLabel') as string) ?? '';
    return new Style({
      stroke: new Stroke({ color: this.colors.stroke, width: 1.5, lineDash: [6, 4] }),
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
