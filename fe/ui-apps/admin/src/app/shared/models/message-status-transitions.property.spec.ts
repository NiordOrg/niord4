import { describe, expect, it } from 'vitest';

import { VALID_STATUS_TRANSITIONS, validTransitionsFor } from './message-status-transitions';
import { MESSAGE_STATUSES, type MessageStatus } from './message-status.model';

/**
 * Properties of the table of status changes an editor may make by hand, over
 * every status and every path through the table: the table names only known statuses and no status
 * changes to itself; the terminal statuses lead nowhere; every path that starts as a draft ends in
 * a terminal status; nothing published goes back to draft or verified, so a published warning can
 * only be cancelled; and nothing reaches publication without being verified first.
 */

const TERMINAL: readonly MessageStatus[] = ['CANCELLED', 'EXPIRED', 'DELETED'];

/** Every path from `from` through the table, each status at most once. */
function paths(from: MessageStatus, seen: readonly MessageStatus[] = []): MessageStatus[][] {
  const route = [...seen, from];
  const onward = validTransitionsFor(from).filter((to) => !route.includes(to));
  return onward.length === 0 ? [route] : onward.flatMap((to) => paths(to, route));
}

describe('manual status changes, properties over the whole table', () => {
  it('names only known statuses, every status once, and no change of a status to itself', () => {
    expect(Object.keys(VALID_STATUS_TRANSITIONS).sort()).toEqual([...MESSAGE_STATUSES].sort());
    for (const from of MESSAGE_STATUSES) {
      const targets = validTransitionsFor(from);
      expect(new Set(targets).size).toBe(targets.length);
      for (const to of targets) {
        expect(MESSAGE_STATUSES).toContain(to);
        expect(to).not.toBe(from);
      }
    }
    expect(validTransitionsFor(undefined)).toEqual([]);
  });

  it('leads nowhere from a terminal status, and from every other status to a terminal one', () => {
    for (const from of MESSAGE_STATUSES) {
      if (TERMINAL.includes(from)) {
        expect(validTransitionsFor(from)).toEqual([]);
      } else {
        for (const path of paths(from)) {
          // A path ends where the table offers nothing new: at a terminal status, or on a cycle
          // back (draft and verified may swap). Every status on it can still reach a terminal one.
          for (const status of path) {
            const reachable = new Set<MessageStatus>([status]);
            for (let grew = true; grew; ) {
              grew = false;
              for (const s of [...reachable]) {
                for (const to of validTransitionsFor(s)) {
                  if (!reachable.has(to)) {
                    reachable.add(to);
                    grew = true;
                  }
                }
              }
            }
            expect(
              TERMINAL.some((t) => reachable.has(t)),
              path.join(' → '),
            ).toBe(true);
          }
        }
      }
    }
  });

  it('never takes a published message back to draft or verified, and publishes only what was verified', () => {
    for (const from of MESSAGE_STATUSES) {
      for (const path of paths(from)) {
        const published = path.indexOf('PUBLISHED');
        if (published < 0) continue;
        const after = path.slice(published + 1);
        expect(
          after.some((s) => s === 'DRAFT' || s === 'VERIFIED'),
          path.join(' → '),
        ).toBe(false);
        if (published > 0) expect(path[published - 1], path.join(' → ')).toBe('VERIFIED');
      }
    }
    expect(validTransitionsFor('PUBLISHED')).toEqual(['CANCELLED']);
  });
});
