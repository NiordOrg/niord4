/**
 * Tests for country-kit/lib/where.mjs, the layout resolver: where the kit finds the base product,
 * the workspace and be/ in each layout it knows, and its refusal of any other place.
 *
 *   npm run test:guards      # from fe/
 *
 * The real kit is the base product's tree; the other layouts are temporary folders, since where()
 * only works out paths and never reads more than the marker.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  backendEntries,
  baseLayout,
  KIT_BACKEND,
  KIT_PACKAGE,
  kitBackendBytes,
  kitBackendFile,
  MARKER,
  markerText,
  where,
  withLineEnds,
} from './lib/where.mjs';

const KIT = path.dirname(fileURLToPath(import.meta.url));
const REPO = path.dirname(KIT);
const FE = path.join(REPO, 'fe');

const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

/** A fresh temporary folder with `relative` made inside it; returns both. */
function folder(relative) {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'kit-where-'));
  temporary.push(tmp);
  const made = path.join(tmp, ...relative.split('/'));
  fs.mkdirSync(made, { recursive: true });
  return { tmp, made };
}

/** A kit folder at `relative` whose marker has the bytes `text`. */
function marked(relative, text) {
  const { tmp, made } = folder(relative);
  fs.writeFileSync(path.join(made, MARKER), text);
  return { tmp, kit: made };
}

describe('where', () => {
  it("resolves the real kit to the base product's tree, which has no marker", () => {
    assert.ok(!fs.existsSync(path.join(KIT, MARKER)), `country-kit/${MARKER} exists`);
    assert.deepEqual(where(), {
      kind: 'source',
      kit: KIT,
      base: FE,
      workspace: FE,
      root: REPO,
      be: path.join(REPO, 'be'),
    });
    assert.equal(where(), where(), 'the default kit is resolved once');
    assert.deepEqual(where(KIT), where());
  });

  it("resolves a country-kit folder beside fe/ as the base product's tree", () => {
    const { tmp, made } = folder('repo/country-kit');
    assert.deepEqual(where(made), {
      kind: 'source',
      kit: made,
      base: path.join(tmp, 'repo', 'fe'),
      workspace: path.join(tmp, 'repo', 'fe'),
      root: path.join(tmp, 'repo'),
      be: path.join(tmp, 'repo', 'be'),
    });
  });

  it('resolves the kit package by its marker at <root>/.niord/kit', () => {
    const { tmp, kit } = marked('c/.niord/kit', markerText('4.0.0-test'));
    assert.deepEqual(where(kit), {
      kind: 'package',
      kit,
      base: path.join(tmp, 'c', 'fe', '.niord', 'base'),
      workspace: path.join(tmp, 'c', 'fe'),
      root: path.join(tmp, 'c'),
      be: path.join(tmp, 'c', 'be'),
      version: '4.0.0-test',
    });
  });

  it('writes the marker as one JSON line naming the package and the version', () => {
    assert.equal(KIT_PACKAGE, 'niord-country-kit');
    assert.equal(markerText('4.0.7'), '{"package":"niord-country-kit","version":"4.0.7"}\n');
  });

  it('refuses a malformed marker, naming the file', () => {
    for (const [text, problem] of [
      ['{"package":"niord-fe-src","version":"4.0.7"}\n', /is not the kit's marker/],
      ['{"package":"niord-country-kit","version":"4.0.7","extra":1}\n', /is not the kit's marker/],
      ['{"package":"niord-country-kit"}\n', /is not the kit's marker/],
      ['{"package":"niord-country-kit","version":"../4.0.7"}\n', /is not the kit's marker/],
      ['{"package":"niord-country-kit","version":7}\n', /is not the kit's marker/],
      ['["niord-country-kit","4.0.7"]\n', /is not the kit's marker/],
      ['{"package":', /is not JSON/],
    ]) {
      const { kit } = marked('c/.niord/kit', text);
      assert.throws(
        () => where(kit),
        (error) => error.message.startsWith(`${path.join(kit, MARKER)} `) && problem.test(error),
        text,
      );
    }
  });

  it('refuses a marker anywhere but <root>/.niord/kit', () => {
    for (const relative of ['c/kit', 'c/.niord/country-kit', 'c/fe/.niord/base/country-kit']) {
      const { kit } = marked(relative, markerText('4.0.7'));
      assert.throws(
        () => where(kit),
        /kit\.json is the kit package's marker, valid only at <root>\/\.niord\/kit/,
        relative,
      );
    }
  });

  it('refuses a kit without a marker in a place it does not know, naming the two it does', () => {
    for (const relative of [
      'somewhere/kit',
      // the kit's place before it moved beside fe/: a stale copy there is never taken for the tree
      'repo/fe/country-kit',
      'c/.niord/fe/country-kit',
      'c/base/kit',
      // a country's unpacked base product holds no kit since the kit package
      'c/fe/.niord/base/country-kit',
    ]) {
      const { made } = folder(relative);
      assert.throws(
        () => where(made),
        (error) =>
          error.message.startsWith(`the kit at ${made} is in no layout it knows: `) &&
          error.message.includes("the base product's tree, <repo>/country-kit beside <repo>/fe") &&
          error.message.includes('the kit package, <root>/.niord/kit with kit.json') &&
          !error.message.includes("a country's unpacked base product"),
        relative,
      );
    }
  });
});

describe('baseLayout', () => {
  it("knows the base product's fe/ and a country's .niord/base/", () => {
    const source = folder('repo/fe');
    assert.deepEqual(baseLayout(source.made), {
      kind: 'source',
      base: source.made,
      workspace: source.made,
      root: path.join(source.tmp, 'repo'),
      be: path.join(source.tmp, 'repo', 'be'),
    });
    const country = folder('c/fe/.niord/base');
    assert.deepEqual(baseLayout(country.made), {
      kind: 'country',
      base: country.made,
      workspace: path.join(country.tmp, 'c', 'fe'),
      root: path.join(country.tmp, 'c'),
      be: path.join(country.tmp, 'c', 'be'),
    });
  });

  it('refuses any other folder, naming it and the layouts it knows', () => {
    for (const relative of ['c/base', 'c/.niord/fe', 'c/somewhere']) {
      const { made } = folder(relative);
      assert.throws(
        () => baseLayout(made),
        (error) =>
          error.message.startsWith(`${made} is not where the kit finds the base product: `) &&
          error.message.includes("the base product's tree, <repo>/country-kit beside <repo>/fe") &&
          error.message.includes('the kit package, <root>/.niord/kit with kit.json'),
        relative,
      );
    }
  });
});

describe('the backend files the kit package carries', () => {
  const BE = path.join(REPO, 'be');
  const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  /** A file's text with its CRLFs read as LF. */
  const lf = (bytes) => bytes.toString('latin1').replaceAll('\r\n', '\n');
  /** The file of a row in the base product's be/. */
  const fromBe = (be, { from }) => path.join(be, ...from.split('/'));
  /** Writes each row's file into `dir` at `relative(row)` with the bytes `bytes(row)`. */
  const write = (dir, relative, bytes) => {
    for (const row of Object.values(KIT_BACKEND)) {
      const file = path.join(dir, ...relative(row).split('/'));
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.writeFileSync(file, bytes(row));
    }
  };

  it("names the six files, each in the base product's be/, and only the shell wrapper executable", () => {
    assert.deepEqual(
      Object.values(KIT_BACKEND).map(({ entry }) => entry),
      [
        'backend/mvnw',
        'backend/mvnw.cmd',
        'backend/maven-wrapper.properties',
        'backend/defaults/base/settings.json',
        'backend/defaults/base/settings-catalog.json',
        'backend/img/company-logo.png',
      ],
    );
    assert.deepEqual(Object.keys(KIT_BACKEND), [
      'mvnw',
      'mvnwCmd',
      'wrapperProperties',
      'baseSettings',
      'settingsCatalog',
      'companyLogo',
    ]);
    for (const row of Object.values(KIT_BACKEND)) {
      assert.ok(fs.statSync(fromBe(BE, row)).isFile(), `be/${row.from} is missing`);
      assert.deepEqual(Object.keys(row), ['entry', 'from', 'eol', 'executable']);
      assert.ok(Object.isFrozen(row), `${row.entry} is not frozen`);
    }
    assert.deepEqual(
      Object.values(KIT_BACKEND)
        .filter(({ executable }) => executable)
        .map(({ entry }) => entry),
      ['backend/mvnw'],
    );
    assert.deepEqual(
      Object.values(KIT_BACKEND).map(({ eol }) => eol),
      ['lf', 'crlf', 'lf', 'lf', 'lf', null],
    );
    assert.ok(Object.isFrozen(KIT_BACKEND));
  });

  it('writes the line ends asked for, byte for byte, and leaves bytes as they are for null', () => {
    const mixed = Buffer.from('a\r\nb\nc', 'latin1');
    assert.equal(withLineEnds(mixed, 'lf').toString('latin1'), 'a\nb\nc');
    assert.equal(withLineEnds(mixed, 'crlf').toString('latin1'), 'a\r\nb\r\nc');
    // A run of CRs before an LF is one line end, so a second pass changes nothing.
    const doubled = Buffer.from('a\r\r\nb\n', 'latin1');
    assert.equal(withLineEnds(doubled, 'lf').toString('latin1'), 'a\nb\n');
    assert.equal(withLineEnds(doubled, 'crlf').toString('latin1'), 'a\r\nb\r\n');
    for (const bytes of [mixed, doubled])
      for (const eol of ['lf', 'crlf'])
        assert.ok(
          withLineEnds(withLineEnds(bytes, eol), eol).equals(withLineEnds(bytes, eol)),
          `${JSON.stringify(bytes.toString('latin1'))} ${eol}`,
        );
    assert.ok(withLineEnds(mixed, null).equals(mixed));
    assert.ok(withLineEnds(PNG, null).equals(PNG));
    // Bytes that are not UTF-8 survive: the text is read and written as latin1.
    const binary = Buffer.from([0xff, 0xfe, 0x0a, 0x80]);
    assert.ok(withLineEnds(binary, 'crlf').equals(Buffer.from([0xff, 0xfe, 0x0d, 0x0a, 0x80])));
    assert.throws(() => withLineEnds(mixed, 'cr'), /"cr" is not a line end/);
  });

  it("resolves the files in the base product's be/, with the package's line ends", () => {
    for (const [key, row] of Object.entries(KIT_BACKEND))
      assert.equal(kitBackendFile(key), fromBe(BE, row), key);
    const cmd = kitBackendBytes('mvnwCmd');
    assert.doesNotMatch(cmd.toString('latin1'), /(^|[^\r])\n/);
    assert.equal(
      cmd.toString('latin1'),
      lf(fs.readFileSync(fromBe(BE, KIT_BACKEND.mvnwCmd))).replaceAll('\n', '\r\n'),
    );
    assert.doesNotMatch(kitBackendBytes('mvnw').toString('latin1'), /\r/);
    assert.ok(
      kitBackendBytes('companyLogo').equals(fs.readFileSync(fromBe(BE, KIT_BACKEND.companyLogo))),
    );
    assert.equal(
      kitBackendBytes('baseSettings').toString('latin1'),
      lf(fs.readFileSync(fromBe(BE, KIT_BACKEND.baseSettings))),
    );
  });

  it("resolves the files in the kit package's backend/, never in the root's be/", () => {
    const { tmp, kit } = marked('c/.niord/kit', markerText('4.0.0-test'));
    write(
      kit,
      ({ entry }) => entry,
      ({ entry }) => (entry.endsWith('.png') ? PNG : 'kit\n'),
    );
    write(
      path.join(tmp, 'c', 'be'),
      ({ from }) => from,
      () => 'be\r\n',
    );
    assert.equal(Object.keys(KIT_BACKEND).length, 6);
    for (const [key, { entry, eol }] of Object.entries(KIT_BACKEND)) {
      assert.equal(kitBackendFile(key, kit), path.join(kit, ...entry.split('/')), key);
      const bytes = kitBackendBytes(key, kit);
      if (eol === null) assert.ok(bytes.equals(PNG), key);
      else assert.equal(bytes.toString('latin1'), eol === 'crlf' ? 'kit\r\n' : 'kit\n', key);
    }
  });

  it('refuses a key it does not know, and a file that is missing', () => {
    for (const lookup of [kitBackendFile, kitBackendBytes])
      assert.throws(
        () => lookup('pom'),
        (error) =>
          error.message ===
          `"pom" is not one of the kit package's backend files (mvnw, mvnwCmd, wrapperProperties, baseSettings, settingsCatalog, companyLogo)`,
      );
    const { kit } = marked('c/.niord/kit', markerText('4.0.0-test'));
    const file = path.join(kit, 'backend', 'mvnw.cmd');
    assert.throws(() => kitBackendBytes('mvnwCmd', kit), {
      message: `${file} is missing: the kit package carries it as backend/mvnw.cmd`,
    });
  });

  it("gives the package's six entries of a be/ folder, and refuses one without a file", () => {
    const entries = backendEntries(BE);
    assert.deepEqual(
      entries.map(({ name }) => name),
      Object.values(KIT_BACKEND).map(({ entry }) => entry),
    );
    for (const [key, { entry }] of Object.entries(KIT_BACKEND))
      assert.ok(entries.find(({ name }) => name === entry).data.equals(kitBackendBytes(key)), key);
    const { made } = folder('c/be');
    write(
      made,
      ({ from }) => from,
      () => 'x\n',
    );
    fs.rmSync(path.join(made, '.mvn', 'wrapper', 'maven-wrapper.properties'));
    assert.throws(() => backendEntries(made), {
      message: `${path.join(made, '.mvn', 'wrapper', 'maven-wrapper.properties')} is missing: the kit package carries it as backend/maven-wrapper.properties`,
    });
  });
});
