import { expect, test } from './auth/fixtures';

// Users & Contacts section e2e — the Users side. The Users tab is gated on the
// Keycloak manage-users client role (realm-management): tests probe the live
// grant and skip the gated flows when the fixture user lacks it. The live
// round-trip creates a DISPOSABLE Keycloak user and deletes it (self-cleaning).

const DISPOSABLE_USERNAME = 'e2e-user-901';

async function openUsersTabOrSkip(page: import('@playwright/test').Page): Promise<boolean> {
  await page.goto('/admin/users');
  await expect(page.getByRole('tab', { name: 'Contacts' })).toBeVisible();
  // The tab label carries an icon glyph in its accessible name — match end-anchored.
  return page
    .getByRole('tab', { name: /Users$/ })
    .isVisible()
    .catch(() => false);
}

test.describe('admin users section', () => {
  test('the section renders under the shell with the Contacts tab always present', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/admin/users');
    await expect(authenticatedPage).toHaveURL(/\/admin\/users(?:[?#]|$)/);
    await expect(authenticatedPage.getByRole('tab', { name: 'Contacts' })).toBeVisible();
  });

  test('the gated Users tab lists users with the standalone Keycloak link', async ({
    authenticatedPage,
  }) => {
    test.skip(!(await openUsersTabOrSkip(authenticatedPage)), 'fixture lacks manage-users');

    await expect(authenticatedPage.getByLabel('Filter users')).toBeVisible();
    // The external console link is a standalone toolbar anchor (no cog menu).
    const link = authenticatedPage.getByRole('link', { name: /Keycloak/ });
    await expect(link).toBeVisible();
    await expect(link).toHaveAttribute('target', '_blank');
    // The live realm has users — rows render.
    await expect(authenticatedPage.locator('app-users-tab tbody tr').first()).toBeVisible();
  });

  test('the user editor opens as a wide tabbed drawer; username is immutable on edit', async ({
    authenticatedPage,
  }) => {
    test.skip(!(await openUsersTabOrSkip(authenticatedPage)), 'fixture lacks manage-users');

    await authenticatedPage.getByRole('button', { name: 'Edit user' }).first().click();
    const drawer = authenticatedPage.getByRole('dialog');
    await expect(drawer).toBeVisible();
    await expect(drawer.getByRole('tab', { name: 'User details' })).toBeVisible();
    await expect(drawer.getByRole('tab', { name: 'Groups' })).toBeVisible();
    await expect(drawer.getByRole('tab', { name: 'Mailing lists' })).toBeVisible();
    await expect(drawer.getByLabel('Username')).toBeDisabled();
    await expect(drawer.getByRole('button', { name: 'Save' })).toBeDisabled(); // unchanged

    await drawer.getByRole('button', { name: 'Cancel' }).click();
    await expect(drawer).toBeHidden();
  });

  test('live round-trip: add a disposable user, find it, edit it, toggle a membership, delete it', async ({
    authenticatedPage,
  }) => {
    test.skip(!(await openUsersTabOrSkip(authenticatedPage)), 'fixture lacks manage-users');
    const page = authenticatedPage;

    // ADD — Groups/Mailing-lists tabs must NOT exist in add mode.
    await page.getByRole('button', { name: /Add$/ }).click();
    const drawer = page.getByRole('dialog');
    await expect(drawer.getByRole('tab', { name: 'User details' })).toBeVisible();
    await expect(drawer.getByRole('tab', { name: 'Groups' })).toHaveCount(0);
    await drawer.getByLabel('Username').fill(DISPOSABLE_USERNAME);
    await drawer.getByLabel('E-mail').fill('e2e-901@example.invalid');
    await drawer.getByRole('button', { name: 'Save' }).click();
    await expect(drawer).toBeHidden();

    // FIND via the filter (commits on blur immediately).
    await page.getByLabel('Filter users').fill(DISPOSABLE_USERNAME);
    await page.getByLabel('Filter users').blur();
    const row = page.getByRole('row').filter({ hasText: DISPOSABLE_USERNAME });
    await expect(row).toHaveCount(1);

    // EDIT — toggle a mailing-list membership on and back off (toast-quiet, dot flips).
    await row.getByRole('button', { name: 'Edit user' }).click();
    await expect(drawer.getByLabel('Username')).toHaveValue(DISPOSABLE_USERNAME);
    await drawer.getByRole('tab', { name: 'Mailing lists' }).click();
    const firstListRow = drawer.locator('app-mailing-list-membership-panel tbody tr').first();
    await expect(firstListRow).toBeVisible();
    await firstListRow.getByRole('button', { name: 'Join' }).click();
    await expect(firstListRow.getByRole('button', { name: 'Leave' })).toBeVisible();
    await firstListRow.getByRole('button', { name: 'Leave' }).click();
    await expect(firstListRow.getByRole('button', { name: 'Join' })).toBeVisible();
    await drawer.getByRole('button', { name: 'Cancel' }).click();
    await expect(drawer).toBeHidden();

    // DELETE (cleanup) — the confirm shows the username.
    await row.getByRole('button', { name: 'Delete user' }).click();
    const confirm = page.getByRole('alertdialog');
    await expect(confirm.getByText(DISPOSABLE_USERNAME)).toBeVisible();
    await confirm.getByRole('button', { name: /Delete|Yes/ }).click();
    await expect(page.getByRole('row').filter({ hasText: DISPOSABLE_USERNAME })).toHaveCount(0);
  });
});

test.describe('admin contacts tab', () => {
  const DISPOSABLE_EMAIL = 'e2e-contact-901@example.invalid';

  async function openContactsTab(page: import('@playwright/test').Page): Promise<void> {
    await page.goto('/admin/users');
    await page.getByRole('tab', { name: 'Contacts' }).click();
  }

  test('the contacts list renders server-paged with the count line', async ({
    authenticatedPage,
  }) => {
    await openContactsTab(authenticatedPage);
    await expect(authenticatedPage.getByLabel('Filter contacts')).toBeVisible();
    await expect(authenticatedPage.getByText(/Displaying \d+ of \d+ contacts/)).toBeVisible();
  });

  test('live round-trip: import a disposable email, edit the contact, then delete it', async ({
    authenticatedPage,
  }) => {
    const page = authenticatedPage;
    await openContactsTab(page);

    // IMPORT (paste, not a file) — the backend replies with its plain-text result.
    await page.getByRole('button', { name: 'Contact actions' }).click();
    await page.getByRole('menuitem', { name: 'Import emails…' }).click();
    const importDialog = page.getByRole('dialog', { name: 'Import contact emails' });
    await expect(importDialog).toBeVisible();
    const importButton = importDialog.getByRole('button', { name: 'Import' });
    await expect(importButton).toBeDisabled(); // empty textarea never fires a request
    // The dialog's onShow reset (fires post-animation) can wipe text typed
    // before it runs — retry the fill until the button enables.
    await expect(async () => {
      await importDialog.locator('textarea').fill(DISPOSABLE_EMAIL);
      await expect(importButton).toBeEnabled({ timeout: 1500 });
    }).toPass({ timeout: 10000 });
    // dispatchEvent: the dialog's enter transition keeps the button 'unstable'
    // for Playwright's actionability check even though it is fully interactive.
    await importButton.dispatchEvent('click');
    await expect(importDialog).toBeHidden();

    // FIND via the server-paged name filter? The import sets no name — filter by email
    // is not supported (name-only search), so locate it on the first page by text
    // after a fresh search ordered by… fall back to filtering rows client-side.
    await expect(async () => {
      await page.getByLabel('Filter contacts').fill('');
      await page.getByLabel('Filter contacts').blur();
      await expect(page.getByRole('row').filter({ hasText: DISPOSABLE_EMAIL }).first()).toBeVisible(
        { timeout: 3000 },
      );
    }).toPass({ timeout: 15000 });
    const row = page.getByRole('row').filter({ hasText: DISPOSABLE_EMAIL }).first();

    // EDIT — its own drawer (Contact details tab), set a name.
    await row.getByRole('button', { name: 'Edit contact' }).click();
    const drawer = page.getByRole('dialog', { name: /Contact$/ });
    await expect(drawer.getByRole('tab', { name: 'Contact details' })).toBeVisible();
    await expect(drawer.getByRole('tab', { name: 'Mailing lists' })).toBeVisible();
    await expect(drawer.getByLabel('E-mail')).toHaveValue(DISPOSABLE_EMAIL);
    await drawer.getByLabel('Name', { exact: true }).fill('E2E disposable');
    await drawer.getByRole('button', { name: 'Save' }).click();
    await expect(drawer).toBeHidden();

    // DELETE (cleanup) — the confirm shows the email.
    await row.getByRole('button', { name: 'Delete contact' }).click();
    const confirm = page.getByRole('alertdialog');
    await expect(confirm.getByText(DISPOSABLE_EMAIL)).toBeVisible();
    await confirm.getByRole('button', { name: /Delete|Yes/ }).click();
    await expect(page.getByRole('row').filter({ hasText: DISPOSABLE_EMAIL })).toHaveCount(0);
  });
});
