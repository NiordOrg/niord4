/**
 * Tests for country-kit/source-zip.mjs.
 *
 *   npm run test:guards      # from fe/
 *
 * Each case builds the source zip of a small tree in a temporary git repository: an fe/ with the
 * files the zip takes and the ones it must never take — another language's pack, the workspace's
 * docs and scripts, a gitignored build output — and a be/ with the English backend bundles the
 * catalog reads. A zip that differs between two builds, or that carries a file it must never
 * take, is the failure these exist for. The country-word guard's cases use a crafted word list on
 * the fixture tree, and the real list on a zip of this tree for the `guard` command.
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { FORMAT } from './catalog.mjs';
import { readCountryWords } from './lib/country-words.mjs';
import { compileList, readIncludeList } from './lib/include.mjs';
import { KIT_BACKEND, kitBackendBytes, markerText, where, withLineEnds } from './lib/where.mjs';
import { readZip, writeZip } from './lib/zip.mjs';
import {
  buildKitZip,
  buildSourceZip,
  gitFiles,
  guardKitZip,
  guardSourceZip,
  importClosureProblems,
  KIT_ADDED,
  KIT_INCLUDE_FILE,
  kitClosureProblems,
  kitEntries,
  relativeImports,
  sha256,
  sourceEntries,
  treeFiles,
  verifyKitZip,
  verifySourceZip,
  writeKitZip,
  writeSourceZip,
} from './source-zip.mjs';

const SCRIPT = fileURLToPath(new URL('./source-zip.mjs', import.meta.url));
/** The set-up stub, a file of the kit the zip no longer carries. */
const STUB = fs.readFileSync(new URL('./templates/setup.mjs', import.meta.url));
/** A crafted word list for the fixture tree: the real list's entries would match nothing there. */
const WORDS = { format: 1, words: ['Fjordland'], allowed: [] };

const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const english = (text) => `{\n  "HELLO": "${text}"\n}\n`;

/** The files of the fixture tree, relative to its root. */
function fixtureFiles() {
  const files = {
    'fe/.gitignore': 'dist/\n.niord/\n',
    'fe/package.json': '{ "name": "niord-app" }\n',
    'fe/angular.json': '{}\n',
    'fe/languages/en/pack.json': JSON.stringify({
      lang: 'en',
      label: 'English',
      locale: 'en-GB',
      flag: 'img/flags/en.png',
    }),
    'fe/languages/en/img/flags/en.png': PNG,
    'fe/languages/en/i18n/admin/en.json': english('Hello'),
    'fe/languages/en/i18n/info/en.json': english('Hello'),
    'fe/ui-apps/info/src/app/shared/utils/date-pattern-tokens.json': '["yyyy", "MM", "dd"]\n',
    'fe/ui-apps/admin/src/main.ts': 'export {};\n',
    'fe/ui-apps/admin/src/app/country-specific/default/language.config.ts': 'export {};\n',
    // Never in the zip:
    'fe/languages/da/i18n/admin/da.json': english('Hej'),
    'fe/docs/x.md': '# Notes\n',
    'fe/scripts/check.mjs': 'export {};\n',
    'fe/CLAUDE.md': '# Rules\n',
    'fe/dist/niord-app/main.js': 'built();\n',
  };
  for (const bundle of ['web', 'message', 'pdf', 'mail', 'template'])
    files[`be/niord/src/main/resources/${bundle}_en.properties`] = `${bundle}.title=Title\n`;
  return files;
}

/** Writes the fixture tree into a fresh git repository and returns its fe/ and be/. */
function tree(edit) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'source-zip-'));
  temporary.push(root);
  const files = fixtureFiles();
  edit?.(files);
  for (const [relative, content] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(root, relative)), { recursive: true });
    fs.writeFileSync(path.join(root, relative), content);
  }
  const git = spawnSync('git', ['init', '-q'], { cwd: root, encoding: 'utf8' });
  assert.equal(git.status, 0, git.stderr);
  return { fe: path.join(root, 'fe'), be: path.join(root, 'be') };
}

describe('source-zip', () => {
  it('gives the same bytes and sha256 for two builds of the same tree', () => {
    const where = tree();
    const one = buildSourceZip({ ...where, version: '4.0.1' });
    const two = buildSourceZip({ ...where, version: '4.0.1' });
    assert.ok(one.zip.equals(two.zip));
    assert.equal(one.sha256, two.sha256);
    assert.equal(one.sha256, sha256(two.zip));
  });

  it('holds what the include list selects and the catalog, nothing else', () => {
    const { zip } = buildSourceZip({ ...tree(), version: '4.0.1' });
    assert.deepEqual(
      readZip(zip).map(({ name }) => name),
      [
        'angular.json',
        'catalog.json',
        'languages/en/i18n/admin/en.json',
        'languages/en/i18n/info/en.json',
        'languages/en/img/flags/en.png',
        'languages/en/pack.json',
        'package.json',
        'ui-apps/admin/src/app/country-specific/default/language.config.ts',
        'ui-apps/admin/src/main.ts',
        'ui-apps/info/src/app/shared/utils/date-pattern-tokens.json',
      ],
    );
    const catalog = JSON.parse(
      readZip(zip)
        .find(({ name }) => name === 'catalog.json')
        .data.toString('utf8'),
    );
    assert.equal(catalog.version, '4.0.1');
    assert.ok(catalog.apps.admin.HELLO);
  });

  it('verify refuses a country-kit/ entry: the zip carries nothing of the kit', () => {
    const { zip } = buildSourceZip({ ...tree(), version: '4.0.1' });
    assert.deepEqual(verifySourceZip(zip), []);
    const withKit = writeZip([
      ...readZip(zip),
      { name: 'country-kit/setup-kit.mjs', data: Buffer.from('export {};\n') },
      { name: 'country-kit/templates/setup.mjs', data: STUB },
    ]);
    assert.deepEqual(verifySourceZip(withKit), [
      'country-kit/setup-kit.mjs is outside the include list (zip-include.json)',
      'country-kit/templates/setup.mjs is outside the include list (zip-include.json)',
    ]);
  });

  it('never lists a gitignored file, and lists an untracked one', () => {
    const { fe } = tree();
    const files = gitFiles(fe);
    assert.ok(files.includes('ui-apps/admin/src/main.ts'));
    assert.ok(!files.some((file) => file.startsWith('dist/')));
  });

  it('passes its own verification', () => {
    const { zip } = buildSourceZip({ ...tree(), version: '4.0.1' });
    assert.deepEqual(verifySourceZip(zip), []);
  });

  it('fails verification on an entry outside the include list, or without a catalog', () => {
    const entries = sourceEntries({ ...tree(), version: '4.0.1' });
    const outside = writeZip([...entries, { name: 'docs/x.md', data: Buffer.from('# x\n') }]);
    assert.deepEqual(verifySourceZip(outside), [
      'docs/x.md is outside the include list (zip-include.json)',
    ]);
    const noCatalog = writeZip(entries.filter(({ name }) => name !== 'catalog.json'));
    assert.deepEqual(verifySourceZip(noCatalog), ['there is no catalog.json']);
  });

  it('refuses a version that is not one', () => {
    assert.throws(
      () => buildSourceZip({ ...tree(), version: '../4.0.1' }),
      /"\.\.\/4\.0\.1" is not a version/,
    );
  });

  it('writes nothing when a selected file names a country word, and names the file', () => {
    const where = tree((files) => {
      files['fe/ui-apps/admin/src/planted.ts'] = 'export {};\n// the Fjordland authority\n';
    });
    const out = path.join(where.fe, '..', 'out', 'niord-fe-src-4.0.1.zip');
    const { problems } = writeSourceZip({ ...where, version: '4.0.1', out, words: WORDS });
    assert.deepEqual(problems, [
      'ui-apps/admin/src/planted.ts:2: names "Fjordland" — allow it in country-kit/country-words.json with a reason, or keep the file out of the zip',
    ]);
    assert.ok(!fs.existsSync(out) && !fs.existsSync(`${out}.sha256`));
  });

  it('writes the zip and its .sha256 when the checks pass', () => {
    const where = tree();
    const out = path.join(where.fe, '..', 'out', 'niord-fe-src-4.0.1.zip');
    const { problems, sha256: hash } = writeSourceZip({
      ...where,
      version: '4.0.1',
      out,
      words: WORDS,
    });
    assert.deepEqual(problems, []);
    assert.equal(sha256(fs.readFileSync(out)), hash);
    assert.equal(fs.readFileSync(`${out}.sha256`, 'utf8'), `${hash}  niord-fe-src-4.0.1.zip\n`);
  });

  it('guards a clean zip, and a word in any entry, the catalog included', () => {
    const entries = sourceEntries({ ...tree(), version: '4.0.1' });
    assert.deepEqual(guardSourceZip(writeZip(entries), WORDS), []);
    const named = entries.map((entry) =>
      entry.name === 'catalog.json'
        ? { ...entry, data: Buffer.from(`${entry.data.toString('utf8')}"FJORDLAND"\n`) }
        : entry,
    );
    const catalog = named.find(({ name }) => name === 'catalog.json').data.toString('utf8');
    const line = catalog.split('\n').indexOf('"FJORDLAND"') + 1;
    assert.deepEqual(guardSourceZip(writeZip(named), WORDS), [
      `catalog.json:${line}: names "Fjordland" — allow it in country-kit/country-words.json with a reason, or keep the file out of the zip`,
    ]);
    assert.equal(guardSourceZip(Buffer.from('not a zip'), WORDS).length, 1);
  });

  it('keeps verify word-blind: a published zip never fails on a word added later', () => {
    const entries = sourceEntries({ ...tree(), version: '4.0.1' });
    const zip = writeZip([
      ...entries,
      { name: 'ui-apps/admin/src/x.ts', data: Buffer.from('Fjordland\n') },
    ]);
    assert.deepEqual(verifySourceZip(zip), []);
    assert.equal(guardSourceZip(zip, WORDS).length, 1);
  });

  it('guard exits 0 on a zip of this tree and 1 when an entry names a word', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'source-zip-guard-'));
    temporary.push(dir);
    const { zip } = buildSourceZip({ version: '4.0.0-guard' });
    const clean = path.join(dir, 'clean.zip');
    fs.writeFileSync(clean, zip);
    const guard = (file) =>
      spawnSync(process.execPath, [SCRIPT, 'guard', file], { encoding: 'utf8' });
    const passed = guard(clean);
    assert.equal(passed.status, 0, passed.stderr);
    assert.match(
      passed.stdout,
      /passes the country-word guard \(\d+ words, \d+ allow-list entries\)/,
    );
    // A word of the real list, taken from it so this file needs to name none.
    const [word] = readCountryWords().words;
    const planted = path.join(dir, 'planted.zip');
    fs.writeFileSync(
      planted,
      writeZip([
        ...readZip(zip),
        { name: 'ui-apps/admin/src/app/planted.ts', data: Buffer.from(`// ${word}\n`) },
      ]),
    );
    const failed = guard(planted);
    assert.equal(failed.status, 1);
    assert.match(failed.stderr, /ui-apps\/admin\/src\/app\/planted\.ts:1: names "/);
    assert.ok(!failed.stderr.includes(`// ${word}`), 'the line itself is never printed');
  });

  it('fails where git has no repository', () => {
    const lone = fs.mkdtempSync(path.join(os.tmpdir(), 'source-zip-no-git-'));
    temporary.push(lone);
    assert.throws(() => gitFiles(lone), /git ls-files failed|git is not available/);
  });
});

describe('the kit beside fe/', () => {
  /** The fixture tree with a kit at its root, as the base product's tree holds it. */
  const withKit = (edit) =>
    tree((files) => {
      files['country-kit/pack.mjs'] = 'export {};\n';
      files['country-kit/templates/setup.mjs'] = STUB;
      files['country-kit/docs/x.md'] = '# Guide\n';
      files['country-kit/pack.test.mjs'] = 'export {};\n';
      edit?.(files);
    });

  it('names its files country-kit/…, beside the files of fe/', () => {
    const { fe } = withKit();
    const files = treeFiles({ fe });
    const names = files.map(({ name }) => name);
    assert.ok(names.includes('ui-apps/admin/src/main.ts'));
    for (const name of [
      'country-kit/docs/x.md',
      'country-kit/pack.mjs',
      'country-kit/pack.test.mjs',
    ])
      assert.ok(names.includes(name), `the tree names ${name}`);
    assert.deepEqual(names, [...names].sort());
    assert.equal(
      files.find(({ name }) => name === 'country-kit/pack.mjs').file,
      path.join(fe, '..', 'country-kit', 'pack.mjs'),
    );
  });

  it('packs nothing of the kit beside fe/, its stub included', () => {
    const { zip } = buildSourceZip({ ...withKit(), version: '4.0.1' });
    const names = readZip(zip).map(({ name }) => name);
    assert.deepEqual(
      names.filter((name) => name.startsWith('country-kit/')),
      [],
    );
    assert.deepEqual(verifySourceZip(zip), []);
  });

  it('fails on a file given by fe/country-kit/ and by the kit beside fe/', () => {
    const both = withKit((files) => {
      files['fe/country-kit/a.mjs'] = 'export {};\n';
      files['country-kit/a.mjs'] = 'export {};\n';
    });
    assert.throws(() => treeFiles(both), /country-kit\/a\.mjs is given twice/);
    assert.throws(() => buildSourceZip({ ...both, version: '4.0.1' }), /given twice/);
  });
});

/** An archive of `files`, name → text, with a catalog, as verify reads one. */
function archive(files) {
  return writeZip([
    ...Object.entries(files).map(([name, text]) => ({ name, data: Buffer.from(text) })),
    {
      name: 'catalog.json',
      data: Buffer.from(JSON.stringify({ format: FORMAT, version: '4.0.1' })),
    },
  ]);
}

/** The import boundary's findings over an archive. */
const closure = (zip) => importClosureProblems(readZip(zip));

describe('the import boundary between the kit and the rest of the zip', () => {
  it('reads the relative specifiers of imports, exports, import() and require()', () => {
    assert.deepEqual(
      relativeImports(
        [
          "import { a } from './a.mjs';",
          "import b, * as c from '../b.mjs';",
          'import "./side.css";',
          "export { d } from './d.mjs';",
          "export * from '../e.mjs';",
          "const f = await import('./f.mjs');",
          "const g = require('../g.cjs');",
          "import type { H } from './h';",
          "import { i } from 'node:fs';",
          "const j = require('eslint');",
        ].join('\n'),
      ),
      ['./a.mjs', '../b.mjs', './side.css', './d.mjs', '../e.mjs', './f.mjs', '../g.cjs', './h'],
    );
  });

  it('skips comments, template literals, regular expressions and computed specifiers', () => {
    assert.deepEqual(
      relativeImports(
        [
          '#!/usr/bin/env node',
          "// import { x } from '../x.mjs';",
          "/* const y = require('./y.cjs'); */",
          "const config = `module.exports = require('./.niord/base/eslint.config.js');`;",
          "const nested = `${`import z from '../z.mjs';`}`;",
          String.raw`const pattern = /from '\.\/x'|import\('\.\./;`,
          'const quote = /[`\'"]/.test(text) ? 1 : 2;',
          "const url = await import(pathToFileURL(path.join(base, 'scripts', 'x.mjs')).href);",
          "const joined = await import('../scripts/' + name);",
          "const from = 'a'; Array.from('./b');",
          "import { kept } from './kept.mjs';",
        ].join('\n'),
      ),
      ['./kept.mjs'],
    );
  });

  it('reports a file outside the kit that imports the kit, and a kit file that imports outside it', () => {
    assert.deepEqual(
      closure(
        archive({
          'eslint.config.js': "const lint = require('./country-kit/lib/country-lint.cjs');\n",
          'scripts/build-urls-check.mjs':
            "import { globToRegExp } from '../country-kit/lib/include.mjs';\n",
          'country-kit/lib/build-checks.mjs':
            "import {\n  APP_BUILDS,\n} from '../../scripts/build-urls-check.mjs';\n",
          'country-kit/lib/info-image.mjs':
            "import { precompress } from '../../ui-apps/info/nginx/precompress.mjs';\n",
        }),
      ),
      [
        "country-kit/lib/build-checks.mjs imports '../../scripts/build-urls-check.mjs': the kit imports the base product only from its resolved base",
        "country-kit/lib/info-image.mjs imports '../../ui-apps/info/nginx/precompress.mjs': the kit imports the base product only from its resolved base",
        "eslint.config.js imports './country-kit/lib/country-lint.cjs': the base product never imports the kit",
        "scripts/build-urls-check.mjs imports '../country-kit/lib/include.mjs': the base product never imports the kit",
      ],
    );
  });

  it("passes the kit's own imports, generated text, comments, computed imports and the templates", () => {
    assert.deepEqual(
      closure(
        archive({
          'country-kit/lib/build-checks.mjs': [
            "import { flatten } from '../stage-languages.mjs';",
            "import { where } from './where.mjs';",
            "const urls = await import(pathToFileURL(path.join(where().base, 'scripts', 'build-urls-check.mjs')).href);",
            "const more = await import('../../scripts/' + name);",
          ].join('\n'),
          'country-kit/lib/workspace.mjs':
            "export const ESLINT_CONFIG = `module.exports = require('./.niord/base/eslint.config.js');\nrequire('../../../eslint.config.js');\n`;\n",
          'scripts/build-urls-check.mjs':
            "// import { globToRegExp } from '../country-kit/lib/include.mjs';\nimport { globToRegExp } from './glob.mjs';\n",
          'eslint.config.js':
            "/* require('./country-kit/lib/country-lint.cjs') */\nconst shared = require('./eslint-shared.cjs');\n",
          'country-kit/templates/setup.mjs': "import { a } from '../../outside.mjs';\n",
          'country-kit/example/fe/vitest.config.ts': "import x from '../../../x.mjs';\n",
          'country-kit/example/be/x.mjs': "import x from '../../../x.mjs';\n",
          'ui-apps/admin/src/main.ts': "import { a } from './app/a';\n",
        }),
      ),
      [],
    );
  });

  it('reads no document, and a specifier that leaves the zip imports nothing of the kit', () => {
    assert.deepEqual(
      importClosureProblems([
        { name: 'proxy.conf.mjs', data: Buffer.from("import '../fe/country-kit/x.mjs';\n") },
        { name: 'README.md', data: Buffer.from("import x from './country-kit/x.mjs';\n") },
      ]),
      [],
    );
  });

  it("passes on a zip of this tree, which carries nothing of the kit and none of the kit package's backend files", () => {
    const { zip } = buildSourceZip({ version: '4.0.0-verify' });
    assert.deepEqual(verifySourceZip(zip), []);
    const entries = readZip(zip);
    const names = entries.map(({ name }) => name);
    assert.deepEqual(
      names.filter((name) => name.startsWith('country-kit/')),
      [],
    );
    const backend = new Set(
      Object.values(KIT_BACKEND).flatMap(({ entry, from }) => [entry, from, `be/${from}`]),
    );
    assert.deepEqual(
      names.filter((name) => name.startsWith('backend/') || backend.has(name)),
      [],
    );
    assert.deepEqual(importClosureProblems(entries), []);
  });
});

/**
 * The files of a small kit tree and the backend contract and files beside it, relative to its
 * root; the backend files have the wrong line ends, which the build corrects.
 */
function kitFixtureFiles() {
  return {
    'country-kit/README.md': '# The kit\n',
    'country-kit/API-CHANGES.md': '# API changes\n',
    'country-kit/setup-kit.mjs': "import { where } from './lib/where.mjs';\nexport { where };\n",
    'country-kit/lib/where.mjs': 'export const where = () => ({});\n',
    'country-kit/templates/setup.mjs': "import { a } from '../../outside.mjs';\n",
    'country-kit/example/fe/niord-country.json': '{ "country": "zz" }\n',
    // Never in the kit package:
    'country-kit/lib/where.test.mjs': 'export {};\n',
    'country-kit/lib/fixtures/x.json': '{}\n',
    'country-kit/test-fixtures/base-modules.mjs': 'export {};\n',
    'country-kit/country-words.json': '{}\n',
    'country-kit/source-zip.mjs': 'export {};\n',
    'country-kit/example/run.mjs': "import { sha256 } from '../source-zip.mjs';\n",
    'country-kit/.prettierrc': '{}\n',
    'country-kit/eslint.config.mjs': 'export default [];\n',
    'be/docs/country-api.md': '# The country API\r\n\r\nOne contract.\r\n',
    'be/mvnw': '#!/bin/sh\r\necho mvnw\r\n',
    'be/mvnw.cmd': '@echo off\nexit /b 0\n',
    'be/.mvn/wrapper/maven-wrapper.properties': 'wrapperVersion=3.3.4\r\n',
    'be/niord/src/main/resources/defaults/base/settings.json': '{\r\n  "values": {}\r\n}\r\n',
    'be/niord/src/main/resources/defaults/base/settings-catalog.json': '{\r\n  "keys": []\r\n}\r\n',
    // Its bytes hold a CRLF, which the package keeps.
    'be/niord/src/main/resources/META-INF/resources/img/company-logo.png': PNG,
  };
}

/** The names of the backend files the kit package's build adds. */
const BACKEND_NAMES = Object.values(KIT_BACKEND).map(({ entry }) => entry);

/** Writes the kit fixture into a fresh git repository and returns its kit and be/ folders. */
function kitTree(edit) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'kit-package-'));
  temporary.push(root);
  const files = kitFixtureFiles();
  edit?.(files);
  for (const [relative, content] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(root, relative)), { recursive: true });
    fs.writeFileSync(path.join(root, relative), content);
  }
  const git = spawnSync('git', ['init', '-q'], { cwd: root, encoding: 'utf8' });
  assert.equal(git.status, 0, git.stderr);
  return { kit: path.join(root, 'country-kit'), be: path.join(root, 'be') };
}

/** The names of an archive's entries. */
const namesOf = (zip) => readZip(zip).map(({ name }) => name);
/** An entry's text, CRLF read as LF. */
const lf = (bytes) => bytes.toString('utf8').replace(/\r\n/g, '\n');
/** An entry of `text`. */
const entry = (name, text) => ({ name, data: Buffer.from(text) });

describe('the kit package, org.niord:niord-country-kit', () => {
  it('gives the same bytes and sha256 for two builds of this tree', () => {
    const one = buildKitZip({ version: '4.0.0-kit' });
    const two = buildKitZip({ version: '4.0.0-kit' });
    assert.ok(one.zip.equals(two.zip));
    assert.equal(one.sha256, two.sha256);
    assert.equal(one.sha256, sha256(two.zip));
  });

  it("holds what kit-include.json selects of the kit, rooted at the kit's top, the contract, the marker and the backend files", () => {
    const { zip } = buildKitZip({ version: '4.0.0-kit' });
    const entries = readZip(zip);
    const names = entries.map(({ name }) => name);
    const selects = compileList(readIncludeList(KIT_INCLUDE_FILE));
    assert.deepEqual(
      names,
      [...gitFiles(where().kit).filter(selects), ...Object.values(KIT_ADDED)].sort(),
    );
    const data = (name) => entries.find((entry) => entry.name === name).data;
    assert.equal(data('kit.json').toString('utf8'), markerText('4.0.0-kit'));
    assert.equal(
      data('docs/country-api.md').toString('utf8'),
      lf(fs.readFileSync(path.join(where().be, 'docs', 'country-api.md'))),
    );
    // The source layout gives the package's bytes, whatever the checkout's line ends.
    const be = (key) => fs.readFileSync(path.join(where().be, ...KIT_BACKEND[key].from.split('/')));
    for (const [key, { entry }] of Object.entries(KIT_BACKEND))
      assert.ok(data(entry).equals(kitBackendBytes(key)), entry);
    const cmd = data('backend/mvnw.cmd').toString('latin1');
    assert.doesNotMatch(cmd, /(^|[^\r])\n/);
    assert.equal(lf(Buffer.from(cmd, 'latin1')), lf(be('mvnwCmd')));
    assert.doesNotMatch(data('backend/mvnw').toString('latin1'), /\r/);
    assert.ok(data('backend/img/company-logo.png').equals(be('companyLogo')));
    for (const key of ['baseSettings', 'settingsCatalog'])
      assert.equal(data(KIT_BACKEND[key].entry).toString('utf8'), lf(be(key)), key);
    const LEFT_OUT =
      /(^|\/)[^/]+\.test\.mjs$|^country-words\.json$|^source-zip\.mjs$|(^|\/)(fixtures|test-fixtures)\/|^example\/[^/]+\.mjs$/;
    for (const name of names) {
      assert.ok(!name.startsWith('country-kit/'), `${name} is named under country-kit/`);
      assert.ok(!LEFT_OUT.test(name), `${name} is in the kit package`);
    }
    for (const name of [
      'lib/where.mjs',
      'lib/tree.mjs',
      'setup-kit.mjs',
      'templates/setup.mjs',
      'zip-include.json',
      'kit-include.json',
      'example/fe/setup.mjs',
    ])
      assert.ok(names.includes(name), `${name} is left out`);
  });

  it('names a small kit as its list selects it, with the contract read as LF and the backend files with their line ends', () => {
    const { zip } = buildKitZip({ ...kitTree(), version: '4.0.1' });
    assert.deepEqual(namesOf(zip), [
      'API-CHANGES.md',
      'README.md',
      'backend/defaults/base/settings-catalog.json',
      'backend/defaults/base/settings.json',
      'backend/img/company-logo.png',
      'backend/maven-wrapper.properties',
      'backend/mvnw',
      'backend/mvnw.cmd',
      'docs/country-api.md',
      'example/fe/niord-country.json',
      'kit.json',
      'lib/where.mjs',
      'setup-kit.mjs',
      'templates/setup.mjs',
    ]);
    const data = (name) => readZip(zip).find((each) => each.name === name).data;
    assert.equal(
      data('docs/country-api.md').toString('utf8'),
      '# The country API\n\nOne contract.\n',
    );
    assert.equal(data('backend/mvnw').toString('utf8'), '#!/bin/sh\necho mvnw\n');
    assert.equal(data('backend/mvnw.cmd').toString('utf8'), '@echo off\r\nexit /b 0\r\n');
    assert.equal(
      data('backend/maven-wrapper.properties').toString('utf8'),
      'wrapperVersion=3.3.4\n',
    );
    assert.equal(
      data('backend/defaults/base/settings.json').toString('utf8'),
      '{\n  "values": {}\n}\n',
    );
    assert.equal(
      data('backend/defaults/base/settings-catalog.json').toString('utf8'),
      '{\n  "keys": []\n}\n',
    );
    assert.ok(data('backend/img/company-logo.png').equals(PNG));
  });

  it('passes its own verification', () => {
    assert.deepEqual(verifyKitZip(buildKitZip({ ...kitTree(), version: '4.0.1' }).zip), []);
    assert.deepEqual(verifyKitZip(buildKitZip({ version: '4.0.0-kit' }).zip), []);
  });

  it('fails verification on an entry outside the list, and on a missing or wrong marker or contract', () => {
    const entries = kitEntries({ ...kitTree(), version: '4.0.1' });
    const without = (name) => entries.filter((each) => each.name !== name);
    const marker = (text) => writeZip([...without('kit.json'), entry('kit.json', text)]);
    const wrong = `kit.json is not the kit's marker: it holds exactly {"package":"niord-country-kit","version":"<version>"} and a newline`;
    assert.deepEqual(
      verifyKitZip(writeZip([...entries, entry('source-zip.mjs', 'export {};\n')])),
      ['source-zip.mjs is outside the kit package (kit-include.json)'],
    );
    assert.deepEqual(verifyKitZip(writeZip(without('kit.json'))), ['there is no kit.json']);
    for (const text of [
      '{"package":"niord-fe-src","version":"4.0.1"}\n',
      '{"package":"niord-country-kit","version":"../4"}\n',
      '{"package":"niord-country-kit","version":"4.0.1","extra":1}\n',
      '{"version":"4.0.1","package":"niord-country-kit"}\n',
      '{"package":"niord-country-kit","version":"4.0.1"}',
      'not json',
    ])
      assert.deepEqual(verifyKitZip(marker(text)), [wrong], text);
    assert.deepEqual(verifyKitZip(writeZip(without('docs/country-api.md'))), [
      'there is no docs/country-api.md',
    ]);
    assert.equal(verifyKitZip(Buffer.from('not a zip')).length, 1);
  });

  it('fails verification on a backend file it does not name, a missing one, and one with the wrong line ends', () => {
    const entries = kitEntries({ ...kitTree(), version: '4.0.1' });
    const without = (name) => entries.filter((each) => each.name !== name);
    const replaced = (name, data) => writeZip([...without(name), { name, data }]);
    // Exactly the six: another file under backend/ is outside the package.
    assert.deepEqual(verifyKitZip(writeZip([...entries, entry('backend/other.txt', 'x\n')])), [
      'backend/other.txt is outside the kit package (kit-include.json)',
    ]);
    for (const name of BACKEND_NAMES)
      assert.deepEqual(verifyKitZip(writeZip(without(name))), [`there is no ${name}`], name);
    assert.deepEqual(verifyKitZip(replaced('backend/mvnw.cmd', Buffer.from('@echo off\nx\r\n'))), [
      'backend/mvnw.cmd does not end every line with CRLF',
    ]);
    assert.deepEqual(verifyKitZip(replaced('backend/mvnw', Buffer.from('#!/bin/sh\r\nx\n'))), [
      'backend/mvnw does not end every line with LF',
    ]);
    assert.deepEqual(
      verifyKitZip(replaced('backend/defaults/base/settings.json', Buffer.from('{\r\n}\r\n'))),
      ['backend/defaults/base/settings.json does not end every line with LF'],
    );
    // The logo is held byte for byte: a CRLF in its bytes is no line end.
    const logo = Buffer.concat([PNG, Buffer.from('\r\n\n')]);
    assert.ok(!withLineEnds(logo, 'lf').equals(logo));
    assert.deepEqual(verifyKitZip(replaced('backend/img/company-logo.png', logo)), []);
  });

  it('reports a shipped script whose import leaves the package or names a file it leaves out', () => {
    assert.deepEqual(
      kitClosureProblems([
        // From country-kit/lib/ in the base product's tree, its fe/scripts/glob.mjs.
        entry('lib/x.mjs', "import { globToRegExp } from '../../fe/scripts/glob.mjs';\n"),
        entry('lib/y.mjs', "import { a } from './missing.mjs';\nimport { b } from './x.mjs';\n"),
        entry('lib/local-sources.mjs', "import { gitFiles } from '../source-zip.mjs';\n"),
        entry('lib/z.cjs', "const lint = require('../../fe/eslint-shared.cjs');\n"),
      ]),
      [
        "lib/x.mjs imports '../../fe/scripts/glob.mjs': it leaves the kit package; the kit loads the base product only from its resolved base",
        "lib/y.mjs imports './missing.mjs': no such file in the kit package (kit-include.json)",
        "lib/local-sources.mjs imports '../source-zip.mjs': no such file in the kit package (kit-include.json)",
        "lib/z.cjs imports '../../fe/eslint-shared.cjs': it leaves the kit package; the kit loads the base product only from its resolved base",
      ],
    );
  });

  it('passes the templates, the example country, computed imports, generated text and documents', () => {
    assert.deepEqual(
      kitClosureProblems([
        entry('templates/x.mjs', "import { a } from '../../anything.mjs';\n"),
        entry('example/fe/x.mjs', "import { a } from '../../anything.mjs';\n"),
        entry('example/be/x.mjs', "import { a } from '../../anything.mjs';\n"),
        entry(
          'lib/build-checks.mjs',
          [
            "import { where } from './where.mjs';",
            "const urls = await import(pathToFileURL(path.join(where().base, 'scripts', 'build-urls-check.mjs')).href);",
            "const more = await import(where().base + '/scripts/x.mjs');",
            "export const CONFIG = `module.exports = require('../../fe/eslint.config.js');\n`;",
          ].join('\n'),
        ),
        entry('lib/where.mjs', 'export const where = () => ({});\n'),
        entry('README.md', "import x from '../fe/x.mjs';\n"),
      ]),
      [],
    );
  });

  it("finds no crossing in this tree's kit package", () => {
    assert.deepEqual(kitClosureProblems(readZip(buildKitZip({ version: '4.0.0-kit' }).zip)), []);
  });

  it('guards the entries as country-kit/<entry>, and names a hit by its entry', () => {
    const words = {
      format: 1,
      words: ['Fjordland'],
      allowed: [
        {
          word: 'Fjordland',
          path: 'country-kit/lib/where.mjs',
          reason: 'a test of the allow-list',
        },
      ],
    };
    const entries = kitEntries({ ...kitTree(), version: '4.0.1' }).map((each) =>
      each.name === 'lib/where.mjs' || each.name === 'setup-kit.mjs'
        ? { ...each, data: Buffer.from(`${each.data.toString('utf8')}// Fjordland\n`) }
        : each,
    );
    assert.deepEqual(guardKitZip(writeZip(entries), words), [
      'setup-kit.mjs:3: names "Fjordland" — allow it in country-kit/country-words.json with a reason, or keep the file out of the zip',
    ]);
    assert.deepEqual(
      guardKitZip(writeZip(kitEntries({ ...kitTree(), version: '4.0.1' })), words),
      [],
    );
    assert.equal(guardKitZip(Buffer.from('not a zip'), words).length, 1);
  });

  it('refuses a kit that holds a name the build adds, a be/ without a backend file, and a version that is not one', () => {
    assert.equal(Object.values(KIT_ADDED).length, 8);
    // A list that selects everything, so a planted backend/ file is selected as well.
    const all = { format: 1, note: 'all', include: ['**'], exclude: [] };
    for (const name of Object.values(KIT_ADDED)) {
      const tree = kitTree((files) => (files[`country-kit/${name}`] = '{}\n'));
      assert.throws(() => kitEntries({ ...tree, version: '4.0.1', list: all }), {
        message: `${name} is selected, but the build adds that name`,
      });
    }
    for (const { entry, from } of Object.values(KIT_BACKEND)) {
      const tree = kitTree((files) => delete files[`be/${from}`]);
      assert.throws(() => kitEntries({ ...tree, version: '4.0.1' }), {
        message: `${path.join(tree.be, ...from.split('/'))} is missing: the kit package carries it as ${entry}`,
      });
    }
    assert.throws(
      () => kitEntries({ ...kitTree(), version: '../4.0.1' }),
      /"\.\.\/4\.0\.1" is not a version/,
    );
  });

  it('writes nothing when a check fails, and the zip and its .sha256 when they pass', () => {
    const words = { format: 1, words: ['Fjordland'], allowed: [] };
    const planted = kitTree((files) => {
      files['country-kit/lib/planted.mjs'] = 'export {};\n// the Fjordland authority\n';
    });
    const out = path.join(planted.kit, '..', 'out', 'niord-country-kit-4.0.1.zip');
    assert.deepEqual(writeKitZip({ ...planted, version: '4.0.1', out, words }).problems, [
      'lib/planted.mjs:2: names "Fjordland" — allow it in country-kit/country-words.json with a reason, or keep the file out of the zip',
    ]);
    assert.ok(!fs.existsSync(out) && !fs.existsSync(`${out}.sha256`));
    const crossing = kitTree((files) => {
      files['country-kit/lib/crossing.mjs'] = "import x from '../source-zip.mjs';\n";
    });
    assert.deepEqual(writeKitZip({ ...crossing, version: '4.0.1', out, words }).problems, [
      "lib/crossing.mjs imports '../source-zip.mjs': no such file in the kit package (kit-include.json)",
    ]);
    assert.ok(!fs.existsSync(out));
    const written = writeKitZip({ ...kitTree(), version: '4.0.1', out, words });
    assert.deepEqual(written.problems, []);
    assert.equal(sha256(fs.readFileSync(out)), written.sha256);
    assert.equal(
      fs.readFileSync(`${out}.sha256`, 'utf8'),
      `${written.sha256}  niord-country-kit-4.0.1.zip\n`,
    );
  });

  it('builds, verifies and guards with --package kit, and refuses another package', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'kit-package-cli-'));
    temporary.push(dir);
    const out = path.join(dir, 'niord-country-kit-4.0.0-cli.zip');
    const run = (...args) => spawnSync(process.execPath, [SCRIPT, ...args], { encoding: 'utf8' });
    const built = run('build', '--package', 'kit', '--version', '4.0.0-cli', '--out', out);
    assert.equal(built.status, 0, built.stderr);
    const hash = sha256(fs.readFileSync(out));
    assert.equal(
      fs.readFileSync(`${out}.sha256`, 'utf8'),
      `${hash}  niord-country-kit-4.0.0-cli.zip\n`,
    );
    assert.ok(built.stdout.includes(hash));
    const names = namesOf(fs.readFileSync(out));
    assert.ok(names.includes('kit.json'));
    assert.deepEqual(
      names.filter((name) => name.startsWith('backend/')),
      [...BACKEND_NAMES].sort(),
    );
    const verified = run('verify', '--package', 'kit', out);
    assert.equal(verified.status, 0, verified.stderr);
    assert.match(verified.stdout, /verified/);
    const guarded = run('guard', out, '--package', 'kit');
    assert.equal(guarded.status, 0, guarded.stderr);
    // A word of the real list in a lib entry, taken from it so this file needs to name none.
    const [word] = readCountryWords().words;
    const planted = path.join(dir, 'planted.zip');
    fs.writeFileSync(
      planted,
      writeZip([...readZip(fs.readFileSync(out)), entry('lib/planted.mjs', `// ${word}\n`)]),
    );
    const failed = run('guard', '--package', 'kit', planted);
    assert.equal(failed.status, 1);
    assert.match(failed.stderr, /lib\/planted\.mjs:1: names "/);
    // Without --package the archive is read as a source zip, which the kit package is not.
    const asSource = run('verify', out);
    assert.equal(asSource.status, 1);
    assert.match(asSource.stderr, /there is no catalog\.json/);
    const other = run('verify', '--package', 'other', out);
    assert.equal(other.status, 2);
    assert.match(other.stderr, /--package is fe-src or kit, not "other"/);
  });
});
