import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Map as OlMapImpl } from 'ol';
import VectorLayer from 'ol/layer/Vector';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { MapShellService } from '@niord/map/map-shell.service';
import type { Message } from '../../models/message.model';
import { OlMessageDetailsLayer } from './ol-message-details-layer';

const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

function pointMessage(id: string, coord: [number, number]): Message {
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
              geometry: { type: 'Point', coordinates: coord },
              properties: {},
            },
          ],
        },
      },
    ],
  };
}

@Component({
  template: `<app-ol-message-details-layer [message]="message()" [fitExtent]="fitExtent()" />`,
  imports: [OlMessageDetailsLayer],
  providers: [MapShellService],
})
class HostComponent {
  readonly message = signal<Message | undefined>(undefined);
  readonly fitExtent = signal(false);
}

describe('OlMessageDetailsLayer', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
  let shell: MapShellService;

  beforeEach(() => {
    fixture = TestBed.createComponent(HostComponent);
    shell = fixture.debugElement.injector.get(MapShellService);
    shell.setMap(new OlMapImpl({}));
  });

  afterEach(() => fixture.destroy());

  it('registers a vector layer', () => {
    fixture.detectChanges();
    expect(shell.layers().some((l) => l instanceof VectorLayer)).toBe(true);
  });

  it('renders features when a message is bound', () => {
    fixture.componentInstance.message.set(pointMessage('m1', [12, 55]));
    fixture.detectChanges();
    const layer = shell.layers().find((l) => l instanceof VectorLayer) as VectorLayer;
    expect(layer.getSource()?.getFeatures()).toHaveLength(1);
  });

  it('clears features when message becomes undefined', () => {
    fixture.componentInstance.message.set(pointMessage('m1', [12, 55]));
    fixture.detectChanges();
    fixture.componentInstance.message.set(undefined);
    fixture.detectChanges();
    const layer = shell.layers().find((l) => l instanceof VectorLayer) as VectorLayer;
    expect(layer.getSource()?.getFeatures()).toHaveLength(0);
  });
});
