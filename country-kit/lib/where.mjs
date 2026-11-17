/**
 * Where the kit is, and so where it finds the base product's fe/, the workspace it serves and the
 * backend's be/. The kit never takes its parent folder to be the base product; it asks this
 * module, which knows two layouts and refuses any other:
 *
 *   source    the base product's tree: the kit is <repo>/country-kit, beside the base product and
 *             workspace <repo>/fe and the backend <repo>/be
 *   package   the kit package: the kit is <root>/.niord/kit with its marker, kit.json; the base
 *             product is <root>/fe/.niord/base, the workspace <root>/fe, the backend <root>/be
 *
 * The marker is written into the kit package when it is built (markerText) and names the package
 * and its version; a marker of another shape, or in another place, is refused. Without a marker
 * the kit's own name and its parent folder tell the layout. The answer is paths only: nothing here
 * checks that the base product is there, so a first set-up, before anything is unpacked, can ask.
 *
 * It also names the backend files the kit package carries for init (KIT_BACKEND: the Maven
 * wrapper, the base settings file and its catalog, the PDF logo) and resolves them in both
 * layouts, the package's `<kit>/backend/…` and the base product's be/, with the package's line
 * ends, so init writes the same bytes from either.
 *
 * Node built-ins only, and nothing else of the kit, so every kit file can import it.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/** The kit package's marker, a file in the kit's own folder. */
export const MARKER = 'kit.json';
/** The kit package's name, as its marker holds it. */
export const KIT_PACKAGE = 'niord-country-kit';

const VERSION = /^[0-9A-Za-z][0-9A-Za-z.+_-]*$/;
const DEFAULT_KIT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const LAYOUTS =
  "the base product's tree, <repo>/country-kit beside <repo>/fe; the kit package, <root>/.niord/kit with kit.json";

/** The marker's bytes for a kit package of `version`: one JSON line. */
export function markerText(version) {
  return `${JSON.stringify({ package: KIT_PACKAGE, version })}\n`;
}

/**
 * The backend files the kit package carries for init, by key: `entry` is the name in the package,
 * `from` the path in the base product's be/ (with `/`) the build copies it from, `eol` the line
 * ends the package holds it with ('lf', 'crlf', or null for the bytes as they are), and
 * `executable` true when init writes it with mode 0755 (the package holds every entry as 0644,
 * lib/zip.mjs).
 */
export const KIT_BACKEND = Object.freeze({
  mvnw: Object.freeze({ entry: 'backend/mvnw', from: 'mvnw', eol: 'lf', executable: true }),
  mvnwCmd: Object.freeze({
    entry: 'backend/mvnw.cmd',
    from: 'mvnw.cmd',
    eol: 'crlf',
    executable: false,
  }),
  wrapperProperties: Object.freeze({
    entry: 'backend/maven-wrapper.properties',
    from: '.mvn/wrapper/maven-wrapper.properties',
    eol: 'lf',
    executable: false,
  }),
  baseSettings: Object.freeze({
    entry: 'backend/defaults/base/settings.json',
    from: 'niord/src/main/resources/defaults/base/settings.json',
    eol: 'lf',
    executable: false,
  }),
  settingsCatalog: Object.freeze({
    entry: 'backend/defaults/base/settings-catalog.json',
    from: 'niord/src/main/resources/defaults/base/settings-catalog.json',
    eol: 'lf',
    executable: false,
  }),
  companyLogo: Object.freeze({
    entry: 'backend/img/company-logo.png',
    from: 'niord/src/main/resources/META-INF/resources/img/company-logo.png',
    eol: null,
    executable: false,
  }),
});

/**
 * `bytes` with the line ends `eol`: every run of CRs before an LF read as LF, then, for 'crlf',
 * every LF written as CRLF, so a second pass changes nothing. Read and written as latin1, so no
 * byte changes but the line ends; null gives the bytes as they are, and any other `eol` throws.
 */
export function withLineEnds(bytes, eol) {
  if (eol === null) return bytes;
  if (eol !== 'lf' && eol !== 'crlf')
    throw new Error(`"${eol}" is not a line end (lf, crlf, or null for the bytes as they are)`);
  const text = bytes.toString('latin1').replace(/\r+\n/g, '\n');
  return Buffer.from(eol === 'crlf' ? text.replaceAll('\n', '\r\n') : text, 'latin1');
}

/** A row of KIT_BACKEND; throws, naming the keys known, on any other key. */
function backendRow(key) {
  if (!Object.hasOwn(KIT_BACKEND, key))
    throw new Error(
      `"${key}" is not one of the kit package's backend files (${Object.keys(KIT_BACKEND).join(', ')})`,
    );
  return KIT_BACKEND[key];
}

/** The bytes of a backend file of the package's `entry`; a missing file, a link, or not a file throws. */
function readBackend(file, entry) {
  let stat;
  try {
    stat = fs.lstatSync(file);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    throw new Error(`${file} is missing: the kit package carries it as ${entry}`, {
      cause: error,
    });
  }
  if (stat.isSymbolicLink()) throw new Error(`${file} is a link; the zip holds files only`);
  if (!stat.isFile()) throw new Error(`${file} is not a regular file`);
  return fs.readFileSync(file);
}

/**
 * The kit package's backend entries of the base product's backend folder `be`, `{ name, data }`
 * in the order of KIT_BACKEND, each with its line ends. A missing file throws, naming it and its
 * entry.
 */
export function backendEntries(be) {
  return Object.values(KIT_BACKEND).map(({ entry, from, eol }) => ({
    name: entry,
    data: withLineEnds(readBackend(path.join(be, ...from.split('/')), entry), eol),
  }));
}

/**
 * The file of the backend file `key` (a key of KIT_BACKEND) for the kit folder `kit` (this kit
 * when none is given): `<kit>/<entry>` in the kit package, `<be>/<from>` in the base product's
 * tree.
 */
export function kitBackendFile(key, kit) {
  const { entry, from } = backendRow(key);
  const layout = where(kit);
  return layout.kind === 'package'
    ? path.join(layout.kit, ...entry.split('/'))
    : path.join(layout.be, ...from.split('/'));
}

/**
 * The bytes of the backend file `key` for the kit folder `kit`, with the package's line ends, so
 * both layouts give the same bytes. A missing file throws, naming it and its entry.
 */
export function kitBackendBytes(key, kit) {
  const { entry, eol } = backendRow(key);
  return withLineEnds(readBackend(kitBackendFile(key, kit), entry), eol);
}

/** True when `base` is the base product's own fe/: a folder named fe, not in a .niord folder. */
function isSourceBase(base) {
  return path.basename(base) === 'fe' && path.basename(path.dirname(base)) !== '.niord';
}

/** True when `base` is a country's unpacked base product, `<workspace>/.niord/base`. */
function isCountryBase(base) {
  return path.basename(base) === 'base' && path.basename(path.dirname(base)) === '.niord';
}

/**
 * True when `kit` is the base product's own kit: a folder named country-kit, not inside a .niord
 * folder, whose parent is not named fe. That is the kit's place before it moved beside fe/, refused
 * so a stale copy there is never taken for the tree.
 */
function isSourceKit(kit) {
  return (
    path.basename(kit) === 'country-kit' &&
    path.basename(path.dirname(kit)) !== 'fe' &&
    !kit.split(path.sep).includes('.niord')
  );
}

/**
 * The layout around the base product's fe/ folder `base`: `{ kind, base, workspace, root, be }`,
 * kind `source` (the base product's tree, where base is also the workspace) or `country` (a
 * country's `<workspace>/.niord/base`). Throws on any other folder, naming it and the layouts
 * known.
 */
export function baseLayout(base) {
  const resolved = path.resolve(base);
  if (isSourceBase(resolved)) {
    const root = path.dirname(resolved);
    return { kind: 'source', base: resolved, workspace: resolved, root, be: path.join(root, 'be') };
  }
  if (isCountryBase(resolved)) {
    const workspace = path.dirname(path.dirname(resolved));
    const root = path.dirname(workspace);
    return { kind: 'country', base: resolved, workspace, root, be: path.join(root, 'be') };
  }
  throw new Error(`${resolved} is not where the kit finds the base product: ${LAYOUTS}`);
}

/** The version a marker's text names; throws, naming the file, on any other text. */
function markerVersion(file, text) {
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    throw new Error(`${file} is not JSON (${error.message})`, { cause: error });
  }
  const keys =
    parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? Object.keys(parsed) : [];
  if (
    keys.length !== 2 ||
    !keys.includes('package') ||
    !keys.includes('version') ||
    parsed.package !== KIT_PACKAGE ||
    typeof parsed.version !== 'string' ||
    !VERSION.test(parsed.version)
  )
    throw new Error(
      `${file} is not the kit's marker: it holds exactly {"package":"${KIT_PACKAGE}","version":"<version>"}`,
    );
  return parsed.version;
}

function resolve(kit) {
  const marker = path.join(kit, MARKER);
  if (fs.existsSync(marker)) {
    const version = markerVersion(marker, fs.readFileSync(marker, 'utf8'));
    if (path.basename(kit) !== 'kit' || path.basename(path.dirname(kit)) !== '.niord')
      throw new Error(
        `${marker}: ${MARKER} is the kit package's marker, valid only at <root>/.niord/kit, and this kit is ${kit}`,
      );
    const root = path.dirname(path.dirname(kit));
    const workspace = path.join(root, 'fe');
    return {
      kind: 'package',
      kit,
      base: path.join(workspace, '.niord', 'base'),
      workspace,
      root,
      be: path.join(root, 'be'),
      version,
    };
  }
  if (isSourceKit(kit)) {
    const { kind, ...layout } = baseLayout(path.join(path.dirname(kit), 'fe'));
    return { kind: 'source', kit, ...layout };
  }
  throw new Error(`the kit at ${kit} is in no layout it knows: ${LAYOUTS}`);
}

let resolvedDefault;

/**
 * The layout of the kit folder `kit` (this kit when none is given, resolved once):
 * `{ kind, kit, base, workspace, root, be }`, kind `source` or `package`, and the package's
 * `version` from its marker. Throws on a malformed or misplaced marker, naming the file, and on a
 * place none of the layouts has, naming the kit and the layouts known.
 */
export function where(kit) {
  if (kit === undefined || path.resolve(kit) === DEFAULT_KIT)
    return (resolvedDefault ??= resolve(DEFAULT_KIT));
  return resolve(path.resolve(kit));
}
