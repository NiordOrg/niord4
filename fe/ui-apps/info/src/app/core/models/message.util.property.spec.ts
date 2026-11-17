import { describe, expect, it } from 'vitest';

import type { Area, Desc, Message } from './message.model';
import { computeAreaHeadings, filterByAreaIds, rootAreaOf, sortDescs } from './message.util';

/**
 * Properties of the public site's message helpers, over generated messages and area trees from a
 * fixed seed: the reader's language comes first in every text list and the
 * other languages keep their order, without changing the fetched message; the area chips keep
 * exactly the messages filed under a ticked area or one of its sub-areas; and an area heading is
 * printed above the first message of each run of one area, and only there.
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

const LANGS = ['da', 'en', 'kl', 'fo'];

function descs(rng: Rng): Desc[] {
  return rng
    .shuffle(LANGS)
    .slice(0, rng.int(0, 4))
    .map((lang) => ({ lang, title: `t-${lang}-${rng.int(1, 99)}` }));
}

/** An area tree three levels deep; every area knows its parent. */
function areas(rng: Rng): Area[] {
  const out: Area[] = [];
  let id = 1;
  for (let r = 0; r < rng.int(1, 3); r++) {
    const root: Area = { id: id++, descs: descs(rng) };
    out.push(root);
    for (let c = 0; c < rng.int(0, 3); c++) {
      const child: Area = { id: id++, parent: root };
      out.push(child);
      for (let g = 0; g < rng.int(0, 3); g++) out.push({ id: id++, parent: child });
    }
  }
  return out;
}

function lineage(area: Area): number[] {
  const ids: number[] = [];
  for (let a: Area | undefined = area; a; a = a.parent) ids.push(a.id);
  return ids;
}

function messages(rng: Rng, pool: readonly Area[]): Message[] {
  return Array.from(
    { length: rng.int(0, 30) },
    (_, n) =>
      ({
        id: `m${n}`,
        mainType: rng.pick(['NW', 'NM'] as const),
        descs: descs(rng),
        areas:
          rng.next() < 0.1
            ? undefined
            : Array.from({ length: rng.int(0, 2) }, () => rng.pick(pool)),
        parts: [{ descs: descs(rng) }],
      }) as unknown as Message,
  );
}

describe('public message helpers, properties over generated messages', () => {
  it("puts the reader's language first in every text list, keeps the others in order and leaves the fetched message alone", () => {
    const rng = seeded(20261115);
    for (let i = 0; i < 3000; i++) {
      const pool = areas(rng);
      const [msg] = messages(rng, pool).concat({
        id: 'x',
        mainType: 'NW',
        descs: descs(rng),
      } as Message);
      const lang = rng.pick(LANGS);
      const frozen = JSON.stringify(msg, (k, v: unknown) => (k === 'parent' ? undefined : v));
      const sorted = sortDescs(msg, lang);
      const lists: [readonly Desc[] | undefined, readonly Desc[] | undefined][] = [
        [msg.descs, sorted.descs],
        ...(msg.parts ?? []).map(
          (p, n): [readonly Desc[] | undefined, readonly Desc[] | undefined] => [
            p.descs,
            sorted.parts?.[n].descs,
          ],
        ),
      ];
      for (const [before, after] of lists) {
        const input = before ?? [];
        const output = after ?? [];
        expect(new Set(output)).toEqual(new Set(input));
        if (input.some((d) => d.lang === lang)) expect(output[0].lang).toBe(lang);
        expect(output.filter((d) => d.lang !== lang)).toEqual(input.filter((d) => d.lang !== lang));
      }
      expect(JSON.stringify(msg, (k, v: unknown) => (k === 'parent' ? undefined : v))).toBe(frozen);
    }
  });

  it('keeps exactly the messages filed under a ticked area or below it, and all of them with nothing ticked', () => {
    const rng = seeded(20261116);
    for (let i = 0; i < 3000; i++) {
      const pool = areas(rng);
      const list = messages(rng, pool);
      const ticked = new Set(pool.filter(() => rng.next() < 0.2).map((a) => a.id));
      const kept = filterByAreaIds(list, ticked);
      if (ticked.size === 0) {
        expect(kept).toBe(list);
        continue;
      }
      const expected = list.filter((m) =>
        (m.areas ?? []).some((a) => lineage(a).some((id) => ticked.has(id))),
      );
      expect(kept).toEqual(expected);
      for (const m of kept) {
        const roots = (m.areas ?? []).map((a) => rootAreaOf(a)?.id);
        expect(roots.every((id) => pool.some((a) => a.id === id && !a.parent))).toBe(true);
      }
    }
  });

  it('prints an area heading above the first message of each run of one area, and only there', () => {
    const rng = seeded(20261117);
    for (let i = 0; i < 3000; i++) {
      const pool = areas(rng);
      const list = messages(rng, pool);
      const levels = rng.int(1, 3);
      const headings = computeAreaHeadings(list, levels);
      let last: number | undefined;
      for (const m of list) {
        const first = m.areas?.[0];
        if (!first) {
          expect(headings.has(m.id)).toBe(false);
          continue;
        }
        const chain = lineage(first).reverse();
        const headingId = chain[Math.min(chain.length, levels) - 1];
        expect(headings.get(m.id)?.id, m.id).toBe(headingId === last ? undefined : headingId);
        last = headingId;
      }
    }
  });
});
