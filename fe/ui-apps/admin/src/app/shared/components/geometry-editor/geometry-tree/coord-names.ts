import type { Feature as OlFeature } from 'ol';

/** Matches a per-coordinate name key: `name:<coordIndex>:<lang>`. */
const COORD_NAME_KEY = /^name:(\d+):([a-zA-Z_]+)$/;

export interface CoordNameUpdate {
  readonly type: 'offset' | 'remove';
  /** Inclusive lower bound on the affected coordinate index. */
  readonly fromIndex: number;
  /** Exclusive upper bound; omitted means open-ended. */
  readonly toIndex?: number;
  /** Index shift, for `offset`. */
  readonly offset?: number;
}

interface ParsedCoordName {
  readonly key: string;
  readonly coordIndex: number;
  readonly lang: string;
  readonly value: unknown;
}

/** Reads the per-coordinate name keys (`name:<idx>:<lang>`) currently on a feature. */
export function readCoordNames(feature: OlFeature): ParsedCoordName[] {
  const names: ParsedCoordName[] = [];
  for (const key of feature.getKeys()) {
    const match = COORD_NAME_KEY.exec(key);
    if (match) {
      names.push({ key, coordIndex: Number(match[1]), lang: match[2], value: feature.get(key) });
    }
  }
  return names;
}

/**
 * Re-indexes the per-coordinate name keys after a vertex insert/delete. Each update either
 * removes names in `[fromIndex, toIndex)` or
 * offsets names at/after `fromIndex` (within `toIndex`) by `offset`, so a name like
 * `name:3:en` follows its coordinate as later coordinates shift. The caller supplies
 * disjoint ranges (a delete is a `remove` span followed by an `offset` of the tail).
 */
export function applyCoordNameUpdates(
  feature: OlFeature,
  updates: readonly CoordNameUpdate[],
): void {
  const names = readCoordNames(feature);
  const reinserted: { key: string; value: unknown }[] = [];

  for (const name of names) {
    for (const update of updates) {
      const inRange =
        name.coordIndex >= update.fromIndex &&
        (update.toIndex === undefined || name.coordIndex < update.toIndex);
      if (!inRange) continue;

      if (update.type === 'offset') {
        feature.unset(name.key);
        const newIndex = name.coordIndex + (update.offset ?? 0);
        reinserted.push({ key: `name:${newIndex}:${name.lang}`, value: name.value });
      } else {
        feature.unset(name.key);
      }
    }
  }

  for (const entry of reinserted) {
    feature.set(entry.key, entry.value);
  }
}
