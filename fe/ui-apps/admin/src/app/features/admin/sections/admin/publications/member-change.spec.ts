import { describe, expect, it } from 'vitest';

import en from '../../../../../../../../../languages/en/i18n/admin/en.json';
import type { IssueMember } from '../../../services/api/publication-series-api.service';
import { MEMBER_CHANGE_KEYS, memberChangeNotes } from './member-change';

/**
 * What a member row of a published issue says under its status when the
 * message has changed since the issue went out.
 *
 * The row itself is never rewritten — a published document lists what it listed
 * — and the status shown is the live one, so these notes are the ONLY way a
 * reader tells "cancelled, and still in this issue because it happened after
 * the cut-off" from a row that was simply never published.
 */

type TranslationNode = string | { readonly [key: string]: TranslationNode };

function labelIn(bundle: object, key: string): string | undefined {
  let node: TranslationNode | undefined = bundle as TranslationNode;
  for (const segment of key.split('.')) {
    if (node === undefined || typeof node === 'string') {
      return undefined;
    }
    node = node[segment];
  }
  return typeof node === 'string' ? node : undefined;
}

function member(over: Partial<IssueMember> = {}): IssueMember {
  return { messageUid: 'uid-1', frozenShortId: 'NM-1', ...over };
}

describe('member change notes', () => {
  /** A live list has no change at all, and neither has a frozen row that agrees. */
  it('says nothing on a row the server marked nothing on', () => {
    expect(memberChangeNotes(member())).toEqual([]);
    expect(memberChangeNotes(member({ drift: [] }))).toEqual([]);
    expect(
      memberChangeNotes(
        member({ current: { exists: true, publiclyVisible: true, status: 'PUBLISHED' } }),
      ),
    ).toEqual([]);
    expect(memberChangeNotes(undefined)).toEqual([]);
  });

  it('names the live status a member changed to, and that it stays in the issue', () => {
    const notes = memberChangeNotes(
      member({
        drift: ['status'],
        current: { exists: true, publiclyVisible: true, status: 'EXPIRED' },
      }),
    );

    expect(notes.length).toBe(1);
    expect(notes[0].key).toBe('admin.publications.change.status');
    expect(notes[0].valueKey).toBe('admin.publications.memberStatus.EXPIRED');
  });

  /**
   * The finding, not the status name.
   *
   * "Expired" and "cancelled, and so no longer readable by the public" are both
   * changes after the release, and only the second means a reader following the
   * document will find nothing. A reader who has to know which statuses are
   * public to see it is a reader who misses it.
   */
  it('says a member left the public site, not just that its status moved', () => {
    const notes = memberChangeNotes(
      member({
        drift: ['status'],
        current: { exists: true, publiclyVisible: false, status: 'CANCELLED' },
      }),
    );

    expect(notes[0].key).toBe('admin.publications.change.statusNotPublic');
    expect(notes[0].valueKey).toBe('admin.publications.memberStatus.CANCELLED');
  });

  /** A deleted message has no type and no status to report, and says so itself. */
  it('reports a vanished message under its own name', () => {
    const notes = memberChangeNotes(
      member({ drift: ['exists'], current: { exists: false, publiclyVisible: false } }),
    );

    expect(notes.map((n) => n.key)).toEqual(['admin.publications.change.gone']);
    expect(notes[0].valueKey).toBeUndefined();
  });

  it('quotes the live type through the shared message-type vocabulary', () => {
    const notes = memberChangeNotes(
      member({
        drift: ['type'],
        current: { exists: true, publiclyVisible: true, type: 'PERMANENT_NOTICE' },
      }),
    );

    expect(notes[0].key).toBe('admin.publications.change.type');
    expect(notes[0].valueKey).toBe('msg.type.PERMANENT_NOTICE');
  });

  /**
   * A sentence quoting a value it does not have would read "changed to" and
   * then nothing, which looks like a rendering fault rather than a finding.
   */
  it('falls back to a wording that quotes nothing when the live value is missing', () => {
    const notes = memberChangeNotes(
      member({ drift: ['type', 'status'], current: { exists: true, publiclyVisible: true } }),
    );

    expect(notes.map((n) => n.key)).toEqual([
      'admin.publications.change.statusChanged',
      'admin.publications.change.typeChanged',
    ]);
    expect(notes.every((n) => n.valueKey === undefined)).toBe(true);
  });

  /** One order, whatever order the server used, so two rows read alike. */
  it('orders the notes itself rather than following the response', () => {
    const notes = memberChangeNotes(
      member({
        drift: ['type', 'status', 'exists'],
        current: { exists: false, publiclyVisible: false },
      }),
    );

    expect(notes.map((n) => n.field)).toEqual(['exists', 'status', 'type']);
  });

  /** A field this build has no sentence for is left off rather than shown as a key path. */
  it('drops a change field it has no wording for', () => {
    const notes = memberChangeNotes(
      member({
        drift: ['somethingNew' as never, 'type'],
        current: { exists: true, publiclyVisible: true, type: 'LOCAL_WARNING' },
      }),
    );

    expect(notes.map((n) => n.field)).toEqual(['type']);
  });

  for (const [name, bundle] of [['English', en]] as const) {
    it(`has a ${name} sentence for every change note`, () => {
      const missing = MEMBER_CHANGE_KEYS.filter((key) => labelIn(bundle, key) === undefined);
      expect(missing, `keys missing:\n${missing.join('\n')}`).toEqual([]);
    });
  }
});
