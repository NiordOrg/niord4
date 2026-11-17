import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { KEYCLOAK } from '../../../../../core/auth/services/keycloak.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { UsersContactsPage } from './users-contacts-page';

// jsdom lacks ResizeObserver, which the tab list observes.
const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

function setup(canManageUsers: boolean) {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      { provide: AuthFacade, useValue: { canManageUsers: signal(canManageUsers) } },
      { provide: LanguageService, useValue: { selectedLanguage: () => 'en' } },
      { provide: KEYCLOAK, useValue: { authServerUrl: 'https://kc/auth', realm: 'niord' } },
      {
        provide: NotificationService,
        useValue: { success: () => undefined, error: () => undefined },
      },
    ],
  });
  const fixture = TestBed.createComponent(UsersContactsPage);
  fixture.detectChanges();
  const httpTesting = TestBed.inject(HttpTestingController);
  // The Contacts tab always mounts and fires its server search; its facade
  // pulls the lang-keyed lists catalog. The Keycloak-proxy fetches (kc-users +
  // kc-groups) fire ONLY when the GATED Users tab mounts — the ungated case's
  // afterEach verify() proves they never do.
  httpTesting
    .expectOne((req) => req.url.includes('/contacts/search'))
    .flush({
      data: [],
      size: 0,
      total: 0,
    });
  httpTesting.expectOne('/rest/mailing-lists/search?lang=en').flush([]);
  if (canManageUsers) {
    httpTesting.expectOne((req) => req.url.includes('/users/kc-users')).flush([]);
    httpTesting.expectOne('/rest/users/kc-groups').flush([]);
  }
  return { fixture, httpTesting };
}

describe('UsersContactsPage', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('with manage-users: both tabs render and Users is the default', () => {
    const { fixture } = setup(true);
    fixture.detectChanges();

    const tabs = [...fixture.nativeElement.querySelectorAll('[role="tab"]')].map((tab) =>
      (tab as HTMLElement).textContent?.trim(),
    );
    expect(tabs).toHaveLength(2);
    expect(fixture.nativeElement.querySelector('app-users-tab')).not.toBeNull();
  });

  it('WITHOUT manage-users: only Contacts renders, becomes the default tab, and NO Keycloak-proxy fetch fires', () => {
    const { fixture } = setup(false);
    fixture.detectChanges();
    // The afterEach verify() proves no kc-users/kc-groups request ever fired
    // (the contacts search + lists catalog were the only fetches).

    const tabs = [...fixture.nativeElement.querySelectorAll('[role="tab"]')];
    expect(tabs).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('app-users-tab')).toBeNull();
    expect(fixture.nativeElement.querySelector('app-contacts-tab')).not.toBeNull();
  });
});
