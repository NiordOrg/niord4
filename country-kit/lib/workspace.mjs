/**
 * The generated workspace of a country: the root files of its fe/ that the Angular CLI, npm,
 * TypeScript, ESLint, Vitest and Playwright read, made from the base product's own files in
 * .niord/base/ (workspace-files.json lists every one and how it is made). A country never writes
 * them, so a base product change to build options, budgets, the service worker, a dependency or a
 * seam's default reaches every country with the version bump.
 *
 * Nothing a pack or the manifest says is written into source text: the language configuration is
 * JSON (language-config.mjs), and the only country values in these files are paths of the
 * country's own tree, the base product files its guarded overrides replace (JSON entries of
 * fileReplacements, a test include and a tsconfig include, never code), the country code in
 * `styles/tokens.<cc>.css`, `styles/fonts.<cc>.css` and the package name — all checked by the
 * manifest's schema and reader — and the country's own npm packages, merged into package.json and
 * package-lock.json as JSON after set-up has checked them (lib/dependencies.mjs). The scripts of
 * package.json carry no manifest value (buildScripts): `build` ends with the checks on every
 * country build, `bundle:info` and `image:info` take the country's paths from the workspace's
 * layout. The kit's commands in them, and the require of its lint blocks in eslint.config.js, name
 * the kit's folder by `kitPath`, relative to fe/ and checked to be a plain relative path (KIT_PATH,
 * the kit package at ../.niord/kit, by default).
 * `e2e` runs the country's Playwright specs in e2e/ through a constant playwright.config.ts, the
 * base product's configuration with the country's folder, whose specs import the base product's
 * login fixtures as `@niord/admin/e2e` (mapped by the generated .niord/tsconfig.e2e.json, which is
 * also the program the specs are linted in, with the administration app).
 *
 * The budgets are the base product's, but for one allowance: each application's `styles` bundle
 * budget rises by the size of the country's font sheet, `styles/fonts.<cc>.css`, which the
 * generated styles add to both applications, so a country's own face never warns and the base
 * product's margin still holds for the rest (raiseStylesBudgets).
 *
 * Country code reaches the base product only through the country API (fe-country-split-plan §4.3,
 * lib/api.mjs): the root tsconfig.json maps the base product's own aliases, `@niord/map/*` and
 * `@niord/html/*`, which its files compile through and lint bans in country files, and the four
 * API entry points. `@niord/<app>/internal/*` is mapped only in the application tsconfigs of an
 * application whose files the country overrides; lint (lib/country-lint.cjs, through the generated
 * eslint.config.js) keeps every country file but an override to the API.
 *
 * Every file is written only when its bytes change.
 */
import fs from 'node:fs';
import path from 'node:path';
import { writeIfChanged } from '../catalog.mjs';
import { mergeDependencies } from './dependencies.mjs';
import { SHIMS } from './language-config.mjs';
import { relocateImports } from './overrides.mjs';

const BASE = '.niord/base';
const GENERATED = '.niord/generated';
const STAGED = '.niord/build';
export const APPS = { admin: 'niord-app', info: 'niord-info' };
// The base product's configurations that belong to no country (the composition rule of
// fe/docs/country-deployments.md). The base product's tree has no other configuration
// (scripts/base-product-tree.test.mjs holds it to this set); a configuration named otherwise
// never reaches a country workspace.
export const CONFIGURATIONS = ['production', 'development', 'local', 'test'];
// The asset overlay of the base product's own tree, never shipped by a country build: the
// default branding the base product's builds stage.
const OVERLAYS = ['assets/default/**'];
/** A country's code: its ISO 3166-1 alpha-2 code, two letters a-z. */
export const COUNTRY_CODE = /^[a-z]{2}$/;
/** The country's Playwright specs, linted with the administration app. */
const E2E_FILES = 'e2e/**/*.ts';

const posix = path.posix;
const into = (relative) => posix.join(BASE, relative);
/** The application a base product path of `ui-apps/<app>/` belongs to. */
const appOf = (file) => /^ui-apps\/(admin|info)\//.exec(file)?.[1];
const json = (value) => `${JSON.stringify(value, null, 2)}\n`;

/**
 * JSON with comments, as angular.json and the tsconfig files are written: `//` and `/* *\/`
 * comments and trailing commas are dropped outside strings before parsing.
 */
export function parseJsonc(text, label = 'the file') {
  let out = '';
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (c === '"') {
      let j = i + 1;
      while (j < text.length && text[j] !== '"') j += text[j] === '\\' ? 2 : 1;
      out += text.slice(i, j + 1);
      i = j;
    } else if (text.startsWith('//', i)) {
      while (i < text.length && text[i] !== '\n') i++;
      out += '\n';
    } else if (text.startsWith('/*', i)) {
      const end = text.indexOf('*/', i + 2);
      i = end < 0 ? text.length : end + 1;
    } else out += c;
  }
  try {
    return JSON.parse(out.replace(/,(\s*[}\]])/g, '$1'));
  } catch (error) {
    throw new Error(`${label}: ${error.message}`, { cause: error });
  }
}

const readJsonc = (file) => parseJsonc(fs.readFileSync(file, 'utf8'), path.basename(file));
const exists = (fe, relative) => fs.existsSync(path.join(fe, relative));

/** Every file under the country's `<app>/` folder, relative to fe/ and with `/`. */
export function countryFiles(fe, app) {
  const dir = path.join(fe, app);
  if (!fs.existsSync(dir)) return [];
  return fs
    .readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) =>
      path.relative(fe, path.join(entry.parentPath, entry.name)).replaceAll('\\', '/'),
    )
    .sort();
}

/**
 * The seams the country fills, per application: `{ replacements: [{ replace, with, configurations }],
 * index }`, the paths relative to fe/. `configurations` undefined means every configuration.
 */
export function filledSeams(fe, seams) {
  const out = Object.fromEntries(
    Object.keys(APPS).map((app) => [app, { replacements: [], index: undefined }]),
  );
  for (const seam of seams.seams ?? []) {
    if (!out[seam.app] || !exists(fe, seam.country)) continue;
    if (seam.option === 'index') out[seam.app].index = seam.country;
    else
      out[seam.app].replacements.push({
        replace: into(seam.replaces),
        with: seam.country,
        configurations: Array.isArray(seam.configurations) ? seam.configurations : undefined,
      });
  }
  return out;
}

/** The generated replacement of each application's language configuration. */
export function languageReplacement(app, seams) {
  const generated = (seams.generated ?? []).find(
    (entry) => entry.app === app && entry.id === `${app}.languages`,
  );
  const replaces =
    generated?.replaces ?? `ui-apps/${app}/src/app/country-specific/default/language.config.ts`;
  return { replace: into(replaces), with: `${GENERATED}/${app}/language.config.ts` };
}

/** A base product asset entry rewritten for the workspace, or undefined when a country never ships it. */
function asset(entry, app) {
  if (typeof entry === 'string') return into(entry);
  const input = entry.input.replace(/\/$/, '');
  if (input.startsWith('.niord/')) {
    const { ignore: _ignore, ...rest } = entry;
    return rest;
  }
  if (input.startsWith(`ui-apps/${app}/public/assets/`)) return undefined;
  const rewritten = { ...entry, input: into(input) };
  if (input === `ui-apps/${app}/public`)
    rewritten.ignore = [...new Set([...(entry.ignore ?? []), ...OVERLAYS])].sort();
  return rewritten;
}

/**
 * The fileReplacements of one configuration: the base product's, the country's seams and guarded
 * overrides, the languages. A configuration's list replaces the options' list, so each carries
 * every override.
 */
function replacements({ base = [], seams, configuration, language, overrides = [] }) {
  const list = base.map((entry) => ({ replace: into(entry.replace), with: into(entry.with) }));
  for (const seam of [...seams, ...overrides]) {
    if (seam.configurations && !seam.configurations.includes(configuration)) continue;
    const existing = list.find((entry) => entry.replace === seam.replace);
    if (existing) existing.with = seam.with;
    else list.push({ replace: seam.replace, with: seam.with });
  }
  list.push(language);
  return list;
}

/**
 * Where the workspace keeps its copy of a base product test set-up file. Vitest transforms a
 * set-up file itself, reading the tsconfig.json nearest to it, and the base product's own
 * tsconfig.json in .niord/base/ names projects the zip does not carry; a copy beside the generated
 * files is read with this workspace's instead. A set-up file imports packages only.
 */
export const setupCopy = (app, file) => `${GENERATED}/${app}/${posix.basename(file)}`;

/**
 * Where the workspace runs the base product's spec of an overridden file: a copy below
 * .niord/generated/<app>/base-specs/, at the spec's path below src/app/, for the same reason as a
 * set-up file's copy. Its relative imports are rewritten to reach the same files in .niord/base/,
 * so its import of the overridden file reaches the country's copy through fileReplacements.
 */
export const baseSpecCopy = (spec) => {
  const [, app, rest] = /^ui-apps\/(admin|info)\/src\/app\/(.+)$/.exec(spec);
  return `${GENERATED}/${app}/base-specs/${rest}`;
};

// A budget's size as Angular reads an absolute one: a number and an optional unit, 1 kB being
// 1000 bytes (@angular/build's bundle calculator). A percentage reads against a baseline.
const SIZE = /^(\d+(?:\.\d+)?)\s*([kmg]?b)?$/i;
const UNIT = { b: 1, kb: 1000, mb: 1000 ** 2, gb: 1000 ** 3 };

/**
 * A budget list with each `styles` bundle budget's maximumWarning and maximumError raised by
 * `allowance` bytes, written back in kB. A budget with a baseline, a size that is not absolute and
 * an allowance of 0 are left as written.
 */
export function raiseStylesBudgets(budgets, allowance) {
  if (!allowance || !Array.isArray(budgets)) return budgets;
  return budgets.map((budget) => {
    if (budget?.type !== 'bundle' || budget.name !== 'styles' || budget.baseline !== undefined)
      return budget;
    const raised = { ...budget };
    for (const key of ['maximumWarning', 'maximumError']) {
      const size = typeof budget[key] === 'string' ? SIZE.exec(budget[key].trim()) : null;
      if (!size) continue;
      const bytes = Number(size[1]) * UNIT[(size[2] ?? 'b').toLowerCase()] + allowance;
      raised[key] = `${Number((bytes / 1000).toFixed(3))}kB`;
    }
    return raised;
  });
}

/** One application's project in the generated angular.json. */
function project({ app, name, baseProject, seams, styles, allowance, overrides, baseSpecs }) {
  const root = into(baseProject.root);
  const sourceRoot = into(baseProject.sourceRoot);
  const build = baseProject.architect.build;
  const language = languageReplacement(app, seams.raw);
  const filled = seams[app];
  const replaced = overrides
    .filter((entry) => appOf(entry.file) === app)
    .map((entry) => ({ replace: into(entry.file), with: entry.with }));
  const options = { ...build.options };
  for (const key of ['browser', 'main', 'polyfills', 'server', 'serviceWorker'])
    if (typeof options[key] === 'string') options[key] = into(options[key]);
  options.index = filled.index ?? (options.index ? into(options.index) : undefined);
  if (options.index === undefined) delete options.index;
  options.tsConfig = `.niord/tsconfig.${app}.app.json`;
  options.assets = (options.assets ?? []).map((entry) => asset(entry, app)).filter(Boolean);
  options.styles = styles;
  if (options.budgets) options.budgets = raiseStylesBudgets(options.budgets, allowance);
  options.fileReplacements = replacements({
    base: options.fileReplacements,
    seams: filled.replacements.filter((seam) => !seam.configurations),
    configuration: undefined,
    language,
    overrides: replaced,
  });

  const configurations = {};
  for (const configuration of CONFIGURATIONS) {
    const own = build.configurations?.[configuration];
    if (!own) continue;
    const copy = structuredClone(own);
    for (const key of ['serviceWorker', 'index', 'browser'])
      if (typeof copy[key] === 'string') copy[key] = into(copy[key]);
    if (copy.styles) copy.styles = styles;
    if (copy.budgets) copy.budgets = raiseStylesBudgets(copy.budgets, allowance);
    if (copy.assets) copy.assets = copy.assets.map((entry) => asset(entry, app)).filter(Boolean);
    if (filled.index && copy.index) copy.index = filled.index;
    copy.fileReplacements = replacements({
      base: own.fileReplacements,
      seams: filled.replacements,
      configuration,
      language,
      overrides: replaced,
    });
    configurations[configuration] = copy;
  }
  const kept = Object.keys(configurations);

  const serve = structuredClone(baseProject.architect.serve);
  serve.options = { ...serve.options, proxyConfig: 'proxy.conf.mjs' };
  serve.configurations = Object.fromEntries(
    Object.entries(serve.configurations ?? {}).filter(([configuration]) =>
      kept.includes(configuration),
    ),
  );

  const test = structuredClone(baseProject.architect.test);
  test.options = {
    ...test.options,
    tsConfig: `.niord/tsconfig.${app}.spec.json`,
    setupFiles: (test.options?.setupFiles ?? []).map((file) => setupCopy(app, file)),
    // The country's specs, and the base product's spec of each file it overrides, which the
    // fileReplacements above point at the country's copy.
    include: [
      `${posix.relative(sourceRoot, app)}/**/*.spec.ts`,
      ...baseSpecs
        .filter((spec) => appOf(spec) === app)
        .map((spec) => posix.relative(sourceRoot, baseSpecCopy(spec))),
    ],
  };
  delete test.options.exclude;

  const lint = structuredClone(baseProject.architect.lint);
  // The templates of the country's code and of its overrides; never `${app}/**/*.html`: the
  // legal documents under assets/ are not templates. The e2e specs go with the administration
  // app, whose login fixtures they use.
  lint.options = {
    ...lint.options,
    lintFilePatterns: [
      `${app}/**/*.ts`,
      `${app}/app/**/*.html`,
      `${app}/overrides/**/*.html`,
      ...(app === 'admin' ? [E2E_FILES] : []),
    ],
  };

  const architect = {
    build: { ...build, options, configurations, defaultConfiguration: build.defaultConfiguration },
    serve,
    test,
    lint,
  };
  return { [name]: { ...baseProject, root, sourceRoot, architect } };
}

/**
 * The generated angular.json, from the base product's. `overrides` are the manifest's guarded
 * overrides, `baseSpecs` the base product's specs of the files they replace.
 */
export function angularJson({ fe, cc, baseAngular, seams, overrides = [], baseSpecs = [] }) {
  if (!COUNTRY_CODE.test(cc))
    throw new Error(`"${cc}" is not a country code, its ISO 3166-1 alpha-2 code, two letters a-z`);
  const filled = { ...filledSeams(fe, seams), raw: seams };
  const fonts = exists(fe, `styles/fonts.${cc}.css`) ? `styles/fonts.${cc}.css` : undefined;
  // What the font sheet adds to each application's styles bundle, at most its own size.
  const allowance = fonts ? fs.statSync(path.join(fe, fonts)).size : 0;
  const projects = {};
  for (const [app, name] of Object.entries(APPS)) {
    const baseProject = baseAngular.projects?.[name];
    if (!baseProject) throw new Error(`the base product's angular.json has no project ${name}`);
    const styles = [
      `${GENERATED}/${app}/styles.css`,
      exists(fe, `styles/tokens.${cc}.css`)
        ? `styles/tokens.${cc}.css`
        : into('styles/tokens.active.css'),
      ...(fonts ? [fonts] : []),
    ];
    Object.assign(
      projects,
      project({
        app,
        name,
        baseProject,
        seams: filled,
        styles,
        allowance,
        overrides,
        baseSpecs,
      }),
    );
  }
  return {
    ...baseAngular,
    $schema: './node_modules/@angular/cli/lib/config/schema.json',
    projects,
  };
}

/**
 * The applications whose country folder holds specs, or that run a base product spec against an
 * override (`baseSpecs`), and those that hold code to lint: the administration app's also when
 * e2e/ holds a spec, which its lint target reads.
 */
export function countryCode(fe, baseSpecs = []) {
  const specs = [];
  const code = [];
  const e2e = countryFiles(fe, 'e2e').some((file) => file.endsWith('.ts'));
  for (const [app, name] of Object.entries(APPS)) {
    const files = countryFiles(fe, app);
    if (
      files.some((file) => file.endsWith('.spec.ts')) ||
      baseSpecs.some((spec) => appOf(spec) === app)
    )
      specs.push(name);
    if (
      files.some((file) => /\.(ts|html)$/.test(file) && !file.includes('/assets/')) ||
      (app === 'admin' && e2e)
    )
      code.push(name);
  }
  return { specs, code };
}

/**
 * The kit's folder relative to the country's fe/, as the generated scripts and lint configuration
 * name it by default: the kit package at the repository root's .niord/kit/, where set-up leaves it
 * from the pinned package and from a checkout alike.
 */
export const KIT_PATH = '../.niord/kit';

/** A segment of a kit path: letters, digits, `.`, `_` and `-`, never `.` or `..` alone. */
const KIT_PATH_SEGMENT = /^[A-Za-z0-9._-]+$/;

/**
 * Refuses a kit path that is not leading `..` segments and then at least one plain segment, `/`
 * between them: the text goes into npm scripts and a require string, so nothing a shell or a
 * string literal reads otherwise, and no absolute path, may reach them.
 */
function checkKitPath(kitPath) {
  const segments = typeof kitPath === 'string' ? kitPath.split('/') : [];
  const first = segments.findIndex((segment) => segment !== '..');
  const plain = (segment) => KIT_PATH_SEGMENT.test(segment) && segment !== '.' && segment !== '..';
  if (first < 0 || !segments.slice(first).every(plain))
    throw new Error(`"${kitPath}" is not a relative path of the kit`);
}

/** The kit's audit of the installed tree (audit.mjs): run first by `build`, alone by `audit`. */
const auditScript = (kitPath) => `node ${kitPath}/audit.mjs`;

/**
 * The scripts that build and check a country's output, with no manifest value, the kit's commands
 * run from `kitPath`: `build` refuses a workspace staged from a local source (guard-build.mjs),
 * audits the installed tree, builds both applications — the public site with the stats its checks
 * read — and ends with the checks on every country build (check-build.mjs); `bundle:info` is the
 * base product's bundle report against the country's own info/bundle-baseline.json, which
 * `-- --update-baseline` records; `image:info -- --tag <ref>` makes the public site's image from
 * the built output and checks it (image-info.mjs).
 */
export function buildScripts(kitPath = KIT_PATH) {
  checkKitPath(kitPath);
  return {
    build: `node ${kitPath}/guard-build.mjs && ${auditScript(kitPath)} && ng build niord-app && ng build niord-info --stats-json && node ${kitPath}/check-build.mjs`,
    'bundle:info':
      'node .niord/base/scripts/info-bundle-report.mjs --root .niord/base/ --dist dist/niord-info/browser --stats dist/niord-info/browser-stats.json --baseline info/bundle-baseline.json --json dist/niord-info-bundle-report.json --md dist/niord-info-bundle-report.md',
    'image:info': `node ${kitPath}/image-info.mjs`,
  };
}

/** The scripts of the kit at KIT_PATH, ../.niord/kit. */
export const BUILD_SCRIPTS = buildScripts();

/**
 * The country's Playwright specs in e2e/, with the generated playwright.config.ts; a country
 * without specs passes, so an untouched one is green.
 */
export const E2E_SCRIPT = 'playwright test --pass-with-no-tests';

/**
 * The generated package.json, from the base product's. `dependencies` are the base product's and
 * the country's merged (mergeDependencies), by default the base product's alone; `kitPath` the
 * kit's folder the kit's commands run from (buildScripts).
 */
export function packageJson({
  fe,
  cc,
  basePackage,
  baseSpecs = [],
  dependencies = basePackage.dependencies,
  kitPath = KIT_PATH,
}) {
  if (!COUNTRY_CODE.test(cc))
    throw new Error(`"${cc}" is not a country code, its ISO 3166-1 alpha-2 code, two letters a-z`);
  const kit = buildScripts(kitPath);
  const { specs, code } = countryCode(fe, baseSpecs);
  const none = (what) => `node -e "console.log('no country ${what}')"`;
  const scripts = {
    ng: 'ng',
    'start:admin': 'ng serve niord-app',
    'start:info': 'ng serve niord-info --port 4400',
    build: kit.build,
    'bundle:info': kit['bundle:info'],
    'image:info': kit['image:info'],
    audit: auditScript(kitPath),
    e2e: E2E_SCRIPT,
    test: specs.length
      ? specs.map((name) => `ng test ${name} --watch=false`).join(' && ')
      : none('specs'),
    lint: code.length
      ? code.map((name) => `ng lint ${name} --max-warnings 0`).join(' && ')
      : none('code'),
  };
  const out = { name: `niord-${cc}-fe`, version: basePackage.version, private: true, scripts };
  for (const key of ['packageManager', 'engines', 'dependencies', 'devDependencies', 'overrides'])
    if (basePackage[key] !== undefined) out[key] = basePackage[key];
  if (dependencies !== undefined) out.dependencies = dependencies;
  return out;
}

/**
 * The lockfile under the workspace's name: the base product's, or the base product's with the
 * country's entries merged in (mergeDependencies).
 */
export function packageLock({ cc, baseLock }) {
  const name = `niord-${cc}-fe`;
  const lock = structuredClone(baseLock);
  lock.name = name;
  if (lock.packages?.['']) lock.packages[''].name = name;
  return lock;
}

/**
 * The generated tsconfig.json: the base product's options, its aliases moved into .niord/base/ —
 * `@niord/map/*` and `@niord/html/*`, which the base product's own files compile through, and the
 * country API's four entry points. Nothing maps `@niord/<app>/internal/*` here (appTsconfig).
 */
export function tsconfigJson({ baseTsconfig }) {
  const compilerOptions = { ...baseTsconfig.compilerOptions };
  const paths = {};
  for (const [alias, targets] of Object.entries(compilerOptions.paths ?? {}))
    paths[alias] = targets.map((target) => `./${into(posix.normalize(target))}`);
  compilerOptions.paths = paths;
  return {
    compileOnSave: false,
    compilerOptions,
    angularCompilerOptions: baseTsconfig.angularCompilerOptions,
    files: [],
    references: Object.keys(APPS).flatMap((app) => [
      { path: `./.niord/tsconfig.${app}.app.json` },
      { path: `./.niord/tsconfig.${app}.spec.json` },
    ]),
  };
}

/**
 * One application tsconfig in .niord/, from the base product's: its patterns moved into
 * .niord/base/, and the country's folder and the generated files added. `spec` keeps the base's
 * spec helpers but none of its specs: the country's specs, and the copies of the base product's
 * specs of the files it overrides (baseSpecCopy), are what the country's tests run.
 *
 * `internal` (the country overrides a file of this application) adds `@niord/<app>/internal/*`,
 * which an override's copy imports base product internals through. A child's `paths` replace its
 * parent's, so this file then declares them all: `rootPaths` (the generated tsconfig.json's),
 * re-based to .niord/, and the alias. TypeScript maps paths per program, not per file; lint keeps
 * every country file but an override to the API.
 */
export function appTsconfig({ app, baseConfig, spec, internal = false, rootPaths = {} }) {
  const move = (pattern) =>
    posix.join('base', posix.normalize(posix.join('ui-apps', app, pattern)));
  const include = (baseConfig.include ?? [])
    .filter((p) => !spec || !p.endsWith('.spec.ts'))
    .map(move);
  const exclude = (baseConfig.exclude ?? []).map(move);
  const compilerOptions = { ...baseConfig.compilerOptions };
  if (compilerOptions.outDir)
    compilerOptions.outDir = posix.join('..', 'out-tsc', posix.basename(compilerOptions.outDir));
  include.push(`../${app}/**/*.ts`, `generated/${app}/**/*.ts`);
  if (!spec) exclude.push(`../${app}/**/*.spec.ts`, `generated/${app}/**/*.spec.ts`);
  if (internal) {
    const rebase = (target) => `./${posix.relative('.niord', posix.normalize(target))}`;
    compilerOptions.paths = {
      ...Object.fromEntries(
        Object.entries(rootPaths).map(([alias, targets]) => [alias, targets.map(rebase)]),
      ),
      [`@niord/${app}/internal/*`]: [`./base/ui-apps/${app}/src/app/*`],
    };
  }
  return {
    extends: '../tsconfig.json',
    compilerOptions,
    include,
    ...(exclude.length ? { exclude } : {}),
  };
}

/** Where the country's e2e specs find the base product's login fixtures, `@niord/admin/e2e`. */
export const E2E_TSCONFIG = '.niord/tsconfig.e2e.json';

/**
 * The generated eslint.config.js: the base product's lint configuration, then the country blocks
 * of the kit at `kitPath`, required as `./<kitPath>` or, for a path above fe/, as it is.
 */
export function eslintConfig(kitPath = KIT_PATH) {
  checkKitPath(kitPath);
  const kit = kitPath.startsWith('../') ? kitPath : `./${kitPath}`;
  return `// Generated by the country kit: the base product's lint configuration, applied to the country's
// files. The base product's globs are relative to this file, so its general blocks reach the
// country's folders; the type-aware rules read this workspace's tsconfig.json. The e2e specs are
// in no program of it, so they are parsed with their own, ${E2E_TSCONFIG}; the base product's
// project service is switched off for them, since the parser takes a project or the service,
// never both. The country blocks come last: imports only through the country API (in the e2e
// specs, the login fixtures, Playwright, node: and their own folder), the security rules restated
// for the country's folders, and no inline disabling (country-kit/lib/country-lint.cjs).
const base = require('./.niord/base/eslint.config.js');
const { countryBlocks } = require('${kit}/lib/country-lint.cjs');

module.exports = [
  { ignores: ['.niord/**', 'dist/**', '.angular/**', 'node_modules/**'] },
  ...base,
  {
    files: ['admin/**/*.ts', 'info/**/*.ts'],
    languageOptions: {
      parserOptions: { projectService: true, tsconfigRootDir: __dirname },
    },
  },
  {
    files: ['${E2E_FILES}'],
    languageOptions: {
      parserOptions: {
        projectService: false,
        project: './${E2E_TSCONFIG}',
        tsconfigRootDir: __dirname,
      },
    },
  },
  ...countryBlocks({ dir: __dirname }),
];
`;
}

/** The lint configuration of the kit at KIT_PATH, ../.niord/kit. */
export const ESLINT_CONFIG = eslintConfig();

export const VITEST_CONFIG = `// Generated by the country kit: the base product's Vitest configuration.
export { default } from './.niord/base/vitest.config';
`;

export const PROXY_CONFIG = `// Generated by the country kit: the base product's dev-server proxy.
export { default } from './.niord/base/proxy.conf.mjs';
`;

export const PLAYWRIGHT_CONFIG = `// Generated by the country kit: the base product's Playwright configuration, run over the
// country's own specs in e2e/. They import the base product's login fixtures from
// '@niord/admin/e2e', which ${E2E_TSCONFIG} maps into .niord/base/.
import { defineConfig } from '@playwright/test';
import base from './.niord/base/playwright.config';

export default defineConfig({
  ...base,
  testDir: './e2e',
  tsconfig: './${E2E_TSCONFIG}',
});
`;

/**
 * The TypeScript configuration of the country's specs. Playwright compiles them with it (its
 * \`tsconfig\` option, which replaces the nearest tsconfig.json for every file it loads) and reads
 * the alias of the base product's login fixtures from it; ESLint parses them in its program, which
 * is why it includes them. It takes the workspace's options and no ambient types, as the base
 * product's own e2e/tsconfig.json does. A child's \`paths\` replace its parent's, so TypeScript and
 * the lint resolve no other alias of the base product; Playwright's loader also walks the root's
 * \`references\`, which is why the lint, not this file, keeps a spec to the login fixtures.
 */
export const e2eTsconfig = () => ({
  extends: '../tsconfig.json',
  compilerOptions: {
    types: [],
    paths: { '@niord/admin/e2e': ['./base/ui-apps/admin/e2e/auth/fixtures.ts'] },
  },
  include: ['../e2e/**/*.ts'],
});

/**
 * The Tailwind entry of one application: the base product's entry stylesheet, and the country's
 * folder in the utility scan, so a utility only a country template uses is built too. Tailwind
 * resolves a relative \`@source\` against the file it is written in, so the base product's own
 * lines still reach its sources from here.
 */
export const styleWrapper = (
  app,
) => `/* Generated by the country kit: the base product's entry stylesheet, and the country's own
   templates in the utility scan. */
@import '../../base/ui-apps/${app}/src/styles.css';
@source '../../../${app}';
@source not '../../../${app}/**/*.spec.ts';
`;

/**
 * Writes the whole workspace into `fe`: the root files, the application tsconfigs, the style
 * wrappers and the language configuration (`languages`: per application, the JSON's value).
 * `overrides` and `baseSpecs` as for angularJson; `country` the country's own npm packages as
 * readCountryDependencies read them (null for none), already checked; `kitPath` the kit's folder
 * relative to fe/, which package.json's scripts and eslint.config.js name. Returns the files
 * written.
 */
export function generateWorkspace({
  fe,
  cc,
  seams,
  languages,
  overrides = [],
  baseSpecs = [],
  country = null,
  kitPath = KIT_PATH,
}) {
  const base = path.join(fe, '.niord', 'base');
  const baseAngular = readJsonc(path.join(base, 'angular.json'));
  const files = new Map();
  files.set(
    'angular.json',
    json(angularJson({ fe, cc, baseAngular, seams, overrides, baseSpecs })),
  );
  for (const [app, name] of Object.entries(APPS))
    for (const file of baseAngular.projects[name].architect.test?.options?.setupFiles ?? [])
      files.set(setupCopy(app, file), fs.readFileSync(path.join(base, file), 'utf8'));
  for (const spec of baseSpecs)
    files.set(
      baseSpecCopy(spec),
      relocateImports(fs.readFileSync(path.join(base, spec), 'utf8'), {
        from: into(spec),
        to: baseSpecCopy(spec),
      }),
    );
  // A copy of a base spec whose override has gone is removed with it.
  for (const app of Object.keys(APPS)) {
    const dir = path.join(fe, GENERATED, app, 'base-specs');
    if (!fs.existsSync(dir)) continue;
    for (const entry of fs.readdirSync(dir, { recursive: true, withFileTypes: true }))
      if (entry.isFile()) {
        const file = path.join(entry.parentPath, entry.name);
        if (!files.has(path.relative(fe, file).replaceAll('\\', '/'))) fs.rmSync(file);
      }
  }
  const basePackage = JSON.parse(fs.readFileSync(path.join(base, 'package.json'), 'utf8'));
  const merged = mergeDependencies({
    basePackage,
    baseLock: JSON.parse(fs.readFileSync(path.join(base, 'package-lock.json'), 'utf8')),
    country,
  });
  files.set(
    'package.json',
    json(
      packageJson({
        fe,
        cc,
        basePackage,
        baseSpecs,
        dependencies: merged.dependencies,
        kitPath,
      }),
    ),
  );
  files.set('package-lock.json', json(packageLock({ cc, baseLock: merged.lock })));
  for (const name of ['.npmrc', '.nvmrc', '.postcssrc.json'])
    files.set(name, fs.readFileSync(path.join(base, name), 'utf8'));
  const tsconfig = tsconfigJson({ baseTsconfig: readJsonc(path.join(base, 'tsconfig.json')) });
  files.set('tsconfig.json', json(tsconfig));
  for (const app of Object.keys(APPS)) {
    const internal = overrides.some((entry) => appOf(entry.file) === app);
    for (const [kind, spec] of [
      ['app', false],
      ['spec', true],
    ])
      files.set(
        `.niord/tsconfig.${app}.${kind}.json`,
        json(
          appTsconfig({
            app,
            spec,
            internal,
            rootPaths: tsconfig.compilerOptions.paths,
            baseConfig: readJsonc(path.join(base, 'ui-apps', app, `tsconfig.${kind}.json`)),
          }),
        ),
      );
    files.set(`${GENERATED}/${app}/styles.css`, styleWrapper(app));
    files.set(`${GENERATED}/${app}/language.config.json`, json(languages[app]));
    files.set(`${GENERATED}/${app}/language.config.ts`, SHIMS[app]);
  }
  files.set('eslint.config.js', eslintConfig(kitPath));
  files.set('vitest.config.ts', VITEST_CONFIG);
  files.set('proxy.conf.mjs', PROXY_CONFIG);
  files.set('playwright.config.ts', PLAYWRIGHT_CONFIG);
  files.set(E2E_TSCONFIG, json(e2eTsconfig()));
  const written = [];
  for (const [relative, text] of files)
    if (writeIfChanged(path.join(fe, relative), text)) written.push(relative);
  return { files: [...files.keys()], written };
}

/** The staging folder each application's assets come from. */
export const stagedFolder = (app) => `${STAGED}/${app}`;

/**
 * The root entries of the base product's fe/ (`roots`, file and folder names) that
 * workspace-files.json (`list`) neither makes nor names as not needed: each is a root file the
 * generated workspace may lack without anyone having decided so.
 */
export const unlistedRootFiles = (roots, list) =>
  roots.filter((name) => !Object.hasOwn(list.files, name) && !Object.hasOwn(list.notNeeded, name));
