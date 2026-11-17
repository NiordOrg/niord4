import { expect, test, type Page } from './auth/fixtures';

// Areas admin section e2e (authed via the admin fixture — Areas rides the /admin
// rail, gated by ADMIN_AREA_ROLES = admin|sysadmin; area write endpoints are
// @RolesAllowed(ADMIN)). The section is a master-detail tree editor: a
// drag-to-re-parent tree beside an inline area editor, with sibling reorder on
// the toolbar Move-up/Move-down buttons (sibling reorder is inline + drag, not a
// modal dialog).
//
// One self-cleaning round-trip on a throwaway subtree the spec owns end to end:
// create a test ROOT area → two child areas under it → assert both children are
// listed → reorder a child up the sibling list (safely revertible — the whole
// subtree is deleted at the end) → rename a child → delete every test node.
// Names are timestamped so parallel/interrupted runs never collide, and a
// finally-block safety net removes any survivor.
//
// Three environment realities this spec is written around (verified out-of-band):
//   1. The inline area editor mounts an OpenLayers extent map per selection; its
//      tile traffic dominates runtime, so this test aborts map tiles up front —
//      the form is unaffected and the flow drops from minutes to seconds.
//   2. Selecting a node loads its detail via an async `getArea` before the form
//      rebuilds. The edit heading is identical across selections, so
//      `selectForEdit` waits for that GET before touching a field — otherwise the
//      edit races the rebuild and is silently reverted.
//   3. The area tree read model is eventually-consistent and area DELETE detaches
//      a child (promotes it to a root) rather than removing it outright. So delete
//      success is asserted via the backend-confirmed toast, and children are
//      deleted twice (detach, then remove the now-childless root).
//
// The firing-area generation dialog is domain-gated (only firing-schedule
// domains surface it); that test seeds the session domain to the firing domain
// so the cog item appears, opens the dialog, and cancels (no messages generated).

const HOME = /localhost:4200\/(?:$|[?#])/;

/** The lenient tree filter (auto-expands ancestors of matches). */
function filter(page: Page) {
  return page.getByRole('textbox', { name: 'Filter areas' });
}

const firstNameInput = (page: Page) =>
  page.getByRole('textbox', { name: 'Name', exact: true }).first();

/**
 * Select a tree node for editing and wait until ITS detail has loaded into the
 * inline form. The row click fires an async `getArea`; the edit heading is the
 * same for every node, so gate on the `getArea` response AND the first Name field
 * showing this node's value before any field edit (else `rebuildForm` reverts it).
 */
async function selectForEdit(page: Page, name: string): Promise<void> {
  const main = page.locator('.tree-row', { hasText: name }).first().locator('.tree-row-main');
  await Promise.all([
    page.waitForResponse(
      (r) => r.request().method() === 'GET' && /\/areas\/area\/\d+(\?|$)/.test(r.url()),
      { timeout: 20000 },
    ),
    main.click(),
  ]);
  await expect(firstNameInput(page)).toHaveValue(name, { timeout: 15000 });
}

/** The ordered visible labels of the throwaway subtree's children. */
async function childOrder(page: Page, childPrefix: string): Promise<string[]> {
  const texts = await page
    .locator('.tree-row-label')
    .filter({ hasText: childPrefix })
    .allInnerTexts();
  return texts.map((t) => t.trim());
}

/**
 * Delete one area by exact tree label. `assert` gates the backend-confirmed
 * "Area deleted" toast; `enableTimeout` bounds the wait for the Delete button so
 * the best-effort finally net can never stall on a stale/flux row.
 */
async function deleteArea(
  page: Page,
  name: string,
  { assert = false, enableTimeout = 10000 }: { assert?: boolean; enableTimeout?: number } = {},
): Promise<void> {
  await filter(page).fill(name);
  const row = page.locator('.tree-row', { hasText: name }).first();
  if ((await row.count()) === 0) return;
  await row.locator('.tree-row-main').click();
  const del = page.getByRole('button', { name: 'Delete', exact: true });
  await expect(del).toBeEnabled({ timeout: enableTimeout });
  await del.click();
  const confirm = page.getByRole('alertdialog');
  await confirm.getByRole('button', { name: 'Yes' }).click();
  if (assert) {
    await expect(page.getByText('Area deleted').first()).toBeVisible({ timeout: 10000 });
  }
  await confirm.waitFor({ state: 'hidden', timeout: 10000 }).catch(() => undefined);
}

test.describe('admin areas section', () => {
  test('the section loads under the shell with the toolbar and the area tree', async ({
    adminPage,
  }) => {
    await adminPage.goto('/admin/areas');
    await expect(adminPage).toHaveURL(/\/admin\/areas(?:[?#]|$)/);
    await expect(adminPage.getByRole('button', { name: 'New area' })).toBeVisible({
      timeout: 15000,
    });
    await expect(filter(adminPage)).toBeVisible();
    await expect(adminPage.getByRole('button', { name: 'Area actions' })).toBeVisible();
    // The deployment always has areas — at least one root row renders.
    await expect(adminPage.locator('.tree-row').first()).toBeVisible({ timeout: 15000 });
    // The empty detail pane invites a selection until a node is picked.
    await expect(adminPage.getByText('Select an area to edit, or add a new one.')).toBeVisible();
  });

  test('round-trip: create a test root + two children, reorder a child (revertible), rename, delete (self-cleaning)', async ({
    adminPage,
  }) => {
    test.setTimeout(90_000);
    const page = adminPage;
    // The inline editor's OpenLayers map tiles are the runtime bottleneck; abort
    // them so each selection mounts instantly. The form/geometry model is intact.
    await page.route(/openstreetmap\.org|openseamap\.org|\/wms\//, (r) => r.abort());
    const runId = `e2e-areas-${Date.now()}`;
    const rootName = `${runId}-root`;
    const childAName = `${runId}-child-A`;
    const childBName = `${runId}-child-B`;

    await page.goto('/admin/areas');
    await expect(page.getByRole('button', { name: 'New area' })).toBeVisible({ timeout: 15000 });
    await expect(page.locator('.tree-row').first()).toBeVisible({ timeout: 15000 });

    const emptyState = page.getByText('Select an area to edit, or add a new one.');
    const save = page.getByRole('button', { name: 'Save area' });

    try {
      // CREATE ROOT — nothing selected ⇒ the New button adds at the root.
      await page.getByRole('button', { name: 'New area' }).click();
      await expect(save).toBeVisible();
      await firstNameInput(page).fill(rootName);
      await save.click();
      await expect(emptyState).toBeVisible();

      // CREATE CHILD A — select the root, then New-sub-area parents the new node under it.
      await filter(page).fill(rootName);
      await selectForEdit(page, rootName);
      await page.getByRole('button', { name: 'New sub-area' }).click();
      await expect(page.getByText('Parent area')).toBeVisible();
      await firstNameInput(page).fill(childAName);
      await save.click();
      await expect(emptyState).toBeVisible();

      // CREATE CHILD B — same flow.
      await filter(page).fill(rootName);
      await selectForEdit(page, rootName);
      await page.getByRole('button', { name: 'New sub-area' }).click();
      await expect(page.getByText('Parent area')).toBeVisible();
      await firstNameInput(page).fill(childBName);
      await save.click();
      await expect(emptyState).toBeVisible();

      // BOTH CHILDREN LISTED — filtering to the child prefix auto-expands the root.
      await filter(page).fill(`${runId}-child`);
      await expect(page.locator('.tree-row-label').filter({ hasText: childAName })).toBeVisible();
      await expect(page.locator('.tree-row-label').filter({ hasText: childBName })).toBeVisible();
      const before = await childOrder(page, `${runId}-child`);
      expect(before).toHaveLength(2);

      // REORDER — move the second-listed child up; the sibling order must swap.
      // Fully revertible: the entire subtree is deleted below.
      const second = before[1];
      await selectForEdit(page, second);
      const moveUp = page.getByRole('button', { name: 'Move area up' });
      await expect(moveUp).toBeEnabled();
      await moveUp.click();
      await expect(async () => {
        const after = await childOrder(page, `${runId}-child`);
        expect(after[0]).toBe(second);
      }).toPass({ timeout: 15000 });

      // RENAME — edit the OTHER child (before[0], not the one just loaded by the
      // reorder), so the select genuinely reloads a different node before the edit.
      const renameTarget = before[0];
      const renamedName = `${renameTarget}-ed`;
      await filter(page).fill(renameTarget);
      await selectForEdit(page, renameTarget);
      await firstNameInput(page).fill(renamedName);
      await expect(save).toBeEnabled();
      await save.click();
      await expect(emptyState).toBeVisible();
      await filter(page).fill(renamedName);
      await expect(page.locator('.tree-row-label').filter({ hasText: renamedName })).toBeVisible({
        timeout: 20000,
      });

      // DELETE — deleting the test root cascade-removes its entire subtree
      // (backend behavior), so one backend-confirmed delete cleans up every node
      // created here. The tree reflects the cascade immediately.
      await deleteArea(page, rootName, { assert: true });
      await filter(page).fill(runId);
      await expect(page.locator('.tree-row').filter({ hasText: runId })).toHaveCount(0, {
        timeout: 15000,
      });
    } finally {
      // Safety net (runs on failure too): reload to drop any open editor, then
      // cascade-delete the test root — this removes any child survivors too.
      await page.goto('/admin/areas');
      await page
        .locator('.tree-row')
        .first()
        .waitFor({ state: 'visible', timeout: 15000 })
        .catch(() => undefined);
      await deleteArea(page, rootName, { enableTimeout: 4000 }).catch(() => undefined);
    }
  });

  test('the firing-area generation dialog opens from the cog on a firing-schedule domain', async ({
    adminPage,
  }) => {
    const page = adminPage;
    // The "Generate firing area messages…" cog item is gated on the session
    // domain's firing-schedule capability. Seed the session domain to the
    // deployment's firing domain (the admin user holds a role there) so the item
    // surfaces; if this backend has no such domain, skip rather than fail.
    await page.addInitScript(() => localStorage.setItem('niord.sessionDomainId', 'niord-fa'));
    await page.goto('/admin/areas');
    await expect(page.getByRole('button', { name: 'Area actions' })).toBeVisible({
      timeout: 15000,
    });
    await page.getByRole('button', { name: 'Area actions' }).click();

    const firingItem = page.getByRole('menuitem', { name: /Generate firing area messages/ });
    const hasFiring = (await firingItem.count()) > 0;
    test.skip(!hasFiring, 'no firing-schedule domain on this backend — firing dialog not testable');

    await firingItem.click();
    const dialog = page.getByRole('dialog', { name: 'Generate Firing Area Messages' });
    await expect(dialog).toBeVisible();
    // The series picker is the dialog's required field — never click Generate (it
    // would create operational messages); cancel closes without side effects.
    await expect(dialog.getByLabel('Message series')).toBeVisible();
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog).toBeHidden();
  });
});

test.describe('areas section role gating', () => {
  test('an editor (non-admin) is redirected home from the areas section', async ({
    editorPage,
  }) => {
    await editorPage.goto('/admin/areas');
    await expect(editorPage).toHaveURL(HOME, { timeout: 15000 });
    expect(editorPage.url()).not.toMatch(/\/admin/);
  });
});
