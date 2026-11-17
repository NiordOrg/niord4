import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { MessagesAreaSortApiService } from './messages-area-sort-api.service';

const API_BASE = '/rest';

describe('MessagesAreaSortApiService', () => {
  let service: MessagesAreaSortApiService;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, appName: 'Niord', helpUrls: {} } },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    service = TestBed.inject(MessagesAreaSortApiService);
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpTesting.verify());

  it('changeAreaSortOrder PUTs {id, afterId, beforeId}', () => {
    service.changeAreaSortOrder('m2', 'm1', 'm3').subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/messages/change-area-sort-order`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ id: 'm2', afterId: 'm1', beforeId: 'm3' });
    req.flush({});
  });

  it('changeAreaSortOrder carries null neighbours at the list edges', () => {
    service.changeAreaSortOrder('m1', null, 'm2').subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/messages/change-area-sort-order`);
    expect(req.request.body).toEqual({ id: 'm1', afterId: null, beforeId: 'm2' });
    req.flush({});
  });

  it('recomputeAreaSortOrder PUTs the re-index endpoint', () => {
    service.recomputeAreaSortOrder().subscribe();
    const req = httpTesting.expectOne(`${API_BASE}/messages/recompute-area-sort-order`);
    expect(req.request.method).toBe('PUT');
    req.flush({});
  });
});
