#!/usr/bin/env node
/**
 * The staging step: writes the language files a build ships from the language packs.
 *
 *   node ../country-kit/stage-languages.mjs                # both applications, run from fe/
 *   node ../country-kit/stage-languages.mjs --app info     # one application (repeat for more)
 *   node ../country-kit/stage-languages.mjs --watch        # stage again on every change under languages/
 *
 * A language pack is a folder `languages/<lang>/`: `pack.json` (exactly `lang`, `label`, `locale`
 * and `flag`), a bundle per application whose interface it translates, `i18n/<app>/<lang>.json`,
 * the flag `img/flags/<lang>.png`, the flag's `ATTRIBUTION.txt` and, but for English, `status/`
 * with the review lists. Most of that must never reach a reader, so no build reads a pack: each
 * application's asset input is `.niord/build/<app>/`, which this step writes and nothing else
 * does, and an `angular.json` configuration's ignore list picks its languages from it.
 *
 * Per application, into `.niord/build/<app>/`:
 *
 *   i18n/en.json            a byte copy of `languages/en/i18n/<app>/en.json`, which must exist;
 *   i18n/<lang>.json        for every other pack that has `i18n/<app>/<lang>.json`, stripped to
 *                           the catalog's keys, which are English's: a byte copy when it has every
 *                           key English has, a usable text for each and no other key; otherwise
 *                           English's keys in English's order, the pack's text where it has a
 *                           usable one and English's where it has not, so a reader sees English
 *                           rather than a key name or a broken text. A key English lacks is
 *                           dropped (stripped), and a text whose `{{ }}` placeholders are not
 *                           English's, or that is empty where English is not, is staged as
 *                           English's (replaced): no staged bundle carries a broken value, even
 *                           in a build that does not run the pack check (country-kit/pack.mjs);
 *   img/flags/<lang>.png    every pack's flag, in both applications: a content language that has
 *                           no interface text of its own still shows its flag.
 *
 * Never `pack.json`, `ATTRIBUTION.txt`, `renames.json`, the flag's drawing or anything under
 * `status/`. A pack is refused, and nothing staged, when `pack.json` has another field or
 * another language than its folder, a locale that is not `ll` or `ll-CC`, or a label that is
 * not plain text of at most 40 characters, when its flag is not `img/flags/<lang>.png`, not a PNG
 * or over 64 KB, when a path it reads is a link or leads out of the pack, and when a bundle has a
 * key `__proto__`, `prototype` or `constructor` or a value that is neither text nor an object
 * of texts.
 *
 * The output depends on the packs alone (folders are read in sorted order), a file is written only
 * when its bytes differ, through a temporary file and a rename, and a staged file no pack produces
 * any more is deleted, so a second run writes nothing. A running dev server serves a pack edit
 * once the step has run again, with or without --watch.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { where } from './lib/where.mjs';

const FE = where().base;

export const APPS = ['admin', 'info'];
const ENGLISH = 'en';
const LANG = /^[a-z]{2,3}$/;
const LOCALE = /^[a-z]{2,3}(-[A-Z]{2})?$/;
const MAX_LABEL = 40;
// A label is shown as text beside its flag: no markup, no entity, no control character.
// eslint-disable-next-line no-control-regex -- the control characters are what it refuses
const NOT_PLAIN = /[<>&\u0000-\u001f\u007f-\u009f]/;
const PACK_FIELDS = ['flag', 'label', 'lang', 'locale'];
const FORBIDDEN_KEYS = new Set(['__proto__', 'prototype', 'constructor']);
const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const MAX_FLAG_BYTES = 64 * 1024;

const toPosix = (p) => p.replaceAll('\\', '/');

/** The first 16 hex digits of the SHA-256 of a text's UTF-8 bytes. */
export function fingerprint(text) {
  return crypto.createHash('sha256').update(text, 'utf8').digest('hex').slice(0, 16);
}

/**
 * The names of a text's `{{ name }}` placeholders, each once and sorted: their order and
 * repetition are a translation's own business, their names are not.
 */
export function placeholders(text) {
  return [
    ...new Set([...text.matchAll(/\{\{\s*([\w.]+)\s*\}\}/g)].map((match) => match[1])),
  ].sort();
}

/** Whether a text has nothing a reader could see. */
export const isBlank = (text) => text.trim() === '';

/**
 * How a line names the pack folder `dir`: `packs/<lang>` for a country's home pack,
 * `.niord/packs/<lang>` for a pinned pack set-up copied, and `languages/<lang>` for a pack of the
 * base product (`en` in a country workspace included), so a line says which folder to edit.
 */
export function packLabel(dir) {
  const parent = path.basename(path.dirname(dir));
  const lang = path.basename(dir);
  if (parent !== 'packs') return `languages/${lang}`;
  return path.basename(path.dirname(path.dirname(dir))) === '.niord'
    ? `.niord/packs/${lang}`
    : `packs/${lang}`;
}

/**
 * The file `relative` inside the pack folder `dir`, or undefined when it does not exist. Throws
 * when the path leads out of the pack, when any part of it is a link, or when it names something
 * other than a regular file.
 */
export function packFile(dir, relative) {
  const base = path.resolve(dir);
  const full = path.resolve(base, relative);
  const inside = path.relative(base, full);
  if (!inside || inside.startsWith('..') || path.isAbsolute(inside))
    throw new Error(`${relative} leads out of the pack`);
  let current = base;
  for (const segment of inside.split(path.sep)) {
    current = path.join(current, segment);
    let stat;
    try {
      stat = fs.lstatSync(current);
    } catch (error) {
      if (error.code === 'ENOENT') return undefined;
      throw error;
    }
    if (stat.isSymbolicLink()) throw new Error(`${toPosix(relative)} goes through a link`);
    if (current === full && !stat.isFile())
      throw new Error(`${toPosix(relative)} is not a regular file`);
  }
  return full;
}

/**
 * Holds a parsed `pack.json` of the language `lang` to its schema: an object of exactly `flag`,
 * `label`, `lang` and `locale`, each a non-empty text, `lang` the language, `locale` ll or ll-CC,
 * `label` plain text of at most 40 characters and `flag` `img/flags/<lang>.png`. `fail(message)`
 * throws, naming the pack: readPack's folder or a pulled archive's `niord-lang-<lang>`.
 */
export function checkManifest(manifest, lang, fail) {
  if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest))
    fail('pack.json is not an object');
  const fields = Object.keys(manifest).sort();
  if (fields.join() !== PACK_FIELDS.join())
    fail(
      `pack.json has ${fields.join(', ') || 'no fields'}; it has exactly ${PACK_FIELDS.join(', ')}`,
    );
  for (const field of PACK_FIELDS)
    if (typeof manifest[field] !== 'string' || !manifest[field].trim())
      fail(`pack.json's ${field} is not a non-empty text`);
  if (manifest.lang !== lang)
    fail(`pack.json names the language "${manifest.lang}", not "${lang}"`);
  if (!LOCALE.test(manifest.locale))
    fail(
      `pack.json's locale "${manifest.locale}" is not a language code with an optional region, ll or ll-CC`,
    );
  if ([...manifest.label].length > MAX_LABEL)
    fail(
      `pack.json's label has ${[...manifest.label].length} characters, over the ${MAX_LABEL} a label may have`,
    );
  if (NOT_PLAIN.test(manifest.label))
    fail("pack.json's label is not plain text: no '<', '>', '&' or control character");
  const flag = `img/flags/${lang}.png`;
  if (manifest.flag !== flag) fail(`pack.json's flag is "${manifest.flag}", not "${flag}"`);
}

/** Reads and checks one pack folder: its `pack.json` (checkManifest) and its flag. */
export function readPack(dir) {
  const lang = path.basename(dir);
  const fail = (message) => {
    throw new Error(`${packLabel(dir)}: ${message}`);
  };
  if (!LANG.test(lang)) fail('a pack folder is named by its language code, two or three a-z');
  const stat = fs.lstatSync(dir);
  if (stat.isSymbolicLink()) fail('is a link');
  if (!stat.isDirectory()) fail('is not a folder');

  let manifest;
  try {
    const file = packFile(dir, 'pack.json');
    if (!file) fail('has no pack.json');
    manifest = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    if (error.message.startsWith(`${packLabel(dir)}:`)) throw error;
    fail(`pack.json: ${error.message}`);
  }
  checkManifest(manifest, lang, fail);
  const flag = `img/flags/${lang}.png`;

  let flagFile;
  try {
    flagFile = packFile(dir, flag);
  } catch (error) {
    fail(error.message);
  }
  if (!flagFile) fail(`has no ${flag}`);
  const size = fs.statSync(flagFile).size;
  if (size > MAX_FLAG_BYTES)
    fail(`${flag} is ${size} bytes, over the ${MAX_FLAG_BYTES} a flag may be`);
  const head = Buffer.alloc(PNG_SIGNATURE.length);
  const fd = fs.openSync(flagFile, 'r');
  try {
    fs.readSync(fd, head, 0, head.length, 0);
  } finally {
    fs.closeSync(fd);
  }
  if (!head.equals(PNG_SIGNATURE)) fail(`${flag} is not a PNG`);

  return { ...manifest, dir, flagFile };
}

function checkKey(key, trail) {
  if (FORBIDDEN_KEYS.has(key)) throw new Error(`has the key "${[...trail, key].join('.')}"`);
}

const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);

/**
 * A bundle as flat dotted keys and their texts, in the bundle's order. Throws on a forbidden key,
 * on a value that is neither a text nor an object, and on a flat name given twice in two shapes
 * (`"menu.close"` beside `menu: { close }`), which would otherwise leave one of the two texts
 * silently unused.
 */
export function flatten(bundle, trail = [], flat = {}) {
  if (!isObject(bundle))
    throw new Error(`${trail.length ? `"${trail.join('.')}"` : 'the bundle'} is not an object`);
  for (const [key, value] of Object.entries(bundle)) {
    checkKey(key, trail);
    const name = [...trail, key].join('.');
    if (typeof value === 'string') {
      if (Object.hasOwn(flat, name)) throw new Error(`"${name}" is given twice`);
      flat[name] = value;
    } else if (isObject(value)) flatten(value, [...trail, key], flat);
    else throw new Error(`"${name}" is neither a text nor an object`);
  }
  return flat;
}

/**
 * Every text of a bundle: flat dotted name → the trail of names it is written under. Throws, as
 * flatten does, on a flat name given twice.
 */
function trailsOf(bundle, owner, trail = [], out = new Map()) {
  for (const [key, value] of Object.entries(bundle)) {
    checkKey(key, trail);
    const here = [...trail, key];
    const name = here.join('.');
    if (typeof value === 'string') {
      if (out.has(name)) throw new Error(`${owner}"${name}" is given twice`);
      out.set(name, here);
    } else if (isObject(value)) trailsOf(value, owner, here, out);
    else throw new Error(`${owner}"${name}" is neither a text nor an object`);
  }
  return out;
}

/**
 * A pack's bundle stripped to English's keys and completed from English: English's keys in
 * English's order and shape, the pack's text where it has a usable one and English's where it has
 * not. A text is usable when its placeholders are English's and it is not empty where English is
 * not. Keys are matched by their flat dotted name, as the pack check matches them: a key the pack
 * writes flat (`"admin.areas.delete"`) where English nests it, or the other way round, is the
 * same key, and its text is staged in English's shape. Returns the completed bundle and, as dotted
 * keys, the keys taken from English because the pack lacks them (`missing`), the keys the pack has
 * and English has not (`stripped`), the keys whose text was replaced by English's (`replaced`)
 * and the keys the pack writes in another shape than English (`reshaped`).
 */
export function fillFromEnglish(en, bundle) {
  if (!isObject(en)) throw new Error('the English bundle is not an object');
  if (!isObject(bundle)) throw new Error('"the bundle" is not an object');
  const english = trailsOf(en, 'the English ');
  const own = trailsOf(bundle, '');
  const texts = flatten(bundle);
  const missing = [];
  const replaced = [];
  function fill(node, trail) {
    const out = {};
    for (const [key, value] of Object.entries(node)) {
      const here = [...trail, key];
      const name = here.join('.');
      if (isObject(value)) {
        out[key] = fill(value, here);
        continue;
      }
      if (!Object.hasOwn(texts, name)) {
        missing.push(name);
        out[key] = value;
      } else if (
        placeholders(texts[name]).join() !== placeholders(value).join() ||
        (isBlank(texts[name]) && !isBlank(value))
      ) {
        replaced.push(name);
        out[key] = value;
      } else out[key] = texts[name];
    }
    return out;
  }
  const stripped = [...own.keys()].filter((name) => !english.has(name));
  const reshaped = [...own]
    .filter(
      ([name, trail]) =>
        english.has(name) && JSON.stringify(english.get(name)) !== JSON.stringify(trail),
    )
    .map(([name]) => name);
  return { bundle: fill(en, []), missing, stripped, replaced, reshaped };
}

/** The packs under `packsDir`, read and checked, in the order of their folder names. */
export function readPacks(packsDir) {
  const packs = [];
  for (const entry of fs
    .readdirSync(packsDir, { withFileTypes: true })
    .sort((a, b) => a.name.localeCompare(b.name, 'en'))) {
    if (entry.isSymbolicLink()) throw new Error(`languages/${entry.name} is a link`);
    if (entry.isDirectory()) packs.push(readPack(path.join(packsDir, entry.name)));
  }
  if (!packs.some((pack) => pack.lang === ENGLISH))
    throw new Error(`no English pack, languages/${ENGLISH}/: every bundle is completed from it`);
  return packs;
}

function parseBundle(file, label) {
  let parsed;
  try {
    parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (error) {
    throw new Error(`${label}: ${error.message}`, { cause: error });
  }
  return parsed;
}

/** The files one application's staging folder holds, as relative path → bytes. */
function stagedFiles(packs, app) {
  const files = new Map();
  const english = packs.find((pack) => pack.lang === ENGLISH);
  const englishLabel = `languages/${ENGLISH}/i18n/${app}/${ENGLISH}.json`;
  const englishFile = packFile(english.dir, `i18n/${app}/${ENGLISH}.json`);
  if (!englishFile) throw new Error(`${englishLabel} does not exist: every ${app} build ships it`);
  const englishBytes = fs.readFileSync(englishFile);
  const englishBundle = parseBundle(englishFile, englishLabel);
  try {
    flatten(englishBundle);
  } catch (error) {
    throw new Error(`${englishLabel} ${error.message}`, { cause: error });
  }
  files.set(`i18n/${ENGLISH}.json`, { bytes: englishBytes });

  for (const pack of packs) {
    files.set(`img/flags/${pack.lang}.png`, { bytes: fs.readFileSync(pack.flagFile) });
    if (pack.lang === ENGLISH) continue;
    const label = `${packLabel(pack.dir)}/i18n/${app}/${pack.lang}.json`;
    let file;
    try {
      file = packFile(pack.dir, `i18n/${app}/${pack.lang}.json`);
    } catch (error) {
      throw new Error(`${packLabel(pack.dir)}: ${error.message}`, { cause: error });
    }
    if (!file) continue;
    const bytes = fs.readFileSync(file);
    let filled;
    try {
      filled = fillFromEnglish(englishBundle, parseBundle(file, label));
    } catch (error) {
      throw new Error(`${label} ${error.message}`, { cause: error });
    }
    const { missing, stripped, replaced, reshaped } = filled;
    files.set(`i18n/${pack.lang}.json`, {
      bytes:
        missing.length || stripped.length || replaced.length || reshaped.length
          ? Buffer.from(`${JSON.stringify(filled.bundle, null, 2)}\n`, 'utf8')
          : bytes,
      filled: missing.length,
      stripped,
      replaced,
    });
  }
  return files;
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

/** How long another run's temporary file is left alone before it counts as a crashed run's. */
export const IN_FLIGHT_MS = 60_000;

/**
 * Whether a file is another run's temporary copy, written and not yet renamed: a second run
 * staging the same application at the same time (a watch in one terminal and a build in another)
 * must not delete it under that run. A temporary file older than `IN_FLIGHT_MS` is a crashed
 * run's and is swept like any other stale file.
 */
function inFlight(file) {
  if (!/\.\d+\.tmp$/.test(file)) return false;
  try {
    return Date.now() - fs.statSync(file).mtimeMs < IN_FLIGHT_MS;
  } catch (error) {
    if (error.code === 'ENOENT') return true;
    throw error;
  }
}

function removeEmptyFolders(dir, root) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true }))
    if (entry.isDirectory()) removeEmptyFolders(path.join(dir, entry.name), root);
  if (dir !== root && fs.readdirSync(dir).length === 0) fs.rmdirSync(dir);
}

/**
 * Stages the packs under `packsDir` for each application of `apps` into `<outDir>/<app>/`.
 * Every pack is read and checked before anything is written. Returns, per application, the
 * staged files, the ones written and removed by this run, per language how many keys were
 * filled from English, and per language the keys stripped and the keys replaced by English's.
 */
export function stageLanguages({ packsDir, outDir, apps = APPS }) {
  for (const app of apps)
    if (!APPS.includes(app)) throw new Error(`unknown application "${app}" (${APPS.join(', ')})`);
  const packs = readPacks(packsDir);
  const planned = apps.map((app) => [app, stagedFiles(packs, app)]);
  const result = {};
  for (const [app, files] of planned) {
    const target = path.join(outDir, app);
    if (fs.existsSync(target) && fs.lstatSync(target).isSymbolicLink())
      throw new Error(`${toPosix(target)} is a link`);
    const written = [];
    const removed = [];
    for (const [relative, { bytes }] of files) {
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
      if (!files.has(relative) && !inFlight(path.join(target, relative))) {
        fs.rmSync(path.join(target, relative), { force: true });
        removed.push(relative);
      }
    if (fs.existsSync(target)) removeEmptyFolders(target, target);
    const filled = {};
    const stripped = {};
    const replaced = {};
    for (const [relative, staged] of files) {
      const lang = relative.match(/^i18n\/(.+)\.json$/)?.[1];
      if (staged.filled) filled[lang] = staged.filled;
      if (staged.stripped?.length) stripped[lang] = staged.stripped;
      if (staged.replaced?.length) replaced[lang] = staged.replaced;
    }
    result[app] = { files: [...files.keys()].sort(), written, removed, filled, stripped, replaced };
  }
  return result;
}

/** One line per run: what each application's folder holds and what this run changed. */
export function summary(result) {
  return Object.entries(result)
    .map(([app, { files, written, removed, filled, stripped = {}, replaced = {} }]) => {
      const bundles = files.filter((f) => f.startsWith('i18n/')).map((f) => f.slice(5, -5));
      const counts = (label, perLanguage, count) => {
        const parts = Object.entries(perLanguage).map(([lang, n]) => `${lang} ${count(n)}`);
        return parts.length ? `${label}: ${parts.join(', ')}` : undefined;
      };
      const changes = [
        counts('filled', filled, (n) => `${n} from en`),
        counts('stripped', stripped, (keys) => keys.length),
        counts('replaced', replaced, (keys) => `${keys.length} by en`),
      ].filter(Boolean);
      return `${app}: ${bundles.join(', ')} + ${files.length - bundles.length} flag(s)${changes.length ? ` (${changes.join('; ')})` : ''}, ${written.length} written, ${removed.length} removed`;
    })
    .join('; ');
}

// Run as a script rather than imported by its test; see workspace-paths-check.mjs for why both
// sides are resolved through links.
const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

if (invokedDirectly) {
  const args = process.argv.slice(2);
  const apps = [];
  let watch = false;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--app' && args[i + 1]) apps.push(args[++i]);
    else if (args[i].startsWith('--app=')) apps.push(args[i].slice('--app='.length));
    else if (args[i] === '--watch') watch = true;
    else {
      console.error(
        `stage-languages: unknown argument "${args[i]}" (--app ${APPS.join('|')}, --watch)`,
      );
      process.exit(2);
    }
  }
  const options = {
    packsDir: path.join(FE, 'languages'),
    outDir: path.join(FE, '.niord', 'build'),
    apps: apps.length ? [...new Set(apps)] : APPS,
  };
  const run = () => {
    try {
      console.log(`stage-languages: ${summary(stageLanguages(options))}`);
      return true;
    } catch (error) {
      console.error(`stage-languages: ${error.message}`);
      return false;
    }
  };
  const ok = run();
  if (!watch) process.exit(ok ? 0 : 1);
  let timer;
  fs.watch(options.packsDir, { recursive: true }, () => {
    clearTimeout(timer);
    timer = setTimeout(run, 200);
  });
  console.log('stage-languages: watching languages/ (Ctrl+C to stop)');
}
