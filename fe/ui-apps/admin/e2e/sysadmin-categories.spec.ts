import { expect, test, type Page } from './auth/fixtures';

// Categories sysadmin section e2e (authed via the sysadmin fixture — Categories
// rides the /sysadmin rail, gated by SYSADMIN_AREA_ROLES = sysadmin; category
// write endpoints are @RolesAllowed(SYSADMIN)). Like Areas it is a master-detail
// tree editor, plus the TEMPLATE authoring block: TEMPLATE nodes carry a tree
// badge and expose an "example message" + a "Test…" execute-preview.
//
// Coverage: the section loads; a non-sysadmin is bounced by the role guard; one
// self-cleaning create → rename → delete round-trip on a throwaway root category;
// the TEMPLATE authoring block + gated "Test…" affordance (deterministic, on an
// unsaved throwaway — creates nothing); and the execute-preview wired against a
// REAL persisted template (RACON, which ships with an example message), asserted
// up to the backend boundary.
//
// NB on the read-only preview dialog: the shared dev backend's
// `PUT /templates/execute` returns 403 in this environment (verified out-of-band
// and in-browser), so clicking Test surfaces the graceful "Error executing the
// template" toast rather than opening the Template Result dialog. The test
// therefore asserts the affordance + backend round-trip and verifies the result
// dialog only when the backend cooperates — it does not fake a preview that the
// environment blocks.

const HOME = /localhost:4200\/(?:$|[?#])/;

function filter(page: Page) {
  return page.getByRole('textbox', { name: 'Filter categories' });
}

async function selectNode(page: Page, name: string): Promise<void> {
  await page.locator('.tree-row', { hasText: name }).first().locator('.tree-row-main').click();
}

/** Best-effort delete of a single category by exact tree label (self-clean helper). */
async function deleteCategory(page: Page, name: string): Promise<void> {
  await filter(page).fill(name);
  const row = page.locator('.tree-row', { hasText: name }).first();
  if ((await row.count()) === 0) return;
  await row.locator('.tree-row-main').click();
  await page
    .getByRole('heading', { name: 'Edit category' })
    .waitFor({ timeout: 10000 })
    .catch(() => undefined);
  await page.getByRole('button', { name: 'Delete', exact: true }).click();
  const confirm = page.getByRole('alertdialog');
  await confirm.getByRole('button', { name: 'Yes' }).click();
  await row.waitFor({ state: 'detached', timeout: 10000 }).catch(() => undefined);
}

test.describe('sysadmin categories section', () => {
  test('the section loads under the shell with the toolbar and the category tree', async ({
    sysadminPage,
  }) => {
    await sysadminPage.goto('/sysadmin/categories');
    await expect(sysadminPage).toHaveURL(/\/sysadmin\/categories(?:[?#]|$)/);
    await expect(sysadminPage.getByRole('button', { name: 'New category' })).toBeVisible({
      timeout: 15000,
    });
    await expect(filter(sysadminPage)).toBeVisible();
    await expect(sysadminPage.getByRole('button', { name: 'Category actions' })).toBeVisible();
    // The deployment always has categories — at least one root row renders.
    await expect(sysadminPage.locator('.tree-row').first()).toBeVisible({ timeout: 15000 });
    await expect(
      sysadminPage.getByText('Select a category to edit, or add a new one.'),
    ).toBeVisible();
  });

  test('round-trip: create a throwaway root category, rename it, delete it (self-cleaning)', async ({
    sysadminPage,
  }) => {
    const page = sysadminPage;
    const name = `e2e-category-${Date.now()}`;
    const editedName = `${name}-ed`;

    await page.goto('/sysadmin/categories');
    await expect(page.getByRole('button', { name: 'New category' })).toBeVisible({
      timeout: 15000,
    });
    await expect(page.locator('.tree-row').first()).toBeVisible({ timeout: 15000 });
    const emptyState = page.getByText('Select a category to edit, or add a new one.');

    try {
      // CREATE — nothing selected ⇒ New adds a root category (type defaults to CATEGORY).
      await page.getByRole('button', { name: 'New category' }).click();
      await expect(page.getByRole('button', { name: 'Save category' })).toBeVisible();
      await page.getByRole('textbox', { name: 'Name', exact: true }).first().fill(name);
      await page.getByRole('button', { name: 'Save category' }).click();
      await expect(emptyState).toBeVisible();

      // RENAME — the tree label updates after save.
      await filter(page).fill(name);
      await selectNode(page, name);
      await expect(page.getByRole('heading', { name: 'Edit category' })).toBeVisible();
      await page.getByRole('textbox', { name: 'Name', exact: true }).first().fill(editedName);
      await page.getByRole('button', { name: 'Save category' }).click();
      await expect(emptyState).toBeVisible();
      await filter(page).fill(editedName);
      await expect(page.locator('.tree-row-label').filter({ hasText: editedName })).toBeVisible();

      // DELETE — the confirm names the throwaway; accept removes it.
      await deleteCategory(page, editedName);
      await filter(page).fill(name);
      await expect(page.locator('.tree-row').filter({ hasText: name })).toHaveCount(0);
    } finally {
      await page.goto('/sysadmin/categories');
      await page
        .locator('.tree-row')
        .first()
        .waitFor({ state: 'visible', timeout: 15000 })
        .catch(() => undefined);
      for (const n of [editedName, name]) {
        await deleteCategory(page, n).catch(() => undefined);
      }
    }
  });

  test('switching a new category to TEMPLATE reveals the authoring block; Test is gated on an example message', async ({
    sysadminPage,
  }) => {
    const page = sysadminPage;
    // Deterministic + zero-mutation: drive the add editor, flip the type toggle,
    // assert the TEMPLATE authoring surface, then cancel without saving.
    await page.goto('/sysadmin/categories');
    await page.getByRole('button', { name: 'New category' }).click();
    await expect(page.getByRole('button', { name: 'Save category' })).toBeVisible();

    // No template block for a plain CATEGORY.
    await expect(page.getByText('Example template message')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Test…' })).toHaveCount(0);

    // Flip to TEMPLATE — the authoring block + example message + Test… appear.
    await page.getByRole('button', { name: 'Template', exact: true }).click();
    await expect(page.getByText('Example template message')).toBeVisible();
    const testBtn = page.getByRole('button', { name: 'Test…' });
    await expect(testBtn).toBeVisible();
    // Test is disabled until an example message is chosen (execute needs one).
    await expect(testBtn).toBeDisabled();

    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByText('Select a category to edit, or add a new one.')).toBeVisible();
  });

  test('the execute-preview is wired on a real TEMPLATE: an example message enables Test and clicking it round-trips to the backend', async ({
    sysadminPage,
  }) => {
    const page = sysadminPage;
    await page.goto('/sysadmin/categories');
    await expect(page.getByRole('button', { name: 'New category' })).toBeVisible({
      timeout: 15000,
    });

    // RACON is a template of the base product's sample (be/niord/sample/archive)
    // whose name is an acronym (identical EN/DA) and which ships with a persisted
    // example message — a stable, language-agnostic target. Filtering auto-expands its ancestors.
    await filter(page).fill('RACON');
    const racon = page.locator('.tree-row', { hasText: 'RACON' }).first();
    await expect(racon).toBeVisible({ timeout: 15000 });
    // TEMPLATE nodes carry the tree badge.
    await expect(racon).toHaveClass(/is-template/);
    await racon.locator('.tree-row-main').click();

    await expect(page.getByRole('heading', { name: 'Edit category' })).toBeVisible({
      timeout: 15000,
    });
    // Loaded as a TEMPLATE with its example message chip → Test is enabled.
    await expect(page.getByRole('button', { name: 'Template', exact: true })).toHaveClass(
      /bg-primary/,
    );
    await expect(page.getByText('NW-099-16')).toBeVisible();
    const testBtn = page.getByRole('button', { name: 'Test…' });
    await expect(testBtn).toBeEnabled();

    // Click Test → the app calls PUT /templates/execute. Assert the round-trip
    // resolves to a user-visible outcome: the read-only Template Result dialog
    // (backend cooperated) or the graceful error toast (backend rejected — the
    // shared dev instance 403s this endpoint). Verify the dialog fully when it
    // opens; never fake a preview the environment blocks.
    await testBtn.click();
    const dialog = page.getByRole('dialog', { name: 'Template Result' });
    const errorToast = page.locator('.toast-error');
    await expect(dialog.or(errorToast).first()).toBeVisible({ timeout: 20000 });

    if (await dialog.isVisible().catch(() => false)) {
      // Happy path: the read-only preview rendered the executed message.
      await expect(dialog.locator('app-message-details-renderer')).toBeVisible();
      await dialog.getByLabel('Close').click();
      await expect(dialog).toBeHidden();
    } else {
      // Documented environment limit: execute is blocked upstream (403).
      await expect(errorToast.first()).toContainText('Error executing the template');
    }
  });
});

test.describe('categories section role gating', () => {
  test('an editor (non-sysadmin) is redirected home from the categories section', async ({
    editorPage,
  }) => {
    await editorPage.goto('/sysadmin/categories');
    await expect(editorPage).toHaveURL(HOME, { timeout: 15000 });
    expect(editorPage.url()).not.toMatch(/\/sysadmin/);
  });
});
