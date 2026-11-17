import { Component, signal, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import OlMapImpl from 'ol/Map';
import type Polygon from 'ol/geom/Polygon';
import VectorLayer from 'ol/layer/Vector';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { MapShellService } from '@niord/map/map-shell.service';
import type { OlMapView } from '@niord/map/ol-map';
import { Message } from '../../../core/models/message.model';
import { provideTestNiordConfig, provideTranslocoSpy } from '../../../testing/test-config';
import { MessageFeaturesLayer } from './message-features-layer';

interface Geometry {
  type: string;
  coordinates: unknown;
}

function messageWith(id: string, mainType: 'NW' | 'NM', ...geometries: Geometry[]): Message {
  return {
    id,
    mainType,
    parts: [
      {
        type: 'DETAILS',
        geometry: {
          type: 'FeatureCollection',
          features: geometries.map((geometry) => ({ type: 'Feature', geometry, properties: {} })),
        },
      },
    ],
  };
}

function pointMessage(id: string, mainType: 'NW' | 'NM', ...coords: [number, number][]): Message {
  return messageWith(
    id,
    mainType,
    ...coords.map((coordinates) => ({ type: 'Point', coordinates })),
  );
}

/** A rectangular firing area, a degree on each side. */
const AREA: Geometry = {
  type: 'Polygon',
  coordinates: [
    [
      [11, 55],
      [12, 55],
      [12, 56],
      [11, 56],
      [11, 55],
    ],
  ],
};

const LINE: Geometry = {
  type: 'LineString',
  coordinates: [
    [12, 55],
    [12.5, 55.5],
  ],
};

function withoutGeometry(id: string): Message {
  return { id, mainType: 'NW', parts: [] };
}

@Component({
  template: `<app-message-features-layer
    [messages]="messages()"
    [fitExtent]="fitExtent()"
    [fallbackView]="fallbackView()"
    [interactive]="interactive()"
    (featureClicked)="clicked = $event"
  />`,
  imports: [MessageFeaturesLayer],
  providers: [MapShellService],
})
class HostComponent {
  readonly messages = signal<readonly Message[]>([]);
  readonly fitExtent = signal(false);
  readonly fallbackView = signal<OlMapView | undefined>(undefined);
  readonly interactive = signal(true);
  readonly layer = viewChild.required(MessageFeaturesLayer);
  clicked: string | undefined;
}

describe('MessageFeaturesLayer', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
  let shell: MapShellService;
  let map: OlMapImpl;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideTestNiordConfig(), provideTranslocoSpy()],
    });
    fixture = TestBed.createComponent(HostComponent);
    shell = fixture.debugElement.injector.get(MapShellService);
    map = new OlMapImpl({});
    shell.setMap(map);
  });

  afterEach(() => fixture.destroy());

  /** The warnings layer and the notices layer, in the order they registered. */
  function layers(): [VectorLayer, VectorLayer] {
    const vectors = shell.layers().filter((layer) => layer instanceof VectorLayer);
    return [vectors[0], vectors[1]];
  }

  /** jsdom paints nothing, so the painted-pixel test hands the callback what a test names. */
  function paintedUnderPointer(hits: { feature: unknown; layer: unknown }[]): void {
    vi.spyOn(map, 'forEachFeatureAtPixel').mockImplementation(((
      _pixel: unknown,
      callback: (f: unknown, l: unknown) => unknown,
    ) => {
      for (const hit of hits) callback(hit.feature, hit.layer);
    }) as never);
  }

  it('draws warnings and notices on two layers of their own', () => {
    fixture.componentInstance.messages.set([
      pointMessage('m1', 'NW', [12, 55]),
      pointMessage('m2', 'NM', [13, 56]),
    ]);
    fixture.detectChanges();

    const [nw, nm] = layers();
    expect(nw.getSource()?.getFeatures()).toHaveLength(1);
    expect(nm.getSource()?.getFeatures()).toHaveLength(1);
    expect(nw.getSource()?.getFeatures()[0].get('message')).toMatchObject({ id: 'm1' });
    expect(nm.getSource()?.getFeatures()[0].get('message')).toMatchObject({ id: 'm2' });
  });

  // The portrayal marks a surface with its tag and a line with tags along it at every zoom, so a
  // pin of the layer's own would print a second symbol over the tag.
  it('draws each geometry once and adds no mark of its own to a surface or a line', () => {
    fixture.componentInstance.messages.set([
      messageWith('m1', 'NW', AREA),
      messageWith('m2', 'NM', LINE),
    ]);
    fixture.detectChanges();

    const [nw, nm] = layers();
    expect(
      nw
        .getSource()
        ?.getFeatures()
        .map((f) => f.getGeometry()?.getType()),
    ).toEqual(['Polygon']);
    expect(
      nm
        .getSource()
        ?.getFeatures()
        .map((f) => f.getGeometry()?.getType()),
    ).toEqual(['LineString']);
  });

  it('draws nothing for a message with no geometry', () => {
    fixture.componentInstance.messages.set([
      pointMessage('m1', 'NW', [12, 55]),
      withoutGeometry('m2'),
    ]);
    fixture.detectChanges();

    const [nw, nm] = layers();
    expect(nw.getSource()?.getFeatures()).toHaveLength(1);
    expect(nm.getSource()?.getFeatures()).toHaveLength(0);
  });

  it('clears what it drew when the list empties', () => {
    fixture.componentInstance.messages.set([pointMessage('m1', 'NW', [12, 55])]);
    fixture.detectChanges();
    fixture.componentInstance.messages.set([]);
    fixture.detectChanges();

    expect(layers()[0].getSource()?.getFeatures()).toHaveLength(0);
  });

  it('names the message a click landed on', () => {
    fixture.componentInstance.messages.set([pointMessage('uuid-1', 'NW', [12, 55])]);
    fixture.detectChanges();
    const layer = layers()[0];
    paintedUnderPointer([{ feature: layer.getSource()?.getFeatures()[0], layer }]);

    map.dispatchEvent({ type: 'click', pixel: [0, 0], coordinate: [0, 0] } as never);

    expect(fixture.componentInstance.clicked).toBe('uuid-1');
  });

  // The S-124 portrayal leaves a surface unfilled, so inside it nothing is painted: the click
  // resolves by containment instead.
  it('names the area a click lands inside, where nothing is painted', () => {
    fixture.componentInstance.messages.set([messageWith('area-1', 'NW', AREA)]);
    fixture.detectChanges();
    paintedUnderPointer([]);
    const area = layers()[0].getSource()!.getFeatures()[0].getGeometry() as Polygon;
    const inside = area.getInteriorPoint().getCoordinates().slice(0, 2);
    vi.spyOn(map, 'getCoordinateFromPixel').mockReturnValue(inside);

    map.dispatchEvent({ type: 'click', pixel: [0, 0], coordinate: [0, 0] } as never);

    expect(fixture.componentInstance.clicked).toBe('area-1');
  });

  it('names a message once however many features it was drawn with', () => {
    fixture.componentInstance.messages.set([pointMessage('m1', 'NW', [12, 55], [12.1, 55.1])]);
    fixture.detectChanges();
    const layer = layers()[0];
    const features = layer.getSource()?.getFeatures() ?? [];
    expect(features).toHaveLength(2);
    paintedUnderPointer(features.map((feature) => ({ feature, layer })));

    expect(fixture.componentInstance.layer().messagesAtPixel([0, 0])).toHaveLength(1);
  });

  it('points the map at the fallback view when nothing drawn has a position', () => {
    fixture.componentInstance.fitExtent.set(true);
    fixture.componentInstance.fallbackView.set({ center: [11, 56], zoom: 7 });
    fixture.componentInstance.messages.set([withoutGeometry('m1')]);
    fixture.detectChanges();

    expect(map.getView().getZoom()).toBe(7);
  });

  it('frames the map on what it drew, no closer than the zoom ceiling', () => {
    const fit = vi.spyOn(map.getView(), 'fit');
    fixture.componentInstance.fitExtent.set(true);
    fixture.componentInstance.messages.set([messageWith('m1', 'NW', LINE)]);
    fixture.detectChanges();

    expect(fit).toHaveBeenCalled();
    expect(fit.mock.calls.at(-1)?.[1]?.maxZoom).toBe(10);
  });

  it('draws, but opens nothing, on a map that is one link', () => {
    fixture.componentInstance.interactive.set(false);
    fixture.componentInstance.messages.set([
      pointMessage('m1', 'NW', [12, 55]),
      withoutGeometry('m2'),
    ]);
    fixture.detectChanges();
    const layer = layers()[0];
    paintedUnderPointer([{ feature: layer.getSource()?.getFeatures()[0], layer }]);

    map.dispatchEvent({ type: 'click', pixel: [0, 0], coordinate: [0, 0] } as never);

    expect(layer.getSource()?.getFeatures()).toHaveLength(1);
    expect(fixture.componentInstance.clicked).toBeUndefined();
  });
});
