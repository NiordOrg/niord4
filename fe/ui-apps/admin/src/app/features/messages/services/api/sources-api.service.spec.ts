import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { SourcesApiService } from './sources-api.service';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
    ],
  });
  return {
    service: TestBed.inject(SourcesApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('SourcesApiService', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('the LIST fetch carries lang; rows are normalized (null descs → [])', () => {
    const { service, httpTesting } = setup();
    let received: unknown;
    service.getAll('da').subscribe((sources) => (received = sources));
    const req = httpTesting.expectOne('/rest/sources/all?lang=da');
    req.flush([{ id: 1, active: true, descs: null }]);
    expect(received).toEqual([{ id: 1, active: true, descs: [] }]);
  });

  it('the LIST fetch preserves each source active flag (active-only filtering is client-side)', () => {
    const { service, httpTesting } = setup();
    let received: readonly { id?: number; active: boolean }[] | undefined;
    service.getAll('en').subscribe((sources) => (received = sources));
    const req = httpTesting.expectOne('/rest/sources/all?lang=en');
    req.flush([
      { id: 1, active: true, descs: [] },
      { id: 2, active: false, descs: [] },
    ]);
    expect(received?.map((s) => s.active)).toEqual([true, false]);
  });

  it('the DETAILS fetch carries NO lang param (all-language descs)', () => {
    const { service, httpTesting } = setup();
    service.getSource(7).subscribe();
    const req = httpTesting.expectOne('/rest/sources/source/7');
    expect(req.request.params.keys().length).toBe(0);
    req.flush({ id: 7, active: true, descs: [] });
  });

  it('update PUTs to the path id derived from the SAME model id as the body', () => {
    const { service, httpTesting } = setup();
    service.update({ id: 9, active: false, descs: [] }).subscribe();
    const req = httpTesting.expectOne('/rest/sources/source/9');
    expect(req.request.method).toBe('PUT');
    expect((req.request.body as { id: number }).id).toBe(9);
    req.flush({ id: 9, active: false, descs: [] });
  });

  it('the export URL is the bare PermitAll list endpoint — no lang, no ticket', () => {
    const { service } = setup();
    expect(service.exportUrl()).toBe('/rest/sources/all');
  });
});
