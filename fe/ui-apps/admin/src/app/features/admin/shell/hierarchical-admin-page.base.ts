import { HttpErrorResponse } from '@angular/common/http';
import { computed, inject, signal } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';
import type { Observable } from 'rxjs';

import { NotificationService } from '../../../core/notifications/notification.service';
import { problemDetail } from '../../../shared/api/problem-detail';
import type { EntityTreeMove } from '../../../shared/components/entity-tree/entity-tree';
import type { HierarchicalEntity } from '../../../shared/components/entity-tree/hierarchical-entity.model';
import { ConfirmService } from '../../../shared/services/confirm.service';

/** An inline-editor model with an add/edit mode (drives move-sibling enablement). */
export interface EditorModelWithMode {
  readonly mode: 'add' | 'edit';
}

/**
 * Shared controller base for the master-detail admin sections (Areas, Categories). Owns the selection
 * + editor-dirty state, the toolbar enablement computeds, the discard guard (`beforeunload` +
 * in-page), and the tree-mutation handlers (move-sibling, delete, drag-reparent) — all keyed off a
 * `keyPrefix` for i18n. Subclasses supply the API calls + how a node loads/builds into their editor
 * model via the abstract hooks. Not a component: subclasses are the `@Component`s and provide the
 * `ConfirmService` this base injects.
 */
export abstract class HierarchicalAdminPage<
  TNode extends HierarchicalEntity,
  TEditorModel extends EditorModelWithMode,
> {
  protected readonly confirm = inject(ConfirmService);
  protected readonly transloco = inject(TranslocoService);
  protected readonly notifications = inject(NotificationService);

  /** i18n key prefix for this section, e.g. `admin.areas` / `admin.categories`. */
  protected abstract readonly keyPrefix: string;

  /** The tree-selected node (a light tree ref); drives toolbar enablement + highlight. */
  protected readonly selectedNode = signal<TNode | undefined>(undefined);
  protected readonly editorModel = signal<TEditorModel | undefined>(undefined);
  protected readonly editorDirty = signal(false);

  readonly hasSelection = computed(() => this.selectedNode() !== undefined);
  /** Highlights the selected node in the tree (reactive, so nested rows repaint too). */
  readonly selectedIds = computed<readonly (number | string)[]>(() => {
    const node = this.selectedNode();
    return node === undefined ? [] : [node.id];
  });
  /** Sibling move is edit-only and blocked while the form has unsaved edits. */
  readonly moveDisabled = computed(() => this.editorModel()?.mode !== 'edit' || this.editorDirty());

  /** Read by the route CanDeactivate guard (leaving the section). */
  hasUnsavedChanges(): boolean {
    return this.editorDirty();
  }
  /** Native reload / tab-close prompt while an edit is in flight (the only leg the router can't cover). */
  onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.editorDirty()) {
      event.preventDefault();
    }
  }

  // ── subclass hooks ───────────────────────────────────────────────────────────
  /** The current tree nodes (the page facade's entities). */
  protected abstract nodes(): readonly TNode[];
  /** Fetch the full detail for `node` and set `editorModel` to an edit-mode model. */
  protected abstract loadEditModel(node: TNode): void;
  /** Set `editorModel` to an add-mode model parented to the current selection. */
  protected abstract buildAddModel(): void;
  protected abstract moveEntity(id: number, parentId: number | null): Observable<unknown>;
  protected abstract changeSort(id: number, moveUp: boolean): Observable<unknown>;
  protected abstract deleteEntity(id: number): Observable<unknown>;
  protected abstract refresh(): void;

  // ── handlers (wired from the shell's outputs) ────────────────────────────────
  protected select(node: TNode): void {
    // Guard first: switching selection replaces the editor, so an unsaved edit would be lost. On
    // cancel the current selection (and its tree highlight) stays put.
    this.guardUnsaved(() => {
      this.selectedNode.set(node);
      this.loadEditModel(node);
    });
  }

  protected add(): void {
    this.guardUnsaved(() => this.buildAddModel());
  }

  protected moveSibling(moveUp: boolean): void {
    const id = this.selectedNode()?.id;
    if (typeof id !== 'number') return;
    this.changeSort(id, moveUp).subscribe({
      next: () => this.refresh(),
      error: () => this.notifications.error(`${this.keyPrefix}.toast.moveError`),
    });
  }

  protected deleteSelected(): void {
    const node = this.selectedNode();
    if (node === undefined || typeof node.id !== 'number') return;
    const id = node.id;
    this.confirm.confirmDestructive({
      header: this.transloco.translate(`${this.keyPrefix}.confirmDelete.header`),
      message: this.transloco.translate(`${this.keyPrefix}.confirmDelete.message`, {
        name: node.descs?.[0]?.name ?? '',
      }),
      accept: () => {
        this.deleteEntity(id).subscribe({
          next: () => {
            this.notifications.success(`${this.keyPrefix}.toast.deleted`);
            this.clearSelection();
            this.refresh();
          },
          error: (error: unknown) =>
            this.notifyFailure(
              error,
              409,
              `${this.keyPrefix}.toast.deleteRefused`,
              `${this.keyPrefix}.toast.deleteError`,
            ),
        });
      },
    });
  }

  protected onNodeMoved(move: EntityTreeMove): void {
    const movedName = this.findNodeName(move.movedId) ?? '';
    const targetName =
      move.targetParentId === null
        ? this.transloco.translate(`${this.keyPrefix}.confirmMove.root`)
        : (this.findNodeName(move.targetParentId) ?? '');
    this.confirm.confirmNeutral({
      header: this.transloco.translate(`${this.keyPrefix}.confirmMove.header`),
      message: this.transloco.translate(`${this.keyPrefix}.confirmMove.message`, {
        name: movedName,
        target: targetName,
      }),
      accept: () => {
        const parentId = move.targetParentId === null ? null : Number(move.targetParentId);
        this.moveEntity(Number(move.movedId), parentId).subscribe({
          next: () => {
            this.clearSelection();
            this.refresh();
          },
          error: (error: unknown) =>
            this.notifyFailure(
              error,
              400,
              `${this.keyPrefix}.toast.moveRefused`,
              `${this.keyPrefix}.toast.moveError`,
            ),
        });
      },
    });
  }

  /**
   * A refusal the server explains (the given status with a problem detail, such as what still uses
   * the node) shows that reason; any other failure shows the plain error.
   */
  protected notifyFailure(
    error: unknown,
    refusedStatus: number,
    refusedKey: string,
    plainKey: string,
  ): void {
    const detail =
      error instanceof HttpErrorResponse && error.status === refusedStatus
        ? problemDetail(error)
        : undefined;
    if (detail === undefined) {
      this.notifications.error(plainKey);
    } else {
      this.notifications.error(refusedKey, { detail });
    }
  }

  protected onEditorSaved(): void {
    this.clearSelection();
    this.refresh();
  }
  protected onEditorCancel(): void {
    this.clearSelection();
  }

  /** Resets selection + editor. Subclasses may `override` to also clear their own per-selection state. */
  protected clearSelection(): void {
    this.editorModel.set(undefined);
    this.selectedNode.set(undefined);
    this.editorDirty.set(false);
  }

  /** Run `proceed`, first confirming a discard when the editor has unsaved changes. */
  protected guardUnsaved(proceed: () => void): void {
    if (!this.editorDirty()) {
      proceed();
      return;
    }
    // Discarding unsaved edits is data-losing → the danger-styled accept button (matching the
    // route-away discard confirm), not the neutral primary one.
    this.confirm.confirmDestructive({
      header: this.transloco.translate('shared.discard.header'),
      message: this.transloco.translate('shared.discard.message'),
      accept: proceed,
    });
  }

  protected findNodeName(id: number | string): string | undefined {
    const walk = (nodes: readonly TNode[]): TNode | undefined => {
      for (const node of nodes) {
        if (node.id === id) return node;
        const found = node.children ? walk(node.children as readonly TNode[]) : undefined;
        if (found) return found;
      }
      return undefined;
    };
    return walk(this.nodes())?.descs?.[0]?.name;
  }
}
