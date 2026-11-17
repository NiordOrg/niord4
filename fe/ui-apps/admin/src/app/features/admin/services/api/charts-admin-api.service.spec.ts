import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { Chart, ChartsAdminApiService } from './charts-admin-api.service';

const API_BASE = '/rest';

describe('ChartsAdminApiService', () => {
  let service: ChartsAdminApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, appName: 'Niord', helpUrls: {} } },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(ChartsAdminApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('getAll maps VOs — absent optionals STAY absent, null active maps to false', () => {
    let charts: Chart[] = [];
    service.getAll().subscribe((result) => (charts = result));

    httpTesting.expectOne(`${API_BASE}/charts/all`).flush([
      {
        chartNumber: '101',
        internationalNumber: 1101,
        name: 'Skagerrak',
        scale: 500000,
        horizontalDatum: 'WGS84',
        active: true,
        geometry: { type: 'Point', coordinates: [10, 56] },
      },
      { chartNumber: '102', active: null },
    ]);

    expect(charts).toHaveLength(2);
    expect(charts[0].scale).toBe(500000);
    expect(charts[0].geometry?.type).toBe('Point');
    expect(charts[1]).toEqual({
      chartNumber: '102',
      internationalNumber: undefined,
      name: undefined,
      scale: undefined,
      horizontalDatum: undefined,
      active: false,
      geometry: undefined,
    });
  });

  it('create POSTs to the collection path with the chart as the body', () => {
    const chart: Chart = { chartNumber: '101', active: true };
    service.create(chart).subscribe();

    const req = httpTesting.expectOne(`${API_BASE}/charts/chart/`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(chart);
    req.flush({});
  });

  it('update PUTs with the URL-encoded chartNumber as the path id', () => {
    const chart: Chart = { chartNumber: 'a/b 1', active: true };
    service.update(chart).subscribe();

    const req = httpTesting.expectOne(`${API_BASE}/charts/chart/a%2Fb%201`);
    expect(req.request.method).toBe('PUT');
    expect((req.request.body as Chart).chartNumber).toBe('a/b 1');
    req.flush({});
  });

  it('delete DELETEs with the URL-encoded chartNumber as the path id', () => {
    service.delete('a/b 1').subscribe();

    const req = httpTesting.expectOne(`${API_BASE}/charts/chart/a%2Fb%201`);
    expect(req.request.method).toBe('DELETE');
    req.flush({});
  });

  it('export is the bare public list URL (no ticket); upload URL is the charts upload endpoint', () => {
    expect(service.exportUrl()).toBe(`${API_BASE}/charts/all`);
    expect(service.uploadUrl()).toBe(`${API_BASE}/charts/upload-charts`);
  });
});
