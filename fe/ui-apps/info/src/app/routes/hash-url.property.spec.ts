import { describe, expect, it } from 'vitest';

import { hashUrlTarget } from './hash-url';

/**
 * Properties of the rewrite of the old hash-routed public site's links, over generated addresses
 * from a fixed seed: whatever a fragment holds, the result stays on this site
 * and an address on the teaser stays on the teaser; and every query parameter the old link carried
 * reaches the new address, also when it carried one query before the fragment and another inside
 * the route.
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

/** The pieces a hostile or broken fragment is made of. */
const PIECES = [
  '/',
  '//',
  '///',
  '\\',
  '\\\\',
  '.',
  '..',
  '%2e',
  '%2E%2E',
  '%2f',
  '%5c',
  '?',
  '#',
  '#?',
  '#/',
  '&',
  '=',
  ':',
  '@',
  'http:',
  'javascript:',
  'elsewhere.example',
  'teaser',
  'messages',
  'map',
  'x',
  ' ',
  '\t',
  '%00',
  '[',
  '%',
  'æ',
];

function fragmentText(rng: Rng): string {
  return Array.from({ length: rng.int(0, 8) }, () => rng.pick(PIECES)).join('');
}

const PATHS = ['/', '/index.html', '/teaser', '/teaser/', '/messages/map', '/x/teaser'];

describe('hashUrlTarget, properties over generated addresses', () => {
  it('never leaves the site and never moves the teaser, whatever the fragment holds', () => {
    const rng = seeded(20261115);
    const SITE = 'https://info.example';
    let rewritten = 0;
    for (let i = 0; i < 20000; i++) {
      const pathname = rng.pick(PATHS);
      const search = rng.next() < 0.3 ? `?lang=${rng.pick(['da', 'en'])}` : '';
      const hash = rng.pick(['#/', '#?', '#']) + fragmentText(rng);
      const target = hashUrlTarget({ pathname, search, hash });
      if (target === null) continue;
      rewritten++;
      const resolved = new URL(target, SITE);
      expect(resolved.origin, `${pathname}${search}${hash} → ${target}`).toBe(SITE);
      expect(target.startsWith('/'), target).toBe(true);
      expect(/^[/\\]{2}/.test(target), target).toBe(false);
      if (/^\/teaser\/?$/.test(pathname)) {
        expect(resolved.pathname, `${pathname}${hash} → ${target}`).toMatch(/^\/teaser\/?$/);
      }
    }
    expect(rewritten).toBeGreaterThan(10000);
  });

  it('carries every query parameter of a link with one query to the new address', () => {
    const rng = seeded(20261116);
    const ROUTES = [
      '/messages/map',
      '/messages/grid',
      '/messages/details',
      '/publications',
      '/teaser',
    ];
    for (let i = 0; i < 3000; i++) {
      const pairs = Array.from({ length: rng.int(1, 4) }, (_, n) => [
        `p${n}`,
        rng.pick(['da', 'en', 'NW', '42', 'a b', 'æøå', '1,2']),
      ]);
      const query = new URLSearchParams(pairs).toString();
      const route = rng.pick(ROUTES);
      // The three shapes an old link with ONE query takes.
      const shape = rng.int(0, 2);
      const address =
        shape === 0
          ? { pathname: '/', search: `?${query}`, hash: `#${route}` }
          : shape === 1
            ? { pathname: '/', search: '', hash: `#${route}?${query}` }
            : { pathname: route, search: '', hash: `#?${query}` };
      const target = hashUrlTarget(address) ?? '';
      const read = new URL(target, 'https://info.example');
      expect(read.pathname).toBe(route);
      for (const [name, value] of pairs) {
        expect(read.searchParams.get(name), `${JSON.stringify(address)} → ${target}`).toBe(value);
      }
    }
  });

  // fe/docs/backlog.md, The public site: An old link with a query before the fragment and another
  // inside the route loses the outer query (PF-3).
  it('carries the query before the fragment and the one inside the route both to the new address', () => {
    // An old link `/?lang=da#/messages/map?area=7` names two parameters, and both arrive.
    const target = hashUrlTarget({
      pathname: '/',
      search: '?lang=da',
      hash: '#/messages/map?area=7',
    });
    expect(target).toBe('/messages/map?area=7&lang=da');
    const read = new URL(target ?? '', 'https://info.example').searchParams;
    expect(read.get('area')).toBe('7');
    expect(read.get('lang')).toBe('da');

    // A fragment of the route's own stays a fragment, after the query.
    expect(hashUrlTarget({ pathname: '/', search: '?lang=da', hash: '#/messages/map#top' })).toBe(
      '/messages/map?lang=da#top',
    );

    // Over generated links with both queries, and with or without a fragment of the route's own:
    // every parameter of either query arrives, under its own name and with its own value.
    const rng = seeded(20261117);
    const VALUES = ['da', 'en', 'NW', '42', 'a b', 'æøå', '1,2', '7?x'];
    const ROUTES = ['/messages/map', '/messages/details', '/publications', '/teaser'];
    for (let i = 0; i < 2000; i++) {
      const pairs = (prefix: string) =>
        Array.from({ length: rng.int(1, 3) }, (_, n) => [`${prefix}${n}`, rng.pick(VALUES)]);
      const outer = pairs('o');
      const inner = pairs('i');
      const route = rng.pick(ROUTES);
      const tail = rng.pick(['', '#top', '#?t=1']);
      const address = {
        pathname: '/',
        search: `?${new URLSearchParams(outer).toString()}`,
        hash: `#${route}?${new URLSearchParams(inner).toString()}${tail}`,
      };
      const moved = hashUrlTarget(address) ?? '';
      const parsed = new URL(moved, 'https://info.example');
      const why = `${JSON.stringify(address)} → ${moved}`;
      expect(parsed.pathname, why).toBe(route);
      for (const [name, value] of [...outer, ...inner]) {
        expect(parsed.searchParams.get(name), why).toBe(value);
      }
      expect(parsed.hash, why).toBe(tail === '#top' ? '#top' : '');
      if (tail === '#?t=1') expect(parsed.searchParams.get('t'), why).toBe('1');
    }
  });
});
