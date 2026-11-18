import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { NotificationService } from '../../../core/notifications/notification.service';
import { provideFakeAuthFacade } from '../../../shared/testing/fake-auth';
import { MyMailsApiService, type MyMail } from './api/my-mails-api.service';
import { MY_MAILS_NOTICE_INTERVAL_MS, MyMailsNoticeService } from './my-mails-notice.service';

const mail = (id: number, status: MyMail['status']): MyMail => ({
  id,
  status,
  subject: `Mail ${id}`,
  recipients: [],
  attempts: status === 'PENDING' ? 0 : 1,
});

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
  });
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.useRealTimers();
  });

  it('looks at once at the last day and raises one sticky warning for the failed mails', () => {
    const { service, mine, notify } = setup([
      of([mail(1, 'ERROR'), mail(2, 'SENT'), mail(3, 'PENDING'), mail(4, 'ERROR')]),
    ]);
    service.start();
    vi.advanceTimersByTime(0);
    expect(mine).toHaveBeenCalledExactlyOnceWith(1);
    expect(notify).toHaveBeenCalledExactlyOnceWith({
      severity: 'warn',
      summaryKey: 'messages.myMails.failedNotice',
      summaryParams: { count: 2 },
      detailKey: 'messages.myMails.failedNoticeDetail',
      life: 0,
    });
  });

  it('does not announce the same mails again at the next look', () => {
    const { service, mine, notify } = setup([of([mail(1, 'ERROR')])]);
    service.start();
    vi.advanceTimersByTime(0);
    vi.advanceTimersByTime(MY_MAILS_NOTICE_INTERVAL_MS);
    expect(mine).toHaveBeenCalledTimes(2);
    expect(notify).toHaveBeenCalledTimes(1);
  });

  it('announces a mail that failed later, even one older than a mail already announced', () => {
    const { service, notify } = setup([
      of([mail(105, 'ERROR'), mail(100, 'PENDING')]),
      of([mail(105, 'ERROR'), mail(100, 'ERROR')]),
    ]);
    service.start();
    vi.advanceTimersByTime(0);
    vi.advanceTimersByTime(MY_MAILS_NOTICE_INTERVAL_MS);
    expect(notify).toHaveBeenCalledTimes(2);
    expect(notify.mock.calls[0][0].summaryParams).toEqual({ count: 1 });
    expect(notify.mock.calls[1][0].summaryParams).toEqual({ count: 1 });
  });

  it('says nothing when a look fails, and tries again at the next', () => {
    const { service, mine, notify } = setup([
      throwError(() => new Error('offline')),
      of([mail(1, 'ERROR')]),
    ]);
    service.start();
    vi.advanceTimersByTime(0);
    expect(notify).not.toHaveBeenCalled();
    vi.advanceTimersByTime(MY_MAILS_NOTICE_INTERVAL_MS);
    expect(mine).toHaveBeenCalledTimes(2);
    expect(notify).toHaveBeenCalledTimes(1);
  });

  it('starts once however often it is asked to', () => {
    const { service, mine } = setup([of([])]);
    service.start();
    service.start();
    vi.advanceTimersByTime(0);
    expect(mine).toHaveBeenCalledTimes(1);
  });

  it('does not look without the user role in the domain, whose read would be refused', () => {
    const { service, mine } = setup([of([mail(1, 'ERROR')])], { domainUserRole: false });
    service.start();
    vi.advanceTimersByTime(0);
    vi.advanceTimersByTime(MY_MAILS_NOTICE_INTERVAL_MS);
    expect(mine).not.toHaveBeenCalled();
  });

  it('stops looking when the application is torn down', () => {
    const { service, mine } = setup([of([])]);
    service.start();
    vi.advanceTimersByTime(0);
    TestBed.resetTestingModule();
    vi.advanceTimersByTime(MY_MAILS_NOTICE_INTERVAL_MS);
    expect(mine).toHaveBeenCalledTimes(1);
  });
});
