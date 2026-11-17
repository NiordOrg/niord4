/**
 * The structural half of the base product's own guard (fe-country-split-plan §4.8): the base
 * product's tree (fe/ and the kit beside it, as the source zip names them) outside
 * country-kit/example/ holds no country tree, no pack but en, no country's pack status or font and
 * no configuration but the base product's.
 *
 *   npm run test:guards      # from fe/
 *
 * The rule reads the files git lists in fe/ and in the kit (tracked and untracked, never ignored,
 * named as the source zip names them: source-zip.mjs treeFiles) and angular.json. The source
 * zip's include list relies on it: it has no exclude for a country's folders any more (P7, D15),
 * so a country tree that came back would ship.
 * Each refusal is tested on crafted lists and on a temporary git tree; the last case runs the rule
 * over this tree.
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { CONFIGURATIONS, parseJsonc } from '../../country-kit/lib/workspace.mjs';
import { gitFiles, treeFiles } from '../../country-kit/source-zip.mjs';

const FE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

// The paths of fe/ that hold a country's files, each with why the base product never has one.
const RULES = [
  {
    path: /^languages\/(?!en\/)/,
    why: 'a pack but en; languages/ holds en only',
  },
  {
    path: /^ui-apps\/[^/]+\/src\/app\/country-specific\/(?!default\/)[^/]+\//,
    why: 'a country tree; country-specific/ holds default/ and its root files only',
  },
  {
    path: /^ui-apps\/[^/]+\/public\/assets\/(?!(?:default|img)\/)[^/]+\//,
    why: "a country's assets; public/assets/ holds no folder but default/ and img/",
  },
  {
    // <cc>, the kit's two-letter country code (lib/workspace.mjs COUNTRY_CODE); tokens.default.css
    // and the like pass.
    path: /^styles\/(?:tokens|tokens\.active|fonts)\.[a-z]{2}\.css$/,
    why: "a country's style sheet; styles/ holds the base product's tokens only",
  },
  {
    path: /^ui-apps\/[^/]+\/i18n-status\//,
    why: "a country's pack status; the base product's packs keep theirs under languages/<lang>/status/",
  },
  {
    path: /^styles\/fonts\//,
    why: "a vendored font face is a country's (§4.8)",
  },
];
const OUTSIDE = "outside the base product's set (country-kit/lib/workspace.mjs CONFIGURATIONS)";

/**
 * What in a tree breaks §4.8's structure: `files` relative to fe/ with `/`, `angular` its parsed
 * angular.json. One line per file and per configuration; an empty list when the tree passes.
 */
function baseProductTreeProblems({ files, angular }) {
  const problems = [];
  for (const file of files) {
    const rule = RULES.find(({ path: pattern }) => pattern.test(file));
    if (rule) problems.push(`${file}: ${rule.why}`);
  }
  for (const [project, { architect = {} }] of Object.entries(angular.projects ?? {}))
    for (const [target, { configurations = {}, defaultConfiguration }] of Object.entries(
      architect,
    )) {
      for (const name of Object.keys(configurations))
        if (!CONFIGURATIONS.includes(name))
          problems.push(
            `angular.json: ${project} ${target} has the configuration "${name}", ${OUTSIDE}`,
          );
      for (const name of defaultConfiguration?.split(',') ?? [])
        if (!CONFIGURATIONS.includes(name.trim()))
          problems.push(
            `angular.json: ${project} ${target} defaults to the configuration "${name.trim()}", ${OUTSIDE}`,
          );
    }
  return problems;
}

const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

/** An angular.json with the base product's two projects and the configurations given. */
function angularWith(build = ['production', 'development'], serve = ['development']) {
  const target = (names) => ({
    builder: 'x',
    configurations: Object.fromEntries(names.map((name) => [name, {}])),
  });
  return {
    projects: {
      'niord-app': {
        architect: { build: target(build), serve: target(serve), test: { builder: 'x' } },
      },
    },
  };
}

const BASE = [
  'languages/en/pack.json',
  'languages/en/i18n/admin/en.json',
  'ui-apps/admin/src/app/country-specific/default/language.config.ts',
  'ui-apps/admin/src/app/country-specific/country.providers.spec.ts',
  'ui-apps/info/src/app/country-specific/country-index.spec.ts',
  'ui-apps/admin/public/assets/img/logo.svg',
  'ui-apps/info/public/assets/default/branding/en.json',
  'ui-apps/info/public/favicon.ico',
  'styles/tokens.default.css',
  'styles/tokens.base.css',
  'styles/tokens.active.css',
  'styles/tokens.abc.css',
  'styles/theme.css',
  'country-kit/example/fe/languages/zz/pack.json',
  'country-kit/example/fe/styles/tokens.xx.css',
];

describe('base-product-tree', () => {
  it("passes the base product's own folders, sheets and configurations", () => {
    assert.deepEqual(baseProductTreeProblems({ files: BASE, angular: angularWith() }), []);
  });

  it('fails a country tree, a pack but en, a country asset folder, sheet, pack status and font', () => {
    const country = [
      'languages/da/pack.json',
      'ui-apps/admin/src/app/country-specific/dk/x.ts',
      'ui-apps/info/public/assets/gl/x.json',
      'styles/tokens.dk.css',
      'styles/tokens.active.gl.css',
      'styles/fonts.gl.css',
      'ui-apps/info/i18n-status/branding-unaccepted.json',
      'styles/fonts/lato/lato-latin-400-normal.woff2',
    ];
    const problems = baseProductTreeProblems({
      files: [...BASE, ...country],
      angular: angularWith(),
    });
    assert.equal(problems.length, country.length, problems.join('\n'));
    for (const file of country)
      assert.ok(
        problems.some((problem) => problem.startsWith(`${file}: `)),
        `${file} passes:\n${problems.join('\n')}`,
      );
  });

  it("fails a configuration outside the generator's base set, and a default naming one", () => {
    const angular = angularWith(['production', 'development', 'dk'], ['development', 'dk']);
    angular.projects['niord-app'].architect.serve.defaultConfiguration = 'gl';
    assert.deepEqual(baseProductTreeProblems({ files: BASE, angular }), [
      'angular.json: niord-app build has the configuration "dk", outside the base product\'s set (country-kit/lib/workspace.mjs CONFIGURATIONS)',
      'angular.json: niord-app serve has the configuration "dk", outside the base product\'s set (country-kit/lib/workspace.mjs CONFIGURATIONS)',
      'angular.json: niord-app serve defaults to the configuration "gl", outside the base product\'s set (country-kit/lib/workspace.mjs CONFIGURATIONS)',
    ]);
  });

  it('reads the files git lists, an untracked one included and an ignored one never', () => {
    const fe = fs.mkdtempSync(path.join(os.tmpdir(), 'base-product-tree-'));
    temporary.push(fe);
    const files = {
      '.gitignore': 'dist/\n',
      'languages/en/pack.json': '{}\n',
      'languages/da/pack.json': '{}\n',
      'dist/languages/da/pack.json': '{}\n',
    };
    for (const [name, text] of Object.entries(files)) {
      fs.mkdirSync(path.dirname(path.join(fe, name)), { recursive: true });
      fs.writeFileSync(path.join(fe, name), text);
    }
    const git = spawnSync('git', ['init', '-q'], { cwd: fe, encoding: 'utf8' });
    assert.equal(git.status, 0, git.stderr);
    const problems = baseProductTreeProblems({ files: gitFiles(fe), angular: angularWith() });
    assert.equal(problems.length, 1, problems.join('\n'));
    assert.ok(problems[0].startsWith('languages/da/pack.json: '), problems[0]);
  });

  it('passes this tree', () => {
    const angular = parseJsonc(
      fs.readFileSync(path.join(FE, 'angular.json'), 'utf8'),
      'angular.json',
    );
    assert.ok(Object.keys(angular.projects).length >= 2);
    const files = treeFiles({ fe: FE }).map(({ name }) => name);
    assert.ok(files.includes('country-kit/README.md'), 'the kit is read with fe/');
    assert.deepEqual(baseProductTreeProblems({ files, angular }), []);
  });
});
