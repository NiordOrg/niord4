import { expect, test } from '@playwright/test';

// Keycloak anonymous guest-fallback — the behavior the project must never regress.
// A slow/unreachable Keycloak must NOT redirect-loop: AuthService catches the init
// failure (or the 8s INIT_TIMEOUT_MS) and drops to an anonymous guest with a banner,
// rather than calling window.location.reload(), which would loop on a flaky IdP.
//
// Simulated by cutting off every Keycloak request — by its realm path, whichever host the
// served build names — so keycloak.init()'s silent check-sso can never complete. No
// credentials needed (this is the anonymous path); requires a dev server at
// http://localhost:4200. The wait is generous because the fallback only fires after the
// in-app init timeout.

test('unreachable Keycloak drops to an anonymous guest banner without redirect-looping', async ({
  page,
}) => {
  // Block the IdP entirely — the silent-check-sso iframe, token, everything.
  await page.route('**/realms/**', (route) => route.abort());

  // domcontentloaded (not load): the aborted auth iframe would otherwise hold the load event.
  await page.goto('/', { waitUntil: 'domcontentloaded' });

  // After the init timeout the shell shows the guest banner...
  await expect(page.getByText(/browsing as a guest/i)).toBeVisible({ timeout: 20000 });
  // ...and crucially we were NOT bounced to the Keycloak authorization endpoint.
  await expect(page).toHaveURL(/localhost:4200/);
  expect(page.url()).not.toMatch(/\/realms\//);
});
