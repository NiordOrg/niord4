import { Component, input, model, type WritableSignal } from '@angular/core';
import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { Observable, of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import en from '../../../../../../../../languages/en/i18n/admin/en.json';
import {
  AuthTicketService,
  type TicketRole,
} from '../../../../core/auth/services/auth-ticket.service';
import { HelpAnchor } from '../../../../core/main-shell/help/help-anchor';
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
import { MessagePrintSettings } from '../../components/message-print-settings/message-print-settings';
import { PrintSettingsStorageService } from '../../services/print-settings-storage.service';
import { MessagePrintDialog } from './message-print-dialog';
import { MessagePrintDialogService, type PrintRequest } from './message-print-dialog.service';

@Component({ selector: 'app-message-print-settings', template: '' })
class StubPrintSettings {
  readonly list = input.required<boolean>();
  readonly report = input<MessageReport | undefined>(undefined);
  readonly printSettings = model<PrintSettings>({ ...DEFAULT_PRINT_SETTINGS });
  readonly reportParams = model<ReportParams>({});
}

@Component({ selector: 'app-help-anchor', template: '' })
class StubHelpAnchor {
  readonly section = input<string>();
}

async function setup(
  opts: { isAuthenticated?: boolean; domainUserRole?: boolean; english?: boolean } = {},
) {
  const fetch = vi.fn<(role?: TicketRole) => Observable<string>>(() => of('TICKET-1'));
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      provideFakeAuthFacade({
        isAuthenticated: opts.isAuthenticated ?? true,
        domainUserRole: opts.domainUserRole,
      }),
      { provide: AuthTicketService, useValue: { fetch } },
      {
        provide: PrintSettingsStorageService,
        useValue: { load: () => ({ ...DEFAULT_PRINT_SETTINGS }), save: vi.fn() },
      },
      {
        provide: NotificationService,
        useValue: { error: vi.fn(), success: vi.fn(), info: vi.fn(), warn: vi.fn() },
      },
    ],
  });
  TestBed.overrideComponent(MessagePrintDialog, {
    remove: { imports: [MessagePrintSettings, HelpAnchor] },
    add: { imports: [StubPrintSettings, StubHelpAnchor] },
  });
  TestBed.inject(TranslocoService).load('en').subscribe();
  if (opts.english) {
    TestBed.inject(TranslocoService).setTranslation(en, 'en', { merge: false });
  }

  const fixture = TestBed.createComponent(MessagePrintDialog);
  const service = TestBed.inject(MessagePrintDialogService);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  return { fixture, service, fetch };
}

const printBtn = () => document.querySelector('.dialog-foot .btn-primary') as HTMLButtonElement;
const headerText = () =>
  document.querySelector('.dialog-header .font-semibold')?.textContent?.trim();

/** What the settings do when they pick a report: set it on the dialog's print settings. */
function chooseReport(fixture: ComponentFixture<MessagePrintDialog>, report: string): void {
  const dialog = fixture.componentInstance as unknown as {
    printSettings: WritableSignal<PrintSettings>;
  };
  dialog.printSettings.update((s) => ({ ...s, report }));
}

afterEach(() => {
  document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
});

describe('MessagePrintDialog', () => {
  it('dismisses an open dialog when its host goes away, so it does not open unasked on the next host', async () => {
    const { fixture, service } = await setup();
    const results: (PrintRequest | undefined)[] = [];
    service.open({ totalMessageNo: 1, list: false }).subscribe((r) => results.push(r));
    await fixture.whenStable();
    expect(service.visible()).toBe(true);

    fixture.destroy();

    expect(results).toEqual([undefined]);
    expect(service.visible()).toBe(false);
  });

  it('asks for a user ticket when the user holds the user role in the session domain', async () => {
    const { fixture, service, fetch } = await setup({ domainUserRole: true });
    const results: (PrintRequest | undefined)[] = [];
    service.open({ totalMessageNo: 1, list: false }).subscribe((r) => results.push(r));
    await fixture.whenStable();

    printBtn().click();
    await fixture.whenStable();

    expect(fetch).toHaveBeenCalledWith('user');
    expect(results).toHaveLength(1);
    expect(results[0]?.ticket).toBe('TICKET-1');
  });

  it('asks for a ticket without a role for a signed-in user with no role in the session domain', async () => {
    const { fixture, service, fetch } = await setup({ domainUserRole: false });
    service.open({ totalMessageNo: 3, list: true }).subscribe();
    await fixture.whenStable();

    printBtn().click();
    await fixture.whenStable();

    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls[0]?.[0]).toBeUndefined();
  });

  it('asks for a ticket without a role for a guest', async () => {
    const { fixture, service, fetch } = await setup({ isAuthenticated: false });
    service.open({ totalMessageNo: 3, list: true }).subscribe();
    await fixture.whenStable();
    chooseReport(fixture, 'public');
    await fixture.whenStable();

    printBtn().click();
    await fixture.whenStable();

    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls[0]?.[0]).toBeUndefined();
  });

  // The settings offer a guest only the report the backend prints for one, and choose none when the
  // deployment has no such report; a print without one would end on the backend's error page.
  it('lets a guest print nothing while no report is chosen', async () => {
    const { fixture, service, fetch } = await setup({ isAuthenticated: false });
    const results: (PrintRequest | undefined)[] = [];
    service.open({ totalMessageNo: 3, list: true }).subscribe((r) => results.push(r));
    await fixture.whenStable();

    expect(printBtn().disabled).toBe(true);
    printBtn().click();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    await fixture.whenStable();

    expect(fetch).not.toHaveBeenCalled();
    expect(results).toEqual([]);
  });

  it('lets a signed-in user print with no report chosen, which the backend answers with its standard one', async () => {
    const { fixture, service } = await setup({ domainUserRole: true });
    service.open({ totalMessageNo: 3, list: true }).subscribe();
    await fixture.whenStable();

    expect(printBtn().disabled).toBe(false);
  });

  it('counts the messages in the header of a list print', async () => {
    const { fixture, service } = await setup();
    service.open({ totalMessageNo: 3, list: true }).subscribe();
    await fixture.whenStable();

    expect(headerText()).toBe('messages.print.dialog.header');
  });

  // The print reports page's Test prints the current domain's list without a tag, whose size it
  // does not know; the header names the report and claims no count.
  it('names the report in the header of a test print and claims no count', async () => {
    const { fixture, service } = await setup();
    service
      .open({
        totalMessageNo: 0,
        list: true,
        report: { reportId: 'nightly', name: 'Nightly list', properties: {}, params: {} },
      })
      .subscribe();
    await fixture.whenStable();

    expect(headerText()).toBe('messages.print.dialog.testHeader');
    expect(document.body.textContent).not.toContain('messages.print.dialog.header');
  });
});

describe('MessagePrintDialog: the English header', () => {
  it('reads "Test print of" and the report name for a test print', async () => {
    const { fixture, service } = await setup({ english: true });
    service
      .open({
        totalMessageNo: 0,
        list: true,
        report: { reportId: 'nightly', name: 'Nightly list', properties: {}, params: {} },
      })
      .subscribe();
    await fixture.whenStable();

    expect(headerText()).toBe('Test print of Nightly list');
  });

  it('falls back to the report id when an unsaved report has no name yet', async () => {
    const { fixture, service } = await setup({ english: true });
    service
      .open({
        totalMessageNo: 0,
        list: true,
        report: { reportId: 'nightly', name: '', properties: {}, params: {} },
      })
      .subscribe();
    await fixture.whenStable();

    expect(headerText()).toBe('Test print of nightly');
  });
});
