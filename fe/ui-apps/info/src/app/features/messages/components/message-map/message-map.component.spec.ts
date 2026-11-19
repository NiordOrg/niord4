import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { toLonLat } from 'ol/proj';
import { describe, expect, it, vi } from 'vitest';

import { OlTileLayer } from '@niord/map/layers/ol-tile-layer';
import { MapShellService } from '@niord/map/map-shell.service';
import { Message } from '../../../../core/models/message.model';
import type { NiordInfoConfig } from '../../../../niord-info.config';
import { MessageFeaturesLayer } from '../../../../shared/components/message-features-layer/message-features-layer';
import {
  getTranslocoTestingModule,
  provideTestNiordConfig,
  TEST_CONFIG,
} from '../../../../testing/test-config';
import { MessageMapComponent } from './message-map.component';

const MESSAGES: Message[] = [
  { id: 'm1', mainType: 'NW', shortId: 'NW-1', parts: [] },
  { id: 'm2', mainType: 'NM', shortId: 'NM-2', parts: [] },
];

/** A message with a position of its own, which the chart draws. */
const PLACED: Message = {
  id: 'm3',
  mainType: 'NW',
  shortId: 'NW-3',
  parts: [
    {
      type: 'DETAILS',
      geometry: {
        type: 'FeatureCollection',
        features: [
          {
            type: 'Feature',
            properties: {},
            geometry: { type: 'Point', coordinates: [12, 55] },
          },
        ],
      },
    },
  ],
};

/** A country's own credits, as its branding file words them. */
const BRANDING = {
  en: {
    MAP_ACCESSIBILITY: '<a href="{{url}}" target="_blank">Accessibility</a>.',
    MAP_COPYRIGHT: '&copy; <a href="https://authority.example/" target="_blank">Authority</a>.',
  },
};

function createMap(
  inputs: Record<string, unknown> = {},
  setup: { branding?: typeof BRANDING; config?: Partial<NiordInfoConfig> } = {},
): ComponentFixture<MessageMapComponent> {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [getTranslocoTestingModule(setup.branding)],
    providers: [provideTestNiordConfig(setup.config)],
  });
  const fixture = TestBed.createComponent(MessageMapComponent);
  for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
  fixture.detectChanges();
  return fixture;
}

function shellOf(fixture: ComponentFixture<MessageMapComponent>): MapShellService {
  return fixture.debugElement.injector.get(MapShellService);
}

function featuresLayer(fixture: ComponentFixture<MessageMapComponent>): MessageFeaturesLayer {
  return fixture.debugElement.query(By.directive(MessageFeaturesLayer)).componentInstance;
}

describe('MessageMapComponent', () => {
  it('mounts the shared map kit and registers the site layers on it', () => {
    const fixture = createMap({ messages: MESSAGES });

    expect(fixture.nativeElement.querySelector('app-ol-map')).toBeTruthy();
    // the base map plus the two message layers
    expect(shellOf(fixture).layers().length).toBeGreaterThanOrEqual(3);
    expect(shellOf(fixture).map()).toBeDefined();
  });

  // OpenLayers writes the credits into the page itself, so they arrive sanitised, and their links
  // open in the same tab, as the footer's do.
  it('hands the base map its credits sanitised, with links that open in the same tab', () => {
    const fixture = createMap({ messages: MESSAGES });
    const credits = fixture.debugElement
      .query(By.directive(OlTileLayer))
      .componentInstance.attributions() as readonly string[];

    expect(credits.length).toBeGreaterThan(0);
    expect(credits.join('')).toContain('https://www.openstreetmap.org/copyright');
    for (const credit of credits) expect(credit).not.toMatch(/\starget=/);
  });

  // The branding file words the statement's link in each language; the address is the country
  // configuration's, one line for every language and for the footer as well.
  it('links the accessibility statement the country configuration names', () => {
    const fixture = createMap(
      { messages: MESSAGES },
      { branding: BRANDING, config: { accessibilityStatement: 'https://statement.example/site' } },
    );
    const credits = (
      fixture.debugElement
        .query(By.directive(OlTileLayer))
        .componentInstance.attributions() as readonly string[]
    ).join('');

    expect(credits).toContain('href="https://statement.example/site"');
    expect(credits).toContain('Authority');
  });

  it('words no statement link for a deployment that names no statement', () => {
    const fixture = createMap({ messages: MESSAGES }, { branding: BRANDING });
    const credits = (
      fixture.debugElement
        .query(By.directive(OlTileLayer))
        .componentInstance.attributions() as readonly string[]
    ).join('');

    expect(credits).not.toContain('Accessibility');
    expect(credits).toContain('Authority');
    expect(credits).toContain('https://www.openstreetmap.org/copyright');
  });

  it('hands the features layer a zoom ceiling as a number', () => {
    const fixture = createMap({ messages: MESSAGES, maxZoom: 12 });

    expect(featuresLayer(fixture).maxZoom()).toBe(12);
  });

  // The country's first root area is known before anything loads, so the first tiles fetched
  // are the country's own rather than a placeholder's the fit then replaces.
  it("starts on the country's first root area", () => {
    const fixture = createMap({ messages: MESSAGES });
    const view = shellOf(fixture).map()!.getView();
    const [lon, lat] = toLonLat(view.getCenter()!);

    expect(view.getZoom()).toBe(TEST_CONFIG.rootAreas[0].zoomLevel);
    expect(lon).toBeCloseTo(TEST_CONFIG.rootAreas[0].longitude, 6);
    expect(lat).toBeCloseTo(TEST_CONFIG.rootAreas[0].latitude, 6);
  });

  it('opens the message a reader clicks on the chart', () => {
    const fixture = createMap({ messages: MESSAGES });
    const open = vi
      .spyOn(fixture.componentInstance, 'openMessageDialog')
      .mockImplementation(() => undefined);

    featuresLayer(fixture).featureClicked.emit('m1');

    expect(open).toHaveBeenCalledWith('m1');
  });

  it('lets a reader follow nothing from the map that shows one message', () => {
    expect(featuresLayer(createMap({ messages: MESSAGES })).interactive()).toBe(true);
    expect(featuresLayer(createMap({ message: MESSAGES[0] })).interactive()).toBe(false);
  });

  it('lists the messages with no position only where that list is asked for', () => {
    const fixture = createMap({ messages: MESSAGES, showNoPosMessages: true });
    expect(fixture.nativeElement.querySelector('#no-pos-messages')).toBeTruthy();

    const quiet = createMap({ messages: MESSAGES, showNoPosMessages: false });
    expect(quiet.nativeElement.querySelector('#no-pos-messages')).toBeNull();
  });

  // The list is the messages themselves, not a report from the layer that draws the rest: it is
  // there on the first render and follows the list the chart is given.
  it('lists exactly the messages that have no position, from the first render on', () => {
    const fixture = createMap({ messages: [...MESSAGES, PLACED], showNoPosMessages: true });
    expect(fixture.componentInstance.noPos().map((msg) => msg.id)).toEqual(['m1', 'm2']);

    fixture.componentRef.setInput('messages', [PLACED]);
    fixture.detectChanges();
    expect(fixture.componentInstance.noPos()).toEqual([]);
    expect(fixture.nativeElement.querySelector('#no-pos-messages')).toBeNull();
  });

  // A message with no English text arrives in Danish on an English page; its title in the panel
  // says so, and one in the page's own language says nothing.
  it('marks a title in the panel that is not in the language of the page', () => {
    const fixture = createMap({
      messages: [
        { ...MESSAGES[0], descs: [{ lang: 'da', title: 'Skydeøvelser' }] },
        { ...MESSAGES[1], descs: [{ lang: 'en', title: 'Firing exercises' }] },
      ],
      showNoPosMessages: true,
    });

    const titles = [
      ...(fixture.nativeElement as HTMLElement).querySelectorAll('#no-pos-messages strong'),
    ].map((title) => [title.textContent!.trim(), title.getAttribute('lang')]);
    expect(titles).toEqual([
      ['Skydeøvelser', 'da'],
      ['Firing exercises', null],
    ]);
  });

  // The list of an issue being browsed holds its cancelled messages: they stay findable in the
  // list views, but the chart of the list draws only what may still be in force.
  it('draws no cancelled message of a list, and leaves it out of the panel beside the chart', () => {
    const live: Message = { ...PLACED, id: 'live', status: 'PUBLISHED' };
    const cancelled: Message = { ...PLACED, id: 'cancelled', status: 'CANCELLED' };
    const cancelledNoPos: Message = { ...MESSAGES[0], id: 'gone', status: 'CANCELLED' };
    const fixture = createMap({
      messages: [live, cancelled, cancelledNoPos],
      showNoPosMessages: true,
    });

    expect(
      featuresLayer(fixture)
        .messages()
        .map((msg) => msg.id),
    ).toEqual(['live']);
    expect(fixture.componentInstance.noPos()).toEqual([]);
    expect(fixture.nativeElement.querySelector('#no-pos-messages')).toBeNull();
  });

  it('keeps an expired message of a list on the chart', () => {
    const expired: Message = { ...PLACED, id: 'expired', status: 'EXPIRED' };
    const fixture = createMap({ messages: [expired] });

    expect(
      featuresLayer(fixture)
        .messages()
        .map((msg) => msg.id),
    ).toEqual(['expired']);
  });

  it('draws a cancelled message on the map of that one message', () => {
    const fixture = createMap({ message: { ...PLACED, status: 'CANCELLED' } });

    expect(
      featuresLayer(fixture)
        .messages()
        .map((msg) => msg.id),
    ).toEqual([PLACED.id]);
  });

  it('folds the list of messages with no position away and back', () => {
    const fixture = createMap({ messages: MESSAGES, showNoPosMessages: true });
    expect(fixture.nativeElement.textContent).toContain('NW-1');

    fixture.componentInstance.toggleMinimize();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('NW-1');
  });
});
