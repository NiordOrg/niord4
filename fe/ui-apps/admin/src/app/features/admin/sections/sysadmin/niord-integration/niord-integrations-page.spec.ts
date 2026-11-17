import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { NiordIntegration } from '../../../services/api/niord-integrations-api.service';
import { NiordIntegrationsPage } from './niord-integrations-page';

interface PageInternals {
  search: () => string;
  filtered: () => readonly { url: string }[];
  onSearch: (value: string) => void;
  deleteIntegration: (integration: NiordIntegration) => void;
  executeIntegration: (integration: NiordIntegration) => void;
  pager: { first: () => number; setFirst: (first: number) => void };
}

/**
 * Auto-accept confirm stub: fires the destructive `accept` callback synchronously.
 * `active`/`dismiss` keep the mounted `<app-confirm>` inert (no pending confirm, so
 * no overlay opens) since it injects this same scoped `ConfirmService`.
 */
const autoAcceptConfirm = {
  active: () => undefined,
  dismiss: () => undefined,
  confirmDestructive: (options: { accept: () => void }) => options.accept(),
};

function setup(notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn() }) {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      { provide: NotificationService, useValue: notifications },
    ],
  });
  TestBed.overrideComponent(NiordIntegrationsPage, {
    add: { providers: [{ provide: ConfirmService, useValue: autoAcceptConfirm }] },
  });
  const fixture = TestBed.createComponent(NiordIntegrationsPage);
  fixture.detectChanges();
  const httpTesting = TestBed.inject(HttpTestingController);
  return {
    fixture,
    page: fixture.componentInstance as unknown as PageInternals,
    httpTesting,
    notifications,
  };
}

/** The facade fires the series-options fetch (constructor) + the list fetch (effect). */
function flushInitial(
  httpTesting: HttpTestingController,
  integrations: unknown[] = [],
  seriesIds: string[] = [],
): void {
  httpTesting
    .expectOne('/rest/message-series/all?messageNumbers=true')
    .flush(seriesIds.map((seriesId) => ({ seriesId })));
  httpTesting.expectOne('/rest/niord-integrations/all').flush(integrations);
}

const vo = (id: number, url: string, mappings: { s: string; t: string }[] = [], active = true) => ({
  id,
  url,
  active,
  assignNewUids: true,
  createBaseData: false,
  messageSeriesMappings: mappings.map((m) => ({ sourceSeriesId: m.s, targetSeriesId: m.t })),
});

describe('NiordIntegrationsPage', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('filters on the URL OR raw mapping source/target ids, case-insensitive', () => {
    const { fixture, page, httpTesting } = setup();
    flushInitial(httpTesting, [
      vo(1, 'https://alpha', [{ s: 'remote-nw', t: 'local-nw' }]),
      vo(2, 'https://beta', [{ s: 'remote-nm', t: 'local-nm' }]),
    ]);
    fixture.detectChanges();

    page.onSearch('ALPHA');
    expect(page.filtered().length).toBe(1);
    expect(page.filtered()[0].url).toBe('https://alpha');

    page.onSearch('local-nm');
    expect(page.filtered().length).toBe(1);
    expect(page.filtered()[0].url).toBe('https://beta');

    page.onSearch('');
    expect(page.filtered().length).toBe(2);
  });

  it('every filter change resets paging to the first page', () => {
    const { fixture, page, httpTesting } = setup();
    flushInitial(
      httpTesting,
      Array.from({ length: 45 }, (_, i) => vo(i, `https://h${i}`)),
    );
    fixture.detectChanges();

    page.pager.setFirst(40);
    expect(page.pager.first()).toBe(40);
    page.onSearch('https');
    expect(page.pager.first()).toBe(0);
  });

  it('shows the none-yet empty state for an empty list', () => {
    const { fixture, httpTesting } = setup();
    flushInitial(httpTesting, []);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-empty-state')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('app-error-banner')).toBeNull();
  });

  it('shows the error banner when the list load fails', () => {
    const { fixture, httpTesting } = setup();
    httpTesting.expectOne('/rest/message-series/all?messageNumbers=true').flush([]);
    httpTesting
      .expectOne('/rest/niord-integrations/all')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-error-banner')).not.toBeNull();
  });

  it('surfaces a series-options load failure with its own banner', () => {
    const { fixture, httpTesting } = setup();
    httpTesting
      .expectOne('/rest/message-series/all?messageNumbers=true')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    httpTesting.expectOne('/rest/niord-integrations/all').flush([]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('app-error-banner')).not.toBeNull();
  });

  it('execute fires the PUT and reports it started on success', () => {
    const notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
    const { fixture, page, httpTesting } = setup(notifications);
    flushInitial(httpTesting, [vo(7, 'https://r')]);
    fixture.detectChanges();

    page.executeIntegration({
      id: 7,
      url: 'https://r',
      active: true,
      assignNewUids: true,
      createBaseData: false,
      messageSeriesMappings: [],
    });

    const req = httpTesting.expectOne('/rest/niord-integrations/execute/7');
    expect(req.request.method).toBe('PUT');
    req.flush(null);
    expect(notifications.info).toHaveBeenCalledWith('admin.niord-integration.toast.executeStarted');
    expect(notifications.error).not.toHaveBeenCalled();
  });

  it('execute toasts an error when the PUT fails', () => {
    const notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
    const { fixture, page, httpTesting } = setup(notifications);
    flushInitial(httpTesting, [vo(7, 'https://r')]);
    fixture.detectChanges();

    page.executeIntegration({
      id: 7,
      url: 'https://r',
      active: true,
      assignNewUids: true,
      createBaseData: false,
      messageSeriesMappings: [],
    });

    httpTesting
      .expectOne('/rest/niord-integrations/execute/7')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(notifications.error).toHaveBeenCalledWith('admin.niord-integration.toast.executeError');
    expect(notifications.info).not.toHaveBeenCalled();
  });

  it('delete confirms, DELETEs, toasts success, then refetches the list', () => {
    const notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
    const { fixture, page, httpTesting } = setup(notifications);
    flushInitial(httpTesting, [vo(7, 'https://r')]);
    fixture.detectChanges();

    page.deleteIntegration({
      id: 7,
      url: 'https://r',
      active: true,
      assignNewUids: true,
      createBaseData: false,
      messageSeriesMappings: [],
    });

    const del = httpTesting.expectOne('/rest/niord-integrations/niord-integration/7');
    expect(del.request.method).toBe('DELETE');
    del.flush(null);
    expect(notifications.success).toHaveBeenCalledWith('admin.niord-integration.toast.deleted');
    TestBed.tick();
    httpTesting.expectOne('/rest/niord-integrations/all').flush([]);
  });

  it('delete toasts the delete-specific error when the DELETE fails', () => {
    const notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn() };
    const { fixture, page, httpTesting } = setup(notifications);
    flushInitial(httpTesting, [vo(7, 'https://r')]);
    fixture.detectChanges();

    page.deleteIntegration({
      id: 7,
      url: 'https://r',
      active: true,
      assignNewUids: true,
      createBaseData: false,
      messageSeriesMappings: [],
    });

    httpTesting
      .expectOne('/rest/niord-integrations/niord-integration/7')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    expect(notifications.error).toHaveBeenCalledWith('admin.niord-integration.toast.deleteError');
    expect(notifications.success).not.toHaveBeenCalled();
  });
});
