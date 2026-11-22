import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { checkEnvironment, checkEnvironmentText } from './lib/environment-check.mjs';

const environment = ({
  api = "'/rest'",
  keycloak = "'/auth'",
  wms = "'/wms/'",
  extra = '',
} = {}) => `// A country's production environment.
import type { AppConfig } from '@niord/admin/internal/core/config/app-config.interface';

const realm = 'niord';

export const environment: AppConfig = {
  apiBaseUrl: ${api}, // the API
  appName: 'Niord',
  helpUrls: { messages: '/editor-manual/manual.html#messages' },
  keycloak: {
    url: ${keycloak},
    realm,
    clientId: 'niord-web',
  },
  map: { openSeaMapLayerEnabled: true${wms === undefined ? '' : `, wmsUrl: ${wms}`} },
  ${extra}
  production: true,
};
`;

describe('the production environment check', () => {
  it('passes origin-relative addresses, and a map without a WMS address', () => {
    assert.deepEqual(checkEnvironmentText(environment()), []);
    assert.deepEqual(checkEnvironmentText(environment({ wms: undefined })), []);
  });

  it('fails an apiBaseUrl on another origin', () => {
    assert.deepEqual(
      checkEnvironmentText(environment({ api: "'https://niord.example.org/rest'" })),
      [
        "environment.production.ts: apiBaseUrl is 'https://niord.example.org/rest', not origin-relative: a deployed bundle reaches its backend, Keycloak and the WMS proxy on its own origin (/rest, /auth, /wms/)",
      ],
    );
  });

  it('fails a keycloak.url on another origin, a scheme-relative one too', () => {
    const [problem] = checkEnvironmentText(environment({ keycloak: "'//sso.example.org/auth'" }));
    assert.match(problem, /keycloak\.url is '\/\/sso\.example\.org\/auth', not origin-relative/);
  });

  it('fails a map.wmsUrl on another origin', () => {
    const [problem] = checkEnvironmentText(environment({ wms: '"https://wms.example.org/"' }));
    assert.match(problem, /map\.wmsUrl is 'https:\/\/wms\.example\.org\/', not origin-relative/);
  });

  it('fails a value that is not a string literal', () => {
    for (const api of ['API', "'/re' + 'st'", '`${base}/rest`'])
      assert.match(
        checkEnvironmentText(environment({ api }))[0],
        /apiBaseUrl is not a string literal/,
      );
  });

  it('fails a spread that might carry an address, and a missing one', () => {
    assert.match(
      checkEnvironmentText(
        environment({ extra: '...shared,' }).replace(/ {2}apiBaseUrl.*\n/, ''),
      )[0],
      /apiBaseUrl may come from a spread/,
    );
    assert.match(
      checkEnvironmentText(environment().replace(/ {2}apiBaseUrl.*\n/, ''))[0],
      /does not set apiBaseUrl/,
    );
  });

  it('fails a production environment without production: true, so no bundle carries the token stub', () => {
    const without = environment().replace(/ {2}production: true,\n/, '');
    assert.deepEqual(checkEnvironmentText(without), [
      "environment.production.ts does not set production: it is `true`, as a production bundle without it lets the e2e test's Keycloak token stub (window.__NIORD_TEST_AUTH__) take Keycloak's place and compiles in the dev routes",
    ]);
    for (const value of ['false', "'true'", '!0', 'isProduction'])
      assert.deepEqual(
        checkEnvironmentText(environment().replace('production: true', `production: ${value}`)),
        [
          "environment.production.ts: production is not `true`: a production bundle without it lets the e2e test's Keycloak token stub (window.__NIORD_TEST_AUTH__) take Keycloak's place and compiles in the dev routes",
        ],
        value,
      );
    // The example country's own file passes.
    const example = path.join(
      path.dirname(fileURLToPath(import.meta.url)),
      'example/fe/admin/environments/environment.production.ts',
    );
    assert.deepEqual(checkEnvironment(example, 'environment.production.ts'), []);
  });

  it("holds the base product's and the example country's test environments to a production build with the TEST mode", () => {
    // Set-up checks a country's production file only; a country's own test file is its own to
    // change. These two are the base product's: a test build of either registers no dev route,
    // refuses the e2e token stub, and keeps the TEST badge.
    const kit = path.dirname(fileURLToPath(import.meta.url));
    for (const file of [
      path.join(kit, '../fe/ui-apps/admin/src/environments/environment.test.ts'),
      path.join(kit, 'example/fe/admin/environments/environment.test.ts'),
    ]) {
      assert.ok(fs.existsSync(file), file);
      assert.deepEqual(checkEnvironment(file, 'environment.test.ts'), [], file);
      assert.match(fs.readFileSync(file, 'utf8'), /^\s*mode: 'test',$/m, file);
    }
  });

  it('checks nothing when the country has no production environment', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'niord-env-'));
    assert.deepEqual(checkEnvironment(path.join(dir, 'environment.production.ts')), []);
    fs.writeFileSync(
      path.join(dir, 'environment.production.ts'),
      environment({ keycloak: "'https://x/auth'" }),
    );
    assert.equal(checkEnvironment(path.join(dir, 'environment.production.ts'), 'x.ts').length, 1);
  });
});
