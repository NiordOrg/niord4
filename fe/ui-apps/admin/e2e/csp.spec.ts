import type { Page, Response } from '@playwright/test';

import { clearCspViolations, cspViolationsOf, expect, test } from './auth/fixtures';

// The administration app under the Content-Security-Policy its backend sends. The dev server sends
// no policy, so this file runs only against the bundle the backend serves: a non-production build
// (the authenticated tests need the token stub, which a production build refuses), E2E_BASE_URL
// naming the backend and E2E_CSP set. The guard in the fixtures fails every test built on the
// fixtures' `test` that makes the browser refuse anything — every test of this file; a spec that
// imports `test` from @playwright/test runs unguarded. The last test proves that the guard sees a
// refusal.
//
// The tile servers are answered here with a one-pixel image. The browser checks the policy before
// a request reaches the route, so a tile that arrives at the route was admitted by the policy.

// The slice of Node's globals the file needs: the e2e tsconfig loads no Node types.
declare const process: { readonly env: Record<string, string | undefined> };
declare const Buffer: { from(data: string, encoding: 'base64'): Uint8Array };

const RUN = Boolean(process.env['E2E_CSP']);
const KEYCLOAK_URL = process.env['KEYCLOAK_URL'];

// The fixtures' Keycloak default is a deployed test realm, never the one this run is for.
if (RUN && !KEYCLOAK_URL) {
  throw new Error(
    'csp.spec.ts needs KEYCLOAK_URL, the Keycloak the served bundle and the backend use ' +
      '(the local stack: http://localhost:8080/auth). See e2e/auth/README.md.',
  );
}

const KEYCLOAK_ORIGIN = KEYCLOAK_URL ? new URL(KEYCLOAK_URL).origin : '';

// The map kit's defaults, which a blank tile setting falls back to.
const DEFAULT_OSM_URL = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
const DEFAULT_OPEN_SEA_MAP_URL = 'https://t1.openseamap.org/seamark/{z}/{x}/{y}.png';

const ONE_PIXEL_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
  'base64',
);

type Directives = ReadonlyMap<string, readonly string[]>;

/** The one Content-Security-Policy a response carries, as sent. */
async function policyOf(response: Response | null): Promise<string> {
  if (!response) throw new Error('the navigation returned no response');
  const values = (await response.headersArray())
    .filter((header) => header.name.toLowerCase() === 'content-security-policy')
    .map((header) => header.value);
  expect(
    values,
    `one Content-Security-Policy on ${response.url()} (is E2E_BASE_URL the backend serving the bundle?)`,
  ).toHaveLength(1);
  return values[0];
}

function directivesOf(policy: string): Directives {
  const directives = new Map<string, readonly string[]>();
  for (const part of policy.split(';')) {
    const [name, ...sources] = part.trim().split(/\s+/);
    if (name) directives.set(name.toLowerCase(), sources);
  }
  return directives;
}

// A first host label holding a range, such as `{a-c}.tile.example.org` or `t{1-4}.example.org`,
// which OpenLayers expands into several hosts; the backend admits it as a wildcard.
const RANGE_HOST = /^((?:https?:)?\/\/)[^./]*\{[^}]+\}[^./]*\./i;

/** The source a tile template needs in img-src, read as the browser reads the template. */
function tileSourceOf(template: string): string | undefined {
  const filled = template.trim().replace(/\{[zxy]\}/g, '0');
  const wildcard = RANGE_HOST.test(filled) ? '*.' : '';
  const address = filled.replace(RANGE_HOST, '$1');
  if (address.startsWith('//')) return wildcard + new URL(`https:${address}`).host;
  // A relative template loads from the app's own origin, which 'self' admits.
  if (!/^https?:\/\//i.test(address)) return undefined;
  const url = new URL(address);
  return `${url.protocol}//${wildcard}${url.host}`;
}

/** The tile sources the served settings ask for, the map kit's defaults where they are blank. */
async function expectedTileSources(page: Page): Promise<string[]> {
  const response = await page.request.get('/rest/settings/web');
  expect(response.ok()).toBe(true);
  expect(response.headers()['content-security-policy'], 'no policy on /rest').toBeUndefined();
  const settings = (await response.json()) as Record<string, unknown>;
  const template = (key: string, fallback: string): string => {
    const value = settings[key];
    return typeof value === 'string' && value.trim() !== '' ? value : fallback;
  };
  return [
    tileSourceOf(template('osmUrl', DEFAULT_OSM_URL)),
    tileSourceOf(template('openSeaMapUrl', DEFAULT_OPEN_SEA_MAP_URL)),
  ].filter((source): source is string => source !== undefined);
}

async function expectAdminPolicy(page: Page, policy: string): Promise<void> {
  const directives = directivesOf(policy);
  expect(directives.get('default-src')).toEqual(["'self'"]);
  expect(directives.get('script-src')).toEqual(["'self'"]);
  expect(directives.get('object-src')).toEqual(["'none'"]);
  expect(directives.get('frame-ancestors')).toEqual(["'self'"]);
  expect(directives.get('connect-src')).toContain(KEYCLOAK_ORIGIN);
  expect(directives.get('frame-src')).toContain(KEYCLOAK_ORIGIN);
  const imgSrc = directives.get('img-src') ?? [];
  for (const source of await expectedTileSources(page)) {
    expect(imgSrc, `img-src admits the tile source ${source}`).toContain(source);
  }
}

/** Answers every image from another origin than the app's and Keycloak's; returns the hits. */
async function stubTiles(page: Page, appOrigin: string): Promise<string[]> {
  const hits: string[] = [];
  await page.route(
    (url) => url.origin !== appOrigin && url.origin !== KEYCLOAK_ORIGIN,
    async (route) => {
      if (route.request().resourceType() !== 'image') return route.continue();
      hits.push(route.request().url());
      return route.fulfill({
        status: 200,
        contentType: 'image/png',
        headers: { 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store' },
        body: ONE_PIXEL_PNG,
      });
    },
  );
  return hits;
}

/** Lets the lazy views, the deferred maps and their tiles finish before the next step. */
async function settle(page: Page): Promise<void> {
  await page.waitForLoadState('networkidle');
}

test.describe('the administration app under its Content-Security-Policy', () => {
  test.skip(!RUN, 'E2E_CSP is not set: the dev server sends no policy (e2e/auth/README.md)');

  test.beforeEach(async ({ page }) => {
    // The copy the tests click is English whatever the deployment's default language.
    await page.addInitScript(() => {
      if (!localStorage.getItem('niord.language')) localStorage.setItem('niord.language', 'en');
    });
  });

  test('anonymous: the landing page carries the policy, draws tiles and runs check-sso', async ({
    page,
    baseURL,
  }) => {
    const appOrigin = new URL(baseURL ?? '').origin;
    const tiles = await stubTiles(page, appOrigin);
    // keycloak-js's check-sso: a hidden frame to Keycloak, which returns to silent-check-sso.html.
    const keycloakFrame = page.waitForRequest(
      (request) =>
        request.url().startsWith(`${KEYCLOAK_ORIGIN}/`) && request.resourceType() === 'document',
    );
    const silentCheckSso = page.waitForResponse(
      (response) => new URL(response.url()).pathname === '/silent-check-sso.html',
    );

    const response = await page.goto('/');
    const policy = await policyOf(response);
    await expectAdminPolicy(page, policy);

    await keycloakFrame;
    expect(await policyOf(await silentCheckSso)).toBe(policy);
    await expect.poll(() => tiles.length, 'the landing map requests tiles').toBeGreaterThan(0);

    // The policy is the HTML documents' alone: neither the API nor the scripts carry one.
    const html = (await response?.text()) ?? '';
    const mainScript = /<script[^>]+src="([^"]*main[^"]*\.js)"/.exec(html)?.[1];
    expect(mainScript, 'index.html loads a main script').toBeDefined();
    const script = await page.request.get(new URL(mainScript ?? '', `${appOrigin}/`).href);
    expect(script.ok()).toBe(true);
    expect(script.headers()['content-security-policy']).toBeUndefined();

    await settle(page);
  });

  test('anonymous: a deep link carries the same policy and draws tiles', async ({
    page,
    baseURL,
  }) => {
    const appOrigin = new URL(baseURL ?? '').origin;
    const tiles = await stubTiles(page, appOrigin);

    const response = await page.goto('/messages/map');
    await expectAdminPolicy(page, await policyOf(response));
    await expect(page.locator('app-ol-map')).toBeVisible();
    await expect.poll(() => tiles.length, 'the message map requests tiles').toBeGreaterThan(0);

    await settle(page);
  });

  test('a real login through Keycloak returns to the app signed in', async ({ page, baseURL }) => {
    test.skip(!process.env['E2E_CSP_LOGIN'], 'E2E_CSP_LOGIN is not set');
    const username = process.env['KEYCLOAK_TEST_USERNAME'];
    const password = process.env['KEYCLOAK_TEST_PASSWORD'];
    if (!username || !password) {
      throw new Error('the login needs KEYCLOAK_TEST_USERNAME and KEYCLOAK_TEST_PASSWORD');
    }
    const appOrigin = new URL(baseURL ?? '').origin;
    await stubTiles(page, appOrigin);

    await page.goto('/');
    await page.getByRole('button', { name: 'Login', exact: true }).first().click();
    await page.waitForURL((url) => url.origin === KEYCLOAK_ORIGIN);
    await page.locator('#username').fill(username);
    await page.locator('#password').fill(password);
    // Back on the app, keycloak-js exchanges the code for tokens: a request connect-src admits.
    const tokenExchange = page.waitForResponse(
      (response) =>
        response.url().startsWith(`${KEYCLOAK_ORIGIN}/`) &&
        response.url().endsWith('/protocol/openid-connect/token'),
    );
    await page.locator('#kc-login').click();
    await page.waitForURL((url) => url.origin === appOrigin);
    expect((await tokenExchange).ok()).toBe(true);
    await expect(page.getByRole('button', { name: 'Login', exact: true })).toHaveCount(0);

    await settle(page);
  });

  test('a sysadmin walks the lists, the maps, the settings and the mail queue', async ({
    sysadminPage: page,
    baseURL,
  }) => {
    const appOrigin = new URL(baseURL ?? '').origin;
    const tiles = await stubTiles(page, appOrigin);

    await page.goto('/messages/table');
    await expect(page.getByRole('button', { name: 'Login', exact: true })).toHaveCount(0);
    await settle(page);

    await page.goto('/messages/map');
    await expect(page.locator('app-ol-map')).toBeVisible();
    await expect.poll(() => tiles.length, 'the message map requests tiles').toBeGreaterThan(0);
    await settle(page);

    const tilesBefore = tiles.length;
    await page.goto('/admin/charts');
    await expect(page.locator('app-ol-map')).toBeVisible();
    await expect
      .poll(() => tiles.length, 'the chart overview requests tiles')
      .toBeGreaterThan(tilesBefore);
    await settle(page);

    await page.goto('/sysadmin/settings');
    await expect(page.locator('app-admin-section-page')).toBeVisible();
    await settle(page);

    await page.goto('/sysadmin/mails');
    await expect(page.getByPlaceholder('Recipient')).toBeVisible();
    await settle(page);
  });

  // The one case of a frame inheriting the page's policy, so a run without a queued mail says it
  // skipped it rather than passing without it (e2e/auth/README.md: the data a full run needs).
  test("a mail's preview, a sandboxed frame, inherits the policy", async ({
    sysadminPage: page,
  }) => {
    await page.goto('/sysadmin/mails');
    await expect(page.getByPlaceholder('Recipient')).toBeVisible();
    const openMail = page.getByRole('button', { name: 'Open mail details' }).first();
    const hasMail = await openMail
      .waitFor({ state: 'visible', timeout: 5000 })
      .then(() => true)
      .catch(() => false);
    test.skip(!hasMail, 'no mail in the queue');

    await openMail.click();
    await expect(page.getByRole('dialog').locator('app-sandboxed-html iframe')).toBeVisible();
    await settle(page);
  });

  test('a new message opens in the editor with its rich-text editor', async ({
    sysadminPage: page,
  }) => {
    await page.goto('/editor');
    // The label comes from the deployment's dictionary, which may shorten "New blank NW".
    const create = page.getByRole('button', { name: /^new (blank )?n[wm]$/i }).first();
    const offered = await create
      .waitFor({ state: 'visible', timeout: 10000 })
      .then(() => true)
      .catch(() => false);
    test.skip(!offered, 'the session domain offers no message to create');

    await create.click();
    await expect(page).toHaveURL(/\/editor\/edit(?:[?#]|$)/);
    const description = page.locator('app-part-card').first().locator('app-description-field');
    await description.locator('[role="button"][aria-expanded]').first().click();
    await expect(description.locator('.ProseMirror').first()).toBeVisible();

    await settle(page);
  });

  test('the guard sees a refused image', async ({ page, baseURL }) => {
    const probe = 'https://csp-probe.invalid/x.png';
    await stubTiles(page, new URL(baseURL ?? '').origin);
    await page.goto('/');
    await page.evaluate((src) => {
      const image = document.createElement('img');
      image.src = src;
      document.body.append(image);
    }, probe);

    // Both channels report it: the browser's own console message and the listener's.
    await expect
      .poll(() => cspViolationsOf(page).filter((message) => message.includes(probe)).length)
      .toBeGreaterThanOrEqual(2);
    expect(
      cspViolationsOf(page).some((message) => message.startsWith('[csp-violation] img-src')),
    ).toBe(true);
    clearCspViolations(page);
  });
});
