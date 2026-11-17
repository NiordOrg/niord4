import { Component, inject, signal } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { TicketedDownloadService } from '../../../../core/auth/services/ticketed-download.service';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../core/main-shell/services/locale-format.service';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { Dialog } from '../../../../shared/components/dialog/dialog';
import { HelpAnchor } from '../../../../core/main-shell/help/help-anchor';
import { SandboxedHtml } from '../../../../shared/components/sandboxed-html/sandboxed-html';
import { Icon } from '../../../../shared/icons/icon';
import { parseWallClockTime } from '../../../../shared/utils/zoned-date';
import {
  MailingListReportsApiService,
  type MailingListReport,
} from '../../services/api/mailing-list-reports-api.service';

/**
 * The public mailing-list reports dialog, opened from the messages Actions menu (login-gated). Lists
 * the reports (SCHEDULED triggers flagged `publicReport`); Execute renders the report HTML in the
 * sandboxed iframe (never `[innerHTML]`); "Open in new window" fetches a `user`-role ticket and
 * opens the ticketed report URL. Homed in the messages feature (where it opens) with its own
 * messages-local reports API service, so the admin→messages feature dependency stays one-way.
 */
@Component({
  selector: 'app-mailing-list-reports-dialog',
  imports: [Dialog, HelpAnchor, SandboxedHtml, Icon, TranslocoPipe],
  templateUrl: './mailing-list-reports-dialog.html',
  host: { class: 'contents' },
})
export class MailingListReportsDialog {
  private readonly api = inject(MailingListReportsApiService);
  private readonly language = inject(LanguageService);
  private readonly localeFormat = inject(LocaleFormatService);
  private readonly downloads = inject(TicketedDownloadService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);

  protected readonly visible = signal(false);
  /** `undefined` while loading (distinct from the empty "No report defined" state). */
  protected readonly reports = signal<MailingListReport[] | undefined>(undefined);
  protected readonly result = signal<string | undefined>(undefined);
  /** The report whose result is currently shown — drives the "open in new window" action. */
  protected readonly resultReport = signal<MailingListReport | undefined>(undefined);
  protected readonly executing = signal(false);

  open(): void {
    this.reports.set(undefined);
    this.result.set(undefined);
    this.resultReport.set(undefined);
    this.executing.set(false);
    this.visible.set(true);
    this.api.getReports(this.language.selectedLanguage()).subscribe({
      next: (reports) => this.reports.set(reports),
      error: () => {
        this.reports.set([]);
        this.notifications.error('messages.mailingListReports.loadError');
      },
    });
  }

  protected onVisibleChange(visible: boolean): void {
    this.visible.set(visible);
  }

  protected scheduleText(report: MailingListReport): string {
    return [
      this.scheduleTypeLabel(report.scheduleType),
      this.localeTime(report.scheduledExecutionTime),
      report.scheduledExecutionTimeZone,
    ]
      .filter(Boolean)
      .join(' · ');
  }

  /** Localize the schedule frequency (DAILY / MONDAY / …); pass through an unknown value. */
  private scheduleTypeLabel(scheduleType: string | undefined): string | undefined {
    if (!scheduleType) {
      return undefined;
    }
    const key = `messages.mailingListReports.scheduleType.${scheduleType}`;
    const label = this.transloco.translate(key);
    return label === key ? scheduleType : label;
  }

  /** Format a backend `HH:mm` string locale-aware (12h/24h per locale); pass through if unparseable. */
  private localeTime(value: string | undefined): string | undefined {
    const carrier = parseWallClockTime(value);
    if (!carrier) {
      return value;
    }
    return this.localeFormat.formatTime(carrier, { local: true });
  }

  protected execute(report: MailingListReport): void {
    if (this.executing()) {
      return;
    }
    this.executing.set(true);
    this.api.executeReport(report.id, this.language.selectedLanguage()).subscribe({
      next: (html) => {
        this.executing.set(false);
        this.result.set(html);
        this.resultReport.set(report);
      },
      error: () => {
        this.executing.set(false);
        this.notifications.error('messages.mailingListReports.executeError');
      },
    });
  }

  protected openInNewWindow(report: MailingListReport): void {
    void this.downloads.openTicketed(
      this.api.executeReportUrl(report.id, this.language.selectedLanguage()),
      'user',
    );
  }
}
