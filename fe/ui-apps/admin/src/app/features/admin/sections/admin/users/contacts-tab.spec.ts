import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { KEYCLOAK } from '../../../../../core/auth/services/keycloak.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { ContactsTab } from './contacts-tab';
import { UsersFacade } from './users.facade';

// jsdom lacks ResizeObserver, which the dialogs' overlays may observe.
const ResizeObserverStub = class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;
globalThis.ResizeObserver ??= ResizeObserverStub;

/** The page provides UsersFacade to both tabs; the host does the same here. */
@Component({
  imports: [ContactsTab],
  providers: [UsersFacade],
  template: `<app-contacts-tab />`,
})
class Host {}

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      { provide: LanguageService, useValue: { selectedLanguage: () => 'en', modelLanguages: [] } },
      { provide: KEYCLOAK, useValue: { authServerUrl: 'https://kc/auth', realm: 'niord' } },
      {
        provide: NotificationService,
        useValue: { success: () => undefined, error: () => undefined },
      },
      { provide: ConfirmService, useValue: { confirmDestructive: () => undefined } },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  const httpTesting = TestBed.inject(HttpTestingController);
  // The host-provided UsersFacade loads the lang-keyed lists catalog.
  httpTesting.expectOne('/rest/mailing-lists/search?lang=en').flush([]);
  // The tab's first search, with no filter.
  searchFor(httpTesting, undefined).flush({ data: [], size: 0, total: 0 });
  fixture.detectChanges();
  return { fixture, httpTesting };
}

/** The one open contacts search whose `name` param is `name` (none: no filter). */
function searchFor(httpTesting: HttpTestingController, name: string | undefined): TestRequest {
  return httpTesting.expectOne(
    (req) => req.url === '/rest/contacts/search' && (req.params.get('name') ?? undefined) === name,
  );
}

/** Type into the filter and leave the field, which commits the filter at once. */
function commitFilter(fixture: ComponentFixture<Host>, value: string): void {
  const input = fixture.nativeElement.querySelector('app-contacts-tab input') as HTMLInputElement;
  input.value = value;
  input.dispatchEvent(new Event('input'));
  input.dispatchEvent(new Event('blur'));
  fixture.detectChanges();
}

function answer(email: string) {
  return { data: [{ id: email.length, email, name: email }], size: 1, total: 1 };
}

function shownEmails(fixture: ComponentFixture<Host>): string[] {
  return [...fixture.nativeElement.querySelectorAll('tbody tr td:first-child')].map((cell) =>
    (cell as HTMLElement).textContent!.trim(),
  );
}

describe('ContactsTab', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('shows the latest filter rows when an older search answers last', () => {
    const { fixture, httpTesting } = setup();

    commitFilter(fixture, 'alpha');
    commitFilter(fixture, 'beta');
    const older = searchFor(httpTesting, 'alpha');
    const newer = searchFor(httpTesting, 'beta');

    newer.flush(answer('beta@example.org'));
    fixture.detectChanges();
    expect(shownEmails(fixture)).toEqual(['beta@example.org']);

    older.flush(answer('alpha@example.org'));
    fixture.detectChanges();
    expect(shownEmails(fixture)).toEqual(['beta@example.org']);
    expect(fixture.nativeElement.querySelector('app-error-banner')).toBeNull();
  });

  it('shows none of an older search rows when it answers before the latest one', () => {
    const { fixture, httpTesting } = setup();

    commitFilter(fixture, 'alpha');
    commitFilter(fixture, 'beta');
    const older = searchFor(httpTesting, 'alpha');
    const newer = searchFor(httpTesting, 'beta');

    older.flush(answer('alpha@example.org'));
    fixture.detectChanges();
    expect(shownEmails(fixture)).toEqual([]);

    newer.flush(answer('beta@example.org'));
    fixture.detectChanges();
    expect(shownEmails(fixture)).toEqual(['beta@example.org']);
  });

  it('an older search that fails after the latest one answered sets no error', () => {
    const { fixture, httpTesting } = setup();

    commitFilter(fixture, 'alpha');
    commitFilter(fixture, 'beta');
    const older = searchFor(httpTesting, 'alpha');
    const newer = searchFor(httpTesting, 'beta');

    newer.flush(answer('beta@example.org'));
    older.flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-error-banner')).toBeNull();
    expect(shownEmails(fixture)).toEqual(['beta@example.org']);
  });

  it('the latest search failing still shows the error', () => {
    const { fixture, httpTesting } = setup();

    commitFilter(fixture, 'beta');
    searchFor(httpTesting, 'beta').flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-error-banner')).not.toBeNull();
  });
});
