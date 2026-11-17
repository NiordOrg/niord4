import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { SecomCertificatesTab } from './secom-certificates-tab';
import { SecomFacade } from './secom.facade';

const SERIES_URL = '/rest/message-series/all?messageNumbers=true';

interface TabInternals {
  busy: () => boolean;
  expiryTone: () => string;
  expired: () => boolean;
  reloadSecurity: () => void;
  renewCertificates: () => void;
}

/** Auto-accept confirm stub: fires the `accept` callback synchronously. */
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
  const fixture = TestBed.createComponent(SecomCertificatesTab);
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
  status: unknown = { enabled: true },
): void {
  httpTesting.expectOne(SERIES_URL).flush([]);
  httpTesting.expectOne('/rest/secom/series-config').flush([]);
  httpTesting.expectOne('/rest/secom/status').flush(status as never);
  httpTesting.expectOne('/rest/secom/subscriptions').flush([]);
  httpTesting.expectOne('/rest/secom/nodes').flush([]);
  httpTesting.expectOne('/rest/secom/datasets').flush([]);
  httpTesting.expectOne('/rest/secom/outbox').flush([]);
}

describe('SecomCertificatesTab', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('tints the service certificate by its remaining lifetime', () => {
    const { tab, httpTesting } = setup();
    flushInitial(httpTesting, {
      enabled: true,
      serviceCertificate: { subject: 'CN=node', daysToExpiry: 9 },
    });
    expect(tab.expiryTone()).toBe('text-danger');
    expect(tab.expired()).toBe(false);
  });

  it('marks an already-lapsed certificate as expired', () => {
    const { tab, httpTesting } = setup();
    flushInitial(httpTesting, {
      enabled: true,
      serviceCertificate: { subject: 'CN=node', daysToExpiry: -3 },
    });
    expect(tab.expired()).toBe(true);
  });

  it('reload reports the swapped-in subject and refreshes the status', () => {
    const { tab, httpTesting, notifications } = setup();
    flushInitial(httpTesting);

    tab.reloadSecurity();
    expect(tab.busy()).toBe(true);
    httpTesting
      .expectOne('/rest/secom/security/reload')
      .flush({ serviceCertificateSubject: 'CN=new-node' });

    expect(tab.busy()).toBe(false);
    TestBed.tick();
    expect(notifications.success).toHaveBeenCalledWith('admin.secom.toast.reloaded', {
      subject: 'CN=new-node',
    });
    httpTesting.expectOne('/rest/secom/status').flush({ enabled: true });
  });

  it('a failed reload says the current material stays active', () => {
    const { tab, httpTesting, notifications } = setup();
    flushInitial(httpTesting);

    tab.reloadSecurity();
    httpTesting
      .expectOne('/rest/secom/security/reload')
      .flush('bad keystore', { status: 400, statusText: 'Bad Request' });

    expect(tab.busy()).toBe(false);
    expect(notifications.error).toHaveBeenCalledWith('admin.secom.toast.reloadError');
  });

  it('renew confirms first, then reports the renewed credentials', () => {
    const { tab, httpTesting, notifications } = setup();
    flushInitial(httpTesting);

    tab.renewCertificates();
    httpTesting.expectOne('/rest/secom/security/renew').flush({
      renewed: [
        { credential: 'robot', mrn: 'urn:mrn:robot' },
        { credential: 'service', mrn: 'urn:mrn:node' },
      ],
      failures: [],
    });

    TestBed.tick();
    expect(notifications.success).toHaveBeenCalledWith('admin.secom.toast.renewed', {
      renewed: 'robot urn:mrn:robot, service urn:mrn:node',
    });
    httpTesting.expectOne('/rest/secom/status').flush({ enabled: true });
  });

  it('a partial renewal warns rather than reading as a clean success', () => {
    const { tab, httpTesting, notifications } = setup();
    flushInitial(httpTesting);

    tab.renewCertificates();
    httpTesting.expectOne('/rest/secom/security/renew').flush({
      renewed: [{ credential: 'service', mrn: 'urn:mrn:node' }],
      failures: ['robot: MIR refused'],
    });

    TestBed.tick();
    expect(notifications.warn).toHaveBeenCalledWith('admin.secom.toast.renewedWithFailures', {
      renewed: 'service urn:mrn:node',
      failures: 'robot: MIR refused',
    });
    expect(notifications.success).not.toHaveBeenCalled();
    httpTesting.expectOne('/rest/secom/status').flush({ enabled: true });
  });

  it('surfaces the problem-document detail of a refused renewal', () => {
    const { tab, httpTesting, notifications } = setup();
    flushInitial(httpTesting);

    tab.renewCertificates();
    httpTesting
      .expectOne('/rest/secom/security/renew')
      .flush(
        { type: 'about:blank', title: 'Bad Request', status: 400, detail: 'MIR returned 401' },
        { status: 400, statusText: 'Bad Request' },
      );

    expect(notifications.error).toHaveBeenCalledWith('admin.secom.toast.renewError', {
      detail: 'MIR returned 401',
    });
  });

  it('points at the application log when the failure carries no detail', () => {
    const { tab, httpTesting, notifications } = setup();
    flushInitial(httpTesting);

    tab.renewCertificates();
    httpTesting
      .expectOne('/rest/secom/security/renew')
      .flush('', { status: 500, statusText: 'Server Error' });

    expect(notifications.error).toHaveBeenCalledWith('admin.secom.toast.renewErrorNoDetail');
  });
});
