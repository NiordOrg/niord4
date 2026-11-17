import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { ScheduledMail, ScheduledMailsApiService } from './scheduled-mails-api.service';

const EMPTY = { recipient: '', sender: '', subject: '', status: '' } as const;

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return {
    service: TestBed.inject(ScheduledMailsApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('ScheduledMailsApiService', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('empty params emit ONLY maxSize + the 0-based page', () => {
    const { service, httpTesting } = setup();
    service.search(EMPTY, 2, 10).subscribe();
    const req = httpTesting.expectOne('/rest/scheduled-mails/search?maxSize=10&page=2');
    expect(req.request.params.keys().sort()).toEqual(['maxSize', 'page']);
    req.flush({ data: [], size: 0, total: 0 });
  });

  it('non-empty params are appended; dates go as epoch millis from/to', () => {
    const { service, httpTesting } = setup();
    service
      .search(
        {
          recipient: 'a@b',
          sender: '',
          subject: 'Hi',
          status: 'SENT',
          fromDate: 1000,
          toDate: 2000,
        },
        0,
        10,
      )
      .subscribe();
    const req = httpTesting.expectOne((r) => r.url === '/rest/scheduled-mails/search');
    expect(req.request.params.get('recipient')).toBe('a@b');
    expect(req.request.params.has('sender')).toBe(false); // empty → omitted
    expect(req.request.params.get('subject')).toBe('Hi');
    expect(req.request.params.get('status')).toBe('SENT');
    expect(req.request.params.get('from')).toBe('1000');
    expect(req.request.params.get('to')).toBe('2000');
    req.flush({ data: [], size: 0, total: 0 });
  });

  it('normalizes the result shape (null data/recipients → empty arrays)', () => {
    const { service, httpTesting } = setup();
    let received: unknown;
    service.search(EMPTY, 0, 10).subscribe((result) => (received = result));
    httpTesting
      .expectOne('/rest/scheduled-mails/search?maxSize=10&page=0')
      .flush({ data: [{ id: 1, status: 'PENDING', sender: 's', subject: 'x', recipients: null }] });
    const result = received as { data: { recipients: unknown[] }[]; size: number; total: number };
    expect(result.data[0].recipients).toEqual([]);
    expect(result.total).toBe(0);
  });

  it('fetches details by id', () => {
    const { service, httpTesting } = setup();
    let received: unknown;
    service.getMailDetails(42).subscribe((mail) => (received = mail));
    httpTesting
      .expectOne('/rest/scheduled-mails/scheduled-mail/42')
      .flush({ id: 42, status: 'SENT', sender: 's', subject: 'x', recipients: [] });
    expect((received as ScheduledMail).id).toBe(42);
  });
});
