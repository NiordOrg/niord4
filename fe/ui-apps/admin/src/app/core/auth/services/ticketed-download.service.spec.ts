import { DOCUMENT } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../config/app-config.token';
import { TicketedDownloadService } from './ticketed-download.service';

function setup() {
  const open = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      { provide: DOCUMENT, useValue: { defaultView: { open } } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return {
    service: TestBed.inject(TicketedDownloadService),
    httpTesting: TestBed.inject(HttpTestingController),
    open,
  };
}

describe('TicketedDownloadService', () => {
  it('openPublic opens the URL directly in a new tab — no ticket round-trip', () => {
    const { service, httpTesting, open } = setup();
    service.openPublic('/rest/sources/all');
    httpTesting.expectNone('/rest/tickets/ticket');
    expect(open).toHaveBeenCalledWith('/rest/sources/all', '_blank', 'noopener');
  });

  it('openTicketed fetches a role-scoped ticket and appends ?ticket=', async () => {
    const { service, httpTesting, open } = setup();
    const pending = service.openTicketed('/rest/contacts/export', 'admin');
    httpTesting.expectOne('/rest/tickets/ticket?role=admin').flush('tkt-1');
    await pending;
    expect(open).toHaveBeenCalledWith('/rest/contacts/export?ticket=tkt-1', '_blank', 'noopener');
  });

  it('appends with & when the URL already has a query string', async () => {
    const { service, httpTesting, open } = setup();
    const pending = service.openTicketed('/rest/x/export?lang=en');
    httpTesting.expectOne('/rest/tickets/ticket').flush('tkt-2');
    await pending;
    expect(open).toHaveBeenCalledWith('/rest/x/export?lang=en&ticket=tkt-2', '_blank', 'noopener');
  });
});
