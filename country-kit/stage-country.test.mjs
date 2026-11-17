import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, it } from 'node:test';
import { flagLanguages, stageCountry } from './lib/stage-country.mjs';

const PNG = Buffer.concat([
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
  Buffer.from('flag'),
]);
const LABELS = { en: 'English', zz: 'Zedish', kl: 'Kalaallisut' };

/** A pack folder `<root>/<lang>/` with pack.json, its flag and the bundles given. */
function pack(root, lang, bundles = {}) {
  const dir = path.join(root, lang);
  fs.mkdirSync(path.join(dir, 'img', 'flags'), { recursive: true });
  fs.writeFileSync(
    path.join(dir, 'pack.json'),
    JSON.stringify({ lang, label: LABELS[lang], locale: lang, flag: `img/flags/${lang}.png` }),
  );
  fs.writeFileSync(
    path.join(dir, 'img', 'flags', `${lang}.png`),
    Buffer.concat([PNG, Buffer.from(lang)]),
  );
  for (const [app, bundle] of Object.entries(bundles)) {
    fs.mkdirSync(path.join(dir, 'i18n', app), { recursive: true });
    fs.writeFileSync(
      path.join(dir, 'i18n', app, `${lang}.json`),
      `${JSON.stringify(bundle, null, 2)}\n`,
    );
  }
  fs.mkdirSync(path.join(dir, 'status'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'status', 'fingerprints.json'), '{}');
  return dir;
}

function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-stage-'));
  const packs = {
    en: pack(root, 'en', {
      admin: { a: { save: 'Save', hello: 'Hello {{ name }}' } },
      info: { TITLE: 'Notices' },
    }),
    zz: pack(root, 'zz', {
      admin: { a: { save: '[Šávé]', hello: '[Ĥéłłó]' }, gone: 'stripped' },
      info: { TITLE: '[Ñóţíçéš]' },
    }),
    kl: pack(root, 'kl'),
  };
  const branding = path.join(root, 'branding-en.json');
  fs.writeFileSync(branding, '{"footer.copyright":"©"}');
  return { root, packs, branding, outDir: path.join(root, 'build') };
}

const manifest = {
  apps: {
    admin: {
      uiLanguages: ['en', 'zz'],
      contentLanguages: ['en', 'zz', 'kl'],
      defaultLanguage: 'en',
    },
    info: { uiLanguages: ['zz'], defaultLanguage: 'zz' },
  },
};

const read = (dir, relative) => fs.readFileSync(path.join(dir, relative), 'utf8');

describe('staging a country', () => {
  it("ships exactly each application's UI languages, English only where they name it", () => {
    const { packs, outDir } = fixture();
    const result = stageCountry({ manifest, packs, outDir });
    assert.deepEqual(result.admin.files, [
      'i18n/en.json',
      'i18n/zz.json',
      'img/flags/en.png',
      'img/flags/kl.png',
      'img/flags/zz.png',
    ]);
    assert.deepEqual(result.info.files, ['i18n/zz.json', 'img/flags/zz.png']);
    assert.equal(fs.existsSync(path.join(outDir, 'info', 'i18n', 'en.json')), false);
  });

  it('strips a bundle to the catalog, fills it from English and replaces a broken placeholder', () => {
    const { packs, outDir } = fixture();
    const result = stageCountry({ manifest, packs, outDir });
    assert.deepEqual(JSON.parse(read(outDir, 'admin/i18n/zz.json')), {
      a: { save: '[Šávé]', hello: 'Hello {{ name }}' },
    });
    assert.deepEqual(result.admin.filled, { zz: 1 });
    assert.equal(read(outDir, 'admin/i18n/en.json'), read(packs.en, 'i18n/admin/en.json'));
  });

  it("ships the flag of every language an application names, and nothing of a pack's status", () => {
    assert.deepEqual(flagLanguages(manifest), { admin: ['en', 'zz', 'kl'], info: ['zz'] });
    const { packs, outDir } = fixture();
    stageCountry({ manifest, packs, outDir });
    assert.equal(fs.existsSync(path.join(outDir, 'admin', 'status')), false);
    assert.equal(fs.existsSync(path.join(outDir, 'admin', 'pack.json')), false);
  });

  it('copies the branding files it is given and the extra files, and removes a stale one', () => {
    const { packs, branding, outDir } = fixture();
    fs.mkdirSync(path.join(outDir, 'admin', 'branding'), { recursive: true });
    fs.writeFileSync(path.join(outDir, 'admin', 'branding', 'old.json'), '{}');
    const result = stageCountry({
      manifest,
      packs,
      outDir,
      assets: { admin: { 'branding/en.json': branding } },
      extra: { admin: { 'niord-build-report.json': Buffer.from('{}\n') } },
    });
    assert.equal(read(outDir, 'admin/branding/en.json'), '{"footer.copyright":"©"}');
    assert.equal(read(outDir, 'admin/niord-build-report.json'), '{}\n');
    assert.deepEqual(result.admin.removed, ['branding/old.json']);
  });

  it('writes nothing the second time', () => {
    const { packs, outDir } = fixture();
    stageCountry({ manifest, packs, outDir });
    const again = stageCountry({ manifest, packs, outDir });
    assert.deepEqual([...again.admin.written, ...again.info.written], []);
  });

  it('puts a text override over the pack and over English, where its key is', () => {
    const { packs, outDir } = fixture();
    const textOverrides = {
      admin: {
        en: { 'a.save': 'Keep' },
        zz: { 'a.save': '[Ķééþ]', 'a.hello': '[Ĥéý {{ name }}]' },
      },
      info: { zz: { TITLE: '[Ñóţéš]' } },
    };
    const result = stageCountry({ manifest, packs, outDir, textOverrides });
    // English is written from JSON, in English's order.
    assert.equal(
      read(outDir, 'admin/i18n/en.json'),
      `${JSON.stringify({ a: { save: 'Keep', hello: 'Hello {{ name }}' } }, null, 2)}\n`,
    );
    // The override wins over the pack's text and over the English that filled a broken one.
    assert.deepEqual(JSON.parse(read(outDir, 'admin/i18n/zz.json')), {
      a: { save: '[Ķééþ]', hello: '[Ĥéý {{ name }}]' },
    });
    assert.deepEqual(JSON.parse(read(outDir, 'info/i18n/zz.json')), { TITLE: '[Ñóţéš]' });
    assert.deepEqual(result.admin.overridden, { en: 1, zz: 2 });
    assert.deepEqual(result.info.overridden, { zz: 1 });
    // The key English filled is now the override's.
    assert.deepEqual(result.admin.filled, {});

    const again = stageCountry({ manifest, packs, outDir, textOverrides });
    assert.deepEqual([...again.admin.written, ...again.info.written], []);
    // Without the overrides, English is its pack's bytes again.
    const plain = stageCountry({ manifest, packs, outDir });
    assert.deepEqual(plain.admin.overridden, {});
    assert.equal(read(outDir, 'admin/i18n/en.json'), read(packs.en, 'i18n/admin/en.json'));
  });

  it('refuses an override key with a forbidden name', () => {
    const { packs, outDir } = fixture();
    assert.throws(
      () =>
        stageCountry({
          manifest,
          packs,
          outDir,
          textOverrides: { admin: { zz: { 'a.__proto__.x': 'y' } } },
        }),
      /text-overrides\/zz\.json \(admin\): the key "a\.__proto__\.x" has a forbidden name/,
    );
  });

  it('fails a UI language whose pack has no bundle for the application', () => {
    const { packs, outDir } = fixture();
    const kl = {
      apps: { ...manifest.apps, info: { uiLanguages: ['kl'], defaultLanguage: 'kl' } },
    };
    assert.throws(
      () => stageCountry({ manifest: kl, packs, outDir }),
      /packs\/kl\/i18n\/info\/kl\.json does not exist/,
    );
  });
});
