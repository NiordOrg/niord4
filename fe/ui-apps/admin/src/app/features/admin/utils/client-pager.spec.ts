import { signal } from '@angular/core';
import { describe, expect, it } from 'vitest';

import { createClientPager } from './client-pager';

const items = (n: number): readonly number[] => Array.from({ length: n }, (_, i) => i);

describe('createClientPager', () => {
  it('slices the current page and reports totals', () => {
    const source = signal(items(45));
    const pager = createClientPager(source, 20);
    expect(pager.total()).toBe(45);
    expect(pager.paged().length).toBe(20);
    pager.setFirst(40);
    expect(pager.paged()).toEqual([40, 41, 42, 43, 44]);
  });

  it('reset() returns to the first page (filter-change rule)', () => {
    const source = signal(items(45));
    const pager = createClientPager(source, 20);
    pager.setFirst(20);
    pager.reset();
    expect(pager.first()).toBe(0);
    expect(pager.paged()[0]).toBe(0);
  });

  it('clamps into range when the dataset shrinks under the current page', () => {
    const source = signal(items(45));
    const pager = createClientPager(source, 20);
    pager.setFirst(40); // page 2
    source.set(items(15)); // only page 0 remains
    expect(pager.page()).toBe(0);
    expect(pager.paged().length).toBe(15);
  });

  it('handles an empty dataset without negative paging', () => {
    const source = signal(items(0));
    const pager = createClientPager(source, 20);
    expect(pager.first()).toBe(0);
    expect(pager.paged()).toEqual([]);
  });
});
