import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Map as OlMapImpl } from 'ol';
import type Polygon from 'ol/geom/Polygon';
import VectorLayer from 'ol/layer/Vector';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { MapShellService } from '@niord/map/map-shell.service';
import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type { AppConfig } from '../../../../core/config/app-config.interface';
import type { Message } from '../../models/message.model';
import { OlMessageListLayer } from './ol-message-list-layer';

const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

function pointMessage(id: string, mainType: 'NW' | 'NM', coord: [number, number]): Message {
  return {
    id,
    mainType,
    parts: [
      {
        geometry: {
          type: 'FeatureCollection',
          features: [
            {
              type: 'Feature',
              geometry: { type: 'Point', coordinates: coord },
              properties: {},
            },
          ],
        },
      },
    ],
  };
}

function areaMessage(id: string): Message {
  return {
    id,
    mainType: 'NW',
    parts: [
      {
        geometry: {
          type: 'FeatureCollection',
          features: [
            {
              type: 'Feature',
              geometry: {
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
              },
              properties: {},
            },
          ],
        },
      },
    ],
  };
}

function noGeometryMessage(id: string): Message {
  return { id, parts: [] };
}

@Component({
  template: `<app-ol-message-list-layer
    [messages]="messages()"
    [selection]="selection()"
    [showOutline]="showOutline()"
    [showNoPos]="showNoPos()"
    (featureClicked)="clicked = $event"
    (noPosMessages)="noPos = $event"
  />`,
  imports: [OlMessageListLayer],
  providers: [
    MapShellService,
    { provide: APP_CONFIG, useValue: { map: { highlightEnabled: true } } as unknown as AppConfig },
  ],
})
class HostComponent {
  readonly messages = signal<readonly Message[]>([]);
  readonly selection = signal<ReadonlySet<string>>(new Set<string>());
  readonly showOutline = signal(false);
  readonly showNoPos = signal(true);
  clicked: string | undefined;
  noPos: readonly Message[] = [];
}

describe('OlMessageListLayer', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
  let shell: MapShellService;

  beforeEach(() => {
    fixture = TestBed.createComponent(HostComponent);
    shell = fixture.debugElement.injector.get(MapShellService);
    shell.setMap(new OlMapImpl({}));
  });

  afterEach(() => fixture.destroy());

  it('registers a vector layer with the map shell', () => {
    fixture.detectChanges();
    expect(shell.layers().some((l) => l instanceof VectorLayer)).toBe(true);
  });

  it('builds an OL feature per GeoJSON feature on the messages input', () => {
    fixture.componentInstance.messages.set([
      pointMessage('m1', 'NW', [12, 55]),
      pointMessage('m2', 'NM', [13, 56]),
    ]);
    fixture.detectChanges();
    const layer = shell.layers().find((l) => l instanceof VectorLayer) as VectorLayer;
    const features = layer.getSource()?.getFeatures() ?? [];
    expect(features).toHaveLength(2);
    expect(features[0].get('messageId')).toBe('m1');
    expect(features[0].get('mainType')).toBe('NW');
    expect(features[1].get('messageId')).toBe('m2');
    expect(features[1].get('mainType')).toBe('NM');
  });

  it('emits noPosMessages for messages without geometry when showNoPos is true', () => {
    fixture.componentInstance.messages.set([
      pointMessage('m1', 'NW', [12, 55]),
      noGeometryMessage('m2'),
    ]);
    fixture.detectChanges();
    const noPos = fixture.componentInstance.noPos;
    expect(noPos).toHaveLength(1);
    expect(noPos[0].id).toBe('m2');
  });

  it('does not emit noPosMessages when showNoPos is false', () => {
    fixture.componentInstance.showNoPos.set(false);
    fixture.componentInstance.messages.set([noGeometryMessage('m1')]);
    fixture.detectChanges();
    expect(fixture.componentInstance.noPos).toEqual([]);
  });

  it('clears features when messages becomes empty', () => {
    fixture.componentInstance.messages.set([pointMessage('m1', 'NW', [12, 55])]);
    fixture.detectChanges();
    fixture.componentInstance.messages.set([]);
    fixture.detectChanges();
    const layer = shell.layers().find((l) => l instanceof VectorLayer) as VectorLayer;
    expect(layer.getSource()?.getFeatures()).toHaveLength(0);
  });

  it('emits featureClicked with the message UUID when a feature is clicked on the map', () => {
    fixture.componentInstance.messages.set([pointMessage('uuid-1', 'NW', [12, 55])]);
    fixture.detectChanges();
    const map = shell.map();
    expect(map).toBeDefined();
    const layer = shell.layers().find((l) => l instanceof VectorLayer) as VectorLayer;
    const feature = layer.getSource()?.getFeatures()[0];
    expect(feature).toBeDefined();
    // Spy on forEachFeatureAtPixel — synthetic click event with empty pixel won't
    // resolve features in jsdom (no rendering), so stub the resolver to return
    // our pre-built feature.
    vi.spyOn(map!, 'forEachFeatureAtPixel').mockImplementation(((
      _pixel: unknown,
      callback: (f: unknown, l: unknown) => unknown,
    ) => callback(feature!, layer)) as never);
    map!.dispatchEvent({ type: 'click', pixel: [0, 0], coordinate: [0, 0] } as never);
    expect(fixture.componentInstance.clicked).toBe('uuid-1');
  });

  // The S-124 portrayal leaves a surface unfilled, so nothing is painted inside it: a click there
  // resolves the area by containment.
  it('emits featureClicked for an area a click lands inside, where nothing is painted', () => {
    fixture.componentInstance.messages.set([areaMessage('area-1')]);
    fixture.detectChanges();
    const map = shell.map()!;
    const layer = shell.layers().find((l) => l instanceof VectorLayer) as VectorLayer;
    const area = layer.getSource()!.getFeatures()[0].getGeometry() as Polygon;
    vi.spyOn(map, 'forEachFeatureAtPixel').mockImplementation((() => undefined) as never);
    vi.spyOn(map, 'getCoordinateFromPixel').mockReturnValue(
      area.getInteriorPoint().getCoordinates().slice(0, 2),
    );

    map.dispatchEvent({ type: 'click', pixel: [0, 0], coordinate: [0, 0] } as never);

    expect(fixture.componentInstance.clicked).toBe('area-1');
  });
});
