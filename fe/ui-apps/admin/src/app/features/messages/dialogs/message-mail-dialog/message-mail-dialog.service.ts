import { Injectable, signal } from '@angular/core';
import { Observable, take } from 'rxjs';

import { DialogHostService } from '../../../../shared/dialogs/dialog-host.service';

export interface MailRequest {
  readonly language?: string;
  readonly emailAddresses: readonly string[];
  readonly mailSubject: string;
  readonly mailMessage: string;
}

/**
 * Sends a mail the dialog hands it. Emits when the mail was accepted and errors when it was not;
 * the sender shows its own toasts.
 */
export type MailSender = (request: MailRequest) => Observable<unknown>;

/**
 * Host-component pattern opener for the mail dialog. The Messages route mounts
 * a single <app-message-mail-dialog>; this service drives its visibility.
 *
 * Unlike the other host dialogs, Send does not hide the dialog first: the dialog
 * runs the caller's {@link MailSender} itself and closes, emitting the user's
 * MailRequest, only when the send succeeds. On a failure it stays open with what
 * was typed, so the user can correct it and send again. Dismissal emits undefined.
 */
@Injectable({ providedIn: 'root' })
export class MessageMailDialogService extends DialogHostService<MailRequest> {
  readonly totalMessageNo = signal(0);
  /**
   * The list the messages were chosen from was answered while the search index was being rebuilt
   * (`indexIncomplete`), so messages may be missing from it: the dialog shows the list's own warning.
   */
  readonly indexIncomplete = signal(false);
  /** True while a send runs; the dialog keeps Send disabled meanwhile. */
  readonly sending = signal(false);

  private sender: MailSender | undefined;
  /** Counts the opens, so a send that ends after its dialog was replaced touches nothing. */
  private openNo = 0;

  open(
    messageIds: readonly string[],
    send: MailSender,
    indexIncomplete = false,
  ): Observable<MailRequest | undefined> {
    this.totalMessageNo.set(messageIds.length);
    this.indexIncomplete.set(indexIncomplete);
    this.sender = send;
    this.sending.set(false);
    this.openNo++;
    return this.armOpen();
  }

  /** Runs the send; hides the dialog and emits the request only when the send succeeds. */
  send(request: MailRequest): void {
    const sender = this.sender;
    if (sender === undefined || this.sending()) return;
    const openNo = this.openNo;
    const current = () => openNo === this.openNo;
    this.sending.set(true);
    sender(request)
      .pipe(take(1))
      .subscribe({
        next: () => {
          if (!current()) return;
          this.sending.set(false);
          // Closed by the user while the send ran: the caller already has its undefined.
          if (this.visible()) this.submit(request);
        },
        error: () => {
          if (current()) this.sending.set(false);
        },
        complete: () => {
          if (current()) this.sending.set(false);
        },
      });
  }
}
