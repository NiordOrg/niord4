/**
 * Tests for country-kit/seams.json, the seam catalogue and the protected list.
 *
 *   npm run test:guards      # from fe/
 *
 * The catalogue names base product files by path, and a rename in the base product leaves it
 * pointing at nothing: a seam whose default is gone replaces nothing, and a protected glob that
 * matches no file protects nothing — both silently. So the catalogue is held to the tree here:
 * every seam's default and replaced file exists, its configurations are the base product's own,
 * its id is unique and its country file lies in its application's folder; every protected glob
 * matches a file git knows. The extension points likewise: each hook's file exports its token;
 * each slot is named by its registry's `SlotName` and placed in its template; every slot a base
 * template places is catalogued, and every name a registry offers. And a country can reach them:
 * each application's country API exports its hooks' tokens and its slot registry, and the base
 * product's tsconfig.json maps both APIs. A crafted catalogue or API shows each rule fails.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it } from 'node:test';
import { readAngularJson } from '../fe/scripts/workspace-paths-check.mjs';
import { globToRegExp } from './lib/include.mjs';
import { where as kitLayout } from './lib/where.mjs';
import { treeFiles } from './source-zip.mjs';

const KIT = path.dirname(fileURLToPath(import.meta.url));
const FE = kitLayout().base;
const APPS = { admin: 'niord-app', info: 'niord-info' };
const OPTIONS = ['index', 'styles'];
const SEAM_FIELDS = new Set([
  'id',
  'app',
  'country',
  'base',
  'replaces',
  'option',
  'configurations',
  'description',
]);

const HOOK_FIELDS = new Set(['id', 'app', 'token', 'file', 'description']);
const SLOT_FIELDS = new Set(['id', 'app', 'template', 'registry', 'description']);
// A slot placed in a template: `<app-slot name="home.welcome" />`.
const PLACED_SLOT = /<app-slot\s+name="([\w.-]+)"/g;

const readCatalogue = () => JSON.parse(fs.readFileSync(path.join(KIT, 'seams.json'), 'utf8'));

/** The names a slot registry's `SlotName` union lists. */
const slotNames = (text) =>
  [...(/export type SlotName\s*=\s*([^;]+);/.exec(text)?.[1].matchAll(/'([^']+)'/g) ?? [])].map(
    (match) => match[1],
  );

/**
 * What is wrong with the catalogue's hooks and slots against a tree: `files` its files relative to
 * fe/, `read` a file's text.
 */
function extensionProblems(catalogue, { files, read }) {
  const problems = [];
  const exists = new Set(files);
  const check = (kind, entry, fields) => {
    const name = `${kind} ${entry.id ?? '(no id)'}`;
    for (const field of Object.keys(entry))
      if (!fields.has(field)) problems.push(`${name}: unknown field ${field}`);
    if (!Object.hasOwn(APPS, entry.app)) problems.push(`${name}: no application "${entry.app}"`);
    return name;
  };
  for (const hook of catalogue.hooks ?? []) {
    const name = check('hook', hook, HOOK_FIELDS);
    if (!exists.has(hook.file)) problems.push(`${name}: its file ${hook.file} does not exist`);
    else if (!new RegExp(`\\bexport const ${hook.token}\\b`).test(read(hook.file)))
      problems.push(`${name}: ${hook.file} does not export ${hook.token}`);
  }
  const catalogued = Object.fromEntries(Object.keys(APPS).map((app) => [app, new Set()]));
  const registries = Object.fromEntries(Object.keys(APPS).map((app) => [app, new Set()]));
  for (const slot of catalogue.slots ?? []) {
    const name = check('slot', slot, SLOT_FIELDS);
    catalogued[slot.app]?.add(slot.id);
    if (!exists.has(slot.template))
      problems.push(`${name}: its template ${slot.template} does not exist`);
    else if (![...read(slot.template).matchAll(PLACED_SLOT)].some((match) => match[1] === slot.id))
      problems.push(
        `${name}: its template ${slot.template} places no <app-slot name="${slot.id}">`,
      );
    if (!exists.has(slot.registry))
      problems.push(`${name}: its registry ${slot.registry} does not exist`);
    else {
      registries[slot.app]?.add(slot.registry);
      if (!slotNames(read(slot.registry)).includes(slot.id))
        problems.push(`${name}: ${slot.registry}'s SlotName does not name it`);
    }
  }
  for (const app of Object.keys(APPS)) {
    for (const file of files) {
      if (!file.startsWith(`ui-apps/${app}/src/`) || !/\.(html|ts)$/.test(file)) continue;
      if (file.endsWith('.spec.ts')) continue;
      for (const match of read(file).matchAll(PLACED_SLOT))
        if (!catalogued[app].has(match[1]))
          problems.push(`${file} places the slot ${match[1]}, which seams.json does not catalogue`);
    }
    for (const registry of registries[app])
      for (const id of slotNames(read(registry)))
        if (!catalogued[app].has(id))
          problems.push(`${registry}'s SlotName names ${id}, which seams.json does not catalogue`);
  }
  return problems;
}

/** The names a country API file exports through its `export { … } from` statements, types included. */
const apiExports = (text) =>
  [...text.matchAll(/export\s+(?:type\s+)?\{([^}]*)\}\s*from/g)].flatMap((match) =>
    match[1]
      .split(',')
      .map((name) => name.trim().replace(/^type\s+/, ''))
      .filter(Boolean)
      .map((name) => name.split(/\s+as\s+/).pop()),
  );

/**
 * What a country cannot reach through its application's API (src/public-api.ts): every hook's
 * token, and the slot registry of every application that has a slot. `read` gives a file's text.
 */
function apiProblems(catalogue, read) {
  const problems = [];
  for (const app of Object.keys(APPS)) {
    const file = `ui-apps/${app}/src/public-api.ts`;
    const needed = (catalogue.hooks ?? [])
      .filter((hook) => hook.app === app)
      .map((hook) => hook.token);
    if ((catalogue.slots ?? []).some((slot) => slot.app === app))
      needed.push('COMPONENT_SLOTS', 'provideSlot');
    const exported = new Set(apiExports(read(file)));
    for (const name of needed)
      if (!exported.has(name)) problems.push(`${file} does not export ${name}`);
  }
  return problems;
}

/**
 * What is wrong with a seam catalogue against a tree: `files` are the tree's files relative to
 * fe/, `configurations` each application's build configuration names.
 */
function catalogueProblems(catalogue, { files, configurations }) {
  const problems = [];
  const exists = new Set(files);
  if (catalogue.format !== 1) problems.push('format is not 1');
  const ids = new Set();
  const entries = [
    ...(catalogue.seams ?? []).map((seam) => ['seam', seam]),
    ...(catalogue.generated ?? []).map((seam) => ['generated', seam]),
  ];
  for (const [kind, seam] of entries) {
    const name = `${kind} ${seam.id ?? '(no id)'}`;
    for (const field of Object.keys(seam))
      if (!SEAM_FIELDS.has(field)) problems.push(`${name}: unknown field ${field}`);
    if (!seam.id) problems.push(`${name}: no id`);
    else if (ids.has(seam.id)) problems.push(`${name}: the id is given twice`);
    ids.add(seam.id);
    if (!Object.hasOwn(APPS, seam.app)) problems.push(`${name}: no application "${seam.app}"`);
    if (kind === 'seam') {
      if (typeof seam.country !== 'string' || !seam.country.startsWith(`${seam.app}/`))
        problems.push(`${name}: its country file ${seam.country} is not under ${seam.app}/`);
      if (!exists.has(seam.base)) problems.push(`${name}: its default ${seam.base} does not exist`);
    } else if (seam.country !== undefined || seam.base !== undefined)
      problems.push(`${name}: a generated seam has no country file and no default of its own`);
    if ((seam.replaces === undefined) === (seam.option === undefined) && kind === 'seam')
      problems.push(`${name}: names neither or both of replaces and option`);
    if (seam.replaces !== undefined && !exists.has(seam.replaces))
      problems.push(`${name}: the file it replaces, ${seam.replaces}, does not exist`);
    if (seam.option !== undefined && !OPTIONS.includes(seam.option))
      problems.push(`${name}: no angular.json option "${seam.option}"`);
    const known = configurations[seam.app] ?? [];
    if (seam.configurations !== 'every')
      if (!Array.isArray(seam.configurations) || !seam.configurations.length)
        problems.push(`${name}: configurations is neither "every" nor a list`);
      else
        for (const configuration of seam.configurations)
          if (!known.includes(configuration))
            problems.push(`${name}: ${APPS[seam.app]} has no configuration "${configuration}"`);
  }
  for (const glob of catalogue.protected ?? []) {
    const re = globToRegExp(glob);
    if (!files.some((file) => re.test(file))) problems.push(`protected ${glob} matches no file`);
  }
  return problems;
}

function tree() {
  const angular = readAngularJson(path.join(FE, 'angular.json'));
  const configurations = Object.fromEntries(
    Object.entries(APPS).map(([app, project]) => [
      app,
      Object.keys(angular.projects[project]?.architect?.build?.configurations ?? {}),
    ]),
  );
  // fe/ and the kit beside it, named as the zip names them, so the kit's files are country-kit/….
  const list = treeFiles();
  const paths = new Map(list.map(({ name, file }) => [name, file]));
  const texts = new Map();
  const read = (file) => {
    if (!texts.has(file))
      texts.set(file, fs.readFileSync(paths.get(file) ?? path.join(FE, file), 'utf8'));
    return texts.get(file);
  };
  return { files: list.map(({ name }) => name), configurations, read };
}

describe('seams.json', () => {
  const where = tree();

  it('names only files of the tree, configurations of the base product and unique ids', () => {
    assert.deepEqual(catalogueProblems(readCatalogue(), where), []);
  });

  it('protects each file the security rules name', () => {
    const { protected: globs } = readCatalogue();
    for (const file of [
      'shared-html/message-html-sanitizer.ts',
      'ui-apps/admin/src/app/core/interceptors/auth-token.interceptor.ts',
      'ui-apps/admin/src/app/app.config.ts',
      'ui-apps/info/src/app/app.config.ts',
      'ui-apps/admin/src/app/shared/forms/rich-text-editor/html-format.ts',
      'ui-apps/admin/src/app/shared/forms/rich-text-editor/rich-text-engine.ts',
      // The bearer token's host rule rests on the runtime overlay's excluded roots, and the
      // protected loader takes its inputs from the i18n services (plan §4.11 rule 3).
      'ui-apps/admin/src/app/core/config/web-settings.ts',
      'ui-apps/admin/src/app/core/config/app-config.token.ts',
      'ui-apps/admin/src/app/core/i18n/dictionary-overrides.service.ts',
      'ui-apps/admin/src/app/core/i18n/branding.service.ts',
      'ui-apps/admin/src/app/core/i18n/language-settings.service.ts',
      'ui-apps/admin/src/app/core/i18n/merge-dictionary-overrides.ts',
    ]) {
      assert.ok(where.files.includes(file), `${file} is not in the tree`);
      assert.ok(
        globs.some((glob) => globToRegExp(glob).test(file)),
        `${file} is not protected`,
      );
    }
  });

  it('fails a catalogue naming a file the tree does not have', () => {
    const catalogue = readCatalogue();
    catalogue.seams[0].base = 'ui-apps/admin/src/environments/environment.gone.ts';
    catalogue.generated[0].replaces = 'ui-apps/admin/src/app/country-specific/default/gone.ts';
    catalogue.protected.push('ui-apps/admin/src/app/core/gone/**');
    assert.deepEqual(catalogueProblems(catalogue, where), [
      'seam admin.environment.development: its default ui-apps/admin/src/environments/environment.gone.ts does not exist',
      'generated admin.languages: the file it replaces, ui-apps/admin/src/app/country-specific/default/gone.ts, does not exist',
      'protected ui-apps/admin/src/app/core/gone/** matches no file',
    ]);
  });

  it('fails a repeated id, a country file outside its application and an unknown configuration', () => {
    const catalogue = readCatalogue();
    catalogue.seams[1].id = catalogue.seams[0].id;
    catalogue.seams[2].country = 'environments/environment.test.ts';
    catalogue.seams[3].configurations = ['production-xx'];
    assert.deepEqual(catalogueProblems(catalogue, where), [
      'seam admin.environment.development: the id is given twice',
      'seam admin.environment.test: its country file environments/environment.test.ts is not under admin/',
      'seam admin.environment.production: niord-app has no configuration "production-xx"',
    ]);
  });
});

describe('the extension points in seams.json', () => {
  const where = tree();

  it('names only hooks their files export and slots their templates and registries have', () => {
    const catalogue = readCatalogue();
    assert.ok(catalogue.hooks.length > 0 && catalogue.slots.length > 0);
    assert.deepEqual(extensionProblems(catalogue, where), []);
  });

  it('catalogues the seams of country routes, menu entries and providers', () => {
    const ids = readCatalogue().seams.map((seam) => seam.id);
    for (const id of [
      'admin.country.providers',
      'admin.country.routes',
      'admin.country.menu',
      'info.country.routes',
      'info.country.providers',
    ])
      assert.ok(ids.includes(id), `${id} is not a seam`);
  });

  it('protects the route guards and the country-route rule', () => {
    const { protected: globs } = readCatalogue();
    for (const file of [
      'ui-apps/admin/src/app/app.routes.ts',
      'ui-apps/admin/src/app/core/country/country-routes.ts',
    ])
      assert.ok(
        globs.some((glob) => globToRegExp(glob).test(file)),
        `${file} is not protected`,
      );
  });

  it("exports every hook's token and the slot registry through the application's country API", () => {
    assert.deepEqual(apiProblems(readCatalogue(), where.read), []);
  });

  it('maps exactly the two country APIs and their testing entries, to files of the tree', () => {
    const { compilerOptions } = readAngularJson(path.join(FE, 'tsconfig.json'));
    const api = Object.fromEntries(
      Object.entries(compilerOptions.paths).filter(([alias]) =>
        /^@niord\/(admin|info)\/api\b/.test(alias),
      ),
    );
    assert.deepEqual(api, {
      '@niord/admin/api': ['./ui-apps/admin/src/public-api.ts'],
      '@niord/admin/api/testing': ['./ui-apps/admin/src/testing-api.ts'],
      '@niord/info/api': ['./ui-apps/info/src/public-api.ts'],
      '@niord/info/api/testing': ['./ui-apps/info/src/testing-api.ts'],
    });
    for (const [target] of Object.values(api))
      assert.ok(where.files.includes(target.slice(2)), `${target} is not in the tree`);
  });

  it('fails an API that lacks a hook token or the slot registry', () => {
    const read = (file) =>
      file === 'ui-apps/admin/src/public-api.ts'
        ? where.read(file).replace(/\bPROMULGATION_EDITORS\b/g, 'PROMULGATION_GONE')
        : file === 'ui-apps/info/src/public-api.ts'
          ? where.read(file).replace(/\bprovideSlot\b/g, 'provideGone')
          : where.read(file);
    assert.deepEqual(apiProblems(readCatalogue(), read), [
      'ui-apps/admin/src/public-api.ts does not export PROMULGATION_EDITORS',
      'ui-apps/info/src/public-api.ts does not export provideSlot',
    ]);
  });

  it('fails a hook naming a token its file does not export', () => {
    const catalogue = readCatalogue();
    catalogue.hooks[0].token = 'PROMULGATION_GONE';
    assert.deepEqual(extensionProblems(catalogue, where), [
      `hook admin.promulgation.renderers: ${catalogue.hooks[0].file} does not export PROMULGATION_GONE`,
    ]);
  });

  it('fails a slot its template does not place', () => {
    const catalogue = readCatalogue();
    catalogue.slots[0].template = 'ui-apps/admin/src/app/app.html';
    assert.deepEqual(extensionProblems(catalogue, where), [
      'slot home.welcome: its template ui-apps/admin/src/app/app.html places no <app-slot name="home.welcome">',
    ]);
  });

  it('fails a slot a template places, or a registry names, that is not catalogued', () => {
    const catalogue = readCatalogue();
    const info = catalogue.slots.find((slot) => slot.app === 'info');
    catalogue.slots = catalogue.slots.filter((slot) => slot !== info);
    assert.deepEqual(extensionProblems(catalogue, where), [
      `${info.template} places the slot layout.beforeFooter, which seams.json does not catalogue`,
    ]);
    const admin = readCatalogue().slots.find((slot) => slot.app === 'admin');
    const read = (file) =>
      file === admin.registry
        ? where
            .read(file)
            .replace("SlotName = 'home.welcome'", "SlotName = 'home.welcome' | 'home.footer'")
        : where.read(file);
    assert.deepEqual(extensionProblems(readCatalogue(), { ...where, read }), [
      `${admin.registry}'s SlotName names home.footer, which seams.json does not catalogue`,
    ]);
  });
});
