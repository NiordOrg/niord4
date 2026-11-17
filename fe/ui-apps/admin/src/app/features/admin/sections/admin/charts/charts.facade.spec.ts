import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { ChartsFacade } from './charts.facade';

describe('ChartsFacade', () => {
  let facade: ChartsFacade;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
        provideHttpClient(),
        provideHttpClientTesting(),
        ChartsFacade,
      ],
    });
    facade = TestBed.inject(ChartsFacade);
    // toObservable() emits through an effect — flush it so the initial fetch fires.
    TestBed.tick();
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('loads the list on init — loading until the response lands; no ?lang= (charts are language-agnostic)', () => {
    expect(facade.loading()).toBe(true);

    const req = httpTesting.expectOne('/rest/charts/all');
    expect(req.request.params.keys()).toEqual([]);
    req.flush([{ chartNumber: '101', active: true }]);

    expect(facade.loading()).toBe(false);
    expect(facade.charts()).toHaveLength(1);
    expect(facade.error()).toBe(false);
  });

  it('surfaces a fetch failure via the error flag', () => {
    httpTesting
      .expectOne('/rest/charts/all')
      .flush('boom', { status: 500, statusText: 'Server Error' });

    expect(facade.error()).toBe(true);
    expect(facade.loading()).toBe(false);
  });

  it('refresh() refetches', () => {
    httpTesting.expectOne('/rest/charts/all').flush([]);
    expect(facade.charts()).toHaveLength(0);

    facade.refresh();
    TestBed.tick();
    httpTesting.expectOne('/rest/charts/all').flush([{ chartNumber: '101', active: true }]);
    expect(facade.charts()).toHaveLength(1);
  });
});
