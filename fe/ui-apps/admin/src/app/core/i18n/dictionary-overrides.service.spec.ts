import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../config/app-config.token';
import { DictionaryOverridesService } from './dictionary-overrides.service';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return {
    service: TestBed.inject(DictionaryOverridesService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('DictionaryOverridesService', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('fetches web + message entries and returns a flat key→value map for the language', () => {
    const { service, httpTesting } = setup();
    let result: Record<string, string> | undefined;
    service.fetch('en').subscribe((r) => (result = r));

    httpTesting.expectOne('/rest/dictionaries/dictionary/web/entries').flush([
      {
        key: 'menu.action',
        descs: [
          { lang: 'en', value: 'Actions' },
          { lang: 'da', value: 'Handling' },
        ],
      },
    ]);
    httpTesting
      .expectOne('/rest/dictionaries/dictionary/message/entries')
      .flush([{ key: 'msg.type.LOCAL_WARNING', descs: [{ lang: 'en', value: 'Local' }] }]);

    expect(result).toEqual({ 'menu.action': 'Actions', 'msg.type.LOCAL_WARNING': 'Local' });
  });

  it('picks the requested language, falls back to the default and skips empty entries', () => {
    const { service, httpTesting } = setup();
    let result: Record<string, string> | undefined;
    service.fetch('da', 'en').subscribe((r) => (result = r));

    httpTesting.expectOne('/rest/dictionaries/dictionary/web/entries').flush([
      {
        key: 'a',
        descs: [
          { lang: 'en', value: 'A' },
          { lang: 'da', value: 'A-da' },
        ],
      },
      { key: 'b', descs: [{ lang: 'en', value: 'B' }] }, // no da desc → the default language's
      { key: 'c', descs: [{ lang: 'da', value: '' }] }, // blank da value → skipped
    ]);
    httpTesting.expectOne('/rest/dictionaries/dictionary/message/entries').flush([]);

    expect(result).toEqual({ a: 'A-da', b: 'B' });
  });

  it('an unchanged system-added row yields no override, in the requested or the default language', () => {
    const { service, httpTesting } = setup();
    let result: Record<string, string> | undefined;
    service.fetch('da', 'en').subscribe((r) => (result = r));

    httpTesting.expectOne('/rest/dictionaries/dictionary/web/entries').flush([
      // asAdded in the requested language → the app's own text stands.
      { key: 'a', descs: [{ lang: 'da', value: 'A-da', asAdded: true }] },
      // No Danish row: the default-language fallback is asAdded → none either.
      { key: 'b', descs: [{ lang: 'en', value: 'B', asAdded: true }] },
      // asAdded Danish beside an edited English row: no fall-through to English.
      {
        key: 'c',
        descs: [
          { lang: 'en', value: 'C edited' },
          { lang: 'da', value: 'C-da', asAdded: true },
        ],
      },
    ]);
    httpTesting.expectOne('/rest/dictionaries/dictionary/message/entries').flush([]);

    expect(result).toEqual({});
  });

  it('a row without the flag (an older backend) or with asAdded false still overrides', () => {
    const { service, httpTesting } = setup();
    let result: Record<string, string> | undefined;
    service.fetch('en').subscribe((r) => (result = r));

    httpTesting.expectOne('/rest/dictionaries/dictionary/web/entries').flush([
      { key: 'a', descs: [{ lang: 'en', value: 'A' }] },
      { key: 'b', descs: [{ lang: 'en', value: 'B', asAdded: false }] },
      { key: 'c', descs: [{ lang: 'en', value: 'C', asAdded: null }] },
    ]);
    httpTesting.expectOne('/rest/dictionaries/dictionary/message/entries').flush([]);

    expect(result).toEqual({ a: 'A', b: 'B', c: 'C' });
  });

  it('an edited row keeps overriding beside an asAdded row of another key', () => {
    const { service, httpTesting } = setup();
    let result: Record<string, string> | undefined;
    service.fetch('en').subscribe((r) => (result = r));

    httpTesting.expectOne('/rest/dictionaries/dictionary/web/entries').flush([
      { key: 'menu.action', descs: [{ lang: 'en', value: 'Actions', asAdded: true }] },
      { key: 'menu.close', descs: [{ lang: 'en', value: 'Shut' }] },
    ]);
    httpTesting
      .expectOne('/rest/dictionaries/dictionary/message/entries')
      .flush([{ key: 'msg.status.DRAFT', descs: [{ lang: 'en', value: 'Draft', asAdded: true }] }]);

    expect(result).toEqual({ 'menu.close': 'Shut' });
  });

  it('a single dictionary failing does not lose the other', () => {
    const { service, httpTesting } = setup();
    let result: Record<string, string> | undefined;
    service.fetch('en').subscribe((r) => (result = r));

    httpTesting
      .expectOne('/rest/dictionaries/dictionary/web/entries')
      .error(new ProgressEvent('error'), { status: 503, statusText: 'Service Unavailable' });
    httpTesting
      .expectOne('/rest/dictionaries/dictionary/message/entries')
      .flush([{ key: 'msg.status.DRAFT', descs: [{ lang: 'en', value: 'Draft' }] }]);

    expect(result).toEqual({ 'msg.status.DRAFT': 'Draft' });
  });
});
