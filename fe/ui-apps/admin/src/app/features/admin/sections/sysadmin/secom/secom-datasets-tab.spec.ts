import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { S124Dataset } from '../../../services/api/secom-admin-api.service';
import { SecomDatasetsTab } from './secom-datasets-tab';
import { SecomFacade, SecomSeriesExposure } from './secom.facade';
import { provideTestLanguages } from '../../../../../shared/testing/language-testing';

const SERIES_URL = '/rest/message-series/all?messageNumbers=true';

interface TabInternals {
  seriesRows: () => readonly SecomSeriesExposure[];
  isSavingSeries: (seriesId: string) => boolean;
  isRepublishing: (uuid: string) => boolean;
  toggleSeries: (row: SecomSeriesExposure, expose: boolean) => void;
  toggleLanguage: (row: SecomSeriesExposure, lang: string, selected: boolean) => void;
  hasLanguage: (row: SecomSeriesExposure, lang: string) => boolean;
  reconcile: () => void;
  republish: (dataset: S124Dataset) => void;
  datasetRows: () => readonly S124Dataset[];
}

function setup(notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() }) {
  TestBed.configureTestingModule({
    providers: [
      provideTestLanguages(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideRouter([]),
      { provide: NotificationService, useValue: notifications },
      // The tab reads the page-scoped facade; provide it directly here.
      SecomFacade,
    ],
  });
  const fixture = TestBed.createComponent(SecomDatasetsTab);
  fixture.detectChanges();
  return {
    fixture,
    tab: fixture.componentInstance as unknown as TabInternals,
    facade: TestBed.inject(SecomFacade),
    httpTesting: TestBed.inject(HttpTestingController),
    notifications,
  };
}

function flushInitial(
  httpTesting: HttpTestingController,
  series: { seriesId: string; mainType?: string }[] = [],
  configs: { seriesId: string; exposeOverSecom: boolean; languages?: string[] }[] = [],
  datasets: unknown[] = [],
): void {
  httpTesting.expectOne(SERIES_URL).flush(series);
  httpTesting.expectOne('/rest/secom/series-config').flush(configs);
  httpTesting.expectOne('/rest/secom/status').flush({ enabled: true });
  httpTesting.expectOne('/rest/secom/subscriptions').flush([]);
  httpTesting.expectOne('/rest/secom/nodes').flush([]);
  httpTesting.expectOne('/rest/secom/datasets').flush(datasets);
  httpTesting.expectOne('/rest/secom/outbox').flush([]);
}

const dataset = (uuid: string): S124Dataset => ({
  uuid,
  mrn: `urn:mrn:s124:${uuid}`,
  messageUid: 'msg',
  status: 'ACTIVE',
  gmlSize: 10,
});

describe('SecomDatasetsTab', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('ticking a series PUTs the exposure and keeps the tick without reloading the list', () => {
    const { tab, httpTesting, notifications } = setup();
    flushInitial(httpTesting, [{ seriesId: 'dma-nw', mainType: 'NW' }], []);
    expect(tab.seriesRows()).toEqual([
      { seriesId: 'dma-nw', exposeOverSecom: false, languages: [] },
    ]);

    tab.toggleSeries({ seriesId: 'dma-nw', exposeOverSecom: false, languages: [] }, true);
    expect(tab.isSavingSeries('dma-nw')).toBe(true);

    const req = httpTesting.expectOne('/rest/secom/series-config/dma-nw');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ seriesId: 'dma-nw', exposeOverSecom: true, languages: [] });
    req.flush({ seriesId: 'dma-nw', exposeOverSecom: true });

    expect(tab.isSavingSeries('dma-nw')).toBe(false);
    expect(tab.seriesRows()).toEqual([
      { seriesId: 'dma-nw', exposeOverSecom: true, languages: [] },
    ]);
    TestBed.tick();
    expect(notifications.success).toHaveBeenCalledWith('admin.secom.toast.seriesUpdated', {
      seriesId: 'dma-nw',
    });
    // The exposed-series line on Overview moves with it.
    httpTesting.expectOne('/rest/secom/status').flush({ enabled: true });
  });

  it('a rejected toggle reverts the tick and reloads the exposure list', () => {
    const { tab, httpTesting, notifications } = setup();
    flushInitial(httpTesting, [{ seriesId: 'dma-nw', mainType: 'NW' }], []);

    tab.toggleSeries({ seriesId: 'dma-nw', exposeOverSecom: false, languages: [] }, true);
    httpTesting
      .expectOne('/rest/secom/series-config/dma-nw')
      .flush('nope', { status: 500, statusText: 'Server Error' });

    expect(notifications.error).toHaveBeenCalledWith('admin.secom.toast.seriesUpdateError', {
      seriesId: 'dma-nw',
    });
    expect(tab.isSavingSeries('dma-nw')).toBe(false);
    // Reverted to the server's truth, which is re-fetched.
    expect(tab.seriesRows()).toEqual([
      { seriesId: 'dma-nw', exposeOverSecom: false, languages: [] },
    ]);
    TestBed.tick();
    httpTesting.expectOne(SERIES_URL).flush([{ seriesId: 'dma-nw', mainType: 'NW' }]);
    httpTesting.expectOne('/rest/secom/series-config').flush([]);
  });

  it('reconcile reports the five counters and refreshes datasets, outbox and status', () => {
    const { tab, httpTesting, notifications } = setup();
    flushInitial(httpTesting);

    tab.reconcile();
    httpTesting
      .expectOne('/rest/secom/datasets/reconcile')
      .flush({ created: 2, updated: 1, cancelled: 0, unchanged: 7, failed: 1 });

    TestBed.tick();
    expect(notifications.info).toHaveBeenCalledWith('admin.secom.toast.reconciled', {
      created: 2,
      updated: 1,
      cancelled: 0,
      unchanged: 7,
      failed: 1,
    });
    httpTesting.expectOne('/rest/secom/datasets').flush([]);
    httpTesting.expectOne('/rest/secom/outbox').flush([]);
    httpTesting.expectOne('/rest/secom/status').flush({ enabled: true });
  });

  it('a failed reconcile toasts and does not refresh', () => {
    const { tab, httpTesting, notifications } = setup();
    flushInitial(httpTesting);

    tab.reconcile();
    httpTesting
      .expectOne('/rest/secom/datasets/reconcile')
      .flush('nope', { status: 500, statusText: 'Server Error' });

    expect(notifications.error).toHaveBeenCalledWith('admin.secom.toast.reconcileError');
  });

  it('republish reports the enqueued delivery count and refreshes the outbox', () => {
    const { tab, httpTesting, notifications } = setup();
    flushInitial(httpTesting, [], [], [dataset('u-1')]);

    tab.republish(dataset('u-1'));
    httpTesting.expectOne('/rest/secom/datasets/u-1/republish').flush(3);

    TestBed.tick();
    expect(notifications.info).toHaveBeenCalledWith('admin.secom.toast.republished', { count: 3 });
    httpTesting.expectOne('/rest/secom/outbox').flush([]);
    httpTesting.expectOne('/rest/secom/status').flush({ enabled: true });
  });

  it('a repeat republish click while the POST is in flight is DROPPED (no duplicate deliveries)', () => {
    const { tab, httpTesting, notifications } = setup();
    flushInitial(httpTesting, [], [], [dataset('u-1')]);

    tab.republish(dataset('u-1'));
    expect(tab.isRepublishing('u-1')).toBe(true);

    // The impatient second and third clicks must not reach the backend: republish
    // is not idempotent, so each extra call enqueues another delivery to EVERY
    // matching subscription.
    tab.republish(dataset('u-1'));
    tab.republish(dataset('u-1'));
    httpTesting.expectOne('/rest/secom/datasets/u-1/republish').flush(3);

    TestBed.tick();
    expect(tab.isRepublishing('u-1')).toBe(false);
    expect(notifications.info).toHaveBeenCalledTimes(1);
    httpTesting.expectOne('/rest/secom/outbox').flush([]);
    httpTesting.expectOne('/rest/secom/status').flush({ enabled: true });
  });

  it('the republish guard is per dataset — one in-flight row never blocks another', () => {
    const { tab, httpTesting } = setup();
    flushInitial(httpTesting, [], [], [dataset('u-1'), dataset('u-2')]);

    tab.republish(dataset('u-1'));
    tab.republish(dataset('u-2'));
    expect(tab.isRepublishing('u-1')).toBe(true);
    expect(tab.isRepublishing('u-2')).toBe(true);

    httpTesting.expectOne('/rest/secom/datasets/u-1/republish').flush(1);
    httpTesting.expectOne('/rest/secom/datasets/u-2/republish').flush(2);
    TestBed.tick();

    expect(tab.isRepublishing('u-1')).toBe(false);
    expect(tab.isRepublishing('u-2')).toBe(false);
    httpTesting.match('/rest/secom/outbox').forEach((req) => req.flush([]));
    httpTesting.match('/rest/secom/status').forEach((req) => req.flush({ enabled: true }));
  });

  it('a failed republish releases the guard so the operator can retry', () => {
    const { tab, httpTesting, notifications } = setup();
    flushInitial(httpTesting, [], [], [dataset('u-1')]);

    tab.republish(dataset('u-1'));
    httpTesting
      .expectOne('/rest/secom/datasets/u-1/republish')
      .flush('nope', { status: 500, statusText: 'Server Error' });

    expect(tab.isRepublishing('u-1')).toBe(false);
    expect(notifications.error).toHaveBeenCalledWith('admin.secom.toast.republishError');

    // Retry now goes through.
    tab.republish(dataset('u-1'));
    httpTesting.expectOne('/rest/secom/datasets/u-1/republish').flush(1);
    TestBed.tick();
    httpTesting.expectOne('/rest/secom/outbox').flush([]);
    httpTesting.expectOne('/rest/secom/status').flush({ enabled: true });
  });

  it('a repeat series toggle while the PUT is in flight is dropped', () => {
    const { tab, httpTesting } = setup();
    flushInitial(httpTesting, [{ seriesId: 'dma-nw', mainType: 'NW' }], []);

    const row = { seriesId: 'dma-nw', exposeOverSecom: false, languages: [] };
    tab.toggleSeries(row, true);
    tab.toggleSeries(row, true);
    // A language click on the same row while its PUT is in flight is dropped too:
    // the row is saved whole, so a queued second PUT would carry a stale copy.
    tab.toggleLanguage(row, 'da', true);
    httpTesting
      .expectOne('/rest/secom/series-config/dma-nw')
      .flush({ seriesId: 'dma-nw', exposeOverSecom: true });

    TestBed.tick();
    httpTesting.expectOne('/rest/secom/status').flush({ enabled: true });
  });

  it('ticking a language PUTs the whole row and shows the languages as the backend echoes them', () => {
    const { tab, httpTesting, notifications } = setup();
    flushInitial(
      httpTesting,
      [{ seriesId: 'dma-nw', mainType: 'NW' }],
      [{ seriesId: 'dma-nw', exposeOverSecom: true, languages: ['en'] }],
    );
    const [row] = tab.seriesRows();
    expect(row).toEqual({ seriesId: 'dma-nw', exposeOverSecom: true, languages: ['en'] });
    expect(tab.hasLanguage(row, 'en')).toBe(true);
    expect(tab.hasLanguage(row, 'da')).toBe(false);

    tab.toggleLanguage(row, 'da', true);
    expect(tab.isSavingSeries('dma-nw')).toBe(true);

    const req = httpTesting.expectOne('/rest/secom/series-config/dma-nw');
    expect(req.request.method).toBe('PUT');
    // Sent in the deployment's language order (EN, DA in the default build), with
    // the exposure flag unchanged.
    expect(req.request.body).toEqual({
      seriesId: 'dma-nw',
      exposeOverSecom: true,
      languages: ['en', 'da'],
    });
    // The backend stores model-language order and echoes it; the row follows.
    req.flush({ seriesId: 'dma-nw', exposeOverSecom: true, languages: ['da', 'en'] });

    expect(tab.isSavingSeries('dma-nw')).toBe(false);
    expect(tab.seriesRows()).toEqual([
      { seriesId: 'dma-nw', exposeOverSecom: true, languages: ['da', 'en'] },
    ]);
    TestBed.tick();
    expect(notifications.success).toHaveBeenCalledWith('admin.secom.toast.seriesUpdated', {
      seriesId: 'dma-nw',
    });
    httpTesting.expectOne('/rest/secom/status').flush({ enabled: true });
  });

  it('unticking the last language sends an empty list, i.e. no restriction', () => {
    const { tab, httpTesting } = setup();
    flushInitial(
      httpTesting,
      [{ seriesId: 'dma-nw', mainType: 'NW' }],
      [{ seriesId: 'dma-nw', exposeOverSecom: false, languages: ['da'] }],
    );

    tab.toggleLanguage(tab.seriesRows()[0], 'da', false);
    const req = httpTesting.expectOne('/rest/secom/series-config/dma-nw');
    expect(req.request.body).toEqual({ seriesId: 'dma-nw', exposeOverSecom: false, languages: [] });
    req.flush({ seriesId: 'dma-nw', exposeOverSecom: false, languages: [] });

    expect(tab.seriesRows()).toEqual([
      { seriesId: 'dma-nw', exposeOverSecom: false, languages: [] },
    ]);
    TestBed.tick();
    httpTesting.expectOne('/rest/secom/status').flush({ enabled: true });
  });

  it('a rejected language change reverts the row and reloads the exposure list', () => {
    const { tab, httpTesting, notifications } = setup();
    flushInitial(
      httpTesting,
      [{ seriesId: 'dma-nw', mainType: 'NW' }],
      [{ seriesId: 'dma-nw', exposeOverSecom: true, languages: ['en'] }],
    );

    tab.toggleLanguage(tab.seriesRows()[0], 'da', true);
    httpTesting
      .expectOne('/rest/secom/series-config/dma-nw')
      .flush('not a model language', { status: 400, statusText: 'Bad Request' });

    expect(notifications.error).toHaveBeenCalledWith('admin.secom.toast.seriesUpdateError', {
      seriesId: 'dma-nw',
    });
    expect(tab.isSavingSeries('dma-nw')).toBe(false);
    TestBed.tick();
    httpTesting.expectOne(SERIES_URL).flush([{ seriesId: 'dma-nw', mainType: 'NW' }]);
    httpTesting
      .expectOne('/rest/secom/series-config')
      .flush([{ seriesId: 'dma-nw', exposeOverSecom: true, languages: ['en'] }]);
    expect(tab.seriesRows()).toEqual([
      { seriesId: 'dma-nw', exposeOverSecom: true, languages: ['en'] },
    ]);
  });

  it('pages the dataset list at the shared admin page size', () => {
    const { tab, httpTesting } = setup();
    const rows = Array.from({ length: 25 }, (_, i) => dataset(`u-${i}`));
    flushInitial(httpTesting, [], [], rows);
    expect(tab.datasetRows().length).toBe(20);
  });
});
