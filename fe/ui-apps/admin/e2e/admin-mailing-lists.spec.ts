import { expect, test } from './auth/fixtures';

// Mailing Lists section e2e: route + toolbar smoke; the create drawer's
// Save gate (required ID + first-language name) and app-confirm dirty-close guard;
// the cog Import/Export wiring; trigger-card type-switch (STATUS_CHANGE <-> SCHEDULED
// swaps the variant fields); and one live add -> recipients-drawer -> edit -> delete
// round-trip (self-cleaning) that also exercises the Users/Contacts tabs + dual-list
// transfer. A second describe covers the messages-area reports dialog
// (login-gated). Runs against the live backend; the round-trip deletes its disposable
// row on the way out.

const DISPOSABLE_ID = 'e2e-mailing-list-901';

test.describe('admin mailing lists section', () => {
  test('the section renders the list (or empty state) with the toolbar under the shell', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/admin/mailing-lists');
    await expect(authenticatedPage).toHaveURL(/\/admin\/mailing-lists(?:[?#]|$)/);
    await expect(authenticatedPage.getByRole('button', { name: 'New Mailing List' })).toBeVisible();
    await expect(authenticatedPage.getByLabel('Filter', { exact: true })).toBeVisible();
  });

  test('the create drawer gates Save on the required ID + first-language name and app-confirm dirty-gates close', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/admin/mailing-lists');
    await authenticatedPage.getByRole('button', { name: 'New Mailing List' }).click();

    const drawer = authenticatedPage.getByRole('dialog');
    await expect(drawer).toBeVisible();

    const save = drawer.getByRole('button', { name: 'Save Mailing List' });
    await expect(save).toBeDisabled(); // nothing changed yet

    await drawer.getByLabel('Mailing List ID').fill(DISPOSABLE_ID);
    await expect(save).toBeDisabled(); // changed, but the first-language name is still required

    // The name fieldset is the first localized-text-input group (description is multiline/textarea).
    await drawer.locator('app-localized-text-input input').first().fill('E2E gate');
    await expect(save).toBeEnabled();

    // A dirty drawer closes through the app confirm dialog (an alertdialog), NOT a native confirm().
    await drawer.getByRole('button', { name: 'Cancel' }).click();
    const confirm = authenticatedPage.getByRole('alertdialog');
    await expect(confirm.getByRole('heading', { name: 'Unsaved changes' })).toBeVisible();
    await confirm.getByRole('button', { name: 'Yes' }).click();
    await expect(drawer).toBeHidden();
  });

  test('the cog Import opens the upload dialog; Export opens the export URL in a new tab', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/admin/mailing-lists');

    await authenticatedPage.getByRole('button', { name: 'Mailing list actions' }).click();
    await authenticatedPage.getByRole('menuitem', { name: 'Import...' }).click();
    const importDialog = authenticatedPage.getByRole('dialog');
    await expect(importDialog.getByText('Upload Mailing-Lists File')).toBeVisible();
    // The dialog has a header ✕ (aria-label "Close") and a footer "Close" — the ✕ is unambiguous.
    await importDialog.getByLabel('Close').click();
    await expect(importDialog).toBeHidden();

    await authenticatedPage.getByRole('button', { name: 'Mailing list actions' }).click();
    const popupPromise = authenticatedPage.context().waitForEvent('page');
    await authenticatedPage.getByRole('menuitem', { name: 'Export...' }).click();
    const popup = await popupPromise;
    expect(popup.url()).toContain('/rest/mailing-lists/export');
    await popup.close();
  });

  test('Add Trigger yields an expanded Status-Change card; switching to Scheduled swaps the variant fields', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/admin/mailing-lists');
    await authenticatedPage.getByRole('button', { name: 'New Mailing List' }).click();
    const drawer = authenticatedPage.getByRole('dialog');

    await drawer.getByRole('button', { name: 'Add Trigger' }).click();
    const card = drawer.locator('app-trigger-card');
    await expect(card).toHaveCount(1);

    // Default add = STATUS_CHANGE, expanded: the Statuses row is present, the Schedule select is not.
    await expect(card.getByText('Statuses')).toBeVisible();
    await expect(card.getByLabel('Schedule')).toHaveCount(0);

    // Switch to SCHEDULED via the segmented control — the variant fields swap.
    await card.getByRole('button', { name: 'Scheduled' }).click();
    await expect(card.getByLabel('Schedule')).toBeVisible();
    await expect(card.getByLabel('Time Zone')).toBeVisible();
    await expect(card.getByText('Statuses')).toHaveCount(0);

    // Discard the dirty drawer.
    await drawer.getByRole('button', { name: 'Cancel' }).click();
    await authenticatedPage.getByRole('alertdialog').getByRole('button', { name: 'Yes' }).click();
    await expect(drawer).toBeHidden();
  });

  test('live round-trip: add a disposable list, manage its recipients, edit it (ID immutable), then delete it (self-cleaning)', async ({
    authenticatedPage,
  }) => {
    const page = authenticatedPage;
    await page.goto('/admin/mailing-lists');

    // Wait for the initial (async) list load to settle before probing — a table row or an
    // empty state — so the leftover check below doesn't race an unloaded list.
    await expect(
      page.locator('table.niord-table tbody tr').first().or(page.locator('app-empty-state')),
    ).toBeVisible();

    // Defensive: a previously-interrupted run may have left the disposable row behind
    // (its PK is user-entered, so re-creating it would 409). Delete it first if present.
    await page.getByLabel('Filter', { exact: true }).fill(DISPOSABLE_ID);
    const leftover = page.getByRole('row').filter({ hasText: DISPOSABLE_ID });
    if ((await leftover.count()) > 0) {
      await leftover.first().getByRole('button', { name: 'Delete Mailing List' }).click();
      await page
        .getByRole('alertdialog')
        .getByRole('button', { name: /Delete|Yes/ })
        .click();
      await expect(leftover).toHaveCount(0);
    }
    await page.getByLabel('Filter', { exact: true }).fill('');

    // ADD
    await page.getByRole('button', { name: 'New Mailing List' }).click();
    const drawer = page.getByRole('dialog');
    await drawer.getByLabel('Mailing List ID').fill(DISPOSABLE_ID);
    await drawer.locator('app-localized-text-input input').first().fill('E2E disposable');
    await drawer.getByRole('button', { name: 'Save Mailing List' }).click();
    await expect(drawer).toBeHidden();

    // FIND via the client-side filter.
    await page.getByLabel('Filter', { exact: true }).fill(DISPOSABLE_ID);
    const row = page.getByRole('row').filter({ hasText: DISPOSABLE_ID });
    await expect(row).toHaveCount(1);

    // RECIPIENTS — the #Recipients link opens the Users/Contacts drawer; the per-tab Save
    // gates on change. Moving one available user in then out nets zero, so nothing persists.
    await row.getByRole('button', { name: 'Manage recipients' }).click();
    const recipients = page.getByRole('dialog');
    await expect(recipients.getByRole('tab', { name: /Users/ })).toBeVisible();
    await expect(recipients.getByRole('tab', { name: /Contacts/ })).toBeVisible();
    // Both tab panels stay mounted, so the Selected/Available pane headers appear twice;
    // asserting the transfer component itself is the unambiguous "panes rendered" check.
    await expect(recipients.locator('app-recipient-transfer').first()).toBeVisible();
    const saveUsers = recipients.getByRole('button', { name: 'Save User Selection' });
    await expect(saveUsers).toBeDisabled();
    const addButtons = recipients.getByRole('button', { name: 'Add', exact: true });
    if ((await addButtons.count()) > 0) {
      await addButtons.first().click();
      await expect(saveUsers).toBeEnabled();
      await recipients.getByRole('button', { name: 'Remove', exact: true }).first().click();
      await expect(saveUsers).toBeDisabled();
    }
    // Not dirty after the net-zero transfer — Escape closes without a discard prompt (the
    // drawer exposes both a header ✕ and a footer "Close", so a role+name click is ambiguous).
    await page.keyboard.press('Escape');
    await expect(recipients).toBeHidden();

    // EDIT — the ID is immutable in edit mode (§13a EDIT:11).
    await row.getByRole('button', { name: 'Edit Mailing List' }).click();
    await expect(drawer.getByLabel('Mailing List ID')).toHaveValue(DISPOSABLE_ID);
    await expect(drawer.getByLabel('Mailing List ID')).toBeDisabled();
    await drawer.locator('app-localized-text-input input').first().fill('E2E renamed');
    await drawer.getByRole('button', { name: 'Save Mailing List' }).click();
    await expect(drawer).toBeHidden();

    // DELETE (cleanup) — the confirm shows the id.
    await row.getByRole('button', { name: 'Delete Mailing List' }).click();
    const confirm = page.getByRole('alertdialog');
    await expect(confirm.getByText(DISPOSABLE_ID)).toBeVisible();
    await confirm.getByRole('button', { name: /Delete|Yes/ }).click();
    await expect(page.getByRole('row').filter({ hasText: DISPOSABLE_ID })).toHaveCount(0);
  });
});

test.describe('messages mailing-list reports dialog', () => {
  test('the Actions menu Report… item opens the reports dialog (login-gated)', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/messages/table');
    // The messages shell is heavy; wait for the desktop Action trigger before opening it.
    // Located by component: a name lookup would collide with the per-row "Message actions" cogs,
    // and the trigger's own label comes from the backend-editable `menu.action` dictionary key.
    const actions = authenticatedPage
      .locator('app-messages-action-menu')
      .getByRole('button')
      .first();
    await expect(actions).toBeVisible({ timeout: 15000 });
    await actions.click();
    // Wait for the menu to actually open before clicking its item.
    const reportItem = authenticatedPage.getByRole('menuitem', { name: /Report/ });
    await expect(reportItem).toBeVisible();
    await reportItem.click();

    const dialog = authenticatedPage.getByRole('dialog');
    await expect(dialog.getByText('Execute Mailing List Report')).toBeVisible();
    // Either reports are listed (an Execute button per row) or the empty state shows.
    await expect(
      dialog
        .getByRole('button', { name: 'Execute' })
        .or(dialog.getByText('No report defined'))
        .first(),
    ).toBeVisible();

    // Close via the header ✕ (aria-label "Close" — unambiguous vs any footer text button).
    await dialog.getByLabel('Close').click();
    await expect(dialog).toBeHidden();
  });
});
