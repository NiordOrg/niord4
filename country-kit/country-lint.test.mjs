/**
 * Negative tests for country-kit/lib/country-lint.cjs, the lint a country's files get in its
 * generated workspace (fe-country-split-plan §4.3, the country API; §4.11 rule 3).
 *
 *   npm run test:guards      # from fe/
 *
 * Each rule is proven the way the boundary tests prove the base product's: a crafted file at a
 * country path must come back with an error, and the spelling the rule allows must not. The
 * workspace is a temporary country fe/ under the base product's .niord/, so the parsers resolve
 * from the base product's node_modules as the generated workspace's do; it is deleted afterwards.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { after, before, describe, it } from 'node:test';
import { where } from './lib/where.mjs';
import { requireFromBase } from './test-fixtures/base-modules.mjs';

const require = createRequire(import.meta.url);
const lint = require('./lib/country-lint.cjs');
const angular = requireFromBase('angular-eslint');
const { ESLint } = requireFromBase('eslint');
const tseslint = requireFromBase('typescript-eslint');

const FE = where().base;

const COUNTRY_PACKAGE = {
  name: 'niord-xx-country',
  private: true,
  dependencies: { 'admin-only': '1.0.0', 'info-only': '1.0.0', shared: '1.0.0' },
  niord: {
    dependencies: {
      'admin-only': { app: 'admin', reason: 'the harbour page' },
      'info-only': { app: 'info', reason: 'the about page' },
      shared: { app: 'both', reason: 'both footers' },
    },
  },
};

/** The blocks the generated configuration ends with, over the base product's parsers. */
function eslintFor(dir) {
  return new ESLint({
    cwd: dir,
    overrideConfigFile: true,
    overrideConfig: [
      {
        files: ['**/*.ts'],
        languageOptions: { parser: tseslint.parser },
        processor: angular.processInlineTemplates,
      },
      { files: ['**/*.html'], languageOptions: { parser: angular.templateParser } },
      ...lint.countryBlocks({ dir }),
    ],
  });
}

let dir;
let eslint;

before(() => {
  fs.mkdirSync(path.join(FE, '.niord'), { recursive: true });
  dir = fs.mkdtempSync(path.join(FE, '.niord', 'country-lint-'));
  for (const folder of ['admin/app', 'admin/overrides', 'info/app', 'country', 'e2e'])
    fs.mkdirSync(path.join(dir, folder), { recursive: true });
  fs.writeFileSync(path.join(dir, 'country', 'package.json'), JSON.stringify(COUNTRY_PACKAGE));
  eslint = eslintFor(dir);
});

after(() => fs.rmSync(dir, { recursive: true, force: true }));

async function messages(file, code, engine = eslint) {
  const [result] = await engine.lintText(code, { filePath: path.join(dir, file) });
  assert.deepEqual(
    result.messages.filter((message) => message.fatal),
    [],
    `${file} did not parse`,
  );
  return result.messages;
}

const errors = async (file, code, engine) =>
  (await messages(file, code, engine)).filter((message) => message.severity === 2);

async function rejects(file, code, rule = 'niord-country/imports') {
  const found = await errors(file, code);
  assert.ok(
    found.some((message) => message.ruleId === rule),
    `expected ${rule} in ${file} for:\n${code}\ngot ${JSON.stringify(found)}`,
  );
}

async function allows(file, code) {
  const found = await errors(file, code);
  assert.deepEqual(
    found.map((message) => `${message.ruleId}: ${message.message}`),
    [],
    `expected no error in ${file} for:\n${code}`,
  );
}

const staticImport = (specifier) => `import '${specifier}';\n`;
const typeImport = (specifier) => `import type { T } from '${specifier}';\nexport type U = T;\n`;
const reExport = (specifier) => `export * from '${specifier}';\n`;
const namedReExport = (specifier) => `export { x } from '${specifier}';\n`;
const dynamicImport = (specifier) => `export const load = () => import('${specifier}');\n`;
const templateImport = (specifier) =>
  `export const load = (name: string) => import(\`${specifier}\${name}\`);\n`;

const PAGE = 'admin/app/harbour/harbour-page.ts';
const SPEC = 'admin/app/harbour/harbour-page.spec.ts';
const FOOTER = 'info/app/footer/footer.component.ts';

describe('niord-country/imports: relative paths stay in the country’s folders', () => {
  const escape = '../../../.niord/base/ui-apps/admin/src/app/core/config/app-config.interface';

  it('rejects a static, type, re-export, dynamic and template-literal escape out of admin/app', async () => {
    for (const form of [staticImport, typeImport, reExport, namedReExport, dynamicImport])
      await rejects(PAGE, form(escape));
    await rejects(PAGE, templateImport('../../../.niord/base/'));
    await rejects(PAGE, staticImport('../../x'));
    await rejects(PAGE, `import x = require('${escape}');\nexport const y = x;\n`);
    await rejects(PAGE, `export type T = import('${escape}').AppConfig;\n`);
  });

  it('rejects an import() whose specifier is built at run time, a template literal included', async () => {
    await rejects(PAGE, 'export const load = (name: string) => import(name);\n');
    await rejects(PAGE, 'export const load = () => import(`./harbour-page`);\n');
  });

  it('allows admin/app, admin/overrides and admin/environments', async () => {
    await allows(
      PAGE,
      staticImport('./harbour.model') +
        staticImport('../country.menu') +
        staticImport('../../overrides/features/messages/x') +
        staticImport('../../environments/environment') +
        dynamicImport('./harbour-details'),
    );
  });

  it('lets a spec reach the application’s assets, and nothing else does', async () => {
    await allows(SPEC, staticImport('../../assets/branding/en.json'));
    await rejects(PAGE, staticImport('../../assets/branding/en.json'));
    await rejects(SPEC, staticImport('../../../info/app/footer/footer.component'));
  });

  it('rejects an absolute path, a drive path and a URL', async () => {
    for (const specifier of [
      '/srv/niord/x',
      'C:/niord/x',
      'https://cdn.example.org/x.js',
      'http://cdn.example.org/x.js',
      'file:///srv/niord/x',
      'data:text/javascript,export default 1',
    ]) {
      await rejects(PAGE, staticImport(specifier));
      await rejects(PAGE, dynamicImport(specifier));
    }
  });

  it('lets only a spec import a Node built-in', async () => {
    await rejects(PAGE, staticImport('node:fs'));
    await allows(SPEC, staticImport('node:fs'));
  });
});

describe('niord-country/imports: the base product through the application’s API alone', () => {
  it('allows @niord/admin/api, and its /testing entry in a spec only', async () => {
    await allows(PAGE, staticImport('@niord/admin/api') + typeImport('@niord/admin/api'));
    await rejects(PAGE, staticImport('@niord/admin/api/testing'));
    await allows(SPEC, staticImport('@niord/admin/api/testing'));
  });

  it('rejects the internals outside admin/overrides and allows them in an override', async () => {
    await rejects(PAGE, staticImport('@niord/admin/internal/core/config/app-config.interface'));
    await rejects(PAGE, dynamicImport('@niord/admin/internal/features/home/home.routes'));
    await allows(
      'admin/overrides/features/messages/x.ts',
      staticImport('@niord/admin/internal/core/config/app-config.interface'),
    );
    await rejects(
      'admin/overrides/features/messages/x.ts',
      staticImport('@niord/info/internal/core/x'),
    );
  });

  it("rejects the other application's API and the base product's own aliases", async () => {
    await rejects(PAGE, staticImport('@niord/info/api'));
    await rejects(FOOTER, staticImport('@niord/admin/api'));
    await rejects(FOOTER, staticImport('@niord/admin/api/testing'));
    await rejects(PAGE, staticImport('@niord/map/ol-map'));
    await rejects(PAGE, staticImport('@niord/html/message-html-sanitizer'));
  });

  it('allows the base product’s ordinary packages', async () => {
    await allows(
      PAGE,
      staticImport('@angular/core') + staticImport('@jsverse/transloco') + staticImport('rxjs'),
    );
  });
});

describe('niord-country/imports: packages', () => {
  it('rejects keycloak-js and signal forms on the public site, statically and dynamically', async () => {
    for (const specifier of ['keycloak-js', 'keycloak-js/lib/x', '@angular/forms/signals']) {
      await rejects(FOOTER, staticImport(specifier));
      await rejects(FOOTER, dynamicImport(specifier));
    }
    await allows(PAGE, staticImport('keycloak-js'));
  });

  it('rejects a country package of the other application, as fe/country/package.json names it', async () => {
    await rejects(PAGE, staticImport('info-only'));
    await rejects(PAGE, dynamicImport('info-only/sub'));
    await rejects(FOOTER, staticImport('admin-only'));
    await allows(PAGE, staticImport('admin-only') + staticImport('shared'));
    await allows(FOOTER, staticImport('info-only') + staticImport('shared'));
  });

  it('reads no country package from a workspace without a readable fe/country/package.json', () => {
    const blocks = lint.countryBlocks({ dir: path.join(dir, 'admin') });
    const options = (app) =>
      blocks.find((block) => block.files[0] === `${app}/**/*.ts`).rules['niord-country/imports'][1];
    assert.deepEqual(options('admin').otherAppPackages, []);
    assert.deepEqual(options('info').otherAppPackages, []);
  });
});

describe("niord-country/e2e-imports: a country's e2e specs import the fixtures, Playwright, node: and their own folder", () => {
  const E2E = 'e2e/harbour.spec.ts';
  const rule = 'niord-country/e2e-imports';

  it("rejects the base product's internals and its APIs", async () => {
    await rejects(E2E, staticImport('@niord/admin/internal/x'), rule);
    await rejects(E2E, staticImport('@niord/admin/api'), rule);
    await rejects(E2E, staticImport('@niord/info/api'), rule);
    await rejects(E2E, staticImport('@niord/admin/e2e/auth/fixtures'), rule);
    await rejects(E2E, staticImport('@playwright/test/reporter'), rule);
  });

  it('rejects a bare package, a built-in without node:, a path out of e2e/, an absolute path and a URL', async () => {
    for (const specifier of [
      'lodash',
      'fs',
      '../admin/app/harbour/harbour-page',
      '../.niord/base/ui-apps/admin/e2e/auth/fixtures',
      '/srv/niord/x',
      'C:/niord/x',
      'https://cdn.example.org/x.js',
    ]) {
      await rejects(E2E, staticImport(specifier), rule);
      await rejects(E2E, dynamicImport(specifier), rule);
    }
    await rejects(E2E, `import x = require('lodash');\nexport const y = x;\n`, rule);
    await rejects(E2E, `export * from 'lodash';\n`, rule);
  });

  it('rejects an import() whose specifier is built at run time', async () => {
    await rejects(E2E, 'export const load = (name: string) => import(name);\n', rule);
  });

  it("allows the base product's fixtures, Playwright, node: built-ins and the specs' own folder", async () => {
    await allows(
      E2E,
      "import { expect, test } from '@niord/admin/e2e';\n" +
        "import { type Page } from '@playwright/test';\n" +
        "import path from 'node:path';\n" +
        "import { login } from './helpers/login';\n" +
        "export const open = async (page: Page, menu: import('@playwright/test').Locator) => {\n" +
        '  await login(page, path.join("a", "b"));\n' +
        '  await menu.click();\n' +
        '  expect(test).toBeDefined();\n' +
        '};\n',
    );
  });

  it('disables nothing by an inline directive, which is reported', async () => {
    const found = await messages(
      E2E,
      `// eslint-disable-next-line ${rule}\n` + staticImport('@niord/admin/internal/x'),
    );
    assert.ok(
      found.some((message) => message.ruleId === rule && message.severity === 2),
      JSON.stringify(found),
    );
    assert.ok(
      found.some((message) => message.ruleId === null && /noInlineConfig/.test(message.message)),
      JSON.stringify(found),
    );
  });

  it('holds the specs to this rule, not the per-application imports rule', async () => {
    const found = await errors(E2E, staticImport('@niord/admin/e2e'));
    assert.deepEqual(found, []);
    const blocks = lint.countryBlocks({ dir });
    const e2e = blocks.filter((block) => block.files.includes('e2e/**/*.ts'));
    assert.ok(e2e.some((block) => block.rules?.[rule]));
    assert.ok(e2e.some((block) => block.linterOptions?.noInlineConfig === true));
  });
});

describe('niord-country/inner-html', () => {
  const html = 'admin/app/harbour/harbour-page.html';
  const bound = (expression) => `<div [innerHTML]="${expression}"></div>\n`;

  it('rejects a bare binding and one through any other pipe', async () => {
    await rejects(html, bound('notes'), 'niord-country/inner-html');
    await rejects(html, bound('notes | uppercase'), 'niord-country/inner-html');
    await rejects(html, bound('notes | safeMessageHtml | uppercase'), 'niord-country/inner-html');
    await rejects(html, '<div [outerHTML]="notes"></div>\n', 'niord-country/inner-html');
    await rejects(html, '<div innerHTML="{{ notes }}"></div>\n', 'niord-country/inner-html');
  });

  it('allows the safe pipes and a translation', async () => {
    await allows(
      html,
      bound('notes | safeMessageHtml') +
        bound('(notes | plainTextToHtml)') +
        bound("'FOOTER_COPYRIGHT' | transloco: { year }") +
        '<div [title]="notes"></div>\n',
    );
  });

  it('reaches an inline template', async () => {
    const component = (expression) =>
      [
        "import { Component } from '@angular/core';",
        '@Component({',
        "  selector: 'app-footer',",
        `  template: \`<p [innerHTML]="${expression}"></p>\`,`,
        '})',
        'export class FooterComponent {}',
        '',
      ].join('\n');
    await rejects(FOOTER, component('text'), 'niord-country/inner-html');
    await allows(FOOTER, component("'FOOTER_COPYRIGHT' | transloco"));
  });
});

describe('the bypassSecurityTrust* ban, restated for country files', () => {
  const bypass = (method) =>
    `export const trust = (dom: { ${method}(value: string): unknown }) => dom.${method}('<b>');\n`;

  it('holds in admin/app, info/app and a folder named like a base product exemption', async () => {
    for (const method of lint.BYPASS_SECURITY_TRUST)
      await rejects(PAGE, bypass(method), 'no-restricted-properties');
    await rejects(
      'admin/app/shared-html/x.ts',
      bypass('bypassSecurityTrustHtml'),
      'no-restricted-properties',
    );
    await rejects(
      'admin/overrides/shared/pipes/plain-text-to-html.pipe.ts',
      bypass('bypassSecurityTrustHtml'),
      'no-restricted-properties',
    );
    await rejects(FOOTER, bypass('bypassSecurityTrustUrl'), 'no-restricted-properties');
  });
});

describe('inline configuration in country files', () => {
  it('disables nothing, and the directive is reported', async () => {
    const found = await messages(
      PAGE,
      '// eslint-disable-next-line niord-country/imports\n' + staticImport('../../x'),
    );
    assert.ok(
      found.some((message) => message.ruleId === 'niord-country/imports' && message.severity === 2),
      JSON.stringify(found),
    );
    assert.ok(
      found.some((message) => message.ruleId === null && /noInlineConfig/.test(message.message)),
      JSON.stringify(found),
    );
  });

  it('in a template too', async () => {
    const found = await messages(
      'admin/app/harbour/harbour-page.html',
      '<!-- eslint-disable-next-line niord-country/inner-html -->\n<div [innerHTML]="notes"></div>\n',
    );
    assert.ok(found.some((message) => message.ruleId === 'niord-country/inner-html'));
    assert.ok(
      found.some((message) => message.ruleId === null && /noInlineConfig/.test(message.message)),
    );
  });
});

describe('the lists the base product’s configuration takes from here', () => {
  it('are the ones its rules name', () => {
    assert.deepEqual(lint.INFO_ANONYMOUS_PACKAGES, [
      'keycloak-js',
      'keycloak-js/*',
      '@angular/forms/signals',
    ]);
    assert.equal(lint.BYPASS_SECURITY_TRUST.length, 5);
    assert.deepEqual(lint.INNER_HTML_PIPES, ['safeMessageHtml', 'plainTextToHtml', 'transloco']);
    assert.equal(lint.plugin.meta.name, 'niord-country');
  });

  it('registers one plugin object in every block, as ESLint requires', () => {
    for (const block of lint.countryBlocks({ dir }))
      if (block.plugins) assert.equal(block.plugins['niord-country'], lint.plugin);
  });
});
