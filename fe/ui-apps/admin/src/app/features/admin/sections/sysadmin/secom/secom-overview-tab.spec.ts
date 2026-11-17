import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { SecomOverviewTab } from './secom-overview-tab';
import { SecomFacade } from './secom.facade';

const SERIES_URL = '/rest/message-series/all?messageNumbers=true';

interface TabInternals {
  renewalState: () => 'scheduled' | 'incomplete' | 'disabled';
  outboxCounts: () => readonly { status: string; count: number }[];
  exposedSeries: () => readonly string[];
  expiryTone: () => string;
  expired: () => boolean;
}

function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideRouter([]),
      SecomFacade,
    ],
  });
  const fixture = TestBed.createComponent(SecomOverviewTab);
  fixture.detectChanges();
  return {
    fixture,
    tab: fixture.componentInstance as unknown as TabInternals,
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

function flushInitial(httpTesting: HttpTestingController, status: unknown): void {
  httpTesting.expectOne(SERIES_URL).flush([]);
  httpTesting.expectOne('/rest/secom/series-config').flush([]);
  httpTesting.expectOne('/rest/secom/status').flush(status as never);
  httpTesting.expectOne('/rest/secom/subscriptions').flush([]);
  httpTesting.expectOne('/rest/secom/nodes').flush([]);
  httpTesting.expectOne('/rest/secom/datasets').flush([]);
  httpTesting.expectOne('/rest/secom/outbox').flush([]);
}

describe('SecomOverviewTab', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('reports a scheduled renewal when the backend computed a renewal date', () => {
    const { tab, httpTesting } = setup();
    flushInitial(httpTesting, {
      enabled: true,
      renewal: {
        enabled: true,
        configured: true,
        windowDays: 30,
        renewsAt: 1000,
        renewsInDays: 12,
      },
    });
    expect(tab.renewalState()).toBe('scheduled');
  });

  it('calls out renewal that is switched on but not fully configured', () => {
    const { tab, httpTesting } = setup();
    flushInitial(httpTesting, {
      enabled: true,
      // enabled without configured → the backend leaves renewsAt null.
      renewal: { enabled: true, configured: false, windowDays: 30 },
    });
    expect(tab.renewalState()).toBe('incomplete');
  });

  it('reports renewal as plainly disabled when it is switched off', () => {
    const { tab, httpTesting } = setup();
    flushInitial(httpTesting, {
      enabled: true,
      renewal: { enabled: false, configured: true, windowDays: 30 },
    });
    expect(tab.renewalState()).toBe('disabled');
  });

  it('lists outbox counts in lifecycle order, skipping statuses with no entries', () => {
    const { tab, httpTesting } = setup();
    flushInitial(httpTesting, {
      enabled: true,
      // Deliberately out of lifecycle order, with a zero entry.
      outboxByStatus: { FAILED: 2, PENDING: 5, ACKED: 0, SENT: 1 },
    });
    expect(tab.outboxCounts()).toEqual([
      { status: 'PENDING', count: 5 },
      { status: 'SENT', count: 1 },
      { status: 'FAILED', count: 2 },
    ]);
  });

  it('has no outbox counts when the queue is empty', () => {
    const { tab, httpTesting } = setup();
    flushInitial(httpTesting, { enabled: true, outboxByStatus: {} });
    expect(tab.outboxCounts()).toEqual([]);
  });

  it('exposes the scoped series and the certificate expiry tier', () => {
    const { tab, httpTesting } = setup();
    flushInitial(httpTesting, {
      enabled: true,
      scopedSeries: ['dma-nw', 'dma-nw-local'],
      serviceCertificate: { subject: 'CN=node', daysToExpiry: 45 },
    });
    expect(tab.exposedSeries()).toEqual(['dma-nw', 'dma-nw-local']);
    expect(tab.expiryTone()).toBe('text-warning');
    expect(tab.expired()).toBe(false);
  });

  it('survives a status payload with no certificate or renewal block', () => {
    const { tab, httpTesting } = setup();
    flushInitial(httpTesting, { enabled: true });
    expect(tab.exposedSeries()).toEqual([]);
    expect(tab.outboxCounts()).toEqual([]);
    expect(tab.renewalState()).toBe('disabled');
    expect(tab.expired()).toBe(false);
  });
});
