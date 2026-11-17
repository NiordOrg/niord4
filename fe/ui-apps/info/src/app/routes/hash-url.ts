/** The parts of an address the site's router reads. */
export interface AddressParts {
  readonly pathname: string;
  readonly search: string;
  readonly hash: string;
}

/** The paths the teaser answers on: the one page another site may show in a frame. */
const TEASER_PATH = /^\/teaser\/?$/;

/**
 * The address a link written for the hash-routed site this one replaces has here, or `null` when
 * the address needs no change.
 *
 * Two shapes are read. The whole route in the fragment — `/#/messages/map` — becomes the path,
 * `/messages/map`; a query the address had before the fragment is joined after the route's own,
 * so `/?lang=da#/messages/map?area=7` becomes `/messages/map?area=7&lang=da`. A query written after
 * the fragment mark — `/teaser#?lang=da&area=x`, the form the pages that embed the teaser use —
 * becomes the query, `/teaser?lang=da&area=x`, joined to one the address already has. A route in
 * the fragment can itself end in such a query, so the second rule reads what the first produced.
 *
 * It runs before the application starts, so the router only ever sees the rewritten address. A
 * fragment is anybody's to write, so the result is always a path on this site, and a fragment
 * that is no address at all is left where it is rather than stopping the start. An address on the
 * teaser is never moved to another page: the teaser is the one page another site may frame, and
 * such a frame must not be able to show the rest of the site by the fragment it names.
 *
 * The teaser frame document's script, `ui-apps/info/public/teaser-frame.js`, carries a copy of
 * this function, which `scripts/info-teaser-frame.test.mjs` holds to the same answers.
 */
export function hashUrlTarget(address: AddressParts): string | null {
  let { pathname, search, hash } = address;
  let rewritten = false;

  if (hash.startsWith('#/')) {
    // The route and whatever followed it are read as the browser reads an address: a `?` in
    // the fragment starts the query, a second `#` starts a fragment of its own.
    let moved: URL;
    try {
      moved = new URL(hash.slice(1), 'http://route.invalid');
    } catch {
      return null;
    }
    const outer = search;
    ({ pathname, search, hash } = moved);
    // A dot segment can leave the path starting with two slashes, which a browser reads as
    // another host; one slash keeps it a path on this site.
    pathname = '/' + pathname.replace(/^\/+/, '');
    // The query before the fragment joins the route's own, ahead of a fragment the route has.
    if (outer.length > 1) search = search ? `${search}&${outer.slice(1)}` : outer;
    rewritten = true;
  }

  if (hash.startsWith('#?')) {
    const query = hash.slice(2);
    search = search ? `${search}&${query}` : `?${query}`;
    hash = '';
    rewritten = true;
  }

  return rewritten && (!TEASER_PATH.test(address.pathname) || TEASER_PATH.test(pathname))
    ? pathname + search + hash
    : null;
}
