import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import {
  S124Dataset,
  SecomAdminApiService,
  SecomOutboxEntry,
  SecomStatus,
} from './secom-admin-api.service';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
    ],
  });
  return {
    service: TestBed.inject(SecomAdminApiService),
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

describe('SecomAdminApiService', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('getStatus GETs /secom/status without ?lang= and fills in absent fields', () => {
    const { service, httpTesting } = setup();
    let received: SecomStatus | undefined;
    service.getStatus().subscribe((status) => (received = status));

    const req = httpTesting.expectOne('/rest/secom/status');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.has('lang')).toBe(false);
    req.flush({
      enabled: true,
      serviceCertificate: {
        subject: 'CN=node',
        created: 1000,
        ageDays: 5,
        expires: 2000,
        daysToExpiry: 30,
        fingerprint: 'AA:BB',
      },
      // A deliberately sparse payload: no rootCertificate, renewal, mirPortalUrl,
      // trustedCertificates or outboxByStatus.
    });

    expect(received?.serviceCertificate.subject).toBe('CN=node');
    expect(received?.serviceCertificate.daysToExpiry).toBe(30);
    expect(received?.rootCertificate).toEqual({
      subject: '',
      created: undefined,
      ageDays: 0,
      expires: undefined,
      daysToExpiry: 0,
      fingerprint: '',
    });
    expect(received?.renewal).toEqual({
      enabled: false,
      configured: false,
      windowDays: 0,
      renewsAt: undefined,
      renewsInDays: undefined,
    });
    expect(received?.trustedCertificates).toEqual([]);
    expect(received?.scopedSeries).toEqual([]);
    expect(received?.mirPortalUrl).toBe('');
    expect(received?.outboxByStatus).toEqual({});
  });

  it('maps a JSON null date to undefined rather than leaking null into the UI model', () => {
    const { service, httpTesting } = setup();
    let received: SecomOutboxEntry[] | undefined;
    service.getOutbox().subscribe((rows) => (received = rows));

    httpTesting.expectOne('/rest/secom/outbox').flush([
      {
        id: 7,
        transactionId: 'tx-1',
        nodeMrn: 'urn:mrn:peer',
        operation: 'UPLOAD',
        status: 'PENDING',
        attempts: 0,
        nextAttemptAt: null,
        lastError: null,
        datasetMrn: null,
      },
    ]);

    expect(received?.[0].nextAttemptAt).toBeUndefined();
    expect(received?.[0].lastError).toBeUndefined();
    expect(received?.[0].datasetMrn).toBeUndefined();
    expect(received?.[0].attempts).toBe(0);
  });

  it('getOutbox omits the status param entirely when the filter is "all"', () => {
    const { service, httpTesting } = setup();
    service.getOutbox('').subscribe();
    const req = httpTesting.expectOne('/rest/secom/outbox');
    expect(req.request.params.has('status')).toBe(false);
    req.flush([]);
  });

  it('getOutbox sends the selected status', () => {
    const { service, httpTesting } = setup();
    service.getOutbox('FAILED').subscribe();
    const req = httpTesting.expectOne((r) => r.url === '/rest/secom/outbox');
    expect(req.request.params.get('status')).toBe('FAILED');
    req.flush([]);
  });

  it('getSeriesConfigs GETs the exposure rows', () => {
    const { service, httpTesting } = setup();
    let received: unknown;
    service.getSeriesConfigs().subscribe((rows) => (received = rows));
    httpTesting
      .expectOne('/rest/secom/series-config')
      .flush([
        { seriesId: 'dma-nw', exposeOverSecom: true, languages: ['da', 'en'] },
        { seriesId: 'dma-nm' },
      ]);
    expect(received).toEqual([
      { seriesId: 'dma-nw', exposeOverSecom: true, languages: ['da', 'en'] },
      // No languages in the row → no restriction, never null.
      { seriesId: 'dma-nm', exposeOverSecom: false, languages: [] },
    ]);
  });

  it('updateSeriesConfig PUTs to the URL-encoded series id with a matching body', () => {
    const { service, httpTesting } = setup();
    service.updateSeriesConfig('dma nw/1', true, []).subscribe();
    const req = httpTesting.expectOne('/rest/secom/series-config/dma%20nw%2F1');
    expect(req.request.method).toBe('PUT');
    // The languages always travel with the row: the PUT replaces it whole.
    expect(req.request.body).toEqual({
      seriesId: 'dma nw/1',
      exposeOverSecom: true,
      languages: [],
    });
    req.flush({ seriesId: 'dma nw/1', exposeOverSecom: true });
  });

  it('updateSeriesConfig sends the language restriction and maps the echoed canonical list', () => {
    const { service, httpTesting } = setup();
    let received: unknown;
    service.updateSeriesConfig('dma-nw', true, ['en', 'da']).subscribe((row) => (received = row));
    const req = httpTesting.expectOne('/rest/secom/series-config/dma-nw');
    expect(req.request.body).toEqual({
      seriesId: 'dma-nw',
      exposeOverSecom: true,
      languages: ['en', 'da'],
    });
    req.flush({ seriesId: 'dma-nw', exposeOverSecom: true, languages: ['da', 'en'] });
    expect(received).toEqual({
      seriesId: 'dma-nw',
      exposeOverSecom: true,
      languages: ['da', 'en'],
    });
  });

  it('security actions are bodyless POSTs', () => {
    const { service, httpTesting } = setup();
    service.reloadSecurity().subscribe();
    const reload = httpTesting.expectOne('/rest/secom/security/reload');
    expect(reload.request.method).toBe('POST');
    expect(reload.request.body).toBeNull();
    reload.flush({ serviceCertificateSubject: 'CN=node' });

    service.renewCertificates().subscribe();
    const renew = httpTesting.expectOne('/rest/secom/security/renew');
    expect(renew.request.method).toBe('POST');
    renew.flush({ renewed: [{ credential: 'service', mrn: 'urn:mrn:node' }], failures: [] });
  });

  it('renewCertificates defaults an absent renewed/failures list to empty arrays', () => {
    const { service, httpTesting } = setup();
    let received: unknown;
    service.renewCertificates().subscribe((result) => (received = result));
    httpTesting.expectOne('/rest/secom/security/renew').flush({});
    expect(received).toEqual({ renewed: [], failures: [] });
  });

  it('getDatasetGml fetches TEXT, not JSON (the endpoint serves application/gml+xml)', () => {
    const { service, httpTesting } = setup();
    let received: string | undefined;
    service.getDatasetGml('abc-123').subscribe((gml) => (received = gml));
    const req = httpTesting.expectOne('/rest/secom/datasets/abc-123/gml');
    expect(req.request.responseType).toBe('text');
    req.flush('<S124:DataSet/>');
    expect(received).toBe('<S124:DataSet/>');
  });

  it('republishDataset POSTs and resolves to the enqueued delivery count', () => {
    const { service, httpTesting } = setup();
    let received: number | undefined;
    service.republishDataset('abc-123').subscribe((count) => (received = count));
    const req = httpTesting.expectOne('/rest/secom/datasets/abc-123/republish');
    expect(req.request.method).toBe('POST');
    req.flush(3);
    expect(received).toBe(3);
  });

  it('reconcile POSTs and maps the five counters', () => {
    const { service, httpTesting } = setup();
    let received: unknown;
    service.reconcile().subscribe((result) => (received = result));
    const req = httpTesting.expectOne('/rest/secom/datasets/reconcile');
    expect(req.request.method).toBe('POST');
    req.flush({ created: 2, updated: 1, cancelled: 0, unchanged: 9, failed: 1 });
    expect(received).toEqual({ created: 2, updated: 1, cancelled: 0, unchanged: 9, failed: 1 });
  });

  it('reconcile maps a missing failed counter to 0', () => {
    const { service, httpTesting } = setup();
    let received: unknown;
    service.reconcile().subscribe((result) => (received = result));
    httpTesting
      .expectOne('/rest/secom/datasets/reconcile')
      .flush({ created: 2, updated: 1, cancelled: 0, unchanged: 9 });
    expect(received).toEqual({ created: 2, updated: 1, cancelled: 0, unchanged: 9, failed: 0 });
  });

  it('deleteSubscription DELETEs the URL-encoded uuid', () => {
    const { service, httpTesting } = setup();
    service.deleteSubscription('uuid/1').subscribe();
    const req = httpTesting.expectOne('/rest/secom/subscriptions/uuid%2F1');
    expect(req.request.method).toBe('DELETE');
    req.flush({ uuid: 'uuid/1', nodeMrn: 'urn:mrn:peer' });
  });

  it('outbox row actions use the entry id: POST retry, DELETE cancel', () => {
    const { service, httpTesting } = setup();
    service.retryOutboxEntry(42).subscribe();
    const retry = httpTesting.expectOne('/rest/secom/outbox/42/retry');
    expect(retry.request.method).toBe('POST');
    retry.flush(42);

    service.cancelOutboxEntry(42).subscribe();
    const cancel = httpTesting.expectOne('/rest/secom/outbox/42');
    expect(cancel.request.method).toBe('DELETE');
    cancel.flush(42);
  });

  it('getDatasets maps the instance rows', () => {
    const { service, httpTesting } = setup();
    let received: S124Dataset[] | undefined;
    service.getDatasets().subscribe((rows) => (received = rows));
    httpTesting.expectOne('/rest/secom/datasets').flush([
      {
        id: 1,
        uuid: 'u-1',
        mrn: 'urn:mrn:s124:1',
        messageUid: 'msg-uid',
        messageShortId: 'NW-001-26',
        status: 'ACTIVE',
        validFrom: 100,
        validTo: 200,
        created: 50,
        updated: 60,
        gmlSize: 1024,
      },
    ]);
    expect(received?.[0].messageShortId).toBe('NW-001-26');
    expect(received?.[0].status).toBe('ACTIVE');
    expect(received?.[0].gmlSize).toBe(1024);
  });
});
