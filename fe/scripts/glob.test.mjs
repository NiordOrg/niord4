/**
 * Tests for scripts/glob.mjs, the base product's own copy of the kit's glob matcher.
 *
 *   npm run test:guards      # from fe/
 *
 * build-urls-check.mjs reads its allow-list globs with this copy, so that no file of the base
 * product imports the kit. The copy must match what the kit's country-kit/lib/include.mjs matches:
 * its matcher block is held byte for byte to the kit's, and both are run over the same cases.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import * as kit from '../../country-kit/lib/include.mjs';
import * as base from './glob.mjs';

const FE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = (relative) => fs.readFileSync(path.join(FE, relative), 'utf8');

const START = '/** A glob with its brace lists expanded';
const LAST = 'export function globToRegExp(';

/** The matcher block of a file: from expandBraces' doc comment to globToRegExp's closing brace. */
function matcherBlock(text, name) {
  const start = text.indexOf(START);
  assert.ok(start >= 0, `${name} has expandBraces' doc comment`);
  const last = text.indexOf(LAST, start);
  assert.ok(last >= 0, `${name} has globToRegExp after it`);
  const end = text.indexOf('\n}\n', last);
  assert.ok(end >= 0, `${name} closes globToRegExp`);
  return text.slice(start, end + 3);
}

/** The literal specifiers of a file's import … from, import '…' and import(). */
function specifiers(text) {
  const found = [];
  for (const re of [
    /\bimport\(\s*(['"`])([^'"`]*)\1\s*\)/g,
    /\bimport\s+(?:[^'"`;]*?\bfrom\s+)?(['"])([^'"]*)\1/g,
    /\bexport\s+[^'"`;]*?\bfrom\s+(['"])([^'"]*)\1/g,
  ])
    for (const match of text.matchAll(re)) found.push(match[2]);
  return found;
}

describe('scripts/glob.mjs', () => {
  it("is the kit's matcher, byte for byte", () => {
    assert.equal(
      matcherBlock(read('scripts/glob.mjs'), 'scripts/glob.mjs'),
      matcherBlock(
        fs.readFileSync(path.join(FE, '..', 'country-kit', 'lib', 'include.mjs'), 'utf8'),
        'country-kit/lib/include.mjs',
      ),
    );
  });

  for (const [name, { expandBraces, globToRegExp }] of [
    ['scripts/glob.mjs', base],
    ['country-kit/lib/include.mjs', kit],
  ]) {
    it(`${name} matches **, *, ? and {a,b} as documented`, () => {
      assert.deepEqual(expandBraces('a/{b,c/d}/e'), ['a/b/e', 'a/c/d/e']);
      const all = globToRegExp('styles/**');
      assert.ok(all.test('styles/a.css') && all.test('styles/fonts/x/y.woff2'));
      assert.ok(!all.test('styles') && !all.test('stylesheet/a.css'));
      const one = globToRegExp('styles/*.{xa,xb}.css');
      assert.ok(one.test('styles/tokens.xa.css') && one.test('styles/fonts.xb.css'));
      assert.ok(!one.test('styles/x/tokens.xa.css') && !one.test('styles/tokens.xc.css'));
      const anywhere = globToRegExp('**/node_modules/**');
      assert.ok(anywhere.test('node_modules/a') && anywhere.test('x/y/node_modules/a/b'));
      assert.ok(globToRegExp('a?.txt').test('ab.txt') && !globToRegExp('a?.txt').test('a/.txt'));
      assert.ok(globToRegExp('*').test('.npmrc'));
    });

    it(`${name} refuses a glob it cannot read`, () => {
      assert.throws(() => globToRegExp('a/{b'), /without its "\}"/);
      assert.throws(() => globToRegExp('/abs/**'), /not a relative glob/);
      assert.throws(() => globToRegExp('a/**x'), /whole segments only/);
    });
  }

  it('is what build-urls-check.mjs reads its globs with: it imports nothing of the kit', () => {
    const all = specifiers(read('scripts/build-urls-check.mjs'));
    assert.ok(all.includes('./glob.mjs'), 'build-urls-check.mjs imports ./glob.mjs');
    assert.deepEqual(
      all.filter((specifier) => specifier.includes('country-kit/')),
      [],
    );
  });
});
