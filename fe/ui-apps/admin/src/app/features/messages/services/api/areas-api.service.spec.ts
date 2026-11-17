import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { MessageGeometryFeatureCollection } from '../../models/message.model';
import type { AreaRef } from '../../models/messages-filter-params.model';
import { AreasApiService } from './areas-api.service';
import type { Geometry } from 'geojson';

const API_BASE = '/rest';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
    ],
  });
  return {
    api: TestBed.inject(AreasApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('AreasApiService.getByIds', () => {
  let s: ReturnType<typeof setup>;
  beforeEach(() => {
    s = setup();
  });
  afterEach(() => {
    s.httpTesting.verify();
  });

  it('omits the lang param for allLanguages (so the backend returns every desc)', () => {
    s.api.getByIds([42], { includeGeometry: true, allLanguages: true }).subscribe();
    const req = s.httpTesting.expectOne((r) => r.url === `${API_BASE}/areas/search/42`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.has('lang')).toBe(false);
    expect(req.request.params.get('geometry')).toBe('true');
    req.flush([]);
  });

  it('sends the lang param on the default (lang-scoped) path', () => {
    s.api.getByIds([42], { includeGeometry: true }).subscribe();
    const req = s.httpTesting.expectOne((r) => r.url === `${API_BASE}/areas/search/42`);
    expect(req.request.params.has('lang')).toBe(true);
    expect(req.request.params.get('geometry')).toBe('true');
    req.flush([]);
  });

  it('short-circuits an empty allLanguages id list without a request', () => {
    let emitted: unknown;
    s.api.getByIds([], { allLanguages: true }).subscribe((v) => (emitted = v));
    s.httpTesting.expectNone(() => true);
    expect(emitted).toEqual([]);
  });
});

describe('AreasApiService.intersectingAreas', () => {
  let s: ReturnType<typeof setup>;
  beforeEach(() => {
    s = setup();
  });
  afterEach(() => {
    s.httpTesting.verify();
  });

  const fc: MessageGeometryFeatureCollection = { type: 'FeatureCollection', features: [] };

  it('POSTs the collection with maxLevel=2, lang, and domain=true (the defaults)', () => {
    s.api.intersectingAreas(fc).subscribe();
    const req = s.httpTesting.expectOne((r) => r.url === `${API_BASE}/areas/intersecting-areas`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toBe(fc);
    expect(req.request.params.get('maxLevel')).toBe('2');
    expect(req.request.params.get('domain')).toBe('true');
    expect(req.request.params.has('lang')).toBe(true);
    req.flush([]);
  });

  it('honours an explicit maxLevel + language', () => {
    s.api.intersectingAreas(fc, { maxLevel: 3, language: 'da' }).subscribe();
    const req = s.httpTesting.expectOne((r) => r.url === `${API_BASE}/areas/intersecting-areas`);
    expect(req.request.params.get('maxLevel')).toBe('3');
    expect(req.request.params.get('lang')).toBe('da');
    req.flush([]);
  });
});

describe('AreasApiService.areaToFeatureCollection', () => {
  let s: ReturnType<typeof setup>;
  beforeEach(() => {
    s = setup();
  });
  afterEach(() => {
    s.httpTesting.verify();
  });

  const geometry: Geometry = { type: 'Point', coordinates: [10, 56] };

  it('builds a single feature, flattening descs into name:<lang> properties', () => {
    const area: AreaRef = {
      id: 42,
      geometry,
      descs: [
        { lang: 'en', name: 'The Sound' },
        { lang: 'da', name: 'Øresund' },
      ],
    };
    const fc = s.api.areaToFeatureCollection(area);
    expect(fc.features).toHaveLength(1);
    const feature = fc.features[0];
    expect(feature.geometry).toEqual(geometry);
    expect(feature.properties).toMatchObject({
      areaId: 42,
      'name:en': 'The Sound',
      'name:da': 'Øresund',
    });
    expect(typeof feature.id).toBe('string');
  });

  it('skips desc entries missing lang or name', () => {
    const fc = s.api.areaToFeatureCollection({
      id: 7,
      geometry,
      descs: [{ lang: 'en' }, { name: 'No lang' }, { lang: 'da', name: 'Navn' }],
    });
    expect(fc.features[0].properties).toEqual({ areaId: 7, 'name:da': 'Navn' });
  });

  it('returns an empty collection when the area has no geometry', () => {
    const fc = s.api.areaToFeatureCollection({ id: 1, descs: [{ lang: 'en', name: 'X' }] });
    expect(fc).toEqual({ type: 'FeatureCollection', features: [] });
  });
});
