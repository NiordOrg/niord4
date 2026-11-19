import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../../../../shared/icons/icon';
import { DomainService } from '../../../../../core/domain/services/domain.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { DatePicker } from '../../../../../shared/forms/date-picker/date-picker';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { Pager } from '../../../../../shared/components/pager/pager';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { endOfDayInZone } from '../../../../../shared/utils/zoned-date';
import {
  ScheduledMail,
  ScheduledMailStatus,
  ScheduledMailsApiService,
} from '../../../services/api/scheduled-mails-api.service';
import { AdminSectionPage } from '../../../shell/admin-section-page';
import { ADMIN_TEXT_FILTER_DEBOUNCE_MS } from '../../../utils/kit-constants';
import { MailDetailsDialog } from './mail-details-dialog';
import { ScheduledMailsFacade } from './scheduled-mails.facade';

type TextParam = 'recipient' | 'sender' | 'subject';

/**
 * Mail-queue search: server-paged, with debounced text filters, a status
 * radio, and a from/to date range snapped to day boundaries in the session
 * domain's timezone. Rows open a read-only details dialog.
 */
@Component({
  selector: 'app-mails-page',
  imports: [
    AdminSectionPage,
    Pager,
    DatePicker,
    Icon,
    ProgressBar,
    EmptyState,
    ErrorBanner,
    MailDetailsDialog,
    TranslocoPipe,
  ],
  providers: [ScheduledMailsFacade],
  templateUrl: './mails-page.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class MailsPage {
  protected readonly facade = inject(ScheduledMailsFacade);
  private readonly api = inject(ScheduledMailsApiService);
  private readonly notifications = inject(NotificationService);
  private readonly localeFormat = inject(LocaleFormatService);
  private readonly domain = inject(DomainService);

  /** Instant input echoes — committed to the facade only after the debounce. */
  protected readonly recipientInput = signal('');
  protected readonly senderInput = signal('');
  protected readonly subjectInput = signal('');

  private readonly debounceTimers = new Map<TextParam, ReturnType<typeof setTimeout>>();

  protected readonly statusOptions = computed(() => [
    { labelKey: 'admin.mails.status.all', value: '' },
    { labelKey: 'admin.mails.status.pending', value: 'PENDING' },
    { labelKey: 'admin.mails.status.sent', value: 'SENT' },
    { labelKey: 'admin.mails.status.held', value: 'HELD' },
    { labelKey: 'admin.mails.status.error', value: 'ERROR' },
  ]);

  protected readonly fromDate = signal<Date | null>(null);
  protected readonly toDate = signal<Date | null>(null);

  protected readonly detailsMail = signal<ScheduledMail | undefined>(undefined);
  protected readonly detailsLoading = signal(false);

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      for (const timer of this.debounceTimers.values()) {
        clearTimeout(timer);
      }
    });
  }

  private inputSignal(param: TextParam) {
    return { recipient: this.recipientInput, sender: this.senderInput, subject: this.subjectInput }[
      param
    ];
  }

  protected onTextInput(param: TextParam, value: string): void {
    this.inputSignal(param).set(value);
    clearTimeout(this.debounceTimers.get(param));
    this.debounceTimers.set(
      param,
      setTimeout(() => this.commitText(param), ADMIN_TEXT_FILTER_DEBOUNCE_MS),
    );
  }

  /** Blur commits immediately; so does the clear-×. */
  protected commitText(param: TextParam): void {
    clearTimeout(this.debounceTimers.get(param));
    const value = this.inputSignal(param)();
    if (this.facade.params()[param] !== value) {
      this.facade.setParams({ [param]: value });
    }
  }

  protected clearText(param: TextParam): void {
    this.inputSignal(param).set('');
    this.commitText(param);
  }

  protected onStatusChange(value: '' | ScheduledMailStatus): void {
    this.facade.setParams({ status: value });
  }

  /** The picker already emits the picked day's midnight in the session domain's zone. */
  protected onFromDateChange(date: Date | null): void {
    this.fromDate.set(date);
    this.facade.setParams({ fromDate: date?.valueOf() });
  }

  protected onToDateChange(date: Date | null): void {
    this.toDate.set(date);
    const timeZone = this.domain.sessionDomain()?.timeZone;
    // The picker emits the START of the picked day; an inclusive upper bound has to run to
    // the end of that same day. Without a zone the picker is disabled, so the only value
    // that can arrive here is a clear.
    this.facade.setParams({
      toDate: date && timeZone ? endOfDayInZone(date, timeZone).valueOf() : undefined,
    });
  }

  protected readonly pageRows = computed(() => [...this.facade.result().data]);

  /** "first@example.org + 2 others" recipient summary. */
  protected firstRecipient(mail: ScheduledMail): { address: string; others: number } {
    const recipients = mail.recipients;
    if (recipients.length === 0) {
      return { address: '', others: 0 };
    }
    return { address: recipients[0].address, others: recipients.length - 1 };
  }

  protected formatDateTime(epoch: number | undefined): string {
    if (epoch === undefined || epoch === null) {
      return '';
    }
    return this.localeFormat.formatDateTime(new Date(epoch));
  }

  /** Layer-3 status-pill class + translated label key — never the raw enum. */
  protected statusClass(mail: ScheduledMail): string {
    return `is-mail-${mail.status.toLowerCase()}`;
  }

  protected statusKey(mail: ScheduledMail): string {
    return `admin.mails.status.${mail.status.toLowerCase()}`;
  }

  protected openMail(mail: ScheduledMail): void {
    if (this.detailsLoading()) {
      return; // dedupe re-entry while a details fetch is in flight
    }
    this.detailsLoading.set(true);
    this.api.getMailDetails(mail.id).subscribe({
      next: (details) => {
        this.detailsLoading.set(false);
        this.detailsMail.set(details);
      },
      error: () => {
        this.detailsLoading.set(false);
        this.notifications.error('admin.mails.toast.detailsError');
      },
    });
  }
}
