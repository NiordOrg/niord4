#!/usr/bin/env node
/**
 * Guard against a built application that loads a font or a stylesheet from another origin.
 *
 *   node scripts/build-urls-check.mjs --dist dist/niord-app/browser    # run from fe/, after a build
 *   node scripts/build-urls-check.mjs --dist dist/niord-app/browser --absolute
 *
 * Both applications ship their fonts and styles themselves: a reader's browser asks no third
 * party for them, so it tells no third party who is reading, and a Content-Security-Policy needs
 * no font or style origin. A stylesheet that names one — a font service's `@import`, a `url()`
 * to a CDN — builds green and works, which is why this reads the built output:
 *
 *   1. no `url(...)` or `@import` to `http://`, `https://` or `//` in a `.css` file, nor in an
 *      inline `<style>` block of an `.html` file (index.html is a seam a country fills);
 *   2. no `<link href>` to `http://`, `https://` or `//` in an `.html` file;
 *   3. no `fonts.googleapis.com` or `fonts.gstatic.com` in any text file, a script included.
 *
 * `--absolute` adds the absolute-URL rule of fe-country-split-plan §4.11 rule 5, which every
 * country build runs (country-kit/check-build.mjs) and the base product's own builds are measured
 * against: no `http://`, `https://` or protocol-relative `//host` URL in a `.js`, `.css` or `.html`
 * file but those build-urls-allowed.json beside this script lists, each with its reason. A country
 * build also reads the country's own list, its fe/build-urls-allowed.json, in the same format
 * (compileCountryUrlAllowList): each of its entries is one literal https: URL with a reason, never
 * a pattern, and may name the one application whose output it admits the URL in (`app`), so a
 * country admits its own addresses one by one and the base product's list carries none of them
 * (R1). The legal documents (`assets/legal/**`) are not read by the rule: they are a country's
 * branding data, held to their element and attribute allow-list by the branding check (§4.11
 * rule 4).
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { globToRegExp } from './glob.mjs';

export const ALLOW_FILE = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  'build-urls-allowed.json',
);

const TEXT = new Set([
  '.html',
  '.htm',
  '.css',
  '.js',
  '.mjs',
  '.cjs',
  '.json',
  '.webmanifest',
  '.map',
  '.txt',
  '.svg',
  '.xml',
]);
const EXTERNAL = String.raw`(?:https?:)?\/\/`;
const HTML = /\.html?$/;
// A stylesheet's rules read a `.css` file whole, and of an `.html` file the inline <style> blocks.
const STYLES = (file) => file.endsWith('.css') || HTML.test(file);
const RULES = [
  {
    applies: STYLES,
    styles: true,
    pattern: new RegExp(String.raw`url\(\s*['"]?\s*${EXTERNAL}`, 'gi'),
    reason: 'a url() to another origin',
  },
  {
    applies: STYLES,
    styles: true,
    pattern: new RegExp(String.raw`@import\s+(?:url\(\s*)?['"]?\s*${EXTERNAL}`, 'gi'),
    reason: 'an @import from another origin',
  },
  {
    applies: (file) => HTML.test(file),
    pattern: new RegExp(String.raw`<link\b[^>]*\bhref\s*=\s*['"]?\s*${EXTERNAL}`, 'gi'),
    reason: 'a <link href> to another origin',
  },
  {
    applies: (file) => TEXT.has(path.extname(file).toLowerCase()),
    pattern: /fonts\.(?:googleapis|gstatic)\.com/gi,
    reason: "Google Fonts' address",
  },
];

/** Every file under a folder, relative to it, with `/`. */
function filesUnder(folder) {
  return fs
    .readdirSync(folder, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) =>
      path.relative(folder, path.join(entry.parentPath, entry.name)).replaceAll('\\', '/'),
    )
    .sort();
}

const STYLE_BLOCK = /<style\b[^>]*>([\s\S]*?)<\/style\s*>/gi;

/**
 * The parts of a file a rule reads, each `{ text, at }` with its offset in the file: the whole
 * file, or for a stylesheet's rule over an `.html` file the content of each <style> block.
 */
function partsFor(rule, file, text) {
  if (!rule.styles || !HTML.test(file)) return [{ text, at: 0 }];
  return [...text.matchAll(STYLE_BLOCK)].map((block) => ({
    text: block[1],
    at: block.index + block[0].indexOf('>') + 1,
  }));
}

/**
 * Checks a built output folder. Returns the errors, one line each naming the file, the line and
 * the text found, and how many text files were read.
 */
export function checkDistUrls(dist) {
  const errors = [];
  let read = 0;
  for (const file of filesUnder(dist)) {
    const rules = RULES.filter((rule) => rule.applies(file));
    if (rules.length === 0) continue;
    read++;
    const text = fs.readFileSync(path.join(dist, file), 'utf8');
    for (const rule of rules) {
      for (const part of partsFor(rule, file, text)) {
        for (const match of part.text.matchAll(rule.pattern)) {
          const index = part.at + match.index;
          const line = text.slice(0, index).split('\n').length;
          const excerpt = text
            .slice(index, index + 80)
            .split('\n')[0]
            .trim();
          errors.push(`${file}:${line}: ${rule.reason}: ${excerpt}`);
        }
      }
    }
  }
  return { errors, read };
}

// An absolute URL: a scheme's `//` and what follows up to a quote, a space, a bracket or a
// backslash, or a protocol-relative `//` that no word, colon or slash precedes and a dotted host
// follows. A scheme with nothing after it (`"https://"`, a prefix a script tests for) names no
// address and is not one.
const ABSOLUTE = new RegExp(
  String.raw`\bhttps?:\/\/[^\s"'\x60<>()\\]+|(?<![\w:/\\])\/\/[a-z0-9-]+(?:\.[a-z0-9-]+)+(?::\d+)?(?:\/[^\s"'\x60<>()\\]*)?`,
  'gi',
);
const ABSOLUTE_FILES = /\.(?:js|css|html?)$/i;
const NOT_READ = globToRegExp('assets/legal/**');
const ENTRY_FIELDS = ['files', 'pattern', 'reason'];
/** The applications a country entry may be scoped to (`app`), each as its output is named. */
export const APP_BUILDS = {
  admin: "the administration app's build",
  info: "the public site's build",
};

/** The line number of an offset in `text`, found by halving over its line starts. */
function lineIndex(text) {
  const starts = [0];
  for (let i = text.indexOf('\n'); i !== -1; i = text.indexOf('\n', i + 1)) starts.push(i + 1);
  return (offset) => {
    let low = 0;
    let high = starts.length - 1;
    while (low < high) {
      const middle = (low + high + 1) >> 1;
      if (starts[middle] <= offset) low = middle;
      else high = middle - 1;
    }
    return low + 1;
  };
}

/**
 * The allow-list's entries compiled, and every problem with them: `{ pattern, files?, reason }`,
 * where `pattern` is a regular expression's source matched against the start of the URL found and
 * `files` a glob (./glob.mjs) over the dist's paths. An entry without a reason,
 * with an unknown field or a pattern or glob that does not compile fails the check itself.
 * `entryProblems(entry)`, when given, adds a list's own rules for an entry, and `fields` are the
 * fields an entry may have. Each compiled entry keeps its index in the list, its reason and, when
 * it has one, its `app`.
 */
export function compileUrlAllowList(
  list,
  label = 'build-urls-allowed.json',
  entryProblems,
  fields = ENTRY_FIELDS,
) {
  const errors = [];
  const entries = [];
  if (!Array.isArray(list?.allow))
    return { entries, errors: [`${label}: allow is not a list`], label };
  list.allow.forEach((entry, i) => {
    const where = `${label}: allow[${i}]`;
    const before = errors.length;
    const unknown = Object.keys(entry ?? {}).filter((key) => !fields.includes(key));
    if (unknown.length) errors.push(`${where} has the unknown field(s) ${unknown.join(', ')}`);
    if (typeof entry?.reason !== 'string' || !entry.reason.trim())
      errors.push(`${where} has no reason`);
    let pattern;
    try {
      if (typeof entry?.pattern !== 'string' || !entry.pattern) throw new Error('no pattern');
      pattern = new RegExp(`^(?:${entry.pattern})`);
    } catch (error) {
      errors.push(
        `${where}: the pattern ${JSON.stringify(entry?.pattern)} is not one (${error.message})`,
      );
    }
    let files;
    try {
      if (entry?.files !== undefined) files = globToRegExp(entry.files);
    } catch (error) {
      errors.push(`${where}: ${error.message}`);
    }
    if (pattern && entryProblems)
      errors.push(...entryProblems(entry).map((problem) => `${where}: ${problem}`));
    // An entry the list gets wrong allows nothing.
    if (errors.length === before)
      entries.push({
        index: i,
        pattern,
        files,
        reason: entry.reason,
        ...(entry.app !== undefined ? { app: entry.app } : {}),
      });
  });
  return { entries, errors, label };
}

const TOP_FIELDS = ['allow', 'format', 'note'];
// The escapes a literal URL needs in a pattern; everything else that is syntax is a pattern's. No
// more: an address that needs `{`, `+` or the like is a template, not one literal URL, and a list
// that guards what a build may load names only literal addresses; such an address is a base
// product change.
const LITERAL_ESCAPES = new Set(['.', '/', '?', '-']);
const SYNTAX = new Set(['(', ')', '|', '*', '+', '[', ']', '{', '}', '^', '$', '.', '?', '\\']);
// A public host by name: letters, digits and hyphens in dotted labels, no port.
const HOST_NAME = /^[a-z0-9-]+(?:\.[a-z0-9-]+)+$/i;

/**
 * The URL a country entry's pattern admits, or why it is not one literal https: URL: the source,
 * with `\.`, `\/`, `\?` and `\-` read as the characters they escape, holds no other
 * regular-expression syntax and ends with `$`, and what it spells is an https: URL whose host is a
 * name of letters, digits, hyphens and dots — no other scheme, no protocol-relative address, no
 * wildcard, no IP address, no port and no credentials.
 */
export function literalUrl(source) {
  if (typeof source !== 'string') return { problem: 'has no pattern' };
  if (!source.endsWith('$') || source.endsWith('\\$'))
    return { problem: `the pattern ${JSON.stringify(source)} does not end with $` };
  let url = '';
  const body = source.slice(0, -1);
  for (let i = 0; i < body.length; i++) {
    const char = body[i];
    if (char === '\\' && LITERAL_ESCAPES.has(body[i + 1])) url += body[++i];
    else if (SYNTAX.has(char))
      return {
        problem: `the pattern ${JSON.stringify(source)} holds the regular-expression syntax ${JSON.stringify(char === '\\' ? body.slice(i, i + 2) : char)}: a country's entry is one literal https: URL, escaping only ., /, ? and -; an address that needs other syntax is a base product change`,
      };
    else url += char;
  }
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    return { problem: `${url} is not an absolute URL` };
  }
  if (parsed.protocol !== 'https:' || !url.toLowerCase().startsWith('https://'))
    return { problem: `${url} is not an https: URL` };
  if (parsed.username || parsed.password) return { problem: `${url} carries credentials` };
  if (!HOST_NAME.test(parsed.host) || /^[\d.]+$/.test(parsed.host) || url[8] === '/')
    return {
      problem: `${url} has the host ${parsed.host || '(none)'}: a country's entry names a host by its name, of letters, digits, hyphens and dots, with no port and no IP address`,
    };
  return { url };
}

/**
 * A country's own list, its fe/build-urls-allowed.json, compiled as compileUrlAllowList does —
 * `{ format: 1, note?, allow: [{ pattern, files?, app?, reason }] }`, an unknown field failing at
 * either level — with the country's rule on each entry: its pattern is one literal https: URL
 * ending with `$` (literalUrl). `app`, 'admin' or 'info', scopes an entry to that application's
 * output; an entry without one admits its URL in either. Each compiled entry also carries the URL
 * it admits.
 */
export function compileCountryUrlAllowList(list, label = 'fe/build-urls-allowed.json') {
  if (list === null || typeof list !== 'object' || Array.isArray(list))
    return { entries: [], errors: [`${label}: is not an object`], label };
  const errors = [];
  const unknown = Object.keys(list).filter((key) => !TOP_FIELDS.includes(key));
  if (unknown.length) errors.push(`${label} has the unknown field(s) ${unknown.join(', ')}`);
  if (list.format !== 1) errors.push(`${label}: format is ${JSON.stringify(list.format)}, not 1`);
  if (list.note !== undefined && typeof list.note !== 'string')
    errors.push(`${label}: note is not a string`);
  const compiled = compileUrlAllowList(
    list,
    label,
    (entry) => {
      const { problem } = literalUrl(entry.pattern);
      const problems = problem ? [problem] : [];
      if (entry.app !== undefined && !Object.hasOwn(APP_BUILDS, entry.app))
        problems.push(`app is ${JSON.stringify(entry.app)}, not "admin" or "info"`);
      return problems;
    },
    [...ENTRY_FIELDS, 'app'],
  );
  const entries = compiled.entries.map((entry) => ({
    ...entry,
    url: literalUrl(list.allow[entry.index].pattern).url,
  }));
  return { entries, errors: [...errors, ...compiled.errors], label };
}

/** Reads the allow-list file; a missing or unreadable one is an error of the check. */
export function readUrlAllowList(file = ALLOW_FILE) {
  try {
    return compileUrlAllowList(JSON.parse(fs.readFileSync(file, 'utf8')), path.basename(file));
  } catch (error) {
    return { entries: [], errors: [`${path.basename(file)}: ${error.message}`] };
  }
}

/**
 * The absolute-URL rule over a built output folder. `allow` is the base product's allow-list's
 * content (`{ allow: [...] }`); `country`, in a country build, the country's own list as
 * compileCountryUrlAllowList compiled it, whose problems its caller reports; `app`, 'admin' or
 * 'info', the application whose output `dist` is, so a country entry scoped to the other one
 * admits nothing here. Without `app` every country entry applies. Returns the errors, one line
 * each naming the file, the line and the URL — and the entry, when only an entry scoped to the
 * other application would have admitted it — the base list's own problems first, how many files
 * were read, and `matched`, the indexes of the country's entries that admitted a URL.
 */
export function checkAbsoluteUrls(dist, allow, country, app) {
  const { entries, errors } = compileUrlAllowList(allow);
  const matched = new Set();
  const admits = (entry, url, file) =>
    entry.pattern.test(url) && (!entry.files || entry.files.test(file));
  const inScope = (entry) => app === undefined || entry.app === undefined || entry.app === app;
  const missing = country
    ? `in neither build-urls-allowed.json nor ${country.label}`
    : 'not in build-urls-allowed.json';
  let read = 0;
  for (const file of filesUnder(dist)) {
    if (!ABSOLUTE_FILES.test(file) || NOT_READ.test(file)) continue;
    read++;
    const text = fs.readFileSync(path.join(dist, file), 'utf8');
    const lineAt = lineIndex(text);
    for (const match of text.matchAll(ABSOLUTE)) {
      const url = match[0];
      if (entries.some((entry) => admits(entry, url, file))) continue;
      const own = country?.entries.filter((entry) => admits(entry, url, file)) ?? [];
      const admitting = own.find(inScope);
      if (admitting) {
        matched.add(admitting.index);
        continue;
      }
      const line = lineAt(match.index);
      const scoped = own[0]
        ? ` (${country.label} allow[${own[0].index}] admits it in ${APP_BUILDS[own[0].app]} only)`
        : '';
      errors.push(`${file}:${line}: an absolute URL ${missing}: ${url.slice(0, 100)}${scoped}`);
    }
  }
  return { errors, read, matched };
}

// Run as a script rather than imported by its test; see workspace-paths-check.mjs for why both
// sides are resolved through links.
const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

if (invokedDirectly) {
  const args = process.argv.slice(2);
  let dist;
  let absolute = false;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--dist' && args[i + 1]) dist = args[++i];
    else if (args[i].startsWith('--dist=')) dist = args[i].slice('--dist='.length);
    else if (args[i] === '--absolute') absolute = true;
    else {
      console.error(
        `build-urls-check: unknown argument "${args[i]}" (--dist <folder> [--absolute])`,
      );
      process.exit(2);
    }
  }
  if (!dist) {
    console.error('build-urls-check: --dist <folder> names the built output to read');
    process.exit(2);
  }
  if (!fs.existsSync(dist) || !fs.statSync(dist).isDirectory()) {
    console.error(`build-urls-check: ${dist} is not a folder; build first`);
    process.exit(2);
  }
  const { errors, read } = checkDistUrls(dist);
  if (read === 0) {
    console.error(`build-urls-check: ${dist} holds no text file to read; build first`);
    process.exit(2);
  }
  if (errors.length) {
    console.error(`build-urls-check: ${errors.length} external font or style URL(s) in ${dist}:`);
    for (const e of errors) console.error('  ' + e);
  }
  let absoluteErrors = [];
  if (absolute) {
    let allow;
    try {
      allow = JSON.parse(fs.readFileSync(ALLOW_FILE, 'utf8'));
    } catch (error) {
      console.error(`build-urls-check: ${path.basename(ALLOW_FILE)}: ${error.message}`);
      process.exit(2);
    }
    absoluteErrors = checkAbsoluteUrls(dist, allow).errors;
    if (absoluteErrors.length) {
      console.error(
        `build-urls-check: ${absoluteErrors.length} absolute URL problem(s) in ${dist}:`,
      );
      for (const e of absoluteErrors) console.error('  ' + e);
    }
  }
  if (errors.length || absoluteErrors.length) process.exit(1);
  console.log(
    `build-urls-check: ${read} text file(s) in ${dist}; no stylesheet, font or <link> from another origin` +
      (absolute ? '; no absolute URL outside build-urls-allowed.json.' : '.'),
  );
}
