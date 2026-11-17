/**
 * Tests for country-kit/lib/text-overrides.mjs.
 *
 *   npm run test:guards      # from fe/
 *
 * The files are read from test-fixtures/text-overrides/ (a valid folder and one breaking each
 * reading rule) and from temporary folders for what git cannot hold: a link, a file that is no
 * JSON. The check runs on a small catalog made here from its English texts, with one negative case
 * per row: a row with no failing case may have stopped working without anyone noticing.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, describe, it } from 'node:test';
import { markupOf } from './catalog.mjs';
import {
  checkTextOverrides,
  readPackBundles,
  readTextOverrides,
  textOverridesFor,
} from './lib/text-overrides.mjs';
import { fingerprint, isBlank, placeholders } from './stage-languages.mjs';

const FIXTURES = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  'test-fixtures',
  'text-overrides',
);
const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

const EN = {
  admin: {
    'menu.open': 'Open {{ name }}',
    'menu.close': 'Close',
    'publish.summary': 'Goes out with <strong>{{count}}</strong>',
    untitled: '',
  },
  info: {
    SITE_TITLE: 'Notices',
    SOURCE_DATE_FORMAT: "d MMMM yyyy 'at' HH:mm",
    DATE_DAYS_SHORT: 'Su|Mo|Tu|We|Th|Fr|Sa',
    FOOTER_COPYRIGHT: '© the authority',
  },
};
const DA = {
  admin: { 'menu.open': 'Åbn {{name}}', 'menu.close': 'Luk', untitled: '' },
  info: { SITE_TITLE: 'Meddelelser' },
};

/** The catalog of EN, as catalog.mjs builds it. */
function catalogOf(en) {
  const apps = {};
  for (const [app, texts] of Object.entries(en)) {
    apps[app] = {};
    for (const [key, text] of Object.entries(texts)) {
      const entry = { fp: fingerprint(text), placeholders: placeholders(text) };
      if (isBlank(text)) entry.empty = true;
      const markup = markupOf(text);
      if (markup.length) entry.markup = markup;
      if (key.endsWith('_DATE_FORMAT')) entry.pattern = true;
      if (key.startsWith('DATE_')) entry.list = text.split('|').length;
      apps[app][key] = entry;
    }
  }
  return {
    format: 1,
    apps,
    backend: {},
    renamedFrom: { admin: { 'menu.close': 'menu.shut' }, info: {}, backend: {} },
    rules: { datePatternTokens: ['yyyy', 'MMMM', 'd', 'HH', 'mm'] },
  };
}

const CATALOG = catalogOf(EN);
const MANIFEST = {
  apps: {
    admin: { uiLanguages: ['en', 'da'], contentLanguages: ['en', 'da'], defaultLanguage: 'da' },
    info: { uiLanguages: ['da'], defaultLanguage: 'da' },
  },
};
const BRANDING = {
  format: 1,
  apps: { admin: { keys: { 'footer.copyright': {} } }, info: { keys: { FOOTER_COPYRIGHT: {} } } },
};
const BUNDLES = { en: EN, da: DA };
const fp = (app, key) => CATALOG.apps[app][key].fp;
const override = (app, key, text, extra = {}) => ({ text, fp: fp(app, key), ...extra });

function check(overrides, { manifest = MANIFEST } = {}) {
  return checkTextOverrides({
    catalog: CATALOG,
    overrides,
    manifest,
    packBundles: BUNDLES,
    brandingSchema: BRANDING,
  });
}

function assertNamed(lines, ...fragments) {
  for (const fragment of fragments)
    assert.ok(
      lines.some((line) => line.includes(fragment)),
      `expected a line naming "${fragment}", got:\n${lines.join('\n') || '(none)'}`,
    );
}

/** One override's errors, the rest of the check passing. */
const errorsOf = (app, key, entry, lang = 'da') =>
  check({ [lang]: { [app]: { [key]: entry } } }).errors;

describe('reading text overrides', () => {
  it('reads each <lang>.json in the folder, and an absent folder as none', () => {
    const { files, errors } = readTextOverrides(path.join(FIXTURES, 'valid'));
    assert.deepEqual(errors, []);
    assert.deepEqual(Object.keys(files), ['da', 'en']);
    assert.deepEqual(files.da.admin['menu.close'], { text: 'Afslut', fp: '7d9eb7acb13e2462' });
    assert.deepEqual(readTextOverrides(path.join(FIXTURES, 'nowhere')), { files: {}, errors: [] });
  });

  it('refuses a badly named file, another file, a folder, another field, a forbidden key, a non-object', () => {
    const { files, errors } = readTextOverrides(path.join(FIXTURES, 'broken'));
    assertNamed(
      errors,
      'text-overrides/de-DE.json is not named <lang>.json',
      'text-overrides/notes.txt is not named <lang>.json',
      'text-overrides/sub is a folder',
      'text-overrides/da.json has "backend"; its sections are admin and info',
      'text-overrides/kl.json: info has the key "a.__proto__.b"',
      'text-overrides/fr.json: admin is not an object',
      'text-overrides/de.json is not an object',
    );
    assert.equal(errors.length, 7, errors.join('\n'));
    // Only the file with no problem is read.
    assert.deepEqual(Object.keys(files), ['nb']);
  });

  it('refuses a file that is no JSON, and a link', (t) => {
    const fe = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-overrides-'));
    temporary.push(fe);
    const dir = path.join(fe, 'text-overrides');
    fs.mkdirSync(dir);
    fs.writeFileSync(path.join(dir, 'sv.json'), '{ "admin": ');
    assertNamed(readTextOverrides(fe).errors, 'text-overrides/sv.json: ');
    fs.rmSync(path.join(dir, 'sv.json'));
    // The folder itself as a link (a junction needs no privilege on Windows).
    const elsewhere = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-overrides-linked-'));
    temporary.push(elsewhere);
    fs.mkdirSync(path.join(elsewhere, 'fe'));
    const link = path.join(elsewhere, 'fe', 'text-overrides');
    fs.symlinkSync(dir, link, 'junction');
    assert.deepEqual(readTextOverrides(path.join(elsewhere, 'fe')).errors, [
      'text-overrides/ is a link',
    ]);
    // The link goes first, so no clean-up can reach through it.
    fs.unlinkSync(link);
    const outside = path.join(fe, 'outside.json');
    fs.writeFileSync(outside, '{}');
    try {
      fs.symlinkSync(outside, path.join(dir, 'da.json'), 'file');
    } catch (error) {
      if (error.code === 'EPERM') return t.skip('creating a link needs a privilege here');
      throw error;
    }
    assertNamed(readTextOverrides(fe).errors, 'text-overrides/da.json is a link');
  });
});

describe('checking text overrides', () => {
  it('passes overrides that hold every rule, and counts and covers them', () => {
    const { files } = readTextOverrides(path.join(FIXTURES, 'valid'));
    const result = check(files);
    assert.deepEqual(result.errors, []);
    assert.deepEqual(result.warnings, []);
    assert.deepEqual(result.covered, {
      da: { admin: ['menu.close', 'menu.open'], info: ['SITE_TITLE'] },
      en: { admin: ['menu.close'] },
    });
    assert.deepEqual(result.counts, { da: { admin: 2, info: 1 }, en: { admin: 1, info: 0 } });
    assertNamed(
      result.lines,
      'text overrides: da 3 (admin 2, info 1)',
      'text overrides: en 1 (admin 1, info 0)',
      "text overrides: da admin menu.close differs from the da pack's text, a deliberate difference",
      'text overrides: en admin menu.close differs from the English text, a deliberate difference',
    );
  });

  it('prints none when there are none', () => {
    const result = check({});
    assert.deepEqual(result.lines, ['text overrides: none']);
    assert.deepEqual(result.counts, {});
    assert.deepEqual(result.covered, {});
  });

  it('language: a language that is not a UI language of the application fails', () => {
    const errors = check({
      en: { info: { SITE_TITLE: override('info', 'SITE_TITLE', 'News') } },
    }).errors;
    assert.deepEqual(errors, [
      'text-overrides/en.json overrides info texts, but en is not a UI language of info (da)',
    ]);
  });

  it('catalog: a key that left the catalog fails, named with its new name when renamed', () => {
    assertNamed(
      errorsOf('admin', 'menu.gone', { text: 'x', fp: '0123456789abcdef' }),
      'text-overrides/da.json: admin menu.gone left the catalog; remove the override',
    );
    assertNamed(
      errorsOf('admin', 'menu.shut', { text: 'x', fp: '0123456789abcdef' }),
      'admin menu.shut left the catalog: the base product renamed it to menu.close; move the override',
    );
  });

  it("branding: a branding schema key or a country.* key is branding's", () => {
    assertNamed(
      errorsOf('info', 'FOOTER_COPYRIGHT', override('info', 'FOOTER_COPYRIGHT', '© os')),
      "info FOOTER_COPYRIGHT is branding's (branding.schema.json)",
    );
    assertNamed(
      errorsOf('admin', 'country.harbour.title', { text: 'Havne', fp: '0123456789abcdef' }),
      "admin country.harbour.title is branding's (the country.* keys)",
    );
  });

  it('shape: an entry that is no object, has another field, or a missing or malformed fp fails', () => {
    assertNamed(errorsOf('admin', 'menu.close', 'Afslut'), 'admin menu.close is not an object');
    assertNamed(
      errorsOf('admin', 'menu.close', override('admin', 'menu.close', 'Afslut', { note: 'x' })),
      'admin menu.close has fp, note, text; it has exactly fp, text',
    );
    assertNamed(
      errorsOf('admin', 'menu.close', { text: 'Afslut' }),
      `admin menu.close has no fingerprint of 16 hex digits, fp; the English text's is ${fp('admin', 'menu.close')}`,
    );
    assertNamed(
      errorsOf('admin', 'menu.close', { text: 'Afslut', fp: 'ABC' }),
      'admin menu.close has no fingerprint of 16 hex digits',
    );
    assertNamed(
      errorsOf('admin', 'menu.close', { text: 7, fp: fp('admin', 'menu.close') }),
      'admin menu.close has no text',
    );
  });

  it("empty: an empty text where English's is not fails; where English's is empty it passes", () => {
    assertNamed(
      errorsOf('admin', 'menu.close', override('admin', 'menu.close', ' ')),
      "admin menu.close is empty, and English's text is not",
    );
    assert.deepEqual(errorsOf('admin', 'untitled', override('admin', 'untitled', '')), []);
  });

  it("placeholders: placeholders other than English's fail", () => {
    assertNamed(
      errorsOf('admin', 'menu.open', override('admin', 'menu.open', 'Åbn {{ navn }}')),
      'text-overrides/da.json: admin menu.open has the placeholders navn, English name',
    );
  });

  it("markup, pattern and list: as for a pack's text", () => {
    assertNamed(
      errorsOf(
        'admin',
        'publish.summary',
        override('admin', 'publish.summary', 'Går ud med <b>{{count}}</b>'),
      ),
      "text-overrides/da.json admin: publish.summary's markup is </b> <b>, English's </strong> <strong>",
    );
    assertNamed(
      errorsOf('admin', 'menu.close', override('admin', 'menu.close', '<em>Luk</em>')),
      'menu.close has markup, </em> <em>, where English has none',
    );
    assertNamed(
      errorsOf('info', 'SOURCE_DATE_FORMAT', override('info', 'SOURCE_DATE_FORMAT', 'd. QQ yyyy')),
      'SOURCE_DATE_FORMAT "d. QQ yyyy" has the token(s) QQ',
    );
    assertNamed(
      errorsOf('info', 'DATE_DAYS_SHORT', override('info', 'DATE_DAYS_SHORT', 'sø|ma|ti')),
      'DATE_DAYS_SHORT has 3 entries, English 7',
    );
  });

  it('fingerprint: an override written against another English text is one warning per language', () => {
    const stale = { text: 'Afslut', fp: '0123456789abcdef' };
    const result = check({
      da: {
        admin: {
          'menu.close': stale,
          'menu.open': { text: 'Vis {{name}}', fp: 'fedcba9876543210' },
        },
      },
    });
    assert.deepEqual(result.errors, []);
    assert.deepEqual(result.warnings, [
      `text-overrides/da.json: 2 override(s) were written against another English text; look at each, then record the English fingerprint shown: admin menu.close (fp ${fp('admin', 'menu.close')}), admin menu.open (fp ${fp('admin', 'menu.open')})`,
    ]);
    // A stale override still supplies its text.
    assert.deepEqual(result.covered, { da: { admin: ['menu.close', 'menu.open'] } });
  });

  it("redundant: an override equal to the pack's text, or to English's for en, is a warning", () => {
    const result = check({
      da: { admin: { 'menu.close': override('admin', 'menu.close', 'Luk') } },
      en: { admin: { 'menu.close': override('admin', 'menu.close', 'Close') } },
    });
    assert.deepEqual(result.errors, []);
    assertNamed(
      result.warnings,
      'text-overrides/da.json: admin menu.close is redundant: the da pack has the same text',
      'text-overrides/en.json: admin menu.close is redundant: the English text has the same text',
    );
  });

  it('gives the findings the upgrade report lists structured, beside the same lines', () => {
    const result = check({
      da: {
        admin: {
          'menu.shut': { text: 'Afslut', fp: '0123456789abcdef' },
          'menu.close': override('admin', 'menu.close', 'Luk'),
          'menu.open': { text: 'Vis {{name}}', fp: '0123456789abcdef' },
          'publish.summary': override('admin', 'publish.summary', 'Går ud med <b>{{count}}</b>'),
        },
      },
      en: { admin: { 'menu.close': override('admin', 'menu.close', 'Shut') } },
    });
    assert.deepEqual(result.leftCatalog, [
      {
        lang: 'da',
        app: 'admin',
        key: 'menu.shut',
        renamedTo: 'menu.close',
        error:
          'text-overrides/da.json: admin menu.shut left the catalog: the base product renamed it to menu.close; move the override',
      },
    ]);
    assert.deepEqual(result.markup, [
      {
        lang: 'da',
        app: 'admin',
        key: 'publish.summary',
        error:
          "text-overrides/da.json admin: publish.summary's markup is </b> <b>, English's </strong> <strong>",
      },
    ]);
    // Each failed override's finding carries its line, exactly as the errors hold it.
    for (const finding of [...result.leftCatalog, ...result.markup])
      assert.ok(result.errors.includes(finding.error), finding.error);
    assert.deepEqual(result.stale, [
      { lang: 'da', app: 'admin', key: 'menu.open', fp: fp('admin', 'menu.open') },
    ]);
    assert.deepEqual(result.redundant, [{ lang: 'da', app: 'admin', key: 'menu.close' }]);
    assert.deepEqual(result.differing, [
      { lang: 'da', app: 'admin', key: 'menu.open' },
      { lang: 'en', app: 'admin', key: 'menu.close' },
    ]);
    assertNamed(result.errors, 'admin menu.shut left the catalog', "publish.summary's markup");
  });

  it('difference: a key the pack lacks is covered and not listed as a difference', () => {
    const result = check({
      da: {
        admin: {
          'publish.summary': override(
            'admin',
            'publish.summary',
            'Går ud med <strong>{{count}}</strong>',
          ),
        },
      },
    });
    assert.deepEqual(result.errors, []);
    assert.deepEqual(result.covered, { da: { admin: ['publish.summary'] } });
    assert.deepEqual(result.lines, ['text overrides: da 1 (admin 1, info 0)']);
  });
});

describe('text overrides for staging and the pack check', () => {
  it('gives staging each application its texts per language', () => {
    const { files } = readTextOverrides(path.join(FIXTURES, 'valid'));
    assert.deepEqual(textOverridesFor(files), {
      admin: {
        da: { 'menu.close': 'Afslut', 'menu.open': 'Vis {{ name }}' },
        en: { 'menu.close': 'Shut' },
      },
      info: { da: { SITE_TITLE: 'Efterretninger' } },
    });
    assert.deepEqual(textOverridesFor({}), { admin: {}, info: {} });
  });

  it("reads the packs' bundles flat, leaving out what a pack lacks", () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-overrides-packs-'));
    temporary.push(root);
    const write = (lang, app, bundle) => {
      fs.mkdirSync(path.join(root, lang, 'i18n', app), { recursive: true });
      fs.writeFileSync(path.join(root, lang, 'i18n', app, `${lang}.json`), JSON.stringify(bundle));
    };
    write('en', 'admin', { menu: { close: 'Close' } });
    write('en', 'info', { SITE_TITLE: 'Notices' });
    write('da', 'admin', { menu: { close: 'Luk' } });
    assert.deepEqual(readPackBundles({ en: path.join(root, 'en'), da: path.join(root, 'da') }), {
      en: { admin: { 'menu.close': 'Close' }, info: { SITE_TITLE: 'Notices' } },
      da: { admin: { 'menu.close': 'Luk' } },
    });
  });
});
