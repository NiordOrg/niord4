import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import type { AdminPromulgationType } from './promulgation-admin.model';
import { PromulgationTypesFacade } from './promulgation-types.facade';

const TYPES_URL = '/rest/promulgations/promulgation-types/all';
const SERVICES_URL = '/rest/promulgations/promulgation-services/all';

const type = (typeId: string): AdminPromulgationType => ({
  typeId,
  serviceId: 'navtex',
  name: typeId.toUpperCase(),
  priority: 0,
});

const NAVTEX_SERVICE = { serviceId: 'navtex', serviceName: 'NAVTEX mailing list' };

/**
 * Drain the effect scheduler + microtask queue so the httpResources resolve
 * (zoneless — no fakeAsync; flush manually instead).
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
      PromulgationTypesFacade,
    ],
  });
  const facade = TestBed.inject(PromulgationTypesFacade);
  const httpTesting = TestBed.inject(HttpTestingController);
  // Both httpResources fire on the first tick after construction.
  TestBed.tick();
  return { facade, httpTesting };
}

describe('PromulgationTypesFacade', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('loads the type catalog and the service registry on construction', async () => {
    const { facade, httpTesting } = setup();
    expect(facade.loading()).toBe(true);
    expect(facade.types()).toEqual([]);
    expect(facade.services()).toEqual([]);

    httpTesting.expectOne(TYPES_URL).flush([type('navtex-dk')]);
    await settle();
    // Still loading while the second resource is in flight.
    expect(facade.loading()).toBe(true);

    httpTesting.expectOne(SERVICES_URL).flush([NAVTEX_SERVICE]);
    await settle();

    expect(facade.types().map((entry) => entry.typeId)).toEqual(['navtex-dk']);
    expect(facade.services()).toEqual([NAVTEX_SERVICE]);
    expect(facade.loading()).toBe(false);
    expect(facade.error()).toBe(false);
  });

  it('degrades a failed types load to an empty list with the error flag set', async () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne(TYPES_URL).flush('boom', { status: 500, statusText: 'Server Error' });
    httpTesting.expectOne(SERVICES_URL).flush([NAVTEX_SERVICE]);
    await settle();

    expect(facade.error()).toBe(true);
    expect(facade.types()).toEqual([]);
    expect(facade.services()).toEqual([NAVTEX_SERVICE]);
    expect(facade.loading()).toBe(false);
  });

  it('degrades a failed services load to an empty registry with the error flag set', async () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne(TYPES_URL).flush([type('navtex-dk')]);
    httpTesting.expectOne(SERVICES_URL).flush('boom', { status: 500, statusText: 'Server Error' });
    await settle();

    expect(facade.error()).toBe(true);
    expect(facade.services()).toEqual([]);
    expect(facade.types().map((entry) => entry.typeId)).toEqual(['navtex-dk']);
  });

  it('refresh() refetches the types list only (the after-mutation reload)', async () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne(TYPES_URL).flush([type('navtex-dk')]);
    httpTesting.expectOne(SERVICES_URL).flush([NAVTEX_SERVICE]);
    await settle();

    facade.refresh();
    await settle();
    httpTesting.expectNone(SERVICES_URL);
    httpTesting.expectOne(TYPES_URL).flush([type('navtex-dk'), type('safetynet-dk')]);
    await settle();

    expect(facade.types().map((entry) => entry.typeId)).toEqual(['navtex-dk', 'safetynet-dk']);
  });

  it('retry() reloads both resources and recovers from the error state', async () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne(TYPES_URL).flush('boom', { status: 500, statusText: 'Server Error' });
    httpTesting.expectOne(SERVICES_URL).flush('boom', { status: 500, statusText: 'Server Error' });
    await settle();
    expect(facade.error()).toBe(true);

    facade.retry();
    await settle();
    httpTesting.expectOne(TYPES_URL).flush([type('navtex-dk')]);
    httpTesting.expectOne(SERVICES_URL).flush([NAVTEX_SERVICE]);
    await settle();

    expect(facade.error()).toBe(false);
    expect(facade.types().map((entry) => entry.typeId)).toEqual(['navtex-dk']);
    expect(facade.services()).toEqual([NAVTEX_SERVICE]);
  });
});
