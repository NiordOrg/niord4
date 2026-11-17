import { expect, test } from './auth/fixtures';

// Smoke + golden-path tests for the sysadmin Settings section. Authed as the
// sysadmin role (sysadminPage falls back to the base test user when no
// role-scoped pair is set). READ-ONLY walk by design: system settings are live
// config, so this suite never commits a setting mutation against the live
// backend — the Save round-trip is exercised manually against a live backend
// with a sacrificial key.

test.describe('sysadmin settings section', () => {
  test('the section loads under the shell with the filter toolbar (list or empty state)', async ({
    sysadminPage,
  }) => {
    await sysadminPage.goto('/sysadmin/settings');
    await expect(sysadminPage).toHaveURL(/\/sysadmin\/settings(?:[?#]|$)/);
    await expect(sysadminPage.getByPlaceholder('Filter')).toBeVisible();
    await expect(sysadminPage.getByRole('button', { name: 'Settings actions' })).toBeVisible();
  });

  test('the cog menu offers Export… and Import…', async ({ sysadminPage }) => {
    await sysadminPage.goto('/sysadmin/settings');
    await sysadminPage.getByRole('button', { name: 'Settings actions' }).click();
    await expect(sysadminPage.getByRole('menuitem', { name: 'Export...' })).toBeVisible();
    await expect(sysadminPage.getByRole('menuitem', { name: 'Import...' })).toBeVisible();
    // Dismiss the popup without exporting (would open a download tab).
    await sysadminPage.keyboard.press('Escape');
  });

  test('filtering narrows the list by key (only when the backend serves settings)', async ({
    sysadminPage,
  }) => {
    await sysadminPage.goto('/sysadmin/settings');
    await expect(sysadminPage.getByPlaceholder('Filter')).toBeVisible();

    // Auto-wait for the loaded state (table OR empty-state) before probing rows —
    // a bare isVisible() samples too early and false-skips while the list loads.
    const firstKeyCell = sysadminPage.locator('table tbody tr td').first();
    await expect(
      firstKeyCell.or(sysadminPage.getByText('No editable settings are available')),
    ).toBeVisible();
    const hasRows = await firstKeyCell.isVisible();
    if (!hasRows) {
      test.skip(true, 'No editable settings reachable — cannot exercise the key filter.');
      return;
    }
    const firstKey = ((await firstKeyCell.textContent()) ?? '').trim();
    const fragment = firstKey.slice(0, Math.min(4, firstKey.length));
    await sysadminPage.getByPlaceholder('Filter').fill(fragment);
    // The clear-X appears only while the filter has text.
    await expect(sysadminPage.getByRole('button', { name: 'Clear filter' })).toBeVisible();
    // Every visible key cell must contain the filter fragment (case-insensitive).
    const keyCells = sysadminPage.locator('table tbody tr td:first-child');
    const count = await keyCells.count();
    for (let i = 0; i < count; i++) {
      expect(((await keyCells.nth(i).textContent()) ?? '').toLowerCase()).toContain(
        fragment.toLowerCase(),
      );
    }
  });

  test('the pencil opens the edit drawer with Key + Description disabled and Save gated', async ({
    sysadminPage,
  }) => {
    await sysadminPage.goto('/sysadmin/settings');
    await expect(sysadminPage.getByPlaceholder('Filter')).toBeVisible();

    const pencil = sysadminPage.getByRole('button', { name: 'Edit Setting' }).first();
    await expect(
      pencil.or(sysadminPage.getByText('No editable settings are available')),
    ).toBeVisible();
    const hasRows = await pencil.isVisible();
    if (!hasRows) {
      test.skip(true, 'No editable settings reachable — cannot open the edit drawer.');
      return;
    }
    await pencil.click();

    // The editor now opens in a drawer (role="dialog"); Key + Description are read-only.
    const dialog = sysadminPage.getByRole('dialog');
    await expect(dialog).toBeVisible();
    const keyField = dialog.getByLabel('Key', { exact: true });
    const descField = dialog.getByLabel('Description', { exact: true });
    await expect(keyField).toBeVisible();
    await expect(keyField).toBeDisabled();
    await expect(descField).toBeDisabled();

    // Freshly opened, no net change → Save disabled.
    await expect(dialog.getByRole('button', { name: 'Save Setting' })).toBeDisabled();

    // Cancel closes the drawer and returns to the list (filter toolbar visible again).
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog).toBeHidden();
    await expect(sysadminPage.getByPlaceholder('Filter')).toBeVisible();
  });
});
