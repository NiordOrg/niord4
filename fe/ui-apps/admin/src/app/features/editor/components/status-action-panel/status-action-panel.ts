import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { NotificationService } from '../../../../core/notifications/notification.service';
import { Confirm } from '../../../../shared/components/confirm/confirm';
import { Icon } from '../../../../shared/icons/icon';
import { ConfirmService } from '../../../../shared/services/confirm.service';
import { StatusClassPipe } from '../../../../shared/pipes/status-class.pipe';
import type { Message, MessageStatus } from '../../../messages/models/message.model';
import { EditorFacade } from '../../services/editor-facade';
import { MessageStatusService } from '../../services/message-status.service';
import { CancelConfirmDialog } from '../cancel-confirm-dialog/cancel-confirm-dialog';
import { PublishConfirmDialog } from '../publish-confirm-dialog/publish-confirm-dialog';
import { LocaleDatePipe } from '../../../../shared/pipes/locale-date.pipe';

/**
 * The status panel: the dynamic "Status: <localized>" label plus the state-machine action buttons.
 * Buttons are gated on status (the second of the two enforcement layers — the service re-checks)
 * and `isEditor`, and disabled while the editor holds unsaved changes (`hasUnsavedChanges`: an
 * edit not undone, or a message never saved) and while a status change runs (the status service's
 * `busy`), so a second confirm cannot send it again. Copy has no status check, only `isEditor`.
 * Verify/Delete use the shared confirm; Publish/Cancel open the custom checkbox dialogs.
 */
@Component({
  selector: 'app-status-action-panel',
  imports: [
    LocaleDatePipe,
    TranslocoPipe,
    Icon,
    StatusClassPipe,
    Confirm,
    PublishConfirmDialog,
    CancelConfirmDialog,
  ],
  providers: [ConfirmService],
  template: `
    <div class="flex flex-col gap-4 p-4">
      @if (status(); as currentStatus) {
        <p class="flex items-center gap-2 text-sm">
          <span class="font-medium text-fg">{{ 'editor.status.label' | transloco }}</span>
          <span class="status-badge" [class]="currentStatus | statusClass">
            {{ 'msg.status.' + currentStatus | transloco }}
          </span>
        </p>
      }

      @if (facade.isEditor()) {
        <div class="flex flex-col gap-2">
          @if (status() === 'DRAFT') {
            <button
              type="button"
              class="btn-primary w-full justify-center"
              [disabled]="actionsDisabled()"
              (click)="confirmVerify()"
            >
              <app-icon name="lucideSparkles" />
              <span>{{ 'editor.status.verifyAction' | transloco }}</span>
            </button>
          }
          @if (status() === 'VERIFIED' && !scheduled()) {
            <button
              type="button"
              class="btn-primary w-full justify-center"
              [disabled]="actionsDisabled()"
              (click)="openPublish()"
            >
              <app-icon name="lucideSend" />
              <span>{{ 'editor.status.publishAction' | transloco }}</span>
            </button>
          }
          @if (status() === 'VERIFIED' && scheduled()) {
            <span class="text-center text-sm text-fg-muted">
              {{
                'editor.status.publish'
                  | transloco: { date: publishDateFrom() | localeDate: 'dateTime' }
              }}
            </span>
          }
          @if (status() === 'DRAFT' || status() === 'VERIFIED') {
            <button
              type="button"
              class="btn-secondary w-full justify-center"
              [disabled]="actionsDisabled()"
              (click)="confirmDelete()"
            >
              <app-icon name="lucideTrash2" />
              <span>{{ 'editor.status.deleteAction' | transloco }}</span>
            </button>
          }
          @if (status() === 'PUBLISHED') {
            <button
              type="button"
              class="btn-danger w-full justify-center"
              [disabled]="actionsDisabled()"
              (click)="cancelVisible.set(true)"
            >
              <app-icon name="lucideCircleX" />
              <span>{{ 'editor.status.cancelAction' | transloco }}</span>
            </button>
          }
          <button
            type="button"
            class="btn-secondary w-full justify-center"
            [disabled]="actionsDisabled()"
            (click)="statusService.copy()"
          >
            <app-icon name="lucideCopy" />
            <span>{{ 'editor.status.copyAction' | transloco }}</span>
          </button>
        </div>
      }
    </div>

    <app-confirm />
    <app-publish-confirm-dialog
      [(visible)]="publishVisible"
      [referencedMessages]="references()"
      (confirmed)="statusService.publish($event)"
    />
    <app-cancel-confirm-dialog
      [(visible)]="cancelVisible"
      [mainType]="facade.message()?.mainType ?? ''"
      (confirmed)="statusService.cancel($event)"
    />
  `,
  host: { class: 'contents' },
})
export class StatusActionPanel {
  private readonly confirm = inject(ConfirmService);
  private readonly transloco = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly notify = inject(NotificationService);
  protected readonly facade = inject(EditorFacade);
  protected readonly statusService = inject(MessageStatusService);

  protected readonly status = computed<MessageStatus | undefined>(
    () => this.facade.message()?.status,
  );
  /** Every action waits while the editor holds unsaved changes or a status change runs. */
  protected readonly actionsDisabled = computed<boolean>(
    () => this.facade.hasUnsavedChanges() || this.statusService.busy(),
  );
  protected readonly scheduled = computed<boolean>(() => !!this.facade.message()?.publishDateFrom);
  protected readonly publishDateFrom = computed<string | undefined>(
    () => this.facade.message()?.publishDateFrom,
  );

  protected readonly references = signal<readonly Message[]>([]);
  protected readonly publishVisible = signal(false);
  protected readonly cancelVisible = signal(false);

  protected confirmVerify(): void {
    // Validate before the confirm (order: validate → confirm); the service re-validates.
    if (!this.statusService.validateForPublish()) {
      return;
    }
    this.confirm.confirmNeutral({
      message: this.transloco.translate('editor.status.confirm.verify'),
      acceptLabel: this.transloco.translate('editor.status.verify_msg'),
      rejectLabel: this.transloco.translate('term.cancel'),
      accept: () => this.statusService.verify(),
    });
  }

  protected confirmDelete(): void {
    this.confirm.confirmDestructive({
      message: this.transloco.translate('editor.status.confirm.delete'),
      acceptLabel: this.transloco.translate('editor.status.delete_msg'),
      rejectLabel: this.transloco.translate('term.cancel'),
      accept: () => this.statusService.delete(),
    });
  }

  /** Validate, look up the messages publishing would cascade-cancel, then open the publish dialog. */
  protected openPublish(): void {
    // Validate before the lookup + dialog (order: validate → lookup → dialog).
    if (!this.statusService.validateForPublish()) {
      return;
    }
    this.statusService
      .referencedMessages()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (refs) => {
          this.references.set(refs);
          this.publishVisible.set(true);
        },
        // A failed lookup must not silently no-op: toast, then open the plain confirm (no cascade).
        error: () => {
          this.notify.error('editor.status.error.lookupFailed');
          this.references.set([]);
          this.publishVisible.set(true);
        },
      });
  }
}
