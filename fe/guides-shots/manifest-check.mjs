#!/usr/bin/env node
/**
 * Compares the pictures the screenshot specs take with the manifests, the guides' checklist.
 *
 *   node guides-shots/manifest-check.mjs [--manifests <folder or file>]... [--report]   # from fe/
 *
 * A spec's test is titled `<manual>/<file>`: the picture it writes under the manual's images/
 * folder. A manifest is a JSON file, one per manual; each of its entries whose disposition is
 * retake, replace or new is a picture a spec must take. The check lists, sorted by manual, the
 * entries no test takes (missing), the tests no such entry asks for (extra), the tests marked
 * fixme or skip (pending), a picture two tests take, and any picture of SECOM or S-124, which the
 * guides leave out. It exits 1 when one of those lists but pending has an entry, or a manifest
 * cannot be read, unless --report is given. A picture a manifest lists at two places is one
 * picture, and is only named.
 *
 * The manifests are read leniently, since they are written by hand: the entries are the file's
 * top-level array or its first array under entries, images, screenshots, shots or items; an
 * entry's disposition is its disposition, action or decision; its file is its newFile, newImage,
 * newName or replacement (a replaced picture under a new name), else its file, image, name,
 * filename or path, directory left off; its manual is its own `manual`, else the file's, else the
 * manifest's file name without .json. pilot.shots.ts is left out.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_MANIFESTS =
  process.env.GUIDES_MANIFEST_DIR || path.resolve(HERE, '..', '..', 'guides', 'manifest');

const TAKEN = new Set(['retake', 'replace', 'new']);
const LIST_KEYS = ['entries', 'images', 'screenshots', 'shots', 'items'];
const DISPOSITION_KEYS = ['disposition', 'action', 'decision'];
const NEW_NAME_KEYS = ['newFile', 'newImage', 'newName', 'replacement'];
const NAME_KEYS = ['file', 'image', 'name', 'filename', 'path'];
const RULED_OUT = /secom|s-?124/i;

// test('…'), test.fixme('…') and the like, with the title in quotes of any kind.
const TEST_CALL = /\btest(?:\.(skip|fixme|fail|only|slow))?\(\s*(['"`])((?:(?!\2).)+)\2/g;
const PICTURE = /^([a-z0-9][a-z0-9._-]*)\/([^/\\]+\.(?:png|jpe?g))$/i;

/** The pictures the specs in `folder` take: `{ manual, file, pending, spec }`. */
export function specPictures(folder = HERE) {
  const pictures = [];
  for (const name of fs.readdirSync(folder).sort()) {
    if (!name.endsWith('.shots.ts') || name === 'pilot.shots.ts') continue;
    const text = fs.readFileSync(path.join(folder, name), 'utf8');
    for (const match of text.matchAll(TEST_CALL)) {
      const picture = PICTURE.exec(match[3]);
      if (!picture) continue;
      const pending = match[1] === 'skip' || match[1] === 'fixme';
      pictures.push({ manual: picture[1], file: picture[2], pending, spec: name });
    }
  }
  return pictures;
}

function entriesOf(json) {
  if (Array.isArray(json)) return json;
  if (json && typeof json === 'object') {
    for (const key of LIST_KEYS) if (Array.isArray(json[key])) return json[key];
  }
  return null;
}

function firstString(entry, keys) {
  for (const key of keys) {
    const value = entry[key];
    if (typeof value === 'string' && value.trim()) return value.trim();
  }
  return undefined;
}

/** The manifest files `sources` name: each JSON file of a folder, or a file itself. */
export function manifestFiles(sources) {
  return sources.flatMap((source) => {
    if (!fs.existsSync(source)) throw new Error(`no manifest at ${source}`);
    if (!fs.statSync(source).isDirectory()) return [source];
    return fs
      .readdirSync(source)
      .filter((name) => name.endsWith('.json'))
      .sort()
      .map((name) => path.join(source, name));
  });
}

/**
 * The pictures the manifests ask the specs for, the problems found reading them, and how many
 * entries each disposition has (all of them, taken or not).
 */
export function manifestPictures(files) {
  const pictures = [];
  const problems = [];
  const dispositions = new Map();
  for (const file of files) {
    let json;
    try {
      json = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch (error) {
      problems.push(`${file}: not JSON (${error.message})`);
      continue;
    }
    const entries = entriesOf(json);
    if (!entries) {
      problems.push(`${file}: no list of entries (a top-level array, or ${LIST_KEYS.join(', ')})`);
      continue;
    }
    const fileManual =
      !Array.isArray(json) && typeof json.manual === 'string' && json.manual.trim()
        ? json.manual.trim()
        : path.basename(file, '.json');
    entries.forEach((entry, index) => {
      if (!entry || typeof entry !== 'object') {
        problems.push(`${path.basename(file)} #${index}: not an object`);
        return;
      }
      const disposition = (firstString(entry, DISPOSITION_KEYS) ?? '').toLowerCase();
      const counted = disposition || '(none)';
      dispositions.set(counted, (dispositions.get(counted) ?? 0) + 1);
      if (!TAKEN.has(disposition)) return;
      const named = firstString(entry, NEW_NAME_KEYS) ?? firstString(entry, NAME_KEYS);
      if (!named) {
        problems.push(`${path.basename(file)} #${index}: a ${disposition} entry names no file`);
        return;
      }
      const manual =
        typeof entry.manual === 'string' && entry.manual.trim() ? entry.manual.trim() : fileManual;
      const name = path.posix.basename(named.replaceAll('\\', '/'));
      pictures.push({ manual, file: name, disposition, manifest: path.basename(file) });
    });
  }
  return { pictures, problems, dispositions };
}

/**
 * The lists the check reports, each sorted by manual and file: missing, extra, pending, twice (a
 * picture two tests take) and ruledOut; and, for information, wanted (the distinct pictures the
 * manifests ask for) and reused (a picture the manifests list at more than one place in a manual,
 * which one test takes once).
 */
export function compare(specs, manifest) {
  const key = (picture) => `${picture.manual}/${picture.file}`;
  const reused = new Set();
  const wanted = new Map();
  for (const picture of manifest) {
    if (wanted.has(key(picture))) reused.add(key(picture));
    wanted.set(key(picture), picture);
  }
  const twice = new Set();
  const taken = new Map();
  for (const picture of specs) {
    if (taken.has(key(picture))) twice.add(`${key(picture)} (${picture.spec})`);
    taken.set(key(picture), picture);
  }
  const all = new Set([...wanted.keys(), ...taken.keys()]);
  return {
    wanted: wanted.size,
    reused: [...reused].sort(),
    missing: [...wanted.keys()].filter((name) => !taken.has(name)).sort(),
    extra: [...taken.keys()].filter((name) => !wanted.has(name)).sort(),
    pending: [...taken.values()]
      .filter((picture) => picture.pending)
      .map(key)
      .sort(),
    twice: [...twice].sort(),
    ruledOut: [...all].filter((name) => RULED_OUT.test(name)).sort(),
  };
}

function parseArguments(argv) {
  const sources = [];
  let report = false;
  for (let i = 0; i < argv.length; i++) {
    const argument = argv[i];
    if (argument === '--report') report = true;
    else if (argument === '--manifests') {
      const value = argv[++i];
      if (!value) throw new Error('--manifests needs a folder or a file');
      sources.push(value);
    } else if (argument === '-h' || argument === '--help') return { help: true };
    else throw new Error(`unknown argument ${argument} (see --help)`);
  }
  return { help: false, report, sources: sources.length ? sources : [DEFAULT_MANIFESTS] };
}

function printList(title, names) {
  if (names.length === 0) return;
  console.log(`\n${title} (${names.length}):`);
  for (const name of names) console.log(`  ${name}`);
}

function main() {
  const options = parseArguments(process.argv.slice(2));
  if (options.help) {
    console.log(
      'node guides-shots/manifest-check.mjs [--manifests <folder or file>]... [--report]\n' +
        `  default manifests: GUIDES_MANIFEST_DIR or ${DEFAULT_MANIFESTS}`,
    );
    return 0;
  }
  const files = manifestFiles(options.sources);
  const { pictures, problems, dispositions } = manifestPictures(files);
  const specs = specPictures();
  const result = compare(specs, pictures);

  const counts = [...dispositions].map(([name, count]) => `${name} ${count}`).join(', ');
  console.log(`manifests: ${files.length} file(s), entries by disposition: ${counts || 'none'}`);
  console.log(
    `to take (retake, replace, new): ${result.wanted}; taken by the specs: ${specs.length}`,
  );
  printList('Manifest problems', problems);
  printList('Listed at more than one place (one picture, taken once)', result.reused);
  printList('Missing: a manifest entry no test takes', result.missing);
  printList('Extra: a test no retake, replace or new entry asks for', result.extra);
  printList('Pending: a test marked fixme or skip', result.pending);
  printList('Taken by two tests', result.twice);
  printList('Ruled out: no SECOM or S-124 picture in the guides', result.ruledOut);

  const failing =
    problems.length +
    result.missing.length +
    result.extra.length +
    result.twice.length +
    result.ruledOut.length;
  if (failing === 0) console.log('\nThe specs take exactly the pictures the manifests ask for.');
  return failing > 0 && !options.report ? 1 : 0;
}

// Run as a script, not when imported. Node reports the main module by its real path, so both
// sides are resolved through links.
const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

if (invokedDirectly) {
  try {
    process.exitCode = main();
  } catch (error) {
    console.error(`manifest-check: ${error.message}`);
    process.exitCode = 2;
  }
}
