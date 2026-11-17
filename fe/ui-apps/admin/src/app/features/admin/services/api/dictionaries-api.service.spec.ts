import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { DictionariesApiService } from './dictionaries-api.service';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return {
    service: TestBed.inject(DictionariesApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('DictionariesApiService', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('fetches entries without ANY lang param (all-language payload)', () => {
    const { service, httpTesting } = setup();
    service.getDictionaryEntries('web').subscribe();
    const req = httpTesting.expectOne('/rest/dictionaries/dictionary/web/entries');
    expect(req.request.params.keys().length).toBe(0);
    req.flush([]);
  });

  it('URL-encodes dictionary names — a comma must NOT reach the path raw', () => {
    const { service, httpTesting } = setup();
    service.getDictionaryEntries('web,mail').subscribe();
    httpTesting.expectOne('/rest/dictionaries/dictionary/web%2Cmail/entries').flush([]);
    // The export URL gets the same treatment — an un-encoded comma would flip
    // the backend into multi-dictionary export.
    expect(service.exportUrl('web,mail')).toBe('/rest/dictionaries/dictionary/web%2Cmail.json');
  });

  it('updates PUT to name + encoded key; deletes DELETE the same shape', () => {
    const { service, httpTesting } = setup();
    service.updateEntry('web', { key: 'a b/c', descs: [] }).subscribe();
    const put = httpTesting.expectOne('/rest/dictionaries/dictionary/web/a%20b%2Fc');
    expect(put.request.method).toBe('PUT');
    put.flush({});

    service.deleteEntry('web', 'a b/c').subscribe();
    const del = httpTesting.expectOne('/rest/dictionaries/dictionary/web/a%20b%2Fc');
    expect(del.request.method).toBe('DELETE');
    del.flush({});
  });

  it('asks key-users with the name in the path and the key as an encoded query parameter', () => {
    const { service, httpTesting } = setup();
    service.getKeyUsers('web,mail', 'a b.c&d/e+f;g').subscribe();
    const req = httpTesting.expectOne(
      (r) => r.url === '/rest/dictionaries/dictionary/web%2Cmail/key-users',
    );
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('key')).toBe('a b.c&d/e+f;g');
    // Every reserved character is escaped — '+' too, which the backend would read as a space.
    expect(req.request.urlWithParams).toBe(
      '/rest/dictionaries/dictionary/web%2Cmail/key-users?key=a%20b.c%26d%2Fe%2Bf%3Bg',
    );
    req.flush({ templates: [], readers: [] });
  });

  it('maps key users: keeps templates, prefix and parameter types, an unknown reader id becomes OTHER', () => {
    const { service, httpTesting } = setup();
    let received: unknown;
    service.getKeyUsers('message', 'msg.status.DRAFT').subscribe((users) => (received = users));
    httpTesting
      .expectOne((r) => r.url === '/rest/dictionaries/dictionary/message/key-users')
      .flush({
        templates: ['templates/a.ftl', 'templates/message-support.ftl'],
        prefix: 'msg.status.',
        readers: ['AUDIO_TEXT', 'SOMETHING_NEWER'],
        paramTypes: ['lights', 'status'],
      });
    expect(received).toEqual({
      templates: ['templates/a.ftl', 'templates/message-support.ftl'],
      prefix: 'msg.status.',
      readers: ['AUDIO_TEXT', 'OTHER'],
      paramTypes: ['lights', 'status'],
    });
  });

  it('maps key users without prefix or lists (an older backend sends no paramTypes) to empty lists and no prefix', () => {
    const { service, httpTesting } = setup();
    let received: unknown;
    service.getKeyUsers('web', 'k').subscribe((users) => (received = users));
    httpTesting
      .expectOne((r) => r.url === '/rest/dictionaries/dictionary/web/key-users')
      .flush({ templates: null });
    expect(received).toEqual({ templates: [], prefix: undefined, readers: [], paramTypes: [] });
  });

  it('previews an import: the file as the multipart part "file", the answer normalised', () => {
    const { service, httpTesting } = setup();
    const file = new File(['[]'], 'texts.json', { type: 'application/json' });
    let received: unknown;
    service.previewImport(file).subscribe((preview) => (received = preview));
    const req = httpTesting.expectOne('/rest/dictionaries/import-preview');
    expect(req.request.method).toBe('POST');
    const body = req.request.body as FormData;
    expect(body.get('file')).toBe(file);
    expect(body.has('add')).toBe(false);
    req.flush({
      add: [
        { dictionary: 'web', key: 'a', values: { en: 'A' }, deleted: false },
        {
          dictionary: 'web',
          key: 'b',
          atonFilter: 'x',
          values: { en: 'B' },
          longValues: { en: 'Bee' },
          deleted: true,
        },
      ],
      overwrite: [
        {
          dictionary: 'mail',
          key: 'c',
          current: { values: { en: 'old', da: 'gammel' } },
          incoming: { atonFilter: null, values: { en: 'new' }, longValues: null },
          basedOn: 'sha256:abc123',
        },
      ],
      unchanged: 12,
      refused: [{ dictionary: 'web', key: null, reason: 'blank key' }],
    });
    expect(received).toEqual({
      add: [
        {
          dictionary: 'web',
          key: 'a',
          atonFilter: undefined,
          values: { en: 'A' },
          longValues: undefined,
          deleted: false,
        },
        {
          dictionary: 'web',
          key: 'b',
          atonFilter: 'x',
          values: { en: 'B' },
          longValues: { en: 'Bee' },
          deleted: true,
        },
      ],
      overwrite: [
        {
          dictionary: 'mail',
          key: 'c',
          current: {
            atonFilter: undefined,
            values: { en: 'old', da: 'gammel' },
            longValues: undefined,
          },
          incoming: { atonFilter: undefined, values: { en: 'new' }, longValues: undefined },
          basedOn: 'sha256:abc123',
        },
      ],
      unchanged: 12,
      refused: [{ dictionary: 'web', key: '', reason: 'blank key' }],
    });
  });

  it('reads a preview with absent lists as empty lists and no unchanged key', () => {
    const { service, httpTesting } = setup();
    let received: unknown;
    service.previewImport(new File(['[]'], 'x.json')).subscribe((preview) => (received = preview));
    httpTesting.expectOne('/rest/dictionaries/import-preview').flush({});
    expect(received).toEqual({ add: [], overwrite: [], unchanged: 0, refused: [] });
  });

  it('imports with the ticked keys as JSON arrays of {dictionary, key}, answering text', () => {
    const { service, httpTesting } = setup();
    const file = new File(['[]'], 'texts.json', { type: 'application/json' });
    let received: unknown;
    service
      .importFile(file, [{ dictionary: 'web', key: 'a' }], [])
      .subscribe((text) => (received = text));
    const req = httpTesting.expectOne('/rest/dictionaries/upload-dictionaries');
    expect(req.request.method).toBe('POST');
    expect(req.request.responseType).toBe('text');
    const body = req.request.body as FormData;
    expect(body.get('file')).toBe(file);
    expect(JSON.parse(body.get('add') as string)).toEqual([{ dictionary: 'web', key: 'a' }]);
    // Both fields are always sent, empty as `[]`: an absent `add` means every missing key.
    expect(body.get('overwrite')).toBe('[]');
    req.flush("Started batch job 'dictionary-import' with file texts.json");
    expect(received).toContain('dictionary-import');
  });

  it('sends only the dictionary and key of each ticked entry', () => {
    const { service, httpTesting } = setup();
    const entry = {
      dictionary: 'web',
      key: 'b',
      values: { en: 'B' },
      deleted: true,
    };
    service.importFile(new File(['[]'], 'x.json'), [], [entry]).subscribe();
    const req = httpTesting.expectOne('/rest/dictionaries/upload-dictionaries');
    expect((req.request.body as FormData).get('overwrite')).toBe(
      '[{"dictionary":"web","key":"b"}]',
    );
    req.flush('');
  });

  it("sends a ticked overwrite's basedOn with its key, and none where the preview gave none", () => {
    const { service, httpTesting } = setup();
    service
      .importFile(
        new File(['[]'], 'x.json'),
        [{ dictionary: 'web', key: 'a' }],
        [
          { dictionary: 'mail', key: 'c', basedOn: 'sha256:abc123' },
          { dictionary: 'mail', key: 'd' },
        ],
      )
      .subscribe();
    const req = httpTesting.expectOne('/rest/dictionaries/upload-dictionaries');
    const body = req.request.body as FormData;
    expect(JSON.parse(body.get('overwrite') as string)).toEqual([
      { dictionary: 'mail', key: 'c', basedOn: 'sha256:abc123' },
      { dictionary: 'mail', key: 'd' },
    ]);
    // An add carries no basedOn: there is no row to compare.
    expect(body.get('add')).toBe('[{"dictionary":"web","key":"a"}]');
    req.flush('');
  });

  it('normalizes the wire shape: null value → "", null longValue stays DEFINED as ""', () => {
    const { service, httpTesting } = setup();
    let received: unknown;
    service.getDictionaryEntries('web').subscribe((entries) => (received = entries));
    httpTesting.expectOne('/rest/dictionaries/dictionary/web/entries').flush([
      { id: 1, key: 'k1', descs: [{ lang: 'en', value: null, longValue: null }] },
      { id: 2, key: 'k2', descs: [{ lang: 'en', value: 'v' }] },
    ]);
    const [first, second] = received as {
      descs: { value: string; longValue?: string }[];
    }[];
    expect(first.descs[0].value).toBe('');
    expect(first.descs[0].longValue).toBe(''); // defined-empty, drives extended columns
    expect(second.descs[0].longValue).toBeUndefined(); // absent stays absent
  });
});
