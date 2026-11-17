import { describe, expect, it } from 'vitest';

import { normalizeAttachmentPath, normalizeRepoUrl, normalizeRepoUrlsInHtml } from './repo-url';

/**
 * Properties of the public site's repository links, over generated file names and links from a
 * fixed seed: an attachment's address names exactly the stored file, in
 * either of the two spellings the backend hands out; a link to this site's repository loads from
 * this origin with its query and fragment, and every other link is left as it was.
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

/** What an uploaded file name may hold, every address delimiter among it; never a valid `%XX`. */
const NAME_CHARS = [
  ...'abcXYZ019æøåÆØÅé-_.~ ',
  '#',
  '?',
  '&',
  '=',
  '+',
  "'",
  '"',
  '(',
  ')',
  ',',
  ';',
  '@',
  '!',
  '$',
  '[',
  ']',
  '%',
  '°',
  '🌊',
];

function fileName(rng: Rng): string {
  for (;;) {
    const name = Array.from({ length: rng.int(1, 14) }, () => rng.pick(NAME_CHARS)).join('');
    if (!/%[0-9a-fA-F]{2}/.test(name) && name !== '.' && name !== '..') return name;
  }
}

function repoPath(rng: Rng): string[] {
  return [
    'messages',
    String(rng.int(0, 99)),
    String(rng.int(0, 99)),
    `${rng.int(1, 9999)}`,
    fileName(rng),
  ];
}

const HOSTS: ReadonlySet<string> = new Set(['info.example', 'niord.example:8443']);
const SITE = 'https://info.example';

describe('repository links on the public site, properties over generated names', () => {
  it('addresses the stored file, the name as uploaded or already encoded', () => {
    const rng = seeded(20261115);
    for (let i = 0; i < 5000; i++) {
      const segments = repoPath(rng);
      const raw = segments.join('/');
      const encoded = segments.map(encodeURIComponent).join('/');
      const fromRaw = normalizeAttachmentPath(raw, HOSTS) ?? '';
      const fromEncoded = normalizeAttachmentPath(encoded, HOSTS) ?? '';
      expect(fromEncoded, raw).toBe(fromRaw);
      // The browser asks for exactly this path: nothing is cut at a `#` or a `?`.
      const asked = new URL(fromRaw, SITE);
      expect(asked.search, raw).toBe('');
      expect(asked.hash, raw).toBe('');
      expect(asked.pathname.split('/').slice(4).map(decodeURIComponent), raw).toEqual(segments);
      expect(asked.pathname.startsWith('/rest/repo/file/messages/'), raw).toBe(true);
    }
  });

  it('brings a link to this repository home with its query and fragment, and leaves every other link alone', () => {
    const rng = seeded(20261116);
    for (let i = 0; i < 5000; i++) {
      const path = '/rest/repo/file/' + repoPath(rng).map(encodeURIComponent).join('/');
      const search = rng.next() < 0.3 ? `?v=${rng.int(1, 9)}` : '';
      const hash = rng.next() < 0.3 ? `#page=${rng.int(1, 99)}` : '';
      const host = rng.pick([
        'info.example',
        'niord.example:8443',
        'other.example',
        'info.example.evil',
      ]);
      const scheme = rng.pick(['https', 'http']);
      const kind = rng.int(0, 2);
      const url =
        kind === 0
          ? `${scheme}://${host}${path}${search}${hash}`
          : kind === 1
            ? `${scheme}://${host}/rest/other/${rng.int(1, 9)}${search}`
            : `${path}${search}${hash}`;
      const normalized = normalizeRepoUrl(url, HOSTS);
      expect(normalizeRepoUrl(normalized, HOSTS), url).toBe(normalized);
      if (kind === 0 && HOSTS.has(host)) {
        expect(normalized, url).toBe(`${path}${search}${hash}`);
      } else {
        expect(normalized, url).toBe(url);
      }
      // In stored HTML, in either quote, exactly the same.
      const quote = rng.pick(['"', "'"]);
      const html = `<p>See <a href=${quote}${url}${quote}>this</a> <img src=${quote}${url}${quote}></p>`;
      expect(normalizeRepoUrlsInHtml(html, HOSTS), url).toBe(
        `<p>See <a href=${quote}${normalized}${quote}>this</a> <img src=${quote}${normalized}${quote}></p>`,
      );
    }
  });
});
