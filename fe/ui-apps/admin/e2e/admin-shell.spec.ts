import { test as anonTest } from '@playwright/test';

import { expect, test } from './auth/fixtures';

// Smoke tests for the Admin / Sysadmin area shells. Run with `npx playwright test`
// while a dev server is up at http://localhost:4200 (npm start). The authed tests
// need the Keycloak test user to hold the admin (and for the sysadmin block, the
// sysadmin) role; the first test of each block fails fast if the role is missing.
// Backend-light on purpose: route wiring, rail, overview cards, and stub sections —
// the full section CRUD flows have their own specs.

anonTest.describe('admin area (anonymous)', () => {
  anonTest('a deep link into /admin redirects to the Keycloak login', async ({ page }) => {
    await page.goto('/admin/sources');
    await page.waitForURL(/\/realms\/niord\/protocol\/openid-connect\/auth/);
  });
});

test.describe('admin area shell', () => {
  test('bare /admin redirects to the overview and renders the section cards', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/admin');
    await expect(authenticatedPage).toHaveURL(/\/admin\/overview(?:[?#]|$)/);
    // One launcher card per non-overview section (6 in the admin area).
    // .first(): the rail link and the overview card share the section name.
    await expect(authenticatedPage.getByRole('link', { name: /Charts/ }).first()).toBeVisible();
    await expect(
      authenticatedPage.getByRole('link', { name: /Mailing Lists/ }).first(),
    ).toBeVisible();
  });

  test('the rail navigates to Publications, the last section to ship', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/admin/overview');
    await authenticatedPage
      .getByRole('navigation')
      .getByRole('link', { name: 'Publications' })
      .click();
    await expect(authenticatedPage).toHaveURL(/\/admin\/publications(?:[?#]|$)/);
    await expect(
      authenticatedPage.getByRole('heading', { level: 2, name: /Publications/ }),
    ).toBeVisible();
    await expect(authenticatedPage.getByText('Coming Soon')).toHaveCount(0);
  });

  test('section deep links resolve under the shell', async ({ authenticatedPage }) => {
    await authenticatedPage.goto('/admin/users');
    await expect(authenticatedPage).toHaveURL(/\/admin\/users(?:[?#]|$)/);
    // Direct child, not a descendant: the routed section pane lives inside this same host, and
    // the users list renders its own <nav class="pager"> once the result set exceeds one page.
    // A bare `app-admin-area-shell nav` then resolves to two elements and trips strict mode —
    // which made this test pass or fail depending on whether the list request had landed yet.
    const rail = authenticatedPage.locator('app-admin-area-shell > div > nav');
    await expect(rail).toBeVisible();
    // Proof it is the section rail rather than an empty shell. Deliberately not matched by
    // label: the section names come from the runtime dictionary, which the backend overrides.
    await expect(rail.getByRole('link').first()).toBeVisible();
  });
});

test.describe('sysadmin area shell', () => {
  test('bare /sysadmin redirects to its overview with the sysadmin rail', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/sysadmin');
    await expect(authenticatedPage).toHaveURL(/\/sysadmin\/overview(?:[?#]|$)/);
    const batchEntry = authenticatedPage
      .getByRole('navigation')
      .getByRole('link', { name: /Batch Jobs/ });
    await expect(batchEntry).toBeVisible();
    // Deliberately: the running-jobs badge renders even when the count is 0.
    await expect(batchEntry.getByLabel(/running batch jobs/)).toBeVisible();
  });

  test('a retired integration URL falls through to home', async ({ authenticatedPage }) => {
    await authenticatedPage.goto('/sysadmin/integration');
    // Deliberately unbuilt: unknown URLs redirect to home.
    await expect(authenticatedPage).toHaveURL(/\/(?:$|\?)/);
  });

  test('trailing-slash bookmarks resolve to their sections', async ({ authenticatedPage }) => {
    // Bookmarked URLs use trailing slashes on /sysadmin/script-resources/ and
    // /sysadmin/batch/ for their two param states — those shapes must keep resolving.
    // Script Resources is a real page now; the bookmark resolves to its section heading.
    await authenticatedPage.goto('/sysadmin/script-resources/');
    await expect(authenticatedPage).toHaveURL(/\/sysadmin\/script-resources\/?(?:[?#]|$)/);
    await expect(
      authenticatedPage.getByRole('heading', { name: 'Script Resources' }),
    ).toBeVisible();

    // Batch Jobs is a real page — the bookmark resolves to it
    // (trailing slash = no :batchName pre-selection).
    await authenticatedPage.goto('/sysadmin/batch/');
    await expect(authenticatedPage).toHaveURL(/\/sysadmin\/batch\/?(?:[?#]|$)/);
    await expect(authenticatedPage.getByRole('heading', { name: 'Batch Jobs' })).toBeVisible();
  });
});
