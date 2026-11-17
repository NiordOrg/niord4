import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../config/app-config.token';
import { AuthTicketService } from './auth-ticket.service';

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
    service: TestBed.inject(AuthTicketService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('AuthTicketService', () => {
  let s: ReturnType<typeof setup>;
  beforeEach(() => {
    s = setup();
  });
  afterEach(() => {
    s.httpTesting.verify();
  });

  it(`GETs ${API_BASE}/tickets/ticket and returns the text body`, () => {
    let received: string | undefined;
    s.service.fetch().subscribe((t) => (received = t));
    const req = s.httpTesting.expectOne(`${API_BASE}/tickets/ticket`);
    expect(req.request.method).toBe('GET');
    expect(req.request.responseType).toBe('text');
    req.flush('the-ticket');
    expect(received).toBe('the-ticket');
  });

  it('fetches a fresh ticket on each call (no caching — security)', () => {
    s.service.fetch().subscribe();
    s.service.fetch().subscribe();
    s.httpTesting.match(`${API_BASE}/tickets/ticket`).forEach((req) => req.flush('t'));
  });

  it('emits ?role= only when a role scope is supplied', () => {
    s.service.fetch('sysadmin').subscribe();
    const req = s.httpTesting.expectOne(`${API_BASE}/tickets/ticket?role=sysadmin`);
    expect(req.request.params.get('role')).toBe('sysadmin');
    req.flush('t');

    s.service.fetch().subscribe();
    const bare = s.httpTesting.expectOne(`${API_BASE}/tickets/ticket`);
    expect(bare.request.params.has('role')).toBe(false);
    bare.flush('t');
  });
});
