import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import type { DestroyRef } from '@angular/core';
import type Keycloak from 'keycloak-js';
import { Observable, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { AuthService } from '../../../core/auth/services/auth.service';
import { KEYCLOAK } from '../../../core/auth/services/keycloak.token';
import { APP_CONFIG } from '../../../core/config/app-config.token';
import { authTokenInterceptor } from '../../../core/interceptors/auth-token.interceptor';
import { BackgroundReadSkipped } from '../../../core/interceptors/background-read';
import { NotificationService } from '../../../core/notifications/notification.service';
import { provideFakeAuthFacade } from '../../../shared/testing/fake-auth';
import { MyMailsApiService, type MyMail } from './api/my-mails-api.service';
import {
  MY_MAILS_ANNOUNCED_STORAGE_KEY,
  MY_MAILS_NOTICE_CHECK_MS,
  MY_MAILS_NOTICE_INTERVAL_MS,
  MyMailsNoticeService,
} from './my-mails-notice.service';

const mail = (id: number, status: MyMail['status']): MyMail => ({
  id,
  status,
  subject: `Mail ${id}`,
  recipients: [],
  attempts: status === 'PENDING' ? 0 : 1,
});

/** A shell's DestroyRef the spec destroys by hand. */
function shellRef() {
  const callbacks: (() => void)[] = [];
  const ref = {
    onDestroy: (callback: () => void) => {
      callbacks.push(callback);
      return () => undefined;
    },
  } as unknown as DestroyRef;
  return { ref, destroy: () => callbacks.forEach((callback) => callback()) };
}

function setHidden(hidden: boolean): void {
  Object.defineProperty(document, 'hidden', { value: hidden, configurable: true });
  document.dispatchEvent(new Event('visibilitychange'));
}

/** The answers of the successive looks, in order; the last one repeats. */
function setup(answers: Observable<readonly MyMail[]>[], opts: { domainUserRole?: boolean } = {}) {
  let call = 0;
  const mine = vi.fn(() => answers[Math.min(call++, answers.length - 1)]);
  const notify = vi.fn();
  TestBed.configureTestingModule({
    providers: [
      provideFakeAuthFacade({ isAuthenticated: true, domainUserRole: opts.domainUserRole ?? true }),
      { provide: MyMailsApiService, useValue: { mine } },
      { provide: NotificationService, useValue: { notify } },
    ],
  });
  return { service: TestBed.inject(MyMailsNoticeService), mine, notify };
}

describe('MyMailsNoticeService', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
    localStorage.removeItem(MY_MAILS_ANNOUNCED_STORAGE_KEY);
  });
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.useRealTimers();
    localStorage.removeItem(MY_MAILS_ANNOUNCED_STORAGE_KEY);
    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
  });

  it('looks at once at the last 7 days, as a background read, and raises one sticky warning', () => {
    const { service, mine, notify } = setup([
      of([mail(1, 'ERROR'), mail(2, 'SENT'), mail(3, 'PENDING'), mail(4, 'ERROR')]),
    ]);
    service.start(shellRef().ref);
    expect(mine).toHaveBeenCalledExactlyOnceWith(7, { background: true });
    expect(notify).toHaveBeenCalledExactlyOnceWith({
      severity: 'warn',
      summaryKey: 'messages.myMails.failedNotice',
      summaryParams: { count: 2 },
      detailKey: 'messages.myMails.failedNoticeDetail',
      life: 0,
    });
  });

  it('looks again after five minutes, not at every check, and does not announce a mail twice', () => {
    const { service, mine, notify } = setup([of([mail(1, 'ERROR')])]);
    service.start(shellRef().ref);
    vi.advanceTimersByTime(MY_MAILS_NOTICE_INTERVAL_MS - MY_MAILS_NOTICE_CHECK_MS);
    expect(mine).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(MY_MAILS_NOTICE_CHECK_MS);
    expect(mine).toHaveBeenCalledTimes(2);
    expect(notify).toHaveBeenCalledTimes(1);
  });

  it('does not announce a failure again after the page is loaded again', () => {
    const first = setup([of([mail(1, 'ERROR')])]);
    first.service.start(shellRef().ref);
    expect(first.notify).toHaveBeenCalledTimes(1);
    TestBed.resetTestingModule();

    const second = setup([of([mail(1, 'ERROR'), mail(2, 'ERROR')])]);
    second.service.start(shellRef().ref);
    expect(second.notify).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ summaryParams: { count: 1 } }),
    );
  });

  it('does not announce a failure another tab announced after this one was loaded', () => {
    const { service, notify } = setup([of([mail(1, 'ERROR'), mail(2, 'ERROR')])]);
    // The other tab announces mail 1 after this tab's service read the stored ids.
    localStorage.setItem(MY_MAILS_ANNOUNCED_STORAGE_KEY, JSON.stringify([1]));
    service.start(shellRef().ref);
    expect(notify).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ summaryParams: { count: 1 } }),
    );
    expect(JSON.parse(localStorage.getItem(MY_MAILS_ANNOUNCED_STORAGE_KEY) ?? '[]')).toEqual([
      2, 1,
    ]);
  });

  it('announces once per page load when the storage holds no list of ids', () => {
    localStorage.setItem(MY_MAILS_ANNOUNCED_STORAGE_KEY, 'not json');
    const { service, notify } = setup([of([mail(1, 'ERROR')])]);
    service.start(shellRef().ref);
    expect(notify).toHaveBeenCalledTimes(1);
  });

  it('announces a mail that failed later, even one older than a mail already announced', () => {
    const { service, notify } = setup([
      of([mail(105, 'ERROR'), mail(100, 'PENDING')]),
      of([mail(105, 'ERROR'), mail(100, 'ERROR')]),
    ]);
    service.start(shellRef().ref);
    vi.advanceTimersByTime(MY_MAILS_NOTICE_INTERVAL_MS);
    expect(notify).toHaveBeenCalledTimes(2);
    expect(notify.mock.calls[0][0].summaryParams).toEqual({ count: 1 });
    expect(notify.mock.calls[1][0].summaryParams).toEqual({ count: 1 });
  });

  it('says nothing when a look fails, and tries again at the next look', () => {
    const { service, mine, notify } = setup([
      throwError(() => new Error('offline')),
      of([mail(1, 'ERROR')]),
    ]);
    service.start(shellRef().ref);
    expect(notify).not.toHaveBeenCalled();
    vi.advanceTimersByTime(MY_MAILS_NOTICE_CHECK_MS);
    expect(mine).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(MY_MAILS_NOTICE_INTERVAL_MS);
    expect(mine).toHaveBeenCalledTimes(2);
    expect(notify).toHaveBeenCalledTimes(1);
  });

  it('tries a look the token was too old for again at the next check', () => {
    const { service, mine, notify } = setup([
      throwError(() => new BackgroundReadSkipped()),
      of([mail(1, 'ERROR')]),
    ]);
    service.start(shellRef().ref);
    expect(notify).not.toHaveBeenCalled();
    vi.advanceTimersByTime(MY_MAILS_NOTICE_CHECK_MS);
    expect(mine).toHaveBeenCalledTimes(2);
    expect(notify).toHaveBeenCalledTimes(1);
  });

  it('does not look while the tab is hidden, and looks when it is shown again', () => {
    const { service, mine } = setup([of([])]);
    Object.defineProperty(document, 'hidden', { value: true, configurable: true });
    service.start(shellRef().ref);
    vi.advanceTimersByTime(3 * MY_MAILS_NOTICE_INTERVAL_MS);
    expect(mine).not.toHaveBeenCalled();
    setHidden(false);
    expect(mine).toHaveBeenCalledTimes(1);
    setHidden(true);
    vi.advanceTimersByTime(3 * MY_MAILS_NOTICE_INTERVAL_MS);
    expect(mine).toHaveBeenCalledTimes(1);
  });

  it('stops looking when no shell is mounted', () => {
    const { service, mine } = setup([of([])]);
    const messages = shellRef();
    const editor = shellRef();
    service.start(messages.ref);
    service.start(editor.ref);
    expect(mine).toHaveBeenCalledTimes(1);
    messages.destroy();
    vi.advanceTimersByTime(MY_MAILS_NOTICE_INTERVAL_MS);
    expect(mine).toHaveBeenCalledTimes(2);
    editor.destroy();
    vi.advanceTimersByTime(3 * MY_MAILS_NOTICE_INTERVAL_MS);
    setHidden(false);
    expect(mine).toHaveBeenCalledTimes(2);
  });

  it('looks at most every five minutes when the shells come and go', () => {
    const { service, mine } = setup([of([])]);
    const first = shellRef();
    service.start(first.ref);
    first.destroy();
    service.start(shellRef().ref);
    expect(mine).toHaveBeenCalledTimes(1);
  });

  it('does not look without the user role in the domain, whose read would be refused', () => {
    const { service, mine } = setup([of([mail(1, 'ERROR')])], { domainUserRole: false });
    service.start(shellRef().ref);
    vi.advanceTimersByTime(MY_MAILS_NOTICE_INTERVAL_MS);
    expect(mine).not.toHaveBeenCalled();
  });

  it('stops looking when the application is torn down', () => {
    const { service, mine } = setup([of([])]);
    service.start(shellRef().ref);
    TestBed.resetTestingModule();
    vi.advanceTimersByTime(MY_MAILS_NOTICE_INTERVAL_MS);
    expect(mine).toHaveBeenCalledTimes(1);
  });
});

describe('MyMailsNoticeService with the session', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.useRealTimers();
  });

  it('neither refreshes the token nor ends the session, even when a refresh would fail', async () => {
    const keycloak = {
      token: 'tkn',
      tokenParsed: { sub: 'sub-1', resource_access: {} },
      idTokenParsed: { sub: 'sub-1' },
      init: vi.fn().mockResolvedValue(true),
      // The token is too old to be sent as it is, and a refresh fails (the network is down).
      isTokenExpired: vi.fn(() => true),
      updateToken: vi.fn().mockRejectedValue(new Error('offline')),
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: KEYCLOAK, useValue: keycloak as unknown as Keycloak },
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', map: { wmsUrl: '/wms/' } } },
        provideFakeAuthFacade({ isAuthenticated: true }),
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        provideHttpClient(withInterceptors([authTokenInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    const auth = TestBed.inject(AuthService);
    await auth.init();
    expect(auth.isAuthenticated()).toBe(true);
    vi.useFakeTimers();
    Object.defineProperty(document, 'hidden', { value: false, configurable: true });

    TestBed.inject(MyMailsNoticeService).start(shellRef().ref);
    vi.advanceTimersByTime(2 * MY_MAILS_NOTICE_INTERVAL_MS);

    expect(keycloak.updateToken).not.toHaveBeenCalled();
    expect(auth.isAuthenticated()).toBe(true);
    TestBed.inject(HttpTestingController).expectNone(() => true);
  });
});
