import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { init, QUESTIONS } from './init.mjs';
import { answersSchema, readAnswers, standardDefaults } from './lib/answers.mjs';
import { where } from './lib/where.mjs';
import { writeZip } from './lib/zip.mjs';
import { readWorkspace } from '../fe/scripts/docs-check.mjs';

const KIT = path.dirname(fileURLToPath(import.meta.url));
const FE = where().base;
const PROMPT_FILE = path.join(KIT, 'docs', 'set-up-prompt.md');
const VERSION = '4.0.0-prompt';
/** The answers the questions' texts and the standard defaults are shown for, as the prompt shows them. */
const PLACEHOLDER = Object.freeze({ country: '<cc>', name: '<name>' });

/**
 * The folders a step after init makes, which the prompt names before they exist, each with the
 * step that makes it.
 */
const MADE_LATER = Object.freeze([
  'fe/.niord/', // set-up, (cd fe && node setup.mjs)
  'fe/node_modules/', // npm ci, run by the launcher and by the builds
  '.local-run/', // the launcher, ./start-local.sh
  'be/target/', // Maven, (cd be && ./mvnw verify)
]);

/** The names a route of the base product's own gives in its brief, which a country never has. */
const STAND_INS = Object.freeze([
  /NIORD_LOCAL_BASE_REPO/,
  /MAVEN_ARGS/,
  /\bNIORD_BASE\b/,
  /maven\.repo\.local/,
]);

/** The never items' needles: each group is held by one item of the never list. */
const NEVER = Object.freeze([
  ['fe/setup.mjs', 'start-local.sh', '.niord/'],
  ['invent'],
  ['token', '~/.m2/settings.xml'],
  ['compute', 'sha256'],
  ['REGISTRY', 'HARBOR_PROJECT', 'robot', 'image.yml', 'staging', 'production'],
  ['dropdb', '--reset', 'database'],
  ['push'],
]);

const temps = [];
/** A new folder under the system's temp folder, removed after the tests. */
function temp(prefix) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  temps.push(dir);
  return dir;
}
after(() => {
  for (const dir of temps) fs.rmSync(dir, { recursive: true, force: true });
});

/** The prompt's text, read in each case, so that each fails on its own while the file is missing. */
const prompt = () => fs.readFileSync(PROMPT_FILE, 'utf8');
const sha = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');

/**
 * The answers the schema holds, by key as init's questions name them: an object's answers as
 * `<key>.<sub>`, every other property as its key, and no property with a `const`, which is not an
 * answer (defaultLanguage).
 */
function schemaKeys() {
  const keys = [];
  for (const [key, node] of Object.entries(answersSchema().properties)) {
    if (Object.hasOwn(node, 'const')) continue;
    if (node.properties) for (const sub of Object.keys(node.properties)) keys.push(`${key}.${sub}`);
    else keys.push(key);
  }
  return keys;
}

/** The required answers: the schema's own, an object's by its own `required` as `<key>.<sub>`. */
function requiredKeys() {
  const { properties, required } = answersSchema();
  const keys = required.filter((key) => !properties[key].properties);
  for (const [key, node] of Object.entries(properties))
    for (const sub of node.required ?? []) keys.push(`${key}.${sub}`);
  return keys;
}

/** The value at the dotted `key` of `object`, or undefined. */
const valueAt = (object, key) => key.split('.').reduce((value, part) => value?.[part], object);

/** The schema's node of the dotted answer `key`. */
const schemaNode = (key) =>
  key.split('.').reduce((node, part) => node.properties[part], answersSchema());

/** The first code span of a Markdown text, or undefined. */
const firstCode = (text) => /`([^`]+)`/.exec(text)?.[1];

/**
 * The questions table: the Markdown table whose header's first cell is `Key`, a row per answer,
 * each `{ key, question, rule, standard }`: the first code span of the first and the second cell,
 * and the third and the fourth cell's text.
 */
function questionRows(text) {
  const lines = text.split(/\r?\n/);
  const cells = (line) =>
    line
      .trim()
      .replace(/^\|/, '')
      .replace(/(?<!\\)\|$/, '')
      .split(/(?<!\\)\|/)
      .map((cell) => cell.trim());
  const header = lines.findIndex((line) => line.startsWith('|') && cells(line)[0] === 'Key');
  assert.notEqual(header, -1, 'the prompt has a table whose first column is Key');
  const rows = [];
  for (const line of lines.slice(header + 2)) {
    if (!line.startsWith('|')) break;
    const [key, question, rule, standard] = cells(line);
    rows.push({ key: firstCode(key), question: firstCode(question), rule, standard });
  }
  return rows;
}

/** The fenced blocks of a Markdown text, each `{ info, lines }`. */
function fences(text) {
  const blocks = [];
  let block = null;
  for (const line of text.split(/\r?\n/)) {
    const fence = /^\s*```(\S*)/.exec(line);
    if (fence && block === null) block = { info: fence[1], lines: [] };
    else if (fence) {
      blocks.push(block);
      block = null;
    } else block?.lines.push(line);
  }
  return blocks;
}

/** The example answers files the prompt shows: each json block that holds a `country`. */
function examples(text) {
  const found = [];
  for (const { info, lines } of fences(text)) {
    if (info !== 'json') continue;
    let value;
    try {
      value = JSON.parse(lines.join('\n'));
    } catch {
      continue;
    }
    if (value !== null && typeof value === 'object' && Object.hasOwn(value, 'country'))
      found.push(value);
  }
  return found;
}

/**
 * The source zip's entries init reads, named as the zip names them (relative to fe/): the default
 * brand tokens, each application's English bundle and each branding file the schema requires.
 */
function sourceZipNames() {
  const schema = JSON.parse(fs.readFileSync(path.join(KIT, 'branding.schema.json'), 'utf8'));
  const names = ['styles/tokens.default.css'];
  for (const [app, section] of Object.entries(schema.apps)) {
    names.push(`languages/en/i18n/${app}/en.json`);
    for (const [relative, entry] of Object.entries(section.files ?? {}))
      if (entry.required)
        names.push(
          `ui-apps/${app}/public/assets/default/branding/${path.posix.basename(relative)}`,
        );
  }
  return names;
}

let country;
/**
 * The country init writes from the prompt's first example answers, made once: a new repository
 * holding, as steps 4 and 5 leave it, the two zips of the release, SHA256SUMS with their two
 * lines and .niord/init-answers.json, then init run on it as step 6 runs it, with the kit of this
 * tree. Returns the root and every line init logged.
 */
function countryOfPrompt() {
  if (country) return country;
  const [example] = examples(prompt());
  assert.ok(example, 'the prompt shows an example answers file');
  const root = temp('niord-prompt-');
  const niord = path.join(root, '.niord');
  fs.mkdirSync(niord);
  const zip = writeZip(
    sourceZipNames().map((name) => ({
      name,
      data: fs.readFileSync(path.join(FE, ...name.split('/'))),
    })),
  );
  const kitZip = Buffer.from('a kit package zip stands in here');
  fs.writeFileSync(path.join(niord, `niord-fe-src-${VERSION}.zip`), zip);
  fs.writeFileSync(path.join(niord, `niord-country-kit-${VERSION}.zip`), kitZip);
  fs.writeFileSync(
    path.join(niord, 'SHA256SUMS'),
    `${sha(zip)}  niord-fe-src-${VERSION}.zip\n${sha(kitZip)}  niord-country-kit-${VERSION}.zip\n`,
  );
  fs.writeFileSync(path.join(niord, 'init-answers.json'), `${JSON.stringify(example, null, 2)}\n`);
  const lines = [];
  init({
    root,
    answers: JSON.parse(fs.readFileSync(path.join(niord, 'init-answers.json'), 'utf8')),
    sha256File: '.niord/SHA256SUMS',
    version: VERSION,
    platform: 'win32',
    log: (line) => lines.push(line),
  });
  country = { root, lines };
  return country;
}

/** The repository paths the prompt names: in its inline code spans and its fenced lines. */
function namedPaths(text) {
  const PATH = /(?<![\w.~$/-])((?:\.niord|\.github|\.local-run|be|fe)\/[^\s`'"()]*)/g;
  const sources = [];
  let inFence = false;
  for (const line of text.split(/\r?\n/)) {
    if (/^\s*```/.test(line)) inFence = !inFence;
    else if (inFence) sources.push(line);
    else for (const m of line.matchAll(/`([^`]+)`/g)) sources.push(m[1]);
  }
  const paths = new Set();
  for (const source of sources)
    for (const m of source.matchAll(PATH)) {
      const token = m[1].replace(/[.,:;]+$/, '');
      if (!/[<>*{}$]/.test(token)) paths.add(token);
    }
  return [...paths];
}

/** The never list: the items of the prompt's lists that start with "Never", continuations joined. */
function neverItems(text) {
  const items = [];
  let item = null;
  for (const line of text.split(/\r?\n/)) {
    const start = /^\s*[-*]\s+(.*)$/.exec(line);
    if (start) {
      item = start[1];
      items.push(item);
    } else if (item !== null && /^\s+\S/.test(line)) items[items.length - 1] += ` ${line.trim()}`;
    else item = null;
  }
  return items.filter((text) => text.startsWith('Never'));
}

/** The commands init's closing lines name: the next commands and the Windows lines' commands. */
function closingCommands(lines) {
  const commands = [];
  for (const line of lines) {
    if (!line.startsWith('init:')) continue;
    const rest = line.slice('init:'.length);
    const command = /^ {3}\S/.test(rest)
      ? rest.trim()
      : /^ on Windows, [^:]*: (.*)$/.exec(rest)?.[1];
    if (
      command !== undefined &&
      /^(\(cd |\.\/start-local\.sh|git add|export NIORD_HOME)/.test(command)
    )
      commands.push(command);
  }
  return commands;
}

describe('the set-up prompt (docs/set-up-prompt.md)', () => {
  it("asks exactly the schema's answers, in init's order and as init asks them", () => {
    const rows = questionRows(prompt());
    assert.deepEqual(
      rows.map((row) => row.key),
      QUESTIONS.map((question) => question.key),
    );
    assert.deepEqual(new Set(rows.map((row) => row.key)), new Set(schemaKeys()));
    rows.forEach((row, i) =>
      assert.equal(
        row.question,
        QUESTIONS[i].prompt(PLACEHOLDER).replace(/:\s*$/, ''),
        `the question of ${row.key}`,
      ),
    );
  });

  it("marks the required answers and states every other one's standard default", () => {
    const rows = questionRows(prompt());
    const isRequired = (row) => row.standard.replace(/[*\s]/g, '') === 'required';
    assert.deepEqual(
      new Set(rows.filter(isRequired).map((row) => row.key)),
      new Set(requiredKeys()),
    );
    const defaults = standardDefaults(PLACEHOLDER);
    for (const row of rows.filter((row) => !isRequired(row))) {
      const code = firstCode(row.standard);
      assert.ok(code, `the standard default of ${row.key} is a code span`);
      assert.deepEqual(JSON.parse(code), valueAt(defaults, row.key), `the default of ${row.key}`);
    }
  });

  it("states each answer's fixed choices and limits as the schema does", () => {
    for (const row of questionRows(prompt())) {
      assert.notEqual(row.rule, '', `the rule of ${row.key}`);
      const node = schemaNode(row.key);
      for (const value of node.enum ?? [])
        assert.match(row.rule, new RegExp(`\\b${value}\\b`), `${row.key} names ${value}`);
      if (node.maxLength !== undefined)
        assert.match(
          row.rule,
          new RegExp(`\\b${node.maxLength}\\b`),
          `${row.key} names its limit ${node.maxLength}`,
        );
    }
  });

  it('its example answers file is complete, and init takes it in a new repository', () => {
    const shown = examples(prompt());
    assert.ok(shown.length > 0, 'the prompt shows an example answers file');
    for (const example of shown)
      assert.deepEqual(readAnswers(example), example, 'every answer written, defaults included');
    const { lines } = countryOfPrompt();
    assert.ok(lines.some((line) => line.startsWith('init: wrote ')));
    assert.deepEqual(
      lines.filter((line) => line.startsWith('init: kept')),
      [],
    );
  });

  it('names only files a country has', () => {
    const text = prompt();
    const { root } = countryOfPrompt();
    const { kitPackage } = readWorkspace();
    const named = namedPaths(text);
    assert.ok(named.length > 0, 'the prompt names paths');
    for (const token of named) {
      if (token === '.niord/kit' || token.startsWith('.niord/kit/')) {
        const relative = token.slice('.niord/kit'.length).replace(/^\//, '').replace(/\/$/, '');
        assert.ok(
          relative === '' || kitPackage.files.has(relative) || kitPackage.folders.has(relative),
          `${token} is in the kit package`,
        );
      } else if (!MADE_LATER.some((folder) => `${token}/`.startsWith(folder)))
        assert.ok(
          fs.existsSync(path.join(root, ...token.split('/'))),
          `${token} is in the country`,
        );
    }
  });

  it("holds none of the route's stand-ins", () => {
    const text = prompt();
    for (const name of STAND_INS) assert.doesNotMatch(text, name);
  });

  it("runs init's own commands", () => {
    const text = prompt();
    const required = [
      'node .niord/kit/init.mjs --answers .niord/init-answers.json --sha256-file .niord/SHA256SUMS',
      'node setup.mjs --update-pins',
    ];
    const commands = closingCommands(countryOfPrompt().lines);
    assert.ok(commands.length >= 4, `init's closing commands: ${commands.join(' | ')}`);
    for (const command of [...required, ...commands])
      assert.ok(text.includes(command), `the prompt runs ${command}`);
  });

  it('never lets the agent do what the person owns', () => {
    const items = neverItems(prompt());
    for (const needles of NEVER)
      assert.ok(
        items.some((item) => needles.every((needle) => item.includes(needle))),
        `one never item holds ${needles.join(', ')}`,
      );
  });
});
