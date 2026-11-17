import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';
import type { Observable } from 'rxjs';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import type { AreaRef } from '../../../../messages/models/messages-filter-params.model';
import { MessagesAreaSortApiService } from '../../../../messages/services/api/messages-area-sort-api.service';
import {
  MasterDetailTreeAdmin,
  type MasterDetailAdminConfig,
} from '../../../components/master-detail-tree-admin/master-detail-tree-admin';
import { JsonImportDialog } from '../../../components/json-import-dialog/json-import-dialog';
import {
  type AdminArea,
  AreasAdminApiService,
} from '../../../services/api/areas-admin-api.service';
import { HierarchicalAdminPage } from '../../../shell/hierarchical-admin-page.base';
import { ModelLanguageSelection } from '../../../services/model-language-selection';
import { AreaEditor, type AreaEditorModel } from './area-editor';
import { AreasFacade } from './areas.facade';
import { FiringGenerateDialog } from './firing-generate-dialog';

const CONFIG: MasterDetailAdminConfig = {
  titleKey: 'admin.sections.areas.label',
  descriptionKey: 'admin.areas.intro',
  addLabelKey: 'admin.areas.newArea',
  addSubLabelKey: 'admin.areas.newSubArea',
  moveUpKey: 'admin.areas.moveUp',
  moveDownKey: 'admin.areas.moveDown',
  deleteKey: 'admin.areas.delete',
  actionsAriaLabelKey: 'admin.areas.actionsAriaLabel',
  loadErrorKey: 'admin.areas.loadError',
  retryKey: 'admin.areas.retry',
  filterPlaceholderKey: 'admin.areas.filterPlaceholder',
};

/**
 * Areas admin section: a master-detail page (drag-to-re-parent area tree beside an inline area
 * editor) on the shared `MasterDetailTreeAdmin` shell + `HierarchicalAdminPage` base. Area-specifics:
 * the full-loaded parent drives the editor's message-sorting inheritance; the cog adds
 * re-index-published-messages and (firing-schedule domains) firing-area message generation.
 */
@Component({
  selector: 'app-areas-page',
  imports: [MasterDetailTreeAdmin, AreaEditor, Confirm, JsonImportDialog, FiringGenerateDialog],
  providers: [AreasFacade, ConfirmService, ModelLanguageSelection],
  templateUrl: './areas-page.html',
  host: {
    class: 'contents',
    '(window:beforeunload)': 'onBeforeUnload($event)',
  },
})
export class AreasPage extends HierarchicalAdminPage<AreaRef, AreaEditorModel> {
  protected readonly modelLanguage = inject(ModelLanguageSelection);
  protected readonly facade = inject(AreasFacade);
  protected readonly api = inject(AreasAdminApiService);
  private readonly messagesAreaSort = inject(MessagesAreaSortApiService);
  private readonly downloads = inject(TicketedDownloadService);
  private readonly domain = inject(DomainFacade);

  private readonly importDialog = viewChild.required(JsonImportDialog);

  protected readonly keyPrefix = 'admin.areas';
  protected readonly config = CONFIG;
  protected readonly firingVisible = signal(false);

  /** The fully-loaded selected area (parent chain) — parent for a new sub-area (sorting inheritance). */
  private readonly selectedFull = signal<AdminArea | undefined>(undefined);

  /**
   * Add is blocked while a node is selected but its detail has not loaded yet — otherwise a
   * New-sub-area click during (or after a failed) `getArea` fetch would build the child against a
   * stale/undefined parent (wrong parent, or created at the root). Root-add (no selection) stays enabled.
   */
  protected readonly addDisabled = computed(
    () => this.selectedNode() !== undefined && this.selectedFull() === undefined,
  );

  private readonly exportLabel = translateSignal('admin.areas.menu.export');
  private readonly importLabel = translateSignal('admin.areas.menu.import');
  private readonly reindexAreasLabel = translateSignal('admin.areas.menu.reindexAreas');
  private readonly reindexMessagesLabel = translateSignal('admin.areas.menu.reindexMessages');
  private readonly generateFiringLabel = translateSignal('admin.areas.menu.generateFiring');

  protected readonly cogItems = computed<MenuItem[]>(() => {
    const items: MenuItem[] = [
      { label: this.exportLabel(), command: () => this.downloads.openPublic(this.api.exportUrl()) },
      { label: this.importLabel(), command: () => this.importDialog().open() },
      { separator: true },
      { label: this.reindexAreasLabel(), command: () => this.recomputeAreas() },
      { label: this.reindexMessagesLabel(), command: () => this.recomputePublished() },
    ];
    if (this.domain.capabilities().firingSchedule) {
      items.push(
        { separator: true },
        { label: this.generateFiringLabel(), command: () => this.firingVisible.set(true) },
      );
    }
    return items;
  });

  protected nodes(): readonly AreaRef[] {
    return this.facade.areas();
  }

  protected loadEditModel(node: AreaRef): void {
    // Drop the previous selection's detail immediately so a New-sub-area click before this fetch
    // resolves can't parent the new area against a stale area (addDisabled gates on this).
    this.selectedFull.set(undefined);
    this.api.getArea(node.id).subscribe({
      next: (full) => {
        this.selectedFull.set(full);
        this.editorModel.set({ mode: 'edit', area: full });
      },
      error: () => this.notifications.error('admin.areas.toast.loadError'),
    });
  }

  protected buildAddModel(): void {
    // A new area is a child of the current selection (root when nothing is selected); the full parent
    // (with its own ancestor chain) drives the editor's message-sorting inheritance.
    const parent = this.selectedFull();
    this.editorModel.set({ mode: 'add', area: { active: true, descs: [], parent } });
  }

  protected moveEntity(id: number, parentId: number | null): Observable<unknown> {
    return this.api.moveArea(id, parentId);
  }
  protected changeSort(id: number, moveUp: boolean): Observable<unknown> {
    return this.api.changeSortOrder(id, moveUp);
  }
  protected deleteEntity(id: number): Observable<unknown> {
    return this.api.delete(id);
  }
  protected refresh(): void {
    this.facade.refresh();
  }
  protected override clearSelection(): void {
    super.clearSelection();
    this.selectedFull.set(undefined);
  }

  protected onImportClosed(): void {
    this.facade.refresh();
  }

  private recomputeAreas(): void {
    this.api.recomputeTreeSortOrder().subscribe({
      next: () => this.notifications.success('admin.areas.toast.reindexed'),
      error: () => this.notifications.error('admin.areas.toast.reindexError'),
    });
  }

  private recomputePublished(): void {
    this.messagesAreaSort.recomputeAreaSortOrder().subscribe({
      next: () => this.notifications.success('admin.areas.toast.reindexed'),
      error: () => this.notifications.error('admin.areas.toast.reindexError'),
    });
  }
}
