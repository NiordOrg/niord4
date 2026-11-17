/**
 * The base product's own copy of the kit's glob matcher (country-kit/lib/include.mjs), which the
 * source zip carries for build-urls-check.mjs: its allow-list's `files` are written in these
 * globs. A copy, so that no file of the base product imports the kit; scripts/glob.test.mjs holds
 * it equal to the kit's.
 *
 * The globs know four things and nothing else:
 *
 *   **      any number of whole path segments, none included (`a/**` is everything under a/)
 *   *       any run of characters inside one segment
 *   ?       one character inside one segment
 *   {a,b}   either alternative; an alternative may hold `/` and the other wildcards, not braces
 *
 * A dot is an ordinary character, so `*` matches `.npmrc` and `**` enters `.husky/`. Matching is
 * case-sensitive.
 */

/** A glob with its brace lists expanded: one glob per combination of alternatives. */
export function expandBraces(glob) {
  const open = glob.indexOf('{');
  if (open === -1) {
    if (glob.includes('}')) throw new Error(`the glob "${glob}" has a "}" without its "{"`);
    return [glob];
  }
  const close = glob.indexOf('}', open);
  if (close === -1) throw new Error(`the glob "${glob}" has a "{" without its "}"`);
  const inner = glob.slice(open + 1, close);
  if (inner.includes('{')) throw new Error(`the glob "${glob}" nests braces`);
  const head = glob.slice(0, open);
  const tail = glob.slice(close + 1);
  return inner.split(',').flatMap((alternative) => expandBraces(`${head}${alternative}${tail}`));
}

const escape = (text) => text.replace(/[.+^${}()|[\]\\]/g, '\\$&');

/** One brace-free glob as a regular expression over a whole path. */
function braceFreeToSource(glob) {
  if (!glob || glob.startsWith('/') || glob.endsWith('/') || glob.includes('\\'))
    throw new Error(`"${glob}" is not a relative glob written with "/"`);
  const segments = glob.split('/');
  let source = '';
  segments.forEach((segment, i) => {
    const last = i === segments.length - 1;
    if (segment === '**') {
      // Any number of whole segments: `a/**/b` matches a/b, `a/**` everything under a/.
      source += last ? (i === 0 ? '.*' : '.+') : '(?:[^/]+/)*';
      return;
    }
    if (segment.includes('**')) throw new Error(`"${glob}": ** stands for whole segments only`);
    if (!segment) throw new Error(`"${glob}" has an empty segment`);
    source += segment
      .split('')
      .map((c) => (c === '*' ? '[^/]*' : c === '?' ? '[^/]' : escape(c)))
      .join('');
    if (!last) source += '/';
  });
  return source;
}

/** A glob as a regular expression over a whole relative path. */
export function globToRegExp(glob) {
  const sources = expandBraces(glob).map(braceFreeToSource);
  return new RegExp(`^(?:${sources.join('|')})$`);
}
