import type { PublicationIssueRow } from '../../../services/api/publication-series-api.service';

/**
 * Client-side ordering for a series' issue list.
 *
 * Client-side because the endpoint returns the whole archive in one response —
 * 498 rows for the busiest series — and because the list is not purely the
 * server's: it carries synthesized MISSING and UPCOMING periods that exist only
 * in the answer, so a re-fetch per sort would be a round trip to reorder rows
 * already in hand.
 *
 * Pure on purpose: no injector, no signals. The comparator is the part worth
 * pinning with a spec, and it can only be pinned while it is a function of its
 * inputs.
 */

/**
 * The three orders the archive can be read in.
 *
 * There is no "published" column. The release moment is not a second date to
 * scan: publishing stamps the cut-off and then does the work, so it is a muted
 * suffix on the cut-off where the two differ, and a column ordering the archive
 * by a value most rows print nowhere is one nobody can check against the table.
 */
export type IssueSortColumn = 'period' | 'edition' | 'name';
export type IssueSortDirection = 'asc' | 'desc';

export interface IssueSort {
  readonly column: IssueSortColumn;
  readonly direction: IssueSortDirection;
}

export const ISSUE_SORT_COLUMNS: readonly IssueSortColumn[] = ['period', 'edition', 'name'];

/**
 * Newest period first — what the list already showed before it was sortable.
 *
 * The question asked at the top of an issue list is "what went out most
 * recently", and the archive is the tail of that answer rather than the head of
 * it. Changing the default would change the answer the page opens on.
 */
export const DEFAULT_ISSUE_SORT: IssueSort = { column: 'period', direction: 'desc' };

/**
 * Which way a column reads the first time it is picked.
 *
 * A date wants its newest row first; a name wants A→Z. Starting every column
 * ascending makes picking "published" land on the oldest issue in the archive,
 * which is never what the click meant.
 */
const FIRST_DIRECTION: Readonly<Record<IssueSortColumn, IssueSortDirection>> = {
  period: 'desc',
  edition: 'asc',
  name: 'asc',
};

/** Clicking the active column reverses it; clicking another starts it its own way. */
export function nextIssueSort(current: IssueSort, column: IssueSortColumn): IssueSort {
  return current.column === column
    ? { column, direction: current.direction === 'asc' ? 'desc' : 'asc' }
    : { column, direction: FIRST_DIRECTION[column] };
}

/** The `aria-sort` value for one column header under the given sort. */
export function issueAriaSort(
  sort: IssueSort,
  column: IssueSortColumn,
): 'ascending' | 'descending' | 'none' {
  if (sort.column !== column) {
    return 'none';
  }
  return sort.direction === 'asc' ? 'ascending' : 'descending';
}

/** The arrow to draw on the active header, or `undefined` on the inactive ones. */
export function issueSortIcon(sort: IssueSort, column: IssueSortColumn): string | undefined {
  if (sort.column !== column) {
    return undefined;
  }
  return sort.direction === 'asc' ? 'lucideArrowUp' : 'lucideArrowDown';
}

/**
 * The one key space both kinds of row share.
 *
 * `sortKey` is the server's own merge key and is present on synthesized rows,
 * which have no cut-off of their own — reading the cut-off first would collapse
 * every MISSING period onto the same value and clump them at one end of the
 * list instead of leaving them between the issues they sit between.
 */
export function issuePeriodKey(row: PublicationIssueRow): number {
  return (
    row.sortKey ??
    row.effectiveCutoff ??
    row.cutoffStampedAt ??
    row.intervalTo ??
    row.intervalFrom ??
    0
  );
}

/**
 * Numeric-aware and case-insensitive, so edition 9 precedes edition 10.
 *
 * Issue names and editions are short human strings ("Uge 9", "2. udgave"), and
 * plain code-point order puts "10" before "9" in every one of them.
 */
const COLLATOR = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });

/**
 * Sort a mixed list of real and synthesized rows.
 *
 * `nameOf` is passed in rather than derived here because naming a row is a
 * language question — a synthesized period is named by what the series WOULD
 * call it — and that belongs to the screen, not to a comparator.
 *
 * Rows with no value for the active column (no edition, say) keep the period
 * order among themselves, which is also what keeps the synthesized rows next to
 * the issues they sit between.
 */
export function sortIssueRows<T extends PublicationIssueRow>(
  rows: readonly T[],
  sort: IssueSort,
  nameOf: (row: T) => string,
): readonly T[] {
  const sign = sort.direction === 'asc' ? 1 : -1;
  return [...rows].sort((a, b) => {
    const primary = comparePrimary(a, b, sort.column, nameOf);
    if (primary !== 0) {
      return primary * sign;
    }
    // Ties break the same way whichever direction is active: newest period
    // first, then the id. A tie-break that followed the direction would reorder
    // rows that did not change relative to each other.
    const period = issuePeriodKey(b) - issuePeriodKey(a);
    if (period !== 0) {
      return period;
    }
    return COLLATOR.compare(a.publicId ?? '', b.publicId ?? '');
  });
}

function comparePrimary<T extends PublicationIssueRow>(
  a: T,
  b: T,
  column: IssueSortColumn,
  nameOf: (row: T) => string,
): number {
  switch (column) {
    case 'period':
      return issuePeriodKey(a) - issuePeriodKey(b);
    case 'edition':
      return COLLATOR.compare(a.edition ?? '', b.edition ?? '');
    case 'name':
      return COLLATOR.compare(nameOf(a), nameOf(b));
  }
}
