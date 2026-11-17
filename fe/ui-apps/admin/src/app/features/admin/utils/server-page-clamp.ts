/**
 * Page-overflow rule for server-paged admin tables: when a response's total
 * puts the current (0-based) page past the new last page — e.g. the dataset
 * shrank between fetches — the facade clamps to the new last page and fires
 * exactly ONE corrective refetch. In-range responses never clamp.
 *
 * Returns the clamped page, or `null` when the current page is already valid
 * (callers refetch only on a non-null result, which makes loops impossible:
 * the corrective response is in range by construction).
 */
export function clampPageToTotal(page: number, pageSize: number, total: number): number | null {
  const lastPage = Math.max(0, Math.ceil(total / pageSize) - 1);
  return page > lastPage ? lastPage : null;
}
