#!/usr/bin/env node
/**
 * Separation gate for the public site, read off the build itself.
 *
 *   npm run build:info && npm run separation:info          # run from fe/
 *   node scripts/info-separation-check.mjs [--stats dist/niord-info/browser-stats.json]
 *   node .niord/base/scripts/info-separation-check.mjs --stats dist/niord-info/browser-stats.json \
 *     --root .niord/base/ --country <cc>                    # a country workspace, from its fe/
 *
 * The public site is a release of its own, read by anonymous visitors, and nothing of the
 * administration application may travel with it. This check and lint are what keep it out: it
 * runs on every build of the public site, and image:info runs it again on the stats of the build
 * an image packages. It asks the question of the build itself: every first-party module the
 * bundle is made of must belong to the public site.
 *
 * "First-party" is everything that is neither a package nor one of the builder's virtual modules
 * (`angular:styles/…`, `angular:css-resource:…`, esbuild's `\0`-prefixed and `<define:…>` names).
 * Each one must start with a path in ALLOWED, and no path may reach out of the workspace with
 * `../` or into `ui-apps/admin/`.
 *
 * In a country workspace the base product's fe/ sits under a prefix, `--root` (`.niord/base/`),
 * and `--country <cc>` adds the country's own public-site files: `info/`, the generated
 * `.niord/generated/info/`, and its own `styles/tokens.<cc>.css`, `styles/fonts.<cc>.css` and
 * `styles/fonts/`. The country's `admin/` and `.niord/generated/admin/` are offences beside
 * `<root>ui-apps/admin/`, and so is every path not allowed, another country's tokens included.
 * Without the two options the check is the base product workspace's, as before them.
 *
 * Two packages are banned outright, for the same reason the site has no login: there is no
 * identity here and no edit flow. They are matched on the FILE the bundle carries, not on the
 * import specifier — `@angular/forms/signals` resolves to `@angular/forms/fesm2022/signals.mjs`,
 * so a check for the specifier would never fire.
 *
 * Exit codes: 0 clean, 1 when something crossed the line (every offender is named with the bundle
 * it landed in), 2 for a usage or input error. It never guesses: a missing stats file is an error,
 * not a pass.
 */
import fs from 'node:fs';
import path from 'node:path';

/**
 * Folders the public site's bundle may be built from: its own, plus the three that belong to
 * neither application — the shared stylesheets, the shared OpenLayers map kit and the shared
 * message-HTML contract. Relative to the base product's fe/, so under `--root` in a country.
 */
const ALLOWED = ['ui-apps/info/', 'styles/', 'shared-map/', 'shared-html/'];

/** Prefixes the builder uses for modules that have no file of their own. */
const VIRTUAL = ['angular:', '\0', '<define:'];

const BANNED = [
  {
    label: 'keycloak-js',
    matches: (input) => /(^|\/)node_modules\/keycloak-js\//.test(input),
  },
  {
    label: '@angular/forms/signals',
    matches: (input) => /(^|\/)node_modules\/@angular\/forms\/.*signals[^/]*\.m?js$/.test(input),
  },
];

const USAGE =
  'usage: info-separation-check.mjs [--stats <file>] [--root <prefix>/ [--country <cc>]]';

function fail(message, code = 2) {
  console.error(`info-separation-check: ${message}`);
  process.exit(code);
}

const options = { stats: 'dist/niord-info/browser-stats.json', root: '', country: undefined };
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '--stats' && argv[i + 1]) options.stats = argv[++i];
  else if (argv[i] === '--root' && argv[i + 1]) options.root = argv[++i];
  else if (argv[i] === '--country' && argv[i + 1]) options.country = argv[++i];
  else fail(`unknown or incomplete option ${argv[i]}\n${USAGE}`);
}
// Whole segments written with "/", inside the workspace.
if (
  options.root &&
  (!/^(?:[A-Za-z0-9_.-]+\/)+$/.test(options.root) ||
    options.root.split('/').some((segment) => segment === '.' || segment === '..'))
)
  fail(`--root "${options.root}" is not a folder inside the workspace ending in "/"\n${USAGE}`);
if (options.country !== undefined) {
  if (!/^[a-z]{2}$/.test(options.country))
    fail(
      `--country "${options.country}" is not a country code: its ISO 3166-1 alpha-2 code, two letters a-z`,
    );
  if (!options.root) fail(`--country needs --root, the base product's place in the workspace`);
}

const allowed = ALLOWED.map((prefix) => options.root + prefix);
const offLimits = [`${options.root}ui-apps/admin/`];
if (options.country) {
  const cc = options.country;
  allowed.push(
    'info/',
    '.niord/generated/info/',
    `styles/tokens.${cc}.css`,
    `styles/fonts.${cc}.css`,
    'styles/fonts/',
  );
  offLimits.push('admin/', '.niord/generated/admin/');
}

// Relative to the current folder: fe/ for every npm script of the base product, as before.
const statsFile = path.resolve(options.stats);
if (!fs.existsSync(statsFile))
  fail(`${options.stats} does not exist — run "npm run build:info" first (it passes --stats-json)`);

const stats = JSON.parse(fs.readFileSync(statsFile, 'utf8'));
const outputs = stats.outputs ?? {};
if (Object.keys(outputs).length === 0) fail(`${options.stats} names no outputs`);

/** input path -> the bundles it landed in */
const inputs = new Map();
for (const [bundle, output] of Object.entries(outputs)) {
  for (const input of Object.keys(output.inputs ?? {})) {
    if (!inputs.has(input)) inputs.set(input, new Set());
    inputs.get(input).add(bundle);
  }
}

const offences = [];
let firstPartyCount = 0;
for (const [input, bundles] of inputs) {
  const where = [...bundles].sort().join(', ');

  for (const ban of BANNED) {
    if (ban.matches(input)) offences.push({ input, where, why: `${ban.label} is banned here` });
  }

  if (VIRTUAL.some((prefix) => input.startsWith(prefix))) continue;
  if (input.startsWith('node_modules/')) continue;

  firstPartyCount += 1;
  if (offLimits.some((prefix) => input.startsWith(prefix)))
    offences.push({ input, where, why: 'the administration application is a separate release' });
  else if (input.startsWith('../')) offences.push({ input, where, why: 'outside the workspace' });
  else if (!allowed.some((prefix) => input.startsWith(prefix)))
    offences.push({ input, where, why: `not under ${allowed.join(' or ')}` });
}

if (offences.length) {
  console.error('info-separation-check: the build carried code the public site must not ship:');
  for (const offence of offences.sort((a, b) => a.input.localeCompare(b.input)))
    console.error(`  ${offence.input}\n      ${offence.why}\n      in ${offence.where}`);
  process.exit(1);
}

console.log(
  `info-separation-check: ${firstPartyCount} first-party module(s), all under ${allowed.join(' or ')};` +
    ' no administration code, no keycloak-js, no @angular/forms/signals.',
);
