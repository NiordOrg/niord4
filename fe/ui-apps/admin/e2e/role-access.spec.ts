import { expect, test } from './auth/fixtures';

// Role-differentiated route access via the per-role fixtures (closes the "dormant
// editorPage/adminPage fixtures" gap). The editor user holds EDITOR_AREA_ROLES, so
// /editor and /schedule load; it lacks the admin/sysadmin roles, so the roleGuard bounces
// it from /admin and /sysadmin to HOME (authed-but-unauthorized → UrlTree('/')), NOT to the
// Keycloak login (that is the anonymous path, covered in route-guards.spec.ts).
//
// Needs KEYCLOAK_TEST_USERNAME / KEYCLOAK_TEST_PASSWORD (optionally the _EDITOR pair) — see
// e2e/auth/README.md. If the base test user also holds admin/sysadmin, the deny assertions
// will (correctly) fail — that signals the test account is over-privileged, not a code bug.

const HOME = /localhost:4200\/(?:$|[?#])/;

test.describe('editor role route access', () => {
  test('editor can reach /editor and /schedule', async ({ editorPage }) => {
    await editorPage.goto('/editor');
    await expect(editorPage).toHaveURL(/\/editor(?:\/|$)/);

    await editorPage.goto('/schedule');
    await expect(editorPage).toHaveURL(/\/schedule(?:[?#]|$)/);
  });

  test('editor is redirected home from the admin and sysadmin areas', async ({ editorPage }) => {
    await editorPage.goto('/admin');
    await expect(editorPage).toHaveURL(HOME);
    expect(editorPage.url()).not.toMatch(/\/admin/);

    await editorPage.goto('/sysadmin');
    await expect(editorPage).toHaveURL(HOME);
    expect(editorPage.url()).not.toMatch(/\/sysadmin/);
  });
});
