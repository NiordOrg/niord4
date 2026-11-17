#!/usr/bin/env node
/**
 * The pack check, and the tools that keep a language pack in step with the catalog
 * (country-kit/catalog.mjs).
 *
 *   node ../country-kit/pack.mjs check                                # every pack, run from fe/
 *   node ../country-kit/pack.mjs check --project niord-app --configuration production
 *   node ../country-kit/pack.mjs check --catalog <file> --accepted <file>
 *   node ../country-kit/pack.mjs migrate <lang>                       # apply the catalog's renames
 *   node ../country-kit/pack.mjs prune <lang> [--catalog <file>]...   # drop keys no catalog has
 *   node ../country-kit/pack.mjs prune <lang> --base <version>        # ... no published catalog has
 *   node ../country-kit/pack.mjs accept <lang> --app admin|info <key>... # the owner accepts a text
 *   node ../country-kit/pack.mjs accept <lang> --app backend --bundle <bundle> <key>...
 *
 * and in a country workspace, from the country's fe/ (packsLayout):
 *
 *   node ../.niord/kit/pack.mjs migrate <lang>
 *   node ../.niord/kit/pack.mjs prune <lang> [--base <version>]
 *   node ../.niord/kit/pack.mjs accept <lang> --app admin|info <key>...
 *   node ../.niord/kit/pack.mjs accept <lang> --app backend --bundle <bundle> <key>...
 *   node ../.niord/kit/pack.mjs latest-catalog --out <file>
 *   node ../.niord/kit/pack.mjs validate <lang> [--catalog <file>] --out <dir>
 *
 * `migrate`, `prune` and `accept` edit the pack under `--packs <dir>`, by default `languages/` in
 * the base product tree and the country's home packs, `packs/`, in a country workspace; their
 * catalog is `--catalog <file>`, by default one built from the tree, or in a country workspace the
 * pinned zip's `.niord/base/catalog.json`. `prune --base <version>` reads instead the catalog of
 * every published base product version from that one up (lib/published-catalogs.mjs, through the
 * Maven wrapper of the tree's be/), and takes no `--catalog`. `check` runs in the base product
 * tree only: a country's set-up runs the pack check. Every line names a pack by the folder it was
 * read from, `languages/<lang>/`, `packs/<lang>/` or `.niord/packs/<lang>/` (packLabel).
 *
 * `latest-catalog` and `validate` are the two steps of a home's pack.yml (fe-country-split-plan
 * §4.5), run in a country workspace only. `latest-catalog` writes the catalog of the newest
 * published base product version to `--out`, through Maven alone: it is the step that holds the
 * workflow token. `validate` holds no token: it refuses a language that is not one of the
 * manifest's home packs, builds the pack's canonical archive (lib/pack-archive.mjs) into
 * `<out>/niord-lang-<lang>.zip` with its `.sha256` beside it, runs the pack check of that one pack
 * against `--catalog` (by default the pinned catalog) as information only, printed and added to
 * the run summary, and says whether the manifest publishes the pack. It fails only when the
 * archive cannot be built.
 *
 * `check` holds every pack under `languages/` to the catalog, which by default is built from the
 * tree in memory. What a pack is checked for is what the builds use it for, read from
 * angular.json: per project and configuration, the UI languages its language configuration names
 * (the administration app's `uiLanguages`, the public site's `languages`), through
 * scripts/build-contents-check.mjs — in the base product tree only: a country's set-up calls
 * checkPacks with its manifest's languages and never reaches this command. `--project` and
 * `--configuration` narrow that to the builds named. No base product build stages a pack's
 * backend half until the kit does, so in this tree the backend rows have no subject; checkPacks
 * runs them for the packs its caller names. The escape hatch is `missing-keys-accepted.json` in
 * fe/ (`--accepted`), a list of `{ lang, app, key, reason, added }` (and `bundle` for
 * `"app": "backend"`); the base product has none, and an absent file is an empty list.
 *
 * The rows of the pack check, for a pack and an application that uses it:
 *
 *   missing key            a catalog key the bundle lacks, or whose text is empty where
 *                          English's is not, fails, unless the escape hatch lists it or a
 *                          country's text override supplies it (listed); so does a language a
 *                          build uses that has no pack folder
 *   backend                a backend catalog key, or a whole bundle, the backend half lacks, or
 *                          a text empty where English's is not, fails for a UI language and is
 *                          a warning for a content language; the other languages'
 *                          `lang.<code>` keys of `web` are optional
 *   own name               a backend half whose `web` lacks the pack's own `lang.<code>`, or holds
 *                          it empty, is a warning for a UI language as for a content language: a
 *                          draft print names each model language by that key, and where no pack
 *                          has it, by its code in upper case
 *   placeholders           a text whose placeholders are not English's fails, unless the escape
 *                          hatch lists it (staging then ships English's) or a text override
 *                          supplies it (staging ships the override's)
 *   renamedFrom            a pack that still has a renamed key's old name is reported as a
 *                          rename, and the new key is missing until `migrate` moves it
 *   date pattern           a `*_DATE_FORMAT` with a token the renderer does not know, or a
 *                          `DATE_*` list with another number of entries than English's or an
 *                          empty one, fails
 *   markup                 a text whose tags, attributes and links are not English's, or that has
 *                          markup where English has none, fails
 *   pack.json, flag, path  a pack.json outside its schema, a flag that is not a PNG in the pack,
 *                          a path out of the pack or through a link, a key `__proto__`,
 *                          `prototype` or `constructor` fails (readPack, packFile, flatten)
 *   extra key              a key the catalog lacks is stripped from the staged bundle, and listed
 *   fingerprint            a text translated from another English text than today's is a
 *                          warning, listed
 *   hatch entry stale      an escape-hatch entry whose key is now translated, supplied by a text
 *                          override, or no longer in the catalog, is a warning
 *   hatch entry unused     an escape-hatch entry for a language or application no build uses
 *                          fails (a backend entry names its bundle, and a language a build uses)
 *   hatch printed          a non-empty escape hatch is printed in full
 *   status files           `status/unaccepted.json` has exactly a `note`, per application an
 *                          `unaccepted` list, with the optional reviewer notes `cldr` and
 *                          `verbatim`, and for the backend half `backend: { <bundle>: {
 *                          unaccepted } }`; every key listed is in the bundle with a text, once; a
 *                          `verbatim` text equals the same key's text in another pack checked
 *                          with it; and every key of an application's bundle is in exactly one of
 *                          `unaccepted` and `status/fingerprints.json`: a key with no
 *                          fingerprint that is not unaccepted fails, and so does an unaccepted
 *                          key with one. In the backend half a key listed and fingerprinted
 *                          fails too, but a key in neither is one warning per bundle with the
 *                          count: the backend half's status came after its texts. `accept`
 *                          moves a key from the first to the second; a hand edit that does the
 *                          same is not told apart, which no check of the committed files could
 *                          do. A foreign pack (a pinned one, or a local copy of one) is staged
 *                          from its canonical archive, which never carries `unaccepted.json`: its
 *                          status is its home's business, and only the fingerprint row applies
 *
 * `migrate`, `prune` and `accept` rewrite a pack's files in the shape the tree keeps them in: a
 * bundle as JSON.stringify writes it with two spaces, the status files with their lists sorted
 * and on one line where they fit, as Prettier writes them. A file is written only when it
 * changes.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  backendPlaceholders,
  buildCatalog,
  decodeProperties,
  FORMAT,
  markupOf,
  parseProperties,
  patternTokens,
  serializeCatalog,
  writeIfChanged,
} from './catalog.mjs';
import { baseLayout, where } from './lib/where.mjs';
import {
  APPS,
  flatten,
  isBlank,
  packFile,
  packLabel,
  placeholders,
  readPack,
} from './stage-languages.mjs';

const FE = where().base;
const ENGLISH = 'en';
const LANG = /^[a-z]{2,3}$/;
const BUNDLE = /^[a-z][a-z0-9]*$/;
const FINGERPRINT = /^[0-9a-f]{16}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const STATUS_LISTS = ['unaccepted', 'cldr', 'verbatim'];
const HATCH_FIELDS = ['added', 'app', 'key', 'lang', 'reason'];
const MAX_LISTED = 20;
const FORBIDDEN_KEYS = new Set(['__proto__', 'prototype', 'constructor']);

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
const same = (a, b) => a.length === b.length && a.every((item, i) => item === b[i]);
const shown = (items) => (items.length ? items.join(' ') : 'none');
const listed = (keys) =>
  keys.length > MAX_LISTED
    ? `${keys.slice(0, MAX_LISTED).join(', ')} and ${keys.length - MAX_LISTED} more`
    : keys.join(', ');
/** A `lang.<code>` key of `web`, a language's name: never stripped or pruned, whichever it names. */
const languageName = (bundle, key) => bundle === 'web' && /^lang\.[a-z]{2,3}$/.test(key);
/** Another language's name, which a pack may lack; its own is held apart (ownNameMissing). */
const exemptBackendKey = (bundle, key, lang) => languageName(bundle, key) && key !== `lang.${lang}`;
/** What the backend row says of a pack whose `web` half lacks its own language's name. */
const ownNameMissing = (lang) =>
  `lang.${lang}, the language's name in a draft print: where no pack has it, the print names ${lang} by its code, ${lang.toUpperCase()}`;

/** A catalog read from a file, checked for the format this tool reads. */
export function readCatalog(file) {
  const catalog = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (!isObject(catalog) || catalog.format !== FORMAT)
    throw new Error(`${file} is not a catalog of format ${FORMAT}`);
  return catalog;
}

/** A JSON file of a pack, or undefined when the pack has none; `label` names it in an error. */
function readJson(dir, relative, label) {
  try {
    const file = packFile(dir, relative);
    return file === undefined ? undefined : JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    throw new Error(`${label}: ${error.message}`, { cause: error });
  }
}

/** A pack's `status/unaccepted.json`, checked for its shape; problems go to `errors`. */
function readUnaccepted(dir, lang, errors) {
  const label = `${packLabel(dir)}/status/unaccepted.json`;
  const lists = {};
  let status;
  try {
    status = readJson(dir, 'status/unaccepted.json', label);
  } catch (error) {
    errors.push(error.message);
    return lists;
  }
  if (status === undefined) return lists;
  if (!isObject(status)) {
    errors.push(`${label} is not an object`);
    return lists;
  }
  const isKeys = (keys) => Array.isArray(keys) && keys.every((key) => typeof key === 'string');
  for (const [field, value] of Object.entries(status)) {
    if (field === 'note') {
      if (typeof value !== 'string') errors.push(`${label}: its note is not a text`);
      continue;
    }
    if (field === 'backend') {
      if (!isObject(value)) {
        errors.push(`${label}: backend is not an object of bundle → { unaccepted }`);
        continue;
      }
      lists.backend = {};
      for (const [bundle, section] of Object.entries(value)) {
        if (!BUNDLE.test(bundle))
          errors.push(`${label}: backend names the bundle "${bundle}", not ^[a-z][a-z0-9]*$`);
        else if (!isObject(section) || Object.keys(section).join() !== 'unaccepted')
          errors.push(`${label}: backend.${bundle} has exactly one list, unaccepted`);
        else if (!isKeys(section.unaccepted))
          errors.push(`${label}: backend.${bundle}.unaccepted is not a list of keys`);
        else lists.backend[bundle] = section.unaccepted;
      }
      continue;
    }
    if (!APPS.includes(field)) {
      errors.push(
        `${label} has "${field}"; it has a note, per application its lists and the backend's`,
      );
      continue;
    }
    if (!isObject(value) || !Array.isArray(value.unaccepted)) {
      errors.push(`${label}: ${field} has no unaccepted list`);
      continue;
    }
    lists[field] = {};
    for (const [list, keys] of Object.entries(value)) {
      if (!STATUS_LISTS.includes(list))
        errors.push(`${label}: ${field} has "${list}"; its lists are ${STATUS_LISTS.join(', ')}`);
      else if (!isKeys(keys)) errors.push(`${label}: ${field}.${list} is not a list of keys`);
      else lists[field][list] = keys;
    }
  }
  return lists;
}

/** A pack's `status/fingerprints.json`, checked for its shape; problems go to `errors`. */
function readFingerprints(dir, lang, errors) {
  const label = `${packLabel(dir)}/status/fingerprints.json`;
  let fingerprints;
  try {
    fingerprints = readJson(dir, 'status/fingerprints.json', label);
  } catch (error) {
    errors.push(error.message);
    return {};
  }
  if (fingerprints === undefined) return {};
  const keysToFingerprints = (section, name) => {
    if (!isObject(section) || !Object.values(section).every((fp) => FINGERPRINT.test(fp))) {
      errors.push(`${label}: ${name} is not an object of key → 16 hex digits`);
      return false;
    }
    return true;
  };
  if (!isObject(fingerprints)) {
    errors.push(`${label} is not an object`);
    return {};
  }
  const out = {};
  for (const [section, value] of Object.entries(fingerprints)) {
    if (APPS.includes(section)) {
      if (keysToFingerprints(value, section)) out[section] = value;
    } else if (section === 'backend' && isObject(value)) {
      out.backend = {};
      for (const [bundle, keys] of Object.entries(value))
        if (keysToFingerprints(keys, `backend.${bundle}`)) out.backend[bundle] = keys;
    } else
      errors.push(`${label} has "${section}"; its sections are ${[...APPS, 'backend'].join(', ')}`);
  }
  return out;
}

/** A pack's bundle of one application as flat keys, or undefined when it has none. */
function readBundle(dir, lang, app) {
  const label = `${packLabel(dir)}/i18n/${app}/${lang}.json`;
  const bundle = readJson(dir, `i18n/${app}/${lang}.json`, label);
  if (bundle === undefined) return undefined;
  try {
    return flatten(bundle);
  } catch (error) {
    throw new Error(`${label} ${error.message}`, { cause: error });
  }
}

/** The escape hatch's entries, each checked for its shape; problems go to `errors`. */
function readHatch(accepted, errors) {
  if (!Array.isArray(accepted)) {
    errors.push('the escape hatch is not a list');
    return [];
  }
  const entries = [];
  for (const [i, entry] of accepted.entries()) {
    const name = `the escape hatch's entry ${i + 1}`;
    if (!isObject(entry)) {
      errors.push(`${name} is not an object`);
      continue;
    }
    const fields = [...HATCH_FIELDS, ...(entry.app === 'backend' ? ['bundle'] : [])].sort();
    const problems = [];
    if (Object.keys(entry).sort().join() !== fields.join())
      problems.push(
        `has ${Object.keys(entry).sort().join(', ')}; it has exactly ${fields.join(', ')}`,
      );
    if (!LANG.test(entry.lang ?? '')) problems.push('names no language code');
    if (![...APPS, 'backend'].includes(entry.app))
      problems.push(`names the application "${entry.app}", not ${[...APPS, 'backend'].join(', ')}`);
    if (typeof entry.key !== 'string' || !entry.key) problems.push('names no key');
    if (typeof entry.reason !== 'string' || isBlank(entry.reason)) problems.push('gives no reason');
    if (!DATE.test(entry.added ?? '')) problems.push('has no added date, YYYY-MM-DD');
    if (problems.length) errors.push(`${name} ${problems.join('; ')}`);
    else entries.push(entry);
  }
  return entries;
}

const hatchName = ({ lang, app, bundle, key }) =>
  `${lang} ${app}${bundle ? ` ${bundle}` : ''} ${key}`;

/**
 * Checks the packs in the folders `packs` against `catalog`, for the applications `uses` names
 * each pack for: `{ admin: [lang], info: [lang], backend: { <lang>: 'ui' | 'content' } }`.
 * `accepted` is the escape hatch, and `workspaceUses` what every build of the workspace uses,
 * against which the hatch's entries are held (by default `uses`). `covered` names per language
 * and application the keys a country's text overrides supply (`{ <lang>: { <app>: [key] } }`):
 * such a key counts as present for the missing-key and placeholder rows, since staging ships the
 * override's text. English is the reference and is checked only for its pack.json. Returns the
 * errors and warnings, one line each, the keys stripped from the staged bundles, the renames
 * found, the hatch's entries and, per language and application, the keys the pack lacks that come
 * from text overrides. Beside the lines, the findings the upgrade report lists, structured: `missing`
 * (`{ lang, app, bundle?, keys, error }`, the keys a pack lacks; `bundle` for `app: 'backend'`),
 * `markup` (`{ lang, app, bundle?, key, error }`, a text whose markup is not English's),
 * `placeholders` (`{ lang, app, bundle?, key, error }`, a text whose placeholders are not
 * English's), `changedEnglish` (`{ lang, app, bundle?, key }`, a text translated from another
 * English text than today's) and `hatchStale` (`{ lang, app, bundle?, key, why }`, an
 * escape-hatch entry that can go: `translated`, `overridden` or `left the catalog`). Each finding
 * a failed row makes carries `error`, its line in `errors`.
 *
 * `foreign` names the languages whose status files are their home's: a pinned pack, or a local
 * copy of one named by NIORD_PACK_<LANG>, both staged from the canonical archive, which never
 * carries `status/unaccepted.json`. For them the status rows are skipped — the file's shape, the
 * one-list rule, verbatim, a key with no fingerprint that is not unaccepted — and the fingerprint
 * row still warns of a text translated from another English text.
 */
export function checkPacks({
  catalog,
  packs,
  uses,
  accepted = [],
  workspaceUses = uses,
  covered = {},
  foreign = [],
}) {
  const errors = [];
  const warnings = [];
  const stripped = [];
  const renames = [];
  const fromOverrides = [];
  const found = { missing: [], markup: [], placeholders: [], changedEnglish: [], hatchStale: [] };
  if (!isObject(catalog) || catalog.format !== FORMAT)
    throw new Error(`the catalog is not of format ${FORMAT}`);
  const hatch = readHatch(accepted, errors);
  const hatched = (lang, app, key, bundle = undefined) =>
    hatch.some(
      (entry) =>
        entry.lang === lang && entry.app === app && entry.key === key && entry.bundle === bundle,
    );
  const overridden = (lang, app, key) =>
    Array.isArray(covered[lang]?.[app]) && covered[lang][app].includes(key);
  const usedFor = (lang) => APPS.filter((app) => uses[app]?.includes(lang));

  // Every pack is read first: a verbatim text is compared with the other packs of the run.
  const read = [];
  const present = new Set();
  for (const dir of packs) {
    present.add(path.basename(dir));
    let manifest;
    try {
      manifest = readPack(dir);
    } catch (error) {
      errors.push(error.message);
      continue;
    }
    const { lang } = manifest;
    present.add(lang);
    if (lang === ENGLISH) continue;
    const sink = { errors, warnings };
    const pack = {
      lang,
      dir,
      sink,
      foreign: foreign.includes(lang),
      bundles: {},
      backendUse: uses.backend?.[lang],
    };
    for (const app of usedFor(lang)) {
      try {
        const bundle = readBundle(dir, lang, app);
        if (bundle) pack.bundles[app] = bundle;
        else
          sink.errors.push(
            `${packLabel(dir)}/i18n/${app}/${lang}.json does not exist, and ${lang} is a UI language of an ${app} build`,
          );
      } catch (error) {
        sink.errors.push(error.message);
      }
    }
    if (!Object.keys(pack.bundles).length && !pack.backendUse) continue;
    pack.unaccepted = pack.foreign ? {} : readUnaccepted(dir, lang, sink.errors);
    pack.fingerprints = readFingerprints(dir, lang, sink.errors);
    read.push(pack);
  }

  // A language a build uses with no pack at all: the missing-key row with every key missing.
  const usedLanguages = new Set([
    ...APPS.flatMap((app) => uses[app] ?? []),
    ...Object.keys(uses.backend ?? {}),
  ]);
  for (const lang of [...usedLanguages].sort()) {
    if (lang === ENGLISH || present.has(lang)) continue;
    const sink = { errors, warnings };
    for (const app of usedFor(lang))
      sink.errors.push(
        `languages/${lang}/ does not exist, and ${lang} is a UI language of an ${app} build`,
      );
    // As the backend row: an error for a UI language, a warning for a content language.
    if (!usedFor(lang).length)
      (uses.backend[lang] === 'ui' ? sink.errors : sink.warnings).push(
        `languages/${lang}/ does not exist, and ${lang} is a ${uses.backend[lang] === 'ui' ? 'UI' : 'content'} language of the backend`,
      );
  }

  for (const pack of read) {
    const { lang } = pack;
    const { errors, warnings } = pack.sink;
    for (const [app, flat] of Object.entries(pack.bundles)) {
      const label = `${packLabel(pack.dir)}/i18n/${app}/${lang}.json`;
      const entries = catalog.apps?.[app] ?? {};
      const renamedFrom = catalog.renamedFrom?.[app] ?? {};
      const lists = pack.unaccepted[app] ?? {};
      const unaccepted = new Set(lists.unaccepted ?? []);
      const fingerprints = pack.fingerprints[app] ?? {};
      const missing = [];
      const supplied = [];
      for (const [key, entry] of Object.entries(entries)) {
        const text = Object.hasOwn(flat, key) ? flat[key] : undefined;
        const oldKey = renamedFrom[key];
        if (oldKey !== undefined && Object.hasOwn(flat, oldKey))
          renames.push({ lang, app, key, from: oldKey });
        if (text === undefined || (isBlank(text) && !entry.empty)) {
          if (overridden(lang, app, key)) supplied.push(key);
          else if (!hatched(lang, app, key)) missing.push(key);
          continue;
        }
        const own = placeholders(text);
        if (!same(own, entry.placeholders)) {
          if (overridden(lang, app, key)) supplied.push(key);
          else if (!hatched(lang, app, key)) {
            const error = `${label}: ${key} has the placeholders ${shown(own)}, English ${shown(entry.placeholders)}`;
            errors.push(error);
            found.placeholders.push({ lang, app, key, error });
          }
          continue;
        }
        const markupError = checkText(label, key, text, entry, catalog, errors);
        if (markupError) found.markup.push({ lang, app, key, error: markupError });
        const fp = fingerprints[key];
        if (fp === undefined) {
          if (!pack.foreign && !unaccepted.has(key))
            errors.push(
              `${packLabel(pack.dir)}/status: ${app} ${key} has no fingerprint and is not unaccepted; a text is accepted with \`pack accept\`, which records the English it was translated from`,
            );
        } else if (unaccepted.has(key)) {
          errors.push(
            `${packLabel(pack.dir)}/status: ${app} ${key} is unaccepted and has a fingerprint; a key is in one of the two, and \`pack accept\` moves it from the first to the second`,
          );
        } else if (fp !== entry.fp) {
          warnings.push(
            `${label}: ${key} was translated from another English text (fingerprint ${fp}, English's now ${entry.fp}); check the translation, then \`pack accept\` it`,
          );
          found.changedEnglish.push({ lang, app, key });
        }
      }
      if (missing.length) {
        const error = `${label} lacks ${missing.length} catalog key(s): ${listed(missing)}`;
        errors.push(error);
        found.missing.push({ lang, app, keys: missing, error });
      }
      if (supplied.length) fromOverrides.push({ lang, app, keys: supplied });
      for (const key of Object.keys(flat))
        if (!Object.hasOwn(entries, key)) stripped.push({ lang, app, key });
      if (!pack.foreign) checkStatus(pack, app, read, errors);
    }
    if (pack.backendUse)
      checkBackend(pack, catalog, hatched, { errors, warnings, stripped, renames, found });
  }

  // The escape hatch: each entry for a language and application a build uses, and current.
  const workspaceLanguages = new Set([
    ...APPS.flatMap((app) => workspaceUses[app] ?? []),
    ...Object.keys(workspaceUses.backend ?? {}),
  ]);
  for (const entry of hatch) {
    const { lang, app, key, bundle } = entry;
    if (app === 'backend') {
      if (!workspaceLanguages.has(lang)) {
        errors.push(`the escape hatch lists ${hatchName(entry)}, but no build uses ${lang}`);
        continue;
      }
    } else if (!workspaceUses[app]?.includes(lang)) {
      errors.push(
        `the escape hatch lists ${hatchName(entry)}, but no ${app} build has ${lang} as a UI language`,
      );
      continue;
    }
    const pack = read.find((candidate) => candidate.lang === lang);
    const checked = app === 'backend' ? pack?.backendUse : pack?.bundles[app];
    if (!checked) continue;
    const reference = app === 'backend' ? catalog.backend?.[bundle] : catalog.apps?.[app];
    const entryOf = reference && Object.hasOwn(reference, key) ? reference[key] : undefined;
    const stale = (why) =>
      found.hatchStale.push({ lang, app, ...(bundle ? { bundle } : {}), key, why });
    if (!entryOf) {
      warnings.push(`the escape hatch lists ${hatchName(entry)}, which the catalog no longer has`);
      stale('left the catalog');
      continue;
    }
    if (app !== 'backend' && overridden(lang, app, key)) {
      warnings.push(`the escape hatch lists ${hatchName(entry)}, which a text override supplies`);
      stale('overridden');
      continue;
    }
    const text = app === 'backend' ? pack.backend?.[bundle]?.get(key) : pack.bundles[app][key];
    const own = app === 'backend' ? backendPlaceholders : placeholders;
    if (
      text !== undefined &&
      (!isBlank(text) || entryOf.empty) &&
      same(own(text), entryOf.placeholders)
    ) {
      warnings.push(`the escape hatch lists ${hatchName(entry)}, which the pack now translates`);
      stale('translated');
    }
  }

  const labels = Object.fromEntries(read.map((pack) => [pack.lang, packLabel(pack.dir)]));
  return { errors, warnings, stripped, renames, accepted: hatch, fromOverrides, labels, ...found };
}

/**
 * The rows on a text itself, for a pack's text and a text override's alike: its markup, and a
 * date pattern's tokens or a word list's entries. `entry` is the key's catalog entry; problems go
 * to `errors`, each starting with `label`. Returns the markup row's line, or undefined when the
 * markup is English's.
 */
export function checkText(label, key, text, entry, catalog, errors) {
  const markup = markupOf(text);
  const reference = entry.markup ?? [];
  let markupError;
  if (!same(markup, reference)) {
    markupError = reference.length
      ? `${label}: ${key}'s markup is ${shown(markup)}, English's ${shown(reference)}`
      : `${label}: ${key} has markup, ${shown(markup)}, where English has none`;
    errors.push(markupError);
  }
  if (entry.pattern) {
    const tokens = catalog.rules?.datePatternTokens ?? [];
    const unknown = patternTokens(text).filter((token) => !tokens.includes(token));
    if (unknown.length)
      errors.push(
        `${label}: ${key} "${text}" has the token(s) ${unknown.join(', ')}, which the date renderer does not know (${tokens.join(' ')})`,
      );
  }
  if (entry.list) {
    const words = text.split('|');
    if (words.length !== entry.list)
      errors.push(`${label}: ${key} has ${words.length} entries, English ${entry.list}`);
    else if (words.some(isBlank)) errors.push(`${label}: ${key} has an empty entry`);
  }
  return markupError;
}

/** The status-file row, for one application of a pack. */
function checkStatus(pack, app, read, errors) {
  const label = `${packLabel(pack.dir)}/status/unaccepted.json`;
  const flat = pack.bundles[app];
  const seen = new Map();
  for (const [list, keys] of Object.entries(pack.unaccepted[app] ?? {}))
    for (const key of keys) {
      if (seen.has(key))
        errors.push(
          `${label}: ${app} lists ${key} ${seen.get(key) === list ? `twice in ${list}` : `in ${seen.get(key)} and ${list}`}`,
        );
      seen.set(key, list);
      if (!Object.hasOwn(flat, key))
        errors.push(`${label}: ${app}.${list} lists ${key}, which the bundle does not have`);
      else if (isBlank(flat[key]))
        errors.push(`${label}: ${app}.${list} lists ${key}, whose text is empty`);
      else if (
        list === 'verbatim' &&
        !read.some((other) => other !== pack && other.bundles[app]?.[key] === flat[key])
      )
        errors.push(
          `${label}: ${app}.verbatim lists ${key}, whose text is not the same key's text in another pack checked with it (${
            read
              .filter((other) => other !== pack && other.bundles[app])
              .map((other) => other.lang)
              .join(', ') || 'none'
          })`,
        );
    }
}

/**
 * The backend half's status rows, for one bundle of a pack that is not foreign: a key listed
 * twice, listed where the half lacks it or holds it empty, or listed and fingerprinted fails; the
 * catalog keys in neither list are counted in one warning, since the half's status came after its
 * texts and recording a fingerprint for them would be accepting them.
 */
function checkBackendStatus(pack, bundle, entries, texts, fingerprints, { errors, warnings }) {
  const label = `${packLabel(pack.dir)}/status/unaccepted.json`;
  const listed = pack.unaccepted.backend?.[bundle] ?? [];
  const seen = new Set();
  for (const key of listed) {
    if (seen.has(key)) errors.push(`${label}: backend.${bundle} lists ${key} twice`);
    seen.add(key);
    if (texts === undefined || !texts.has(key))
      errors.push(
        `${label}: backend.${bundle} lists ${key}, which backend/${bundle}.properties does not have`,
      );
    else if (isBlank(texts.get(key)))
      errors.push(`${label}: backend.${bundle} lists ${key}, whose text is empty`);
    else if (fingerprints[key] !== undefined)
      errors.push(
        `${packLabel(pack.dir)}/status: backend ${bundle} ${key} is unaccepted and has a fingerprint; a key is in one of the two, and \`pack accept\` moves it from the first to the second`,
      );
  }
  if (texts === undefined) return;
  const untracked = [...texts.keys()].filter(
    (key) =>
      Object.hasOwn(entries, key) &&
      !exemptBackendKey(bundle, key, pack.lang) &&
      !isBlank(texts.get(key)) &&
      fingerprints[key] === undefined &&
      !seen.has(key),
  );
  if (untracked.length)
    warnings.push(
      `${packLabel(pack.dir)}/backend/${bundle}.properties: ${untracked.length} key(s) are neither accepted nor listed as unaccepted; pack accept --app backend --bundle ${bundle} records an accepted one`,
    );
}

/** The backend rows, for a pack whose language a deployment uses. */
function checkBackend(pack, catalog, hatched, { errors, warnings, stripped, renames, found }) {
  const { lang, dir, backendUse } = pack;
  const lacking = backendUse === 'ui' ? errors : warnings;
  pack.backend = {};
  if (!pack.foreign)
    for (const bundle of Object.keys(pack.unaccepted.backend ?? {}))
      if (!Object.hasOwn(catalog.backend ?? {}, bundle))
        errors.push(
          `${packLabel(dir)}/status/unaccepted.json: backend names the bundle ${bundle}, which the catalog does not have`,
        );
  for (const [bundle, entries] of Object.entries(catalog.backend ?? {})) {
    const label = `${packLabel(dir)}/backend/${bundle}.properties`;
    let file;
    try {
      file = packFile(dir, `backend/${bundle}.properties`);
    } catch (error) {
      errors.push(`${packLabel(dir)}: ${error.message}`);
      continue;
    }
    const required = Object.keys(entries).filter(
      (key) => !exemptBackendKey(bundle, key, lang) && !hatched(lang, 'backend', key, bundle),
    );
    if (!file) {
      if (!pack.foreign)
        checkBackendStatus(pack, bundle, entries, undefined, {}, { errors, warnings });
      // `web` holds the pack's own name too: its one line says so, else a line of its own.
      const ownName = bundle === 'web' ? ownNameMissing(lang) : undefined;
      if (required.length) {
        const error = `${label} does not exist, and ${lang} is a ${backendUse === 'ui' ? 'UI' : 'content'} language: the default language serves its ${required.length} key(s)${ownName ? `; and no ${ownName}` : ''}`;
        lacking.push(error);
        if (backendUse === 'ui')
          found.missing.push({ lang, app: 'backend', bundle, keys: required, error });
      } else if (ownName) warnings.push(`${label} does not exist: no ${ownName}`);
      continue;
    }
    let texts;
    try {
      texts = new Map(
        parseProperties(decodeProperties(fs.readFileSync(file))).map(({ key, value }) => [
          key,
          value,
        ]),
      );
    } catch (error) {
      errors.push(`${label} ${error.message}`);
      continue;
    }
    pack.backend[bundle] = texts;
    // A warning for a UI language as for a content language: the print still renders.
    if (bundle === 'web' && isBlank(texts.get(`lang.${lang}`) ?? ''))
      warnings.push(`${label} has no ${ownNameMissing(lang)}`);
    const fingerprints = pack.fingerprints.backend?.[bundle] ?? {};
    const renamedFrom = catalog.renamedFrom?.backend?.[bundle] ?? {};
    const missing = [];
    for (const [key, entry] of Object.entries(entries)) {
      const oldKey = renamedFrom[key];
      if (oldKey !== undefined && texts.has(oldKey))
        renames.push({ lang, app: 'backend', bundle, key, from: oldKey });
      const text = texts.get(key);
      if (text === undefined || (isBlank(text) && !entry.empty)) {
        if (!exemptBackendKey(bundle, key, lang) && !hatched(lang, 'backend', key, bundle))
          missing.push(key);
        continue;
      }
      const own = backendPlaceholders(text);
      if (!same(own, entry.placeholders)) {
        if (!hatched(lang, 'backend', key, bundle)) {
          const error = `${label}: ${key} has the placeholders ${shown(own)}, English ${shown(entry.placeholders)}`;
          errors.push(error);
          found.placeholders.push({ lang, app: 'backend', bundle, key, error });
        }
        continue;
      }
      const markupError = checkText(label, key, text, entry, catalog, errors);
      if (markupError) found.markup.push({ lang, app: 'backend', bundle, key, error: markupError });
      if (fingerprints[key] !== undefined && fingerprints[key] !== entry.fp) {
        warnings.push(
          `${label}: ${key} was translated from another English text (fingerprint ${fingerprints[key]}, English's now ${entry.fp})`,
        );
        found.changedEnglish.push({ lang, app: 'backend', bundle, key });
      }
    }
    if (missing.length) {
      const error = `${label} lacks ${missing.length} catalog key(s): ${listed(missing)}`;
      lacking.push(error);
      if (backendUse === 'ui')
        found.missing.push({ lang, app: 'backend', bundle, keys: missing, error });
    }
    if (!pack.foreign)
      checkBackendStatus(pack, bundle, entries, texts, fingerprints, { errors, warnings });
    for (const key of texts.keys())
      if (FORBIDDEN_KEYS.has(key)) errors.push(`${label} has the key "${key}"`);
      else if (!Object.hasOwn(entries, key) && !languageName(bundle, key))
        stripped.push({ lang, app: 'backend', bundle, key });
  }
}

// ---------------------------------------------------------------------------------------------
// migrate, prune, accept
// ---------------------------------------------------------------------------------------------

/**
 * JSON as Prettier writes it for the status files: every non-empty object over several lines,
 * an array of plain values on one line when it fits in 100 columns and one per line when not.
 */
export function formatJson(value) {
  const format = (item, indent, column, trailing) => {
    if (Array.isArray(item)) {
      if (!item.length) return '[]';
      const inner = `${indent}  `;
      if (item.every((element) => element === null || typeof element !== 'object')) {
        const line = `[${item.map((element) => JSON.stringify(element)).join(', ')}]`;
        if (column + line.length + trailing <= 100) return line;
      }
      const lines = item.map(
        (element, i) => inner + format(element, inner, inner.length, i < item.length - 1 ? 1 : 0),
      );
      return `[\n${lines.join(',\n')}\n${indent}]`;
    }
    if (isObject(item)) {
      const keys = Object.keys(item);
      if (!keys.length) return '{}';
      const inner = `${indent}  `;
      const lines = keys.map((key, i) => {
        const head = `${inner}${JSON.stringify(key)}: `;
        return head + format(item[key], inner, head.length, i < keys.length - 1 ? 1 : 0);
      });
      return `{\n${lines.join(',\n')}\n${indent}}`;
    }
    return JSON.stringify(item);
  };
  return `${format(value, '', 0, 0)}\n`;
}

const sortedObject = (object) =>
  Object.fromEntries(
    Object.keys(object)
      .sort()
      .map((key) => [key, object[key]]),
  );

/**
 * `status/unaccepted.json` in its kept order: the note, each application's sorted lists, then the
 * backend's bundles, sorted, each with its sorted list.
 */
function orderedUnaccepted(status) {
  const out = {};
  if (status.note !== undefined) out.note = status.note;
  for (const app of APPS) {
    if (!status[app]) continue;
    out[app] = {};
    for (const list of STATUS_LISTS)
      if (status[app][list]) out[app][list] = [...new Set(status[app][list])].sort();
  }
  if (status.backend)
    out.backend = Object.fromEntries(
      Object.keys(status.backend)
        .sort()
        .map((bundle) => [
          bundle,
          { unaccepted: [...new Set(status.backend[bundle].unaccepted ?? [])].sort() },
        ]),
    );
  return out;
}

/** `status/fingerprints.json` in its kept order: the applications, then backend, keys sorted. */
function orderedFingerprints(fingerprints) {
  const out = {};
  for (const app of APPS) if (fingerprints[app]) out[app] = sortedObject(fingerprints[app]);
  if (fingerprints.backend)
    out.backend = Object.fromEntries(
      Object.keys(fingerprints.backend)
        .sort()
        .map((bundle) => [bundle, sortedObject(fingerprints.backend[bundle])]),
    );
  return out;
}

/** A pack's files the tools rewrite, read for editing. */
function openPack(packsDir, lang) {
  if (!LANG.test(lang)) throw new Error(`"${lang}" is not a language code`);
  if (lang === ENGLISH)
    throw new Error('the en pack is the reference: nothing in it is migrated, pruned or accepted');
  const dir = path.join(packsDir, lang);
  readPack(dir);
  const json = (relative) => readJson(dir, relative, `${packLabel(dir)}/${relative}`);
  const bundles = {};
  for (const app of APPS) {
    const bundle = json(`i18n/${app}/${lang}.json`);
    if (bundle !== undefined) {
      flatten(bundle);
      bundles[app] = bundle;
    }
  }
  return {
    lang,
    dir,
    bundles,
    unaccepted: json('status/unaccepted.json'),
    fingerprints: json('status/fingerprints.json'),
  };
}

/** Writes back what a tool changed; returns the pack-relative paths written. */
function savePack(pack, { bundles = [], status = false, backend = {} }) {
  const written = [];
  const write = (relative, text) => {
    if (writeIfChanged(path.join(pack.dir, relative), text)) written.push(relative);
  };
  for (const app of bundles)
    write(`i18n/${app}/${pack.lang}.json`, `${JSON.stringify(pack.bundles[app], null, 2)}\n`);
  if (status) {
    if (pack.unaccepted)
      write('status/unaccepted.json', formatJson(orderedUnaccepted(pack.unaccepted)));
    if (pack.fingerprints)
      write('status/fingerprints.json', formatJson(orderedFingerprints(pack.fingerprints)));
  }
  for (const [bundle, text] of Object.entries(backend)) write(`backend/${bundle}.properties`, text);
  return written;
}

/** The path of a flat key in a nested bundle, or undefined. */
export function pathOf(bundle, key) {
  const walk = (node, rest) => {
    if (Object.hasOwn(node, rest) && typeof node[rest] === 'string') return [rest];
    for (const [name, child] of Object.entries(node))
      if (isObject(child) && rest.startsWith(`${name}.`)) {
        const found = walk(child, rest.slice(name.length + 1));
        if (found) return [name, ...found];
      }
    return undefined;
  };
  return walk(bundle, key);
}

/** Deletes the text at `trail`, and every object that leaves empty. */
function deleteAt(bundle, trail) {
  const parents = [bundle];
  for (const name of trail.slice(0, -1)) parents.push(parents.at(-1)[name]);
  delete parents.at(-1)[trail.at(-1)];
  for (let i = trail.length - 1; i > 0; i--)
    if (!Object.keys(parents[i]).length) delete parents[i - 1][trail[i - 1]];
}

/**
 * Sets a new flat key's text. With `english`, English's bundle of the same application, the key
 * goes where English has it — flat, nested or partly both, as English writes that key — so the
 * pack's shape follows English's key by key; without it, or when English lacks the key, nested
 * the way the bundle nests: into the objects already there. A text already at a segment the key
 * would be nested under, or an object where its text goes, is never replaced: that throws.
 */
export function setKey(bundle, key, text, english = undefined) {
  let trail = english ? pathOf(english, key) : undefined;
  if (!trail) {
    trail = [];
    let node = bundle;
    let rest = key;
    for (;;) {
      const name = Object.keys(node).find(
        (candidate) => isObject(node[candidate]) && rest.startsWith(`${candidate}.`),
      );
      if (!name) break;
      trail.push(name);
      node = node[name];
      rest = rest.slice(name.length + 1);
    }
    const nested = Object.values(bundle).some(isObject);
    trail.push(...(nested ? rest.split('.') : [rest]));
  }
  let node = bundle;
  for (const [i, segment] of trail.slice(0, -1).entries()) {
    if (!Object.hasOwn(node, segment)) node[segment] = {};
    else if (!isObject(node[segment]))
      throw new Error(
        `${key} cannot be set: "${trail.slice(0, i + 1).join('.')}" is a text, and the key goes under it; move that text by hand`,
      );
    node = node[segment];
  }
  if (isObject(node[trail.at(-1)]))
    throw new Error(`${key} cannot be set: the bundle has an object there; move it by hand`);
  node[trail.at(-1)] = text;
}

/** Sets a flat key's text in a nested bundle: in its place when the bundle has it, else as setKey. */
export function setText(bundle, key, text, english = undefined) {
  if (key.split('.').some((segment) => FORBIDDEN_KEYS.has(segment)))
    throw new Error(`the key "${key}" has a forbidden name`);
  const trail = pathOf(bundle, key);
  if (!trail) return setKey(bundle, key, text, english);
  let node = bundle;
  for (const name of trail.slice(0, -1)) node = node[name];
  node[trail.at(-1)] = text;
}

/** A backend half's file as text and entries, or undefined when the pack has none. */
function openProperties(pack, bundle) {
  const relative = `backend/${bundle}.properties`;
  const file = packFile(pack.dir, relative);
  if (!file) return undefined;
  try {
    const text = decodeProperties(fs.readFileSync(file));
    return { text, entries: parseProperties(text) };
  } catch (error) {
    throw new Error(`${packLabel(pack.dir)}/${relative} ${error.message}`, { cause: error });
  }
}

const escapeKey = (key) => key.replace(/[\\=: \t\f#!]/g, (c) => `\\${c}`);

function renameListEntries(lists, oldKey, newKey) {
  for (const list of STATUS_LISTS)
    if (lists?.[list]?.includes(oldKey))
      lists[list] = lists[list].map((key) => (key === oldKey ? newKey : key));
}

/**
 * Applies the catalog's renames to a pack: a renamed key's text moves to its new name, with its
 * fingerprint and its place in the status lists, when the pack has the old key and not the new
 * one. `english` is English's bundle per application (englishBundles): the new key is written
 * in English's shape for it (setKey). Returns the moves and the files written.
 */
export function migratePack({ packsDir, lang, catalog, english = {} }) {
  const pack = openPack(packsDir, lang);
  const moved = [];
  const changedBundles = [];
  let statusChanged = false;
  for (const app of APPS) {
    const bundle = pack.bundles[app];
    if (!bundle) continue;
    for (const [newKey, oldKey] of Object.entries(catalog.renamedFrom?.[app] ?? {})) {
      const from = pathOf(bundle, oldKey);
      if (!from || pathOf(bundle, newKey)) continue;
      const text = from.reduce((node, name) => node[name], bundle);
      deleteAt(bundle, from);
      setKey(bundle, newKey, text, english[app]);
      const fingerprints = pack.fingerprints?.[app];
      if (fingerprints && Object.hasOwn(fingerprints, oldKey)) {
        fingerprints[newKey] = fingerprints[oldKey];
        delete fingerprints[oldKey];
      }
      renameListEntries(pack.unaccepted?.[app], oldKey, newKey);
      moved.push({ app, key: newKey, from: oldKey });
      if (!changedBundles.includes(app)) changedBundles.push(app);
      statusChanged = true;
    }
  }
  const backend = {};
  for (const [bundle, renames] of Object.entries(catalog.renamedFrom?.backend ?? {})) {
    const file = openProperties(pack, bundle);
    if (!file) continue;
    const lines = file.text.split(/(\r\n|\r|\n)/);
    for (const [newKey, oldKey] of Object.entries(renames)) {
      if (file.entries.some(({ key }) => key === newKey)) continue;
      const entries = file.entries.filter(({ key }) => key === oldKey);
      if (!entries.length) continue;
      for (const entry of entries) {
        // Natural line n is lines[2n]: split keeps each line ending between two lines.
        const line = lines[entry.first * 2];
        const indent = line.match(/^[ \t\f]*/)[0];
        if (!line.slice(indent.length).startsWith(escapeKey(oldKey)))
          throw new Error(
            `${packLabel(pack.dir)}/backend/${bundle}.properties: ${oldKey} is not written as a plain key on line ${entry.first + 1}; rename it by hand`,
          );
        lines[entry.first * 2] =
          indent + escapeKey(newKey) + line.slice(indent.length + escapeKey(oldKey).length);
        entry.key = newKey;
      }
      const fingerprints = pack.fingerprints?.backend?.[bundle];
      if (fingerprints && Object.hasOwn(fingerprints, oldKey)) {
        fingerprints[newKey] = fingerprints[oldKey];
        delete fingerprints[oldKey];
        statusChanged = true;
      }
      if (pack.unaccepted?.backend?.[bundle]?.unaccepted?.includes(oldKey)) {
        renameListEntries(pack.unaccepted.backend[bundle], oldKey, newKey);
        statusChanged = true;
      }
      moved.push({ app: 'backend', bundle, key: newKey, from: oldKey });
      backend[bundle] = lines.join('');
    }
  }
  const written = savePack(pack, { bundles: changedBundles, status: statusChanged, backend });
  return { moved, written };
}

/**
 * Deletes from a pack every key none of `catalogs` has — a key a catalog renames from counts as
 * one it has, so `migrate` can still move it — with its fingerprint and its place in the status
 * lists. A file it removes nothing from is left byte for byte. Returns the keys removed and the
 * files written.
 */
export function prunePack({ packsDir, lang, catalogs }) {
  const pack = openPack(packsDir, lang);
  const keep = (section, renames) =>
    new Set(
      catalogs.flatMap((catalog) => [
        ...Object.keys(section(catalog) ?? {}),
        ...Object.values(renames(catalog) ?? {}),
      ]),
    );
  const removed = [];
  const changedBundles = [];
  let statusChanged = false;
  for (const app of APPS) {
    const kept = keep(
      (catalog) => catalog.apps?.[app],
      (catalog) => catalog.renamedFrom?.[app],
    );
    const bundle = pack.bundles[app];
    if (bundle)
      for (const key of Object.keys(flatten(bundle)))
        if (!kept.has(key)) {
          deleteAt(bundle, pathOf(bundle, key));
          removed.push({ app, key });
          if (!changedBundles.includes(app)) changedBundles.push(app);
        }
    const fingerprints = pack.fingerprints?.[app];
    for (const key of Object.keys(fingerprints ?? {}))
      if (!kept.has(key)) {
        delete fingerprints[key];
        statusChanged = true;
      }
    const lists = pack.unaccepted?.[app];
    for (const list of STATUS_LISTS)
      if (lists?.[list]?.some((key) => !kept.has(key))) {
        lists[list] = lists[list].filter((key) => kept.has(key));
        statusChanged = true;
      }
  }
  const backend = {};
  const bundles = new Set(catalogs.flatMap((catalog) => Object.keys(catalog.backend ?? {})));
  for (const bundle of bundles) {
    const kept = keep(
      (catalog) => catalog.backend?.[bundle],
      (catalog) => catalog.renamedFrom?.backend?.[bundle],
    );
    const file = openProperties(pack, bundle);
    if (file) {
      const drop = file.entries.filter(({ key }) => !kept.has(key) && !languageName(bundle, key));
      if (drop.length) {
        const lines = file.text.split(/(\r\n|\r|\n)/);
        const gone = new Set(drop.flatMap(({ first, last }) => range(first, last)));
        const out = [];
        for (let n = 0; n * 2 < lines.length; n++)
          if (!gone.has(n)) out.push(lines[n * 2], lines[n * 2 + 1] ?? '');
        backend[bundle] = out.join('');
        for (const { key } of drop) removed.push({ app: 'backend', bundle, key });
      }
    }
    const fingerprints = pack.fingerprints?.backend?.[bundle];
    for (const key of Object.keys(fingerprints ?? {}))
      if (!kept.has(key)) {
        delete fingerprints[key];
        statusChanged = true;
      }
    const lists = pack.unaccepted?.backend?.[bundle];
    if (lists?.unaccepted?.some((key) => !kept.has(key))) {
      lists.unaccepted = lists.unaccepted.filter((key) => kept.has(key));
      statusChanged = true;
    }
  }
  const written = savePack(pack, { bundles: changedBundles, status: statusChanged, backend });
  return { removed, written };
}

/** English's bundle of each application in the English pack folder `dir`, as written. */
export function englishBundles(dir) {
  const bundles = {};
  for (const app of APPS) {
    const bundle = readJson(
      dir,
      `i18n/${app}/${ENGLISH}.json`,
      `${packLabel(dir)}/i18n/${app}/${ENGLISH}.json`,
    );
    if (bundle !== undefined) bundles[app] = bundle;
  }
  return bundles;
}

const range = (first, last) => Array.from({ length: last - first + 1 }, (_, i) => first + i);

/**
 * The owner accepts the texts of `keys` in one application of a pack: each leaves the pack's
 * `unaccepted` list, and its fingerprint records the English text it was accepted against, which
 * also clears a fingerprint warning. Every key must be in the catalog and have a text in the pack.
 * With `app: 'backend'` the keys are those of the backend half's `bundle`, which only it takes.
 * Returns the keys removed from `unaccepted` and the files written.
 */
export function acceptKeys({ packsDir, lang, app, bundle: backendBundle, keys, catalog }) {
  if (app === 'backend')
    return acceptBackendKeys({ packsDir, lang, bundle: backendBundle, keys, catalog });
  if (!APPS.includes(app))
    throw new Error(`--app is ${[...APPS, 'backend'].join(', ')}, not "${app}"`);
  if (backendBundle !== undefined)
    throw new Error('--bundle names a bundle of the backend half: it comes with --app backend');
  if (!keys.length) throw new Error('name the key(s) to accept');
  const pack = openPack(packsDir, lang);
  const bundle = pack.bundles[app];
  if (!bundle) throw new Error(`${packLabel(pack.dir)} has no ${app} bundle`);
  const flat = flatten(bundle);
  const problems = [];
  for (const key of keys) {
    const entry = catalog.apps?.[app]?.[key];
    if (!entry) problems.push(`${key} is not in the ${app} catalog`);
    else if (!Object.hasOwn(flat, key) || (isBlank(flat[key]) && !entry.empty))
      problems.push(
        `${key} has no text in ${packLabel(pack.dir)}/i18n/${app}/${lang}.json to accept`,
      );
  }
  if (problems.length) throw new Error(problems.join('; '));
  const unaccepted = pack.unaccepted?.[app]?.unaccepted ?? [];
  const removed = keys.filter((key) => unaccepted.includes(key));
  if (pack.unaccepted?.[app])
    pack.unaccepted[app].unaccepted = unaccepted.filter((key) => !keys.includes(key));
  pack.fingerprints ??= {};
  pack.fingerprints[app] ??= {};
  for (const key of keys) pack.fingerprints[app][key] = catalog.apps[app][key].fp;
  const written = savePack(pack, { status: true });
  return { removed, written };
}

/** acceptKeys for the backend half's `bundle`: `backend/<bundle>.properties` and its status. */
function acceptBackendKeys({ packsDir, lang, bundle, keys, catalog }) {
  if (bundle === undefined)
    throw new Error('--app backend needs --bundle <bundle>, the backend half the keys are in');
  if (!BUNDLE.test(bundle)) throw new Error(`"${bundle}" is not a bundle name`);
  if (!keys.length) throw new Error('name the key(s) to accept');
  const pack = openPack(packsDir, lang);
  const entries = catalog.backend?.[bundle];
  if (!entries) throw new Error(`the catalog has no backend bundle ${bundle}`);
  const file = openProperties(pack, bundle);
  if (!file) throw new Error(`${packLabel(pack.dir)} has no backend/${bundle}.properties`);
  const texts = new Map(file.entries.map(({ key, value }) => [key, value]));
  const problems = [];
  for (const key of keys) {
    const entry = Object.hasOwn(entries, key) ? entries[key] : undefined;
    if (!entry) problems.push(`${key} is not in the backend ${bundle} catalog`);
    else if (!texts.has(key) || (isBlank(texts.get(key)) && !entry.empty))
      problems.push(
        `${key} has no text in ${packLabel(pack.dir)}/backend/${bundle}.properties to accept`,
      );
  }
  if (problems.length) throw new Error(problems.join('; '));
  const listed = pack.unaccepted?.backend?.[bundle];
  const unaccepted = listed?.unaccepted ?? [];
  const removed = keys.filter((key) => unaccepted.includes(key));
  if (listed) listed.unaccepted = unaccepted.filter((key) => !keys.includes(key));
  pack.fingerprints ??= {};
  pack.fingerprints.backend ??= {};
  pack.fingerprints.backend[bundle] ??= {};
  for (const key of keys) pack.fingerprints.backend[bundle][key] = entries[key].fp;
  const written = savePack(pack, { status: true });
  return { removed, written };
}

// ---------------------------------------------------------------------------------------------
// The command line
// ---------------------------------------------------------------------------------------------

/** Per application the languages a set of build configurations uses, en left out. */
export function usesOf(configurations) {
  const uses = { ...Object.fromEntries(APPS.map((app) => [app, []])), backend: {} };
  for (const { app, ui } of configurations)
    if (APPS.includes(app))
      for (const lang of ui)
        if (lang !== ENGLISH && !uses[app].includes(lang)) uses[app].push(lang);
  for (const app of APPS) uses[app].sort();
  return uses;
}

/** The pack folders under `packsDir`, sorted; a link is an error. */
export function packFolders(packsDir) {
  return fs
    .readdirSync(packsDir, { withFileTypes: true })
    .sort((a, b) => a.name.localeCompare(b.name, 'en'))
    .filter((entry) => {
      if (entry.isSymbolicLink())
        throw new Error(`${packLabel(path.join(packsDir, entry.name))} is a link`);
      return entry.isDirectory();
    })
    .map((entry) => path.join(packsDir, entry.name));
}

/** The printed report of a check. */
export function report(
  { errors, warnings, stripped, renames, accepted, fromOverrides = [], labels = {} },
  hatchFile,
) {
  const lines = [];
  for (const error of errors) lines.push(`error: ${error}`);
  for (const warning of warnings) lines.push(`warning: ${warning}`);
  const grouped = new Map();
  for (const { lang, app, bundle, key } of stripped) {
    const folder = labels[lang] ?? `languages/${lang}`;
    const where =
      app === 'backend'
        ? `${folder}/backend/${bundle}.properties`
        : `${folder}/i18n/${app}/${lang}.json`;
    grouped.set(where, [...(grouped.get(where) ?? []), key]);
  }
  for (const [where, keys] of grouped)
    lines.push(`stripped (not in the catalog): ${where}: ${listed(keys)}`);
  for (const { lang, app, bundle, key, from } of renames)
    lines.push(
      `rename: ${lang} ${app}${bundle ? ` ${bundle}` : ''} ${from} → ${key}; run \`pack migrate ${lang}\``,
    );
  for (const { lang, app, keys } of fromOverrides)
    lines.push(
      `${lang} ${app}: ${keys.length} key(s) the pack lacks come from text overrides: ${listed(keys)}`,
    );
  if (accepted.length) {
    lines.push(`the escape hatch, ${hatchFile}, lists ${accepted.length} key(s) shown in English:`);
    for (const entry of accepted)
      lines.push(`  ${hatchName(entry)} — ${entry.reason} (added ${entry.added})`);
  }
  return lines;
}

const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

/**
 * Where the tools find the packs and the catalog, for the base product's fe/ `fe` (the one the
 * layout resolver finds for the kit, when none is given). In a country workspace that is the
 * unpacked base product, `<country>/fe/.niord/base/`: the packs are the country's home packs,
 * `<country>/fe/packs/` (a pinned pack is never edited here: it is the pack's own repository's),
 * and the catalog is the pinned zip's `catalog.json`, since the base product's be/ is not there to
 * build one from. In the base product tree they are `languages/` and a catalog built from the
 * tree. Any other folder is refused, as the resolver refuses it.
 */
export function packsLayout(fe = FE) {
  const layout = baseLayout(fe);
  const country = layout.kind === 'country';
  return country
    ? {
        country,
        packsDir: path.join(layout.workspace, 'packs'),
        catalog: path.join(fe, 'catalog.json'),
        english: path.join(fe, 'languages', ENGLISH),
      }
    : {
        country,
        packsDir: path.join(fe, 'languages'),
        catalog: undefined,
        english: path.join(fe, 'languages', ENGLISH),
      };
}

const COMMANDS = ['check', 'migrate', 'prune', 'accept', 'latest-catalog', 'validate'];

/**
 * `validate <lang>`: a home pack's canonical archive and its sha256 into `--out`, the pack check
 * against `--catalog` as information, and whether the manifest publishes the pack. Refuses a
 * language that is not one of the manifest's home packs; returns 1 when the archive cannot be
 * built, else 0, whatever the pack check finds.
 */
async function validate({ layout, workspace, packsDir, options, env, log, error }) {
  if (!layout.country)
    throw new Error(
      "validate runs in a country workspace, from its fe/: it archives one of the country's home packs",
    );
  const [lang, ...more] = options.positional;
  if (!lang) throw new Error('validate needs a language');
  if (more.length) throw new Error(`validate takes one language, not ${more.join(' ')}`);
  if (!LANG.test(lang)) throw new Error(`"${lang}" is not a language code, two or three a-z`);
  if (!options.out) throw new Error('validate needs --out <dir>');
  if (options.catalogs.length > 1) throw new Error('validate reads one --catalog');
  const dir = path.join(packsDir, lang);
  if (!fs.existsSync(dir)) throw new Error(`${packLabel(dir)}/ does not exist`);
  const { readManifest } = await import('./lib/manifest.mjs');
  const manifest = readManifest(path.join(workspace, 'niord-country.json'));
  const entry = manifest.packs[lang];
  if (!entry?.home)
    throw new Error(
      `niord-country.json does not name ${lang} as a home pack: a country publishes its home packs only`,
    );
  const publish = entry.publish === true;
  const { buildPackArchive } = await import('./lib/pack-archive.mjs');
  let archive;
  try {
    archive = buildPackArchive(dir, lang);
  } catch (failure) {
    error(`pack validate: ${failure.message}`);
    error(`pack validate: no archive of ${packLabel(dir)} was built`);
    return 1;
  }
  const name = `niord-lang-${lang}.zip`;
  fs.mkdirSync(options.out, { recursive: true });
  fs.writeFileSync(path.join(options.out, name), archive.zip);
  fs.writeFileSync(path.join(options.out, `${name}.sha256`), `${archive.sha256}  ${name}\n`);
  log(
    `pack validate: ${packLabel(dir)} → ${name}, ${archive.entries.length} files, ${archive.zip.length} bytes, sha256 ${archive.sha256}`,
  );

  // The pack check against the latest catalog: information, since each consumer is held to its
  // own version's catalog by its own set-up.
  const information = [];
  let catalogVersion = 'unknown';
  try {
    const catalog = readCatalog(options.catalogs[0] ?? layout.catalog);
    catalogVersion = catalog.version ?? 'unknown';
    const has = (prefix) => archive.entries.some(({ name: entry }) => entry.startsWith(prefix));
    const result = checkPacks({
      catalog,
      packs: [layout.english, dir],
      uses: {
        admin: has('i18n/admin/') ? [lang] : [],
        info: has('i18n/info/') ? [lang] : [],
        backend: has('backend/') ? { [lang]: 'ui' } : {},
      },
    });
    information.push(...report(result, 'missing-keys-accepted.json'));
    information.push(
      `${result.errors.length} error(s), ${result.warnings.length} warning(s), ${result.stripped.length} key(s) stripped`,
    );
  } catch (failure) {
    information.push(`the pack check did not run: ${failure.message}`);
  }
  log(
    `pack validate: the pack check of ${lang} against the base product catalog ${catalogVersion}, information only (a consumer's set-up holds it to the consumer's version):`,
  );
  for (const line of information) log(`pack validate:   ${line}`);
  log(`publish: ${publish}`);

  if (env.GITHUB_STEP_SUMMARY) {
    // A fence longer than any run of backticks in the lines, which quote the pack's texts.
    const fence = '`'.repeat(
      Math.max(
        3,
        ...information.flatMap((line) => line.match(/`+/g) ?? []).map((run) => run.length + 1),
      ),
    );
    fs.appendFileSync(
      env.GITHUB_STEP_SUMMARY,
      [
        `### niord-lang-${lang}`,
        '',
        `The canonical archive \`${name}\`: ${archive.entries.length} files, sha256 \`${archive.sha256}\`. ${publish ? 'niord-country.json publishes it.' : 'niord-country.json does not publish it (`"publish": false`).'}`,
        '',
        `The pack check against the latest base product catalog, ${catalogVersion} (information only):`,
        '',
        `${fence}text`,
        ...information,
        fence,
        '',
      ].join('\n'),
    );
  }
  if (env.GITHUB_OUTPUT)
    fs.appendFileSync(
      env.GITHUB_OUTPUT,
      `lang=${lang}\npublish=${publish}\nsha256=${archive.sha256}\n`,
    );
  return 0;
}

/**
 * The command line, for the tree `fe` (packsLayout); returns the exit status. `log` and
 * `error` print a line; `env` holds MAVEN_ARGS and GitHub's GITHUB_STEP_SUMMARY and
 * GITHUB_OUTPUT; `run` stands in for lib/maven.mjs's runMaven in a test.
 */
export async function main(
  args,
  { fe = FE, log = console.log, error = console.error, env = process.env, run = undefined } = {},
) {
  const [command, ...rest] = args;
  if (!COMMANDS.includes(command))
    throw new Error(`unknown command "${command ?? ''}" (${COMMANDS.join(', ')})`);
  const layout = packsLayout(fe);
  // The tree's own fe/ and be/: the country's in a country workspace, else the base product's.
  const { workspace, be } = baseLayout(fe);
  const maven = { fe: workspace, be, env, ...(run && { run }) };
  const options = { catalogs: [], positional: [] };
  for (let i = 0; i < rest.length; i++) {
    const flag = rest[i];
    const value = () => {
      if (rest[i + 1] === undefined) throw new Error(`${flag} needs a value`);
      return rest[++i];
    };
    if (flag === '--project') options.project = value();
    else if (flag === '--configuration') options.configuration = value();
    else if (flag === '--catalog') options.catalogs.push(path.resolve(value()));
    else if (flag === '--accepted') options.accepted = path.resolve(value());
    else if (flag === '--app') options.app = value();
    else if (flag === '--bundle') options.bundle = value();
    else if (flag === '--packs') options.packs = path.resolve(value());
    else if (flag === '--base') options.base = value();
    else if (flag === '--out') options.out = path.resolve(value());
    else if (flag.startsWith('--')) throw new Error(`unknown option ${flag}`);
    else options.positional.push(flag);
  }
  const takes = {
    check: ['--project', '--configuration', '--catalog', '--accepted', '--packs'],
    migrate: ['--catalog', '--packs'],
    prune: ['--catalog', '--packs', '--base'],
    accept: ['--catalog', '--packs', '--app', '--bundle'],
    'latest-catalog': ['--out'],
    validate: ['--catalog', '--packs', '--out'],
  }[command];
  const given = {
    '--project': options.project,
    '--configuration': options.configuration,
    '--catalog': options.catalogs.length || undefined,
    '--accepted': options.accepted,
    '--app': options.app,
    '--bundle': options.bundle,
    '--packs': options.packs,
    '--base': options.base,
    '--out': options.out,
  };
  for (const [flag, set] of Object.entries(given))
    if (set !== undefined && !takes.includes(flag)) throw new Error(`${command} takes no ${flag}`);
  const packsDir = options.packs ?? layout.packsDir;
  const catalogs = () => {
    if (options.catalogs.length) return options.catalogs.map(readCatalog);
    return [
      layout.catalog
        ? readCatalog(layout.catalog)
        : buildCatalog({ fe, be: path.resolve(fe, '..', 'be') }),
    ];
  };

  if (command === 'check') {
    if (layout.country)
      throw new Error(
        "check runs in the base product tree; in a country workspace set-up (node setup.mjs) runs the pack check, with the country's manifest, packs and text overrides",
      );
    if (options.positional.length) throw new Error(`check takes no ${options.positional[0]}`);
    if (options.configuration && !options.project)
      throw new Error('--configuration needs --project');
    if (options.catalogs.length > 1) throw new Error('check reads one --catalog');
    // The base product's own scripts, from the kit's resolved base: `fe` may be any tree.
    const script = (name) => pathToFileURL(path.join(where().base, 'scripts', name)).href;
    const { configurationLanguages } = await import(script('build-contents-check.mjs'));
    const { readAngularJson } = await import(script('workspace-paths-check.mjs'));
    const all = await configurationLanguages(readAngularJson());
    const selected = all.filter(
      (row) =>
        (!options.project || row.project === options.project) &&
        (!options.configuration || row.configuration === options.configuration),
    );
    if (options.project && !selected.length)
      throw new Error(
        `angular.json has no ${options.configuration ? `configuration "${options.configuration}" of ` : ''}project "${options.project}"`,
      );
    const hatchFile = options.accepted ?? path.join(fe, 'missing-keys-accepted.json');
    const accepted = fs.existsSync(hatchFile) ? JSON.parse(fs.readFileSync(hatchFile, 'utf8')) : [];
    const uses = usesOf(selected);
    const result = checkPacks({
      catalog: catalogs()[0],
      packs: packFolders(packsDir),
      uses,
      accepted,
      workspaceUses: usesOf(all),
    });
    const scope = options.project
      ? `${options.project}${options.configuration ? `:${options.configuration}` : ''}`
      : 'every build';
    const used =
      APPS.filter((app) => uses[app].length)
        .map((app) => `${app} ${uses[app].join(', ')}`)
        .join('; ') || 'English only, no pack to check';
    for (const line of report(result, path.relative(fe, hatchFile).replaceAll('\\', '/')))
      (line.startsWith('error') ? error : log)(`pack check: ${line}`);
    const verdict = `${result.errors.length} error(s), ${result.warnings.length} warning(s), ${result.stripped.length} key(s) stripped`;
    if (result.errors.length) {
      error(`pack check: ${scope} (${used}): ${verdict}`);
      return 1;
    }
    log(`pack check: ${scope} (${used}): ${verdict}`);
    return 0;
  }

  if (command === 'latest-catalog') {
    if (!layout.country)
      throw new Error(
        "latest-catalog runs in a country workspace, from its fe/: it reads the newest published catalog through the country's Maven wrapper",
      );
    if (options.positional.length)
      throw new Error(`latest-catalog takes no ${options.positional[0]}`);
    if (!options.out) throw new Error('latest-catalog needs --out <file>');
    const { latestCatalog } = await import('./lib/published-catalogs.mjs');
    const { version, catalog } = latestCatalog(maven);
    fs.mkdirSync(path.dirname(options.out), { recursive: true });
    fs.writeFileSync(options.out, serializeCatalog(catalog));
    log(`latest-catalog: ${version}`);
    return 0;
  }

  if (command === 'validate')
    return validate({ layout, workspace, packsDir, options, env, log, error });

  const [lang, ...keys] = options.positional;
  if (!lang) throw new Error(`${command} needs a language`);
  const folder = packLabel(path.join(packsDir, lang));
  const files = (written) => written.map((file) => `${folder}/${file}`).join(', ') || 'nothing';
  if (command === 'migrate') {
    if (keys.length) throw new Error(`migrate takes one language, not ${keys.join(' ')}`);
    const { moved, written } = migratePack({
      packsDir,
      lang,
      catalog: catalogs()[0],
      english: englishBundles(layout.english),
    });
    for (const { app, bundle, key, from } of moved)
      log(`pack migrate: ${lang} ${app}${bundle ? ` ${bundle}` : ''} ${from} → ${key}`);
    log(`pack migrate: ${moved.length} key(s) moved; ${files(written)} written`);
    return 0;
  }
  if (command === 'prune') {
    if (keys.length) throw new Error(`prune takes one language, not ${keys.join(' ')}`);
    let pruneCatalogs;
    if (options.base !== undefined) {
      if (options.catalogs.length)
        throw new Error(
          '--base reads the catalogs of the published versions from <version> up; it takes no --catalog',
        );
      openPack(packsDir, lang);
      const { publishedCatalogs } = await import('./lib/published-catalogs.mjs');
      const published = publishedCatalogs({
        ...maven,
        from: options.base,
        onVersion: (version, index, count) =>
          log(`pack prune: reading the catalog of ${version} (${index + 1} of ${count})`),
      });
      log(`pack prune: the catalogs of ${published.map(({ version }) => version).join(', ')} read`);
      pruneCatalogs = published.map(({ catalog }) => catalog);
    } else pruneCatalogs = catalogs();
    const { removed, written } = prunePack({ packsDir, lang, catalogs: pruneCatalogs });
    for (const { app, bundle, key } of removed)
      log(`pack prune: ${lang} ${app}${bundle ? ` ${bundle}` : ''} ${key}`);
    log(`pack prune: ${removed.length} key(s) removed; ${files(written)} written`);
    return 0;
  }
  if (!options.app) throw new Error(`accept needs --app ${[...APPS, 'backend'].join('|')}`);
  const { removed, written } = acceptKeys({
    packsDir,
    lang,
    app: options.app,
    bundle: options.bundle,
    keys,
    catalog: catalogs()[0],
  });
  log(
    `pack accept: ${keys.length} key(s) accepted, ${removed.length} of them taken off unaccepted; ${files(written)} written`,
  );
  return 0;
}

if (invokedDirectly) {
  try {
    process.exit(await main(process.argv.slice(2)));
  } catch (error) {
    console.error(`pack: ${error.message}`);
    process.exit(2);
  }
}
