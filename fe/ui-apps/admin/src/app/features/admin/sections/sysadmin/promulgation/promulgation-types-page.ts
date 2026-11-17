import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { Menu } from '../../../../../shared/components/menu/menu';
import { MenuTrigger } from '../../../../../shared/components/menu-trigger/menu-trigger';
import { Pager } from '../../../../../shared/components/pager/pager';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { Icon } from '../../../../../shared/icons/icon';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { JsonImportDialog } from '../../../components/json-import-dialog/json-import-dialog';
import { PromulgationAdminApiService } from '../../../services/api/promulgation-admin-api.service';
import { AdminSectionPage } from '../../../shell/admin-section-page';
import { createClientPager } from '../../../utils/client-pager';
import { ADMIN_PAGE_SIZE } from '../../../utils/kit-constants';
import type { AdminPromulgationType } from './promulgation-admin.model';
import { PromulgationTypeEditor } from './promulgation-type-editor';
import { PromulgationTypesFacade } from './promulgation-types.facade';

/** Editor-drawer input: the open mode plus the type the drawer opens on. */
export interface PromulgationTypeEditorModel {
  readonly mode: 'add' | 'edit';
  readonly type: AdminPromulgationType;
}

/**
 * Promulgation Types (sysadmin) — the channel catalog behind the message
 * editor's promulgation sections: client-filtered, client-paged list with a
 * per-service "Add" dropdown (one entry per registered backend service —
 * `serviceName` labels are backend display data, never translation keys),
 * edit / delete row actions, and a cog with ticketed JSON export + batch-job
 * import.
 */
@Component({
  selector: 'app-promulgation-types-page',
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
    PromulgationTypeEditor,
    TranslocoPipe,
  ],
  providers: [PromulgationTypesFacade, ConfirmService],
  templateUrl: './promulgation-types-page.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class PromulgationTypesPage {
  protected readonly facade = inject(PromulgationTypesFacade);
  protected readonly api = inject(PromulgationAdminApiService);
  private readonly downloads = inject(TicketedDownloadService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);

  private readonly importDialog = viewChild.required(JsonImportDialog);

  protected readonly search = signal('');
  protected readonly editorModel = signal<PromulgationTypeEditorModel | undefined>(undefined);

  /** Client-side filter on type ID, name OR service ID, case-insensitive. */
  protected readonly filtered = computed(() => {
    const term = this.search().trim().toLowerCase();
    const types = this.facade.types();
    if (term === '') {
      return types;
    }
    return types.filter(
      (type) =>
        type.typeId.toLowerCase().includes(term) ||
        type.name.toLowerCase().includes(term) ||
        type.serviceId.toLowerCase().includes(term),
    );
  });

  protected readonly pager = createClientPager(this.filtered, ADMIN_PAGE_SIZE);

  /** One "Add" menu entry per registered backend service, labelled with its `serviceName` verbatim. */
  protected readonly addItems = computed<MenuItem[]>(() =>
    this.facade.services().map((service) => ({
      label: service.serviceName,
      command: () => this.addType(service.serviceId),
    })),
  );

  private readonly exportLabel = translateSignal('admin.promulgation.menu.export');
  private readonly importLabel = translateSignal('admin.promulgation.menu.import');
  protected readonly cogItems = computed<MenuItem[]>(() => [
    { label: this.exportLabel(), icon: 'lucideUpload', command: () => void this.export() },
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

  /** Comma-joined domain names. */
  protected domainNames(type: AdminPromulgationType): string {
    return (type.domains ?? []).map((domain) => domain.name ?? '').join(', ');
  }

  protected addType(serviceId: string): void {
    // New-type defaults: active, OPTIONAL, priority 0, no domains, no
    // language restriction.
    this.editorModel.set({
      mode: 'add',
      type: {
        serviceId,
        typeId: '',
        name: '',
        priority: 0,
        active: true,
        requirement: 'OPTIONAL',
        domains: [],
      },
    });
  }

  /** The list GET returns full detail VOs — edit opens on the row, no detail refetch. */
  protected editType(type: AdminPromulgationType): void {
    this.editorModel.set({ mode: 'edit', type });
  }

  protected deleteType(type: AdminPromulgationType): void {
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.promulgation.confirmDelete.header'),
      message: this.transloco.translate('admin.promulgation.confirmDelete.message', {
        typeId: type.typeId,
      }),
      accept: () => {
        this.api.deleteType(type.typeId).subscribe({
          next: () => {
            this.notifications.success('admin.promulgation.toast.deleted');
            this.facade.refresh();
          },
          error: () => this.notifications.error('admin.promulgation.toast.deleteError'),
        });
      },
    });
  }

  protected onEditorSaved(): void {
    this.editorModel.set(undefined);
    this.facade.refresh();
  }

  protected onEditorClosed(): void {
    this.editorModel.set(undefined);
  }

  /** The list endpoint is sysadmin-checked server-side — the export download needs a sysadmin ticket. */
  private async export(): Promise<void> {
    try {
      await this.downloads.openTicketed(this.api.exportUrl(), 'sysadmin');
    } catch {
      this.notifications.error('admin.promulgation.toast.exportError');
    }
  }

  protected onImportClosed(): void {
    this.facade.refresh();
  }
}
