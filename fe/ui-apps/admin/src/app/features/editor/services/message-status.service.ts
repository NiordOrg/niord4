import { HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';
import { finalize, Observable, of } from 'rxjs';

import { NotificationService } from '../../../core/notifications/notification.service';
import { problemDetail } from '../../../shared/api/problem-detail';
import { MessageCopyDialogService } from '../../messages/dialogs/message-copy-dialog/message-copy-dialog.service';
import type { Message, MessageStatus, ReferenceType } from '../../messages/models/message.model';
import { MessagesApiService } from '../../messages/services/api/messages-api.service';
import { canPublish } from '../util/message-publish-validation.util';
import { EditorFacade } from './editor-facade';

// Reference types whose PUBLISHED holders are offered for cascade-cancel when publishing.
const CASCADE_REFERENCE_TYPES: readonly ReferenceType[] = [
  'CANCELLATION',
  'REPETITION',
  'REPETITION_NEW_TIME',
  'UPDATE',
];

/**
 * The status state machine (DRAFT→VERIFIED→PUBLISHED→CANCELLED, VERIFIED→DRAFT un-verify,
 * DRAFT/VERIFIED→DELETED) plus copy.
 * Route-scoped (provided in the editor route) so it can read the shared `EditorFacade`. Each
 * transition double-enforces the status guard (here AND in the panel's button gates) and reloads
 * the canonical message on success and on a refusal (409). One change runs at a time (`busy`).
 * Keeps the status page/panel thin.
 */
@Injectable()
export class MessageStatusService {
  private readonly facade = inject(EditorFacade);
  private readonly api = inject(MessagesApiService);
  private readonly notify = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  private readonly copyDialog = inject(MessageCopyDialogService);

  private readonly _busy = signal(false);
  /**
   * True while a status change is on its way to the backend. The status panel disables its buttons
   * meanwhile, and a status change asked for then is ignored, so a second confirm cannot send the
   * change again.
   */
  readonly busy = this._busy.asReadonly();

  /**
   * Run the publish/verify validation against the current message, toasting on failure. Callers use
   * it to validate BEFORE opening a confirm/publish dialog; the transition methods re-validate as
   * the authoritative gate.
   */
  validateForPublish(): boolean {
    const message = this.facade.message();
    return !!message && this.passesPublishValidation(message);
  }

  /** Referenced PUBLISHED messages that publishing this one would cascade-cancel (publish dialog). */
  referencedMessages(): Observable<Message[]> {
    const message = this.facade.message();
    if (!message) {
      return of([]);
    }
    return this.api.referencedMessages(message.id, CASCADE_REFERENCE_TYPES, 'PUBLISHED');
  }

  verify(): void {
    const message = this.facade.message();
    if (!message) {
      return;
    }
    if (message.status !== 'DRAFT') {
      this.notify.error('editor.status.error.onlyDraft');
      return;
    }
    if (!this.passesPublishValidation(message)) {
      return;
    }
    this.transition(
      message.id,
      'VERIFIED',
      'editor.status.toast.verified',
      'editor.status.error.verifyFailed',
    );
  }

  /**
   * Un-verify: step a VERIFIED message back to DRAFT (backend-legal VERIFIED→DRAFT transition).
   * Exposed by the preview state-flow's clickable Draft node; the full Status panel has no such
   * button. Guards status===VERIFIED (the backend is the authoritative gate) and reloads on success.
   */
  revertToDraft(): void {
    const message = this.facade.message();
    if (!message) {
      return;
    }
    if (message.status !== 'VERIFIED') {
      this.notify.error('editor.status.error.onlyVerifiedRevert');
      return;
    }
    this.transition(
      message.id,
      'DRAFT',
      'editor.status.toast.revertedToDraft',
      'editor.status.error.revertFailed',
    );
  }

  publish(cancelReferenced: boolean): void {
    const message = this.facade.message();
    if (!message) {
      return;
    }
    if (message.status !== 'VERIFIED') {
      this.notify.error('editor.status.error.onlyVerified');
      return;
    }
    if (!this.passesPublishValidation(message)) {
      return;
    }
    const options = cancelReferenced ? { cancelReferencedMessages: true } : undefined;
    this.transition(
      message.id,
      'PUBLISHED',
      'editor.status.toast.published',
      'editor.status.error.publishFailed',
      options,
    );
  }

  delete(): void {
    const message = this.facade.message();
    if (!message) {
      return;
    }
    if (message.status !== 'DRAFT' && message.status !== 'VERIFIED') {
      this.notify.error('editor.status.error.onlyDraftVerified');
      return;
    }
    this.transition(
      message.id,
      'DELETED',
      'editor.status.toast.deleted',
      'editor.status.error.deleteFailed',
    );
  }

  cancel(createCancellation: boolean): void {
    const message = this.facade.message();
    if (!message) {
      return;
    }
    if (message.status !== 'PUBLISHED') {
      this.notify.error('editor.status.error.onlyPublished');
      return;
    }
    if (this._busy()) {
      return;
    }
    this._busy.set(true);
    this.api
      .updateMessageStatus(message.id, 'CANCELLED')
      .pipe(finalize(() => this._busy.set(false)))
      .subscribe({
        next: () => {
          // NM-only: re-enter the copy flow to draft the cancellation message (allow-listed past the
          // unsaved-changes guard). Otherwise just reload the now-cancelled message.
          if (createCancellation && message.mainType === 'NM') {
            this.facade.bypassGuardOnce();
            void this.router.navigate(['/editor/copy', message.id, 'CANCELLATION']);
          } else {
            this.facade.reload();
            this.notify.success('editor.status.toast.cancelled');
          }
        },
        error: (error: unknown) => this.notifyFailure(error, 'editor.status.error.cancelFailed'),
      });
  }

  /**
   * Copy gated on `isEditor` alone (no status guard). Opens the copy dialog (NM messages may add a
   * back-reference to the original); on confirm, navigate to the copy route allow-listed past the
   * unsaved-changes guard. Cancel is a no-op.
   */
  copy(): void {
    const message = this.facade.message();
    if (!message) {
      return;
    }
    this.copyDialog.open(message.id, message.mainType).subscribe((commands) => {
      if (!commands) {
        return;
      }
      this.facade.bypassGuardOnce();
      void this.router.navigate(commands);
    });
  }

  private passesPublishValidation(message: Message): boolean {
    const validation = canPublish(message);
    if (validation.ok) {
      return true;
    }
    const fields = [
      ...validation.missingFieldKeys.map((key) => this.transloco.translate(key)),
      ...validation.missingPromulgations,
    ].join(', ');
    this.notify.error('editor.status.error.missingFields', { fields });
    return false;
  }

  private transition(
    id: string,
    status: MessageStatus,
    successKey: string,
    errorKey: string,
    options?: { cancelReferencedMessages?: boolean },
  ): void {
    if (this._busy()) {
      return;
    }
    this._busy.set(true);
    this.api
      .updateMessageStatus(id, status, options)
      .pipe(finalize(() => this._busy.set(false)))
      .subscribe({
        next: () => {
          this.facade.reload();
          this.notify.success(successKey);
        },
        error: (error: unknown) => this.notifyFailure(error, errorKey),
      });
  }

  /**
   * A 409 is a change the backend refused (another officer changed the message meanwhile, or a
   * referenced message cannot be cancelled from this domain), and nothing was changed: reload the
   * message, so the panel shows its status as it is now, and show the backend's reason. Nothing is
   * lost: every status action is disabled while the editor holds unsaved changes. Any other failure
   * keeps the operation's own key and the message as it is.
   */
  private notifyFailure(error: unknown, errorKey: string): void {
    if (!(error instanceof HttpErrorResponse && error.status === 409)) {
      this.notify.error(errorKey);
      return;
    }
    this.facade.reload();
    const detail = problemDetail(error);
    if (detail === undefined) {
      this.notify.error(errorKey);
    } else {
      this.notify.error('editor.status.error.refusedReloaded', { detail });
    }
  }
}
