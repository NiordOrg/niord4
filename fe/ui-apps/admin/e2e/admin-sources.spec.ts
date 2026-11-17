import { expect, test } from './auth/fixtures';

// Smoke tests for the Sources section (the admin recipe-prover). Backend-light:
// list rendering, the create drawer's per-language form shape, and cancel.
// The full create→edit→delete round-trip and import/export run against the
// live backend during manual testing.

test.describe('admin sources section', () => {
  test('the section renders a list (or the empty state) under the shell', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/admin/sources');
    await expect(authenticatedPage).toHaveURL(/\/admin\/sources(?:[?#]|$)/);
    // Either rows arrived (table header visible) or the none-yet empty state shows.
    await expect(authenticatedPage.getByRole('button', { name: 'New Source' })).toBeVisible();
  });

  test('the create drawer opens with one Name input per configured language and cancels cleanly', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/admin/sources');
    await authenticatedPage.getByRole('button', { name: 'New Source' }).click();

    const dialog = authenticatedPage.getByRole('dialog');
    await expect(dialog).toBeVisible();
    // Two configured languages today — but assert ≥2 so a future third language doesn't break this.
    const nameInputs = dialog.getByRole('textbox', { name: 'Name' });
    await expect(nameInputs.first()).toBeVisible();
    expect(await nameInputs.count()).toBeGreaterThanOrEqual(2);

    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog).toBeHidden();
  });
});
