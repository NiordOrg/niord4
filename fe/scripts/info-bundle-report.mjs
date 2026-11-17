#!/usr/bin/env node
/**
 * Bundle report and gate for the public site.
 *
 *   npm run build:info && npm run bundle:info          # run from fe/
 *   node scripts/info-bundle-report.mjs [--dist dist/niord-info/browser]
 *     [--stats dist/niord-info/browser-stats.json] [--baseline ui-apps/info/bundle-baseline.json]
 *     [--json dist/niord-info-bundle-report.json] [--md dist/niord-info-bundle-report.md]
 *     [--root <prefix>/] [--update-baseline]
 *
 * Every path is relative to the current folder, which is fe/ for the base product's npm scripts.
 * A country's workspace runs it from its own fe/ with --root .niord/base/, where the base
 * product's fe/ sits, against its own baseline (info/bundle-baseline.json): a baseline's
 * landingEntryPoints stay relative to the base product's fe/, so every baseline names the same
 * entry points, and the stats are read under the prefix.
 *
 * The public site is read by ships on a satellite link, so the number that decides whether a
 * change is affordable is what a first visit transfers — not the size of the output folder, most
 * of which a first visit never fetches. A first visit lands on the route the site's root redirects
 * to, which the baseline names as `landingRoute`, together with the files that route is built from
 * as `landingEntryPoints`. This script measures exactly that set and compares it with the recorded
 * baseline.
 *
 * The set is derived from the artefacts, never from a list kept by hand:
 *
 *   1. index.html.
 *   2. Styles: the href of every <link rel="stylesheet"> in index.html, de-duplicated (with
 *      critical CSS inlined the builder emits the same sheet twice, print-media plus plain).
 *   3. Initial JS: the src of every <script type="module"> and the href of every
 *      <link rel="modulepreload">, plus everything those reach through static imports.
 *      A dynamic import is by definition not part of the first visit and is excluded.
 *   4. Landing lazy JS: the outputs built from the landing route's entry points, plus their
 *      static imports, minus whatever the initial set already carried.
 *   5. Fonts: every url() in the stylesheets of (2) that ends in .woff2 and resolves inside the
 *      output. The .woff twin is deliberately left out — every browser in the support matrix
 *      takes the woff2 and never asks for the other one.
 *   6. Everything else is reported as information and never gated.
 *
 * Transfer is min(raw, brotli) per file, because nginx serves a pre-compressed file only when it
 * is the smaller one — which for an already-compressed .woff2 it is not. gzip is recorded for
 * information and never gated.
 *
 * A group is within tolerance while it has grown by no more than `tolerancePercent` of its
 * baseline or by `toleranceBytes`, whichever allows more. The byte floor is for the small groups:
 * brotli output moves by a few bytes whenever a chunk hash in index.html changes, which on a group
 * of a few hundred bytes is several percent although nothing a reader waits for has moved.
 *
 * Exit codes: 0 when every group is within tolerance, 1 when one is not (every group that moved
 * is named), 2 for a usage or input error. An improvement is reported, never absorbed:
 * --update-baseline is a deliberate act at the end of a piece of work.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';

const FE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const USAGE =
  'usage: info-bundle-report.mjs [--dist <dir>] [--stats <file>] [--baseline <file>] [--json <file>] [--md <file>] [--root <prefix>/] [--update-baseline]';

function fail(message, code = 2) {
  console.error(`info-bundle-report: ${message}`);
  process.exit(code);
}

const options = {
  dist: 'dist/niord-info/browser',
  stats: 'dist/niord-info/browser-stats.json',
  baseline: 'ui-apps/info/bundle-baseline.json',
  json: 'dist/niord-info-bundle-report.json',
  md: 'dist/niord-info-bundle-report.md',
  root: '',
  updateBaseline: false,
};
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i++) {
  const arg = argv[i];
  if (arg === '--update-baseline') options.updateBaseline = true;
  else if (arg.startsWith('--') && argv[i + 1] && !argv[i + 1].startsWith('--')) {
    const key = arg.slice(2).replace(/-([a-z])/g, (_, c) => c.toUpperCase());
    if (!(key in options)) fail(`unknown option ${arg}\n${USAGE}`);
    options[key] = argv[++i];
  } else fail(`unknown or incomplete option ${arg}\n${USAGE}`);
}

// Whole segments written with "/", inside the workspace.
if (
  options.root &&
  (!/^(?:[A-Za-z0-9_.-]+\/)+$/.test(options.root) ||
    options.root.split('/').some((segment) => segment === '.' || segment === '..'))
)
  fail(`--root "${options.root}" is not a folder inside the workspace ending in "/"\n${USAGE}`);

// Relative to the current folder: fe/ for every npm script of the base product, as before.
const dist = path.resolve(options.dist);
const statsFile = path.resolve(options.stats);
const baselineFile = path.resolve(options.baseline);

if (!fs.existsSync(dist)) fail(`${options.dist} does not exist — run "npm run build:info" first`);
if (!fs.existsSync(statsFile))
  fail(`${options.stats} does not exist — run "npm run build:info" first (it passes --stats-json)`);
if (!fs.existsSync(baselineFile)) fail(`${options.baseline} does not exist`);

const stats = JSON.parse(fs.readFileSync(statsFile, 'utf8'));
const baseline = JSON.parse(fs.readFileSync(baselineFile, 'utf8'));
const outputs = stats.outputs ?? {};

// ---------------------------------------------------------------------------------------------
// Sizes
// ---------------------------------------------------------------------------------------------

const measured = new Map();
function measure(file) {
  const cached = measured.get(file);
  if (cached) return cached;
  const buffer = fs.readFileSync(path.join(dist, file));
  const raw = buffer.length;
  const size = {
    file,
    raw,
    brotli: zlib.brotliCompressSync(buffer, {
      params: {
        [zlib.constants.BROTLI_PARAM_QUALITY]: 11,
        [zlib.constants.BROTLI_PARAM_SIZE_HINT]: raw,
      },
    }).length,
    gzip: zlib.gzipSync(buffer, { level: 9 }).length,
  };
  size.transfer = Math.min(size.raw, size.brotli);
  measured.set(file, size);
  return size;
}

function group(files) {
  const sizes = [...files].sort().map(measure);
  return sizes.reduce(
    (total, size) => ({
      files: total.files + 1,
      raw: total.raw + size.raw,
      brotli: total.brotli + size.brotli,
      gzip: total.gzip + size.gzip,
      transfer: total.transfer + size.transfer,
    }),
    { files: 0, raw: 0, brotli: 0, gzip: 0, transfer: 0 },
  );
}

// ---------------------------------------------------------------------------------------------
// The first-visit set
// ---------------------------------------------------------------------------------------------

function href(tag, attribute) {
  const match = tag.match(new RegExp(`${attribute}=["']([^"']+)["']`, 'i'));
  return match ? match[1] : undefined;
}

function insideOutput(reference, from = '.') {
  if (!reference || /^[a-z]+:/i.test(reference) || reference.startsWith('//')) return undefined;
  const clean = reference.split(/[?#]/)[0].replace(/^\.\//, '');
  const resolved = path
    .normalize(path.join(path.dirname(from), clean))
    .replaceAll('\\', '/')
    .replace(/^\.\//, '');
  return fs.existsSync(path.join(dist, resolved)) ? resolved : undefined;
}

const indexFile = 'index.html';
if (!fs.existsSync(path.join(dist, indexFile))) fail(`${options.dist}/index.html does not exist`);
const indexHtml = fs.readFileSync(path.join(dist, indexFile), 'utf8');

const styleFiles = new Set();
for (const tag of indexHtml.match(/<link\b[^>]*>/gi) ?? []) {
  if (!/rel=["']stylesheet["']/i.test(tag)) continue;
  const file = insideOutput(href(tag, 'href'));
  if (file) styleFiles.add(file);
}

const initialSeeds = new Set();
for (const tag of indexHtml.match(/<script\b[^>]*>/gi) ?? []) {
  if (!/type=["']module["']/i.test(tag)) continue;
  const file = insideOutput(href(tag, 'src'));
  if (file) initialSeeds.add(file);
}
for (const tag of indexHtml.match(/<link\b[^>]*>/gi) ?? []) {
  if (!/rel=["']modulepreload["']/i.test(tag)) continue;
  const file = insideOutput(href(tag, 'href'));
  if (file) initialSeeds.add(file);
}
if (initialSeeds.size === 0) fail('index.html names no module script — is this a browser build?');

// Static imports only: a dynamic import is a later visit, not this one.
function staticClosure(seeds) {
  const reached = new Set();
  const queue = [...seeds];
  while (queue.length) {
    const file = queue.pop();
    if (reached.has(file)) continue;
    reached.add(file);
    for (const dependency of outputs[file]?.imports ?? []) {
      if (dependency.kind === 'import-statement' && !reached.has(dependency.path))
        queue.push(dependency.path);
    }
  }
  return reached;
}

const initialJs = staticClosure(initialSeeds);

const landingEntryPoints = baseline.landingEntryPoints ?? [];
if (landingEntryPoints.length === 0) fail(`${options.baseline} names no landingEntryPoints`);
const landingSeeds = new Set();
for (const entryPoint of landingEntryPoints) {
  const hits = Object.keys(outputs).filter(
    (file) => outputs[file].entryPoint === options.root + entryPoint,
  );
  // A renamed route file would otherwise shrink the measured set and read as an improvement.
  if (hits.length === 0)
    fail(
      `landing entry point "${entryPoint}" matches no output — update ${options.baseline} if the route moved`,
    );
  hits.forEach((file) => landingSeeds.add(file));
}
const landingLazyJs = new Set(
  [...staticClosure(landingSeeds)].filter((file) => !initialJs.has(file)),
);

const fontFiles = new Set();
for (const styleFile of styleFiles) {
  const css = fs.readFileSync(path.join(dist, styleFile), 'utf8');
  for (const match of css.matchAll(/url\(\s*["']?([^"')]+)["']?\s*\)/gi)) {
    if (!/\.woff2$/i.test(match[1].split(/[?#]/)[0])) continue;
    const file = insideOutput(match[1], styleFile);
    if (file) fontFiles.add(file);
  }
}

const groups = {
  index: group([indexFile]),
  styles: group(styleFiles),
  initialJs: group(initialJs),
  landingLazyJs: group(landingLazyJs),
  fonts: group(fontFiles),
};

const firstVisit = Object.values(groups).reduce(
  (total, g) => ({
    files: total.files + g.files,
    raw: total.raw + g.raw,
    transfer: total.transfer + g.transfer,
  }),
  { files: 0, raw: 0, transfer: 0 },
);
const initial = [groups.index, groups.styles, groups.initialJs].reduce(
  (total, g) => ({ raw: total.raw + g.raw, transfer: total.transfer + g.transfer }),
  { raw: 0, transfer: 0 },
);

// ---------------------------------------------------------------------------------------------
// Everything the first visit does not fetch — information only
// ---------------------------------------------------------------------------------------------

function walk(directory, prefix = '') {
  const found = [];
  for (const entry of fs.readdirSync(path.join(dist, directory), { withFileTypes: true })) {
    const relative = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isDirectory()) found.push(...walk(path.join(directory, entry.name), relative));
    else found.push(relative);
  }
  return found;
}

const counted = new Set([indexFile, ...styleFiles, ...initialJs, ...landingLazyJs, ...fontFiles]);
const everything = walk('.');
const rest = everything.filter((file) => !counted.has(file));
const scripts = everything.filter((file) => file.endsWith('.js'));
const largestScript = scripts.map(measure).sort((a, b) => b.raw - a.raw)[0];
// A component stylesheet is an output of the build but not a file in the browser folder: the
// builder inlines it into the component's chunk. Its size therefore comes from the stats entry.
const componentStyles = Object.entries(outputs)
  .filter(([file]) => file.endsWith('.css') && !styleFiles.has(file))
  .map(([file, output]) => ({ file, raw: output.bytes }))
  .sort((a, b) => b.raw - a.raw);
const largestComponentStyle = componentStyles[0];

const report = {
  _comment: `What a first visit to the public site transfers, measured from the build named under source. Written to ${options.baseline} by --update-baseline, and then the gate every later build is held to.`,
  generatedAt: new Date().toISOString().slice(0, 10),
  source: {
    measuredFrom: `${options.dist} + ${options.stats}`,
    angular: JSON.parse(fs.readFileSync(path.join(FE, 'package.json'), 'utf8')).dependencies[
      '@angular/core'
    ],
    build: 'ng build niord-info --stats-json (production, outputHashing all)',
  },
  tolerancePercent: baseline.tolerancePercent ?? 2,
  toleranceBytes: baseline.toleranceBytes ?? 64,
  transferRule: baseline.transferRule,
  landingRoute: baseline.landingRoute,
  landingEntryPoints,
  groups,
  totals: { firstVisit, initial },
  reference: {
    _comment: 'Not gated. Recorded so a later piece of work can see what moved and where.',
    largestScript: largestScript && {
      file: largestScript.file,
      raw: largestScript.raw,
      brotli: largestScript.brotli,
    },
    largestComponentStyle: largestComponentStyle && {
      file: largestComponentStyle.file,
      raw: largestComponentStyle.raw,
    },
    allJs: group(scripts),
    notFirstVisit: group(rest),
  },
  targets: baseline.targets,
};

// ---------------------------------------------------------------------------------------------
// Compare, print, gate
// ---------------------------------------------------------------------------------------------

const tolerance = report.tolerancePercent;
const toleranceBytes = report.toleranceBytes;
const rows = [
  ...Object.keys(groups).map((name) => ({ name, now: groups[name], was: baseline.groups?.[name] })),
  { name: 'first visit', now: firstVisit, was: baseline.totals?.firstVisit },
];
const regressions = [];
for (const row of rows) {
  if (!row.was) {
    row.status = 'no baseline';
    continue;
  }
  const allowed = Math.max(
    row.was.transfer * (1 + tolerance / 100),
    row.was.transfer + toleranceBytes,
  );
  row.delta = row.now.transfer - row.was.transfer;
  row.status = row.now.transfer > allowed ? 'OVER' : row.delta <= 0 ? 'ok' : 'ok (within)';
  if (row.now.transfer > allowed) regressions.push(row);
}

const n = (value) => (value === undefined ? '—' : value.toLocaleString('en-US').replace(/,/g, ' '));
const pad = (value, width) => String(value).padStart(width);
console.log(`\nFirst visit to ${report.landingRoute}, measured in ${options.dist}\n`);
console.log(
  `${'group'.padEnd(14)}${pad('files', 6)}${pad('raw', 11)}${pad('gzip', 10)}${pad('brotli', 10)}${pad('transfer', 11)}${pad('baseline', 11)}${pad('delta', 10)}  status`,
);
for (const row of rows) {
  console.log(
    `${row.name.padEnd(14)}${pad(n(row.now.files), 6)}${pad(n(row.now.raw), 11)}${pad(n(row.now.gzip), 10)}${pad(n(row.now.brotli), 10)}${pad(n(row.now.transfer), 11)}${pad(n(row.was?.transfer), 11)}${pad(row.delta === undefined ? '—' : `${row.delta > 0 ? '+' : ''}${n(row.delta)}`, 10)}  ${row.status}`,
  );
}
console.log(
  `\ninitial (index + styles + initialJs): ${n(initial.raw)} raw / ${n(initial.transfer)} transfer` +
    `\nnot fetched on a first visit: ${report.reference.notFirstVisit.files} file(s), ${n(report.reference.notFirstVisit.raw)} raw\n`,
);

const markdown = [
  `### niord-info bundle report — first visit to ${report.landingRoute}`,
  '',
  '| Group | files | raw | transfer | baseline | Δ | status |',
  '| --- | ---: | ---: | ---: | ---: | ---: | --- |',
  ...rows.map(
    (row) =>
      `| ${row.name} | ${row.now.files} | ${n(row.now.raw)} | ${n(row.now.transfer)} | ${n(row.was?.transfer)} | ${row.delta === undefined ? '—' : `${row.delta > 0 ? '+' : ''}${n(row.delta)}`} | ${row.status} |`,
  ),
  '',
  `Transfer is min(raw, brotli) per file; tolerance ${tolerance}% or ${toleranceBytes} B per group, whichever is larger.`,
  '',
].join('\n');

fs.mkdirSync(path.dirname(path.resolve(options.json)), { recursive: true });
fs.writeFileSync(path.resolve(options.json), `${JSON.stringify(report, null, 2)}\n`);
fs.writeFileSync(path.resolve(options.md), markdown);
if (process.env['GITHUB_STEP_SUMMARY'])
  fs.appendFileSync(process.env['GITHUB_STEP_SUMMARY'], markdown);

if (options.updateBaseline) {
  fs.writeFileSync(baselineFile, `${JSON.stringify(report, null, 2)}\n`);
  console.log(`baseline updated: ${options.baseline}`);
  process.exit(0);
}

if (regressions.length) {
  console.error(
    `info-bundle-report: ${regressions.length} group(s) above the baseline by more than ${tolerance}% or ${toleranceBytes} B, whichever is larger:`,
  );
  for (const row of regressions)
    console.error(
      `  ${row.name}: ${n(row.now.transfer)} transfer against a baseline of ${n(row.was.transfer)} (+${n(row.delta)} B, +${((row.delta / row.was.transfer) * 100).toFixed(1)}%)`,
    );
  process.exit(1);
}
console.log('info-bundle-report: every group is at or below its baseline.');
