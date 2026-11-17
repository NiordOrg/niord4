import { describe, expect, it } from 'vitest';

import type { PublicationIssueRow } from '../../../services/api/publication-series-api.service';
import {
  DEFAULT_ISSUE_SORT,
  issueAriaSort,
  issuePeriodKey,
  issueSortIcon,
  nextIssueSort,
  sortIssueRows,
  type IssueSort,
} from './issue-sort';

/**
 * The order of a series' issue list.
 *
 * Worth pinning because it is the one part of the screen that reorders data the
 * server sent: a comparator that quietly drops the synthesized periods to the
 * bottom, or that puts edition 10 before edition 9, produces a list that looks
 * plausible and is wrong — and nobody rereads an archive of 498 rows to check.
 */

const DAY = 86_400_000;

function row(over: Partial<PublicationIssueRow>): PublicationIssueRow {
  return { descs: [], ...over } as PublicationIssueRow;
}

/** The name a screen would render; the comparator never derives one itself. */
function nameOf(r: PublicationIssueRow): string {
  return r.descs?.[0]?.name ?? '';
}

function ids(rows: readonly PublicationIssueRow[]): (string | undefined)[] {
  return rows.map((r) => r.publicId ?? r.pseudo);
}

describe('issue sort — the default', () => {
  /** Cut-off descending: the list opens on what went out last, as it always did. */
  it('starts on the period, newest first', () => {
    expect(DEFAULT_ISSUE_SORT).toEqual({ column: 'period', direction: 'desc' });
  });

  it('orders newest period first', () => {
    const rows = [
      row({ publicId: 'old', sortKey: 1 * DAY }),
      row({ publicId: 'new', sortKey: 3 * DAY }),
      row({ publicId: 'mid', sortKey: 2 * DAY }),
    ];

    expect(ids(sortIssueRows(rows, DEFAULT_ISSUE_SORT, nameOf))).toEqual(['new', 'mid', 'old']);
  });

  it('reverses on ascending', () => {
    const rows = [
      row({ publicId: 'old', sortKey: 1 * DAY }),
      row({ publicId: 'new', sortKey: 3 * DAY }),
    ];

    const sorted = sortIssueRows(rows, { column: 'period', direction: 'asc' }, nameOf);
    expect(ids(sorted)).toEqual(['old', 'new']);
  });

  /** The input is never mutated: the facade's list is shared with the pager. */
  it('leaves the source array alone', () => {
    const rows = [row({ publicId: 'a', sortKey: 1 }), row({ publicId: 'b', sortKey: 2 })];
    sortIssueRows(rows, DEFAULT_ISSUE_SORT, nameOf);
    expect(ids(rows)).toEqual(['a', 'b']);
  });
});

describe('issue sort — the keys', () => {
  /**
   * `sortKey` first, because it is the only key both kinds of row carry.
   *
   * A synthesized period has no cut-off of its own, so reading the cut-off first
   * would collapse every MISSING row onto the same value and clump them at one
   * end instead of leaving them between the issues they sit between.
   */
  it('reads the period from the server merge key before anything derived', () => {
    expect(issuePeriodKey(row({ sortKey: 5, effectiveCutoff: 9 }))).toBe(5);
    expect(issuePeriodKey(row({ effectiveCutoff: 9, cutoffStampedAt: 7 }))).toBe(9);
    expect(issuePeriodKey(row({ cutoffStampedAt: 7, intervalTo: 6 }))).toBe(7);
    expect(issuePeriodKey(row({ intervalTo: 6 }))).toBe(6);
    expect(issuePeriodKey(row({ intervalFrom: 4 }))).toBe(4);
    expect(issuePeriodKey(row({}))).toBe(0);
  });
});

describe('issue sort — the synthesized rows', () => {
  /**
   * A MISSING period is never dropped by a sort it has no value for.
   *
   * It has no name and no edition, so on a text column it collates with the
   * other value-less rows rather than vanishing — an archive review that lost
   * the gaps whenever somebody reordered it would lose exactly the rows the
   * screen exists to surface.
   */
  it('keeps a gap row in the list on a column it has no value for', () => {
    const rows = [
      row({ publicId: 'w3', edition: '3', sortKey: 3 * DAY }),
      row({ pseudo: 'MISSING', sortKey: 2 * DAY }),
      row({ publicId: 'w1', edition: '1', sortKey: 1 * DAY }),
    ];

    const sorted = sortIssueRows(rows, { column: 'edition', direction: 'asc' }, nameOf);
    expect(ids(sorted)).toEqual(['MISSING', 'w1', 'w3']);
  });

  /** On the period column it keeps its place between the issues it sits between. */
  it('interleaves gap rows on the period column', () => {
    const rows = [
      row({ pseudo: 'UPCOMING', sortKey: 4 * DAY }),
      row({ publicId: 'w1', sortKey: 1 * DAY }),
      row({ publicId: 'w3', sortKey: 3 * DAY }),
    ];

    expect(ids(sortIssueRows(rows, DEFAULT_ISSUE_SORT, nameOf))).toEqual(['UPCOMING', 'w3', 'w1']);
  });
});

describe('issue sort — text columns', () => {
  /** Editions are numbered, and code-point order puts "10" before "9". */
  it('compares editions numerically', () => {
    const rows = [
      row({ publicId: 'b', edition: '10', sortKey: 2 }),
      row({ publicId: 'a', edition: '9', sortKey: 1 }),
    ];

    const sorted = sortIssueRows(rows, { column: 'edition', direction: 'asc' }, nameOf);
    expect(ids(sorted)).toEqual(['a', 'b']);
  });

  /** Rows with no edition keep the period order among themselves. */
  it('orders edition-less rows by their period', () => {
    const rows = [
      row({ publicId: 'old', sortKey: 1 * DAY }),
      row({ publicId: 'new', sortKey: 2 * DAY }),
    ];

    const sorted = sortIssueRows(rows, { column: 'edition', direction: 'asc' }, nameOf);
    expect(ids(sorted)).toEqual(['new', 'old']);
  });

  /**
   * The tie-break does not follow the direction: turning the column round reverses the editions,
   * not the rows that share one.
   */
  it('orders edition-less rows newest period first in the descending direction too', () => {
    const rows = [
      row({ publicId: 'old', sortKey: 1 * DAY }),
      row({ publicId: 'new', sortKey: 2 * DAY }),
    ];

    const sorted = sortIssueRows(rows, { column: 'edition', direction: 'desc' }, nameOf);
    expect(ids(sorted)).toEqual(['new', 'old']);
  });

  /** Rows that share the period as well fall back on the id, in both directions. */
  it('orders rows tied on the period by their id in both directions', () => {
    const rows = [
      row({ publicId: 'w10', sortKey: 1 * DAY }),
      row({ publicId: 'w9', sortKey: 1 * DAY }),
    ];

    for (const direction of ['asc', 'desc'] as const) {
      const sorted = sortIssueRows(rows, { column: 'period', direction }, nameOf);
      expect(ids(sorted), direction).toEqual(['w9', 'w10']);
    }
  });

  /** The name comes from the caller, because naming a row is a language question. */
  it('sorts by the name the screen would render', () => {
    const rows = [
      row({ publicId: 'b', descs: [{ lang: 'da', name: 'Uge 10' }], sortKey: 2 }),
      row({ publicId: 'a', descs: [{ lang: 'da', name: 'Uge 9' }], sortKey: 1 }),
    ];

    const sorted = sortIssueRows(rows, { column: 'name', direction: 'asc' }, nameOf);
    expect(ids(sorted)).toEqual(['a', 'b']);
  });
});

describe('issue sort — the header state', () => {
  /** Clicking the active column reverses it. */
  it('toggles the direction of the active column', () => {
    expect(nextIssueSort({ column: 'period', direction: 'desc' }, 'period')).toEqual({
      column: 'period',
      direction: 'asc',
    });
    expect(nextIssueSort({ column: 'period', direction: 'asc' }, 'period')).toEqual({
      column: 'period',
      direction: 'desc',
    });
  });

  /**
   * A new column starts the way that column reads.
   *
   * Dates newest-first, text A→Z. Starting every column ascending makes picking
   * the cut-off land on the oldest issue in the archive, which is never what the
   * click meant.
   */
  it('starts a new column in its own direction', () => {
    const from: IssueSort = { column: 'name', direction: 'asc' };
    expect(nextIssueSort(from, 'period').direction).toBe('desc');
    expect(nextIssueSort(from, 'edition').direction).toBe('asc');
    expect(nextIssueSort({ column: 'period', direction: 'desc' }, 'name').direction).toBe('asc');
  });

  /** aria-sort names the state of ONE header; every other header is 'none'. */
  it('reports aria-sort for the active column only', () => {
    const sort: IssueSort = { column: 'name', direction: 'asc' };
    expect(issueAriaSort(sort, 'name')).toBe('ascending');
    expect(issueAriaSort({ ...sort, direction: 'desc' }, 'name')).toBe('descending');
    expect(issueAriaSort(sort, 'period')).toBe('none');
    expect(issueAriaSort(sort, 'edition')).toBe('none');
  });

  /** The arrow is drawn on the active header only — it repeats what aria-sort says. */
  it('draws an arrow on the active column only', () => {
    const sort: IssueSort = { column: 'name', direction: 'asc' };
    expect(issueSortIcon(sort, 'name')).toBe('lucideArrowUp');
    expect(issueSortIcon({ ...sort, direction: 'desc' }, 'name')).toBe('lucideArrowDown');
    expect(issueSortIcon(sort, 'period')).toBeUndefined();
  });
});
