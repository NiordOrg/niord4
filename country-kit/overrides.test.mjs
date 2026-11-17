/**
 * Tests for country-kit/lib/overrides.mjs, the guarded overrides, and country-kit/security.json.
 *
 *   npm run test:guards      # from fe/
 *
 * A small base product tree in a temporary folder: a component with its template, stylesheet and
 * spec, a protected file, a seam's default, a file the zip leaves out and one that imports outside
 * its application. Each refusal of `--add-override` has its case, and each way set-up fails an
 * override that no longer fits the base product it builds against.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  acceptOverride,
  addOverride,
  baseSpecs,
  checkOverrides,
  fingerprint,
  lineDiff,
  overridePath,
  readSecurity,
  securityProblems,
} from './lib/overrides.mjs';

const KIT = path.dirname(fileURLToPath(import.meta.url));
const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

const APP = 'ui-apps/admin/src/app';
const FOO = `${APP}/features/foo/foo.ts`;
const COMPONENT = `import { Component } from '@angular/core';
import { Bar } from '../bar/bar';
import type { Shared } from '../../shared/shared.model';
export * from './foo.model';

@Component({
  selector: 'app-foo',
  imports: [Bar],
  templateUrl: './foo.html',
  styleUrl: './foo.scss',
})
export class Foo {
  readonly lazy = () => import('./lazy/lazy').then((m) => m.Lazy);
}
`;

const SEAMS = {
  seams: [
    {
      id: 'admin.country.providers',
      app: 'admin',
      country: 'admin/app/country.providers.ts',
      base: `${APP}/country-specific/default/country.providers.ts`,
      replaces: `${APP}/country-specific/default/country.providers.ts`,
    },
  ],
  generated: [
    {
      id: 'admin.languages',
      app: 'admin',
      replaces: `${APP}/country-specific/default/language.config.ts`,
    },
  ],
  protected: [`${APP}/core/auth/**`],
};
const INCLUDE = { include: ['ui-apps/**'], exclude: [`${APP}/excluded/**`] };
const TODAY = '2026-10-21';

/** A base product tree and an empty country fe/; returns both folders. */
function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-overrides-'));
  temporary.push(root);
  const base = path.join(root, 'base');
  const fe = path.join(root, 'fe');
  const files = {
    [FOO]: COMPONENT,
    [`${APP}/features/foo/foo.html`]: '<p>{{ text }}</p>\n<app-bar />\n',
    [`${APP}/features/foo/foo.scss`]: ':host { display: block; }\n',
    [`${APP}/features/foo/foo.spec.ts`]: "import { Foo } from './foo';\n",
    [`${APP}/features/bar/bar.ts`]: 'export class Bar {}\n',
    [`${APP}/core/auth/auth.ts`]: 'export const auth = 1;\n',
    [`${APP}/country-specific/default/country.providers.ts`]: 'export {};\n',
    [`${APP}/country-specific/default/language.config.ts`]: 'export {};\n',
    [`${APP}/excluded/x.ts`]: 'export {};\n',
    [`${APP}/features/out/out.ts`]: "import { x } from '../../../../../../shared-map/x';\n",
    [`${APP}/features/plain.json`]: '{ "a": 1 }\n',
  };
  for (const [relative, text] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(base, relative)), { recursive: true });
    fs.writeFileSync(path.join(base, relative), text);
  }
  fs.mkdirSync(fe);
  return { base, fe };
}

const add = (where, file, extra = {}) =>
  addOverride({
    ...where,
    manifest: { overrides: [] },
    file,
    reason: 'the example needs it',
    seams: SEAMS,
    include: INCLUDE,
    today: TODAY,
    ...extra,
  });

describe('--add-override', () => {
  it('copies a component with its template and stylesheet, its imports written as the alias', () => {
    const where = fixture();
    const entry = add(where, FOO);
    assert.equal(entry.with, 'admin/overrides/features/foo/foo.ts');
    assert.deepEqual(Object.keys(entry.basedOn), [
      FOO,
      `${APP}/features/foo/foo.html`,
      `${APP}/features/foo/foo.scss`,
    ]);
    for (const [file, value] of Object.entries(entry.basedOn))
      assert.equal(value, fingerprint(fs.readFileSync(path.join(where.base, file))));
    assert.equal(entry.reason, 'the example needs it');
    assert.equal(entry.added, TODAY);
    const copy = fs.readFileSync(path.join(where.fe, entry.with), 'utf8');
    assert.match(copy, /from '@niord\/admin\/internal\/features\/bar\/bar';/);
    assert.match(copy, /from '@niord\/admin\/internal\/shared\/shared\.model';/);
    assert.match(copy, /export \* from '@niord\/admin\/internal\/features\/foo\/foo\.model';/);
    assert.match(copy, /import\('@niord\/admin\/internal\/features\/foo\/lazy\/lazy'\)/);
    assert.match(copy, /templateUrl: '\.\/foo\.html'/);
    for (const resource of ['foo.html', 'foo.scss'])
      assert.equal(
        fs.readFileSync(path.join(where.fe, 'admin/overrides/features/foo', resource), 'utf8'),
        fs.readFileSync(path.join(where.base, APP, 'features/foo', resource), 'utf8'),
      );
  });

  it('copies a JSON file as it is', () => {
    const where = fixture();
    const entry = add(where, `${APP}/features/plain.json`);
    assert.deepEqual(Object.keys(entry.basedOn), [`${APP}/features/plain.json`]);
    assert.equal(
      fs.readFileSync(path.join(where.fe, 'admin/overrides/features/plain.json'), 'utf8'),
      '{ "a": 1 }\n',
    );
  });

  it("refuses a file outside an application's src/app/", () => {
    assert.throws(() => add(fixture(), 'shared-map/x.ts'), /only application code is overridden/);
  });

  it('refuses a template, which is overridden through its component', () => {
    assert.throws(
      () => add(fixture(), `${APP}/features/foo/foo.html`),
      /cannot be replaced: only TypeScript, JavaScript and JSON files can/,
    );
  });

  it('refuses a spec', () => {
    assert.throws(() => add(fixture(), `${APP}/features/foo/foo.spec.ts`), /is a spec/);
  });

  it('refuses an override without a reason', () => {
    assert.throws(() => add(fixture(), FOO, { reason: ' ' }), /needs --reason/);
  });

  it('refuses a file the base product does not have', () => {
    assert.throws(
      () => add(fixture(), `${APP}/features/gone.ts`),
      /is not a file of the base product's source zip/,
    );
  });

  it("refuses a file the zip's include list leaves out", () => {
    assert.throws(
      () => add(fixture(), `${APP}/excluded/x.ts`),
      /is not a file of the base product's source zip/,
    );
  });

  it('refuses a protected file', () => {
    assert.throws(
      () => add(fixture(), `${APP}/core/auth/auth.ts`),
      /is protected \(seams\.json; country-kit\/README\.md, Guarded overrides\)/,
    );
  });

  it("refuses a seam's file and a generated seam's", () => {
    assert.throws(
      () => add(fixture(), `${APP}/country-specific/default/country.providers.ts`),
      /is the seam admin\.country\.providers: fill the seam instead, at admin\/app\/country\.providers\.ts/,
    );
    assert.throws(
      () => add(fixture(), `${APP}/country-specific/default/language.config.ts`),
      /is the seam admin\.languages: fill the seam instead/,
    );
  });

  it('refuses a file already overridden', () => {
    assert.throws(
      () =>
        add(fixture(), FOO, {
          manifest: { overrides: [{ file: FOO, with: 'admin/overrides/features/foo/foo.ts' }] },
        }),
      /is already overridden by admin\/overrides\/features\/foo\/foo\.ts/,
    );
  });

  it('never overwrites a file of the country', () => {
    const where = fixture();
    const html = path.join(where.fe, 'admin/overrides/features/foo/foo.html');
    fs.mkdirSync(path.dirname(html), { recursive: true });
    fs.writeFileSync(html, 'mine');
    assert.throws(
      () => add(where, FOO),
      /foo\.html exists: --add-override never overwrites a file/,
    );
    assert.equal(fs.readFileSync(html, 'utf8'), 'mine');
    assert.equal(fs.existsSync(path.join(where.fe, 'admin/overrides/features/foo/foo.ts')), false);
  });

  it('refuses a file that imports outside its application, which the alias does not reach', () => {
    assert.throws(
      () => add(fixture(), `${APP}/features/out/out.ts`),
      /lies outside ui-apps\/admin\/src\/app\/: an override reaches the base product through @niord\/admin\/internal\/\* only/,
    );
  });
});

/** A fixture with the component overridden, and its manifest. */
function overridden() {
  const where = fixture();
  const entry = add(where, FOO);
  return { ...where, entry, manifest: { overrides: [entry] } };
}

const check = (where, extra = {}) =>
  checkOverrides({
    fe: where.fe,
    base: where.base,
    manifest: where.manifest,
    seams: SEAMS,
    security: { format: 1, fixes: [] },
    include: INCLUDE,
    ...extra,
  });

const change = (where, file, text) => fs.writeFileSync(path.join(where.base, file), text);

describe('the override check of every set-up', () => {
  it('passes an override that matches the base product, and lists it with its base spec', () => {
    const where = overridden();
    const { errors, lines } = check(where);
    assert.deepEqual(errors, []);
    assert.deepEqual(lines, [
      'the guarded overrides, niord-country.json, replace 1 file(s):',
      `  ${FOO} -> admin/overrides/features/foo/foo.ts — the example needs it (added ${TODAY}); the base product's foo.spec.ts runs against it`,
    ]);
    assert.deepEqual(baseSpecs(where.base, where.manifest.overrides), [
      `${APP}/features/foo/foo.spec.ts`,
    ]);
  });

  it('fails a changed basedOn file, naming it and the command that records it', () => {
    const where = overridden();
    change(where, `${APP}/features/foo/foo.html`, '<p>changed</p>\n');
    assert.deepEqual(check(where).errors, [
      `override ${FOO}: the base product changed ${APP}/features/foo/foo.html since the copy was based on it: compare admin/overrides/features/foo/foo.ts with the base product's, then run \`node setup.mjs --accept-override ${FOO}\``,
    ]);
  });

  it('fails an override whose base file was removed', () => {
    const where = overridden();
    fs.rmSync(path.join(where.base, FOO));
    assert.match(check(where).errors[0], /removed from the base product/);
  });

  it('fails an override of a file that joined the protected list', () => {
    const where = overridden();
    const seams = { ...SEAMS, protected: [...SEAMS.protected, `${APP}/features/foo/**`] };
    assert.deepEqual(check(where, { seams }).errors, [
      `override ${FOO}: joined the protected list: replace it with an extension point`,
    ]);
  });

  it("fails an override of a seam's file, a generated seam's included", () => {
    const where = overridden();
    const providers = `${APP}/country-specific/default/country.providers.ts`;
    const languages = `${APP}/country-specific/default/language.config.ts`;
    // A hand-edited manifest: --add-override refuses both files.
    const manifest = {
      overrides: [providers, languages].map((file) => ({
        file,
        with: overridePath(file),
        basedOn: { [file]: fingerprint(fs.readFileSync(path.join(where.base, file))) },
        reason: 'hand-edited',
        added: TODAY,
      })),
    };
    for (const entry of manifest.overrides) {
      fs.mkdirSync(path.dirname(path.join(where.fe, entry.with)), { recursive: true });
      fs.writeFileSync(path.join(where.fe, entry.with), 'export {};\n');
    }
    assert.deepEqual(check({ ...where, manifest }).errors, [
      `override ${providers}: is the seam admin.country.providers: fill the seam instead, at admin/app/country.providers.ts`,
      `override ${languages}: is the seam admin.languages: fill the seam instead`,
    ]);
  });

  it('fails an override whose copy is missing', () => {
    const where = overridden();
    fs.rmSync(path.join(where.fe, where.entry.with));
    assert.deepEqual(check(where).errors, [
      `override ${FOO}: its copy admin/overrides/features/foo/foo.ts does not exist`,
    ]);
  });

  it('fails with SECURITY first when a security fix changed a file the override copies', () => {
    const where = overridden();
    change(where, `${APP}/features/foo/foo.html`, '<p>{{ text | safe }}</p>\n');
    const security = {
      format: 1,
      fixes: [
        {
          version: '4.0.7',
          files: [`${APP}/features/foo/foo.html`],
          summary: 'the text is escaped',
        },
      ],
    };
    const { errors } = check(where, { security, since: '4.0.6' });
    assert.equal(errors.length, 2);
    assert.match(
      errors[0],
      /^SECURITY: 4\.0\.7 fixed ui-apps\/admin\/src\/app\/features\/foo\/foo\.html \(the text is escaped\)/,
    );
  });

  it('gives each error structured, with its kind and its line, for the upgrade report', () => {
    const where = overridden();
    const html = `${APP}/features/foo/foo.html`;
    change(where, html, '<p>{{ text | safe }}</p>\n');
    fs.rmSync(path.join(where.fe, where.entry.with));
    const providers = `${APP}/country-specific/default/country.providers.ts`;
    const seamEntry = {
      file: providers,
      with: overridePath(providers),
      basedOn: { [providers]: fingerprint(fs.readFileSync(path.join(where.base, providers))) },
      reason: 'hand-edited',
      added: TODAY,
    };
    fs.mkdirSync(path.dirname(path.join(where.fe, seamEntry.with)), { recursive: true });
    fs.writeFileSync(path.join(where.fe, seamEntry.with), 'export {};\n');
    const seams = { ...SEAMS, protected: [...SEAMS.protected, `${APP}/features/foo/**`] };
    const security = {
      format: 1,
      fixes: [{ version: '4.0.7', files: [html], summary: 'the text is escaped' }],
    };
    const manifest = { overrides: [where.entry, seamEntry] };
    const { errors, findings } = check({ ...where, manifest }, { seams, security, since: '4.0.6' });
    // Each finding's line is the error, in the same order.
    assert.deepEqual(
      findings.map((finding) => finding.error),
      errors,
    );
    assert.deepEqual(
      findings.map(({ error, ...finding }) => finding),
      [
        { file: FOO, kind: 'protected' },
        { file: FOO, kind: 'copy' },
        { file: FOO, kind: 'security', version: '4.0.7', changed: html },
        { file: FOO, kind: 'changed' },
        { file: providers, kind: 'seam' },
      ],
    );
    assert.deepEqual(check(overridden()).findings, []);
  });

  it('raises no SECURITY for a fix the last set-up already had, or without a set-up record', () => {
    // The fix of 4.0.7 shipped by the country's last set-up (4.0.7 or later): a later ordinary
    // change to the file is the plain "changed since" error, not a security stop.
    const where = overridden();
    change(where, `${APP}/features/foo/foo.html`, '<p>{{ text }}</p>\n<app-qux />\n');
    const security = {
      format: 1,
      fixes: [{ version: '4.0.7', files: [`${APP}/features/foo/foo.html`], summary: 'escaped' }],
    };
    for (const since of ['4.0.7', '4.0.9', undefined]) {
      const { errors } = check(where, { security, since });
      assert.equal(errors.length, 1, `since ${since}: ${errors.join('\n')}`);
      assert.match(errors[0], /^override .*: the base product changed .*foo\.html since the copy/);
    }
    assert.match(check(where, { security, since: '4.0.6' }).errors[0], /^SECURITY: 4\.0\.7/);
    const { lines } = acceptOverride({
      ...where,
      target: FOO,
      security,
      previous: { version: '4.0.7', unavailable: 'the test' },
    });
    assert.doesNotMatch(lines.join('\n'), /SECURITY/);
  });

  it('reads a CRLF file and its LF copy as the same', () => {
    assert.equal(fingerprint(Buffer.from('a\r\nb\r\n')), fingerprint(Buffer.from('a\nb\n')));
    assert.notEqual(fingerprint(Buffer.from('a\nb\n')), fingerprint(Buffer.from('a\nc\n')));
  });
});

describe('--accept-override', () => {
  it('records the current fingerprints and prints what changed, with the diff', () => {
    const where = overridden();
    change(where, `${APP}/features/foo/foo.html`, '<p>{{ text }}</p>\n<app-baz />\n');
    const { entry, lines } = acceptOverride({
      ...where,
      target: where.entry.with,
      security: { format: 1, fixes: [] },
    });
    assert.equal(
      entry.basedOn[`${APP}/features/foo/foo.html`],
      fingerprint(fs.readFileSync(path.join(where.base, APP, 'features/foo/foo.html'))),
    );
    assert.equal(entry.basedOn[FOO], where.entry.basedOn[FOO]);
    const text = lines.join('\n');
    assert.match(text, new RegExp(`${FOO}: unchanged`));
    assert.match(
      text,
      /admin\/overrides\/features\/foo\/foo\.ts is the base product's current file/,
    );
    assert.match(text, /foo\.html: sha256:[0-9a-f]{64} -> sha256:[0-9a-f]{64}/);
    assert.match(text, /-2 <app-baz \/>\n {6}\+2 <app-bar \/>/);
    assert.doesNotMatch(text, /SECURITY/);
    assert.deepEqual(check({ ...where, manifest: { overrides: [entry] } }).errors, []);
  });

  it('prints a SECURITY block when a fix changed a file it copies', () => {
    const where = overridden();
    change(where, `${APP}/features/foo/foo.scss`, ':host { display: none; }\n');
    const { lines } = acceptOverride({
      ...where,
      target: FOO,
      security: {
        format: 1,
        fixes: [{ version: '4.0.7', files: [`${APP}/features/foo/foo.scss`], summary: 'hidden' }],
      },
      previous: { version: '4.0.6', unavailable: 'the test' },
    });
    assert.ok(lines.includes(`  SECURITY: 4.0.7 changed ${APP}/features/foo/foo.scss: hidden`));
  });

  it('refuses an override whose base file is gone, and a file no override names', () => {
    const where = overridden();
    fs.rmSync(path.join(where.base, APP, 'features/foo/foo.scss'));
    assert.throws(
      () => acceptOverride({ ...where, target: FOO, security: { format: 1, fixes: [] } }),
      /no longer has ui-apps\/admin\/src\/app\/features\/foo\/foo\.scss/,
    );
    assert.throws(
      () => acceptOverride({ ...where, target: `${APP}/features/bar/bar.ts`, security: {} }),
      /has no override of/,
    );
  });

  it("prints the base product's own diff since the recorded fingerprint from the previous version's file", () => {
    const where = overridden();
    const html = `${APP}/features/foo/foo.html`;
    const before = fs.readFileSync(path.join(where.base, html));
    change(where, html, '<p>{{ text }}</p>\n<app-bar />\n<app-baz />\n');
    const { lines } = acceptOverride({
      ...where,
      target: FOO,
      security: { format: 1, fixes: [] },
      previous: { version: '4.0.6', files: new Map([[html, before]]) },
      version: '4.0.7',
    });
    const text = lines.join('\n');
    assert.match(
      text,
      new RegExp(
        `foo\\.html: sha256:[0-9a-f]{64} -> sha256:[0-9a-f]{64}\\n {4}the base product's diff of ${html} since the recorded fingerprint \\(4\\.0\\.6 to 4\\.0\\.7; - before, \\+ now\\):\\n {6}\\+3 <app-baz />\\n`,
      ),
    );
    // The copy against the base product's current file follows, as before.
    assert.match(text, /admin\/overrides\/features\/foo\/foo\.html against the base product's/);
  });

  it('says why the diff since the recorded fingerprint is not available', () => {
    const html = `${APP}/features/foo/foo.html`;
    const accept = (previous) => {
      const where = overridden();
      change(where, html, '<p>changed</p>\n');
      return acceptOverride({
        ...where,
        target: FOO,
        security: { format: 1, fixes: [] },
        previous,
        version: '4.0.7',
      }).lines.join('\n');
    };
    const head = `    the base product's diff of ${html} since the recorded fingerprint: not available: `;
    // The previous version's file is not the one the fingerprint recorded.
    assert.ok(
      accept({
        version: '4.0.6',
        files: new Map([[html, Buffer.from('<p>other</p>\n')]]),
      }).includes(`${head}the recorded fingerprint is not the previous version's file (4.0.6)`),
    );
    assert.ok(
      accept({ version: '4.0.6', files: new Map() }).includes(
        `${head}the previous version's zip (4.0.6) does not hold it`,
      ),
    );
    assert.ok(
      accept({ unavailable: 'no niord-fe-src-4.0.6.zip in .niord/' }).includes(
        `${head}no niord-fe-src-4.0.6.zip in .niord/`,
      ),
    );
    assert.ok(accept(undefined).includes(`${head}no previous base product version is known`));
  });

  it('diffs by lines', () => {
    assert.deepEqual(lineDiff('a\nb\nc', 'a\nx\nc'), ['-2 b', '+2 x']);
    assert.deepEqual(lineDiff('a\nb', 'a\nb'), []);
  });
});

describe('security.json', () => {
  it('is format 1 with a list of fixes, each a plain version, clean paths and a summary', () => {
    const security = readSecurity(path.join(KIT, 'security.json'));
    assert.equal(security.format, 1);
    assert.ok(Array.isArray(security.fixes));
    assert.deepEqual(securityProblems(security), []);
  });

  it('fails a fix with a version that is not plain or a path that is not clean', () => {
    assert.deepEqual(
      securityProblems({
        format: 1,
        fixes: [{ version: 'v4', files: ['../x.ts', 'ui-apps\\a.ts'], summary: 'x' }],
      }),
      [
        'fixes[0].version is not a plain version',
        'fixes[0].files is not a list of clean paths relative to fe/',
      ],
    );
  });
});
