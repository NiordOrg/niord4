#!/usr/bin/env node
/**
 * The language catalog: every key of the base product's English text, as the data a language
 * pack is checked against (country-kit/pack.mjs).
 *
 *   node ../country-kit/catalog.mjs                       # writes .niord/catalog.json, run from fe/
 *   node ../country-kit/catalog.mjs --out <file> --version <v>
 *
 * Read from the `en` pack (`languages/en/`), niord-base's English backend bundles and the public
 * site's date renderer, and written with every object's keys sorted, so the same tree gives the
 * same bytes:
 *
 *   format          1
 *   version         the base product version it describes: `tree` for a working tree, the
 *                   published version when the publish workflow writes it
 *   apps            per application, `admin` and `info`, every key as a flat dotted name:
 *                     fp            the fingerprint of its English text (stage-languages.mjs)
 *                     placeholders  the names of its `{{ name }}` placeholders, sorted
 *                     empty         true when the English text is empty or blank, so a
 *                                   translation may be too
 *                     markup        the English text's markup — tag names, attribute names and
 *                                   every href and src — when it has any (see markupOf)
 *                     pattern       true for a public-site date pattern, `*_DATE_FORMAT`
 *                     list          for a public-site `DATE_*` list of words, its entry count
 *   backend         per bundle of niord-base (web, message, pdf, mail, template), every key with
 *                   fp, placeholders (`{{name}}` names and MessageFormat's `{n}`), empty and
 *                   markup, as for an application
 *   renamedFrom     per application, and per backend bundle, new key → old key, from
 *                   `languages/en/renames.json`, where the change that renames a key names it
 *   rules           datePatternTokens: the tokens the public site's date renderer knows, from
 *                   `ui-apps/info/src/app/shared/utils/date-pattern-tokens.json`, the file the
 *                   renderer itself imports
 *
 * The backend bundles are read wherever they are:
 * `be/niord/src/main/resources/defaults/base/texts/en/<bundle>.properties`, where the backend
 * keeps its English text, or the legacy root-level
 * `be/niord/src/main/resources/<bundle>_en.properties`, still read for a tree from before the
 * backend moved them; a bundle found in both places, or in neither, is an error. They are
 * read as niord-base reads a bundle, `Properties.load` over a UTF-8 reader: java.util.Properties'
 * syntax, the bytes as UTF-8 and a byte-order mark as part of the first key. A file that is not
 * valid UTF-8, or that starts with a byte-order mark, is refused rather than read otherwise than
 * the backend reads it (see decodeProperties).
 *
 * The English text itself is held to the rules a translation is held to, so a rule that would
 * fail every pack fails here first: a date pattern with a token the renderer does not know, a
 * `DATE_*` list with an empty entry, a key `__proto__`, `prototype` or `constructor`, and a
 * renames.json entry whose new key the catalog lacks or whose old key it still has.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  APPS,
  fingerprint,
  flatten,
  isBlank,
  packFile,
  placeholders,
  readPack,
} from './stage-languages.mjs';
import { where } from './lib/where.mjs';

const { base: FE, be: BE } = where();

export const FORMAT = 1;
export const BACKEND_BUNDLES = ['web', 'message', 'pdf', 'mail', 'template'];
const RESOURCES = 'niord/src/main/resources';
const TOKENS_FILE = 'ui-apps/info/src/app/shared/utils/date-pattern-tokens.json';
const FORBIDDEN_KEYS = new Set(['__proto__', 'prototype', 'constructor']);

// The element names of HTML, current and obsolete, and of the SVG and MathML roots: a `<` and a
// name from this list is markup, any other bare name is text (`<none>`, `<ingen>`). A custom
// element's name has a hyphen, and is markup too, as is any tag with an attribute: the mail and
// PDF HTML the backend makes has no sanitiser to drop `<foo onclick=…>`.
const HTML_ELEMENTS = new Set(
  `a abbr acronym address applet area article aside audio b base basefont bdi bdo bgsound big
  blink blockquote body br button canvas caption center cite code col colgroup data datalist dd
  del details dfn dialog dir div dl dt em embed fieldset figcaption figure font footer form frame
  frameset h1 h2 h3 h4 h5 h6 head header hgroup hr html i iframe image img input ins isindex kbd
  keygen label legend li link listing main map mark marquee math menu menuitem meta meter multicol
  nav nextid nobr noembed noframes noscript object ol optgroup option output p param picture
  plaintext pre progress q rb rp rt rtc ruby s samp script search section select slot small
  source spacer span strike strong style sub summary sup svg table tbody td template textarea
  tfoot th thead time title tr track tt u ul var video wbr xmp`.split(/\s+/),
);
const TAG =
  /<(\/?)([A-Za-z][\w-]*)((?:\s+[^\s"'>/=]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s"'=<>`]+))?)*)\s*\/?>/g;
const ATTRIBUTE = /([^\s"'>/=]+)(?:\s*=\s*("[^"]*"|'[^']*'|[^\s"'=<>`]+))?/g;
const URL_ATTRIBUTES = new Set(['href', 'src']);

/**
 * A text's markup as a sorted list: `<name>` and `</name>` for every element tag, `@name` for
 * every attribute and `href=<value>` / `src=<value>` for every link and source. Empty when the
 * text has none. Two texts carry the same markup when their lists are equal: the same tags,
 * attributes and links, each as often, in any order a translation's word order needs.
 */
export function markupOf(text) {
  const found = [];
  for (const [, closing, rawName, attributes] of text.matchAll(TAG)) {
    const name = rawName.toLowerCase();
    if (!HTML_ELEMENTS.has(name) && !name.includes('-') && !attributes.trim()) continue;
    found.push(closing ? `</${name}>` : `<${name}>`);
    for (const [, rawAttribute, rawValue] of attributes.matchAll(ATTRIBUTE)) {
      const attribute = rawAttribute.toLowerCase();
      found.push(`@${attribute}`);
      if (URL_ATTRIBUTES.has(attribute))
        found.push(`${attribute}=${(rawValue ?? '').replace(/^(["'])(.*)\1$/s, '$2')}`);
    }
  }
  return found.sort();
}

/** The letter runs of a date pattern outside its quoted runs: the tokens the renderer reads. */
export function patternTokens(pattern) {
  return pattern.replace(/'[^']*'/g, ' ').match(/[A-Za-z]+/g) ?? [];
}

/** A backend text's placeholders: `{{name}}` names and MessageFormat's `{n}`, sorted. */
export function backendPlaceholders(text) {
  const formats = [...text.replace(/\{\{[^}]*\}\}/g, '').matchAll(/\{\s*(\d+)\s*(?:,[^{}]*)?\}/g)];
  return [...new Set([...placeholders(text), ...formats.map((match) => `{${match[1]}}`)])].sort();
}

/**
 * A `.properties` file's entries as java.util.Properties reads them: `#` and `!` comment lines,
 * `=`, `:` or white space between key and value, a line ending in an odd number of backslashes
 * continued on the next with its leading white space dropped, `\uXXXX` and `\t \n \r \f`
 * escapes, any line ending. Returns `{ key, value, first, last }` in file order, `first` and
 * `last` the zero-based lines the entry spans; a key given twice is listed twice, and the last
 * one is the one Java keeps.
 */
export function parseProperties(text) {
  const lines = text.split(/\r\n|\r|\n/);
  const entries = [];
  const blank = (c) => c === ' ' || c === '\t' || c === '\f';
  for (let i = 0; i < lines.length; ) {
    const first = i;
    let line = lines[i++].replace(/^[ \t\f]+/, '');
    if (line === '' || line[0] === '#' || line[0] === '!') continue;
    let logical = '';
    for (;;) {
      const backslashes = line.match(/\\*$/)[0].length;
      if (backslashes % 2 === 0) {
        logical += line;
        break;
      }
      logical += line.slice(0, -1);
      if (i >= lines.length) break;
      line = lines[i++].replace(/^[ \t\f]+/, '');
    }
    let end = 0;
    while (end < logical.length) {
      const c = logical[end];
      if (c === '\\') end += 2;
      else if (c === '=' || c === ':' || blank(c)) break;
      else end++;
    }
    let start = Math.min(end, logical.length);
    while (start < logical.length && blank(logical[start])) start++;
    if (logical[start] === '=' || logical[start] === ':') {
      start++;
      while (start < logical.length && blank(logical[start])) start++;
    }
    entries.push({
      key: unescapeProperty(logical.slice(0, Math.min(end, logical.length)), first),
      value: unescapeProperty(logical.slice(start), first),
      first,
      last: i - 1,
    });
  }
  return entries;
}

function unescapeProperty(raw, line) {
  let out = '';
  for (let j = 0; j < raw.length; j++) {
    if (raw[j] !== '\\') {
      out += raw[j];
      continue;
    }
    const escape = raw[++j];
    if (escape === undefined) break;
    if (escape === 'u') {
      const hex = raw.slice(j + 1, j + 5);
      if (!/^[0-9a-fA-F]{4}$/.test(hex))
        throw new Error(`line ${line + 1}: a malformed \\uxxxx escape`);
      out += String.fromCharCode(parseInt(hex, 16));
      j += 4;
    } else {
      out += { t: '\t', n: '\n', r: '\r', f: '\f' }[escape] ?? escape;
    }
  }
  return out;
}

/**
 * A `.properties` file's bytes as text, decoded as UTF-8. niord-base reads a bundle through a
 * UTF-8 reader that turns a malformed byte into U+FFFD and keeps a byte-order mark as part of the
 * first key, so either is refused here: the message follows the file's name.
 */
export function decodeProperties(bytes) {
  let text;
  try {
    text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes);
  } catch {
    throw new Error('is not valid UTF-8, the encoding niord-base reads it in');
  }
  if (text.startsWith('\uFEFF'))
    throw new Error(
      'starts with a byte-order mark, which niord-base reads as part of its first key',
    );
  return text;
}

/** A `.properties` file as key → text, the last of a key given twice winning, as in Java. */
export function readProperties(file) {
  const map = new Map();
  for (const { key, value } of parseProperties(decodeProperties(fs.readFileSync(file))))
    map.set(key, value);
  return map;
}

/** Where each of niord-base's English bundles is, under the backend folder `be`. */
export function backendBundleFiles(be) {
  const files = {};
  const problems = [];
  for (const bundle of BACKEND_BUNDLES) {
    const places = [
      `${RESOURCES}/${bundle}_en.properties`,
      `${RESOURCES}/defaults/base/texts/en/${bundle}.properties`,
    ].filter((relative) => fs.existsSync(path.join(be, relative)));
    if (places.length === 1) files[bundle] = path.join(be, places[0]);
    else if (places.length === 0)
      problems.push(
        `be/: no English ${bundle} bundle, neither ${RESOURCES}/${bundle}_en.properties nor ${RESOURCES}/defaults/base/texts/en/${bundle}.properties`,
      );
    else
      problems.push(
        `be/: the English ${bundle} bundle is in two places, ${places.join(' and ')}; it is one or the other`,
      );
  }
  return { files, problems };
}

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

/** A copy of `value` with every object's keys sorted; arrays keep their order. */
function sortKeys(value) {
  if (Array.isArray(value)) return value.map(sortKeys);
  if (!isObject(value)) return value;
  return Object.fromEntries(
    Object.keys(value)
      .sort()
      .map((key) => [key, sortKeys(value[key])]),
  );
}

/** The catalog as the bytes `catalog.json` holds. */
export function serializeCatalog(catalog) {
  return `${JSON.stringify(sortKeys(catalog), null, 2)}\n`;
}

/** `renames.json`, checked for its shape: `{ <app>: {new: old}, backend: { <bundle>: {new: old} } }`. */
function readRenames(enDir, problems) {
  const empty = { ...Object.fromEntries(APPS.map((app) => [app, {}])), backend: {} };
  const file = packFile(enDir, 'renames.json');
  if (!file) return empty;
  let renames;
  try {
    renames = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    problems.push(`languages/en/renames.json: ${error.message}`);
    return empty;
  }
  if (!isObject(renames)) {
    problems.push('languages/en/renames.json is not an object');
    return empty;
  }
  const pairs = (section, label) => {
    if (!isObject(section)) {
      problems.push(`languages/en/renames.json: ${label} is not an object of new key → old key`);
      return {};
    }
    const out = {};
    for (const [newKey, oldKey] of Object.entries(section)) {
      if (typeof oldKey !== 'string' || !oldKey)
        problems.push(`languages/en/renames.json: ${label} "${newKey}" names no old key`);
      else out[newKey] = oldKey;
    }
    return out;
  };
  const result = structuredClone(empty);
  for (const [section, value] of Object.entries(renames)) {
    if (APPS.includes(section)) result[section] = pairs(value, section);
    else if (section === 'backend' && isObject(value)) {
      for (const [bundle, bundleRenames] of Object.entries(value)) {
        if (!BACKEND_BUNDLES.includes(bundle))
          problems.push(
            `languages/en/renames.json: backend names the bundle "${bundle}", not one of ${BACKEND_BUNDLES.join(', ')}`,
          );
        else result.backend[bundle] = pairs(bundleRenames, `backend.${bundle}`);
      }
    } else
      problems.push(
        `languages/en/renames.json has "${section}"; its sections are ${[...APPS, 'backend'].join(', ')}`,
      );
  }
  return result;
}

function checkRenames(renames, keys, label, problems) {
  for (const [newKey, oldKey] of Object.entries(renames)) {
    if (!Object.hasOwn(keys, newKey))
      problems.push(
        `languages/en/renames.json: ${label} renames to "${newKey}", which English does not have`,
      );
    if (Object.hasOwn(keys, oldKey))
      problems.push(
        `languages/en/renames.json: ${label} renames "${oldKey}", which English still has`,
      );
  }
}

/**
 * The catalog of the tree whose `fe/` and `be/` folders are given. Throws, naming every problem,
 * when the English text breaks one of the catalog's rules.
 */
export function buildCatalog({ fe = FE, be = BE, version = 'tree' } = {}) {
  const problems = [];
  const enDir = path.join(fe, 'languages', 'en');
  readPack(enDir);

  let datePatternTokens = [];
  try {
    datePatternTokens = JSON.parse(fs.readFileSync(path.join(fe, TOKENS_FILE), 'utf8'));
  } catch (error) {
    problems.push(`${TOKENS_FILE}: ${error.message}`);
  }
  if (
    !Array.isArray(datePatternTokens) ||
    !datePatternTokens.every((token) => typeof token === 'string' && /^[A-Za-z]+$/.test(token))
  ) {
    problems.push(`${TOKENS_FILE} is not a list of letter tokens`);
    datePatternTokens = [];
  }

  const apps = {};
  for (const app of APPS) {
    const label = `languages/en/i18n/${app}/en.json`;
    const file = packFile(enDir, `i18n/${app}/en.json`);
    if (!file) {
      problems.push(`${label} does not exist`);
      continue;
    }
    let flat = {};
    try {
      flat = flatten(JSON.parse(fs.readFileSync(file, 'utf8')));
    } catch (error) {
      problems.push(`${label} ${error.message}`);
    }
    const entries = {};
    for (const [key, text] of Object.entries(flat)) {
      const entry = { fp: fingerprint(text), placeholders: placeholders(text) };
      if (isBlank(text)) entry.empty = true;
      const markup = markupOf(text);
      if (markup.length) entry.markup = markup;
      if (app === 'info' && key.endsWith('_DATE_FORMAT')) {
        entry.pattern = true;
        const unknown = patternTokens(text).filter((token) => !datePatternTokens.includes(token));
        if (unknown.length)
          problems.push(
            `${label} ${key} "${text}" has the token(s) ${unknown.join(', ')}, which the date renderer does not know (${TOKENS_FILE})`,
          );
      }
      if (app === 'info' && key.startsWith('DATE_') && text.includes('|')) {
        const words = text.split('|');
        entry.list = words.length;
        if (words.some(isBlank)) problems.push(`${label} ${key} has an empty entry`);
      }
      entries[key] = entry;
    }
    apps[app] = entries;
  }

  const backend = {};
  const { files, problems: placement } = backendBundleFiles(be);
  problems.push(...placement);
  for (const [bundle, file] of Object.entries(files)) {
    const label = path.relative(path.dirname(be), file).replaceAll('\\', '/');
    let texts = new Map();
    try {
      texts = readProperties(file);
    } catch (error) {
      problems.push(`${label} ${error.message}`);
    }
    const entries = {};
    for (const [key, text] of texts) {
      if (FORBIDDEN_KEYS.has(key)) {
        problems.push(`${label} has the key "${key}"`);
        continue;
      }
      const entry = { fp: fingerprint(text), placeholders: backendPlaceholders(text) };
      if (isBlank(text)) entry.empty = true;
      const markup = markupOf(text);
      if (markup.length) entry.markup = markup;
      entries[key] = entry;
    }
    backend[bundle] = entries;
  }

  const renamedFrom = readRenames(enDir, problems);
  for (const app of APPS) checkRenames(renamedFrom[app], apps[app] ?? {}, app, problems);
  for (const [bundle, renames] of Object.entries(renamedFrom.backend))
    checkRenames(renames, backend[bundle] ?? {}, `backend.${bundle}`, problems);

  if (problems.length)
    throw new Error(
      `the English text breaks ${problems.length} of the catalog's rules:\n  ${problems.join('\n  ')}`,
    );
  return { format: FORMAT, version, apps, backend, renamedFrom, rules: { datePatternTokens } };
}

/** Writes a file through a temporary copy and a rename, and only when its bytes differ. */
export function writeIfChanged(file, text) {
  const bytes = Buffer.from(text, 'utf8');
  try {
    if (fs.readFileSync(file).equals(bytes)) return false;
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const temporary = `${file}.${process.pid}.tmp`;
  fs.writeFileSync(temporary, bytes);
  fs.renameSync(temporary, file);
  return true;
}

// Run as a script rather than imported by its test; see workspace-paths-check.mjs for why both
// sides are resolved through links.
const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

if (invokedDirectly) {
  const args = process.argv.slice(2);
  let out = path.join(FE, '.niord', 'catalog.json');
  let version = 'tree';
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--out' && args[i + 1]) out = path.resolve(args[++i]);
    else if (args[i] === '--version' && args[i + 1]) version = args[++i];
    else {
      console.error(`catalog: unknown argument "${args[i]}" (--out <file>, --version <v>)`);
      process.exit(2);
    }
  }
  try {
    const catalog = buildCatalog({ version });
    const written = writeIfChanged(out, serializeCatalog(catalog));
    const count = (section) =>
      Object.entries(section)
        .map(([name, keys]) => `${Object.keys(keys).length} ${name}`)
        .join(', ');
    console.log(
      `catalog: ${version}: ${count(catalog.apps)} key(s); backend ${count(catalog.backend)}; ${written ? 'written to' : 'unchanged in'} ${path.relative(FE, out).replaceAll('\\', '/')}`,
    );
  } catch (error) {
    console.error(`catalog: ${error.message}`);
    process.exit(1);
  }
}
