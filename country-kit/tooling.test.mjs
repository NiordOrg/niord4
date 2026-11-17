/**
 * Tests for the kit's own tooling in niord-ng's tree: the kit sits beside fe/, so it carries the
 * configuration Prettier, ESLint and git look for in the folder of a file, and its tests find the
 * base product's npm packages through test-fixtures/base-modules.mjs. None of it is in the source
 * zip.
 *
 *   npm run test:guards      # from fe/
 *
 * The kit's Prettier configuration is the base product's, byte for byte, and its ESLint
 * configuration declares the same Node globals for the kit's scripts as the base product's does
 * for its own.
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { where } from './lib/where.mjs';
import { requireFromBase } from './test-fixtures/base-modules.mjs';

const KIT = path.dirname(fileURLToPath(import.meta.url));
const FE = where().base;

/** The globals of the one block of `config` that lints every .mjs script and declares globals. */
function scriptGlobals(config, label) {
  const blocks = config.filter(
    (block) => block.files?.includes('**/*.mjs') && block.languageOptions?.globals,
  );
  assert.equal(blocks.length, 1, `${label}: one block declares the globals of **/*.mjs`);
  return blocks[0].languageOptions.globals;
}

describe("the kit's tooling", () => {
  it("formats with the base product's Prettier configuration, byte for byte", () => {
    assert.deepEqual(
      fs.readFileSync(path.join(KIT, '.prettierrc')),
      fs.readFileSync(path.join(FE, '.prettierrc')),
      'country-kit/.prettierrc differs from fe/.prettierrc',
    );
  });

  it('checks its files out with LF line endings', () => {
    const lines = fs.readFileSync(path.join(KIT, '.gitattributes'), 'utf8').split(/\r?\n/);
    assert.ok(lines.includes('* text=auto eol=lf'), 'country-kit/.gitattributes');
  });

  it("lints its scripts with the base product's Node globals", async () => {
    const { default: kit } = await import(pathToFileURL(path.join(KIT, 'eslint.config.mjs')).href);
    const base = requireFromBase(path.join(FE, 'eslint.config.js'));
    assert.deepEqual(
      scriptGlobals(kit, 'country-kit/eslint.config.mjs'),
      scriptGlobals(base, 'fe/eslint.config.js'),
    );
  });

  it("finds the base product's npm packages in fe/node_modules", () => {
    const resolved = requireFromBase.resolve('js-yaml');
    assert.ok(
      !path.relative(path.join(FE, 'node_modules'), resolved).startsWith('..'),
      `js-yaml resolves to ${resolved}`,
    );
  });
});
