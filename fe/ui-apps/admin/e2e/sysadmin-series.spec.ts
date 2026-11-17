import { expect, test } from './auth/fixtures';

// Sysadmin Message Series section e2e (authed via the sysadmin fixture; falls
// back to the base pair). Route + toolbar smoke; the list renders rows with
// KEYED sequence labels (never raw enum values); and one self-cleaning
// round-trip on a throwaway series: create → edit (next-number popover
// open + cancel, sequence-type flips hide/show the Short Format group) →
// save → delete. The throwaway id is timestamped so parallel runs never
// collide, and a finally-block safety net removes it even when an assertion
// fails mid-flight. Real series are never mutated — the next-number popover
// is only ever opened and cancelled.

test.describe('sysadmin message series section', () => {
  test('the section loads under the shell with the toolbar', async ({ sysadminPage }) => {
    await sysadminPage.goto('/sysadmin/series');
    await expect(sysadminPage).toHaveURL(/\/sysadmin\/series(?:[?#]|$)/);
    await expect(sysadminPage.getByRole('button', { name: 'New Message Series' })).toBeVisible({
      timeout: 15000,
    });
    await expect(sysadminPage.getByLabel('Filter', { exact: true })).toBeVisible();
  });

  test('the list renders series rows with keyed sequence labels (no raw enums)', async ({
    sysadminPage,
  }) => {
    await sysadminPage.goto('/sysadmin/series');
    await expect(sysadminPage.getByRole('columnheader', { name: 'Series ID' })).toBeVisible({
      timeout: 15000,
    });
    const rows = sysadminPage.locator('table.niord-table tbody tr');
    await expect(rows.first()).toBeVisible();

    // The Sequence column must render the localized labels, never the raw
    // NumberSequenceType enum values.
    const sequenceCells = await rows.locator('td:nth-child(3)').allInnerTexts();
    expect(sequenceCells.length).toBeGreaterThan(0);
    for (const text of sequenceCells) {
      expect(text.trim()).not.toMatch(/^(YEARLY|CONTINUOUS|MANUAL|NONE)$/);
    }
  });

  test('round-trip: create a throwaway series, open+cancel the next-number popover, flip the sequence type (Short Format group hides/shows), save, delete (self-cleaning)', async ({
    sysadminPage,
  }) => {
    const page = sysadminPage;
    // Unique per run so parallel/interrupted runs never collide on the PK.
    const id = `e2e-test-series-${Date.now()}`;

    await page.goto('/sysadmin/series');
    await expect(page.getByRole('button', { name: 'New Message Series' })).toBeVisible({
      timeout: 15000,
    });
    // Wait for the initial list load to settle (rows or the empty state).
    await expect(
      page.locator('table.niord-table tbody tr').first().or(page.locator('app-empty-state')),
    ).toBeVisible();

    try {
      // CREATE — defaults are NW + Yearly; only the ID is required.
      await page.getByRole('button', { name: 'New Message Series' }).click();
      const drawer = page.getByRole('dialog');
      await expect(drawer).toBeVisible();
      const save = drawer.getByRole('button', { name: 'Save Message Series' });
      await expect(save).toBeDisabled(); // nothing changed yet
      await drawer.getByLabel('Message Series ID').fill(id);
      await expect(save).toBeEnabled();
      await save.click();
      await expect(drawer).toBeHidden();

      // FIND via the client-side filter — the new row carries the keyed Yearly label.
      await page.getByLabel('Filter', { exact: true }).fill(id);
      const row = page.getByRole('row').filter({ hasText: id });
      await expect(row).toHaveCount(1);
      await expect(row).toContainText('Yearly');

      // EDIT — the ID is immutable in edit mode.
      await row.getByRole('button', { name: 'Edit Message Series' }).click();
      await expect(drawer).toBeVisible();
      await expect(drawer.getByLabel('Message Series ID')).toHaveValue(id);
      await expect(drawer.getByLabel('Message Series ID')).toBeDisabled();

      // NEXT-NUMBER POPOVER — pristine form ⇒ the pencil is enabled; open it,
      // assert the YEARLY shape (Year + Next number + Compute/Cancel/Update),
      // then CANCEL — dismissing without Update must persist nothing.
      const pencil = drawer.getByRole('button', { name: 'Edit Next Message Series Number' });
      await expect(pencil).toBeEnabled();
      await pencil.click();
      // The popover panel portals to the CDK overlay container — scope by its form.
      const popover = page.locator('form').filter({ hasText: 'Next Message Number' });
      await expect(popover).toBeVisible();
      await expect(popover.getByLabel('Year')).toBeVisible(); // YEARLY-only year input
      await expect(popover.getByLabel('Next number')).toBeVisible();
      await expect(popover.getByRole('button', { name: 'Compute' })).toBeVisible();
      await expect(popover.getByRole('button', { name: 'Update' })).toBeVisible();
      await popover.getByRole('button', { name: 'Cancel' }).click();
      await expect(popover).toBeHidden();
      await expect(drawer).toBeVisible(); // the drawer stays open behind the popover

      // SEQUENCE-TYPE FLIPS — the Short Format group hides for NONE and
      // reappears for an auto-numbered type (the working text survives; only
      // the save payload strips it).
      const shortFormatLabel = drawer.getByText('Short Format', { exact: true });
      await expect(shortFormatLabel).toBeVisible();
      await drawer.getByLabel('None', { exact: true }).check();
      await expect(shortFormatLabel).toBeHidden();
      await drawer.getByLabel('Yearly', { exact: true }).check();
      await expect(shortFormatLabel).toBeVisible();

      // A net change (Yearly → Continuous) enables Save; the list re-renders
      // the keyed label after the refetch.
      await drawer.getByLabel('Continuous', { exact: true }).check();
      await expect(save).toBeEnabled();
      await save.click();
      await expect(drawer).toBeHidden();
      await expect(row).toContainText('Continuous');

      // DELETE — the confirm names the throwaway id.
      await row.getByRole('button', { name: 'Delete Message Series' }).click();
      const confirm = page.getByRole('alertdialog');
      await expect(confirm.getByText(id)).toBeVisible();
      await confirm.getByRole('button', { name: /Delete|Yes/ }).click();
      await expect(page.getByRole('row').filter({ hasText: id })).toHaveCount(0);
    } finally {
      // Safety net (runs on failure too): reload the section to drop any open
      // drawer/popover, then delete the throwaway if it survived. Best-effort
      // and non-asserting so it never masks the original test failure.
      await page.goto('/sysadmin/series');
      await page
        .locator('table.niord-table tbody tr')
        .first()
        .or(page.locator('app-empty-state'))
        .waitFor({ state: 'visible', timeout: 15000 })
        .catch(() => undefined);
      await page.getByLabel('Filter', { exact: true }).fill(id);
      const leftover = page.getByRole('row').filter({ hasText: id });
      if ((await leftover.count()) > 0) {
        await leftover.first().getByRole('button', { name: 'Delete Message Series' }).click();
        await page
          .getByRole('alertdialog')
          .getByRole('button', { name: /Delete|Yes/ })
          .click();
        await leftover
          .first()
          .waitFor({ state: 'detached', timeout: 10000 })
          .catch(() => undefined);
      }
    }
  });
});
