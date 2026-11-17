import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { describe, beforeEach, afterEach, it, expect } from 'vitest';

import { APP_CONFIG } from '../../config/app-config.token';
import { DomainsApiService } from './domains-api.service';
import { LanguageService } from '../../main-shell/services/language.service';

const API_BASE = '/rest';

describe('DomainsApiService', () => {
  let service: DomainsApiService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, appName: 'Niord', helpUrls: {} } },
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
      ],
    });
    service = TestBed.inject(DomainsApiService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it(`calls ${API_BASE}/domains/all with language parameter`, () => {
    service.lookup().subscribe();
    const req = httpMock.expectOne((r) => r.url === `${API_BASE}/domains/all`);
    expect(req.request.params.get('lang')).toBe('en');
    req.flush([]);
  });

  it('uses explicit language override when provided', () => {
    service.lookup({ language: 'da' }).subscribe();
    const req = httpMock.expectOne((r) => r.url === `${API_BASE}/domains/all`);
    expect(req.request.params.get('lang')).toBe('da');
    req.flush([]);
  });

  it('skips the per-domain Keycloak lookup (the session catalog never shows Keycloak state)', () => {
    service.lookup().subscribe();
    const req = httpMock.expectOne((r) => r.url === `${API_BASE}/domains/all`);
    expect(req.request.params.get('keycloakState')).toBe('false');
    req.flush([]);
  });
});
