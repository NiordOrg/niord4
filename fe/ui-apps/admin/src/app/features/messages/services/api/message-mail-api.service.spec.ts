import { TestBed } from '@angular/core/testing';
import { HttpParams, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { MessageMailApiService } from './message-mail-api.service';

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
    api: TestBed.inject(MessageMailApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('MessageMailApiService', () => {
  let s: ReturnType<typeof setup>;
  beforeEach(() => {
    s = setup();
  });
  afterEach(() => {
    s.httpTesting.verify();
  });

  it(`POSTs ${API_BASE}/message-mail/send with the mail in the body and the selection in the query (L-153)`, () => {
    const params = new HttpParams().set('tag', 't1').set('lang', 'en');
    const body = {
      mailTo: ['a@x.test', 'b@x.test'],
      mailSubject: 'Hello',
      mailMessage: 'A long text',
    };

    s.api.send(params, body).subscribe();

    const req = s.httpTesting.expectOne((r) => r.url === `${API_BASE}/message-mail/send`);
    expect(req.request.method).toBe('POST');
    expect(req.request.responseType).toBe('text');
    expect(req.request.params.get('tag')).toBe('t1');
    expect(req.request.params.get('lang')).toBe('en');
    // The subject, the text and the addresses travel in the body, never in the address: a long
    // text no longer meets the request-line limit, and none of it reaches an access log.
    expect(req.request.params.keys()).toEqual(['tag', 'lang']);
    expect(req.request.body).toEqual(body);
    req.flush('Message mail scheduled to a@x.test in 3 ms');
  });
});
