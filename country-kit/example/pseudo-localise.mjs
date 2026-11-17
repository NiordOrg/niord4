#!/usr/bin/env node
/**
 * Writes the example country's pack `zz` — Zedish, an invented language — from the base product's
 * English text: both applications' bundles, the backend half and the status file, every catalog
 * key translated, and the backend's `web` with the pack's own name, `lang.zz`, so the pack check
 * passes and a reader of the example sees at a glance which text came through the pack.
 *
 *   node ../country-kit/example/pseudo-localise.mjs --out <pack folder>   # run from the base product's fe/
 *
 * Each text is pseudo-localised: its letters accented and the whole put in brackets,
 * `Save area` → `[Šávé áŕéá]`, so a text cut short or never translated stands out. Nothing a
 * program reads is touched: `{{ name }}` placeholders, MessageFormat's `{0}` and `{0,number}`
 * arguments and its quoted runs (`'…'`, in the backend's texts), HTML tags with their attributes,
 * entities, and `%s`-style conversions are kept as they are. A public-site date pattern
 * (`*_DATE_FORMAT`) is kept whole, a `DATE_*` list keeps its entries, unbracketed, and an empty
 * English text stays empty. `status/fingerprints.json` records the English each text was made
 * from, so every text counts as accepted; there is no `unaccepted.json`.
 *
 * Only the generated folders are replaced (`i18n/`, `backend/`, `status/`); `pack.json`, the flag,
 * its drawing and `ATTRIBUTION.txt` are the pack's own.
 *
 * The harness also writes the zz site title, SITE_TITLE as the public site's bundle has it, into
 * the example's page, info/app/index.html (pseudoLocalise's `index`), so the page check of
 * check-build passes and the committed page, which holds the same title, cannot drift from the
 * bundle: the example's test fails while the two differ.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  backendBundleFiles,
  backendPlaceholders,
  decodeProperties,
  markupOf,
  parseProperties,
} from '../catalog.mjs';
import { where } from '../lib/where.mjs';
import { APPS, fingerprint, flatten } from '../stage-languages.mjs';

const FE = where().base;

// Each letter and its accented stand-in; a letter not listed is kept.
const LETTERS = 'abcdefghijklnorstuwyzACDEGHILNOSTUYZ';
const ACCENTED = 'áƀçðéƒĝĥíĵķłñóŕšţúŵýžÁÇÐÉĜĤÍŁÑÓŠŢÚÝŽ';
const ACCENTS = Object.fromEntries([...LETTERS].map((c, i) => [c, [...ACCENTED][i]]));

/**
 * The spans of a text a program reads, left as they are: `{{ … }}`, a `{…}` group with its nested
 * groups, a tag, an entity, a `%s`-style conversion and, with `quotes`, a MessageFormat quoted run.
 */
function protectedSpans(text, { quotes }) {
  const spans = [];
  for (let i = 0; i < text.length; ) {
    const rest = text.slice(i);
    let length = 0;
    if (rest.startsWith('{')) {
      let depth = 0;
      for (let j = 0; j < rest.length; j++) {
        if (rest[j] === '{') depth++;
        else if (rest[j] === '}' && --depth === 0) {
          length = j + 1;
          break;
        }
      }
      if (!length) length = rest.length;
    } else if (rest.startsWith('<')) {
      const end = rest.indexOf('>');
      length = end < 0 ? 1 : end + 1;
    } else if (/^&(#\d+|#x[0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);/.test(rest)) {
      length = rest.indexOf(';') + 1;
    } else if (/^%[-#+ 0-9.]*[sdfxX%]/.test(rest)) {
      length = rest.match(/^%[-#+ 0-9.]*[sdfxX%]/)[0].length;
    } else if (quotes && rest.startsWith("'")) {
      const end = rest.indexOf("'", 1);
      length = end < 0 ? 1 : end + 1;
    }
    if (length) {
      spans.push([i, i + length]);
      i += length;
    } else i++;
  }
  return spans;
}

/** A text's letters accented outside the protected spans. */
export function accent(text, { quotes = false } = {}) {
  let out = '';
  let at = 0;
  const swap = (part) => [...part].map((c) => ACCENTS[c] ?? c).join('');
  for (const [start, end] of protectedSpans(text, { quotes })) {
    out += swap(text.slice(at, start)) + text.slice(start, end);
    at = end;
  }
  return out + swap(text.slice(at));
}

/** One text of an application or the backend, pseudo-localised. */
export function pseudo(text, { key = '', app = '', quotes = false } = {}) {
  if (text.trim() === '') return text;
  if (app === 'info' && key.endsWith('_DATE_FORMAT')) return text;
  if (app === 'info' && key.startsWith('DATE_') && text.includes('|'))
    return text
      .split('|')
      .map((word) => accent(word, { quotes }))
      .join('|');
  return `[${accent(text, { quotes })}]`;
}

/** A bundle with every text pseudo-localised, its shape and key order kept. */
function pseudoBundle(bundle, app, trail = []) {
  return Object.fromEntries(
    Object.entries(bundle).map(([key, value]) => [
      key,
      typeof value === 'string'
        ? pseudo(value, { key: [...trail, key].join('.'), app })
        : pseudoBundle(value, app, [...trail, key]),
    ]),
  );
}

const ESCAPES = { '\\': '\\\\', '\n': '\\n', '\r': '\\r', '\t': '\\t', '\f': '\\f' };

/**
 * A `.properties` value as java.util.Properties stores it: every UTF-16 unit outside printable
 * ASCII as \uXXXX, and the characters the format reads escaped.
 */
function propertiesValue(value) {
  let out = '';
  for (let i = 0; i < value.length; i++) {
    const c = value[i];
    const code = value.charCodeAt(i);
    if (ESCAPES[c]) out += ESCAPES[c];
    else if (c === ' ' && i === 0) out += '\\ ';
    else if ('#!=:'.includes(c)) out += `\\${c}`;
    else if (code > 0x7e || code < 0x20) out += `\\u${code.toString(16).padStart(4, '0')}`;
    else out += c;
  }
  return out;
}

const propertiesKey = (key) => key.replace(/([\\ =:#!])/g, '\\$1');
const same = (a, b) => a.length === b.length && a.every((item, i) => item === b[i]);

/**
 * A stand-in text with a catalog entry's placeholders and no markup, for a backend key whose
 * English text is not at hand: `[Ẑẑẑ {0} {{name}}]`, or empty where English's is.
 */
export function standIn(entry) {
  if (entry.empty) return '';
  const names = entry.placeholders.map((name) => (/^\{\d+\}$/.test(name) ? name : `{{${name}}}`));
  return `[Ẑẑẑ${names.map((name) => ` ${name}`).join('')}]`;
}

/**
 * The backend texts of one bundle as `catalog` (a catalog of the version the pack is for) lists
 * them, from `english`, the bundle's English texts at hand (key → text): a key whose English text
 * has the entry's placeholders and markup is pseudo-localised from it, a key without markup gets a
 * stand-in text (standIn), and a key with markup and no such text is left out and returned in
 * `skipped`, for the escape hatch. Keys the catalog lacks are left out.
 */
export function backendFromCatalog(entries, english) {
  const texts = new Map();
  const skipped = [];
  for (const [key, entry] of Object.entries(entries)) {
    const text = english.get(key);
    if (
      text !== undefined &&
      same(backendPlaceholders(text), entry.placeholders) &&
      same(markupOf(text), entry.markup ?? []) &&
      (text.trim() === '') === Boolean(entry.empty)
    )
      texts.set(key, pseudo(text, { quotes: true }));
    else if (entry.markup?.length) skipped.push(key);
    else texts.set(key, standIn(entry));
  }
  return { texts, skipped };
}

/**
 * Writes the pack's generated folders into `out` from the base product tree whose fe/ and be/
 * folders are given. Returns the number of texts written per application and backend bundle
 * (`counts`) and the backend keys left out (`skipped`, `[{ bundle, key }]`).
 *
 * With `catalog`, the pack is for that catalog's version, not for the tree's: the upgrade test
 * sets up the example of a previous release, whose English the zip carries for both applications
 * (`fe` is then that zip's English pack) but not for the backend, so the backend half is made from
 * the catalog — every key it lists, with its placeholders, and the catalog's fingerprints. A key
 * the tree's English still has in the same form is pseudo-localised from it; a key removed, renamed
 * or given other placeholders since gets a stand-in text with the catalog's placeholders; a key
 * with markup and no such text cannot be made up, and is returned in `skipped` for the escape
 * hatch.
 *
 * With `index`, the path of a country's info/app/index.html, that page's title is replaced with
 * the public site's zz SITE_TITLE, as the bundle has it (writeIndexTitle).
 */
export function pseudoLocalise({
  fe = FE,
  be = path.resolve(fe, '..', 'be'),
  out,
  catalog,
  index,
}) {
  const counts = {};
  const fingerprints = {};
  const skipped = [];
  for (const folder of ['i18n', 'backend', 'status'])
    fs.rmSync(path.join(out, folder), { recursive: true, force: true });

  for (const app of APPS) {
    const english = JSON.parse(
      fs.readFileSync(path.join(fe, 'languages', 'en', 'i18n', app, 'en.json'), 'utf8'),
    );
    const bundle = pseudoBundle(english, app);
    const file = path.join(out, 'i18n', app, 'zz.json');
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, `${JSON.stringify(bundle, null, 2)}\n`);
    if (index && app === 'info') {
      const title = flatten(bundle).SITE_TITLE;
      if (title === undefined) throw new Error("the public site's English has no SITE_TITLE");
      writeIndexTitle(index, title);
    }
    const flat = flatten(english);
    fingerprints[app] = Object.fromEntries(
      Object.entries(flat).map(([key, text]) => [key, fingerprint(text)]),
    );
    counts[app] = Object.keys(flat).length;
  }

  const { files, problems } = backendBundleFiles(be);
  if (problems.length) throw new Error(problems.join('; '));
  fingerprints.backend = {};
  const bundles = catalog ? Object.keys(catalog.backend ?? {}) : Object.keys(files);
  for (const bundle of bundles) {
    const english = new Map();
    if (files[bundle])
      for (const { key, value } of parseProperties(
        decodeProperties(fs.readFileSync(files[bundle])),
      ))
        english.set(key, value);
    let texts;
    if (catalog) {
      const made = backendFromCatalog(catalog.backend[bundle], english);
      texts = made.texts;
      skipped.push(...made.skipped.map((key) => ({ bundle, key })));
      fingerprints.backend[bundle] = Object.fromEntries(
        [...texts.keys()].map((key) => [key, catalog.backend[bundle][key].fp]),
      );
    } else {
      texts = new Map([...english].map(([key, text]) => [key, pseudo(text, { quotes: true })]));
      fingerprints.backend[bundle] = Object.fromEntries(
        [...english].map(([key, text]) => [key, fingerprint(text)]),
      );
    }
    // The pack's own name, which no catalog lists: a draft print names a language by it.
    if (bundle === 'web') texts.set('lang.zz', pseudo('Zedish'));
    const lines = [
      `# ${bundle}: the example's Zedish, generated by country-kit/example/pseudo-localise.mjs`,
    ];
    for (const [key, value] of texts) lines.push(`${propertiesKey(key)}=${propertiesValue(value)}`);
    const file = path.join(out, 'backend', `${bundle}.properties`);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, `${lines.join('\n')}\n`);
    counts[`backend ${bundle}`] = texts.size;
  }

  const status = path.join(out, 'status', 'fingerprints.json');
  fs.mkdirSync(path.dirname(status), { recursive: true });
  fs.writeFileSync(status, `${JSON.stringify(fingerprints, null, 2)}\n`);
  return { counts, skipped };
}

const HTML_ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;' };

/**
 * Replaces the text of the one `<title>…</title>` of the page `file` with `title`, its `&`, `<`
 * and `>` escaped; no other byte changes. A page without exactly one title is refused.
 */
export function writeIndexTitle(file, title) {
  const html = fs.readFileSync(file, 'utf8');
  const count = html.match(/<title>[\s\S]*?<\/title>/g)?.length ?? 0;
  if (count !== 1) throw new Error(`${file} has ${count} <title> elements, not one`);
  const escaped = title.replace(/[&<>]/g, (c) => HTML_ESCAPES[c]);
  fs.writeFileSync(
    file,
    html.replace(/<title>[\s\S]*?<\/title>/, () => `<title>${escaped}</title>`),
  );
}

const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

if (invokedDirectly) {
  const args = process.argv.slice(2);
  const outIndex = args.indexOf('--out');
  if (outIndex < 0 || !args[outIndex + 1] || args.length !== 2) {
    console.error('pseudo-localise: --out <pack folder> is required, and nothing else');
    process.exit(2);
  }
  try {
    const { counts } = pseudoLocalise({ out: path.resolve(args[outIndex + 1]) });
    console.log(
      `pseudo-localise: zz written, ${Object.entries(counts)
        .map(([what, n]) => `${what} ${n}`)
        .join(', ')}`,
    );
  } catch (error) {
    console.error(`pseudo-localise: ${error.message}`);
    process.exit(1);
  }
}
