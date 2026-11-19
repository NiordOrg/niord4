import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainService } from '../../../../../core/domain/services/domain.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { DatePicker } from '../../../../../shared/forms/date-picker/date-picker';
import { ActiveConfirm, ConfirmService } from '../../../../../shared/services/confirm.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { wallClockIn } from '../../../../../shared/testing/zone-testing';
import { MailsPage } from './mails-page';

/** Deliberately west of the runner: a browser-day boundary would land on the wrong day. */
const DOMAIN_TZ = 'America/Halifax';

interface PageInternals {
  onTextInput: (param: 'recipient' | 'sender' | 'subject', value: string) => void;
  commitText: (param: 'recipient' | 'sender' | 'subject') => void;
  onFromDateChange: (date: Date | null) => void;
  onToDateChange: (date: Date | null) => void;
  firstRecipient: (mail: { recipients: readonly { recipientType: string; address: string }[] }) => {
    address: string;
    others: number;
  };
  onStatusChange: (status: '' | 'PENDING' | 'SENT' | 'HELD' | 'ERROR') => void;
  facade: {
    params: () => { recipient: string; fromDate?: number; toDate?: number };
    refresh: () => void;
  };
}

/** The picker's own selection entry point — the page is fed by it, never by a raw Date. */
interface PickerInternals {
  onDateSelected: (date: Date) => void;
}

function setup(timeZone: string = DOMAIN_TZ) {
  const notifications = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      { provide: NotificationService, useValue: notifications },
      { provide: DomainService, useValue: { sessionDomain: () => ({ timeZone }) } },
    ],
  });
  const fixture = TestBed.createComponent(MailsPage);
  fixture.detectChanges();
  const httpTesting = TestBed.inject(HttpTestingController);
  httpTesting.expectOne('/rest/settings/setting/mailDeleteAfterDays').flush({ value: 30 });
  httpTesting
    .expectOne('/rest/scheduled-mails/search?maxSize=20&page=0')
    .flush({ data: [], size: 0, total: 0 });
  fixture.detectChanges();
  const [fromPicker, toPicker] = fixture.debugElement
    .queryAll(By.directive(DatePicker))
    .map((el) => el.componentInstance as unknown as PickerInternals);
  return {
    fixture,
    page: fixture.componentInstance as unknown as PageInternals,
    httpTesting,
    fromPicker,
    toPicker,
    notifications,
  };
}

type Setup = ReturnType<typeof setup>;

interface MailVo {
  id: number;
  status: string;
  sender: string;
  subject: string;
  recipients: { recipientType: 'TO' | 'CC' | 'BCC'; address: string }[];
  attempts?: number;
  lastError?: string;
  created?: number;
  sentAgainAt?: number;
}

/** 11 June 2026, 10:00:00 UTC: the minute the mailing list's run made its mails. */
const RUN = Date.UTC(2026, 5, 11, 10, 0, 0);

const mail = (id: number, status: string, extra: Partial<MailVo> = {}): MailVo => ({
  id,
  status,
  sender: 'niord@example.org',
  subject: 'Firing exercise',
  recipients: [{ recipientType: 'TO', address: `to${id}@example.org` }],
  attempts: status === 'PENDING' ? 0 : 1,
  created: RUN + id * 1000,
  ...extra,
});

const addresses = (from: number, to: number) =>
  Array.from({ length: to - from + 1 }, (_, i) => ({
    recipientType: 'TO' as const,
    address: `a${from + i}@example.org`,
  }));

/** The English texts the Send again assertions read, so their parameters are filled in. */
function seedTexts(): void {
  TestBed.inject(TranslocoService).setTranslation(
    {
      admin: {
        mails: {
          status: { sent: 'Sent', held: 'Held', error: 'Error' },
          sendAgain: {
            title: 'Send again',
            messageOne: 'One mail',
            messageSent: 'One sent mail',
            messageMany: 'Send these {{count}} mails again',
            line: '{{count}} × {{subject}} · made {{created}} · {{status}} · to {{recipients}}',
            more: '{{addresses}} and {{count}} more',
          },
        },
      },
    },
    'en',
  );
}

/** Answers the page's search (after a filter change or a refresh) with the given rows. */
function answerSearch(s: Setup, rows: MailVo[], status = '', total = rows.length): void {
  TestBed.tick();
  const query = status ? `&status=${status}` : '';
  s.httpTesting
    .expectOne(`/rest/scheduled-mails/search?maxSize=20&page=0${query}`)
    .flush({ data: rows, size: rows.length, total });
  s.fixture.detectChanges();
}

function showRows(s: Setup, rows: MailVo[]): void {
  s.page.facade.refresh();
  answerSearch(s, rows);
}

function filterStatus(s: Setup, status: 'SENT' | 'HELD' | 'ERROR', rows: MailVo[]): void {
  s.page.onStatusChange(status);
  answerSearch(s, rows, status);
}

/** The rows' Send again buttons, found by their icon: a seeded text replaces the label's key. */
function sendAgainButtons(fixture: ComponentFixture<unknown>): HTMLButtonElement[] {
  return Array.from(
    (fixture.nativeElement as HTMLElement).querySelectorAll('tbody app-icon[name="lucideSend"]'),
  ).map((icon) => icon.closest('button') as HTMLButtonElement);
}

function sendAllButton(fixture: ComponentFixture<unknown>): HTMLButtonElement | undefined {
  return Array.from(
    (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button'),
  ).find((button) => button.textContent?.trim() === 'admin.mails.sendAgain.all');
}

/** The confirmation the page's own ConfirmService holds open. */
function activeConfirm(fixture: ComponentFixture<unknown>): ActiveConfirm | undefined {
  return fixture.debugElement.injector.get(ConfirmService).active();
}

/** Accepts the open confirmation as its accept button does. */
function accept(fixture: ComponentFixture<unknown>): void {
  const confirm = fixture.debugElement.injector.get(ConfirmService);
  const active = confirm.active();
  expect(active).toBeDefined();
  confirm.dismiss();
  active!.accept();
}

/** The bulk action's read: the committed filter, page 0, at most 500 mails. */
function answerBulkRead(s: Setup, status: string, rows: MailVo[], total = rows.length): void {
  s.httpTesting
    .expectOne(`/rest/scheduled-mails/search?maxSize=500&page=0&status=${status}`)
    .flush({ data: rows, size: rows.length, total });
  s.fixture.detectChanges();
}

/** A click on the calendar's 11 June cell: the grid always hands back a browser-local day. */
function clickJune11(): Date {
  return new Date(2026, 5, 11);
}

describe('MailsPage', () => {
  afterEach(() => {
    vi.useRealTimers();
    TestBed.inject(HttpTestingController).verify();
    // The page's confirmation portals to the body-level CDK overlay; dispose any leaked panel.
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('text filters commit after 1s idle — not per keystroke', () => {
    vi.useFakeTimers();
    const { page, httpTesting } = setup();

    page.onTextInput('recipient', 'a');
    page.onTextInput('recipient', 'ab');
    expect(page.facade.params().recipient).toBe(''); // not committed yet

    vi.advanceTimersByTime(1000);
    TestBed.tick();
    expect(page.facade.params().recipient).toBe('ab');
    httpTesting
      .expectOne('/rest/scheduled-mails/search?maxSize=20&page=0&recipient=ab')
      .flush({ data: [], size: 0, total: 0 });
  });

  it('blur commits immediately, cancelling the pending debounce', () => {
    vi.useFakeTimers();
    const { page, httpTesting } = setup();

    page.onTextInput('sender', 'example');
    page.commitText('sender');
    TestBed.tick();
    httpTesting
      .expectOne('/rest/scheduled-mails/search?maxSize=20&page=0&sender=example')
      .flush({ data: [], size: 0, total: 0 });

    vi.advanceTimersByTime(1000);
    TestBed.tick(); // the debounce timer must NOT fire a second commit/search
  });

  it('bounds the picked day by the DOMAIN day, keeping the day the user clicked', () => {
    const { page, httpTesting, fromPicker, toPicker } = setup();

    fromPicker.onDateSelected(clickJune11());
    TestBed.tick();
    // Halifax is west of the runner, so a browser-day boundary would have re-bucketed
    // this to 10 June — the day nobody clicked.
    expect(wallClockIn(DOMAIN_TZ, new Date(page.facade.params().fromDate!))).toBe(
      '2026-06-11 00:00:00',
    );
    httpTesting
      .expectOne((r) => r.url === '/rest/scheduled-mails/search' && r.params.has('from'))
      .flush({ data: [], size: 0, total: 0 });

    toPicker.onDateSelected(clickJune11());
    TestBed.tick();
    expect(wallClockIn(DOMAIN_TZ, new Date(page.facade.params().toDate!))).toBe(
      '2026-06-11 23:59:59',
    );
    // Inclusive to the last millisecond — a mail stamped at 23:59:59.500 is still inside.
    expect(page.facade.params().toDate! % 1000).toBe(999);
    httpTesting
      .expectOne((r) => r.url === '/rest/scheduled-mails/search' && r.params.has('to'))
      .flush({ data: [], size: 0, total: 0 });
  });

  it('states the exact instants for a fixed-offset zone (UTC)', () => {
    const { page, httpTesting, fromPicker, toPicker } = setup('UTC');

    fromPicker.onDateSelected(clickJune11());
    TestBed.tick();
    expect(new Date(page.facade.params().fromDate!).toISOString()).toBe('2026-06-11T00:00:00.000Z');
    httpTesting
      .expectOne((r) => r.url === '/rest/scheduled-mails/search' && r.params.has('from'))
      .flush({ data: [], size: 0, total: 0 });

    toPicker.onDateSelected(clickJune11());
    TestBed.tick();
    expect(new Date(page.facade.params().toDate!).toISOString()).toBe('2026-06-11T23:59:59.999Z');
    httpTesting
      .expectOne((r) => r.url === '/rest/scheduled-mails/search' && r.params.has('to'))
      .flush({ data: [], size: 0, total: 0 });
  });

  it('renders translated status pills — never the raw enum in the DOM', () => {
    const { fixture, httpTesting } = setup();
    // Trigger a refetch so the next response carries a row.
    (fixture.componentInstance as unknown as { facade: { refresh: () => void } }).facade.refresh();
    TestBed.tick();
    httpTesting.expectOne('/rest/scheduled-mails/search?maxSize=20&page=0').flush({
      data: [{ id: 1, status: 'SENT', sender: 's', subject: 'x', recipients: [] }],
      size: 1,
      total: 1,
    });
    fixture.detectChanges();
    const pill = fixture.nativeElement.querySelector('.status-badge');
    expect(pill).not.toBeNull();
    expect(pill.classList.contains('is-mail-sent')).toBe(true);
    expect(pill.textContent).toContain('admin.mails.status.sent');
    expect(fixture.nativeElement.textContent).not.toContain('SENT');
  });

  it('offers Held between Sent and Error, and its button searches status=HELD', () => {
    const { fixture, httpTesting } = setup();
    const buttons = Array.from(
      fixture.nativeElement.querySelectorAll('.seg .seg-btn') as NodeListOf<HTMLButtonElement>,
    );
    expect(buttons.map((button) => button.textContent?.trim())).toEqual([
      'admin.mails.status.all',
      'admin.mails.status.pending',
      'admin.mails.status.sent',
      'admin.mails.status.held',
      'admin.mails.status.error',
    ]);
    buttons[3].click();
    TestBed.tick();
    httpTesting
      .expectOne('/rest/scheduled-mails/search?maxSize=20&page=0&status=HELD')
      .flush({ data: [], size: 0, total: 0 });
    fixture.detectChanges();
    expect(buttons[3].classList.contains('is-active')).toBe(true);
  });

  it('renders a held mail with the held pill', () => {
    const { fixture, httpTesting } = setup();
    (fixture.componentInstance as unknown as { facade: { refresh: () => void } }).facade.refresh();
    TestBed.tick();
    httpTesting.expectOne('/rest/scheduled-mails/search?maxSize=20&page=0').flush({
      data: [{ id: 1, status: 'HELD', sender: 's', subject: 'x', recipients: [], attempts: 1 }],
      size: 1,
      total: 1,
    });
    fixture.detectChanges();
    const pill = fixture.nativeElement.querySelector('.status-badge');
    expect(pill.classList.contains('is-mail-held')).toBe(true);
    expect(pill.textContent).toContain('admin.mails.status.held');
  });

  it('dedupes the details fetch while one is in flight (no request races)', () => {
    const { page, httpTesting } = setup();
    const open = (page as unknown as { openMail: (mail: { id: number }) => void }).openMail.bind(
      page,
    );
    open({ id: 1 });
    open({ id: 2 }); // ignored — a fetch is in flight
    const reqs = httpTesting.match((r) => r.url.includes('/scheduled-mails/scheduled-mail/'));
    expect(reqs.length).toBe(1);
    reqs[0].flush({ id: 1, status: 'SENT', sender: 's', subject: 'x', recipients: [] });
  });

  it('summarizes recipients as first address + N others', () => {
    const { page } = setup();
    expect(page.firstRecipient({ recipients: [] })).toEqual({ address: '', others: 0 });
    expect(
      page.firstRecipient({
        recipients: [
          { recipientType: 'TO', address: 'a@x.dk' },
          { recipientType: 'CC', address: 'b@x.dk' },
          { recipientType: 'BCC', address: 'c@x.dk' },
        ],
      }),
    ).toEqual({ address: 'a@x.dk', others: 2 });
  });

  describe('Send again', () => {
    it('offers the row button on held, sent and failed mails, not on pending ones or records', () => {
      const s = setup();
      showRows(s, [
        mail(1, 'HELD'),
        mail(2, 'SENT'),
        mail(3, 'ERROR', { attempts: 5, lastError: 'Connection refused' }),
        mail(4, 'PENDING'),
        mail(5, 'ERROR', { attempts: 0, lastError: 'Not sent: no template' }),
      ]);
      const rows = Array.from(
        (s.fixture.nativeElement as HTMLElement).querySelectorAll('tbody tr'),
      );
      expect(rows.map((row) => row.querySelector('app-icon[name="lucideSend"]') !== null)).toEqual([
        true,
        true,
        true,
        false,
        false,
      ]);
      expect(sendAgainButtons(s.fixture)[0].title).toBe('admin.mails.sendAgain.title');
    });

    it('offers "Send all matching again" only while the status filter is Held or Error', () => {
      const s = setup();
      expect(sendAllButton(s.fixture)).toBeUndefined();
      filterStatus(s, 'HELD', [mail(1, 'HELD')]);
      expect(sendAllButton(s.fixture)?.disabled).toBe(false);
      filterStatus(s, 'SENT', [mail(2, 'SENT')]);
      expect(sendAllButton(s.fixture)).toBeUndefined();
      filterStatus(s, 'ERROR', []);
      expect(sendAllButton(s.fixture)?.disabled).toBe(true); // nothing matches
    });

    it('confirms a sent mail with its own message and every recipient, then posts it', () => {
      const s = setup();
      seedTexts();
      const sent = mail(7, 'SENT', {
        recipients: addresses(1, 12),
        sentAgainAt: 1_700_000_000_000,
      });
      showRows(s, [sent]);
      sendAgainButtons(s.fixture)[0].click();

      const confirm = activeConfirm(s.fixture)!;
      expect(confirm.intent).toBe('neutral');
      expect(confirm.header).toBe('Send again');
      expect(confirm.acceptLabel).toBe('Send again');
      expect(confirm.message).toBe('One sent mail');
      const created = TestBed.inject(LocaleFormatService).formatDateTime(new Date(sent.created!));
      expect(confirm.details).toEqual([
        `1 × Firing exercise · made ${created} · Sent · to ${addresses(1, 12)
          .map((r) => r.address)
          .join(', ')}`,
      ]);
      s.httpTesting.expectNone('/rest/scheduled-mails/send-again'); // nothing before accept

      accept(s.fixture);
      const req = s.httpTesting.expectOne('/rest/scheduled-mails/send-again');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({
        mails: [{ id: 7, status: 'SENT', sentAgainAt: 1_700_000_000_000 }],
      });
      req.flush({ queued: [7], refused: [] });
      expect(s.notifications.success).toHaveBeenCalledWith('admin.mails.sendAgain.queued', {
        count: 1,
      });
      // The list is read again, so the row shows the mail pending.
      answerSearch(s, [{ ...sent, status: 'PENDING' }]);
    });

    it('confirms a held mail with the general message and a null sentAgainAt', () => {
      const s = setup();
      seedTexts();
      showRows(s, [mail(3, 'HELD')]);
      sendAgainButtons(s.fixture)[0].click();
      expect(activeConfirm(s.fixture)?.message).toBe('One mail');
      accept(s.fixture);
      const req = s.httpTesting.expectOne('/rest/scheduled-mails/send-again');
      expect(req.request.body).toEqual({ mails: [{ id: 3, status: 'HELD', sentAgainAt: null }] });
      req.flush({ queued: [3], refused: [] });
      answerSearch(s, []);
    });

    it('groups the matching mails by subject, status and minute, oldest first, capping addresses', () => {
      const s = setup();
      seedTexts();
      filterStatus(s, 'HELD', [mail(1, 'HELD')]);
      sendAllButton(s.fixture)!.click();
      const older = mail(9, 'HELD', {
        created: RUN - 3_600_000,
        recipients: [{ recipientType: 'TO', address: 'b@example.org' }],
      });
      answerBulkRead(s, 'HELD', [
        mail(1, 'HELD', { recipients: addresses(1, 6) }),
        mail(2, 'HELD', { recipients: addresses(5, 10) }),
        mail(3, 'HELD', { recipients: addresses(11, 12) }),
        mail(4, 'HELD', { subject: 'Another subject' }),
        older,
      ]);

      const confirm = activeConfirm(s.fixture)!;
      expect(confirm.message).toBe('Send these 5 mails again');
      const format = (epoch: number) =>
        TestBed.inject(LocaleFormatService).formatDateTime(new Date(epoch));
      const first10 = addresses(1, 10)
        .map((r) => r.address)
        .join(', ');
      expect(confirm.details).toEqual([
        `1 × Firing exercise · made ${format(older.created!)} · Held · to b@example.org`,
        `3 × Firing exercise · made ${format(RUN + 1000)} · Held · to ${first10} and 2 more`,
        `1 × Another subject · made ${format(RUN + 4000)} · Held · to to4@example.org`,
      ]);

      accept(s.fixture);
      const req = s.httpTesting.expectOne('/rest/scheduled-mails/send-again');
      expect(req.request.body.mails.map((item: { id: number }) => item.id)).toEqual([
        1, 2, 3, 4, 9,
      ]);
      req.flush({ queued: [1, 2, 3, 4, 9], refused: [] });
      expect(s.notifications.success).toHaveBeenCalledWith('admin.mails.sendAgain.queued', {
        count: 5,
      });
      answerSearch(s, [], 'HELD');
    });

    it('sends nothing when more than 500 mails match', () => {
      const s = setup();
      filterStatus(s, 'HELD', [mail(1, 'HELD')]);
      sendAllButton(s.fixture)!.click();
      answerBulkRead(s, 'HELD', [mail(1, 'HELD')], 501);
      expect(s.notifications.warn).toHaveBeenCalledWith('admin.mails.sendAgain.tooMany', {
        total: 501,
        max: 500,
      });
      expect(activeConfirm(s.fixture)).toBeUndefined();
      s.httpTesting.expectNone('/rest/scheduled-mails/send-again');
    });

    it('leaves failure records out, and says so when only records match', () => {
      const s = setup();
      seedTexts();
      const record = mail(1, 'ERROR', { attempts: 0, lastError: 'Not sent: no template' });
      const failed = mail(2, 'ERROR', { attempts: 5, lastError: 'Connection refused' });
      filterStatus(s, 'ERROR', [record, failed]);

      sendAllButton(s.fixture)!.click();
      answerBulkRead(s, 'ERROR', [record]);
      expect(s.notifications.info).toHaveBeenCalledWith('admin.mails.sendAgain.nothing');
      expect(activeConfirm(s.fixture)).toBeUndefined();

      sendAllButton(s.fixture)!.click();
      answerBulkRead(s, 'ERROR', [record, failed]);
      expect(activeConfirm(s.fixture)?.details).toHaveLength(1);
      accept(s.fixture);
      const req = s.httpTesting.expectOne('/rest/scheduled-mails/send-again');
      expect(req.request.body).toEqual({ mails: [{ id: 2, status: 'ERROR', sentAgainAt: null }] });
      req.flush({ queued: [2], refused: [] });
      answerSearch(s, [], 'ERROR');
    });

    it('says how many were left as they were when the backend refused some', () => {
      const s = setup();
      filterStatus(s, 'HELD', [mail(1, 'HELD'), mail(2, 'HELD')]);
      sendAllButton(s.fixture)!.click();
      answerBulkRead(s, 'HELD', [mail(1, 'HELD'), mail(2, 'HELD')]);
      accept(s.fixture);
      s.httpTesting
        .expectOne('/rest/scheduled-mails/send-again')
        .flush({ queued: [1], refused: [{ id: 2, reason: 'CHANGED' }] });
      expect(s.notifications.warn).toHaveBeenCalledWith('admin.mails.sendAgain.partly', {
        queued: 1,
        refused: 1,
      });
      expect(s.notifications.success).not.toHaveBeenCalled();
      answerSearch(s, [], 'HELD');
    });

    it("shows the backend's reason when it refuses the request while the filter is LOG", () => {
      const s = setup();
      showRows(s, [mail(1, 'HELD')]);
      sendAgainButtons(s.fixture)[0].click();
      accept(s.fixture);
      const detail =
        'The mail filter (the setting mailValidRecipients) is LOG, so the mails would only be held again. Set it to ALL on the Settings page first.';
      s.httpTesting
        .expectOne('/rest/scheduled-mails/send-again')
        .flush(
          { type: 'about:blank', title: 'Conflict', status: 409, detail },
          { status: 409, statusText: 'Conflict' },
        );
      expect(s.notifications.error).toHaveBeenCalledWith('admin.mails.sendAgain.refused', {
        detail,
      });
      // Nothing changed, so the list is not read again (verify() in afterEach).
    });

    it('says nothing was changed when the request fails otherwise, never the raw error', () => {
      const s = setup();
      showRows(s, [mail(1, 'HELD')]);
      sendAgainButtons(s.fixture)[0].click();
      accept(s.fixture);
      s.httpTesting
        .expectOne('/rest/scheduled-mails/send-again')
        .flush(
          { status: 500, detail: 'java.lang.IllegalStateException: boom' },
          { status: 500, statusText: 'Server Error' },
        );
      expect(s.notifications.error).toHaveBeenCalledExactlyOnceWith('admin.mails.sendAgain.error');
    });

    it('blocks a second request while one runs', () => {
      const s = setup();
      showRows(s, [mail(1, 'HELD')]);
      sendAgainButtons(s.fixture)[0].click();
      accept(s.fixture);
      s.fixture.detectChanges();
      expect(sendAgainButtons(s.fixture)[0].disabled).toBe(true);
      sendAgainButtons(s.fixture)[0].click(); // a disabled button: no second confirmation
      expect(activeConfirm(s.fixture)).toBeUndefined();
      s.httpTesting
        .expectOne('/rest/scheduled-mails/send-again')
        .flush({ queued: [1], refused: [] });
      answerSearch(s, []);
    });
  });
});
