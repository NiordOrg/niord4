/**
 * The country manifest, `fe/niord-country.json`: read, held to its schema
 * (niord-country.schema.json, where an unknown field fails) and to the rules a schema cannot
 * say:
 *
 *   - each application's `defaultLanguage` is one of its `uiLanguages`;
 *   - the administration app's `uiLanguages` are among its `contentLanguages`: a language the UI
 *     is in can be written in;
 *   - every language an application names but `en` has a `packs` entry, and `en` has none: it
 *     comes with the base product;
 *   - a pack is either `pinned` or `home`, never both; `publish` belongs to a home pack, `owner`
 *     to a pinned one;
 *   - a `locales` entry is for a language the manifest names;
 *   - an `overrides` entry's paths have no `.`, `..` or empty segment; its `with` is its `file`'s
 *     path below `ui-apps/<app>/src/app/`, under `<app>/overrides/`; its `basedOn` names its
 *     `file` and only files of the same application's `src/app/`; it is not a spec; and no two
 *     entries replace the same file.
 *
 * Its values are data. The kit hands them to its generators, which write JSON the applications
 * import; none is ever written into source text.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { validate } from './schema.mjs';

const KIT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const MANIFEST_SCHEMA_FILE = path.join(KIT, 'niord-country.schema.json');
const ENGLISH = 'en';

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

let schema;
/** The manifest's schema, read once. */
export function manifestSchema() {
  schema ??= JSON.parse(fs.readFileSync(MANIFEST_SCHEMA_FILE, 'utf8'));
  return schema;
}

/** The rules of one pack entry, readable whatever the entry's shape: `{ path, message }` each. */
function packEntryProblems(packs) {
  const problems = [];
  if (!isObject(packs)) return problems;
  for (const [lang, entry] of Object.entries(packs)) {
    if (!isObject(entry)) continue;
    const at = `packs.${lang}`;
    const pinned = Object.hasOwn(entry, 'pinned');
    const home = Object.hasOwn(entry, 'home');
    if (pinned && home)
      problems.push({ path: at, message: 'is both pinned and home; a pack is one or the other' });
    else if (!pinned && !home) problems.push({ path: at, message: 'is neither pinned nor home' });
    if (Object.hasOwn(entry, 'publish') && !home)
      problems.push({ path: `${at}.publish`, message: 'belongs to a home pack only' });
    if (Object.hasOwn(entry, 'owner') && !pinned)
      problems.push({ path: `${at}.owner`, message: 'belongs to a pinned pack only' });
  }
  return problems;
}

/** Every language a manifest's applications name, in the order they first appear. */
export function namedLanguages(manifest) {
  const { admin, info } = manifest.apps;
  return [...new Set([...admin.uiLanguages, ...admin.contentLanguages, ...info.uiLanguages])];
}

/** The rules beyond the schema, for a manifest the schema passes. */
function semanticProblems(manifest) {
  const problems = [];
  const fail = (at, message) => problems.push({ path: at, message });
  for (const app of ['admin', 'info']) {
    const { uiLanguages, defaultLanguage } = manifest.apps[app];
    if (!uiLanguages.includes(defaultLanguage))
      fail(
        `apps.${app}.defaultLanguage`,
        `"${defaultLanguage}" is not one of its uiLanguages (${uiLanguages.join(', ')})`,
      );
  }
  const { uiLanguages, contentLanguages } = manifest.apps.admin;
  for (const lang of uiLanguages)
    if (!contentLanguages.includes(lang))
      fail(
        'apps.admin.uiLanguages',
        `"${lang}" is not one of its contentLanguages: a language the UI is in can be written in`,
      );
  const named = namedLanguages(manifest);
  for (const lang of named)
    if (lang !== ENGLISH && !Object.hasOwn(manifest.packs, lang))
      fail('packs', `has no entry for "${lang}", which an application names`);
  if (Object.hasOwn(manifest.packs, ENGLISH))
    fail(`packs.${ENGLISH}`, 'is not a pack of the country: en comes with the base product');
  // A pack no application uses would be resolved (a pinned one fetched) but never checked.
  for (const lang of Object.keys(manifest.packs))
    if (lang !== ENGLISH && !named.includes(lang))
      fail(`packs.${lang}`, `is for "${lang}", which no application names`);
  for (const lang of Object.keys(manifest.locales ?? {}))
    if (!named.includes(lang))
      fail(`locales.${lang}`, `is for "${lang}", which no application names`);
  problems.push(...overrideProblems(manifest.overrides ?? []));
  return problems;
}

const cleanPath = (value) =>
  !value.includes('\\') && value.split('/').every((part) => part && part !== '.' && part !== '..');

/** The rules of the guarded overrides beyond their schema, for entries the schema passes. */
function overrideProblems(overrides) {
  const problems = [];
  const seen = new Set();
  overrides.forEach((entry, i) => {
    const fail = (field, message) =>
      problems.push({ path: `overrides[${i}]${field ? `.${field}` : ''}`, message });
    for (const [field, value] of [
      ['file', entry.file],
      ['with', entry.with],
      ...Object.keys(entry.basedOn).map((key) => [`basedOn[${JSON.stringify(key)}]`, key]),
    ])
      if (!cleanPath(value))
        fail(field, `${JSON.stringify(value)} has a ".", ".." or empty segment, or a backslash`);
    const [, app, rest] = /^ui-apps\/(admin|info)\/src\/app\/(.+)$/.exec(entry.file);
    if (entry.file.endsWith('.spec.ts'))
      fail('file', 'is a spec: the base product runs its own spec against the copy');
    if (entry.with !== `${app}/overrides/${rest}`)
      fail('with', `is not ${app}/overrides/${rest}, the copy's place for ${entry.file}`);
    if (!Object.hasOwn(entry.basedOn, entry.file))
      fail('basedOn', `does not name ${entry.file}, the file the copy replaces`);
    for (const key of Object.keys(entry.basedOn))
      if (!key.startsWith(`ui-apps/${app}/src/app/`))
        fail(
          'basedOn',
          `names ${key}, outside ui-apps/${app}/src/app/: an override is based on files of its own application`,
        );
    if (seen.has(entry.file)) fail('file', `replaces ${entry.file}, which an earlier entry does`);
    seen.add(entry.file);
  });
  return problems;
}

/**
 * What is wrong with a parsed manifest, one line each (`<path> <message>`); an empty list when
 * nothing is. The schema's own message for a pack entry gives way to the clearer rule above.
 */
export function manifestProblems(manifest) {
  const entries = packEntryProblems(manifest?.packs);
  const explained = new Set(entries.map(({ path: at }) => at.split('.').slice(0, 2).join('.')));
  const structural = [
    ...entries,
    ...validate(manifest, manifestSchema()).filter(
      ({ path: at, message }) => !(explained.has(at) && message.startsWith('matches none')),
    ),
  ];
  const problems = structural.length ? structural : semanticProblems(manifest);
  return problems.map(({ path: at, message }) => `${at} ${message}`);
}

function freeze(value) {
  if (value !== null && typeof value === 'object') {
    for (const item of Object.values(value)) freeze(item);
    Object.freeze(value);
  }
  return value;
}

/** Reads, checks and returns a manifest file, frozen; throws naming every problem. */
export function readManifest(file) {
  const label = path.basename(file);
  let stat;
  try {
    stat = fs.lstatSync(file);
  } catch (error) {
    if (error.code === 'ENOENT') throw new Error(`${label} does not exist`, { cause: error });
    throw error;
  }
  if (stat.isSymbolicLink() || !stat.isFile()) throw new Error(`${label} is not a regular file`);
  let manifest;
  try {
    manifest = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    throw new Error(`${label}: ${error.message}`, { cause: error });
  }
  const problems = manifestProblems(manifest);
  if (problems.length)
    throw new Error(`${label} breaks ${problems.length} rule(s):\n  ${problems.join('\n  ')}`);
  return freeze(manifest);
}

/**
 * What the pack check holds each pack to, from a manifest: per application the UI languages but
 * en, and for the backend `ui` for a UI language of either application, `content` for a language
 * that is only a content language (country-kit/pack.mjs checkPacks's `uses`).
 */
export function packUses(manifest) {
  const { admin, info } = manifest.apps;
  const ui = new Set([...admin.uiLanguages, ...info.uiLanguages]);
  const withoutEnglish = (langs) => langs.filter((lang) => lang !== ENGLISH).sort();
  const backend = {};
  for (const lang of namedLanguages(manifest))
    if (lang !== ENGLISH) backend[lang] = ui.has(lang) ? 'ui' : 'content';
  return {
    admin: withoutEnglish(admin.uiLanguages),
    info: withoutEnglish(info.uiLanguages),
    backend,
  };
}
