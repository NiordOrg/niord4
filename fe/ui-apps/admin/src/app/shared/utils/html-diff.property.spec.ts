import { describe, expect, it } from 'vitest';

import { htmlDiff } from './html-diff.util';

/**
 * Properties of the message comparison's Changes tab, over generated pairs of message texts from a
 * fixed seed (error hunt, phase 2): the diff, with its deletions dropped and its insertions kept,
 * reads as the new text; with its insertions dropped and its deletions kept, as the old one; and the
 * same text on both sides marks nothing.
 *
 * The one fault found is pinned, not fixed: a character reference changed into another (`&lt;` to
 * `&gt;`) is cut before its `;`, which the tab then shows as a character of both texts (PF-5 in
 * the hunt's record).
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
  return { next, int, pick };
}

type Rng = ReturnType<typeof seeded>;

const WORDS = [
  'Wreck',
  'buoy',
  'light',
  'unlit',
  'is',
  'reported',
  '55°',
  "30.0'N",
  '012°',
  "15.4'E",
  'Ø',
  'gas',
  'pipe',
  'depth',
  '5',
  'm',
  '(see',
  'chart)',
  '-',
  '1/2',
  'NW-123-26',
  'cancelled.',
  ',',
];
const REFERENCES = ['&amp;', '&lt;', '&gt;', '&nbsp;'];

/** A text of words; `references` lets a word be a character reference. */
function sentence(rng: Rng, references: boolean): string[] {
  return Array.from({ length: rng.int(1, 14) }, () =>
    references && rng.next() < 0.25 ? rng.pick(REFERENCES) : rng.pick(WORDS),
  );
}

/** The sentence after an editor's change: words replaced, inserted, deleted. */
function edited(rng: Rng, words: readonly string[], references: boolean): string[] {
  const out = [...words];
  for (let n = rng.int(1, 3); n > 0; n--) {
    const at = rng.int(0, out.length);
    const word = references && rng.next() < 0.4 ? rng.pick(REFERENCES) : rng.pick(WORDS);
    const what = rng.int(0, 2);
    if (what === 0 && out.length > 0) out.splice(Math.min(at, out.length - 1), 1, word);
    else if (what === 1) out.splice(at, 0, word);
    else if (out.length > 1) out.splice(Math.min(at, out.length - 1), 1);
  }
  return out;
}

/** The text a reader sees of the diff with one side's marks dropped and the other side's kept. */
function side(diff: string, keep: 'ins' | 'del'): string {
  const body = new DOMParser().parseFromString(diff, 'text/html').body;
  for (const element of [...body.querySelectorAll(keep === 'ins' ? 'del' : 'ins')])
    element.remove();
  return body.textContent ?? '';
}

function shown(html: string): string {
  return new DOMParser().parseFromString(html, 'text/html').body.textContent ?? '';
}

function paragraph(words: readonly string[]): string {
  return `<p>${words.join(' ')}</p>`;
}

describe('htmlDiff, properties over generated message texts', () => {
  it('reads as the new text without its deletions and as the old one without its insertions', () => {
    const rng = seeded(20261115);
    for (let i = 0; i < 500; i++) {
      const words = sentence(rng, false);
      const before = paragraph(words);
      const after = paragraph(edited(rng, words, false));
      const diff = htmlDiff(before, after);
      expect(side(diff, 'ins'), `${before} → ${after}: ${diff}`).toBe(shown(after));
      expect(side(diff, 'del'), `${before} → ${after}: ${diff}`).toBe(shown(before));
    }
    expect(htmlDiff('<p>Wreck &amp; buoy</p>', '<p>Wreck &amp; buoy</p>')).toBe(
      '<p>Wreck &amp; buoy</p>',
    );
  }, 60_000);

  it('splits a changed character reference, so neither text is shown — documents a fault (PF-5)', () => {
    // The smallest case: "A & B" became "A < B". Expected: the tab reads "A < B" without the
    // deletion and "A & B" without the insertion. Seen: the reference is cut before its ";", which
    // is left over as a character of its own on both sides.
    const diff = htmlDiff('<p>A &amp; B</p>', '<p>A &lt; B</p>');
    expect(diff).toBe('<p>A <del>&amp</del><ins>&lt</ins>; B</p>');
    expect(side(diff, 'ins')).toBe('A <; B');
    expect(side(diff, 'del')).toBe('A &; B');
    // The tab diffs the rendered parts' innerHTML, which writes `&amp;`, `&lt;`, `&gt;` and
    // `&nbsp;`: "depth < 5 m" corrected to "depth > 5 m" shows as "depth >; 5 m".
    const depth = htmlDiff('<p>depth &lt; 5 m</p>', '<p>depth &gt; 5 m</p>');
    expect(side(depth, 'ins')).toBe('depth >; 5 m');
    expect(side(depth, 'del')).toBe('depth <; 5 m');

    // Over generated texts with references: each pair whose change touches a reference.
    const rng = seeded(20261116);
    let broken = 0;
    for (let i = 0; i < 400; i++) {
      const words = sentence(rng, true);
      const before = paragraph(words);
      const after = paragraph(edited(rng, words, true));
      const diff = htmlDiff(before, after);
      if (side(diff, 'ins') !== shown(after) || side(diff, 'del') !== shown(before)) broken++;
    }
    expect(broken).toBeGreaterThan(20);
  }, 60_000);
});
