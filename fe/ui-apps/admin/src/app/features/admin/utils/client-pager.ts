import { Signal, computed, signal } from '@angular/core';

/**
 * Client-slice paging for admin sections whose endpoint returns the full
 * dataset in one response (charts, sources, dictionaries, …): the rows are
 * filtered and paged entirely on the client.
 *
 * Page state is 0-based and exposed as `first`/`rows` to bind directly to the
 * paginator. Every filter change must reset to the first page (deliberately,
 * so a filter never lands on a now-empty last page) — callers do that by
 * calling `reset()` whenever their filter signal changes.
 */
export interface ClientPager<T> {
  readonly page: Signal<number>;
  readonly first: Signal<number>;
  readonly rows: number;
  readonly total: Signal<number>;
  readonly paged: Signal<readonly T[]>;
  setFirst(first: number): void;
  reset(): void;
}

export function createClientPager<T>(
  items: Signal<readonly T[]>,
  pageSize: number,
): ClientPager<T> {
  const page = signal(0);
  const total = computed(() => items().length);
  // A shrinking dataset (delete on the last page) clamps into range so the
  // user is never stranded on an empty page.
  const effectivePage = computed(() => {
    const lastPage = Math.max(0, Math.ceil(total() / pageSize) - 1);
    return Math.min(page(), lastPage);
  });
  return {
    page: effectivePage,
    first: computed(() => effectivePage() * pageSize),
    rows: pageSize,
    total,
    paged: computed(() => {
      const start = effectivePage() * pageSize;
      return items().slice(start, start + pageSize);
    }),
    setFirst(first: number): void {
      page.set(Math.floor(first / pageSize));
    },
    reset(): void {
      page.set(0);
    },
  };
}
