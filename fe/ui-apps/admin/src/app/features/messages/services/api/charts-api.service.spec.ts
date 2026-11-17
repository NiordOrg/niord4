import { TestBed } from '@angular/core/testing';
import { HttpParams, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LangHttpHelper } from '../../../../shared/api/lang-http.helper';
import type { MessageGeometryFeatureCollection } from '../../models/message.model';
import {
  ChartsApiService,
  type ComputedMessageChart,
  toComputedMessageChart,
} from './charts-api.service';

const API_BASE = '/rest';

function setup() {
  // LangHttpHelper is faked: langParams returns bare HttpParams (the service then layers
  // name/geometry/limit on top), and getByIdsCsv is a spy so getByChartNumbers' delegation
  // contract can be asserted without re-testing the helper.
  const getByIdsCsv = vi.fn(() => of([]));
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, appName: 'Niord', helpUrls: {} } },
      { provide: LangHttpHelper, useValue: { langParams: () => new HttpParams(), getByIdsCsv } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return {
    api: TestBed.inject(ChartsApiService),
    httpTesting: TestBed.inject(HttpTestingController),
    getByIdsCsv,
  };
}

describe('ChartsApiService', () => {
  let s: ReturnType<typeof setup>;
  beforeEach(() => {
    s = setup();
  });
  afterEach(() => {
    s.httpTesting.verify();
  });

  describe('search', () => {
    it('short-circuits on empty query (no HTTP)', () => {
      s.api.search('').subscribe();
      s.httpTesting.expectNone(() => true);
    });

    it('GETs /charts/search with name, default geometry=false, default limit=10', () => {
      s.api.search('rock').subscribe();
      const req = s.httpTesting.expectOne((r) => r.url === `${API_BASE}/charts/search`);
      expect(req.request.params.get('name')).toBe('rock');
      expect(req.request.params.get('geometry')).toBe('false');
      expect(req.request.params.get('limit')).toBe('10');
      req.flush([]);
    });

    it('honours includeGeometry + limit', () => {
      s.api.search('rock', { includeGeometry: true, limit: 5 }).subscribe();
      const req = s.httpTesting.expectOne((r) => r.url === `${API_BASE}/charts/search`);
      expect(req.request.params.get('geometry')).toBe('true');
      expect(req.request.params.get('limit')).toBe('5');
      req.flush([]);
    });
  });

  describe('getByChartNumbers', () => {
    it('delegates to getByIdsCsv with geometry=false by default', () => {
      s.api.getByChartNumbers(['101', '102']).subscribe();
      expect(s.getByIdsCsv).toHaveBeenCalledWith('/charts/search', ['101', '102'], {
        language: undefined,
        extraParams: { geometry: 'false' },
      });
    });

    it('passes geometry=true + language when requested', () => {
      s.api.getByChartNumbers(['101'], { includeGeometry: true, language: 'da' }).subscribe();
      expect(s.getByIdsCsv).toHaveBeenCalledWith('/charts/search', ['101'], {
        language: 'da',
        extraParams: { geometry: 'true' },
      });
    });
  });

  describe('intersectingCharts', () => {
    const fc: MessageGeometryFeatureCollection = { type: 'FeatureCollection', features: [] };

    it('POSTs the feature collection to /charts/intersecting-charts with no query params', () => {
      s.api.intersectingCharts(fc).subscribe();
      const req = s.httpTesting.expectOne(
        (r) => r.url === `${API_BASE}/charts/intersecting-charts`,
      );
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toBe(fc);
      expect(req.request.params.keys()).toEqual([]);
      req.flush([]);
    });

    it('prunes each returned chart to { chartNumber, internationalNumber, active, scale }', () => {
      let result: ComputedMessageChart[] | undefined;
      s.api.intersectingCharts(fc).subscribe((charts) => (result = charts));
      const req = s.httpTesting.expectOne(
        (r) => r.url === `${API_BASE}/charts/intersecting-charts`,
      );
      req.flush([
        {
          chartNumber: '101',
          internationalNumber: 4567,
          active: true,
          scale: 50000,
          name: 'Kattegat',
          horizontalDatum: 'WGS-84',
        },
      ]);
      expect(result).toEqual([
        { chartNumber: '101', internationalNumber: 4567, active: true, scale: 50000 },
      ]);
    });
  });
});

describe('toComputedMessageChart', () => {
  it('keeps only chartNumber / internationalNumber / active / scale (the stored subset)', () => {
    expect(
      toComputedMessageChart({
        chartNumber: '9',
        internationalNumber: 3,
        active: false,
        scale: 100000,
        name: 'dropped',
      } as ComputedMessageChart),
    ).toEqual({ chartNumber: '9', internationalNumber: 3, active: false, scale: 100000 });
  });
});
