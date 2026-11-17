import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Map as OlMapImpl } from 'ol';
import VectorLayer from 'ol/layer/Vector';
import type Style from 'ol/style/Style';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { MapShellService } from '@niord/map/map-shell.service';
import { Message } from '../../../../core/models/message.model';
import { provideTestNiordConfig, provideTranslocoSpy } from '../../../../testing/test-config';
import { MessageLabelsLayer } from './message-labels-layer';

/** Two features, the first with a name and two coordinates, the second with one. */
const MESSAGE: Message = {
  id: 'm1',
  mainType: 'NM',
  parts: [
    {
      type: 'DETAILS',
      geometry: {
        type: 'FeatureCollection',
        features: [
          {
            type: 'Feature',
            geometry: {
              type: 'LineString',
              coordinates: [
                [12, 55],
                [12.5, 55.5],
              ],
            },
            properties: { 'name:en': 'The channel' },
          },
          {
            type: 'Feature',
            geometry: { type: 'Point', coordinates: [13, 56] },
            properties: {},
          },
        ],
      },
    },
  ],
};

@Component({
  template: `<app-message-labels-layer [message]="message()" [name]="name()" />`,
  imports: [MessageLabelsLayer],
  providers: [MapShellService],
})
class HostComponent {
  readonly message = signal<Message | undefined>(undefined);
  readonly name = signal('Labels');
}

describe('MessageLabelsLayer', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
  let shell: MapShellService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideTestNiordConfig(), provideTranslocoSpy()],
    });
    fixture = TestBed.createComponent(HostComponent);
    shell = fixture.debugElement.injector.get(MapShellService);
    shell.setMap(new OlMapImpl({}));
  });

  afterEach(() => fixture.destroy());

  function labelLayer(): VectorLayer {
    return shell.layers().find((layer) => layer instanceof VectorLayer) as VectorLayer;
  }

  function styleTexts(): string[] {
    const texts: string[] = [];
    for (const feature of labelLayer().getSource()?.getFeatures() ?? []) {
      for (const style of (feature.getStyle() as Style[]) ?? []) {
        const text = style.getText()?.getText();
        if (typeof text === 'string') texts.push(text);
      }
    }
    return texts;
  }

  it('offers itself to the layer switcher under the name it is given', () => {
    fixture.detectChanges();
    expect(labelLayer().get('name')).toBe('Labels');
    expect(labelLayer().get('showInLayerSwitcher')).toBe(true);
  });

  it('draws the name a geometry carries in the reader language', () => {
    fixture.componentInstance.message.set(MESSAGE);
    fixture.detectChanges();

    expect(styleTexts()).toContain('The channel');
  });

  it('numbers every coordinate once, running on across the features', () => {
    fixture.componentInstance.message.set(MESSAGE);
    fixture.detectChanges();

    expect(styleTexts().filter((text) => /^\d+$/.test(text))).toEqual(['1', '2', '3']);
  });

  it('draws nothing for a message with no geometry', () => {
    fixture.componentInstance.message.set({ id: 'm2', mainType: 'NW', parts: [] });
    fixture.detectChanges();

    expect(labelLayer().getSource()?.getFeatures()).toHaveLength(0);
  });
});
