import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { of, Subject, type Observable } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import en from '../../../../../../../../languages/en/i18n/admin/en.json';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import type { MessageReport } from '../../../../shared/models/print/message-report.model';
import {
  DEFAULT_PRINT_SETTINGS,
  type PrintSettings,
  type ReportParams,
} from '../../../../shared/models/print/print-settings.model';
import { provideFakeAuthFacade } from '../../../../shared/testing/fake-auth';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import {
  MessageReportsApiService,
  type ListReportsOptions,
} from '../../services/api/message-reports-api.service';
import { MessagePrintSettings } from './message-print-settings';

function report(reportId: string): MessageReport {
  return { reportId, name: reportId, properties: {}, params: {} };
}

/** What /message-reports/reports answers in the base product: the three built-in reports. */
const LIST_REPORTS = [report('standard'), report('draft'), report('public')];

/** What /message-reports/detail-reports answers. */
const DETAIL_REPORTS = [report('standard'), report('draft')];

@Component({
  imports: [MessagePrintSettings],
  template: `<app-message-print-settings
    [list]="list()"
    [report]="report()"
    [(printSettings)]="printSettings"
    [(reportParams)]="reportParams"
  />`,
})
class Host {
  readonly list = signal(true);
  readonly report = signal<MessageReport | undefined>(undefined);
  readonly printSettings = signal<PrintSettings>({ ...DEFAULT_PRINT_SETTINGS });
  readonly reportParams = signal<ReportParams>({});
}

interface SetupOptions {
  readonly isAuthenticated: boolean;
  readonly list?: boolean;
  readonly listReports?: readonly MessageReport[];
  readonly detailReports?: readonly MessageReport[];
  readonly initialReport?: string;
  /** Load the English administration texts, to read the labels as an English user does. */
  readonly english?: boolean;
}

async function setup(opts: SetupOptions) {
  const listReports = vi.fn<(o: ListReportsOptions) => Observable<readonly MessageReport[]>>((o) =>
    of(o.forList ? (opts.listReports ?? LIST_REPORTS) : (opts.detailReports ?? DETAIL_REPORTS)),
  );
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      provideFakeAuthFacade({ isAuthenticated: opts.isAuthenticated }),
      { provide: MessageReportsApiService, useValue: { listReports } },
      {
        provide: LanguageService,
        useValue: { modelLanguages: [], selectedLanguage: () => 'en' },
      },
      { provide: NotificationService, useValue: { error: vi.fn() } },
    ],
  });
  const transloco = TestBed.inject(TranslocoService);
  transloco.load('en').subscribe();
  if (opts.english) transloco.setTranslation(en, 'en');
  const fixture = TestBed.createComponent(Host);
  const host = fixture.componentInstance;
  host.list.set(opts.list ?? true);
  if (opts.initialReport !== undefined) {
    host.printSettings.set({ ...DEFAULT_PRINT_SETTINGS, report: opts.initialReport });
  }
  fixture.autoDetectChanges();
  await fixture.whenStable();
  const settings = fixture.debugElement.children[0].componentInstance as MessagePrintSettings;
  const offered = () =>
    (settings as unknown as { reportOptions: () => { value: string }[] })
      .reportOptions()
      .map((o) => o.value);
  return { fixture, host, offered, listReports };
}

describe('MessagePrintSettings: the reports offered', () => {
  it('offers a signed-out reader printing a list only the public report, and chooses it', async () => {
    const { host, offered } = await setup({ isAuthenticated: false });

    expect(offered()).toEqual(['public']);
    expect(host.printSettings().report).toBe('public');
  });

  it('replaces a report the backend refuses a signed-out list print with the public one', async () => {
    const { host, offered } = await setup({ isAuthenticated: false, initialReport: 'standard' });

    expect(offered()).toEqual(['public']);
    expect(host.printSettings().report).toBe('public');
  });

  it('chooses no report for a signed-out list print when the deployment has no public report', async () => {
    const { host, offered } = await setup({
      isAuthenticated: false,
      listReports: [report('standard'), report('draft')],
      initialReport: 'standard',
    });

    expect(offered()).toEqual([]);
    expect(host.printSettings().report).toBeUndefined();
  });

  it('offers a signed-in user every list report and keeps the first as the default', async () => {
    const { host, offered } = await setup({ isAuthenticated: true });

    expect(offered()).toEqual(['standard', 'draft', 'public']);
    expect(host.printSettings().report).toBe('standard');
  });

  it("keeps a signed-in user's chosen report", async () => {
    const { host } = await setup({ isAuthenticated: true, initialReport: 'draft' });

    expect(host.printSettings().report).toBe('draft');
  });

  it('gives a signed-out reader printing one message a report of that list, not the public list report', async () => {
    // A guest who printed a list holds 'public', which the backend refuses for one message
    // (it serves an anonymous caller the standard report there).
    const { host } = await setup({ isAuthenticated: false, list: false, initialReport: 'public' });

    expect(host.printSettings().report).toBe('standard');
  });

  it('loads the reports again for the new mode when the dialog switches between a list and one message', async () => {
    const { fixture, host, offered } = await setup({ isAuthenticated: false });
    expect(host.printSettings().report).toBe('public');

    host.list.set(false);
    await fixture.whenStable();

    expect(offered()).toEqual(['standard', 'draft']);
    expect(host.printSettings().report).toBe('standard');
  });

  it('ignores an answer that a later load has overtaken', async () => {
    const first = new Subject<readonly MessageReport[]>();
    const { fixture, host, listReports } = await setup({ isAuthenticated: false });
    listReports.mockImplementationOnce(() => first);
    host.list.set(false);
    await fixture.whenStable();
    host.list.set(true);
    await fixture.whenStable();

    first.next(DETAIL_REPORTS);
    await fixture.whenStable();

    expect(host.printSettings().report).toBe('public');
  });
});

describe('MessagePrintSettings: a test print of a report', () => {
  // The print reports page's Test hands the dialog the in-edit report, which may be unsaved or of
  // another domain; a select of the reports loaded earlier would show it empty.
  it('shows no Report select for a hardcoded report, whose dialog header names it', async () => {
    const { fixture, host } = await setup({ isAuthenticated: true });
    expect(fixture.nativeElement.querySelector('#printReport')).not.toBeNull();

    host.report.set({
      reportId: 'nightly',
      name: 'Nightly',
      properties: {},
      params: { title: 'Notices 2026' },
    });
    await fixture.whenStable();

    expect(fixture.nativeElement.querySelector('#printReport')).toBeNull();
    expect(host.printSettings().report).toBe('nightly');
    expect(host.reportParams()).toEqual({ title: 'Notices 2026' });
  });
});

describe('MessagePrintSettings: the labels', () => {
  it('names the two layouts as the publication series editor does', async () => {
    const { fixture } = await setup({ isAuthenticated: true, english: true });

    const layouts = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('.seg-btn'),
    ).map((button) => button.textContent?.trim());
    expect(layouts).toEqual(['Portrait', 'Landscape']);
  });
});
