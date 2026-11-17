/**
 * The catalogs of the published base product versions (fe-country-split-plan §4.5): which versions
 * of `org.niord.web:niord-fe-src` the registry has, and the `catalog.json` at the root of each
 * version's zip. `pack prune --base <version>` reads every catalog from that version up, and a
 * home's pack.yml checks its pack against the newest (`pack latest-catalog`).
 *
 * Everything comes through the Maven wrapper of the tree's be/ (lib/maven.mjs), as the stub fetches
 * the pinned zip: Maven's credentials, mirrors and cache serve it, and MAVEN_ARGS passes through.
 *
 *   catalogVersions    makes Maven resolve the meta-version LATEST of niord-fe-src with -U, which
 *                      has it fetch the registry's maven-metadata.xml into the local repository as
 *                      maven-metadata-<repository>.xml (a version range is not resolved by
 *                      dependency:copy, and does not fetch it), then reads every
 *                      maven-metadata*.xml in the head local repository — the one -U writes;
 *                      maven-metadata-local.xml is what an install writes — and returns the union
 *                      of their versions that are plain versions npm's semver reads, lowest first.
 *                      The head is MAVEN_ARGS's -Dmaven.repo.local, else Maven's own
 *                      settings.localRepository, asked with help:evaluate. A
 *                      -Dmaven.repo.local.tail is never read: Maven writes every download into the
 *                      head only, so a tail's metadata is never refreshed and can keep a version
 *                      the registry no longer lists
 *   latestCatalog      the newest version's catalog
 *   publishedCatalogs  the catalog of every version from `from` up; `from` must be published
 *
 * Each zip is fetched with dependency:copy into `.niord/catalogs/` and read as data: only its
 * `catalog.json`, through lib/zip.mjs's checks, held to the catalog format and to its version.
 * Node built-ins only: the token-holding step of pack.yml runs it before anything is installed.
 */
import fs from 'node:fs';
import path from 'node:path';
import { FORMAT } from '../catalog.mjs';
import { runMaven } from './maven.mjs';
import { VERSION } from './pins.mjs';
import { compare, parseVersion } from './semver.mjs';
import { readZip } from './zip.mjs';

export const ARTIFACT = 'org.niord.web:niord-fe-src';
export const CATALOGS = path.join('.niord', 'catalogs');
const ARTIFACT_PATH = ['org', 'niord', 'web', 'niord-fe-src'];
const METADATA = /^maven-metadata(-[^/\\]+)?\.xml$/;

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

/** A version npm's semver reads, or undefined. */
function semver(version) {
  try {
    return parseVersion(version);
  } catch {
    return undefined;
  }
}

/** Ascending by semver. */
const byVersion = (a, b) => compare(parseVersion(a), parseVersion(b));

/** The versions listed in a maven-metadata.xml's `<versions>`. */
export function metadataVersions(xml) {
  const block = /<versions>([\s\S]*?)<\/versions>/.exec(xml);
  if (!block) return [];
  return [...block[1].matchAll(/<version>\s*([^<]*?)\s*<\/version>/g)].map((match) => match[1]);
}

/** MAVEN_ARGS's `-Dmaven.repo.local=`, the head local repository; a tail is not returned. */
export function repositoryArguments(mavenArgs = '') {
  let local;
  for (const arg of mavenArgs.split(/\s+/))
    if (arg.startsWith('-Dmaven.repo.local=')) local = arg.slice('-Dmaven.repo.local='.length);
  return { local };
}

/** The head local repository: the one Maven writes the -U refresh of the metadata into. */
function headRepository({ fe, be, run, env }) {
  const { local } = repositoryArguments(env.MAVEN_ARGS);
  if (local) return local;
  const out = path.join(fe, CATALOGS, 'local-repository.txt');
  fs.rmSync(out, { force: true });
  const output = `-Doutput=${out}`;
  const status = run({
    be,
    args: ['-B', '-q', '-N', 'help:evaluate', '-Dexpression=settings.localRepository', output],
    paths: [output],
    env,
  });
  if (status !== 0 || !fs.existsSync(out))
    throw new Error(`Maven could not name its local repository (help:evaluate, exit ${status})`);
  const lines = fs
    .readFileSync(out, 'utf8')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
  fs.rmSync(out, { force: true });
  if (!lines.length) throw new Error('Maven named no local repository (help:evaluate)');
  return lines.at(-1);
}

/**
 * The published versions of niord-fe-src, lowest first. `run` stands in for lib/maven.mjs's
 * runMaven in a test; `fe` is the folder `.niord/catalogs/` is made in.
 */
export function catalogVersions({ fe, be, run = runMaven, env = process.env }) {
  const resolve = path.join(fe, CATALOGS, 'resolve');
  fs.rmSync(resolve, { recursive: true, force: true });
  fs.mkdirSync(resolve, { recursive: true });
  const outputDirectory = `-DoutputDirectory=${resolve}`;
  let status;
  try {
    status = run({
      be,
      args: [
        '-B',
        '-q',
        '-N',
        '-U',
        'dependency:copy',
        `-Dartifact=${ARTIFACT}:LATEST:zip`,
        outputDirectory,
      ],
      paths: [outputDirectory],
      env,
    });
  } finally {
    fs.rmSync(resolve, { recursive: true, force: true });
  }
  if (status !== 0)
    throw new Error(
      `Maven could not resolve the newest ${ARTIFACT} (exit ${status}): the registry's maven-metadata.xml was not read`,
    );
  const versions = new Set();
  const read = [];
  const folder = path.join(headRepository({ fe, be, run, env }), ...ARTIFACT_PATH);
  let names = [];
  try {
    names = fs.readdirSync(folder);
  } catch {
    // No folder: nothing was resolved or installed, refused below.
  }
  for (const name of names.filter((candidate) => METADATA.test(candidate)).sort()) {
    read.push(path.join(folder, name));
    for (const version of metadataVersions(fs.readFileSync(path.join(folder, name), 'utf8')))
      if (VERSION.test(version) && semver(version)) versions.add(version);
  }
  if (!versions.size)
    throw new Error(
      `no published version of ${ARTIFACT} was found${read.length ? ` in ${read.join(', ')}` : ': Maven left no maven-metadata*.xml'}`,
    );
  return [...versions].sort(byVersion);
}

/** One version's catalog: its zip fetched into `.niord/catalogs/`, its catalog.json read as data. */
export function fetchCatalog({ fe, be, version, run = runMaven, env = process.env }) {
  if (!VERSION.test(version)) throw new Error(`"${version}" is not a plain version`);
  const dir = path.join(fe, CATALOGS);
  fs.mkdirSync(dir, { recursive: true });
  const zip = path.join(dir, `niord-fe-src-${version}.zip`);
  fs.rmSync(zip, { force: true });
  const outputDirectory = `-DoutputDirectory=${dir}`;
  const status = run({
    be,
    args: [
      '-B',
      '-q',
      '-N',
      'dependency:copy',
      `-Dartifact=${ARTIFACT}:${version}:zip`,
      outputDirectory,
    ],
    paths: [outputDirectory],
    env,
  });
  if (status !== 0)
    throw new Error(`Maven could not fetch ${ARTIFACT}:${version}:zip (exit ${status})`);
  if (!fs.existsSync(zip)) throw new Error(`Maven left no ${path.basename(zip)} in ${CATALOGS}/`);
  let catalog;
  try {
    const entry = readZip(fs.readFileSync(zip)).find(({ name }) => name === 'catalog.json');
    if (!entry) throw new Error('has no catalog.json');
    catalog = JSON.parse(entry.data.toString('utf8'));
  } catch (error) {
    throw new Error(`${path.basename(zip)}: ${error.message}`, { cause: error });
  } finally {
    fs.rmSync(zip, { force: true });
  }
  if (!isObject(catalog) || catalog.format !== FORMAT)
    throw new Error(`the catalog of ${version} is not of format ${FORMAT}`);
  if (catalog.version !== version)
    throw new Error(`the catalog in the zip of ${version} names the version ${catalog.version}`);
  return catalog;
}

/** The newest published version and its catalog: `{ version, catalog }`. */
export function latestCatalog({ fe, be, run = runMaven, env = process.env }) {
  const version = catalogVersions({ fe, be, run, env }).at(-1);
  return { version, catalog: fetchCatalog({ fe, be, version, run, env }) };
}

/**
 * `[{ version, catalog }]` of every published version from `from` up, lowest first. Each version
 * costs one Maven run and one zip in the local repository, after the run that lists them:
 * `onVersion(version, index, count)` is called before each version's fetch, so a caller can say
 * which it is reading.
 */
export function publishedCatalogs({
  fe,
  be,
  from,
  run = runMaven,
  env = process.env,
  onVersion = () => {},
}) {
  if (!VERSION.test(from ?? '') || !semver(from))
    throw new Error(`--base "${from}" is not a base product version`);
  const versions = catalogVersions({ fe, be, run, env });
  if (!versions.includes(from))
    throw new Error(
      `${from} is not a published version of ${ARTIFACT} (published: ${versions.join(', ')})`,
    );
  const wanted = versions.filter((version) => byVersion(version, from) >= 0);
  return wanted.map((version, index) => {
    onVersion(version, index, wanted.length);
    return { version, catalog: fetchCatalog({ fe, be, version, run, env }) };
  });
}
