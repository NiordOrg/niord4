/**
 * Tests for country-kit/pack.mjs.
 *
 *   npm run test:guards      # from fe/
 *
 * Each case writes a small tree into a temporary folder — the en, da and kl packs and niord-base's
 * English bundles — builds its catalog, breaks a pack the way an edit could and expects the check
 * to name it. There is one negative case per row of the pack check and one for the status
 * files, named by its row: a row with no failing case may have stopped working without anyone
 * noticing. Then the tools: migrate, prune and accept; the backend half's status; a foreign pack;
 * and the commands of a home's pack.yml — validate, latest-catalog and prune --base, with a fake
 * Maven standing in for the registry.
 */
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { buildCatalog, serializeCatalog } from './catalog.mjs';
import {
  acceptKeys,
  checkPacks,
  formatJson,
  main,
  migratePack,
  packFolders,
  packsLayout,
  prunePack,
  report,
  setKey,
  setText,
} from './pack.mjs';
import { readZip, writeZip } from './lib/zip.mjs';
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

const EN = {
  admin: {
    menu: { open: 'Open {{ name }}', close: 'Close' },
    publish: { summary: 'Goes out with <strong>{{count}}</strong>' },
    untitled: '',
  },
  info: {
    SITE_TITLE: 'Notices',
    SOURCE_DATE_FORMAT: "d MMMM yyyy 'at' HH:mm",
    DATE_DAYS_SHORT: 'Su|Mo|Tu|We|Th|Fr|Sa',
    BTN_CLOSE: 'Close',
  },
};
const DA = {
  admin: {
    menu: { open: 'Åbn {{name}}', close: 'Luk' },
    publish: { summary: 'Går ud med <strong>{{count}}</strong>' },
    untitled: '',
  },
  info: {
    SITE_TITLE: 'Meddelelser',
    SOURCE_DATE_FORMAT: "d. MMMM yyyy 'kl.' HH:mm",
    DATE_DAYS_SHORT: 'sø|ma|ti|on|to|fr|lø',
    BTN_CLOSE: 'Luk',
  },
};
const KL = {
  info: {
    SITE_TITLE: 'Nalunaarutit',
    SOURCE_DATE_FORMAT: "d. MMMM yyyy 'kl.' HH:mm",
    DATE_DAYS_SHORT: 'sap|ata|mar|pin|sis|tal|arf',
    BTN_CLOSE: 'Matuguk',
  },
};
const EN_BACKEND = {
  web: 'lang.en = English\nmsg.more = Load {{ from }} - {{to}}\n',
  message: 'msg.title = Message {0}\n',
  pdf: 'pdf.volume = Volume {0}\n',
  mail: 'mail.regards = Kind regards,<br>{0}\n',
  template: 'cancel.message = Cancel message {0}\n',
};
const DA_BACKEND = {
  web: 'lang.da = Dansk\nlang.en = Engelsk\nmsg.more = Hent {{from}} - {{ to }}\n',
  message: 'msg.title = Meddelelse {0}\n',
  pdf: 'pdf.volume = Bind {0}\n',
  mail: 'mail.regards = Venlig hilsen,<br>{0}\n',
  template: 'cancel.message = Annullér meddelelse {0}\n',
};

/** Every flat key of a bundle, sorted, with the fingerprint of its English text; undefined skipped. */
function fingerprintsOf(bundles) {
  const flat = (node, trail = []) =>
    Object.entries(node).flatMap(([key, value]) =>
      value === undefined
        ? []
        : typeof value === 'string'
          ? [[[...trail, key].join('.')]]
          : flat(value, [...trail, key]),
    );
  const english = (app, key) => key.split('.').reduce((node, name) => node[name], EN[app]);
  return Object.fromEntries(
    Object.entries(bundles).map(([app, bundle]) => [
      app,
      Object.fromEntries(
        flat(bundle)
          .sort()
          .map(([key]) => [key, fingerprint(english(app, key))]),
      ),
    ]),
  );
}

const packJson = (lang, label, locale) =>
  JSON.stringify({ lang, label, locale, flag: `img/flags/${lang}.png` }, null, 2);
const NOTE = 'Values awaiting acceptance.';

/** The files of a passing tree, relative to its root, as text or bytes. */
function baseFiles() {
  const files = {
    'fe/languages/en/pack.json': packJson('en', 'English', 'en-GB'),
    'fe/languages/en/img/flags/en.png': PNG,
    'fe/languages/en/i18n/admin/en.json': JSON.stringify(EN.admin, null, 2),
    'fe/languages/en/i18n/info/en.json': JSON.stringify(EN.info, null, 2),
    'fe/languages/en/renames.json': '{}\n',
    'fe/ui-apps/info/src/app/shared/utils/date-pattern-tokens.json': JSON.stringify([
      'yyyy',
      'MMMM',
      'd',
      'HH',
      'mm',
    ]),
    'fe/languages/da/pack.json': packJson('da', 'Dansk', 'da'),
    'fe/languages/da/img/flags/da.png': PNG,
    'fe/languages/da/i18n/admin/da.json': `${JSON.stringify(DA.admin, null, 2)}\n`,
    'fe/languages/da/i18n/info/da.json': `${JSON.stringify(DA.info, null, 2)}\n`,
    'fe/languages/da/status/unaccepted.json': formatJson({
      note: NOTE,
      admin: { unaccepted: [] },
      info: { unaccepted: [] },
    }),
    'fe/languages/da/status/fingerprints.json': formatJson(fingerprintsOf(DA)),
    'fe/languages/kl/pack.json': packJson('kl', 'Kalaallisut', 'kl'),
    'fe/languages/kl/img/flags/kl.png': PNG,
    'fe/languages/kl/i18n/info/kl.json': `${JSON.stringify(KL.info, null, 2)}\n`,
    'fe/languages/kl/status/unaccepted.json': formatJson({
      note: NOTE,
      info: {
        unaccepted: ['BTN_CLOSE', 'SITE_TITLE'],
        cldr: ['DATE_DAYS_SHORT'],
        verbatim: ['SOURCE_DATE_FORMAT'],
      },
    }),
    // The two unaccepted keys have no fingerprint: a key is in exactly one of the two lists.
    'fe/languages/kl/status/fingerprints.json': formatJson(
      fingerprintsOf({ info: { ...KL.info, BTN_CLOSE: undefined, SITE_TITLE: undefined } }),
    ),
  };
  for (const bundle of BUNDLES) {
    files[`${RESOURCES}/${bundle}_en.properties`] = EN_BACKEND[bundle];
    files[`fe/languages/da/backend/${bundle}.properties`] = DA_BACKEND[bundle];
  }
  return files;
}

/** Writes a tree into a fresh folder; returns its fe/, be/ and packs folders. */
function tree(edit) {
  const files = baseFiles();
  edit?.(files);
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pack-'));
  temporary.push(root);
  for (const [file, content] of Object.entries(files)) {
    if (content === undefined) continue;
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    fs.writeFileSync(path.join(root, file), content);
  }
  const fe = path.join(root, 'fe');
  return { fe, be: path.join(root, 'be'), packsDir: path.join(fe, 'languages') };
}

const USES = { admin: ['da'], info: ['da', 'kl'], backend: {} };

/** The check of a tree, as edited by `edit` and with the options given. */
function check(edit, { uses = USES, accepted = [], workspaceUses = undefined, covered } = {}) {
  const { fe, be, packsDir } = tree(edit);
  return checkPacks({
    catalog: buildCatalog({ fe, be }),
    packs: packFolders(packsDir),
    uses,
    accepted,
    workspaceUses: workspaceUses ?? uses,
    ...(covered && { covered }),
  });
}

const editJson = (file, change) => (files) => {
  const json = JSON.parse(files[file]);
  change(json);
  files[file] = `${JSON.stringify(json, null, 2)}\n`;
};
const DA_ADMIN = 'fe/languages/da/i18n/admin/da.json';
const DA_INFO = 'fe/languages/da/i18n/info/da.json';
const KL_INFO = 'fe/languages/kl/i18n/info/kl.json';
const hatchEntry = (lang, app, key, extra = {}) => ({
  lang,
  app,
  key,
  reason: '4.0.183 security fix',
  added: '2026-11-02',
  ...extra,
});

function assertNamed(lines, ...fragments) {
  for (const fragment of fragments)
    assert.ok(
      lines.some((line) => line.includes(fragment)),
      `expected a line naming "${fragment}", got:\n${lines.join('\n') || '(none)'}`,
    );
}

describe('pack check', () => {
  it('passes packs that hold every catalog key with its placeholders, markup and patterns', () => {
    const result = check();
    assert.deepEqual(result.errors, []);
    assert.deepEqual(result.warnings, []);
    assert.deepEqual(result.stripped, []);
    assert.deepEqual(result.renames, []);
    for (const field of ['missing', 'markup', 'placeholders', 'changedEnglish', 'hatchStale'])
      assert.deepEqual(result[field], [], field);
  });

  it('fails a key a pack gives twice, once flat and once nested', () => {
    const result = check(editJson(DA_ADMIN, (da) => (da['menu.close'] = 'Lukke')));
    assertNamed(result.errors, 'languages/da/i18n/admin/da.json "menu.close" is given twice');
  });

  it('gives the findings the upgrade report lists structured, beside the same lines', () => {
    const edit = (files) => {
      editJson(DA_ADMIN, (da) => delete da.menu.close)(files);
      editJson(DA_ADMIN, (da) => (da.publish.summary = 'Går ud med {{count}}'))(files);
      // The Danish info title was translated from another English text.
      editJson('fe/languages/da/status/fingerprints.json', (fingerprints) => {
        fingerprints.info.SITE_TITLE = fingerprint('Notes');
      })(files);
    };
    const accepted = [hatchEntry('da', 'info', 'BTN_CLOSE')];
    const result = check(edit, { accepted });
    assert.deepEqual(result.missing, [
      {
        lang: 'da',
        app: 'admin',
        keys: ['menu.close'],
        error: 'languages/da/i18n/admin/da.json lacks 1 catalog key(s): menu.close',
      },
    ]);
    assert.deepEqual(result.markup, [
      {
        lang: 'da',
        app: 'admin',
        key: 'publish.summary',
        error:
          "languages/da/i18n/admin/da.json: publish.summary's markup is none, English's </strong> <strong>",
      },
    ]);
    // Each structured finding carries its line, exactly as the errors hold it.
    for (const finding of [...result.missing, ...result.markup])
      assert.ok(result.errors.includes(finding.error), finding.error);
    assert.deepEqual(result.changedEnglish, [{ lang: 'da', app: 'info', key: 'SITE_TITLE' }]);
    assert.deepEqual(result.hatchStale, [
      { lang: 'da', app: 'info', key: 'BTN_CLOSE', why: 'translated' },
    ]);
    assertNamed(
      result.errors,
      'languages/da/i18n/admin/da.json lacks 1 catalog key(s): menu.close',
      "publish.summary's markup is none, English's",
    );
    assertNamed(result.warnings, 'SITE_TITLE was translated from another English text');
  });

  it('missing key: a catalog key lacking, or empty where English is not, fails unless the hatch lists it', () => {
    const edit = (files) => {
      editJson(DA_ADMIN, (da) => delete da.menu.close)(files);
      editJson(DA_INFO, (da) => (da.BTN_CLOSE = ' '))(files);
    };
    const { errors } = check(edit);
    assertNamed(
      errors,
      'languages/da/i18n/admin/da.json lacks 1 catalog key(s): menu.close',
      'languages/da/i18n/info/da.json lacks 1 catalog key(s): BTN_CLOSE',
    );
    assert.equal(errors.length, 2, errors.join('\n'));
    const accepted = [
      hatchEntry('da', 'admin', 'menu.close'),
      hatchEntry('da', 'info', 'BTN_CLOSE'),
    ];
    assert.deepEqual(check(edit, { accepted }).errors, []);
    assertNamed(
      check((files) => (files[DA_ADMIN] = undefined)).errors,
      'languages/da/i18n/admin/da.json does not exist, and da is a UI language of an admin build',
    );
    assertNamed(
      check(undefined, { uses: { ...USES, admin: ['da', 'fr'] } }).errors,
      'languages/fr/ does not exist, and fr is a UI language of an admin build',
    );
    // English's own empty text may stay empty in a translation.
    assert.deepEqual(check(editJson(DA_ADMIN, (da) => (da.untitled = ''))).errors, []);
  });

  it('backend: a missing key or half fails for a UI language and warns for a content language', () => {
    const edit = (files) => {
      files['fe/languages/da/backend/pdf.properties'] = '# nothing yet\n';
      files['fe/languages/da/backend/mail.properties'] = undefined;
      files['fe/languages/da/backend/web.properties'] = 'msg.more = Hent {{from}} - {{to}}\n';
    };
    const ui = check(edit, { uses: { ...USES, backend: { da: 'ui' } } });
    assertNamed(
      ui.errors,
      'languages/da/backend/pdf.properties lacks 1 catalog key(s): pdf.volume',
      'languages/da/backend/mail.properties does not exist, and da is a UI language',
    );
    // Another language's name, lang.en, is optional; the pack's own, lang.da, is a warning.
    assert.equal(ui.errors.length, 2, `lang.en is optional:\n${ui.errors.join('\n')}`);
    assertNamed(ui.warnings, 'languages/da/backend/web.properties has no lang.da');
    const content = check(edit, { uses: { ...USES, backend: { da: 'content' } } });
    assert.deepEqual(content.errors, []);
    assertNamed(
      content.warnings,
      'languages/da/backend/pdf.properties lacks 1 catalog key(s): pdf.volume',
      'languages/da/backend/mail.properties does not exist, and da is a content language',
      'languages/da/backend/web.properties has no lang.da',
    );
  });

  it("backend: a pack's own lang.<code> lacking from its web half is a warning for a UI and a content language alike", () => {
    const ownName = (text) => (files) => {
      acceptedBackend(files);
      files['fe/languages/da/backend/web.properties'] = text;
    };
    const warning =
      "languages/da/backend/web.properties has no lang.da, the language's name in a draft print: where no pack has it, the print names da by its code, DA";
    for (const use of ['ui', 'content'])
      for (const text of [
        'lang.en = Engelsk\nmsg.more = Hent {{from}} - {{ to }}\n',
        'lang.da =\nlang.en = Engelsk\nmsg.more = Hent {{from}} - {{ to }}\n',
      ]) {
        const result = check(ownName(text), { uses: { ...USES, backend: { da: use } } });
        assert.deepEqual([result.errors, result.warnings], [[], [warning]], `${use}: ${text}`);
        assert.deepEqual(result.missing, [], use);
      }
    // The pack's own name present, another language's absent: nothing to say, nothing stripped.
    const own = check(
      ownName('lang.da = Dansk\nmsg.more = Hent {{from}} - {{ to }}\n'),
      UI_BACKEND,
    );
    assert.deepEqual([own.errors, own.warnings, own.stripped], [[], [], []]);
    // No web half at all: the one line of the bundle names the missing lang.da too.
    const none = check(ownName(undefined), { uses: { ...USES, backend: { da: 'content' } } });
    assert.deepEqual(none.errors, []);
    const web = none.warnings.filter((line) => line.includes('web.properties'));
    assert.deepEqual(web, [
      "languages/da/backend/web.properties does not exist, and da is a content language: the default language serves its 1 key(s); and no lang.da, the language's name in a draft print: where no pack has it, the print names da by its code, DA",
    ]);
  });

  it("backend: a text may be empty where English's is, and fails empty where English's is not", () => {
    const blankInEnglish = (files) => {
      files[`${RESOURCES}/template_en.properties`] =
        'cancel.message = Cancel message {0}\ncancel.prefix =\n';
      files['fe/languages/da/backend/template.properties'] =
        'cancel.message = Annullér meddelelse {0}\ncancel.prefix =\n';
    };
    const uses = { uses: { ...USES, backend: { da: 'ui' } } };
    assert.deepEqual(check(blankInEnglish, uses).errors, []);
    const blankInDanish = (files) => {
      blankInEnglish(files);
      files['fe/languages/da/backend/message.properties'] = 'msg.title =\n';
    };
    const { errors } = check(blankInDanish, uses);
    assertNamed(
      errors,
      'languages/da/backend/message.properties lacks 1 catalog key(s): msg.title',
    );
    assert.equal(errors.length, 1, errors.join('\n'));
  });

  it("placeholders: a text whose placeholders are not English's fails unless the hatch lists it", () => {
    const edit = (files) => {
      editJson(DA_ADMIN, (da) => (da.menu.open = 'Åbn {{navn}}'))(files);
      files['fe/languages/da/backend/message.properties'] = 'msg.title = Meddelelse {1}\n';
    };
    const result = check(edit, { uses: { ...USES, backend: { da: 'ui' } } });
    const { errors } = result;
    assertNamed(
      errors,
      'languages/da/i18n/admin/da.json: menu.open has the placeholders navn, English name',
      'languages/da/backend/message.properties: msg.title has the placeholders {1}, English {0}',
    );
    // Structured too, each finding with its line, for the upgrade report.
    assert.deepEqual(result.placeholders, [
      {
        lang: 'da',
        app: 'admin',
        key: 'menu.open',
        error: 'languages/da/i18n/admin/da.json: menu.open has the placeholders navn, English name',
      },
      {
        lang: 'da',
        app: 'backend',
        bundle: 'message',
        key: 'msg.title',
        error:
          'languages/da/backend/message.properties: msg.title has the placeholders {1}, English {0}',
      },
    ]);
    for (const finding of result.placeholders)
      assert.ok(errors.includes(finding.error), finding.error);
    const hatched = check(edit, { accepted: [hatchEntry('da', 'admin', 'menu.open')] });
    assert.deepEqual(hatched.errors, []);
    assert.deepEqual(hatched.placeholders, []);
  });

  it('text overrides: a key an override supplies is not missing, and its placeholders do not fail', () => {
    const edit = (files) => {
      editJson(DA_ADMIN, (da) => delete da.menu.close)(files);
      editJson(DA_ADMIN, (da) => (da.menu.open = 'Åbn {{navn}}'))(files);
      editJson(DA_INFO, (da) => delete da.BTN_CLOSE)(files);
    };
    const covered = { da: { admin: ['menu.close', 'menu.open'] } };
    const result = check(edit, { covered });
    // The info key no override supplies still fails.
    assert.deepEqual(result.errors, [
      'languages/da/i18n/info/da.json lacks 1 catalog key(s): BTN_CLOSE',
    ]);
    assert.deepEqual(result.fromOverrides, [
      { lang: 'da', app: 'admin', keys: ['menu.open', 'menu.close'] },
    ]);
    assertNamed(
      report(result, 'missing-keys-accepted.json'),
      'da admin: 2 key(s) the pack lacks come from text overrides: menu.open, menu.close',
    );
    // Covering a key for another language or application covers nothing here.
    const elsewhere = check(edit, { covered: { kl: { admin: ['menu.close'] } } });
    assertNamed(
      elsewhere.errors,
      'languages/da/i18n/admin/da.json lacks 1 catalog key(s): menu.close',
      'menu.open has the placeholders navn, English name',
    );
    assert.deepEqual(elsewhere.fromOverrides, []);
    // A hatch entry for a key an override supplies is stale.
    const hatched = check(edit, { covered, accepted: [hatchEntry('da', 'admin', 'menu.close')] });
    assertNamed(hatched.warnings, 'da admin menu.close, which a text override supplies');
    // With no overrides the check is as it was.
    assert.deepEqual(check().fromOverrides, []);
  });

  it('renamedFrom: a pack that has the old key is reported as a rename, the new key missing', () => {
    const result = check((files) => {
      files['fe/languages/en/renames.json'] = JSON.stringify({
        admin: { 'menu.close': 'menu.shut' },
      });
      editJson(DA_ADMIN, (da) => {
        da.menu.shut = da.menu.close;
        delete da.menu.close;
      })(files);
    });
    assert.deepEqual(result.renames, [
      { lang: 'da', app: 'admin', key: 'menu.close', from: 'menu.shut' },
    ]);
    assertNamed(result.errors, 'lacks 1 catalog key(s): menu.close');
    assert.deepEqual(result.stripped, [{ lang: 'da', app: 'admin', key: 'menu.shut' }]);
  });

  it('date pattern: an unknown token, or a word list of another length or with an empty entry, fails', () => {
    const { errors } = check((files) => {
      editJson(DA_INFO, (da) => {
        da.SOURCE_DATE_FORMAT = "QQQ d. MMMM yyyy 'kl.' HH:mm";
        da.DATE_DAYS_SHORT = 'sø|ma|ti|on|to|fr';
      })(files);
      editJson(KL_INFO, (kl) => (kl.DATE_DAYS_SHORT = 'sap|ata||pin|sis|tal|arf'))(files);
    });
    assertNamed(
      errors,
      'languages/da/i18n/info/da.json: SOURCE_DATE_FORMAT "QQQ d. MMMM yyyy \'kl.\' HH:mm" has the token(s) QQQ',
      'languages/da/i18n/info/da.json: DATE_DAYS_SHORT has 6 entries, English 7',
      'languages/kl/i18n/info/kl.json: DATE_DAYS_SHORT has an empty entry',
    );
  });

  it("markup: tags, attributes or links other than English's, or markup where English has none, fail", () => {
    const { errors } = check(
      (files) => {
        editJson(DA_ADMIN, (da) => {
          da.publish.summary = 'Går ud med <b>{{count}}</b>';
          da.menu.close = '<a href="https://evil.example/">Luk</a>';
        })(files);
        editJson(DA_INFO, (da) => (da.BTN_CLOSE = '<foo onclick="x()">Luk</foo>'))(files);
        files['fe/languages/da/backend/mail.properties'] = 'mail.regards = Venlig hilsen, {0}\n';
      },
      { uses: { ...USES, backend: { da: 'ui' } } },
    );
    assertNamed(
      errors,
      "publish.summary's markup is </b> <b>, English's </strong> <strong>",
      'menu.close has markup, </a> <a> @href href=https://evil.example/, where English has none',
      "languages/da/backend/mail.properties: mail.regards's markup is none, English's <br>",
      'BTN_CLOSE has markup, <foo> @onclick, where English has none',
    );
  });

  it('pack.json, flag, path: a pack outside its schema, a flag that is no PNG, a forbidden key fail', () => {
    const { errors } = check((files) => {
      files['fe/languages/da/pack.json'] = packJson('da', 'Dansk', 'de_DE');
      files['fe/languages/kl/img/flags/kl.png'] = '<svg xmlns="http://www.w3.org/2000/svg"/>';
    });
    assertNamed(
      errors,
      `languages/da: pack.json's locale "de_DE" is not a language code`,
      'languages/kl: img/flags/kl.png is not a PNG',
    );
    const forbidden = check((files) => {
      files[KL_INFO] = '{ "SITE_TITLE": "x", "__proto__": { "BTN_CLOSE": "y" } }';
    });
    assertNamed(forbidden.errors, 'languages/kl/i18n/info/kl.json has the key "__proto__"');
    const outside = check((files) => {
      files['fe/languages/da/pack.json'] = JSON.stringify({
        ...JSON.parse(packJson('da', 'Dansk', 'da')),
        flag: '../en/img/flags/en.png',
      });
    });
    assertNamed(
      outside.errors,
      `pack.json's flag is "../en/img/flags/en.png", not "img/flags/da.png"`,
    );
  });

  it('extra key: a key the catalog lacks is stripped and listed, and fails nothing', () => {
    const result = check(
      editJson(DA_ADMIN, (da) => {
        da.menu.gone = 'Væk';
      }),
    );
    assert.deepEqual(result.errors, []);
    assert.deepEqual(result.stripped, [{ lang: 'da', app: 'admin', key: 'menu.gone' }]);
    assertNamed(
      report(result, 'missing-keys-accepted.json'),
      'stripped (not in the catalog): languages/da/i18n/admin/da.json: menu.gone',
    );
  });

  it('fingerprint: a text translated from another English text is a warning', () => {
    const result = check(
      editJson('fe/languages/da/status/fingerprints.json', (fingerprints) => {
        fingerprints.admin['menu.close'] = fingerprint('Shut');
      }),
    );
    assert.deepEqual(result.errors, []);
    assertNamed(
      result.warnings,
      `languages/da/i18n/admin/da.json: menu.close was translated from another English text (fingerprint ${fingerprint('Shut')}, English's now ${fingerprint('Close')})`,
    );
  });

  it('hatch entry stale: an entry whose key is now translated, or gone from the catalog, is a warning', () => {
    const result = check(undefined, {
      accepted: [hatchEntry('da', 'admin', 'menu.close'), hatchEntry('da', 'info', 'GONE_KEY')],
    });
    assert.deepEqual(result.errors, []);
    assertNamed(
      result.warnings,
      'the escape hatch lists da admin menu.close, which the pack now translates',
      'the escape hatch lists da info GONE_KEY, which the catalog no longer has',
    );
  });

  it('hatch entry unused: an entry for a language or application no build uses fails', () => {
    const { errors } = check(undefined, {
      accepted: [
        hatchEntry('kl', 'admin', 'menu.close'),
        hatchEntry('fr', 'backend', 'pdf.volume', { bundle: 'pdf' }),
        hatchEntry('da', 'backend', 'pdf.volume'),
      ],
    });
    assertNamed(
      errors,
      'the escape hatch lists kl admin menu.close, but no admin build has kl as a UI language',
      'the escape hatch lists fr backend pdf pdf.volume, but no build uses fr',
      "the escape hatch's entry 3 has added, app, key, lang, reason; it has exactly added, app, bundle, key, lang, reason",
    );
  });

  it('hatch printed: a non-empty escape hatch is printed in full', () => {
    const accepted = [hatchEntry('da', 'admin', 'menu.close')];
    const result = check(
      editJson(DA_ADMIN, (da) => delete da.menu.close),
      { accepted },
    );
    assert.deepEqual(result.errors, []);
    assert.deepEqual(result.accepted, accepted);
    assertNamed(
      report(result, 'missing-keys-accepted.json'),
      'the escape hatch, missing-keys-accepted.json, lists 1 key(s) shown in English:',
      'da admin menu.close — 4.0.183 security fix (added 2026-11-02)',
    );
  });

  it('status files: their shape, every listed key once with a text, verbatim, no fingerprint ⇒ unaccepted', () => {
    const { errors } = check((files) => {
      files['fe/languages/kl/status/unaccepted.json'] = formatJson({
        note: NOTE,
        info: {
          unaccepted: ['BTN_CLOSE', 'SITE_TITLE', 'BTN_CLOSE', 'GONE_KEY'],
          cldr: ['DATE_DAYS_SHORT', 'SITE_TITLE'],
          verbatim: ['SOURCE_DATE_FORMAT', 'DATE_DAYS_SHORT'],
          reviewed: [],
        },
      });
      editJson(DA_INFO, (da) => (da.SOURCE_DATE_FORMAT = "d. MMMM yyyy 'klokken' HH:mm"))(files);
      editJson('fe/languages/da/status/fingerprints.json', (fingerprints) => {
        delete fingerprints.admin['menu.open'];
      })(files);
    });
    assertNamed(
      errors,
      'languages/kl/status/unaccepted.json: info has "reviewed"; its lists are unaccepted, cldr, verbatim',
      'languages/kl/status/unaccepted.json: info lists BTN_CLOSE twice in unaccepted',
      'languages/kl/status/unaccepted.json: info.unaccepted lists GONE_KEY, which the bundle does not have',
      'languages/kl/status/unaccepted.json: info lists SITE_TITLE in unaccepted and cldr',
      "languages/kl/status/unaccepted.json: info.verbatim lists SOURCE_DATE_FORMAT, whose text is not the same key's text in another pack checked with it (da)",
      'languages/da/status: admin menu.open has no fingerprint and is not unaccepted',
    );
  });

  it('status files: a key is in exactly one of unaccepted and the fingerprints', () => {
    // Unaccepted and fingerprinted: the seeded state the check used to let through.
    const both = check(
      editJson('fe/languages/kl/status/fingerprints.json', (fingerprints) => {
        fingerprints.info.SITE_TITLE = fingerprint('Notices');
      }),
    );
    assert.deepEqual(both.errors, [
      'languages/kl/status: info SITE_TITLE is unaccepted and has a fingerprint; a key is in one of the two, and `pack accept` moves it from the first to the second',
    ]);
    // Taken off unaccepted by hand, with no fingerprint recorded: still refused.
    const byHand = check((files) => {
      files['fe/languages/kl/status/unaccepted.json'] = formatJson({
        note: NOTE,
        info: {
          unaccepted: ['BTN_CLOSE'],
          cldr: ['DATE_DAYS_SHORT'],
          verbatim: ['SOURCE_DATE_FORMAT'],
        },
      });
    });
    assert.deepEqual(byHand.errors, [
      'languages/kl/status: info SITE_TITLE has no fingerprint and is not unaccepted; a text is accepted with `pack accept`, which records the English it was translated from',
    ]);
  });
});

describe('pack tools', () => {
  it("migrate moves a renamed key's text, fingerprint and status entry to the new key", () => {
    // menu.shut is unaccepted, so it has no fingerprint; the backend key has one.
    const { fe, be, packsDir } = tree((files) => {
      files['fe/languages/en/renames.json'] = JSON.stringify({
        admin: { 'menu.close': 'menu.shut' },
        backend: { pdf: { 'pdf.volume': 'pdf.tome' } },
      });
      editJson(DA_ADMIN, (da) => {
        da.menu.shut = da.menu.close;
        delete da.menu.close;
      })(files);
      editJson('fe/languages/da/status/fingerprints.json', (fingerprints) => {
        delete fingerprints.admin['menu.close'];
        fingerprints.backend = { pdf: { 'pdf.tome': fingerprint('Volume {0}') } };
      })(files);
      files['fe/languages/da/status/unaccepted.json'] = formatJson({
        note: NOTE,
        admin: { unaccepted: ['menu.shut'] },
        info: { unaccepted: [] },
      });
      files['fe/languages/da/backend/pdf.properties'] = Buffer.from(
        '# Bind på dansk\r\npdf.tome = Bind {0} – æøå\r\n',
        'utf8',
      );
    });
    const catalog = buildCatalog({ fe, be });
    const { moved, written } = migratePack({ packsDir, lang: 'da', catalog });
    assert.deepEqual(moved, [
      { app: 'admin', key: 'menu.close', from: 'menu.shut' },
      { app: 'backend', bundle: 'pdf', key: 'pdf.volume', from: 'pdf.tome' },
    ]);
    assert.deepEqual(written.sort(), [
      'backend/pdf.properties',
      'i18n/admin/da.json',
      'status/fingerprints.json',
      'status/unaccepted.json',
    ]);
    const read = (file) => fs.readFileSync(path.join(packsDir, 'da', file), 'utf8');
    assert.equal(JSON.parse(read('i18n/admin/da.json')).menu.close, 'Luk');
    assert.deepEqual(JSON.parse(read('status/fingerprints.json')).backend, {
      pdf: { 'pdf.volume': fingerprint('Volume {0}') },
    });
    assert.equal(JSON.parse(read('status/fingerprints.json')).admin['menu.close'], undefined);
    assert.deepEqual(JSON.parse(read('status/unaccepted.json')).admin.unaccepted, ['menu.close']);
    // Byte for byte as it was, apart from the renamed key: UTF-8 in, UTF-8 out.
    assert.deepEqual(
      fs.readFileSync(path.join(packsDir, 'da/backend/pdf.properties')),
      Buffer.from('# Bind på dansk\r\npdf.volume = Bind {0} – æøå\r\n', 'utf8'),
    );
    const after = checkPacks({ catalog, packs: packFolders(packsDir), uses: USES });
    assert.deepEqual([after.errors, after.renames], [[], []]);
    assert.deepEqual(migratePack({ packsDir, lang: 'da', catalog }).written, []);
    // A half saved as Latin-1 is refused, not read otherwise than the backend reads it.
    fs.writeFileSync(
      path.join(packsDir, 'da/backend/pdf.properties'),
      Buffer.from('pdf.tome = Bind {0} æøå\n', 'latin1'),
    );
    assert.throws(
      () => migratePack({ packsDir, lang: 'da', catalog }),
      /languages\/da\/backend\/pdf\.properties is not valid UTF-8/,
    );
  });

  it('prune drops the keys no catalog has and leaves every other byte as it was', () => {
    const { fe, be, packsDir } = tree((files) => {
      editJson(DA_ADMIN, (da) => {
        da.old = { gone: 'Væk' };
      })(files);
      editJson('fe/languages/da/status/fingerprints.json', (fingerprints) => {
        fingerprints.admin['old.gone'] = fingerprint('Gone');
      })(files);
      files['fe/languages/da/backend/web.properties'] =
        'lang.da = Dansk\nold.key = gammel \\\n  tekst\nmsg.more = Hent {{from}} - {{ to }}\n';
    });
    const before = (file) => fs.readFileSync(path.join(packsDir, file));
    const untouched = ['da/i18n/info/da.json', 'da/status/unaccepted.json', 'kl/i18n/info/kl.json'];
    const bytes = untouched.map(before);
    const { removed, written } = prunePack({
      packsDir,
      lang: 'da',
      catalogs: [buildCatalog({ fe, be })],
    });
    assert.deepEqual(removed, [
      { app: 'admin', key: 'old.gone' },
      { app: 'backend', bundle: 'web', key: 'old.key' },
    ]);
    assert.deepEqual(written.sort(), [
      'backend/web.properties',
      'i18n/admin/da.json',
      'status/fingerprints.json',
    ]);
    assert.equal(
      fs.readFileSync(path.join(packsDir, 'da/i18n/admin/da.json'), 'utf8'),
      `${JSON.stringify(DA.admin, null, 2)}\n`,
    );
    assert.equal(
      fs.readFileSync(path.join(packsDir, 'da/status/fingerprints.json'), 'utf8'),
      formatJson(fingerprintsOf(DA)),
    );
    assert.equal(
      fs.readFileSync(path.join(packsDir, 'da/backend/web.properties'), 'utf8'),
      'lang.da = Dansk\nmsg.more = Hent {{from}} - {{ to }}\n',
    );
    assert.deepEqual(untouched.map(before), bytes);
  });

  it('accept takes a key off unaccepted and records the English it was accepted against', () => {
    const { fe, be, packsDir } = tree((files) => {
      files['fe/languages/da/status/unaccepted.json'] = formatJson({
        note: NOTE,
        admin: { unaccepted: ['menu.close', 'menu.open'] },
        info: { unaccepted: [] },
      });
      editJson('fe/languages/da/status/fingerprints.json', (fingerprints) => {
        delete fingerprints.admin['menu.close'];
        fingerprints.admin['menu.open'] = fingerprint('Open');
      })(files);
    });
    const catalog = buildCatalog({ fe, be });
    const { removed } = acceptKeys({
      packsDir,
      lang: 'da',
      app: 'admin',
      keys: ['menu.close', 'menu.open'],
      catalog,
    });
    assert.deepEqual(removed, ['menu.close', 'menu.open']);
    assert.equal(
      fs.readFileSync(path.join(packsDir, 'da/status/unaccepted.json'), 'utf8'),
      `{\n  "note": "${NOTE}",\n  "admin": {\n    "unaccepted": []\n  },\n  "info": {\n    "unaccepted": []\n  }\n}\n`,
    );
    const fingerprints = JSON.parse(
      fs.readFileSync(path.join(packsDir, 'da/status/fingerprints.json'), 'utf8'),
    );
    assert.equal(fingerprints.admin['menu.close'], fingerprint('Close'));
    assert.equal(fingerprints.admin['menu.open'], fingerprint('Open {{ name }}'));
    assert.deepEqual(Object.keys(fingerprints.admin), Object.keys(fingerprints.admin).sort());
    assert.throws(
      () => acceptKeys({ packsDir, lang: 'da', app: 'admin', keys: ['menu.nothing'], catalog }),
      /menu\.nothing is not in the admin catalog/,
    );
  });

  it('sets a text where its key is, nests a new key as the bundle does, and refuses a forbidden name', () => {
    const nested = { menu: { open: 'Open', close: 'Close' }, title: 'Title' };
    setText(nested, 'menu.close', 'Shut');
    setText(nested, 'menu.help', 'Help');
    assert.deepEqual(nested, {
      menu: { open: 'Open', close: 'Shut', help: 'Help' },
      title: 'Title',
    });
    const flat = { 'BTN.CLOSE': 'Close', TITLE: 'Notices' };
    setText(flat, 'BTN.CLOSE', 'Luk');
    setText(flat, 'BTN.OPEN', 'Åbn');
    assert.deepEqual(flat, { 'BTN.CLOSE': 'Luk', TITLE: 'Notices', 'BTN.OPEN': 'Åbn' });
    assert.throws(() => setText(nested, 'menu.__proto__.x', 'y'), /forbidden name/);
  });

  it("sets a new key in English's shape for it, and never replaces a text at a segment", () => {
    // English writes menu.action flat beside the nested menu, and admin.areas nested.
    const english = {
      'menu.action': 'Action',
      menu: { open: 'Open' },
      admin: { areas: { x: 'X' } },
    };
    const pack = { menu: { open: 'Åbn' }, 'admin.areas.delete': 'Slet' };
    setKey(pack, 'menu.action', 'Handling', english);
    setKey(pack, 'admin.areas.x', 'Æ', english);
    assert.deepEqual(pack, {
      menu: { open: 'Åbn' },
      'admin.areas.delete': 'Slet',
      'menu.action': 'Handling',
      admin: { areas: { x: 'Æ' } },
    });
    // Without English the bundle's own nesting decides, as before.
    const nested = { menu: { open: 'Åbn' } };
    setKey(nested, 'menu.close.now', 'Luk');
    assert.deepEqual(nested, { menu: { open: 'Åbn', close: { now: 'Luk' } } });
    assert.throws(
      () =>
        setKey({ menu: { close: 'Luk' } }, 'menu.close.now', 'Nu', {
          menu: { close: { now: 'Now' } },
        }),
      /"menu\.close" is a text, and the key goes under it/,
    );
    assert.throws(
      () => setKey({ menu: { close: 'Luk' } }, 'menu.close.now', 'Nu'),
      /"menu\.close" is a text, and the key goes under it/,
    );
  });

  it('migrate, prune and accept run in a country workspace: home packs, the pinned catalog', async () => {
    const { fe, be } = tree((files) => {
      files['fe/languages/en/renames.json'] = JSON.stringify({
        admin: { 'menu.close': 'menu.shut' },
      });
      editJson(DA_ADMIN, (da) => {
        da.menu.shut = da.menu.close;
        delete da.menu.close;
        da.old = { gone: 'Væk' };
      })(files);
      editJson('fe/languages/da/status/fingerprints.json', (fingerprints) => {
        delete fingerprints.admin['menu.close'];
      })(files);
      files['fe/languages/da/status/unaccepted.json'] = formatJson({
        note: NOTE,
        admin: { unaccepted: ['menu.shut'] },
        info: { unaccepted: [] },
      });
    });
    // A country's fe/: the zip unpacked into .niord/base/ (English and the catalog) and its
    // home pack in packs/. The base product's languages/da/ and be/ are not there.
    const country = path.join(path.dirname(fe), 'country', 'fe');
    const base = path.join(country, '.niord', 'base');
    fs.cpSync(path.join(fe, 'languages', 'en'), path.join(base, 'languages', 'en'), {
      recursive: true,
    });
    fs.writeFileSync(
      path.join(base, 'catalog.json'),
      serializeCatalog(buildCatalog({ fe, be, version: '4.0.1' })),
    );
    fs.cpSync(path.join(fe, 'languages', 'da'), path.join(country, 'packs', 'da'), {
      recursive: true,
    });
    assert.deepEqual(packsLayout(base), {
      country: true,
      packsDir: path.join(country, 'packs'),
      catalog: path.join(base, 'catalog.json'),
      english: path.join(base, 'languages', 'en'),
    });
    assert.equal(packsLayout(fe).packsDir, path.join(fe, 'languages'));
    const run = async (args, fromTree = base) => {
      const lines = [];
      const status = await main(args, { fe: fromTree, log: (line) => lines.push(line) });
      return { status, lines };
    };
    const pack = (file) => path.join(country, 'packs', 'da', file);
    const readJson = (file) => JSON.parse(fs.readFileSync(pack(file), 'utf8'));

    const migrated = await run(['migrate', 'da']);
    assert.equal(migrated.status, 0);
    assert.deepEqual(migrated.lines, [
      'pack migrate: da admin menu.shut → menu.close',
      'pack migrate: 1 key(s) moved; packs/da/i18n/admin/da.json, packs/da/status/unaccepted.json written',
    ]);
    assert.equal(readJson('i18n/admin/da.json').menu.close, 'Luk');
    const pruned = await run(['prune', 'da']);
    assert.deepEqual(pruned.lines, [
      'pack prune: da admin old.gone',
      'pack prune: 1 key(s) removed; packs/da/i18n/admin/da.json written',
    ]);
    const accepted = await run(['accept', 'da', '--app', 'admin', 'menu.close']);
    assert.deepEqual(accepted.lines, [
      'pack accept: 1 key(s) accepted, 1 of them taken off unaccepted; packs/da/status/unaccepted.json, packs/da/status/fingerprints.json written',
    ]);
    assert.deepEqual(readJson('status/unaccepted.json').admin.unaccepted, []);
    assert.equal(readJson('status/fingerprints.json').admin['menu.close'], fingerprint('Close'));
    await assert.rejects(run(['check']), /set-up \(node setup\.mjs\) runs the pack check/);

    // From any tree with --packs and --catalog; that tree's own languages/ is left alone.
    const own = fs.readFileSync(path.join(fe, 'languages/da/i18n/admin/da.json'), 'utf8');
    fs.writeFileSync(
      pack('i18n/admin/da.json'),
      JSON.stringify({ ...readJson('i18n/admin/da.json'), stray: 'x' }, null, 2),
    );
    const flags = await run(
      [
        'prune',
        'da',
        '--packs',
        path.join(country, 'packs'),
        '--catalog',
        path.join(base, 'catalog.json'),
      ],
      fe,
    );
    assert.equal(flags.lines[0], 'pack prune: da admin stray');
    assert.equal(fs.readFileSync(path.join(fe, 'languages/da/i18n/admin/da.json'), 'utf8'), own);
  });

  it("refuses a tree that is neither the base product's fe/ nor a country's .niord/base/", async () => {
    const elsewhere = fs.mkdtempSync(path.join(os.tmpdir(), 'pack-elsewhere-'));
    temporary.push(elsewhere);
    assert.throws(() => packsLayout(elsewhere), /is not where the kit finds the base product: /);
    await assert.rejects(
      main(['prune', 'da'], { fe: elsewhere, log: () => {} }),
      /is not where the kit finds the base product: /,
    );
  });

  it('writes the status files as Prettier does: a list on one line where it fits', () => {
    const long = Array.from({ length: 12 }, (_, i) => `KEY_NUMBER_${i}`);
    assert.equal(
      formatJson({ note: 'n', info: { unaccepted: ['A', 'B'], cldr: long } }),
      `{\n  "note": "n",\n  "info": {\n    "unaccepted": ["A", "B"],\n    "cldr": [\n${long.map((key) => `      "${key}"`).join(',\n')}\n    ]\n  }\n}\n`,
    );
  });
});

const UI_BACKEND = { uses: { ...USES, backend: { da: 'ui' } } };
const DA_UNACCEPTED = 'fe/languages/da/status/unaccepted.json';
const DA_FINGERPRINTS = 'fe/languages/da/status/fingerprints.json';

/** Every backend key of the Danish half, fingerprinted as accepted: no count warning is left. */
function acceptedBackend(files) {
  editJson(DA_FINGERPRINTS, (fingerprints) => {
    fingerprints.backend = {
      web: { 'msg.more': fingerprint('Load {{ from }} - {{to}}') },
      message: { 'msg.title': fingerprint('Message {0}') },
      pdf: { 'pdf.volume': fingerprint('Volume {0}') },
      mail: { 'mail.regards': fingerprint('Kind regards,<br>{0}') },
      template: { 'cancel.message': fingerprint('Cancel message {0}') },
    };
  })(files);
}

describe("the backend half's status", () => {
  it('counts the keys in neither list in one warning per bundle, which is no error', () => {
    // DK's state when the backend half came: texts, no fingerprints, no backend section.
    const result = check(undefined, UI_BACKEND);
    assert.deepEqual(result.errors, []);
    assertNamed(
      result.warnings,
      'languages/da/backend/web.properties: 1 key(s) are neither accepted nor listed as unaccepted; pack accept --app backend --bundle web records an accepted one',
      'languages/da/backend/template.properties: 1 key(s) are neither',
    );
    assert.equal(result.warnings.length, 5, result.warnings.join('\n'));
    // Accepted, or listed as unaccepted: nothing to count.
    const tracked = check((files) => {
      acceptedBackend(files);
      editJson(DA_FINGERPRINTS, (fingerprints) => delete fingerprints.backend.pdf)(files);
      editJson(DA_UNACCEPTED, (status) => {
        status.backend = { pdf: { unaccepted: ['pdf.volume'] } };
      })(files);
    }, UI_BACKEND);
    assert.deepEqual([tracked.errors, tracked.warnings], [[], []]);
  });

  it('fails a backend section outside its shape', () => {
    const { errors } = check(
      editJson(DA_UNACCEPTED, (status) => {
        status.backend = {
          pdf: { unaccepted: ['pdf.volume'], cldr: [] },
          Mail: { unaccepted: [] },
          web: { unaccepted: 'msg.more' },
        };
      }),
      UI_BACKEND,
    );
    assertNamed(
      errors,
      'languages/da/status/unaccepted.json: backend.pdf has exactly one list, unaccepted',
      'languages/da/status/unaccepted.json: backend names the bundle "Mail", not ^[a-z][a-z0-9]*$',
      'languages/da/status/unaccepted.json: backend.web.unaccepted is not a list of keys',
    );
    assertNamed(
      check(
        editJson(DA_UNACCEPTED, (status) => (status.backend = [])),
        UI_BACKEND,
      ).errors,
      'languages/da/status/unaccepted.json: backend is not an object of bundle → { unaccepted }',
    );
  });

  it('fails a key listed twice, listed where the half lacks it or holds it empty, or listed and fingerprinted', () => {
    const { errors } = check((files) => {
      acceptedBackend(files);
      files['fe/languages/da/backend/message.properties'] = 'msg.title =\n';
      editJson(DA_UNACCEPTED, (status) => {
        status.backend = {
          web: { unaccepted: ['msg.gone'] },
          message: { unaccepted: ['msg.title'] },
          pdf: { unaccepted: ['pdf.volume', 'pdf.volume'] },
          mail: { unaccepted: ['mail.regards'] },
          extra: { unaccepted: [] },
        };
      })(files);
    }, UI_BACKEND);
    assertNamed(
      errors,
      'languages/da/status/unaccepted.json: backend.web lists msg.gone, which backend/web.properties does not have',
      'languages/da/status/unaccepted.json: backend.message lists msg.title, whose text is empty',
      'languages/da/status/unaccepted.json: backend.pdf lists pdf.volume twice',
      'languages/da/status: backend mail mail.regards is unaccepted and has a fingerprint',
      'languages/da/status/unaccepted.json: backend names the bundle extra, which the catalog does not have',
    );
    // A listed key whose half is missing altogether is not in the half either.
    assertNamed(
      check((files) => {
        files['fe/languages/da/backend/pdf.properties'] = undefined;
        editJson(DA_UNACCEPTED, (status) => {
          status.backend = { pdf: { unaccepted: ['pdf.volume'] } };
        })(files);
      }, UI_BACKEND).errors,
      'backend.pdf lists pdf.volume, which backend/pdf.properties does not have',
    );
  });
});

describe('foreign packs', () => {
  it('foreign: a pinned pack staged with no unaccepted.json passes with keys unaccepted at home', () => {
    // kl's two unaccepted keys have no fingerprint; its archive carries no unaccepted.json.
    const edit = (files) => (files['fe/languages/kl/status/unaccepted.json'] = undefined);
    assertNamed(
      check(edit).errors,
      'languages/kl/status: info SITE_TITLE has no fingerprint and is not unaccepted',
    );
    const { fe, be, packsDir } = tree(edit);
    const catalog = buildCatalog({ fe, be });
    const run = (extra) =>
      checkPacks({ catalog, packs: packFolders(packsDir), uses: USES, ...extra });
    assert.deepEqual(run({ foreign: ['kl'] }).errors, []);
    // The fingerprint row still applies to a foreign pack.
    fs.writeFileSync(
      path.join(packsDir, 'kl', 'status', 'fingerprints.json'),
      formatJson({ info: { DATE_DAYS_SHORT: fingerprint('Sun|Mon') } }),
    );
    const changed = run({ foreign: ['kl'] });
    assert.deepEqual(changed.errors, []);
    assertNamed(changed.warnings, 'languages/kl/i18n/info/kl.json: DATE_DAYS_SHORT was translated');
    // A foreign pack's backend half has no status row of its own either.
    assert.deepEqual(
      run({ foreign: ['da', 'kl'], uses: { ...USES, backend: { da: 'ui' } } }).warnings.filter(
        (line) => line.includes('neither accepted'),
      ),
      [],
    );
  });
});

describe("the backend half's tools", () => {
  it('accept --app backend --bundle takes a key off its list and records its fingerprint', () => {
    const { fe, be, packsDir } = tree((files) => {
      editJson(DA_UNACCEPTED, (status) => {
        status.backend = { pdf: { unaccepted: ['pdf.volume'] }, mail: { unaccepted: [] } };
      })(files);
    });
    const catalog = buildCatalog({ fe, be });
    const accept = (extra) => acceptKeys({ packsDir, lang: 'da', catalog, ...extra });
    assert.throws(
      () => accept({ app: 'backend', keys: ['pdf.volume'] }),
      /--app backend needs --bundle <bundle>/,
    );
    assert.throws(
      () => accept({ app: 'admin', bundle: 'pdf', keys: ['menu.close'] }),
      /--bundle names a bundle of the backend half: it comes with --app backend/,
    );
    assert.throws(
      () => accept({ app: 'backend', bundle: 'pdf', keys: ['pdf.nothing'] }),
      /pdf\.nothing is not in the backend pdf catalog/,
    );
    assert.throws(
      () => accept({ app: 'backend', bundle: 'extra', keys: ['x'] }),
      /the catalog has no backend bundle extra/,
    );
    const { removed, written } = accept({ app: 'backend', bundle: 'pdf', keys: ['pdf.volume'] });
    assert.deepEqual(removed, ['pdf.volume']);
    assert.deepEqual(written.sort(), ['status/fingerprints.json', 'status/unaccepted.json']);
    const read = (file) => fs.readFileSync(path.join(packsDir, 'da', file), 'utf8');
    // The backend section after the applications, its bundles sorted.
    assert.equal(
      read('status/unaccepted.json'),
      `{\n  "note": "${NOTE}",\n  "admin": {\n    "unaccepted": []\n  },\n  "info": {\n    "unaccepted": []\n  },\n  "backend": {\n    "mail": {\n      "unaccepted": []\n    },\n    "pdf": {\n      "unaccepted": []\n    }\n  }\n}\n`,
    );
    assert.deepEqual(JSON.parse(read('status/fingerprints.json')).backend, {
      pdf: { 'pdf.volume': fingerprint('Volume {0}') },
    });
    const after = checkPacks({
      catalog,
      packs: packFolders(packsDir),
      uses: { ...USES, backend: { da: 'ui' } },
    });
    assert.deepEqual(after.errors, []);
    assert.ok(!after.warnings.some((line) => line.includes('backend/pdf.properties')));
  });

  it('accept refuses a backend key with no text in the half', () => {
    const { fe, be, packsDir } = tree((files) => {
      files['fe/languages/da/backend/pdf.properties'] = 'pdf.volume =\n';
    });
    assert.throws(
      () =>
        acceptKeys({
          packsDir,
          lang: 'da',
          app: 'backend',
          bundle: 'pdf',
          keys: ['pdf.volume'],
          catalog: buildCatalog({ fe, be }),
        }),
      /pdf\.volume has no text in languages\/da\/backend\/pdf\.properties to accept/,
    );
  });

  it('prune drops a backend key no catalog has from its unaccepted list; migrate moves it', () => {
    const { fe, be, packsDir } = tree((files) => {
      editJson(DA_UNACCEPTED, (status) => {
        status.backend = { pdf: { unaccepted: ['pdf.gone', 'pdf.volume'] } };
      })(files);
    });
    const { written } = prunePack({ packsDir, lang: 'da', catalogs: [buildCatalog({ fe, be })] });
    assert.deepEqual(written, ['status/unaccepted.json']);
    const read = (dir) =>
      JSON.parse(fs.readFileSync(path.join(dir, 'da/status/unaccepted.json'), 'utf8'));
    assert.deepEqual(read(packsDir).backend, { pdf: { unaccepted: ['pdf.volume'] } });

    const renamed = tree((files) => {
      files['fe/languages/en/renames.json'] = JSON.stringify({
        backend: { pdf: { 'pdf.volume': 'pdf.tome' } },
      });
      files['fe/languages/da/backend/pdf.properties'] = 'pdf.tome = Bind {0}\n';
      editJson(DA_UNACCEPTED, (status) => {
        status.backend = { pdf: { unaccepted: ['pdf.tome'] } };
      })(files);
    });
    migratePack({
      packsDir: renamed.packsDir,
      lang: 'da',
      catalog: buildCatalog({ fe: renamed.fe, be: renamed.be }),
    });
    assert.deepEqual(read(renamed.packsDir).backend, { pdf: { unaccepted: ['pdf.volume'] } });
  });
});

/** The registry's maven-metadata.xml listing `versions`. */
const metadataXml = (versions) =>
  `<metadata><versioning><versions>${versions.map((v) => `<version>${v}</version>`).join('')}</versions></versioning></metadata>`;

/**
 * A country workspace made from a tree: `.niord/base/` with English and the pinned catalog
 * 4.0.1, the home pack da and a kl folder in `packs/`, the manifest (da home and published, kl
 * pinned), and a fake Maven that serves `published`, version → catalog.
 */
function countryWorkspace(edit, published = {}) {
  const { fe, be } = tree(edit);
  const country = path.join(path.dirname(fe), 'country', 'fe');
  const base = path.join(country, '.niord', 'base');
  fs.cpSync(path.join(fe, 'languages', 'en'), path.join(base, 'languages', 'en'), {
    recursive: true,
  });
  fs.writeFileSync(
    path.join(base, 'catalog.json'),
    serializeCatalog(buildCatalog({ fe, be, version: '4.0.1' })),
  );
  for (const lang of ['da', 'kl'])
    fs.cpSync(path.join(fe, 'languages', lang), path.join(country, 'packs', lang), {
      recursive: true,
    });
  fs.writeFileSync(
    path.join(country, 'niord-country.json'),
    JSON.stringify({
      country: 'xx',
      name: 'Example',
      apps: {
        admin: { uiLanguages: ['en', 'da'], contentLanguages: ['en', 'da'], defaultLanguage: 'en' },
        info: { uiLanguages: ['da', 'kl'], defaultLanguage: 'da' },
      },
      packs: { da: { home: true, publish: true }, kl: { pinned: true } },
      locales: {},
      overrides: [],
    }),
  );
  fs.mkdirSync(path.join(path.dirname(country), 'be'), { recursive: true });
  const repository = path.join(path.dirname(path.dirname(country)), 'm2');
  const calls = [];
  const run = ({ args }) => {
    calls.push(args);
    const value = (name) =>
      args.find((arg) => arg.startsWith(`-D${name}=`))?.slice(name.length + 3);
    const [, , version] = value('artifact').split(':');
    if (version === 'LATEST') {
      const folder = path.join(repository, 'org', 'niord', 'web', 'niord-fe-src');
      fs.mkdirSync(folder, { recursive: true });
      fs.writeFileSync(
        path.join(folder, 'maven-metadata-github.xml'),
        metadataXml(Object.keys(published)),
      );
      return 0;
    }
    if (!published[version]) return 1;
    fs.mkdirSync(value('outputDirectory'), { recursive: true });
    fs.writeFileSync(
      path.join(value('outputDirectory'), `niord-fe-src-${version}.zip`),
      writeZip([{ name: 'catalog.json', data: Buffer.from(serializeCatalog(published[version])) }]),
    );
    return 0;
  };
  const runMain = async (args, env = {}) => {
    const lines = [];
    const errors = [];
    const status = await main(args, {
      fe: base,
      log: (line) => lines.push(line),
      error: (line) => errors.push(line),
      env: { MAVEN_ARGS: `-Dmaven.repo.local=${repository}`, ...env },
      run,
    });
    return { status, lines, errors };
  };
  return { fe, be, country, base, main: runMain, calls };
}

describe('the commands of a home', () => {
  it('validate archives a home pack, says whether it is published, and checks it as information', async () => {
    const { country, main: run } = countryWorkspace((files) =>
      // A key the pack lacks: the check reports it, and validate still passes.
      editJson(DA_ADMIN, (da) => delete da.menu.close)(files),
    );
    const out = path.join(path.dirname(country), 'out');
    const summary = path.join(path.dirname(country), 'summary.md');
    const output = path.join(path.dirname(country), 'output.txt');
    const { status, lines } = await run(['validate', 'da', '--out', out], {
      GITHUB_STEP_SUMMARY: summary,
      GITHUB_OUTPUT: output,
    });
    assert.equal(status, 0);
    const zip = fs.readFileSync(path.join(out, 'niord-lang-da.zip'));
    const sha256 = crypto.createHash('sha256').update(zip).digest('hex');
    assert.equal(
      fs.readFileSync(path.join(out, 'niord-lang-da.zip.sha256'), 'utf8'),
      `${sha256}  niord-lang-da.zip\n`,
    );
    assert.ok(!readZip(zip).some(({ name }) => name === 'status/unaccepted.json'));
    assertNamed(
      lines,
      'pack validate: packs/da → niord-lang-da.zip, ',
      'the pack check of da against the base product catalog 4.0.1, information only',
      'error: packs/da/i18n/admin/da.json lacks 1 catalog key(s): menu.close',
    );
    assert.equal(lines.at(-1), 'publish: true');
    assert.equal(fs.readFileSync(output, 'utf8'), `lang=da\npublish=true\nsha256=${sha256}\n`);
    const written = fs.readFileSync(summary, 'utf8');
    assert.match(written, /### niord-lang-da/);
    assert.match(written, /latest base product catalog, 4\.0\.1 \(information only\)/);
    assert.match(written, /lacks 1 catalog key\(s\): menu\.close/);
  });

  it('validate names the catalog it checks against, and fails only when no archive is built', async () => {
    const { country, fe, be, main: run } = countryWorkspace();
    const latest = path.join(path.dirname(country), 'latest.json');
    fs.writeFileSync(latest, serializeCatalog(buildCatalog({ fe, be, version: '4.0.9' })));
    const out = path.join(path.dirname(country), 'out');
    const checked = await run(['validate', 'da', '--catalog', latest, '--out', out]);
    assert.equal(checked.status, 0);
    assertNamed(checked.lines, 'against the base product catalog 4.0.9');
    fs.writeFileSync(path.join(country, 'packs', 'da', 'notes.md'), 'x');
    const stray = await run(['validate', 'da', '--out', out]);
    assert.equal(stray.status, 1);
    assertNamed(stray.errors, 'packs/da/notes.md is not part of a pack');
    fs.rmSync(path.join(country, 'packs', 'da', 'notes.md'));
    fs.writeFileSync(path.join(country, 'packs', 'da', 'pack.json'), packJson('kl', 'Dansk', 'da'));
    const other = await run(['validate', 'da', '--out', out]);
    assert.equal(other.status, 1);
    assertNamed(other.errors, 'pack.json names the language "kl", not "da"');
  });

  it("validate refuses a language that is not a code, has no folder, or is not the manifest's home pack", async () => {
    const { country, fe, main: run } = countryWorkspace();
    const out = path.join(path.dirname(country), 'out');
    await assert.rejects(run(['validate', 'DA', '--out', out]), /"DA" is not a language code/);
    await assert.rejects(run(['validate', 'fr', '--out', out]), /packs\/fr\/ does not exist/);
    await assert.rejects(
      run(['validate', 'kl', '--out', out]),
      /niord-country\.json does not name kl as a home pack/,
    );
    await assert.rejects(run(['validate', 'da']), /validate needs --out <dir>/);
    await assert.rejects(
      main(['validate', 'da', '--out', out], { fe, log: () => {}, env: {} }),
      /validate runs in a country workspace/,
    );
  });

  it('latest-catalog writes the newest published catalog, through Maven alone', async () => {
    const { fe, be } = tree();
    const workspace = countryWorkspace(undefined, {
      '4.0.1': buildCatalog({ fe, be, version: '4.0.1' }),
      '4.0.2': buildCatalog({ fe, be, version: '4.0.2' }),
    });
    const out = path.join(path.dirname(workspace.country), 'latest', 'catalog.json');
    const { status, lines } = await workspace.main(['latest-catalog', '--out', out]);
    assert.equal(status, 0);
    assert.deepEqual(lines, ['latest-catalog: 4.0.2']);
    assert.equal(JSON.parse(fs.readFileSync(out, 'utf8')).version, '4.0.2');
    assert.ok(workspace.calls[0].includes('-Dartifact=org.niord.web:niord-fe-src:LATEST:zip'));
    await assert.rejects(workspace.main(['latest-catalog']), /latest-catalog needs --out <file>/);
    await assert.rejects(
      main(['latest-catalog', '--out', out], { fe, log: () => {}, env: {} }),
      /latest-catalog runs in a country workspace/,
    );
  });

  it('prune --base reads every published catalog from that version up, and takes no --catalog', async () => {
    const edit = (files) => {
      editJson(DA_ADMIN, (da) => (da.old = { gone: 'Væk' }))(files);
      editJson(DA_UNACCEPTED, (status) => (status.admin.unaccepted = ['old.gone']))(files);
    };
    const { fe, be } = tree(edit);
    const withOld = buildCatalog({ fe, be, version: '4.0.0' });
    withOld.apps.admin['old.gone'] = { fp: fingerprint('Gone'), placeholders: [] };
    const published = {
      '4.0.0': withOld,
      '4.0.1': buildCatalog({ fe, be, version: '4.0.1' }),
      '4.0.2': buildCatalog({ fe, be, version: '4.0.2' }),
    };
    const workspace = countryWorkspace(edit, published);
    const kept = await workspace.main(['prune', 'da', '--base', '4.0.0']);
    assert.deepEqual(kept.lines, [
      'pack prune: reading the catalog of 4.0.0 (1 of 3)',
      'pack prune: reading the catalog of 4.0.1 (2 of 3)',
      'pack prune: reading the catalog of 4.0.2 (3 of 3)',
      'pack prune: the catalogs of 4.0.0, 4.0.1, 4.0.2 read',
      'pack prune: 0 key(s) removed; nothing written',
    ]);
    const pruned = await workspace.main(['prune', 'da', '--base', '4.0.1']);
    assert.deepEqual(pruned.lines, [
      'pack prune: reading the catalog of 4.0.1 (1 of 2)',
      'pack prune: reading the catalog of 4.0.2 (2 of 2)',
      'pack prune: the catalogs of 4.0.1, 4.0.2 read',
      'pack prune: da admin old.gone',
      'pack prune: 1 key(s) removed; packs/da/i18n/admin/da.json, packs/da/status/unaccepted.json written',
    ]);
    await assert.rejects(
      workspace.main(['prune', 'da', '--base', '4.0.1', '--catalog', 'x.json']),
      /--base reads the catalogs of the published versions from <version> up; it takes no --catalog/,
    );
    await assert.rejects(
      workspace.main(['prune', 'da', '--base', '3.9.0']),
      /3\.9\.0 is not a published version/,
    );
  });

  it('refuses an option a command does not take, and names every command', async () => {
    const { main: run } = countryWorkspace();
    await assert.rejects(run(['accept', 'da', '--base', '4.0.1']), /accept takes no --base/);
    await assert.rejects(run(['migrate', 'da', '--out', 'x']), /migrate takes no --out/);
    await assert.rejects(
      run(['publish']),
      /unknown command "publish" \(check, migrate, prune, accept, latest-catalog, validate\)/,
    );
  });
});

describe('check', () => {
  // `check` reads the builds' languages from the base product's own angular.json, whose builds
  // name English only: the tree's da and kl packs are there, but no build uses either.
  it('exits 0 when no build uses a pack, and 1 on a hatch entry no build uses', async () => {
    const { fe } = tree();
    const run = async (args) => {
      const lines = [];
      const status = await main(['check', ...args], {
        fe,
        log: (line) => lines.push(line),
        error: (line) => lines.push(line),
        env: {},
      });
      return { status, lines };
    };
    const clean = await run([]);
    assert.equal(clean.status, 0, clean.lines.join('\n'));
    assertNamed(
      clean.lines,
      'pack check: every build (English only, no pack to check): 0 error(s)',
    );
    const hatchFile = path.join(fe, 'hatch.json');
    fs.writeFileSync(hatchFile, formatJson([hatchEntry('da', 'admin', 'menu.close')]));
    const unused = await run(['--accepted', hatchFile]);
    assert.equal(unused.status, 1, unused.lines.join('\n'));
    assertNamed(
      unused.lines,
      'pack check: error: the escape hatch lists da admin menu.close, but no admin build has da as a UI language',
    );
  });
});
