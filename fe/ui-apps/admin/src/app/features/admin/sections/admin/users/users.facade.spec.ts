import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { UsersFacade } from './users.facade';

const ALL_USERS_URL = '/rest/users/kc-users?search=&first=0&max=10000';

const userVo = (i: number) => ({
  keycloakId: `id-${i}`,
  username: `user-${i}`,
  email: `user-${i}@x.dk`,
});

describe('UsersFacade', () => {
  let facade: UsersFacade;
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
        { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
        provideHttpClient(),
        provideHttpClientTesting(),
        UsersFacade,
      ],
    });
    facade = TestBed.inject(UsersFacade);
    facade.activateUsersSide();
    TestBed.tick();
    httpTesting = TestBed.inject(HttpTestingController);
    // activateUsersSide triggers: the all-users fetch, the group tree, and the
    // lang-keyed all-lists catalog.
    httpTesting.expectOne(ALL_USERS_URL).flush([]);
    httpTesting.expectOne('/rest/users/kc-groups').flush([]);
    httpTesting.expectOne('/rest/mailing-lists/search?lang=en').flush([]);
  });

  afterEach(() => httpTesting.verify());

  it('loads the WHOLE user list in one request (client-paged, like the other admin sections)', () => {
    facade.loadUsers();
    httpTesting.expectOne(ALL_USERS_URL).flush(Array.from({ length: 25 }, (_, i) => userVo(i)));

    expect(facade.users()).toHaveLength(25); // the full set — the tab pages it client-side
    expect(facade.usersLoading()).toBe(false);
    expect(facade.usersError()).toBe(false);
  });

  it('a failed load surfaces the error state', () => {
    facade.loadUsers();
    httpTesting.expectOne(ALL_USERS_URL).flush('boom', { status: 500, statusText: 'Server Error' });

    expect(facade.usersError()).toBe(true);
    expect(facade.users()).toHaveLength(0);
  });

  it('activateUsersSide is idempotent — a second call does not re-fetch', () => {
    facade.activateUsersSide(); // already active from beforeEach — no new request
    httpTesting.verify(); // throws if a duplicate fetch fired
  });
});
