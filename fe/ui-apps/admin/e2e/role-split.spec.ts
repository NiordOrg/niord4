import { expect, test } from './auth/fixtures';

// Role-split route access — wires the dormant `adminPage` / `userPage` fixtures alongside
// the already-used `editorPage` (see role-access.spec.ts for the editor equivalent). Proves
// the two admin areas are gated by distinct role sets and that the editor shell admits the
// read-only `user` role:
//
//   /admin     → ADMIN_AREA_ROLES     = ['admin', 'sysadmin']
//   /sysadmin  → SYSADMIN_AREA_ROLES  = ['sysadmin']
//   /editor    → EDITOR_VIEW_ROLES    = ['user', 'editor', 'admin', 'sysadmin']  (read-only for `user`)
//
// An authenticated-but-unauthorized visit resolves to UrlTree('/') (redirect HOME with an
// access-denied toast), NOT to the Keycloak login (that is the anonymous path — route-guards.spec.ts).
//
// Needs KEYCLOAK_TEST_USERNAME / KEYCLOAK_TEST_PASSWORD (optionally the _ADMIN / _USER pairs) — see
// e2e/auth/README.md. If the base account is over-privileged (holds sysadmin), the admin deny
// assertion will (correctly) fail — that signals the test account, not a code bug.

const HOME = /localhost:4200\/(?:$|[?#])/;

test.describe('admin role route access', () => {
  test('an admin reaches the /admin area shell', async ({ adminPage }) => {
    await adminPage.goto('/admin');
    // Bare /admin redirects to its overview and mounts the area shell + rail.
    await expect(adminPage).toHaveURL(/\/admin\/overview(?:[?#]|$)/, { timeout: 15000 });
    await expect(adminPage.locator('app-admin-area-shell nav')).toBeVisible();
  });

  test('an admin is redirected home from the /sysadmin area (lacks the sysadmin role)', async ({
    adminPage,
  }) => {
    await adminPage.goto('/sysadmin');
    await expect(adminPage).toHaveURL(HOME, { timeout: 15000 });
    expect(adminPage.url()).not.toMatch(/\/sysadmin/);
  });
});

test.describe('plain user route access', () => {
  test('a plain user is redirected home from BOTH admin areas', async ({ userPage }) => {
    await userPage.goto('/admin');
    await expect(userPage).toHaveURL(HOME, { timeout: 15000 });
    expect(userPage.url()).not.toMatch(/\/admin/);

    await userPage.goto('/sysadmin');
    await expect(userPage).toHaveURL(HOME, { timeout: 15000 });
    expect(userPage.url()).not.toMatch(/\/sysadmin/);
  });

  test('a plain user can open the editor shell (read-only view access)', async ({ userPage }) => {
    // EDITOR_VIEW_ROLES admits `user`: the route guard lets them in and the persistent editor
    // chrome mounts — proof of read access (mutating actions stay gated inside the components).
    await userPage.goto('/editor');
    await expect(userPage).toHaveURL(/\/editor(?:[?#]|$)/, { timeout: 15000 });
    await expect(userPage.locator('app-editor-action-menu')).toHaveCount(1);
  });
});
