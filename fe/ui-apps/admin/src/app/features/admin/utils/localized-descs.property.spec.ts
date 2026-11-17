import { describe, expect, it } from 'vitest';

import { currentDesc, orderDescs, padAndOrderDescs } from './localized-descs';

/**
 * Properties of the language fallback and the per-language lists of the admin forms, over generated
 * texts from a fixed seed (error hunt, phase 2): padding never drops a text the server sent and
 * gives every configured language one entry in the configured order; ordering is a permutation;
 * and the one text shown for the reader's language takes each field from the reader's language,
 * else the default language, else the first text that has it.
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

interface Desc {
  readonly lang: string;
  readonly name?: string;
  readonly description?: string;
}

const LANGS = ['en', 'da', 'kl', 'fo', 'de'];

/** One text per language (the backend keeps one per language), some fields blank or missing. */
function descs(rng: Rng): Desc[] {
  const langs = rng.shuffle(LANGS).slice(0, rng.int(0, LANGS.length));
  const field = (lang: string, what: string): string | undefined =>
    rng.pick([undefined, '', '  ', `${what} ${lang} ${rng.int(1, 999)}`, ` ${what} ${lang} `]);
  return langs.map((lang) => ({
    lang,
    name: field(lang, 'name'),
    description: field(lang, 'text'),
  }));
}

function configured(rng: Rng): string[] {
  return rng.shuffle(LANGS).slice(0, rng.int(1, 3));
}

const filled = (value: string | undefined): boolean =>
  typeof value === 'string' && value.trim() !== '';

describe('localized texts, properties over generated texts', () => {
  it('pads to one entry per configured language, in that order, and drops no text the server sent', () => {
    const rng = seeded(20261115);
    for (let i = 0; i < 5000; i++) {
      const input = descs(rng);
      const languages = configured(rng);
      const padded = padAndOrderDescs(input, languages, (lang) => ({ lang, name: '' }));
      expect(padded.slice(0, languages.length).map((d) => d.lang)).toEqual(languages);
      for (const desc of input) expect(padded).toContain(desc);
      const extra = padded.slice(languages.length);
      expect(extra).toEqual(input.filter((d) => !languages.includes(d.lang)));
      // Padding twice changes nothing.
      expect(padAndOrderDescs(padded, languages, (lang) => ({ lang, name: '' }))).toEqual(padded);
    }
  });

  it('orders the texts as a permutation: configured languages first, in order, the rest as they came', () => {
    const rng = seeded(20261116);
    for (let i = 0; i < 5000; i++) {
      const input = descs(rng);
      const languages = configured(rng);
      const ordered = orderDescs(input, languages);
      expect(new Set(ordered)).toEqual(new Set(input));
      expect(ordered).toHaveLength(input.length);
      const known = ordered.filter((d) => languages.includes(d.lang)).map((d) => d.lang);
      expect(known).toEqual(languages.filter((lang) => input.some((d) => d.lang === lang)));
      expect(ordered.slice(known.length)).toEqual(input.filter((d) => !languages.includes(d.lang)));
    }
  });

  it("shows each field in the reader's language, else the default language, else the first text that has it", () => {
    const rng = seeded(20261117);
    let mixed = 0;
    for (let i = 0; i < 5000; i++) {
      const input = descs(rng);
      const languages = configured(rng);
      const reader = rng.pick(LANGS);
      const shown = currentDesc(input, languages, reader);
      if (input.length === 0) {
        expect(shown).toBeUndefined();
        continue;
      }
      const preferred = input.find((d) => d.lang === reader);
      const fallback = input.find((d) => d.lang === languages[0]);
      for (const field of ['name', 'description'] as const) {
        const source = [preferred, fallback, ...input].find((d) => filled(d?.[field]));
        if (source) {
          expect(shown?.[field], `${field} for ${reader}`).toBe(source[field]);
        } else {
          expect(filled(shown?.[field])).toBe(false);
        }
      }
      expect(shown?.lang).toBe((preferred ?? fallback ?? input[0]).lang);
      if (shown && shown.lang !== reader && preferred === undefined) mixed++;
      // Never the stored object: a display copy.
      expect(input).not.toContain(shown);
    }
    expect(mixed).toBeGreaterThan(100);
  });
});
