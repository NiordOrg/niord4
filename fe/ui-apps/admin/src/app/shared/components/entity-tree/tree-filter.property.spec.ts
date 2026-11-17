import { describe, expect, it } from 'vitest';

import type { HierarchicalEntity } from './hierarchical-entity.model';
import type { EntityTreeNode } from './to-tree-nodes';
import { filterTree, seedExpandKeys, setEquals } from './tree-filter';

/**
 * Properties of the area, category and group trees' search, over generated trees and queries from a
 * fixed seed: every node whose label holds the query, in any case, is shown;
 * every node shown either matches, lies under a match, or leads to one; the nodes leading to a match
 * are expanded; nothing outside the tree is expanded; and an empty query changes nothing.
 * (The tree component's own caching of pruned rows is the backlog's "The entity tree's search prunes
 * only its top level"; it is not in this function.)
 */

/** A small seeded generator (mulberry32): the same seed gives the same inputs on every run. */
function seeded(seed: number) {
  let state = seed >>> 0;
  const next = (): number => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
  const int = (min: number, max: number): number => min + Math.floor(next() * (max - min + 1));
  const pick = <T>(items: readonly T[]): T => items[Math.floor(next() * items.length)];
  return { next, int, pick };
}

type Rng = ReturnType<typeof seeded>;
type Node = EntityTreeNode<HierarchicalEntity>;

const WORDS = [
  'Danmark',
  'Bornholm',
  'Øresund',
  'Kattegat',
  'Skagerrak',
  'Storebælt',
  'Lillebælt',
  'Østersøen',
  'Grønland',
  'Fjord',
  'Havn',
];

let counter = 0;

function tree(rng: Rng, depth = 0): Node[] {
  return Array.from({ length: rng.int(depth === 0 ? 1 : 0, depth > 3 ? 0 : 4) }, () => {
    const id = counter++;
    return {
      key: `k${id}`,
      label: `${rng.pick(WORDS)} ${id}`,
      data: { id },
      expanded: rng.next() < 0.3,
      styleClass: '',
      children: tree(rng, depth + 1),
    };
  });
}

function all(nodes: readonly Node[], out: Node[] = []): Node[] {
  for (const node of nodes) {
    out.push(node);
    all(node.children, out);
  }
  return out;
}

describe('filterTree, properties over generated trees', () => {
  it('shows every match, only what matches or leads to or lies under a match, and expands the way to each', () => {
    const rng = seeded(20261115);
    for (let i = 0; i < 2000; i++) {
      const nodes = tree(rng);
      const word = rng.pick(WORDS);
      const query = rng.pick([
        word,
        word.toUpperCase(),
        ` ${word.slice(1, 4).toLowerCase()} `,
        word.slice(0, 2),
      ]);
      const q = query.trim().toLowerCase();
      const matches = (n: Node): boolean => n.label.toLowerCase().includes(q);
      const { nodes: shown, expandKeys } = filterTree(nodes, query);

      const shownKeys = new Set(all(shown).map((n) => n.key));
      for (const n of all(nodes)) if (matches(n)) expect(shownKeys.has(n.key), n.label).toBe(true);

      const check = (list: readonly Node[], underMatch: boolean): boolean => {
        let leadsToMatch = false;
        for (const n of list) {
          const below = check(n.children, underMatch || matches(n));
          const ok = underMatch || matches(n) || below;
          expect(ok, n.label).toBe(true);
          if (below && !matches(n) && !underMatch)
            expect(expandKeys.has(n.key), n.label).toBe(true);
          leadsToMatch ||= matches(n) || below;
        }
        return leadsToMatch;
      };
      check(shown, false);
      for (const key of expandKeys) expect(shownKeys.has(key), key).toBe(true);
    }
  });

  it('changes nothing for an empty query, and seeds exactly the nodes marked expanded', () => {
    const rng = seeded(20261116);
    for (let i = 0; i < 1000; i++) {
      const nodes = tree(rng);
      const { nodes: shown, expandKeys } = filterTree(nodes, rng.pick(['', ' ', '\t ']));
      expect(shown).toEqual(nodes);
      expect(expandKeys.size).toBe(0);
      const seeded = seedExpandKeys(nodes);
      expect(
        setEquals(
          seeded,
          new Set(
            all(nodes)
              .filter((n) => n.expanded)
              .map((n) => n.key),
          ),
        ),
      ).toBe(true);
    }
  });
});
