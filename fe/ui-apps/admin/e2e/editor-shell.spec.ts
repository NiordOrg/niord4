import { expect, test } from './auth/fixtures';

// Smoke test for the editor shell. Run with `npx playwright test`
// while a dev server is up at http://localhost:4200 (npm start) and the Keycloak
// test credentials are exported (see e2e/auth/README.md).
//
// Backend-light on purpose: it exercises route wiring + the missing-template guard
// (a seedless template route redirects to the Start screen instead of crashing)
// without needing a specific draft message to exist. The full edit→save and
// copy→referenceType flows are driven against a live backend during manual testing.

test.describe('editor shell', () => {
  test('bare /editor mounts the Start screen (no auto-created draft)', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/editor');

    // Bare `/editor` is the new-message Start screen — it does not redirect to a blank draft.
    await expect(authenticatedPage).toHaveURL(/\/editor(?:[?#]|$)/);
    await expect(
      authenticatedPage.getByRole('heading', { name: /create a message/i }),
    ).toBeVisible();

    // The persistent chrome (tab strip + cog action menu) mounts.
    await expect(authenticatedPage.locator('app-editor-action-menu')).toHaveCount(1);
  });

  test('a seedless template route redirects to the Start screen, not a crash', async ({
    authenticatedPage,
  }) => {
    // Entering the template hand-off route with no seeded template would dereference null.
    // `templateSeedGuard` blocks activation and returns the user to the Start screen: the seed
    // lives in a transient store, so a deep link or reload ALWAYS re-bootstraps empty and no
    // Retry could ever resolve it. (The retry panel still exists for a genuinely failed load of
    // a real id — covered by editor-edit-form.spec.ts, "an unknown id surfaces the retry panel".)
    await authenticatedPage.goto('/editor/edit');

    await expect(authenticatedPage).toHaveURL(/\/editor(?:[?#]|$)/);
    await expect(
      authenticatedPage.getByRole('heading', { name: /create a message/i }),
    ).toBeVisible();
  });

  test('the status route is registered and mounts the shell (deep-linkable)', async ({
    authenticatedPage,
  }) => {
    // Deep-linking a status URL must resolve under the shell (not 404). The id need not exist:
    // the load failure surfaces an inline retry, but the persistent chrome mounts either way.
    // The full verify→publish→cancel state machine is driven against a live backend during manual testing.
    await authenticatedPage.goto('/editor/status/does-not-exist');

    await expect(authenticatedPage).toHaveURL(/\/editor\/status\/[^/]+(?:[?#]|$)/);
    await expect(authenticatedPage.locator('app-editor-action-menu')).toHaveCount(1);
  });

  test('the history route is registered and mounts the shell (deep-linkable)', async ({
    authenticatedPage,
  }) => {
    // Same contract as the status route: the revision-diff page deep-links under the shell.
    // The real revision table + JSON diff are exercised against a live message during manual testing.
    await authenticatedPage.goto('/editor/history/does-not-exist');

    await expect(authenticatedPage).toHaveURL(/\/editor\/history\/[^/]+(?:[?#]|$)/);
    await expect(authenticatedPage.locator('app-editor-action-menu')).toHaveCount(1);
  });

  test('the comments route is registered and mounts the shell (deep-linkable)', async ({
    authenticatedPage,
  }) => {
    // The comments page deep-links under the shell. The list / editor / acknowledge flow runs
    // against a live message during manual testing (it needs a persisted message with a comment thread).
    await authenticatedPage.goto('/editor/comments/does-not-exist');

    await expect(authenticatedPage).toHaveURL(/\/editor\/comments\/[^/]+(?:[?#]|$)/);
    await expect(authenticatedPage.locator('app-editor-action-menu')).toHaveCount(1);
  });
});
