import { Component, computed, effect, input, output, signal, viewChild } from '@angular/core';
import { CdkTree, CdkTreeModule } from '@angular/cdk/tree';
import { TranslocoPipe, translateSignal } from '@jsverse/transloco';

import { Icon } from '../../icons/icon';
import type { HierarchicalEntity } from '../entity-tree/hierarchical-entity.model';
import { type EntityTreeNode, toTreeNodes } from '../entity-tree/to-tree-nodes';
import { filterTree, seedExpandKeys, setEquals } from '../entity-tree/tree-filter';
import { PickerPopover } from '../picker-popover/picker-popover';

/**
 * Anchored hierarchical multi-select popover — the Area/Category filter-chip
 * editor. Pure presentation over `@angular/cdk/tree`: the consumer passes the
 * loaded `entities` (roots with nested `children`) and the currently-applied
 * `selectedIds`, and applies each `selectionChange` immediately (live-apply).
 *
 * Selection is a pure computed over `selectedIds`, so the unidirectional loop
 * is: toggle → emit the full entity set → consumer updates the filter slice →
 * `selectedIds` changes → checkboxes reflect. No internal selection state, no
 * imperative seeding for selection — an async roots load just re-renders.
 *
 * Each node is independent (no ancestor/descendant propagation) — areas /
 * categories are filtered by their own id, not by ancestry. Done/backdrop/Esc
 * just close.
 */
@Component({
  selector: 'app-tree-picker',
  templateUrl: './tree-picker.html',
  imports: [CdkTreeModule, Icon, PickerPopover, TranslocoPipe],
  host: { class: 'contents' },
})
export class TreePicker<
  T extends HierarchicalEntity & { readonly id: number } = HierarchicalEntity & {
    readonly id: number;
  },
> {
  readonly entities = input.required<readonly T[]>();
  /** The currently-applied ids — selection state lives with the consumer. */
  readonly selectedIds = input<readonly number[]>([]);
  /** Header title as an i18n key. */
  readonly header = input<string>('');
  readonly searchPlaceholder = input<string>('messages.treePicker.searchPlaceholder');
  readonly selectionChange = output<readonly T[]>();

  private readonly shell = viewChild.required(PickerPopover);
  private readonly tree = viewChild(CdkTree);

  /** Live filter query driving the lenient filter. */
  protected readonly filterText = signal('');

  private readonly untitled = translateSignal('messages.common.untitled');

  protected readonly nodes = computed<EntityTreeNode<T>[]>(() =>
    toTreeNodes(this.entities(), {
      untitled: this.untitled(),
      expanded: (_entity, isRoot) => isRoot,
    }),
  );

  /** The (possibly pruned) roots rendered by the CDK tree. */
  protected readonly visibleRoots = computed(
    () => filterTree(this.nodes(), this.filterText()).nodes,
  );

  private readonly expansionKeys = computed<ReadonlySet<string>>(
    () => {
      const nodes = this.nodes();
      const query = this.filterText().trim();
      return query ? filterTree(nodes, query).expandKeys : seedExpandKeys(nodes);
    },
    { equal: setEquals },
  );

  private readonly selectedSet = computed(() => new Set(this.selectedIds()));

  /** The EntityTreeNodes whose entity id is in `selectedIds` — drives the checkboxes. */
  protected readonly selection = computed<EntityTreeNode<T>[]>(() => {
    const ids = this.selectedSet();
    const picked: EntityTreeNode<T>[] = [];
    const walk = (list: readonly EntityTreeNode<T>[]): void => {
      for (const node of list) {
        if (ids.has(node.data.id)) picked.push(node);
        if (node.children.length) walk(node.children);
      }
    };
    walk(this.nodes());
    return picked;
  });

  protected readonly selectedCount = computed(() => this.selection().length);

  protected readonly childrenOf = (node: EntityTreeNode<T>): EntityTreeNode<T>[] => node.children;
  protected readonly trackByKey = (_index: number, node: EntityTreeNode<T>): string => node.key;
  protected readonly keyOf = (node: EntityTreeNode<T>): string => node.key;

  constructor() {
    effect(() => {
      const tree = this.tree();
      const keys = this.expansionKeys();
      if (!tree) return;
      tree._getExpansionModel().setSelection(...keys);
    });
  }

  protected isChecked(node: EntityTreeNode<T>): boolean {
    return this.selectedSet().has(node.data.id);
  }

  /** Open anchored at a persistent element (the chip-editor trigger button). */
  open(anchor: HTMLElement): void {
    this.shell().open(anchor);
  }

  close(): void {
    this.shell().close();
  }

  /** Filter-chip trigger: a re-click while open closes it (else open fresh). */
  toggle(anchor: HTMLElement): void {
    this.shell().toggle(anchor);
  }

  /** Toggle a node's checkbox, then emit the FULL new entity selection. */
  protected onToggleCheckbox(node: EntityTreeNode<T>): void {
    const next = new Set(this.selectedIds());
    const id = node.data.id;
    if (next.has(id)) next.delete(id);
    else next.add(id);
    const picked: EntityTreeNode<T>[] = [];
    const walk = (list: readonly EntityTreeNode<T>[]): void => {
      for (const n of list) {
        if (next.has(n.data.id)) picked.push(n);
        if (n.children.length) walk(n.children);
      }
    };
    walk(this.nodes());
    this.onSelectionChange(picked);
  }

  protected onSelectionChange(
    value: EntityTreeNode<T> | EntityTreeNode<T>[] | null | undefined,
  ): void {
    const nodes = Array.isArray(value) ? value : value ? [value] : [];
    this.selectionChange.emit(nodes.map((node) => node.data));
  }

  protected clear(): void {
    this.selectionChange.emit([]);
  }
}
