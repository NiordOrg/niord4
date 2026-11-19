import { describe, expect, it } from 'vitest';

import type { HierarchicalEntity } from './hierarchical-entity.model';
import type { EntityTreeNode } from './to-tree-nodes';
import { childrenByKey, filterTree, setEquals } from './tree-filter';

function node(
  key: string,
  label: string,
  children: EntityTreeNode<HierarchicalEntity>[] = [],
): EntityTreeNode<HierarchicalEntity> {
  return {
    key,
    label,
    data: { id: key },
    expanded: false,
    styleClass: '',
    children,
  };
}

/**
 * Anchorland › { Kattegat › Læsø, Skagerrak }, Sweden › Göteborg.
 * Rebuilt per test so mutation-by-accident can't leak between cases.
 */
function sampleTree(): EntityTreeNode<HierarchicalEntity>[] {
  return [
    node('anc', 'Anchorland', [
      node('kat', 'Kattegat', [node('lae', 'Læsø')]),
      node('ska', 'Skagerrak'),
    ]),
    node('se', 'Sweden', [node('got', 'Göteborg')]),
  ];
}

describe('filterTree', () => {
  it('returns the tree unchanged with no forced expansion for an empty query', () => {
    const tree = sampleTree();
    const result = filterTree(tree, '');

    expect(result.nodes).toEqual(tree);
    expect(result.expandKeys.size).toBe(0);
  });

  it('treats a whitespace-only query as empty', () => {
    const tree = sampleTree();
    const result = filterTree(tree, '   ');

    expect(result.nodes).toEqual(tree);
    expect(result.expandKeys.size).toBe(0);
  });

  it('keeps the FULL subtree of a direct match and expands its descendants', () => {
    const tree = sampleTree();
    const result = filterTree(tree, 'Anchorland');

    // Sweden is pruned; Anchorland is kept by reference with its whole subtree intact.
    expect(result.nodes).toHaveLength(1);
    expect(result.nodes[0]).toBe(tree[0]);
    expect(result.nodes[0].children.map((c) => c.key)).toEqual(['kat', 'ska']);
    expect(result.nodes[0].children[0].children.map((c) => c.key)).toEqual(['lae']);

    // The match and every descendant that has children are expanded (leaves are not).
    expect([...result.expandKeys].sort()).toEqual(['anc', 'kat']);
  });

  it('keeps only the matching branch and auto-expands the ancestor path', () => {
    const tree = sampleTree();
    const result = filterTree(tree, 'Læsø');

    // Anchorland › Kattegat › Læsø survives; Skagerrak (sibling) and Sweden are pruned.
    expect(result.nodes).toHaveLength(1);
    expect(result.nodes[0].key).toBe('anc');
    expect(result.nodes[0].children.map((c) => c.key)).toEqual(['kat']);
    expect(result.nodes[0].children[0].children.map((c) => c.key)).toEqual(['lae']);

    // Only the ancestor path is expanded; the leaf match itself has no children so is not seeded.
    expect([...result.expandKeys].sort()).toEqual(['anc', 'kat']);
    expect(result.expandKeys.has('lae')).toBe(false);
  });

  it('prunes a branch entirely when nothing matches', () => {
    const result = filterTree(sampleTree(), 'nonexistent');

    expect(result.nodes).toHaveLength(0);
    expect(result.expandKeys.size).toBe(0);
  });

  it('matches case-insensitively', () => {
    const upper = filterTree(sampleTree(), 'KATTEGAT');
    const lower = filterTree(sampleTree(), 'kattegat');

    expect(upper.nodes).toEqual(lower.nodes);
    expect([...upper.expandKeys].sort()).toEqual([...lower.expandKeys].sort());
    expect(upper.nodes[0].children.map((c) => c.key)).toEqual(['kat']);
  });
});

describe('childrenByKey', () => {
  it('maps every visible key to the children it shows, at every depth', () => {
    const tree = sampleTree();
    const index = childrenByKey(filterTree(tree, 'Læsø').nodes);

    expect([...index.keys()].sort()).toEqual(['anc', 'kat', 'lae']);
    // The pruned ancestor maps to its pruned list, not to its full children.
    expect(index.get('anc')?.map((c) => c.key)).toEqual(['kat']);
    expect(index.get('kat')?.map((c) => c.key)).toEqual(['lae']);
    expect(index.get('lae')).toEqual([]);
  });

  it('keeps the children array of an unpruned node by reference', () => {
    const tree = sampleTree();
    const index = childrenByKey(filterTree(tree, '').nodes);

    expect(index.size).toBe(6);
    expect(index.get('anc')).toBe(tree[0].children);
    expect(index.get('kat')).toBe(tree[0].children[0].children);
    expect(index.get('se')).toBe(tree[1].children);
  });
});

describe('setEquals', () => {
  it('returns true for the same reference', () => {
    const set = new Set(['a', 'b']);
    expect(setEquals(set, set)).toBe(true);
  });

  it('returns true for structurally identical sets', () => {
    expect(setEquals(new Set(['a', 'b']), new Set(['b', 'a']))).toBe(true);
  });

  it('returns false when sizes differ', () => {
    expect(setEquals(new Set(['a']), new Set(['a', 'b']))).toBe(false);
  });

  it('returns false for same-size sets with differing members', () => {
    expect(setEquals(new Set(['a', 'b']), new Set(['a', 'c']))).toBe(false);
  });
});
