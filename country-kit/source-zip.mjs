#!/usr/bin/env node
/**
 * The two zips niord-ng publishes for a country, each at the version of the release:
 *
 *   fe-src  the base product's source zip, `org.niord.web:niord-fe-src:<version>:zip`, what a
 *           country's set-up fetches, verifies by its sha256 and builds from (the default)
 *   kit     the country kit's package, `org.niord:niord-country-kit:<version>:zip`, the kit a
 *           country runs: set-up, the guides, the templates and the backend contract
 *
 *   node ../country-kit/source-zip.mjs build [--package kit] --version <v> --out <file>   # from fe/
 *   node ../country-kit/source-zip.mjs verify [--package kit] <file>
 *   node ../country-kit/source-zip.mjs guard [--package kit] <file>
 *
 *   npm run kit:zip -- --version <v> --out <file>       # build of the source zip, from fe/
 *   npm run kit:package -- --version <v> --out <file>   # build of the kit package, from fe/
 *
 * `--package <fe-src|kit>` may stand anywhere after the command; without it each command is the
 * source zip's, and any other package exits 2. The source zip's commands:
 *
 * `build` takes the files of fe/ and of the kit beside it (as country-kit/…) that git sees —
 * tracked and untracked, gitignored ones never (`git ls-files -co --exclude-standard`), so a
 * working tree's own edits count and no build output, `.niord/` or `node_modules/` can enter
 * (treeFiles) — keeps those the include list selects (zip-include.json, lib/include.mjs), adds
 * `catalog.json` built from this tree with the version given (catalog.mjs), and writes the
 * archive with lib/zip.mjs: sorted, dated 1980-01-01, mode 0644, so two builds of the same tree
 * give the same bytes. The paths are relative to fe/, so an unpacked zip has the layout of a
 * checkout's fe/. The kit is its own package, and the include list selects no country-kit/ path,
 * so the zip carries nothing of it. It prints the sha256 and writes it beside the archive as
 * `<file>.sha256`, in the form `sha256sum -c` reads. A selected path that is a link, or not a
 * regular file, fails the build; so does a tree without git. Before writing anything it runs
 * `verify`'s check and `guard`'s on the archive it made, so a country word in a file the zip
 * would carry fails the build, and nothing is written.
 *
 * `verify` reads an archive as set-up does (lib/zip.mjs readZip) and fails on any entry the
 * include list does not select, and on a missing or unreadable `catalog.json`. It knows no
 * country word, so a zip published before a word was added still verifies. It also fails on a
 * relative import that crosses between country-kit/ and the rest of the zip
 * (importClosureProblems): the base product never imports the kit, and a kit file loads the base
 * product's code only from the base it resolves (lib/where.mjs), never by a relative path. A zip
 * from before the kit's own package carried the whole kit, and the closure check still reads one.
 *
 * `guard` runs the country-word guard (lib/country-words.mjs, with country-words.json beside the
 * kit) in its zip mode over every entry of an archive, `catalog.json` included: a word no
 * allow-list entry permits fails it. Unused entries are the tree mode's, which test:guards runs
 * over fe/ and country-kit/.
 *
 * The kit package's commands, with `--package kit`:
 *
 * `build` takes the files of the kit's folder that git sees (lib/tree.mjs gitFiles), keeps those
 * kit-include.json selects (lib/include.mjs) and adds two at the top, `docs/country-api.md`, the
 * backend contract copied from be/docs/country-api.md with its CRLFs read as LF, and `kit.json`,
 * the package's marker (lib/where.mjs markerText), and the six backend files under backend/ that
 * init copies into a country's be/ (lib/where.mjs KIT_BACKEND: the Maven wrapper, the base
 * settings file and its catalog, the PDF logo), copied from be/ with the line ends the table
 * names. The names are relative to the kit's folder, with no `country-kit/` before them, since a
 * country unpacks the package into `<root>/.niord/kit` and the resolver finds the marker at the
 * kit's top. A selected file of an added name fails the build; otherwise it writes, checks and
 * prints as the source zip's does.
 *
 * `verify` fails on any entry the list does not select but the ones the build adds, on a missing
 * marker or one that is not exactly markerText of a version, on a missing contract, on a missing
 * backend file or one with the wrong line ends, and on a relative import of a shipped script, the
 * templates and the example's fe/ and be/ aside, that names no entry of the package
 * (kitClosureProblems): the kit's imports stay inside the package, and its loads of the base
 * product's code are computed from the base it resolves, which the check does not read.
 *
 * `guard` runs the country-word guard's zip mode over every entry, matched as `country-kit/<entry>`
 * as the allow-list names a kit file, and names a hit by its entry.
 *
 * The publish workflow runs all three on each archive it deploys: `build`, then `verify` and
 * `guard` on the file written. Each exits 1 on a finding, printing one line per problem, and 2
 * when it cannot run (its arguments, no git, an unreadable list).
 *
 * Installing a zip into a local Maven repository, where a country's set-up then finds it (run in
 * be/; `-N` keeps Maven to the parent project):
 *
 *   ./mvnw -B -N install:install-file -Dfile=<file> -DgroupId=org.niord.web \
 *     -DartifactId=niord-fe-src -Dversion=<v> -Dpackaging=zip
 *   ./mvnw -B -N install:install-file -Dfile=<file> -DgroupId=org.niord \
 *     -DartifactId=niord-country-kit -Dversion=<v> -Dpackaging=zip
 *
 * The publish workflow deploys both to the registry with the same coordinates through
 * `deploy:deploy-file`, as it deploys the administration bundles.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildCatalog, FORMAT, serializeCatalog } from './catalog.mjs';
import {
  countryWordFindings,
  countryWordProblems,
  readCountryWords,
} from './lib/country-words.mjs';
import { compileList, readIncludeList } from './lib/include.mjs';
import { CATALOG_ENTRY, gitFiles, KIT_FOLDER } from './lib/tree.mjs';
import {
  backendEntries,
  KIT_BACKEND,
  KIT_PACKAGE,
  MARKER,
  markerText,
  where,
  withLineEnds,
} from './lib/where.mjs';
import { readZip, writeZip } from './lib/zip.mjs';

export { CATALOG_ENTRY, gitFiles, KIT_FOLDER } from './lib/tree.mjs';

const FE = where().base;
export const VERSION = /^[0-9A-Za-z][0-9A-Za-z.+_-]*$/;

/** The sha256 of some bytes, as lower-case hex. */
export const sha256 = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');

/**
 * The files of a tree as the zip names them, `{ name, file }` sorted by name: the files git sees
 * in `fe`, named relative to it, and, when the folder `kit` is there, the kit's, named
 * `country-kit/<path>`. A name both give (a tree that holds fe/country-kit/ and the kit beside
 * fe/) fails.
 */
export function treeFiles({
  fe = FE,
  kit = path.join(path.dirname(path.resolve(fe)), 'country-kit'),
} = {}) {
  const files = gitFiles(fe).map((name) => ({ name, file: path.join(fe, name) }));
  if (fs.existsSync(kit)) {
    const named = new Set(files.map(({ name }) => name));
    for (const relative of gitFiles(kit)) {
      const name = `${KIT_FOLDER}${relative}`;
      if (named.has(name))
        throw new Error(`${name} is given twice, by ${fe} and by the kit at ${kit}`);
      files.push({ name, file: path.join(kit, relative) });
    }
  }
  return files.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
}

/**
 * The entries of the source zip of the tree whose fe/, kit and be/ folders are given: every file
 * the include list selects, and the catalog. `files` replaces git's list (a test's tree), named
 * relative to fe/.
 */
export function sourceEntries({
  fe = FE,
  kit,
  be = path.resolve(fe, '..', 'be'),
  version,
  list,
  files,
}) {
  if (typeof version !== 'string' || !VERSION.test(version))
    throw new Error(`"${version}" is not a version (${VERSION.source})`);
  const include = list ?? readIncludeList();
  const selects = compileList(include);
  const entries = [];
  const tree = files
    ? files.map((name) => ({ name, file: path.join(fe, name) }))
    : treeFiles({ fe, kit });
  for (const { name, file } of tree.filter((entry) => selects(entry.name))) {
    if (name === CATALOG_ENTRY)
      throw new Error(`${name} is selected, but the zip's catalog takes that name`);
    const stat = fs.lstatSync(file);
    if (stat.isSymbolicLink()) throw new Error(`${name} is a link; the zip holds files only`);
    if (!stat.isFile()) throw new Error(`${name} is not a regular file`);
    entries.push({ name, data: fs.readFileSync(file) });
  }
  const catalog = buildCatalog({ fe, be, version });
  entries.push({ name: CATALOG_ENTRY, data: Buffer.from(serializeCatalog(catalog), 'utf8') });
  return entries;
}

/** The source zip's bytes and sha256. */
export function buildSourceZip(options) {
  const zip = writeZip(sourceEntries(options));
  return { zip, sha256: sha256(zip) };
}

/**
 * What is wrong with an archive as a source zip: its entries read as set-up reads them, each one
 * selected by the include list, and a catalog. An empty list when nothing is.
 */
export function verifySourceZip(zip, list = readIncludeList()) {
  let entries;
  try {
    entries = readZip(zip);
  } catch (error) {
    return [error.message];
  }
  const selects = compileList(list);
  const problems = entries
    .filter(({ name }) => name !== CATALOG_ENTRY && !selects(name))
    .map(({ name }) => `${name} is outside the include list (zip-include.json)`);
  const catalog = entries.find(({ name }) => name === CATALOG_ENTRY);
  if (!catalog) problems.push(`there is no ${CATALOG_ENTRY}`);
  else
    try {
      const parsed = JSON.parse(catalog.data.toString('utf8'));
      if (parsed?.format !== FORMAT) problems.push(`${CATALOG_ENTRY} is not of format ${FORMAT}`);
      else if (typeof parsed.version !== 'string' || !VERSION.test(parsed.version))
        problems.push(`${CATALOG_ENTRY} names no version`);
    } catch (error) {
      problems.push(`${CATALOG_ENTRY}: ${error.message}`);
    }
  problems.push(...importClosureProblems(entries));
  return problems;
}

const IDENTIFIER_START = /[A-Za-z_$]/;
const IDENTIFIER_PART = /[\w$]/;
/** The words after which a `/` starts a regular expression rather than a division. */
const BEFORE_EXPRESSION = new Set([
  'await',
  'case',
  'default',
  'delete',
  'do',
  'else',
  'export',
  'extends',
  'import',
  'in',
  'instanceof',
  'new',
  'of',
  'return',
  'throw',
  'typeof',
  'void',
  'yield',
]);

/**
 * The tokens of a script's own code, `{ type, value }` with type `name`, `string`, `number`,
 * `literal` (a regular expression or a template literal, whose text is not code) or `punct`.
 * Comments are dropped, and so is all of a template literal, its `${…}` parts included: it is
 * generated text, such as a configuration file the kit writes. A lexer, not a parser: a `/` is
 * read as a regular expression after punctuation or a keyword, as a division after a value.
 */
function codeTokens(text) {
  const tokens = [];
  // One entry per open `{` of code: 'brace', or 'part' for the `${` of a template literal.
  const stack = [];
  let parts = 0;
  let inTemplate = false;
  let i = text.startsWith('#!') ? text.indexOf('\n') : 0;
  if (i < 0) return tokens;
  const emit = (type, value) => {
    if (!parts) tokens.push({ type, value });
  };
  const expressionNext = () => {
    const last = tokens.at(-1);
    if (!last) return true;
    if (last.type === 'name') return BEFORE_EXPRESSION.has(last.value);
    if (last.type === 'punct') return ![')', ']', '}'].includes(last.value);
    return false;
  };
  while (i < text.length) {
    const char = text[i];
    if (inTemplate) {
      if (char === '\\') i += 2;
      else if (char === '`') {
        inTemplate = false;
        i++;
        emit('literal', '`');
      } else if (char === '$' && text[i + 1] === '{') {
        stack.push('part');
        parts++;
        inTemplate = false;
        i += 2;
      } else i++;
      continue;
    }
    if (/\s/.test(char)) i++;
    else if (char === '/' && text[i + 1] === '/') {
      const end = text.indexOf('\n', i);
      i = end < 0 ? text.length : end;
    } else if (char === '/' && text[i + 1] === '*') {
      const end = text.indexOf('*/', i + 2);
      i = end < 0 ? text.length : end + 2;
    } else if (char === '/' && expressionNext()) {
      let j = i + 1;
      let inClass = false;
      while (j < text.length && text[j] !== '\n') {
        if (text[j] === '\\') j++;
        else if (text[j] === '[') inClass = true;
        else if (text[j] === ']') inClass = false;
        else if (text[j] === '/' && !inClass) break;
        j++;
      }
      if (text[j] === '/') {
        i = j + 1;
        while (i < text.length && IDENTIFIER_PART.test(text[i])) i++;
        emit('literal', '/');
      } else {
        i++;
        emit('punct', '/');
      }
    } else if (char === "'" || char === '"') {
      let j = i + 1;
      let value = '';
      while (j < text.length && text[j] !== char && text[j] !== '\n') {
        if (text[j] === '\\') j++;
        value += text[j] ?? '';
        j++;
      }
      i = j + 1;
      emit('string', value);
    } else if (char === '`') {
      inTemplate = true;
      i++;
    } else if (IDENTIFIER_START.test(char)) {
      let j = i + 1;
      while (j < text.length && IDENTIFIER_PART.test(text[j])) j++;
      emit('name', text.slice(i, j));
      i = j;
    } else if (/[0-9]/.test(char)) {
      let j = i + 1;
      while (j < text.length && /[\w.]/.test(text[j])) j++;
      emit('number', text.slice(i, j));
      i = j;
    } else {
      if (char === '{') stack.push('brace');
      else if (char === '}' && stack.pop() === 'part') {
        parts--;
        inTemplate = true;
        i++;
        continue;
      }
      emit('punct', char);
      i++;
    }
  }
  return tokens;
}

/**
 * The relative specifiers (`./…`, `../…`) a script imports, in order: of `import … from`, a bare
 * `import '…'`, `export … from`, and of `import('…')` and `require('…')` with one string literal.
 * A computed specifier, a comment and the text of a template literal are not read.
 */
export function relativeImports(text) {
  const tokens = codeTokens(text);
  const specifiers = [];
  const is = (k, type, value) =>
    tokens[k]?.type === type && (value === undefined || tokens[k].value === value);
  for (let k = 0; k < tokens.length; k++) {
    const { type, value } = tokens[k];
    if (type !== 'name' || is(k - 1, 'punct', '.')) continue;
    let specifier;
    if ((value === 'from' || value === 'import') && is(k + 1, 'string'))
      specifier = tokens[k + 1].value;
    else if (
      (value === 'import' || value === 'require') &&
      is(k + 1, 'punct', '(') &&
      is(k + 2, 'string') &&
      (is(k + 3, 'punct', ')') || is(k + 3, 'punct', ','))
    )
      specifier = tokens[k + 2].value;
    if (specifier?.startsWith('./') || specifier?.startsWith('../')) specifiers.push(specifier);
  }
  return specifiers;
}

/** The kit's files that run inside a country's own tree, where their relative paths point. */
const COUNTRY_TREE = /^country-kit\/(templates|example\/fe|example\/be)\//;

/**
 * The relative imports of an archive's entries that cross between the kit and the rest of it,
 * one line each naming the file and the specifier: a script outside country-kit/ (.mjs, .cjs,
 * .js, .ts) that imports into it, and a kit script (.mjs, .cjs, .js) that imports outside it, the
 * templates and the example's fe/ and be/ aside. An empty list when nothing crosses.
 */
export function importClosureProblems(entries) {
  const problems = [];
  for (const { name, data } of entries) {
    const inKit = name.startsWith(KIT_FOLDER);
    const read = inKit
      ? /\.(mjs|cjs|js)$/.test(name) && !COUNTRY_TREE.test(name)
      : /\.(mjs|cjs|js|ts)$/.test(name);
    if (!read) continue;
    const text = data.toString('utf8');
    // A specifier into the kit names its folder, so a file that does not cannot cross.
    if (!inKit && !text.includes('country-kit')) continue;
    for (const specifier of relativeImports(text)) {
      const target = path.posix.normalize(path.posix.join(path.posix.dirname(name), specifier));
      const intoKit = target === 'country-kit' || target.startsWith(KIT_FOLDER);
      if (!inKit && intoKit)
        problems.push(`${name} imports '${specifier}': the base product never imports the kit`);
      else if (inKit && !intoKit)
        problems.push(
          `${name} imports '${specifier}': the kit imports the base product only from its resolved base`,
        );
    }
  }
  return problems;
}

/**
 * What the country-word guard's zip mode finds in an archive, every entry read as set-up reads it,
 * the catalog included: one line per word no entry permits, an empty list when it passes (an
 * entry that permits nothing here may permit a file outside the zip). `list` is the guard's
 * list, read from country-words.json only when the guard runs.
 */
export function guardSourceZip(zip, list = readCountryWords()) {
  let entries;
  try {
    entries = readZip(zip);
  } catch (error) {
    return [error.message];
  }
  return countryWordProblems(countryWordFindings(entries, list), 'zip');
}

/**
 * The source zip of a tree, checked as the publish workflow checks it: `{ zip, sha256, problems }`,
 * `problems` verify's and the guard's ([] when both pass). `include` and `words` replace the
 * kit's own lists (a test's).
 */
export function checkedSourceZip({ include, words, ...options }) {
  const list = include ?? readIncludeList();
  const { zip, sha256: hash } = buildSourceZip({ ...options, list });
  const problems = [
    ...verifySourceZip(zip, list),
    ...guardSourceZip(zip, words ?? readCountryWords()),
  ];
  return { zip, sha256: hash, problems };
}

/**
 * The `build` command: the checked zip of a tree written to `out` with its `.sha256`, or, when a
 * check fails, nothing written. Returns `{ zip, sha256, problems }`.
 */
export function writeSourceZip({ out, ...options }) {
  return written(checkedSourceZip(options), out);
}

/** A checked archive written to `out` with its `.sha256`, unless a check failed. */
function written(built, out) {
  if (built.problems.length) return built;
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, built.zip);
  fs.writeFileSync(`${out}.sha256`, `${built.sha256}  ${path.basename(out)}\n`);
  return built;
}

/** The kit package's include list, beside the kit; its paths are relative to the kit's folder. */
export const KIT_INCLUDE_FILE = path.join(where().kit, 'kit-include.json');
/**
 * The entries the kit package's build adds, which the kit's folder never gives: the contract and
 * the marker at its top, and the backend files under backend/ by their keys in KIT_BACKEND
 * (lib/where.mjs), so `KIT_ADDED.mvnwCmd` is `backend/mvnw.cmd`.
 */
export const KIT_ADDED = Object.freeze({
  contract: 'docs/country-api.md',
  marker: MARKER,
  ...Object.fromEntries(Object.entries(KIT_BACKEND).map(([key, { entry }]) => [key, entry])),
});
const ADDED = Object.values(KIT_ADDED);
const MARKER_SHAPE = `${MARKER} is not the kit's marker: it holds exactly {"package":"${KIT_PACKAGE}","version":"<version>"} and a newline`;

/**
 * The entries of the kit package of the kit folder `kit` and the backend folder `be`: every file
 * of the kit git sees that the list selects (kit-include.json), named relative to the kit's folder,
 * and the ones the build adds: `docs/country-api.md` (be/docs/country-api.md with its CRLFs read
 * as LF, as niord-local-run copied it, so a Windows checkout gives CI's bytes), `kit.json` (the
 * marker of `version`, lib/where.mjs markerText), and the six backend files init copies
 * (lib/where.mjs KIT_BACKEND): backend/mvnw, backend/mvnw.cmd and backend/maven-wrapper.properties,
 * the Maven wrapper, backend/defaults/base/settings.json and settings-catalog.json, the base
 * settings file and its catalog, and backend/img/company-logo.png, the PDF logo, each copied from
 * be/ with the same line-end rule whatever the checkout: mvnw and the text files LF, mvnw.cmd
 * CRLF, the logo byte for byte. `files` replaces git's list (a test's tree).
 */
export function kitEntries({ kit = where().kit, be = where().be, version, list, files }) {
  if (typeof version !== 'string' || !VERSION.test(version))
    throw new Error(`"${version}" is not a version (${VERSION.source})`);
  const selects = compileList(list ?? readIncludeList(KIT_INCLUDE_FILE));
  const entries = [];
  for (const name of (files ?? gitFiles(kit)).filter(selects)) {
    if (ADDED.includes(name)) throw new Error(`${name} is selected, but the build adds that name`);
    const file = path.join(kit, name);
    const stat = fs.lstatSync(file);
    if (stat.isSymbolicLink()) throw new Error(`${name} is a link; the zip holds files only`);
    if (!stat.isFile()) throw new Error(`${name} is not a regular file`);
    entries.push({ name, data: fs.readFileSync(file) });
  }
  const contract = path.join(be, 'docs', 'country-api.md');
  if (!fs.existsSync(contract))
    throw new Error(`${contract} is missing: the kit package carries it as ${KIT_ADDED.contract}`);
  const text = fs.readFileSync(contract, 'utf8').replaceAll('\r\n', '\n');
  entries.push({ name: KIT_ADDED.contract, data: Buffer.from(text, 'utf8') });
  entries.push({ name: KIT_ADDED.marker, data: Buffer.from(markerText(version), 'utf8') });
  entries.push(...backendEntries(be));
  return entries;
}

/** The kit package's bytes and sha256. */
export function buildKitZip(options) {
  const zip = writeZip(kitEntries(options));
  return { zip, sha256: sha256(zip) };
}

/** True when `data` is exactly the marker of the version it names, a version VERSION accepts. */
function isMarker(data) {
  const text = data.toString('utf8');
  try {
    const { version } = JSON.parse(text);
    return typeof version === 'string' && VERSION.test(version) && text === markerText(version);
  } catch {
    return false;
  }
}

/**
 * What is wrong with an archive as the kit package: its entries read as set-up reads them, each
 * one selected by the list but the ones the build adds, the marker exactly as markerText writes
 * it, the contract, each backend file of KIT_BACKEND with its line ends (LF, or CRLF for
 * mvnw.cmd; the logo's bytes are not read), and no shipped script importing outside the package
 * (kitClosureProblems). An empty list when nothing is.
 */
export function verifyKitZip(zip, list = readIncludeList(KIT_INCLUDE_FILE)) {
  let entries;
  try {
    entries = readZip(zip);
  } catch (error) {
    return [error.message];
  }
  const selects = compileList(list);
  const problems = entries
    .filter(({ name }) => !ADDED.includes(name) && !selects(name))
    .map(({ name }) => `${name} is outside the kit package (kit-include.json)`);
  const marker = entries.find(({ name }) => name === MARKER);
  if (!marker) problems.push(`there is no ${MARKER}`);
  else if (!isMarker(marker.data)) problems.push(MARKER_SHAPE);
  if (!entries.some(({ name }) => name === KIT_ADDED.contract))
    problems.push(`there is no ${KIT_ADDED.contract}`);
  for (const { entry, eol } of Object.values(KIT_BACKEND)) {
    const found = entries.find(({ name }) => name === entry);
    if (!found) problems.push(`there is no ${entry}`);
    else if (eol !== null && !found.data.equals(withLineEnds(found.data, eol)))
      problems.push(`${entry} does not end every line with ${eol === 'crlf' ? 'CRLF' : 'LF'}`);
  }
  problems.push(...kitClosureProblems(entries));
  return problems;
}

/**
 * The relative imports of the kit package's entries that name no entry of it, one line each
 * naming the file and the specifier: of every script (.mjs, .cjs, .js) but the templates and the
 * example's fe/ and be/, which run inside a country's own tree. A specifier that climbs above the
 * package's top leaves it, since the kit loads the base product's code only by a computed import
 * from the base it resolves (lib/where.mjs), which is not read; any other names a file the list
 * leaves out, or none. An empty list when every import resolves inside the package.
 */
export function kitClosureProblems(entries) {
  const names = new Set(entries.map(({ name }) => name));
  const problems = [];
  for (const { name, data } of entries) {
    if (!/\.(mjs|cjs|js)$/.test(name) || COUNTRY_TREE.test(`${KIT_FOLDER}${name}`)) continue;
    for (const specifier of relativeImports(data.toString('utf8'))) {
      const target = path.posix.normalize(path.posix.join(path.posix.dirname(name), specifier));
      if (target === '..' || target.startsWith('../'))
        problems.push(
          `${name} imports '${specifier}': it leaves the kit package; the kit loads the base product only from its resolved base`,
        );
      else if (!names.has(target))
        problems.push(
          `${name} imports '${specifier}': no such file in the kit package (kit-include.json)`,
        );
    }
  }
  return problems;
}

/**
 * What the country-word guard's zip mode finds in the kit package: every entry matched as
 * `country-kit/<entry>`, the name the allow-list's globs give a kit file (country-words.json),
 * and each hit reported by its entry's own name. One line per word no entry permits; an empty list
 * when it passes.
 */
export function guardKitZip(zip, list = readCountryWords()) {
  let entries;
  try {
    entries = readZip(zip);
  } catch (error) {
    return [error.message];
  }
  const { hits, unused } = countryWordFindings(
    entries.map(({ name, data }) => ({ name: `${KIT_FOLDER}${name}`, data })),
    list,
  );
  return countryWordProblems(
    { hits: hits.map((hit) => ({ ...hit, name: hit.name.slice(KIT_FOLDER.length) })), unused },
    'zip',
  );
}

/**
 * The kit package of a tree, checked as the publish workflow checks it: `{ zip, sha256,
 * problems }`, `problems` verify's and the guard's ([] when both pass). `include` and `words`
 * replace the kit's own lists (a test's).
 */
export function checkedKitZip({ include, words, ...options }) {
  const list = include ?? readIncludeList(KIT_INCLUDE_FILE);
  const { zip, sha256: hash } = buildKitZip({ ...options, list });
  const problems = [...verifyKitZip(zip, list), ...guardKitZip(zip, words ?? readCountryWords())];
  return { zip, sha256: hash, problems };
}

/**
 * The `build --package kit` command: the checked kit package written to `out` with its
 * `.sha256`, or, when a check fails, nothing written. Returns `{ zip, sha256, problems }`.
 */
export function writeKitZip({ out, ...options }) {
  return written(checkedKitZip(options), out);
}

// Run as a script rather than imported by its test; see scripts/workspace-paths-check.mjs for
// why both sides are resolved through links.
const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

/** What each package's commands run, and what their messages call it. */
const PACKAGES = {
  'fe-src': {
    label: 'a source zip',
    write: writeSourceZip,
    verify: verifySourceZip,
    guard: guardSourceZip,
  },
  kit: { label: 'the kit package', write: writeKitZip, verify: verifyKitZip, guard: guardKitZip },
};

/** `args` without `--package <name>`, wherever it stands, and the package it names (fe-src). */
function packageOf(args) {
  const at = args.indexOf('--package');
  if (at < 0) return { pkg: PACKAGES['fe-src'], rest: args };
  const name = args[at + 1] ?? '';
  if (!Object.hasOwn(PACKAGES, name)) throw new Error(`--package is fe-src or kit, not "${name}"`);
  return { pkg: PACKAGES[name], rest: [...args.slice(0, at), ...args.slice(at + 2)] };
}

function main(args) {
  const [command, ...given] = args;
  const { pkg, rest } = packageOf(given);
  if (command === 'build') {
    let version;
    let out;
    for (let i = 0; i < rest.length; i++) {
      if (rest[i] === '--version' && rest[i + 1]) version = rest[++i];
      else if (rest[i] === '--out' && rest[i + 1]) out = path.resolve(rest[++i]);
      else
        throw new Error(
          `build: unknown argument "${rest[i]}" (--package <fe-src|kit> --version <v> --out <file>)`,
        );
    }
    if (!version || !out) throw new Error('build needs --version <v> and --out <file>');
    const { zip, sha256: hash, problems } = pkg.write({ version, out });
    if (problems.length) {
      console.error(
        `source-zip: the archive fails its checks; ${path.basename(out)} is not written:`,
      );
      for (const problem of problems) console.error(`  ${problem}`);
      return 1;
    }
    console.log(`source-zip: ${path.basename(out)} ${version}, ${zip.length} bytes`);
    console.log(hash);
    return 0;
  }
  if (command === 'verify') {
    if (rest.length !== 1) throw new Error('verify takes one archive');
    const zip = fs.readFileSync(path.resolve(rest[0]));
    const problems = pkg.verify(zip);
    if (problems.length) {
      console.error(`source-zip: ${rest[0]} is not ${pkg.label}:`);
      for (const problem of problems) console.error(`  ${problem}`);
      return 1;
    }
    console.log(`source-zip: ${rest[0]} verified as ${pkg.label}, sha256 ${sha256(zip)}`);
    return 0;
  }
  if (command === 'guard') {
    if (rest.length !== 1) throw new Error('guard takes one archive');
    const list = readCountryWords();
    const problems = pkg.guard(fs.readFileSync(path.resolve(rest[0])), list);
    if (problems.length) {
      console.error(`source-zip: ${rest[0]} fails the country-word guard:`);
      for (const problem of problems) console.error(`  ${problem}`);
      return 1;
    }
    console.log(
      `source-zip: ${rest[0]} passes the country-word guard (${list.words.length} words, ${list.allowed.length} allow-list entries)`,
    );
    return 0;
  }
  throw new Error(`unknown command "${command ?? ''}" (build, verify, guard)`);
}

if (invokedDirectly) {
  try {
    process.exit(main(process.argv.slice(2)));
  } catch (error) {
    console.error(`source-zip: ${error.message}`);
    process.exit(2);
  }
}
