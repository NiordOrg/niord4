import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { SecomSubscription } from '../../../services/api/secom-admin-api.service';
import { SecomSubscribersTab } from './secom-subscribers-tab';
import { SecomFacade } from './secom.facade';

const SERIES_URL = '/rest/message-series/all?messageNumbers=true';

interface TabInternals {
  subscriptionRows: () => readonly SecomSubscription[];
  nodeRows: () => readonly { mrn: string }[];
  wktPreview: (wkt: string) => string;
  hasPeriod: (subscription: SecomSubscription) => boolean;
  deleteSubscription: (subscription: SecomSubscription) => void;
}

/** Auto-accept confirm stub: fires the destructive `accept` callback synchronously. */
const autoAcceptConfirm = {
  active: () => undefined,
  dismiss: () => undefined,
  confirmDestructive: (options: { accept: () => void }) => options.accept(),
  confirmNeutral: (options: { accept: () => void }) => options.accept(),
};

function setup(notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() }) {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      { provide: NotificationService, useValue: notifications },
      { provide: ConfirmService, useValue: autoAcceptConfirm },
      SecomFacade,
    ],
  });
  const fixture = TestBed.createComponent(SecomSubscribersTab);
  fixture.detectChanges();
  return {
    fixture,
    tab: fixture.componentInstance as unknown as TabInternals,
    httpTesting: TestBed.inject(HttpTestingController),
    notifications,
  };
}

function flushInitial(
  httpTesting: HttpTestingController,
  subscriptions: unknown[] = [],
  nodes: unknown[] = [],
): void {
  httpTesting.expectOne(SERIES_URL).flush([]);
  httpTesting.expectOne('/rest/secom/series-config').flush([]);
  httpTesting.expectOne('/rest/secom/status').flush({ enabled: true });
  httpTesting.expectOne('/rest/secom/subscriptions').flush(subscriptions);
  httpTesting.expectOne('/rest/secom/nodes').flush(nodes);
  httpTesting.expectOne('/rest/secom/datasets').flush([]);
  httpTesting.expectOne('/rest/secom/outbox').flush([]);
}

const subscription = (uuid: string): SecomSubscription => ({
  uuid,
  nodeMrn: 'urn:mrn:peer',
  dataProductType: 'S124',
  productVersion: '1.0',
});

describe('SecomSubscribersTab', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('removing a subscription confirms, DELETEs, then refreshes the list', () => {
    const { tab, httpTesting, notifications } = setup();
    flushInitial(httpTesting, [{ uuid: 'sub-1', nodeMrn: 'urn:mrn:peer' }]);
    expect(tab.subscriptionRows().length).toBe(1);

    tab.deleteSubscription(subscription('sub-1'));
    const req = httpTesting.expectOne('/rest/secom/subscriptions/sub-1');
    expect(req.request.method).toBe('DELETE');
    req.flush({ uuid: 'sub-1', nodeMrn: 'urn:mrn:peer' });

    TestBed.tick();
    expect(notifications.success).toHaveBeenCalledWith('admin.secom.toast.subscriptionRemoved');
    httpTesting.expectOne('/rest/secom/subscriptions').flush([]);
    httpTesting.expectOne('/rest/secom/status').flush({ enabled: true });
  });

  it('a failed removal toasts and leaves the list alone', () => {
    const { tab, httpTesting, notifications } = setup();
    flushInitial(httpTesting, [{ uuid: 'sub-1', nodeMrn: 'urn:mrn:peer' }]);

    tab.deleteSubscription(subscription('sub-1'));
    httpTesting
      .expectOne('/rest/secom/subscriptions/sub-1')
      .flush('nope', { status: 500, statusText: 'Server Error' });

    expect(notifications.error).toHaveBeenCalledWith('admin.secom.toast.subscriptionRemoveError');
    expect(tab.subscriptionRows().length).toBe(1);
  });

  it('clips a long WKT geometry but keeps a short one intact', () => {
    const { tab, httpTesting } = setup();
    flushInitial(httpTesting);
    expect(tab.wktPreview('POINT(1 2)')).toBe('POINT(1 2)');
    expect(tab.wktPreview('x'.repeat(80))).toBe(`${'x'.repeat(60)}…`);
  });

  it('shows a period when either bound is set', () => {
    const { tab, httpTesting } = setup();
    flushInitial(httpTesting);
    expect(tab.hasPeriod(subscription('a'))).toBe(false);
    expect(tab.hasPeriod({ ...subscription('a'), periodStart: 1 })).toBe(true);
    expect(tab.hasPeriod({ ...subscription('a'), periodEnd: 2 })).toBe(true);
  });

  it('pages subscriptions and peers independently', () => {
    const { tab, httpTesting } = setup();
    const subs = Array.from({ length: 25 }, (_, i) => ({
      uuid: `s-${i}`,
      nodeMrn: 'urn:mrn:peer',
    }));
    const nodes = Array.from({ length: 3 }, (_, i) => ({ mrn: `urn:mrn:peer-${i}` }));
    flushInitial(httpTesting, subs, nodes);
    expect(tab.subscriptionRows().length).toBe(20);
    expect(tab.nodeRows().length).toBe(3);
  });
});
