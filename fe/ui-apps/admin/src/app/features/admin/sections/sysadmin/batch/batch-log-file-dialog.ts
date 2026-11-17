import { Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { TranslocoPipe } from '@jsverse/transloco';
import { of, switchMap } from 'rxjs';
import { catchError } from 'rxjs/operators';

import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { BatchApiService } from '../../../services/api/batch-api.service';

/**
 * Read-only log-file viewer for a batch instance. On open it fetches the
 * instance's log file names (keyed error toast on a names fetch failure, rather
 * than a silent empty group). Picking a name auto-loads that file's FULL
 * content into a read-only monospace viewer; a content-fetch error deliberately
 * renders the error body inline, with no toast. The radio group is
 * non-uncheckable — re-clicking the selected name never deselects it.
 */
@Component({
  selector: 'app-batch-log-file-dialog',
  imports: [Dialog, TranslocoPipe],
  templateUrl: './batch-log-file-dialog.html',
})
export class BatchLogFileDialog {
  private readonly api = inject(BatchApiService);
  private readonly notifications = inject(NotificationService);

  protected readonly visible = signal(false);
  protected readonly instanceId = signal<number | undefined>(undefined);
  protected readonly fileNames = signal<readonly string[]>([]);
  protected readonly selectedFile = signal<string | undefined>(undefined);

  private readonly loadKey = computed(() => ({
    instanceId: this.instanceId(),
    file: this.selectedFile(),
  }));

  /**
   * Content auto-loads when the (instance, file) selection changes. `switchMap`
   * cancels a superseded request so a slow earlier response can't land over a
   * newer file's content; an empty selection clears the viewer, and a
   * content-fetch error deliberately renders its body verbatim.
   */
  protected readonly content = toSignal(
    toObservable(this.loadKey).pipe(
      switchMap(({ instanceId, file }) =>
        !file || instanceId === undefined
          ? of('')
          : this.api
              .getLogFileContent(instanceId, file)
              .pipe(
                catchError((err: { error?: unknown; message?: string }) => of(stringifyError(err))),
              ),
      ),
    ),
    { initialValue: '' },
  );

  open(instanceId: number): void {
    this.instanceId.set(instanceId);
    this.fileNames.set([]);
    this.selectedFile.set(undefined);
    this.visible.set(true);
    this.api.getLogFileNames(instanceId).subscribe({
      next: (names) => this.fileNames.set(names),
      error: () => this.notifications.error('admin.batch.toast.logNamesError'),
    });
  }

  /** Select a name (non-uncheckable — re-clicking the active name is a no-op). */
  protected selectFile(name: string): void {
    if (name === this.selectedFile()) {
      return;
    }
    this.selectedFile.set(name);
  }

  protected close(): void {
    this.visible.set(false);
  }
}

/** Best-effort plain-text rendering of an error response body for the viewer. */
function stringifyError(err: { error?: unknown; message?: string }): string {
  if (typeof err.error === 'string') {
    return err.error;
  }
  return err.message ?? '';
}
