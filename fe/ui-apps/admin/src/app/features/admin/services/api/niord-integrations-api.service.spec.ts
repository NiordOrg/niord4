import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { NiordIntegration, NiordIntegrationsApiService } from './niord-integrations-api.service';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return {
    service: TestBed.inject(NiordIntegrationsApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

const integration = (overrides: Partial<NiordIntegration> = {}): NiordIntegration => ({
  url: 'https://remote.niord',
  active: true,
  assignNewUids: true,
  createBaseData: false,
  messageSeriesMappings: [{ sourceSeriesId: 'remote-nw', targetSeriesId: 'local-nw' }],
  ...overrides,
});

describe('NiordIntegrationsApiService', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('getAll GETs /niord-integrations/all (no lang) and maps mapping rows', () => {
    const { service, httpTesting } = setup();
    let received: NiordIntegration[] | undefined;
    service.getAll().subscribe((rows) => (received = rows));

    const req = httpTesting.expectOne('/rest/niord-integrations/all');
    expect(req.request.method).toBe('GET');
    req.flush([
      {
        id: 1,
        url: 'https://remote.niord',
        active: true,
        assignNewUids: false,
        createBaseData: true,
        messageSeriesMappings: [{ sourceSeriesId: 'remote-nw', targetSeriesId: 'local-nw' }],
      },
    ]);
    expect(received).toEqual([
      {
        id: 1,
        url: 'https://remote.niord',
        active: true,
        assignNewUids: false,
        createBaseData: true,
        messageSeriesMappings: [{ sourceSeriesId: 'remote-nw', targetSeriesId: 'local-nw' }],
      },
    ]);
  });

  it('create POSTs to /niord-integration/ with no path id', () => {
    const { service, httpTesting } = setup();
    service.create(integration()).subscribe();
    const req = httpTesting.expectOne('/rest/niord-integrations/niord-integration/');
    expect(req.request.method).toBe('POST');
    expect((req.request.body as { id?: number }).id).toBeUndefined();
    req.flush({ id: 5 });
  });

  it('update PUTs to the path id derived from the SAME model id as the body', () => {
    const { service, httpTesting } = setup();
    service.update(integration({ id: 9 })).subscribe();
    const req = httpTesting.expectOne('/rest/niord-integrations/niord-integration/9');
    expect(req.request.method).toBe('PUT');
    expect((req.request.body as { id: number }).id).toBe(9);
    req.flush({ id: 9 });
  });

  it('save sends every mapping row verbatim — blank source survives, no pruning', () => {
    const { service, httpTesting } = setup();
    service
      .create(
        integration({
          messageSeriesMappings: [
            { sourceSeriesId: '', targetSeriesId: 'local-nw' },
            { sourceSeriesId: 'remote-nm', targetSeriesId: undefined },
          ],
        }),
      )
      .subscribe();
    const req = httpTesting.expectOne('/rest/niord-integrations/niord-integration/');
    expect(req.request.body.messageSeriesMappings).toEqual([
      { sourceSeriesId: '', targetSeriesId: 'local-nw' },
      { sourceSeriesId: 'remote-nm', targetSeriesId: null },
    ]);
    req.flush({ id: 1 });
  });

  it('delete DELETEs by id', () => {
    const { service, httpTesting } = setup();
    service.delete(3).subscribe();
    const req = httpTesting.expectOne('/rest/niord-integrations/niord-integration/3');
    expect(req.request.method).toBe('DELETE');
    req.flush(null);
  });

  it('execute issues a bodyless PUT to /execute/{id}', () => {
    const { service, httpTesting } = setup();
    service.execute(7).subscribe();
    const req = httpTesting.expectOne('/rest/niord-integrations/execute/7');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toBeNull();
    req.flush(null);
  });
});
