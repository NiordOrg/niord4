/**
 * Negative tests of scripts/info-container-test.mjs's options, which it reads before it asks
 * docker for anything: none of these runs needs docker.
 *
 *   npm run test:guards      # from fe/
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';

const SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'info-container-test.mjs');

function run(...args) {
  const result = spawnSync(process.execPath, [SCRIPT, ...args], {
    encoding: 'utf8',
    timeout: 30_000,
  });
  return { status: result.status, output: `${result.stdout}${result.stderr}` };
}

describe('info-container-test options', () => {
  it('exits 2 on a --language that is not a language code', () => {
    for (const language of ['../x', 'EN', 'english', 'e']) {
      const result = run('--image', 'niord-test-info', '--language', language);
      assert.equal(result.status, 2, result.output);
      assert.match(result.output, /is not a language code/);
      assert.match(result.output, /usage: info-container-test\.mjs/);
    }
  });

  it('exits 2 on --language without a value', () => {
    const result = run('--image', 'niord-test-info', '--language');
    assert.equal(result.status, 2, result.output);
    assert.match(result.output, /unknown or incomplete option --language/);
  });

  it('exits 2 on --no-legal-doc together with --legal-doc, in either order', () => {
    for (const args of [
      ['--no-legal-doc', '--legal-doc', 'disclaimer.zz.html'],
      ['--legal-doc', 'disclaimer.zz.html', '--no-legal-doc', '--no-branding-text'],
    ]) {
      const result = run('--image', 'niord-test-info', ...args);
      assert.equal(result.status, 2, result.output);
      assert.match(result.output, /--no-legal-doc and --legal-doc contradict each other/);
    }
  });

  it('exits 2 on --no-branding-text given a value, which is no option', () => {
    const result = run('--image', 'niord-test-info', '--no-branding-text=en');
    assert.equal(result.status, 2, result.output);
    assert.match(result.output, /unknown or incomplete option --no-branding-text=en/);
  });

  it('exits 2 without --image', () => {
    const result = run('--language', 'zz');
    assert.equal(result.status, 2, result.output);
    assert.match(result.output, /--image is required/);
  });
});
