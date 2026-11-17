import { describe, expect, it } from 'vitest';

import { clampPageToTotal } from './server-page-clamp';

describe('clampPageToTotal', () => {
  it('returns null when the current page is still in range', () => {
    expect(clampPageToTotal(0, 10, 35)).toBeNull();
    expect(clampPageToTotal(3, 10, 35)).toBeNull(); // pages 0..3 for 35 rows
  });

  it('clamps to the new last page when totals shrink below the current page', () => {
    expect(clampPageToTotal(5, 10, 35)).toBe(3);
    expect(clampPageToTotal(1, 10, 10)).toBe(0);
  });

  it('clamps to page 0 when the dataset empties (never strands the user)', () => {
    expect(clampPageToTotal(4, 10, 0)).toBe(0);
  });

  it('a clamped page is in range for the corrective refetch (no loop possible)', () => {
    const clamped = clampPageToTotal(7, 10, 21);
    expect(clamped).toBe(2);
    expect(clampPageToTotal(clamped!, 10, 21)).toBeNull();
  });
});
