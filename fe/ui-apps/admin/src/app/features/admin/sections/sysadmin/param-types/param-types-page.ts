import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { Icon } from '../../../../../shared/icons/icon';
import { Menu } from '../../../../../shared/components/menu/menu';
import { MenuTriggerFor } from '../../../../../shared/components/menu/menu-trigger.directive';
import { Pager } from '../../../../../shared/components/pager/pager';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { ModelLanguageSelection, type ModelText } from '../../../services/model-language-selection';
import { ModelLanguagePicker } from '../../../components/model-language-picker/model-language-picker';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { MenuTrigger } from '../../../../../shared/components/menu-trigger/menu-trigger';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { JsonImportDialog } from '../../../components/json-import-dialog/json-import-dialog';
import {
  ParamType,
  ParamTypeKind,
  ParamTypesAdminApiService,
} from '../../../services/api/param-types-admin-api.service';
import { AdminSectionPage } from '../../../shell/admin-section-page';
import { createClientPager } from '../../../utils/client-pager';
import { ADMIN_PAGE_SIZE } from '../../../utils/kit-constants';
import { ParamTypeEditor, ParamTypeEditorModel } from './param-type-editor';
import { ParamTypesFacade } from './param-types.facade';

/**
 * Parameter Types (sysadmin) — list + drawer editor for the three kinds. STANDARD
 * rows are read-only (no action icons); LIST/COMPOSITE are created via the New
 * menu-button and edited/copied/deleted. The cog carries public Export + JSON
 * Import. Detail (values/templateParams) is re-fetched on edit/copy.
 */
@Component({
  selector: 'app-param-types-page',
  imports: [
    AdminSectionPage,
    ModelLanguagePicker,
    Pager,
    Icon,
    Menu,
    MenuTriggerFor,
    Confirm,
    MenuTrigger,
    ProgressBar,
    EmptyState,
    ErrorBanner,
    JsonImportDialog,
    ParamTypeEditor,
    TranslocoPipe,
  ],
  providers: [ParamTypesFacade, ConfirmService, ModelLanguageSelection],
  templateUrl: './param-types-page.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class ParamTypesPage {
  protected readonly facade = inject(ParamTypesFacade);
  protected readonly api = inject(ParamTypesAdminApiService);
  private readonly downloads = inject(TicketedDownloadService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  protected readonly modelLanguage = inject(ModelLanguageSelection);

  private readonly importDialog = viewChild.required(JsonImportDialog);

  protected readonly search = signal('');
  protected readonly editorModel = signal<ParamTypeEditorModel | undefined>(undefined);

  protected readonly filtered = computed(() => {
    const term = this.search().trim().toLowerCase();
    const paramTypes = this.facade.paramTypes();
    if (term === '') {
      return paramTypes;
    }
    return paramTypes.filter((paramType) => (paramType.name ?? '').toLowerCase().includes(term));
  });

  protected readonly pager = createClientPager(this.filtered, ADMIN_PAGE_SIZE);
  protected readonly pageRows = computed(() => [...this.pager.paged()]);

  private readonly newListLabel = translateSignal('admin.paramTypes.menu.newList');
  private readonly newCompositeLabel = translateSignal('admin.paramTypes.menu.newComposite');
  protected readonly addItems = computed<MenuItem[]>(() => [
    { label: this.newListLabel(), command: () => this.addParamType('LIST') },
    { label: this.newCompositeLabel(), command: () => this.addParamType('COMPOSITE') },
  ]);

  private readonly exportLabel = translateSignal('admin.paramTypes.menu.export');
  private readonly importLabel = translateSignal('admin.paramTypes.menu.import');
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

  protected kindLabelKey(paramType: ParamType): string {
    return `admin.paramTypes.kind.${paramType.type}`;
  }

  /** LIST values' / COMPOSITE param names' current-language text, one entry per line. */
  protected valuesList(paramType: ParamType): ModelText[] {
    if (paramType.type === 'LIST') {
      return (paramType.values ?? [])
        .map((entry) => this.modelLanguage.text(entry.descs, 'value'))
        .filter((text) => text.value !== '');
    }
    if (paramType.type === 'COMPOSITE') {
      return (paramType.templateParams ?? [])
        .map((param) => this.modelLanguage.text(param.descs, 'name'))
        .filter((text) => text.value !== '');
    }
    return [];
  }

  protected addParamType(kind: Exclude<ParamTypeKind, 'STANDARD'>): void {
    this.editorModel.set({
      mode: 'add',
      paramType: { type: kind, values: [], templateParams: [] },
    });
  }

  protected editParamType(paramType: ParamType): void {
    if (paramType.type === 'STANDARD' || paramType.id === undefined) {
      return;
    }
    this.api.getDetails(paramType.id).subscribe({
      next: (detail) => this.editorModel.set({ mode: 'edit', paramType: detail }),
      error: () => this.notifications.error('admin.paramTypes.toast.loadError'),
    });
  }

  /** Copy = load detail, strip id + name, open in add mode. */
  protected copyParamType(paramType: ParamType): void {
    if (paramType.type === 'STANDARD' || paramType.id === undefined) {
      return;
    }
    this.api.getDetails(paramType.id).subscribe({
      next: (detail) =>
        this.editorModel.set({
          mode: 'add',
          paramType: { ...detail, id: undefined, name: undefined },
        }),
      error: () => this.notifications.error('admin.paramTypes.toast.loadError'),
    });
  }

  protected deleteParamType(paramType: ParamType): void {
    if (paramType.type === 'STANDARD' || paramType.id === undefined) {
      return;
    }
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.paramTypes.confirmDelete.header'),
      message: this.transloco.translate('admin.paramTypes.confirmDelete.message', {
        name: paramType.name ?? '',
      }),
      accept: () => {
        this.api.delete(paramType.id!).subscribe({
          next: () => {
            this.notifications.success('admin.paramTypes.toast.deleted');
            this.facade.refresh();
          },
          error: () => this.notifications.error('admin.paramTypes.toast.deleteError'),
        });
      },
    });
  }

  private export(): void {
    this.downloads.openPublic(this.api.exportUrl());
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
