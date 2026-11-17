import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { PassThrough, Readable } from 'node:stream';
import { after, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  askAnswers,
  backendJob,
  folderSource,
  frontendJob,
  gitignoreText,
  imageWorkflow,
  init,
  kitVersion,
  QUESTIONS,
  readSha256Lines,
  skeleton,
  zipSource,
} from './init.mjs';
import { readAnswers, standardDefaults } from './lib/answers.mjs';
import { checkBranding } from './lib/branding.mjs';
import { readManifest } from './lib/manifest.mjs';
import { parentVersion } from './lib/pins.mjs';
import { scaffold } from './lib/scaffold.mjs';
import { backendEntries, markerText, where } from './lib/where.mjs';
import { readZip, writeZip } from './lib/zip.mjs';
import { requireFromBase } from './test-fixtures/base-modules.mjs';
import { runBash, stepEnv, stepRun } from '../fe/scripts/workflow-step.mjs';

const yaml = requireFromBase('js-yaml');

const KIT = path.dirname(fileURLToPath(import.meta.url));
const FE = where().base;
const BE = where().be;
const VERSION = '4.0.9';
const sha = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');
const KIT_ZIP = Buffer.from('a kit package zip stands in here');
const KIT_SHA = sha(KIT_ZIP);

/** A made-up country's answers: the seven required ones, every other answer its default. */
const ANSWERS = Object.freeze({
  country: 'qq',
  name: 'Example Country QQ',
  organisation: 'The Example Hydrographic Office',
  timeZone: 'UTC',
  map: Object.freeze({ lat: 0, lon: 0 }),
  owners: Object.freeze(['@example']),
});

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

/** A real zip of the tree's fe/ files `names`. */
const sourceZip = (names = sourceZipNames()) =>
  writeZip(
    names.map((name) => ({ name, data: fs.readFileSync(path.join(FE, ...name.split('/'))) })),
  );
const ZIP = sourceZip();
const SHA = sha(ZIP);

/** The release notes' two lines, as sha256sum writes them. */
const sumsText = ({ version = VERSION, sha256 = SHA, kitSha256 = KIT_SHA } = {}) =>
  `${sha256}  niord-fe-src-${version}.zip\n${kitSha256}  niord-country-kit-${version}.zip\n`;

const KIT_EXCLUDED = new Set(['country-words.json', 'source-zip.mjs', 'eslint.config.mjs']);

/**
 * The kit package of `version` unpacked into `dir`, as the Quick start unzips it: the tree's kit
 * files the package carries (kit-include.json) but the guides and the example, which init never
 * reads, the backend entries built from the tree's be/, and the marker.
 */
function unpackKit(dir, version = VERSION) {
  fs.mkdirSync(dir, { recursive: true });
  const shipped = (name) => !name.endsWith('.test.mjs') && !KIT_EXCLUDED.has(name);
  for (const entry of fs.readdirSync(KIT, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (entry.name === 'lib' || entry.name === 'templates')
        fs.cpSync(path.join(KIT, entry.name), path.join(dir, entry.name), {
          recursive: true,
          filter: (source) => shipped(path.basename(source)),
        });
    } else if (/\.(mjs|json)$/.test(entry.name) && shipped(entry.name))
      fs.copyFileSync(path.join(KIT, entry.name), path.join(dir, entry.name));
  }
  for (const { name, data } of backendEntries(BE)) {
    const file = path.join(dir, ...name.split('/'));
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, data);
  }
  fs.writeFileSync(path.join(dir, 'kit.json'), markerText(version));
  return dir;
}

/** One kit package, unpacked once, for the tests that call init in-process. */
const SHARED_KIT = unpackKit(path.join(temp('niord-init-kit-'), '.niord', 'kit'));

/**
 * A new country repository as the Quick start leaves it before init: only .niord/, holding both
 * zips of `version` and SHA256SUMS with their two lines, and, with `kit`, the kit package unzipped
 * into .niord/kit. The source zip lies in `zipIn` (.niord, or fe/.niord as the older guide has it).
 */
function repository({ kit = false, version = VERSION, zipIn = '.niord', zip = ZIP } = {}) {
  const root = temp('niord-init-');
  fs.mkdirSync(path.join(root, '.niord'));
  fs.mkdirSync(path.join(root, ...zipIn.split('/')), { recursive: true });
  fs.writeFileSync(path.join(root, ...zipIn.split('/'), `niord-fe-src-${version}.zip`), zip);
  fs.writeFileSync(path.join(root, '.niord', `niord-country-kit-${version}.zip`), KIT_ZIP);
  fs.writeFileSync(
    path.join(root, '.niord', 'SHA256SUMS'),
    sumsText({ version, sha256: sha(zip) }),
  );
  if (kit) unpackKit(path.join(root, '.niord', 'kit'), version);
  return root;
}

const run = (root, options = {}) =>
  init({
    root,
    answers: ANSWERS,
    sha256File: '.niord/SHA256SUMS',
    kit: SHARED_KIT,
    platform: 'linux',
    log: () => {},
    ...options,
  });
const read = (root, relative) => fs.readFileSync(path.join(root, relative), 'utf8');
const exists = (root, relative) => fs.existsSync(path.join(root, relative));
const writeIn = (root, relative, data) => {
  const file = path.join(root, ...relative.split('/'));
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, data);
};

/**
 * init run as the Quick start runs it, from the repository root: the kit's own init.mjs, of the
 * kit unzipped into the repository or, for a refusal, of the shared kit package.
 */
const cli = (root, args, input, kit = path.join(root, '.niord', 'kit')) =>
  spawnSync(process.execPath, [path.join(kit, 'init.mjs'), ...args], {
    cwd: root,
    encoding: 'utf8',
    input,
  });

/** Every path under `root`, each folder as 'folder' and each file as its SHA-256. */
function snapshot(root) {
  const paths = {};
  for (const entry of fs.readdirSync(root, { recursive: true, withFileTypes: true })) {
    const file = path.join(entry.parentPath, entry.name);
    const relative = path.relative(root, file).replaceAll('\\', '/');
    paths[relative] = entry.isDirectory() ? 'folder' : sha(fs.readFileSync(file));
  }
  return paths;
}

/**
 * Asserts that `action` throws, its message matching `pattern` (or the error passing it, a
 * function), and leaves every path under `root` as it was.
 */
function assertRefused(root, action, pattern) {
  const before = snapshot(root);
  assert.throws(action, (error) => {
    if (typeof pattern === 'function') assert.ok(pattern(error), error.message);
    else assert.match(error.message, pattern);
    return true;
  });
  assert.deepEqual(snapshot(root), before, 'a refusal wrote or changed a file');
}

let image;
/** The image.yml init writes into a new repository, made once. */
function imageText() {
  if (image === undefined) {
    const root = repository();
    run(root);
    image = read(root, '.github/workflows/image.yml');
  }
  return image;
}

/** A job's lines in a workflow, from its key to the next job's. */
function jobText(text, job) {
  const lines = text.split('\n');
  const start = lines.indexOf(`  ${job}:`);
  assert.ok(start >= 0, `the workflow has no job ${job}`);
  const end = lines.findIndex((line, i) => i > start && /^ {2}[a-z-]+:$/.test(line));
  return lines.slice(start, end < 0 ? undefined : end).join('\n');
}

/** The manifest the scaffold writes for ANSWERS of the country `cc`, as text. */
const manifestOf = (cc) =>
  scaffold({ answers: { ...ANSWERS, country: cc }, kit: SHARED_KIT, version: VERSION })
    .find((row) => row.path === 'fe/niord-country.json')
    .data.toString('utf8');

/** The 16 lines a person types for ANSWERS, Enter alone for every defaulted answer. */
const DEFAULTED_LINES = [
  'qq',
  'Example Country QQ',
  'The Example Hydrographic Office',
  '',
  '',
  'UTC',
  '0',
  '0',
  '',
  '',
  '',
  '',
  '',
  '',
  '',
  '@example',
];

describe('init', () => {
  it('writes every file of the scaffold and of the skeleton, byte for byte, into a repository holding only .niord/', () => {
    const root = repository();
    const { written, kept, changed } = run(root);
    assert.deepEqual(kept, []);
    assert.deepEqual(changed, []);
    const rows = scaffold({ answers: ANSWERS, kit: SHARED_KIT, version: VERSION });
    const files = skeleton({
      cc: 'qq',
      version: VERSION,
      sha256: SHA,
      kitSha256: KIT_SHA,
      source: zipSource(readZip(ZIP)),
      kit: SHARED_KIT,
    });
    assert.equal(rows.length, 23);
    assert.deepEqual(written, [...rows.map((row) => row.path), ...files.keys()]);
    assert.equal(written.length, 40, written.join('\n'));
    // The kit retired templates/renovate.json: the bump arrives by the Niord base bump app.
    assert.ok(
      !files.has('.github/renovate.json'),
      'the skeleton still writes .github/renovate.json',
    );
    assert.ok(!written.includes('.github/renovate.json'), written.join('\n'));
    assert.equal(exists(root, '.github/renovate.json'), false);
    for (const { path: relative, data } of rows)
      assert.ok(fs.readFileSync(path.join(root, relative)).equals(data), relative);
    for (const [relative, data] of files)
      assert.ok(fs.readFileSync(path.join(root, relative)).equals(data), relative);
    // The four files both once wrote are the scaffold's: from the answers, not placeholders.
    for (const relative of [
      'fe/niord-country.json',
      '.github/CODEOWNERS',
      '.github/workflows/image.yml',
      'be/bootstrap/README.md',
    ])
      assert.ok(!files.has(relative), `the skeleton still writes ${relative}`);
    assert.doesNotMatch(read(root, '.github/CODEOWNERS'), /@OWNER/);
    assert.match(read(root, '.github/CODEOWNERS'), /^\/fe\/niord-country\.json +@example$/m);
    assert.equal(yaml.load(read(root, '.github/workflows/image.yml')).env.MODULE, 'niord-qq');
    assert.equal(readManifest(path.join(root, 'fe', 'niord-country.json')).name, ANSWERS.name);
    // Only the paths written are there, beside .niord/'s own.
    const present = Object.entries(snapshot(root))
      .filter(([relative, kind]) => kind !== 'folder' && !relative.startsWith('.niord/'))
      .map(([relative]) => relative)
      .sort();
    assert.deepEqual(present, written.filter((file) => !file.startsWith('.niord/')).sort());
  });

  it('writes be/pom.xml with the parent version of the kit package, no pom needed first', () => {
    const root = repository();
    run(root);
    assert.equal(parentVersion(read(root, 'be/pom.xml')), VERSION);
    assert.ok(
      fs
        .readFileSync(path.join(root, 'be', 'mvnw'))
        .equals(fs.readFileSync(path.join(SHARED_KIT, 'backend', 'mvnw'))),
    );
  });

  it('pins both hashes of SHA256SUMS in the lock', () => {
    const root = repository();
    run(root);
    assert.deepEqual(JSON.parse(read(root, 'fe/niord-pins.lock.json')), {
      format: 1,
      base: { version: VERSION, sha256: SHA },
      kit: { sha256: KIT_SHA },
      packs: {},
    });
    // The root's .niord/, the kit and its zip, ignores itself, as set-up's stub writes it.
    assert.equal(read(root, '.niord/.gitignore'), '*\n');
  });

  it('takes the hashes in flag form as the same input', () => {
    const root = repository();
    run(root, { sha256File: undefined, sha256: SHA, kitSha256: KIT_SHA });
    assert.deepEqual(JSON.parse(read(root, 'fe/niord-pins.lock.json')).kit, { sha256: KIT_SHA });
  });

  it('finds the source zip in fe/.niord/ when .niord/ has none', () => {
    const root = repository({ zipIn: 'fe/.niord' });
    assert.equal(exists(root, `.niord/niord-fe-src-${VERSION}.zip`), false);
    const { written } = run(root);
    assert.ok(written.includes('be/pom.xml'));
    assert.equal(JSON.parse(read(root, 'fe/niord-pins.lock.json')).base.sha256, SHA);
  });

  it('keeps an existing .niord/.gitignore', () => {
    const root = repository();
    fs.writeFileSync(path.join(root, '.niord', '.gitignore'), '*\r\n');
    const { written, kept } = run(root);
    assert.ok(kept.includes('.niord/.gitignore'));
    assert.ok(!written.includes('.niord/.gitignore'));
    assert.equal(read(root, '.niord/.gitignore'), '*\r\n');
  });

  it("keeps an existing be/pom.xml of the kit's version, AGENTS.md, CLAUDE.md, image.yml and ci.yml byte for byte", () => {
    const root = repository();
    const own = {
      'be/pom.xml': `<project>\r\n<parent><groupId>org.niord</groupId><artifactId>niord-parent</artifactId><version>${VERSION}</version></parent>\r\n</project>`,
      'AGENTS.md': "# The country's own contract\n",
      'CLAUDE.md': '# Own\n@AGENTS.md\n',
      '.github/workflows/image.yml': 'name: Image\n',
      '.github/workflows/ci.yml': 'name: CI\non:\n  workflow_dispatch:\n',
    };
    for (const [relative, text] of Object.entries(own)) writeIn(root, relative, text);
    const { written, kept } = run(root);
    assert.deepEqual([...kept].sort(), Object.keys(own).sort());
    for (const [relative, text] of Object.entries(own)) {
      assert.equal(read(root, relative), text, relative);
      assert.ok(!written.includes(relative), relative);
    }
    // The rest of the backend is written beside the kept pom.
    assert.ok(written.includes('be/mvnw'));
  });

  it("keeps an existing fe/niord-country.json of the answers' country and writes the rest", () => {
    const root = repository();
    const own = manifestOf('qq').replace(ANSWERS.name, 'The Country QQ, as it named itself');
    writeIn(root, 'fe/niord-country.json', own);
    const { written, kept } = run(root);
    assert.deepEqual(kept, ['fe/niord-country.json']);
    assert.equal(read(root, 'fe/niord-country.json'), own);
    assert.ok(written.includes('be/pom.xml') && written.includes('.github/CODEOWNERS'));
  });

  it('lists every file as kept on a second run, and changes no byte', () => {
    const root = repository();
    const first = run(root);
    const before = snapshot(root);
    const second = run(root);
    assert.deepEqual(second.written, []);
    assert.deepEqual(second.changed, []);
    assert.deepEqual(second.kept, first.written);
    assert.deepEqual(snapshot(root), before);
  });

  describe('refuses, writing nothing', () => {
    it('answers it refuses, naming every problem', () => {
      const root = repository();
      const { owners, ...withoutOwners } = ANSWERS;
      assert.deepEqual(owners, ['@example']);
      assertRefused(
        root,
        () =>
          run(root, {
            answers: { ...withoutOwners, timeZone: 'europe/london' },
          }),
        (error) =>
          /^the answers are refused: nothing was written\n/.test(error.message) &&
          /^owners: is required/m.test(error.message) &&
          /^timeZone: /m.test(error.message),
      );
      assertRefused(
        root,
        () => run(root, { answers: { ...ANSWERS, country: 'xxx' } }),
        /^country: .*two letters a-z/m,
      );
      assertRefused(root, () => run(root, { answers: undefined }), /the answers are refused/);
    });

    for (const [what, options, pattern] of [
      [
        'a missing --kit-sha256',
        { sha256File: undefined, sha256: SHA },
        /^--kit-sha256 is the kit package zip's SHA-256, 64 lower-case hex digits$/,
      ],
      [
        'a malformed --kit-sha256',
        { sha256File: undefined, sha256: SHA, kitSha256: 'F'.repeat(64) },
        /^--kit-sha256 is the kit package zip's SHA-256, 64 lower-case hex digits$/,
      ],
      [
        'a short --zip-sha256',
        { sha256File: undefined, sha256: 'f'.repeat(63), kitSha256: KIT_SHA },
        /^--zip-sha256 is the zip's SHA-256, 64 lower-case hex digits$/,
      ],
      [
        'both forms of the hashes',
        { sha256: SHA, kitSha256: KIT_SHA },
        /^give the release's hashes once: --sha256-file, or --zip-sha256 with --kit-sha256$/,
      ],
      [
        'no hashes at all',
        { sha256File: undefined },
        /^the release's two hashes are needed: --sha256-file <file>, or --zip-sha256 and --kit-sha256$/,
      ],
      [
        'a source zip of another hash, in flag form',
        { sha256File: undefined, sha256: 'f'.repeat(64), kitSha256: KIT_SHA },
        /^\.niord\/niord-fe-src-4\.0\.9\.zip has the SHA-256 [0-9a-f]{64}, not f{64}: nothing was written$/,
      ],
      [
        'a kit zip of another hash, in flag form',
        { sha256File: undefined, sha256: SHA, kitSha256: 'e'.repeat(64) },
        /^\.niord\/niord-country-kit-4\.0\.9\.zip has the SHA-256 [0-9a-f]{64}, not e{64}: nothing was written$/,
      ],
      ['a SHA256SUMS that does not exist', { sha256File: '.niord/NONE' }, /^\.niord\/NONE /],
    ])
      it(what, () => {
        const root = repository();
        assertRefused(root, () => run(root, options), pattern);
      });

    for (const [what, text, pattern] of [
      [
        'a SHA256SUMS line of another hash',
        sumsText({ sha256: 'a'.repeat(64) }),
        /niord-fe-src-4\.0\.9\.zip has the SHA-256 [0-9a-f]{64}, not a{64}: nothing was written$/,
      ],
      [
        'a malformed SHA256SUMS line',
        `abc  niord-fe-src-${VERSION}.zip\n${KIT_SHA}  niord-country-kit-${VERSION}.zip\n`,
        /"abc {2}niord-fe-src-4\.0\.9\.zip"/,
      ],
      [
        "a SHA256SUMS line in sha256sum's binary form",
        `${SHA} *niord-fe-src-${VERSION}.zip\n${KIT_SHA}  niord-country-kit-${VERSION}.zip\n`,
        /\*niord-fe-src-4\.0\.9\.zip"/,
      ],
      ['a third name in SHA256SUMS', `${sumsText()}${'a'.repeat(64)}  other.zip\n`, /other\.zip/],
      [
        'a name given twice in SHA256SUMS',
        `${sumsText()}${SHA}  niord-fe-src-${VERSION}.zip\n`,
        /niord-fe-src-4\.0\.9\.zip twice/,
      ],
      [
        "SHA256SUMS of another version's zips",
        sumsText({ version: '4.0.8' }),
        /niord-fe-src-4\.0\.8\.zip/,
      ],
      [
        'a SHA256SUMS without the kit line',
        `${SHA}  niord-fe-src-${VERSION}.zip\n`,
        /no line for niord-country-kit-4\.0\.9\.zip/,
      ],
    ])
      it(what, () => {
        const root = repository();
        fs.writeFileSync(path.join(root, '.niord', 'SHA256SUMS'), text);
        assertRefused(root, () => run(root), pattern);
      });

    it('a missing source zip, and a missing kit zip', () => {
      const root = repository();
      fs.rmSync(path.join(root, '.niord', `niord-fe-src-${VERSION}.zip`));
      assertRefused(
        root,
        () => run(root),
        /^\.niord\/niord-fe-src-4\.0\.9\.zip does not exist \(nor fe\/\.niord\/niord-fe-src-4\.0\.9\.zip\): fetch the release's zip first$/,
      );
      const other = repository();
      fs.rmSync(path.join(other, '.niord', `niord-country-kit-${VERSION}.zip`));
      assertRefused(
        other,
        () => run(other),
        /^\.niord\/niord-country-kit-4\.0\.9\.zip does not exist: fetch the release's kit package first$/,
      );
    });

    it('a source zip that is not a zip', () => {
      const root = repository({
        zip: Buffer.from('not a zip at all, but its hash is in SHA256SUMS'),
      });
      assertRefused(root, () => run(root), /^\.niord\/niord-fe-src-4\.0\.9\.zip: is not a ZIP/);
    });

    it('a source zip without the default tokens', () => {
      const root = repository({
        zip: sourceZip(sourceZipNames().filter((name) => name !== 'styles/tokens.default.css')),
      });
      assertRefused(root, () => run(root), /the source zip has no styles\/tokens\.default\.css/);
    });

    it('an existing be/pom.xml of another parent version', () => {
      const root = repository();
      writeIn(
        root,
        'be/pom.xml',
        '<project><parent><groupId>org.niord</groupId><artifactId>niord-parent</artifactId><version>4.0.8</version></parent></project>',
      );
      assertRefused(
        root,
        () => run(root),
        /^be\/pom\.xml's parent is 4\.0\.8, but the kit at \.niord\/kit is 4\.0\.9: /,
      );
    });

    it("a --version that is not the kit package's", () => {
      const root = repository();
      assertRefused(
        root,
        () => run(root, { version: '4.0.8' }),
        /^--version 4\.0\.8 is not the kit's version: \.niord\/kit is 4\.0\.9 \(its kit\.json\); init writes the version of the kit it runs from$/,
      );
    });

    it("no --version in the base product's tree, and one that is not a version", () => {
      const root = repository();
      assertRefused(
        root,
        () => run(root, { kit: KIT }),
        /^the kit at .* is the base product's tree, which names no version: give --version <v>$/,
      );
      assertRefused(root, () => run(root, { kit: KIT, version: 'v4' }), /"v4" is not/);
    });

    it('a file in the way of a folder init writes into', () => {
      const root = repository();
      writeIn(root, 'be', 'a file where be/ goes');
      assertRefused(root, () => run(root), /^be is not a folder/);
    });

    it('an existing fe/niord-country.json that is not a manifest the kit reads', () => {
      const root = repository();
      writeIn(root, 'fe/niord-country.json', '{"broken": true}\n');
      assertRefused(
        root,
        () => run(root),
        /^fe\/niord-country\.json exists and is not a manifest the kit reads \(niord-country\.json breaks [\s\S]*broken[\s\S]*\): nothing was written$/,
      );
    });

    it("an existing fe/niord-country.json of another country than the answers'", () => {
      const root = repository();
      writeIn(root, 'fe/niord-country.json', manifestOf('xx'));
      assertRefused(
        root,
        () => run(root),
        /^fe\/niord-country\.json exists and names xx, not qq, the answers' country: nothing was written$/,
      );
    });
  });

  describe('the version', () => {
    it("is the kit package's, from its kit.json, and the same --version is taken", () => {
      assert.equal(kitVersion({ kit: SHARED_KIT }), VERSION);
      assert.equal(kitVersion({ kit: SHARED_KIT, version: VERSION }), VERSION);
      assert.throws(() => kitVersion({ kit: SHARED_KIT, version: '4.0.10' }), /is not the kit's/);
      const root = repository();
      assert.ok(run(root, { version: VERSION }).written.includes('be/pom.xml'));
    });

    it("is --version in the base product's tree", () => {
      assert.equal(kitVersion({ kit: KIT, version: '4.0.0-n2' }), '4.0.0-n2');
      assert.throws(() => kitVersion({ kit: KIT }), /names no version/);
    });

    it("writes the --version given when it runs from the base product's tree", () => {
      const version = '4.0.0-n2';
      const root = repository({ version });
      fs.writeFileSync(path.join(root, 'answers.json'), JSON.stringify(ANSWERS));
      const result = spawnSync(
        process.execPath,
        [
          path.join(KIT, 'init.mjs'),
          '--answers',
          'answers.json',
          '--sha256-file',
          '.niord/SHA256SUMS',
          '--version',
          version,
        ],
        { cwd: root, encoding: 'utf8' },
      );
      assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
      assert.equal(parentVersion(read(root, 'be/pom.xml')), version);
      assert.equal(JSON.parse(read(root, 'fe/niord-pins.lock.json')).base.version, version);
    });
  });

  describe("the release notes' two lines", () => {
    it('are read as sha256sum writes them, CRLF and blank lines taken', () => {
      assert.deepEqual(readSha256Lines(sumsText(), VERSION), { sha256: SHA, kitSha256: KIT_SHA });
      assert.deepEqual(readSha256Lines(`\r\n${sumsText().replaceAll('\n', '\r\n')}\r\n`, VERSION), {
        sha256: SHA,
        kitSha256: KIT_SHA,
      });
    });

    for (const [what, text, pattern] of [
      ['one space', `${SHA} niord-fe-src-${VERSION}.zip\n`, /"[0-9a-f]{64} niord-fe-src/],
      ['upper-case hex', sumsText({ sha256: 'A'.repeat(64) }), /"A{64} {2}niord-fe-src/],
      [
        'no source zip line',
        `${KIT_SHA}  niord-country-kit-${VERSION}.zip\n`,
        /no line for niord-fe-src-4\.0\.9\.zip/,
      ],
      ['nothing', '', /no line for niord-fe-src-4\.0\.9\.zip/],
    ])
      it(`refuse ${what}`, () => {
        assert.throws(() => readSha256Lines(text, VERSION), pattern);
      });
  });

  describe('the questions', () => {
    /** askAnswers over `lines`, with what it wrote. */
    const ask = async (lines, country) => {
      const output = new PassThrough();
      let written = '';
      output.on('data', (chunk) => (written += chunk));
      const answers = await askAnswers({
        input: Readable.from([`${lines.join('\n')}\n`]),
        output,
        country,
      });
      return { answers, written };
    };

    it('asks the 16 answers in their order, with the standard defaults in brackets', async () => {
      const { written } = await ask(DEFAULTED_LINES);
      const defaults = standardDefaults(ANSWERS);
      const prompts = [
        'Country code, ISO 3166-1 alpha-2 in lower case: ',
        'Country name, as the applications show it, at most 60 characters: ',
        'Organisation that runs the deployment: ',
        'Languages besides English, published packs, separated by spaces [none]: ',
        'S-124 producer code, IHO S-62, four capitals or digits [none]: ',
        'Time zone of the first domain and the public site, IANA (UTC or Area/Location): ',
        'Map centre latitude, whole degrees from -90 to 90: ',
        'Map centre longitude, whole degrees from -180 to 180: ',
        'Map zoom level, 1 to 18 [6]: ',
        "First domain's main type, NW or NM [NW]: ",
        `First domain's id [${defaults.domain.id}]: `,
        `First domain's name [${defaults.domain.name}]: `,
        `First message series' id [${defaults.series.id}]: `,
        `First message series' short format [${defaults.series.shortFormat}]: `,
        "First message series' numbering, YEARLY, CONTINUOUS, MANUAL or NONE [YEARLY]: ",
        'Repository owners for CODEOWNERS, @user or @org/team, separated by spaces: ',
      ];
      assert.equal(QUESTIONS.length, 16);
      assert.ok(Object.isFrozen(QUESTIONS));
      assert.ok(
        written.startsWith(
          `init: answer each question and press Enter; a value in [brackets] is the standard default, taken with Enter alone\n${prompts.join('')}\ninit: the answers, as --answers takes them:\n`,
        ),
        written,
      );
      assert.equal(defaults.domain.id, 'qq-nw');
      assert.equal(defaults.domain.name, 'Example Country QQ Navigational Warnings');
    });

    it('leaves a blank answer out, so readAnswers takes the default the prompt showed', async () => {
      const { answers, written } = await ask(DEFAULTED_LINES);
      assert.deepEqual(answers, ANSWERS);
      assert.ok(written.endsWith(`${JSON.stringify(ANSWERS, null, 2)}\n`), written);
    });

    it("splits the lists, reads whole numbers, keeps other text for the schema, and shows the main type's defaults", async () => {
      const lines = [
        'qq',
        '  Example Country QQ  ',
        'The Example Hydrographic Office',
        'da, fo  kl',
        'QQ01',
        'Europe/London',
        '50',
        '-20',
        '7.5',
        'NM',
        '',
        'QQ Notices',
        'qq-notices',
        '',
        'CONTINUOUS',
        '@example,@example/team',
      ];
      const { answers, written } = await ask(lines);
      assert.deepEqual(answers, {
        country: 'qq',
        name: 'Example Country QQ',
        organisation: 'The Example Hydrographic Office',
        languages: ['da', 'fo', 'kl'],
        producerCode: 'QQ01',
        timeZone: 'Europe/London',
        map: { lat: 50, lon: -20, zoom: '7.5' },
        domain: { mainType: 'NM', name: 'QQ Notices' },
        series: { id: 'qq-notices', numberSequenceType: 'CONTINUOUS' },
        owners: ['@example', '@example/team'],
      });
      assert.ok(written.includes("First domain's id [qq-nm]: "), written);
      assert.ok(
        written.includes(
          "First message series' short format [QQ-NM-${number-3-digits}-${year-2-digits}]: ",
        ),
        written,
      );
      assert.throws(() => readAnswers(answers), /^map\.zoom: /m);
    });

    it('skips the country question when the code came on the command line', async () => {
      const { answers, written } = await ask(DEFAULTED_LINES.slice(1), 'qq');
      assert.deepEqual(answers, ANSWERS);
      assert.ok(written.includes('init: country: qq, from the command line\n'), written);
      assert.ok(!written.includes('Country code, ISO'), written);
    });

    it('refuses answers that end before the last question', async () => {
      await assert.rejects(
        ask(DEFAULTED_LINES.slice(0, 5)),
        /^Error: the answers ended before the question timeZone: nothing was written$/,
      );
    });
  });

  describe('on the command line', () => {
    const SUMS = ['--sha256-file', '.niord/SHA256SUMS'];

    it('asks the questions on a pipe without a TTY and writes the standard defaults it showed', () => {
      const root = repository({ kit: true });
      const result = cli(root, SUMS, `${DEFAULTED_LINES.join('\n')}\n`);
      assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
      const defaults = standardDefaults(ANSWERS);
      assert.ok(result.stdout.includes(`First domain's id [${defaults.domain.id}]: `));
      const [domain] = JSON.parse(read(root, 'be/bootstrap/domains.json'));
      assert.equal(domain.domainId, defaults.domain.id);
      assert.equal(domain.name, defaults.domain.name);
      assert.equal(domain.zoomLevel, 6);
      assert.deepEqual(domain.messageSeries, [
        {
          seriesId: defaults.series.id,
          mainType: 'NW',
          shortFormat: defaults.series.shortFormat,
          numberSequenceType: 'YEARLY',
        },
      ]);
      assert.doesNotMatch(
        read(root, 'be/src/main/resources/application.yml'),
        /^\s*producer-code:/m,
      );
      assert.match(result.stdout, /^init: wrote be\/pom\.xml$/m);
      assert.match(result.stdout, /^init: {3}\(cd fe && node setup\.mjs\)$/m);
    });

    it('prints the answers as JSON that --answers takes, writing the same files', () => {
      const lines = [...DEFAULTED_LINES];
      lines[3] = 'da';
      lines[4] = 'QQ01';
      const asked = repository({ kit: true });
      const result = cli(asked, SUMS, `${lines.join('\n')}\n`);
      assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
      const json = /init: the answers, as --answers takes them:\n(\{[\s\S]*?\n\})\n/.exec(
        result.stdout,
      );
      assert.ok(json, result.stdout);
      const answers = JSON.parse(json[1]);
      assert.deepEqual(answers, { ...ANSWERS, languages: ['da'], producerCode: 'QQ01' });
      assert.match(result.stdout, /^init: {3}\(cd fe && node setup\.mjs --update-pins\)$/m);

      const filed = repository({ kit: true });
      fs.writeFileSync(path.join(filed, '.niord', 'init-answers.json'), json[1]);
      const again = cli(filed, ['--answers', '.niord/init-answers.json', ...SUMS]);
      assert.equal(again.status, 0, `${again.stdout}${again.stderr}`);
      const outside = (paths) =>
        Object.fromEntries(
          Object.entries(paths).filter(([relative]) => !relative.startsWith('.niord/')),
        );
      assert.deepEqual(outside(snapshot(filed)), outside(snapshot(asked)));
    });

    it('takes the code as the first argument and asks the other 15', () => {
      const root = repository({ kit: true });
      const result = cli(root, ['qq', ...SUMS], `${DEFAULTED_LINES.slice(1).join('\n')}\n`);
      assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
      assert.ok(result.stdout.includes('init: country: qq, from the command line'));
      assert.equal(readManifest(path.join(root, 'fe', 'niord-country.json')).country, 'qq');
    });

    it('exits 1, writing nothing, when the answers end before the last question', () => {
      const root = repository();
      const before = snapshot(root);
      const result = cli(root, SUMS, `${DEFAULTED_LINES.slice(0, 9).join('\n')}\n`, SHARED_KIT);
      assert.equal(result.status, 1, `${result.stdout}${result.stderr}`);
      assert.ok(
        result.stderr.includes(
          'init: the answers ended before the question domain.mainType: nothing was written',
        ),
        result.stderr,
      );
      assert.deepEqual(snapshot(root), before);
    });

    for (const [what, prepare, pattern] of [
      [
        'a missing source zip',
        (root) => fs.rmSync(path.join(root, '.niord', `niord-fe-src-${VERSION}.zip`)),
        /^init: \.niord\/niord-fe-src-4\.0\.9\.zip does not exist /m,
      ],
      [
        'a source zip of another hash than its line',
        (root) =>
          fs.writeFileSync(
            path.join(root, '.niord', 'SHA256SUMS'),
            sumsText({ sha256: 'a'.repeat(64) }),
          ),
        /^init: \.niord\/niord-fe-src-4\.0\.9\.zip has the SHA-256 [0-9a-f]{64}, not a{64}: nothing was written$/m,
      ],
    ])
      it(`exits 1, writing nothing, before the first question on ${what}`, () => {
        const root = repository();
        prepare(root);
        const before = snapshot(root);
        const result = cli(root, SUMS, 'qq\n', SHARED_KIT);
        assert.equal(result.status, 1, `${result.stdout}${result.stderr}`);
        assert.match(result.stderr, pattern);
        assert.equal(result.stdout, '', 'a question was asked before the release was checked');
        assert.deepEqual(snapshot(root), before);
      });

    it('exits 1, writing nothing, on asked answers it refuses', () => {
      const root = repository();
      const lines = [...DEFAULTED_LINES];
      lines[5] = 'europe/london';
      const before = snapshot(root);
      const result = cli(root, SUMS, `${lines.join('\n')}\n`, SHARED_KIT);
      assert.equal(result.status, 1, `${result.stdout}${result.stderr}`);
      assert.match(result.stderr, /^init: the answers are refused: nothing was written$/m);
      assert.match(result.stderr, /^timeZone: /m);
      assert.deepEqual(snapshot(root), before);
    });

    it('fills a missing country of the answers file from the code, and refuses another code', () => {
      const root = repository();
      const { country, ...rest } = ANSWERS;
      assert.equal(country, 'qq');
      fs.writeFileSync(path.join(root, 'answers.json'), JSON.stringify(rest));
      fs.writeFileSync(path.join(root, 'qq.json'), `\uFEFF${JSON.stringify(ANSWERS)}`);
      const before = snapshot(root);
      const other = cli(root, ['xx', '--answers', 'qq.json', ...SUMS], '', SHARED_KIT);
      assert.equal(other.status, 1, `${other.stdout}${other.stderr}`);
      assert.ok(
        other.stderr.includes(
          "init: the code on the command line, xx, is not the answers file's country, qq",
        ),
        other.stderr,
      );
      assert.deepEqual(snapshot(root), before);
      const filled = cli(root, ['qq', '--answers', 'answers.json', ...SUMS], '', SHARED_KIT);
      assert.equal(filled.status, 0, `${filled.stdout}${filled.stderr}`);
      assert.equal(readManifest(path.join(root, 'fe', 'niord-country.json')).country, 'qq');
    });

    for (const [what, args, file, pattern] of [
      [
        'a missing answers file',
        ['--answers', 'none.json'],
        undefined,
        /^init: none\.json cannot be read/m,
      ],
      [
        'an answers file that is not JSON',
        ['--answers', 'bad.json'],
        '{ country: qq',
        /^init: bad\.json is not JSON \(/m,
      ],
      [
        'a code that is not one',
        ['Norway'],
        undefined,
        /^init: "Norway" is not a country code, its ISO 3166-1 alpha-2 code, two letters a-z$/m,
      ],
      ['a three-letter code', ['xxx'], undefined, /^init: "xxx" is not a country code/m],
    ])
      it(`exits 1, writing nothing, on ${what}`, () => {
        const root = repository();
        if (file !== undefined) fs.writeFileSync(path.join(root, 'bad.json'), file);
        const before = snapshot(root);
        const result = cli(root, [...args, ...SUMS], '', SHARED_KIT);
        assert.equal(result.status, 1, `${result.stdout}${result.stderr}`);
        assert.match(result.stderr, pattern);
        assert.deepEqual(snapshot(root), before);
      });

    it('exits 2 on an unknown argument and on a flag without its value, naming its usage', () => {
      const root = repository();
      const usage =
        "(init [<cc>] [--answers <file>] --sha256-file <file> | --zip-sha256 <fe-sha256> --kit-sha256 <kit-sha256> [--version <v>, the base product's tree only])";
      for (const [args, argument] of [
        [['--zip-sha256', SHA, '--kit-sha256'], '--kit-sha256'],
        [['--answers', '--sha256-file', '.niord/SHA256SUMS'], '--answers'],
        [['qq', 'qq'], 'qq'],
        [['--frontend-only'], '--frontend-only'],
      ]) {
        const result = cli(root, args, '', SHARED_KIT);
        assert.equal(result.status, 2, `${args.join(' ')}: ${result.stderr}`);
        assert.ok(
          result.stderr.includes(`init: unknown argument "${argument}" ${usage}`),
          result.stderr,
        );
      }
      assert.equal(exists(root, 'fe/setup.mjs'), false);
    });

    it('refuses a kit zip of another hash given in flag form', () => {
      const root = repository();
      fs.writeFileSync(path.join(root, 'answers.json'), JSON.stringify(ANSWERS));
      const other = cli(
        root,
        ['--answers', 'answers.json', '--zip-sha256', SHA, '--kit-sha256', 'e'.repeat(64)],
        '',
        SHARED_KIT,
      );
      assert.equal(other.status, 1, other.stderr);
      assert.match(other.stderr, /niord-country-kit-4\.0\.9\.zip has the SHA-256 /);
    });
  });

  it(
    'gives start-local.sh, be/mvnw and the group script their exec bits, and no other file',
    {
      skip: process.platform === 'win32' && 'Windows has no exec bits; the closing lines say so',
    },
    () => {
      const root = repository();
      const { written } = run(root);
      const executable = ['be/mvnw', 'be/scripts/check-country-build-group.sh', 'start-local.sh'];
      for (const relative of written)
        assert.equal(
          fs.statSync(path.join(root, relative)).mode & 0o111,
          executable.includes(relative) ? 0o111 : 0,
          relative,
        );
    },
  );

  describe('the closing lines', () => {
    const NEXT = [
      'init: next, from the repository root:',
      'init:   (cd fe && node setup.mjs)',
      'init:   ./start-local.sh',
      "init: ./start-local.sh --sample starts the base product's sample instead (the kit's README, Running it locally)",
      'init: on GitHub, install the Niord base bump app on this repository once, so each base version arrives as a pull request: https://github.com/apps/niord-base-bump (the country guide, Upgrades)',
    ];
    const WINDOWS = [
      'init: on Windows, give the scripts their exec bits in the first commit: git add -A && git add --chmod=+x start-local.sh be/mvnw be/scripts/check-country-build-group.sh',
      'init: on Windows, before be/mvnw verify: export NIORD_HOME=$(cygpath -m "$TEMP")/niord',
    ];
    const lines = (platform, answers = ANSWERS) => {
      const out = [];
      run(repository(), { platform, answers, log: (line) => out.push(line) });
      return out;
    };

    it("name the next two commands and the app's install link, and on Windows the exec bits and NIORD_HOME", () => {
      assert.deepEqual(lines('linux').slice(-NEXT.length), NEXT);
      assert.deepEqual(lines('win32').slice(-NEXT.length - WINDOWS.length), [...NEXT, ...WINDOWS]);
      assert.ok(!lines('darwin').some((line) => line.includes('on Windows')));
    });

    it('update the pins in the first set-up when languages were answered', () => {
      const out = lines('linux', { ...ANSWERS, languages: ['da'] });
      assert.ok(out.includes('init:   (cd fe && node setup.mjs --update-pins)'), out.join('\n'));
    });
  });

  it('writes no README.md into be/bootstrap/releases/, and the bootstrap README says what a release folder holds', () => {
    const root = repository();
    const { written } = run(root);
    // niord-base's bootstrap archive accepts only releases/<folder>/*.json|*.md|*.sql there.
    assert.ok(!written.some((file) => file.startsWith('be/bootstrap/releases/')));
    assert.equal(exists(root, 'be/bootstrap/releases'), false);
    const readme = read(root, 'be/bootstrap/README.md');
    assert.match(readme, /releases\/<yyyy-mm-dd>-<topic>\//);
    assert.match(readme, /a sysadmin decides to import/);
    assert.match(readme, /Nothing here arrives by itself/);
    assert.match(readme, /that folder's own `README\.md`/);
  });

  it("writes fe/.gitattributes and .gitattributes with the base product's rules but the hooks', and Windows command scripts CRLF", () => {
    const rules = (text) =>
      text
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter((line) => line && !line.startsWith('#'));
    const base = rules(read(FE, '.gitattributes'));
    assert.ok(base.includes('.husky/* text eol=lf'), "the base product's hooks rule moved");
    // The one rule more comes right after the LF rule, since a later attribute line wins.
    const expected = [
      base[0],
      '*.cmd text eol=crlf',
      ...base.slice(1).filter((line) => !line.startsWith('.husky/')),
    ];
    const root = repository();
    run(root);
    assert.deepEqual(rules(read(root, 'fe/.gitattributes')), expected);
    assert.deepEqual(rules(read(root, '.gitattributes')), expected);
    assert.equal(rules(read(root, 'fe/.gitattributes'))[0], '* text=auto eol=lf');

    const own = repository();
    writeIn(own, 'fe/.gitattributes', '* text=auto\n');
    assert.ok(run(own).kept.includes('fe/.gitattributes'));
    assert.equal(read(own, 'fe/.gitattributes'), '* text=auto\n');
  });

  it('writes pack-releases.md, the record of the packs published and withdrawn, and owns it in CODEOWNERS', () => {
    const root = repository();
    run(root);
    const record = read(root, 'pack-releases.md');
    assert.match(record, /^\| Pack \| Tag \| Digest \| Date \|$/m);
    assert.match(record, /^\| Pack \| Tag \| Digest \| Date \| Reason \|$/m);
    const codeowners = read(root, '.github/CODEOWNERS');
    for (const owned of [
      '/fe/build-urls-allowed.json',
      '/fe/admin/branding-unaccepted.json',
      '/fe/info/branding-unaccepted.json',
      '/fe/packs/',
      '/pack-releases.md',
      '/.github/workflows/pack.yml',
      '/.github/workflows/base-bump.yml',
    ])
      assert.match(
        codeowners,
        new RegExp(`^${owned.replace(/[.*/]/g, '\\$&')} +@example$`, 'm'),
        owned,
      );
    assert.doesNotMatch(codeowners, /renovate/);
  });

  it('watches the country dependencies for security updates only, and writes none of their files', () => {
    const root = repository();
    run(root);
    const dependabot = read(root, '.github/dependabot.yml');
    const npm = /^ {2}- package-ecosystem: npm\n((?: {4}.*\n)+)/m.exec(dependabot);
    assert.ok(npm, 'dependabot.yml has no npm entry');
    assert.match(npm[1], /^ {4}directory: \/fe\/country$/m);
    assert.match(npm[1], /^ {4}open-pull-requests-limit: 0$/m);
    assert.doesNotMatch(dependabot, /package-ecosystem: maven/);
    // Absent counts as empty: no country package, no text override, no accepted audit finding.
    for (const absent of ['fe/country', 'fe/text-overrides', 'fe/audit-accepted.json'])
      assert.equal(exists(root, absent), false, absent);
    const codeowners = read(root, '.github/CODEOWNERS');
    for (const owned of ['/fe/country/', '/fe/text-overrides/', '/fe/audit-accepted.json'])
      assert.ok(codeowners.includes(owned), owned);
  });

  it('writes the stub byte for byte as the template, and a manifest the kit reads', () => {
    const root = repository();
    run(root);
    assert.ok(
      fs
        .readFileSync(path.join(root, 'fe', 'setup.mjs'))
        .equals(fs.readFileSync(path.join(KIT, 'templates', 'setup.mjs'))),
    );
    const manifest = readManifest(path.join(root, 'fe', 'niord-country.json'));
    assert.equal(manifest.country, 'qq');
    assert.deepEqual(manifest.apps.info.uiLanguages, ['en']);
  });

  it('writes AGENTS.md byte for byte as the agent template, and a CLAUDE.md that imports it', () => {
    const root = repository();
    run(root);
    assert.ok(
      fs
        .readFileSync(path.join(root, 'AGENTS.md'))
        .equals(fs.readFileSync(path.join(KIT, 'templates', 'AGENTS.md'))),
    );
    assert.equal(read(root, 'CLAUDE.md'), '@AGENTS.md\n');
  });

  it('reads the templates and the kit data from the kit it is given, the defaults from the source it is given', () => {
    const kit = path.join(temp('niord-init-kit-'), 'kit');
    fs.cpSync(path.join(KIT, 'templates'), path.join(kit, 'templates'), { recursive: true });
    for (const name of ['branding.schema.json', 'workspace-files.json'])
      fs.copyFileSync(path.join(KIT, name), path.join(kit, name));
    fs.writeFileSync(path.join(kit, 'templates', 'AGENTS.md'), '# The given kit\n');
    const files = skeleton({
      cc: 'qq',
      version: VERSION,
      sha256: SHA,
      source: folderSource(FE),
      kit,
    });
    assert.equal(files.get('AGENTS.md')?.toString('utf8'), '# The given kit\n');
    assert.equal(files.get('CLAUDE.md').toString('utf8'), '@AGENTS.md\n');
    assert.ok(
      files
        .get('fe/styles/tokens.qq.css')
        .equals(fs.readFileSync(path.join(FE, 'styles', 'tokens.default.css'))),
    );
    // The same bytes from the zip as from the folder it was made of.
    const zipped = skeleton({
      cc: 'qq',
      version: VERSION,
      sha256: SHA,
      source: zipSource(readZip(ZIP)),
      kit,
    });
    assert.deepEqual([...zipped.keys()], [...files.keys()]);
    for (const [relative, data] of files) assert.ok(zipped.get(relative).equals(data), relative);
    assert.throws(
      () => skeleton({ cc: 'qq', version: VERSION, sha256: SHA, source: () => undefined, kit }),
      /the source zip has no languages\/en\/i18n\/admin\/en\.json/,
    );
  });

  it('writes branding the branding check passes, the tokens and the ignore list', () => {
    const root = repository();
    run(root);
    for (const app of ['admin', 'info'])
      assert.deepEqual(
        checkBranding({ app, dir: path.join(root, 'fe', app, 'assets'), uiLanguages: ['en'] })
          .errors,
        [],
        app,
      );
    assert.equal(read(root, 'fe/styles/tokens.qq.css'), read(FE, 'styles/tokens.default.css'));
    assert.equal(read(root, 'fe/.gitignore'), gitignoreText());
    assert.match(read(root, 'fe/.gitignore'), /^\/\.niord\/$/m);
    // The generated Playwright configuration and what a run of `npm run e2e` leaves behind.
    for (const line of ['/playwright.config.ts', '/test-results/', '/playwright-report/'])
      assert.ok(read(root, 'fe/.gitignore').split('\n').includes(line), `${line} is not ignored`);
  });

  it("starts the example's ignore list with init's", () => {
    assert.ok(read(KIT, 'example/fe/.gitignore').startsWith(gitignoreText()));
  });

  it('writes image.yml with the frontend job, the backend job and the push job that needs both', () => {
    const image = imageText();
    assert.ok(image.includes(`jobs:\n${frontendJob()}\n${backendJob()}\n  push:\n`));
    const { jobs } = yaml.load(image);
    assert.deepEqual(Object.keys(jobs), ['frontend', 'backend', 'push']);
    assert.deepEqual(jobs.push.needs, ['frontend', 'backend']);
    assert.equal(jobs.push['timeout-minutes'], 20);
    assert.deepEqual(
      jobs.push.steps
        .filter((step) => step.uses?.startsWith('actions/download-artifact@'))
        .map((step) => step.with.name),
      ['frontend-dist', 'info-image', 'backend-dist'],
    );
    const verify = stepRun(image, { job: 'push', step: 'Verify the three artifacts' });
    for (const artifact of ['frontend-dist.tar', 'info-image.tar', 'backend-dist.tar'])
      assert.ok(verify.includes(`sha256sum -c ${artifact}.sha256\n`), artifact);
    const frontend = jobText(image, 'frontend');
    const push = jobText(image, 'push');
    assert.match(
      frontend,
      /IMAGE="\$REGISTRY\/\$HARBOR_PROJECT\/\$\{name,,\}-info:\$BASE_VERSION\.\$GITHUB_RUN_NUMBER"/,
    );
    assert.doesNotMatch(frontend, /github\.event\.repository\.name/);
    assert.match(frontend, /npm run image:info -- --tag "\$IMAGE"/);
    assert.match(frontend, /echo "ref=\$IMAGE" >> "\$GITHUB_OUTPUT"/);
    assert.match(frontend, /docker save "\$IMAGE" -o info-image\.tar/);
    assert.match(frontend, /sha256sum info-image\.tar \| tee info-image\.tar\.sha256/);
    assert.match(frontend, /info-image: \$\{\{ steps\.image\.outputs\.ref \}\}/);
    assert.match(push, /sha256sum -c info-image\.tar\.sha256/);
    assert.match(push, /docker load -i info-image\.tar/);
    assert.match(push, /--password-stdin/);
    assert.match(push, /docker login "\$REGISTRY" /);
    assert.doesNotMatch(push, /IMAGE%%/);
    assert.match(push, /docker push "\$IMAGE"/);
    assert.doesNotMatch(push, /\bnpm\b|\bnpx\b|setup\.mjs|country-kit/);
  });

  it('binds the workflow token on the steps that run Maven, and on nothing else', () => {
    const root = repository();
    run(root);
    const token = { GITHUB_TOKEN: '${{ github.token }}' };
    for (const [file, jobs] of [
      [
        '.github/workflows/image.yml',
        [
          ['frontend', ['Set up the workspace'], /^set -euo pipefail\nnode setup\.mjs\n$/],
          [
            'backend',
            ['Set up the workspace', 'Build the jar', 'Fetch the image tools'],
            /^set -euo pipefail\nnode setup\.mjs\n$/,
          ],
        ],
      ],
      [
        '.github/workflows/pack.yml',
        [
          [
            'validate',
            ['Set up the workspace', 'Fetch the latest base product catalog'],
            /^set -euo pipefail\nnode setup\.mjs\n$/,
          ],
        ],
      ],
    ]) {
      const text = read(root, file);
      const workflow = yaml.load(text);
      for (const [job, steps, setup] of jobs) {
        for (const step of steps)
          assert.deepEqual(stepEnv(text, { job, step }), token, `${file}: ${job}: ${step}`);
        // The run as YAML reads it, a one-line run with its line's end as stepRun gives it: stepRun
        // would also take the comment above the next step into a `run: |` block.
        const { run: setupRun } = workflow.jobs[job].steps.find(
          (step) => step.name === 'Set up the workspace',
        );
        assert.match(
          setupRun.endsWith('\n') ? setupRun : `${setupRun}\n`,
          setup,
          `${file}: ${job}`,
        );
      }
      // Only there: no other step, no job and no workflow env holds it.
      assert.equal(
        text.match(/^\s*GITHUB_TOKEN:/gm)?.length,
        jobs.reduce((sum, [, steps]) => sum + steps.length, 0),
        file,
      );
    }
  });

  it('builds the jar in a backend job that runs set-up before Maven and holds no secret', () => {
    const image = imageText();
    const { backend } = yaml.load(image).jobs;
    assert.deepEqual(
      backend.steps.map((step) => step.name ?? step.uses),
      [
        'actions/checkout@v4',
        'Set up JDK 25 (Temurin) + GitHub Packages credentials',
        'Maven repository cache',
        'Read the base version',
        'actions/setup-node@v4',
        'Set up the workspace',
        'Build the jar',
        'Fetch the image tools',
        'Override check',
        'Split the jar',
        'Pack the split jar and the image tools with their SHA-256',
        'actions/upload-artifact@v4',
      ],
    );
    const step = (name) => backend.steps.find((entry) => entry.name === name);
    assert.deepEqual(backend.steps[0].with, { 'persist-credentials': false });
    assert.match(
      step('Set up JDK 25 (Temurin) + GitHub Packages credentials').uses,
      /^actions\/setup-java@/,
    );
    assert.match(step('Maven repository cache').uses, /^actions\/cache@/);
    assert.equal(step('Read the base version').id, 'base');
    // No npm runs in this job, so a cache would have nothing to save.
    assert.deepEqual(backend.steps[4].with, { 'node-version': '24' });
    assert.equal(step('Set up the workspace')['working-directory'], 'fe');
    assert.deepEqual(backend.steps.at(-1).with.name, 'backend-dist');
    assert.equal(
      backend.steps.at(-1).with.path,
      'be/backend-dist.tar\nbe/backend-dist.tar.sha256\n',
    );
    assert.deepEqual(backend.permissions, { contents: 'read', packages: 'read' });
    assert.equal(backend.environment, undefined);
    assert.equal(backend.if, undefined);
    assert.deepEqual(backend.defaults, { run: { 'working-directory': 'be' } });
    assert.deepEqual(backend.outputs, { version: '${{ steps.base.outputs.version }}' });
    assert.doesNotMatch(jobText(image, 'backend'), /secrets\./);
  });

  it('starts every run block of image.yml strict, one-liners included, and puts no expression in any', () => {
    const { jobs } = yaml.load(imageText());
    for (const [name, job] of Object.entries(jobs))
      for (const step of job.steps.filter((entry) => entry.run !== undefined)) {
        assert.ok(step.run.startsWith('set -euo pipefail\n'), `${name}: ${step.name}`);
        assert.ok(!step.run.includes('${{'), `${name}: ${step.name}`);
      }
  });

  it("names the registry and the project in the workflow's env, empty until the country sets them, and the deployable from the code", () => {
    const root = repository();
    run(root);
    assert.match(
      read(root, '.github/workflows/image.yml'),
      /^env:\n {2}REGISTRY: ''\n {2}HARBOR_PROJECT: ''\n/m,
    );
    assert.deepEqual(yaml.load(imageText()).env, {
      REGISTRY: '',
      HARBOR_PROJECT: '',
      MODULE: 'niord-qq',
      MVN_FLAGS: '-B -ntp -e',
      MAVEN_OPTS: '-Xmx2g',
    });
  });

  describe("the backend job's base version", () => {
    const step = () => stepRun(imageText(), { job: 'backend', step: 'Read the base version' });
    const readBase = (version, env = {}) => {
      const cwd = temp('niord-init-be-');
      fs.writeFileSync(
        path.join(cwd, 'pom.xml'),
        [
          '<project>',
          '  <parent>',
          '    <groupId>org.niord</groupId>',
          '    <artifactId>niord-parent</artifactId>',
          `    <version>${version}</version>`,
          '  </parent>',
          '  <artifactId>niord-xx</artifactId>',
          '</project>',
          '',
        ].join('\n'),
      );
      return runBash(step(), {
        cwd,
        env: { MODULE: 'niord-xx', GITHUB_RUN_NUMBER: '7', ...env },
      });
    };

    it("reads the parent's version and makes the run's version of it", () => {
      const result = readBase('4.0.97');
      assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
      assert.deepEqual(result.outputs, { base: '4.0.97', version: '4.0.97.7' });
    });

    it('refuses a parent that is not a 4.0.<n> release', () => {
      const result = readBase('4.0.0-example');
      assert.equal(result.status, 1, `${result.stdout}${result.stderr}`);
      assert.match(result.stdout, /::error::no 4\.0\.<run> base version/);
      assert.deepEqual(result.outputs, {});
    });

    it('refuses an empty MODULE, before anything else', () => {
      const result = readBase('4.0.97', { MODULE: '' });
      assert.equal(result.status, 1, `${result.stdout}${result.stderr}`);
      assert.match(result.stdout, /::error::MODULE is not set/);
      assert.deepEqual(result.outputs, {});
    });
  });

  describe("the push job's version check", () => {
    const step = () => stepRun(imageText(), { job: 'push', step: 'Check the version' });
    const check = (version, image) => runBash(step(), { env: { VERSION: version, IMAGE: image } });

    it("accepts the backend job's version when the public-site image carries it", () => {
      const result = check('4.0.97.7', 'reg.example/p/x-info:4.0.97.7');
      assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
    });

    for (const [what, version, image] of [
      ['a public-site image of another tag', '4.0.97.7', 'reg.example/p/x-info:4.0.97.8'],
      ['a version not of the 4.0.<base>.<run> shape', 'latest', 'reg.example/p/x-info:latest'],
    ])
      it(`refuses ${what}`, () => {
        const result = check(version, image);
        assert.equal(result.status, 1, `${result.stdout}${result.stderr}`);
        assert.match(result.stdout, /::error::.*nothing is pushed/);
      });
  });

  describe("the push job's push step", () => {
    const step = () =>
      stepRun(imageText(), {
        job: 'push',
        step: 'Push the public-site image',
      });
    const push = (image, env = {}) =>
      runBash(step(), {
        env: {
          REGISTRY: 'reg.example',
          HARBOR_PROJECT: 'p',
          GITHUB_REPOSITORY: 'Org/X',
          HARBOR_ROBOT_USER: 'robot',
          HARBOR_ROBOT_PASSWORD: 'secret',
          IMAGE: image,
          ...env,
        },
        fakes: { docker: [{ match: '*', out: '' }] },
      });

    for (const [what, image, env] of [
      ['on another host', 'evil.example/p/x-info:4.0.1.7'],
      ['under another name', 'reg.example/p/other-info:4.0.1.7'],
      ['in another project', 'reg.example/q/x-info:4.0.1.7'],
      ['with a tag that is not a version', 'reg.example/p/x-info:latest'],
      ['while the registry is not set', 'reg.example/p/x-info:4.0.1.7', { REGISTRY: '' }],
    ])
      it(`refuses an image ${what}, before Docker is called`, () => {
        const result = push(image, env);
        assert.equal(result.status, 1, `${result.stdout}${result.stderr}`);
        assert.match(result.stdout, /::error::.*nothing is pushed/);
        assert.deepEqual(result.calls, []);
      });

    it('loads, logs in to the registry of the workflow and pushes the expected image', () => {
      const result = push('reg.example/p/x-info:4.0.1.7');
      assert.equal(result.status, 0, `${result.stdout}${result.stderr}`);
      assert.deepEqual(result.calls, [
        'docker load -i info-image.tar',
        'docker login reg.example -u robot --password-stdin',
        'docker push reg.example/p/x-info:4.0.1.7',
      ]);
    });
  });

  it("writes pack.yml as the kit's template, whose jobs pack-workflow.test.mjs holds to their rules", () => {
    const root = repository();
    run(root);
    assert.equal(read(root, '.github/workflows/pack.yml'), read(KIT, 'templates/pack.yml'));
    const pack = read(root, '.github/workflows/pack.yml');
    assert.match(stepRun(pack, { job: 'publish', step: 'Push the archive' }), /oci\.mjs publish/);
    assert.match(
      stepRun(pack, { job: 'withdraw', step: 'Add the tag to the withdrawn marker' }),
      /oci\.mjs withdraw/,
    );
  });

  it("writes base-bump.yml as the kit's template, whose jobs base-bump-workflow.test.mjs holds to their rules", () => {
    const root = repository();
    run(root);
    const text = read(root, '.github/workflows/base-bump.yml');
    assert.equal(text, read(KIT, 'templates/base-bump.yml'));
    const { jobs } = yaml.load(text);
    assert.deepEqual(Object.keys(jobs), ['compute', 'apply', 'recount']);
    // The run as YAML reads it, which is what the runner executes.
    const push = jobs.apply.steps.find((step) => step.name === 'Commit and push the pins');
    assert.ok(push, 'apply has no step Commit and push the pins');
    assert.ok(push.run.includes('git push -q origin "HEAD:refs/heads/$HEAD_REF"'), push.run);
  });

  it('refuses an image template that has lost a job line', () => {
    const template = read(KIT, 'templates/image.yml');
    for (const [job, line] of [
      ['frontend', '  # The frontend job: written here by init from image-frontend-job.yml.\n'],
      ['backend', '  # The backend job: written here by init from image-backend-job.yml.\n'],
    ]) {
      const kit = temp('niord-init-kit-');
      fs.mkdirSync(path.join(kit, 'templates'));
      fs.writeFileSync(path.join(kit, 'templates', 'image.yml'), template.replace(line, ''));
      assert.throws(() => imageWorkflow(kit), new RegExp(`lost its ${job}-job line`), job);
    }
  });

  it('never overwrites a file, and prints both build jobs when image.yml exists', () => {
    const root = repository();
    fs.writeFileSync(path.join(root, 'CLAUDE.md'), "the country's own");
    writeIn(root, '.github/workflows/image.yml', 'name: Image\n');
    const lines = [];
    const { kept } = run(root, { log: (line) => lines.push(line) });
    assert.deepEqual([...kept].sort(), ['.github/workflows/image.yml', 'CLAUDE.md']);
    assert.equal(read(root, 'CLAUDE.md'), "the country's own");
    assert.equal(read(root, '.github/workflows/image.yml'), 'name: Image\n');
    assert.ok(
      lines.some((line) => line.includes('add this frontend job') && line.includes('frontend:')),
    );
    assert.ok(
      lines.some((line) => line.includes('add this backend job') && line.includes('backend:')),
    );
    assert.ok(
      lines.some(
        (line) =>
          line.includes('.niord/kit/templates/image.yml') &&
          line.includes('backend-dist') &&
          line.includes('Check the version'),
      ),
    );
    assert.deepEqual(run(root).written, []);
  });

  it('keeps an existing base-bump.yml and prints that it is to be compared with the template', () => {
    const root = repository();
    const own = "name: Base bump\r\n# the country's own\r\n";
    writeIn(root, '.github/workflows/base-bump.yml', own);
    const lines = [];
    const { written, kept } = run(root, { log: (line) => lines.push(line) });
    assert.ok(kept.includes('.github/workflows/base-bump.yml'));
    assert.ok(!written.includes('.github/workflows/base-bump.yml'));
    assert.equal(read(root, '.github/workflows/base-bump.yml'), own);
    assert.ok(
      lines.includes(
        'init: .github/workflows/base-bump.yml exists; compare it with .niord/kit/templates/base-bump.yml (a base bump pull request lists a difference as an item)',
      ),
      lines.join('\n'),
    );

    // A new repository gets the template, so there is nothing to compare.
    const fresh = [];
    run(repository(), { log: (line) => fresh.push(line) });
    assert.ok(!fresh.some((line) => line.includes('compare it with')), fresh.join('\n'));
  });

  describe('the notice that base-bump.yml dispatches ci.yml, which must take workflow_dispatch', () => {
    const NOTICE =
      'init: .github/workflows/ci.yml exists without a workflow_dispatch: trigger; base-bump.yml dispatches it on a re-pinned bump branch, so add workflow_dispatch: to its on:';
    /** init's lines in a new repository holding `ci` as its ci.yml, or none when it is undefined. */
    const linesWith = (ci) => {
      const root = repository();
      if (ci !== undefined) writeIn(root, '.github/workflows/ci.yml', ci);
      const lines = [];
      const { written, kept } = run(root, { log: (line) => lines.push(line) });
      if (ci === undefined) {
        // init writes the kit's ci.yml, which takes workflow_dispatch.
        assert.ok(written.includes('.github/workflows/ci.yml'));
        assert.match(read(root, '.github/workflows/ci.yml'), /^\s*workflow_dispatch:/m);
      } else {
        assert.ok(kept.includes('.github/workflows/ci.yml'));
        assert.equal(read(root, '.github/workflows/ci.yml'), ci);
      }
      return lines;
    };

    it('is printed when a kept ci.yml has no workflow_dispatch trigger', () => {
      const lines = linesWith(
        'name: CI\non:\n  pull_request:\n  push:\n    branches: [main]\n# workflow_dispatch is wanted\n',
      );
      assert.equal(lines.filter((line) => line === NOTICE).length, 1, lines.join('\n'));
    });

    for (const [what, ci] of [
      ['init writes ci.yml', undefined],
      [
        'a kept ci.yml has a workflow_dispatch: trigger, in LF',
        'name: CI\non:\n  pull_request:\n  workflow_dispatch:\n',
      ],
      [
        'a kept ci.yml has a workflow_dispatch: trigger, in CRLF',
        'name: CI\r\non:\r\n  pull_request:\r\n  workflow_dispatch:\r\n',
      ],
    ])
      it(`is not printed when ${what}`, () => {
        const lines = linesWith(ci);
        assert.ok(!lines.some((line) => line.includes('workflow_dispatch')), lines.join('\n'));
      });
  });

  it("keeps a country's own CLAUDE.md, writes AGENTS.md beside it, and prints that CLAUDE.md must import it", () => {
    const imports = (line) => line.includes('CLAUDE.md') && line.includes('@AGENTS.md');
    const root = repository();
    fs.writeFileSync(path.join(root, 'CLAUDE.md'), "the country's own");
    const lines = [];
    const { written, kept } = run(root, { log: (line) => lines.push(line) });
    assert.ok(kept.includes('CLAUDE.md'));
    assert.ok(written.includes('AGENTS.md'));
    assert.equal(read(root, 'CLAUDE.md'), "the country's own");
    assert.ok(lines.some(imports), lines.join('\n'));

    // A CLAUDE.md that already imports AGENTS.md needs no instruction.
    const importing = repository();
    fs.writeFileSync(path.join(importing, 'CLAUDE.md'), '# Own\n@AGENTS.md\n');
    const quiet = [];
    assert.ok(run(importing, { log: (line) => quiet.push(line) }).kept.includes('CLAUDE.md'));
    assert.ok(!quiet.some(imports), quiet.join('\n'));

    // An AGENTS.md that exists is kept, and the one-line CLAUDE.md is written beside it.
    const agents = repository();
    fs.writeFileSync(path.join(agents, 'AGENTS.md'), "# The country's own contract\n");
    const result = run(agents);
    assert.ok(result.kept.includes('AGENTS.md'));
    assert.ok(result.written.includes('CLAUDE.md'));
    assert.equal(read(agents, 'AGENTS.md'), "# The country's own contract\n");
    assert.equal(read(agents, 'CLAUDE.md'), '@AGENTS.md\n');
  });

  describe('the country launcher', () => {
    /** The lines of a .gitignore that are rules: no comment, no empty line. */
    const rules = (text) =>
      text
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter((line) => line && !line.startsWith('#'));

    it('writes start-local.sh byte for byte as the template, executable off Windows', () => {
      const root = repository();
      run(root);
      const file = path.join(root, 'start-local.sh');
      assert.ok(
        fs
          .readFileSync(file)
          .equals(fs.readFileSync(path.join(KIT, 'templates', 'start-local.sh'))),
      );
      if (process.platform !== 'win32')
        assert.equal(fs.statSync(file).mode & 0o111, 0o111, 'start-local.sh has no exec bits');
    });

    it('keeps an existing start-local.sh', () => {
      const root = repository();
      fs.writeFileSync(path.join(root, 'start-local.sh'), "#!/bin/sh\necho the country's own\n");
      const { kept } = run(root);
      assert.ok(kept.includes('start-local.sh'));
      assert.equal(read(root, 'start-local.sh'), "#!/bin/sh\necho the country's own\n");
    });

    it('writes a root .gitignore that ignores .local-run/ into a new repository', () => {
      const root = repository();
      const { written } = run(root);
      assert.ok(written.includes('.gitignore'));
      assert.deepEqual(rules(read(root, '.gitignore')), ['/.local-run/']);
    });

    for (const [what, text] of [
      ['ending in a newline', 'be/target/\n.idea/\n'],
      ['without a final newline', 'be/target/\n.idea/'],
    ])
      it(`adds /.local-run/ once to an existing root .gitignore ${what}, keeping its content`, () => {
        const root = repository();
        fs.writeFileSync(path.join(root, '.gitignore'), text);
        const lines = [];
        const { written, kept, changed } = run(root, { log: (line) => lines.push(line) });
        assert.ok(!written.includes('.gitignore') && !kept.includes('.gitignore'));
        assert.deepEqual(changed, ['.gitignore']);
        const after = read(root, '.gitignore');
        assert.ok(after.startsWith(text.endsWith('\n') ? text : `${text}\n`), after);
        assert.deepEqual(rules(after), ['be/target/', '.idea/', '/.local-run/']);
        assert.ok(lines.includes('init: added /.local-run/ to .gitignore'), lines.join('\n'));
        const again = run(root);
        assert.deepEqual(again.changed, []);
        assert.ok(again.kept.includes('.gitignore'));
        assert.equal(read(root, '.gitignore'), after);
      });

    for (const [what, text] of [
      ['ending in CRLF', 'be/target/\r\n.idea/\r\n'],
      ['in CRLF without a final line ending', 'be/target/\r\n.idea/'],
    ])
      it(`adds /.local-run/ in CRLF to an existing root .gitignore ${what}`, () => {
        const root = repository();
        fs.writeFileSync(path.join(root, '.gitignore'), text);
        const { changed } = run(root);
        assert.deepEqual(changed, ['.gitignore']);
        const after = read(root, '.gitignore');
        assert.ok(after.startsWith(text.endsWith('\n') ? text : `${text}\r\n`), after);
        assert.doesNotMatch(after, /(?<!\r)\n/, 'a bare LF in a CRLF .gitignore');
        assert.deepEqual(rules(after), ['be/target/', '.idea/', '/.local-run/']);
      });

    for (const line of ['.local-run/', '/.local-run/', '.local-run', '/.local-run'])
      it(`leaves a root .gitignore that has ${line} as it is`, () => {
        const root = repository();
        const text = `be/target/\n${line}\n`;
        fs.writeFileSync(path.join(root, '.gitignore'), text);
        const { kept, changed } = run(root);
        assert.ok(kept.includes('.gitignore'));
        assert.deepEqual(changed, []);
        assert.equal(read(root, '.gitignore'), text);
      });

    it('leaves the root .gitignore as it is when a refusal stops init', () => {
      const root = repository();
      fs.writeFileSync(path.join(root, '.gitignore'), 'be/target/\n');
      assertRefused(root, () => run(root, { version: '4.0.8' }), /is not the kit's version/);
    });
  });
});
