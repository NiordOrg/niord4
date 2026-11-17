import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { type AdminCategory, CategoriesAdminApiService } from './categories-admin-api.service';

const API_BASE = '/rest';

function category(overrides: Partial<AdminCategory> = {}): AdminCategory {
  return { active: true, descs: [{ lang: 'en', name: 'C' }], ...overrides };
}

describe('CategoriesAdminApiService', () => {
  let service: CategoriesAdminApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, appName: 'Niord', helpUrls: {} } },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(CategoriesAdminApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('getCategory fetches a single record with no parent/children params', () => {
    service.getCategory(42).subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/categories/category/42`);
    expect(req.request.method).toBe('GET');
    expect(req.request.params.keys()).toHaveLength(0);
    req.flush({});
  });

  it('create POSTs to the collection path with the category body', () => {
    const cat = category();
    service.create(cat).subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/categories/category/`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(cat);
    req.flush({});
  });

  it('update PUTs to the id path', () => {
    service.update(category({ id: 12 })).subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/categories/category/12`);
    expect(req.request.method).toBe('PUT');
    req.flush({});
  });

  it('delete DELETEs the id path', () => {
    service.delete(12).subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/categories/category/12`);
    expect(req.request.method).toBe('DELETE');
    req.flush({});
  });

  it('moveCategory PUTs {categoryId, parentId}; a root move sends parentId null', () => {
    service.moveCategory(5, null).subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/categories/move-category`);
    expect(req.request.body).toEqual({ categoryId: 5, parentId: null });
    req.flush({});
  });

  it('changeSortOrder PUTs {categoryId, moveUp}', () => {
    service.changeSortOrder(5, true).subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/categories/change-sort-order`);
    expect(req.request.body).toEqual({ categoryId: 5, moveUp: true });
    req.flush({});
  });

  it('recomputeTreeSortOrder PUTs the re-index endpoint', () => {
    service.recomputeTreeSortOrder().subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/categories/recompute-tree-sort-order`);
    expect(req.request.method).toBe('PUT');
    req.flush({});
  });

  it('executeTemplate PUTs /templates/execute with the URL-encoded messageId + the category', () => {
    const cat = category({ id: 7, type: 'TEMPLATE' });
    service.executeTemplate(cat, 'NW-123-24').subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/templates/execute`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ messageId: 'NW-123-24', category: cat });
    req.flush({});
  });

  it('export is the pruned /categories/export (not /all); upload is the categories upload', () => {
    expect(service.exportUrl()).toBe(`${API_BASE}/categories/export`);
    expect(service.uploadUrl()).toBe(`${API_BASE}/categories/upload-categories`);
  });
});
