import { TestBed } from '@angular/core/testing';
import { fromLonLat } from 'ol/proj';
import { describe, expect, it, vi } from 'vitest';

import { MapShellService } from '@niord/map/map-shell.service';
import type { GeometryFeature } from '../../../../shared/models/geometry.model';
import { InsertLocationsMapLayer } from './insert-locations-map-layer';

const point = (lon: number, lat: number): GeometryFeature => ({
  type: 'Feature',
  geometry: { type: 'Point', coordinates: [lon, lat] },
  properties: {},
});

/** A square area centred on (lon, lat), `half` degrees from centre to edge. */
const square = (lon: number, lat: number, half: number): GeometryFeature => ({
  type: 'Feature',
  geometry: {
    type: 'Polygon',
    coordinates: [
      [
        [lon - half, lat - half],
        [lon + half, lat - half],
        [lon + half, lat + half],
        [lon - half, lat + half],
        [lon - half, lat - half],
      ],
    ],
  },
  properties: {},
});

/**
 * A minimal fake OL map: it captures the `singleclick` handler the layer attaches, and lets a test
 * decide which feature index (if any) a click "hits", and where on the chart the click lands.
 * Real canvas hit-testing can't run in jsdom.
 */
function setup(
  hitIdx: number | undefined,
  features: GeometryFeature[] = [point(10, 55), point(11, 56)],
  clickedAt: number[] | null = null,
) {
  let clickHandler: ((event: { pixel: number[] }) => void) | undefined;
  const fakeMap = {
    getLayers: () => ({ getArray: () => [] as unknown[] }),
    addLayer: vi.fn(),
    removeLayer: vi.fn(),
    on: (type: string, handler: (event: { pixel: number[] }) => void) => {
      if (type === 'singleclick') clickHandler = handler;
      return {};
    },
    forEachFeatureAtPixel: (
      _pixel: number[],
      callback: (feature: { get: (key: string) => unknown }) => unknown,
    ) =>
      hitIdx === undefined
        ? undefined
        : callback({ get: (key) => (key === 'idx' ? hitIdx : undefined) }),
    getCoordinateFromPixel: () => clickedAt,
    getView: () => ({ fit: vi.fn() }),
  };

  TestBed.configureTestingModule({ providers: [MapShellService] });
  const shell = TestBed.inject(MapShellService);
  shell.setMap(fakeMap as never);

  const fixture = TestBed.createComponent(InsertLocationsMapLayer);
  fixture.componentRef.setInput('features', features);
  fixture.componentRef.setInput('selectedIndices', new Set<number>());
  const emitted: number[] = [];
  fixture.componentInstance.toggled.subscribe((index) => emitted.push(index));
  fixture.autoDetectChanges();

  return { fixture, emitted, click: () => clickHandler?.({ pixel: [0, 0] }) };
}

describe('InsertLocationsMapLayer', () => {
  it('emits the clicked feature index (map click → checkbox toggle)', async () => {
    const { fixture, emitted, click } = setup(1);
    await fixture.whenStable();
    click();
    expect(emitted).toEqual([1]);
  });

  it('emits nothing when the click misses every feature', async () => {
    const { fixture, emitted, click } = setup(undefined);
    await fixture.whenStable();
    click();
    expect(emitted).toEqual([]);
  });

  // An area is drawn unfilled, so a click inside it hits nothing painted: it picks the innermost
  // area the click lies in.
  it('picks the innermost area a click lands inside, where nothing is painted', async () => {
    const { fixture, emitted, click } = setup(
      undefined,
      [square(10, 55, 1), square(10, 55, 0.1), point(12, 57)],
      fromLonLat([10, 55]),
    );
    await fixture.whenStable();
    click();
    expect(emitted).toEqual([1]);
  });
});
