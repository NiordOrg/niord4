import { Component, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { TranslocoPipe } from '@jsverse/transloco';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { HelpAnchor } from '../../../../core/main-shell/help/help-anchor';
import { LocaleFormatService } from '../../../../core/main-shell/services/locale-format.service';
import { Dialog } from '../../../../shared/components/dialog/dialog';
import { fromMyMailVo, type MyMail, type MyMailVo } from '../../services/api/my-mails-api.service';

/** The period the dialog lists, in days. */
export const MY_MAILS_DAYS = 7;

/**
 * "My mails…", opened from the messages Actions menu: the mails the signed-in user queued in the
 * last week (the mail dialog's and the comments' mails), newest first, each with what became of
 * it — sent, waiting, being retried after a failure, or not sent after the last retry, with the
 * mail server's last error. The list is read each time the dialog opens and held only while it is
 * open.
 */
@Component({
  selector: 'app-my-mails-dialog',
  imports: [Dialog, HelpAnchor, TranslocoPipe],
  templateUrl: './my-mails-dialog.html',
  host: { class: 'contents' },
})
export class MyMailsDialog {
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;
  private readonly localeFormat = inject(LocaleFormatService);

  protected readonly days = MY_MAILS_DAYS;
  protected readonly visible = signal(false);

  /** Idle while the dialog is closed; each opening reads the list again. */
  protected readonly mails = httpResource<readonly MyMail[]>(
    () =>
      this.visible()
        ? { url: `${this.apiBaseUrl}/scheduled-mails/mine`, params: { days: MY_MAILS_DAYS } }
        : undefined,
    { parse: (raw) => ((raw as readonly MyMailVo[] | null) ?? []).map(fromMyMailVo) },
  );

  open(): void {
    this.visible.set(true);
  }

  protected onVisibleChange(visible: boolean): void {
    this.visible.set(visible);
  }

  protected close(): void {
    this.visible.set(false);
  }

  /** The creation time in the session domain's zone, never the browser's. */
  protected formatCreated(mail: MyMail): string {
    return mail.created === undefined || mail.created === null
      ? ''
      : this.localeFormat.formatDateTime(mail.created);
  }

  protected recipients(mail: MyMail): string {
    return mail.recipients.map((recipient) => recipient.address).join(', ');
  }

  /** The status as the sender reads it: a pending mail that failed once is being retried. */
  protected statusKey(mail: MyMail): string {
    switch (mail.status) {
      case 'SENT':
        return 'messages.myMails.status.sent';
      case 'ERROR':
        return 'messages.myMails.status.error';
      case 'PENDING':
        return mail.attempts > 0
          ? 'messages.myMails.status.retrying'
          : 'messages.myMails.status.waiting';
    }
  }

  /** The Layer-3 pill of the Mail Queue's statuses; a retry reads as pending. */
  protected statusClass(mail: MyMail): string {
    return `is-mail-${mail.status.toLowerCase()}`;
  }

  /** The last error is shown while the mail is retried and once it failed for good. */
  protected showsError(mail: MyMail): boolean {
    return mail.status !== 'SENT' && !!mail.lastError;
  }
}
