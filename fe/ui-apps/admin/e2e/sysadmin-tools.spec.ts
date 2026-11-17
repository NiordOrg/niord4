import { expect, test, type Page } from './auth/fixtures';

// Sysadmin "tools" surfaces (authed via the sysadmin fixture) — the three
// admin pages that share the list + drawer-editor + cog (import/export)
// pattern:
//   • Script Resources  (/sysadmin/script-resources) — FM/JS template catalog
//     with a revision-history dialog and a ticketed export.
//   • Print Reports      (/sysadmin/reports)         — PDF report definitions,
//     ticketed export + JSON import.
//   • Parameter Types    (/sysadmin/param-types)     — LIST / COMPOSITE editors.
//
// Mutating round-trips are self-cleaning: every throwaway entity is stamped with
// a per-run token so parallel/interrupted runs never collide, and a finally block
// deletes any survivor even when an assertion fails mid-flight. Imports only OPEN
// the upload dialog (file-picker asserted present) — nothing is ever uploaded.
// Exports assert the ticketed download opens in a new tab (no file is consumed).

const HOME = /localhost:4200\/(?:$|[?#])/;

/** Wait for a list section to settle: rows arrived OR the empty state shows. */
async function waitForList(page: Page): Promise<void> {
  await expect(
    page.locator('table.niord-table tbody tr').first().or(page.locator('app-empty-state')),
  ).toBeVisible({ timeout: 15000 });
}

test.describe('sysadmin script resources section', () => {
  test('the section loads with the toolbar and renders the list', async ({ sysadminPage }) => {
    await sysadminPage.goto('/sysadmin/script-resources');
    await expect(sysadminPage).toHaveURL(/\/sysadmin\/script-resources(?:[?#]|$)/);
    await expect(sysadminPage.getByRole('button', { name: 'Add Script Resource' })).toBeVisible({
      timeout: 15000,
    });
    await expect(
      sysadminPage.getByRole('button', { name: 'Script resource actions' }),
    ).toBeVisible();
    await waitForList(sysadminPage);
  });

  test('round-trip: add a throwaway Freemarker resource, open its revision-history dialog, rename it (edit), then delete it (self-cleaning)', async ({
    sysadminPage,
  }) => {
    const page = sysadminPage;
    const token = `e2e-sr-${Date.now()}`;
    const pathA = `e2e-test/${token}.ftl`;
    const pathB = `e2e-test/${token}-v2.ftl`;

    await page.goto('/sysadmin/script-resources');
    await expect(page.getByRole('button', { name: 'Add Script Resource' })).toBeVisible({
      timeout: 15000,
    });
    await waitForList(page);

    try {
      // ADD — the New button is a menu (Freemarker / JavaScript). Only the path
      // is required; the FM path must end .ftl. Content stays empty.
      await page.getByRole('button', { name: 'Add Script Resource' }).click();
      await page.getByRole('menuitem', { name: 'Freemarker Template' }).click();
      const addDrawer = page.getByRole('dialog', { name: 'New Script Resource' });
      await expect(addDrawer).toBeVisible();
      const addSave = addDrawer.getByRole('button', { name: 'Save', exact: true });
      await expect(addSave).toBeDisabled(); // pristine — nothing changed yet
      await addDrawer.getByLabel('Path', { exact: true }).fill(pathA);
      await expect(addSave).toBeEnabled();
      await addSave.click();
      await expect(addDrawer).toBeHidden({ timeout: 15000 });

      // FIND via the client-side filter.
      await page.getByLabel('Filter', { exact: true }).fill(token);
      const row = page.getByRole('row').filter({ hasText: token });
      await expect(row).toHaveCount(1);

      // EDIT — the drawer populates from the row.
      await row.getByRole('button', { name: 'Edit', exact: true }).click();
      const editDrawer = page.getByRole('dialog', { name: 'Edit Script Resource' });
      await expect(editDrawer).toBeVisible();
      await expect(editDrawer.getByLabel('Path', { exact: true })).toHaveValue(pathA);

      // REVISION HISTORY — the History… affordance is present once the resource
      // has an id; it opens a dialog ABOVE the still-open drawer.
      await editDrawer.getByRole('button', { name: 'History...' }).click();
      const history = page.getByRole('dialog', { name: 'Revision History' });
      await expect(history).toBeVisible();
      await expect(history.getByText('Version', { exact: true })).toBeVisible();
      // Two Close affordances (header ✕ + footer button) — use the footer's.
      await history.locator('.dialog-foot').getByRole('button', { name: 'Close' }).click();
      await expect(history).toBeHidden();
      await expect(editDrawer).toBeVisible(); // drawer stayed open underneath

      // A net change (rename) enables Save; the list re-renders after the refetch.
      await editDrawer.getByLabel('Path', { exact: true }).fill(pathB);
      const editSave = editDrawer.getByRole('button', { name: 'Save', exact: true });
      await expect(editSave).toBeEnabled();
      await editSave.click();
      await expect(editDrawer).toBeHidden({ timeout: 15000 });
      await expect(row).toContainText(`${token}-v2`);

      // DELETE — the confirm is an alertdialog; default accept is "Yes".
      await row.getByRole('button', { name: 'Delete', exact: true }).click();
      const confirm = page.getByRole('alertdialog');
      await expect(confirm.getByText('Delete script resource?')).toBeVisible();
      await confirm.getByRole('button', { name: 'Yes' }).click();
      await expect(page.getByRole('row').filter({ hasText: token })).toHaveCount(0);
    } finally {
      await cleanupByToken(page, '/sysadmin/script-resources', token);
    }
  });

  test('the cog Import… opens the upload dialog with a file-picker (no upload)', async ({
    sysadminPage,
  }) => {
    const page = sysadminPage;
    await page.goto('/sysadmin/script-resources');
    await expect(page.getByRole('button', { name: 'Script resource actions' })).toBeVisible({
      timeout: 15000,
    });
    await page.getByRole('button', { name: 'Script resource actions' }).click();
    await page.getByRole('menuitem', { name: 'Import...' }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('Upload Script Resources File')).toBeVisible();
    await expect(dialog.getByText('Choose file…')).toBeVisible();
    await expect(dialog.locator('input[type="file"]')).toHaveCount(1);
    // Close via the header ✕ (aria-label "Close") — unambiguous vs the footer text.
    await dialog.getByLabel('Close').click();
    await expect(dialog).toBeHidden();
  });

  test('the cog Export triggers a ticketed download (opens the export URL in a new tab)', async ({
    sysadminPage,
  }) => {
    const page = sysadminPage;
    await page.goto('/sysadmin/script-resources');
    await expect(page.getByRole('button', { name: 'Script resource actions' })).toBeVisible({
      timeout: 15000,
    });
    await page.getByRole('button', { name: 'Script resource actions' }).click();
    const popupPromise = page.context().waitForEvent('page', { timeout: 15000 });
    await page.getByRole('menuitem', { name: 'Export...' }).click();
    const popup = await popupPromise;
    expect(popup.url()).toContain('/script-resources/all');
    expect(popup.url()).toContain('ticket=');
    await popup.close();
  });
});

test.describe('sysadmin print reports section', () => {
  test('the section loads with the toolbar and renders the list', async ({ sysadminPage }) => {
    await sysadminPage.goto('/sysadmin/reports');
    await expect(sysadminPage).toHaveURL(/\/sysadmin\/reports(?:[?#]|$)/);
    await expect(sysadminPage.getByRole('button', { name: 'New Report' })).toBeVisible({
      timeout: 15000,
    });
    await expect(sysadminPage.getByRole('button', { name: 'Print report actions' })).toBeVisible();
    await waitForList(sysadminPage);
  });

  test('New opens the report editor drawer and cancels cleanly (no report is created)', async ({
    sysadminPage,
  }) => {
    const page = sysadminPage;
    await page.goto('/sysadmin/reports');
    await page.getByRole('button', { name: 'New Report' }).click();
    const drawer = page.getByRole('dialog', { name: 'New Print Report' });
    await expect(drawer).toBeVisible();
    await expect(drawer.getByLabel('Report ID', { exact: true })).toBeVisible();
    // Seeded defaults are the baseline — Save stays gated until a real edit.
    await expect(drawer.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();
    await drawer.getByRole('button', { name: 'Cancel' }).click();
    await expect(drawer).toBeHidden();
  });

  test('the cog Import… opens the upload dialog with a file-picker (no upload)', async ({
    sysadminPage,
  }) => {
    const page = sysadminPage;
    await page.goto('/sysadmin/reports');
    await expect(page.getByRole('button', { name: 'Print report actions' })).toBeVisible({
      timeout: 15000,
    });
    await page.getByRole('button', { name: 'Print report actions' }).click();
    await page.getByRole('menuitem', { name: 'Import...' }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('Upload Print Reports File')).toBeVisible();
    await expect(dialog.getByText('Choose file…')).toBeVisible();
    await expect(dialog.locator('input[type="file"]')).toHaveCount(1);
    await dialog.getByLabel('Close').click();
    await expect(dialog).toBeHidden();
  });

  test('the cog Export triggers a ticketed download (opens the export URL in a new tab)', async ({
    sysadminPage,
  }) => {
    const page = sysadminPage;
    await page.goto('/sysadmin/reports');
    await expect(page.getByRole('button', { name: 'Print report actions' })).toBeVisible({
      timeout: 15000,
    });
    await page.getByRole('button', { name: 'Print report actions' }).click();
    const popupPromise = page.context().waitForEvent('page', { timeout: 15000 });
    await page.getByRole('menuitem', { name: 'Export...' }).click();
    const popup = await popupPromise;
    expect(popup.url()).toContain('/message-reports/all');
    expect(popup.url()).toContain('ticket=');
    await popup.close();
  });
});

test.describe('sysadmin parameter types section', () => {
  test('the section loads with the toolbar and renders the list with keyed kind labels', async ({
    sysadminPage,
  }) => {
    await sysadminPage.goto('/sysadmin/param-types');
    await expect(sysadminPage).toHaveURL(/\/sysadmin\/param-types(?:[?#]|$)/);
    await expect(sysadminPage.getByRole('button', { name: 'New Parameter Type' })).toBeVisible({
      timeout: 15000,
    });
    await expect(
      sysadminPage.getByRole('columnheader', { name: 'Type', exact: true }),
    ).toBeVisible();
    await waitForList(sysadminPage);
    // The Type column renders localized labels, never the raw enum values.
    await expect(sysadminPage.locator('table.niord-table tbody')).not.toContainText('STANDARD');
  });

  test('round-trip: create a throwaway List parameter type, rename it (edit), then delete it (self-cleaning)', async ({
    sysadminPage,
  }) => {
    const page = sysadminPage;
    const token = `e2e-pt-${Date.now()}`;
    const nameA = token;
    const nameB = `${token}-v2`;

    await page.goto('/sysadmin/param-types');
    await expect(page.getByRole('button', { name: 'New Parameter Type' })).toBeVisible({
      timeout: 15000,
    });
    await waitForList(page);

    try {
      // ADD — the New button is a menu (List / Composite). Only the name is required.
      await page.getByRole('button', { name: 'New Parameter Type' }).click();
      await page.getByRole('menuitem', { name: 'List Param Type' }).click();
      const addDrawer = page.getByRole('dialog', { name: 'New Parameter Type' });
      await expect(addDrawer).toBeVisible();
      // LIST variant renders the Values transfer list, not the Composite params grid.
      await expect(addDrawer.getByText('Selected Values')).toBeVisible();
      const addSave = addDrawer.getByRole('button', { name: 'Save', exact: true });
      await expect(addSave).toBeDisabled(); // pristine
      await addDrawer.getByLabel('Name', { exact: true }).fill(nameA);
      await expect(addSave).toBeEnabled();
      await addSave.click();
      await expect(addDrawer).toBeHidden({ timeout: 15000 });

      // FIND — the new row carries the keyed "List" kind label.
      await page.getByLabel('Filter', { exact: true }).fill(token);
      const row = page.getByRole('row').filter({ hasText: token });
      await expect(row).toHaveCount(1);
      await expect(row).toContainText('List');

      // EDIT — clicking edit detail-loads then opens the drawer populated.
      await row.getByRole('button', { name: 'Edit', exact: true }).click();
      const editDrawer = page.getByRole('dialog', { name: 'Edit Parameter Type' });
      await expect(editDrawer).toBeVisible();
      await expect(editDrawer.getByLabel('Name', { exact: true })).toHaveValue(nameA);

      // A net change (rename) enables Save; the list re-renders after the refetch.
      await editDrawer.getByLabel('Name', { exact: true }).fill(nameB);
      const editSave = editDrawer.getByRole('button', { name: 'Save', exact: true });
      await expect(editSave).toBeEnabled();
      await editSave.click();
      await expect(editDrawer).toBeHidden({ timeout: 15000 });
      await expect(row).toContainText(nameB);

      // DELETE — confirm alertdialog, default accept "Yes".
      await row.getByRole('button', { name: 'Delete', exact: true }).click();
      const confirm = page.getByRole('alertdialog');
      await expect(confirm.getByText('Delete parameter type?')).toBeVisible();
      await confirm.getByRole('button', { name: 'Yes' }).click();
      await expect(page.getByRole('row').filter({ hasText: token })).toHaveCount(0);
    } finally {
      await cleanupByToken(page, '/sysadmin/param-types', token);
    }
  });

  test('New → Composite opens the composite editor (Parameters grid, not the Values list) and cancels cleanly', async ({
    sysadminPage,
  }) => {
    const page = sysadminPage;
    await page.goto('/sysadmin/param-types');
    await expect(page.getByRole('button', { name: 'New Parameter Type' })).toBeVisible({
      timeout: 15000,
    });
    await page.getByRole('button', { name: 'New Parameter Type' }).click();
    await page.getByRole('menuitem', { name: 'Composite Param Type' }).click();

    const drawer = page.getByRole('dialog', { name: 'New Parameter Type' });
    await expect(drawer).toBeVisible();
    // Composite variant: the nested-parameters field, with its Add-Parameter action.
    await expect(drawer.getByText('Parameters', { exact: true })).toBeVisible();
    await expect(drawer.getByRole('button', { name: 'Add Parameter' })).toBeVisible();
    // The LIST-only transfer list is absent.
    await expect(drawer.getByText('Selected Values')).toHaveCount(0);

    await drawer.getByRole('button', { name: 'Cancel' }).click();
    await expect(drawer).toBeHidden();
  });

  test('the cog Import… opens the upload dialog with a file-picker (no upload)', async ({
    sysadminPage,
  }) => {
    const page = sysadminPage;
    await page.goto('/sysadmin/param-types');
    await expect(page.getByRole('button', { name: 'Parameter type actions' })).toBeVisible({
      timeout: 15000,
    });
    await page.getByRole('button', { name: 'Parameter type actions' }).click();
    await page.getByRole('menuitem', { name: 'Import...' }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('Upload Parameter Types File')).toBeVisible();
    await expect(dialog.getByText('Choose file…')).toBeVisible();
    await expect(dialog.locator('input[type="file"]')).toHaveCount(1);
    await dialog.getByLabel('Close').click();
    await expect(dialog).toBeHidden();
  });
});

test.describe('sysadmin tools role gating', () => {
  test('an editor (non-sysadmin) is redirected home from the script-resources section', async ({
    editorPage,
  }) => {
    await editorPage.goto('/sysadmin/script-resources');
    await expect(editorPage).toHaveURL(HOME, { timeout: 15000 });
    expect(editorPage.url()).not.toMatch(/\/sysadmin/);
  });
});

/**
 * Best-effort safety net (runs on failure too): reload the section to drop any
 * open drawer/dialog, then delete every throwaway row matching the run token.
 * Non-asserting so it never masks the original failure.
 */
async function cleanupByToken(page: Page, route: string, token: string): Promise<void> {
  await page.goto(route);
  await page
    .locator('table.niord-table tbody tr')
    .first()
    .or(page.locator('app-empty-state'))
    .waitFor({ state: 'visible', timeout: 15000 })
    .catch(() => undefined);
  await page
    .getByLabel('Filter', { exact: true })
    .fill(token)
    .catch(() => undefined);
  const leftovers = page.getByRole('row').filter({ hasText: token });
  // Delete one at a time — each deletion re-renders the filtered list.
  for (let guard = 0; guard < 5; guard++) {
    if ((await leftovers.count().catch(() => 0)) === 0) {
      return;
    }
    await leftovers
      .first()
      .getByRole('button', { name: 'Delete', exact: true })
      .click()
      .catch(() => undefined);
    await page
      .getByRole('alertdialog')
      .getByRole('button', { name: 'Yes' })
      .click()
      .catch(() => undefined);
    await page.waitForTimeout(500);
  }
}
