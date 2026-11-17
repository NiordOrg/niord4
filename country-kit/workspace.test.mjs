import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { languageConfigs, SHIMS } from './lib/language-config.mjs';
import { relocateImports } from './lib/overrides.mjs';
import {
  angularJson,
  appTsconfig,
  baseSpecCopy,
  BUILD_SCRIPTS,
  buildScripts,
  CONFIGURATIONS,
  E2E_TSCONFIG,
  e2eTsconfig,
  ESLINT_CONFIG,
  eslintConfig,
  generateWorkspace,
  KIT_PATH,
  packageJson,
  packageLock,
  parseJsonc,
  PLAYWRIGHT_CONFIG,
  styleWrapper,
  tsconfigJson,
  unlistedRootFiles,
} from './lib/workspace.mjs';
import { where } from './lib/where.mjs';

const KIT = path.dirname(fileURLToPath(import.meta.url));
const FE = where().base;
const readJsonc = (relative) => parseJsonc(fs.readFileSync(path.join(FE, relative), 'utf8'));
const WORKSPACE_FILES = JSON.parse(fs.readFileSync(path.join(KIT, 'workspace-files.json'), 'utf8'));
const SEAMS = JSON.parse(fs.readFileSync(path.join(KIT, 'seams.json'), 'utf8'));
const baseAngular = readJsonc('angular.json');

/** A country fe/ holding the files given, relative path → text. */
function country(files = {}) {
  const fe = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-workspace-'));
  for (const [relative, text] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(fe, relative)), { recursive: true });
    fs.writeFileSync(path.join(fe, relative), text);
  }
  return fe;
}

describe('workspace-files.json', () => {
  it("names every tracked root file of the base product's fe/, as made or not needed", () => {
    const result = spawnSync('git', ['ls-files'], { cwd: FE, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    const roots = [
      ...new Set(
        result.stdout
          .split('\n')
          .filter(Boolean)
          .map((file) => file.split('/')[0]),
      ),
    ];
    assert.deepEqual(unlistedRootFiles(roots, WORKSPACE_FILES), []);
  });

  it('fails a root file in neither list', () => {
    assert.deepEqual(
      unlistedRootFiles(['angular.json', 'README.md', 'new.config.js'], WORKSPACE_FILES),
      ['new.config.js'],
    );
  });

  it('lists the agent files, AGENTS.md and its CLAUDE.md importer, as not needed', () => {
    assert.deepEqual(unlistedRootFiles(['AGENTS.md', 'CLAUDE.md'], WORKSPACE_FILES), []);
  });
});

describe('the generated angular.json', () => {
  const fe = country({
    'admin/environments/environment.production.ts': 'export {}',
    'admin/environments/environment.local.ts': 'export {}',
    'info/app/info.config.ts': 'export {}',
    'info/app/index.html': '<html></html>',
    'info/app/footer/footer.component.spec.ts': '',
    'styles/tokens.xx.css': ':root {}',
    'styles/fonts.xx.css': '',
  });
  const angular = angularJson({ fe, cc: 'xx', baseAngular, seams: SEAMS });
  const admin = angular.projects['niord-app'];
  const info = angular.projects['niord-info'];

  it("keeps the two projects and the base product's own configurations, no country's", () => {
    assert.deepEqual(Object.keys(angular.projects), ['niord-app', 'niord-info']);
    assert.deepEqual(Object.keys(admin.architect.build.configurations), [
      'production',
      'development',
      'local',
      'test',
    ]);
    assert.deepEqual(Object.keys(info.architect.build.configurations), ['production', 'local']);
    assert.deepEqual(CONFIGURATIONS, ['production', 'development', 'local', 'test']);
  });

  it('drops a configuration of the base product named outside its own set', () => {
    const extra = structuredClone(baseAngular);
    const architect = extra.projects['niord-app'].architect;
    architect.build.configurations.yy = { outputHashing: 'all' };
    architect.serve.configurations.yy = { buildTarget: 'niord-app:build:yy' };
    const made = angularJson({ fe, cc: 'xx', baseAngular: extra, seams: SEAMS }).projects[
      'niord-app'
    ];
    assert.deepEqual(Object.keys(made.architect.build.configurations), [
      'production',
      'development',
      'local',
      'test',
    ]);
    assert.ok(!('yy' in made.architect.serve.configurations));
    assert.ok('development' in made.architect.serve.configurations);
  });

  it('reads every base product path from .niord/base/', () => {
    assert.equal(admin.root, '.niord/base/ui-apps/admin');
    assert.equal(admin.architect.build.options.browser, '.niord/base/ui-apps/admin/src/main.ts');
    assert.equal(
      admin.architect.build.configurations.production.serviceWorker,
      '.niord/base/ui-apps/admin/ngsw-config.json',
    );
    assert.equal(admin.architect.build.options.tsConfig, '.niord/tsconfig.admin.app.json');
  });

  it("takes the assets from the staged folder and the base product's public files, never its overlays", () => {
    assert.deepEqual(info.architect.build.options.assets, [
      {
        glob: '**/*',
        input: '.niord/base/ui-apps/info/public',
        ignore: ['assets/default/**'],
      },
      { glob: '**/*', input: '.niord/build/info', output: 'assets' },
      { glob: '*.svg', input: '.niord/base/shared-map/portrayal', output: 'assets/portrayal' },
    ]);
  });

  it("styles each application with the wrapper, the country's tokens and its font sheet", () => {
    const styles = [
      '.niord/generated/info/styles.css',
      'styles/tokens.xx.css',
      'styles/fonts.xx.css',
    ];
    assert.deepEqual(info.architect.build.options.styles, styles);
    assert.deepEqual(info.architect.build.configurations.production.styles, styles);
    const bare = angularJson({ fe: country(), cc: 'xx', baseAngular, seams: SEAMS });
    assert.deepEqual(bare.projects['niord-app'].architect.build.options.styles, [
      '.niord/generated/admin/styles.css',
      '.niord/base/styles/tokens.active.css',
    ]);
  });

  /** A project's build budgets: the options' and each configuration's own. */
  const budgetsOf = (project) => ({
    options: project.architect.build.options.budgets,
    ...Object.fromEntries(
      Object.entries(project.architect.build.configurations ?? {}).map(([name, options]) => [
        name,
        options.budgets,
      ]),
    ),
  });
  const isStyles = (budget) => budget.type === 'bundle' && budget.name === 'styles';

  it("raises each application's styles budget by the size of its font sheet", () => {
    const fonts = angularJson({
      fe: country({ 'styles/fonts.xx.css': 'a'.repeat(3586) }),
      cc: 'xx',
      baseAngular,
      seams: SEAMS,
    });
    for (const [name, raised] of [
      ['niord-info', { maximumWarning: '56.586kB', maximumError: '58.586kB' }],
      ['niord-app', { maximumWarning: '118.586kB', maximumError: '138.586kB' }],
    ]) {
      const made = budgetsOf(fonts.projects[name]);
      const base = budgetsOf(baseAngular.projects[name]);
      const styles = made.options.filter(isStyles);
      assert.equal(styles.length, 1, name);
      assert.deepEqual(styles[0], { ...base.options.find(isStyles), ...raised }, name);
      assert.deepEqual(
        made.options.filter((budget) => !isStyles(budget)),
        base.options.filter((budget) => !isStyles(budget)),
        name,
      );
      // A configuration's empty list stays empty, and one without a list gains none.
      for (const configuration of Object.keys(base).filter((key) => key !== 'options'))
        assert.deepEqual(made[configuration], base[configuration], `${name} ${configuration}`);
    }
    assert.deepEqual(budgetsOf(fonts.projects['niord-info']).local, []);
  });

  it('leaves the budgets as the base product writes them without a font sheet, or with an empty one', () => {
    const bare = angularJson({ fe: country(), cc: 'xx', baseAngular, seams: SEAMS });
    for (const name of ['niord-app', 'niord-info']) {
      assert.deepEqual(budgetsOf(bare.projects[name]), budgetsOf(baseAngular.projects[name]));
      assert.deepEqual(
        budgetsOf(angular.projects[name]),
        budgetsOf(baseAngular.projects[name]),
        `${name}, with the fixture's empty styles/fonts.xx.css`,
      );
    }
  });

  it("holds the base product's styles budgets to absolute sizes, which the font allowance raises", () => {
    for (const name of ['niord-app', 'niord-info']) {
      const styles = baseAngular.projects[name].architect.build.options.budgets.filter(isStyles);
      assert.equal(styles.length, 1, name);
      assert.equal(styles[0].baseline, undefined, name);
      for (const key of ['maximumWarning', 'maximumError'])
        assert.match(styles[0][key], /^\d+(?:\.\d+)?\s*[kmg]?b$/i, `${name} ${key}`);
    }
  });

  it('replaces a seam where the country has its file, in the configurations the seam names', () => {
    const language = {
      replace: '.niord/base/ui-apps/admin/src/app/country-specific/default/language.config.ts',
      with: '.niord/generated/admin/language.config.ts',
    };
    const env = (name) => ({
      replace: '.niord/base/ui-apps/admin/src/environments/environment.ts',
      with: name,
    });
    const build = admin.architect.build.configurations;
    assert.deepEqual(build.production.fileReplacements, [
      env('admin/environments/environment.production.ts'),
      language,
    ]);
    assert.deepEqual(build.local.fileReplacements, [
      env('admin/environments/environment.local.ts'),
      language,
    ]);
    assert.deepEqual(build.test.fileReplacements, [
      env('.niord/base/ui-apps/admin/src/environments/environment.test.ts'),
      language,
    ]);
    assert.equal(info.architect.build.options.index, 'info/app/index.html');
    assert.deepEqual(info.architect.build.configurations.local.fileReplacements[0], {
      replace: '.niord/base/ui-apps/info/src/app/country-specific/default/info.config.ts',
      with: 'info/app/info.config.ts',
    });
  });

  it("serves through the proxy, tests the country's specs and lints the country's files", () => {
    assert.equal(admin.architect.serve.options.proxyConfig, 'proxy.conf.mjs');
    assert.deepEqual(Object.keys(info.architect.serve.configurations), ['production', 'local']);
    assert.deepEqual(info.architect.test.options.include, ['../../../../../info/**/*.spec.ts']);
    assert.deepEqual(info.architect.test.options.setupFiles, [
      '.niord/generated/info/test-setup.ts',
    ]);
    assert.equal(info.architect.test.options.runnerConfig, 'vitest.config.ts');
    assert.deepEqual(admin.architect.lint.options.lintFilePatterns, [
      'admin/**/*.ts',
      'admin/app/**/*.html',
      'admin/overrides/**/*.html',
      'e2e/**/*.ts',
    ]);
    assert.deepEqual(info.architect.lint.options.lintFilePatterns, [
      'info/**/*.ts',
      'info/app/**/*.html',
      'info/overrides/**/*.html',
    ]);
  });

  // The workspace runs a copy of each base product test set-up file from .niord/generated/<app>/
  // (setupCopy), where a relative import would resolve to nothing: every country's `npm test`
  // would break while every base product check stayed green. So the base files import packages only.
  const relativeSpecifiers = (text) =>
    [...text.matchAll(/(?:\bfrom|\bimport|\brequire)\s*\(?\s*['"]([^'"]+)['"]/g)]
      .map((match) => match[1])
      .filter((specifier) => /^[./]/.test(specifier));

  it('copies base product test set-up files that import packages only, never a relative path', () => {
    const setupFiles = Object.values(baseAngular.projects).flatMap(
      (project) => project.architect.test?.options?.setupFiles ?? [],
    );
    assert.ok(setupFiles.length > 0, 'the base angular.json names no test set-up file');
    for (const file of setupFiles)
      assert.deepEqual(
        relativeSpecifiers(fs.readFileSync(path.join(FE, file), 'utf8')),
        [],
        `${file} imports a relative path, which its copy in .niord/generated/ cannot resolve`,
      );
  });

  it('finds a relative import in a set-up file in every spelling', () => {
    const text = [
      "import { getTestBed } from '@angular/core/testing';",
      "import './polyfills';",
      'import {\n  a,\n} from "../shared/a";',
      "export * from '/abs/b';",
      "const c = await import('./c');",
    ].join('\n');
    assert.deepEqual(relativeSpecifiers(text), ['./polyfills', '../shared/a', '/abs/b', './c']);
  });

  it('refuses a country code that is not one', () => {
    assert.throws(
      () => angularJson({ fe, cc: '../x', baseAngular, seams: SEAMS }),
      /not a country code/,
    );
    assert.throws(
      () => angularJson({ fe, cc: 'xxx', baseAngular, seams: SEAMS }),
      /"xxx" is not a country code, its ISO 3166-1 alpha-2 code, two letters a-z/,
    );
  });
});

describe('the generated package files and TypeScript configurations', () => {
  const basePackage = JSON.parse(fs.readFileSync(path.join(FE, 'package.json'), 'utf8'));

  it('refuses a three-letter country code', () => {
    const fe = country({ 'info/app/x.spec.ts': '' });
    assert.throws(
      () => packageJson({ fe, cc: 'xxx', basePackage }),
      /"xxx" is not a country code, its ISO 3166-1 alpha-2 code, two letters a-z/,
    );
  });

  it("names the workspace for the country, with the base product's dependencies and the kit's scripts", () => {
    const fe = country({
      'info/app/footer/footer.component.spec.ts': '',
      'admin/environments/environment.production.ts': '',
    });
    const pkg = packageJson({ fe, cc: 'xx', basePackage });
    assert.equal(pkg.name, 'niord-xx-fe');
    assert.deepEqual(pkg.dependencies, basePackage.dependencies);
    assert.deepEqual(pkg.devDependencies, basePackage.devDependencies);
    assert.deepEqual(pkg.overrides, basePackage.overrides);
    assert.equal(
      pkg.scripts.build,
      'node ../.niord/kit/guard-build.mjs && node ../.niord/kit/audit.mjs && ng build niord-app && ng build niord-info --stats-json && node ../.niord/kit/check-build.mjs',
    );
    assert.equal(
      pkg.scripts['bundle:info'],
      'node .niord/base/scripts/info-bundle-report.mjs --root .niord/base/ --dist dist/niord-info/browser --stats dist/niord-info/browser-stats.json --baseline info/bundle-baseline.json --json dist/niord-info-bundle-report.json --md dist/niord-info-bundle-report.md',
    );
    assert.equal(pkg.scripts['image:info'], 'node ../.niord/kit/image-info.mjs');
    assert.equal(pkg.scripts.audit, 'node ../.niord/kit/audit.mjs');
    assert.equal(pkg.scripts['start:info'], 'ng serve niord-info --port 4400');
    assert.equal(pkg.scripts.e2e, 'playwright test --pass-with-no-tests');
    assert.equal(pkg.scripts.test, 'ng test niord-info --watch=false');
    assert.equal(
      pkg.scripts.lint,
      'ng lint niord-app --max-warnings 0 && ng lint niord-info --max-warnings 0',
    );
    assert.equal(pkg.scripts.prepare, undefined);
    assert.equal(pkg['lint-staged'], undefined);
  });

  it('lints the administration app of a country whose only code is its e2e specs', () => {
    const fe = country({ 'e2e/x.spec.ts': '' });
    const scripts = packageJson({ fe, cc: 'xx', basePackage }).scripts;
    assert.equal(scripts.lint, 'ng lint niord-app --max-warnings 0');
    assert.equal(scripts.test, `node -e "console.log('no country specs')"`);
    assert.equal(
      packageJson({ fe: country({ 'e2e/README.md': '' }), cc: 'xx', basePackage }).scripts.lint,
      `node -e "console.log('no country code')"`,
    );
  });

  it("writes no manifest value into a script: two countries' scripts are the same", () => {
    const fe = country({ 'info/app/x.spec.ts': '', 'admin/app/y.ts': '' });
    const scripts = packageJson({ fe, cc: 'qq', basePackage }).scripts;
    for (const [name, text] of Object.entries(scripts))
      assert.doesNotMatch(text, /\bqq\b/, `${name} carries the country code`);
    const other = packageJson({ fe, cc: 'zy', basePackage }).scripts;
    assert.deepEqual(other, scripts);
  });

  it("renames the lockfile's root and changes nothing else", () => {
    const baseLock = {
      name: 'niord-app',
      lockfileVersion: 3,
      packages: { '': { name: 'niord-app', version: '0.0.0' }, 'node_modules/a': {} },
    };
    const lock = packageLock({ cc: 'xx', baseLock });
    assert.equal(lock.name, 'niord-xx-fe');
    assert.deepEqual(lock.packages[''], { name: 'niord-xx-fe', version: '0.0.0' });
    assert.deepEqual(lock.packages['node_modules/a'], {});
    assert.equal(baseLock.name, 'niord-app');
  });

  const ROOT_PATHS = {
    '@niord/map/*': ['./.niord/base/shared-map/*'],
    '@niord/html/*': ['./.niord/base/shared-html/*'],
    '@niord/admin/api': ['./.niord/base/ui-apps/admin/src/public-api.ts'],
    '@niord/admin/api/testing': ['./.niord/base/ui-apps/admin/src/testing-api.ts'],
    '@niord/info/api': ['./.niord/base/ui-apps/info/src/public-api.ts'],
    '@niord/info/api/testing': ['./.niord/base/ui-apps/info/src/testing-api.ts'],
  };

  it("moves the base product's aliases and the country API into .niord/base/, and nothing internal", () => {
    const tsconfig = tsconfigJson({ baseTsconfig: readJsonc('tsconfig.json') });
    assert.deepEqual(tsconfig.compilerOptions.paths, ROOT_PATHS);
    assert.equal(tsconfig.references.length, 4);
  });

  it('maps internal/* nowhere for a country without overrides', () => {
    for (const app of ['admin', 'info'])
      for (const kind of ['app', 'spec']) {
        const config = appTsconfig({
          app,
          spec: kind === 'spec',
          rootPaths: ROOT_PATHS,
          baseConfig: readJsonc(`ui-apps/${app}/tsconfig.${kind}.json`),
        });
        assert.equal(config.compilerOptions.paths, undefined, `${app} ${kind}`);
      }
  });

  it('maps internal/* in the tsconfigs of the application a country overrides, with every root path re-based', () => {
    const config = appTsconfig({
      app: 'admin',
      spec: false,
      internal: true,
      rootPaths: ROOT_PATHS,
      baseConfig: readJsonc('ui-apps/admin/tsconfig.app.json'),
    });
    assert.deepEqual(config.compilerOptions.paths, {
      '@niord/map/*': ['./base/shared-map/*'],
      '@niord/html/*': ['./base/shared-html/*'],
      '@niord/admin/api': ['./base/ui-apps/admin/src/public-api.ts'],
      '@niord/admin/api/testing': ['./base/ui-apps/admin/src/testing-api.ts'],
      '@niord/info/api': ['./base/ui-apps/info/src/public-api.ts'],
      '@niord/info/api/testing': ['./base/ui-apps/info/src/testing-api.ts'],
      '@niord/admin/internal/*': ['./base/ui-apps/admin/src/app/*'],
    });
    assert.ok(!JSON.stringify(config).includes('@niord/info/internal'));
  });

  it('keeps the testing entry points out of the application program and in the spec one', () => {
    const app = appTsconfig({
      app: 'admin',
      spec: false,
      baseConfig: readJsonc('ui-apps/admin/tsconfig.app.json'),
    });
    const spec = appTsconfig({
      app: 'admin',
      spec: true,
      baseConfig: readJsonc('ui-apps/admin/tsconfig.spec.json'),
    });
    assert.ok(app.exclude.includes('base/ui-apps/admin/src/testing-api.ts'));
    assert.ok(spec.include.includes('base/ui-apps/admin/src/testing-api.ts'));
  });

  it("runs the country's e2e specs with the base product's Playwright options and login fixtures, from constant text", () => {
    assert.match(
      PLAYWRIGHT_CONFIG,
      /^import base from '\.\/\.niord\/base\/playwright\.config';$/m,
      "the base product's configuration is imported",
    );
    assert.match(PLAYWRIGHT_CONFIG, /^ {2}\.\.\.base,$/m);
    assert.match(PLAYWRIGHT_CONFIG, /^ {2}testDir: '\.\/e2e',$/m);
    assert.match(PLAYWRIGHT_CONFIG, /^ {2}tsconfig: '\.\/\.niord\/tsconfig\.e2e\.json',$/m);
    assert.ok(PLAYWRIGHT_CONFIG.indexOf('...base,') < PLAYWRIGHT_CONFIG.indexOf('testDir'));
    assert.equal(E2E_TSCONFIG, '.niord/tsconfig.e2e.json');
    assert.deepEqual(e2eTsconfig().compilerOptions.paths, {
      '@niord/admin/e2e': ['./base/ui-apps/admin/e2e/auth/fixtures.ts'],
    });
    // The lint's program too: the workspace's options, the specs, and the fixtures' alias alone.
    assert.deepEqual(e2eTsconfig(), {
      extends: '../tsconfig.json',
      compilerOptions: {
        types: [],
        paths: { '@niord/admin/e2e': ['./base/ui-apps/admin/e2e/auth/fixtures.ts'] },
      },
      include: ['../e2e/**/*.ts'],
    });
    // The alias reaches a file the base product has, from where the tsconfig is written.
    assert.ok(fs.existsSync(path.join(FE, 'ui-apps', 'admin', 'e2e', 'auth', 'fixtures.ts')));
  });

  /**
   * A workspace generated for `cc` from the base product's own files, with `options` passed on to
   * generateWorkspace: the files it names, a few of them read, and every file's bytes.
   */
  const generate = (cc, options = {}) => {
    const fe = country({ 'info/app/x.spec.ts': '' });
    fs.mkdirSync(path.join(fe, '.niord', 'base'), { recursive: true });
    for (const name of ['angular.json', 'package.json', 'package-lock.json', 'tsconfig.json'])
      fs.copyFileSync(path.join(FE, name), path.join(fe, '.niord', 'base', name));
    for (const name of ['.npmrc', '.nvmrc', '.postcssrc.json'])
      fs.copyFileSync(path.join(FE, name), path.join(fe, '.niord', 'base', name));
    for (const app of ['admin', 'info'])
      for (const kind of ['app', 'spec']) {
        const file = path.join('ui-apps', app, `tsconfig.${kind}.json`);
        fs.mkdirSync(path.dirname(path.join(fe, '.niord', 'base', file)), { recursive: true });
        fs.copyFileSync(path.join(FE, file), path.join(fe, '.niord', 'base', file));
      }
    for (const [, project] of Object.entries(baseAngular.projects))
      for (const file of project.architect.test?.options?.setupFiles ?? []) {
        fs.mkdirSync(path.dirname(path.join(fe, '.niord', 'base', file)), { recursive: true });
        fs.copyFileSync(path.join(FE, file), path.join(fe, '.niord', 'base', file));
      }
    const languages = { admin: {}, info: {} };
    const { files } = generateWorkspace({ fe, cc, seams: SEAMS, languages, ...options });
    const read = (relative) => fs.readFileSync(path.join(fe, relative), 'utf8');
    const out = {
      files,
      config: read('playwright.config.ts'),
      tsconfig: read('.niord/tsconfig.e2e.json'),
      e2e: JSON.parse(read('package.json')).scripts.e2e,
      bytes: Object.fromEntries(files.map((relative) => [relative, read(relative)])),
    };
    fs.rmSync(fe, { recursive: true, force: true });
    return out;
  };

  it('gives two countries the same Playwright configuration and alias', () => {
    const one = generate('qq');
    const two = generate('zy');
    assert.ok(one.files.includes('playwright.config.ts') && one.files.includes(E2E_TSCONFIG));
    assert.equal(one.config, PLAYWRIGHT_CONFIG);
    assert.deepEqual(JSON.parse(one.tsconfig), e2eTsconfig());
    assert.equal(one.e2e, 'playwright test --pass-with-no-tests');
    assert.deepEqual(
      { config: two.config, tsconfig: two.tsconfig, e2e: two.e2e },
      { config: one.config, tsconfig: one.tsconfig, e2e: one.e2e },
    );
    assert.doesNotMatch(one.config + one.tsconfig, /\bqq\b/);
  });

  it("lints with the base product's configuration, then the country blocks, from constant text", () => {
    assert.ok(
      ESLINT_CONFIG.includes(
        "\nconst { countryBlocks } = require('../.niord/kit/lib/country-lint.cjs');\n",
      ),
    );
    assert.ok(ESLINT_CONFIG.endsWith('  ...countryBlocks({ dir: __dirname }),\n];\n'));
    assert.ok(ESLINT_CONFIG.indexOf('...base,') < ESLINT_CONFIG.indexOf('...countryBlocks('));
  });

  it("lints the e2e specs with their own program, between the base product's blocks and the country's", () => {
    const block =
      "  {\n    files: ['e2e/**/*.ts'],\n    languageOptions: {\n      parserOptions: {\n        projectService: false,\n        project: './.niord/tsconfig.e2e.json',\n        tsconfigRootDir: __dirname,\n      },\n    },\n  },\n";
    assert.ok(ESLINT_CONFIG.includes(block), ESLINT_CONFIG);
    const at = ESLINT_CONFIG.indexOf(block);
    assert.ok(ESLINT_CONFIG.indexOf('...base,') < at);
    assert.ok(at < ESLINT_CONFIG.indexOf('...countryBlocks('));
    assert.ok(ESLINT_CONFIG.includes(`project: './${E2E_TSCONFIG}'`));
  });

  it("names the kit package's folder at the repository root by default", () => {
    assert.equal(KIT_PATH, '../.niord/kit');
    assert.deepEqual(buildScripts(), BUILD_SCRIPTS);
    assert.deepEqual(buildScripts(KIT_PATH), BUILD_SCRIPTS);
    assert.equal(eslintConfig(), ESLINT_CONFIG);
    assert.equal(eslintConfig(KIT_PATH), ESLINT_CONFIG);
  });

  it("runs the kit's commands from the kit's folder it is given", () => {
    const scripts = packageJson({
      fe: country(),
      cc: 'xx',
      basePackage,
      kitPath: 'tools/niord-kit',
    }).scripts;
    assert.equal(
      scripts.build,
      'node tools/niord-kit/guard-build.mjs && node tools/niord-kit/audit.mjs && ng build niord-app && ng build niord-info --stats-json && node tools/niord-kit/check-build.mjs',
    );
    assert.equal(scripts['image:info'], 'node tools/niord-kit/image-info.mjs');
    assert.equal(scripts.audit, 'node tools/niord-kit/audit.mjs');
    // The bundle report is the base product's own script, not the kit's.
    assert.equal(scripts['bundle:info'], BUILD_SCRIPTS['bundle:info']);
  });

  it("requires the country blocks from the kit's folder it is given, and changes no other line", () => {
    const config = eslintConfig('tools/niord-kit');
    assert.ok(
      config.includes(
        "\nconst { countryBlocks } = require('./tools/niord-kit/lib/country-lint.cjs');\n",
      ),
    );
    const lines = config.split('\n');
    const defaults = ESLINT_CONFIG.split('\n');
    assert.equal(lines.length, defaults.length);
    const changed = lines.flatMap((line, at) => (line === defaults[at] ? [] : [at]));
    assert.deepEqual(
      changed.map((at) => defaults[at]),
      ["const { countryBlocks } = require('../.niord/kit/lib/country-lint.cjs');"],
    );
  });

  it('generates the same bytes without a kit path as with the default, and only package.json and eslint.config.js differ with another', () => {
    const bare = generate('qq');
    assert.deepEqual(generate('qq', { kitPath: KIT_PATH }).bytes, bare.bytes);
    const moved = generate('qq', { kitPath: 'tools/niord-kit' });
    assert.deepEqual(Object.keys(moved.bytes), Object.keys(bare.bytes));
    assert.deepEqual(
      Object.keys(bare.bytes).filter((relative) => moved.bytes[relative] !== bare.bytes[relative]),
      ['package.json', 'eslint.config.js'],
    );
    assert.equal(moved.bytes['eslint.config.js'], eslintConfig('tools/niord-kit'));
    assert.equal(
      JSON.parse(moved.bytes['package.json']).scripts.build,
      buildScripts('tools/niord-kit').build,
    );
  });

  it('refuses a kit path that is not a plain relative path, since it is written into scripts and a require', () => {
    const fe = country();
    for (const kitPath of [
      '',
      '/abs/kit',
      'C:/kit',
      '..\\.niord\\kit',
      "../.niord/kit'",
      '"kit',
      '../my kit',
      'kit && rm -rf x',
      'kit;x',
      '../.niord/../kit',
      './kit',
      'kit/./lib',
      'kit/',
      '..',
    ]) {
      const refused = { message: /is not a relative path of the kit/ };
      assert.throws(() => packageJson({ fe, cc: 'xx', basePackage, kitPath }), refused, kitPath);
      assert.throws(() => buildScripts(kitPath), refused, kitPath);
      assert.throws(() => eslintConfig(kitPath), refused, kitPath);
    }
    for (const kitPath of ['tools/niord-kit', '../.niord/kit', 'kit', '../../a_b/c-d.e'])
      assert.doesNotThrow(() => eslintConfig(kitPath), kitPath);
  });

  it("compiles the base product's sources with the country's folder, and specs only the country's", () => {
    const app = appTsconfig({
      app: 'info',
      spec: false,
      baseConfig: readJsonc('ui-apps/info/tsconfig.app.json'),
    });
    assert.ok(app.include.includes('base/ui-apps/info/src/**/*.ts'));
    assert.ok(app.include.includes('base/shared-map/**/*.ts'));
    assert.ok(app.include.includes('../info/**/*.ts'));
    assert.ok(app.include.includes('generated/info/**/*.ts'));
    assert.ok(app.exclude.includes('../info/**/*.spec.ts'));
    const spec = appTsconfig({
      app: 'info',
      spec: true,
      baseConfig: readJsonc('ui-apps/info/tsconfig.spec.json'),
    });
    assert.ok(
      !spec.include.some((pattern) => pattern.startsWith('base/') && pattern.endsWith('.spec.ts')),
    );
    assert.ok(spec.include.includes('base/ui-apps/info/src/test-setup.ts'));
    assert.equal(spec.compilerOptions.outDir, '../out-tsc/info-spec');
  });

  it('reads JSON with comments and trailing commas, strings left alone', () => {
    assert.deepEqual(parseJsonc('{ // a\n "a": "x // y", /* b */ "b": [1,], }'), {
      a: 'x // y',
      b: [1],
    });
  });

  it("wraps each application's entry stylesheet with the country's folder in the utility scan", () => {
    assert.match(
      styleWrapper('info'),
      /@import '\.\.\/\.\.\/base\/ui-apps\/info\/src\/styles\.css';\n@source '\.\.\/\.\.\/\.\.\/info';/,
    );
  });
});

describe('the generated language configuration', () => {
  const packs = {
    en: { label: 'English', locale: 'en-GB' },
    zz: { label: 'Zedish', locale: 'zz' },
    kl: { label: 'Kalaallisut', locale: 'kl' },
  };
  const manifest = {
    apps: {
      admin: {
        uiLanguages: ['en', 'zz'],
        contentLanguages: ['zz', 'en', 'kl'],
        defaultLanguage: 'en',
      },
      info: { uiLanguages: ['zz'], defaultLanguage: 'zz' },
    },
    locales: { zz: 'en-GB' },
  };

  it("lists each application's languages from the manifest and pack.json, as data", () => {
    const { admin, info } = languageConfigs({
      manifest,
      packs,
      branding: { admin: ['en'], info: ['zz'] },
    });
    assert.deepEqual(
      admin.availableLanguages.map((option) => option.value),
      ['zz', 'en', 'kl'],
    );
    assert.deepEqual(admin.availableLanguages[0], {
      value: 'zz',
      label: 'Zedish',
      locale: 'en-GB',
      flagPath: 'assets/img/flags/zz.png',
    });
    assert.deepEqual(admin.uiLanguages, ['en', 'zz']);
    assert.deepEqual(admin.modelLanguages, ['zz', 'en', 'kl']);
    assert.deepEqual(admin.brandingLanguages, ['en']);
    assert.deepEqual(info, {
      languages: [
        { value: 'zz', label: 'Zedish', locale: 'en-GB', flagPath: 'assets/img/flags/zz.png' },
      ],
      siteLanguages: ['zz'],
      defaultLanguage: 'zz',
      brandingLanguages: ['zz'],
    });
  });

  it('imports the JSON from constant TypeScript', () => {
    for (const shim of Object.values(SHIMS))
      assert.match(shim, /import languages from '\.\/language\.config\.json';/);
  });
});

describe('a guarded override in the generated workspace', () => {
  const file =
    'ui-apps/admin/src/app/features/messages/components/message-selection-toggle/message-selection-toggle.ts';
  const spec = file.replace(/\.ts$/, '.spec.ts');
  const overrides = [
    { file, with: 'admin/overrides/features/messages/components/x/message-selection-toggle.ts' },
  ];
  const replacement = { replace: `.niord/base/${file}`, with: overrides[0].with };
  const fe = country({ 'info/app/footer/footer.component.spec.ts': '' });
  const angular = angularJson({
    fe,
    cc: 'xx',
    baseAngular,
    seams: SEAMS,
    overrides,
    baseSpecs: [spec],
  });
  const admin = angular.projects['niord-app'];

  it('replaces the file in the options and in every configuration, whose list replaces the options', () => {
    assert.ok(
      admin.architect.build.options.fileReplacements.some(
        (entry) => entry.replace === replacement.replace && entry.with === replacement.with,
      ),
    );
    for (const [name, configuration] of Object.entries(admin.architect.build.configurations))
      assert.ok(
        configuration.fileReplacements.some(
          (entry) => entry.replace === replacement.replace && entry.with === replacement.with,
        ),
        `${name} lacks the override`,
      );
    assert.doesNotMatch(
      JSON.stringify(angular.projects['niord-info'].architect.build),
      /message-selection-toggle/,
    );
  });

  it("runs a copy of the base product's spec of the file, compiled with the spec configuration only", () => {
    const copy = baseSpecCopy(spec);
    assert.equal(
      copy,
      '.niord/generated/admin/base-specs/features/messages/components/message-selection-toggle/message-selection-toggle.spec.ts',
    );
    assert.deepEqual(admin.architect.test.options.include, [
      '../../../../../admin/**/*.spec.ts',
      '../../../../generated/admin/base-specs/features/messages/components/message-selection-toggle/message-selection-toggle.spec.ts',
    ]);
    const config = (kind) =>
      appTsconfig({
        app: 'admin',
        spec: kind === 'spec',
        baseConfig: readJsonc(`ui-apps/admin/tsconfig.${kind}.json`),
      });
    assert.ok(config('spec').include.includes('generated/admin/**/*.ts'));
    assert.ok(config('app').exclude.includes('generated/admin/**/*.spec.ts'));
  });

  it("points the copy's relative imports back at the same files in .niord/base/", () => {
    assert.equal(
      relocateImports(
        "import { T } from './message-selection-toggle';\nimport { S } from '../../services/s';\n",
        { from: `.niord/base/${spec}`, to: baseSpecCopy(spec) },
      ),
      "import { T } from '../../../../../../../base/ui-apps/admin/src/app/features/messages/components/message-selection-toggle/message-selection-toggle';\n" +
        "import { S } from '../../../../../../../base/ui-apps/admin/src/app/features/messages/services/s';\n",
    );
  });

  it("lints the overrides' templates, never the legal documents", () => {
    assert.ok(admin.architect.lint.options.lintFilePatterns.includes('admin/overrides/**/*.html'));
    assert.ok(!admin.architect.lint.options.lintFilePatterns.includes('admin/**/*.html'));
  });

  it("tests an application whose only spec is the base product's spec of an override", () => {
    const basePackage = JSON.parse(fs.readFileSync(path.join(FE, 'package.json'), 'utf8'));
    const bare = country({ 'admin/overrides/features/x.ts': '' });
    assert.equal(
      packageJson({ fe: bare, cc: 'xx', basePackage }).scripts.test,
      `node -e "console.log('no country specs')"`,
    );
    assert.equal(
      packageJson({ fe: bare, cc: 'xx', basePackage, baseSpecs: [spec] }).scripts.test,
      'ng test niord-app --watch=false',
    );
  });
});
