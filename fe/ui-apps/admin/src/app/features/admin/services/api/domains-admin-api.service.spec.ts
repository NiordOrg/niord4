import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import type { AdminDomain } from '../../models/admin-domain.model';
import { DomainsAdminApiService } from './domains-admin-api.service';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
    ],
  });
  return {
    service: TestBed.inject(DomainsAdminApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

const domain: AdminDomain = {
  domainId: 'niord-nw',
  name: 'NW',
  active: true,
  sortOrder: 0,
  timeZone: 'Europe/Copenhagen',
  messageSortOrder: 'AREA ASC',
};

describe('DomainsAdminApiService', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('GETs the admin list with inactive=true and the active language', () => {
    const { service, httpTesting } = setup();
    service.getAll().subscribe();
    const req = httpTesting.expectOne((r) => r.url === '/rest/domains/all');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('inactive')).toBe('true');
    expect(req.request.params.get('lang')).toBe('en');
    // The backend's keycloakState default (true) must stay in effect so the
    // list carries the per-domain inKeycloak state.
    expect(req.request.params.has('keycloakState')).toBe(false);
    req.flush([]);
  });

  it('GETs the admin list with an explicit language override', () => {
    const { service, httpTesting } = setup();
    service.getAll('da').subscribe();
    const req = httpTesting.expectOne((r) => r.url === '/rest/domains/all');
    expect(req.request.params.get('lang')).toBe('da');
    expect(req.request.params.get('inactive')).toBe('true');
    req.flush([]);
  });

  /**
   * A caller that only needs an id and a name opts out of the Keycloak state.
   *
   * The server computes `inKeycloak` by asking Keycloak once per domain, which
   * is a third of a second on a catalogue of this size — paid by every picker
   * that renders the list as names and shows the state nowhere.
   */
  it('GETs the admin list without the Keycloak state when the caller opts out', () => {
    const { service, httpTesting } = setup();
    service.getAll('en', { keycloakState: false }).subscribe();
    const req = httpTesting.expectOne((r) => r.url === '/rest/domains/all');
    expect(req.request.params.get('keycloakState')).toBe('false');
    // Still the admin list: inactive domains and the language stay as they were.
    expect(req.request.params.get('inactive')).toBe('true');
    expect(req.request.params.get('lang')).toBe('en');
    req.flush([]);
  });

  /** Anything but an explicit opt-out leaves the server's default in place. */
  it('keeps the Keycloak state when the option is absent or true', () => {
    const { service, httpTesting } = setup();
    service.getAll('en', {}).subscribe();
    service.getAll('en', { keycloakState: true }).subscribe();
    const requests = httpTesting.match((r) => r.url === '/rest/domains/all');
    expect(requests.length).toBe(2);
    for (const req of requests) {
      expect(req.request.params.has('keycloakState')).toBe(false);
      req.flush([]);
    }
  });

  it('POSTs the new domain to the trailing-slash collection path', () => {
    const { service, httpTesting } = setup();
    service.create(domain).subscribe();
    const req = httpTesting.expectOne('/rest/domains/domain/');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(domain);
    req.flush(domain);
  });

  it('PUTs the domain on its own id — path id equals body id by construction', () => {
    const { service, httpTesting } = setup();
    service.update(domain).subscribe();
    const req = httpTesting.expectOne('/rest/domains/domain/niord-nw');
    expect(req.request.method).toBe('PUT');
    expect((req.request.body as AdminDomain).domainId).toBe('niord-nw');
    req.flush(domain);
  });

  it('URL-encodes a free-form domainId in every path (ids may carry reserved chars)', () => {
    const { service, httpTesting } = setup();
    const special = { ...domain, domainId: 'dk/client #1' };
    service.update(special).subscribe();
    expect(httpTesting.expectOne('/rest/domains/domain/dk%2Fclient%20%231').request.method).toBe(
      'PUT',
    );
    service.delete('dk/client #1').subscribe();
    expect(httpTesting.expectOne('/rest/domains/domain/dk%2Fclient%20%231').request.method).toBe(
      'DELETE',
    );
  });

  it('DELETEs on the domainId path', () => {
    const { service, httpTesting } = setup();
    service.delete('niord-nw').subscribe();
    const req = httpTesting.expectOne('/rest/domains/domain/niord-nw');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('POSTs the whole domain to the Keycloak provisioning endpoint (void response)', () => {
    const { service, httpTesting } = setup();
    let completed = false;
    service.createInKeycloak(domain).subscribe({ complete: () => (completed = true) });
    const req = httpTesting.expectOne('/rest/domains/keycloak');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual(domain);
    req.flush(null);
    expect(completed).toBe(true);
  });

  it('export URL includes inactive domains so the backup is complete', () => {
    const { service } = setup();
    expect(service.exportUrl()).toBe('/rest/domains/all?inactive=true');
  });

  it('upload URL targets the domain-import endpoint', () => {
    const { service } = setup();
    expect(service.uploadUrl()).toBe('/rest/domains/upload-domains');
  });
});
