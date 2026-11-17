import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type { NamedFilter, NamedFilterCreatePayload } from '../../models/named-filter.model';
import { NamedFiltersApiService } from './named-filters-api.service';

const API_BASE = '/rest';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return {
    api: TestBed.inject(NamedFiltersApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('NamedFiltersApiService', () => {
  let s: ReturnType<typeof setup>;
  beforeEach(() => {
    s = setup();
  });
  afterEach(() => {
    s.httpTesting.verify();
  });

  it('add POSTs /filters/filter/ with the payload body', () => {
    const payload = {
      name: 'My filter',
      parameters: 'type=NW',
    } as unknown as NamedFilterCreatePayload;
    s.api.add(payload).subscribe();
    const req = s.httpTesting.expectOne(`${API_BASE}/filters/filter/`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(payload);
    req.flush({} as NamedFilter);
  });

  it('remove DELETEs /filters/filter/<id>', () => {
    s.api.remove(7).subscribe();
    const req = s.httpTesting.expectOne(`${API_BASE}/filters/filter/7`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });
});
