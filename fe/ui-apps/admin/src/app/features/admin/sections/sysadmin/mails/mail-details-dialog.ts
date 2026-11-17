import { Component, computed, input, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { SandboxedHtml } from '../../../../../shared/components/sandboxed-html/sandboxed-html';
import {
  ScheduledMail,
  ScheduledMailRecipient,
} from '../../../services/api/scheduled-mails-api.service';
import { MAIL_PREVIEW_CSS } from './mail-preview.css';

/**
 * Read-only mail details: recipient rows render only for non-empty types, the
 * error row only when a send error exists, and the Sent row keeps its label
 * even while PENDING/empty. The HTML body renders in the sandboxed-iframe
 * primitive — full document fidelity, scripts inert.
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
  readonly sentLabel = input<string>('');
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

  private recipientsOf(type: ScheduledMailRecipient['recipientType']): string {
    return (this.mail()?.recipients ?? [])
      .filter((recipient) => recipient.recipientType === type)
      .map((recipient) => recipient.address)
      .join(', ');
  }
}
