import { expect, test } from './auth/fixtures';

// Smoke tests for the Dictionaries section. Backend-light: selector + toolbar
// render, the entry drawer's per-language shape, and cancel. The full entry
// CRUD, copy and import/export run against the live backend during manual
// testing.

test.describe('sysadmin dictionaries section', () => {
  test('the section renders the dictionary selector and toolbar under the shell', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/sysadmin/dictionaries');
    await expect(authenticatedPage).toHaveURL(/\/sysadmin\/dictionaries(?:[?#]|$)/);
    await expect(authenticatedPage.getByRole('button', { name: 'New Entry' })).toBeVisible();
    await expect(authenticatedPage.getByText('Show Extended')).toBeVisible();
  });

  test('the create drawer opens with a key field and one value textarea per language', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/sysadmin/dictionaries');
    await authenticatedPage.getByRole('button', { name: 'New Entry' }).click();

    const dialog = authenticatedPage.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByLabel('Key')).toBeVisible();
    const valueInputs = dialog.getByRole('textbox', { name: 'Values' });
    expect(await valueInputs.count()).toBeGreaterThanOrEqual(2);

    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog).toBeHidden();
  });
});
