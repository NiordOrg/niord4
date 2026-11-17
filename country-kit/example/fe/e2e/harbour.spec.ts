import { expect, test } from '@niord/admin/e2e';

// The example country's own page, reached from its menu entry for the editor area's roles. A
// country's e2e specs take the base product's login fixtures from `@niord/admin/e2e` and run with
// `npm run e2e` against its own `npm run start:admin` and a Keycloak the runner names
// (ui-apps/admin/e2e/auth/README.md of the base product). The example's harness only lists this
// spec (`npx playwright test --list`, country-kit/example/run.mjs): it never runs against a shared
// test environment.

test.describe('the harbour page', () => {
  test('shows the harbour notes to an editor', async ({ editorPage }) => {
    await editorPage.goto('/harbour');

    await expect(editorPage.getByRole('heading', { name: 'Harbour notes' })).toBeVisible();
  });
});
