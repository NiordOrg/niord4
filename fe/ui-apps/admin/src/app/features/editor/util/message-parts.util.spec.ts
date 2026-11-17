import { describe, expect, it } from 'vitest';

import type { MessagePart } from '../../messages/models/message.model';
import {
  changePartType,
  ensureParts,
  insertPartAfter,
  movePartTo,
  newPart,
  removePartAt,
  reindexParts,
  swapPart,
} from './message-parts.util';

const parts = (...types: string[]): MessagePart[] => types.map((type) => ({ type }) as MessagePart);

describe('message parts util', () => {
  describe('newPart', () => {
    it('is a DETAILS part with an empty geometry collection', () => {
      const part = newPart();
      expect(part.type).toBe('DETAILS');
      expect(part.geometry?.features).toEqual([]);
    });
  });

  describe('reindexParts', () => {
    it('renumbers each part to its position', () => {
      const result = reindexParts([
        { type: 'A', indexNo: 5 },
        { type: 'B', indexNo: 9 },
      ]);
      expect(result.map((p) => p.indexNo)).toEqual([0, 1]);
    });
  });

  describe('ensureParts', () => {
    it('seeds a default part when empty or absent', () => {
      expect(ensureParts(undefined)).toHaveLength(1);
      expect(ensureParts([])).toHaveLength(1);
      expect(ensureParts(undefined)[0].type).toBe('DETAILS');
    });

    it('keeps a non-empty list as-is', () => {
      const existing = parts('A');
      expect(ensureParts(existing)).toBe(existing);
    });
  });

  describe('insertPartAfter', () => {
    it('inserts after the index and re-indexes', () => {
      const result = insertPartAfter(parts('A', 'B'), 0);
      expect(result.map((p) => p.type)).toEqual(['A', 'DETAILS', 'B']);
      expect(result.map((p) => p.indexNo)).toEqual([0, 1, 2]);
    });
  });

  describe('removePartAt', () => {
    it('removes and re-indexes', () => {
      expect(removePartAt(parts('A', 'B', 'C'), 1)?.map((p) => p.type)).toEqual(['A', 'C']);
    });

    it('re-adds a default when the list empties (≥1 invariant)', () => {
      const result = removePartAt(parts('A'), 0);
      expect(result).toHaveLength(1);
      expect(result?.[0].type).toBe('DETAILS');
    });

    it('returns null when out of bounds', () => {
      expect(removePartAt(parts('A'), 5)).toBeNull();
    });
  });

  describe('swapPart', () => {
    it('swaps down and re-indexes', () => {
      const result = swapPart(parts('A', 'B'), 0, 'down');
      expect(result?.map((p) => p.type)).toEqual(['B', 'A']);
      expect(result?.map((p) => p.indexNo)).toEqual([0, 1]);
    });

    it('returns null at a boundary', () => {
      expect(swapPart(parts('A', 'B'), 0, 'up')).toBeNull();
      expect(swapPart(parts('A', 'B'), 1, 'down')).toBeNull();
    });
  });

  describe('movePartTo', () => {
    it('splice-moves a part forward and re-indexes', () => {
      const result = movePartTo(parts('A', 'B', 'C'), 0, 2);
      expect(result?.map((p) => p.type)).toEqual(['B', 'C', 'A']);
      expect(result?.map((p) => p.indexNo)).toEqual([0, 1, 2]);
    });

    it('splice-moves a part backward and re-indexes', () => {
      const result = movePartTo(parts('A', 'B', 'C'), 2, 0);
      expect(result?.map((p) => p.type)).toEqual(['C', 'A', 'B']);
      expect(result?.map((p) => p.indexNo)).toEqual([0, 1, 2]);
    });

    it('returns null when the indices are equal', () => {
      expect(movePartTo(parts('A', 'B'), 1, 1)).toBeNull();
    });

    it('returns null when either index is out of bounds', () => {
      expect(movePartTo(parts('A', 'B'), -1, 1)).toBeNull();
      expect(movePartTo(parts('A', 'B'), 0, 5)).toBeNull();
    });
  });

  describe('changePartType', () => {
    it('changes the type at the index', () => {
      expect(changePartType(parts('A', 'B'), 1, 'X')?.map((p) => p.type)).toEqual(['A', 'X']);
    });

    it('returns null when out of bounds', () => {
      expect(changePartType(parts('A'), 3, 'X')).toBeNull();
    });
  });
});
