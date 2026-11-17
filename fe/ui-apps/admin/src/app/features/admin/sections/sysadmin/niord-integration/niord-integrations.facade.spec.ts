import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { NiordIntegrationsFacade } from './niord-integrations.facade';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      // MessageSeriesApiService's DI chain reaches LanguageService → Transloco.
      provideTestTransloco(),
      NiordIntegrationsFacade,
    ],
  });
  const facade = TestBed.inject(NiordIntegrationsFacade);
  // toObservable() emits through an effect — flush it so the initial fetch fires.
  TestBed.tick();
  const httpTesting = TestBed.inject(HttpTestingController);
  // The series-options fetch fires from a field initializer (constructor time).
  return { facade, httpTesting };
}

/** Resolve the series fetch so afterEach.verify() stays clean. */
function flushSeries(httpTesting: HttpTestingController, ids: string[] = []): void {
  httpTesting
    .expectOne('/rest/message-series/all?messageNumbers=true')
    .flush(ids.map((seriesId) => ({ seriesId })));
}

describe('NiordIntegrationsFacade', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('fetches the unlocalized list on init (no ?lang=) and exposes signals', () => {
    const { facade, httpTesting } = setup();
    expect(facade.loading()).toBe(true);
    flushSeries(httpTesting);
    const req = httpTesting.expectOne('/rest/niord-integrations/all');
    expect(req.request.params.has('lang')).toBe(false);
    req.flush([{ id: 1, url: 'https://r', active: true, messageSeriesMappings: [] }]);
    expect(facade.loading()).toBe(false);
    expect(facade.integrations().length).toBe(1);
    expect(facade.error()).toBe(false);
  });

  it('refresh() refetches the list (post-mutation)', () => {
    const { facade, httpTesting } = setup();
    flushSeries(httpTesting);
    httpTesting.expectOne('/rest/niord-integrations/all').flush([]);
    facade.refresh();
    TestBed.tick();
    httpTesting
      .expectOne('/rest/niord-integrations/all')
      .flush([{ id: 2, url: 'https://r2', active: false, messageSeriesMappings: [] }]);
    expect(facade.integrations().length).toBe(1);
  });

  it('a failed list fetch sets the error flag (no silent failure)', () => {
    const { facade, httpTesting } = setup();
    flushSeries(httpTesting);
    httpTesting
      .expectOne('/rest/niord-integrations/all')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(facade.error()).toBe(true);
    expect(facade.loading()).toBe(false);
  });

  it('series options populate from the full series list', () => {
    const { facade, httpTesting } = setup();
    flushSeries(httpTesting, ['dma-nw', 'dma-nm']);
    httpTesting.expectOne('/rest/niord-integrations/all').flush([]);
    expect(facade.seriesOptions()).toEqual(['dma-nw', 'dma-nm']);
    expect(facade.seriesError()).toBe(false);
  });

  it('a failed series-options fetch is surfaced (not silently emptied)', () => {
    const { facade, httpTesting } = setup();
    httpTesting
      .expectOne('/rest/message-series/all?messageNumbers=true')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    httpTesting.expectOne('/rest/niord-integrations/all').flush([]);
    expect(facade.seriesError()).toBe(true);
    expect(facade.seriesOptions()).toEqual([]);
  });
});
