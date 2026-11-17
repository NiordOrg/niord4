/**
 * Staging for a country workspace: writes into `.niord/build/<app>/` everything a build ships
 * from the packs and the country's branding, and nothing else — each application's angular.json
 * reads that folder as its only asset input for them, so status files, licences, a flag's drawing
 * or another country's files can never be copied.
 *
 * Per application:
 *
 *   i18n/<lang>.json      exactly the application's `uiLanguages` (`en` only when they name it),
 *                         each built from three layers, a later one winning for the keys it sets:
 *                         English; the language's pack, stripped to the catalog's keys and filled
 *                         from English (stage-languages.mjs fillFromEnglish), so a key the pack
 *                         lacks — the escape hatch's included — or whose placeholders are not
 *                         English's shows English and no application needs English at runtime;
 *                         the country's text overrides of that language (lib/text-overrides.mjs).
 *                         Branding comes after them, merged by the applications at runtime. A
 *                         bundle no layer changed is its source's bytes; English with overrides is
 *                         written from JSON
 *   img/flags/<lang>.png  the flag of every language the application names: the administration
 *                         app's UI and content languages, the public site's UI languages
 *   branding/…, legal/…   the country's own files under `<app>/assets/`, as the caller names them
 *                         (the files branding.schema.json allows; the branding check has passed)
 *   extra files           what the caller adds: the build report, in the administration app
 *
 * A file is written only when its bytes differ, through a temporary copy and a rename, and a
 * staged file nothing produces any more is removed, so a second run writes nothing.
 */
import fs from 'node:fs';
import path from 'node:path';
import { setText } from '../pack.mjs';
import { fillFromEnglish, flatten, packFile, readPack } from '../stage-languages.mjs';

const ENGLISH = 'en';
const toPosix = (p) => p.replaceAll('\\', '/');
const json = (bundle) => Buffer.from(`${JSON.stringify(bundle, null, 2)}\n`, 'utf8');

function readBundle(dir, relative, label) {
  let file;
  try {
    file = packFile(dir, relative);
  } catch (error) {
    throw new Error(`${label}: ${error.message}`, { cause: error });
  }
  if (!file) return undefined;
  try {
    return { bytes: fs.readFileSync(file), bundle: JSON.parse(fs.readFileSync(file, 'utf8')) };
  } catch (error) {
    throw new Error(`${label}: ${error.message}`, { cause: error });
  }
}

/** Sets each override's text in `bundle`, in the key's place; returns how many it set. */
function applyOverrides(bundle, overrides, label) {
  let count = 0;
  for (const [key, text] of Object.entries(overrides)) {
    try {
      setText(bundle, key, text);
    } catch (error) {
      throw new Error(`${label}: ${error.message}`, { cause: error });
    }
    count++;
  }
  return count;
}

/**
 * The files one application's staging folder holds: relative path → bytes. `textOverrides` maps
 * each application to its overridden texts per language, `{ <app>: { <lang>: { <key>: <text> } } }`.
 */
export function plannedFiles({
  app,
  uiLanguages,
  flagLanguages,
  packs,
  assets,
  extra = {},
  textOverrides = {},
}) {
  const files = new Map();
  const english = packs[ENGLISH];
  const englishLabel = `languages/en/i18n/${app}/en.json`;
  const en = readBundle(english, `i18n/${app}/en.json`, englishLabel);
  if (!en) throw new Error(`${englishLabel} does not exist in the base product`);
  try {
    flatten(en.bundle);
  } catch (error) {
    throw new Error(`${englishLabel} ${error.message}`, { cause: error });
  }
  const ownOverrides = Object.hasOwn(textOverrides, app) ? textOverrides[app] : {};
  const overridesOf = (lang) => (Object.hasOwn(ownOverrides, lang) ? ownOverrides[lang] : {});
  const filled = {};
  const overridden = {};
  for (const lang of uiLanguages) {
    const overrides = overridesOf(lang);
    const overridesLabel = `text-overrides/${lang}.json (${app})`;
    if (lang === ENGLISH) {
      if (!Object.keys(overrides).length) {
        files.set(`i18n/${ENGLISH}.json`, en.bytes);
        continue;
      }
      const bundle = structuredClone(en.bundle);
      overridden[ENGLISH] = applyOverrides(bundle, overrides, overridesLabel);
      files.set(`i18n/${ENGLISH}.json`, json(bundle));
      continue;
    }
    const label = `packs/${lang}/i18n/${app}/${lang}.json`;
    const own = readBundle(packs[lang], `i18n/${app}/${lang}.json`, label);
    if (!own) throw new Error(`${label} does not exist, and ${lang} is a UI language of ${app}`);
    let result;
    try {
      result = fillFromEnglish(en.bundle, own.bundle);
    } catch (error) {
      throw new Error(`${label} ${error.message}`, { cause: error });
    }
    const count = applyOverrides(result.bundle, overrides, overridesLabel);
    if (count) overridden[lang] = count;
    const changed =
      count || result.missing.length || result.stripped.length || result.replaced.length;
    files.set(`i18n/${lang}.json`, changed ? json(result.bundle) : own.bytes);
    // A key an override sets shows the override's text, not English's.
    const fromEnglish = [...result.missing, ...result.replaced].filter(
      (key) => !Object.hasOwn(overrides, key),
    ).length;
    if (fromEnglish) filled[lang] = fromEnglish;
  }
  for (const lang of flagLanguages) {
    const { flagFile } = readPack(packs[lang]);
    files.set(`img/flags/${lang}.png`, fs.readFileSync(flagFile));
  }
  for (const [relative, source] of Object.entries(assets))
    files.set(relative, fs.readFileSync(source));
  for (const [relative, bytes] of Object.entries(extra)) files.set(relative, bytes);
  return { files, filled, overridden };
}

/** Every file under `dir`, relative and with `/`, sorted; links are listed, never followed. */
function listFiles(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs
    .readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter((entry) => !entry.isDirectory())
    .map((entry) => toPosix(path.relative(dir, path.join(entry.parentPath, entry.name))))
    .sort();
}

function removeEmptyFolders(dir, root) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true }))
    if (entry.isDirectory()) removeEmptyFolders(path.join(dir, entry.name), root);
  if (dir !== root && fs.readdirSync(dir).length === 0) fs.rmdirSync(dir);
}

/** Writes `files` into `target`, only what changed, and removes what is no longer planned. */
export function writeFolder(target, files) {
  if (fs.existsSync(target) && fs.lstatSync(target).isSymbolicLink())
    throw new Error(`${toPosix(target)} is a link`);
  const written = [];
  const removed = [];
  for (const [relative, bytes] of files) {
    const file = path.join(target, relative);
    let current;
    try {
      if (fs.lstatSync(file).isFile()) current = fs.readFileSync(file);
      else fs.rmSync(file, { recursive: true, force: true });
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
    if (current?.equals(bytes)) continue;
    fs.mkdirSync(path.dirname(file), { recursive: true });
    const temporary = `${file}.${process.pid}.tmp`;
    fs.writeFileSync(temporary, bytes);
    fs.renameSync(temporary, file);
    written.push(relative);
  }
  for (const relative of listFiles(target))
    if (!files.has(relative)) {
      fs.rmSync(path.join(target, relative), { force: true });
      removed.push(relative);
    }
  if (fs.existsSync(target)) removeEmptyFolders(target, target);
  return { written, removed };
}

/**
 * The languages each application ships a flag for: the administration app's UI and content
 * languages, the public site's UI languages.
 */
export function flagLanguages(manifest) {
  const admin = manifest.apps.admin;
  return {
    admin: [...new Set([...admin.uiLanguages, ...admin.contentLanguages])],
    info: [...manifest.apps.info.uiLanguages],
  };
}

/**
 * Stages both applications into `<outDir>/<app>/`. `packs` maps every language the manifest
 * names, English included, to its pack folder; `assets` maps each application to its branding and
 * legal files (relative output path → source file); `extra` to further files; `textOverrides`
 * each application to its overridden texts per language (lib/text-overrides.mjs
 * textOverridesFor). Everything is read before anything is written. Returns per application the
 * files, what was written and removed, and per language how many keys were taken from English
 * (`filled`) and how many from the text overrides (`overridden`).
 */
export function stageCountry({
  manifest,
  packs,
  outDir,
  assets = {},
  extra = {},
  textOverrides = {},
}) {
  const flags = flagLanguages(manifest);
  const planned = Object.entries(manifest.apps).map(([app, use]) => [
    app,
    plannedFiles({
      app,
      uiLanguages: use.uiLanguages,
      flagLanguages: flags[app],
      packs,
      assets: assets[app] ?? {},
      extra: extra[app] ?? {},
      textOverrides,
    }),
  ]);
  const result = {};
  for (const [app, { files, filled, overridden }] of planned)
    result[app] = {
      files: [...files.keys()].sort(),
      filled,
      overridden,
      ...writeFolder(path.join(outDir, app), files),
    };
  return result;
}
