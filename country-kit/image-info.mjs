#!/usr/bin/env node
/**
 * `image:info` — the public site's image, packaged from a built dist and checked before a push.
 *
 *   npm run build && npm run image:info -- --tag <registry>/<name>:<version>   # a country, from fe/
 *   npm run build:info && npm run image:info -- --tag niord-info               # the base build (manual)
 *   node <kit>/image-info.mjs --tag <ref> [--dist <dir>] [--package-only] [--no-browser]
 *
 * It refuses, before docker builds anything (fe-country-split-plan §3.2, §4.8, §4.11 rule 5):
 *   - a malformed tag (lib/info-image.mjs parseTag): no digest, no leading "-", a tag a country
 *     must name; the reference is one argument of docker's, never part of a command line;
 *   - in a country workspace: --package-only (a country's image is always checked); NIORD_BASE or
 *     a NIORD_PACK_<LANG> while CI is set; a workspace with no build report, or one set up from a
 *     local source (guard-build.mjs's refusal) — what is pushed is built from the pins alone;
 *   - a dist that is not a production build or holds a file the image may not ship
 *     (outputProblems): a source map or a sourceMappingURL, an index.html bundle without a
 *     content hash, a pre-compressed twin, a link or other non-regular file, and any file outside
 *     info-image-allowed.json.
 *
 * Then it assembles the build context in a fresh temporary folder — the base product's Dockerfile,
 * its five nginx files and the dist's copy, pre-compressed there; never the workspace — runs
 * `docker build` and removes the folder. A country's image carries the build report as the OCI
 * labels org.niord.build-report and org.opencontainers.image.version (§4.11 rule 2), written as a
 * LABEL instruction into the context's copy of the Dockerfile, never on the command line, and read
 * back with docker inspect after the build: a label that differs from the report fails the run. A
 * docker or node call that cannot start is named with its error's code and message.
 *
 * In a country it then checks the image it built, never the dist beside it: the image's web root
 * and security headers are copied out (docker create, docker cp) and must hold exactly the dist's
 * files, byte for byte, beside their .br/.gz twins — which binds the build's stats to the image —
 * and then run, each as a child process of this Node with constant arguments:
 *   - the separation check on the build's stats (--root .niord/base/ --country <cc>);
 *   - the layout check on the copied web root, under the copied policy (Chromium);
 *   - the container test against the image, in the site's default language, with a legal
 *     document the build staged, and --browser.
 * A country that has no legal document yet, or no branding text for its default language — one
 * fresh from `init` — is not refused: image:info warns, naming the file to add, and the container
 * test skips those probes by name (--no-legal-doc, --no-branding-text), so the image job of an
 * untouched country is green from its first push.
 * `--no-browser` leaves out the layout check and the container test's browser part, and says the
 * image is not checked for a push. The tag is left for the push, which is the country workflow's
 * (templates/image.yml), never this script's; a caller that does not push removes it.
 *
 * `--package-only` packages the base build without checking it, a manual tool (fe/AGENTS.md):
 * nothing in CI runs it and its image is never pushed, so the tag may leave out its version;
 * layout:info and test:container:info check it by hand.
 *
 * Exit codes: 0 built (and in a country checked), 1 refused or a check failed (every finding is
 * printed), 2 a usage error, or docker or Chromium missing.
 */
import fs from 'node:fs';
import { createRequire } from 'node:module';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { refusal, REPORT } from './guard-build.mjs';
import {
  assembleContext,
  defaultRun,
  dockerBuildArgs,
  imageFilesProblems,
  labelProblems,
  outputProblems,
  parseTag,
  readAllowList,
  runFailure,
} from './lib/info-image.mjs';
import { readManifest } from './lib/manifest.mjs';
import { where } from './lib/where.mjs';

const KIT = path.dirname(fileURLToPath(import.meta.url));

const USAGE =
  'usage: image-info.mjs --tag <registry>/<name>:<version> [--dist <dir>] [--package-only] [--no-browser]';

/** Where the base product's fe/ sits in a country workspace, as the public site's checks take it. */
const BASE_PREFIX = '.niord/base/';

/** `: <stderr>` of a captured call that wrote any, else ''. */
const withStderr = (result) => (result.stderr?.trim() ? `: ${result.stderr.trim()}` : '');

class Stop extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

function parseArgs(argv) {
  const options = {
    tag: undefined,
    dist: 'dist/niord-info/browser',
    packageOnly: false,
    browser: true,
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--tag' && argv[i + 1] !== undefined) options.tag = argv[++i];
    else if (arg === '--dist' && argv[i + 1] !== undefined) options.dist = argv[++i];
    else if (arg === '--package-only') options.packageOnly = true;
    else if (arg === '--no-browser') options.browser = false;
    else throw new Stop(2, `unknown or incomplete option ${arg}\n${USAGE}`);
  }
  if (options.tag === undefined) throw new Stop(2, `--tag is required\n${USAGE}`);
  return options;
}

/**
 * True when `kit` is a country's kit (the layout resolver finds it outside the base product's
 * tree) and `cwd` is the workspace it serves, the country's fe/. Throws on a kit in no layout it
 * knows.
 */
export function isCountryWorkspace(kit, cwd) {
  const layout = where(kit);
  return (
    layout.kind !== 'source' &&
    path.relative(path.resolve(layout.workspace), path.resolve(cwd)) === ''
  );
}

/** Why the environment may not build a country's image: a local override while CI is set. */
export function ciOverrideProblem(env) {
  if (!env.CI) return undefined;
  const set = Object.keys(env)
    .filter((name) => name === 'NIORD_BASE' || name.startsWith('NIORD_PACK_'))
    .filter((name) => env[name])
    .sort();
  return set.length
    ? `${set.join(', ')} ${set.length === 1 ? 'is' : 'are'} set while CI is: an image is built from the pinned zip and packs only`
    : undefined;
}

/**
 * The legal document the container test asks for: the default language's disclaimer when the
 * build staged it, else the first legal document staged; undefined when there is none.
 */
export function legalDocument(cwd, lang) {
  const dir = path.join(cwd, '.niord', 'build', 'info', 'legal');
  const staged = fs.existsSync(dir)
    ? fs
        .readdirSync(dir)
        .filter((name) => name.endsWith('.html'))
        .sort()
    : [];
  const preferred = `disclaimer.${lang}.html`;
  return staged.includes(preferred) ? preferred : staged[0];
}

/**
 * True when the build staged the public site's branding text for `lang`,
 * assets/branding/<lang>.json.
 */
export function hasBrandingText(cwd, lang) {
  return fs.existsSync(path.join(cwd, '.niord', 'build', 'info', 'branding', `${lang}.json`));
}

/**
 * Undefined when Playwright's Chromium starts from the workspace in `cwd`, else why not. It is
 * started, not looked for: which browser build a launch takes is Playwright's to decide.
 */
export async function chromiumProblem(cwd) {
  let chromium;
  try {
    ({ chromium } = createRequire(path.join(cwd, 'package.json'))('@playwright/test'));
  } catch (error) {
    return `@playwright/test cannot be loaded from ${cwd} (npm ci): ${error.message.split('\n')[0]}`;
  }
  try {
    const browser = await chromium.launch();
    await browser.close();
    return undefined;
  } catch (error) {
    return `Chromium does not start: ${error.message.split('\n')[0]}`;
  }
}

/**
 * The OCI labels of a country's image, name → value: the build report as compact JSON and, when
 * it names one, the base product's version.
 */
export function reportLabels(cwd) {
  const report = JSON.parse(fs.readFileSync(path.join(cwd, REPORT), 'utf8'));
  const labels = { 'org.niord.build-report': JSON.stringify(report) };
  if (typeof report.base?.version === 'string')
    labels['org.opencontainers.image.version'] = report.base.version;
  return labels;
}

/**
 * The whole command, with every dependency on the machine passed in: `run(command, args,
 * { capture, cwd })` starts docker and node (lib/info-image.mjs defaultRun), `chromium(cwd)`
 * answers whether Chromium starts. Answers the exit code.
 */
export async function imageInfo({
  argv,
  cwd = process.cwd(),
  env = process.env,
  kit = KIT,
  run = defaultRun,
  chromium = chromiumProblem,
  log = console.log,
  error = console.error,
}) {
  let options;
  try {
    options = parseArgs(argv);
    let base;
    try {
      base = where(kit).base;
    } catch (problem) {
      throw new Stop(2, problem.message);
    }
    const country = isCountryWorkspace(kit, cwd);
    if (!country && !options.packageOnly)
      throw new Stop(
        2,
        "outside a country workspace image:info packages the base product's own images only: pass --package-only (npm run image:info does)",
      );
    try {
      parseTag(options.tag, { requireTag: country });
    } catch (problem) {
      throw new Stop(2, `--tag: ${problem.message}`);
    }
    const dist = path.resolve(cwd, options.dist);
    const stats = path.join(path.dirname(dist), 'browser-stats.json');

    let manifest;
    let legal;
    let brandingText = true;
    let labels = {};
    if (country) {
      if (options.packageOnly)
        throw new Stop(
          2,
          "--package-only is the base product's own images; a country's image is always checked before a push",
        );
      const override = ciOverrideProblem(env);
      if (override) throw new Stop(1, override);
      const guard = refusal(cwd);
      if (guard) throw new Stop(1, guard);
      manifest = readManifest(path.join(cwd, 'niord-country.json'));
      const lang = manifest.apps.info.defaultLanguage;
      legal = legalDocument(cwd, lang);
      if (!legal)
        error(
          `image:info: warning: .niord/build/info/legal/ holds no legal document, so the container test skips its legal-document probes (--no-legal-doc): add info/assets/legal/disclaimer.${lang}.html and run \`node setup.mjs\``,
        );
      brandingText = hasBrandingText(cwd, lang);
      if (!brandingText)
        error(
          `image:info: warning: .niord/build/info/branding/ holds no ${lang}.json, so the container test skips its branding-text probe (--no-branding-text): add info/assets/branding/${lang}.json and run \`node setup.mjs\``,
        );
      if (!fs.existsSync(stats))
        throw new Stop(
          2,
          `${path.relative(cwd, stats).replaceAll('\\', '/')} does not exist: build with \`npm run build\` (it passes --stats-json)`,
        );
      labels = reportLabels(cwd);
      if (options.browser) {
        const problem = await chromium(cwd);
        if (problem)
          throw new Stop(
            2,
            `${problem}\n  install it with \`npx playwright install --only-shell chromium\`, or pass --no-browser for an image that is not pushed`,
          );
      }
    }

    const allowed = readAllowList();
    const problems = outputProblems(dist, allowed);
    if (problems.length) {
      throw new Stop(
        1,
        `${options.dist} cannot be packaged (${problems.length} problem(s)):\n  ${problems.join('\n  ')}`,
      );
    }
    const docker = run('docker', ['version', '--format', '{{.Server.Version}}'], { capture: true });
    if (docker.status !== 0)
      throw new Stop(2, `docker does not answer (${runFailure(docker)})${withStderr(docker)}`);

    const context = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-info-image-'));
    try {
      const totals = assembleContext({
        browserDir: dist,
        baseRoot: base,
        out: context,
        allowed,
        labels,
      });
      const kb = (bytes) => `${(bytes / 1024).toFixed(1)} kB`;
      log(
        `image:info: context assembled; ${totals.files} text file(s) pre-compressed, ${kb(totals.raw)} raw -> ${kb(totals.brotli)} brotli / ${kb(totals.gzip)} gzip`,
      );
      // ALPINE_IMAGE, when set, is the build's base (dockerBuildArgs), so a job log shows a base
      // other than the Dockerfile's default.
      const baseImage = env.ALPINE_IMAGE;
      if (baseImage !== undefined) log(`image:info: the base image is ${baseImage} (ALPINE_IMAGE)`);
      const build = run('docker', dockerBuildArgs({ context, tag: options.tag, base: baseImage }), {
        cwd,
      });
      if (build.status !== 0)
        throw new Stop(1, `docker build of ${options.tag} failed (${runFailure(build)})`);
    } finally {
      fs.rmSync(context, { recursive: true, force: true });
    }
    log(`image:info: built ${options.tag} from ${options.dist}`);
    if (Object.keys(labels).length > 0) {
      const differences = labelProblems({ run, tag: options.tag, labels });
      if (differences.length)
        throw new Stop(
          1,
          `${options.tag} does not carry the build report it was built with:\n  ${differences.join('\n  ')}`,
        );
      log(`image:info: docker inspect reads ${Object.keys(labels).join(' and ')} back as written`);
    }
    if (options.packageOnly) return 0;

    const failures = checkImage({
      run,
      cwd,
      base,
      dist,
      stats,
      tag: options.tag,
      manifest,
      legal,
      brandingText,
      browser: options.browser,
      log,
      error,
    });
    if (failures.length) {
      error(`image:info: ${options.tag} is not fit to push; ${failures.length} check(s) failed:`);
      for (const failure of failures) error(`  - ${failure}`);
      return 1;
    }
    if (!options.browser) {
      log(
        `image:info: ${options.tag} built and checked without a browser — no layout check and no browser part of the container test, so it is not checked for a push`,
      );
    } else {
      log(`image:info: ${options.tag} is checked and may be pushed by the country workflow`);
    }
    return 0;
  } catch (problem) {
    if (problem instanceof Stop) {
      error(`image:info: ${problem.message}`);
      return problem.code;
    }
    error(`image:info: ${problem.message}`);
    return 1;
  }
}

/** The checks of a country's built image; one line per failed check. */
function checkImage({
  run,
  cwd,
  base,
  dist,
  stats,
  tag,
  manifest,
  legal,
  brandingText,
  browser,
  log,
  error,
}) {
  const failures = [];
  const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-info-image-check-'));
  try {
    const html = path.join(scratch, 'html');
    const nginx = path.join(scratch, 'nginx');
    fs.mkdirSync(nginx);
    const created = run('docker', ['create', tag], { capture: true });
    const id = created.stdout.trim();
    if (created.status !== 0)
      return [`docker create ${tag} failed (${runFailure(created)})${withStderr(created)}`];
    if (!/^[0-9a-f]{12,64}$/.test(id))
      return [`docker create ${tag} answered no container id${withStderr(created)}`];
    try {
      const copies = [
        run('docker', ['cp', `${id}:/usr/share/nginx/html`, html], { capture: true }),
        run('docker', ['cp', `${id}:/etc/nginx/niord-security-headers.conf`, nginx], {
          capture: true,
        }),
      ];
      const failed = copies.find((copy) => copy.status !== 0);
      if (failed)
        return [`docker cp out of ${tag} failed (${runFailure(failed)})${withStderr(failed)}`];
    } finally {
      const removed = run('docker', ['rm', id], { capture: true });
      if (removed.status !== 0)
        error(
          `image:info: warning: docker rm ${id} failed (${runFailure(removed)})${withStderr(removed)}; remove the container by hand`,
        );
    }

    const differences = imageFilesProblems({ distDir: dist, extractedDir: html });
    if (differences.length) {
      for (const difference of differences) error(`  ${difference}`);
      failures.push(
        `the image does not hold exactly the dist the checks read (${differences.length} difference(s))`,
      );
    } else {
      log('image:info: the image holds exactly the dist, beside its .br/.gz twins');
    }

    const script = (name) => path.join(base, 'scripts', name);
    const node = (label, args) => {
      log(`\nimage:info: ${label}`);
      const result = run(process.execPath, args, { cwd });
      if (result.status !== 0) failures.push(`${label} (${runFailure(result)})`);
    };
    node('the separation check', [
      script('info-separation-check.mjs'),
      '--stats',
      stats,
      '--root',
      BASE_PREFIX,
      '--country',
      manifest.country,
    ]);
    if (browser)
      node("the layout check against the image's files and policy", [
        script('info-layout-check.mjs'),
        '--dist',
        html,
        '--nginx',
        nginx,
      ]);
    node(`the container test${browser ? ' with --browser' : ''}`, [
      script('info-container-test.mjs'),
      '--image',
      tag,
      ...(legal ? ['--legal-doc', legal] : ['--no-legal-doc']),
      '--language',
      manifest.apps.info.defaultLanguage,
      ...(brandingText ? [] : ['--no-branding-text']),
      ...(browser ? ['--browser'] : []),
    ]);
  } finally {
    fs.rmSync(scratch, { recursive: true, force: true });
  }
  return failures;
}

const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

if (invokedDirectly) process.exitCode = await imageInfo({ argv: process.argv.slice(2) });
