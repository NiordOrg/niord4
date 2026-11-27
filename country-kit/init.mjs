#!/usr/bin/env node
/**
 * Starts a country repository: the whole standard country, backend and frontend, written from a
 * handful of answers, that sets up, builds and starts locally at once, onto which branding,
 * another language and the country's own functionality are each an increment.
 *
 *   node .niord/kit/init.mjs [<cc>] [--answers <file>]
 *       (--sha256-file <file> | --zip-sha256 <fe-sha256> --kit-sha256 <kit-sha256>)
 *       [--version <v>]                                              # from the repository root
 *
 * The answers (lib/answers.mjs, init-answers.schema.json) arrive one of two ways. Without
 * --answers init asks them one by one, line by line, each standard default shown in brackets and
 * taken with Enter alone; it reads lines, not keys, so it works on a pipe without a TTY, which is
 * what Git Bash's mintty gives a program. With --answers it reads them from a JSON file (the set-up
 * prompt's, the harness's and the tests' route). A code given first presets the country: its
 * question is skipped, and an answers file that names another country is refused. init validates
 * the whole object once and refuses with every problem named; asked answers are printed as the
 * JSON --answers takes, so a refused run can be repeated from a file.
 *
 * The version is the kit package's, from its marker (kit.json): init writes the version of the
 * kit it runs from, and a --version that disagrees is refused. Only in the base product's tree,
 * whose kit names no version, is --version given, and needed. No be/pom.xml is needed first; one
 * that exists is kept and must name that version as its parent's.
 *
 * Before init the repository holds the release's two packages in .niord/ (the source zip may lie
 * in fe/.niord/ instead, as the older guide fetches it) and the kit package unzipped into
 * .niord/kit/, from where init runs: only the kit is unzipped by hand. init checks both zips
 * against the release notes' two lines, given as a file exactly as the notes print them
 * (--sha256-file) or as the two flags, pins both in fe/niord-pins.lock.json and reads the source
 * zip in memory, after its hash check, for the base product's English texts, branding defaults
 * and tokens. That check pins the hashes; it does not authenticate the zips, since init itself
 * runs from the unpacked kit: the operator's `sha256sum -c` of the zips against the notes' lines,
 * before the kit's unzip, is the check.
 *
 * init validates everything first and writes nothing on a refusal: the answers, the hashes, the
 * zips, an existing pom, an existing manifest (one the kit reads, of the answers' country) and
 * every file computed in memory; the zips and the pom before the questions are asked. It then
 * writes, never over an existing file, which it lists as kept, but for the root's .gitignore, the
 * one existing file it changes:
 *
 *   the scaffold (lib/scaffold.mjs, 23 files from the answers): be/ whole (the pom on the base
 *   product's parent with the country build group's test dependencies, the Maven wrapper, the
 *   application class, application.yml and its local profile, the country settings file, the
 *   bootstrap with the first domain and its series, overrides.json and the group check), the
 *   manifest fe/niord-country.json, the public site's fe/info/app/info.config.ts,
 *   .github/CODEOWNERS with the owners, .github/workflows/image.yml (the frontend job, the
 *   backend job and a push job that verifies the three artifacts and the version and pushes the
 *   public site's image to the workflow's REGISTRY; MODULE filled, REGISTRY and HARBOR_PROJECT
 *   left to the country; when the repository has an image.yml both build jobs are printed to be
 *   added by hand), .github/workflows/ci.yml and the root README.md
 *   start-local.sh                  the country launcher, byte for byte templates/start-local.sh:
 *                                   the local stack in one command
 *   .gitignore                      the root's, with /.local-run/ (the launcher's downloads and
 *                                   logs): the one existing file init changes, adding that line
 *                                   when it lacks it
 *   .niord/.gitignore               '*': the root's .niord/ (the kit and its zips) ignores itself;
 *                                   set-up writes the same
 *   fe/setup.mjs                    the set-up stub, byte for byte the kit's template
 *   fe/<app>/assets/branding/…      what branding.schema.json requires: each application's
 *                                   required keys in en.json (with the English bundle's text, or
 *                                   none) and each required file as the base product's default
 *                                   copy. An optional key is left out: an empty or placeholder
 *                                   value is not neutral, because branding is merged over the
 *                                   bundle
 *   fe/styles/tokens.<cc>.css       a copy of the base product's default brand tokens
 *   fe/.gitignore                   the generated files, from workspace-files.json
 *   fe/.gitattributes               the base product's line-ending rule (templates/gitattributes):
 *                                   text stored LF, Windows command scripts (*.cmd) CRLF, the
 *                                   binary types never converted, so a Windows checkout keeps
 *                                   the bytes the kit hashes and compares
 *   .gitattributes                  the same rule at the root, for start-local.sh, the workflows
 *                                   and AGENTS.md
 *   .github/workflows/pack.yml      the home packs' publishing workflow: validate, publish and
 *                                   withdraw (fe-country-split-plan §4.5)
 *   .github/workflows/base-bump.yml the re-pin workflow of a base bump pull request (the Niord base
 *                                   bump app's, or a base-bump/<version> branch a person pushed):
 *                                   checks the release notes, pins the lock, moves the pinned
 *                                   packs, posts the upgrade report with the human actions and
 *                                   sets the status niord/upgrade-actions; it dispatches ci.yml,
 *                                   which must take workflow_dispatch
 *   .github/dependabot.yml          security updates only
 *   AGENTS.md                       the agent contract, byte for byte templates/AGENTS.md: it names
 *                                   no country, and a country's own rules go below it
 *   CLAUDE.md                       the one line @AGENTS.md, which loads AGENTS.md in Claude Code;
 *                                   a CLAUDE.md that exists is kept, and init prints that it must
 *                                   import AGENTS.md
 *   fe/niord-pins.lock.json         the base product's version and the two zips' hashes
 *
 * start-local.sh, be/mvnw and be/scripts/check-country-build-group.sh get the mode 0755. The
 * closing lines name the next two commands, the link to install the Niord base bump app and, on
 * Windows, the commit line that gives those three their exec bits and the NIORD_HOME that
 * be/mvnw verify needs there.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';
import { readAnswers, standardDefaults } from './lib/answers.mjs';
import { backendJob, frontendJob } from './lib/image-workflow.mjs';
import { readManifest } from './lib/manifest.mjs';
import { lockText, parentVersion, SHA256 } from './lib/pins.mjs';
import { scaffold } from './lib/scaffold.mjs';
import { KIT_PACKAGE, where } from './lib/where.mjs';
import { readZip } from './lib/zip.mjs';
import { flatten } from './stage-languages.mjs';

export { backendJob, frontendJob, imageWorkflow } from './lib/image-workflow.mjs';

const KIT = path.dirname(fileURLToPath(import.meta.url));
const CODE = /^[a-z]{2}$/;
const VERSION = /^[0-9][0-9A-Za-z.+_-]*$/;
const BASE_BUMP = '.github/workflows/base-bump.yml';
const CI = '.github/workflows/ci.yml';
const MANIFEST = 'fe/niord-country.json';
const SCRIPT = 0o755;
const FILE = 0o644;

/** The country's CLAUDE.md, the one line that loads AGENTS.md in Claude Code. */
export const CLAUDE_IMPORTER = '@AGENTS.md\n';

/** The line of the root's .gitignore that ignores the launcher's folder. */
const LOCAL_RUN = '/.local-run/';
/** The lines of a .gitignore that already ignore the launcher's folder at the root. */
const LOCAL_RUN_RULES = ['.local-run/', '/.local-run/', '.local-run', '/.local-run'];

/**
 * The root's .niord/.gitignore: the kit package and its zip, which the stub fetches there, ignore
 * themselves. The stub writes the same text (templates/setup.mjs).
 */
export const NIORD_GITIGNORE = '*\n';

/** The root's .gitignore of a new country, and what init adds to an existing one. */
export const rootGitignoreText = `# The country launcher's downloads and logs (./start-local.sh), never committed\n${LOCAL_RUN}\n`;

/** The country's fe/.gitignore: every generated file and folder, from workspace-files.json. */
export function gitignoreText(kit = KIT) {
  const { gitignore } = JSON.parse(fs.readFileSync(path.join(kit, 'workspace-files.json'), 'utf8'));
  return `# Generated by set-up (node setup.mjs), never committed\n${gitignore.join('\n')}\n`;
}

/**
 * The base product's files as the source zip holds them: `(relative) → Buffer | undefined`, the
 * entry of that name (relative to the zip's root, the base product's fe/) of readZip's `entries`.
 */
export function zipSource(entries) {
  const files = new Map(entries.map(({ name, data }) => [name, data]));
  return (relative) => files.get(relative);
}

/** The same over a folder holding the base product's fe/ (its own tree, in the tests). */
export function folderSource(dir) {
  return (relative) => {
    const file = path.join(dir, ...relative.split('/'));
    return fs.existsSync(file) && fs.statSync(file).isFile() ? fs.readFileSync(file) : undefined;
  };
}

/** The entry `relative` of `source`; a missing one throws, naming it. */
function required(source, relative) {
  const data = source(relative);
  if (data === undefined)
    throw new Error(
      `the source zip has no ${relative}: init reads the base product's defaults from it`,
    );
  return data;
}

/**
 * The branding skeleton of one application from the schema: `{ texts, files }`, the texts the
 * required keys with their English bundle's text (an empty object when no key is required), the
 * files each required file's relative path in the country's assets folder → the base product's
 * default copy, from `source`. A required file without a default copy fails.
 */
export function brandingSkeleton({ schema, app, source }) {
  const section = schema.apps[app];
  const english = flatten(
    JSON.parse(required(source, `languages/en/i18n/${app}/en.json`).toString('utf8')),
  );
  const texts = {};
  for (const [key, entry] of Object.entries(section.keys ?? {}))
    if (entry.required) texts[key] = english[key] ?? '';
  const files = {};
  for (const [relative, entry] of Object.entries(section.files ?? {})) {
    if (!entry.required) continue;
    if (relative.includes('{'))
      throw new Error(`${app}: the required file ${relative} is per language`);
    const name = path.posix.basename(relative);
    const data = source(`ui-apps/${app}/public/assets/default/branding/${name}`);
    if (data === undefined)
      throw new Error(
        `${app}: the required file ${relative} has no default copy in the base product`,
      );
    files[relative] = data;
  }
  return { texts, files };
}

/**
 * Every file init writes beside the scaffold's: path relative to the repository root → its bytes.
 * The templates and the kit's data come from `kit`, the base product's defaults from `source`
 * (zipSource or folderSource); the lock pins the kit package by `kitSha256` when it is given.
 */
export function skeleton({ cc, version, sha256, kitSha256, source, kit = KIT }) {
  const template = (name) => fs.readFileSync(path.join(kit, 'templates', name));
  const files = new Map();
  files.set('start-local.sh', template('start-local.sh'));
  files.set('.gitignore', Buffer.from(rootGitignoreText));
  files.set('.niord/.gitignore', Buffer.from(NIORD_GITIGNORE));
  files.set('fe/setup.mjs', template('setup.mjs'));
  const schema = JSON.parse(fs.readFileSync(path.join(kit, 'branding.schema.json'), 'utf8'));
  for (const app of Object.keys(schema.apps)) {
    const { texts, files: copies } = brandingSkeleton({ schema, app, source });
    if (Object.keys(texts).length)
      files.set(
        `fe/${app}/assets/branding/en.json`,
        Buffer.from(`${JSON.stringify(texts, null, 2)}\n`),
      );
    for (const [relative, data] of Object.entries(copies))
      files.set(`fe/${app}/assets/${relative}`, data);
  }
  files.set(`fe/styles/tokens.${cc}.css`, required(source, 'styles/tokens.default.css'));
  files.set('fe/.gitignore', Buffer.from(gitignoreText(kit)));
  files.set('fe/.gitattributes', template('gitattributes'));
  // The same rule at the root: start-local.sh, the workflows and AGENTS.md keep LF on a Windows
  // checkout, so a country's CI may compare the launcher with the template byte for byte.
  files.set('.gitattributes', template('gitattributes'));
  files.set('.github/workflows/pack.yml', template('pack.yml'));
  files.set(BASE_BUMP, template('base-bump.yml'));
  files.set('.github/dependabot.yml', template('dependabot.yml'));
  files.set('AGENTS.md', template('AGENTS.md'));
  files.set('CLAUDE.md', Buffer.from(CLAUDE_IMPORTER));
  files.set(
    'fe/niord-pins.lock.json',
    Buffer.from(
      lockText({ version, sha256, ...(kitSha256 ? { kit: { sha256: kitSha256 } } : {}) }),
    ),
  );
  return files;
}

/**
 * The version init writes, of the kit folder `kit` and the --version given: the kit package's,
 * from its marker, where a --version that differs is refused; in the base product's tree, whose
 * kit names none, the --version, which is then needed.
 */
export function kitVersion({ kit = KIT, version } = {}) {
  const layout = where(kit);
  let chosen = version;
  if (layout.kind === 'package') {
    if (version !== undefined && version !== layout.version)
      throw new Error(
        `--version ${version} is not the kit's version: .niord/kit is ${layout.version} (its kit.json); init writes the version of the kit it runs from`,
      );
    chosen = layout.version;
  } else if (version === undefined)
    throw new Error(
      `the kit at ${layout.kit} is the base product's tree, which names no version: give --version <v>`,
    );
  if (!VERSION.test(chosen))
    throw new Error(
      `"${chosen}" is not a base product version: a digit, then letters, digits, '.', '+', '_' or '-'`,
    );
  return chosen;
}

/** The release's two zips of `version`: the source zip and the kit package. */
const zipNames = (version) => ({
  sha256: `niord-fe-src-${version}.zip`,
  kitSha256: `${KIT_PACKAGE}-${version}.zip`,
});

/**
 * The two hashes of the release notes' lines `text` for `version`: `{ sha256, kitSha256 }`. Every
 * line but a blank one is `<sha256>  <file>` as sha256sum writes it (64 lower-case hex digits, two
 * spaces), its CR dropped, and the files are the release's two zips, each once; anything else
 * throws, naming the line.
 */
export function readSha256Lines(text, version) {
  const names = zipNames(version);
  const keyOf = new Map(Object.entries(names).map(([key, name]) => [name, key]));
  const hashes = {};
  const lines = text
    .replace(/^\uFEFF/, '')
    .replaceAll('\r', '')
    .split('\n');
  for (const line of lines) {
    if (line.trim() === '') continue;
    const match = /^([0-9a-f]{64}) {2}(.+)$/.exec(line);
    if (!match)
      throw new Error(
        `the line "${line}" is not "<sha256>  <file>", sha256sum's 64 lower-case hex digits, two spaces and the file`,
      );
    const [, hash, name] = match;
    const key = keyOf.get(name);
    if (key === undefined)
      throw new Error(
        `the line "${line}" names ${name}, which is neither ${names.sha256} nor ${names.kitSha256}`,
      );
    if (hashes[key] !== undefined) throw new Error(`the lines name ${name} twice`);
    hashes[key] = hash;
  }
  for (const [key, name] of Object.entries(names))
    if (hashes[key] === undefined) throw new Error(`there is no line for ${name}`);
  return hashes;
}

/** The answers' place `key` (`map.lat`) in `answers`, set to `value`. */
function setAnswer(answers, key, value) {
  const [part, field] = key.split('.');
  if (field === undefined) answers[part] = value;
  else (answers[part] ??= {})[field] = value;
}

/** How a line is read for an answer: a list, a whole number, or the text. */
const LIST = 'list';
const NUMBER = 'number';
const TEXT = 'text';

/** The standard defaults of the answers so far, which name the domain's and the series' defaults. */
const defaultsOf = (answers) =>
  standardDefaults({
    country: answers.country,
    name: answers.name,
    domain: { mainType: answers.domain?.mainType },
  });

/**
 * The questions, in the order init asks them: each answer's key, how its line is read and its
 * prompt, of the answers so far. The domain's main type comes before its id and name, whose
 * defaults follow it.
 */
export const QUESTIONS = Object.freeze(
  [
    ['country', TEXT, () => 'Country code, ISO 3166-1 alpha-2 in lower case: '],
    ['name', TEXT, () => 'Country name, as the applications show it, at most 60 characters: '],
    ['organisation', TEXT, () => 'Organisation that runs the deployment: '],
    [
      'languages',
      LIST,
      () => 'Languages besides English, published packs, separated by spaces [none]: ',
    ],
    ['producerCode', TEXT, () => 'S-124 producer code, IHO S-62, four capitals or digits [none]: '],
    [
      'timeZone',
      TEXT,
      () => 'Time zone of the first domain and the public site, IANA (UTC or Area/Location): ',
    ],
    ['map.lat', NUMBER, () => 'Map centre latitude, whole degrees from -90 to 90: '],
    ['map.lon', NUMBER, () => 'Map centre longitude, whole degrees from -180 to 180: '],
    ['map.zoom', NUMBER, () => 'Map zoom level, 1 to 18 [6]: '],
    ['domain.mainType', TEXT, () => "First domain's main type, NW or NM [NW]: "],
    ['domain.id', TEXT, (answers) => `First domain's id [${defaultsOf(answers).domain.id}]: `],
    [
      'domain.name',
      TEXT,
      (answers) => `First domain's name [${defaultsOf(answers).domain.name}]: `,
    ],
    [
      'series.id',
      TEXT,
      (answers) => `First message series' id [${defaultsOf(answers).series.id}]: `,
    ],
    [
      'series.shortFormat',
      TEXT,
      (answers) =>
        `First message series' short format [${defaultsOf(answers).series.shortFormat}]: `,
    ],
    [
      'series.numberSequenceType',
      TEXT,
      () => "First message series' numbering, YEARLY, CONTINUOUS, MANUAL or NONE [YEARLY]: ",
    ],
    [
      'owners',
      LIST,
      () => 'Repository owners for CODEOWNERS, @user or @org/team, separated by spaces: ',
    ],
  ].map(([key, kind, prompt]) => Object.freeze({ key, kind, prompt })),
);

/** An answer's line, trimmed and not blank, as its question reads it. */
function parsed(kind, text) {
  if (kind === LIST) return text.split(/[\s,]+/).filter(Boolean);
  if (kind === NUMBER && /^-?[0-9]+$/.test(text)) return Number(text);
  return text;
}

/**
 * The answers asked one by one on `output`, each read as a line of `input`: line by line rather
 * than key by key, so a pipe without a TTY (Git Bash's mintty) works, and every line a pipe
 * delivers at once is kept for the question it answers. A blank line leaves the answer out, so the
 * standard default the prompt showed is taken; `country`, given on the command line, is not asked.
 * Returns the answers as given, which init validates; the answers' end before the last question
 * throws.
 */
export async function askAnswers({ input, output, country }) {
  const reader = readline.createInterface({ input, terminal: false });
  const lines = reader[Symbol.asyncIterator]();
  const answers = {};
  output.write(
    'init: answer each question and press Enter; a value in [brackets] is the standard default, taken with Enter alone\n',
  );
  if (country !== undefined) {
    answers.country = country;
    output.write(`init: country: ${country}, from the command line\n`);
  }
  try {
    for (const { key, kind, prompt } of QUESTIONS) {
      if (key === 'country' && country !== undefined) continue;
      output.write(prompt(answers));
      const { value, done } = await lines.next();
      if (done)
        throw new Error(`the answers ended before the question ${key}: nothing was written`);
      const text = value.trim();
      if (text !== '') setAnswer(answers, key, parsed(kind, text));
    }
  } finally {
    reader.close();
  }
  // A pipe echoes no Enter, so the last prompt's line is ended here; a terminal has ended it.
  output.write(
    `${output.isTTY ? '' : '\n'}init: the answers, as --answers takes them:\n${JSON.stringify(answers, null, 2)}\n`,
  );
  return answers;
}

/**
 * The launcher's lines to add to the existing root .gitignore `text`, in its own line ending, or
 * undefined when a line already ignores the folder.
 */
function localRunAddition(text) {
  if (text.split(/\r?\n/).some((line) => LOCAL_RUN_RULES.includes(line.trim()))) return undefined;
  const eol = /\r\n/.test(text) ? '\r\n' : '\n';
  return `${text === '' || text.endsWith('\n') ? '' : eol}${rootGitignoreText.replaceAll('\n', eol)}`;
}

/**
 * Whether something, a file, a folder or a link, is at `file`. A file where a folder above it
 * should be is no folder, so nothing is at the path (checkFolders refuses the write).
 */
function present(file) {
  try {
    fs.lstatSync(file);
    return true;
  } catch (error) {
    if (error.code === 'ENOENT' || error.code === 'ENOTDIR') return false;
    throw error;
  }
}

/** The release's two hashes of init's options: one form, the file or both flags. */
function releaseHashes({ root, sha256, kitSha256, sha256File, version }) {
  const flags = sha256 !== undefined || kitSha256 !== undefined;
  if (sha256File !== undefined && flags)
    throw new Error(
      "give the release's hashes once: --sha256-file, or --zip-sha256 with --kit-sha256",
    );
  if (sha256File !== undefined) {
    let text;
    try {
      text = fs.readFileSync(path.resolve(root, sha256File), 'utf8');
    } catch (error) {
      throw new Error(
        `${sha256File} cannot be read (${error.code === 'ENOENT' ? 'there is no such file' : error.message}): it holds the release notes' two lines`,
        { cause: error },
      );
    }
    try {
      return readSha256Lines(text, version);
    } catch (error) {
      throw new Error(`${sha256File}: ${error.message}`, { cause: error });
    }
  }
  if (!flags)
    throw new Error(
      "the release's two hashes are needed: --sha256-file <file>, or --zip-sha256 and --kit-sha256",
    );
  if (!SHA256.test(sha256 ?? ''))
    throw new Error("--zip-sha256 is the zip's SHA-256, 64 lower-case hex digits");
  if (!SHA256.test(kitSha256 ?? ''))
    throw new Error("--kit-sha256 is the kit package zip's SHA-256, 64 lower-case hex digits");
  return { sha256, kitSha256 };
}

/** The bytes of the zip at `relative` in `root`, refused unless their SHA-256 is `hash`. */
function checkedZip(root, relative, hash) {
  const bytes = fs.readFileSync(path.join(root, ...relative.split('/')));
  const actual = crypto.createHash('sha256').update(bytes).digest('hex');
  if (actual !== hash)
    throw new Error(`${relative} has the SHA-256 ${actual}, not ${hash}: nothing was written`);
  return bytes;
}

/** Throws unless every folder above `relative` in `root` is a folder or not there yet. */
function checkFolders(root, relative) {
  const parts = relative.split('/').slice(0, -1);
  for (let i = 1; i <= parts.length; i++) {
    const folder = parts.slice(0, i).join('/');
    const file = path.join(root, ...parts.slice(0, i));
    if (!present(file)) return;
    if (!fs.statSync(file).isDirectory())
      throw new Error(`${folder} is not a folder, and init writes ${relative} into it`);
  }
}

/**
 * The lines init prints last: the next commands, the app's install link, and on Windows the exec
 * bits and NIORD_HOME.
 */
function closingLines(answers, platform) {
  const setup = answers.languages.length ? 'node setup.mjs --update-pins' : 'node setup.mjs';
  const lines = [
    'init: next, from the repository root:',
    `init:   (cd fe && ${setup})`,
    'init:   ./start-local.sh',
    "init: ./start-local.sh --sample starts the base product's sample instead (the kit's README, Running it locally)",
    'init: on GitHub, install the Niord base bump app on this repository once, so each base version arrives as a pull request: https://github.com/apps/niord-base-bump (the country guide, Upgrades)',
  ];
  if (platform === 'win32')
    lines.push(
      'init: on Windows, give the scripts their exec bits in the first commit: git add -A && git add --chmod=+x start-local.sh be/mvnw be/scripts/check-country-build-group.sh',
      // One known Niord home for the integration tests, the same folder as their own default; the
      // path may be written with forward slashes or backslashes.
      'init: on Windows, before be/mvnw verify: export NIORD_HOME=$(cygpath -m "$TEMP")/niord',
    );
  return lines;
}

/**
 * The release in the repository `root` checked against `version` and the notes' `hashes`, reading
 * nothing but what it checks and writing nothing: an existing be/pom.xml names `version` as its
 * parent's, both zips are there and have their hashes, and the source zip is one. Returns the
 * source zip's place and its entries, read in memory. main() runs it before the questions, so a
 * missing or wrong zip costs no answers; init() runs it again.
 */
export function checkRelease({ root, version, hashes }) {
  const pom = path.join(root, 'be', 'pom.xml');
  if (present(pom)) {
    const parent = parentVersion(fs.readFileSync(pom, 'utf8'));
    if (parent !== version)
      throw new Error(
        `be/pom.xml's parent is ${parent}, but the kit at .niord/kit is ${version}: unpack the kit package of ${parent}, or move be/pom.xml's parent to ${version}`,
      );
  }

  const names = zipNames(version);
  const sourceAt = [`.niord/${names.sha256}`, `fe/.niord/${names.sha256}`].find((relative) =>
    present(path.join(root, ...relative.split('/'))),
  );
  if (sourceAt === undefined)
    throw new Error(
      `.niord/${names.sha256} does not exist (nor fe/.niord/${names.sha256}): fetch the release's zip first`,
    );
  const kitAt = `.niord/${names.kitSha256}`;
  if (!present(path.join(root, ...kitAt.split('/'))))
    throw new Error(`${kitAt} does not exist: fetch the release's kit package first`);
  const sourceZip = checkedZip(root, sourceAt, hashes.sha256);
  checkedZip(root, kitAt, hashes.kitSha256);
  try {
    return { sourceAt, entries: readZip(sourceZip) };
  } catch (error) {
    throw new Error(`${sourceAt}: ${error.message}: nothing was written`, { cause: error });
  }
}

/**
 * Throws unless the manifest at `file`, which init keeps, is one the kit reads and names the
 * answers' country `cc`: init would otherwise write a country beside another's manifest.
 */
function checkKeptManifest(file, cc) {
  let manifest;
  try {
    manifest = readManifest(file);
  } catch (error) {
    throw new Error(
      `${MANIFEST} exists and is not a manifest the kit reads (${error.message}): nothing was written`,
      { cause: error },
    );
  }
  if (manifest.country !== cc)
    throw new Error(
      `${MANIFEST} exists and names ${manifest.country}, not ${cc}, the answers' country: nothing was written`,
    );
}

/**
 * Writes the country of `answers` into the repository `root`. Returns the files written, kept and
 * changed (the root's .gitignore, when init added the launcher's line to it). Validates
 * everything before it writes, and throws, writing nothing, on: a version it cannot take
 * (kitVersion), refused answers (every problem named), the hashes missing, given twice or
 * malformed, an existing be/pom.xml of another parent version, a zip missing or not its hash, a
 * source zip that is not one or lacks a file init copies, an existing fe/niord-country.json that
 * is no manifest or names another country, and a file in the way of a folder. `sha256File` is
 * relative to `root`; `platform` chooses the closing lines.
 */
export function init({
  root,
  answers,
  sha256,
  kitSha256,
  sha256File,
  version,
  kit = KIT,
  platform = process.platform,
  log = console.log,
}) {
  const v = kitVersion({ kit, version });
  let a;
  try {
    a = readAnswers(answers);
  } catch (error) {
    throw new Error(
      error.message.replace(
        /^the answers are refused:/,
        'the answers are refused: nothing was written',
      ),
      { cause: error },
    );
  }
  const hashes = releaseHashes({ root, sha256, kitSha256, sha256File, version: v });
  const { entries } = checkRelease({ root, version: v, hashes });
  const manifest = path.join(root, ...MANIFEST.split('/'));
  if (present(manifest)) checkKeptManifest(manifest, a.country);

  const rows = scaffold({ answers: a, kit, version: v });
  const scaffolded = new Set(rows.map((row) => row.path));
  for (const [relative, data] of skeleton({
    cc: a.country,
    version: v,
    ...hashes,
    source: zipSource(entries),
    kit,
  })) {
    if (scaffolded.has(relative))
      throw new Error(`${relative} is both the scaffold's and the skeleton's`);
    rows.push({ path: relative, data, mode: relative === 'start-local.sh' ? SCRIPT : FILE });
  }

  // What is written, kept and changed, decided before the first write.
  const written = [];
  const kept = [];
  const changed = [];
  let gitignoreAddition;
  const toWrite = [];
  for (const row of rows) {
    const file = path.join(root, ...row.path.split('/'));
    if (!present(file)) {
      checkFolders(root, row.path);
      toWrite.push({ ...row, file });
      continue;
    }
    const addition =
      row.path === '.gitignore' && fs.statSync(file).isFile()
        ? localRunAddition(fs.readFileSync(file, 'utf8'))
        : undefined;
    if (addition === undefined) kept.push(row.path);
    else {
      gitignoreAddition = addition;
      changed.push(row.path);
    }
  }

  for (const { path: relative, data, mode, file } of toWrite) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, data, { flag: 'wx', mode });
    // The umask, and the zip's 0644 the data came from, would otherwise decide the mode.
    if (mode === SCRIPT) fs.chmodSync(file, SCRIPT);
    written.push(relative);
  }
  if (gitignoreAddition !== undefined)
    fs.appendFileSync(path.join(root, '.gitignore'), gitignoreAddition);

  for (const relative of written) log(`init: wrote ${relative}`);
  for (const relative of kept) log(`init: kept ${relative}, which exists`);
  for (const relative of changed) log(`init: added ${LOCAL_RUN} to ${relative}`);
  if (
    kept.includes('CLAUDE.md') &&
    !fs
      .readFileSync(path.join(root, 'CLAUDE.md'), 'utf8')
      .split(/\r?\n/)
      .some((line) => line.trim() === CLAUDE_IMPORTER.trim())
  )
    log(
      'init: CLAUDE.md exists; add the line @AGENTS.md to it, so that Claude Code reads the agent contract in AGENTS.md',
    );
  if (kept.includes('.github/workflows/image.yml')) {
    log(
      `init: .github/workflows/image.yml exists; add this frontend job to its jobs:\n\n${frontendJob(kit)}`,
    );
    log(
      `init: .github/workflows/image.yml exists; add this backend job to its jobs:\n\n${backendJob(kit)}`,
    );
    log(
      "init: the push job of .niord/kit/templates/image.yml needs both jobs, downloads backend-dist and verifies its SHA-256, and runs 'Check the version' before it pushes",
    );
  }
  if (kept.includes(BASE_BUMP))
    log(
      `init: ${BASE_BUMP} exists; compare it with .niord/kit/templates/base-bump.yml (a base bump pull request lists a difference as an item)`,
    );
  // The re-pin workflow dispatches the country's CI on the branch it pushed to, since a push with
  // the workflow's token starts no workflow. init's own ci.yml takes workflow_dispatch; a kept one
  // may not.
  if (
    kept.includes(CI) &&
    (written.includes(BASE_BUMP) || kept.includes(BASE_BUMP)) &&
    fs.statSync(path.join(root, CI)).isFile() &&
    !/^\s*workflow_dispatch:/m.test(fs.readFileSync(path.join(root, CI), 'utf8'))
  )
    log(
      `init: ${CI} exists without a workflow_dispatch: trigger; base-bump.yml dispatches it on a re-pinned bump branch, so add workflow_dispatch: to its on:`,
    );
  for (const line of closingLines(a, platform)) log(line);
  return { written, kept, changed };
}

const USAGE =
  "(init [<cc>] [--answers <file>] --sha256-file <file> | --zip-sha256 <fe-sha256> --kit-sha256 <kit-sha256> [--version <v>, the base product's tree only])";
const VALUE_FLAGS = {
  '--answers': 'answersFile',
  '--sha256-file': 'sha256File',
  '--zip-sha256': 'sha256',
  '--kit-sha256': 'kitSha256',
  '--version': 'version',
};

/** The command line's options; an unknown argument, or a flag without its value, is undefined. */
function parseArgs(args) {
  const options = {};
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (Object.hasOwn(VALUE_FLAGS, arg)) {
      const value = args[i + 1];
      if (value === undefined || value.startsWith('--')) return { unknown: arg };
      options[VALUE_FLAGS[arg]] = value;
      i++;
    } else if (!arg.startsWith('--') && options.cc === undefined) options.cc = arg;
    else return { unknown: arg };
  }
  return { options };
}

/** The answers file `given`, relative to the working folder, parsed. */
function readAnswersFile(given) {
  let text;
  try {
    text = fs.readFileSync(path.resolve(given), 'utf8');
  } catch (error) {
    throw new Error(
      `${given} cannot be read (${error.code === 'ENOENT' ? 'there is no such file' : error.message})`,
      { cause: error },
    );
  }
  try {
    return JSON.parse(text.replace(/^\uFEFF/, ''));
  } catch (error) {
    throw new Error(`${given} is not JSON (${error.message})`, { cause: error });
  }
}

async function main(args) {
  const { options, unknown } = parseArgs(args);
  if (unknown !== undefined) {
    console.error(`init: unknown argument "${unknown}" ${USAGE}`);
    process.exit(2);
  }
  const { cc, answersFile, sha256File, sha256, kitSha256, version } = options;
  try {
    if (cc !== undefined && !CODE.test(cc))
      throw new Error(
        `"${cc}" is not a country code, its ISO 3166-1 alpha-2 code, two letters a-z`,
      );
    // The command line and the release are checked before the questions, so a wrong hash or a
    // missing zip costs no answers.
    const v = kitVersion({ kit: KIT, version });
    const hashes = releaseHashes({
      root: process.cwd(),
      sha256,
      kitSha256,
      sha256File,
      version: v,
    });
    if (answersFile === undefined) checkRelease({ root: process.cwd(), version: v, hashes });
    let answers;
    if (answersFile !== undefined) {
      answers = readAnswersFile(answersFile);
      if (cc !== undefined && answers !== null && typeof answers === 'object') {
        if (answers.country === undefined) answers = { country: cc, ...answers };
        else if (answers.country !== cc)
          throw new Error(
            `the code on the command line, ${cc}, is not the answers file's country, ${answers.country}`,
          );
      }
    } else
      answers = await askAnswers({ input: process.stdin, output: process.stdout, country: cc });
    init({ root: process.cwd(), answers, sha256, kitSha256, sha256File, version, kit: KIT });
  } catch (error) {
    console.error(`init: ${error.message}`);
    process.exit(1);
  }
}

const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

if (invokedDirectly) await main(process.argv.slice(2));
