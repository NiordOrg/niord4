import { expect, test } from './auth/fixtures';

// Sysadmin Domains section e2e (authed via the sysadmin fixture). Deliberately
// read-only against real data: creating a domain silently provisions a Keycloak
// client server-side and deleting one mutates the catalog the session runs on,
// so this spec never saves — it asserts the edit drawer populates from a real
// row (ID immutable, Save gated while pristine), the create drawer opens with
// the add-mode defaults and cancels cleanly, and the cog Import… dialog opens
// (smoke). The full create → edit → copy → delete walk runs manually against a
// disposable domain. A negative test proves
// a non-sysadmin (editor role) is bounced from the section by the role guard
// (authed-but-unauthorized → UrlTree('/'), i.e. redirect home — see
// role-access.spec.ts for the area-level equivalent).

const HOME = /localhost:4200\/(?:$|[?#])/;

test.describe('sysadmin domains section', () => {
  test('the section loads under the shell with the toolbar', async ({ sysadminPage }) => {
    await sysadminPage.goto('/sysadmin/domains');
    await expect(sysadminPage).toHaveURL(/\/sysadmin\/domains(?:[?#]|$)/);
    await expect(sysadminPage.getByRole('button', { name: 'New Domain' })).toBeVisible({
      timeout: 15000,
    });
    // The console link-out and the cog menu ride the same toolbar.
    await expect(sysadminPage.getByRole('link', { name: /Keycloak/ })).toBeVisible();
    await expect(sysadminPage.getByRole('button', { name: 'Domain actions' })).toBeVisible();
  });

  test('the list renders domain rows', async ({ sysadminPage }) => {
    await sysadminPage.goto('/sysadmin/domains');
    await expect(sysadminPage.getByRole('columnheader', { name: 'Keycloak ID' })).toBeVisible({
      timeout: 15000,
    });
    // The deployment always has at least one domain — the app runs on one.
    await expect(sysadminPage.locator('table.niord-table tbody tr').first()).toBeVisible();
  });

  test('Edit opens the drawer populated from the row: ID immutable, Keycloak status row present, Save disabled while pristine', async ({
    sysadminPage,
  }) => {
    const page = sysadminPage;
    await page.goto('/sysadmin/domains');
    const firstRow = page.locator('table.niord-table tbody tr').first();
    await expect(firstRow).toBeVisible({ timeout: 15000 });
    // Column 3 is the Keycloak ID (after the active dot and the sort order).
    const domainId = (await firstRow.locator('td').nth(2).innerText()).trim();
    expect(domainId).not.toBe('');

    await firstRow.getByRole('button', { name: 'Edit Domain' }).click();
    const drawer = page.getByRole('dialog');
    await expect(drawer).toBeVisible();
    await expect(drawer.getByRole('heading', { name: 'Edit Domain' })).toBeVisible();

    // Populated from the row, and the PK is locked in edit mode.
    const idInput = drawer.getByLabel('Keycloak ID', { exact: true });
    await expect(idInput).toHaveValue(domainId);
    await expect(idInput).toBeDisabled();
    // The Keycloak status row renders in edit mode only — either the
    // "client exists" text or the Create in Keycloak button.
    await expect(drawer.getByText('Keycloak Status')).toBeVisible();

    // Pristine: no net change ⇒ Save stays disabled.
    await expect(drawer.getByRole('button', { name: 'Save Domain' })).toBeDisabled();

    // A pristine drawer closes without the unsaved-changes confirm.
    await drawer.getByRole('button', { name: 'Cancel' }).click();
    await expect(drawer).toBeHidden();
  });

  test('New opens the create drawer with the add-mode defaults and cancels cleanly (no domain is created)', async ({
    sysadminPage,
  }) => {
    const page = sysadminPage;
    await page.goto('/sysadmin/domains');
    await page.getByRole('button', { name: 'New Domain' }).click();

    const drawer = page.getByRole('dialog');
    await expect(drawer).toBeVisible();
    await expect(drawer.getByRole('heading', { name: 'New Domain' })).toBeVisible();

    // Add-mode defaults: blank editable ID, active on, sort order 0, AREA ASC message
    // sorting — and the time zone left unanswered, showing its placeholder rather than a
    // guess taken from the browser.
    const idInput = drawer.getByLabel('Keycloak ID', { exact: true });
    await expect(idInput).toHaveValue('');
    await expect(idInput).toBeEnabled();
    await expect(drawer.getByRole('checkbox', { name: 'Domain is active' })).toBeChecked();
    await expect(drawer.getByLabel('Sort Order', { exact: true })).toHaveValue('0');
    await expect(drawer.getByRole('combobox', { name: 'Time Zone' })).toHaveText(
      'Choose a time zone…',
    );
    await expect(drawer.getByRole('combobox', { name: 'Message Sort Order' })).toContainText(
      'Area - ascending',
    );
    // No Keycloak status row in add mode (provisioning happens server-side on create).
    await expect(drawer.getByText('Keycloak Status')).toHaveCount(0);

    // The seeded defaults ARE the baseline — Save stays disabled until a real edit,
    // and the pristine drawer cancels without the unsaved-changes confirm.
    await expect(drawer.getByRole('button', { name: 'Save Domain' })).toBeDisabled();
    await drawer.getByRole('button', { name: 'Cancel' }).click();
    await expect(drawer).toBeHidden();
  });

  test('the cog Import… opens the upload dialog (smoke)', async ({ sysadminPage }) => {
    await sysadminPage.goto('/sysadmin/domains');
    await expect(sysadminPage.getByRole('button', { name: 'Domain actions' })).toBeVisible({
      timeout: 15000,
    });
    await sysadminPage.getByRole('button', { name: 'Domain actions' }).click();
    await sysadminPage.getByRole('menuitem', { name: 'Import...' }).click();

    const dialog = sysadminPage.getByRole('dialog');
    await expect(dialog.getByText('Upload Domains File')).toBeVisible();
    // Close via the header ✕ (aria-label "Close" — unambiguous vs the footer text button).
    await dialog.getByLabel('Close').click();
    await expect(dialog).toBeHidden();
  });
});

test.describe('domains section role gating', () => {
  test('an editor (non-sysadmin) is redirected home from the domains section', async ({
    editorPage,
  }) => {
    await editorPage.goto('/sysadmin/domains');
    await expect(editorPage).toHaveURL(HOME);
    expect(editorPage.url()).not.toMatch(/\/sysadmin/);
  });
});
