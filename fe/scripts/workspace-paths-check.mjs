#!/usr/bin/env node
/**
 * Guard for the workspace paths in angular.json.
 *
 *   node scripts/workspace-paths-check.mjs      # run from fe/
 *
 * Every application lives in its own folder under `ui-apps/`, so each path in angular.json names
 * one — or one of the shared folders, `styles/`, `shared-map/` and `shared-html/`, which belong to
 * both applications and to neither.
 * A path that still points at the workspace-root layout — `src/…`, `public…`, `tsconfig.…` — is
 * the one mistake this file exists to catch, because the build does not: a `fileReplacements` entry whose
 * `replace` file does not exist is silently skipped, so the build stays green with the default file
 * in its place. The same goes for an asset input that no longer resolves: the assets are simply
 * absent from the output.
 *
 * The check therefore does three things:
 *
 *   1. No string in angular.json may start with a workspace-root path.
 *   2. Every path that names a file must exist on disk — but a project's staged language folder,
 *      `.niord/build/<app>`, which the staging step writes before a build and a fresh checkout
 *      does not have.
 *   3. Every path a project names lies in that project's own application folder (its `root`) or
 *      in one of the shared folders. An asset input or a stylesheet taken from the other
 *      application builds green — the whole workspace is on disk — and the image packages the
 *      built output without asking where it came from; the separation check reads the module
 *      graph, which assets never enter, so this rule is the one guard for such an input. The
 *      unit-test builder's `include` and
 *      `exclude` globs are outside this rule: the builder resolves them against the project's
 *      source root, so a relative climb such as `../../../shared-map/` is not a workspace path.
 *      The one other place a project may read is its own staged language folder,
 *      `.niord/build/<app>` for the project in `ui-apps/<app>`. The language packs, `languages/`,
 *      are not a shared folder: a pack holds review lists and attribution that no reader may get,
 *      so a build reads only the staged copy of what it ships.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const FE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const FILE = path.join(FE, 'angular.json');

// angular.json carries explanatory comments, which JSON.parse rejects; strip them outside strings.
function stripComments(text) {
  let out = '';
  let inString = false;
  let escaped = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inString) {
      out += c;
      if (escaped) escaped = false;
      else if (c === '\\') escaped = true;
      else if (c === '"') inString = false;
      continue;
    }
    if (c === '"') {
      inString = true;
      out += c;
    } else if (c === '/' && text[i + 1] === '/') {
      while (i < text.length && text[i] !== '\n') i++;
      out += '\n';
    } else if (c === '/' && text[i + 1] === '*') {
      i += 2;
      while (i < text.length && !(text[i] === '*' && text[i + 1] === '/')) i++;
      i++;
    } else {
      out += c;
    }
  }
  return out;
}

/** angular.json, parsed. */
export function readAngularJson(file = FILE) {
  return JSON.parse(stripComments(fs.readFileSync(file, 'utf8')));
}

// Keys whose string values name a file on disk. `serviceWorker` is a path here; it may also be a
// boolean, and `ignore` entries are globs, so both are handled by the checks below.
const FILE_KEYS = new Set([
  'replace',
  'with',
  'browser',
  'index',
  'tsConfig',
  'serviceWorker',
  'ngswConfigPath',
  'input',
  'styles',
  'setupFiles',
]);
// Keys whose values are globs rather than files: they name a folder all the same, so rule 3
// applies to them, and rule 2 does not.
const GLOB_KEYS = new Set(['lintFilePatterns']);
const STALE = [/^src\//, /^public($|\/)/, /^tsconfig\./];
const SHARED = ['styles/', 'shared-map/', 'shared-html/'];
const STAGED = /^\.niord\/build\/([^/]+)(\/|$)/;
const PACKS = /^languages($|\/)/;

export const onDisk = {
  exists: (relative) => fs.existsSync(path.join(FE, relative)),
};

/**
 * Checks a parsed angular.json. Returns the errors, one line each, and how many file paths were
 * found on disk. `disk` is replaceable so a test can hand in a tree of its own.
 */
export function checkWorkspacePaths(config, disk = onDisk) {
  const errors = [];
  let checked = 0;

  function walk(node, trail, key, project) {
    if (typeof node === 'string') {
      const where = trail.join('.');
      if (STALE.some((re) => re.test(node))) {
        errors.push(`${where}: "${node}" still points at the workspace root`);
        return;
      }
      const staged = node.match(STAGED)?.[1];
      if (project && (FILE_KEYS.has(key) || GLOB_KEYS.has(key))) {
        const own = typeof project.root === 'string' ? `${project.root}/` : undefined;
        if (staged !== undefined) {
          if (!own || !project.root.endsWith(`/${staged}`))
            errors.push(
              `${where}: "${node}" is the staged language folder of ui-apps/${staged}/, not of this project (${own ?? 'no root set'})`,
            );
        } else if (PACKS.test(node)) {
          errors.push(
            `${where}: "${node}" reads the language packs, which reach a build only through its staged folder .niord/build/<app>/ (npm run languages:stage)`,
          );
        } else if (
          !(own && node.startsWith(own)) &&
          !SHARED.some((folder) => node.startsWith(folder))
        )
          errors.push(
            `${where}: "${node}" is neither in this project's folder (${own ?? 'no root set'}) nor in a shared folder (${SHARED.join(', ')})`,
          );
      }
      if (FILE_KEYS.has(key) && !node.includes('*') && staged === undefined) {
        checked++;
        if (!disk.exists(node)) errors.push(`${where}: "${node}" does not exist`);
      }
      return;
    }
    if (Array.isArray(node)) {
      node.forEach((item, i) => walk(item, [...trail, i], key, project));
      return;
    }
    if (node && typeof node === 'object') {
      for (const [childKey, value] of Object.entries(node)) {
        const childProject = trail.length === 2 && trail[1] === 'projects' ? value : project;
        walk(value, [...trail, childKey], childKey, childProject);
      }
    }
  }

  walk(config, ['angular.json'], undefined, undefined);
  return { errors, checked };
}

// Run as a script rather than imported by its test. Node reports the main module by its real
// path, so both sides are resolved through links: compared as typed, a run through a symlinked
// or junctioned folder would look like an import, check nothing and exit 0.
const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

if (invokedDirectly) {
  const { errors, checked } = checkWorkspacePaths(readAngularJson());
  if (errors.length) {
    console.error(`angular.json: ${errors.length} bad path(s):`);
    for (const e of errors) console.error('  ' + e);
    process.exit(1);
  }
  console.log(
    `workspace-paths-check: ${checked} path(s) in angular.json exist, each in its project's own folder or a shared one, none workspace-rooted.`,
  );
}
