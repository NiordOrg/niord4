/**
 * Negative tests for the boundaries in eslint.config.js: the imports, the time-zone selectors, the
 * rule that only the message-HTML sanitiser marks a value safe, and the recommended rules reaching
 * the kit's and the workspace's scripts.
 *
 *   npm run test:guards      # from fe/
 *
 * A boundary that stops matching fails nothing by itself — a glob left behind by a renamed
 * folder, a relative spelling nobody wrote a pattern for, a dynamic import() — and the lint run
 * stays green. So every boundary is proven here the only way that counts: a crafted import at a
 * real place in the tree must come back as an error, and the spelling the boundary allows must
 * not. Each crafted file sits in a folder that exists, so a renamed folder fails this test
 * instead of silently taking its rules with it.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { ESLint } from 'eslint';

const FE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const BOUNDARY_RULES = new Set([
  'no-restricted-imports',
  'no-restricted-syntax',
  'no-restricted-properties',
]);

// Only the rules the boundaries live in. The type-aware rules need a TypeScript program, and a
// crafted file belongs to none, so the project service is switched off with them.
const eslint = new ESLint({
  cwd: FE,
  overrideConfig: { languageOptions: { parserOptions: { projectService: false } } },
  ruleFilter: ({ ruleId }) => BOUNDARY_RULES.has(ruleId),
});

async function boundaryErrors(file, code) {
  const folder = path.posix.dirname(file);
  assert.ok(
    fs.existsSync(path.join(FE, folder)),
    `${folder}/ no longer exists: eslint.config.js names it, so its rules have stopped applying — update the config and this test`,
  );
  const [result] = await eslint.lintText(code, { filePath: path.join(FE, file) });
  const fatal = result.messages.filter((message) => message.fatal);
  assert.deepEqual(fatal, [], `${file} did not parse`);
  return result.messages.filter((message) => BOUNDARY_RULES.has(message.ruleId));
}

// A side-effect import binds nothing, so several can share one crafted file.
const staticImport = (specifier) => `import '${specifier}';\n`;
const reExport = (specifier) => `export * from '${specifier}';\n`;
const dynamicImport = (specifier) => `export const load = () => import('${specifier}');\n`;
const templateImport = (specifier) =>
  `export const load = (name: string) => import(\`${specifier}\${name}\`);\n`;

async function rejects(file, code) {
  const errors = await boundaryErrors(file, code);
  assert.ok(errors.length > 0, `expected a boundary error in ${file} for:\n${code}`);
}

async function allows(file, code) {
  const errors = await boundaryErrors(file, code);
  assert.deepEqual(
    errors.map((error) => error.message),
    [],
    `expected no boundary error in ${file} for:\n${code}`,
  );
}

/**
 * The spellings of a path from `file` into a folder of the workspace: relative, relative behind a
 * leading `./`, and spelled out through the workspace root.
 */
function spellings(file, target) {
  const folder = path.posix.dirname(file);
  const relative = path.posix.relative(folder, target);
  const toRoot = path.posix.relative(folder, '.');
  return [relative, `./${relative}`, `${toRoot}/${target}`];
}

const INFO = 'ui-apps/info/src/app';
const ADMIN = 'ui-apps/admin/src/app';

describe('the public site imports nothing from the administration app', () => {
  const files = [
    `${INFO}/app.ts`,
    `${INFO}/app.config.ts`,
    `${INFO}/core/services/probe.ts`,
    `${INFO}/shared/components/probe.ts`,
    `${INFO}/i18n/probe.ts`,
    `${INFO}/routes/probe.ts`,
    `${INFO}/features/messages/probe.ts`,
    `${INFO}/features/publications/probe.ts`,
    `${INFO}/features/teaser/probe.ts`,
    `${INFO}/country-specific/default/probe.ts`,
    `${INFO}/testing/probe.ts`,
    `${INFO}/core/services/probe.spec.ts`,
    'ui-apps/info/src/main.ts',
  ];
  for (const file of files) {
    it(`from ${file}`, async () => {
      for (const specifier of spellings(file, `${ADMIN}/core/models/message.model`)) {
        await rejects(file, staticImport(specifier));
        await rejects(file, reExport(specifier));
        await rejects(file, dynamicImport(specifier));
      }
      for (const specifier of spellings(file, `${ADMIN}/features/`))
        await rejects(file, templateImport(specifier));
      for (const specifier of ['keycloak-js', '@angular/forms/signals']) {
        await rejects(file, staticImport(specifier));
        await rejects(file, dynamicImport(specifier));
      }
    });
  }
});

describe('the administration app imports nothing from the public site', () => {
  const files = [
    `${ADMIN}/app.config.ts`,
    `${ADMIN}/core/probe.ts`,
    `${ADMIN}/shared/probe.ts`,
    `${ADMIN}/shared/utils/zoned-date.ts`,
    `${ADMIN}/features/admin/probe.ts`,
    `${ADMIN}/features/editor/probe.ts`,
    `${ADMIN}/features/home/probe.ts`,
    `${ADMIN}/features/messages/probe.ts`,
    `${ADMIN}/features/schedule/probe.ts`,
    `${ADMIN}/country-specific/default/probe.ts`,
    `${ADMIN}/features/messages/probe.spec.ts`,
    'ui-apps/admin/src/environments/probe.ts',
    'ui-apps/admin/e2e/probe.ts',
  ];
  for (const file of files) {
    it(`from ${file}`, async () => {
      for (const specifier of spellings(file, `${INFO}/core/models/message.model`)) {
        await rejects(file, staticImport(specifier));
        await rejects(file, reExport(specifier));
        await rejects(file, dynamicImport(specifier));
      }
      for (const specifier of spellings(file, `${INFO}/features/`))
        await rejects(file, templateImport(specifier));
    });
  }
});

describe('the public site keeps its layers', () => {
  it('shared/ and core/ never import features/', async () => {
    await rejects(
      `${INFO}/shared/components/probe.ts`,
      staticImport('../../features/messages/messages.routes'),
    );
    await rejects(
      `${INFO}/core/services/probe.ts`,
      staticImport('../../features/messages/messages.routes'),
    );
  });

  it('no feature imports a sibling feature, in either spelling', async () => {
    const siblings = { messages: 'teaser', publications: 'messages', teaser: 'publications' };
    for (const [feature, sibling] of Object.entries(siblings)) {
      const file = `${INFO}/features/${feature}/probe.ts`;
      await rejects(file, staticImport(`../${sibling}/x`));
      await rejects(file, staticImport(`../../features/${sibling}/x`));
    }
  });

  it('a feature still imports shared/ and core/', async () => {
    await allows(
      `${INFO}/features/messages/probe.ts`,
      staticImport('../../shared/utils/zoned-date') + staticImport('../../core/services/x'),
    );
  });
});

describe('the public site imports a country folder from app.config.ts alone', () => {
  const others = [
    `${INFO}/niord-info.config.ts`,
    `${INFO}/app.ts`,
    `${INFO}/provide-niord-info.ts`,
    `${INFO}/core/services/probe.ts`,
    `${INFO}/shared/components/probe.ts`,
    `${INFO}/i18n/probe.ts`,
    `${INFO}/routes/probe.ts`,
    `${INFO}/features/messages/probe.ts`,
    `${INFO}/testing/probe.ts`,
  ];
  for (const file of others) {
    it(`not from ${file}`, async () => {
      for (const country of ['default', 'xx']) {
        const [relative] = spellings(file, `${INFO}/country-specific/${country}/info.config`);
        await rejects(file, staticImport(relative));
      }
    });
  }

  it('app.config.ts imports country-specific/default/ and nothing else', async () => {
    const file = `${INFO}/app.config.ts`;
    await allows(file, staticImport('./country-specific/default/info.config'));
    await allows(file, staticImport('./country-specific/default/language.config'));
    await allows(file, staticImport('./country-specific/default/country.providers'));
    await allows(file, staticImport('./country-specific/default/country.routes'));
    await allows(file, dynamicImport('./country-specific/default/country.routes'));
    await rejects(file, staticImport('./country-specific/xx/info.config'));
    await rejects(file, staticImport('./country-specific/xx/language.config'));
    await rejects(file, staticImport('./country-specific/xx/country.providers'));
    await rejects(file, staticImport('./country-specific/xx/country.routes'));
  });

  it('nor dynamically, but app.config.ts with country-specific/default/', async () => {
    for (const file of [
      `${INFO}/niord-info.config.ts`,
      `${INFO}/features/messages/probe.ts`,
      `${INFO}/shared/components/probe.spec.ts`,
    ]) {
      for (const country of ['default', 'xx']) {
        const [relative] = spellings(file, `${INFO}/country-specific/${country}/country.routes`);
        await rejects(file, dynamicImport(relative));
        await rejects(file, templateImport(relative.replace(/country\.routes$/, '')));
      }
    }
    const config = `${INFO}/app.config.ts`;
    await allows(config, dynamicImport('./country-specific/default/country.routes'));
    await rejects(config, dynamicImport('./country-specific/xx/country.routes'));
    await rejects(config, templateImport('./country-specific/xx/'));
  });
});

/** The country API is one-way: base product code never imports it (fe-country-split-plan §4.3). */
describe('base product code never imports a country API', () => {
  it('from any layer of either application', async () => {
    for (const file of [
      `${ADMIN}/features/messages/probe.ts`,
      `${ADMIN}/app.config.ts`,
      `${ADMIN}/country-specific/default/probe.ts`,
      'ui-apps/admin/src/environments/probe.ts',
      `${INFO}/core/services/probe.ts`,
      `${INFO}/country-specific/default/probe.ts`,
      `${INFO}/testing/probe.ts`,
    ])
      for (const specifier of [
        '@niord/admin/api',
        '@niord/admin/api/testing',
        '@niord/info/api',
        '@niord/info/api/testing',
      ])
        await rejects(file, staticImport(specifier));
  });
});

describe('the administration app keeps its layers', () => {
  it('shared/ and core/ never import features/', async () => {
    await rejects(`${ADMIN}/shared/probe.ts`, staticImport('../features/messages/x'));
    await rejects(`${ADMIN}/core/probe.ts`, staticImport('../features/messages/x'));
  });

  it('features/messages/ is the hub, and nothing else is shared between features', async () => {
    await allows(`${ADMIN}/features/editor/probe.ts`, staticImport('../messages/x'));
    await rejects(`${ADMIN}/features/editor/probe.ts`, staticImport('../schedule/x'));
    await rejects(`${ADMIN}/features/messages/probe.ts`, staticImport('../editor/x'));
  });
});

describe('the time-zone selectors still apply where they did', () => {
  const localDate = 'export const d = new Date(2026, 0, 1);\n';

  it('in either application', async () => {
    await rejects(`${ADMIN}/features/messages/probe.ts`, localDate);
    await rejects(`${INFO}/features/messages/probe.ts`, localDate);
  });

  it('but not in the conversion layer or a spec', async () => {
    await allows(`${ADMIN}/shared/utils/zoned-date.ts`, localDate);
    await allows(`${ADMIN}/features/messages/probe.spec.ts`, localDate);
    await allows(`${INFO}/features/messages/probe.spec.ts`, localDate);
  });
});

describe('only the sanitiser and two documented exemptions mark a value safe', () => {
  const bypass = (method) =>
    `export const trust = (dom: { ${method}(value: string): unknown }) => dom.${method}('<b>');\n`;
  const methods = [
    'bypassSecurityTrustHtml',
    'bypassSecurityTrustScript',
    'bypassSecurityTrustStyle',
    'bypassSecurityTrustUrl',
    'bypassSecurityTrustResourceUrl',
  ];

  it('not in the public site, in any layer', async () => {
    for (const method of methods) {
      await rejects(`${INFO}/shared/pipes/probe.ts`, bypass(method));
    }
    await rejects(`${INFO}/features/messages/components/probe.ts`, bypass(methods[0]));
    await rejects(`${INFO}/core/services/probe.ts`, bypass(methods[0]));
  });

  it('not in the administration app outside the two exemptions', async () => {
    await rejects(`${ADMIN}/features/messages/pipes/probe.ts`, bypass(methods[0]));
    await rejects(`${ADMIN}/shared/pipes/probe.ts`, bypass(methods[0]));
  });

  it('not through destructuring either', async () => {
    await rejects(
      `${INFO}/shared/pipes/probe.ts`,
      'export const trust = ({ bypassSecurityTrustHtml }: Record<string, unknown>) => bypassSecurityTrustHtml;\n',
    );
  });

  it('not in the map kit', async () => {
    await rejects('shared-map/probe.ts', bypass(methods[0]));
  });

  it('in the sanitiser, the plain-text pipe and the sandboxed-HTML component', async () => {
    await allows('shared-html/message-html-sanitizer.ts', bypass(methods[0]));
    await allows(`${ADMIN}/shared/pipes/plain-text-to-html.pipe.ts`, bypass(methods[0]));
    await allows(`${ADMIN}/shared/components/sandboxed-html/sandboxed-html.ts`, bypass(methods[0]));
  });
});

describe('the map kit takes only the dependencies on its list', () => {
  const file = 'shared-map/probe.ts';

  it('OpenLayers by module path, Angular and rxjs', async () => {
    await allows(
      file,
      staticImport('ol/Feature') +
        staticImport('ol/geom/Point') +
        staticImport('@angular/core') +
        staticImport('rxjs') +
        staticImport('./map-shell.service'),
    );
  });

  it('never the bare OpenLayers barrel', async () => {
    await rejects(file, staticImport('ol'));
  });

  it('nothing an application owns', async () => {
    await rejects(file, staticImport('@jsverse/transloco'));
    await rejects(file, staticImport('../ui-apps/info/src/app/core/services/x'));
    await rejects(file, staticImport('../ui-apps/admin/src/app/core/config/x'));
  });
});

// Every rule this time: what is proven is that the recommended set reaches the scripts at all. The
// workspace's scripts are linted from fe/ by eslint.config.js; the kit's, beside fe/, from the
// kit's folder by its own eslint.config.mjs (npm run lint:kit), so each is proven where it runs.
const KIT = path.join(FE, '..', 'country-kit');
const scriptsEslint = new ESLint({ cwd: FE });
const kitEslint = new ESLint({ cwd: KIT });

/** The rules `code` breaks as the file `file`, relative to the folder `cwd` its lint runs in. */
async function lintedRules(linter, cwd, file, code) {
  const folder = path.posix.dirname(file);
  assert.ok(
    fs.existsSync(path.join(cwd, folder)),
    `${folder}/ no longer exists under ${cwd}: the scripts' lint is proven there — update this test`,
  );
  const [result] = await linter.lintText(code, { filePath: path.join(cwd, file) });
  const fatal = result.messages.filter((message) => message.fatal);
  assert.deepEqual(fatal, [], `${file} did not parse`);
  return result.messages.map((message) => message.ruleId ?? message.message);
}

/** The rules a workspace script breaks, `file` relative to fe/. */
const scriptRules = (file, code) => lintedRules(scriptsEslint, FE, file, code);
/** The rules a kit script breaks, `file` relative to the kit. */
const kitRules = (file, code) => lintedRules(kitEslint, KIT, file, code);

describe('the scripts are linted', () => {
  const broken = 'const a = 1;\na = 2;\ndebugger;\n';

  for (const [label, rules, file] of [
    ['country-kit/', kitRules, 'lib/probe.mjs'],
    ['', scriptRules, 'scripts/probe.mjs'],
    ['country-kit/', kitRules, 'example/probe.mjs'],
    ['country-kit/', kitRules, 'lib/probe.cjs'],
    ['', scriptRules, 'scripts/publications/probe.js'],
  ]) {
    it(`${label}${file} reports a const reassignment and a debugger`, async () => {
      const found = await rules(file, broken);
      assert.ok(found.includes('no-const-assign'), `no no-const-assign in ${file}: ${found}`);
      assert.ok(found.includes('no-debugger'), `no no-debugger in ${file}: ${found}`);
    });
  }

  it("Node's globals are declared, and CommonJS's in a CommonJS file", async () => {
    const node = "process.exitCode = 0;\nconsole.log(Buffer.from('x'), new URL('https://x'));\n";
    const commonjs = "module.exports = require('node:fs');\nconsole.log(__dirname);\n";
    assert.deepEqual(await kitRules('lib/probe.mjs', node), []);
    assert.deepEqual(await scriptRules('scripts/probe.mjs', node), []);
    assert.deepEqual(await kitRules('lib/probe.cjs', commonjs), []);
    assert.deepEqual(await scriptRules('scripts/publications/probe.js', commonjs), []);
  });

  it("the example country's own files are left to its own lint", async () => {
    assert.equal(await kitEslint.isPathIgnored(path.join(KIT, 'example/fe/x.mjs')), true);
    assert.equal(await kitEslint.isPathIgnored(path.join(KIT, 'example/be/x.mjs')), true);
  });

  it('the set-up stub keeps every rule', async () => {
    const stub = 'templates/setup.mjs';
    const rules = await kitRules(stub, broken);
    assert.ok(rules.includes('no-debugger'), `no no-debugger in the stub: ${rules}`);

    // No file of the kit is exempted from a rule: each fires in the stub and every other script.
    for (const [rule, code] of [
      ['preserve-caught-error', "try {\n  x();\n} catch (error) {\n  throw new Error('y');\n}\n"],
      ['no-control-regex', "/[\\u0000]/.test('');\n"],
    ]) {
      for (const other of [
        stub,
        'lib/probe.mjs',
        'templates/probe.mjs',
        'test-fixtures/probe.mjs',
      ]) {
        const elsewhere = await kitRules(other, code);
        assert.ok(elsewhere.includes(rule), `no ${rule} in country-kit/${other}: ${elsewhere}`);
      }
    }
  });
});
