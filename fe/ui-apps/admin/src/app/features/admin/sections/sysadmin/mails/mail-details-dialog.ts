import { Component, computed, input, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { SandboxedHtml } from '../../../../../shared/components/sandboxed-html/sandboxed-html';
import {
  ScheduledMail,
  ScheduledMailRecipient,
  ScheduledMailStatus,
} from '../../../services/api/scheduled-mails-api.service';
import { MAIL_PREVIEW_CSS } from './mail-preview.css';

/**
 * Read-only mail details: recipient rows render only for non-empty types, the
 * error row only when a send error exists. The date row shows the mail's send
 * date, the time it is or was due, labelled by status: Due while pending (after
 * a failed attempt, the next attempt) and for a held mail (the time it was due
 * when the mail filter held it), Sent once sent, Last attempt once it
 * failed for good; it is left out when the value is empty, and for a mail in
 * Error that was never attempted (a mailing-list trigger's failure record, made
 * in Error with 0 attempts, whose send date is only its creation time). The Sent
 * again row, when and by whom the mail was last sent again, shows only for a mail
 * a system administrator has sent again. The HTML body
 * renders in the sandboxed-iframe primitive — full document fidelity, scripts
 * inert.
 */
@Component({
  selector: 'app-mail-details-dialog',
  imports: [Dialog, SandboxedHtml, TranslocoPipe],
  templateUrl: './mail-details-dialog.html',
})
export class MailDetailsDialog {
  /** `undefined` = closed; the page owns visibility. */
  readonly mail = input.required<ScheduledMail | undefined>();
  /** Pre-formatted by the page so the dialog stays locale-agnostic. */
  readonly createdLabel = input<string>('');
  /** The pre-formatted send date: the time the mail is, or was, due to be sent. */
  readonly sendDateLabel = input<string>('');
  /** The pre-formatted time of the last Send again; empty when the mail was never sent again. */
  readonly sentAgainLabel = input<string>('');
  readonly closed = output<void>();

  /** Inline stylesheet for the sandboxed preview (linked mail CSS can't load). */
  protected readonly mailPreviewCss = MAIL_PREVIEW_CSS;

  protected readonly to = computed(() => this.recipientsOf('TO'));
  protected readonly cc = computed(() => this.recipientsOf('CC'));
  protected readonly bcc = computed(() => this.recipientsOf('BCC'));

  /** i18n key for the translated status label — never the raw enum. */
  protected readonly statusKey = computed(
    () => `admin.mails.status.${this.mail()?.status.toLowerCase() ?? 'pending'}`,
  );

  /**
   * The label of the send date, which means "due at" whatever the status; undefined when the row
   * is left out: a mail in Error with no attempt is a failure record, which no one tried to send.
   */
  protected readonly sendDateKey = computed(() => {
    const mail = this.mail();
    const status = mail?.status as ScheduledMailStatus | undefined;
    switch (status) {
      case 'SENT':
        return 'admin.mails.details.sent';
      case 'ERROR':
        return (mail?.attempts ?? 0) > 0 ? 'admin.mails.details.lastAttempt' : undefined;
      case 'PENDING':
      case 'HELD':
      case undefined:
        return 'admin.mails.details.due';
    }
  });

  private recipientsOf(type: ScheduledMailRecipient['recipientType']): string {
    return (this.mail()?.recipients ?? [])
      .filter((recipient) => recipient.recipientType === type)
      .map((recipient) => recipient.address)
      .join(', ');
  }
}
