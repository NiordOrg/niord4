import { Component, computed, DestroyRef, effect, inject, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { take } from 'rxjs';

import { AuthFacade } from '../../../../core/auth/facades/auth.facade';
import { AuthTicketService } from '../../../../core/auth/services/auth-ticket.service';
import { HelpAnchor } from '../../../../core/main-shell/help/help-anchor';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { Dialog } from '../../../../shared/components/dialog/dialog';
import { Icon } from '../../../../shared/icons/icon';
import type {
  PrintSettings,
  ReportParams,
} from '../../../../shared/models/print/print-settings.model';
import { MessagePrintSettings } from '../../components/message-print-settings/message-print-settings';
import { PrintSettingsStorageService } from '../../services/print-settings-storage.service';
import { MessagePrintDialogService } from './message-print-dialog.service';

/**
 * The print dialog, drawn by its host: the Messages page, the editor shell and the print reports
 * page each mount one. `MessagePrintDialogService` (root) holds what the caller asked for; this
 * component shows it and, on Print, fetches the one-time ticket the PDF URL carries.
 *
 * The ticket asks for the `user` role whenever the user holds it in the session domain: a ticket
 * asked for with no role names the user but grants no role, so the backend would treat the PDF as a
 * guest's, leave the non-public messages out of a list and refuse a single draft or verified
 * message with 403. A guest, or a signed-in user with no role in the session domain, asks with no
 * role, since the backend refuses a role the caller does not hold.
 *
 * A guest prints only with a report the settings chose for them (`MessagePrintSettings`, which offers
 * a guest only what the backend prints for one); until then, or when the deployment has no such
 * report, Print is disabled, since a print without a report would end on the backend's error page.
 *
 * A host that goes away while the dialog is open dismisses it, so its caller resolves with
 * `undefined` and the next host does not open it unasked.
 */
@Component({
  selector: 'app-message-print-dialog',
  templateUrl: './message-print-dialog.html',
  imports: [Dialog, HelpAnchor, Icon, MessagePrintSettings, TranslocoPipe],
  host: {
    '(document:keydown.enter)': 'onEnterKey()',
  },
})
export class MessagePrintDialog {
  protected readonly dialog = inject(MessagePrintDialogService);
  private readonly storage = inject(PrintSettingsStorageService);
  private readonly auth = inject(AuthFacade);
  private readonly authTicket = inject(AuthTicketService);
  private readonly notifications = inject(NotificationService);

  protected readonly printSettings = signal<PrintSettings>(this.storage.load());
  protected readonly reportParams = signal<ReportParams>({});
  protected readonly submitting = signal(false);

  protected readonly listMode = computed(() => this.dialog.list());
  protected readonly hardcodedReport = computed(() => this.dialog.report());
  protected readonly totalMessageNo = computed(() => this.dialog.totalMessageNo());

  /**
   * The header's text. A test print of a report (the print reports page's Test, the one caller that
   * sets a report) names the report and claims no count: without a tag it prints the current
   * domain's list, whose size the page does not know.
   */
  protected readonly title = computed(() => {
    const report = this.hardcodedReport();
    return report
      ? {
          key: 'messages.print.dialog.testHeader',
          params: { report: report.name || report.reportId },
        }
      : {
          key: 'messages.print.dialog.header',
          params: { totalMessageNo: this.totalMessageNo() },
        };
  });

  /** Whether Print may be pressed: always for a signed-in user, for a guest once a report is set. */
  protected readonly canPrint = computed(
    () =>
      this.auth.isAuthenticated() ||
      this.hardcodedReport() !== undefined ||
      !!this.printSettings().report,
  );

  constructor() {
    inject(DestroyRef).onDestroy(() => this.dialog.dismiss());
    effect(() => {
      if (this.dialog.visible()) {
        this.reportParams.set({});
        this.submitting.set(false);
      }
    });
  }

  protected onPrint(): void {
    if (this.submitting() || !this.canPrint()) return;
    this.submitting.set(true);
    this.storage.save(this.printSettings());
    this.authTicket
      .fetch(this.auth.hasDomainUserRole() ? 'user' : undefined)
      .pipe(take(1))
      .subscribe({
        next: (ticket) => {
          this.submitting.set(false);
          this.dialog.submit({
            settings: this.printSettings(),
            reportParams: this.reportParams(),
            ticket,
          });
        },
        error: () => {
          this.submitting.set(false);
          this.notifications.error('messages.print.errorTicket');
        },
      });
  }

  protected onEnterKey(): void {
    if (!this.dialog.visible()) return;
    this.onPrint();
  }
}
