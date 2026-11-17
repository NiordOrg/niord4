/**
 * Tests for country-kit/lib/zip.mjs and country-kit/lib/include.mjs.
 *
 *   npm run test:guards      # from fe/
 *
 * The writer must give the same bytes for the same files, or a pinned sha256 means nothing; the
 * reader must refuse every entry an unpacker could be led astray by. The refusals are tested on
 * archives the writer made and then edited byte by byte, as an attacker would.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, it } from 'node:test';
import { readCountryWords } from './lib/country-words.mjs';
import { compileList, expandBraces, globToRegExp, readIncludeList } from './lib/include.mjs';
import { KIT_BACKEND, where } from './lib/where.mjs';
import { readZip, writeZip } from './lib/zip.mjs';
import { KIT_ADDED, treeFiles } from './source-zip.mjs';

const FE = where().base;
const file = (name, text) => ({ name, data: Buffer.from(text, 'utf8') });
const FILES = [
  file('b/second.txt', 'second '.repeat(200)),
  file('a.txt', 'first'),
  file('b/first.json', '{"x": 1}\n'),
];

/** An archive of `entries` with every occurrence of `from` in its bytes replaced by `to`. */
function edited(entries, from, to) {
  assert.equal(Buffer.byteLength(from), Buffer.byteLength(to));
  const zip = writeZip(entries);
  const needle = Buffer.from(from);
  let at = zip.indexOf(needle);
  assert.ok(at >= 0, `${from} is not in the archive`);
  while (at >= 0) {
    Buffer.from(to).copy(zip, at);
    at = zip.indexOf(needle, at + 1);
  }
  return zip;
}

/** The offset of the first central directory header. */
const centralOf = (zip) => zip.indexOf(Buffer.from([0x50, 0x4b, 0x01, 0x02]));

describe('writeZip', () => {
  it('gives the same bytes for the same files, whatever their order', () => {
    const one = writeZip(FILES);
    const two = writeZip([...FILES].reverse());
    assert.ok(one.equals(two));
  });

  it('writes the entries sorted by name, dated 1980-01-01, as regular files of mode 0644', () => {
    const zip = writeZip(FILES);
    assert.deepEqual(
      readZip(zip).map(({ name }) => name),
      ['a.txt', 'b/first.json', 'b/second.txt'],
    );
    const central = centralOf(zip);
    assert.equal(zip.readUInt16LE(central + 12), 0, 'time 00:00');
    assert.equal(zip.readUInt16LE(central + 14), (1 << 5) | 1, 'date 1980-01-01');
    assert.equal(zip.readUInt32LE(central + 38) >>> 16, 0o100644);
    assert.equal(zip.readUInt16LE(central + 8) & 0x0800, 0x0800, 'UTF-8 names');
  });

  it('reads back what it wrote, deflated or stored', () => {
    const entries = readZip(writeZip(FILES));
    for (const { name, data } of FILES)
      assert.ok(entries.find((entry) => entry.name === name).data.equals(data), name);
  });

  it('refuses to write a name it would refuse to read', () => {
    assert.throws(() => writeZip([file('../up.txt', 'x')]), /has a "\." or "\.\." segment/);
    assert.throws(() => writeZip([file('a.txt', 'x'), file('A.TXT', 'y')]), /given twice/);
  });
});

describe('readZip', () => {
  it('refuses a symbolic link', () => {
    const zip = writeZip([file('link', 'target')]);
    const central = centralOf(zip);
    zip.writeUInt32LE((0o120777 << 16) >>> 0, central + 38);
    assert.throws(() => readZip(zip), /"link" is a symbolic link/);
  });

  it('refuses a name that climbs out with ..', () => {
    const zip = edited([file('zz/escape.txt', 'x')], 'zz/escape.txt', '../escape.txt');
    assert.throws(() => readZip(zip), /"\.\.\/escape\.txt" has a "\." or "\.\." segment/);
  });

  it('refuses an absolute name, a drive letter and a backslash', () => {
    assert.throws(
      () => readZip(edited([file('xetc/passwd', 'x')], 'xetc/passwd', '/etc/passwd')),
      /is absolute/,
    );
    assert.throws(
      () => readZip(edited([file('Cxwin.ini', 'x')], 'Cxwin.ini', 'C:win.ini')),
      /names a drive/,
    );
    assert.throws(
      () => readZip(edited([file('a/b.txt', 'x')], 'a/b.txt', 'a\\b.txt')),
      /has a backslash/,
    );
  });

  it('refuses a name given twice, also when only its case differs', () => {
    assert.throws(
      () => readZip(edited([file('one.txt', 'x'), file('two.txt', 'y')], 'two.txt', 'one.txt')),
      /"one\.txt" is given twice/,
    );
    assert.throws(
      () => readZip(edited([file('one.txt', 'x'), file('two.txt', 'y')], 'two.txt', 'ONE.txt')),
      /"ONE\.txt" is given twice/,
    );
  });

  it('refuses an archive over its size or entry limit', () => {
    const zip = writeZip(FILES);
    assert.throws(() => readZip(zip, { maxBytes: 1000 }), /over the 1000 bytes allowed/);
    assert.throws(() => readZip(zip, { maxEntries: 2 }), /has 3 entries, over the 2 allowed/);
  });

  it('refuses an encrypted entry and an unknown method', () => {
    const encrypted = writeZip([file('a.txt', 'x')]);
    const central = centralOf(encrypted);
    encrypted.writeUInt16LE(encrypted.readUInt16LE(central + 8) | 1, central + 8);
    assert.throws(() => readZip(encrypted), /is encrypted/);
    const method = writeZip([file('a.txt', 'x')]);
    method.writeUInt16LE(12, centralOf(method) + 10);
    assert.throws(() => readZip(method), /uses compression method 12/);
  });

  it('refuses an entry whose bytes do not match its CRC-32', () => {
    const zip = writeZip([file('a.txt', 'abc')]);
    zip[30 + 'a.txt'.length] ^= 0xff;
    assert.throws(() => readZip(zip), /fails its CRC-32/);
  });

  it('refuses what is not an archive', () => {
    assert.throws(() => readZip(Buffer.from('not a zip at all, just text')), /no end-of-central/);
  });
});

describe('the include list', () => {
  it('matches **, *, ? and {a,b} as documented', () => {
    assert.deepEqual(expandBraces('a/{b,c/d}/e'), ['a/b/e', 'a/c/d/e']);
    const all = globToRegExp('styles/**');
    assert.ok(all.test('styles/a.css') && all.test('styles/fonts/x/y.woff2'));
    assert.ok(!all.test('styles') && !all.test('stylesheet/a.css'));
    const one = globToRegExp('styles/*.{xa,xb}.css');
    assert.ok(one.test('styles/tokens.xa.css') && one.test('styles/fonts.xb.css'));
    assert.ok(!one.test('styles/x/tokens.xa.css') && !one.test('styles/tokens.xc.css'));
    const anywhere = globToRegExp('**/node_modules/**');
    assert.ok(anywhere.test('node_modules/a') && anywhere.test('x/y/node_modules/a/b'));
    assert.ok(globToRegExp('a?.txt').test('ab.txt') && !globToRegExp('a?.txt').test('a/.txt'));
    assert.ok(globToRegExp('*').test('.npmrc'));
  });

  it('refuses a glob it cannot read', () => {
    assert.throws(() => globToRegExp('a/{b'), /without its "\}"/);
    assert.throws(() => globToRegExp('/abs/**'), /not a relative glob/);
    assert.throws(() => globToRegExp('a/**x'), /whole segments only/);
  });

  it("selects the files of the zip, the public site's checks among scripts/, and no other pack, test or document", () => {
    const selects = compileList(readIncludeList());
    for (const path of [
      'ui-apps/admin/src/main.ts',
      'ui-apps/admin/src/app/country-specific/default/language.config.ts',
      'ui-apps/info/nginx/default.conf.template',
      'ui-apps/info/Dockerfile',
      'languages/en/i18n/admin/en.json',
      'styles/tokens.default.css',
      'package-lock.json',
      '.npmrc',
      'scripts/info-separation-check.mjs',
      'scripts/info-layout-check.mjs',
      'scripts/info-bundle-report.mjs',
      'scripts/info-container-test.mjs',
      'scripts/build-urls-check.mjs',
      'scripts/build-urls-allowed.json',
      'scripts/glob.mjs',
      'eslint-shared.cjs',
      'ui-apps/admin/e2e/smoke.spec.ts',
      'ui-apps/admin/e2e/auth/keycloak-login.ts',
      'ui-apps/admin/e2e/auth/fixtures.ts',
      'ui-apps/admin/e2e/tsconfig.json',
      'playwright.config.ts',
    ])
      assert.ok(selects(path), `${path} is left out`);
    for (const path of [
      'ui-apps/info/src/app/country-specific/country-index.spec.ts',
      'ui-apps/info/i18n-status/branding-unaccepted.json',
      'ui-apps/admin/e2e/auth/README.md',
      'ui-apps/admin/CLAUDE.md',
      'ui-apps/admin/AGENTS.md',
      'ui-apps/info/AGENTS.md',
      'languages/da/i18n/admin/da.json',
      'docs/backlog.md',
      'scripts/docs-check.mjs',
      'scripts/build-contents-check.mjs',
      'scripts/build-urls-check.test.mjs',
      'scripts/info-bundle-report.test.mjs',
      'scripts/info-separation-check.test.mjs',
      'scripts/perf/publications-requests.mjs',
      'ui-apps/info/Dockerfile.dockerignore',
      'CLAUDE.md',
      'AGENTS.md',
      'README.md',
      'transloco.config.ts',
      'country-kit/pack.test.mjs',
      // The kit is its own package (kit-include.json), no longer in the source zip.
      'country-kit/catalog.mjs',
      'country-kit/setup-kit.mjs',
      'country-kit/templates/setup.mjs',
      'country-kit/example/fe/niord-country.json',
      'country-kit/docs/README.md',
      'country-kit/docs/responsibilities-and-upgrades.md',
      'country-kit/docs/designers-brief.md',
      'country-kit/docs/country-guide.md',
      'country-kit/docs/operators-guide.md',
      'country-kit/templates/AGENTS.md',
      'country-kit/templates/pack.yml',
      'country-kit/templates/base-bump.yml',
      // niord-ng's tooling for the kit's folder at the repository root, and the kit's test helper.
      'country-kit/.gitattributes',
      'country-kit/.prettierrc',
      'country-kit/eslint.config.mjs',
      'country-kit/tooling.test.mjs',
      'country-kit/test-fixtures/base-modules.mjs',
      'country-kit/example/fe/node_modules/x/index.js',
      'country-kit/example/fe/.niord/base/package.json',
    ])
      assert.ok(!selects(path), `${path} is let in`);
  });

  it('carries nothing of the kit: no country-kit/ entry', () => {
    const list = readIncludeList();
    assert.deepEqual(Object.keys(list).sort(), ['exclude', 'format', 'include', 'note']);
    // treeFiles still reads the kit beside fe/, so this proves the list, not the walk.
    const selects = compileList(list);
    const names = treeFiles()
      .map(({ name }) => name)
      .filter(selects);
    assert.deepEqual(
      names.filter((name) => name.startsWith('country-kit/')),
      [],
    );
    // The backend files the kit package carries are its build's, never the source zip's.
    assert.deepEqual(
      names.filter((name) => name.startsWith('backend/') || name.startsWith('be/')),
      [],
    );
  });

  it('refuses an add field as unknown', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'zip-include-'));
    try {
      const file = path.join(dir, 'zip-include.json');
      fs.writeFileSync(
        file,
        JSON.stringify({
          format: 1,
          include: ['a/**'],
          exclude: [],
          add: { 'x/a.mjs': 'scripts/a.mjs' },
        }),
      );
      assert.throws(() => readIncludeList(file), /has the unknown field\(s\) add/);
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });

  it("selects of the tree's scripts/ exactly the public site's checks, the URL allow-list and the glob matcher", () => {
    const selects = compileList(readIncludeList());
    const scripts = fs
      .readdirSync(path.join(FE, 'scripts'), { recursive: true, withFileTypes: true })
      .filter((entry) => entry.isFile())
      .map((entry) =>
        path.relative(FE, path.join(entry.parentPath, entry.name)).replaceAll('\\', '/'),
      );
    assert.deepEqual(scripts.filter(selects).sort(), [
      'scripts/build-urls-allowed.json',
      'scripts/build-urls-check.mjs',
      'scripts/glob.mjs',
      'scripts/info-bundle-report.mjs',
      'scripts/info-container-test.mjs',
      'scripts/info-layout-check.mjs',
      'scripts/info-separation-check.mjs',
    ]);
    // Whatever they import, the zip carries too.
    for (const script of scripts.filter(selects).filter((name) => name.endsWith('.mjs')))
      for (const [, target] of fs
        .readFileSync(path.join(FE, script), 'utf8')
        .matchAll(/\bfrom\s+'(\.[^']+)'|\bimport\(\s*'(\.[^']+)'/g))
        assert.ok(
          selects(path.posix.normalize(path.posix.join('scripts', target))),
          `${script} imports ${target}, which the zip does not carry`,
        );
  });

  it('selects of the kit package what the kit runs and reads, and no test, fixture, guard list, publish tool, harness or root tooling', () => {
    const selects = compileList(readIncludeList(path.join(where().kit, 'kit-include.json')));
    for (const path of [
      'README.md',
      'API-CHANGES.md',
      'setup-kit.mjs',
      'catalog.mjs',
      'init.mjs',
      'pack.mjs',
      'stage-languages.mjs',
      'zip-include.json',
      'kit-include.json',
      'seams.json',
      'security.json',
      'branding.schema.json',
      'niord-country.schema.json',
      'init-answers.schema.json',
      'info-image-allowed.json',
      'secret-scan.json',
      'workspace-files.json',
      'lib/where.mjs',
      'lib/country-lint.cjs',
      'lib/local-sources.mjs',
      'lib/tree.mjs',
      'lib/answers.mjs',
      'lib/scaffold.mjs',
      'lib/image-workflow.mjs',
      'docs/country-guide.md',
      'templates/setup.mjs',
      'templates/AGENTS.md',
      'templates/pack.yml',
      'templates/base-bump.yml',
      'templates/ci.yml',
      'templates/be/pom.xml.tmpl',
      'templates/be/Application.java.tmpl',
      'templates/be/gitignore',
      'templates/be/check-country-build-group.sh',
      'example/fe/niord-country.json',
      'example/fe/setup.mjs',
      'example/be/pom.xml',
    ])
      assert.ok(selects(path), `${path} is left out`);
    for (const path of [
      'source-zip.mjs',
      'country-words.json',
      'pack.test.mjs',
      'answers.test.mjs',
      'scaffold.test.mjs',
      'lib/where.test.mjs',
      'example/acceptance.mjs',
      'example/run.mjs',
      'example/pseudo-localise.mjs',
      'example/run.test.mjs',
      'test-fixtures/base-modules.mjs',
      'lib/fixtures/x.json',
      '.gitattributes',
      '.prettierrc',
      'eslint.config.mjs',
      'example/fe/node_modules/x/index.js',
      'example/fe/.niord/base/package.json',
      // The list is rooted at the kit's top, not at fe/ as zip-include.json is.
      'country-kit/setup-kit.mjs',
      'country-kit/lib/where.mjs',
      'country-kit/docs/country-guide.md',
    ])
      assert.ok(!selects(path), `${path} is let in`);
    // The build adds the contract, the marker and the six backend files; the list selects no
    // backend/ path, so a kit file can never stand in for one of be/'s.
    const backend = Object.values(KIT_BACKEND).map(({ entry }) => entry);
    assert.deepEqual(Object.values(KIT_ADDED), ['docs/country-api.md', 'kit.json', ...backend]);
    for (const name of backend)
      assert.ok(!selects(name), `${name} is selected, but the build adds it`);
  });

  it("carries an end-to-end suite that names no country's test environment", () => {
    const selects = compileList(readIncludeList());
    const suite = fs
      .readdirSync(path.join(FE, 'ui-apps', 'admin', 'e2e'), {
        recursive: true,
        withFileTypes: true,
      })
      .filter((entry) => entry.isFile())
      .map((entry) =>
        path.relative(FE, path.join(entry.parentPath, entry.name)).replaceAll('\\', '/'),
      )
      .filter(selects);
    assert.ok(suite.includes('ui-apps/admin/e2e/auth/fixtures.ts'), suite.join('\n'));
    const files = [...suite, 'playwright.config.ts'].map((name) => ({
      name,
      text: fs.readFileSync(path.join(FE, name), 'utf8'),
    }));
    assert.deepEqual(testEnvironmentNames(files, readCountryWords().words), []);
  });

  it('finds a test environment named in a crafted suite file', () => {
    const found = testEnvironmentNames(
      [
        { name: 'ui-apps/admin/e2e/a.spec.ts', text: "await page.goto('/');\n" },
        {
          name: 'ui-apps/admin/e2e/auth/keycloak-login.ts',
          text: "const URL = process.env['KEYCLOAK_URL'] ?? 'https://login.example-authority.org/auth';\n",
        },
        {
          name: 'ui-apps/admin/e2e/b.spec.ts',
          text: "request.get('https://niord.Harbour.Example/rest');\n",
        },
        { name: 'ui-apps/admin/e2e/c.spec.ts', text: '// against harbour.example in FJORDLAND\n' },
      ],
      ['harbour.example', 'Fjordland'],
    );
    assert.deepEqual(found, [
      'ui-apps/admin/e2e/auth/keycloak-login.ts:1: login.example-authority.org',
      'ui-apps/admin/e2e/b.spec.ts:1: harbour.example',
      'ui-apps/admin/e2e/c.spec.ts:1: harbour.example',
      'ui-apps/admin/e2e/c.spec.ts:1: Fjordland',
    ]);
  });
});

/**
 * Every line of `files` ({ name, text }) that names a country's test or production environment:
 * one of `words`, matched as the country-word guard matches them (a case-insensitive substring of
 * the NFC text), or a Keycloak host of the `login.<domain>` shape, the form a hard-coded default
 * takes. Answers `<file>:<line>: <what>` per finding, a word as the list spells it.
 */
function testEnvironmentNames(files, words) {
  const folded = (text) => text.normalize('NFC').toLowerCase();
  const LOGIN = /\blogin\.[a-z0-9-]+(?:\.[a-z0-9-]+)+/;
  const found = [];
  for (const { name, text } of files)
    text.split('\n').forEach((line, index) => {
      const where = `${name}:${index + 1}`;
      for (const word of words)
        if (folded(line).includes(folded(word))) found.push(`${where}: ${word}`);
      const login = LOGIN.exec(line);
      if (login) found.push(`${where}: ${login[0]}`);
    });
  return found;
}
