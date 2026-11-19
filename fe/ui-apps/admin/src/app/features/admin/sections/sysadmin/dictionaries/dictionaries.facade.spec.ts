import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DictionariesFacade } from './dictionaries.facade';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      DictionariesFacade,
    ],
  });
  const facade = TestBed.inject(DictionariesFacade);
  TestBed.tick();
  const httpTesting = TestBed.inject(HttpTestingController);
  // The names list loads once alongside the first entries fetch.
  httpTesting
    .expectOne('/rest/dictionaries/names')
    .flush(['web', 'message', 'mail', 'pilotage', 'template']);
  return { facade, httpTesting };
}

describe('DictionariesFacade', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it("defaults to the 'web' dictionary", () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne('/rest/dictionaries/dictionary/web/entries').flush([]);
    expect(facade.currentName()).toBe('web');
    expect(facade.loading()).toBe(false);
  });

  it('switches dictionary only for known names and refetches', () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne('/rest/dictionaries/dictionary/web/entries').flush([]);

    facade.selectDictionary('nope');
    TestBed.tick();
    expect(facade.currentName()).toBe('web'); // unknown name: no-op, no request

    facade.selectDictionary('mail');
    TestBed.tick();
    expect(facade.currentName()).toBe('mail');
    httpTesting
      .expectOne('/rest/dictionaries/dictionary/mail/entries')
      .flush([{ key: 'k', descs: [] }]);
    expect(facade.entries().length).toBe(1);
  });

  it('refresh() refetches the current dictionary (post-mutation / post-import)', () => {
    const { facade, httpTesting } = setup();
    httpTesting.expectOne('/rest/dictionaries/dictionary/web/entries').flush([]);
    facade.refresh();
    TestBed.tick();
    httpTesting.expectOne('/rest/dictionaries/dictionary/web/entries').flush([]);
    expect(facade.error()).toBe(false);
  });

  it('a failed entries fetch sets the error flag', () => {
    const { facade, httpTesting } = setup();
    httpTesting
      .expectOne('/rest/dictionaries/dictionary/web/entries')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(facade.error()).toBe(true);
  });

  it('a failed read of the names counts as a failed load, and refresh() reads them again', () => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
        provideHttpClient(),
        provideHttpClientTesting(),
        DictionariesFacade,
      ],
    });
    const facade = TestBed.inject(DictionariesFacade);
    TestBed.tick();
    const httpTesting = TestBed.inject(HttpTestingController);
    httpTesting
      .expectOne('/rest/dictionaries/names')
      .flush('boom', { status: 503, statusText: 'Service Unavailable' });
    httpTesting.expectOne('/rest/dictionaries/dictionary/web/entries').flush([]);
    expect(facade.namesFailed()).toBe(true);
    expect(facade.error()).toBe(true);

    facade.refresh();
    TestBed.tick();
    httpTesting.expectOne('/rest/dictionaries/names').flush(['web', 'mail']);
    httpTesting.expectOne('/rest/dictionaries/dictionary/web/entries').flush([]);
    expect(facade.names()).toEqual(['web', 'mail']);
    expect(facade.error()).toBe(false);
  });

  it("falls back to the first available name when 'web' is absent from the deployment", () => {
    TestBed.configureTestingModule({
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
        provideHttpClient(),
        provideHttpClientTesting(),
        DictionariesFacade,
      ],
    });
    const facade = TestBed.inject(DictionariesFacade);
    TestBed.tick();
    const httpTesting = TestBed.inject(HttpTestingController);
    // The default fetch for 'web' may fire before the names arrive — flush whatever came.
    httpTesting
      .match(() => true)
      .forEach((req) => {
        if (req.request.url.endsWith('/rest/dictionaries/names')) {
          req.flush(['message', 'mail']);
        } else {
          req.flush([]);
        }
      });
    TestBed.tick();
    expect(facade.currentName()).toBe('message');
    // The corrective refetch targets the fallback dictionary.
    httpTesting.expectOne('/rest/dictionaries/dictionary/message/entries').flush([]);
  });
});
