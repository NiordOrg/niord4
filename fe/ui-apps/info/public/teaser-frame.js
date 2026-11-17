/**
 * The teaser frame document's script. The server gives a frame that asks for the site's root the
 * teaser frame document instead of the application; `ui-apps/info/nginx/default.conf.template`
 * says when.
 *
 * A page that embeds the teaser by the address of the hash-routed site this one replaces —
 * `/#/teaser?lang=da` — asks for the root, because a browser never sends the fragment. The server
 * cannot tell that frame from one that would show the rest of the site, which another site may not
 * frame, so every frame that asks for the root gets a document that shows nothing of its own. This
 * script moves the frame on to the teaser's own address when the fragment names the teaser, and
 * leaves it empty when the fragment names anything else.
 *
 * The fragment is read by the rules the application reads it by: `hashUrlTarget` in
 * `ui-apps/info/src/app/routes/hash-url.ts`. The build bundles that function into the
 * application's own chunks, which a document without the application cannot load, so this file
 * carries a copy, and `scripts/info-teaser-frame.test.mjs` holds the two to the same answers.
 */

/** The paths the teaser answers on: the one page another site may show in a frame. */
const TEASER_PATH = /^\/teaser\/?$/;

/** A copy of the application's `hashUrlTarget`; its own file says what it does and why. */
export function hashUrlTarget(address) {
  let { pathname, search, hash } = address;
  let rewritten = false;

  if (hash.startsWith('#/')) {
    let moved;
    try {
      moved = new URL(hash.slice(1), 'http://route.invalid');
    } catch {
      return null;
    }
    const outer = search;
    ({ pathname, search, hash } = moved);
    pathname = '/' + pathname.replace(/^\/+/, '');
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

/** The teaser's address the fragment of a frame at the root names, or null for any other. */
export function teaserFrameTarget(address) {
  const target = hashUrlTarget(address);
  if (target === null) return null;
  return TEASER_PATH.test(new URL(target, 'http://route.invalid').pathname) ? target : null;
}

// Guarded, so that a test can import the two functions outside a browser.
if (typeof location === 'object') {
  const target = teaserFrameTarget(location);
  if (target !== null) location.replace(target);
}
