import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { MyMailsApiService, type MyMail } from './my-mails-api.service';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return {
    api: TestBed.inject(MyMailsApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('MyMailsApiService', () => {
  let s: ReturnType<typeof setup>;
  beforeEach(() => {
    s = setup();
  });
  afterEach(() => {
    s.httpTesting.verify();
  });

  it('GETs /rest/scheduled-mails/mine with the period in days', () => {
    s.api.mine(7).subscribe();
    const req = s.httpTesting.expectOne((r) => r.url === '/rest/scheduled-mails/mine');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('days')).toBe('7');
    expect(req.request.params.keys()).toEqual(['days']);
    req.flush([]);
  });

  it('gives a mail without recipients or attempts an empty list and zero', () => {
    let result: readonly MyMail[] | undefined;
    s.api.mine(1).subscribe((mails) => (result = mails));
    s.httpTesting.expectOne('/rest/scheduled-mails/mine?days=1').flush([
      { id: 1, status: 'ERROR', subject: 'A', recipients: null, attempts: 5, lastError: 'x' },
      { id: 2, status: 'PENDING', subject: 'B' },
    ]);
    expect(result).toEqual([
      { id: 1, status: 'ERROR', subject: 'A', recipients: [], attempts: 5, lastError: 'x' },
      { id: 2, status: 'PENDING', subject: 'B', recipients: [], attempts: 0 },
    ]);
  });

  it('reads an empty answer as no mail', () => {
    let result: readonly MyMail[] | undefined;
    s.api.mine(1).subscribe((mails) => (result = mails));
    s.httpTesting.expectOne('/rest/scheduled-mails/mine?days=1').flush(null);
    expect(result).toEqual([]);
  });
});
