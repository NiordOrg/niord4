import { Component, computed, inject, signal } from '@angular/core';
import { form, FormField } from '@angular/forms/signals';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { CodeTextInput } from '../../../components/code-text-input/code-text-input';
import { BatchApiService } from '../../../services/api/batch-api.service';

/**
 * Execute-JavaScript dialog. The code goes through the shared `CodeTextInput`
 * CVA (a plain validated textarea, behind which a richer editor can swap in via
 * the same control). The code POSTs to the backend, which SCHEDULES a
 * `script-executor` job to run it server-side — the most dangerous admin
 * surface, so a confirm step + a keyed "runs server-side" warning precede
 * scheduling. On success the "scheduled" info toast fires; a failed POST raises
 * a keyed error toast rather than being swallowed.
 */
@Component({
  selector: 'app-batch-javascript-dialog',
  imports: [Dialog, Confirm, CodeTextInput, FormField, TranslocoPipe],
  providers: [ConfirmService],
  templateUrl: './batch-javascript-dialog.html',
})
export class BatchJavaScriptDialog {
  private readonly api = inject(BatchApiService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);

  protected readonly visible = signal(false);
  protected readonly scheduling = signal(false);

  private readonly formModel = signal<{ javaScript: string }>({ javaScript: '' });
  protected readonly form = form(this.formModel);

  /**
   * Schedule disabled while blank/whitespace or mid-schedule. The non-blank rule
   * (trimmed length > 0) is a computed, not a form validator.
   */
  protected readonly submitDisabled = computed(
    () => this.formModel().javaScript.trim().length === 0 || this.scheduling(),
  );

  open(): void {
    this.formModel.set({ javaScript: '' });
    this.form().reset();
    this.scheduling.set(false);
    this.visible.set(true);
  }

  protected close(): void {
    this.visible.set(false);
  }

  /** Confirm step (the keyed "runs server-side" warning) before scheduling. */
  protected submit(): void {
    if (this.submitDisabled()) {
      return;
    }
    this.confirm.confirmNeutral({
      header: this.transloco.translate('admin.batch.editor.confirmTitle'),
      message: this.transloco.translate('admin.batch.editor.serverSideWarning'),
      acceptLabel: this.transloco.translate('admin.batch.editor.schedule'),
      accept: () => this.schedule(),
    });
  }

  private schedule(): void {
    const code = this.formModel().javaScript;
    this.scheduling.set(true);
    this.api.executeJavaScript(code).subscribe({
      next: () => {
        this.notifications.info('admin.batch.toast.scheduled');
        this.scheduling.set(false);
        this.visible.set(false);
      },
      error: () => {
        this.scheduling.set(false);
        this.notifications.error('admin.batch.toast.scheduleError');
      },
    });
  }
}
