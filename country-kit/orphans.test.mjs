/**
 * Tests for country-kit/lib/orphans.mjs, the orphan-file check of §4.3.
 *
 *   npm run test:guards      # from fe/
 *
 * A country tree in a temporary folder, reached from its seams and its one override; a file
 * nothing reaches, in the application code and in the overrides, is what the check fails.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, describe, it } from 'node:test';
import { codeLinks, orphanFiles } from './lib/orphans.mjs';

const temporary = [];
after(() => {
  for (const folder of temporary) fs.rmSync(folder, { recursive: true, force: true });
});

const SEAMS = {
  seams: [
    { id: 'admin.country.providers', app: 'admin', country: 'admin/app/country.providers.ts' },
    { id: 'admin.country.routes', app: 'admin', country: 'admin/app/country.routes.ts' },
    { id: 'info.config', app: 'info', country: 'info/app/info.config.ts' },
    { id: 'info.index', app: 'info', country: 'info/app/index.html', option: 'index' },
    { id: 'admin.environment.production', app: 'admin', country: 'admin/environments/x.ts' },
  ],
};
const MANIFEST = {
  overrides: [
    {
      file: 'ui-apps/admin/src/app/features/foo/foo.ts',
      with: 'admin/overrides/features/foo/foo.ts',
    },
  ],
};

/** A country fe/ holding the example's shape, plus `extra` (relative path → text). */
function country(extra = {}) {
  const fe = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-orphans-'));
  temporary.push(fe);
  const files = {
    'admin/app/country.providers.ts':
      "import { Note } from './note/note';\nexport * from './util';",
    'admin/app/note/note.ts':
      "@Component({ templateUrl: './note.html', styleUrls: ['./note.scss'] })\nexport class Note {}",
    'admin/app/note/note.html': '<p></p>',
    'admin/app/note/note.scss': "@use './tokens';",
    'admin/app/note/_tokens.scss': '',
    'admin/app/note/note.spec.ts': "import { Helper } from '../helper/helper';",
    'admin/app/util/index.ts': 'export const u = 1;',
    'admin/app/country.routes.ts':
      "export default [{ loadComponent: () => import('./page/page').then((m) => m.Page) }];",
    'admin/app/page/page.ts': 'export class Page {}',
    'admin/environments/x.ts': 'export {};',
    'admin/overrides/features/foo/foo.ts':
      "@Component({ templateUrl: './foo.html', styleUrl: './foo.scss' })\nexport class Foo {}",
    'admin/overrides/features/foo/foo.html': '<p></p>',
    'admin/overrides/features/foo/foo.scss': '',
    'admin/overrides/features/foo/foo.spec.ts': "import { Foo } from './foo';",
    'info/app/info.config.ts': "import data from './data.json';",
    'info/app/data.json': '{}',
    'info/app/index.html': '<html></html>',
    ...extra,
  };
  for (const [relative, text] of Object.entries(files)) {
    fs.mkdirSync(path.dirname(path.join(fe, relative)), { recursive: true });
    fs.writeFileSync(path.join(fe, relative), text);
  }
  return fe;
}

describe('the orphan-file check', () => {
  it('passes files reached by import, dynamic import, re-export, templateUrl, styleUrls and @use', () => {
    assert.deepEqual(orphanFiles({ fe: country(), seams: SEAMS, manifest: MANIFEST }), []);
  });

  it('ignores specs, and follows no import a spec makes', () => {
    const fe = country({ 'admin/app/helper/helper.ts': 'export class Helper {}' });
    assert.deepEqual(orphanFiles({ fe, seams: SEAMS, manifest: MANIFEST }), [
      'admin/app/helper/helper.ts is neither a seam nor reached from one (country-kit/README.md, The orphan-file check): import it from the seam file that uses it, or remove it',
    ]);
  });

  it('fails a file in the application code that nothing imports', () => {
    const fe = country({ 'admin/app/old/old-page.ts': 'export class OldPage {}' });
    assert.deepEqual(orphanFiles({ fe, seams: SEAMS, manifest: MANIFEST }), [
      'admin/app/old/old-page.ts is neither a seam nor reached from one (country-kit/README.md, The orphan-file check): import it from the seam file that uses it, or remove it',
    ]);
  });

  it('fails a stylesheet in the overrides that no override uses', () => {
    const fe = country({ 'admin/overrides/features/foo/foo.old.scss': '' });
    assert.deepEqual(orphanFiles({ fe, seams: SEAMS, manifest: MANIFEST }), [
      "admin/overrides/features/foo/foo.old.scss is neither an override's copy nor used by one (country-kit/README.md, The orphan-file check): name it in an overrides entry, or remove it",
    ]);
  });

  it('fails an environment file no seam names: a misspelt production environment is never built', () => {
    const fe = country({ 'admin/environments/environment.prodution.ts': 'export {};' });
    assert.deepEqual(orphanFiles({ fe, seams: SEAMS, manifest: MANIFEST }), [
      "admin/environments/environment.prodution.ts is no environment seam's file and reached from none (country-kit/README.md, The orphan-file check): a misspelt name is never built; name it as seams.json does, or remove it",
    ]);
  });

  it('fails a link in a code folder, and passes a tree without one', (t) => {
    const fe = country();
    assert.deepEqual(codeLinks(fe), []);
    const base = path.join(fe, '.niord', 'base', 'ui-apps', 'admin', 'src', 'app', 'core');
    fs.mkdirSync(base, { recursive: true });
    fs.writeFileSync(path.join(base, 'auth.ts'), 'export {};');
    try {
      fs.symlinkSync(base, path.join(fe, 'admin', 'app', 'core'), 'junction');
    } catch (error) {
      // Windows without developer mode cannot make a link; Linux (CI) and a junction can.
      t.skip(`this host cannot create a link (${error.code})`);
      return;
    }
    assert.deepEqual(codeLinks(fe), [
      "admin/app/core is a link: the country's code folders hold its own files, never a link into the base product's sources or anywhere else",
    ]);
  });

  it('fails the copy of an override the manifest no longer names', () => {
    const fe = country();
    assert.equal(
      orphanFiles({ fe, seams: SEAMS, manifest: { overrides: [] } }).length,
      3,
      'the copy, its template and its stylesheet',
    );
  });
});
