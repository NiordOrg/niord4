import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { folderSource, skeleton } from './init.mjs';
import { checkBranding } from './lib/branding.mjs';
import { imageWorkflow } from './lib/image-workflow.mjs';
import { manifestProblems } from './lib/manifest.mjs';
import { parentVersion } from './lib/pins.mjs';
import {
  ANSWERED,
  className,
  countrySettings,
  fill,
  javaPackage,
  packPlugins,
  scaffold,
  USE_BASE_DEFAULT_REASONS,
} from './lib/scaffold.mjs';
import { checkSettingsValues } from './lib/settings-check.mjs';
import { KIT_BACKEND, kitBackendBytes, markerText, where } from './lib/where.mjs';
import { requireFromBase } from './test-fixtures/base-modules.mjs';
import { stepEnv } from '../fe/scripts/workflow-step.mjs';

const yaml = requireFromBase('js-yaml');
const prettier = requireFromBase('prettier');

const KIT = path.dirname(fileURLToPath(import.meta.url));
const VERSION = '4.0.9';
const LEFTOVER = /\{\{[A-Za-z][A-Za-z0-9]*\}\}/;

/** A person's answers with made-up codes: the seven required ones, and any others given. */
const answersOf = (overrides = {}) => ({
  country: 'qq',
  name: 'Qualia',
  organisation: 'The Qualia Hydrographic Office',
  timeZone: 'Europe/London',
  map: { lat: 50, lon: -3 },
  owners: ['@qualia/maintainers', '@someone'],
  ...overrides,
});

const generate = (overrides = {}, version = VERSION) =>
  scaffold({ answers: answersOf(overrides), kit: where().kit, version });

/** The generated files by path, as text. */
const textsOf = (files) => new Map(files.map(({ path: file, data }) => [file, data.toString()]));
const textOf = (overrides, file) => textsOf(generate(overrides)).get(file);

/** The paths of the country `cc` in the order the generator returns them. */
const pathsOf = (cc, pkg, cls) => [
  'be/pom.xml',
  'be/mvnw',
  'be/mvnw.cmd',
  'be/.mvn/wrapper/maven-wrapper.properties',
  'be/.gitattributes',
  'be/.gitignore',
  'be/.java-version',
  'be/overrides.json',
  `be/src/main/java/org/niord/${pkg}/${cls}.java`,
  'be/src/main/resources/application.yml',
  'be/src/main/resources/application-local.yml',
  'be/src/main/resources/defaults/country/settings.json',
  'be/src/main/resources/defaults/country/README.md',
  'be/bootstrap/README.md',
  'be/bootstrap/batch-set.json',
  'be/bootstrap/domains.json',
  'be/scripts/check-country-build-group.sh',
  'fe/niord-country.json',
  'fe/info/app/info.config.ts',
  '.github/CODEOWNERS',
  '.github/workflows/image.yml',
  '.github/workflows/ci.yml',
  'README.md',
];

/** Every file under `dir`, relative, with its size and modification time. */
function snapshot(dir) {
  return fs
    .readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) => {
      const file = path.join(entry.parentPath, entry.name);
      const stat = fs.statSync(file);
      return `${path.relative(dir, file)} ${stat.size} ${stat.mtimeMs}`;
    })
    .sort();
}

describe('the scaffold: the files and their modes', () => {
  it('returns the 23 files in the order init writes them, each a Buffer with its mode', () => {
    const files = generate();
    assert.deepEqual(
      files.map(({ path: file }) => file),
      pathsOf('qq', 'qq', 'NiordQqApplication'),
    );
    for (const { path: file, data, mode } of files) {
      assert.ok(Buffer.isBuffer(data), file);
      assert.ok(!file.includes('\\') && !path.isAbsolute(file), file);
      const executable = file === 'be/mvnw' || file === 'be/scripts/check-country-build-group.sh';
      assert.equal(mode, executable ? 0o755 : 0o644, file);
    }
  });

  it('writes nothing: neither the kit nor the working folder changes', () => {
    const cwd = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-scaffold-cwd-'));
    const before = snapshot(path.join(KIT, 'templates'));
    const previous = process.cwd();
    process.chdir(cwd);
    try {
      generate({ languages: ['da', 'kl'], producerCode: 'AB12' });
    } finally {
      process.chdir(previous);
    }
    assert.deepEqual(fs.readdirSync(cwd), []);
    assert.deepEqual(snapshot(path.join(KIT, 'templates')), before);
  });

  it('copies the Maven wrapper as the kit package carries it, and neither settings file nor the logo', () => {
    const files = new Map(generate().map(({ path: file, data }) => [file, data]));
    for (const [file, key] of [
      ['be/mvnw', 'mvnw'],
      ['be/mvnw.cmd', 'mvnwCmd'],
      ['be/.mvn/wrapper/maven-wrapper.properties', 'wrapperProperties'],
    ])
      assert.ok(files.get(file).equals(kitBackendBytes(key, where().kit)), file);
    assert.ok(!files.get('be/mvnw').includes('\r'));
    const cmd = files.get('be/mvnw.cmd').toString('latin1');
    assert.ok(cmd.length > 0);
    assert.ok(!/[^\r]\n/.test(cmd) && !cmd.startsWith('\n'), 'every line of mvnw.cmd ends in CRLF');
    for (const file of files.keys()) {
      assert.ok(!file.startsWith('backend/'), file);
      assert.ok(!file.endsWith('defaults/base/settings.json'), file);
      assert.ok(!file.endsWith('settings-catalog.json'), file);
      assert.ok(!file.endsWith('company-logo.png'), file);
    }
  });

  it('writes the text files with LF line ends only, but mvnw.cmd', () => {
    for (const { path: file, data } of generate({ languages: ['da'] }))
      if (file !== 'be/mvnw.cmd') assert.ok(!data.includes('\r'), file);
  });

  it('writes no third-party Maven folder, no branding file and no copyright line of its own', () => {
    // The Maven wrapper's two scripts carry the Apache licence header they are published with.
    const wrapper = ['be/mvnw', 'be/mvnw.cmd'];
    for (const { path: file, data } of generate({ languages: ['da'] })) {
      assert.ok(!file.startsWith('be/third-party-maven/'), file);
      assert.ok(!/\/assets\//.test(file), file);
      if (!wrapper.includes(file)) assert.ok(!/copyright|©/i.test(data.toString()), file);
    }
  });
});

describe('the scaffold: the settings file', () => {
  const be = where().be;
  const treeFile = (key) => fs.readFileSync(path.join(be, ...KIT_BACKEND[key].from.split('/')));
  const base = JSON.parse(treeFile('baseSettings').toString());
  const catalog = JSON.parse(treeFile('settingsCatalog').toString());
  const settingsOf = (overrides) =>
    JSON.parse(textOf(overrides, 'be/src/main/resources/defaults/country/settings.json'));

  it("reads the tree's base settings file and catalog, which the kit package carries unchanged", () => {
    for (const key of ['baseSettings', 'settingsCatalog'])
      assert.equal(
        treeFile(key).toString().replace(/\r\n/g, '\n'),
        kitBackendBytes(key, where().kit).toString(),
        key,
      );
  });

  it("answers for every key of the tree's base settings file, once, in its order", () => {
    const settings = settingsOf({ languages: ['da'] });
    assert.deepEqual(Object.keys(settings), ['values', 'useBaseDefault']);
    const listed = settings.useBaseDefault.map(({ key }) => key);
    for (const key of Object.keys(base.values))
      assert.ok(
        Object.hasOwn(settings.values, key) !== listed.includes(key),
        `${key} is in values or in useBaseDefault, not both and not neither`,
      );
    assert.deepEqual(
      [...Object.keys(settings.values), ...listed].sort(),
      Object.keys(base.values).sort(),
    );
    const order = Object.keys(base.values);
    const ordered = (keys) =>
      keys.every((key, i) => i === 0 || order.indexOf(keys[i - 1]) < order.indexOf(key));
    assert.ok(ordered(Object.keys(settings.values)));
    assert.ok(ordered(listed));
  });

  it("sets every required key of the tree's catalog", () => {
    const required = catalog.settings.filter((entry) => entry.required).map(({ key }) => key);
    assert.ok(required.includes('country') && required.includes('organisation'));
    const { values } = settingsOf();
    for (const key of required) assert.ok(Object.hasOwn(values, key), key);
  });

  it('gives every key left to the base default its reason from the table: a new base key fails here', () => {
    for (const { key, reason } of settingsOf().useBaseDefault)
      assert.equal(
        reason,
        USE_BASE_DEFAULT_REASONS[key],
        `${key} has a reason in USE_BASE_DEFAULT_REASONS`,
      );
    assert.equal(Object.keys(USE_BASE_DEFAULT_REASONS).length, 15);
    for (const reason of Object.values(USE_BASE_DEFAULT_REASONS)) assert.ok(reason.trim());
  });

  it('sets exactly the eleven answered values', () => {
    assert.deepEqual(
      settingsOf({ languages: ['da', 'kl'], map: { lat: -12, lon: 130, zoom: 5 } }).values,
      {
        defaultLanguage: 'en',
        'niord.home': '${NIORD_HOME}',
        country: 'QQ',
        organisation: 'The Qualia Hydrographic Office',
        modelLanguages: ['en', 'da', 'kl'],
        siteLanguages: ['en', 'da', 'kl'],
        infoSiteLanguages: ['en', 'da', 'kl'],
        infoDefaultLanguage: 'en',
        mapDefaultZoomLevel: 5,
        mapDefaultLatitude: -12,
        mapDefaultLongitude: 130,
      },
    );
    assert.equal(Object.keys(ANSWERED).length, 11);
  });

  const complete = {
    country: 'qq',
    organisation: 'O',
    languages: [],
    map: { lat: 1, lon: 2, zoom: 3 },
  };
  const entry = (key, extra = {}) => ({
    key,
    type: 'String',
    scope: 'operator',
    required: false,
    ...extra,
  });
  const syntheticCatalog = (extra = []) => ({
    settings: [...Object.keys(ANSWERED).map((key) => entry(key)), ...extra],
  });

  it("gives a base key the table does not know the base product's default at the version", () => {
    const { useBaseDefault } = countrySettings({
      answers: complete,
      baseSettings: { values: { country: 'XX', newKey: 1 } },
      catalog: syntheticCatalog([entry('newKey')]),
      version: VERSION,
    });
    assert.deepEqual(useBaseDefault, [
      { key: 'newKey', reason: "the base product's default at 4.0.9" },
    ]);
  });

  it('refuses a required catalog key no answer sets, naming it', () => {
    assert.throws(
      () =>
        countrySettings({
          answers: complete,
          baseSettings: { values: {} },
          catalog: syntheticCatalog([entry('registryUrl', { required: true })]),
          version: VERSION,
        }),
      /registryUrl/,
    );
  });

  it('refuses an answered key the catalog no longer declares, or declares as state', () => {
    const declared = syntheticCatalog().settings;
    assert.throws(
      () =>
        countrySettings({
          answers: complete,
          baseSettings: { values: {} },
          catalog: { settings: declared.filter(({ key }) => key !== 'mapDefaultZoomLevel') },
          version: VERSION,
        }),
      /mapDefaultZoomLevel/,
    );
    assert.throws(
      () =>
        countrySettings({
          answers: complete,
          baseSettings: { values: {} },
          catalog: {
            settings: declared.map((e) =>
              e.key === 'organisation' ? { ...e, scope: 'state' } : e,
            ),
          },
          version: VERSION,
        }),
      /organisation/,
    );
  });
});

/**
 * The rules DefaultSettingsChecks.check applies to a country's settings file
 * (be/niord/src/test/java/org/niord/core/defaults/DefaultSettingsChecks.java, check, values,
 * checkValues and checkRequired, with SettingsCatalog.hasType), re-stated so the generated file is
 * held to them without a Maven build: `{ errors, warnings }`, each `{ key, rule }`.
 */
function settingsFindings({ catalog, baseFile, countryFile }) {
  const errors = [];
  const warnings = [];
  const error = (key, rule) => errors.push({ key, rule });
  const entries = new Map(catalog.settings.map((e) => [e.key, e]));
  const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
  const secrets = [
    /-----BEGIN [A-Z ]*PRIVATE KEY-----/,
    /eyJ[A-Za-z0-9_-]*\.[A-Za-z0-9_-]+\./,
    /AKIA[0-9A-Z]{16}/,
    /gh[pousr]_[A-Za-z0-9]{36,}/,
    /xox[abprs]-/,
    /[A-Za-z][A-Za-z0-9+.-]*:\/\/[^/\s:@]+:[^/\s@]+@/,
  ];
  const strings = (value, out = []) => {
    if (typeof value === 'string') out.push(value);
    else if (value !== null && typeof value === 'object')
      Object.values(value).forEach((v) => strings(v, out));
    return out;
  };
  const hasType = (type, value) => {
    if (value === null) return true;
    switch (type) {
      case 'String':
      case 'Password':
      case 'Path':
        return typeof value === 'string';
      case 'Integer':
        return Number.isInteger(value) && value >= -(2 ** 31) && value <= 2 ** 31 - 1;
      case 'Long':
      case 'Date':
        return Number.isInteger(value);
      case 'Float':
      case 'Double':
        return typeof value === 'number';
      case 'Boolean':
        return typeof value === 'boolean';
      case 'json':
        return true;
      default:
        throw new Error(`no type ${type}`);
    }
  };
  const loopback = (host) => {
    const h = host.toLowerCase();
    return h === 'localhost' || h.endsWith('.localhost') || h.startsWith('127.') || h === '[::1]';
  };

  let values = {};
  if (!isObject(countryFile)) error('', 'format');
  else {
    for (const member of Object.keys(countryFile))
      if (member !== 'values' && member !== 'useBaseDefault') error(member, 'format');
    if (isObject(countryFile.values)) values = countryFile.values;
    else error('values', 'format');
  }
  for (const [key, value] of Object.entries(values)) {
    const declared = entries.get(key);
    if (!declared) error(key, 'undeclared-key');
    else {
      if (declared.scope === 'state') error(key, 'state-key');
      if (declared.type && value !== null && !hasType(declared.type, value)) error(key, 'type');
      if (declared.type === 'Password' && value !== null && value !== '') error(key, 'secret');
    }
    for (const text of strings(value)) {
      for (const pattern of secrets) if (pattern.test(text)) error(key, 'secret');
      for (const match of text.matchAll(/\bhttp:\/\/(\[[^\]]*]|[^/:?#\s]+)/gi))
        if (!loopback(match[1])) warnings.push({ key, rule: 'https' });
    }
  }
  const listed = new Set();
  const list = isObject(countryFile) ? countryFile.useBaseDefault : undefined;
  if (list !== undefined && !Array.isArray(list)) error('useBaseDefault', 'format');
  else
    for (const item of list ?? []) {
      const key = isObject(item) && typeof item.key === 'string' ? item.key : null;
      const reason = isObject(item) && typeof item.reason === 'string' ? item.reason.trim() : '';
      if (key === null || key.trim() === '') {
        error('useBaseDefault', 'use-base-default');
        continue;
      }
      if (reason === '') error(key, 'use-base-default');
      if (listed.has(key)) {
        error(key, 'use-base-default');
        continue;
      }
      listed.add(key);
      const declared = entries.get(key);
      if (!declared) error(key, 'undeclared-key');
      else if (declared.required) error(key, 'use-base-default');
    }
  for (const key of Object.keys(baseFile.values))
    if (!Object.hasOwn(values, key) && !listed.has(key)) error(key, 'missing-key');
  for (const declared of entries.values())
    if (declared.required && !Object.hasOwn(values, declared.key) && !listed.has(declared.key))
      error(declared.key, 'required');
  return { errors, warnings };
}

describe('the scaffold: the settings file passes the rules DefaultSettingsChecks applies', () => {
  const be = where().be;
  const read = (key) =>
    JSON.parse(fs.readFileSync(path.join(be, ...KIT_BACKEND[key].from.split('/')), 'utf8'));
  const catalog = read('settingsCatalog');
  const baseFile = read('baseSettings');

  it('finds each rule on a broken file (the re-stated checks are not vacuous)', () => {
    const small = {
      settings: [
        { key: 'country', type: 'String', scope: 'operator', required: true },
        { key: 'organisation', type: 'String', scope: 'operator', required: true },
        { key: 'zoom', type: 'Integer', scope: 'operator', required: false },
        { key: 'secret', type: 'Password', scope: 'operator', required: false },
        { key: 'url', type: 'String', scope: 'operator', required: false },
        { key: 'lastRun', type: 'Date', scope: 'state', required: false },
        { key: 'kept', type: 'json', scope: 'operator', required: false },
      ],
    };
    const smallBase = { values: { country: 'XX', organisation: 'O', zoom: 6, kept: {} } };
    const rules = (countryFile) =>
      settingsFindings({ catalog: small, baseFile: smallBase, countryFile }).errors.map(
        ({ key, rule }) => `${key} ${rule}`,
      );
    const fine = {
      values: { country: 'QQ', organisation: 'O', zoom: 6 },
      useBaseDefault: [{ key: 'kept', reason: 'r' }],
    };
    assert.deepEqual(rules(fine), []);
    assert.ok(rules([]).includes(' format'));
    assert.ok(rules({ ...fine, extra: 1 }).includes('extra format'));
    assert.ok(rules({ ...fine, values: [] }).includes('values format'));
    assert.ok(rules({ ...fine, useBaseDefault: {} }).includes('useBaseDefault format'));
    assert.ok(
      rules({ ...fine, values: { ...fine.values, unknown: 1 } }).includes('unknown undeclared-key'),
    );
    assert.ok(
      rules({
        ...fine,
        useBaseDefault: [...fine.useBaseDefault, { key: 'unknown', reason: 'r' }],
      }).includes('unknown undeclared-key'),
    );
    assert.ok(
      rules({ ...fine, values: { ...fine.values, lastRun: 1 } }).includes('lastRun state-key'),
    );
    assert.ok(rules({ ...fine, values: { ...fine.values, zoom: '6' } }).includes('zoom type'));
    assert.ok(rules({ ...fine, values: { ...fine.values, zoom: 6.5 } }).includes('zoom type'));
    assert.ok(rules({ ...fine, values: { ...fine.values, zoom: 2 ** 31 } }).includes('zoom type'));
    assert.deepEqual(rules({ ...fine, values: { ...fine.values, zoom: null } }), []);
    assert.ok(
      rules({ ...fine, values: { ...fine.values, secret: 'x' } }).includes('secret secret'),
    );
    // Each sample is joined at run time, so the kit's own secret scan reads no secret in this file
    for (const leak of [
      ['-----BEGIN ', 'RSA PRIVATE', ' KEY-----'].join(''),
      'eyJhbGciOi.eyJzdWIiOi.sig',
      ['AK', 'IA', 'ABCDEFGHIJKLMNOP'].join(''),
      `ghp_${'a'.repeat(36)}`,
      'xoxb-1',
      ['https://user:', 'pass', '@host/'].join(''),
    ])
      assert.ok(
        rules({ ...fine, values: { ...fine.values, url: leak } }).includes('url secret'),
        leak,
      );
    assert.ok(
      rules({ values: { country: 'QQ', organisation: 'O', zoom: 6 } }).includes('kept missing-key'),
    );
    assert.ok(
      rules({ ...fine, useBaseDefault: [{ key: 'kept', reason: ' ' }] }).includes(
        'kept use-base-default',
      ),
    );
    assert.ok(
      rules({ ...fine, useBaseDefault: [{ reason: 'r' }, ...fine.useBaseDefault] }).includes(
        'useBaseDefault use-base-default',
      ),
    );
    assert.ok(
      rules({ ...fine, useBaseDefault: [...fine.useBaseDefault, ...fine.useBaseDefault] }).includes(
        'kept use-base-default',
      ),
    );
    assert.ok(
      rules({
        values: { organisation: 'O', zoom: 6 },
        useBaseDefault: [...fine.useBaseDefault, { key: 'country', reason: 'r' }],
      }).includes('country use-base-default'),
    );
    assert.ok(
      rules({ ...fine, values: { organisation: 'O', zoom: 6 } }).includes('country required'),
    );
    assert.deepEqual(
      settingsFindings({
        catalog: small,
        baseFile: smallBase,
        countryFile: { ...fine, values: { ...fine.values, url: 'http://example.org/' } },
      }).warnings,
      [{ key: 'url', rule: 'https' }],
    );
  });

  for (const languages of [[], ['da', 'kl']])
    it(`passes them with the languages [${languages}]: no error and no https warning`, () => {
      const countryFile = JSON.parse(
        textOf({ languages }, 'be/src/main/resources/defaults/country/settings.json'),
      );
      assert.deepEqual(settingsFindings({ catalog, baseFile, countryFile }), {
        errors: [],
        warnings: [],
      });
    });
});

/**
 * What is wrong with `text` as an XML document, strictly: the XML declaration first, comments
 * without `--` inside, start, end and self-closing tags balanced, every attribute quoted, `&` only
 * as an entity, one root element, `<project>`.
 */
function xmlProblems(text) {
  const problems = [];
  const declaration = '<?xml version="1.0" encoding="UTF-8"?>\n';
  if (!text.startsWith(declaration)) problems.push('no XML declaration');
  const token =
    /<!--([\s\S]*?)-->|<([A-Za-z][\w.:-]*)((?:\s+[\w.:-]+\s*=\s*(?:"[^"<]*"|'[^'<]*'))*)\s*(\/?)>|<\/([A-Za-z][\w.:-]*)\s*>|([^<]+)|(<)/y;
  const entity = /&(?!(?:amp|lt|gt|quot|apos|#[0-9]+|#x[0-9A-Fa-f]+);)/;
  const stack = [];
  const roots = [];
  token.lastIndex = text.startsWith(declaration) ? declaration.length : 0;
  let match;
  while (token.lastIndex < text.length && (match = token.exec(text))) {
    const [, comment, open, attributes, selfClosing, close, chars, stray] = match;
    if (comment !== undefined) {
      if (comment.includes('--') || comment.endsWith('-')) problems.push('a comment holds --');
    } else if (open !== undefined) {
      if (entity.test(attributes)) problems.push(`<${open}> has a bare & in an attribute`);
      if (stack.length === 0) roots.push(open);
      if (!selfClosing) stack.push(open);
    } else if (close !== undefined) {
      const top = stack.pop();
      if (top !== close) problems.push(`</${close}> closes <${top}>`);
    } else if (chars !== undefined) {
      if (entity.test(chars)) problems.push('a bare & in text');
      if (stack.length === 0 && chars.trim()) problems.push('text outside the root');
    } else if (stray !== undefined) {
      problems.push(`a < that starts no tag at ${match.index}`);
      break;
    }
  }
  if (stack.length) problems.push(`unclosed: ${stack.join(' ')}`);
  if (roots.length !== 1 || roots[0] !== 'project')
    problems.push(`the roots are ${roots.join(' ')}`);
  return problems;
}

/** The pom's <dependency> blocks: `groupId:artifactId[:type][:scope]`, `!` after an exclusion. */
const dependenciesOf = (pom) =>
  [...pom.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)].map(([, body]) => {
    const own = body.replace(/<exclusions>[\s\S]*?<\/exclusions>/, '');
    const tag = (name) => own.match(new RegExp(`<${name}>([^<]*)</${name}>`))?.[1];
    const excluded =
      /<exclusion>\s*<groupId>io\.quarkus<\/groupId>\s*<artifactId>quarkus-junit4-mock<\/artifactId>/.test(
        body,
      );
    return (
      [tag('groupId'), tag('artifactId'), tag('type'), tag('scope')].filter(Boolean).join(':') +
      (excluded ? '!' : '')
    );
  });

describe('the scaffold: the pom', () => {
  for (const languages of [[], ['da'], ['da', 'kl']])
    describe(`with the languages [${languages}]`, () => {
      const pom = textOf({ languages }, 'be/pom.xml');

      it('is well-formed, with no slot left', () => {
        assert.deepEqual(xmlProblems(pom), []);
        assert.ok(!LEFTOVER.test(pom) && !pom.includes('{{'));
      });

      it('pins the version as its parent, where set-up and the image job read it', () => {
        assert.equal(parentVersion(pom), VERSION);
        const lines = pom.split('\n');
        const at = lines.findIndex((line) =>
          line.includes('<artifactId>niord-parent</artifactId>'),
        );
        const version = lines
          .slice(at, at + 3)
          .map((line) => line.match(/.*<version>(.*)<\/version>.*/)?.[1])
          .find(Boolean);
        assert.equal(version, VERSION);
        assert.match(
          pom,
          /<parent>\s*<groupId>org\.niord<\/groupId>\s*<artifactId>niord-parent<\/artifactId>\s*<version>4\.0\.9<\/version>\s*<relativePath\/>\s*<\/parent>/,
        );
      });

      it("is the country's deployable, read from the base product's registry only", () => {
        assert.match(
          pom,
          /<\/parent>[\s\S]*?<groupId>org\.niord\.qq<\/groupId>\s*<artifactId>niord-qq<\/artifactId>\s*<packaging>jar<\/packaging>/,
        );
        const repositories = [...pom.matchAll(/<repository>([\s\S]*?)<\/repository>/g)].map(
          ([, r]) => r,
        );
        assert.equal(repositories.length, 1);
        assert.match(repositories[0], /<id>github<\/id>/);
        assert.match(
          repositories[0],
          /<url>https:\/\/maven\.pkg\.github\.com\/NiordOrg\/niord<\/url>/,
        );
        assert.match(repositories[0], /<snapshots><enabled>false<\/enabled><\/snapshots>/);
        assert.match(repositories[0], /<releases><enabled>true<\/enabled><\/releases>/);
        for (const absent of [
          'grad-mirror',
          'third-party-maven',
          'maven-dependency-plugin',
          'maven-clean-plugin',
          'niord-bootstrap',
        ])
          assert.ok(!pom.includes(absent), absent);
      });

      it('has the properties and dependencies a country build needs', () => {
        assert.match(pom, /<artemis\.version>2\.54\.0<\/artemis\.version>/);
        assert.match(
          pom,
          /<testcontainers-keycloak\.version>3\.4\.0<\/testcontainers-keycloak\.version>/,
        );
        assert.match(pom, /<maven\.deploy\.skip>true<\/maven\.deploy\.skip>/);
        assert.deepEqual(dependenciesOf(pom), [
          'org.niord.base:niord-base',
          'org.niord.base:niord-base:test-jar:test',
          'org.postgresql:postgresql:runtime',
          'org.springframework.boot:spring-boot-starter-liquibase',
          'org.springframework.boot:spring-boot-starter-artemis',
          'org.apache.artemis:artemis-jakarta-server',
          'org.springframework.boot:spring-boot-starter-test:test',
          'org.springframework.security:spring-security-test:test',
          'org.springframework.boot:spring-boot-testcontainers:test',
          'org.testcontainers:testcontainers:test',
          'org.testcontainers:testcontainers-postgresql:test',
          'org.testcontainers:testcontainers-junit-jupiter:test!',
          'com.github.dasniko:testcontainers-keycloak:test!',
          'io.rest-assured:rest-assured:test',
          'org.awaitility:awaitility:test',
        ]);
        assert.equal((pom.match(/<version>\$\{project\.version\}<\/version>/g) ?? []).length, 2);
        assert.match(
          pom,
          /<artifactId>testcontainers-keycloak<\/artifactId>\s*<version>\$\{testcontainers-keycloak\.version\}<\/version>/,
        );
        assert.match(pom, /<artifactId>rest-assured<\/artifactId>\s*<version>6\.0\.0<\/version>/);
        assert.match(
          pom,
          /<plugin>\s*<groupId>org\.springframework\.boot<\/groupId>\s*<artifactId>spring-boot-maven-plugin<\/artifactId>\s*<\/plugin>/,
        );
      });

      it("copies each pack's backend half and flag, and checks the flag only", () => {
        const ids = [...pom.matchAll(/<id>(copy-[^<]*)<\/id>/g)].map(([, id]) => id);
        assert.deepEqual(
          ids,
          languages.flatMap((lang) => [`copy-${lang}-pack-backend`, `copy-${lang}-pack-flag`]),
        );
        const available = pom.split('\n').filter((line) => line.includes('<available'));
        assert.deepEqual(
          available.map((line) => line.trim()),
          languages.map(
            (lang) =>
              `<available file="\${project.basedir}/../fe/.niord/packs/${lang}/img/flags/${lang}.png" type="file"/>`,
          ),
        );
        assert.ok(!pom.includes('type="dir"'));
        for (const lang of languages) {
          assert.ok(
            pom.includes(
              `<outputDirectory>\${project.build.outputDirectory}/defaults/packs/${lang}</outputDirectory>`,
            ),
          );
          assert.ok(
            pom.includes(
              `<directory>\${project.basedir}/../fe/.niord/packs/${lang}/backend</directory>`,
            ),
          );
          assert.ok(
            pom.includes(
              `<directory>\${project.basedir}/../fe/.niord/packs/${lang}/img/flags</directory>`,
            ),
          );
          assert.ok(pom.includes(`<include>${lang}.png</include>`));
        }
        const plugins = ['maven-resources-plugin', 'maven-antrun-plugin'];
        for (const plugin of plugins)
          assert.equal(
            pom.includes(`<artifactId>${plugin}</artifactId>`),
            languages.length > 0,
            plugin,
          );
        if (languages.length) {
          assert.match(pom, /<id>require-staged-packs<\/id>\s*<phase>validate<\/phase>/);
          assert.ok(
            pom.includes(
              '<fail message="fe/.niord/packs/ lacks a pack this build copies: run node fe/setup.mjs first">',
            ),
          );
          assert.ok(!/maven-antrun-plugin<\/artifactId>\s*<version>/.test(pom));
          assert.equal(
            (pom.match(/<phase>process-resources<\/phase>/g) ?? []).length,
            2 * languages.length,
          );
          assert.equal(
            (pom.match(/<filtering>false<\/filtering>/g) ?? []).length,
            2 * languages.length,
          );
        }
      });
    });

  it('leaves no blank line where the language block is empty', () => {
    assert.match(textOf({}, 'be/pom.xml'), /<\/plugin>\n {8}<\/plugins>/);
    assert.equal(packPlugins([]), '');
    assert.ok(packPlugins(['da']).endsWith('\n'));
  });
});

describe('the scaffold: the application and its configuration', () => {
  it('turns S-124 on with and without a producer code, the code only when answered', () => {
    const plain = yaml.load(textOf({}, 'be/src/main/resources/application.yml'));
    assert.equal(plain.spring.application.name, 'niord-qq');
    assert.equal(plain.niord.s124.enabled, true);
    assert.ok(!Object.hasOwn(plain.niord.s124, 'producer-code'));
    const coded = yaml.load(
      textOf({ producerCode: 'AB12' }, 'be/src/main/resources/application.yml'),
    );
    assert.equal(coded.niord.s124.enabled, true);
    assert.equal(coded.niord.s124['producer-code'], 'AB12');
  });

  it('keeps a producer code a string through YAML, whatever it looks like', () => {
    // The validator accepts these; unquoted, YAML would read a number, a boolean and null
    for (const producerCode of ['0123', 'TRUE', 'NULL', '1E10']) {
      const read = yaml.load(textOf({ producerCode }, 'be/src/main/resources/application.yml'));
      assert.strictEqual(read.niord.s124['producer-code'], producerCode, producerCode);
    }
  });

  it('names the package after the code, with _ after a Java keyword', () => {
    assert.equal(javaPackage('do'), 'do_');
    assert.equal(javaPackage('if'), 'if_');
    assert.equal(javaPackage('qq'), 'qq');
    assert.equal(className('do'), 'NiordDoApplication');
    const files = textsOf(generate({ country: 'do' }));
    const java = files.get('be/src/main/java/org/niord/do_/NiordDoApplication.java');
    assert.ok(java, [...files.keys()].join('\n'));
    assert.match(java, /^package org\.niord\.do_;$/m);
    assert.match(java, /^public class NiordDoApplication \{$/m);
    assert.match(java, /SpringApplication\.run\(NiordDoApplication\.class, args\);/);
    assert.deepEqual([...files.keys()], pathsOf('do', 'do_', 'NiordDoApplication'));
    const qq = textOf({}, 'be/src/main/java/org/niord/qq/NiordQqApplication.java');
    assert.match(qq, /^package org\.niord\.qq;$/m);
    for (const line of [
      '@SpringBootApplication(scanBasePackages = "org.niord")',
      '@EntityScan("org.niord")',
      '@EnableScheduling',
      '@EnableAsync',
      'import org.springframework.boot.persistence.autoconfigure.EntityScan;',
    ])
      assert.ok(qq.split('\n').includes(line), line);
    assert.ok(!qq.includes('Qualia'), 'the javadoc names the code, not the free-text name');
  });

  it('writes the local profile for niord_<cc>, the local realm and the mail catcher', () => {
    const local = yaml.load(textOf({}, 'be/src/main/resources/application-local.yml'));
    assert.equal(local.server.address, '127.0.0.1');
    assert.equal(local.spring.datasource.url, 'jdbc:postgresql://localhost:5432/niord_qq');
    assert.equal(
      local.spring.security.oauth2.resourceserver.jwt['issuer-uri'],
      'http://localhost:8080/auth/realms/niord',
    );
    assert.equal(local.spring.mail.port, 1025);
    assert.equal(local.niord.smtp.port, 1025);
    assert.equal(local.spring.jpa.properties['jakarta.persistence.validation.mode'], 'none');
  });

  it("writes the build's small files", () => {
    const files = textsOf(generate());
    assert.equal(files.get('be/.java-version'), '25\n');
    const ignored = files.get('be/.gitignore').split('\n');
    assert.ok(ignored.includes('target/') && ignored.includes('.flattened-pom.xml'));
    const rules = files
      .get('be/.gitattributes')
      .split('\n')
      .filter((line) => line && !line.startsWith('#'))
      .map((line) => line.split(/\s+/).join(' '));
    assert.deepEqual(rules, [
      'bootstrap/** text=auto eol=lf',
      'mvnw text eol=lf',
      'mvnw.cmd text eol=crlf',
    ]);
    assert.deepEqual(JSON.parse(files.get('be/overrides.json')), { overrides: [] });
  });
});

describe('the scaffold: the bootstrap and the group script', () => {
  it('bootstraps one domain with one series, in the time zone as the person spelt it', () => {
    const files = textsOf(
      generate({
        timeZone: 'America/Argentina/Buenos_Aires',
        map: { lat: -34, lon: -58, zoom: 7 },
        domain: { id: 'qq-nm', name: 'Qualia Notices', mainType: 'NM' },
        series: {
          id: 'qq-nm-series',
          shortFormat: 'QQ-${number}-${year}',
          numberSequenceType: 'CONTINUOUS',
        },
      }),
    );
    assert.deepEqual(JSON.parse(files.get('be/bootstrap/batch-set.json')), [
      { jobName: 'domain-import', fileName: 'domains.json' },
    ]);
    assert.deepEqual(JSON.parse(files.get('be/bootstrap/domains.json')), [
      {
        domainId: 'qq-nm',
        name: 'Qualia Notices',
        active: true,
        publish: true,
        sortOrder: 1,
        timeZone: 'America/Argentina/Buenos_Aires',
        lat: -34,
        lon: -58,
        zoomLevel: 7,
        messageSeries: [
          {
            seriesId: 'qq-nm-series',
            mainType: 'NM',
            shortFormat: 'QQ-${number}-${year}',
            numberSequenceType: 'CONTINUOUS',
          },
        ],
      },
    ]);
    assert.equal(
      files.get('be/bootstrap/README.md'),
      fs.readFileSync(path.join(KIT, 'templates', 'bootstrap-README.md'), 'utf8'),
    );
  });

  it("is the guide's neutral group script", () => {
    const guide = fs.readFileSync(path.join(KIT, 'docs', 'country-guide-in-depth.md'), 'utf8');
    const script = guide.match(
      /```bash\n(#!\/usr\/bin\/env bash\n# Fails unless the country build group[\s\S]*?)```/,
    )?.[1];
    assert.ok(script, "the guide's script");
    const generated = textOf({}, 'be/scripts/check-country-build-group.sh');
    assert.equal(generated, script);
    assert.ok(!/test-it/.test(generated));
    assert.ok(!/\b(?:dk|gl|qq)\b/i.test(generated));
  });
});

describe('the scaffold: the frontend and repository files', () => {
  it('writes a manifest that passes its checks, English first and every language pinned', () => {
    for (const languages of [[], ['da'], ['da', 'kl']]) {
      const files = textsOf(generate({ languages }));
      const manifest = JSON.parse(files.get('fe/niord-country.json'));
      assert.deepEqual(manifestProblems(manifest), [], `[${languages}]`);
      const all = ['en', ...languages];
      assert.deepEqual(manifest, {
        country: 'qq',
        name: 'Qualia',
        apps: {
          admin: { uiLanguages: all, contentLanguages: all, defaultLanguage: 'en' },
          info: { uiLanguages: all, defaultLanguage: 'en' },
        },
        packs: Object.fromEntries(languages.map((lang) => [lang, { pinned: true }])),
        locales: {},
        overrides: [],
      });
      const { values } = JSON.parse(
        files.get('be/src/main/resources/defaults/country/settings.json'),
      );
      assert.deepEqual(
        checkSettingsValues({ values, manifest, backendHalf: () => false }).errors,
        [],
      );
      assert.deepEqual(checkSettingsValues({ values, manifest, backendHalf: () => true }), {
        warnings: [],
        errors: [],
      });
    }
  });

  it("passes checkBranding with a second UI language over init's skeleton: no branding is written", () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-scaffold-branding-'));
    const write = (relative, data) => {
      const file = path.join(root, ...relative.split('/'));
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fs.writeFileSync(file, data);
    };
    for (const [relative, data] of skeleton({
      cc: 'qq',
      version: VERSION,
      sha256: 'a'.repeat(64),
      source: folderSource(where().base),
      kit: where().kit,
    }))
      write(relative, data);
    const files = generate({ languages: ['da'] });
    for (const { path: relative, data } of files) write(relative, data);
    for (const app of ['admin', 'info'])
      assert.deepEqual(
        checkBranding({ app, dir: path.join(root, 'fe', app, 'assets'), uiLanguages: ['en', 'da'] })
          .errors,
        [],
        app,
      );
    for (const { path: relative } of files)
      assert.ok(!/^fe\/[^/]+\/assets\//.test(relative), relative);
    for (const { path: relative, data } of files)
      assert.equal(
        data.toString().includes('The Qualia Hydrographic Office'),
        relative === 'be/src/main/resources/defaults/country/settings.json',
        relative,
      );
  });

  it("fills image.yml's MODULE with the artifactId and nothing else", () => {
    const template = imageWorkflow(where().kit);
    const generated = textOf({}, '.github/workflows/image.yml');
    assert.equal(template.split('\n').filter((line) => line === "  MODULE: ''").length, 1);
    assert.equal(generated, template.replace("\n  MODULE: ''\n", "\n  MODULE: 'niord-qq'\n"));
    assert.ok(generated.split('\n').includes("  REGISTRY: ''"));
    assert.ok(generated.split('\n').includes("  HARBOR_PROJECT: ''"));
  });

  it('names the owners on every rule line of CODEOWNERS, and no comment still asks for them', () => {
    const template = fs.readFileSync(path.join(KIT, 'templates', 'CODEOWNERS'), 'utf8').split('\n');
    const generated = textOf({}, '.github/CODEOWNERS').split('\n');
    assert.equal(generated.length, template.length);
    let rules = 0;
    template.forEach((line, i) => {
      if (!line.trim()) assert.equal(generated[i], line);
      else if (!line.startsWith('#')) {
        rules++;
        assert.ok(line.includes('@OWNER'), line);
        assert.equal(generated[i], line.replaceAll('@OWNER', '@qualia/maintainers @someone'));
      }
    });
    assert.ok(rules > 0);
    assert.ok(!generated.some((line) => line.includes('@OWNER')), 'a line still names @OWNER');
    assert.ok(
      !generated.some((line) => /\bReplace\b/.test(line)),
      'a comment still asks to replace',
    );
  });

  it("writes the public site's configuration with the time zone", async () => {
    const config = textOf({ timeZone: 'Europe/Kyiv' }, 'fe/info/app/info.config.ts');
    assert.match(config, /^import type \{ InfoConfig \} from '@niord\/info\/api';$/m);
    assert.match(config, /^export const COUNTRY_INFO: InfoConfig = \{$/m);
    for (const line of [
      "  timeZone: 'Europe/Kyiv',",
      "  executionMode: 'production',",
      '  wmsLayer: false,',
      '  rootAreas: [],',
    ])
      assert.ok(config.split('\n').includes(line), line);
    const options = JSON.parse(fs.readFileSync(path.join(KIT, '.prettierrc'), 'utf8'));
    assert.ok(await prettier.check(config, { ...options, parser: 'typescript' }), 'prettier-clean');
  });

  it('writes a README naming the country and its code, and not the organisation', async () => {
    const readme = textOf({ languages: ['da'] }, 'README.md');
    assert.match(readme, /^# Qualia$/m);
    assert.ok(readme.includes('`qq`'));
    assert.ok(readme.includes('.niord/kit/docs/country-guide.md'));
    assert.ok(readme.includes('start-local.sh'));
    assert.ok(!readme.includes('Hydrographic'));
    const options = JSON.parse(fs.readFileSync(path.join(KIT, '.prettierrc'), 'utf8'));
    assert.ok(await prettier.check(readme, { ...options, parser: 'markdown' }), 'prettier-clean');
  });
});

describe('the scaffold: ci.yml', () => {
  const raw = fs.readFileSync(path.join(KIT, 'templates', 'ci.yml'), 'utf8');
  const text = textOf({}, '.github/workflows/ci.yml');
  const doc = yaml.load(text);
  const TOKEN = '${{ github.token }}';

  it('parses, raw and filled, and takes pull_request, push and workflow_dispatch', () => {
    assert.ok(yaml.load(raw));
    assert.deepEqual(Object.keys(doc.on).sort(), ['pull_request', 'push', 'workflow_dispatch']);
    assert.deepEqual(doc.on.pull_request.branches, ['main']);
    assert.deepEqual(doc.on.push.branches, ['main']);
    assert.deepEqual(doc.on.push.paths, [
      'be/**',
      'fe/**',
      '.github/workflows/**',
      '!be/**/*.md',
      '!fe/**/*.md',
    ]);
    assert.deepEqual(doc.permissions, { contents: 'read', packages: 'read' });
    assert.deepEqual(Object.keys(doc.jobs), ['tests', 'frontend']);
  });

  it('runs every run block strict, with no expression inside', () => {
    for (const [job, { steps }] of Object.entries(doc.jobs))
      for (const step of steps.filter((s) => s.run !== undefined)) {
        assert.ok(step.run.startsWith('set -euo pipefail\n'), `${job}: ${step.name}`);
        assert.ok(!step.run.includes('${{'), `${job}: ${step.name}`);
      }
  });

  it('binds the token on the Maven steps only', () => {
    const holders = Object.entries(doc.jobs).flatMap(([job, { steps }]) =>
      steps
        .filter((s) => s.env && Object.hasOwn(s.env, 'GITHUB_TOKEN'))
        .map((s) => `${job}: ${s.name}`),
    );
    assert.deepEqual(holders, [
      'tests: Set up the workspace',
      'tests: Build, unit and integration tests',
      'tests: Fetch the image tools',
      'frontend: Set up the workspace',
    ]);
    for (const holder of holders) {
      const [job, step] = holder.split(': ');
      assert.equal(stepEnv(text, { job, step }).GITHUB_TOKEN, TOKEN, holder);
    }
    assert.equal((text.match(/^\s*GITHUB_TOKEN:/gm) ?? []).length, 4);
    assert.ok(!Object.hasOwn(doc.env, 'GITHUB_TOKEN'));
    for (const job of Object.values(doc.jobs))
      assert.ok(!job.env || !Object.hasOwn(job.env, 'GITHUB_TOKEN'));
  });

  it('builds, then checks the overrides of the country jar and the group', () => {
    const steps = doc.jobs.tests.steps;
    assert.deepEqual(
      steps.map((s) => s.name ?? s.uses),
      [
        'actions/checkout@v4',
        'Set up JDK 25 (Temurin) + GitHub Packages credentials',
        'Maven repository cache',
        'actions/setup-node@v4',
        'Set up the workspace',
        'Build, unit and integration tests',
        'Read the base version',
        'Fetch the image tools',
        'Override check',
        'Country build group ran',
        'Test reports',
      ],
    );
    const byName = (name) => steps.find((s) => s.name === name);
    const java = byName('Set up JDK 25 (Temurin) + GitHub Packages credentials').with;
    assert.equal(java['server-id'], 'github');
    assert.equal(java['java-version'], '25');
    assert.equal(
      byName('Maven repository cache').with.key,
      "maven-${{ runner.os }}-${{ hashFiles('**/pom.xml') }}",
    );
    assert.equal(steps[3].with['node-version'], '24');
    assert.ok(byName('Build, unit and integration tests').run.includes('./mvnw $MVN_FLAGS verify'));
    assert.ok(
      byName('Override check').run.includes(
        'target/k8s/scripts/check-overrides.sh "target/niord-qq-$BASE.jar" .',
      ),
    );
    assert.ok(
      byName('Country build group ran').run.includes('scripts/check-country-build-group.sh'),
    );
    assert.equal(byName('Test reports').if, 'always()');
    const frontend = doc.jobs.frontend.steps
      .map((s) => s.run?.trim().split('\n').at(-1))
      .filter(Boolean);
    assert.deepEqual(frontend, [
      'node setup.mjs',
      'npm ci --ignore-scripts --no-audit --no-fund',
      'npm test',
      'npm run lint',
      'npm run e2e -- --list',
      'npm run build',
    ]);
    assert.ok(!/test-it|python/.test(text));
  });
});

describe('the scaffold: the templates and their slots', () => {
  it('fills a slot and refuses one left unfilled or one the template does not have', () => {
    assert.equal(fill('t', 'a {{x}} b {{x}}{{y}}', { x: '1', y: '' }), 'a 1 b 1');
    assert.equal(fill('t', '{{x}}', { x: '{{y}}' }), '{{y}}');
    assert.throws(
      () => fill('templates/t', 'a {{x}} {{y}}', { x: '1' }),
      /templates\/t: the slot \{\{y\}\} is left unfilled/,
    );
    assert.throws(
      () => fill('templates/t', 'a {{x}}', { x: '1', z: '2' }),
      /templates\/t: no slot \{\{z\}\} to fill/,
    );
    assert.throws(() => fill('t', '{{x}}', { x: 1 }), /\{\{x\}\}/);
    assert.equal(fill('t', 'run: ${{ github.token }}', {}), 'run: ${{ github.token }}');
  });

  const TEMPLATES = {
    'be/pom.xml.tmpl': 'be/pom.xml',
    'be/Application.java.tmpl': 'be/src/main/java/org/niord/qq/NiordQqApplication.java',
    'be/application.yml.tmpl': 'be/src/main/resources/application.yml',
    'be/application-local.yml.tmpl': 'be/src/main/resources/application-local.yml',
    'be/gitattributes': 'be/.gitattributes',
    'be/gitignore': 'be/.gitignore',
    'be/java-version': 'be/.java-version',
    'be/defaults-country-README.md': 'be/src/main/resources/defaults/country/README.md',
    'be/check-country-build-group.sh': 'be/scripts/check-country-build-group.sh',
    'ci.yml': '.github/workflows/ci.yml',
  };

  it('has a generated file for every template of templates/be/ and templates/ci.yml, with no slot left', () => {
    assert.deepEqual(
      fs.readdirSync(path.join(KIT, 'templates', 'be')).sort(),
      Object.keys(TEMPLATES)
        .filter((name) => name.startsWith('be/'))
        .map((name) => name.slice(3))
        .sort(),
    );
    for (const languages of [[], ['da']])
      for (const producerCode of ['', 'AB12']) {
        const files = textsOf(generate({ languages, producerCode }));
        for (const [template, file] of Object.entries(TEMPLATES)) {
          assert.ok(files.has(file), file);
          assert.ok(!LEFTOVER.test(files.get(file)), `${template} → ${file}`);
        }
        for (const [file, data] of files) assert.ok(!LEFTOVER.test(data), file);
      }
  });

  it('fills every slot of every template when each is given', () => {
    for (const template of Object.keys(TEMPLATES)) {
      const text = fs.readFileSync(path.join(KIT, 'templates', ...template.split('/')), 'utf8');
      const slots = Object.fromEntries(
        [...text.matchAll(/\{\{([A-Za-z][A-Za-z0-9]*)\}\}/g)].map(([, slot]) => [slot, 'x']),
      );
      assert.ok(!LEFTOVER.test(fill(template, text, slots)), template);
    }
  });
});

describe('the scaffold: what it refuses', () => {
  it('refuses a version that is not a plain version', () => {
    for (const version of ['', '4.0.9<x>', 'v4.0.9', undefined])
      assert.throws(
        () => scaffold({ answers: answersOf(), kit: where().kit, version }),
        /is not a base product version/,
        String(version),
      );
  });

  it('refuses answers the validator refuses', () => {
    assert.throws(() => generate({ country: 'DK' }), /country/);
  });

  it('refuses a kit package without its backend files, naming the file', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-scaffold-kit-'));
    const kit = path.join(root, '.niord', 'kit');
    fs.mkdirSync(kit, { recursive: true });
    fs.writeFileSync(path.join(kit, 'kit.json'), markerText(VERSION));
    fs.cpSync(path.join(KIT, 'templates'), path.join(kit, 'templates'), { recursive: true });
    assert.throws(
      () => scaffold({ answers: answersOf(), kit, version: VERSION }),
      /backend[\\/]mvnw is missing: the kit package carries it as backend\/mvnw/,
    );
  });
});
