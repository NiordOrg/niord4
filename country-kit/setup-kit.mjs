/**
 * The kit's half of a country's set-up, run by the country's `fe/setup.mjs` (templates/setup.mjs)
 * once that stub has verified the kit package and the base product's source zip, or, under
 * NIORD_BASE, from the kit beside a checkout's fe/:
 *
 *   1. the stub: the committed `setup.mjs` must be the kit's own template, line endings aside;
 *      on a version change (step 3's record names another version) a stub the base product has
 *      changed since is a failed check, one more item of the upgrade report's To do, rather than
 *      a refusal before the report. Under NIORD_BASE the checkout's kit is first copied into the
 *      root's .niord/kit/ exactly as its package would unpack (lib/local-sources.mjs copyKit), so
 *      the template the stub check's message names is there;
 *   2. NIORD_BASE: a checkout's files, exactly as the zip would hold them, copied into
 *      .niord/base/ with the catalog built from the checkout (lib/local-sources.mjs copyBase);
 *   3. the last set-up record, .niord/build/niord-build-report.json (lib/previous-base.mjs):
 *      when it names another base product version than this one, that version's files from the
 *      zip the stub left in .niord/ for it, verified against the record's sha256, and the kit's
 *      files from the kit package zip beside the country's fe/ when the record names its hash
 *      (else from that version's zip). Without a record, `--previous <version>` names the previous
 *      version, and the upgrade report compares with it by version, without its files; with a
 *      record it must name the record's version. Then the
 *      manifest, niord-country.json (lib/manifest.mjs), and the pins of its foreign packs in
 *      niord-pins.lock.json (lib/pins.mjs);
 *   4. with `--previous <version>`, which stands alone, the previous version of step 3; with
 *      `--add-override <base file> --reason <text>` or `--accept-override <file>`, the
 *      guarded override added or its fingerprints recorded, with the base product's own diff of
 *      each changed file since the recorded fingerprint when the previous version's files are at
 *      hand (lib/overrides.mjs), and the manifest written and read again; with
 *      `--update-country-lock`, country/package-lock.json made anew from country/package.json
 *      against the base product's lockfile (lib/dependencies.mjs); with `--update-pins
 *      [<lang>=<tag>]...`, each pinned pack's pin moved to the tag given, else to the highest tag
 *      on GHCR its withdrawn marker does not list and that is a readable pack, with that tag's
 *      digest; a candidate is pulled and verified as a pack before it is pinned;
 *   5. every pack the manifest names but English staged into .niord/packs/<lang>/ in exactly its
 *      canonical archive's layout (lib/pack-archive.mjs), so the country's Maven build reads every
 *      pack's backend half from one place: a home pack from packs/<lang>/ (which the checks still
 *      read, so its labels and status/unaccepted.json stay the home's); a pinned pack from GHCR,
 *      anonymously, its withdrawn marker read first and a pin it lists made a failed check (with
 *      the reason, and in the upgrade report), then its manifest and archive by the pin's digest,
 *      verified before a byte is unpacked (lib/oci.mjs; the archive cached in .niord/oci/ and
 *      verified again when reused); a local NIORD_PACK_<LANG> copy, refused when CI is set. A
 *      staged folder of a language no longer named is removed, and so is a cached archive this
 *      run did not use: .niord/oci/ can be deleted at any time;
 *   6. the publish time of every entry the country's lock adds, from the registry or from
 *      .niord/country-dependency-ages.json, where an answer older than 7 days is kept;
 *   7. the checks, each reporting every finding before set-up stops: the text overrides of
 *      text-overrides/ (lib/text-overrides.mjs), read first, since a key an override supplies
 *      counts as present in the pack check; the pack check against the base product's catalog with
 *      the escape hatch missing-keys-accepted.json (pack.mjs; a pinned or local pack's status rows
 *      are its home's, `foreign`); the branding of each application
 *      (lib/branding.mjs); the production environment's addresses (lib/environment-check.mjs); the
 *      backend settings file's languages (lib/settings-check.mjs); the guarded overrides against the
 *      base product and its security.json (lib/overrides.mjs); the orphan files and the links in the code folders (lib/orphans.mjs);
 *      the country's own npm packages, country/package.json and its lock (lib/dependencies.mjs);
 *      audit-accepted.json's shape (audit.mjs, which `npm run build` runs); the secret-pattern
 *      scan of the country's fe/ inputs with the base product's secret-scan.json (lib/secrets.mjs,
 *      secretScanInputs); and, as warnings, every use of a deprecated country API export
 *      (lib/api.mjs, country-kit/API-CHANGES.md). On a version change the upgrade report,
 *      .niord/niord-upgrade-report.md (lib/upgrade-report.mjs), and its data,
 *      .niord/niord-upgrade-report.json, are written from the checks' findings, both also when a
 *      check failed; the build report, which is the set-up record, only
 *      when they all passed, so a failed bump keeps comparing with the last good version. A
 *      failure from step 5 to here removes .niord/packs/ whole, so a build or Maven never reads a
 *      pack set-up did not pass: .niord/packs/ exists only after a set-up whose checks passed;
 *   8. staging (lib/stage-country.mjs) — English, the pack, the text overrides — with the build
 *      report (lib/build-report.mjs) and the record of the country-only utilities,
 *      .niord/build/country-utilities.json (lib/utilities.mjs), which the build check reads and no
 *      build ships; then the generated workspace with the country's packages merged into
 *      package.json and its lockfile, and the language configuration (lib/workspace.mjs,
 *      lib/language-config.mjs).
 *
 * The stub hands over no arguments: the kit reads them from the command line itself, so the stub
 * stays the same file in every country.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { acceptedLines, readAuditAccepted } from './audit.mjs';
import { writeIfChanged } from './catalog.mjs';
import { apiFiles, deprecatedUsesOf, deprecationWarning, readApi } from './lib/api.mjs';
import { checkBranding } from './lib/branding.mjs';
import { buildReport, reportText } from './lib/build-report.mjs';
import {
  addedEntries,
  checkCountryDependencies,
  publishTimes,
  readCountryDependencies,
  reportDependencies,
  updateCountryLock,
} from './lib/dependencies.mjs';
import { checkEnvironment } from './lib/environment-check.mjs';
import { readIncludeList } from './lib/include.mjs';
import { languageConfigs } from './lib/language-config.mjs';
import { copyBase, copyKit, copyPack, packVariable } from './lib/local-sources.mjs';
import { packUses, readManifest } from './lib/manifest.mjs';
import {
  formatReference,
  GHCR,
  packTags,
  pullPack,
  readWithdrawn,
  registryHost,
  tagDigest,
  TAG,
} from './lib/oci.mjs';
import { packEntries, readPackArchive, stagePack } from './lib/pack-archive.mjs';
import { LOCK, ownerOf, readLock, readPins, writeLock } from './lib/pins.mjs';
import { codeLinks, orphanFiles } from './lib/orphans.mjs';
import { previousBase, readRecord, RECORD } from './lib/previous-base.mjs';
import {
  acceptOverride,
  addOverride,
  baseSpec,
  baseSpecs,
  checkOverrides,
  readSecurity,
} from './lib/overrides.mjs';
import { checkSettings, hasBackendHalf } from './lib/settings-check.mjs';
import { stageCountry } from './lib/stage-country.mjs';
import {
  checkTextOverrides,
  readPackBundles,
  readTextOverrides,
  textOverridesFor,
} from './lib/text-overrides.mjs';
import { readAllow, filesUnder as secretFilesUnder, scanFiles } from './lib/secrets.mjs';
import { parseVersion } from './lib/semver.mjs';
import { REPORT, REPORT_DATA, reportDataText, upgradeReportData } from './lib/upgrade-report.mjs';
import { countryUtilities } from './lib/utilities.mjs';
import { generateWorkspace } from './lib/workspace.mjs';
import { checkPacks, readCatalog, report } from './pack.mjs';
import { readPack } from './stage-languages.mjs';

export const KIT = path.dirname(fileURLToPath(import.meta.url));
const ENGLISH = 'en';
const APPS = ['admin', 'info'];
const HATCH = 'missing-keys-accepted.json';
const SETTINGS = 'be/src/main/resources/defaults/country/settings.json';
const ENVIRONMENT = 'admin/environments/environment.production.ts';
const MANIFEST = 'niord-country.json';
const AGES = '.niord/country-dependency-ages.json';
const UTILITIES = '.niord/build/country-utilities.json';
const USAGE =
  'node setup.mjs takes no argument, `--add-override <base file> --reason <text>`, `--accept-override <file>`, `--update-country-lock`, `--update-pins [<lang>=<tag>]...` or `--previous <version>`';
const UPDATE_LOCK = '--update-country-lock';
const UPDATE_PINS = '--update-pins';
const PREVIOUS = '--previous';
const toPosix = (p) => p.replaceAll('\\', '/');

const normalise = (text) => text.replace(/\r\n?/g, '\n');

/**
 * The check error of a stub the base product changed, on a version change (classifySetup reads
 * it). The copy it names is relative to fe/, where `node setup.mjs` runs: the kit is in the root's
 * .niord/kit/, unpacked there by the stub or copied there from a checkout.
 */
export const STUB_CHANGED =
  "stub: fe/setup.mjs is not the kit's own; the base product changed it: in fe/, copy ../.niord/kit/templates/setup.mjs over setup.mjs";

/** Throws unless the stub at `stub` is the kit's template, line endings aside. */
export function checkStub(stub, kit = KIT) {
  const template = fs.readFileSync(path.join(kit, 'templates', 'setup.mjs'), 'utf8');
  if (normalise(fs.readFileSync(stub, 'utf8')) !== normalise(template))
    throw new Error(
      "fe/setup.mjs is not the kit's own: it is never edited. In fe/, copy ../.niord/kit/templates/setup.mjs over setup.mjs",
    );
}

/** The languages a developer's NIORD_PACK_<LANG> names among the manifest's pinned packs. */
export const localPacks = (manifest, env) =>
  Object.entries(manifest.packs)
    .filter(([lang, entry]) => entry.pinned && env[packVariable(lang)])
    .map(([lang]) => lang);

/**
 * Where set-up keeps the archives of pinned packs: .niord/oci/<sha256>.zip, verified on reuse
 * (lib/oci.mjs pullPack). A file whose size is not the layer's is not read.
 */
export function archiveCache(fe) {
  const dir = path.join(fe, '.niord', 'oci');
  const file = (digest) => path.join(dir, `${digest.slice('sha256:'.length)}.zip`);
  return {
    get: (digest, size) => {
      try {
        if (Number.isInteger(size) && fs.statSync(file(digest)).size !== size) return undefined;
        return fs.readFileSync(file(digest));
      } catch {
        return undefined;
      }
    },
    put: (digest, bytes) => {
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(file(digest), bytes);
    },
  };
}

/**
 * The withdrawn marker's entry for a pin's digest, read anonymously before anything of the pack
 * is: undefined when the marker does not list it. A pack without a marker is unreadable.
 */
async function withdrawnEntry({ pin, fetch }) {
  try {
    const { withdrawn } = await readWithdrawn({
      registry: pin.registry,
      repository: pin.repository,
      lang: pin.lang,
      fetch,
    });
    return withdrawn.find((entry) => entry.digest === pin.digest);
  } catch (error) {
    if (error.code === 'NO_MARKER')
      throw new Error(
        `pack ${pin.lang}: ${error.message}, so the pack is unreadable: a published pack always has one, pushed by its first publish`,
        { cause: error },
      );
    throw new Error(`pack ${pin.lang}: the withdrawn marker could not be read: ${error.message}`, {
      cause: error,
    });
  }
}

/** The check error of a pinned pack whose digest its home withdrew. */
export const withdrawnError = ({ lang, reference, date, reason }) =>
  `pack ${lang}: ${reference} is withdrawn (${date}): ${reason}; move the pin with node setup.mjs --update-pins`;

/**
 * The pack folder of every language the manifest names, English included, with where each came
 * from, and every pack but English staged into `.niord/packs/<lang>/` in its archive's layout: a
 * home pack from `packs/<lang>/` (the folder the checks read), a developer's NIORD_PACK_<LANG>
 * copy, or a pinned pack pulled by its pin (`pins`, lib/pins.mjs readPins) from its registry,
 * the withdrawn marker read first. Returns `{ dirs, sources, references, foreign, withdrawn }`:
 * `references` the pinned packs' references, `foreign` the pinned and local languages (whose
 * status rows are their home's), `withdrawn` `[{ lang, reference, digest, tag, reason, date }]`
 * for each pin its home withdrew, which is neither pulled nor given a folder. A cached archive in
 * `.niord/oci/` that no pin of this run used, a withdrawn pin's included, is deleted.
 */
export async function resolvePacks({
  fe,
  base,
  manifest,
  env = process.env,
  pins = {},
  fetch,
  log = () => {},
}) {
  const dirs = { [ENGLISH]: path.join(base, 'languages', ENGLISH) };
  const sources = { [ENGLISH]: 'base' };
  const references = {};
  const foreign = [];
  const withdrawn = [];
  const used = new Set();
  const staged = path.join(fe, '.niord', 'packs');
  for (const [lang, entry] of Object.entries(manifest.packs)) {
    const target = path.join(staged, lang);
    if (entry.home) {
      const dir = path.join(fe, 'packs', lang);
      if (!fs.existsSync(dir))
        throw new Error(
          `packs/${lang}/ does not exist, and niord-country.json names ${lang} as a home pack`,
        );
      stagePack({ entries: packEntries(dir, lang), target });
      dirs[lang] = dir;
      sources[lang] = 'home';
      continue;
    }
    const variable = packVariable(lang);
    if (env[variable]) {
      if (env.CI)
        throw new Error(
          `${variable} is refused when CI is set: a pipeline builds from pinned packs only`,
        );
      const source = path.resolve(fe, env[variable]);
      copyPack({ source, target });
      dirs[lang] = target;
      sources[lang] = `local: ${toPosix(source)}`;
      foreign.push(lang);
      continue;
    }
    const pin = pins[lang];
    if (!pin)
      throw new Error(
        `${lang} is a pinned pack with no pin in ${LOCK}: run node setup.mjs --update-pins`,
      );
    sources[lang] = 'pinned';
    const listed = await withdrawnEntry({ pin, fetch });
    if (listed) {
      fs.rmSync(target, { recursive: true, force: true });
      withdrawn.push({ lang, reference: pin.reference, ...listed });
      continue;
    }
    let pulled;
    try {
      pulled = await pullPack({ reference: pin.reference, fetch, cache: archiveCache(fe) });
    } catch (error) {
      throw new Error(`pack ${lang}: ${error.message}`, { cause: error });
    }
    used.add(`${pulled.layer.slice('sha256:'.length)}.zip`);
    stagePack({ entries: readPackArchive(pulled.archive, lang), target });
    log(
      `pack ${lang}: ${pin.reference} ${pulled.cached ? 'verified from .niord/oci/' : 'pulled and verified'}, staged into .niord/packs/${lang}/`,
    );
    dirs[lang] = target;
    references[lang] = pin.reference;
    foreign.push(lang);
  }
  // A folder of a language the manifest no longer names, or one a stopped run left behind.
  if (fs.existsSync(staged))
    for (const name of fs.readdirSync(staged))
      if (!Object.hasOwn(manifest.packs, name) || withdrawn.some((w) => w.lang === name))
        fs.rmSync(path.join(staged, name), { recursive: true, force: true });
  // An archive no pin of this run used: a pin moved on, a withdrawn one, or a stopped run's.
  const cache = path.join(fe, '.niord', 'oci');
  if (fs.existsSync(cache))
    for (const name of fs.readdirSync(cache))
      if (name.endsWith('.zip') && !used.has(name))
        fs.rmSync(path.join(cache, name), { force: true });
  return { dirs, sources, references, foreign, withdrawn };
}

/**
 * `--update-pins [<lang>=<tag>]...`: each pinned pack's pin in niord-pins.lock.json moved to the
 * tag given, else to the highest numeric tag whose digest the pack's withdrawn marker does not
 * list and that is a readable pack, with that tag's digest; a given tag that is withdrawn, absent
 * or not a readable pack is refused. A candidate is pulled and verified as set-up would (its
 * manifest, config, one pack layer, size, digest and archive), the archive cached in .niord/oci/
 * for the set-up that follows; a candidate that is not a readable pack is skipped with a line
 * saying why, and a registry that fails to answer stops the command. The registry
 * is ghcr.io, or the loopback registry NIORD_PACK_REGISTRY names (the example's harness). The
 * lock keeps its base and its kit pin and holds exactly the manifest's pinned packs. Returns the
 * lines to print.
 */
export async function updatePins({ fe, manifest, given = {}, env = process.env, fetch }) {
  let registry = GHCR;
  if (env.NIORD_PACK_REGISTRY) {
    registry = registryHost(env.NIORD_PACK_REGISTRY);
    if (registry === GHCR)
      throw new Error(
        'NIORD_PACK_REGISTRY names a loopback registry (http://127.0.0.1:<port>); leave it unset for ghcr.io',
      );
  }
  const pinned = Object.keys(manifest.packs)
    .filter((lang) => manifest.packs[lang].pinned)
    .sort();
  for (const lang of Object.keys(given))
    if (!pinned.includes(lang))
      throw new Error(
        `${UPDATE_PINS} ${lang}=${given[lang]}: ${lang} is not a pinned pack of niord-country.json`,
      );
  const file = path.join(fe, LOCK);
  const lock = readLock(fs.readFileSync(file, 'utf8'));
  const packs = {};
  const lines = [];
  for (const lang of pinned) {
    const owner = ownerOf(manifest.packs[lang]);
    const repository = `${owner}/niord-lang-${lang}`;
    const at = `${registry}/${repository}`;
    const pin = { registry, repository, lang };
    const listed = new Map();
    // The marker first, as set-up reads it: a pack without one is unreadable.
    try {
      const { withdrawn } = await readWithdrawn({ ...pin, fetch });
      for (const entry of withdrawn) listed.set(entry.digest, entry);
    } catch (error) {
      if (error.code === 'NO_MARKER')
        throw new Error(`${error.message}: the pack is unreadable, and no pin is written`, {
          cause: error,
        });
      throw error;
    }
    // The digest of `candidate` (undefined when the registry has no such tag), or `{ problem }`
    // when its manifest is not one a pack has.
    const digestOf = async (candidate) => {
      try {
        return { found: await tagDigest({ ...pin, tag: candidate, fetch }) };
      } catch (error) {
        if (unanswered(error)) throw error;
        return { problem: error.message };
      }
    };
    // What makes the pack at `candidate` unreadable, as set-up would find it, or undefined.
    const unreadable = async (candidate, found) => {
      try {
        const reference = formatReference({ registry, owner, lang, tag: candidate, digest: found });
        const pulled = await pullPack({ reference, fetch, cache: archiveCache(fe) });
        readPackArchive(pulled.archive, lang);
        return undefined;
      } catch (error) {
        if (unanswered(error)) throw error;
        return error.message;
      }
    };
    let tag = given[lang];
    let digest;
    if (tag !== undefined) {
      const { found, problem } = await digestOf(tag);
      if (problem) throw new Error(`${at}:${tag} is not a readable pack: ${problem}`);
      if (!found) throw new Error(`${at} has no tag ${tag}`);
      const entry = listed.get(found);
      if (entry)
        throw new Error(
          `${at}:${tag} is withdrawn (${entry.date}): ${entry.reason}; pin another tag`,
        );
      const why = await unreadable(tag, found);
      if (why) throw new Error(`${at}:${tag} is not a readable pack: ${why}`);
      digest = found;
    } else {
      let skipped = 0;
      for (const candidate of await packTags({ ...pin, fetch })) {
        const { found, problem } = await digestOf(candidate);
        if ((!found && !problem) || listed.has(found)) continue;
        const why = problem ?? (await unreadable(candidate, found));
        if (why) {
          lines.push(`${lang}: tag ${candidate} is not a readable pack (${why}); skipped`);
          skipped++;
          continue;
        }
        tag = candidate;
        digest = found;
        break;
      }
      if (!digest)
        throw new Error(
          `${at} has no tag that its withdrawn marker does not list${skipped ? ` and that is a readable pack: ${lines.slice(-skipped).join('; ')}` : ''}`,
        );
    }
    packs[lang] = formatReference({ registry, owner, lang, tag, digest });
    const old = lock.packs[lang];
    lines.push(
      old === packs[lang]
        ? `${lang}: ${packs[lang]}, unchanged`
        : `${lang}: ${old ?? 'no pin'} -> ${packs[lang]}`,
    );
  }
  for (const lang of Object.keys(lock.packs))
    if (!pinned.includes(lang))
      lines.push(`${lang}: ${lock.packs[lang]} removed: not a pinned pack`);
  writeLock(file, { version: lock.base.version, sha256: lock.base.sha256, kit: lock.kit, packs });
  lines.push(
    `${LOCK} written; review the new digests, and a first pin against the summary of the home's Pack run that published its tag`,
  );
  return lines;
}

/**
 * Whether an error is the registry's failure to answer (no connection, or an HTTP status the
 * client refuses) rather than an answer that is not a pack: the first stops --update-pins, the
 * second only skips that tag.
 */
const unanswered = (error) => error instanceof TypeError || /\bHTTP \d{3}\b/.test(error.message);

/** The escape hatch's entries; an absent file is an empty list. */
export function readHatch(fe) {
  const file = path.join(fe, HATCH);
  if (!fs.existsSync(file)) return [];
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    throw new Error(`${HATCH}: ${error.message}`, { cause: error });
  }
}

/** The language each application's branding is held to: `en` where it is a UI language, else the default. */
export const referenceLanguage = (use) =>
  use.uiLanguages.includes(ENGLISH) ? ENGLISH : use.defaultLanguage;

/**
 * The branding and legal files an application stages, as the branding check found them: output
 * path under the staging folder → source file. Only files branding.schema.json names reach it.
 */
export const brandingAssets = (fe, app, files) =>
  Object.fromEntries(
    files
      .map(({ source, output }) => [
        output.replace(/^assets\//, ''),
        path.join(fe, app, 'assets', source),
      ])
      .sort(([a], [b]) => a.localeCompare(b, 'en')),
  );

/**
 * The command line set-up was given: `{}`, `{ add, reason }`, `{ accept }`, `{ updateLock: true }`,
 * `{ updatePins: { <lang>: <tag> } }` (empty for every pinned pack's highest good tag) or
 * `{ previous }`, a base product version. Throws on anything else.
 */
export function parseArgs(args) {
  if (args.includes(UPDATE_LOCK)) {
    if (args.length !== 1)
      throw new Error(`${UPDATE_LOCK} stands alone and takes no value: ${USAGE}`);
    return { updateLock: true };
  }
  if (args.includes(UPDATE_PINS)) {
    if (args[0] !== UPDATE_PINS)
      throw new Error(`${UPDATE_PINS} comes first, followed only by <lang>=<tag> pairs: ${USAGE}`);
    const updatePins = {};
    for (const pair of args.slice(1)) {
      const match = /^([a-z]{2,3})=([^=]*)$/.exec(pair);
      if (!match || !TAG.test(match[2]) || Object.hasOwn(updatePins, match[1]))
        throw new Error(
          `${UPDATE_PINS} takes <lang>=<tag> pairs, a tag being digits, each language once: "${pair}"`,
        );
      updatePins[match[1]] = match[2];
    }
    return { updatePins };
  }
  const options = {};
  for (let i = 0; i < args.length; i++) {
    const flag = args[i];
    const value = args[i + 1];
    const key = {
      '--add-override': 'add',
      '--reason': 'reason',
      '--accept-override': 'accept',
      [PREVIOUS]: 'previous',
    }[flag];
    if (!key || value === undefined || value.startsWith('--') || Object.hasOwn(options, key))
      throw new Error(`unknown argument "${flag}": ${USAGE}`);
    options[key] = value;
    i++;
  }
  if (options.previous !== undefined) {
    if (Object.keys(options).length !== 1) throw new Error(`${PREVIOUS} stands alone: ${USAGE}`);
    try {
      parseVersion(options.previous);
    } catch {
      throw new Error(
        `${PREVIOUS} takes a base product version, such as 4.0.97: "${options.previous}"`,
      );
    }
  }
  if (options.accept !== undefined && (options.add !== undefined || options.reason !== undefined))
    throw new Error(`--accept-override stands alone: ${USAGE}`);
  if ((options.add === undefined) !== (options.reason === undefined))
    throw new Error(`--add-override and --reason come together: ${USAGE}`);
  return options;
}

/**
 * The version set-up compares with, from the last set-up record or, without one, from
 * `--previous` (`previous`): `{ from, upgrade, previous }`, `upgrade` whether `version` is
 * another, `previous` lib/previous-base.mjs's result for the record's version, or, for
 * `--previous` alone, `{ version, option: true, unavailable }`: the report then compares by version.
 * A `--previous` beside a record must name the record's version.
 */
export function upgradeFrom({ fe, record, previous: option, version, log = () => {} }) {
  if (record && option !== undefined && option !== record.base.version)
    throw new Error(
      `${PREVIOUS} ${option}: the set-up record (${RECORD}) names ${record.base.version}; drop ${PREVIOUS}, or remove the record to compare by version`,
    );
  const from = record?.base.version ?? option;
  const upgrade = from !== undefined && from !== version;
  if (!record)
    log(
      option === undefined
        ? `setup: no previous set-up record (${RECORD}), so no upgrade report`
        : upgrade
          ? `setup: no previous set-up record (${RECORD}); the upgrade report compares with ${PREVIOUS} ${option}, by version`
          : `setup: no previous set-up record (${RECORD}), and ${PREVIOUS} names this version, so no upgrade report`,
    );
  let previous;
  if (!upgrade)
    previous = {
      version: from,
      unavailable: !from
        ? `there is no previous set-up record (${RECORD})`
        : record
          ? `the last set-up was of this version, ${version}, so there is no previous version's file`
          : `${PREVIOUS} names this version, ${version}, so there is no previous version's file`,
    };
  else if (record) previous = previousBase({ fe, record });
  else
    previous = {
      version: from,
      option: true,
      unavailable: `there is no set-up record (${RECORD}); ${PREVIOUS} ${from} names the previous version, so its files are not at hand`,
    };
  return { from, upgrade, previous };
}

/**
 * Writes the upgrade report, .niord/niord-upgrade-report.md, and its data,
 * .niord/niord-upgrade-report.json, from the same findings (lib/upgrade-report.mjs's input), each
 * only when its text changed; returns `{ markdown, data }`.
 */
export function writeUpgradeReport({ fe, ...input }) {
  const { markdown, data } = upgradeReportData({ fe, ...input });
  writeIfChanged(path.join(fe, REPORT), markdown);
  writeIfChanged(path.join(fe, REPORT_DATA), reportDataText(data));
  return { markdown, data };
}

/** The manifest's text as set-up writes it: JSON, two spaces, the overrides sorted by file. */
export const manifestText = (manifest) =>
  `${JSON.stringify(
    {
      ...manifest,
      overrides: [...(manifest.overrides ?? [])].sort((a, b) => a.file.localeCompare(b.file, 'en')),
    },
    null,
    2,
  )}\n`;

/** Today, as an override's `added` records it. */
const today = () => new Date().toISOString().slice(0, 10);

/**
 * `--add-override` or `--accept-override`: changes the manifest's overrides and writes it.
 * `previous` is the version the last set-up built against (lib/previous-base.mjs), `version`
 * this one's, for the base product's diff since the recorded fingerprint. Returns the lines to
 * print.
 */
function changeOverrides({ fe, base, manifest, options, previous, version, kit = KIT }) {
  const seams = JSON.parse(fs.readFileSync(path.join(kit, 'seams.json'), 'utf8'));
  const overrides = [...(manifest.overrides ?? [])];
  let lines;
  if (options.add !== undefined) {
    const entry = addOverride({
      fe,
      base,
      manifest,
      file: options.add,
      reason: options.reason,
      seams,
      include: readIncludeList(path.join(kit, 'zip-include.json')),
      today: today(),
    });
    overrides.push(entry);
    lines = [
      `added the override ${entry.file} -> ${entry.with}, based on ${Object.keys(entry.basedOn).length} file(s); change the copy, and add the country's own spec beside it`,
    ];
  } else {
    const result = acceptOverride({
      fe,
      base,
      manifest,
      target: options.accept,
      security: readSecurity(path.join(kit, 'security.json')),
      previous,
      version,
    });
    overrides.splice(
      overrides.findIndex((entry) => entry.file === result.entry.file),
      1,
      result.entry,
    );
    lines = result.lines;
  }
  fs.writeFileSync(path.join(fe, MANIFEST), manifestText({ ...manifest, overrides }));
  return lines;
}

/**
 * The country's fe/ inputs the secret scan reads (§4.11 rule 4): its seams, templates, branding
 * and legal files, styles, text overrides, home packs, the `foreign` packs (pinned or local) set-up
 * staged into .niord/packs/ — a home pack's staged copy is its packs/ folder's, scanned once — the
 * manifest, its own npm packages and the files that accept something.
 */
export function secretScanInputs(fe, foreign = []) {
  const folders = [
    'admin',
    'info',
    'styles',
    'text-overrides',
    'packs',
    ...foreign.map((lang) => `.niord/packs/${lang}`),
  ];
  const files = [
    MANIFEST,
    'country/package.json',
    'country/package-lock.json',
    HATCH,
    'audit-accepted.json',
    'niord-pins.lock.json',
  ];
  return [
    ...folders.flatMap((folder) => secretFilesUnder(fe, folder)),
    ...files.filter((file) => fs.existsSync(path.join(fe, file))),
  ].sort();
}

/**
 * A secret-scan finding as set-up prints it: a file of a pinned pack's staged copy names the pin
 * and what to do, since the country cannot fix the home's published pack; any other file's
 * finding is as the scan wrote it.
 */
const pinnedFinding = (references) => (finding) => {
  const lang = /^\.niord\/packs\/([a-z]{2,3})\//.exec(finding)?.[1];
  if (!lang || !Object.hasOwn(references, lang)) return finding;
  return `pinned pack ${lang} (${references[lang]}): ${finding} — pin another tag (node setup.mjs --update-pins ${lang}=<tag>) and tell its home`;
};

// The prefixes of the checks' lines in `errors` and `warnings`, which runChecks gives the
// structured findings' lines too.
const PACK = 'pack check: ';
const TEXT = 'text overrides: ';
const DEPS = 'country dependencies: ';

/**
 * Every check of step 6: the errors and warnings, one line each, and the printed report.
 * `dependencies` is what set-up read of the country's packages: `{ country, errors, published,
 * now }` (readCountryDependencies, its or publishTimes's errors, the publish times); by default
 * none. `since` is the version of the last set-up record, which a security fix must be newer than
 * to stop set-up (lib/overrides.mjs). `foreign` names the pinned and local packs, whose status
 * rows are their home's; `references` the pinned packs' references, which a secret found in one's
 * staged copy names; `leaveOut` the languages the pack check leaves out (a withdrawn pin, which
 * set-up reports in one line of its own, and which the settings check counts as having its
 * backend half, since it was not pulled). `findings` holds the checks' structured results the
 * upgrade report is made from: the pack check's, the text overrides', the country dependencies',
 * the override check's, the branding checks' (per application), the deprecated API exports the
 * country uses, and the pack folders. Every structured finding of a failed row carries `error`,
 * its line exactly as `errors` holds it, its prefix included.
 */
export function runChecks({
  fe,
  base,
  manifest,
  packDirs,
  kit = KIT,
  dependencies = {},
  since = undefined,
  foreign = [],
  references = {},
  leaveOut = [],
}) {
  const errors = [];
  const warnings = [];
  const lines = [];
  // A finding's line, as `errors` holds it: each family's lines carry its prefix.
  const lined = (prefix, items = []) =>
    items.map((item) =>
      item.error === undefined ? item : { ...item, error: `${prefix}${item.error}` },
    );

  const catalog = readCatalog(path.join(base, 'catalog.json'));
  const schema = JSON.parse(fs.readFileSync(path.join(kit, 'branding.schema.json'), 'utf8'));

  // The text overrides first: a key one supplies counts as present in the pack check.
  const textFiles = readTextOverrides(fe);
  const text = checkTextOverrides({
    catalog,
    overrides: textFiles.files,
    manifest,
    packBundles: readPackBundles(packDirs),
    brandingSchema: schema,
  });
  errors.push(...[...textFiles.errors, ...text.errors].map((line) => `${TEXT}${line}`));
  warnings.push(...text.warnings.map((line) => `${TEXT}${line}`));

  const accepted = readHatch(fe);
  const uses = packUses(manifest);
  const kept = (langs) => langs.filter((lang) => !leaveOut.includes(lang));
  const result = checkPacks({
    catalog,
    packs: Object.values(packDirs),
    uses: {
      admin: kept(uses.admin),
      info: kept(uses.info),
      backend: Object.fromEntries(
        Object.entries(uses.backend).filter(([lang]) => !leaveOut.includes(lang)),
      ),
    },
    accepted,
    covered: text.covered,
    foreign,
  });
  errors.push(...result.errors.map((line) => `${PACK}${line}`));
  warnings.push(...result.warnings.map((line) => `${PACK}${line}`));
  lines.push(
    ...report({ ...result, errors: [], warnings: [] }, HATCH).map((line) => `${PACK}${line}`),
  );
  lines.push(...text.lines);

  const branding = {};
  const brandingFindings = {};
  for (const app of APPS) {
    const prefix = `branding (${app}): `;
    const use = manifest.apps[app];
    branding[app] = checkBranding({
      schema,
      app,
      dir: path.join(fe, app, 'assets'),
      uiLanguages: use.uiLanguages,
      referenceLanguage: referenceLanguage(use),
    });
    errors.push(...branding[app].errors.map((line) => `${prefix}${line}`));
    warnings.push(...branding[app].warnings.map((line) => `${prefix}${line}`));
    brandingFindings[app] = lined(prefix, branding[app].findings);
  }

  errors.push(...checkEnvironment(path.join(fe, ENVIRONMENT), ENVIRONMENT));

  const settings = checkSettings({
    file: path.resolve(fe, '..', SETTINGS),
    manifest,
    backendHalf: (lang) => leaveOut.includes(lang) || hasBackendHalf(packDirs[lang]),
    label: SETTINGS,
  });
  errors.push(...settings.errors);
  warnings.push(...settings.warnings);

  const seams = JSON.parse(fs.readFileSync(path.join(kit, 'seams.json'), 'utf8'));
  let overrideFindings = [];
  try {
    const overrides = checkOverrides({
      fe,
      base,
      manifest,
      seams,
      security: readSecurity(path.join(kit, 'security.json')),
      include: readIncludeList(path.join(kit, 'zip-include.json')),
      since,
    });
    errors.push(...overrides.errors);
    lines.push(...overrides.lines);
    overrideFindings = overrides.findings;
  } catch (error) {
    errors.push(`overrides: ${error.message}`);
  }
  errors.push(...orphanFiles({ fe, seams, manifest }).map((line) => `orphan file: ${line}`));
  errors.push(...codeLinks(fe).map((line) => `link: ${line}`));

  const { country = null, published, now } = dependencies;
  const read = (name) => JSON.parse(fs.readFileSync(path.join(base, name), 'utf8'));
  const packages = checkCountryDependencies({
    country,
    basePackage: read('package.json'),
    baseLock: read('package-lock.json'),
    published,
    now,
  });
  errors.push(
    ...[...(dependencies.errors ?? []), ...packages.errors].map((line) => `${DEPS}${line}`),
  );
  warnings.push(...packages.warnings.map((line) => `${DEPS}${line}`));
  lines.push(...packages.lines);

  // audit-accepted.json is held to its shape here, before anything is staged; the audit itself
  // runs on the installed tree, first in `npm run build` (audit.mjs).
  const audit = readAuditAccepted(fe);
  errors.push(...audit.errors.map((line) => `npm audit: ${line}`));
  const auditAccepted = audit.errors.length ? [] : audit.entries;
  lines.push(...acceptedLines(auditAccepted));

  // Every file a country writes for the browser ships to anonymous readers: no secret in one.
  const allow = readAllow(path.join(kit, 'secret-scan.json'));
  const secrets = scanFiles({
    root: fe,
    files: secretScanInputs(fe, foreign),
    allow: allow.allow,
    scope: 'inputs',
    country: manifest.country,
  });
  errors.push(
    ...[...allow.errors, ...secrets.errors, ...secrets.findings.map(pinnedFinding(references))].map(
      (line) => `secrets: ${line}`,
    ),
  );

  let deprecated = [];
  try {
    const apis = Object.fromEntries(
      Object.entries(apiFiles(base)).map(([entry, file]) => [entry, readApi(file)]),
    );
    deprecated = deprecatedUsesOf({ fe, apis });
    warnings.push(...deprecated.map((use) => `country API: ${deprecationWarning(use)}`));
  } catch (error) {
    errors.push(`country API: ${error.message}`);
  }

  return {
    errors,
    warnings,
    lines,
    accepted,
    branding,
    seams,
    textOverrides: textFiles.files,
    textCounts: text.counts,
    auditAccepted,
    findings: {
      packs: {
        ...result,
        missing: lined(PACK, result.missing),
        markup: lined(PACK, result.markup),
        placeholders: lined(PACK, result.placeholders),
      },
      textOverrides: {
        ...text,
        markup: lined(TEXT, text.markup),
        leftCatalog: lined(TEXT, text.leftCatalog),
      },
      dependencies: { ...packages, peers: lined(DEPS, packages.peers) },
      overrides: overrideFindings,
      branding: brandingFindings,
      deprecated,
      packDirs,
    },
  };
}

/**
 * Step 5: the country's packages (readCountryDependencies) and the publish time of every entry
 * its lock adds. A malformed folder is an error the checks report, not a stop.
 */
async function readDependencies({ fe, base, fetchJson, now }) {
  let country;
  try {
    country = readCountryDependencies(fe);
  } catch (error) {
    return { country: null, errors: [error.message], published: new Map(), now };
  }
  if (!country) return { country, errors: [], published: new Map(), now };
  const baseLock = JSON.parse(fs.readFileSync(path.join(base, 'package-lock.json'), 'utf8'));
  const times = await publishTimes({
    entries: addedEntries(country, baseLock),
    ...(fetchJson ? { fetchJson } : {}),
    cacheFile: path.join(fe, AGES),
    now,
  });
  return { country, errors: times.errors, published: times.published, now };
}

/**
 * Set-up from step 1 on. `source` is the base product's folder the stub verified or the checkout
 * NIORD_BASE names, whose kit beside it is then this kit, copied into ../.niord/kit/ first;
 * `sha256` the zip's hash (null for a checkout); `kitSha256` the kit package zip's hash the stub
 * verified (null for a checkout). `fetchJson` asks the registry for a package's document and
 * `run` runs npm for `--update-country-lock` (tests pass their own), `fetch` is the pinned packs'
 * registry fetch (lib/oci.mjs), `now` is when the dependencies' age counts from.
 */
export async function setup({
  fe,
  stub,
  source,
  version,
  sha256,
  kitSha256 = null,
  provenance,
  args = process.argv.slice(2),
  env = process.env,
  log = console.log,
  warn = console.warn,
  fetchJson,
  run,
  fetch,
  now = new Date(),
}) {
  const started = Date.now();
  const options = parseArgs(args);
  // The last good set-up, which the upgrade report and --accept-override compare with, or else the
  // version --previous names. Its lines are logged after the copies below, in their old place.
  const noted = [];
  const { from, upgrade, previous } = upgradeFrom({
    fe,
    record: readRecord(fe),
    previous: options.previous,
    version,
    log: (line) => noted.push(line),
  });
  // A stub that is not the kit's own is refused at once, except on a version change: there the
  // base product changed its stub, which is one more item of the upgrade report's To do, and
  // set-up stops after the report as it does on any failed check.
  const base = path.join(fe, '.niord', 'base');
  const local = path.resolve(source) !== path.resolve(base);
  // A checkout's kit, which runs this, is copied first into the root's .niord/kit/, where the kit
  // package unpacks: the copy a refused stub is told to take from is then there.
  const kitCopy = path.resolve(fe, '..', '.niord', 'kit');
  if (local && path.resolve(KIT) !== kitCopy) {
    const count = copyKit({
      kit: KIT,
      target: kitCopy,
      version,
      be: path.resolve(source, '..', 'be'),
    });
    log(
      `setup: ${count} file(s) of the kit ${toPosix(KIT)} copied into ../.niord/kit/, with its marker, the backend contract and its backend/ files`,
    );
  }
  let stubChanged = false;
  try {
    checkStub(stub);
  } catch (error) {
    if (!upgrade) throw error;
    stubChanged = true;
  }
  if (local) {
    const count = copyBase({ source, target: base, version });
    log(
      `setup: ${count} file(s) of the checkout ${toPosix(source)} copied into .niord/base/, its catalog built`,
    );
  }

  for (const line of noted) log(line);

  if (options.updateLock)
    for (const line of updateCountryLock({ fe, base, ...(run ? { run } : {}) }))
      log(`setup: ${line}`);

  let manifest = readManifest(path.join(fe, MANIFEST));
  if (options.add !== undefined || options.accept !== undefined) {
    for (const line of changeOverrides({ fe, base, manifest, options, previous, version }))
      log(`setup: ${line}`);
    manifest = readManifest(path.join(fe, MANIFEST));
  }
  if (options.updatePins)
    for (const line of await updatePins({ fe, manifest, given: options.updatePins, env, fetch }))
      log(`setup: --update-pins: ${line}`);
  const lockFile = path.join(fe, LOCK);
  const pins = readPins(
    fs.existsSync(lockFile) ? fs.readFileSync(lockFile, 'utf8') : undefined,
    manifest,
    { local: localPacks(manifest, env) },
  );
  // From staging the packs to the checks' verdict, any failure removes .niord/packs/ whole: a
  // build or the country's Maven reads its packs there, and never one set-up did not pass. Every
  // set-up stages every pack again, from packs/ or the verified .niord/oci/ cache.
  let resolved;
  let dependencies;
  let checks;
  try {
    resolved = await resolvePacks({
      fe,
      base,
      manifest,
      env,
      pins,
      fetch,
      log: (line) => log(`setup: ${line}`),
    });
    dependencies = await readDependencies({ fe, base, fetchJson, now });
    checks = runChecks({
      fe,
      base,
      manifest,
      packDirs: resolved.dirs,
      dependencies,
      since: from,
      foreign: resolved.foreign,
      references: resolved.references,
      leaveOut: resolved.withdrawn.map(({ lang }) => lang),
    });
    if (stubChanged) {
      checks.errors.unshift(STUB_CHANGED);
      checks.findings.stub = { error: STUB_CHANGED };
    }
    // First of all: a withdrawn pin is the one line that names its pack.
    checks.errors.unshift(...resolved.withdrawn.map(withdrawnError));
    checks.findings.withdrawn = resolved.withdrawn.map((item) => ({
      ...item,
      error: withdrawnError(item),
    }));
    for (const line of checks.lines) log(`setup: ${line}`);
    for (const line of checks.warnings) warn(`setup: warning: ${line}`);
    for (const line of checks.errors) warn(`setup: error: ${line}`);
    // Written before set-up stops on a failed check: a red bump is when the report is needed.
    if (upgrade) {
      writeUpgradeReport({
        country: manifest.country,
        manifest,
        fe,
        previous,
        current: { version, base, kit: KIT },
        checks,
      });
      log(`setup: the upgrade report (${from} to ${version}): ${REPORT}`);
      log(`setup: its data: ${REPORT_DATA}`);
    }
    if (checks.errors.length)
      throw new Error(
        `${checks.errors.length} check(s) failed; nothing was staged or generated for the applications, and .niord/packs/ was removed`,
      );
  } catch (error) {
    fs.rmSync(path.join(fe, '.niord', 'packs'), { recursive: true, force: true, maxRetries: 3 });
    throw error;
  }
  const { dirs, sources, references } = resolved;

  const overrides = manifest.overrides ?? [];
  const buildReportText = reportText(
    buildReport({
      version,
      sha256,
      kitSha256,
      provenance,
      manifest,
      packSources: sources,
      packReferences: references,
      accepted: checks.accepted,
      overrides: overrides.map((entry) => ({ ...entry, baseSpec: baseSpec(base, entry.file) })),
      dependencies: reportDependencies(dependencies.country),
      textOverrides: checks.textCounts,
      auditAccepted: checks.auditAccepted,
    }),
  );
  const outDir = path.join(fe, '.niord', 'build');
  const staged = stageCountry({
    manifest,
    packs: dirs,
    outDir,
    assets: Object.fromEntries(
      APPS.map((app) => [app, brandingAssets(fe, app, checks.branding[app].files)]),
    ),
    extra: { admin: { 'niord-build-report.json': Buffer.from(buildReportText, 'utf8') } },
    textOverrides: textOverridesFor(checks.textOverrides),
  });
  writeIfChanged(path.join(outDir, 'niord-build-report.json'), buildReportText);
  // Beside the staging folders, never in them: the build check reads it, no build ships it.
  const utilities = countryUtilities(fe);
  writeIfChanged(
    path.join(fe, UTILITIES),
    `${JSON.stringify({ format: 1, ...utilities }, null, 2)}\n`,
  );
  log(
    `setup: country-only utilities: ${APPS.map((app) => `${app} ${utilities[app].map(({ utility }) => utility).join(', ') || 'none'}`).join('; ')}`,
  );
  for (const app of APPS) {
    const { files, written, removed, filled, overridden = {} } = staged[app];
    const bundles = files
      .filter((file) => file.startsWith('i18n/'))
      .map((file) => file.slice(5, -5));
    const fills = [
      ...Object.entries(filled).map(([lang, n]) => `${lang} ${n} from en`),
      ...Object.entries(overridden)
        .filter(([, n]) => n > 0)
        .map(([lang, n]) => `${lang} ${n} from text overrides`),
    ];
    log(
      `setup: staged ${app}: ${bundles.join(', ')}; ${files.length - bundles.length} other file(s)${fills.length ? ` (${fills.join(', ')})` : ''}; ${written.length} written, ${removed.length} removed`,
    );
  }

  const packs = Object.fromEntries(
    Object.entries(dirs).map(([lang, dir]) => [lang, readPack(dir)]),
  );
  const languages = languageConfigs({
    manifest,
    packs,
    branding: Object.fromEntries(APPS.map((app) => [app, checks.branding[app].brandingLanguages])),
  });
  const workspace = generateWorkspace({
    fe,
    cc: manifest.country,
    seams: checks.seams,
    languages,
    overrides,
    baseSpecs: baseSpecs(base, overrides),
    country: dependencies.country,
  });
  log(
    `setup: workspace generated (${workspace.files.length} file(s), ${workspace.written.length} written); ${provenance}; ${((Date.now() - started) / 1000).toFixed(1)} s`,
  );
  log(
    'setup: next `npm ci`, then `npm run build`, `npm test`, `npm run lint` or `npm run start:admin`',
  );
}
