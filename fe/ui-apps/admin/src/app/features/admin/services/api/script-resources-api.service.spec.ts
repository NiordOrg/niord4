import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { ScriptResource, ScriptResourcesApiService } from './script-resources-api.service';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return {
    service: TestBed.inject(ScriptResourcesApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('ScriptResourcesApiService', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('lists with NO query params by default', () => {
    const { service, httpTesting } = setup();
    service.getAll().subscribe();
    const req = httpTesting.expectOne((r) => r.url === '/rest/script-resources/all');
    expect(req.request.params.keys().length).toBe(0);
    req.flush([]);
  });

  it('appends only the type (no path: the list never asks the server to load a resource)', () => {
    const { service, httpTesting } = setup();
    service.getAll('FM').subscribe();
    const req = httpTesting.expectOne((r) => r.url === '/rest/script-resources/all');
    expect(req.request.params.get('type')).toBe('FM');
    expect(req.request.params.has('path')).toBe(false);
    req.flush([]);
  });

  it('PUTs on the numeric id, not the path', () => {
    const { service, httpTesting } = setup();
    const resource: ScriptResource = { id: 42, type: 'JS', path: 'x.js', content: '' };
    service.update(resource).subscribe();
    const req = httpTesting.expectOne('/rest/script-resources/script-resource/42');
    expect(req.request.method).toBe('PUT');
    req.flush({});
  });

  it('fetches history keyed on the id', () => {
    const { service, httpTesting } = setup();
    service.getHistory(7).subscribe();
    httpTesting.expectOne('/rest/script-resources/script-resource/7/history').flush([]);
  });

  it('exposes bare export + upload URLs (the ticket/role is appended by the download service)', () => {
    const { service } = setup();
    expect(service.exportUrl()).toBe('/rest/script-resources/all');
    expect(service.uploadUrl()).toBe('/rest/script-resources/upload-script-resources');
  });

  it('maps the history source; an unknown or missing source is undefined', () => {
    const { service, httpTesting } = setup();
    let received: unknown;
    service.getHistory(7).subscribe((history) => (received = history));
    httpTesting.expectOne('/rest/script-resources/script-resource/7/history').flush([
      { version: 3, created: 3, user: 'sys', source: 'import', snapshot: '{}' },
      { version: 2, created: 2, source: 'editor', snapshot: '{}' },
      { version: 1, created: 1, source: 'later', snapshot: '{}' },
      { version: 0, created: 0, user: null, source: null, snapshot: '{}' },
    ]);
    expect((received as { source?: string }[]).map((entry) => entry.source)).toEqual([
      'import',
      'editor',
      undefined,
      undefined,
    ]);
  });

  it('previews an import: the file as the multipart part "file", the answer normalised', () => {
    const { service, httpTesting } = setup();
    const file = new File(['[]'], 'script-resources.json', { type: 'application/json' });
    let received: unknown;
    service.previewImport(file).subscribe((preview) => (received = preview));
    const req = httpTesting.expectOne('/rest/script-resources/import-preview');
    expect(req.request.method).toBe('POST');
    const body = req.request.body as FormData;
    expect(body.get('file')).toBe(file);
    expect(body.has('add')).toBe(false);
    expect(body.has('overwrite')).toBe(false);
    req.flush({
      add: [{ path: 'templates/a.ftl', type: 'FM', content: 'A', listed: true }],
      overwrite: [
        {
          path: 'templates/b.js',
          type: 'JS',
          current: 'old',
          incoming: 'new',
          basedOn: 'sha256:abc123',
        },
      ],
      unchanged: ['templates/c.ftl'],
      refused: [{ path: 'templates/d.js', reason: 'uses Java.type' }],
    });
    expect(received).toEqual({
      add: [{ path: 'templates/a.ftl', type: 'FM', content: 'A', listed: true }],
      overwrite: [
        {
          path: 'templates/b.js',
          type: 'JS',
          current: 'old',
          incoming: 'new',
          basedOn: 'sha256:abc123',
        },
      ],
      unchanged: ['templates/c.ftl'],
      refused: [{ path: 'templates/d.js', reason: 'uses Java.type' }],
    });
  });

  it('reads a preview with absent lists and flags as empty lists and unlisted', () => {
    const { service, httpTesting } = setup();
    let received: unknown;
    service.previewImport(new File(['[]'], 'x.json')).subscribe((preview) => (received = preview));
    httpTesting
      .expectOne('/rest/script-resources/import-preview')
      .flush({ add: [{ path: 'p.ftl', type: 'FM' }], refused: null });
    expect(received).toEqual({
      add: [{ path: 'p.ftl', type: 'FM', content: '', listed: false }],
      overwrite: [],
      unchanged: [],
      refused: [],
    });
  });

  it('imports with the ticked adds and overwrites as JSON arrays, answering text', () => {
    const { service, httpTesting } = setup();
    const file = new File(['[]'], 'script-resources.json', { type: 'application/json' });
    let received: unknown;
    service
      .importFile(file, ['templates/a.ftl', 'templates/b.ftl'], [])
      .subscribe((text) => (received = text));
    const req = httpTesting.expectOne('/rest/script-resources/upload-script-resources');
    expect(req.request.method).toBe('POST');
    expect(req.request.responseType).toBe('text');
    const body = req.request.body as FormData;
    expect(body.get('file')).toBe(file);
    expect(JSON.parse(body.get('add') as string)).toEqual(['templates/a.ftl', 'templates/b.ftl']);
    // Both fields are always sent, empty as `[]`: an absent `add` means every missing path.
    expect(body.get('overwrite')).toBe('[]');
    req.flush("Started batch job 'script-resource-import' with file script-resources.json");
    expect(received).toContain('script-resource-import');
  });

  it("sends a ticked overwrite as {path, basedOn} with the preview's hash, and as the bare path without one", () => {
    const { service, httpTesting } = setup();
    service
      .importFile(
        new File(['[]'], 'x.json'),
        ['templates/a.ftl'],
        [{ path: 'templates/b.js', basedOn: 'sha256:abc123' }, { path: 'templates/c.ftl' }],
      )
      .subscribe();
    const req = httpTesting.expectOne('/rest/script-resources/upload-script-resources');
    const body = req.request.body as FormData;
    expect(JSON.parse(body.get('overwrite') as string)).toEqual([
      { path: 'templates/b.js', basedOn: 'sha256:abc123' },
      'templates/c.ftl',
    ]);
    expect(JSON.parse(body.get('add') as string)).toEqual(['templates/a.ftl']);
    req.flush('');
  });
});
