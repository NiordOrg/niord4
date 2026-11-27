import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { MessagesApiService } from '../../../messages/services/api/messages-api.service';
import type { GeometryFeature } from '../../../../shared/models/geometry.model';
import type { Message } from '../../../messages/models/message.model';
import { InsertLocationsDialog } from './insert-locations-dialog';

// jsdom lacks ResizeObserver; ol.Map references it while the embedded picker map mounts.
const ResizeObserverStub = class {
  observe(): void {
    /* no-op */
  }
  unobserve(): void {
    /* no-op */
  }
  disconnect(): void {
    /* no-op */
  }
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

const point = (props: Record<string, unknown> = {}): GeometryFeature => ({
  type: 'Feature',
  geometry: { type: 'Point', coordinates: [12, 56] },
  properties: props,
});

function messageWith(...features: GeometryFeature[]): Message {
  return {
    id: 'm',
    parts: [{ geometry: { type: 'FeatureCollection', features } }],
  };
}

@Component({
  imports: [InsertLocationsDialog],
  template: `
    <app-insert-locations-dialog
      [(visible)]="visible"
      [message]="message()"
      [partIndex]="partIndex()"
      [lang]="lang()"
      (insertContent)="inserted.set($event)"
    />
  `,
})
class Host {
  readonly visible = signal(false);
  readonly message = signal<Message | undefined>(undefined);
  readonly partIndex = signal(0);
  readonly lang = signal('en');
  readonly inserted = signal<string | undefined>(undefined);
}

const format = vi.fn();

async function setup(message: Message, translations: Record<string, string> = {}) {
  format.mockReturnValue(of('<rendered/>'));
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      { provide: MessagesApiService, useValue: { formatMessageGeometry: format } },
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord' } },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();
  TestBed.inject(TranslocoService).setTranslation(translations, 'en');

  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.message.set(message);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  return fixture;
}

const q = (sel: string) => document.querySelector(sel);
const insertButton = () => q('.dialog-foot .btn-primary') as HTMLButtonElement;
const featureBoxes = () =>
  Array.from(document.querySelectorAll<HTMLInputElement>('.dialog-body .checkbox'));
const templateRadios = () =>
  Array.from(
    document.querySelectorAll<HTMLInputElement>('input[name="insert-locations-template"]'),
  );
const formatSelect = () => q('.dialog-body select') as HTMLSelectElement;
const lastCall = () => format.mock.calls[format.mock.calls.length - 1];

beforeEach(() => {
  format.mockReset();
  localStorage.removeItem('formatLocationSettings');
});
afterEach(() => {
  document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  localStorage.removeItem('formatLocationSettings');
});

describe('InsertLocationsDialog', () => {
  it('excludes buffered features (parentFeatureIds present) from the checkbox list', async () => {
    const fixture = await setup(messageWith(point(), point({ parentFeatureIds: 'root.1' })));
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    expect(featureBoxes()).toHaveLength(1);
  });

  it('lists a feature by its name, else by its geometry type in words', async () => {
    const line: GeometryFeature = {
      type: 'Feature',
      geometry: {
        type: 'LineString',
        coordinates: [
          [12, 56],
          [13, 56],
        ],
      },
      properties: {},
    };
    const fixture = await setup(messageWith(point({ 'name:en': 'Alpha' }), line), {
      'geometry.type.LineString': 'Line',
    });
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    const labels = featureBoxes().map((box) => box.parentElement?.lastElementChild?.textContent);
    expect(labels).toEqual(['Alpha', 'Line']);
  });

  it('never calls the endpoint (and keeps Insert disabled) when nothing is selected', async () => {
    // Two features → nothing auto-selects.
    const fixture = await setup(messageWith(point({ 'name:en': 'Alpha' }), point()));
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    expect(format).not.toHaveBeenCalled();
    expect(insertButton().disabled).toBe(true);
  });

  it('posts the selected FeatureCollection with the lang/template/format params', async () => {
    const alpha = point({ 'name:en': 'Alpha' });
    const fixture = await setup(messageWith(alpha, point()));
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    featureBoxes()[0].click();
    await fixture.whenStable();

    const [geometry, options] = lastCall();
    expect(geometry.type).toBe('FeatureCollection');
    expect(geometry.features).toHaveLength(1);
    expect(geometry.features[0]).toMatchObject({ properties: { 'name:en': 'Alpha' } });
    expect(options).toEqual({ lang: 'en', template: 'numbered-list', format: 'dec' });
  });

  it('re-fires the render when the template and coordinate format change', async () => {
    // Sole feature → auto-selected → an initial render fires.
    const fixture = await setup(messageWith(point({ 'name:en': 'Alpha' })));
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    expect(lastCall()[1].template).toBe('numbered-list');

    templateRadios()[2].click(); // 'table'
    await fixture.whenStable();
    expect(lastCall()[1].template).toBe('table');

    const select = formatSelect();
    select.value = 'sec';
    select.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(lastCall()[1].format).toBe('sec');
  });

  it('auto-selects a sole feature and emits the rendered HTML on Insert', async () => {
    const fixture = await setup(messageWith(point({ 'name:en': 'Alpha' })));
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    expect(featureBoxes()[0].checked).toBe(true);

    insertButton().click();
    await fixture.whenStable();

    expect(fixture.componentInstance.inserted()).toBe('<rendered/>');
  });

  it('mounts the interactive picker map when open with features', async () => {
    const fixture = await setup(messageWith(point({ 'name:en': 'Alpha' }), point()));
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    expect(q('app-ol-map')).not.toBeNull();
    expect(q('app-insert-locations-map-layer')).not.toBeNull();
  });

  it('restores the persisted template/format (with the list rewrite) on open', async () => {
    localStorage.setItem(
      'formatLocationSettings',
      JSON.stringify({ template: 'list', format: 'navtex' }),
    );
    const fixture = await setup(messageWith(point({ 'name:en': 'Alpha' })));
    fixture.componentInstance.visible.set(true);
    await fixture.whenStable();

    // 'list' migrates to 'numbered-list'; the format persists.
    expect(lastCall()[1]).toMatchObject({ template: 'numbered-list', format: 'navtex' });
  });
});
