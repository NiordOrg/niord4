/** An area with an optional parent reference — the lineage the grouping walks. */
export interface AreaLineageNode {
  readonly parent?: AreaLineageNode;
}

/**
 * Returns the top ancestor of `area` within `maxLevels` of the root — the area
 * that heads a group in the area-sorted lists (the messages list and the firing
 * schedule). Walks the `parent` chain to the root, then clamps to the Nth level
 * from the top. 2 levels by default — e.g. a deep "Anchorland › North Sound › Gull Island"
 * groups under "North Sound".
 */
export function topAreaWithinLevels<A extends AreaLineageNode>(area: A, maxLevels = 2): A {
  const chain: A[] = [];
  let cursor: A | undefined = area;
  while (cursor) {
    chain.unshift(cursor);
    cursor = cursor.parent as A | undefined;
  }
  return chain[Math.min(chain.length - 1, maxLevels - 1)];
}
