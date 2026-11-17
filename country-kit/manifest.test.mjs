/**
 * Tests for country-kit/lib/manifest.mjs, country-kit/lib/schema.mjs and the manifest's schema.
 *
 *   npm run test:guards      # from fe/
 *
 * A passing manifest in each shape the plan names — Greenland's, a foreign pack pinned and a home
 * pack, and the example country's, English only in the administration app — and one manifest per
 * rule that breaks it. A manifest that slips through sets a country up with a language it cannot
 * serve, which is why these exist.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { manifestProblems, packUses, readManifest } from './lib/manifest.mjs';
import { checkSchema, validate } from './lib/schema.mjs';

const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

/** Greenland's manifest, as the plan gives it (§4.1). */
const greenland = () => ({
  country: 'gl',
  name: 'Greenland',
  apps: {
    admin: {
      uiLanguages: ['da', 'en'],
      contentLanguages: ['da', 'en', 'kl'],
      defaultLanguage: 'da',
    },
    info: { uiLanguages: ['kl', 'da', 'en'], defaultLanguage: 'kl' },
  },
  packs: { da: { pinned: true }, kl: { home: true, publish: false } },
  locales: {},
  overrides: [],
});

/** The example country's shape: en and its own pack, the public site in its own language only. */
const example = () => ({
  country: 'xx',
  name: 'Example Country',
  apps: {
    admin: { uiLanguages: ['en', 'zz'], contentLanguages: ['en', 'zz'], defaultLanguage: 'en' },
    info: { uiLanguages: ['zz'], defaultLanguage: 'zz' },
  },
  packs: { zz: { home: true, publish: false } },
  locales: { zz: 'en-GB' },
});

function assertRejected(edit, fragment) {
  const manifest = greenland();
  edit(manifest);
  const problems = manifestProblems(manifest);
  assert.ok(
    problems.some((problem) => problem.includes(fragment)),
    `expected a problem naming "${fragment}", got:\n${problems.join('\n') || '(none)'}`,
  );
}

describe('the country manifest', () => {
  it("passes Greenland's manifest and the example country's", () => {
    assert.deepEqual(manifestProblems(greenland()), []);
    assert.deepEqual(manifestProblems(example()), []);
    assert.deepEqual(
      manifestProblems({
        ...greenland(),
        packs: { da: { pinned: true, owner: 'sifa-dk' }, kl: { home: true, publish: true } },
      }),
      [],
    );
  });

  it('reads a manifest file, frozen, and names the file when it is broken', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'manifest-'));
    temporary.push(dir);
    const file = path.join(dir, 'niord-country.json');
    fs.writeFileSync(file, JSON.stringify(example()));
    const manifest = readManifest(file);
    assert.equal(manifest.country, 'xx');
    assert.ok(Object.isFrozen(manifest.apps.admin.uiLanguages));
    fs.writeFileSync(file, JSON.stringify({ ...example(), colour: 'red' }));
    assert.throws(
      () => readManifest(file),
      /niord-country\.json breaks 1 rule\(s\):\n {2}colour is an unknown field/,
    );
    fs.writeFileSync(file, '{ not json');
    assert.throws(() => readManifest(file), /niord-country\.json: /);
  });

  it('gives the pack check its uses: UI languages but en, the backend ui or content', () => {
    assert.deepEqual(packUses(greenland()), {
      admin: ['da'],
      info: ['da', 'kl'],
      backend: { da: 'ui', kl: 'ui' },
    });
    const contentOnly = greenland();
    contentOnly.apps.info.uiLanguages = ['da', 'en'];
    contentOnly.apps.info.defaultLanguage = 'da';
    assert.deepEqual(packUses(contentOnly).backend, { da: 'ui', kl: 'content' });
  });

  it('rejects an unknown field, at the top and inside', () => {
    assertRejected((m) => (m.theme = 'dark'), 'theme is an unknown field');
    assertRejected(
      (m) => (m.apps.info.contentLanguages = ['kl']),
      'apps.info.contentLanguages is an unknown field',
    );
    assertRejected((m) => (m.packs.kl.label = 'Kalaallisut'), 'packs.kl');
  });

  it('rejects a language code that is not two or three a-z', () => {
    assertRejected(
      (m) => m.apps.admin.uiLanguages.push('EN'),
      'apps.admin.uiLanguages[2] "EN" does not match',
    );
    assertRejected((m) => (m.country = 'greenland'), 'country "greenland" does not match');
    assertRejected(
      (m) => (m.packs['de-DE'] = { pinned: true }),
      'packs["de-DE"] is an unknown field',
    );
  });

  it('rejects a country code that is not two letters a-z, and keeps three-letter language codes', () => {
    assertRejected((m) => (m.country = 'xxx'), 'country "xxx" does not match ^[a-z]{2}$');
    const three = greenland();
    three.apps.admin.contentLanguages.push('fil');
    three.packs.fil = { home: true, publish: false };
    assert.deepEqual(manifestProblems(three), []);
  });

  it('rejects a name that is not plain text or is too long', () => {
    assertRejected((m) => (m.name = 'Green<b>land</b>'), 'name "Green<b>land</b>" does not match');
    assertRejected((m) => (m.name = 'G'.repeat(61)), 'name has 61 characters, over 60');
  });

  it('rejects a default language that is not a UI language', () => {
    assertRejected(
      (m) => (m.apps.info.defaultLanguage = 'fo'),
      'apps.info.defaultLanguage "fo" is not one of its uiLanguages',
    );
  });

  it('rejects an administration UI language that is not a content language', () => {
    assertRejected(
      (m) => (m.apps.admin.contentLanguages = ['en', 'kl']),
      'apps.admin.uiLanguages "da" is not one of its contentLanguages',
    );
  });

  it('rejects a named language without a packs entry', () => {
    assertRejected((m) => delete m.packs.kl, 'packs has no entry for "kl"');
  });

  it('rejects a packs entry for en', () => {
    assertRejected(
      (m) => (m.packs.en = { home: true, publish: false }),
      'packs.en is not a pack of the country',
    );
  });

  it('rejects a pack both pinned and home, or neither', () => {
    assertRejected(
      (m) => (m.packs.da = { pinned: true, home: true }),
      'packs.da is both pinned and home',
    );
    assertRejected((m) => (m.packs.da = {}), 'packs.da is neither pinned nor home');
  });

  it('rejects publish on a pinned pack', () => {
    assertRejected(
      (m) => (m.packs.da.publish = true),
      'packs.da.publish belongs to a home pack only',
    );
  });

  it('rejects owner on a home pack, and an owner that is not a GitHub owner name', () => {
    assertRejected(
      (m) => (m.packs.kl.owner = 'sifa-dk'),
      'packs.kl.owner belongs to a pinned pack only',
    );
    assertRejected((m) => (m.packs.da.owner = 'SIFA DK'), 'packs.da');
  });

  it('rejects a pack for a language no application names', () => {
    assertRejected(
      (m) => (m.packs.fo = { home: true, publish: false }),
      'packs.fo is for "fo", which no application names',
    );
  });

  it('rejects a locale for a language no application names, and a malformed locale', () => {
    assertRejected(
      (m) => (m.locales.fo = 'fo-FO'),
      'locales.fo is for "fo", which no application names',
    );
    assertRejected((m) => (m.locales.da = 'de_DE'), 'locales.da "de_DE" does not match');
  });
});

const FILE = 'ui-apps/admin/src/app/features/editor/foo/foo.ts';
const HASH = `sha256:${'a'.repeat(64)}`;

/** A guarded override of a component, as `--add-override` writes it (§4.9). */
const override = () => ({
  file: FILE,
  with: 'admin/overrides/features/editor/foo/foo.ts',
  basedOn: {
    [FILE]: HASH,
    'ui-apps/admin/src/app/features/editor/foo/foo.html': HASH,
    'ui-apps/admin/src/app/shared/forms/field.css': HASH,
  },
  reason: "DK's own workflow step",
  added: '2026-11-20',
});

/** Greenland's manifest with one override, edited. */
function assertOverrideRejected(edit, fragment) {
  assertRejected((m) => {
    const entry = override();
    m.overrides.push(entry);
    edit(entry, m);
  }, fragment);
}

describe('the guarded overrides of the manifest', () => {
  it('passes an override of a component and its resources', () => {
    const manifest = greenland();
    manifest.overrides.push(override());
    assert.deepEqual(manifestProblems(manifest), []);
    const info = greenland();
    info.overrides.push({
      file: 'ui-apps/info/src/app/core/config/x.json',
      with: 'info/overrides/core/config/x.json',
      basedOn: { 'ui-apps/info/src/app/core/config/x.json': HASH },
      reason: 'a test',
      added: '2026-11-20',
    });
    assert.deepEqual(manifestProblems(info), []);
  });

  it('rejects a copy outside the overrides folders', () => {
    assertOverrideRejected(
      (entry) => (entry.with = 'admin/app/features/editor/foo/foo.ts'),
      'overrides[0].with "admin/app/features/editor/foo/foo.ts" does not match',
    );
  });

  it("rejects a copy that does not mirror its file's path", () => {
    assertOverrideRejected(
      (entry) => (entry.with = 'admin/overrides/foo/foo.ts'),
      'overrides[0].with is not admin/overrides/features/editor/foo/foo.ts',
    );
  });

  it("rejects a copy in the other application's folder, and basedOn of the other application", () => {
    assertOverrideRejected(
      (entry) => (entry.with = 'info/overrides/features/editor/foo/foo.ts'),
      'overrides[0].with is not admin/overrides/features/editor/foo/foo.ts',
    );
    assertOverrideRejected(
      (entry) => (entry.basedOn['ui-apps/info/src/app/x.html'] = HASH),
      'overrides[0].basedOn names ui-apps/info/src/app/x.html, outside ui-apps/admin/src/app/',
    );
  });

  it("rejects a file outside an application's src/app/", () => {
    assertOverrideRejected(
      (entry) => (entry.file = 'shared-map/portrayal/portrayal.ts'),
      'overrides[0].file "shared-map/portrayal/portrayal.ts" does not match',
    );
  });

  it('rejects a template, which is overridden through its component', () => {
    assertOverrideRejected(
      (entry) => (entry.file = 'ui-apps/admin/src/app/features/editor/foo/foo.html'),
      'overrides[0].file "ui-apps/admin/src/app/features/editor/foo/foo.html" does not match',
    );
  });

  it('rejects a spec', () => {
    assertOverrideRejected((entry) => {
      entry.file = 'ui-apps/admin/src/app/features/editor/foo/foo.spec.ts';
      entry.with = 'admin/overrides/features/editor/foo/foo.spec.ts';
      entry.basedOn = { [entry.file]: HASH };
    }, 'overrides[0].file is a spec');
  });

  it('rejects basedOn without the file it replaces', () => {
    assertOverrideRejected(
      (entry) => delete entry.basedOn[FILE],
      `overrides[0].basedOn does not name ${FILE}`,
    );
  });

  it('rejects a fingerprint that is not sha256', () => {
    assertOverrideRejected(
      (entry) => (entry.basedOn[FILE] = 'md5:abc'),
      `overrides[0].basedOn["${FILE}"] "md5:abc" does not match`,
    );
  });

  it('rejects a ".." segment in a path', () => {
    assertOverrideRejected(
      (entry) => (entry.basedOn['ui-apps/admin/src/app/../../x.html'] = HASH),
      'has a ".", ".." or empty segment',
    );
  });

  it('rejects two overrides of one file', () => {
    assertRejected((m) => {
      m.overrides.push(override(), { ...override(), reason: 'again' });
    }, `overrides[1].file replaces ${FILE}, which an earlier entry does`);
  });

  it('rejects an override without a reason, or with an unknown field', () => {
    assertOverrideRejected((entry) => delete entry.reason, 'overrides[0] has no reason');
    assertOverrideRejected(
      (entry) => (entry.protected = true),
      'overrides[0].protected is an unknown field',
    );
  });
});

describe('the schema validator', () => {
  it('refuses a schema keyword it does not apply', () => {
    assert.throws(() => checkSchema({ type: 'string', minLength: 1 }), /uses "minLength"/);
  });

  it('applies const, enum, items, uniqueItems, minItems and oneOf', () => {
    const schema = {
      type: 'object',
      properties: {
        mode: { enum: ['a', 'b'] },
        flag: { const: true },
        list: { type: 'array', items: { type: 'integer' }, uniqueItems: true, minItems: 2 },
        either: { oneOf: [{ type: 'string' }, { type: 'boolean' }] },
      },
    };
    assert.deepEqual(validate({ mode: 'a', flag: true, list: [1, 2], either: 'x' }, schema), []);
    const problems = validate({ mode: 'c', flag: false, list: [1, 1.5, 1], either: 3 }, schema).map(
      ({ path: at, message }) => `${at} ${message}`,
    );
    assert.deepEqual(problems, [
      'mode is not one of "a", "b"',
      'flag is not true',
      'list[2] repeats 1',
      'list[1] is a number, not a whole number',
      'either matches none of its 2 forms (1: either is a whole number, not a text | 2: either is a whole number, not true or false)',
    ]);
  });
});
