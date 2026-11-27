import { TestBed } from '@angular/core/testing';
import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../config/app-config.token';
import type { AppConfig } from '../config/app-config.interface';
import { DomainFacade } from '../domain/facades/domain.facade';
import type { Domain } from '../domain/models/domain.model';
import { niordDomainInterceptor } from './niord-domain.interceptor';
import { TICKETED_REQUEST } from './ticketed-request';

const niordDk: Domain = { domainId: 'niord-dk' };
const API_BASE_URL = 'https://niord.example/rest';

interface ConfigureOptions {
  readonly sessionDomain?: Domain;
}

function configure({ sessionDomain }: ConfigureOptions) {
  const sessionDomainSignal = signal<Domain | undefined>(sessionDomain);

  const appConfig = {
    apiBaseUrl: API_BASE_URL,
    appName: 'Niord',
    helpUrls: {},
    keycloak: { url: '', realm: '', clientId: '' },
  } as unknown as AppConfig;

  TestBed.configureTestingModule({
    providers: [
      { provide: DomainFacade, useValue: { sessionDomain: sessionDomainSignal } },
      { provide: APP_CONFIG, useValue: appConfig },
      provideHttpClient(withInterceptors([niordDomainInterceptor])),
      provideHttpClientTesting(),
    ],
  });

  return {
    http: TestBed.inject(HttpClient),
    httpMock: TestBed.inject(HttpTestingController),
    sessionDomain: sessionDomainSignal,
  };
}

describe('niordDomainInterceptor', () => {
  let httpMock: HttpTestingController | null = null;

  beforeEach(() => {
    httpMock = null;
  });

  afterEach(() => httpMock?.verify());

  it('skips non-backend requests', () => {
    const { http, httpMock: m } = configure({ sessionDomain: niordDk });
    httpMock = m;
    http.get('/static/something').subscribe();
    const req = m.expectOne('/static/something');
    expect(req.request.headers.has('NiordDomain')).toBe(false);
    req.flush({});
  });

  it('skips when no session domain is set', () => {
    const { http, httpMock: m } = configure({});
    httpMock = m;
    http.get(`${API_BASE_URL}/messages`).subscribe();
    const req = m.expectOne(`${API_BASE_URL}/messages`);
    expect(req.request.headers.has('NiordDomain')).toBe(false);
    req.flush({});
  });

  it('adds no NiordDomain header to a ticketed request: the ticket names its domain', () => {
    const { http, httpMock: m } = configure({ sessionDomain: niordDk });
    httpMock = m;
    const context = new HttpContext().set(TICKETED_REQUEST, true);
    http.get(`${API_BASE_URL}/message-reports/report.pdf?ticket=T`, { context }).subscribe();
    const req = m.expectOne(`${API_BASE_URL}/message-reports/report.pdf?ticket=T`);
    expect(req.request.headers.has('NiordDomain')).toBe(false);
    req.flush({});
  });

  it('adds NiordDomain header on backend requests when session domain is set', () => {
    const { http, httpMock: m } = configure({ sessionDomain: niordDk });
    httpMock = m;
    http.get(`${API_BASE_URL}/messages`).subscribe();
    const req = m.expectOne(`${API_BASE_URL}/messages`);
    expect(req.request.headers.get('NiordDomain')).toBe('niord-dk');
    req.flush({});
  });
});
