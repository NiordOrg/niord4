import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { problemDetail } from '../../../../../shared/api/problem-detail';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { Pager } from '../../../../../shared/components/pager/pager';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { JsonImportDialog } from '../../../components/json-import-dialog/json-import-dialog';
import {
  BatchApiService,
  BatchExecution,
  BatchInstance,
} from '../../../services/api/batch-api.service';
import { AdminSectionPage } from '../../../shell/admin-section-page';
import { BatchFacade } from './batch.facade';
import { BatchJavaScriptDialog } from './batch-javascript-dialog';
import { BatchLogFileDialog } from './batch-log-file-dialog';
import { batchStatusClass, batchStatusKey } from './batch-status';

/**
 * Batch Jobs monitor — a bespoke two-column master/detail
 * page: a job-type list (live counts, 3s page poll) and a server-paged
 * executions table flattened into instance-header + execution rows. Start/end
 * times render via `LocaleFormatService` in the session-domain timezone (read
 * live). Status pills are keyed + colored from an exhaustive map. Stop/restart/
 * abandon buttons render from server-computed flags verbatim and refetch the
 * page on success (keyed error toast on failure; a restart the server refuses
 * with a 409 shows its reason). Instance data files download
 * via a `role=sysadmin` ticket (the backend's download check is sysadmin, and a
 * ticket carries only the roles it was asked for). The sysadmin-only toolbar
 * carries Upload-batch-set and Execute-JavaScript actions.
 */
@Component({
  selector: 'app-batch-page',
  imports: [
    AdminSectionPage,
    Pager,
    ProgressBar,
    EmptyState,
    JsonImportDialog,
    BatchLogFileDialog,
    BatchJavaScriptDialog,
    TranslocoPipe,
  ],
  providers: [BatchFacade],
  templateUrl: './batch-page.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class BatchPage {
  protected readonly facade = inject(BatchFacade);
  private readonly api = inject(BatchApiService);
  private readonly downloads = inject(TicketedDownloadService);
  private readonly notifications = inject(NotificationService);
  private readonly localeFormat = inject(LocaleFormatService);
  private readonly auth = inject(AuthFacade);

  private readonly logDialog = viewChild.required(BatchLogFileDialog);
  private readonly jsDialog = viewChild.required(BatchJavaScriptDialog);
  private readonly importDialog = viewChild.required(JsonImportDialog);

  /** Upload-batch-set + Execute-JavaScript are sysadmin-only. */
  protected readonly canSysadmin = this.auth.hasRole('sysadmin');

  /** Guards re-entrant action PUTs while one is in flight. */
  private readonly actionPending = signal(false);

  protected readonly uploadUrl = this.api.uploadBatchSetUrl();

  constructor() {
    // Read the optional `:batchName` ONCE on entry to pre-select the type; never
    // written back on later clicks (page-local selection state).
    const batchName = inject(ActivatedRoute).snapshot.paramMap.get('batchName');
    if (batchName) {
      this.facade.selectType(batchName);
    }
  }

  /** Materialize the readonly flattened rows for the template. */
  protected readonly pageRows = computed(() => [...this.facade.rows()]);

  protected selectType(name: string): void {
    this.facade.selectType(name);
  }

  protected statusClass(execution: BatchExecution): string {
    return batchStatusClass(execution.batchStatus);
  }

  protected statusKey(execution: BatchExecution): string {
    return batchStatusKey(execution.batchStatus);
  }

  /** True when the execution is running (drives the end-time vs progress branch). */
  protected isStarted(execution: BatchExecution): boolean {
    return execution.batchStatus === 'STARTED';
  }

  /**
   * Progress bar only when STARTED AND the instance reports a truthy progress
   * (a 0/absent progress is the empty-cell case). STARTED-without-progress shows
   * neither end time nor bar.
   */
  protected showProgress(row: { instance: BatchInstance; execution: BatchExecution }): boolean {
    return this.isStarted(row.execution) && !!row.instance.progress;
  }

  protected formatTime(epoch: number | undefined): string {
    if (epoch === undefined || epoch === null) {
      return '';
    }
    return this.localeFormat.formatDateTime(new Date(epoch));
  }

  protected stop(execution: BatchExecution): void {
    this.runAction(this.api.stop(execution.executionId));
  }

  protected restart(execution: BatchExecution): void {
    this.runAction(this.api.restart(execution.executionId), (error) => {
      // A 409 says why the server will not restart it (a bootstrap job never is): say why.
      const detail =
        error instanceof HttpErrorResponse && error.status === 409
          ? problemDetail(error)
          : undefined;
      if (detail === undefined) {
        this.notifications.error('admin.batch.toast.actionError');
      } else {
        this.notifications.error('admin.batch.toast.restartRefused', { detail });
      }
    });
  }

  protected abandon(execution: BatchExecution): void {
    this.runAction(this.api.abandon(execution.executionId));
  }

  /**
   * Stop/restart/abandon: refetch the current page on success; on failure the
   * given handler, or the keyed toast.
   */
  private runAction(
    request: ReturnType<BatchApiService['stop']>,
    onError: (error: unknown) => void = () =>
      this.notifications.error('admin.batch.toast.actionError'),
  ): void {
    if (this.actionPending()) {
      return;
    }
    this.actionPending.set(true);
    request.subscribe({
      next: () => {
        this.actionPending.set(false);
        this.facade.refreshInstances();
      },
      error: (error: unknown) => {
        this.actionPending.set(false);
        onError(error);
      },
    });
  }

  /** Instance data-file download via a one-time role=sysadmin ticket. */
  protected async download(instance: BatchInstance): Promise<void> {
    if (!instance.fileName) {
      return;
    }
    try {
      await this.downloads.openTicketed(
        this.api.downloadUrl(instance.instanceId, instance.fileName),
        'sysadmin',
      );
    } catch {
      this.notifications.error('admin.batch.toast.downloadError');
    }
  }

  protected thumbnailUrl(instance: BatchInstance): string {
    return this.api.thumbnailUrl(instance.instanceId);
  }

  protected openLogs(instance: BatchInstance): void {
    this.logDialog().open(instance.instanceId);
  }

  protected openExecuteJavaScript(): void {
    this.jsDialog().open();
  }

  protected openUpload(): void {
    this.importDialog().open();
  }

  /** The "view batch job" link target for the import dialog's result slot. */
  protected jobLinkFor(jobName: string): string {
    return `/sysadmin/batch/${jobName}`;
  }
}
