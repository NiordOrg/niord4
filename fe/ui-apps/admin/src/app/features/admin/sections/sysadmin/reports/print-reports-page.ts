import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { Icon } from '../../../../../shared/icons/icon';
import { Menu } from '../../../../../shared/components/menu/menu';
import { Pager } from '../../../../../shared/components/pager/pager';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { MenuTrigger } from '../../../../../shared/components/menu-trigger/menu-trigger';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { MessagePrintDialog } from '../../../../messages/dialogs/message-print-dialog/message-print-dialog';
import { provideMessageTagsDataSource } from '../../../../messages/services/api/message-tags-data-source.provider';
import { JsonImportDialog } from '../../../components/json-import-dialog/json-import-dialog';
import {
  MessageReportsAdminApiService,
  SystemMessageReport,
} from '../../../services/api/message-reports-admin-api.service';
import { AdminSectionPage } from '../../../shell/admin-section-page';
import { createClientPager } from '../../../utils/client-pager';
import { ADMIN_PAGE_SIZE } from '../../../utils/kit-constants';
import { ReportEditor, ReportEditorModel } from './report-editor';
import { ReportsFacade } from './reports.facade';

/**
 * Print Reports (sysadmin) — list + drawer editor for PDF print-report definitions.
 * The cog carries ticketed Export + JSON Import. The single-tag test-print field
 * resolves tags through a locked-excluding data source bound here.
 */
@Component({
  selector: 'app-print-reports-page',
  imports: [
    AdminSectionPage,
    Pager,
    Icon,
    Menu,
    Confirm,
    MenuTrigger,
    ProgressBar,
    EmptyState,
    ErrorBanner,
    JsonImportDialog,
    ReportEditor,
    MessagePrintDialog,
    TranslocoPipe,
  ],
  providers: [
    ReportsFacade,
    ConfirmService,
    // The single-tag test-print field resolves tags through this (locked excluded).
    provideMessageTagsDataSource(),
  ],
  templateUrl: './print-reports-page.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class PrintReportsPage {
  protected readonly facade = inject(ReportsFacade);
  protected readonly api = inject(MessageReportsAdminApiService);
  private readonly downloads = inject(TicketedDownloadService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);

  private readonly importDialog = viewChild.required(JsonImportDialog);

  protected readonly search = signal('');
  protected readonly editorModel = signal<ReportEditorModel | undefined>(undefined);

  protected readonly filtered = computed(() => {
    const term = this.search().trim().toLowerCase();
    const reports = this.facade.reports();
    if (term === '') {
      return reports;
    }
    // Match on both the reportId and the name.
    return reports.filter(
      (report) =>
        report.reportId.toLowerCase().includes(term) || report.name.toLowerCase().includes(term),
    );
  });

  protected readonly pager = createClientPager(this.filtered, ADMIN_PAGE_SIZE);
  protected readonly pageRows = computed(() => [...this.pager.paged()]);

  private readonly exportLabel = translateSignal('admin.reports.menu.export');
  private readonly importLabel = translateSignal('admin.reports.menu.import');
  protected readonly cogItems = computed<MenuItem[]>(() => [
    { label: this.exportLabel(), icon: 'lucideUpload', command: () => this.export() },
    {
      label: this.importLabel(),
      icon: 'lucideDownload',
      command: () => this.importDialog().open(),
    },
  ]);

  protected onSearch(value: string): void {
    this.search.set(value);
    this.pager.reset();
  }

  protected domainNames(report: SystemMessageReport): string {
    return (report.domains ?? []).map((domain) => domain.name ?? domain.domainId).join(', ');
  }

  protected addReport(): void {
    this.editorModel.set({
      mode: 'add',
      report: {
        reportId: '',
        name: '',
        sortOrder: 10,
        templatePath: '',
        domains: [],
        properties: {},
        params: {},
      },
    });
  }

  protected editReport(report: SystemMessageReport): void {
    this.editorModel.set({ mode: 'edit', report });
  }

  protected deleteReport(report: SystemMessageReport): void {
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.reports.confirmDelete.header'),
      message: this.transloco.translate('admin.reports.confirmDelete.message', {
        reportId: report.reportId,
      }),
      accept: () => {
        this.api.delete(report.reportId).subscribe({
          next: () => {
            this.notifications.success('admin.reports.toast.deleted');
            this.facade.refresh();
          },
          error: () => this.notifications.error('admin.reports.toast.deleteError'),
        });
      },
    });
  }

  private async export(): Promise<void> {
    try {
      await this.downloads.openTicketed(this.api.exportUrl(), 'sysadmin');
    } catch {
      this.notifications.error('admin.reports.toast.exportError');
    }
  }

  protected onEditorSaved(): void {
    this.editorModel.set(undefined);
    this.facade.refresh();
  }

  protected onEditorClosed(): void {
    this.editorModel.set(undefined);
  }

  protected onImportClosed(): void {
    this.facade.refresh();
  }
}
