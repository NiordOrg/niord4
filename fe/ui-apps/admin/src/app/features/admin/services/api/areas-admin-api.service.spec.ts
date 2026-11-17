import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { type AdminArea, AreasAdminApiService } from './areas-admin-api.service';

const API_BASE = '/rest';

describe('AreasAdminApiService', () => {
  let service: AreasAdminApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, appName: 'Niord', helpUrls: {} } },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(AreasAdminApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('getArea always requests the ancestor chain (parent=true)', () => {
    service.getArea(42).subscribe();
    const req = httpTesting.expectOne((r) => r.url === `${API_BASE}/areas/area/42`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('parent')).toBe('true');
    expect(req.request.params.has('lang')).toBe(false);
    req.flush({});
  });

  it('getArea adds the lang filter only when a language is given', () => {
    service.getArea(7, { language: 'da' }).subscribe();
    const req = httpTesting.expectOne((r) => r.url === `${API_BASE}/areas/area/7`);
    expect(req.request.params.get('lang')).toBe('da');
    req.flush({});
  });

  it('create POSTs to the collection path with the area body', () => {
    const area: AdminArea = { active: true, descs: [{ lang: 'en', name: 'A' }] };
    service.create(area).subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/areas/area/`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(area);
    req.flush({});
  });

  it('update PUTs to the id path', () => {
    const area: AdminArea = { id: 12, active: true, descs: [] };
    service.update(area).subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/areas/area/12`);
    expect(req.request.method).toBe('PUT');
    req.flush({});
  });

  it('delete DELETEs the id path', () => {
    service.delete(12).subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/areas/area/12`);
    expect(req.request.method).toBe('DELETE');
    req.flush({});
  });

  it('moveArea PUTs {areaId, parentId}; a root move sends parentId null', () => {
    service.moveArea(5, null).subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/areas/move-area`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ areaId: 5, parentId: null });
    req.flush({});
  });

  it('changeSortOrder PUTs {areaId, moveUp}', () => {
    service.changeSortOrder(5, true).subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/areas/change-sort-order`);
    expect(req.request.body).toEqual({ areaId: 5, moveUp: true });
    req.flush({});
  });

  it('recomputeTreeSortOrder PUTs the re-index endpoint', () => {
    service.recomputeTreeSortOrder().subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/areas/recompute-tree-sort-order`);
    expect(req.request.method).toBe('PUT');
    req.flush({});
  });

  it('generateFiringAreaMessages POSTs {seriesId, tagId}', () => {
    service.generateFiringAreaMessages('NW-DK', 'tag-1').subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/firing-schedules/generate-firing-area-messages`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ seriesId: 'NW-DK', tagId: 'tag-1' });
    req.flush({});
  });

  it('export is the bare public list URL; upload is the areas upload endpoint', () => {
    expect(service.exportUrl()).toBe(`${API_BASE}/areas/all`);
    expect(service.uploadUrl()).toBe(`${API_BASE}/areas/upload-areas`);
  });
});
