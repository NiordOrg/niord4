import { Component, computed, effect, input, output, signal, viewChild } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { CdkDrag, CdkDragHandle, type CdkDragEnd } from '@angular/cdk/drag-drop';
import { CdkTree, CdkTreeModule } from '@angular/cdk/tree';
import { TranslocoPipe, translateSignal } from '@jsverse/transloco';
import { BehaviorSubject, type Observable, distinctUntilChanged, map, tap } from 'rxjs';

import { Icon } from '../../icons/icon';
import type { HierarchicalEntity } from './hierarchical-entity.model';
import { type EntityTreeNode, toTreeNodes } from './to-tree-nodes';
import { childrenByKey, filterTree, seedExpandKeys, setEquals } from './tree-filter';

/** One stable empty list for a leaf, so a leaf's children never look changed. */
const NO_CHILDREN: never[] = [];

/** A drag-reparent request: move `movedId` under `targetParentId` (null = the tree root). */
export interface EntityTreeMove {
  readonly movedId: number | string;
  readonly targetParentId: number | string | null;
}

/** A tiny drag (< this many px) is treated as a click, not a re-parent. */
const DRAG_CLICK_THRESHOLD = 5;

/**
 * Single-select hierarchical browser (areas / categories / Keycloak groups)
 * over `@angular/cdk/tree`. Stateless: a row click or keyboard activation just
 * emits `entitySelected` — there is no internal selection highlight to leak
 * across dialog reopens.
 *
 * The lenient filter and the initial-expansion seeding (both absent from CDK)
 * are hand-built and shared with `TreePicker` via `./tree-filter`.
 */
@Component({
  selector: 'app-entity-tree',
  templateUrl: './entity-tree.html',
  styleUrls: ['./entity-tree.css'],
  imports: [CdkTreeModule, CdkDrag, CdkDragHandle, Icon, TranslocoPipe],
})
export class EntityTree<T extends HierarchicalEntity = HierarchicalEntity> {
  readonly entities = input.required<readonly T[]>();
  readonly flagInactive = input<boolean>(true);
  /** When true, the loaded tree starts fully expanded (used by the category dialog). */
  readonly autoExpand = input<boolean>(false);
  /**
   * When true, only the root nodes start expanded; their descendants stay
   * collapsed. Ignored when `autoExpand` is true.
   */
  readonly expandFirstLevel = input<boolean>(false);
  /** Placeholder text (already resolved) for the filter row. */
  readonly filterPlaceholder = input<string>('');
  /** Whether to show the filter/search row (hidden for short, pre-scoped trees). */
  readonly showFilter = input<boolean>(true);
  /**
   * Ids of entities that are already selected outside this tree (e.g. the
   * chips in the parent filter field). Matching nodes get an "already-picked"
   * style so the user can see the full current selection while picking more.
   */
  readonly selectedIds = input<readonly (number | string)[]>([]);
  /**
   * Opt-in drag-to-re-parent. Default false so the read-only consumers (areas-field, filter panels)
   * are unaffected. When true, dragging a row onto another row emits `nodeMoved` (drop target becomes
   * the new parent); dropping on empty space re-parents to the root. Sibling reorder is host-driven
   * (a toolbar), not drag. Self / own-descendant drops are guarded out.
   */
  readonly dnd = input<boolean>(false);
  /**
   * Opt-in "template" node marker: when true, nodes matching `templatePredicate` render a small
   * badge. The predicate keeps this shared component entity-agnostic — the consumer decides what
   * "template" means (e.g. `category.type === 'TEMPLATE'`).
   */
  readonly flagTemplate = input<boolean>(false);
  readonly templatePredicate = input<(entity: T) => boolean>(() => false);

  readonly entitySelected = output<T>();
  /** A drag re-parent request (only when `dnd` is on) — the host confirms + calls the move endpoint. */
  readonly nodeMoved = output<EntityTreeMove>();

  /** Live filter query driving the lenient filter. */
  protected readonly filterText = signal('');

  private readonly tree = viewChild(CdkTree);

  /** Untitled placeholder when no description is available in the active language. */
  private readonly untitledLabel = translateSignal('messages.common.untitled');

  protected readonly treeNodes = computed<EntityTreeNode<T>[]>(() => {
    const flagInactive = this.flagInactive();
    const flagTemplate = this.flagTemplate();
    const isTemplate = this.templatePredicate();
    const expandAll = this.autoExpand();
    const expandFirst = this.expandFirstLevel();
    // Selection is NOT baked into styleClass — it is rendered reactively via `isSelected`
    // (see selectedIdSet below), so nested rows repaint. styleClass carries only the fixed-per-node
    // flags (inactive / template) that never need a reactive repaint.
    return toTreeNodes(this.entities(), {
      untitled: this.untitledLabel(),
      expanded: (_entity, isRoot) => expandAll || (isRoot && expandFirst),
      styleClass: (entity) => {
        const classes: string[] = [];
        if (flagInactive && entity.active === false) classes.push('entity-tree-node-inactive');
        if (flagTemplate && isTemplate(entity)) classes.push('entity-tree-node-template');
        return classes.join(' ');
      },
    });
  });

  /** The (possibly pruned) roots rendered by the CDK tree. */
  protected readonly visibleRoots = computed(
    () => filterTree(this.treeNodes(), this.filterText()).nodes,
  );

  /**
   * CDK's nested rows read their children once, when a row is created, and a row whose key stays
   * keeps them: a search (a pruned copy of an ancestor under the same key) or new entities would
   * never reach the rows below the top level. So each row's children are served from an index of
   * the visible tree, keyed by node key, and that index is published before CDK receives the new
   * roots, because CDK walks the children of the roots as soon as it gets them.
   */
  private readonly visibleChildren = new BehaviorSubject<ReadonlyMap<string, EntityTreeNode<T>[]>>(
    new Map(),
  );
  protected readonly visibleRoots$ = toObservable(this.visibleRoots).pipe(
    tap((roots) => this.visibleChildren.next(childrenByKey(roots))),
  );

  /**
   * Keys to force-expand. When filtering: ancestors + subtrees of matches. When
   * not: the base seed from the mapper `expanded` hint. `setEquals` stops an
   * unrelated `selectedIds` re-map (identical key set) from re-running the
   * seeding effect and collapsing the user's manual expansions.
   */
  private readonly expansionKeys = computed<ReadonlySet<string>>(
    () => {
      const nodes = this.treeNodes();
      const query = this.filterText().trim();
      return query ? filterTree(nodes, query).expandKeys : seedExpandKeys(nodes);
    },
    { equal: setEquals },
  );

  /**
   * Selection is read reactively per row (not baked into `node.styleClass`): the selection changes
   * without the entities changing, and CDK keeps a row whose `trackBy` key stays, so a re-mapped
   * node's `styleClass` would not repaint it. A signal read in the row binding re-evaluates on every
   * selection change instead.
   */
  private readonly selectedIdSet = computed<ReadonlySet<number | string>>(
    () => new Set(this.selectedIds()),
  );
  protected isSelected(node: EntityTreeNode<T>): boolean {
    return this.selectedIdSet().has(node.data.id);
  }

  // CDK holds expansion in the tree's expansion model (keyed by node.key), never
  // on the node — seed it imperatively whenever the desired set changes.
  protected readonly childrenOf = (node: EntityTreeNode<T>): Observable<EntityTreeNode<T>[]> =>
    this.visibleChildren.pipe(
      map((index) => index.get(node.key) ?? NO_CHILDREN),
      distinctUntilChanged(),
    );
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

  protected onActivate(node: EntityTreeNode<T>): void {
    this.entitySelected.emit(node.data);
  }

  /**
   * Free-drag drop of a row. Resolve the row under the release point (via `elementFromPoint` on the
   * native pointer) — a valid target row re-parents the dragged node under it; empty space re-parents
   * to the root. Tiny drags read as a click, and self / own-descendant drops are ignored. The visual
   * position is reset (the host reloads the tree after the move endpoint).
   */
  protected onDragEnded(node: EntityTreeNode<T>, event: CdkDragEnd): void {
    event.source.reset();
    if (
      Math.abs(event.distance.x) < DRAG_CLICK_THRESHOLD &&
      Math.abs(event.distance.y) < DRAG_CLICK_THRESHOLD
    ) {
      return;
    }
    const point = pointerClientPoint(event.event);
    const row = point
      ? document.elementFromPoint(point.x, point.y)?.closest('[data-node-key]')
      : null;
    const targetKey = row?.getAttribute('data-node-key') ?? null;
    if (targetKey === null) {
      this.nodeMoved.emit({ movedId: node.data.id, targetParentId: null });
      return;
    }
    if (targetKey === node.key || this.isDescendant(node, targetKey)) {
      return;
    }
    const target = this.findNode(targetKey);
    if (target) {
      this.nodeMoved.emit({ movedId: node.data.id, targetParentId: target.data.id });
    }
  }

  private findNode(key: string): EntityTreeNode<T> | undefined {
    const walk = (nodes: readonly EntityTreeNode<T>[]): EntityTreeNode<T> | undefined => {
      for (const candidate of nodes) {
        if (candidate.key === key) return candidate;
        const found = walk(candidate.children);
        if (found) return found;
      }
      return undefined;
    };
    return walk(this.treeNodes());
  }

  private isDescendant(node: EntityTreeNode<T>, key: string): boolean {
    const walk = (nodes: readonly EntityTreeNode<T>[]): boolean =>
      nodes.some((child) => child.key === key || walk(child.children));
    return walk(node.children);
  }
}

/** The client-viewport coordinates of a drag's release pointer (mouse or touch). */
function pointerClientPoint(
  event: MouseEvent | TouchEvent | PointerEvent,
): { x: number; y: number } | null {
  if ('clientX' in event) {
    return { x: event.clientX, y: event.clientY };
  }
  const touch = event.changedTouches[0];
  return touch ? { x: touch.clientX, y: touch.clientY } : null;
}
