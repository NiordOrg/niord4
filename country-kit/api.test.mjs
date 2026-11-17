import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  API_ENTRIES,
  apiFiles,
  apiImports,
  deprecatedUses,
  deprecatedUsesOf,
  readApi,
} from './lib/api.mjs';
import { SHIMS } from './lib/language-config.mjs';
import { where } from './lib/where.mjs';

const KIT = path.dirname(fileURLToPath(import.meta.url));
const FE = where().base;
const FIXTURES = path.join(KIT, 'test-fixtures', 'api');

const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

/** An API file of the text given, in a temporary folder. */
function apiText(text) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-api-'));
  temporary.push(dir);
  const file = path.join(dir, 'public-api.ts');
  fs.writeFileSync(file, text);
  return file;
}

/** Every .ts file under `dir`, absolute. */
const typeScript = (dir) =>
  fs.existsSync(dir)
    ? fs
        .readdirSync(dir, { recursive: true, withFileTypes: true })
        .filter((entry) => entry.isFile() && entry.name.endsWith('.ts'))
        .map((entry) => path.join(entry.parentPath, entry.name))
    : [];

/**
 * The exports no country file uses today and that are there all the same, each with its reason:
 * the types of what an extension point takes or gives, which a country's code names when it
 * declares a value of its own rather than inferring one, and the values a kit check holds a
 * country's files to. Nothing else may be unused.
 */
const CONTRACT_ONLY = {
  '@niord/admin/api': {
    PromulgationRenderer:
      "the shape of a PROMULGATION_RENDERERS registration, which a country's provider gives",
    PromulgationEditor:
      "the shape of a PROMULGATION_EDITORS registration, which a country's provider gives",
    SlotName: 'the slots there are: what provideSlot accepts',
    SlotLoader: "the lazy loader provideSlot takes, for a country's own loader",
    SlotRegistration: 'what COMPONENT_SLOTS holds, as a country spec reads it',
    MenuAnchor: "the menu items a CountryMenuEntry's `after` may name",
    AuthRole: 'the roles a country route or menu entry may name',
    EDITOR_VIEW_ROLES: "the roles of the base product's editor view, for a country page beside it",
    ADMIN_AREA_ROLES: "the roles of the base product's admin area, for a country page in it",
    SYSADMIN_AREA_ROLES: "the roles of the base product's sysadmin area, for a country page in it",
  },
  '@niord/info/api': {
    RootAreaSpec: "the shape of InfoConfig's root areas, for a country's info.config.ts",
    SlotName: 'the slots there are: what provideSlot accepts',
    SlotLoader: "the lazy loader provideSlot takes, for a country's own loader",
    SlotRegistration: 'what COMPONENT_SLOTS holds, as a country spec reads it',
    PREFERENCE_KEYS:
      'the names every cookie statement a country ships must give (set-up checks them), for a country component that lists what the site stores',
    PREFERENCE_PREFIX:
      'the names every cookie statement a country ships must give (set-up checks them), for a country component that lists what the site stores',
  },
};

/** The names each entry point's users import: the example country's files and the shims. */
function usedNames() {
  const files = ['admin', 'info'].flatMap((app) =>
    typeScript(path.join(KIT, 'example', 'fe', app)),
  );
  const texts = [...files.map((file) => fs.readFileSync(file, 'utf8')), ...Object.values(SHIMS)];
  const used = Object.fromEntries(API_ENTRIES.map((entry) => [entry, new Set()]));
  for (const text of texts)
    for (const { specifier, names } of apiImports(text)) {
      assert.ok(names, `a namespace import of ${specifier} hides which names it uses`);
      for (const name of names) used[specifier].add(name);
    }
  return used;
}

describe('the country API files', () => {
  const files = apiFiles(FE);

  it("are the four entry points fe/tsconfig.json maps, each in the kit's form", () => {
    assert.deepEqual(Object.keys(files), API_ENTRIES);
    for (const file of Object.values(files)) assert.ok(readApi(file).length > 0, file);
  });

  it('export nothing speculative: every export is used, or a contract-only type with its reason', () => {
    const used = usedNames();
    for (const [entry, file] of Object.entries(files)) {
      const contract = CONTRACT_ONLY[entry] ?? {};
      for (const { name } of readApi(file))
        assert.ok(
          used[entry].has(name) || Object.hasOwn(contract, name),
          `${entry} exports ${name}, which no example file or shim imports and which is not a contract-only export`,
        );
      // The list stays honest: a contract-only name is exported, and not yet used.
      const names = readApi(file).map((export_) => export_.name);
      for (const name of Object.keys(contract)) {
        assert.ok(names.includes(name), `${entry} lists ${name} as contract-only but exports none`);
        assert.ok(!used[entry].has(name), `${entry}'s ${name} is used now: drop it from the list`);
      }
    }
  });

  it('name every import a country file makes', () => {
    const used = usedNames();
    for (const [entry, file] of Object.entries(files)) {
      const names = readApi(file).map((export_) => export_.name);
      for (const name of used[entry])
        assert.ok(names.includes(name), `a country file imports ${name}, which ${entry} lacks`);
    }
  });

  it('keep the testing helpers out of the main entry points', () => {
    for (const app of ['admin', 'info']) {
      const main = readApi(files[`@niord/${app}/api`]).map((entry) => entry.module);
      assert.ok(!main.some((module) => /testing/.test(module)), app);
    }
  });

  it('record every deprecated export in API-CHANGES.md', () => {
    const changes = fs.readFileSync(path.join(KIT, 'API-CHANGES.md'), 'utf8');
    for (const file of Object.values(files))
      for (const { name, deprecated } of readApi(file))
        if (deprecated) assert.ok(changes.includes(`\`${name}\``), name);
  });
});

describe('the API form', () => {
  const header = '/**\n * The API.\n */\n\n';

  it('reads each export with its module, its kind and a deprecation', () => {
    assert.deepEqual(readApi(path.join(FIXTURES, 'public-api.ts')), [
      { name: 'greet', type: false, module: './app/greet' },
      {
        name: 'sayHello',
        type: false,
        module: './app/greet',
        deprecated: { since: '4.0.200', instead: 'use greet, which takes the name' },
      },
      { name: 'GreetOptions', type: true, module: './app/greet' },
    ]);
  });

  it('reads a deprecation with the version it is removed in', () => {
    const file = apiText(
      `${header}/**\n * Old.\n *\n * Deprecated since 4.0.1-example, removed in 4.0.2-example: use b.\n */\nexport { a } from './app/a';\n`,
    );
    assert.deepEqual(readApi(file)[0].deprecated, {
      since: '4.0.1-example',
      removedIn: '4.0.2-example',
      instead: 'use b',
    });
  });

  it('refuses a deprecation line outside its form, and an end not after its start', () => {
    const marked = (line) =>
      apiText(`${header}/**\n * Old.\n *\n * ${line}\n */\nexport { a } from './app/a';\n`);
    for (const line of [
      'Deprecated since 4.0.1, removed later: use b.',
      'Deprecated since 4.0.1, removed in 4.0.2.: use b.',
      'Deprecated since 4.0.1 use b.',
    ])
      assert.throws(
        () => readApi(marked(line)),
        /is not `Deprecated since <version>: <what to use instead>` or `Deprecated since <version>, removed in <version>/,
        line,
      );
    assert.throws(
      () => readApi(marked('Deprecated since 4.0.2, removed in 4.0.1: use b.')),
      /ends no later than it starts/,
    );
  });

  it('refuses an export *', () => {
    assert.throws(
      () => readApi(apiText(`${header}/** All. */\nexport * from './app/x';\n`)),
      /"export \* from '\.\/app\/x';" is not .*no `export \*`/,
    );
  });

  it('refuses a local declaration', () => {
    assert.throws(
      () => readApi(apiText(`${header}/** A value. */\nexport const x = 1;\n`)),
      /"export const x = 1;" is not .*no local declaration/,
    );
  });

  it('refuses an export without its own contract comment', () => {
    assert.throws(
      () => readApi(apiText(`${header}export { a } from './app/a';\n`)),
      /has no contract comment of its own/,
    );
    assert.throws(
      () =>
        readApi(
          apiText(
            `${header}/** A. */\nexport { a } from './app/a';\nexport { b } from './app/b';\n`,
          ),
        ),
      /"export \{ b \} from '\.\/app\/b';" has no contract comment/,
    );
  });

  it('refuses another statement, a file without its header and a name exported twice', () => {
    assert.throws(
      () => readApi(apiText(`${header}/** A. */\nimport { a } from './app/a';\n`)),
      /is not `export/,
    );
    assert.throws(
      () => readApi(apiText(`/** A. */\nexport { a } from './app/a';\n`)),
      /has no contract comment/,
    );
    assert.throws(
      () =>
        readApi(
          apiText(
            `${header}/** A. */\nexport { a } from './app/a';\n/** A. */\nexport { a } from './app/b';\n`,
          ),
        ),
      /a is exported twice/,
    );
  });

  it('fails a base product whose tsconfig.json does not map an entry point', () => {
    const base = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-api-base-'));
    temporary.push(base);
    fs.writeFileSync(
      path.join(base, 'tsconfig.json'),
      JSON.stringify({ compilerOptions: { paths: { '@niord/admin/api': ['./public-api.ts'] } } }),
    );
    assert.throws(() => apiFiles(base), /does not hold/);
    fs.writeFileSync(path.join(base, 'public-api.ts'), header);
    assert.throws(() => apiFiles(base), /does not map @niord\/admin\/api\/testing/);
  });
});

describe('deprecated exports a country uses', () => {
  const apis = { '@niord/admin/api': readApi(path.join(FIXTURES, 'public-api.ts')) };

  it('warn once per use, by name and through a namespace import, never for a current export', () => {
    assert.deepEqual(deprecatedUses({ fe: path.join(FIXTURES, 'country'), apis }), [
      'admin/app/everything.ts uses sayHello from @niord/admin/api, deprecated since 4.0.200: use greet, which takes the name; its removal is not scheduled yet (.niord/kit/API-CHANGES.md, from the repository root)',
      'admin/app/greeting.ts uses sayHello from @niord/admin/api, deprecated since 4.0.200: use greet, which takes the name; its removal is not scheduled yet (.niord/kit/API-CHANGES.md, from the repository root)',
    ]);
  });

  it('name the version a deprecated export is removed in, and give each use structured for the upgrade report', () => {
    const ending = {
      '@niord/admin/api': apis['@niord/admin/api'].map((entry) =>
        entry.deprecated
          ? { ...entry, deprecated: { ...entry.deprecated, removedIn: '4.0.300' } }
          : entry,
      ),
    };
    const fe = path.join(FIXTURES, 'country');
    assert.match(deprecatedUses({ fe, apis: ending })[0], /; it is removed in 4\.0\.300 \(/);
    assert.deepEqual(deprecatedUsesOf({ fe, apis: ending })[1], {
      file: 'admin/app/greeting.ts',
      name: 'sayHello',
      specifier: '@niord/admin/api',
      since: '4.0.200',
      removedIn: '4.0.300',
      instead: 'use greet, which takes the name',
    });
  });

  it('read the names of every import and re-export form', () => {
    assert.deepEqual(
      apiImports(
        [
          "import { a, type B, c as d } from '@niord/admin/api';",
          "import type { E } from '@niord/info/api';",
          "export { f } from '@niord/admin/api/testing';",
          "import * as g from '@niord/info/api';",
          "const h = await import('@niord/admin/api');",
          "import { i } from '@niord/admin/internal/x';",
        ].join('\n'),
      ),
      [
        { specifier: '@niord/admin/api', names: ['a', 'B', 'c'] },
        { specifier: '@niord/info/api', names: ['E'] },
        { specifier: '@niord/admin/api/testing', names: ['f'] },
        { specifier: '@niord/info/api', names: null },
        { specifier: '@niord/admin/api', names: null },
      ],
    );
  });
});
