import type { Page } from '@playwright/test';

import { scenarioEnv } from './env';

// The slice of Node's globals the file needs: the e2e tsconfig loads no Node types.
declare const Buffer: { from(data: string, encoding: 'base64'): Uint8Array };

const ONE_PIXEL_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
  'base64',
);

/**
 * Keeps the page's browser on this machine: only the app's own origin, the public site's,
 * Keycloak's and MailHog's are let through. An image from anywhere else (a map tile) is answered
 * with one pixel, and every other request off the machine is aborted, so no scenario depends on a
 * host it cannot reach and none is asked.
 */
export async function keepOffline(page: Page, baseURL: string | undefined): Promise<void> {
  const allowed = new Set(
    [baseURL, scenarioEnv.infoUrl, scenarioEnv.keycloakOrigin, scenarioEnv.mailhogUrl]
      .filter((url): url is string => !!url)
      .map((url) => new URL(url).origin),
  );
  await page.context().route(
    (url) => !allowed.has(url.origin),
    (route) =>
      route.request().resourceType() === 'image'
        ? route.fulfill({
            status: 200,
            contentType: 'image/png',
            headers: { 'Access-Control-Allow-Origin': '*', 'Cache-Control': 'no-store' },
            body: ONE_PIXEL_PNG,
          })
        : route.abort(),
  );
}
