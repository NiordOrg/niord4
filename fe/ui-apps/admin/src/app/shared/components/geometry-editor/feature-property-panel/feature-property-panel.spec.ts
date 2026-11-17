import { TestBed } from '@angular/core/testing';
import { Injectable } from '@angular/core';
import { provideTransloco, type Translation, type TranslocoLoader } from '@jsverse/transloco';
import { Feature as OlFeature } from 'ol';
import { Point } from 'ol/geom';
import { Observable, of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { BUFFER_RADIUS, BUFFER_RADIUS_TYPE, PARENT_FEATURE_IDS } from '../util/buffer-feature';
import { FeaturePropertyPanel } from './feature-property-panel';

@Injectable({ providedIn: 'root' })
class TranslationLoader implements TranslocoLoader {
  getTranslation(): Observable<Translation> {
    return of({} as Translation);
  }
}

function bufferFeature(radius = 5): OlFeature {
  const f = new OlFeature(new Point([0, 0]));
  f.set(PARENT_FEATURE_IDS, 'p1'); // makes isBufferFeature() true
  f.set(BUFFER_RADIUS, radius);
  f.set(BUFFER_RADIUS_TYPE, 'nm');
  return f;
}

function mount(feature: OlFeature) {
  TestBed.configureTestingModule({
    providers: [
      provideTransloco({
        config: { availableLangs: ['en'], defaultLang: 'en', flatten: { aot: false } },
        loader: TranslationLoader,
      }),
    ],
  });
  const fixture = TestBed.createComponent(FeaturePropertyPanel);
  fixture.componentRef.setInput('feature', feature);
  const changed = vi.fn();
  fixture.componentInstance.changed.subscribe(changed);
  fixture.detectChanges();
  const radiusInput = fixture.nativeElement.querySelector(
    'input[type="number"]',
  ) as HTMLInputElement | null;
  return { fixture, radiusInput, changed };
}

describe('FeaturePropertyPanel', () => {
  it('shows the radius control for any buffer feature, seeded with the current radius', () => {
    const { radiusInput } = mount(bufferFeature(3));
    expect(radiusInput).toBeTruthy();
    expect(radiusInput?.value).toBe('3');
  });

  it('does NOT show the radius control for a non-buffer feature', () => {
    const { radiusInput } = mount(new OlFeature(new Point([0, 0])));
    expect(radiusInput).toBeNull();
  });

  it('writes a positive radius onto the feature and emits changed', () => {
    const feature = bufferFeature(5);
    const { radiusInput, changed } = mount(feature);
    radiusInput!.value = '12';
    radiusInput!.dispatchEvent(new Event('change'));
    expect(feature.get(BUFFER_RADIUS)).toBe(12);
    expect(changed).toHaveBeenCalled();
  });

  it('clamps a negative radius to 0', () => {
    const feature = bufferFeature(5);
    const { radiusInput } = mount(feature);
    radiusInput!.value = '-4';
    radiusInput!.dispatchEvent(new Event('change'));
    expect(feature.get(BUFFER_RADIUS)).toBe(0);
  });

  it('keeps the last radius on a blank value (never deletes the buffer) and still re-emits', () => {
    const feature = bufferFeature(7);
    const { radiusInput, changed } = mount(feature);
    changed.mockClear();
    radiusInput!.value = '';
    radiusInput!.dispatchEvent(new Event('change'));
    expect(feature.get(BUFFER_RADIUS)).toBe(7); // unchanged
    expect(changed).toHaveBeenCalled(); // re-emit so the host restores the field
  });
});
