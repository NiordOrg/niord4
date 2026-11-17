/**
 * Tests for country-kit/catalog.mjs.
 *
 *   npm run test:guards      # from fe/
 *
 * Each case writes a small tree into a temporary folder — the en pack, the date renderer's token
 * list and niord-base's English bundles — and builds its catalog. The catalog is what every pack
 * is checked against, so a key it misses, a placeholder it misreads or a bundle it does not find
 * lets a broken translation through.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import {
  backendPlaceholders,
  buildCatalog,
  decodeProperties,
  markupOf,
  parseProperties,
  serializeCatalog,
} from './catalog.mjs';
import { fingerprint } from './stage-languages.mjs';

const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

const PNG = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  Buffer.from('flag'),
]);
const RESOURCES = 'be/niord/src/main/resources';
const BUNDLES = ['web', 'message', 'pdf', 'mail', 'template'];

const EN_ADMIN = {
  menu: { open: 'Open {{ name }}', close: 'Close' },
  publish: { summary: 'Goes out with <strong>{{count}}</strong> of {{ total }} {{count}}' },
  attachments: { none: '<none>' },
  untitled: ' ',
};
const EN_INFO = {
  SITE_TITLE: 'Notices',
  SOURCE_DATE_FORMAT: "d MMMM yyyy 'at' HH:mm",
  DATE_DAYS_SHORT: 'Su|Mo|Tu|We|Th|Fr|Sa',
  DATE_FROM: 'From',
};

/** The files of a passing tree, relative to its root. */
function baseFiles() {
  const files = {
    'fe/languages/en/pack.json': JSON.stringify({
      lang: 'en',
      label: 'English',
      locale: 'en-GB',
      flag: 'img/flags/en.png',
    }),
    'fe/languages/en/img/flags/en.png': PNG,
    'fe/languages/en/i18n/admin/en.json': JSON.stringify(EN_ADMIN),
    'fe/languages/en/i18n/info/en.json': JSON.stringify(EN_INFO),
    'fe/languages/en/renames.json': '{}\n',
    'fe/ui-apps/info/src/app/shared/utils/date-pattern-tokens.json': JSON.stringify([
      'yyyy',
      'MMMM',
      'd',
      'HH',
      'mm',
    ]),
  };
  for (const bundle of BUNDLES) files[`${RESOURCES}/${bundle}_en.properties`] = `${bundle}.x = X\n`;
  files[`${RESOURCES}/mail_en.properties`] =
    '# Common texts\nmail.dear.user = Dear {0}\nmail.regards=Kind regards,<br>{0}\n';
  files[`${RESOURCES}/web_en.properties`] =
    'lang.en = English\nmsg.more = Load {{ from }} - {{to}}\n';
  return files;
}

function tree(edit) {
  const files = baseFiles();
  edit?.(files);
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'catalog-'));
  temporary.push(root);
  for (const [file, content] of Object.entries(files)) {
    if (content === undefined) continue;
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    fs.writeFileSync(path.join(root, file), content);
  }
  return { fe: path.join(root, 'fe'), be: path.join(root, 'be') };
}

function assertRefused(edit, fragment) {
  assert.throws(
    () => buildCatalog(tree(edit)),
    (error) => {
      assert.ok(
        error.message.includes(fragment),
        `expected an error naming "${fragment}", got: ${error.message}`,
      );
      return true;
    },
  );
}

describe('catalog', () => {
  it('lists every English key per application with its fingerprint and placeholders', () => {
    const catalog = buildCatalog(tree());
    assert.equal(catalog.format, 1);
    assert.equal(catalog.version, 'tree');
    assert.deepEqual(Object.keys(catalog.apps.admin).sort(), [
      'attachments.none',
      'menu.close',
      'menu.open',
      'publish.summary',
      'untitled',
    ]);
    assert.deepEqual(catalog.apps.admin['menu.open'], {
      fp: fingerprint('Open {{ name }}'),
      placeholders: ['name'],
    });
    assert.deepEqual(catalog.apps.admin['publish.summary'].placeholders, ['count', 'total']);
    assert.equal(catalog.apps.admin.untitled.empty, true);
    assert.deepEqual(catalog.rules.datePatternTokens, ['yyyy', 'MMMM', 'd', 'HH', 'mm']);
  });

  it("marks markup by its HTML tags, so the text '<none>' is not markup", () => {
    const catalog = buildCatalog(tree());
    assert.deepEqual(catalog.apps.admin['publish.summary'].markup, ['</strong>', '<strong>']);
    assert.equal(catalog.apps.admin['attachments.none'].markup, undefined);
    assert.deepEqual(markupOf('See <a href="https://x.dk/a" target=_blank>it</a><br/>'), [
      '</a>',
      '<a>',
      '<br>',
      '@href',
      '@target',
      'href=https://x.dk/a',
    ]);
    assert.deepEqual(markupOf('a < b and <ingen>, <x-widget>'), ['<x-widget>']);
    // An unknown name is markup once it carries an attribute: no sanitiser stands behind mail.
    assert.deepEqual(markupOf('<foo onclick="x()">hi</foo>'), ['<foo>', '@onclick']);
  });

  it("marks the public site's date patterns and word lists", () => {
    const { info } = buildCatalog(tree()).apps;
    assert.equal(info.SOURCE_DATE_FORMAT.pattern, true);
    assert.equal(info.DATE_DAYS_SHORT.list, 7);
    assert.equal(info.DATE_FROM.list, undefined);
    assert.equal(info.SITE_TITLE.pattern, undefined);
  });

  it('reads the backend from the legacy root-level *_en.properties, still read', () => {
    const { backend } = buildCatalog(tree());
    assert.deepEqual(Object.keys(backend).sort(), [...BUNDLES].sort());
    assert.deepEqual(backend.mail['mail.dear.user'], {
      fp: fingerprint('Dear {0}'),
      placeholders: ['{0}'],
    });
    assert.deepEqual(backend.mail['mail.regards'].markup, ['<br>']);
    assert.deepEqual(backend.web['msg.more'].placeholders, ['from', 'to']);
    assert.ok(backend.web['lang.en']);
    assert.equal(backend.template['template.x'].empty, undefined);
  });

  it('marks a blank backend English text empty, as for an application', () => {
    const { backend } = buildCatalog(
      tree((files) => {
        files[`${RESOURCES}/template_en.properties`] = 'template.x = X\ntemplate.prefix =\n';
      }),
    );
    assert.deepEqual(backend.template['template.prefix'], {
      fp: fingerprint(''),
      placeholders: [],
      empty: true,
    });
  });

  it('reads the backend from defaults/base/texts/en/, where the backend keeps its English', () => {
    const moved = buildCatalog(
      tree((files) => {
        for (const bundle of BUNDLES) {
          files[`${RESOURCES}/defaults/base/texts/en/${bundle}.properties`] =
            files[`${RESOURCES}/${bundle}_en.properties`];
          delete files[`${RESOURCES}/${bundle}_en.properties`];
        }
      }),
    );
    assert.deepEqual(moved.backend, buildCatalog(tree()).backend);
  });

  it('refuses a bundle found in both places, or in neither', () => {
    assertRefused((files) => {
      files[`${RESOURCES}/defaults/base/texts/en/pdf.properties`] = 'pdf.x = X\n';
    }, 'the English pdf bundle is in two places');
    assertRefused((files) => {
      delete files[`${RESOURCES}/template_en.properties`];
    }, 'no English template bundle');
  });

  it('parses .properties as java.util.Properties does', () => {
    const text = [
      '# a comment \\',
      '! another',
      '   key.one = one \\',
      '      continued',
      'key\\ two:two',
      'key.three\tthree',
      'key.four=caf\\u00e9 \\t\\=x\\',
      '',
      'key.five',
    ].join('\r\n');
    assert.deepEqual(
      parseProperties(text).map(({ key, value, first, last }) => [key, value, first, last]),
      [
        ['key.one', 'one continued', 2, 3],
        ['key two', 'two', 4, 4],
        ['key.three', 'three', 5, 5],
        ['key.four', 'café \t=x', 6, 7],
        ['key.five', '', 8, 8],
      ],
    );
    assert.throws(() => parseProperties('bad = \\u12'), /malformed \\uxxxx/);
    assert.equal(decodeProperties(Buffer.from('café', 'utf8')), 'café');
    assert.deepEqual(backendPlaceholders('{0} of {1,number} and {{name}} {0}'), [
      'name',
      '{0}',
      '{1}',
    ]);
  });

  it('refuses a bundle the backend would read otherwise: Latin-1 bytes, a byte-order mark', () => {
    assertRefused((files) => {
      files[`${RESOURCES}/pdf_en.properties`] = Buffer.from('pdf.x = caf\u00e9\n', 'latin1');
    }, 'be/niord/src/main/resources/pdf_en.properties is not valid UTF-8');
    assertRefused((files) => {
      files[`${RESOURCES}/pdf_en.properties`] = Buffer.from('\uFEFFpdf.x = X\n', 'utf8');
    }, 'be/niord/src/main/resources/pdf_en.properties starts with a byte-order mark');
  });

  it('reads renamedFrom from renames.json and refuses an entry that does not hold', () => {
    const catalog = buildCatalog(
      tree((files) => {
        files['fe/languages/en/renames.json'] = JSON.stringify({
          admin: { 'menu.close': 'menu.shut' },
          backend: { mail: { 'mail.regards': 'mail.best' } },
        });
      }),
    );
    assert.deepEqual(catalog.renamedFrom, {
      admin: { 'menu.close': 'menu.shut' },
      info: {},
      backend: { mail: { 'mail.regards': 'mail.best' } },
    });
    assertRefused((files) => {
      files['fe/languages/en/renames.json'] = JSON.stringify({ admin: { 'menu.gone': 'menu.x' } });
    }, 'admin renames to "menu.gone", which English does not have');
    assertRefused((files) => {
      files['fe/languages/en/renames.json'] = JSON.stringify({
        info: { SITE_TITLE: 'DATE_FROM' },
      });
    }, 'info renames "DATE_FROM", which English still has');
    assertRefused((files) => {
      files['fe/languages/en/renames.json'] = JSON.stringify({ server: {} });
    }, 'renames.json has "server"');
  });

  it('holds English to the rules a translation is held to', () => {
    assertRefused((files) => {
      files['fe/languages/en/i18n/info/en.json'] = JSON.stringify({
        ...EN_INFO,
        PRINT_DATE_FORMAT: 'QQQ yyyy',
      });
    }, 'PRINT_DATE_FORMAT "QQQ yyyy" has the token(s) QQQ');
    assertRefused((files) => {
      files['fe/languages/en/i18n/info/en.json'] = JSON.stringify({
        ...EN_INFO,
        DATE_DAYS_SHORT: 'Su||Tu',
      });
    }, 'DATE_DAYS_SHORT has an empty entry');
    assertRefused((files) => {
      files[`${RESOURCES}/pdf_en.properties`] = 'constructor = x\n';
    }, 'has the key "constructor"');
  });

  it('writes the same bytes on every run, every object sorted', () => {
    const folders = tree();
    const first = serializeCatalog(buildCatalog(folders));
    assert.equal(serializeCatalog(buildCatalog(folders)), first);
    const keys = Object.keys(JSON.parse(first).apps.admin);
    assert.deepEqual(keys, [...keys].sort());
    assert.ok(first.endsWith('}\n'));
  });
});
