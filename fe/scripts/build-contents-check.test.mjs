/**
 * Negative tests for scripts/build-contents-check.mjs.
 *
 *   npm run test:guards      # from fe/
 *
 * Each case builds a small workspace in a temporary folder — an angular.json object, a public
 * folder, the staged language folders as the staging step leaves them, the language
 * configurations and the packs' pack.json — breaks it the way an edit could, and expects the
 * check to name the configuration and the path. Every one of these breaks builds green, which is why
 * the check exists. The assets are resolved by the installed builder, as in the real run.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { duplicateOutputs } from '../../country-kit/lib/asset-outputs.mjs';
import { builderModule, checkBuildContents } from './build-contents-check.mjs';

const APP = 'ui-apps/app';
const SITE = 'ui-apps/site';
// The staged language folders of the two projects, named by their folders' names.
const APP_STAGED = '.niord/build/app';
const SITE_STAGED = '.niord/build/site';
const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

const adminLanguages = (fields) => `import type { LanguageConfig } from '../../core/config';

export const COUNTRY_LANGUAGES: LanguageConfig = ${JSON.stringify(fields, null, 2)};
`;
// Each language's name and locale, as its pack's pack.json gives them (rule 9).
const NAMES = { en: ['English', 'en-GB'], da: ['Dansk', 'da'], kl: ['Kalaallisut', 'kl'] };
const option = (value, flag) => ({
  label: NAMES[value][0],
  value,
  locale: NAMES[value][1],
  ...(flag ? { flagPath: `assets/img/flags/${flag}` } : {}),
});
const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

/** The language packs the configurations name: their pack.json and flag, which rule 9 reads. */
function packFiles() {
  const files = {};
  for (const [lang, [label, locale]] of Object.entries(NAMES)) {
    files[`languages/${lang}/pack.json`] = JSON.stringify({
      lang,
      label,
      locale,
      flag: `img/flags/${lang}.png`,
    });
    files[`languages/${lang}/img/flags/${lang}.png`] = PNG;
  }
  return files;
}

/** The files of a passing workspace: an administration-shaped app and a public-site-shaped one. */
function baseFiles() {
  return {
    ...packFiles(),
    [`${APP}/public/favicon.ico`]: '',
    [`${APP_STAGED}/i18n/en.json`]: '{}',
    [`${APP_STAGED}/i18n/da.json`]: '{}',
    [`${APP_STAGED}/img/flags/en.png`]: '',
    [`${APP_STAGED}/img/flags/da.png`]: '',
    [`${APP_STAGED}/img/flags/kl.png`]: '',
    [`${APP}/public/assets/img/niord_white.png`]: '',
    [`${APP}/public/assets/dk/branding/da.json`]: '{}',
    [`${APP}/public/assets/dk/branding/en.json`]: '{}',
    [`${APP}/public/assets/dk/legal/disclaimer.da.html`]: '<p></p>',
    [`${APP}/public/assets/dk/legal/disclaimer.en.html`]: '<p></p>',
    [`${APP}/public/assets/gl/img/logo-gl.png`]: '',
    [`${APP}/src/app/country-specific/default/language.config.ts`]: adminLanguages({
      availableLanguages: [option('en', 'en.png')],
      uiLanguages: ['en'],
      defaultLanguage: 'en',
    }),
    [`${APP}/src/app/country-specific/dk/language.config.ts`]: adminLanguages({
      availableLanguages: [option('da', 'da.png'), option('en', 'en.png')],
      uiLanguages: ['da', 'en'],
      brandingLanguages: ['da', 'en'],
      defaultLanguage: 'da',
    }),
    [`${APP}/src/app/country-specific/gl/language.config.ts`]: adminLanguages({
      availableLanguages: [option('kl', 'kl.png'), option('da', 'da.png'), option('en', 'en.png')],
      uiLanguages: ['da', 'en'],
      defaultLanguage: 'da',
    }),
    [`${SITE_STAGED}/i18n/en.json`]: '{}',
    [`${SITE_STAGED}/i18n/da.json`]: '{}',
    [`${SITE_STAGED}/img/flags/en.png`]: '',
    [`${SITE_STAGED}/img/flags/da.png`]: '',
    [`${SITE}/public/assets/default/branding/favicon.png`]: '',
    [`${SITE}/public/assets/dk/branding/da.json`]: '{}',
    [`${SITE}/public/assets/dk/branding/en.json`]: '{}',
    [`${SITE}/src/app/country-specific/default/language.config.ts`]: adminLanguages({
      languages: [option('en', 'en.png')],
      siteLanguages: ['en'],
      defaultLanguage: 'en',
    }),
    [`${SITE}/src/app/country-specific/dk/language.config.ts`]: adminLanguages({
      languages: [option('da', 'da.png'), option('en', 'en.png')],
      siteLanguages: ['da', 'en'],
      defaultLanguage: 'da',
    }),
  };
}

const languageSwap = (root, country) => ({
  replace: `${root}/src/app/country-specific/default/language.config.ts`,
  with: `${root}/src/app/country-specific/${country}/language.config.ts`,
});

/** The angular.json of a passing workspace. */
function baseConfig() {
  return {
    projects: {
      app: {
        root: APP,
        sourceRoot: `${APP}/src`,
        architect: {
          build: {
            options: {
              assets: [
                { glob: '**/*', input: `${APP}/public`, ignore: ['assets/dk/**', 'assets/gl/**'] },
                {
                  glob: '**/*',
                  input: APP_STAGED,
                  output: 'assets',
                  ignore: ['i18n/da.json', 'img/flags/da.png', 'img/flags/kl.png'],
                },
              ],
            },
            configurations: {
              production: {},
              dk: {
                fileReplacements: [languageSwap(APP, 'dk')],
                assets: [
                  {
                    glob: '**/*',
                    input: `${APP}/public`,
                    ignore: ['assets/dk/**', 'assets/gl/**'],
                  },
                  { glob: '**/*', input: `${APP}/public/assets/dk`, output: 'assets' },
                  {
                    glob: '**/*',
                    input: APP_STAGED,
                    output: 'assets',
                    ignore: ['img/flags/kl.png'],
                  },
                ],
              },
              gl: {
                fileReplacements: [languageSwap(APP, 'gl')],
                assets: [
                  {
                    glob: '**/*',
                    input: `${APP}/public`,
                    ignore: ['assets/dk/**', 'assets/gl/**'],
                  },
                  { glob: '**/*', input: `${APP}/public/assets/gl`, output: 'assets' },
                  { glob: '**/*', input: APP_STAGED, output: 'assets' },
                ],
              },
            },
          },
        },
      },
      site: {
        root: SITE,
        sourceRoot: `${SITE}/src`,
        architect: {
          build: {
            options: {
              assets: [
                {
                  glob: '**/*',
                  input: `${SITE}/public`,
                  ignore: ['assets/default/**', 'assets/dk/**'],
                },
                { glob: '**/*', input: `${SITE}/public/assets/default`, output: 'assets' },
                {
                  glob: '**/*',
                  input: SITE_STAGED,
                  output: 'assets',
                  ignore: ['i18n/da.json', 'img/flags/da.png'],
                },
              ],
            },
            configurations: {
              production: {},
              dk: {
                fileReplacements: [languageSwap(SITE, 'dk')],
                assets: [
                  {
                    glob: '**/*',
                    input: `${SITE}/public`,
                    ignore: ['assets/default/**', 'assets/dk/**'],
                  },
                  { glob: '**/*', input: `${SITE}/public/assets/dk`, output: 'assets' },
                  { glob: '**/*', input: SITE_STAGED, output: 'assets' },
                ],
              },
            },
          },
        },
      },
    },
  };
}

/**
 * Writes a workspace into a fresh temporary folder and returns that folder. The unedited one is
 * written once and shared by the cases that break only angular.json.
 */
let unedited;
function workspace(edit) {
  if (!edit && unedited) return unedited;
  const files = baseFiles();
  edit?.(files);
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'build-contents-check-'));
  temporary.push(root);
  for (const [file, content] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    fs.writeFileSync(path.join(root, file), content);
  }
  if (!edit) unedited = root;
  return root;
}

async function checkOf({ files, config } = {}) {
  const angular = baseConfig();
  config?.(angular.projects);
  return checkBuildContents(angular, { root: workspace(files) });
}

async function assertRejected(setup, configuration, ...fragments) {
  const { errors } = await checkOf(setup);
  for (const fragment of fragments)
    assert.ok(
      errors.some((error) => error.startsWith(`${configuration}: `) && error.includes(fragment)),
      `expected an error of ${configuration} naming "${fragment}", got:\n${errors.join('\n') || '(none)'}`,
    );
}

const app = (projects) => projects.app.architect.build;
const site = (projects) => projects.site.architect.build;

describe('build-contents-check', () => {
  it('passes a workspace whose configurations ship what their languages name', async () => {
    const { errors, checked } = await checkOf();
    assert.deepEqual(errors, []);
    assert.deepEqual(
      checked.map(({ configuration }) => configuration),
      ['app:production', 'app:dk', 'app:gl', 'site:production', 'site:dk'],
    );
  });

  it('rejects two inputs writing one output path', async () => {
    await assertRejected(
      {
        config: (projects) =>
          app(projects).configurations.dk.assets.push({
            glob: 'en.json',
            input: `${APP}/public/assets/dk/branding`,
            output: 'assets/i18n',
          }),
      },
      'app:dk',
      'assets/i18n/en.json is written by 2 inputs',
    );
  });

  it('rejects the default configuration shipping a Danish bundle', async () => {
    await assertRejected(
      {
        config: (projects) =>
          (app(projects).options.assets[1].ignore = app(projects).options.assets[1].ignore.filter(
            (glob) => glob !== 'i18n/da.json',
          )),
      },
      'app:production',
      'ships assets/i18n/da.json, but',
    );
  });

  it('rejects a configuration missing the en bundle its UI languages name', async () => {
    await assertRejected(
      {
        config: (projects) => app(projects).configurations.dk.assets[2].ignore.push('i18n/en.json'),
      },
      'app:dk',
      'ships no assets/i18n/en.json',
    );
  });

  it('passes UI languages without en that ship no en bundle, and rejects one that ships it', async () => {
    const danishOnly = (files) =>
      (files[`${APP}/src/app/country-specific/dk/language.config.ts`] = adminLanguages({
        availableLanguages: [option('da', 'da.png'), option('en', 'en.png')],
        uiLanguages: ['da'],
        brandingLanguages: ['da', 'en'],
        defaultLanguage: 'da',
      }));
    const { errors } = await checkOf({
      files: danishOnly,
      config: (projects) => app(projects).configurations.dk.assets[2].ignore.push('i18n/en.json'),
    });
    assert.deepEqual(errors, []);
    await assertRejected(
      { files: danishOnly },
      'app:dk',
      'ships assets/i18n/en.json, but',
      'names no UI language "en"',
    );
  });

  it("holds the public site's branding to its brandingLanguages when it names them", async () => {
    const brandedIn = (brandingLanguages) => (files) =>
      (files[`${SITE}/src/app/country-specific/dk/language.config.ts`] = adminLanguages({
        languages: [option('da', 'da.png'), option('en', 'en.png')],
        siteLanguages: ['da', 'en'],
        brandingLanguages,
        defaultLanguage: 'da',
      }));
    assert.deepEqual((await checkOf({ files: brandedIn(['da', 'en']) })).errors, []);
    await assertRejected(
      { files: brandedIn(['da']) },
      'site:dk',
      'ships assets/branding/en.json, but "en" is not a branding language',
    );
  });

  it('rejects a flag no language names', async () => {
    await assertRejected(
      {
        config: (projects) =>
          (site(projects).options.assets[2].ignore = site(projects).options.assets[2].ignore.filter(
            (glob) => glob !== 'img/flags/da.png',
          )),
      },
      'site:production',
      'ships assets/img/flags/da.png, a flag no language',
    );
  });

  it('rejects a named flag the build does not ship', async () => {
    await assertRejected(
      {
        files: (files) =>
          (files[`${APP}/src/app/country-specific/dk/language.config.ts`] = adminLanguages({
            availableLanguages: [
              option('kl', 'kl.png'),
              option('da', 'da.png'),
              option('en', 'en.png'),
            ],
            uiLanguages: ['da', 'en'],
            brandingLanguages: ['da', 'en'],
            defaultLanguage: 'da',
          })),
      },
      'app:dk',
      'names the flag assets/img/flags/kl.png, which this build does not ship',
    );
  });

  it('rejects a README or an attribution shipped beside the images', async () => {
    await assertRejected(
      { files: (files) => (files[`${APP}/public/assets/img/README.txt`] = 'about the images') },
      'app:dk',
      'ships assets/img/README.txt, a README',
    );
    await assertRejected(
      { files: (files) => (files[`${APP_STAGED}/img/flags/ATTRIBUTION.txt`] = 'drawn here') },
      'app:gl',
      'ships assets/img/flags/ATTRIBUTION.txt, a README, ATTRIBUTION or LICENSE file',
    );
  });

  it('rejects a translation review list shipped beside the assets', async () => {
    await assertRejected(
      { files: (files) => (files[`${SITE}/public/assets/da-unaccepted.json`] = '[]') },
      'site:dk',
      'ships assets/da-unaccepted.json, a translation review list',
    );
  });

  it("rejects a DK configuration reading GL's overlay", async () => {
    await assertRejected(
      {
        config: (projects) =>
          app(projects).configurations.dk.assets.push({
            glob: '**/*',
            input: `${APP}/public/assets/gl`,
            output: 'assets',
          }),
      },
      'app:dk',
      `ships assets/img/logo-gl.png from the gl overlay (${APP}/public/assets/gl/img/logo-gl.png)`,
    );
  });

  it("rejects a country's own overlay shipped under its folder name", async () => {
    await assertRejected(
      {
        config: (projects) => (app(projects).configurations.gl.assets[0].ignore = ['assets/dk/**']),
      },
      'app:gl',
      "ships assets/gl/img/logo-gl.png under the gl overlay's folder name",
    );
  });

  it("rejects the default overlay in a country's configuration", async () => {
    await assertRejected(
      {
        config: (projects) =>
          site(projects).configurations.dk.assets.push({
            glob: '**/*',
            input: `${SITE}/public/assets/default`,
            output: 'assets',
          }),
      },
      'site:dk',
      'ships assets/branding/favicon.png from the default overlay',
    );
  });

  it('rejects a branding file or legal document for a language without branding', async () => {
    await assertRejected(
      {
        files: (files) =>
          (files[`${APP}/src/app/country-specific/dk/language.config.ts`] = adminLanguages({
            availableLanguages: [option('da', 'da.png'), option('en', 'en.png')],
            uiLanguages: ['da', 'en'],
            brandingLanguages: ['da'],
            defaultLanguage: 'da',
          })),
      },
      'app:dk',
      'ships assets/branding/en.json, but "en" is not a branding language',
      'ships assets/legal/disclaimer.en.html, but "en" is not a branding language',
    );
    await assertRejected(
      { files: (files) => (files[`${APP}/public/assets/branding/en.json`] = '{}') },
      'app:production',
      'ships assets/branding/en.json, but "en" is not a branding language',
    );
  });

  it('rejects a bundle or flag that does not come from the staged language folder', async () => {
    await assertRejected(
      {
        files: (files) => (files[`${SITE}/public/assets/i18n/en.json`] = '{}'),
        config: (projects) => site(projects).options.assets[2].ignore.push('i18n/en.json'),
      },
      'site:production',
      `ships assets/i18n/en.json from ${SITE}/public/assets/i18n/en.json; bundles and flags reach a build only from ${SITE_STAGED}/`,
    );
    await assertRejected(
      {
        config: (projects) => {
          const dk = app(projects).configurations.dk;
          dk.assets[2].ignore.push('img/flags/en.png');
          dk.assets.push({
            glob: 'en.png',
            input: 'languages/en/img/flags',
            output: 'assets/img/flags',
          });
        },
      },
      'app:dk',
      `ships assets/img/flags/en.png from languages/en/img/flags/en.png; bundles and flags reach a build only from ${APP_STAGED}/`,
    );
  });

  it('rejects a language named otherwise than its pack names it, or with no pack', async () => {
    await assertRejected(
      {
        files: (files) =>
          (files[`${SITE}/src/app/country-specific/dk/language.config.ts`] = adminLanguages({
            languages: [{ ...option('da', 'da.png'), label: 'Danish' }, option('en', 'en.png')],
            siteLanguages: ['da', 'en'],
            defaultLanguage: 'da',
          })),
      },
      'site:dk',
      'names "da" "Danish" (da); its pack, languages/da/pack.json, names it "Dansk" (da)',
    );
    await assertRejected(
      {
        files: (files) =>
          (files[`${APP}/src/app/country-specific/gl/language.config.ts`] = adminLanguages({
            availableLanguages: [
              { ...option('kl', 'kl.png'), locale: 'de-DE' },
              option('da', 'da.png'),
              option('en', 'en.png'),
            ],
            uiLanguages: ['da', 'en'],
            defaultLanguage: 'da',
          })),
      },
      'app:gl',
      'names "kl" "Kalaallisut" (de-DE); its pack, languages/kl/pack.json, names it "Kalaallisut" (kl)',
    );
    await assertRejected(
      {
        files: (files) => {
          delete files['languages/kl/pack.json'];
          delete files['languages/kl/img/flags/kl.png'];
        },
      },
      'app:gl',
      'names the language "kl", which has no pack, languages/kl/',
    );
  });

  it('rejects a language configuration with a runtime import', async () => {
    await assertRejected(
      {
        files: (files) =>
          (files[`${APP}/src/app/country-specific/dk/language.config.ts`] =
            `import { DANISH } from './danish';\n\nexport const COUNTRY_LANGUAGES = { uiLanguages: [DANISH, 'en'] };\n`),
      },
      'app:dk',
      'imports at runtime',
    );
  });

  it('fails loudly when the builder moved its asset code', () => {
    assert.throws(() => {
      const empty = fs.mkdtempSync(path.join(os.tmpdir(), 'no-builder-'));
      temporary.push(empty);
      builderModule('resolve-assets.js', empty);
    }, /has no src\/utils\/resolve-assets\.js: the builder moved its asset code/);
  });

  it("names every output path two inputs write, through the kit's shared resolution", () => {
    assert.deepEqual(
      duplicateOutputs([
        { source: 'ui-apps/app/public/a.png', destination: 'a.png' },
        { source: 'ui-apps/app/public/b.png', destination: 'b.png' },
        { source: '.niord/build/app/a.png', destination: 'a.png' },
      ]),
      [{ destination: 'a.png', sources: ['ui-apps/app/public/a.png', '.niord/build/app/a.png'] }],
    );
  });
});
