/**
 * Tests for eslint-shared.cjs, the lint lists the base product's configuration shares with the
 * country lint.
 *
 *   npm run test:guards      # from fe/
 *
 * The country lint keeps its own copy of the lists in country-kit/lib/country-lint.cjs, because a
 * country's configuration loads that file from the kit. The two copies must stay equal, or a
 * country's files would be held to other bans than the base product's. And the base product's
 * configuration must not reach into the kit for them.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';

const FE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);

const LISTS = [
  'INFO_ANONYMOUS_PACKAGES',
  'INFO_ANONYMOUS_MESSAGE',
  'BYPASS_SECURITY_TRUST',
  'INNER_HTML_PIPES',
];

/** The literal specifiers of a file's require(), import() and import … from. */
function specifiers(text) {
  const found = [];
  for (const re of [
    /\brequire\(\s*(['"`])([^'"`]*)\1\s*\)/g,
    /\bimport\(\s*(['"`])([^'"`]*)\1\s*\)/g,
    /\bimport\s+(?:[^'"`;]*?\bfrom\s+)?(['"])([^'"]*)\1/g,
    /\bexport\s+[^'"`;]*?\bfrom\s+(['"])([^'"]*)\1/g,
  ])
    for (const match of text.matchAll(re)) found.push(match[2]);
  return found;
}

describe('eslint-shared.cjs', () => {
  it("holds the country lint's lists, each equal to the kit's copy", () => {
    const shared = require('../eslint-shared.cjs');
    const kit = require('../../country-kit/lib/country-lint.cjs');
    assert.deepEqual(Object.keys(shared).sort(), [...LISTS].sort());
    for (const name of LISTS) {
      assert.ok(kit[name] !== undefined, `country-lint.cjs exports ${name}`);
      assert.deepStrictEqual(shared[name], kit[name], name);
    }
  });

  it("is what eslint.config.js reads them from: it requires nothing of the kit's", () => {
    const text = fs.readFileSync(path.join(FE, 'eslint.config.js'), 'utf8');
    const all = specifiers(text);
    assert.ok(all.includes('./eslint-shared.cjs'), 'eslint.config.js requires ./eslint-shared.cjs');
    assert.deepEqual(
      all.filter((specifier) => specifier.includes('country-kit/')),
      [],
    );
  });
});
