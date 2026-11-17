import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Feature, Map as OlMapImpl } from 'ol';
import VectorLayer from 'ol/layer/Vector';
import { Style, Text } from 'ol/style';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { MapShellService } from '@niord/map/map-shell.service';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { Message } from '../../models/message.model';
import { OlMessageLabelsLayer } from './ol-message-labels-layer';

const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

function lineMessage(): Message {
  return {
    id: 'm1',
    parts: [
      {
        geometry: {
          type: 'FeatureCollection',
          features: [
            {
              type: 'Feature',
              geometry: {
                type: 'LineString',
                coordinates: [
                  [0, 0],
                  [1, 1],
                  [2, 2],
                ],
              },
              properties: {
                'name:0:en': 'Start',
              },
            },
          ],
        },
      },
    ],
  };
}

@Component({
  template: `<app-ol-message-labels-layer [message]="message()" />`,
  imports: [OlMessageLabelsLayer],
  providers: [MapShellService],
})
class HostComponent {
  readonly message = signal<Message | undefined>(undefined);
}

describe('OlMessageLabelsLayer', () => {
  let fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>;
  let shell: MapShellService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideTestTransloco(),
        // LanguageService normally reads localStorage and bootstraps Transloco;
        // a fixed stub is enough since these tests don't exercise lang choice.
        { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
      ],
    });
    fixture = TestBed.createComponent(HostComponent);
    shell = fixture.debugElement.injector.get(MapShellService);
    shell.setMap(new OlMapImpl({}));
  });

  afterEach(() => fixture.destroy());

  it('registers a vector layer', () => {
    fixture.detectChanges();
    expect(shell.layers().some((l) => l instanceof VectorLayer)).toBe(true);
  });

  it('renders one label feature per readable coordinate', () => {
    fixture.componentInstance.message.set(lineMessage());
    fixture.detectChanges();
    const layer = shell.layers().find((l) => l instanceof VectorLayer) as VectorLayer;
    // LineString with 3 coords → 3 labels.
    expect(layer.getSource()?.getFeatures()).toHaveLength(3);
  });

  it('clears labels when message becomes undefined', () => {
    fixture.componentInstance.message.set(lineMessage());
    fixture.detectChanges();
    fixture.componentInstance.message.set(undefined);
    fixture.detectChanges();
    const layer = shell.layers().find((l) => l instanceof VectorLayer) as VectorLayer;
    expect(layer.getSource()?.getFeatures()).toHaveLength(0);
  });
});

// Extract the text inside the name-label style of a label feature. Index 0 of
// the labelStyle array is the index-circle style (no `text.getText()` value);
// index 1 (when present) is the name label whose Text payload carries the
// translated string we want to assert on.
function nameOf(feature: Feature): string | undefined {
  const styles = feature.get('labelStyle') as Style[] | undefined;
  if (!styles || styles.length < 2) return undefined;
  const text = styles[1].getText() as Text | null;
  return text?.getText() as string | undefined;
}

function bilingualLine(): Message {
  return {
    id: 'm-bilingual',
    parts: [
      {
        geometry: {
          type: 'FeatureCollection',
          features: [
            {
              type: 'Feature',
              geometry: {
                type: 'LineString',
                coordinates: [
                  [0, 0],
                  [1, 1],
                ],
              },
              properties: {
                language: 'da',
                'name:0:da': 'kyst',
                'name:0:en': 'coast',
                'name:1:da': 'gul stage',
                'name:1:en': 'yellow buoy',
              },
            },
          ],
        },
      },
    ],
  };
}

/**
 * Separate describe block because the language stub differs per-test — we want
 * to verify the labels layer pulls from `LanguageService.selectedLanguage()`
 * and that the geometry-service language fallback ladder is exercised end-
 * to-end (component-side, not just the geometry-service unit test).
 */
describe('OlMessageLabelsLayer (language)', () => {
  it('renders names in the requested language', () => {
    TestBed.configureTestingModule({
      providers: [
        provideTestTransloco(),
        { provide: LanguageService, useValue: { selectedLanguage: () => 'da' } },
      ],
    });
    const fixture = TestBed.createComponent(HostComponent);
    const shell = fixture.debugElement.injector.get(MapShellService);
    shell.setMap(new OlMapImpl({}));
    fixture.componentInstance.message.set(bilingualLine());
    fixture.detectChanges();
    const layer = shell.layers().find((l) => l instanceof VectorLayer) as VectorLayer;
    const features = layer.getSource()?.getFeatures() ?? [];
    expect(features.map(nameOf)).toEqual(['kyst', 'gul stage']);
    fixture.destroy();
  });

  it('falls back to the feature `language` property when the user lang has no translation', () => {
    // User language is 'fr' (no translations on the feature). The feature
    // declares `language: 'da'`, so the layer should display the Danish names.
    // This is the "new language added to the system after the message was
    // authored" scenario — the geometry-service spec covers it at unit level;
    // this asserts it ends up in the rendered Text payload too.
    TestBed.configureTestingModule({
      providers: [
        provideTestTransloco(),
        { provide: LanguageService, useValue: { selectedLanguage: () => 'fr' } },
      ],
    });
    const fixture = TestBed.createComponent(HostComponent);
    const shell = fixture.debugElement.injector.get(MapShellService);
    shell.setMap(new OlMapImpl({}));
    fixture.componentInstance.message.set(bilingualLine());
    fixture.detectChanges();
    const layer = shell.layers().find((l) => l instanceof VectorLayer) as VectorLayer;
    const features = layer.getSource()?.getFeatures() ?? [];
    expect(features.map(nameOf)).toEqual(['kyst', 'gul stage']);
    fixture.destroy();
  });
});
