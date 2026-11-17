import { test } from '@playwright/test';

import { expect, test as authedTest } from './auth/fixtures';

// Anonymous route-guard redirects. The `roleGuard` (canMatch) sends an
// unauthenticated visitor to the Keycloak login for every role-gated top-level
// area. `/admin/sources` asserts this in admin-shell.spec.ts; these cover the
// other gated areas — including the `/schedule` area and the editor shell.
// Anonymous → no Keycloak credentials needed, just a dev server
// (`npm start`) at http://localhost:4200.
//
// Flow: the app boots a silent check-sso (no redirect, guest fallback), then the
// guard on the gated route calls keycloak.login(), which navigates to the realm's
// authorization endpoint.
//
// The last block is signed in (the Keycloak test credentials, see e2e/auth/README.md): it pins the
// router's `canceledNavigationResolution: 'computed'` (app.config.ts) through the editor's
// unsaved-changes prompt on the browser's Back.

const KEYCLOAK_LOGIN = /\/realms\/niord\/protocol\/openid-connect\/auth/;

const GATED_PATHS = ['/editor', '/schedule', '/sysadmin'] as const;

test.describe('role-gated routes redirect anonymous users to the Keycloak login', () => {
  for (const path of GATED_PATHS) {
    test(`anonymous deep link into ${path} redirects to Keycloak`, async ({ page }) => {
      await page.goto(path);
      await page.waitForURL(KEYCLOAK_LOGIN);
    });
  }
});

authedTest.describe('an unsaved-changes prompt on the browser Back keeps the history', () => {
  authedTest(
    'Back from a new, unsaved message asks; No keeps the message and the Start entry behind it',
    async ({ editorPage: page }) => {
      await page.goto('/editor');
      // Same dictionary caveat as editor-start.spec.ts: "New blank NW" or the backend's "New NW".
      await page.getByRole('button', { name: /^new (blank )?nw$/i }).click();
      await expect(page).toHaveURL(/\/editor\/edit(?:[?#]|$)/);

      // A new message cannot be reached again from Start, so Back asks first.
      await page.goBack();
      const confirm = page.getByRole('alertdialog');
      await expect(confirm.getByRole('heading', { name: 'Unsaved changes' })).toBeVisible();
      await confirm
        .locator('.confirm-foot')
        .getByRole('button', { name: 'No', exact: true })
        .click();
      await expect(confirm).toBeHidden();
      await expect(page).toHaveURL(/\/editor\/edit(?:[?#]|$)/);
      await expect(page.locator('app-editor-action-menu')).toHaveCount(1);

      // The history moved back to the message's entry instead of writing its address over the
      // Start screen's: a second Back reaches Start again and asks again.
      await page.goBack();
      await expect(confirm.getByRole('heading', { name: 'Unsaved changes' })).toBeVisible();
      await confirm
        .locator('.confirm-foot')
        .getByRole('button', { name: 'Yes', exact: true })
        .click();
      await expect(page).toHaveURL(/\/editor(?:[?#]|$)/);
      await expect(page.getByRole('heading', { name: /create a message/i })).toBeVisible();

      // Yes dropped the message: Forward finds nothing to ask about (the seed is used up, so the
      // template route returns to Start).
      await page.goForward();
      await expect(page).toHaveURL(/\/editor(?:[?#]|$)/);
      await expect(page.getByRole('heading', { name: /create a message/i })).toBeVisible();
      await expect(confirm).toHaveCount(0);
    },
  );
});
