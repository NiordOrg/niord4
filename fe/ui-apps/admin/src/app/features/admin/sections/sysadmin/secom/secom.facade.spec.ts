import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { SecomFacade } from './secom.facade';

const SERIES_URL = '/rest/message-series/all?messageNumbers=true';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      // MessageSeriesApiService's DI chain reaches LanguageService → Transloco.
      provideTestTransloco(),
      SecomFacade,
    ],
  });
  const facade = TestBed.inject(SecomFacade);
  // toObservable() emits through an effect — flush it so the initial fetches fire.
  TestBed.tick();
  return { facade, httpTesting: TestBed.inject(HttpTestingController) };
}

/** Resolve every load the facade fires on mount, so afterEach.verify() stays clean. */
function flushAll(
  httpTesting: HttpTestingController,
  overrides: Partial<Record<string, unknown>> = {},
): void {
  const bodies: Record<string, unknown> = {
    [SERIES_URL]: [],
    '/rest/secom/series-config': [],
    '/rest/secom/status': { enabled: true },
    '/rest/secom/subscriptions': [],
    '/rest/secom/nodes': [],
    '/rest/secom/datasets': [],
    '/rest/secom/outbox': [],
    ...overrides,
  };
  for (const [url, body] of Object.entries(bodies)) {
    httpTesting.expectOne(url).flush(body as never);
  }
}

describe('SecomFacade', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('fires all six section loads on mount, none carrying ?lang=', () => {
    const { facade, httpTesting } = setup();
    expect(facade.statusLoading()).toBe(true);

    for (const url of [
      '/rest/secom/status',
      '/rest/secom/series-config',
      '/rest/secom/subscriptions',
      '/rest/secom/nodes',
      '/rest/secom/datasets',
      '/rest/secom/outbox',
    ]) {
      const req = httpTesting.expectOne(url);
      expect(req.request.params.has('lang')).toBe(false);
      req.flush(url === '/rest/secom/status' ? { enabled: true } : []);
    }
    httpTesting.expectOne(SERIES_URL).flush([]);
    expect(facade.statusLoading()).toBe(false);
  });

  it('exposes the status once loaded', () => {
    const { facade, httpTesting } = setup();
    flushAll(httpTesting, {
      '/rest/secom/status': {
        enabled: true,
        serviceCertificate: { subject: 'CN=node', daysToExpiry: 11 },
        scopedSeries: ['dma-nw'],
        datasets: 4,
        subscriptions: 2,
        outboxByStatus: { PENDING: 3 },
      },
    });
    expect(facade.status()?.datasets).toBe(4);
    expect(facade.status()?.scopedSeries).toEqual(['dma-nw']);
    expect(facade.statusError()).toBe(false);
    expect(facade.unavailable()).toBe(false);
  });

  it('a 404 on the status probe means the subsystem is not deployed, not an error', () => {
    const { facade, httpTesting } = setup();
    // The whole /secom controller is absent on a node without niord.secom.enabled,
    // so every one of its endpoints 404s.
    httpTesting.expectOne(SERIES_URL).flush([]);
    for (const url of [
      '/rest/secom/status',
      '/rest/secom/series-config',
      '/rest/secom/subscriptions',
      '/rest/secom/nodes',
      '/rest/secom/datasets',
      '/rest/secom/outbox',
    ]) {
      httpTesting.expectOne(url).flush('Not Found', { status: 404, statusText: 'Not Found' });
    }

    expect(facade.unavailable()).toBe(true);
    // The disabled note replaces the page — it must not ALSO read as a failure.
    expect(facade.statusError()).toBe(false);
  });

  it('a 500 on the status probe is a real error, NOT "SECOM is disabled"', () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne(SERIES_URL).flush([]);
    httpTesting.expectOne('/rest/secom/series-config').flush([]);
    httpTesting
      .expectOne('/rest/secom/status')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    httpTesting.expectOne('/rest/secom/subscriptions').flush([]);
    httpTesting.expectOne('/rest/secom/nodes').flush([]);
    httpTesting.expectOne('/rest/secom/datasets').flush([]);
    httpTesting.expectOne('/rest/secom/outbox').flush([]);

    expect(facade.unavailable()).toBe(false);
    expect(facade.statusError()).toBe(true);
    expect(facade.statusLoading()).toBe(false);
  });

  it('joins NW series with the stored exposure rows and drops non-NW series', () => {
    const { facade, httpTesting } = setup();
    flushAll(httpTesting, {
      [SERIES_URL]: [
        { seriesId: 'dma-nw', mainType: 'NW' },
        { seriesId: 'dma-nw-local', mainType: 'NW' },
        { seriesId: 'dma-nm', mainType: 'NM' },
      ],
      '/rest/secom/series-config': [{ seriesId: 'dma-nw', exposeOverSecom: true }],
    });

    expect(facade.seriesExposure()).toEqual([
      { seriesId: 'dma-nw', exposeOverSecom: true, languages: [] },
      // No stored row → not exposed, no language restriction.
      { seriesId: 'dma-nw-local', exposeOverSecom: false, languages: [] },
    ]);
    expect(facade.seriesError()).toBe(false);
  });

  it('a stored row that is switched off reads as not exposed', () => {
    const { facade, httpTesting } = setup();
    flushAll(httpTesting, {
      [SERIES_URL]: [{ seriesId: 'dma-nw', mainType: 'NW' }],
      '/rest/secom/series-config': [{ seriesId: 'dma-nw', exposeOverSecom: false }],
    });
    expect(facade.seriesExposure()).toEqual([
      { seriesId: 'dma-nw', exposeOverSecom: false, languages: [] },
    ]);
  });

  it('a stored language restriction rides along on its series row', () => {
    const { facade, httpTesting } = setup();
    flushAll(httpTesting, {
      [SERIES_URL]: [{ seriesId: 'dma-nw', mainType: 'NW' }],
      '/rest/secom/series-config': [
        { seriesId: 'dma-nw', exposeOverSecom: true, languages: ['en'] },
      ],
    });
    expect(facade.seriesExposure()).toEqual([
      { seriesId: 'dma-nw', exposeOverSecom: true, languages: ['en'] },
    ]);
  });

  it("surfaces a failed section load as that section's error only", () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne(SERIES_URL).flush([]);
    httpTesting.expectOne('/rest/secom/series-config').flush([]);
    httpTesting.expectOne('/rest/secom/status').flush({ enabled: true });
    httpTesting.expectOne('/rest/secom/subscriptions').flush([]);
    httpTesting.expectOne('/rest/secom/nodes').flush([]);
    httpTesting
      .expectOne('/rest/secom/datasets')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    httpTesting.expectOne('/rest/secom/outbox').flush([]);

    expect(facade.datasetsError()).toBe(true);
    expect(facade.datasets()).toEqual([]);
    expect(facade.outboxError()).toBe(false);
    expect(facade.statusError()).toBe(false);
  });

  it('refreshDatasets() refetches only the datasets', () => {
    const { facade, httpTesting } = setup();
    flushAll(httpTesting);

    facade.refreshDatasets();
    TestBed.tick();
    httpTesting.expectOne('/rest/secom/datasets').flush([{ uuid: 'u-1', mrn: 'urn:mrn:s124:1' }]);
    expect(facade.datasets().length).toBe(1);
  });

  it('changing the outbox status filter refetches server-side with the status param', () => {
    const { facade, httpTesting } = setup();
    flushAll(httpTesting);

    facade.setOutboxStatus('FAILED');
    TestBed.tick();
    const req = httpTesting.expectOne((r) => r.url === '/rest/secom/outbox');
    expect(req.request.params.get('status')).toBe('FAILED');
    req.flush([{ id: 1, status: 'FAILED', nodeMrn: 'urn:mrn:peer', operation: 'UPLOAD' }]);
    expect(facade.outbox().length).toBe(1);
    expect(facade.outboxStatus()).toBe('FAILED');
  });

  it('refreshOutbox() keeps the active status filter', () => {
    const { facade, httpTesting } = setup();
    flushAll(httpTesting);

    facade.setOutboxStatus('PENDING');
    TestBed.tick();
    httpTesting.expectOne((r) => r.url === '/rest/secom/outbox').flush([]);

    facade.refreshOutbox();
    TestBed.tick();
    const req = httpTesting.expectOne((r) => r.url === '/rest/secom/outbox');
    expect(req.request.params.get('status')).toBe('PENDING');
    req.flush([]);
  });
});
