import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { computed, signal } from '@angular/core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthService } from '../auth/services/auth.service';
import { authTokenInterceptor } from './auth-token.interceptor';
import { niordDomainInterceptor } from './niord-domain.interceptor';
import { APP_CONFIG } from '../config/app-config.token';
import type { AppConfig } from '../config/app-config.interface';
import { DomainFacade } from '../domain/facades/domain.facade';

interface ConfigureOptions {
  readonly authenticated?: boolean;
  readonly token?: string | undefined;
  readonly tokenRejects?: boolean;
  readonly withDomainInterceptor?: boolean;
  readonly sessionDomainId?: string;
  readonly apiBaseUrl?: string;
  readonly wmsUrl?: string;
}

function configure(opts: ConfigureOptions = {}) {
  const authenticated = signal(opts.authenticated ?? false);
  const ensureFreshToken = vi.fn(() => {
    if (opts.tokenRejects) return Promise.reject(new Error('refresh failed'));
    return Promise.resolve(opts.token);
  });
  const sessionExpired = vi.fn();
  const authStub = {
    isAuthenticated: computed(() => authenticated()),
    ensureFreshToken,
    sessionExpired,
  } as unknown as AuthService;

  const interceptors = opts.withDomainInterceptor
    ? withInterceptors([authTokenInterceptor, niordDomainInterceptor])
    : withInterceptors([authTokenInterceptor]);

  const sessionDomain = signal<{ domainId: string } | undefined>(
    opts.sessionDomainId ? { domainId: opts.sessionDomainId } : undefined,
  );

  // Minimal config: `isNiordBackendRequest` only reads `apiBaseUrl` and
  // `map.wmsUrl`. Other fields are required by the interface so we stub a
  // valid-enough shape. The relative-prefix branch (`/api/`, `/wms/`) is what
  // the tests exercise — the absolute-URL branch is covered separately.
  const appConfig = {
    apiBaseUrl: opts.apiBaseUrl ?? '/api',
    appName: 'Niord',
    helpUrls: {},
    keycloak: { url: '', realm: '', clientId: '' },
    map: { wmsUrl: opts.wmsUrl ?? '/wms/' },
  } as unknown as AppConfig;

  TestBed.configureTestingModule({
    providers: [
      { provide: AuthService, useValue: authStub },
      { provide: DomainFacade, useValue: { sessionDomain } },
      { provide: APP_CONFIG, useValue: appConfig },
      provideHttpClient(interceptors),
      provideHttpClientTesting(),
    ],
  });

  return {
    http: TestBed.inject(HttpClient),
    httpMock: TestBed.inject(HttpTestingController),
    setAuthenticated: (v: boolean) => authenticated.set(v),
    ensureFreshToken,
    sessionExpired,
  };
}

describe('authTokenInterceptor', () => {
  let httpMock: HttpTestingController | null = null;

  beforeEach(() => {
    httpMock = null;
  });

  afterEach(() => httpMock?.verify());

  it('passes non-/api/** requests through without modification', () => {
    const { http, httpMock: m, ensureFreshToken } = configure({ authenticated: true });
    httpMock = m;
    http.get('/static/asset.json').subscribe();
    const req = m.expectOne('/static/asset.json');
    expect(req.request.headers.has('Authorization')).toBe(false);
    expect(ensureFreshToken).not.toHaveBeenCalled();
    req.flush({});
  });

  it('passes /api/** through without bearer header when anonymous', () => {
    const { http, httpMock: m, ensureFreshToken } = configure({ authenticated: false });
    httpMock = m;
    http.get('/api/messages').subscribe();
    const req = m.expectOne('/api/messages');
    expect(req.request.headers.has('Authorization')).toBe(false);
    expect(ensureFreshToken).not.toHaveBeenCalled();
    req.flush({});
  });

  it('attaches Bearer header on /api/** when authenticated', async () => {
    const {
      http,
      httpMock: m,
      ensureFreshToken,
    } = configure({
      authenticated: true,
      token: 'tkn-123',
    });
    httpMock = m;
    http.get('/api/messages').subscribe();
    await Promise.resolve();
    const req = m.expectOne('/api/messages');
    expect(req.request.headers.get('Authorization')).toBe('Bearer tkn-123');
    expect(ensureFreshToken).toHaveBeenCalledTimes(1);
    req.flush({});
  });

  it('omits Bearer header when ensureFreshToken resolves undefined', async () => {
    const { http, httpMock: m } = configure({ authenticated: true, token: undefined });
    httpMock = m;
    http.get('/api/messages').subscribe();
    await Promise.resolve();
    const req = m.expectOne('/api/messages');
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({});
  });

  it('drops to guest (sessionExpired, NOT a logout redirect) when /api/** returns 401', async () => {
    const errors: unknown[] = [];
    const { http, httpMock: m, sessionExpired } = configure({ authenticated: true, token: 'tkn' });
    httpMock = m;
    http.get('/api/messages').subscribe({ error: (e) => errors.push(e) });
    await Promise.resolve();
    const req = m.expectOne('/api/messages');
    req.flush({ message: 'unauthorized' }, { status: 401, statusText: 'Unauthorized' });
    expect(sessionExpired).toHaveBeenCalledTimes(1);
    expect(errors).toHaveLength(1);
  });

  it('does NOT drop to guest on non-401 errors (e.g. 403)', async () => {
    const errors: unknown[] = [];
    const { http, httpMock: m, sessionExpired } = configure({ authenticated: true, token: 'tkn' });
    httpMock = m;
    http.get('/api/messages').subscribe({ error: (e) => errors.push(e) });
    await Promise.resolve();
    const req = m.expectOne('/api/messages');
    req.flush({ message: 'forbidden' }, { status: 403, statusText: 'Forbidden' });
    expect(sessionExpired).not.toHaveBeenCalled();
    expect(errors).toHaveLength(1);
  });

  it('propagates the error and does NOT issue the HTTP request when ensureFreshToken rejects', async () => {
    // The logout-on-refresh-failure behavior lives in AuthService.ensureFreshToken itself
    // (see auth.service.spec). The interceptor's responsibility is only to propagate the
    // rejection and skip the HTTP call.
    const errors: unknown[] = [];
    const {
      http,
      httpMock: m,
      ensureFreshToken,
    } = configure({
      authenticated: true,
      tokenRejects: true,
    });
    httpMock = m;
    http.get('/api/messages').subscribe({ error: (e) => errors.push(e) });
    await Promise.resolve();
    await Promise.resolve();
    expect(ensureFreshToken).toHaveBeenCalledTimes(1);
    expect(errors).toHaveLength(1);
    m.expectNone('/api/messages');
  });

  it('attaches Bearer to a WMS tile on the API origin', async () => {
    const { http, httpMock: m } = configure({ authenticated: true, token: 'tkn-wms' });
    httpMock = m;
    http.get('/wms/?REQUEST=GetMap', { responseType: 'blob' }).subscribe();
    await Promise.resolve();
    const req = m.expectOne('/wms/?REQUEST=GetMap');
    expect(req.request.headers.get('Authorization')).toBe('Bearer tkn-wms');
    req.flush(new Blob());
  });

  it('does NOT attach Bearer to a WMS prefix on another origin', () => {
    const {
      http,
      httpMock: m,
      ensureFreshToken,
    } = configure({
      authenticated: true,
      token: 'tkn-wms',
      apiBaseUrl: 'https://niord.example.invalid/rest',
      wmsUrl: 'https://tiles.example.invalid/wms/',
    });
    httpMock = m;
    http
      .get('https://tiles.example.invalid/wms/?REQUEST=GetMap', { responseType: 'blob' })
      .subscribe();
    const req = m.expectOne('https://tiles.example.invalid/wms/?REQUEST=GetMap');
    expect(req.request.headers.has('Authorization')).toBe(false);
    expect(ensureFreshToken).not.toHaveBeenCalled();
    req.flush(new Blob());
  });

  it('attaches Bearer BEFORE niordDomainInterceptor runs (interceptor ordering)', async () => {
    const { http, httpMock: m } = configure({
      authenticated: true,
      token: 'tkn-order',
      withDomainInterceptor: true,
      sessionDomainId: 'niord-dk',
    });
    httpMock = m;
    http.get('/api/messages').subscribe();
    await Promise.resolve();
    const req = m.expectOne('/api/messages');
    expect(req.request.headers.get('Authorization')).toBe('Bearer tkn-order');
    expect(req.request.headers.get('NiordDomain')).toBe('niord-dk');
    req.flush({});
  });
});
