/**
 * Negative tests for the checks on every country build, check-build.mjs and lib/build-checks.mjs.
 *
 *   npm run test:guards      # from fe/
 *
 * Each case writes a country's fe/ with a small, clean output of both applications — no Angular
 * build — breaks one rule in it and expects the check to name it. The separation check and the
 * bundle report run through an injected runner, which records their command lines. The one-source
 * check resolves a small generated angular.json with the base product's own installed
 * @angular/build, as a country's check resolves its workspace's.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { main } from './check-build.mjs';
import { builderDir, resolveOutputs } from './lib/asset-outputs.mjs';
import { BUNDLE_ARGS, checkBuild, indexProblems, oneSourcePerOutput } from './lib/build-checks.mjs';
import { where } from './lib/where.mjs';

const BASE = where().base;
const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

const PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
const MANIFEST = {
  country: 'xx',
  name: 'Example country',
  apps: {
    admin: { uiLanguages: ['en', 'zz'], contentLanguages: ['en', 'zz'], defaultLanguage: 'en' },
    info: { uiLanguages: ['zz'], defaultLanguage: 'zz' },
  },
  packs: { zz: { home: true, publish: false } },
  overrides: [],
};
const ADMIN = 'dist/niord-app/browser';
const INFO = 'dist/niord-info/browser';
/** A generated angular.json's two projects, each with the asset inputs given. */
const angularJson = ({ admin, info }) =>
  JSON.stringify({
    projects: Object.fromEntries(
      [
        ['niord-app', 'admin', admin],
        ['niord-info', 'info', info],
      ].map(([name, app, assets]) => [
        name,
        {
          root: `.niord/base/ui-apps/${app}`,
          sourceRoot: `.niord/base/ui-apps/${app}/src`,
          architect: {
            build: { options: { assets }, configurations: { production: {}, development: {} } },
          },
        },
      ]),
    ),
  });
const page = (styles, { lang = 'en', title = 'Niord' } = {}) =>
  `<!doctype html><html lang="${lang}"><head><meta charset="utf-8"><title>${title}</title><link rel="stylesheet" href="${styles}"></head><body><script src="main-AbCdEf12.js" type="module"></script></body></html>`;
/** The public site's built page of the example's shape, in its default language, zz. */
const infoPage = (options) =>
  page('styles-GhIjKl34.css', { lang: 'zz', title: 'Ẑẑ site', ...options });

/** A clean build of the example's shape: relative path → content. */
const CLEAN = {
  'niord-country.json': JSON.stringify(MANIFEST),
  'angular.json': angularJson({ admin: [], info: [] }),
  'info/bundle-baseline.json': '{}',
  '.niord/build/country-utilities.json': JSON.stringify({
    format: 1,
    admin: [],
    info: [{ file: 'info/app/footer/footer.component.ts', utility: 'sm:grid-cols-[3fr_2fr]' }],
  }),
  [`${ADMIN}/index.html`]: page('styles-AbCdEf12.css'),
  [`${ADMIN}/main-AbCdEf12.js`]: 'const svg="http://www.w3.org/2000/svg";',
  [`${ADMIN}/styles-AbCdEf12.css`]: '.p-2{padding:.5rem}',
  [`${ADMIN}/assets/i18n/en.json`]: '{}',
  [`${ADMIN}/assets/i18n/zz.json`]: '{}',
  [`${ADMIN}/assets/img/flags/en.png`]: PNG,
  [`${ADMIN}/assets/img/flags/zz.png`]: PNG,
  [`${ADMIN}/assets/legal/disclaimer.en.html`]:
    '<p><a href="https://example.org/terms">Terms</a></p>',
  [`${INFO}/index.html`]: infoPage(),
  [`${INFO}/main-AbCdEf12.js`]: 'bootstrap();',
  [`${INFO}/styles-GhIjKl34.css`]:
    '@media (width>=40rem){.sm\\:grid-cols-\\[3fr_2fr\\]{grid-template-columns:3fr 2fr}}',
  [`${INFO}/assets/i18n/zz.json`]: JSON.stringify({ SITE_TITLE: 'Ẑẑ site' }),
  [`${INFO}/assets/img/flags/zz.png`]: PNG,
  'dist/niord-info/browser-stats.json': '{"outputs":{}}',
};

/** A country fe/ with the clean build changed by `edit` (a path → content, or null to delete). */
function country(edit = {}) {
  const fe = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-check-build-'));
  temporary.push(fe);
  for (const [file, content] of Object.entries({ ...CLEAN, ...edit })) {
    if (content === null) continue;
    fs.mkdirSync(path.dirname(path.join(fe, file)), { recursive: true });
    fs.writeFileSync(path.join(fe, file), content);
  }
  return fe;
}

/** The checks over a country; `statuses` are the separation check's and the bundle report's exit codes. */
function check(edit, statuses = {}) {
  const fe = country(edit);
  const calls = [];
  const run = (script, args, { cwd }) => {
    calls.push({ script: path.basename(script), args, cwd });
    return statuses[path.basename(script)] ?? 0;
  };
  return { ...checkBuild({ fe, manifest: MANIFEST, base: BASE, run }), calls, fe };
}

function assertFinding(edit, fragment, statuses) {
  const { errors } = check(edit, statuses);
  assert.ok(
    errors.some((error) => error.includes(fragment)),
    `expected a finding naming "${fragment}", got:\n${errors.join('\n') || '(none)'}`,
  );
}

describe('the checks on every country build', () => {
  it('passes a clean build, running the separation check and the bundle report with constant arguments', () => {
    const { errors, warnings, calls, fe, lines } = check();
    assert.deepEqual(errors, []);
    assert.deepEqual(warnings, []);
    assert.ok(
      lines.includes(`${INFO}/index.html: lang zz, titled as SITE_TITLE`),
      lines.join('\n'),
    );
    assert.deepEqual(calls, [
      {
        script: 'info-separation-check.mjs',
        args: [
          '--stats',
          'dist/niord-info/browser-stats.json',
          '--root',
          '.niord/base/',
          '--country',
          'xx',
        ],
        cwd: fe,
      },
      { script: 'info-bundle-report.mjs', args: BUNDLE_ARGS, cwd: fe },
    ]);
  });

  it('fails an extra bundle and a missing one', () => {
    assertFinding(
      { [`${INFO}/assets/i18n/en.json`]: '{}' },
      `bundles (${INFO}): assets/i18n/en.json is shipped`,
    );
    assertFinding(
      { [`${ADMIN}/assets/i18n/zz.json`]: null },
      `bundles (${ADMIN}): niord-country.json names zz, and assets/i18n/zz.json is not shipped`,
    );
  });

  describe("the public site's built page", () => {
    const PAGE = `${INFO}/index.html`;
    const fix =
      "the country's info/app/index.html (the index seam) gives the page's lang and title in its default language";

    it('fails a page whose lang is not the default language', () => {
      assertFinding(
        { [PAGE]: infoPage({ lang: 'en' }) },
        `index (${INFO}): index.html has lang "en", not zz, the public site's default language: ${fix}`,
      );
    });

    it("fails a page whose title is not the default language's SITE_TITLE", () => {
      assertFinding(
        { [PAGE]: infoPage({ title: 'Example country — Navigational Warnings' }) },
        `index (${INFO}): index.html is titled "Example country — Navigational Warnings", not "Ẑẑ site", the SITE_TITLE of assets/i18n/zz.json: ${fix}`,
      );
    });

    it('decodes the title and collapses its white space before comparing', () => {
      const { errors } = check({
        [PAGE]: infoPage({ lang: 'ZZ', title: '\n  A &amp;   B\n' }),
        [`${INFO}/assets/i18n/zz.json`]: JSON.stringify({ SITE_TITLE: 'A & B' }),
      });
      assert.deepEqual(errors, []);
    });

    it('keeps a reference past the last code point as written, and reports the title', () => {
      assertFinding(
        { [PAGE]: infoPage({ title: 'A &#99999999; &#x110000;' }) },
        `index (${INFO}): index.html is titled "A &#99999999; &#x110000;", not "Ẑẑ site", the SITE_TITLE of assets/i18n/zz.json: ${fix}`,
      );
    });

    it("reads one built page alone, against the default language's bundle", () => {
      const dist = path.join(country(), INFO);
      assert.deepEqual(indexProblems({ dist, defaultLanguage: 'zz' }), []);
      assert.deepEqual(indexProblems({ dist, defaultLanguage: 'en' }), [
        `index.html has lang "zz", not en, the public site's default language: ${fix}`,
        "assets/i18n/en.json cannot be read (ENOENT), so index.html's title is held to nothing",
      ]);
      fs.writeFileSync(path.join(dist, 'index.html'), '<html><title>a</title><title>b</title>');
      assert.deepEqual(indexProblems({ dist, defaultLanguage: 'zz' }), [
        `index.html has no lang, and zz is the public site's default language: ${fix}`,
        `index.html has 2 titles, not one: ${fix}`,
      ]);
    });

    it('fails a build without the page, and a bundle without SITE_TITLE', () => {
      assertFinding({ [PAGE]: null }, `index (${INFO}): index.html does not exist`);
      assertFinding(
        { [`${INFO}/assets/i18n/zz.json`]: '{}' },
        `index (${INFO}): assets/i18n/zz.json has no SITE_TITLE to hold index.html's title to`,
      );
    });
  });

  it('fails a file under assets/i18n/ that is not a UI bundle', () => {
    assertFinding(
      { [`${ADMIN}/assets/i18n/zz.yaml`]: '' },
      `bundles (${ADMIN}): assets/i18n/zz.yaml is not a UI bundle`,
    );
  });

  it('fails a file under assets/img/flags/ that is not a flag', () => {
    assertFinding(
      { [`${INFO}/assets/img/flags/zz.webp`]: PNG },
      `flags (${INFO}): assets/img/flags/zz.webp is not a flag`,
    );
  });

  it('fails an extra flag and a missing one', () => {
    assertFinding(
      { [`${INFO}/assets/img/flags/en.png`]: PNG },
      `flags (${INFO}): assets/img/flags/en.png is shipped`,
    );
    assertFinding(
      { [`${ADMIN}/assets/img/flags/en.png`]: null },
      `flags (${ADMIN}): niord-country.json names en, and assets/img/flags/en.png is not shipped`,
    );
  });

  it("fails a flag's drawing, a licence notice, a status file, an installed package, a file of the generated workspace and another tree's assets", () => {
    assertFinding(
      { [`${INFO}/assets/img/flags/zz.svg`]: '<svg/>' },
      `assets/img/flags/zz.svg is a flag's drawing`,
    );
    assertFinding(
      { [`${ADMIN}/assets/ATTRIBUTION.txt`]: 'CC0' },
      "assets/ATTRIBUTION.txt is a pack's licence notice",
    );
    assertFinding(
      { [`${ADMIN}/assets/i18n/status/unaccepted.json`]: '[]' },
      'assets/i18n/status/unaccepted.json is a pack status file',
    );
    assertFinding(
      { [`${INFO}/node_modules/x/index.js`]: 'x' },
      'node_modules/x/index.js is an installed package',
    );
    assertFinding(
      { [`${INFO}/.niord/x.json`]: '{}' },
      `files (${INFO}): .niord/x.json is a file of the generated workspace`,
    );
    assertFinding(
      { [`${INFO}/assets/dk/branding/da.json`]: '{}' },
      "assets/dk/branding/da.json is another tree's assets",
    );
    assertFinding(
      { [`${INFO}/assets/default/branding/en.json`]: '{}' },
      "assets/default/branding/en.json is another tree's assets",
    );
  });

  it('fails an external font URL, and an absolute URL the base product does not list', () => {
    assertFinding(
      {
        [`${INFO}/styles-GhIjKl34.css`]: `${CLEAN[`${INFO}/styles-GhIjKl34.css`]}@import url(https://fonts.googleapis.com/css2?family=Lato);`,
      },
      `urls (${INFO}): styles-GhIjKl34.css:1: an @import from another origin`,
    );
    assertFinding(
      { [`${ADMIN}/main-AbCdEf12.js`]: 'fetch("https://cdn.example.org/tracker.js")' },
      `urls (${ADMIN}): main-AbCdEf12.js:1: an absolute URL in neither build-urls-allowed.json nor fe/build-urls-allowed.json: https://cdn.example.org/tracker.js`,
    );
  });

  describe("the country's own URL allow-list", () => {
    const SITE = 'https://authority.example/zz/';
    const footer = { [`${INFO}/main-AbCdEf12.js`]: `const site="${SITE}";bootstrap();` };
    const list = (...allow) => ({
      'build-urls-allowed.json': JSON.stringify({ format: 1, note: "the country's", allow }),
    });
    const site = {
      pattern: String.raw`https://authority\.example/zz/$`,
      reason: "the authority's site, linked from the public site's footer",
    };

    it("admits the country's address, names it on every build, and fails it without the list", () => {
      const { errors, warnings, lines } = check({ ...footer, ...list(site) });
      assert.deepEqual(errors, []);
      assert.deepEqual(warnings, []);
      assert.ok(
        lines.includes(
          "urls: the country's allow-list admits https://authority.example/zz/ — the authority's site, linked from the public site's footer",
        ),
        lines.join('\n'),
      );
      assertFinding(
        footer,
        `urls (${INFO}): main-AbCdEf12.js:1: an absolute URL in neither build-urls-allowed.json nor fe/build-urls-allowed.json: ${SITE}`,
      );
    });

    it('warns of an entry that admits nothing in either application, and passes', () => {
      const { errors, warnings } = check(list(site));
      assert.deepEqual(errors, []);
      assert.deepEqual(warnings, [
        "urls: fe/build-urls-allowed.json: allow[0] (https://authority.example/zz/) admits no URL of either application's build, so it permits nothing: remove it",
      ]);
    });

    it('fails an entry that is a pattern rather than one literal https: URL, and admits nothing by it', () => {
      const { errors } = check({
        ...footer,
        ...list({ ...site, pattern: String.raw`https://[a-z]+\.example/zz/$` }),
      });
      assert.equal(errors.length, 2, errors.join('\n'));
      assert.match(
        errors[0],
        /^urls: fe\/build-urls-allowed\.json: allow\[0\]: the pattern .* holds the regular-expression syntax "\["/,
      );
      assert.match(errors[1], /an absolute URL in neither/);
    });

    it('fails an http: entry, one without a reason, and a list that is not JSON', () => {
      assertFinding(
        { ...footer, ...list({ ...site, pattern: String.raw`http://authority\.example/zz/$` }) },
        'urls: fe/build-urls-allowed.json: allow[0]: http://authority.example/zz/ is not an https: URL',
      );
      assertFinding(
        { ...footer, ...list({ pattern: site.pattern }) },
        'urls: fe/build-urls-allowed.json: allow[0] has no reason',
      );
      assertFinding({ 'build-urls-allowed.json': '{' }, 'urls: fe/build-urls-allowed.json: ');
    });

    describe('an entry scoped to one application', () => {
      const both = {
        [`${ADMIN}/main-AbCdEf12.js`]: `const site="${SITE}";`,
        [`${INFO}/main-AbCdEf12.js`]: `const site="${SITE}";bootstrap();`,
      };

      it('an info-scoped entry no longer admits the URL in dist/niord-app main-*.js', () => {
        const scoped = check({ ...both, ...list({ ...site, files: 'main-*.js', app: 'info' }) });
        assert.deepEqual(scoped.errors, [
          `urls (${ADMIN}): main-AbCdEf12.js:1: an absolute URL in neither build-urls-allowed.json nor fe/build-urls-allowed.json: ${SITE} (fe/build-urls-allowed.json allow[0] admits it in the public site's build only)`,
        ]);
        assert.deepEqual(scoped.warnings, []);
        const unscoped = check({ ...both, ...list({ ...site, files: 'main-*.js' }) });
        assert.deepEqual(unscoped.errors, []);
        assert.deepEqual(unscoped.warnings, []);
      });

      it("warns of a scoped entry that admits nothing in its application's build", () => {
        const onlyAdmin = { [`${ADMIN}/main-AbCdEf12.js`]: `const site="${SITE}";` };
        const info = check({ ...onlyAdmin, ...list({ ...site, app: 'info' }) });
        assert.equal(info.errors.length, 1, info.errors.join('\n'));
        assert.match(info.errors[0], new RegExp(`^urls \\(${ADMIN.replaceAll('/', '\\/')}\\): `));
        assert.deepEqual(info.warnings, [
          "urls: fe/build-urls-allowed.json: allow[0] (https://authority.example/zz/) admits no URL of the public site's build, so it permits nothing: remove it",
        ]);
        const admin = check({ ...footer, ...list({ ...site, app: 'admin' }) });
        assert.deepEqual(admin.warnings, [
          "urls: fe/build-urls-allowed.json: allow[0] (https://authority.example/zz/) admits no URL of the administration app's build, so it permits nothing: remove it",
        ]);
      });

      it('names the scope on every build', () => {
        const { errors, lines } = check({ ...footer, ...list({ ...site, app: 'info' }) });
        assert.deepEqual(errors, []);
        assert.ok(
          lines.includes(
            "urls: the country's allow-list admits https://authority.example/zz/ in the public site's build only — the authority's site, linked from the public site's footer",
          ),
          lines.join('\n'),
        );
      });

      it('fails an app that is neither admin nor info', () => {
        assertFinding(
          { ...footer, ...list({ ...site, app: 'backend' }) },
          'urls: fe/build-urls-allowed.json: allow[0]: app is "backend", not "admin" or "info"',
        );
      });
    });
  });

  it('fails a recorded utility missing from the CSS', () => {
    assertFinding(
      { [`${INFO}/styles-GhIjKl34.css`]: '.p-2{padding:.5rem}' },
      `utility (${INFO}): sm:grid-cols-[3fr_2fr], which info/app/footer/footer.component.ts uses, is in no stylesheet`,
    );
  });

  it('fails a secret in a built script, without printing it', () => {
    const value = ['gh', 'p_', 'A1b2C3d4'.repeat(5)].join('');
    const { errors } = check({ [`${INFO}/main-AbCdEf12.js`]: `const t="${value}";` });
    assert.deepEqual(errors, [
      `secrets: ${INFO}/main-AbCdEf12.js:1: github-token (ghp_…, 44 characters)`,
    ]);
  });

  it('fails when the separation check or the bundle report exits 1', () => {
    assertFinding({}, 'separation check: info-separation-check.mjs exited 1', {
      'info-separation-check.mjs': 1,
    });
    assertFinding(
      {},
      'bundle report: info-bundle-report.mjs exited 1, against info/bundle-baseline.json',
      {
        'info-bundle-report.mjs': 1,
      },
    );
  });

  it('warns, and passes, when the country has no bundle baseline yet', () => {
    const { errors, warnings, calls } = check({ 'info/bundle-baseline.json': null });
    assert.deepEqual(errors, []);
    assert.match(
      warnings.join('\n'),
      /info\/bundle-baseline\.json does not exist.*npm run bundle:info -- --update-baseline/,
    );
    assert.deepEqual(
      calls.map(({ script }) => script),
      ['info-separation-check.mjs'],
    );
  });

  it('exits 2 when the build has not run or angular.json is missing, and 1 on a finding', async () => {
    const lines = [];
    const quiet = {
      log: () => {},
      warn: (line) => lines.push(line),
      run: () => 0,
      resolve: async () => [],
    };
    assert.equal(
      await main({ fe: country({ 'dist/niord-info/browser-stats.json': null }), ...quiet }),
      2,
    );
    assert.match(
      lines.join('\n'),
      /dist\/niord-info\/browser-stats\.json missing: run `npm run build`/,
    );
    assert.equal(await main({ fe: country({ 'angular.json': null }), ...quiet }), 2);
    assert.match(lines.join('\n'), /angular\.json missing: run `node setup\.mjs`/);
    assert.equal(await main({ fe: country(), ...quiet }), 0);
    assert.equal(
      await main({ fe: country({ [`${INFO}/assets/i18n/en.json`]: '{}' }), ...quiet }),
      1,
    );
  });
});

describe('one source per output file', () => {
  // The base product's own installed builder: a temporary workspace has no node_modules.
  const resolve = (assets, options) =>
    resolveOutputs(assets, { ...options, packageDir: builderDir(BASE) });
  const base = (app) => ({ glob: '**/*', input: `.niord/base/ui-apps/${app}/public` });
  const staged = (app) => ({ glob: '**/*', input: `.niord/build/${app}`, output: 'assets' });
  const ASSETS = {
    admin: [base('admin'), staged('admin')],
    info: [base('info'), staged('info')],
  };
  const INPUTS = {
    'angular.json': angularJson(ASSETS),
    '.niord/base/ui-apps/admin/public/favicon.ico': 'base',
    '.niord/base/ui-apps/admin/public/assets/img/niord.png': PNG,
    '.niord/build/admin/branding/logo.png': PNG,
    '.niord/base/ui-apps/info/public/robots.txt': 'base',
    '.niord/build/info/i18n/zz.json': '{}',
  };

  it("fails a file the country's staged folder and a base product input both write, naming both inputs", async () => {
    const fe = country({ ...INPUTS, '.niord/build/admin/img/niord.png': PNG });
    const { errors } = await oneSourcePerOutput({ fe, resolve });
    assert.deepEqual(errors, [
      'one source (niord-app:production, development): assets/img/niord.png is written by 2 inputs (.niord/base/ui-apps/admin/public/assets/img/niord.png, .niord/build/admin/img/niord.png); one output path takes one source',
    ]);
    const lines = [];
    const warn = (line) => lines.push(line);
    assert.equal(await main({ fe, log: () => {}, warn, run: () => 0, resolve }), 1);
    assert.match(lines.join('\n'), /error: one source \(niord-app:production, development\)/);
  });

  it('passes inputs that write distinct files, and says what it resolved', async () => {
    const { errors, lines } = await oneSourcePerOutput({ fe: country(INPUTS), resolve });
    assert.deepEqual(errors, []);
    assert.deepEqual(lines, [
      'one source (niord-app:production, development): 3 asset file(s)',
      'one source (niord-info:production, development): 2 asset file(s)',
    ]);
  });

  it('fails a project without a build target, and assets that do not resolve', async () => {
    const empty = country({ 'angular.json': JSON.stringify({ projects: {} }) });
    assert.match(
      (await oneSourcePerOutput({ fe: empty, resolve })).errors.join('\n'),
      /angular\.json has no build target for niord-app: run `node setup\.mjs`/,
    );
    const failing = async () => {
      throw new Error('the builder moved its asset code');
    };
    assert.match(
      (await oneSourcePerOutput({ fe: country(INPUTS), resolve: failing })).errors[0],
      /^one source \(niord-app:production, development\): its assets do not resolve: the builder moved/,
    );
  });
});
