import { describe, expect, it } from 'vitest';

import type { BasePromulgation, Message } from '../../messages/models/message.model';
import {
  canonicalize,
  canonicalKey,
  messageContentSignature,
  partContent,
  promulgationsContent,
} from './message-change.util';

describe('canonicalize', () => {
  it('collapses empty-equivalent primitives to absent, keeps real values (incl. 0)', () => {
    expect(canonicalize('')).toBeUndefined();
    expect(canonicalize(null)).toBeUndefined();
    expect(canonicalize(false)).toBeUndefined();
    expect(canonicalize(0)).toBe(0); // 0 is a real value — NOT dropped
    expect(canonicalize('x')).toBe('x');
    expect(canonicalize(true)).toBe(true);
  });

  it('drops empty objects/arrays and empty properties recursively', () => {
    expect(canonicalize({ a: '', b: null, c: false })).toBeUndefined();
    expect(canonicalize([])).toBeUndefined();
    expect(canonicalize({ a: 'x', b: '' })).toEqual({ a: 'x' });
    expect(canonicalize([{ a: '' }, { b: 'y' }])).toEqual([{ b: 'y' }]);
  });

  it('is key-order-insensitive but array-order-sensitive', () => {
    expect(canonicalKey({ a: 1, b: 2 })).toBe(canonicalKey({ b: 2, a: 1 }));
    expect(canonicalKey(['a', 'b'])).not.toBe(canonicalKey(['b', 'a']));
  });
});

describe('promulgationsContent', () => {
  const chan = (
    typeId: string,
    promulgate: boolean,
    extra: Record<string, unknown> = {},
  ): BasePromulgation =>
    ({
      type: { typeId, serviceId: typeId, name: typeId },
      promulgate,
      ...extra,
    }) as BasePromulgation;

  it('ignores a NOT-chosen channel entirely — Type-driven show/hide/re-id never counts', () => {
    // Reported bug: NAVTEX is present-but-not-promulgating. A Type change removes it, and reverting
    // re-adds it with a fresh (null) id. All three states must project identically → no dot.
    const loaded = [
      chan('navtex', false, { id: 5, text: 'x' }),
      chan('twitter', true, { text: 't' }),
    ];
    const afterTypeChange = [chan('twitter', true, { text: 't' })]; // navtex removed
    const afterRevert = [
      chan('navtex', false, { id: null, text: 'x' }),
      chan('twitter', true, { text: 't' }),
    ];

    const key = (p: BasePromulgation[]) => canonicalKey(promulgationsContent(p));
    expect(key(afterTypeChange)).toBe(key(loaded));
    expect(key(afterRevert)).toBe(key(loaded));
  });

  it('ignores server id churn on a PROMULGATING channel (same content, different id)', () => {
    const withId = [chan('navtex', true, { id: 5, text: 'NAVAREA' })];
    const nullId = [chan('navtex', true, { id: null, text: 'NAVAREA' })];
    expect(canonicalKey(promulgationsContent(nullId))).toBe(
      canonicalKey(promulgationsContent(withId)),
    );
  });

  it('detects a real content edit on a promulgating channel', () => {
    const before = [chan('navtex', true, { id: 5, text: 'NAVAREA' })];
    const after = [chan('navtex', true, { id: 5, text: 'NAVAREA edited' })];
    expect(canonicalKey(promulgationsContent(after))).not.toBe(
      canonicalKey(promulgationsContent(before)),
    );
  });

  it('detects turning a channel on', () => {
    const off = [chan('twitter', false, { text: 't' })];
    const on = [chan('twitter', true, { text: 't' })];
    expect(canonicalKey(promulgationsContent(on))).not.toBe(
      canonicalKey(promulgationsContent(off)),
    );
  });
});

describe('messageContentSignature', () => {
  const base: Message = { id: 'm', type: 'LOCAL_WARNING', descs: [{ lang: 'en', source: 'EMA' }] };

  it('is stable across blank-language padding + empty-normalisation (the mergeDescs rewrite)', () => {
    // What the form sync produces on ANY change: descs padded with a blank `da`, booleans forced
    // to explicit `false`, empty strings — none of which is a user content change.
    const rewritten: Message = {
      id: 'm',
      type: 'LOCAL_WARNING',
      separatePage: false,
      originalInformation: false,
      descs: [
        { lang: 'en', source: 'EMA', title: '' },
        { lang: 'da', source: undefined, title: '' },
      ],
    };
    expect(messageContentSignature(rewritten)).toBe(messageContentSignature(base));
  });

  it('ignores the volatile editorFields map and each part indexNo', () => {
    const a: Message = {
      id: 'm',
      type: 'LOCAL_WARNING',
      editorFields: { charts: true },
      parts: [{ type: 'DETAILS', indexNo: 0 }],
    };
    const b: Message = {
      id: 'm',
      type: 'LOCAL_WARNING',
      editorFields: { charts: false, source: true },
      parts: [{ type: 'DETAILS', indexNo: 5 }],
    };
    expect(messageContentSignature(a)).toBe(messageContentSignature(b));
  });

  it('detects a genuine per-language content change', () => {
    const changed: Message = {
      id: 'm',
      type: 'LOCAL_WARNING',
      descs: [{ lang: 'en', source: 'CHANGED' }],
    };
    expect(messageContentSignature(changed)).not.toBe(messageContentSignature(base));
  });

  it('a part identity ignores indexNo AND the derived startCoordIndex stamp', () => {
    const part = (indexNo: number) => ({
      type: 'DETAILS',
      indexNo,
      geometry: {
        type: 'FeatureCollection' as const,
        features: [
          {
            type: 'Feature' as const,
            properties: { startCoordIndex: indexNo, name: 'x' },
            geometry: { type: 'Point' as const, coordinates: [1, 2] },
          },
        ],
      },
    });
    // Same content, different indexNo + startCoordIndex → identical identity.
    expect(canonicalKey(partContent(part(1)))).toBe(canonicalKey(partContent(part(9))));
  });

  it('treats two dates that are the same instant as equal (ISO round-trip tolerance)', () => {
    // The form round-trips every date through `new Date(iso).toISOString()`, so a backend
    // `…:00Z` becomes `…:00.000Z` — same moment, different string. That must NOT read as a change.
    const backend: Message = {
      id: 'm',
      type: 'LOCAL_WARNING',
      publishDateFrom: '2026-07-06T10:00:00Z',
      parts: [
        {
          type: 'DETAILS',
          indexNo: 0,
          eventDates: [{ allDay: false, fromDate: '2026-07-06T10:00:00Z' }],
        },
      ],
    };
    const roundTripped: Message = {
      id: 'm',
      type: 'LOCAL_WARNING',
      publishDateFrom: '2026-07-06T10:00:00.000Z',
      parts: [
        {
          type: 'DETAILS',
          indexNo: 0,
          eventDates: [{ allDay: false, fromDate: '2026-07-06T10:00:00.000Z' }],
        },
      ],
    };
    expect(messageContentSignature(roundTripped)).toBe(messageContentSignature(backend));
  });

  it('treats an epoch-NUMBER date and its ISO-STRING form equal (real backend vs form re-sync)', () => {
    // The backend serialises dates as epoch numbers; the form writes them back as ISO strings. The
    // loaded baseline therefore holds a number where the re-synced working copy holds a string for
    // the SAME instant — this must NOT dot the Dates field (the actual type-change regression).
    const from = '2026-05-01T07:35:00.000Z';
    const epoch = new Date(from).getTime();
    // The Message model types dates as `string`, but the runtime value is a number — cast to model it.
    const loaded = {
      id: 'm',
      type: 'LOCAL_WARNING',
      publishDateFrom: epoch,
      parts: [{ type: 'TIME', indexNo: 0, eventDates: [{ allDay: false, fromDate: epoch }] }],
    } as unknown as Message;
    const reSynced: Message = {
      id: 'm',
      type: 'LOCAL_WARNING',
      publishDateFrom: from,
      parts: [{ type: 'TIME', indexNo: 0, eventDates: [{ allDay: false, fromDate: from }] }],
    };
    expect(messageContentSignature(reSynced)).toBe(messageContentSignature(loaded));
  });

  it('still detects a genuine date change', () => {
    const at10: Message = {
      id: 'm',
      type: 'LOCAL_WARNING',
      publishDateFrom: '2026-07-06T10:00:00Z',
    };
    const at11: Message = {
      id: 'm',
      type: 'LOCAL_WARNING',
      publishDateFrom: '2026-07-06T11:00:00Z',
    };
    expect(messageContentSignature(at10)).not.toBe(messageContentSignature(at11));
  });

  it('treats a part reorder as a change (array order IS content)', () => {
    const ab: Message = {
      id: 'm',
      parts: [
        { type: 'DETAILS', indexNo: 0, descs: [{ lang: 'en', subject: 'A' }] },
        { type: 'DETAILS', indexNo: 1, descs: [{ lang: 'en', subject: 'B' }] },
      ],
    };
    const ba: Message = {
      id: 'm',
      parts: [
        { type: 'DETAILS', indexNo: 0, descs: [{ lang: 'en', subject: 'B' }] },
        { type: 'DETAILS', indexNo: 1, descs: [{ lang: 'en', subject: 'A' }] },
      ],
    };
    expect(messageContentSignature(ab)).not.toBe(messageContentSignature(ba));
  });
});
