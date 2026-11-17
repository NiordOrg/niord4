import { expect, test } from './auth/fixtures';

// The Firing Area Schedule area (`/schedule`) — a top-level, role-gated
// (EDITOR_AREA_ROLES) feature. (The `admin-firing-schedules` spec covers the
// DIFFERENT `/sysadmin/schedules` config page.)
//
// Needs an authenticated session holding editor/admin/sysadmin. Set
// KEYCLOAK_TEST_USERNAME / KEYCLOAK_TEST_PASSWORD (and optionally the _EDITOR
// per-role pair) before running — see e2e/auth/README.md. Backend-light: it
// asserts the route resolves under the schedule view shell; row data depends on
// the `firingSchedule` domain flag, so the deeper interactions (date nav, the
// inline interval editor, Update Firing Exercises) are covered by manual testing.

test.describe('firing area schedule', () => {
  test('an editor can open /schedule and the view shell mounts', async ({ editorPage }) => {
    await editorPage.goto('/schedule');
    await expect(editorPage).toHaveURL(/\/schedule(?:[?#]|$)/);
    // The route resolved and the lazy ScheduleView mounted (not a guard bounce to home).
    await expect(editorPage.locator('app-schedule-view')).toBeAttached();
  });
});
