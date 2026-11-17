/**
 * The include list of the base product's source zip, and the small glob matcher it is written in.
 *
 * `zip-include.json` beside the kit is `{ format, note, include, exclude }`: a path as the zip
 * names it (relative to fe/; a file of the kit beside fe/ under country-kit/), written with `/`,
 * is in the zip when an `include` glob matches it and no `exclude` glob does. Three readers use
 * the globs, so all three select the same files: the zip build (source-zip.mjs build), its
 * verifier (source-zip.mjs verify) and set-up's local `NIORD_BASE` copy of a checkout. The publish
 * workflow runs build, verify and the country-word guard (source-zip.mjs guard) on the archive it
 * deploys; the guard's allow-list globs are this file's globs too (lib/country-words.mjs).
 *
 * The same reader reads `kit-include.json` beside it, the list of the kit's own package,
 * `org.niord:niord-country-kit`, in the same format; its paths are relative to the kit's folder
 * (source-zip.mjs build, verify and guard with `--package kit`).
 *
 * The globs know four things and nothing else:
 *
 *   **      any number of whole path segments, none included (`a/**` is everything under a/)
 *   *       any run of characters inside one segment
 *   ?       one character inside one segment
 *   {a,b}   either alternative; an alternative may hold `/` and the other wildcards, not braces
 *
 * A dot is an ordinary character, so `*` matches `.npmrc` and `**` enters `.husky/`. Matching is
 * case-sensitive, as the zip's paths are.
 *
 * The base product keeps an equal copy of the matcher in scripts/glob.mjs for build-urls-check.mjs,
 * so no file of the base product imports the kit, and a test holds the two equal.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const KIT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const INCLUDE_FILE = path.join(KIT, 'zip-include.json');
export const INCLUDE_FORMAT = 1;

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

/**
 * An include list as a predicate over relative paths. `list` is `{ include, exclude }`, each an
 * array of globs relative to fe/.
 */
export function compileList(list) {
  const include = list.include.map(globToRegExp);
  const exclude = (list.exclude ?? []).map(globToRegExp);
  return (relative) =>
    include.some((re) => re.test(relative)) && !exclude.some((re) => re.test(relative));
}

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const LIST_FIELDS = ['exclude', 'format', 'include', 'note'];

/** Reads and checks an include list: its fields, its format and that every glob compiles. */
export function readIncludeList(file = INCLUDE_FILE) {
  const label = path.basename(file);
  let list;
  try {
    list = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    throw new Error(`${label}: ${error.message}`, { cause: error });
  }
  if (!isObject(list)) throw new Error(`${label} is not an object`);
  const unknown = Object.keys(list).filter((key) => !LIST_FIELDS.includes(key));
  if (unknown.length) throw new Error(`${label} has the unknown field(s) ${unknown.join(', ')}`);
  if (list.format !== INCLUDE_FORMAT)
    throw new Error(`${label} is not of format ${INCLUDE_FORMAT}`);
  for (const field of ['include', 'exclude'])
    if (
      !Array.isArray(list[field]) ||
      !list[field].every((glob) => typeof glob === 'string' && glob)
    )
      throw new Error(`${label}: ${field} is not a list of globs`);
  if (!list.include.length) throw new Error(`${label} includes nothing`);
  try {
    compileList(list);
  } catch (error) {
    throw new Error(`${label}: ${error.message}`, { cause: error });
  }
  return list;
}
