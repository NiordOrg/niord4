import { emptyFeatureCollection, type MessagePart } from '../../messages/models/message.model';

const DEFAULT_PART_TYPE = 'DETAILS';

/** A blank message part with an empty geometry collection. */
export function newPart(): MessagePart {
  const geometry = emptyFeatureCollection();
  return { type: DEFAULT_PART_TYPE, indexNo: 0, eventDates: [], descs: [], geometry };
}

/** Renumber each part's `indexNo` to its array position (the server's ordering field). */
export function reindexParts(parts: readonly MessagePart[]): MessagePart[] {
  return parts.map((part, index) => ({ ...part, indexNo: index }));
}

/**
 * Parts guaranteeing the ≥1 invariant — seed a default part when the list is empty/absent.
 *
 * The seeded part defaults to `hideSubject: true`, because it is by definition the message's ONLY
 * part: its subject would render directly under a title composed from that same subject, so the
 * subject line reads as a duplicate. A part ADDED later (`insertPartAfter` → `newPart()`) gets the
 * flag unset instead — with two or more parts the subject is what tells them apart.
 *
 * This is a DEFAULT at creation, never a correction: nothing re-derives the flag afterwards, so a
 * user who unticks it keeps that choice through every later edit and save. The template-execution
 * path never reaches here — the backend already stamps the flag on the parts it renders.
 */
export function ensureParts(parts: MessagePart[] | undefined): MessagePart[] {
  return parts && parts.length > 0 ? parts : [{ ...newPart(), hideSubject: true }];
}

/** Insert a fresh part after `index`, re-indexed. */
export function insertPartAfter(parts: readonly MessagePart[], index: number): MessagePart[] {
  const next = [...parts];
  next.splice(index + 1, 0, newPart());
  return reindexParts(next);
}

/**
 * Remove the part at `index` (re-adding a default if it empties, the ≥1 invariant), re-indexed.
 * Returns `null` when `index` is out of bounds (a no-op the caller should not commit).
 */
export function removePartAt(parts: readonly MessagePart[], index: number): MessagePart[] | null {
  if (index < 0 || index >= parts.length) {
    return null;
  }
  const next = [...parts];
  next.splice(index, 1);
  if (next.length === 0) {
    next.push(newPart());
  }
  return reindexParts(next);
}

/**
 * Swap the part at `index` with its up/down neighbour, re-indexed. Returns `null` when the target
 * neighbour is out of bounds (a no-op the caller should not commit).
 */
export function swapPart(
  parts: readonly MessagePart[],
  index: number,
  direction: 'up' | 'down',
): MessagePart[] | null {
  const toIndex = direction === 'up' ? index - 1 : index + 1;
  if (toIndex < 0 || toIndex >= parts.length) {
    return null;
  }
  const next = [...parts];
  [next[index], next[toIndex]] = [next[toIndex], next[index]];
  return reindexParts(next);
}

/**
 * Move the part at `from` to position `to` (splice-move for drag-reorder), re-indexed. Returns
 * `null` when either index is out of bounds or `from === to` (a no-op the caller should not commit).
 */
export function movePartTo(
  parts: readonly MessagePart[],
  from: number,
  to: number,
): MessagePart[] | null {
  if (from < 0 || from >= parts.length || to < 0 || to >= parts.length || from === to) {
    return null;
  }
  const next = [...parts];
  const [moved] = next.splice(from, 1);
  next.splice(to, 0, moved);
  return reindexParts(next);
}

/** Set the part type at `index`, re-indexed. Returns `null` when `index` is out of bounds. */
export function changePartType(
  parts: readonly MessagePart[],
  index: number,
  type: string,
): MessagePart[] | null {
  if (index < 0 || index >= parts.length) {
    return null;
  }
  return reindexParts(parts.map((part, i) => (i === index ? { ...part, type } : part)));
}
