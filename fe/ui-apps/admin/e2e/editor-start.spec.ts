import { expect, test } from './auth/fixtures';

// Smoke tests for the editor Start screen. Run with `npx playwright test`
// while a dev server is up at http://localhost:4200 (npm start) and the Keycloak test
// credentials are exported (see e2e/auth/README.md).
//
// Backend-light: it exercises the bare-`/editor` Start route + its domain/role gating and
// verifies the Start screen did NOT break the deep-linkable sub-routes. The full
// create→edit→save round-trip runs against a live backend during manual testing.

test.describe('editor Start screen', () => {
  test('bare /editor renders the Start screen (no auto-created draft)', async ({ editorPage }) => {
    await editorPage.goto('/editor');

    // Stays on the bare route — it deliberately does not redirect to a blank draft.
    await expect(editorPage).toHaveURL(/\/editor(?:[?#]|$)/);
    await expect(editorPage.getByRole('heading', { name: /create a message/i })).toBeVisible();
    await expect(editorPage.locator('app-editor-action-menu')).toHaveCount(1);
  });

  test('"New blank NW" enters the editor edit route with a fresh draft', async ({ editorPage }) => {
    await editorPage.goto('/editor');

    // The label comes from the runtime `web` dictionary, which overrides the bundled
    // "New blank NW" with "New NW" — the backend wins, so accept either. ANCHORED on purpose:
    // an unanchored /new nw/i would also match the sibling "New NW from Template" button.
    await editorPage.getByRole('button', { name: /^new (blank )?nw$/i }).click();

    // Seeded via the transient template store, the create flow enters the template/new-blank route.
    await expect(editorPage).toHaveURL(/\/editor\/edit(?:[?#]|$)/);
    await expect(editorPage.locator('app-editor-action-menu')).toHaveCount(1);
  });

  test('a plain user sees the drafts list but no create cards', async ({ userPage }) => {
    await userPage.goto('/editor');

    await expect(userPage.getByRole('heading', { name: /create a message/i })).toBeVisible();
    // Create affordances are editor-only; a case-officer user gets read-only proof-reading access.
    // Same dictionary caveat as above — /new blank/i alone passed vacuously, because the button
    // it looked for does not exist under that name for ANY role.
    await expect(userPage.getByRole('button', { name: /^new (blank )?n[wm]$/i })).toHaveCount(0);
    await expect(userPage.locator('app-recently-edited-drafts')).toHaveCount(1);
  });

  test('deep link /editor/edit/:id still resolves under the shell (no 404)', async ({
    editorPage,
  }) => {
    // Bookmarked edit URLs must still resolve. The id need not exist —
    // the load surfaces an inline retry, but the route resolves and the chrome mounts.
    await editorPage.goto('/editor/edit/does-not-exist');

    await expect(editorPage).toHaveURL(/\/editor\/edit\/[^/]+(?:[?#]|$)/);
    await expect(editorPage.locator('app-editor-action-menu')).toHaveCount(1);
  });

  test('deep link /editor/status/:id still resolves under the shell (no 404)', async ({
    editorPage,
  }) => {
    await editorPage.goto('/editor/status/does-not-exist');

    await expect(editorPage).toHaveURL(/\/editor\/status\/[^/]+(?:[?#]|$)/);
    await expect(editorPage.locator('app-editor-action-menu')).toHaveCount(1);
  });
});
