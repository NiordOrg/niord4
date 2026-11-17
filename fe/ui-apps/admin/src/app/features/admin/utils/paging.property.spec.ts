import { signal } from '@angular/core';
import { describe, expect, it } from 'vitest';

import { createClientPager } from './client-pager';
import { clampPageToTotal } from './server-page-clamp';

/**
 * Properties of the admin tables' paging arithmetic, over generated lists and page moves from a
 * fixed seed: the client pager always shows a page in range, its pages cover
 * the list exactly once, and a shrinking list never strands the reader on an empty page; the server
 * clamp corrects an overflowing page once and never loops.
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
  return { next, int };
}

describe('paging, properties over generated lists', () => {
  it('pages a list so the pages cover every row once, in order', () => {
    const rng = seeded(20261115);
    for (let i = 0; i < 500; i++) {
      const rows = rng.int(1, 60);
      const items = signal(Array.from({ length: rng.int(0, 400) }, (_, n) => n));
      const pager = createClientPager(items, rows);
      const seen: number[] = [];
      const pages = Math.max(1, Math.ceil(items().length / rows));
      for (let page = 0; page < pages; page++) {
        pager.setFirst(page * rows);
        expect(pager.first()).toBe(page * rows);
        expect(pager.paged().length).toBeLessThanOrEqual(rows);
        seen.push(...pager.paged());
      }
      expect(seen).toEqual(items());
      expect(pager.total()).toBe(items().length);
    }
  });

  it('always shows a page in range, with rows on it whenever the list has rows, whatever the moves', () => {
    const rng = seeded(20261116);
    for (let i = 0; i < 500; i++) {
      const rows = rng.int(1, 50);
      const items = signal(Array.from({ length: rng.int(0, 300) }, (_, n) => n));
      const pager = createClientPager(items, rows);
      for (let move = 0; move < 30; move++) {
        const what = rng.int(0, 3);
        if (what === 0) pager.setFirst(rng.int(0, 400));
        else if (what === 1) pager.reset();
        else if (what === 2) items.set(items().slice(0, rng.int(0, items().length)));
        else items.set(Array.from({ length: rng.int(0, 300) }, (_, n) => n));

        const total = items().length;
        const lastPage = Math.max(0, Math.ceil(total / rows) - 1);
        expect(pager.page()).toBeGreaterThanOrEqual(0);
        expect(pager.page()).toBeLessThanOrEqual(lastPage);
        expect(pager.first()).toBe(pager.page() * rows);
        if (total > 0) expect(pager.paged().length).toBeGreaterThan(0);
        expect(pager.paged()).toEqual(items().slice(pager.first(), pager.first() + rows));
      }
    }
  });

  it('clamps an overflowing server page to the last page once, and leaves a page in range alone', () => {
    const rng = seeded(20261117);
    for (let i = 0; i < 20000; i++) {
      const size = rng.int(1, 100);
      const total = rng.int(0, 5000);
      const page = rng.int(0, 120);
      const lastPage = Math.max(0, Math.ceil(total / size) - 1);
      const clamped = clampPageToTotal(page, size, total);
      if (page <= lastPage) {
        expect(clamped).toBeNull();
      } else {
        expect(clamped).toBe(lastPage);
        // The corrective fetch's answer is in range: no second correction, no loop.
        expect(clampPageToTotal(clamped ?? 0, size, total)).toBeNull();
        // The page it lands on has rows unless there are none at all.
        if (total > 0) expect((clamped ?? 0) * size).toBeLessThan(total);
      }
    }
  });
});
