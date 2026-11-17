import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { CategoriesApiService } from './categories-api.service';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return {
    service: TestBed.inject(CategoriesApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('CategoriesApiService', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('searchTemplates scopes to type=TEMPLATE + domain + ancestorId + name (no AtoN body)', () => {
    const { service, httpTesting } = setup();
    service.searchTemplates({ domainId: 'niord', ancestorId: 7, name: 'light' }).subscribe();

    const req = httpTesting.expectOne((r) => r.url === '/rest/categories/search');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('type')).toBe('TEMPLATE');
    expect(req.request.params.get('domain')).toBe('niord');
    expect(req.request.params.get('ancestorId')).toBe('7');
    expect(req.request.params.get('name')).toBe('light');
    req.flush([]);
  });

  it('getTemplateDetails re-hydrates by CSV of ids', () => {
    const { service, httpTesting } = setup();
    service.getTemplateDetails([3, 5]).subscribe();
    httpTesting.expectOne((r) => r.url === '/rest/categories/search/3,5').flush([]);
  });
});
