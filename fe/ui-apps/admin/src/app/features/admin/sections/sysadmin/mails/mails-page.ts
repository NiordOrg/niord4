import { HttpErrorResponse } from '@angular/common/http';
import {
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { Icon } from '../../../../../shared/icons/icon';
import { DomainService } from '../../../../../core/domain/services/domain.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { problemDetail } from '../../../../../shared/api/problem-detail';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { DatePicker } from '../../../../../shared/forms/date-picker/date-picker';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { Pager } from '../../../../../shared/components/pager/pager';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { endOfDayInZone } from '../../../../../shared/utils/zoned-date';
import {
  SEND_AGAIN_MAX,
  ScheduledMail,
  ScheduledMailStatus,
  ScheduledMailsApiService,
} from '../../../services/api/scheduled-mails-api.service';
import { AdminSectionPage } from '../../../shell/admin-section-page';
import { ADMIN_TEXT_FILTER_DEBOUNCE_MS } from '../../../utils/kit-constants';
import { MailDetailsDialog } from './mail-details-dialog';
import { ScheduledMailsFacade } from './scheduled-mails.facade';
import {
  SEND_AGAIN_ADDRESSES_SHOWN,
  SendAgainGroup,
  canSendAgain,
  groupForSendAgain,
  sendAgainItem,
} from './send-again';

type TextParam = 'recipient' | 'sender' | 'subject';

/**
 * Mail-queue search: server-paged, with debounced text filters, a status
 * radio, and a from/to date range snapped to day boundaries in the session
 * domain's timezone. Rows open a read-only details dialog.
 *
 * Send again puts a held, sent or failed mail back to PENDING, exactly as it was
 * made: one mail from its row, or every mail of the current search while the
 * status filter is Held or Error (at most `SEND_AGAIN_MAX`; a sent mail only one
 * at a time). The page carries no role gate of its own: the sysadmin route and
 * the backend's guard of the endpoint keep it to a system administrator.
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
    Confirm,
    TranslocoPipe,
  ],
  // The confirmation is wider than the root one and lists the mails, so the page mounts its own.
  providers: [ScheduledMailsFacade, ConfirmService],
  templateUrl: './mails-page.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class MailsPage {
  protected readonly facade = inject(ScheduledMailsFacade);
  private readonly api = inject(ScheduledMailsApiService);
  private readonly notifications = inject(NotificationService);
  private readonly localeFormat = inject(LocaleFormatService);
  private readonly domain = inject(DomainService);
  private readonly confirm = inject(ConfirmService);
  private readonly transloco = inject(TranslocoService);

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

  /**
   * Blocks re-entry while Send again reads the search or posts its request. The buttons only say
   * so (aria-disabled): a disabled opener could not take the focus back from the confirmation.
   */
  protected readonly sendingAgain = signal(false);

  private readonly mailList = viewChild<ElementRef<HTMLElement>>('mailList');
  private readonly sendAllButton = viewChild<ElementRef<HTMLButtonElement>>('sendAllButton');
  private readonly refreshButton = viewChild<ElementRef<HTMLButtonElement>>('refreshButton');

  /** "Send all matching again…" is offered only while the status filter is Held or Error. */
  protected readonly sendAllAgainOffered = computed(() => {
    const status = this.facade.params().status;
    return status === 'HELD' || status === 'ERROR';
  });

  protected readonly canSendAgain = canSendAgain;

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

  /** The row button: one mail, after the confirmation. */
  protected sendAgain(mail: ScheduledMail): void {
    if (this.sendingAgain() || !canSendAgain(mail)) {
      return;
    }
    this.confirmSendAgain([mail]);
  }

  /**
   * Every mail of the current search, read again with the committed filter in one page of
   * `SEND_AGAIN_MAX`: a search that matches more sends nothing, so no mail is left out unseen. Sent
   * mails are left out (one at a time only), and so are failure records, which are not mails.
   */
  protected sendAllMatchingAgain(): void {
    if (this.sendingAgain()) {
      return;
    }
    this.sendingAgain.set(true);
    this.api.search(this.facade.params(), 0, SEND_AGAIN_MAX).subscribe({
      next: (result) => {
        this.sendingAgain.set(false);
        if (result.total > SEND_AGAIN_MAX) {
          this.notifications.warn('admin.mails.sendAgain.tooMany', {
            total: result.total,
            max: SEND_AGAIN_MAX,
          });
          return;
        }
        const mails = result.data.filter((mail) => mail.status !== 'SENT' && canSendAgain(mail));
        if (mails.length === 0) {
          this.notifications.info('admin.mails.sendAgain.nothing');
          return;
        }
        this.confirmSendAgain(mails);
      },
      error: () => {
        this.sendingAgain.set(false);
        this.notifications.error('admin.mails.loadError');
      },
    });
  }

  private confirmSendAgain(mails: readonly ScheduledMail[]): void {
    const t = (key: string, params?: Record<string, unknown>) =>
      this.transloco.translate(`admin.mails.sendAgain.${key}`, params);
    const single = mails.length === 1;
    let message: string;
    if (!single) {
      message = t('messageMany', { count: mails.length });
    } else if (mails[0].status === 'SENT') {
      message = t('messageSent');
    } else {
      message = t('messageOne');
    }
    const details = groupForSendAgain(mails).map((group) =>
      t('line', {
        count: group.mails.length,
        subject: group.subject,
        created: this.formatDateTime(group.created),
        status: this.transloco.translate(`admin.mails.status.${group.status.toLowerCase()}`),
        recipients: this.groupRecipients(group, single),
      }),
    );
    this.confirm.confirmNeutral({
      header: t('title'),
      message,
      details,
      acceptLabel: t('title'),
      accept: () => this.postSendAgain(mails),
    });
  }

  /** A single mail lists every recipient; a group its first addresses and how many more. */
  private groupRecipients(group: SendAgainGroup, single: boolean): string {
    if (single || group.addresses.length <= SEND_AGAIN_ADDRESSES_SHOWN) {
      return group.addresses.join(', ');
    }
    return this.transloco.translate('admin.mails.sendAgain.more', {
      addresses: group.addresses.slice(0, SEND_AGAIN_ADDRESSES_SHOWN).join(', '),
      count: group.addresses.length - SEND_AGAIN_ADDRESSES_SHOWN,
    });
  }

  private postSendAgain(mails: readonly ScheduledMail[]): void {
    if (this.sendingAgain()) {
      return;
    }
    this.sendingAgain.set(true);
    this.api.sendAgain(mails.map(sendAgainItem)).subscribe({
      next: (result) => {
        this.sendingAgain.set(false);
        if (result.refused.some((refusal) => refusal.reason === 'FILTER')) {
          // Only a sent mail is refused for the filter, and a sent mail goes one at a time (the
          // bulk action leaves sent mails out), so this text speaks of the one mail of the request.
          this.notifications.warn('admin.mails.sendAgain.filter');
        } else if (result.refused.length === 0) {
          this.notifications.success('admin.mails.sendAgain.queued', {
            count: result.queued.length,
          });
        } else {
          this.notifications.warn('admin.mails.sendAgain.partly', {
            queued: result.queued.length,
            refused: result.refused.length,
          });
        }
        this.keepFocusOnRefresh();
        this.facade.refresh();
      },
      error: (err: unknown) => {
        this.sendingAgain.set(false);
        // A 409 (the mail filter is LOG or empty) and a 400 (a request the backend will not take)
        // carry a problem document whose detail says what to do; nothing was changed either way.
        // Any other failure's detail may be raw exception text, which is never shown.
        const status = err instanceof HttpErrorResponse ? err.status : 0;
        const detail = status === 409 || status === 400 ? problemDetail(err) : undefined;
        if (detail === undefined) {
          this.notifications.error('admin.mails.sendAgain.error');
          return;
        }
        this.notifications.error('admin.mails.sendAgain.refused', { detail });
      },
    });
  }

  /**
   * The list is read again after a Send again: the table, and the row button the focus went back
   * to, leave while it loads, and the bulk button is disabled, so a keyboard user would be left on
   * the page's body. The focus moves to Refresh, beside the bulk button and never disabled.
   */
  private keepFocusOnRefresh(): void {
    const focused = document.activeElement;
    if (!(focused instanceof HTMLElement)) {
      return;
    }
    const inList = this.mailList()?.nativeElement.contains(focused) ?? false;
    const onSendAll = focused === this.sendAllButton()?.nativeElement;
    if (inList || onSendAll) {
      this.refreshButton()?.nativeElement.focus();
    }
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
