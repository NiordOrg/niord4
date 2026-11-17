#!/usr/bin/env node
/**
 * The link check of the built guides (build.sh runs it after the build): every relative link
 * (`href`) and resource (`src`) in every page under the site folder must name a file of the site,
 * and every `#fragment` on a link to a page (or within one) must name an id of that page, exactly
 * once. External URLs (any scheme, or `//host`) are not fetched. Plain Node, no dependencies.
 *
 * It also checks the administration application's help links, the contract between the app and
 * the manuals: help-urls.json maps each help key to "/<manual>/manual.html#<id>", and every value
 * must name a page of the built site and, when it has one, an id of that page. A key whose target
 * does not resolve fails the check, named with its target.
 *
 *   node check-links.mjs <site folder> [--no-pdf] [--help-urls <file>]
 *
 * --no-pdf: the PDFs were not built, so a link to a .pdf is counted and skipped, not failed.
 * --help-urls: the help map to check; by default the application's own, HELP_URLS below.
 * Exits 1 on any problem and lists each one. A problem in one of the ARCHIVE pages (the old
 * development guides, kept as they were written) is listed as a warning and fails nothing.
 *
 * Last, what a published page may never hold, read in every page's HTML, its links and
 * attributes included. FORBIDDEN (an internal host name, a person's home folder, a staff account's
 * user id) fails the build on any page, the archive's too, so it cannot be published by accident.
 * RULED_OUT (SECOM or S-124, a GitHub address other than https://github.com/NiordOrg, a country's
 * repositories) fails it on every page outside the ARCHIVE and is a warning in the archive, whose
 * text is kept as it was written.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ARCHIVE = ['dev-guide-v2/', 'dev-guide-v3/'];
const HELP_URLS = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../fe/ui-apps/admin/src/app/core/main-shell/help/help-urls.json',
);

// Never on a published page. The hosts are the operator's internal network and the addresses the
// guides replaced (the old production, test and public-site hosts); the paths are a person's home
// folder, by drive or by Git Bash's /c/, and a staff account's user id.
const FORBIDDEN = [
  [/\b(?:[a-z0-9-]+\.)*sitcloud\.dk\b/gi, 'an internal host'],
  [/\b(?:[a-z0-9-]+\.)*t-dma\.dk\b/gi, 'the old test environment, now niord.staging.brs2.dk'],
  [/\bniord\.dma\.dk\b/gi, 'the old production address, now niord.brs2.dk'],
  [/\bnautiskinformation\.soefartsstyrelsen\.dk\b/gi, 'the old public site, now nautiskinformation.brs2.dk'],
  [/\b[a-z]:(?:\\|\/|&#92;)+users(?:\\|\/|&#92;)+[a-z0-9._-]+/gi, "a person's home folder"],
  [/(?:^|[^a-z0-9])\/c\/users\/[a-z0-9._-]+/gi, "a person's home folder"],
  [/\bx\d{6}\b/g, "a staff account's user id"],
];
// Never on a page outside the archive (in the archive, a warning): SECOM and S-124, which the
// guides leave out; a GitHub address other than the organisation itself, https://github.com/NiordOrg
// (no deep link into a repository, no other organisation's); and a country's repositories or
// packages, which the guides describe only as "a country" built on the base product.
const RULED_OUT = [
  [/secom|\bs-?124(?!\d)/gi, 'SECOM or S-124, which the guides leave out'],
  [
    /\b(?:[a-z0-9-]+\.)*github(?:usercontent)?\.com\/(?!niordorg\/?\.?(?:[^\w./-]|$))[^\s"'<>()[\]]*/gi,
    'a GitHub address other than https://github.com/NiordOrg',
  ],
  [/\bsifa-dk\b/gi, "a country's repositories or packages"],
];

const usage = 'usage: node check-links.mjs <site folder> [--no-pdf] [--help-urls <file>]';
const args = process.argv.slice(2);
let noPdf = false;
let helpUrlsFile = HELP_URLS;
const rest = [];
for (let i = 0; i < args.length; i++) {
  if (args[i] === '--no-pdf') noPdf = true;
  else if (args[i] === '--help-urls' && i + 1 < args.length) helpUrlsFile = path.resolve(args[++i]);
  else rest.push(args[i]);
}
if (rest.length !== 1 || rest[0].startsWith('-')) {
  console.error(usage);
  process.exit(2);
}
const site = path.resolve(rest[0]);
if (!fs.statSync(site, { throwIfNoEntry: false })?.isDirectory()) {
  console.error(`check-links: ${rest[0]} is not a folder`);
  process.exit(2);
}

const walk = (dir) =>
  fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = path.join(dir, e.name);
    return e.isDirectory() ? walk(p) : [p];
  });

const decodeEntities = (s) =>
  s
    .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#([0-9]+);/g, (_, d) => String.fromCodePoint(parseInt(d, 10)))
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&');

/** The tags of a page, without comments, scripts and styles, whose text is not markup. */
function tags(html) {
  const text = html
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<script\b[\s\S]*?<\/script\s*>/gi, '<script>')
    .replace(/<style\b[\s\S]*?<\/style\s*>/gi, '<style>');
  const out = [];
  const tagRe = /<([a-zA-Z][a-zA-Z0-9-]*)\b([^>]*)>/g;
  const attrRe = /([^\s=/"'<>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?/g;
  for (const m of text.matchAll(tagRe)) {
    const attrs = {};
    for (const a of m[2].matchAll(attrRe)) {
      const value = a[2] ?? a[3] ?? a[4];
      if (value !== undefined) attrs[a[1].toLowerCase()] = decodeEntities(value);
    }
    out.push({ name: m[1].toLowerCase(), attrs });
  }
  return out;
}

const files = walk(site);
const pages = files.filter((f) => /\.html?$/i.test(f));
const ids = new Map(); // page -> Map(id -> count)
const links = []; // { from, attr, value }
const leaks = []; // what a page may never hold: always a failure
const ruledOut = []; // RULED_OUT: a failure, a warning in the archive
for (const page of pages) {
  const counts = new Map();
  const html = fs.readFileSync(page, 'utf8');
  const lines = html.split('\n');
  const scan = (rules, into) => {
    for (const [re, what] of rules) {
      lines.forEach((line, i) => {
        for (const m of line.matchAll(re)) {
          into.push(`${path.relative(site, page).split(path.sep).join('/')}:${i + 1}: "${m[0].trim()}", ${what}`);
        }
      });
    }
  };
  scan(FORBIDDEN, leaks);
  scan(RULED_OUT, ruledOut);
  for (const tag of tags(html)) {
    const id = tag.attrs.id ?? (tag.name === 'a' ? tag.attrs.name : undefined);
    if (id !== undefined && id !== '') counts.set(id, (counts.get(id) ?? 0) + 1);
    for (const attr of ['href', 'src']) {
      if (tag.attrs[attr] !== undefined) links.push({ from: page, attr, value: tag.attrs[attr] });
    }
  }
  ids.set(page, counts);
}

const rel = (p) => path.relative(site, p).split(path.sep).join('/');
const problems = [];
for (const [page, counts] of ids) {
  for (const [id, n] of counts) {
    if (n > 1) problems.push(`${rel(page)}: the id "${id}" is defined ${n} times`);
  }
}

let checked = 0;
let external = 0;
let pdfSkipped = 0;
for (const { from, attr, value } of links) {
  const v = value.trim();
  if (/^[a-z][a-z0-9+.-]*:/i.test(v) || v.startsWith('//')) {
    external++;
    continue;
  }
  const hash = v.indexOf('#');
  const rawPath = (hash < 0 ? v : v.slice(0, hash)).replace(/\?.*$/, '');
  const rawFragment = hash < 0 ? null : v.slice(hash + 1);
  const where = `${rel(from)}: ${attr}="${value}"`;
  let target = from;
  if (rawPath !== '') {
    let decoded;
    try {
      decoded = decodeURIComponent(rawPath);
    } catch {
      problems.push(`${where}: the path is not valid percent-encoding`);
      continue;
    }
    target = decoded.startsWith('/') ? path.join(site, decoded) : path.resolve(path.dirname(from), decoded);
    if (target !== site && !target.startsWith(site + path.sep)) {
      problems.push(`${where}: leaves the site`);
      continue;
    }
    if (noPdf && /\.pdf$/i.test(target)) {
      pdfSkipped++;
      continue;
    }
    const stat = fs.statSync(target, { throwIfNoEntry: false });
    if (stat?.isDirectory()) target = path.join(target, 'index.html');
    if (!fs.statSync(target, { throwIfNoEntry: false })?.isFile()) {
      problems.push(`${where}: no such file ${rel(target)}`);
      continue;
    }
  }
  checked++;
  if (rawFragment === null || rawFragment === '' || rawFragment === 'top') continue;
  const targetIds = ids.get(target);
  if (!targetIds) continue; // a fragment on a non-HTML resource is the viewer's business
  let fragment;
  try {
    fragment = decodeURIComponent(rawFragment);
  } catch {
    fragment = rawFragment;
  }
  if (!targetIds.has(fragment)) problems.push(`${where}: no id "${fragment}" in ${rel(target)}`);
}

// The help map: key -> "/<manual>/manual.html#<id>", read against the built site.
let helpKeys = 0;
let helpMap;
try {
  helpMap = JSON.parse(fs.readFileSync(helpUrlsFile, 'utf8').replace(/^﻿/, ''));
} catch (e) {
  problems.push(`help map ${helpUrlsFile}: cannot be read (${e.message})`);
}
if (helpMap !== undefined && (helpMap === null || typeof helpMap !== 'object' || Array.isArray(helpMap))) {
  problems.push(`help map ${helpUrlsFile}: not a flat object of key -> URL`);
  helpMap = undefined;
}
for (const [key, value] of Object.entries(helpMap ?? {})) {
  helpKeys++;
  const where = `help key "${key}" -> ${JSON.stringify(value)}`;
  if (typeof value !== 'string' || !value.startsWith('/')) {
    problems.push(`${where}: not a site path starting with /`);
    continue;
  }
  const hash = value.indexOf('#');
  const page = path.join(site, decodeURI(hash < 0 ? value : value.slice(0, hash)));
  const targetIds = ids.get(page);
  if (!targetIds) {
    problems.push(`${where}: the build produced no page ${rel(page)}`);
    continue;
  }
  const fragment = hash < 0 ? '' : decodeURIComponent(value.slice(hash + 1));
  if (fragment !== '' && targetIds.get(fragment) !== 1) {
    problems.push(`${where}: ${rel(page)} has ${targetIds.get(fragment) ?? 'no'} id "${fragment}"`);
  }
}

console.log(
  `check-links: ${helpKeys} help keys of ${path.basename(helpUrlsFile)} checked; ` +
    `${pages.length} pages, ${checked} relative links checked, ${external} external not fetched` +
    (noPdf ? `, ${pdfSkipped} PDF links skipped (no --pdf)` : ''),
);
console.log(
  `check-links: every page read for internal hosts, home folders, staff ids, SECOM or S-124, ` +
    `GitHub addresses and a country's repositories: ` +
    `${leaks.length + ruledOut.length} found`,
);
problems.push(...new Set(ruledOut));
const archived = problems.filter((p) => ARCHIVE.some((a) => p.startsWith(a)));
const failures = [...problems.filter((p) => !archived.includes(p)), ...new Set(leaks)];
if (archived.length) {
  console.log(`check-links: ${archived.length} warning(s) in the archive, not failed:`);
  for (const p of archived) console.log(`  ${p}`);
}
if (failures.length) {
  for (const p of failures) console.error(`  ${p}`);
  console.error(`check-links: ${failures.length} problem(s)`);
  process.exit(1);
}
console.log('check-links: every relative link and fragment resolves');
