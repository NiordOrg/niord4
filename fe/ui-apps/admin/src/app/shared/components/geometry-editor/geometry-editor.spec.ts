import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { By } from '@angular/platform-browser';
import { Feature, type Map as OlMap } from 'ol';
import Collection from 'ol/Collection';
import { GeoJSON } from 'ol/format';
import { LineString, Point } from 'ol/geom';
import DragAndDrop, { DragAndDropEvent } from 'ol/interaction/DragAndDrop';
import Modify, { ModifyEvent } from 'ol/interaction/Modify';
import Translate, { TranslateEvent } from 'ol/interaction/Translate';
import { fromLonLat, get as getProjection } from 'ol/proj';
import { describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../core/config/app-config.token';
import { DomainFacade } from '../../../core/domain/facades/domain.facade';
import { provideFakeAuthFacade } from '../../testing/fake-auth';
import { provideTestTransloco } from '../../testing/transloco-testing';
import type { GeometryFeatureCollection } from '../../models/geometry.model';
import { OlEditorLayer } from './ol-editor-layer/ol-editor-layer';
import { GeometryEditor } from './geometry-editor';

// jsdom lacks ResizeObserver, which ol.Map's constructor references (the editor renders a map).
const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

interface DrawModeSignal {
  (): string;
  set(value: string): void;
}

interface EditorHandle {
  commit(): void;
  cancel(): void;
  features(): readonly unknown[];
  seed(): readonly unknown[];
  drawMode: DrawModeSignal;
  drawModes(): readonly string[];
  onReorderFeatures(event: { previousIndex: number; currentIndex: number }): void;
}

const pointFc = {
  type: 'FeatureCollection',
  features: [
    { type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [12, 55] } },
  ],
} as unknown as GeometryFeatureCollection;

// Two named points so a reorder is observable by identity (same OL instances, reordered) and by
// the round-tripped properties on commit.
const twoNamedFc = {
  type: 'FeatureCollection',
  features: [
    {
      type: 'Feature',
      properties: { 'name:en': 'alpha' },
      geometry: { type: 'Point', coordinates: [12, 55] },
    },
    {
      type: 'Feature',
      properties: { 'name:en': 'beta' },
      geometry: { type: 'Point', coordinates: [13, 56] },
    },
  ],
} as unknown as GeometryFeatureCollection;

function setup(geometry?: GeometryFeatureCollection) {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      // Stub DomainFacade (the editor's domain-default map view reads mapDefaults()) so the real
      // facade's AuthFacade → KEYCLOAK chain isn't pulled into the test.
      { provide: DomainFacade, useValue: { mapDefaults: () => ({}) } as unknown as DomainFacade },
      provideFakeAuthFacade(),
      provideTestTransloco(),
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  const fixture = TestBed.createComponent(GeometryEditor);
  if (geometry) {
    fixture.componentRef.setInput('geometry', geometry);
  }
  fixture.detectChanges();
  TestBed.tick();
  return { fixture, view: fixture.componentInstance as unknown as EditorHandle };
}

describe('GeometryEditor (commit-on-OK contract)', () => {
  it('imports the input geometry into the working feature model', () => {
    const { view } = setup(pointFc);
    expect(view.features()).toHaveLength(1);
  });

  it('commit with no edits emits cancelled, not a phantom geometryChange', () => {
    const { fixture, view } = setup(pointFc);
    const emitted: GeometryFeatureCollection[] = [];
    let cancelled = false;
    fixture.componentInstance.geometryChange.subscribe((g) => emitted.push(g));
    fixture.componentInstance.cancelled.subscribe(() => (cancelled = true));

    view.commit();

    expect(emitted).toHaveLength(0);
    expect(cancelled).toBe(true);
  });

  it('cancel discards working changes (re-imports the input) and emits cancelled', () => {
    const { fixture, view } = setup(pointFc);
    let cancelled = false;
    fixture.componentInstance.cancelled.subscribe(() => (cancelled = true));

    view.cancel();

    expect(cancelled).toBe(true);
    expect(view.features()).toHaveLength(1);
  });

  it('starts with an empty model when no geometry is provided', () => {
    const { view } = setup();
    expect(view.features()).toHaveLength(0);
  });

  it('parses the drawControls CSV into the enabled draw modes', () => {
    const { view } = setup(pointFc);
    expect(view.drawModes()).toEqual([
      'point',
      'polyline',
      'polygon',
      'select',
      'remove',
      'measure',
    ]);
  });

  it('opens in point mode on EXISTING geometry too, and switches when a mode is chosen', () => {
    const { view } = setup(pointFc);
    expect(view.drawMode()).toBe('point');
    view.drawMode.set('polygon');
    expect(view.drawMode()).toBe('polygon');
  });

  it('opens in point mode on an EMPTY geometry', () => {
    const { view } = setup();
    expect(view.drawMode()).toBe('point');
  });

  it('falls back to select where the host offers no point tool (the chart extent editor)', () => {
    const { fixture, view } = setup(pointFc);
    // Chart extents are areas, not positions — its toolbar is box/polygon/select/remove. Defaulting
    // to a mode with no matching button would drop stray points into a coverage extent.
    fixture.componentRef.setInput('drawControls', 'box,polygon,select,remove');
    fixture.componentRef.setInput('geometry', { type: 'FeatureCollection', features: [] });
    fixture.detectChanges();
    expect(view.drawMode()).toBe('select');
  });

  it('drag-reorder swaps the working feature order and commit round-trips the new order', () => {
    const { fixture, view } = setup(twoNamedFc);
    const [alpha, beta] = view.features() as Feature[];
    expect(view.features()).toHaveLength(2);

    view.onReorderFeatures({ previousIndex: 0, currentIndex: 1 });

    // Same instances, reordered in the working model.
    expect(view.features()[0]).toBe(beta);
    expect(view.features()[1]).toBe(alpha);

    const emitted: GeometryFeatureCollection[] = [];
    fixture.componentInstance.geometryChange.subscribe((g) => emitted.push(g));
    view.commit();

    expect(emitted).toHaveLength(1);
    const names = emitted[0].features.map(
      (f) => (f.properties as Record<string, unknown> | null)?.['name:en'],
    );
    expect(names).toEqual(['beta', 'alpha']);
  });

  it('reorders the editor layer source so a later map edit keeps the new order', () => {
    const { fixture, view } = setup(twoNamedFc);
    const [alpha, beta] = view.features() as Feature[];
    const layer = fixture.debugElement.query(By.directive(OlEditorLayer))
      .componentInstance as OlEditorLayer;

    view.onReorderFeatures({ previousIndex: 0, currentIndex: 1 });

    // A subsequent add emits the SOURCE order; had the source not been reordered too, the
    // working model would revert to the import order.
    const added = new Feature(new Point([0, 0]));
    added.setId('added');
    layer.addFeatures([added]);

    const features = view.features() as Feature[];
    expect(features).toHaveLength(3);
    expect(features[0]).toBe(beta);
    expect(features[1]).toBe(alpha);
  });

  it('keeps the layer seed independent of working-model edits (no reseed loop)', () => {
    const { fixture, view } = setup(pointFc);
    const seedBefore = view.seed();
    expect(seedBefore).toHaveLength(1);

    // Simulate the layer reporting an edit (e.g. a drawn feature). The working model
    // must update, but the seed (the layer's import input) must NOT change — otherwise
    // the layer would re-seed its source mid-edit and drop the in-progress interaction.
    const layer = fixture.debugElement.query(By.directive(OlEditorLayer))
      .componentInstance as OlEditorLayer;
    layer.featuresChanged.emit([new Feature(new Point([0, 0])), new Feature(new Point([1, 1]))]);

    expect(view.features()).toHaveLength(2);
    expect(view.seed()).toBe(seedBefore);
  });
});

// The editor layer brings a feature off the globe (drawn on a world copy, dropped with a longitude
// beyond 180, dragged or reshaped past the antimeridian) back by whole turns before it emits, and
// leaves out a dropped feature whose latitude is beyond 90. These tests drive the mounted layer.
describe('GeometryEditor (a geometry off the globe)', () => {
  function mountedLayer(fixture: ReturnType<typeof setup>['fixture']) {
    const layer = fixture.debugElement.query(By.directive(OlEditorLayer))
      .componentInstance as OlEditorLayer;
    const map = (layer as unknown as { shell: { map(): OlMap | undefined } }).shell.map();
    if (!map) throw new Error('the editor rendered no map');
    return { layer, map };
  }

  function interaction<T>(map: OlMap, type: abstract new (...args: never[]) => T): T {
    const found = map
      .getInteractions()
      .getArray()
      .find((i) => i instanceof type);
    if (!found) throw new Error(`no ${type.name} interaction on the map`);
    return found as T;
  }

  function committedCoordinates(fixture: ReturnType<typeof setup>['fixture'], view: EditorHandle) {
    const emitted: GeometryFeatureCollection[] = [];
    fixture.componentInstance.geometryChange.subscribe((g) => emitted.push(g));
    view.commit();
    expect(emitted).toHaveLength(1);
    return emitted[0].features.map(
      (f) => (f.geometry as unknown as { coordinates: unknown }).coordinates,
    );
  }

  it('commits a point added at 190 E as 170 W', () => {
    const { fixture, view } = setup(pointFc);
    const { layer } = mountedLayer(fixture);

    layer.addFeatures([new Feature(new Point(fromLonLat([190, 55])))]);

    const coordinates = committedCoordinates(fixture, view) as number[][];
    expect(coordinates).toHaveLength(2);
    expect(coordinates[1][0]).toBeCloseTo(-170, 6);
    expect(coordinates[1][1]).toBeCloseTo(55, 6);
  });

  it('takes a dropped point at 190 E as 170 W and leaves out a dropped point at 95 N', () => {
    const { fixture, view } = setup(pointFc);
    const { map } = mountedLayer(fixture);
    const dropped = new GeoJSON().readFeatures(
      {
        type: 'FeatureCollection',
        features: [
          { type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [190, 55] } },
          { type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [10, 95] } },
        ],
      },
      { dataProjection: 'EPSG:4326', featureProjection: 'EPSG:3857' },
    );
    const projection = getProjection('EPSG:3857');
    if (!projection) throw new Error('EPSG:3857 is not defined');

    interaction(map, DragAndDrop).dispatchEvent(
      new DragAndDropEvent('addfeatures', new File([], 'drop.geojson'), dropped, projection),
    );

    expect(view.features()).toHaveLength(2);
    const coordinates = committedCoordinates(fixture, view) as number[][];
    expect(coordinates[1][0]).toBeCloseTo(-170, 6);
    expect(coordinates[1][1]).toBeCloseTo(55, 6);
  });

  it('brings a point dragged past the antimeridian to 370 E back to 10 E', () => {
    const { fixture, view } = setup(pointFc);
    const { map } = mountedLayer(fixture);
    const feature = view.features()[0] as Feature;
    (feature.getGeometry() as Point).setCoordinates(fromLonLat([370, 55]));

    interaction(map, Translate).dispatchEvent(
      new TranslateEvent(
        'translateend',
        new Collection([feature]),
        [1, 1],
        [0, 0],
        undefined as never,
      ),
    );

    const coordinates = committedCoordinates(fixture, view) as number[][];
    expect(coordinates[0][0]).toBeCloseTo(10, 6);
    expect(coordinates[0][1]).toBeCloseTo(55, 6);
  });

  it('brings a line reshaped past the antimeridian back by one turn, its shape kept', () => {
    const { fixture, view } = setup(pointFc);
    const { layer, map } = mountedLayer(fixture);
    const line = new Feature(new LineString([fromLonLat([175, 60]), fromLonLat([170, 60])]));
    layer.addFeatures([line]);
    (line.getGeometry() as LineString).setCoordinates([
      fromLonLat([190, 60]),
      fromLonLat([200, 60]),
    ]);

    interaction(map, Modify).dispatchEvent(
      new ModifyEvent('modifyend', new Collection([line]), undefined as never),
    );

    const coordinates = committedCoordinates(fixture, view) as number[][][];
    expect(coordinates[1][0][0]).toBeCloseTo(-170, 6);
    expect(coordinates[1][1][0]).toBeCloseTo(-160, 6);
  });
});
