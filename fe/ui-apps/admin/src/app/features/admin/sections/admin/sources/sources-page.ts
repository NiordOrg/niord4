import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';

import { Menu } from '../../../../../shared/components/menu/menu';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { MenuTrigger } from '../../../../../shared/components/menu-trigger/menu-trigger';
import { Icon } from '../../../../../shared/icons/icon';
import { Pager } from '../../../../../shared/components/pager/pager';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { JsonImportDialog } from '../../../components/json-import-dialog/json-import-dialog';
import { Source, SourcesApiService } from '../../../../messages/services/api/sources-api.service';
import { AdminSectionPage } from '../../../shell/admin-section-page';
import { createClientPager } from '../../../utils/client-pager';
import { ADMIN_PAGE_SIZE } from '../../../utils/kit-constants';
import { SourceEditor, SourceEditorModel } from './source-editor';
import { SourcesFacade } from './sources.facade';
import { ModelLanguageSelection } from '../../../services/model-language-selection';
import { ModelLanguagePicker } from '../../../components/model-language-picker/model-language-picker';

/**
 * Sources section: client-filtered, client-paged catalog list with a
 * create/edit drawer, confirm-delete, and JSON import/export. The list shows
 * the selected model-language desc (the endpoint lang-filters server-side); editing
 * fetches the all-language details.
 */
@Component({
  selector: 'app-sources-page',
  imports: [
    AdminSectionPage,
    ModelLanguagePicker,
    Icon,
    Menu,
    Pager,
    Confirm,
    MenuTrigger,
    ProgressBar,
    EmptyState,
    ErrorBanner,
    JsonImportDialog,
    SourceEditor,
    TranslocoPipe,
  ],
  providers: [SourcesFacade, ConfirmService, ModelLanguageSelection],
  templateUrl: './sources-page.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class SourcesPage {
  protected readonly modelLanguage = inject(ModelLanguageSelection);
  protected readonly facade = inject(SourcesFacade);
  protected readonly api = inject(SourcesApiService);
  private readonly downloads = inject(TicketedDownloadService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);

  private readonly importDialog = viewChild.required(JsonImportDialog);

  protected readonly search = signal('');

  /**
   * Filter on the current-language name OR abbreviation, case-insensitive.
   * Crash-safe for sources without a current-language desc — such rows simply
   * don't match.
   */
  protected readonly filtered = computed(() => {
    const term = this.search().trim().toLowerCase();
    const sources = this.facade.sources();
    if (!term) {
      return sources;
    }
    return sources.filter((source) => {
      const desc = source.descs[0];
      return (
        desc !== undefined &&
        (desc.name.toLowerCase().includes(term) || desc.abbreviation.toLowerCase().includes(term))
      );
    });
  });

  protected readonly pager = createClientPager(this.filtered, ADMIN_PAGE_SIZE);

  protected readonly editorModel = signal<SourceEditorModel | undefined>(undefined);

  private readonly exportLabel = translateSignal('admin.sources.menu.export');
  private readonly importLabel = translateSignal('admin.sources.menu.import');

  protected readonly cogItems = computed<MenuItem[]>(() => [
    { label: this.exportLabel(), command: () => this.downloads.openPublic(this.api.exportUrl()) },
    { label: this.importLabel(), command: () => this.importDialog().open() },
  ]);

  protected onSearch(value: string): void {
    this.search.set(value);
    this.pager.reset();
  }

  protected addSource(): void {
    this.editorModel.set({ mode: 'add', source: { active: true, descs: [] } });
  }

  protected editSource(source: Source): void {
    if (source.id === undefined) {
      return;
    }
    this.api.getSource(source.id).subscribe({
      next: (details) => this.editorModel.set({ mode: 'edit', source: details }),
      error: () => this.notifications.error('admin.sources.toast.loadError'),
    });
  }

  protected deleteSource(source: Source): void {
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.sources.confirmDelete.header'),
      message: this.transloco.translate('admin.sources.confirmDelete.message', {
        name: source.descs[0]?.name ?? '',
      }),
      accept: () => {
        if (source.id === undefined) {
          return;
        }
        this.api.delete(source.id).subscribe({
          next: () => {
            this.notifications.success('admin.sources.toast.deleted');
            this.facade.refresh();
          },
          error: () => this.notifications.error('admin.sources.toast.deleteError'),
        });
      },
    });
  }

  protected onEditorSaved(): void {
    this.editorModel.set(undefined);
    this.facade.refresh();
  }

  protected onImportClosed(): void {
    // Consumers deliberately refetch on EVERY close path, so an import's
    // results are always reflected in the list.
    this.facade.refresh();
  }
}
