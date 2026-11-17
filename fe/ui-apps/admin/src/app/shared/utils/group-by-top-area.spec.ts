import { describe, expect, it } from 'vitest';

import { topAreaWithinLevels } from './group-by-top-area';

interface TestArea {
  readonly id: number;
  readonly parent?: TestArea;
}

function lineage(...ids: number[]): TestArea {
  // ids root-first; returns the deepest node with its parent chain.
  let node: TestArea | undefined;
  for (const id of ids) {
    node = { id, parent: node };
  }
  return node as TestArea;
}

describe('topAreaWithinLevels', () => {
  it('returns the 2nd-from-root ancestor by default (maxLevels=2)', () => {
    // Anchorland(1) › North Sound(2) › Gull Island(3) → groups under North Sound(2).
    expect(topAreaWithinLevels(lineage(1, 2, 3)).id).toBe(2);
  });

  it('clamps to the root when the lineage is shallower than maxLevels', () => {
    expect(topAreaWithinLevels(lineage(1)).id).toBe(1);
    expect(topAreaWithinLevels(lineage(1, 2)).id).toBe(2);
  });

  it('honours a custom maxLevels', () => {
    expect(topAreaWithinLevels(lineage(1, 2, 3, 4), 1).id).toBe(1);
    expect(topAreaWithinLevels(lineage(1, 2, 3, 4), 3).id).toBe(3);
  });

  it('returns the area itself when it has no parent', () => {
    const solo: TestArea = { id: 9 };
    expect(topAreaWithinLevels(solo)).toBe(solo);
  });
});
