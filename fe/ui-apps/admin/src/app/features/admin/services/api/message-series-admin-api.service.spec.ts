import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type { AdminMessageSeries } from '../../models/admin-message-series.model';
import { MessageSeriesAdminApiService } from './message-series-admin-api.service';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return {
    service: TestBed.inject(MessageSeriesAdminApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

const series: AdminMessageSeries = {
  seriesId: 'dma-nw',
  mainType: 'NW',
  types: [],
  numberSequenceType: 'YEARLY',
  shortFormat: 'NW-${number-3-digits}-${year-2-digits}',
  editorFields: ['signature'],
  excludeFromMessageIndex: false,
};

describe('MessageSeriesAdminApiService', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('POSTs the new series to the trailing-slash collection path', () => {
    const { service, httpTesting } = setup();
    service.create(series).subscribe();
    const req = httpTesting.expectOne('/rest/message-series/series/');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(series);
    req.flush({});
  });

  it('PUTs the series on its own id — path id equals body id by construction', () => {
    const { service, httpTesting } = setup();
    service.update(series).subscribe();
    const req = httpTesting.expectOne('/rest/message-series/series/dma-nw');
    expect(req.request.method).toBe('PUT');
    expect((req.request.body as AdminMessageSeries).seriesId).toBe('dma-nw');
    req.flush({});
  });

  it('URL-encodes a free-form seriesId in every path (ids may carry reserved chars)', () => {
    const { service, httpTesting } = setup();
    const special = { ...series, seriesId: 'dk/nw #1' };
    service.update(special).subscribe();
    expect(httpTesting.expectOne('/rest/message-series/series/dk%2Fnw%20%231').request.method).toBe(
      'PUT',
    );
    service.delete('dk/nw #1').subscribe();
    expect(httpTesting.expectOne('/rest/message-series/series/dk%2Fnw%20%231').request.method).toBe(
      'DELETE',
    );
    service.getNextNumber('dk/nw #1', 2026).subscribe();
    httpTesting.expectOne('/rest/message-series/series/dk%2Fnw%20%231/number/2026').flush(1);
  });

  it('DELETEs on the seriesId path', () => {
    const { service, httpTesting } = setup();
    service.delete('dma-nw').subscribe();
    const req = httpTesting.expectOne('/rest/message-series/series/dma-nw');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('GETs the stored next number for a year (bare integer, possibly null)', () => {
    const { service, httpTesting } = setup();
    let received: number | null | undefined;
    service.getNextNumber('dma-nw', 2026).subscribe((value) => (received = value));
    const req = httpTesting.expectOne('/rest/message-series/series/dma-nw/number/2026');
    expect(req.request.method).toBe('GET');
    req.flush(42);
    expect(received).toBe(42);
  });

  it('exposes the stored-number load as an httpResource request on the same encoded URL', () => {
    const { service } = setup();
    expect(service.nextNumberRequest('dk/nw #1', 2026).url).toBe(
      '/rest/message-series/series/dk%2Fnw%20%231/number/2026',
    );
  });

  it('PUTs the next number for a year as a bare integer body', () => {
    const { service, httpTesting } = setup();
    service.setNextNumber('dma-nw', 2026, 43).subscribe();
    const req = httpTesting.expectOne('/rest/message-series/series/dma-nw/number/2026');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toBe(43);
    req.flush(43);
  });

  it('GETs the computed next number for a year', () => {
    const { service, httpTesting } = setup();
    let received: number | null | undefined;
    service.computeNextNumber('dma-nw', 2026).subscribe((value) => (received = value));
    const req = httpTesting.expectOne('/rest/message-series/series/dma-nw/compute-number/2026');
    expect(req.request.method).toBe('GET');
    req.flush(7);
    expect(received).toBe(7);
  });
});
