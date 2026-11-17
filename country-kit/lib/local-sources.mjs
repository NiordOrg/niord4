/**
 * The local overrides of set-up, for a developer working on the base product and a country at
 * once: `NIORD_BASE=<a checkout's fe/>` instead of the pinned zip, `NIORD_PACK_<LANG>=<a pack
 * folder>` instead of a pinned pack. Both are refused when `CI` is set, both are recorded in the
 * build report, and a production build refuses a workspace staged from either (guard-build.mjs).
 *
 * Each is copied into the workspace — the builder reads asset inputs only inside the workspace
 * root — and a copy holds regular files only: a link anywhere in what is copied fails.
 *
 *   NIORD_BASE           exactly the files the source zip would hold of the checkout's fe/ and
 *                        the kit beside it, named as the zip names them (zip-include.json,
 *                        through lib/include.mjs; the files git sees, or every file when the
 *                        checkout has no git), into .niord/base/, and the catalog built from the
 *                        checkout's fe/ and be/ as catalog.json, as the zip carries it; and the
 *                        kit beside the checkout's fe/ into the root's .niord/kit/, exactly as
 *                        the kit package would unpack (kit-include.json, with the backend
 *                        contract, the marker and the backend files under backend/ it adds:
 *                        mvnw and the text files with LF, mvnw.cmd with CRLF, the logo byte
 *                        for byte)
 *   NIORD_PACK_<LANG>    the pack folder's canonical archive entries (lib/pack-archive.mjs), into
 *                        .niord/packs/<lang>/, so a local copy has exactly a published pack's
 *                        layout: no status/unaccepted.json, and a stray file fails
 */
import fs from 'node:fs';
import path from 'node:path';
import { buildCatalog, serializeCatalog } from '../catalog.mjs';
import { CATALOG_ENTRY, gitFiles, KIT_FOLDER } from './tree.mjs';
import { compileList, readIncludeList } from './include.mjs';
import { packEntries, stagePack } from './pack-archive.mjs';
import { backendEntries, KIT_BACKEND, MARKER, markerText } from './where.mjs';

const toPosix = (p) => p.replaceAll('\\', '/');
// Folders never walked when a checkout has no git: what they hold is never in the zip.
const SKIPPED = new Set(['node_modules', '.niord', 'dist', '.angular', 'coverage', '.git']);

/** Every regular file under `dir`, relative and with `/`, sorted; a link fails. */
export function walkFiles(dir, { skip = new Set() } = {}) {
  const out = [];
  const visit = (current) => {
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const full = path.join(current, entry.name);
      const relative = toPosix(path.relative(dir, full));
      if (entry.isSymbolicLink())
        throw new Error(`${relative} is a link; a local source holds files only`);
      if (entry.isDirectory()) {
        if (!skip.has(entry.name)) visit(full);
      } else if (entry.isFile()) out.push(relative);
      else throw new Error(`${relative} is not a regular file`);
    }
  };
  visit(dir);
  return out.sort();
}

/**
 * Replaces `target` with a copy of `files`, each `{ name, file }` copied to `<target>/<name>`, and
 * the entries of `added`, each `{ name, data }` written to `<target>/<name>`. All of it is written
 * into a temporary folder that is renamed into place last, so a failure leaves `target` as it was.
 */
function copyInto(files, target, added = []) {
  const temporary = `${target}.${process.pid}.tmp`;
  fs.rmSync(temporary, { recursive: true, force: true });
  fs.mkdirSync(temporary, { recursive: true });
  for (const { name, file } of files) {
    const stat = fs.lstatSync(file);
    if (stat.isSymbolicLink())
      throw new Error(`${name} is a link; a local source holds files only`);
    if (!stat.isFile()) throw new Error(`${name} is not a regular file`);
    const to = path.join(temporary, name);
    fs.mkdirSync(path.dirname(to), { recursive: true });
    fs.copyFileSync(file, to);
  }
  for (const { name, data } of added) {
    const to = path.join(temporary, ...name.split('/'));
    fs.mkdirSync(path.dirname(to), { recursive: true });
    fs.writeFileSync(to, data);
  }
  fs.rmSync(target, { recursive: true, force: true });
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.renameSync(temporary, target);
}

/** The files of one root, relative and with `/`: git's list, or a walk where it has no git. */
function rootFiles(root) {
  try {
    return gitFiles(root);
  } catch {
    return walkFiles(root, { skip: SKIPPED });
  }
}

/**
 * The files the source zip would hold of a checkout whose fe/ is `source`, `{ name, file }` named
 * as the zip names them: fe/'s, and, when the kit is beside fe/ (`<source>/../country-kit`), the
 * kit's under country-kit/. git's list per root when it has git, so gitignored output never
 * enters; otherwise a walk that skips build output. A checkout from before the kit moved holds it
 * in fe/, and fe/'s list names it. A name both roots give fails.
 */
export function checkoutFiles(source, list = readIncludeList()) {
  const files = rootFiles(source).map((name) => ({ name, file: path.join(source, name) }));
  const kit = path.join(path.dirname(path.resolve(source)), 'country-kit');
  if (fs.existsSync(kit)) {
    const named = new Set(files.map(({ name }) => name));
    for (const relative of rootFiles(kit)) {
      const name = `${KIT_FOLDER}${relative}`;
      if (named.has(name))
        throw new Error(`${name} is given twice, by ${source} and by the kit at ${kit}`);
      files.push({ name, file: path.join(kit, relative) });
    }
  }
  const selects = compileList(list);
  return files.filter(({ name }) => selects(name)).sort((a, b) => (a.name < b.name ? -1 : 1));
}

/**
 * Copies a base product checkout's fe/ and the kit beside it into `target` as the zip would have
 * unpacked them, catalog included. Returns the number of files copied.
 */
export function copyBase({ source, target, version }) {
  const files = checkoutFiles(source);
  if (files.some(({ name }) => name === CATALOG_ENTRY))
    throw new Error(
      `${CATALOG_ENTRY} is selected from the checkout, but the catalog takes that name`,
    );
  const catalog = buildCatalog({ fe: source, be: path.resolve(source, '..', 'be'), version });
  copyInto(files, target, [{ name: CATALOG_ENTRY, data: serializeCatalog(catalog) }]);
  return files.length;
}

/** The backend contract's name in the kit package, added by its build beside the marker. */
const KIT_CONTRACT = 'docs/country-api.md';
/** The names the copy adds, as the kit package's build adds them. */
const ADDED = [KIT_CONTRACT, MARKER, ...Object.values(KIT_BACKEND).map(({ entry }) => entry)];

/**
 * Copies the kit folder `kit` into `target` (<root>/.niord/kit) as its package would unpack: the
 * files of the kit that kit-include.json beside it selects (git's list, or a walk where it has no
 * git), the backend contract from `<be>/docs/country-api.md` with its CRLFs read as LF as
 * docs/country-api.md, the marker of `version` as kit.json, and the backend files of `be` under
 * backend/ (lib/where.mjs KIT_BACKEND: the Maven wrapper, the base settings file and its catalog,
 * the PDF logo; mvnw and the text files with LF, mvnw.cmd with CRLF, the logo byte for byte). The
 * same rule as the package's build (source-zip.mjs kitEntries), which the kit package leaves out,
 * so it is not imported. Everything added is read before the copy starts and written with the kit's
 * files into the folder renamed into place last, so a missing file or a failed write leaves `target`
 * as it was. Returns the number of the kit's files copied, the ones the copy adds left out.
 */
export function copyKit({ kit, target, version, be }) {
  const selects = compileList(readIncludeList(path.join(kit, 'kit-include.json')));
  const files = rootFiles(kit)
    .filter(selects)
    .map((name) => {
      if (ADDED.includes(name)) throw new Error(`${name} is selected, but the copy adds that name`);
      return { name, file: path.join(kit, name) };
    });
  const contract = path.join(be, 'docs', 'country-api.md');
  if (!fs.existsSync(contract))
    throw new Error(`${contract} is missing: the kit carries it as ${KIT_CONTRACT}`);
  const text = fs.readFileSync(contract, 'utf8').replaceAll('\r\n', '\n');
  copyInto(files, target, [
    { name: KIT_CONTRACT, data: text },
    { name: MARKER, data: markerText(version) },
    ...backendEntries(be),
  ]);
  return files.length;
}

/** The environment variable that names a local copy of a pack: NIORD_PACK_DA for `da`. */
export const packVariable = (lang) => `NIORD_PACK_${lang.toUpperCase()}`;

/**
 * Stages a local pack folder into `target` (`.niord/packs/<lang>/`) as its canonical archive
 * would unpack: exactly the layout's files, text with LF line endings, links and stray files
 * refused. Returns the number of files staged.
 */
export function copyPack({ source, target }) {
  if (!fs.existsSync(source) || !fs.statSync(source).isDirectory())
    throw new Error(`${source} is not a pack folder`);
  if (fs.lstatSync(source).isSymbolicLink()) throw new Error(`${source} is a link`);
  const entries = packEntries(source, path.basename(target));
  stagePack({ entries, target });
  return entries.length;
}
