import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { SecomOutboxEntry, SecomOutboxStatus } from '../../../services/api/secom-admin-api.service';
import { SecomOutboxTab } from './secom-outbox-tab';
import { SecomFacade } from './secom.facade';

const SERIES_URL = '/rest/message-series/all?messageNumbers=true';

interface TabInternals {
  rows: () => readonly SecomOutboxEntry[];
  statusOptions: readonly { value: '' | SecomOutboxStatus; labelKey: string }[];
  onStatusChange: (status: '' | SecomOutboxStatus) => void;
  canRetry: (entry: SecomOutboxEntry) => boolean;
  canCancel: (entry: SecomOutboxEntry) => boolean;
  errorPreview: (lastError: string) => string;
  retry: (entry: SecomOutboxEntry) => void;
  cancel: (entry: SecomOutboxEntry) => void;
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
  const fixture = TestBed.createComponent(SecomOutboxTab);
  fixture.detectChanges();
  return {
    fixture,
    tab: fixture.componentInstance as unknown as TabInternals,
    httpTesting: TestBed.inject(HttpTestingController),
    notifications,
  };
}

function flushInitial(httpTesting: HttpTestingController, outbox: unknown[] = []): void {
  httpTesting.expectOne(SERIES_URL).flush([]);
  httpTesting.expectOne('/rest/secom/series-config').flush([]);
  httpTesting.expectOne('/rest/secom/status').flush({ enabled: true });
  httpTesting.expectOne('/rest/secom/subscriptions').flush([]);
  httpTesting.expectOne('/rest/secom/nodes').flush([]);
  httpTesting.expectOne('/rest/secom/datasets').flush([]);
  httpTesting.expectOne('/rest/secom/outbox').flush(outbox);
}

const entry = (id: number, status: string): SecomOutboxEntry => ({
  id,
  transactionId: `tx-${id}`,
  nodeMrn: 'urn:mrn:peer',
  operation: 'UPLOAD',
  status,
  attempts: 1,
});

describe('SecomOutboxTab', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('offers "all" plus one option per delivery status, in lifecycle order', () => {
    const { tab, httpTesting } = setup();
    flushInitial(httpTesting);
    expect(tab.statusOptions.map((o) => o.value)).toEqual([
      '',
      'PENDING',
      'SENT',
      'ACKED',
      'OPENED',
      'FAILED',
      'CANCELLED',
    ]);
  });

  it('switching the filter refetches server-side with the status param', async () => {
    const { fixture, tab, httpTesting } = setup();
    flushInitial(httpTesting);

    tab.onStatusChange('FAILED');
    await fixture.whenStable();
    const req = httpTesting.expectOne((r) => r.url === '/rest/secom/outbox');
    expect(req.request.params.get('status')).toBe('FAILED');
    req.flush([entry(1, 'FAILED')]);
    expect(tab.rows().length).toBe(1);
  });

  it('only pending and failed entries can be retried; only pending can be cancelled', () => {
    const { tab, httpTesting } = setup();
    flushInitial(httpTesting);

    expect(tab.canRetry(entry(1, 'PENDING'))).toBe(true);
    expect(tab.canRetry(entry(2, 'FAILED'))).toBe(true);
    expect(tab.canRetry(entry(3, 'SENT'))).toBe(false);
    expect(tab.canRetry(entry(4, 'ACKED'))).toBe(false);

    expect(tab.canCancel(entry(1, 'PENDING'))).toBe(true);
    expect(tab.canCancel(entry(2, 'FAILED'))).toBe(false);
    expect(tab.canCancel(entry(3, 'SENT'))).toBe(false);
  });

  it('retry POSTs the entry id and refreshes', () => {
    const { tab, httpTesting, notifications } = setup();
    flushInitial(httpTesting, [entry(7, 'FAILED')]);

    tab.retry(entry(7, 'FAILED'));
    httpTesting.expectOne('/rest/secom/outbox/7/retry').flush(7);

    TestBed.tick();
    expect(notifications.success).toHaveBeenCalledWith('admin.secom.toast.outboxRetried');
    httpTesting.expectOne('/rest/secom/outbox').flush([]);
    httpTesting.expectOne('/rest/secom/status').flush({ enabled: true });
  });

  it('cancel confirms first, then DELETEs the entry', () => {
    const { tab, httpTesting, notifications } = setup();
    flushInitial(httpTesting, [entry(7, 'PENDING')]);

    tab.cancel(entry(7, 'PENDING'));
    const req = httpTesting.expectOne('/rest/secom/outbox/7');
    expect(req.request.method).toBe('DELETE');
    req.flush(7);

    TestBed.tick();
    expect(notifications.success).toHaveBeenCalledWith('admin.secom.toast.outboxCancelled');
    httpTesting.expectOne('/rest/secom/outbox').flush([]);
    httpTesting.expectOne('/rest/secom/status').flush({ enabled: true });
  });

  it('a failed cancel toasts and leaves the list alone', () => {
    const { tab, httpTesting, notifications } = setup();
    flushInitial(httpTesting, [entry(7, 'PENDING')]);

    tab.cancel(entry(7, 'PENDING'));
    httpTesting
      .expectOne('/rest/secom/outbox/7')
      .flush('nope', { status: 500, statusText: 'Server Error' });

    expect(notifications.error).toHaveBeenCalledWith('admin.secom.toast.outboxCancelError');
  });

  describe('the list is the newest 100 (SECOM4)', () => {
    const NEWEST_ONLY_KEY = 'admin.secom.outbox.newestOnly';
    const entries = (count: number, status = 'ACKED'): SecomOutboxEntry[] =>
      Array.from({ length: count }, (_, i) => entry(i + 1, status));

    async function render(fixture: { whenStable: () => Promise<unknown> }): Promise<void> {
      TestBed.tick();
      await fixture.whenStable();
    }

    it('says it shows the newest 100 entries when the endpoint answers its full 100', async () => {
      const { fixture, httpTesting } = setup();
      TestBed.inject(TranslocoService).setTranslationKey(
        NEWEST_ONLY_KEY,
        'Showing the newest {{count}} entries.',
      );
      flushInitial(httpTesting, entries(100));
      await render(fixture);

      const note = (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="outbox-newest-only"]',
      );
      expect(note?.textContent?.trim()).toBe('Showing the newest 100 entries.');
    });

    it('says the same under a status filter that answers 100 entries', async () => {
      const { fixture, tab, httpTesting } = setup();
      flushInitial(httpTesting);

      tab.onStatusChange('FAILED');
      await fixture.whenStable();
      httpTesting.expectOne((r) => r.url === '/rest/secom/outbox').flush(entries(100, 'FAILED'));
      await render(fixture);

      expect((fixture.nativeElement as HTMLElement).textContent).toContain(NEWEST_ONLY_KEY);
    });

    it('says nothing when the list holds fewer than 100 entries, so it is the whole outbox', async () => {
      const { fixture, httpTesting } = setup();
      flushInitial(httpTesting, entries(99));
      await render(fixture);

      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('[data-testid="outbox-newest-only"]')).toBeNull();
      expect(host.textContent).not.toContain(NEWEST_ONLY_KEY);
    });
  });

  it('clips a long failure message but keeps a short one intact', () => {
    const { tab, httpTesting } = setup();
    flushInitial(httpTesting);
    expect(tab.errorPreview('short')).toBe('short');
    const long = 'x'.repeat(80);
    expect(tab.errorPreview(long)).toBe(`${'x'.repeat(50)}…`);
  });
});
