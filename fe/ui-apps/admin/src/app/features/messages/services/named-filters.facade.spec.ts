import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../core/config/app-config.token';
import { NotificationService } from '../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../shared/testing/transloco-testing';
import { ConfirmService } from '../../../shared/services/confirm.service';
import { AuthFacade } from '../../../core/auth/facades/auth.facade';
import type { NamedFilter } from '../models/named-filter.model';
import { NamedFiltersApiService } from './api/named-filters-api.service';
import { NamedFiltersFacade } from './named-filters.facade';
import { MessagesFacade } from './messages-facade.service';

const API_BASE = '/rest';
const LIST_URL = `${API_BASE}/filters/all`;

interface FakeAuth {
  isAuthenticated: ReturnType<typeof signal<boolean>>;
}

// The GET (list) now runs through httpResource → HttpClient; only the mutations
// stay on the api service, so the fake exposes just add/remove.
interface FakeApi {
  add: ReturnType<typeof vi.fn>;
  remove: ReturnType<typeof vi.fn>;
}

interface FakeMessagesFacade {
  filter: {
    requestParams: ReturnType<typeof signal<string>>;
    applyQueryString: ReturnType<typeof vi.fn>;
  };
}

interface Setup {
  facade: NamedFiltersFacade;
  auth: FakeAuth;
  api: FakeApi;
  httpMock: HttpTestingController;
}

function setup(): Setup {
  const auth: FakeAuth = { isAuthenticated: signal(false) };
  const api: FakeApi = { add: vi.fn(), remove: vi.fn() };
  const messages: FakeMessagesFacade = {
    filter: { requestParams: signal(''), applyQueryString: vi.fn() },
  };

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE, appName: 'Niord', helpUrls: {} } },
      { provide: AuthFacade, useValue: auth },
      { provide: NamedFiltersApiService, useValue: api },
      { provide: MessagesFacade, useValue: messages },
      // Real signal-bus ConfirmService; the facade's
      // confirmDestructive publishes to it — an <app-confirm> would render it.
      ConfirmService,
    ],
  });

  return {
    facade: TestBed.inject(NamedFiltersFacade),
    auth,
    api,
    httpMock: TestBed.inject(HttpTestingController),
  };
}

/**
 * Drain Angular's effect scheduler and the JS microtask queue so the httpResource
 * URL recomputes, the request is issued, and the resource's 'loading' → resolved |
 * error transition completes. The project is zoneless, so we cannot use fakeAsync —
 * flush manually instead.
 */
async function settle(): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
}

describe('NamedFiltersFacade', () => {
  let s: Setup;

  beforeEach(() => {
    s = setup();
  });

  afterEach(() => {
    s.httpMock.verify();
  });

  it('namedFilters is [] when anonymous and does not fetch', () => {
    expect(s.facade.namedFilters()).toEqual([]);
    s.httpMock.expectNone(LIST_URL);
  });

  it('does not fetch on login until load() is called (lazy load)', async () => {
    s.auth.isAuthenticated.set(true);
    await settle();
    s.httpMock.expectNone(LIST_URL);

    s.facade.load();
    await settle();
    s.httpMock.expectOne(LIST_URL).flush([]);
  });

  it('exposes the loaded filters after a successful load', async () => {
    s.auth.isAuthenticated.set(true);
    s.facade.load();
    await settle();
    s.httpMock
      .expectOne(LIST_URL)
      .flush([
        { id: 1, name: 'Foo', parameters: '' } as NamedFilter,
        { id: 2, name: 'Bar', parameters: '' } as NamedFilter,
      ]);
    await settle();

    expect(s.facade.namedFilters().map((f) => f.name)).toEqual(['Foo', 'Bar']);
    expect(s.facade.hasLoaded()).toBe(true);
    expect(s.facade.lastError()).toBeUndefined();
  });

  // Regression: resource.value() throws when the resource is in error state. The
  // facade must guard reads via hasValue() so consumers get an empty list, not a throw.
  it('does not throw when the loader errors — namedFilters returns []', async () => {
    s.auth.isAuthenticated.set(true);
    s.facade.load();
    await settle();
    s.httpMock.expectOne(LIST_URL).flush('Forbidden', { status: 403, statusText: 'Forbidden' });
    await settle();

    expect(() => s.facade.namedFilters()).not.toThrow();
    expect(s.facade.namedFilters()).toEqual([]);
    expect(s.facade.lastError()).toBeDefined();
  });

  // L-043: a failed load must not read as "no saved filters"; the menu needs to tell the two apart.
  it('loadFailed is true after a failed load and false after a good one', async () => {
    s.auth.isAuthenticated.set(true);
    expect(s.facade.loadFailed()).toBe(false);

    s.facade.load();
    await settle();
    s.httpMock.expectOne(LIST_URL).flush('Boom', { status: 500, statusText: 'Server Error' });
    await settle();
    expect(s.facade.loadFailed()).toBe(true);

    // Opening the menu again tries once more. While the retry is in flight the menu must read it as
    // loading (loading() and not hasLoaded()), not as a loaded empty list.
    s.facade.load();
    await settle();
    const retry = s.httpMock.expectOne(LIST_URL);
    expect(s.facade.loading()).toBe(true);
    expect(s.facade.hasLoaded()).toBe(false);

    // A good answer clears the failure.
    retry.flush([{ id: 1, name: 'Foo', parameters: '' } as NamedFilter]);
    await settle();
    expect(s.facade.loadFailed()).toBe(false);
    expect(s.facade.hasLoaded()).toBe(true);
    expect(s.facade.namedFilters().map((f) => f.name)).toEqual(['Foo']);
  });

  it('a reload after a good load keeps hasLoaded true, so the list stays in the menu', async () => {
    s.auth.isAuthenticated.set(true);
    s.facade.load();
    await settle();
    s.httpMock.expectOne(LIST_URL).flush([{ id: 7, name: 'Foo', parameters: '' } as NamedFilter]);
    await settle();

    s.api.remove.mockReturnValue(of(undefined));
    s.facade.confirmRemove({ id: 7, name: 'Foo', parameters: '' } as NamedFilter);
    TestBed.inject(ConfirmService).active()?.accept();
    await settle();

    const reload = s.httpMock.expectOne(LIST_URL);
    expect(s.facade.loading()).toBe(true);
    expect(s.facade.hasLoaded()).toBe(true);
    reload.flush([]);
    await settle();
  });

  it('loadFailed is false once the user logs out after a failed load', async () => {
    s.auth.isAuthenticated.set(true);
    s.facade.load();
    await settle();
    s.httpMock.expectOne(LIST_URL).flush('Boom', { status: 500, statusText: 'Server Error' });
    await settle();
    expect(s.facade.loadFailed()).toBe(true);

    s.auth.isAuthenticated.set(false);
    await settle();
    expect(s.facade.loadFailed()).toBe(false);
  });

  // L-043: a delete that failed left the filter in place without a word.
  it('a failed delete shows the remove error as a toast and keeps the list', async () => {
    const notifications = TestBed.inject(NotificationService);
    const filter = { id: 7, name: 'Foo', parameters: '' } as NamedFilter;
    s.auth.isAuthenticated.set(true);
    s.facade.load();
    await settle();
    s.httpMock.expectOne(LIST_URL).flush([filter]);
    await settle();

    s.api.remove.mockReturnValue(throwError(() => new Error('Server Error')));
    s.facade.confirmRemove(filter);
    TestBed.inject(ConfirmService).active()?.accept();
    await settle();

    expect(s.api.remove).toHaveBeenCalledWith(7);
    expect(
      notifications.messages().map((m) => ({ severity: m.severity, summary: m.summary })),
    ).toEqual([{ severity: 'error', summary: 'messages.namedFilter.error.remove' }]);
    expect(s.facade.namedFilters().map((f) => f.name)).toEqual(['Foo']);
    s.httpMock.expectNone(LIST_URL);
  });

  it('a delete that succeeds shows no error and reloads the list', async () => {
    const notifications = TestBed.inject(NotificationService);
    const filter = { id: 7, name: 'Foo', parameters: '' } as NamedFilter;
    s.auth.isAuthenticated.set(true);
    s.facade.load();
    await settle();
    s.httpMock.expectOne(LIST_URL).flush([filter]);
    await settle();

    s.api.remove.mockReturnValue(of(undefined));
    s.facade.confirmRemove(filter);
    TestBed.inject(ConfirmService).active()?.accept();
    await settle();

    s.httpMock.expectOne(LIST_URL).flush([]);
    await settle();
    expect(notifications.messages()).toEqual([]);
    expect(s.facade.namedFilters()).toEqual([]);
  });

  it('clears state when the user logs out (resource returns to idle)', async () => {
    s.auth.isAuthenticated.set(true);
    s.facade.load();
    await settle();
    s.httpMock.expectOne(LIST_URL).flush([{ id: 1, name: 'Foo', parameters: '' } as NamedFilter]);
    await settle();
    expect(s.facade.namedFilters().map((f) => f.name)).toEqual(['Foo']);

    s.auth.isAuthenticated.set(false);
    await settle();
    expect(s.facade.namedFilters()).toEqual([]);
    expect(s.facade.lastError()).toBeUndefined();
  });
});
