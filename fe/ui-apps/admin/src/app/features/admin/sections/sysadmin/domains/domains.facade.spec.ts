import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { DomainsFacade } from './domains.facade';

function setup(language: WritableSignal<string> = signal('en')) {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: LanguageService, useValue: { selectedLanguage: language } },
      DomainsFacade,
    ],
  });
  const facade = TestBed.inject(DomainsFacade);
  // toObservable() emits through an effect — flush it so the initial fetch fires.
  TestBed.tick();
  return {
    facade,
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

function expectListRequest(httpTesting: HttpTestingController, lang: string) {
  const req = httpTesting.expectOne((r) => r.url === '/rest/domains/all');
  expect(req.request.params.get('inactive')).toBe('true');
  expect(req.request.params.get('lang')).toBe(lang);
  return req;
}

describe('DomainsFacade', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('fetches the inactive-inclusive list at the active language on init', () => {
    const { facade, httpTesting } = setup();
    expect(facade.loading()).toBe(true);
    expectListRequest(httpTesting, 'en').flush([
      { domainId: 'niord-nw', name: 'NW', active: true },
      { domainId: 'niord-old', name: 'Old', active: false, inKeycloak: null },
    ]);
    expect(facade.loading()).toBe(false);
    expect(facade.domains().length).toBe(2);
    expect(facade.error()).toBe(false);
  });

  it('refresh() refetches (post-mutation) and shows the loading shell again', () => {
    const { facade, httpTesting } = setup();
    expectListRequest(httpTesting, 'en').flush([]);
    facade.refresh();
    TestBed.tick();
    expect(facade.loading()).toBe(true);
    expectListRequest(httpTesting, 'en').flush([{ domainId: 'niord-nm', active: true }]);
    expect(facade.domains().length).toBe(1);
  });

  it('refetches with the new ?lang= when the UI language switches', () => {
    const language = signal('en');
    const { httpTesting } = setup(language);
    expectListRequest(httpTesting, 'en').flush([]);

    language.set('da');
    TestBed.tick();
    expectListRequest(httpTesting, 'da').flush([{ domainId: 'niord-nw', active: true }]);
  });

  it('a failed fetch sets the error flag (no silent failure)', () => {
    const { facade, httpTesting } = setup();
    expectListRequest(httpTesting, 'en').flush('boom', {
      status: 500,
      statusText: 'Server Error',
    });
    expect(facade.error()).toBe(true);
    expect(facade.loading()).toBe(false);
  });
});
