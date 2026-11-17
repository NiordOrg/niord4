import { describe, expect, it } from 'vitest';

import type { PublicationIssueRow } from '../../../services/api/publication-series-api.service';
import {
  ISSUE_SORT_COLUMNS,
  issuePeriodKey,
  nextIssueSort,
  sortIssueRows,
  type IssueSort,
} from './issue-sort';

/**
 * Properties of the order of a series' issue list, over generated archives from a fixed seed (error
 * hunt, phase 2): the comparator is a total order whatever the rows, so the list comes out the same
 * whatever order the answer arrived in; it is a permutation of the rows; numbers inside names and
 * editions count as numbers; the two directions read the primary column in opposite orders; and
 * rows that tie on the primary column break the tie the same way in both directions, newest period
 * first and then the id.
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
  const pick = <T>(items: readonly T[]): T => items[Math.floor(next() * items.length)];
  const shuffle = <T>(items: readonly T[]): T[] => {
    const out = [...items];
    for (let i = out.length - 1; i > 0; i--) {
      const j = Math.floor(next() * (i + 1));
      [out[i], out[j]] = [out[j], out[i]];
    }
    return out;
  };
  return { next, int, pick, shuffle };
}

type Rng = ReturnType<typeof seeded>;

type Row = PublicationIssueRow & { readonly name: string };

const WEEK = 7 * 24 * 3600 * 1000;

/** An archive of real and synthesized rows: real ones carry an id, synthesized ones a sort key. */
function archive(rng: Rng): Row[] {
  const start = Date.UTC(2020, 0, 6);
  const rows: Row[] = [];
  const count = rng.int(1, 40);
  for (let n = 0; n < count; n++) {
    const at = start + rng.int(0, 300) * WEEK + rng.int(0, 3) * 3600 * 1000;
    const synthesized = rng.next() < 0.2;
    const edition =
      rng.next() < 0.6
        ? `${rng.int(1, 12)}. ${rng.pick(['udgave', 'Udgave', 'edition'])}`
        : undefined;
    const name = `${rng.pick(['Uge', 'uge', 'Week', 'Efterretninger', 'Ét'])} ${rng.int(1, 53)}`;
    const row = synthesized
      ? { pseudo: 'MISSING', sortKey: at, intervalFrom: at, intervalTo: at + WEEK, name }
      : {
          publicId: `issue-${n}`,
          edition,
          cutoffStampedAt: rng.next() < 0.7 ? at : undefined,
          effectiveCutoff: rng.next() < 0.5 ? at : undefined,
          intervalFrom: rng.next() < 0.8 ? at - WEEK : undefined,
          intervalTo: rng.next() < 0.8 ? at : undefined,
          name,
        };
    rows.push(row as unknown as Row);
  }
  return rows;
}

/**
 * An archive drawn from small pools, so that many rows tie on each column: a few periods, a few
 * editions (some rows without one) and a few names. The ids are numbered, so the id tie-break can be
 * read as a number.
 */
function tiedArchive(rng: Rng): Row[] {
  const start = Date.UTC(2020, 0, 6);
  const rows: Row[] = [];
  const count = rng.int(2, 30);
  for (let n = 0; n < count; n++) {
    const at = start + rng.int(0, 4) * WEEK;
    const name = `Uge ${rng.int(1, 3)}`;
    const row =
      rng.next() < 0.2
        ? { pseudo: 'MISSING', sortKey: at, intervalFrom: at, intervalTo: at + WEEK, name }
        : {
            publicId: `issue-${n}`,
            edition: rng.next() < 0.7 ? `${rng.int(1, 3)}. udgave` : undefined,
            effectiveCutoff: at,
            intervalFrom: at - WEEK,
            intervalTo: at,
            name,
          };
    rows.push(row as unknown as Row);
  }
  return rows;
}

const nameOf = (row: Row): string => row.name;
const ids = (rows: readonly Row[]): string[] => rows.map((r) => r.publicId ?? `~${r.sortKey}`);

const SORTS: IssueSort[] = ISSUE_SORT_COLUMNS.flatMap((column) =>
  (['asc', 'desc'] as const).map((direction) => ({ column, direction })),
);

describe('issue list order, properties over generated archives', () => {
  it('orders the same rows the same way whatever order they arrive in, and keeps every row', () => {
    const rng = seeded(20261115);
    for (let i = 0; i < 800; i++) {
      const rows = archive(rng);
      for (const sort of SORTS) {
        const once = sortIssueRows(rows, sort, nameOf);
        expect(once).toHaveLength(rows.length);
        expect(new Set(once)).toEqual(new Set(rows));
        expect(ids(sortIssueRows(rng.shuffle(rows), sort, nameOf)), JSON.stringify(sort)).toEqual(
          ids(once),
        );
      }
    }
  });

  it('reads the primary column in order, numbers inside it as numbers, and the other direction in the reverse order', () => {
    const rng = seeded(20261116);
    const number = (text: string | undefined): number => Number(/\d+/.exec(text ?? '')?.[0] ?? -1);
    for (let i = 0; i < 800; i++) {
      const rows = archive(rng);
      for (const sort of SORTS) {
        const sorted = sortIssueRows(rows, sort, nameOf);
        const key = (row: Row): number =>
          sort.column === 'period'
            ? issuePeriodKey(row)
            : sort.column === 'edition'
              ? number(row.edition)
              : number(row.name);
        const sign = sort.direction === 'asc' ? 1 : -1;
        for (let n = 1; n < sorted.length; n++) {
          const a = sorted[n - 1];
          const b = sorted[n];
          // The names start with one of a few words; within a word the number decides.
          const sameWord =
            sort.column !== 'name' ||
            a.name.split(' ')[0].toLowerCase() === b.name.split(' ')[0].toLowerCase();
          const sameEditionWord =
            sort.column !== 'edition' ||
            (a.edition?.split(' ')[1] ?? '').toLowerCase() ===
              (b.edition?.split(' ')[1] ?? '').toLowerCase();
          if (
            sameWord &&
            sameEditionWord &&
            (sort.column !== 'edition' || (a.edition && b.edition))
          ) {
            expect(
              sign * (key(b) - key(a)),
              `${JSON.stringify(sort)}: ${a.name}/${a.edition} then ${b.name}/${b.edition}`,
            ).toBeGreaterThanOrEqual(0);
          }
        }
      }
    }
  });

  it('breaks a tie on the primary column the same way in both directions: newest period first, then the id', () => {
    const rng = seeded(20261117);
    const idNumber = (row: Row): number =>
      row.publicId === undefined ? -1 : Number(row.publicId.slice('issue-'.length));
    const primary = (row: Row, sort: IssueSort): string =>
      sort.column === 'period'
        ? String(issuePeriodKey(row))
        : sort.column === 'edition'
          ? (row.edition ?? '')
          : row.name;
    // Per sort: tied pairs told apart by the period, and tied pairs with the same period too.
    const byPeriod = new Map<string, number>();
    const byId = new Map<string, number>();
    for (let i = 0; i < 300; i++) {
      const rows = tiedArchive(rng);
      for (const sort of SORTS) {
        const label = JSON.stringify(sort);
        const previous = new Map<string, Row>();
        for (const row of sortIssueRows(rows, sort, nameOf)) {
          const key = primary(row, sort);
          const before = previous.get(key);
          previous.set(key, row);
          if (!before) continue;
          const pair = `${label}: ${ids([before])[0]} then ${ids([row])[0]}`;
          const newer = issuePeriodKey(before) - issuePeriodKey(row);
          expect(newer, pair).toBeGreaterThanOrEqual(0);
          if (newer > 0) {
            byPeriod.set(label, (byPeriod.get(label) ?? 0) + 1);
          } else {
            expect(idNumber(row) - idNumber(before), pair).toBeGreaterThanOrEqual(0);
            byId.set(label, (byId.get(label) ?? 0) + 1);
          }
        }
      }
    }
    for (const sort of SORTS) {
      const label = JSON.stringify(sort);
      // A tie on the period column is a tie on the period, so only the id can break it there.
      if (sort.column !== 'period') expect(byPeriod.get(label) ?? 0, label).toBeGreaterThan(100);
      expect(byId.get(label) ?? 0, label).toBeGreaterThan(100);
    }
  });

  it('turns the active column round on a second click, and starts any other its own way', () => {
    for (const current of SORTS) {
      for (const column of ISSUE_SORT_COLUMNS) {
        const next = nextIssueSort(current, column);
        expect(next.column).toBe(column);
        if (column === current.column) {
          expect(next.direction).not.toBe(current.direction);
          expect(nextIssueSort(next, column)).toEqual(current);
        } else {
          expect(nextIssueSort(nextIssueSort(current, column), column).direction).not.toBe(
            next.direction,
          );
        }
      }
    }
  });
});
