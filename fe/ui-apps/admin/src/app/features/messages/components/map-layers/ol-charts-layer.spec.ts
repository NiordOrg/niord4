import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Map as OlMapImpl } from 'ol';
import VectorLayer from 'ol/layer/Vector';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { of, Subject } from 'rxjs';

import { MapShellService } from '@niord/map/map-shell.service';
import type { ChartRef } from '../../models/messages-filter-params.model';
import { ChartsApiService } from '../../services/api/charts-api.service';
import { MessageGeometryService } from '../../services/message-geometry.service';
import { OlChartsLayer } from './ol-charts-layer';

const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

@Component({
  template: `<app-ol-charts-layer [charts]="charts()" />`,
  imports: [OlChartsLayer],
  providers: [MapShellService],
})
class HostComponent {
  readonly charts = signal<readonly ChartRef[]>([]);
}

const POLYGON_GEOMETRY = {
  type: 'Polygon' as const,
  coordinates: [
    [
      [10, 54],
      [12, 54],
      [12, 56],
      [10, 56],
      [10, 54],
    ],
  ],
};

describe('OlChartsLayer', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
  let shell: MapShellService;
  let api: { getByChartNumbers: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    api = { getByChartNumbers: vi.fn(() => of([])) };
    TestBed.configureTestingModule({ providers: [{ provide: ChartsApiService, useValue: api }] });
    fixture = TestBed.createComponent(HostComponent);
    shell = fixture.debugElement.injector.get(MapShellService);
    shell.setMap(new OlMapImpl({}));
  });

  afterEach(() => fixture.destroy());

  it('registers a single VectorLayer on init', () => {
    fixture.detectChanges();
    const layers = shell.layers();
    expect(layers).toHaveLength(1);
    expect(layers[0]).toBeInstanceOf(VectorLayer);
  });

  it('renders chart features when geometry is already present (no API call)', () => {
    fixture.componentInstance.charts.set([{ chartNumber: '101', geometry: POLYGON_GEOMETRY }]);
    fixture.detectChanges();
    const layer = shell.layers()[0] as VectorLayer;
    expect(layer.getSource()?.getFeatures()).toHaveLength(1);
    expect(api.getByChartNumbers).not.toHaveBeenCalled();
  });

  it('fetches geometries when refs are missing them, then renders the response', () => {
    api.getByChartNumbers.mockReturnValueOnce(
      of([{ chartNumber: '101', geometry: POLYGON_GEOMETRY }]),
    );
    fixture.componentInstance.charts.set([{ chartNumber: '101' }]);
    fixture.detectChanges();
    expect(api.getByChartNumbers).toHaveBeenCalledWith(['101'], { includeGeometry: true });
    const layer = shell.layers()[0] as VectorLayer;
    expect(layer.getSource()?.getFeatures()).toHaveLength(1);
  });

  it('clears the source when charts becomes empty', () => {
    fixture.componentInstance.charts.set([{ chartNumber: '101', geometry: POLYGON_GEOMETRY }]);
    fixture.detectChanges();
    fixture.componentInstance.charts.set([]);
    fixture.detectChanges();
    const layer = shell.layers()[0] as VectorLayer;
    expect(layer.getSource()?.getFeatures()).toHaveLength(0);
  });

  it('does not process a late geometry response after the layer is destroyed', () => {
    // In-flight enrichment request that resolves only after destroy.
    const enriched$ = new Subject<ChartRef[]>();
    api.getByChartNumbers.mockReturnValueOnce(enriched$);
    const gjSpy = vi.spyOn(TestBed.inject(MessageGeometryService), 'gjToOlGeometry');

    fixture.componentInstance.charts.set([{ chartNumber: '101' }]);
    fixture.detectChanges();
    expect(api.getByChartNumbers).toHaveBeenCalled();

    // Destroy disposes the VectorSource; takeUntilDestroyed must cancel the subscription.
    fixture.destroy();
    enriched$.next([{ chartNumber: '101', geometry: POLYGON_GEOMETRY }]);
    enriched$.complete();

    // Callback never ran -> no geometry conversion / addFeature on the disposed source.
    expect(gjSpy).not.toHaveBeenCalled();
  });
});
