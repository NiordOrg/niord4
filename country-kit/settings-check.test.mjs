import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, it } from 'node:test';
import { checkSettings, checkSettingsValues, hasBackendHalf } from './lib/settings-check.mjs';

// GL's shape: Danish and English in the administration app, Kalaallisut a content language there
// and a UI language of the public site.
const manifest = {
  apps: {
    admin: {
      uiLanguages: ['da', 'en'],
      contentLanguages: ['da', 'en', 'kl'],
      defaultLanguage: 'da',
    },
    info: { uiLanguages: ['kl', 'da', 'en'], defaultLanguage: 'kl' },
  },
};
const halves =
  (...langs) =>
  (lang) =>
    langs.includes(lang);
const check = (values, backendHalf = halves('da', 'kl')) =>
  checkSettingsValues({ values, manifest, backendHalf, label: 'settings.json' });

describe('the settings-file checks', () => {
  it('passes languages the manifest names, each with its backend half', () => {
    assert.deepEqual(
      check({
        siteLanguages: ['da', 'en'],
        modelLanguages: ['da', 'en', 'kl'],
        infoSiteLanguages: ['kl', 'da', 'en'],
        defaultLanguage: 'da',
        infoDefaultLanguage: 'kl',
      }),
      { warnings: [], errors: [] },
    );
  });

  it('warns on a language the manifest does not name for that use', () => {
    const { warnings, errors } = check({ siteLanguages: ['da', 'kl'], infoSiteLanguages: ['fo'] });
    assert.deepEqual(errors, []);
    assert.deepEqual(warnings, [
      'settings.json: siteLanguages names kl, which niord-country.json does not name as a UI language of the administration app; the deployment drops it',
      'settings.json: infoSiteLanguages names fo, which niord-country.json does not name as a UI language of the public site; the deployment drops it',
    ]);
  });

  it('says a model language the manifest does not name is kept, with a field under its code', () => {
    const { warnings, errors } = check({ modelLanguages: ['da', 'fo'] });
    assert.deepEqual(errors, []);
    assert.deepEqual(warnings, [
      'settings.json: modelLanguages names fo, which niord-country.json does not name as a content language of the administration app; the deployment keeps it: the editor gives it a field labelled with its code in upper case',
    ]);
  });

  it('says a model language that is a UI language only is kept, with a field under its label', () => {
    const uiOnly = {
      apps: {
        admin: { uiLanguages: ['da', 'en'], contentLanguages: ['da'], defaultLanguage: 'da' },
        info: { uiLanguages: ['da'], defaultLanguage: 'da' },
      },
    };
    const { warnings, errors } = checkSettingsValues({
      values: { modelLanguages: ['da', 'en'] },
      manifest: uiOnly,
      backendHalf: halves('da'),
      label: 'settings.json',
    });
    assert.deepEqual(errors, []);
    assert.deepEqual(warnings, [
      "settings.json: modelLanguages names en, which niord-country.json does not name as a content language of the administration app; the deployment keeps it: the editor gives it a field under its pack's label",
    ]);
  });

  it('says what the administration app does with a default language the build lacks', () => {
    const { warnings, errors } = check({ defaultLanguage: 'fo' });
    assert.deepEqual(errors, []);
    assert.deepEqual(warnings, [
      'settings.json: defaultLanguage names fo, which niord-country.json does not name as a language of the build; the administration app keeps it while modelLanguages names it, and otherwise takes the first model language',
    ]);
  });

  it('says the public site takes its first language for a default it does not serve', () => {
    const { warnings, errors } = check({ infoDefaultLanguage: 'fo' });
    assert.deepEqual(errors, []);
    assert.deepEqual(warnings, [
      'settings.json: infoDefaultLanguage names fo, which niord-country.json does not name as a UI language of the public site; the public site takes its first language instead',
    ]);
  });

  it('warns on a model language whose pack has no backend half', () => {
    const { warnings, errors } = check({ modelLanguages: ['da', 'kl'] }, halves('da'));
    assert.deepEqual(errors, []);
    assert.match(warnings[0], /modelLanguages names kl, whose pack has no backend half yet/);
  });

  it('fails a default language that is a content language only, with no backend half', () => {
    const contentOnly = {
      apps: {
        admin: { uiLanguages: ['en'], contentLanguages: ['en', 'kl'], defaultLanguage: 'en' },
        info: { uiLanguages: ['en'], defaultLanguage: 'en' },
      },
    };
    const { errors } = checkSettingsValues({
      values: { defaultLanguage: 'kl' },
      manifest: contentOnly,
      backendHalf: halves(),
    });
    assert.equal(errors.length, 1);
    assert.match(
      errors[0],
      /defaultLanguage is kl, a content language whose pack has no backend half/,
    );
    assert.deepEqual(
      checkSettingsValues({
        values: { defaultLanguage: 'kl' },
        manifest: contentOnly,
        backendHalf: halves('kl'),
      }).errors,
      [],
    );
  });

  it('reads the values of the settings file, and checks nothing when there is none', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-settings-'));
    const file = path.join(dir, 'settings.json');
    assert.deepEqual(checkSettings({ file, manifest, backendHalf: halves() }), {
      warnings: [],
      errors: [],
    });
    fs.writeFileSync(
      file,
      JSON.stringify({ values: { siteLanguages: ['fo'] }, useBaseDefault: [] }),
    );
    assert.equal(
      checkSettings({ file, manifest, backendHalf: halves(), label: 'x' }).warnings.length,
      1,
    );
    fs.writeFileSync(file, '{');
    assert.match(
      checkSettings({ file, manifest, backendHalf: halves(), label: 'x' }).errors[0],
      /^x: /,
    );
  });

  it("finds a pack's backend half by its .properties files", () => {
    const pack = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-pack-'));
    assert.equal(hasBackendHalf(pack), false);
    fs.mkdirSync(path.join(pack, 'backend'));
    fs.writeFileSync(path.join(pack, 'backend', 'web.properties'), 'a=b\n');
    assert.equal(hasBackendHalf(pack), true);
  });
});
