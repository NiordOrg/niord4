import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainService } from '../../../../../core/domain/services/domain.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { DatePicker } from '../../../../../shared/forms/date-picker/date-picker';
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
  facade: {
    params: () => { recipient: string; fromDate?: number; toDate?: number };
  };
}

/** The picker's own selection entry point — the page is fed by it, never by a raw Date. */
interface PickerInternals {
  onDateSelected: (date: Date) => void;
}

function setup(timeZone: string = DOMAIN_TZ) {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      {
        provide: NotificationService,
        useValue: { success: () => undefined, error: () => undefined },
      },
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
  };
}

/** A click on the calendar's 11 June cell: the grid always hands back a browser-local day. */
function clickJune11(): Date {
  return new Date(2026, 5, 11);
}

describe('MailsPage', () => {
  afterEach(() => {
    vi.useRealTimers();
    TestBed.inject(HttpTestingController).verify();
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
});
