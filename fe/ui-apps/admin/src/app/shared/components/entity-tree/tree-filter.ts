import type { HierarchicalEntity } from './hierarchical-entity.model';
import type { EntityTreeNode } from './to-tree-nodes';

/**
 * Shared tree behaviours the `@angular/cdk/tree` substrate does not provide
 * (lenient label filtering + initial-expansion seeding). Both wrappers
 * (`EntityTree`, `TreePicker`) import from here so their filter +
 * expansion-seeding semantics cannot diverge.
 */

export interface TreeFilterResult<T extends HierarchicalEntity> {
  /** The pruned tree to render. */
  readonly nodes: EntityTreeNode<T>[];
  /** Keys that must be expanded so every match is visible (path to + subtree of matches). */
  readonly expandKeys: ReadonlySet<string>;
}

/**
 * Lenient, label-based filter:
 * - keep a node if it OR any descendant matches the query;
 * - keep the ancestors of a match and mark them for auto-expansion so the match
 *   is revealed;
 * - a node that matches by its own label shows its FULL subtree (and is expanded
 *   along with all its descendants).
 *
 * An empty/whitespace query returns the tree unchanged with no forced expansion.
 */
export function filterTree<T extends HierarchicalEntity>(
  nodes: readonly EntityTreeNode<T>[],
  query: string,
): TreeFilterResult<T> {
  const q = query.trim().toLowerCase();
  if (!q) return { nodes: [...nodes], expandKeys: new Set() };

  const expandKeys = new Set<string>();

  const addSubtreeKeys = (list: readonly EntityTreeNode<T>[]): void => {
    for (const node of list) {
      if (node.children.length > 0) {
        expandKeys.add(node.key);
        addSubtreeKeys(node.children);
      }
    }
  };

  const walk = (list: readonly EntityTreeNode<T>[]): EntityTreeNode<T>[] => {
    const kept: EntityTreeNode<T>[] = [];
    for (const node of list) {
      if (node.label.toLowerCase().includes(q)) {
        // Direct match: keep the full subtree and expand it so it's all visible.
        kept.push(node);
        if (node.children.length > 0) {
          expandKeys.add(node.key);
          addSubtreeKeys(node.children);
        }
        continue;
      }
      const children = walk(node.children);
      if (children.length > 0) {
        // Ancestor of a match: keep the matching branch and auto-expand.
        expandKeys.add(node.key);
        kept.push({ ...node, children });
      }
    }
    return kept;
  };

  return { nodes: walk(nodes), expandKeys };
}

/**
 * Collects the keys of nodes whose mapper `expanded` HINT is `true` — the base
 * initial-expansion seed (autoExpand → every node; expandFirstLevel / picker
 * roots → root nodes only). CDK does not read `expanded` off the node, so the
 * wrappers feed these keys into the tree's expansion model imperatively.
 */
export function seedExpandKeys<T extends HierarchicalEntity>(
  nodes: readonly EntityTreeNode<T>[],
  into = new Set<string>(),
): Set<string> {
  for (const node of nodes) {
    if (node.expanded) into.add(node.key);
    if (node.children.length > 0) seedExpandKeys(node.children, into);
  }
  return into;
}

/**
 * Content equality for `ReadonlySet<string>`, used as a `computed({ equal })`
 * comparator so an expansion-key set that is structurally identical (e.g. after
 * an unrelated `selectedIds` change re-maps the nodes) does NOT re-notify the
 * seeding effect and thrash the user's manual expand/collapse state.
 */
export function setEquals(a: ReadonlySet<string>, b: ReadonlySet<string>): boolean {
  if (a === b) return true;
  if (a.size !== b.size) return false;
  for (const value of a) if (!b.has(value)) return false;
  return true;
}
