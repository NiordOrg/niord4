import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../core/config/app-config.token';
import type { GeometryFeatureCollection } from '../models/geometry.model';
import { GeometryApiService } from './geometry-api.service';

const API_BASE = '/rest';

const emptyFc: GeometryFeatureCollection = { type: 'FeatureCollection', features: [] };

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return {
    api: TestBed.inject(GeometryApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('GeometryApiService', () => {
  let s: ReturnType<typeof setup>;
  beforeEach(() => {
    s = setup();
  });
  afterEach(() => {
    s.httpTesting.verify();
  });

  it('POSTs parse-geometry with { geometryText } and returns the FeatureCollection', () => {
    let result: GeometryFeatureCollection | undefined;
    s.api.parseGeometry('56N 11 30.23E').subscribe((fc) => (result = fc));

    const req = s.httpTesting.expectOne(`${API_BASE}/messages/parse-geometry`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ geometryText: '56N 11 30.23E' });
    req.flush(emptyFc);
    expect(result).toEqual(emptyFc);
  });

  it('POSTs parse-utm with { geometryText }', () => {
    s.api.parseUtm('32V 624726.15 6207884.60').subscribe();

    const req = s.httpTesting.expectOne(`${API_BASE}/messages/parse-utm`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ geometryText: '32V 624726.15 6207884.60' });
    req.flush(emptyFc);
  });

  it('POSTs format-geometry with the FeatureCollection body, lang param, and a text response', () => {
    let text: string | undefined;
    s.api.formatGeometry(emptyFc, 'da').subscribe((t) => (text = t));

    const req = s.httpTesting.expectOne((r) => r.url === `${API_BASE}/messages/format-geometry`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(emptyFc);
    expect(req.request.params.get('lang')).toBe('da');
    expect(req.request.responseType).toBe('text');
    req.flush('56N 11 30.23E');
    expect(text).toBe('56N 11 30.23E');
  });

  it('omits the lang param when no language is given', () => {
    s.api.formatGeometry(emptyFc).subscribe();
    const req = s.httpTesting.expectOne((r) => r.url === `${API_BASE}/messages/format-geometry`);
    expect(req.request.params.has('lang')).toBe(false);
    req.flush('');
  });
});
