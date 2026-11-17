/**
 * Tests for country-kit/stage-languages.mjs.
 *
 *   npm run test:guards      # from fe/
 *
 * Each case writes a small set of language packs into a temporary folder, stages them into
 * another, and checks what the staging folder holds — or, for a pack that breaks a rule, that
 * the step refuses it and stages nothing. A pack that slips through builds green and ships, which
 * is why these exist.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import {
  fillFromEnglish,
  fingerprint,
  flatten,
  IN_FLIGHT_MS,
  placeholders,
  stageLanguages,
  summary,
} from './stage-languages.mjs';

const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

const PNG = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  Buffer.from('flag'),
]);
const pack = (lang, label) => `${JSON.stringify(
  { lang, label, locale: `${lang}-XX`, flag: `img/flags/${lang}.png` },
  null,
  2,
)}
`;
// Formatted as the bundles in the tree are, but not as JSON.stringify writes them, so a byte
// copy and a re-serialised bundle tell apart.
const EN_ADMIN = '{\n  "menu": { "open": "Open", "close": "Close" },\n  "title": "Niord"\n}\n';
const DA_ADMIN = '{\n  "menu": { "open": "Åbn", "close": "Luk" },\n  "title": "Niord"\n}\n';
const EN_INFO = '{ "SITE_TITLE": "Notices", "BTN_CLOSE": "Close" }\n';
const DA_INFO = '{ "SITE_TITLE": "Meddelelser", "BTN_CLOSE": "Luk" }\n';

/** The files of a passing set of packs: en and da with both applications, kl with the public site. */
function baseFiles() {
  return {
    'en/pack.json': pack('en', 'English'),
    'en/i18n/admin/en.json': EN_ADMIN,
    'en/i18n/info/en.json': EN_INFO,
    'en/img/flags/en.png': PNG,
    'en/ATTRIBUTION.txt': 'drawn here',
    'en/renames.json': '{}\n',
    'da/pack.json': pack('da', 'Dansk'),
    'da/i18n/admin/da.json': DA_ADMIN,
    'da/i18n/info/da.json': DA_INFO,
    'da/img/flags/da.png': PNG,
    'da/ATTRIBUTION.txt': 'drawn here',
    'da/status/unaccepted.json': '{}\n',
    'da/status/fingerprints.json': '{}\n',
    'kl/pack.json': pack('kl', 'Kalaallisut'),
    'kl/i18n/info/kl.json': '{ "SITE_TITLE": "Nalunaarutit", "BTN_CLOSE": "Matuguk" }\n',
    'kl/img/flags/kl.png': PNG,
    'kl/status/unaccepted.json': '{}\n',
  };
}

/** Writes the packs into a fresh folder; returns the packs folder and an empty output folder. */
function packs(edit) {
  const files = baseFiles();
  edit?.(files);
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'stage-languages-'));
  temporary.push(root);
  const packsDir = path.join(root, 'languages');
  for (const [file, content] of Object.entries(files)) {
    if (content === undefined) continue;
    fs.mkdirSync(path.dirname(path.join(packsDir, file)), { recursive: true });
    fs.writeFileSync(path.join(packsDir, file), content);
  }
  return { packsDir, outDir: path.join(root, '.niord', 'build') };
}

/** Every file under a folder, relative and with `/`, sorted. */
function tree(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs
    .readdirSync(dir, { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) =>
      path.relative(dir, path.join(entry.parentPath, entry.name)).replaceAll('\\', '/'),
    )
    .sort();
}

const read = (dir, file) => fs.readFileSync(path.join(dir, file), 'utf8');

function assertRefused(edit, fragment, apps = undefined) {
  const { packsDir, outDir } = packs(edit);
  assert.throws(
    () => stageLanguages({ packsDir, outDir, apps }),
    (error) => {
      assert.ok(
        error.message.includes(fragment),
        `expected an error naming "${fragment}", got: ${error.message}`,
      );
      return true;
    },
  );
  assert.deepEqual(tree(outDir), [], 'nothing is staged from a refused set of packs');
}

describe('stage-languages', () => {
  it('stages English and every complete bundle byte for byte, and every flag for both', () => {
    const { packsDir, outDir } = packs();
    const result = stageLanguages({ packsDir, outDir });
    assert.deepEqual(tree(outDir), [
      'admin/i18n/da.json',
      'admin/i18n/en.json',
      'admin/img/flags/da.png',
      'admin/img/flags/en.png',
      'admin/img/flags/kl.png',
      'info/i18n/da.json',
      'info/i18n/en.json',
      'info/i18n/kl.json',
      'info/img/flags/da.png',
      'info/img/flags/en.png',
      'info/img/flags/kl.png',
    ]);
    assert.equal(read(outDir, 'admin/i18n/en.json'), EN_ADMIN);
    assert.equal(read(outDir, 'admin/i18n/da.json'), DA_ADMIN);
    assert.equal(read(outDir, 'info/i18n/da.json'), DA_INFO);
    assert.ok(fs.readFileSync(path.join(outDir, 'admin/img/flags/kl.png')).equals(PNG));
    assert.deepEqual(result.admin.filled, {});
  });

  it('never stages pack.json, ATTRIBUTION.txt, renames.json or status/', () => {
    const { packsDir, outDir } = packs();
    stageLanguages({ packsDir, outDir });
    for (const file of tree(outDir))
      assert.match(file, /^(admin|info)\/(i18n\/[a-z]+\.json|img\/flags\/[a-z]+\.png)$/);
  });

  it("fills a bundle's missing keys from English, in English's order, and strips a key English lacks", () => {
    const { packsDir, outDir } = packs((files) => {
      files['da/i18n/admin/da.json'] =
        '{ "extra": "Ekstra", "title": "Niord DK", "menu": { "close": "Luk", "gone": { "x": "y" } } }\n';
    });
    const result = stageLanguages({ packsDir, outDir });
    const staged = read(outDir, 'admin/i18n/da.json');
    assert.equal(
      staged,
      `${JSON.stringify({ menu: { open: 'Open', close: 'Luk' }, title: 'Niord DK' }, null, 2)}\n`,
    );
    assert.deepEqual(result.admin.filled, { da: 1 });
    assert.deepEqual(result.admin.stripped, { da: ['extra', 'menu.gone.x'] });
    assert.deepEqual(result.admin.replaced, {});
    assert.match(
      summary(result),
      /admin: da, en \+ 3 flag\(s\) \(filled: da 1 from en; stripped: da 2\)/,
    );
    assert.deepEqual(fillFromEnglish(JSON.parse(EN_ADMIN), { menu: {} }).missing, [
      'menu.open',
      'menu.close',
      'title',
    ]);
  });

  it('matches a key by its flat name: a pack that writes it flat where English nests, or nested where English writes it flat, keeps its text', () => {
    const { packsDir, outDir } = packs((files) => {
      files['en/i18n/admin/en.json'] =
        '{ "menu.action": "Action", "menu": { "open": "Open", "close": "Close" }, "title": "Niord" }\n';
      // The opposite shape on both keys: menu.action nested, menu.close flat.
      files['da/i18n/admin/da.json'] =
        '{ "menu": { "action": "Handling", "open": "Åbn" }, "menu.close": "Luk", "title": "Niord" }\n';
    });
    const result = stageLanguages({ packsDir, outDir, apps: ['admin'] });
    assert.deepEqual(JSON.parse(read(outDir, 'admin/i18n/da.json')), {
      'menu.action': 'Handling',
      menu: { open: 'Åbn', close: 'Luk' },
      title: 'Niord',
    });
    assert.deepEqual(result.admin.filled, {});
    assert.deepEqual(result.admin.stripped, {});
    assert.deepEqual(result.admin.replaced, {});
    assert.deepEqual(
      fillFromEnglish({ admin: { areas: { delete: 'Delete' } } }, { 'admin.areas.delete': 'Slet' }),
      {
        bundle: { admin: { areas: { delete: 'Slet' } } },
        missing: [],
        stripped: [],
        replaced: [],
        reshaped: ['admin.areas.delete'],
      },
    );
  });

  it('refuses a key a pack gives twice, once flat and once nested, whichever comes first', () => {
    for (const twice of [
      '{ "menu.close": "Luk", "menu": { "open": "Åbn", "close": "Lukke" }, "title": "Niord" }\n',
      '{ "menu": { "open": "Åbn", "close": "Lukke" }, "menu.close": "Luk", "title": "Niord" }\n',
    ]) {
      assertRefused((files) => {
        files['da/i18n/admin/da.json'] = twice;
      }, '"menu.close" is given twice');
      assert.throws(() => flatten(JSON.parse(twice)), /"menu\.close" is given twice/);
      assert.throws(
        () => fillFromEnglish(JSON.parse(EN_ADMIN), JSON.parse(twice)),
        /"menu\.close" is given twice/,
      );
    }
    assert.throws(
      () =>
        fillFromEnglish(
          JSON.parse(EN_ADMIN.replace('"title"', '"menu.open": "Open", "title"')),
          {},
        ),
      /the English "menu\.open" is given twice/,
    );
  });

  it("stages English's text for a placeholder other than English's, or an empty text", () => {
    const { packsDir, outDir } = packs((files) => {
      files['en/i18n/info/en.json'] =
        '{ "SITE_TITLE": "Notices", "BTN_CLOSE": "Close {{ what }}", "COUNT": "{{n}} of {{ total }}" }\n';
      files['da/i18n/info/da.json'] =
        '{ "SITE_TITLE": " ", "BTN_CLOSE": "Luk {{hvad}}", "COUNT": "{{ total }}: {{n}}" }\n';
    });
    const result = stageLanguages({ packsDir, outDir, apps: ['info'] });
    assert.deepEqual(JSON.parse(read(outDir, 'info/i18n/da.json')), {
      SITE_TITLE: 'Notices',
      BTN_CLOSE: 'Close {{ what }}',
      COUNT: '{{ total }}: {{n}}',
    });
    assert.deepEqual(result.info.replaced.da, ['SITE_TITLE', 'BTN_CLOSE']);
    assert.deepEqual(result.info.filled, { kl: 1 });
    assert.match(summary(result), /\(filled: kl 1 from en; replaced: da 2 by en, kl 1 by en\)/);
  });

  it('stages one application alone, which needs no bundle of the other', () => {
    const { packsDir, outDir } = packs((files) => {
      delete files['en/i18n/admin/en.json'];
      delete files['da/i18n/admin/da.json'];
    });
    stageLanguages({ packsDir, outDir, apps: ['info'] });
    assert.deepEqual(
      tree(outDir).map((file) => file.split('/')[0]),
      Array(6).fill('info'),
    );
  });

  it('removes a staged file no pack produces any more', () => {
    const { packsDir, outDir } = packs();
    stageLanguages({ packsDir, outDir });
    fs.rmSync(path.join(packsDir, 'kl'), { recursive: true });
    fs.writeFileSync(path.join(outDir, 'info/i18n/stale.json'), '{}');
    const result = stageLanguages({ packsDir, outDir });
    assert.ok(!tree(outDir).some((file) => /kl\.|stale/.test(file)));
    assert.deepEqual(result.info.removed, ['i18n/kl.json', 'i18n/stale.json', 'img/flags/kl.png']);
  });

  it("leaves another run's temporary file alone, and sweeps a crashed run's", () => {
    const { packsDir, outDir } = packs();
    stageLanguages({ packsDir, outDir });
    const inFlight = path.join(outDir, 'admin/i18n/da.json.4242.tmp');
    const crashed = path.join(outDir, 'admin/i18n/en.json.4243.tmp');
    fs.writeFileSync(inFlight, DA_ADMIN);
    fs.writeFileSync(crashed, EN_ADMIN);
    const old = (Date.now() - IN_FLIGHT_MS - 1000) / 1000;
    fs.utimesSync(crashed, old, old);
    const result = stageLanguages({ packsDir, outDir });
    assert.ok(fs.existsSync(inFlight), "another run's temporary file survives the sweep");
    assert.ok(!fs.existsSync(crashed), "a crashed run's temporary file is swept");
    assert.deepEqual(result.admin.removed, ['i18n/en.json.4243.tmp']);
  });

  it('writes nothing on a second run, and stages the same bytes', () => {
    const { packsDir, outDir } = packs();
    stageLanguages({ packsDir, outDir });
    const stamp = (file) => fs.statSync(path.join(outDir, file)).mtimeMs;
    const before = new Map(tree(outDir).map((file) => [file, [stamp(file), read(outDir, file)]]));
    const result = stageLanguages({ packsDir, outDir });
    assert.deepEqual(result.admin.written, []);
    assert.deepEqual(result.info.written, []);
    assert.deepEqual(
      new Map(tree(outDir).map((file) => [file, [stamp(file), read(outDir, file)]])),
      before,
    );
  });

  it('refuses a pack.json with another field, or naming another language', () => {
    assertRefused((files) => {
      files['da/pack.json'] = JSON.stringify({
        ...JSON.parse(pack('da', 'Dansk')),
        script: 'x.js',
      });
    }, 'languages/da: pack.json has flag, label, lang, locale, script');
    assertRefused((files) => {
      files['da/pack.json'] = pack('kl', 'Dansk').replace('img/flags/kl.png', 'img/flags/da.png');
    }, 'languages/da: pack.json names the language "kl", not "da"');
  });

  it('refuses a pack.json whose locale is not ll or ll-CC, or whose label is not short plain text', () => {
    const withField = (field, value) => (files) => {
      files['da/pack.json'] = JSON.stringify({
        ...JSON.parse(pack('da', 'Dansk')),
        [field]: value,
      });
    };
    assertRefused(
      withField('locale', 'de_DE'),
      `pack.json's locale "de_DE" is not a language code`,
    );
    assertRefused(
      withField('locale', 'de-de'),
      `pack.json's locale "de-de" is not a language code`,
    );
    assertRefused(
      withField('label', 'D'.repeat(41)),
      "pack.json's label has 41 characters, over the 40",
    );
    assertRefused(withField('label', '<b>Dansk</b>'), "pack.json's label is not plain text");
    assertRefused(withField('label', 'Dansk &amp; mere'), "pack.json's label is not plain text");
  });

  it('refuses a flag that is not img/flags/<lang>.png, or not a PNG', () => {
    assertRefused((files) => {
      files['da/pack.json'] = pack('da', 'Dansk').replace(
        'img/flags/da.png',
        '../en/img/flags/en.png',
      );
    }, `pack.json's flag is "../en/img/flags/en.png", not "img/flags/da.png"`);
    assertRefused((files) => {
      files['kl/img/flags/kl.png'] = '<svg xmlns="http://www.w3.org/2000/svg"/>';
    }, 'languages/kl: img/flags/kl.png is not a PNG');
  });

  it('refuses a bundle with a key that could reach an object prototype', () => {
    assertRefused((files) => {
      files['da/i18n/info/da.json'] = '{ "SITE_TITLE": "x", "__proto__": { "BTN_CLOSE": "y" } }';
    }, 'languages/da/i18n/info/da.json has the key "__proto__"');
    assertRefused((files) => {
      files['kl/i18n/info/kl.json'] = '{ "SITE_TITLE": 1 }';
    }, '"SITE_TITLE" is neither a text nor an object');
  });

  it('refuses a set of packs without the English bundle of a staged application', () => {
    assertRefused((files) => {
      delete files['en/i18n/info/en.json'];
    }, 'languages/en/i18n/info/en.json does not exist');
    assertRefused((files) => {
      for (const file of Object.keys(files)) if (file.startsWith('en/')) delete files[file];
    }, 'no English pack');
  });

  it('refuses a flag reached through a link out of the pack', (context) => {
    const { packsDir, outDir } = packs();
    // A folder outside the pack that holds a valid flag, linked in place of the pack's own
    // folder. A junction on Windows, where a file link needs a privilege; a link elsewhere.
    const outside = path.join(path.dirname(packsDir), 'outside');
    fs.mkdirSync(outside);
    fs.writeFileSync(path.join(outside, 'da.png'), PNG);
    const flags = path.join(packsDir, 'da/img/flags');
    fs.rmSync(flags, { recursive: true });
    try {
      fs.symlinkSync(outside, flags, 'junction');
    } catch {
      context.skip('this system does not let the test create a link');
      return;
    }
    assert.throws(
      () => stageLanguages({ packsDir, outDir }),
      /languages\/da: img\/flags\/da\.png goes through a link/,
    );
    assert.deepEqual(tree(outDir), []);
  });

  it("names a text's placeholders once each, sorted, with or without spaces", () => {
    assert.deepEqual(placeholders('{{b}} {{ a }} {{b}} {{ c.d }} { e } {{}}'), ['a', 'b', 'c.d']);
  });

  it('fingerprints a text by the SHA-256 of its UTF-8 bytes', () => {
    assert.equal(fingerprint(''), 'e3b0c44298fc1c14');
    assert.equal(fingerprint('abc'), 'ba7816bf8f01cfea');
  });
});
