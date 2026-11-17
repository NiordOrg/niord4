/**
 * The canonical archive of a language pack (fe-country-split-plan §4.5): what a home publishes as
 * `ghcr.io/<owner>/niord-lang-<lang>:<run>`, what a consumer pulls by digest, and what set-up
 * stages into `.niord/packs/<lang>/` for every pack but English, home, pinned or local alike.
 *
 * The layout, for the language L, and nothing else:
 *
 *   pack.json                      required; held to the strict schema (stage-languages.mjs
 *                                  checkManifest, its lang L): through readPack for a pack folder,
 *                                  as the archive is read for a pulled pack, before anything is
 *                                  staged
 *   i18n/admin/L.json              the administration app's text, when the pack has it
 *   i18n/info/L.json               the public site's text, when the pack has it
 *   backend/<bundle>.properties    the backend half, each bundle named ^[a-z][a-z0-9]*$
 *   img/flags/L.png                required; a PNG of at most 64 KB (§4.11 rule 4)
 *   img/flags/L.svg                the flag's drawing
 *   ATTRIBUTION.txt                the drawing's sources and its render command
 *   status/fingerprints.json       the English each accepted text was translated from
 *
 * `status/unaccepted.json` holds the reviewers' notes and is left out, the one file left out
 * silently; any other file in the pack folder (a stray file, a `renames.json`), a link, a folder
 * where a file is expected, a folder (empty or not) that holds no entry of the layout and anything
 * that is not a regular file fail, naming it. The text entries (.json, .properties, .txt, .svg)
 * have CRLF and CR line endings turned into LF, so a checkout with `core.autocrlf=true` gives the
 * same entries as any other host; the archive itself is lib/zip.mjs's writeZip (entries sorted,
 * dated 1980-01-01, each deflated by the zlib of the Node build that runs it), so the same files
 * give the same archive on the same Node build. A publish never rebuilds: validate builds the
 * archive once, publish pushes those bytes, and a consumer verifies them against the pin. Caps:
 * at most 32 entries, 2 MiB an entry and 8 MiB in all, inflated (lib/oci.mjs's 16 MiB bounds the
 * layer's bytes on the wire).
 *
 * Node built-ins, ./zip.mjs and ../stage-languages.mjs only: set-up stages a pinned pack with it
 * before `npm ci`.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { checkManifest, packLabel, readPack } from '../stage-languages.mjs';
import { readZip, writeZip } from './zip.mjs';

/** The names an archive of the language `<lang>` may hold; `<bundle>` is ^[a-z][a-z0-9]*$. */
export const ARCHIVE_LAYOUT = Object.freeze([
  'pack.json',
  'i18n/admin/<lang>.json',
  'i18n/info/<lang>.json',
  'backend/<bundle>.properties',
  'img/flags/<lang>.png',
  'img/flags/<lang>.svg',
  'ATTRIBUTION.txt',
  'status/fingerprints.json',
]);

export const MAX_ENTRIES = 32;
export const MAX_ENTRY_BYTES = 2 * 1024 * 1024;
export const MAX_ARCHIVE_BYTES = 8 * 1024 * 1024;
export const MAX_FLAG_BYTES = 64 * 1024;

const LANG = /^[a-z]{2,3}$/;
const BUNDLE = /^[a-z][a-z0-9]*$/;
const UNACCEPTED = 'status/unaccepted.json';
const TEXT = /\.(json|properties|txt|svg)$/;
const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

const required = (lang) => ['pack.json', `img/flags/${lang}.png`];

/** The folders of the layout: every parent folder of one of its entries. */
const LAYOUT_FOLDERS = new Set(
  ARCHIVE_LAYOUT.flatMap((entry) =>
    entry
      .split('/')
      .slice(0, -1)
      .map((_, i, parts) => parts.slice(0, i + 1).join('/')),
  ),
);

/** Whether `name` is in the archive layout of the language `lang`. */
export function inLayout(name, lang) {
  if (!LANG.test(lang)) return false;
  const bundle = /^backend\/([^/]+)\.properties$/.exec(name);
  if (bundle) return BUNDLE.test(bundle[1]);
  return ARCHIVE_LAYOUT.filter((entry) => !entry.includes('<bundle>'))
    .map((entry) => entry.replace('<lang>', lang))
    .includes(name);
}

/** A text entry with its line endings made LF; other bytes as they are. */
export function canonicalBytes(name, data) {
  if (!TEXT.test(name)) return data;
  // UTF-8 never has the byte 0x0d inside a character, so latin1 maps every byte to itself.
  const text = data.toString('latin1');
  return text.includes('\r') ? Buffer.from(text.replace(/\r\n?/g, '\n'), 'latin1') : data;
}

/** The caps every archive is held to, on the entries `{ name, data }`; `label` names the pack. */
function checkCaps(entries, lang, label) {
  if (entries.length > MAX_ENTRIES)
    throw new Error(
      `${label} has ${entries.length} files, over the ${MAX_ENTRIES} a pack may have`,
    );
  let total = 0;
  for (const { name, data } of entries) {
    if (data.length > MAX_ENTRY_BYTES)
      throw new Error(
        `${label}/${name} is ${data.length} bytes, over the ${MAX_ENTRY_BYTES} a pack's file may be`,
      );
    if (name === `img/flags/${lang}.png` && data.length > MAX_FLAG_BYTES)
      throw new Error(
        `${label}/${name} is ${data.length} bytes, over the ${MAX_FLAG_BYTES} a flag may be`,
      );
    total += data.length;
  }
  if (total > MAX_ARCHIVE_BYTES)
    throw new Error(`${label} holds ${total} bytes, over the ${MAX_ARCHIVE_BYTES} a pack may hold`);
}

/**
 * The archive's entries `{ name, data }` from the pack folder `dir` of the language `lang`, in the
 * archive's order; throws naming the first file that breaks a rule of the layout or a cap.
 */
export function packEntries(dir, lang) {
  const label = packLabel(dir);
  if (!LANG.test(lang)) throw new Error(`"${lang}" is not a language code, two or three a-z`);
  if (path.basename(dir) !== lang)
    throw new Error(`${label} is not the folder of the language ${lang}`);
  // The folder itself, pack.json's schema and the flag: readPack's rules, as the pack check's.
  readPack(dir);
  const entries = [];
  const walk = (folder, trail) => {
    const names = fs.readdirSync(folder).sort();
    for (const name of names) {
      const relative = [...trail, name].join('/');
      const full = path.join(folder, name);
      const stat = fs.lstatSync(full);
      if (stat.isSymbolicLink()) throw new Error(`${label}/${relative} is a link`);
      if (stat.isDirectory()) {
        if (inLayout(relative, lang) || relative === UNACCEPTED)
          throw new Error(`${label}/${relative} is a folder where the layout has a file`);
        if (!LAYOUT_FOLDERS.has(relative))
          throw new Error(`${label}/${relative} is a folder that is not part of a pack`);
        walk(full, [...trail, name]);
        continue;
      }
      if (!stat.isFile()) throw new Error(`${label}/${relative} is not a regular file`);
      if (relative === UNACCEPTED) continue;
      if (!inLayout(relative, lang))
        throw new Error(
          `${label}/${relative} is not part of a pack (${ARCHIVE_LAYOUT.join(', ').replaceAll('<lang>', lang)}; status/unaccepted.json stays out of the archive)`,
        );
      if (stat.size > MAX_ENTRY_BYTES)
        throw new Error(
          `${label}/${relative} is ${stat.size} bytes, over the ${MAX_ENTRY_BYTES} a pack's file may be`,
        );
      entries.push({ name: relative, data: canonicalBytes(relative, fs.readFileSync(full)) });
      if (entries.length > MAX_ENTRIES)
        throw new Error(`${label} has over the ${MAX_ENTRIES} files a pack may have`);
    }
  };
  walk(dir, []);
  for (const name of required(lang))
    if (!entries.some((entry) => entry.name === name)) throw new Error(`${label} has no ${name}`);
  checkCaps(entries, lang, label);
  return entries.sort((a, b) => Buffer.compare(Buffer.from(a.name), Buffer.from(b.name)));
}

/** The canonical archive of the pack folder `dir`: `{ zip, sha256, entries }`. */
export function buildPackArchive(dir, lang) {
  const entries = packEntries(dir, lang);
  const zip = writeZip(entries);
  return { zip, sha256: crypto.createHash('sha256').update(zip).digest('hex'), entries };
}

/**
 * The entries of a pack archive of the language `lang`, read through lib/zip.mjs's checks and the
 * layout's: throws on a name outside the layout, `status/unaccepted.json`, a missing pack.json or
 * flag, a pack.json outside its schema (checkManifest, as readPack holds a folder's), a flag that
 * is not a PNG, and on any cap, each naming `niord-lang-<lang>`.
 */
export function readPackArchive(bytes, lang) {
  const label = `niord-lang-${lang}`;
  if (!LANG.test(lang)) throw new Error(`"${lang}" is not a language code, two or three a-z`);
  let entries;
  try {
    entries = readZip(bytes, { maxEntries: MAX_ENTRIES, maxBytes: MAX_ARCHIVE_BYTES });
  } catch (error) {
    throw new Error(`${label}: the archive ${error.message}`, { cause: error });
  }
  for (const { name } of entries) {
    if (name === UNACCEPTED)
      throw new Error(`${label}: the archive has ${UNACCEPTED}, which a published pack never has`);
    if (!inLayout(name, lang))
      throw new Error(`${label}: the archive has ${name}, which is not part of a ${lang} pack`);
  }
  for (const name of required(lang))
    if (!entries.some((entry) => entry.name === name))
      throw new Error(`${label}: the archive has no ${name}`);
  checkCaps(entries, lang, label);
  let manifest;
  try {
    manifest = JSON.parse(entries.find(({ name }) => name === 'pack.json').data.toString('utf8'));
  } catch (error) {
    throw new Error(`${label}: pack.json: ${error.message}`, { cause: error });
  }
  checkManifest(manifest, lang, (message) => {
    throw new Error(`${label}: ${message}`);
  });
  const flag = entries.find(({ name }) => name === `img/flags/${lang}.png`).data;
  if (!flag.subarray(0, PNG_SIGNATURE.length).equals(PNG_SIGNATURE))
    throw new Error(`${label}: img/flags/${lang}.png is not a PNG`);
  return entries;
}

/**
 * Writes `entries` into a fresh folder beside `target` and renames it into place, so the folder
 * holds exactly the archive layout of the language it is named for. A pack staged before is
 * removed first; a remove or rename that fails (a file held open on Windows) throws, and the
 * folder may then be part-removed: set-up then fails and removes `.niord/packs/` whole, the next
 * set-up stages every pack again from `packs/<lang>/` or the verified `.niord/oci/` cache, and its
 * sweep removes a `.tmp` sibling a stopped run left.
 */
export function stagePack({ entries, target }) {
  const lang = path.basename(target);
  for (const { name } of entries)
    if (!inLayout(name, lang))
      throw new Error(`${name} is not part of a ${lang} pack, and nothing was staged`);
  const temporary = `${target}.${process.pid}.tmp`;
  fs.rmSync(temporary, { recursive: true, force: true });
  fs.mkdirSync(temporary, { recursive: true });
  try {
    for (const { name, data } of entries) {
      const file = path.join(temporary, ...name.split('/'));
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.writeFileSync(file, data, { flag: 'wx', mode: 0o644 });
    }
    fs.rmSync(target, { recursive: true, force: true });
    fs.renameSync(temporary, target);
  } finally {
    fs.rmSync(temporary, { recursive: true, force: true });
  }
}
