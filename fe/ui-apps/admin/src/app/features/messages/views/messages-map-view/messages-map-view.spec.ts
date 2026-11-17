import { Component, input, output, signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type { AppConfig } from '../../../../core/config/app-config.interface';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../core/main-shell/services/locale-format.service';
import { BaseMapLayers } from '../../../../shared/components/ol-map/base-map-layers';
import { OlMap } from '@niord/map/ol-map';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { OlMessageListLayer } from '../../components/map-layers/ol-message-list-layer';
import type { Message } from '../../models/message.model';
import { MessageDetailsDialogService } from '../../services/message-details-dialog.service';
import { MessagesFacade } from '../../services/messages-facade.service';
import { MessagesMapView } from './messages-map-view';

const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

// The real OpenLayers map needs a sized container, a pixel-ratio media query and a tile
// gate; none of that is what these tests are about. `<ng-content />` keeps the layer
// children — including the real message-list layer the assertions read — in the view.
@Component({ selector: 'app-ol-map', template: `<ng-content />` })
class StubOlMap {
  readonly view = input<unknown>(undefined);
  readonly viewChange = output<unknown>();
}
@Component({ selector: 'app-base-map-layers', template: `` })
class StubBaseMapLayers {
  readonly openSeaMap = input(false);
}

function pointMessage(id: string, shortId: string, lon: number): Message {
  return {
    id,
    shortId,
    mainType: 'NW',
    parts: [
      {
        geometry: {
          type: 'FeatureCollection',
          features: [
            {
              type: 'Feature',
              geometry: { type: 'Point', coordinates: [lon, 55] },
              properties: {},
            },
          ],
        },
      },
    ],
  };
}

const SAMPLE: readonly Message[] = [pointMessage('m1', 'NW-1', 12), pointMessage('m2', 'NW-2', 13)];

// One module-level facade: `TestBed.overrideComponent` caches the compiled definition, so a
// per-test stub would leave every later test bound to the first one's object.
const facade = {
  list: {
    messages: signal<readonly Message[]>([]),
    total: signal(0),
    loading: signal(false),
    hasMore: signal(false),
    error: signal<unknown>(null),
    loadMore: vi.fn(),
    retry: vi.fn(),
  },
  filter: {
    enabled: () => signal(false),
    slice: () => signal({ charts: [], areas: [] }),
  },
  shell: { maxPageSize: signal(100) },
};

function setup() {
  facade.list.messages.set(SAMPLE);
  facade.list.total.set(SAMPLE.length);
  facade.list.loading.set(false);
  facade.list.hasMore.set(false);
  facade.list.error.set(null);
  vi.clearAllMocks();

  const dialog = { open: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      {
        provide: APP_CONFIG,
        useValue: {
          apiBaseUrl: '/rest',
          appName: 'Niord',
          helpUrls: {},
          map: { highlightEnabled: true },
        } as unknown as AppConfig,
      },
      {
        provide: DomainFacade,
        useValue: { mapDefaults: signal({ center: undefined, zoom: undefined }) },
      },
      {
        provide: LanguageService,
        useValue: { selectedLanguage: signal('en'), activeLocale: signal('en') },
      },
      { provide: LocaleFormatService, useValue: { formatNumber: (n: number) => String(n) } },
      { provide: MessagesFacade, useValue: facade as unknown as MessagesFacade },
      {
        provide: MessageDetailsDialogService,
        useValue: dialog as unknown as MessageDetailsDialogService,
      },
    ],
  });
  TestBed.overrideComponent(MessagesMapView, {
    remove: { imports: [OlMap, BaseMapLayers] },
    add: { imports: [StubOlMap, StubBaseMapLayers] },
  });
  return { fixture: TestBed.createComponent(MessagesMapView), dialog };
}

describe('MessagesMapView', () => {
  let fixture: ReturnType<typeof setup>['fixture'];

  beforeEach(() => {
    ({ fixture } = setup());
    fixture.detectChanges();
  });

  afterEach(() => fixture.destroy());

  function listLayer(): OlMessageListLayer {
    return fixture.debugElement.query(By.directive(OlMessageListLayer))
      .componentInstance as OlMessageListLayer;
  }

  /** The result-list panel starts collapsed; the rows only exist once it is expanded. */
  function expandList(): void {
    const el = fixture.nativeElement as HTMLElement;
    if (el.querySelectorAll('app-map-result-list li').length > 0) return;
    (el.querySelector('app-map-result-list header button') as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  function rows(): HTMLLIElement[] {
    return Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('app-map-result-list li'),
    );
  }

  function hover(row: HTMLElement): void {
    row.dispatchEvent(new MouseEvent('mouseenter'));
    fixture.detectChanges();
  }

  it('passes the hovered row through to the message layer', () => {
    expandList();
    hover(rows()[0]);
    expect(listLayer().hovered()).toBe('m1');
  });

  it('clears the hover halo when the row zoom button fits the map to the message', () => {
    expandList();
    const row = rows()[0];
    hover(row);
    const layer = listLayer();
    expect(layer.hovered()).toBe('m1');

    const fitToMessage = vi.spyOn(layer, 'fitToMessage');
    // Second button in the row: the first is the row itself, the second the zoom action.
    (row.querySelectorAll('button')[1] as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(fitToMessage).toHaveBeenCalledWith('m1');
    expect(layer.hovered()).toBeUndefined();
  });

  it('hovering another row after a zoom highlights that message', () => {
    expandList();
    hover(rows()[0]);
    (rows()[0].querySelectorAll('button')[1] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(listLayer().hovered()).toBeUndefined();

    // The zoom collapses the panel; re-opening it is the user re-entering the list.
    expandList();
    hover(rows()[1]);
    expect(listLayer().hovered()).toBe('m2');
  });
});
