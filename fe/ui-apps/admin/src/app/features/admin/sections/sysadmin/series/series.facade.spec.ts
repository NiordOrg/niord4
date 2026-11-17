import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import type { AdminMessageSeries } from '../../../models/admin-message-series.model';
import { SeriesFacade } from './series.facade';

const LIST_URL = '/rest/message-series/all?messageNumbers=true';

const entry = (seriesId: string, nextMessageNumber?: number): AdminMessageSeries => ({
  seriesId,
  mainType: 'NW',
  numberSequenceType: 'YEARLY',
  ...(nextMessageNumber !== undefined ? { nextMessageNumber } : {}),
});

/**
 * Drain the effect scheduler + microtask queue so the list httpResource
 * resolves (zoneless — no fakeAsync; flush manually instead).
 */
async function settle(): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
}

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
      provideHttpClient(),
      provideHttpClientTesting(),
      SeriesFacade,
    ],
  });
  const facade = TestBed.inject(SeriesFacade);
  const httpTesting = TestBed.inject(HttpTestingController);
  // The list httpResource fires on the first tick after construction.
  TestBed.tick();
  return { facade, httpTesting };
}

describe('SeriesFacade', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('loads the series list (with message numbers) on construction', async () => {
    const { facade, httpTesting } = setup();
    expect(facade.loading()).toBe(true);
    expect(facade.entries()).toEqual([]);

    const req = httpTesting.expectOne(LIST_URL);
    expect(req.request.method).toBe('GET');
    req.flush([entry('dma-nw', 14), entry('dma-nm')]);
    await settle();

    expect(facade.entries().map((series) => series.seriesId)).toEqual(['dma-nw', 'dma-nm']);
    expect(facade.entries()[0].nextMessageNumber).toBe(14);
    expect(facade.loading()).toBe(false);
    expect(facade.error()).toBe(false);
  });

  it('degrades a failed load to an empty list with the error flag set', async () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne(LIST_URL).flush('boom', { status: 500, statusText: 'Server Error' });
    await settle();

    expect(facade.error()).toBe(true);
    expect(facade.entries()).toEqual([]);
    expect(facade.loading()).toBe(false);
  });

  it('refresh() refetches the list (the after-mutation reload)', async () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne(LIST_URL).flush([entry('dma-nw')]);
    await settle();

    facade.refresh();
    await settle();
    httpTesting.expectOne(LIST_URL).flush([entry('dma-nw'), entry('dma-fa')]);
    await settle();

    expect(facade.entries().map((series) => series.seriesId)).toEqual(['dma-nw', 'dma-fa']);
  });

  it('refresh() recovers from the error state', async () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne(LIST_URL).flush('boom', { status: 500, statusText: 'Server Error' });
    await settle();
    expect(facade.error()).toBe(true);

    facade.refresh();
    await settle();
    httpTesting.expectOne(LIST_URL).flush([entry('dma-nw')]);
    await settle();

    expect(facade.error()).toBe(false);
    expect(facade.entries().map((series) => series.seriesId)).toEqual(['dma-nw']);
  });
});
