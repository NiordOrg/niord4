/**
 * Negative tests of scripts/info-separation-check.mjs, on crafted stats files.
 *
 *   npm run test:guards      # from fe/
 *
 * Each stats file names one first-party input beside a package one, as `ng build --stats-json`
 * writes them: in the base product workspace, and in a country workspace under --root and
 * --country.
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { after, before, describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';

const SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'info-separation-check.mjs');

let dir;
let count = 0;

/** Runs the check, from `dir`, on a stats file whose bundle is built from `input`. */
function check(input, ...args) {
  const file = `stats-${++count}.json`;
  fs.writeFileSync(
    path.join(dir, file),
    JSON.stringify({
      outputs: {
        'main-ABCDEFGH.js': {
          inputs: {
            [input]: { bytesInOutput: 1 },
            'node_modules/@angular/core/fesm2022/core.mjs': {},
          },
        },
      },
    }),
  );
  const result = spawnSync(process.execPath, [SCRIPT, '--stats', file, ...args], {
    cwd: dir,
    encoding: 'utf8',
  });
  return { status: result.status, output: `${result.stdout}${result.stderr}` };
}

const COUNTRY = ['--root', '.niord/base/', '--country', 'xx'];

describe('info-separation-check', () => {
  before(() => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), 'info-separation-check-'));
  });
  after(() => fs.rmSync(dir, { recursive: true, force: true }));

  describe('in the base product workspace', () => {
    it('passes the public site, the shared folders and a package', () => {
      for (const input of [
        'ui-apps/info/src/main.ts',
        'styles/tokens.css',
        'shared-map/src/map.ts',
        'shared-html/src/safe.ts',
      ]) {
        const result = check(input);
        assert.equal(result.status, 0, `${input}: ${result.output}`);
        assert.match(
          result.output,
          /1 first-party module\(s\), all under ui-apps\/info\/ or styles\//,
        );
      }
    });

    it('fails administration code', () => {
      const result = check('ui-apps/admin/src/app/core/auth/auth.service.ts');
      assert.equal(result.status, 1, result.output);
      assert.match(result.output, /the administration application is a separate release/);
    });

    it('fails a path outside the workspace', () => {
      const result = check('../x/y.ts');
      assert.equal(result.status, 1, result.output);
      assert.match(result.output, /outside the workspace/);
    });

    it('fails keycloak-js', () => {
      const result = check('node_modules/keycloak-js/lib/keycloak.js');
      assert.equal(result.status, 1, result.output);
      assert.match(result.output, /keycloak-js is banned here/);
    });

    it("fails a country folder that is not the base product's", () => {
      const result = check('info/app/footer/footer.component.ts');
      assert.equal(result.status, 1, result.output);
      assert.match(result.output, /not under ui-apps\/info\//);
    });
  });

  describe('in a country workspace (--root .niord/base/ --country xx)', () => {
    for (const input of [
      '.niord/base/ui-apps/admin/src/app/app.config.ts',
      'admin/app/harbour/harbour-page.ts',
      '.niord/generated/admin/language.config.ts',
    ])
      it(`fails ${input}`, () => {
        const result = check(input, ...COUNTRY);
        assert.equal(result.status, 1, result.output);
        assert.match(result.output, /the administration application is a separate release/);
      });

    for (const input of [
      'styles/tokens.yy.css',
      'ui-apps/info/src/main.ts',
      'styles/other.css',
      '../x.ts',
    ])
      it(`fails ${input}`, () => {
        const result = check(input, ...COUNTRY);
        assert.equal(result.status, 1, result.output);
      });

    for (const input of [
      'info/app/footer/footer.component.ts',
      '.niord/base/ui-apps/info/src/main.ts',
      '.niord/base/shared-map/src/map.ts',
      '.niord/generated/info/language.config.ts',
      'styles/tokens.xx.css',
      'styles/fonts.xx.css',
      'styles/fonts/lato/lato-latin-400-normal.woff2',
    ])
      it(`passes ${input}`, () => {
        const result = check(input, ...COUNTRY);
        assert.equal(result.status, 0, result.output);
      });

    it('fails administration code of the base product under --root alone', () => {
      const result = check('.niord/base/ui-apps/admin/src/main.ts', '--root', '.niord/base/');
      assert.equal(result.status, 1, result.output);
    });
  });

  describe('usage', () => {
    for (const [what, args] of [
      ['--country without --root', ['--country', 'xx']],
      ['a country code of capitals', ['--root', '.niord/base/', '--country', 'XX']],
      ['a country code of three letters', ['--root', '.niord/base/', '--country', 'xxx']],
      ['a country code of four letters', ['--root', '.niord/base/', '--country', 'xxxx']],
      ['a root out of the workspace', ['--root', '../base/']],
      ['a root without its "/"', ['--root', '.niord/base']],
      ['an unknown option', ['--admin']],
    ])
      it(`exits 2 on ${what}`, () => {
        const result = check('ui-apps/info/src/main.ts', ...args);
        assert.equal(result.status, 2, result.output);
      });

    it('exits 2 on a missing stats file', () => {
      const result = spawnSync(process.execPath, [SCRIPT, '--stats', 'missing.json'], {
        cwd: dir,
        encoding: 'utf8',
      });
      assert.equal(result.status, 2);
    });
  });
});
