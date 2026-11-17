import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { ParamType, ParamTypesAdminApiService } from './param-types-admin-api.service';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return {
    service: TestBed.inject(ParamTypesAdminApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

const listType: ParamType = { id: 5, type: 'LIST', name: 'sea_state', values: [] };

describe('ParamTypesAdminApiService', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('lists all-language with NO query params (D5)', () => {
    const { service, httpTesting } = setup();
    service.getAll().subscribe();
    const req = httpTesting.expectOne((r) => r.url === '/rest/templates/parameter-types');
    expect(req.request.params.keys().length).toBe(0);
    req.flush([]);
  });

  it('fetches detail by id', () => {
    const { service, httpTesting } = setup();
    service.getDetails(5).subscribe();
    httpTesting.expectOne('/rest/templates/parameter-type/5').flush(listType);
  });

  it('POSTs to the trailing-slash create endpoint', () => {
    const { service, httpTesting } = setup();
    service.create(listType).subscribe();
    const req = httpTesting.expectOne('/rest/templates/parameter-type/');
    expect(req.request.method).toBe('POST');
    req.flush({});
  });

  it('PUTs / DELETEs on the numeric id', () => {
    const { service, httpTesting } = setup();
    service.update(listType).subscribe();
    expect(httpTesting.expectOne('/rest/templates/parameter-type/5').request.method).toBe('PUT');
    service.delete(5).subscribe();
    expect(httpTesting.expectOne('/rest/templates/parameter-type/5').request.method).toBe('DELETE');
  });

  it('exposes the public export + upload URLs', () => {
    const { service } = setup();
    expect(service.exportUrl()).toBe('/rest/templates/parameter-types/export');
    expect(service.uploadUrl()).toBe('/rest/templates/upload-param-types');
  });
});
