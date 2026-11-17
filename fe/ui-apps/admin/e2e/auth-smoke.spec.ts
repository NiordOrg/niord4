import { expect, test } from './auth/fixtures';

// Smoke test for the password-grant + token-injection auth fixture.
// Verifies the test user lands on /messages/table authenticated WITHOUT going
// through any Keycloak redirect, and the account button shows their username
// (proof the JWT was parsed and AuthService.preferredUsername populated).
test.describe('auth fixture', () => {
  test('starts authenticated on /messages/table without a Keycloak redirect', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/messages/table');
    await expect(authenticatedPage).toHaveURL(/\/messages\/table$/);
    // The header account control switches its accessible name by auth state:
    // anonymous it is the "Login" button (shared.auth.login); authenticated it
    // becomes a menu trigger named for the signed-in user (their parsed `name`
    // claim). An authenticated session therefore exposes NO "Login" control —
    // proof the injected JWT was parsed and the account menu built from it. The
    // check is locale-stable (one label) and independent of the display name.
    await expect(authenticatedPage.locator('header')).toBeVisible();
    await expect(authenticatedPage.getByRole('button', { name: 'Login', exact: true })).toHaveCount(
      0,
    );
  });
});
