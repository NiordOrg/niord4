/**
 * The administration app's service worker (ui-apps/admin/ngsw-config.json, docs/platform.md):
 * every file a build writes is cached by exactly one asset group, the `media/` files the styles
 * reference (a country's vendored face, fe-country-split-plan §4.7) in a lazy group of their own so
 * a face shows offline after its first load, no data group caches an API response, and a navigation
 * never claims the backend's paths.
 *
 *   node --test scripts/admin-service-worker.test.mjs      # from fe/; also in npm run test:guards
 *
 * The test runs Angular's own generator, the one the production build runs, over an in-memory build
 * output, so it reads the config as the build reads it.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { Generator } from '@angular/service-worker/config';

const FE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const CONFIG = JSON.parse(fs.readFileSync(path.join(FE, 'ui-apps/admin/ngsw-config.json'), 'utf8'));

// One file of each kind the build writes into dist/niord-app/browser.
const FACE = '/media/face-latin-400-normal-DDDD.woff2';
const SILENT_CHECK_SSO = ['/silent-check-sso.html', '/silent-check-sso.js'];
const IMAGE = '/assets/img/niord.png';
const BUILD_OUTPUT = [
  '/index.html',
  '/favicon.ico',
  '/main-AAAA.js',
  '/styles-BBBB.css',
  '/chunk-CCCC.js',
  ...SILENT_CHECK_SSO,
  '/assets/i18n/en.json',
  '/assets/branding/en.json',
  IMAGE,
  FACE,
];

/** The generator's file system over the files above; it only lists and hashes them. */
const memoryFs = (files) => ({
  list: async () => [...files],
  read: async (file) => `contents of ${file}`,
  hash: async (file) => `hash-of-${file}`,
  write: async () => {},
});

const manifest = await new Generator(memoryFs(BUILD_OUTPUT), '/').process(CONFIG);

/** Whether the worker treats a URL as a navigation: a positive pattern matches and no negative. */
function isNavigation(url) {
  const matches = (positive) =>
    manifest.navigationUrls
      .filter((entry) => entry.positive === positive)
      .some((entry) => new RegExp(entry.regex).test(url));
  return matches(true) && !matches(false);
}

describe('the administration app service worker', () => {
  it('caches a face the styles reference in the lazy media group', () => {
    const media = manifest.assetGroups.find((group) => group.name === 'media');
    assert.ok(media, 'no asset group named media');
    assert.equal(media.installMode, 'lazy');
    assert.deepEqual(media.urls, [FACE]);
  });

  it('prefetches the silent-check-sso files with the app shell', () => {
    const shell = manifest.assetGroups.find((group) => group.name === 'app-shell');
    assert.ok(shell, 'no asset group named app-shell');
    for (const file of SILENT_CHECK_SSO) {
      assert.ok(shell.urls.includes(file), `${file} is not in the app-shell group`);
    }
  });

  it('caches an image under assets/ in the images group', () => {
    const images = manifest.assetGroups.find((group) => group.name === 'images');
    assert.ok(images, 'no asset group named images');
    assert.deepEqual(images.urls, [IMAGE]);
  });

  it('caches every file of the build in exactly one asset group', () => {
    for (const file of BUILD_OUTPUT) {
      const groups = manifest.assetGroups.filter((group) => group.urls.includes(file));
      assert.equal(
        groups.length,
        1,
        `${file} is in ${groups.length} groups: ${groups.map((g) => g.name).join(', ')}`,
      );
    }
  });

  it('caches no API response', () => {
    assert.deepEqual(manifest.dataGroups, []);
  });

  it('serves navigations fresh and leaves the backend paths to the network', () => {
    assert.equal(manifest.navigationRequestStrategy, 'freshness');
    for (const excluded of ['!/rest/**', '!/auth/**', '!/wms/**']) {
      assert.ok(CONFIG.navigationUrls.includes(excluded), `navigationUrls lacks ${excluded}`);
    }
    assert.ok(isNavigation('/messages/editor'));
    for (const url of ['/rest/messages', '/auth/realms/niord', '/wms/ows']) {
      assert.ok(!isNavigation(url), `${url} is served as a navigation`);
    }
  });
});
