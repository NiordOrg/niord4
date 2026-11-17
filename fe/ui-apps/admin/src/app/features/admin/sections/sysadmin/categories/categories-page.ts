import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';
import type { Observable } from 'rxjs';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import type { CategoryRef } from '../../../../messages/models/message.model';
import {
  MasterDetailTreeAdmin,
  type MasterDetailAdminConfig,
} from '../../../components/master-detail-tree-admin/master-detail-tree-admin';
import { JsonImportDialog } from '../../../components/json-import-dialog/json-import-dialog';
import {
  type AdminCategory,
  type AdminCategoryDesc,
  type CategoryType,
  CategoriesAdminApiService,
} from '../../../services/api/categories-admin-api.service';
import { HierarchicalAdminPage } from '../../../shell/hierarchical-admin-page.base';
import { ModelLanguageSelection } from '../../../services/model-language-selection';
import { CategoryEditor, type CategoryEditorModel } from './category-editor';
import { CategoriesFacade } from './categories.facade';

const CONFIG: MasterDetailAdminConfig = {
  titleKey: 'admin.sections.categories.label',
  descriptionKey: 'admin.categories.intro',
  addLabelKey: 'admin.categories.newCategory',
  addSubLabelKey: 'admin.categories.newSubCategory',
  moveUpKey: 'admin.categories.moveUp',
  moveDownKey: 'admin.categories.moveDown',
  deleteKey: 'admin.categories.delete',
  actionsAriaLabelKey: 'admin.categories.actionsAriaLabel',
  loadErrorKey: 'admin.categories.loadError',
  retryKey: 'admin.categories.retry',
  filterPlaceholderKey: 'admin.categories.filterPlaceholder',
};

/**
 * Categories sysadmin section: a master-detail page (drag-to-re-parent category tree beside an inline
 * category editor) on the shared `MasterDetailTreeAdmin` shell + `HierarchicalAdminPage` base.
 * Category-specifics: TEMPLATE nodes carry a tree badge, Add is disabled while the open editor is a
 * TEMPLATE, and the cog is export / import / re-index (no firing / no published-sort).
 */
@Component({
  selector: 'app-categories-page',
  imports: [MasterDetailTreeAdmin, CategoryEditor, Confirm, JsonImportDialog],
  providers: [CategoriesFacade, ConfirmService, ModelLanguageSelection],
  templateUrl: './categories-page.html',
  host: {
    class: 'contents',
    '(window:beforeunload)': 'onBeforeUnload($event)',
  },
})
export class CategoriesPage extends HierarchicalAdminPage<CategoryRef, CategoryEditorModel> {
  protected readonly modelLanguage = inject(ModelLanguageSelection);
  protected readonly facade = inject(CategoriesFacade);
  protected readonly api = inject(CategoriesAdminApiService);
  private readonly downloads = inject(TicketedDownloadService);

  private readonly importDialog = viewChild.required(JsonImportDialog);

  protected readonly keyPrefix = 'admin.categories';
  protected readonly config = CONFIG;

  /** TEMPLATE nodes get the tree badge. */
  protected readonly isTemplateNode = (category: CategoryRef): boolean =>
    category.type === 'TEMPLATE';
  /** The editor's LIVE type (mirrors the radio, not just the loaded snapshot) — from `(typeChange)`. */
  protected readonly liveType = signal<CategoryType | undefined>(undefined);
  /** Add is blocked while the open editor is a TEMPLATE, tracking live radio changes. */
  protected readonly addDisabled = computed(() => this.liveType() === 'TEMPLATE');

  private readonly exportLabel = translateSignal('admin.categories.menu.export');
  private readonly importLabel = translateSignal('admin.categories.menu.import');
  private readonly reindexLabel = translateSignal('admin.categories.menu.reindex');

  protected readonly cogItems = computed<MenuItem[]>(() => [
    { label: this.exportLabel(), command: () => this.downloads.openPublic(this.api.exportUrl()) },
    { label: this.importLabel(), command: () => this.importDialog().open() },
    { separator: true },
    { label: this.reindexLabel(), command: () => this.recompute() },
  ]);

  protected nodes(): readonly CategoryRef[] {
    return this.facade.categories();
  }

  protected loadEditModel(node: CategoryRef): void {
    this.api.getCategory(node.id).subscribe({
      next: (full) => this.editorModel.set({ mode: 'edit', category: full }),
      error: () => this.notifications.error('admin.categories.toast.loadError'),
    });
  }

  protected buildAddModel(): void {
    // A new category is a child of the current selection (root when nothing is selected); the parent
    // ref carries the id (create payload) + name (add-mode display).
    const parent = this.selectedNode();
    const parentRef: AdminCategory | undefined = parent
      ? { active: true, id: parent.id, descs: [...(parent.descs ?? [])] as AdminCategoryDesc[] }
      : undefined;
    this.editorModel.set({
      mode: 'add',
      category: { active: true, type: 'CATEGORY', descs: [], parent: parentRef },
    });
  }

  protected moveEntity(id: number, parentId: number | null): Observable<unknown> {
    return this.api.moveCategory(id, parentId);
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
    this.liveType.set(undefined);
  }

  protected onImportClosed(): void {
    this.facade.refresh();
  }

  private recompute(): void {
    this.api.recomputeTreeSortOrder().subscribe({
      next: () => this.notifications.success('admin.categories.toast.reindexed'),
      error: () => this.notifications.error('admin.categories.toast.reindexError'),
    });
  }
}
