/**
 * The orphan-file check (fe-country-split-plan §4.3): every file of a country's code folders is
 * there for a reason the kit can see. A non-spec file in `admin/app/`, `info/app/` or
 * `admin/environments/` must be a seam's country file or be reached from one; a non-spec file in
 * `admin/overrides/` or `info/overrides/` must be an override's copy or be reached from one. A
 * file nothing reaches is never built, so it is either dead or a seam spelt wrongly — both silent
 * without this check: an environment file spelt `environment.prodution.ts` would leave the
 * production build on the base product's neutral environment.
 *
 * The same folders hold no link (codeLinks): the lint and this check read paths as written, so a
 * link into the base product's sources would pass both and compile, reaching what the country API
 * leaves out.
 *
 * Reached means through a relative import (static, side-effect, re-export or dynamic), a
 * component's `templateUrl` / `styleUrl(s)`, or a stylesheet's relative `@use`, `@forward` or
 * `@import`, inside the country's tree. `*.spec.ts` files are the country's tests and are neither
 * checked nor followed: a component only a spec uses is an orphan.
 */
import fs from 'node:fs';
import path from 'node:path';
import { componentResources, relativeSpecifiers } from './overrides.mjs';

const posix = path.posix;
const FOLDERS = [
  'admin/app/',
  'info/app/',
  'admin/environments/',
  'admin/overrides/',
  'info/overrides/',
];
const SEAM_FOLDER = /^(admin|info)\/(app|environments)\//;
const SPEC = /\.spec\.ts$/;
const SCRIPT = /\.(ts|mjs|js)$/;
const STYLE = /\.(s?css)$/;

const isFile = (fe, relative) => {
  try {
    return fs.statSync(path.join(fe, relative)).isFile();
  } catch {
    return false;
  }
};

/** Every file below a folder of the country's fe/, relative to fe/ and with `/`. */
function filesUnder(fe, folder) {
  const dir = path.join(fe, folder);
  if (!fs.existsSync(dir)) return [];
  return fs
    .readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) =>
      path.relative(fe, path.join(entry.parentPath, entry.name)).replaceAll('\\', '/'),
    );
}

/** The file a module specifier written in `from` names, as TypeScript would find it, or undefined. */
function resolveModule(fe, from, specifier) {
  const target = posix.normalize(posix.join(posix.dirname(from), specifier));
  if (target.startsWith('../')) return undefined;
  const candidates = [
    target,
    `${target}.ts`,
    `${target}.mjs`,
    `${target}.js`,
    `${target}.json`,
    `${target}/index.ts`,
  ];
  return candidates.find((candidate) => isFile(fe, candidate));
}

/** The file a stylesheet's relative `@use`, `@forward` or `@import` names, partials included. */
function resolveStyle(fe, from, specifier) {
  const target = posix.normalize(posix.join(posix.dirname(from), specifier));
  if (target.startsWith('../')) return undefined;
  const dir = posix.dirname(target);
  const name = posix.basename(target);
  const candidates = [target, `${target}.scss`, `${target}.css`, posix.join(dir, `_${name}.scss`)];
  return candidates.find((candidate) => isFile(fe, candidate));
}

/** The country files `file` reaches directly. */
function reachedFrom(fe, file) {
  const text = fs.readFileSync(path.join(fe, file), 'utf8');
  if (SCRIPT.test(file))
    return [
      ...relativeSpecifiers(text).map((specifier) => resolveModule(fe, file, specifier)),
      ...componentResources(text).map((resource) => {
        const target = posix.normalize(posix.join(posix.dirname(file), resource));
        return !target.startsWith('../') && isFile(fe, target) ? target : undefined;
      }),
    ].filter(Boolean);
  if (STYLE.test(file))
    return [...text.matchAll(/@(?:use|forward|import)\s+(['"])(\.\.?\/[^'"\n]+)\1/g)]
      .map((match) => resolveStyle(fe, file, match[2]))
      .filter(Boolean);
  return [];
}

/**
 * Every file of the country's code folders that no seam and no override reaches, one error each.
 * `seams` is the catalogue (seams.json), `manifest` the country's manifest.
 */
export function orphanFiles({ fe, seams, manifest }) {
  const roots = [
    ...(seams.seams ?? [])
      .map((seam) => seam.country)
      .filter((file) => typeof file === 'string' && SEAM_FOLDER.test(file)),
    ...(manifest.overrides ?? []).map((entry) => entry.with),
  ].filter((file) => isFile(fe, file));
  const reached = new Set();
  const queue = [...roots];
  while (queue.length) {
    const file = queue.pop();
    if (reached.has(file) || SPEC.test(file)) continue;
    reached.add(file);
    queue.push(...reachedFrom(fe, file));
  }
  const errors = [];
  for (const folder of FOLDERS)
    for (const file of filesUnder(fe, folder).sort()) {
      if (SPEC.test(file) || reached.has(file)) continue;
      errors.push(
        folder.endsWith('environments/')
          ? `${file} is no environment seam's file and reached from none (country-kit/README.md, The orphan-file check): a misspelt name is never built; name it as seams.json does, or remove it`
          : folder.endsWith('app/')
            ? `${file} is neither a seam nor reached from one (country-kit/README.md, The orphan-file check): import it from the seam file that uses it, or remove it`
            : `${file} is neither an override's copy nor used by one (country-kit/README.md, The orphan-file check): name it in an overrides entry, or remove it`,
      );
    }
  return errors;
}

/**
 * Every link in the country's code folders (FOLDERS), one error each: walked once without
 * following any, as branding.mjs walks the branding folders.
 */
export function codeLinks(fe) {
  const errors = [];
  for (const folder of FOLDERS) {
    const dir = path.join(fe, folder);
    let stat;
    try {
      stat = fs.lstatSync(dir);
    } catch {
      continue;
    }
    if (stat.isSymbolicLink()) {
      errors.push(
        `${folder.slice(0, -1)} is a link: the country's code folders hold its own files`,
      );
      continue;
    }
    for (const entry of fs.readdirSync(dir, { recursive: true, withFileTypes: true }))
      if (entry.isSymbolicLink())
        errors.push(
          `${path.relative(fe, path.join(entry.parentPath, entry.name)).replaceAll('\\', '/')} is a link: the country's code folders hold its own files, never a link into the base product's sources or anywhere else`,
        );
  }
  return errors.sort();
}
