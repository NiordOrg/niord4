import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Map as OlMapImpl } from 'ol';
import VectorLayer from 'ol/layer/Vector';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { of, Subject } from 'rxjs';

import { MapShellService } from '@niord/map/map-shell.service';
import type { AreaRef } from '../../models/messages-filter-params.model';
import { AreasApiService } from '../../services/api/areas-api.service';
import { MessageGeometryService } from '../../services/message-geometry.service';
import { OlAreasLayer } from './ol-areas-layer';

const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

@Component({
  template: `<app-ol-areas-layer [areas]="areas()" [hovered]="hovered()" />`,
  imports: [OlAreasLayer],
  providers: [MapShellService],
})
class HostComponent {
  readonly areas = signal<readonly AreaRef[]>([]);
  readonly hovered = signal<number | undefined>(undefined);
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

describe('OlAreasLayer', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
  let shell: MapShellService;
  let api: { getByIds: ReturnType<typeof vi.fn> };

  beforeEach(() => {
    api = { getByIds: vi.fn(() => of([])) };
    TestBed.configureTestingModule({ providers: [{ provide: AreasApiService, useValue: api }] });
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

  it('renders area features when geometry is already present (no API call)', () => {
    fixture.componentInstance.areas.set([
      { id: 1, descs: [{ name: 'Skagerrak' }], geometry: POLYGON_GEOMETRY },
    ]);
    fixture.detectChanges();
    const layer = shell.layers()[0] as VectorLayer;
    expect(layer.getSource()?.getFeatures()).toHaveLength(1);
    expect(api.getByIds).not.toHaveBeenCalled();
  });

  it('fetches geometries when refs are missing them, then renders the response', () => {
    api.getByIds.mockReturnValueOnce(
      of([{ id: 1, descs: [{ name: 'Skagerrak' }], geometry: POLYGON_GEOMETRY }]),
    );
    fixture.componentInstance.areas.set([{ id: 1 }]);
    fixture.detectChanges();
    expect(api.getByIds).toHaveBeenCalledWith([1], { includeGeometry: true });
    const layer = shell.layers()[0] as VectorLayer;
    expect(layer.getSource()?.getFeatures()).toHaveLength(1);
  });

  it('clears the source when areas becomes empty', () => {
    fixture.componentInstance.areas.set([
      { id: 1, descs: [{ name: 'X' }], geometry: POLYGON_GEOMETRY },
    ]);
    fixture.detectChanges();
    fixture.componentInstance.areas.set([]);
    fixture.detectChanges();
    const layer = shell.layers()[0] as VectorLayer;
    expect(layer.getSource()?.getFeatures()).toHaveLength(0);
  });

  it('adds a hover halo (two styles) to the highlighted area only', () => {
    fixture.componentInstance.areas.set([
      { id: 1, descs: [{ name: 'A' }], geometry: POLYGON_GEOMETRY },
      { id: 2, descs: [{ name: 'B' }], geometry: POLYGON_GEOMETRY },
    ]);
    fixture.detectChanges();
    const layer = shell.layers()[0] as VectorLayer;
    const styleFn = layer.getStyle() as unknown as (f: unknown, r: number) => unknown;
    const features = layer.getSource()!.getFeatures();
    const f1 = features.find((f) => f.get('areaId') === 1)!;
    const f2 = features.find((f) => f.get('areaId') === 2)!;

    // Nothing hovered → a single base style per feature.
    expect(Array.isArray(styleFn(f1, 1))).toBe(false);

    fixture.componentInstance.hovered.set(1);
    fixture.detectChanges();
    expect(Array.isArray(styleFn(f1, 1))).toBe(true); // halo + base
    expect(Array.isArray(styleFn(f2, 1))).toBe(false); // others unaffected
  });

  it('does not process a late geometry response after the layer is destroyed', () => {
    // In-flight enrichment request that resolves only after destroy.
    const enriched$ = new Subject<AreaRef[]>();
    api.getByIds.mockReturnValueOnce(enriched$);
    const gjSpy = vi.spyOn(TestBed.inject(MessageGeometryService), 'gjToOlGeometry');

    fixture.componentInstance.areas.set([{ id: 1 }]);
    fixture.detectChanges();
    expect(api.getByIds).toHaveBeenCalled();

    // Destroy disposes the VectorSource; takeUntilDestroyed must cancel the subscription.
    fixture.destroy();
    enriched$.next([{ id: 1, descs: [{ name: 'Late' }], geometry: POLYGON_GEOMETRY }]);
    enriched$.complete();

    // Callback never ran -> no geometry conversion / addFeature on the disposed source.
    expect(gjSpy).not.toHaveBeenCalled();
  });
});
