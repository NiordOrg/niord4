import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../config/app-config.token';
import { UserDirectoryApiService } from './users-api.service';

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
    api: TestBed.inject(UserDirectoryApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('UserDirectoryApiService', () => {
  let s: ReturnType<typeof setup>;
  beforeEach(() => {
    s = setup();
  });
  afterEach(() => {
    s.httpTesting.verify();
  });

  describe('searchDisplay', () => {
    it('maps search results into DisplayUser with a joined displayName', () => {
      let received: { username: string; displayName: string }[] | undefined;
      s.api.searchDisplay('jo').subscribe((users) => (received = users));
      const req = s.httpTesting.expectOne((r) => r.url === `${API_BASE}/users/search`);
      expect(req.request.params.get('name')).toBe('jo');
      req.flush([{ username: 'jdoe', firstName: 'John', lastName: 'Doe' }, { username: 'solo' }]);
      expect(received).toEqual([
        { username: 'jdoe', firstName: 'John', lastName: 'Doe', displayName: 'John Doe' },
        { username: 'solo', displayName: 'solo' },
      ]);
    });

    it('short-circuits on empty query (no HTTP request)', () => {
      let received: unknown;
      s.api.searchDisplay('').subscribe((users) => (received = users));
      s.httpTesting.expectNone(() => true);
      expect(received).toEqual([]);
    });
  });

  describe('searchEmails', () => {
    it(`GETs ${API_BASE}/users/search-emails?name=<q>`, () => {
      let received: readonly string[] | undefined;
      s.api.searchEmails('john').subscribe((emails) => (received = emails));
      const req = s.httpTesting.expectOne((r) => r.url === `${API_BASE}/users/search-emails`);
      expect(req.request.params.get('name')).toBe('john');
      req.flush(['john@niord.test', 'johnny@niord.test']);
      expect(received).toEqual(['john@niord.test', 'johnny@niord.test']);
    });

    it('short-circuits on empty query (no HTTP request)', () => {
      s.api.searchEmails('').subscribe();
      s.httpTesting.expectNone(() => true);
    });
  });
});
