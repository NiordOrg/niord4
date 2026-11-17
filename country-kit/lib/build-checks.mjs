/**
 * The checks on every country build (fe-country-split-plan §4.8, §4.11 rules 4 and 5), over both
 * applications' output in a country workspace, dist/niord-app/browser and dist/niord-info/browser.
 * check-build.mjs runs them as the last step of the generated `npm run build`. Every finding is one
 * line; the separation check and the bundle report are the base product's own scripts, run as
 * child processes with constant arguments and no shell. One source per output (oneSourcePerOutput)
 * reads the generated angular.json instead of the output: which input wrote a file is not in it.
 * The absolute-URL rule reads the base product's scripts/build-urls-allowed.json and the country's
 * own fe/build-urls-allowed.json, whose entries — each one literal https: URL with its reason, and
 * scoped to one application's output when it names one — it names on every build, warning of one
 * that admits nothing in its scope. The public site's built page (indexProblems) is held to the
 * default language's built bundle, after packs and text overrides, so the check re-implements no
 * staging.
 *
 * Built-ins only, and the base product's own scripts/build-urls-check.mjs, which the zip carries,
 * loaded from the base product the layout resolver finds (lib/where.mjs); the one-source check
 * finds the workspace's installed @angular/build at run time (asset-outputs.mjs).
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { flatten } from '../stage-languages.mjs';
import { duplicateOutputs, resolveOutputs } from './asset-outputs.mjs';
import { readAllow, scanFiles } from './secrets.mjs';
import { cssSelector } from './utilities.mjs';
import { where } from './where.mjs';
import { APPS, parseJsonc } from './workspace.mjs';

/** The base product's fe/: `.niord/base/` in a country workspace, fe/ in the base product. */
export const BASE = where().base;
const { APP_BUILDS, checkAbsoluteUrls, checkDistUrls, compileCountryUrlAllowList } = await import(
  pathToFileURL(path.join(BASE, 'scripts', 'build-urls-check.mjs')).href
);
export const DISTS = { admin: 'dist/niord-app/browser', info: 'dist/niord-info/browser' };
export const STATS = 'dist/niord-info/browser-stats.json';
export const BASELINE = 'info/bundle-baseline.json';
export const UTILITIES = '.niord/build/country-utilities.json';
/** The country's own URL allow-list, beside its manifest. */
export const COUNTRY_URLS = 'fe/build-urls-allowed.json';
const ROOT = '.niord/base/';

/** The bundle report's command line, the same as the generated `bundle:info` script's. */
export const BUNDLE_ARGS = [
  '--root',
  ROOT,
  '--dist',
  DISTS.info,
  '--stats',
  STATS,
  '--baseline',
  BASELINE,
  '--json',
  'dist/niord-info-bundle-report.json',
  '--md',
  'dist/niord-info-bundle-report.md',
];

/** Runs `node <script> <args>` in `cwd`, its output inherited and no shell; returns its exit status. */
export function runNode(script, args, { cwd }) {
  const result = spawnSync(process.execPath, [script, ...args], {
    cwd,
    stdio: 'inherit',
    shell: false,
  });
  if (result.error) throw result.error;
  return result.status;
}

/** Every file under `dir`, relative to it and with `/`. */
function filesUnder(dir) {
  return fs
    .readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) =>
      path.relative(dir, path.join(entry.parentPath, entry.name)).replaceAll('\\', '/'),
    )
    .sort();
}

/**
 * The country's own URL allow-list, fe/build-urls-allowed.json, compiled
 * (compileCountryUrlAllowList); a country without one has an empty list. A file that is not
 * regular, cannot be read or is not JSON is an error of the check.
 */
export function readCountryUrlAllow(fe) {
  const file = path.join(fe, 'build-urls-allowed.json');
  const failed = (message) => ({
    entries: [],
    errors: [`${COUNTRY_URLS}: ${message}`],
    label: COUNTRY_URLS,
  });
  let stat;
  try {
    stat = fs.lstatSync(file);
  } catch (error) {
    if (error.code === 'ENOENT') return { entries: [], errors: [], label: COUNTRY_URLS };
    return failed(error.message);
  }
  if (!stat.isFile()) return failed('is not a regular file');
  try {
    return compileCountryUrlAllowList(JSON.parse(fs.readFileSync(file, 'utf8')), COUNTRY_URLS);
  } catch (error) {
    return failed(error.message);
  }
}

/** What a country build must have made before it can be checked, as the paths that are missing. */
export function missingOutputs(fe) {
  return [...Object.values(DISTS), STATS].filter(
    (relative) => !fs.existsSync(path.join(fe, relative)),
  );
}

/** The languages each application's flags are for: the administration app's UI and content languages, the public site's UI languages. */
export function flagLanguages(manifest) {
  const admin = manifest.apps.admin;
  return {
    admin: [...new Set([...admin.uiLanguages, ...(admin.contentLanguages ?? [])])].sort(),
    info: [...manifest.apps.info.uiLanguages].sort(),
  };
}

const sameList = (label, shipped, expected, what) => {
  const errors = [];
  for (const lang of shipped.filter((each) => !expected.includes(each)))
    errors.push(`${label}: ${what(lang)} is shipped, and niord-country.json does not name ${lang}`);
  for (const lang of expected.filter((each) => !shipped.includes(each)))
    errors.push(`${label}: niord-country.json names ${lang}, and ${what(lang)} is not shipped`);
  return errors;
};

/**
 * What a built file's path may never be: a pack's status file, an installed package, a pack's
 * licence notice or flag drawing, another tree's assets (the base product's default branding and
 * the in-tree countries' folders, or any other two-letter folder under assets/), or a file of the
 * generated workspace.
 */
export function pathProblem(file) {
  const segments = file.split('/');
  if (segments.includes('status')) return 'a pack status file';
  if (segments.includes('node_modules')) return 'an installed package';
  if (segments.includes('.niord')) return 'a file of the generated workspace';
  if (segments.at(-1) === 'ATTRIBUTION.txt') return "a pack's licence notice";
  if (/^assets\/img\/flags\/[^/]+\.svg$/.test(file)) return "a flag's drawing";
  if (/^assets\/(?:default|[a-z]{2})\//.test(file)) return "another tree's assets";
  return undefined;
}

const NAMED_REFERENCES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' };

/**
 * A page's text as a reader sees it: character references decoded, white space collapsed. A
 * numeric reference past the last code point (U+10FFFF) stays as written.
 */
const readable = (text) =>
  text
    .replace(/&(?:#(\d+)|#x([0-9a-f]+)|([a-z]+));/gi, (reference, dec, hex, name) => {
      if (!dec && !hex) return NAMED_REFERENCES[name.toLowerCase()] ?? reference;
      const codePoint = dec ? Number(dec) : parseInt(hex, 16);
      return codePoint <= 0x10ffff ? String.fromCodePoint(codePoint) : reference;
    })
    .replace(/\s+/g, ' ')
    .trim();

/**
 * 10. The public site's built page, `dist`/index.html, against the default language: its html
 * tag's lang is `defaultLanguage`, compared without case, and its one title reads as the
 * SITE_TITLE of the built bundle `dist`/assets/i18n/<defaultLanguage>.json. The page is the
 * country's info/app/index.html (the index seam) or the base product's English one, so a country
 * whose public site defaults to another language gives its own. Returns the problems, one line
 * each, relative to `dist`.
 */
export function indexProblems({ dist, defaultLanguage }) {
  const fix =
    "the country's info/app/index.html (the index seam) gives the page's lang and title in its default language";
  let html;
  try {
    html = fs.readFileSync(path.join(dist, 'index.html'), 'utf8');
  } catch (error) {
    return [
      error.code === 'ENOENT'
        ? `index.html does not exist: ${fix}`
        : `index.html cannot be read (${error.code ?? error.message})`,
    ];
  }
  const problems = [];
  const attributes = html.match(/<html\b((?:[^>"']|"[^"]*"|'[^']*')*)>/i)?.[1] ?? '';
  const lang = attributes.match(/(?:^|\s)lang\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))/i);
  const pageLang = lang ? (lang[1] ?? lang[2] ?? lang[3]) : undefined;
  if (pageLang === undefined)
    problems.push(
      `index.html has no lang, and ${defaultLanguage} is the public site's default language: ${fix}`,
    );
  else if (pageLang.toLowerCase() !== defaultLanguage.toLowerCase())
    problems.push(
      `index.html has lang "${pageLang}", not ${defaultLanguage}, the public site's default language: ${fix}`,
    );

  const bundle = `assets/i18n/${defaultLanguage}.json`;
  let siteTitle;
  try {
    siteTitle = flatten(JSON.parse(fs.readFileSync(path.join(dist, bundle), 'utf8'))).SITE_TITLE;
    if (siteTitle === undefined)
      problems.push(`${bundle} has no SITE_TITLE to hold index.html's title to`);
  } catch (error) {
    problems.push(
      `${bundle} cannot be read (${error.code ?? error.message}), so index.html's title is held to nothing`,
    );
  }
  const titles = [...html.matchAll(/<title\b[^>]*>([\s\S]*?)<\/title\s*>/gi)].map(([, text]) =>
    readable(text),
  );
  if (titles.length !== 1) problems.push(`index.html has ${titles.length} titles, not one: ${fix}`);
  else if (siteTitle !== undefined && titles[0] !== readable(siteTitle))
    problems.push(
      `index.html is titled "${titles[0]}", not "${readable(siteTitle)}", the SITE_TITLE of ${bundle}: ${fix}`,
    );
  return problems;
}

/**
 * Every check on a country build in `fe` (the country's fe/), whose manifest is `manifest`
 * (lib/manifest.mjs). `base` is the base product's fe/ the scripts and allow-lists are read from,
 * `kit` the kit whose secret-scan.json the scan takes; `run(script, args, { cwd })` runs a base
 * product script and returns its exit status (runNode). Returns `{ errors, warnings, lines }`,
 * one line each.
 */
export function checkBuild({ fe, manifest, base = BASE, kit = where().kit, run = runNode }) {
  const errors = [];
  const warnings = [];
  const lines = [];
  const cc = manifest.country;

  let urlAllow;
  try {
    urlAllow = JSON.parse(
      fs.readFileSync(path.join(base, 'scripts', 'build-urls-allowed.json'), 'utf8'),
    );
  } catch (error) {
    errors.push(`urls: build-urls-allowed.json: ${error.message}`);
    urlAllow = { allow: [] };
  }
  const countryUrls = readCountryUrlAllow(fe);
  errors.push(...countryUrls.errors.map((line) => `urls: ${line}`));
  const countryMatched = new Set();
  const secretAllow = readAllow(path.join(kit, 'secret-scan.json'));
  errors.push(...secretAllow.errors.map((line) => `secrets: ${line}`));

  let utilities = { admin: [], info: [] };
  try {
    utilities = JSON.parse(fs.readFileSync(path.join(fe, UTILITIES), 'utf8'));
  } catch (error) {
    errors.push(
      `utility: ${UTILITIES} cannot be read (${error.code ?? error.message}): run \`node setup.mjs\``,
    );
  }

  const flags = flagLanguages(manifest);
  const secretFiles = [];
  for (const [app, relative] of Object.entries(DISTS)) {
    const dist = path.join(fe, relative);
    const files = filesUnder(dist);
    const label = (check) => `${check} (${relative})`;

    // 1. Exactly the UI bundles of the manifest.
    const bundles = [];
    for (const file of files.filter((each) => each.startsWith('assets/i18n/'))) {
      const lang = /^assets\/i18n\/([a-z]{2,3})\.json$/.exec(file)?.[1];
      if (lang) bundles.push(lang);
      else if (!pathProblem(file)) errors.push(`${label('bundles')}: ${file} is not a UI bundle`);
    }
    errors.push(
      ...sameList(
        label('bundles'),
        bundles.sort(),
        [...manifest.apps[app].uiLanguages].sort(),
        (lang) => `assets/i18n/${lang}.json`,
      ),
    );

    // 2. Exactly the flags of the languages the application names.
    const shippedFlags = [];
    for (const file of files.filter((each) => each.startsWith('assets/img/flags/'))) {
      const lang = /^assets\/img\/flags\/([a-z]{2,3})\.png$/.exec(file)?.[1];
      if (lang) shippedFlags.push(lang);
      else if (!pathProblem(file)) errors.push(`${label('flags')}: ${file} is not a flag`);
    }
    errors.push(
      ...sameList(
        label('flags'),
        shippedFlags.sort(),
        flags[app],
        (lang) => `assets/img/flags/${lang}.png`,
      ),
    );

    // 3. Nothing from a pack's status, its licence or drawing, an installed package or another tree.
    for (const file of files) {
      const problem = pathProblem(file);
      if (problem) errors.push(`${label('files')}: ${file} is ${problem}`);
    }

    // 4. No external font or style URL, and no absolute URL outside the base product's list and
    // the country's, whose entries scoped to the other application admit nothing here.
    errors.push(...checkDistUrls(dist).errors.map((line) => `${label('urls')}: ${line}`));
    const absolute = checkAbsoluteUrls(dist, urlAllow, countryUrls, app);
    errors.push(...absolute.errors.map((line) => `${label('urls')}: ${line}`));
    for (const index of absolute.matched) countryMatched.add(index);

    // 5. Each country-only utility in the application's built CSS.
    const css = files
      .filter((file) => file.endsWith('.css'))
      .map((file) => fs.readFileSync(path.join(dist, file), 'utf8'))
      .join('\n');
    for (const { file, utility } of utilities[app] ?? [])
      if (!css.includes(cssSelector(utility)))
        errors.push(
          `${label('utility')}: ${utility}, which ${file} uses, is in no stylesheet: Tailwind's scan of the country's templates lost it`,
        );

    lines.push(
      `${relative}: bundles ${bundles.join(', ') || 'none'}; flags ${shippedFlags.join(', ') || 'none'}; ${(utilities[app] ?? []).length} country utility(ies); ${files.length} file(s)`,
    );
    secretFiles.push(...files.map((file) => `${relative}/${file}`));
  }

  // The country's own addresses, said on every build with their scope, and an entry that admits
  // nothing in its scope — its application's build, or either for an entry without one — named,
  // so the list never outlives the addresses it was written for.
  for (const { index, url, reason, app } of countryUrls.entries) {
    const scope = app ? ` in ${APP_BUILDS[app]} only` : '';
    lines.push(`urls: the country's allow-list admits ${url}${scope} — ${reason}`);
    if (!countryMatched.has(index))
      warnings.push(
        `urls: ${COUNTRY_URLS}: allow[${index}] (${url}) admits no URL of ${app ? APP_BUILDS[app] : "either application's build"}, so it permits nothing: remove it`,
      );
  }

  // 6. No secret in either application's output.
  const secrets = scanFiles({
    root: fe,
    files: secretFiles,
    allow: secretAllow.allow,
    scope: 'dist',
    country: cc,
  });
  errors.push(...secrets.errors.map((line) => `secrets: ${line}`));
  errors.push(...secrets.findings.map((line) => `secrets: ${line}`));

  // 7. The public site's separation, read off this build's stats.
  const separation = run(
    path.join(base, 'scripts', 'info-separation-check.mjs'),
    ['--stats', STATS, '--root', ROOT, '--country', cc],
    { cwd: fe },
  );
  if (separation !== 0)
    errors.push(`separation check: info-separation-check.mjs exited ${separation}`);

  // 8. The public site's first visit against the country's own baseline.
  if (fs.existsSync(path.join(fe, BASELINE))) {
    const bundle = run(path.join(base, 'scripts', 'info-bundle-report.mjs'), BUNDLE_ARGS, {
      cwd: fe,
    });
    if (bundle !== 0)
      errors.push(`bundle report: info-bundle-report.mjs exited ${bundle}, against ${BASELINE}`);
  } else
    warnings.push(
      `bundle report: ${BASELINE} does not exist, so the public site's first visit is held to no baseline: copy the base product's, .niord/base/ui-apps/info/bundle-baseline.json, to ${BASELINE}, record the country's own over it with \`npm run bundle:info -- --update-baseline\` and commit it`,
    );

  // 10. The public site's built page in its default language, titled as that language's bundle.
  const { defaultLanguage } = manifest.apps.info;
  const page = indexProblems({ dist: path.join(fe, DISTS.info), defaultLanguage });
  errors.push(...page.map((line) => `index (${DISTS.info}): ${line}`));
  if (!page.length)
    lines.push(`${DISTS.info}/index.html: lang ${defaultLanguage}, titled as SITE_TITLE`);

  return { errors, warnings, lines };
}

/**
 * 9. One source per output file (R9): the asset inputs of both projects of the workspace's
 * generated angular.json, `fe`/angular.json, resolved as the builder resolves them, and every
 * output path more than one input writes. `ng build` copies assets concurrently, so which of two
 * such files a reader gets is not defined; nothing in the built output tells, so the check reads
 * the build's own configuration. A configuration without assets of its own copies the options'
 * list, so each distinct list is resolved once and named with the configurations that use it.
 * `resolve` is resolveOutputs (the workspace's installed builder); a test hands in its own.
 * Returns `{ errors, lines }`; throws when angular.json cannot be read.
 */
export async function oneSourcePerOutput({ fe, resolve = resolveOutputs }) {
  const angular = parseJsonc(
    fs.readFileSync(path.join(fe, 'angular.json'), 'utf8'),
    'angular.json',
  );
  const errors = [];
  const lines = [];
  for (const name of Object.values(APPS)) {
    const definition = angular.projects?.[name];
    const build = definition?.architect?.build;
    if (!build) {
      errors.push(
        `one source: angular.json has no build target for ${name}: run \`node setup.mjs\``,
      );
      continue;
    }
    const projectRoot = definition.root ?? '';
    const sourceRoot = definition.sourceRoot ?? path.posix.join(projectRoot, 'src');
    const shared = build.options?.assets ?? [];
    const lists = new Map();
    const configurations = Object.entries(build.configurations ?? {});
    if (configurations.length === 0) lists.set(shared, []);
    for (const [configuration, options] of configurations) {
      const assets = options.assets ?? shared;
      lists.set(assets, [...(lists.get(assets) ?? []), configuration]);
    }
    for (const [assets, used] of lists) {
      const label = used.length ? `${name}:${used.join(', ')}` : name;
      let outputs;
      try {
        outputs = await resolve(assets, { root: fe, projectRoot, sourceRoot });
      } catch (error) {
        errors.push(`one source (${label}): its assets do not resolve: ${error.message}`);
        continue;
      }
      for (const { destination, sources } of duplicateOutputs(outputs))
        errors.push(
          `one source (${label}): ${destination} is written by ${sources.length} inputs (${sources.join(', ')}); one output path takes one source`,
        );
      lines.push(`one source (${label}): ${outputs.length} asset file(s)`);
    }
  }
  return { errors, lines };
}
