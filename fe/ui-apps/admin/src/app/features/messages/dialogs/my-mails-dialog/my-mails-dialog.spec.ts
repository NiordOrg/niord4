import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { EMPTY } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LocaleFormatService } from '../../../../core/main-shell/services/locale-format.service';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import type { MyMailVo } from '../../services/api/my-mails-api.service';
import { MyMailsDialog } from './my-mails-dialog';

const URL = '/rest/scheduled-mails/mine?days=7';

function setup() {
  const formatDateTime = vi.fn((value: number) => `zoned:${value}`);
  TestBed.configureTestingModule({
    providers: [
      {
        provide: APP_CONFIG,
        useValue: {
          apiBaseUrl: '/rest',
          appName: 'Niord',
          documentationUrl: 'https://docs.example',
        },
      },
      {
        provide: Router,
        // HelpAnchor's HelpUrlService reads router.events + routerState.snapshot eagerly.
        useValue: {
          navigate: vi.fn(),
          url: '/',
          events: EMPTY,
          routerState: { snapshot: { root: { data: {}, firstChild: null } } },
        },
      },
      { provide: LocaleFormatService, useValue: { formatDateTime } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
    ],
  });
  const fixture = TestBed.createComponent(MyMailsDialog);
  fixture.detectChanges();
  return { fixture, httpTesting: TestBed.inject(HttpTestingController), formatDateTime };
}

/** Lets the resource take the answer in and the view show it. */
async function settle(fixture: { detectChanges: () => void }): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
  fixture.detectChanges();
}

/** Opens the dialog and answers its read with the given body (or an error). */
async function openWith(body: readonly MyMailVo[] | 'error') {
  const s = setup();
  s.fixture.componentInstance.open();
  s.fixture.detectChanges();
  const req = s.httpTesting.expectOne(URL);
  expect(req.request.method).toBe('GET');
  if (body === 'error') {
    req.flush('boom', { status: 500, statusText: 'Server Error' });
  } else {
    req.flush(body);
  }
  await settle(s.fixture);
  return s;
}

function text(): string {
  return document.querySelector('.dialog-panel')?.textContent ?? '';
}

const mail = (vo: Partial<MyMailVo> & Pick<MyMailVo, 'id' | 'status'>): MyMailVo => ({
  created: 1_700_000_000_000,
  subject: `Mail ${vo.id}`,
  recipients: [{ recipientType: 'TO', address: `to${vo.id}@example.org` }],
  attempts: 0,
  ...vo,
});

describe('MyMailsDialog', () => {
  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    // The dialog portals its panel to the body-level CDK overlay; dispose leaks.
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
  });

  it('reads nothing while closed', () => {
    const { httpTesting } = setup();
    httpTesting.expectNone(URL);
  });

  it('labels each status as the sender reads it, with the last error where it failed', async () => {
    await openWith([
      mail({ id: 1, status: 'SENT', attempts: 1 }),
      mail({ id: 2, status: 'PENDING', attempts: 0 }),
      mail({ id: 3, status: 'PENDING', attempts: 2, lastError: 'Relay busy' }),
      mail({ id: 4, status: 'ERROR', attempts: 5, lastError: 'Connection refused' }),
    ]);
    const rows = Array.from(document.querySelectorAll('.dialog-panel tbody tr'));
    expect(rows.map((row) => row.querySelector('.status-badge')?.textContent?.trim())).toEqual([
      'messages.myMails.status.sent',
      'messages.myMails.status.waiting',
      'messages.myMails.status.retrying',
      'messages.myMails.status.error',
    ]);
    expect(rows[0].textContent).not.toContain('messages.myMails.lastError');
    expect(rows[1].textContent).not.toContain('messages.myMails.lastError');
    expect(rows[2].textContent).toContain('messages.myMails.lastError');
    expect(rows[3].textContent).toContain('messages.myMails.lastError');
  });

  it('says a held mail was not delivered, with the held pill and no error line', async () => {
    await openWith([mail({ id: 5, status: 'HELD', attempts: 1 })]);
    const row = document.querySelector('.dialog-panel tbody tr');
    const pill = row?.querySelector('.status-badge');
    expect(pill?.textContent?.trim()).toBe('messages.myMails.status.held');
    expect(pill?.classList.contains('is-mail-held')).toBe(true);
    expect(row?.textContent).not.toContain('messages.myMails.lastError');
  });

  it('shows the subject, the recipients and the creation time in the domain zone', async () => {
    const { formatDateTime } = await openWith([
      mail({
        id: 7,
        status: 'SENT',
        created: 1_710_000_000_000,
        subject: 'Firing exercise',
        recipients: [
          { recipientType: 'TO', address: 'a@example.org' },
          { recipientType: 'CC', address: 'b@example.org' },
        ],
      }),
    ]);
    expect(formatDateTime).toHaveBeenCalledWith(1_710_000_000_000);
    const row = text();
    expect(row).toContain('zoned:1710000000000');
    expect(row).toContain('Firing exercise');
    expect(row).toContain('a@example.org, b@example.org');
  });

  it('reads the creation time the backend writes as ISO text as epoch millis', async () => {
    const { formatDateTime } = await openWith([
      mail({ id: 8, status: 'HELD', attempts: 1, created: '2026-11-19T09:27:20.142+00:00' }),
    ]);
    const created = Date.UTC(2026, 10, 19, 9, 27, 20, 142);
    expect(formatDateTime).toHaveBeenCalledWith(created);
    expect(text()).toContain(`zoned:${created}`);
  });

  it('takes a mail without recipients or attempts on the wire', async () => {
    await openWith([{ id: 9, status: 'PENDING', subject: 'Bare', recipients: null }]);
    expect(text()).toContain('messages.myMails.status.waiting');
  });

  it('says so when there is no mail', async () => {
    await openWith([]);
    expect(text()).toContain('messages.myMails.empty');
    expect(document.querySelector('.dialog-panel table')).toBeNull();
  });

  it('says so when the read fails', async () => {
    await openWith('error');
    expect(text()).toContain('messages.myMails.loadError');
    expect(document.querySelector('.dialog-panel table')).toBeNull();
  });

  it('reads the list again at each opening', async () => {
    const { fixture, httpTesting } = await openWith([]);
    fixture.componentInstance['close']();
    fixture.detectChanges();
    fixture.componentInstance.open();
    fixture.detectChanges();
    httpTesting.expectOne(URL).flush([mail({ id: 1, status: 'SENT' })]);
    await settle(fixture);
    expect(text()).toContain('messages.myMails.status.sent');
  });

  it('renders a help anchor pointing at the my-mails-action manual section', async () => {
    await openWith([]);
    const link = document.querySelector<HTMLAnchorElement>(
      'a[href="https://docs.example/editor-manual/manual.html#my-mails-action"]',
    );
    expect(link).not.toBeNull();
  });
});
