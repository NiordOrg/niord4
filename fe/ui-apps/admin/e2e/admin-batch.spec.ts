import { expect, test } from './auth/fixtures';

// Smoke + golden-path tests for the sysadmin Batch Jobs section. Authed as the
// sysadmin role (sysadminPage falls back to the base test user when no
// role-scoped pair is set). READ-ONLY walk by design: this suite never
// schedules real JavaScript and never stops/restarts/abandons a live execution
// against the backend — those branches are unit-tested and exercised manually
// against a live backend. It only opens dialogs and reads counts.

test.describe('sysadmin batch jobs section', () => {
  test('the section loads under the shell (type cards or empty state)', async ({
    sysadminPage,
  }) => {
    await sysadminPage.goto('/sysadmin/batch');
    await expect(sysadminPage).toHaveURL(/\/sysadmin\/batch(?:[?#]|$)/);
    // Auto-wait for the loaded state: at least one type card OR the no-types
    // empty state (a bare isVisible samples too early).
    const firstCard = sysadminPage.locator('.batch-type-card').first();
    await expect(firstCard.or(sysadminPage.getByText('No batch jobs are available'))).toBeVisible();
  });

  test('the sysadmin toolbar offers Upload batch set and Execute JavaScript', async ({
    sysadminPage,
  }) => {
    await sysadminPage.goto('/sysadmin/batch');
    await expect(sysadminPage.getByRole('button', { name: 'Upload batch set…' })).toBeVisible();
    await expect(sysadminPage.getByRole('button', { name: 'Execute JavaScript…' })).toBeVisible();
  });

  test('selecting a type card loads its executions table (when any type exists)', async ({
    sysadminPage,
  }) => {
    await sysadminPage.goto('/sysadmin/batch');
    const firstCard = sysadminPage.locator('.batch-type-card').first();
    await expect(firstCard.or(sysadminPage.getByText('No batch jobs are available'))).toBeVisible();
    const hasTypes = await firstCard.isVisible();
    if (!hasTypes) {
      test.skip(true, 'No batch job types reachable — cannot exercise the executions table.');
      return;
    }
    await firstCard.click();
    // The detail pane resolves to either an executions table or the
    // no-executions empty state for that type.
    await expect(
      sysadminPage
        .locator('table tbody tr')
        .first()
        .or(sysadminPage.getByText('No executions for this job type')),
    ).toBeVisible();
  });

  test('a /sysadmin/batch/{type} deep link pre-selects that type (when any type exists)', async ({
    sysadminPage,
  }) => {
    await sysadminPage.goto('/sysadmin/batch');
    const firstCard = sysadminPage.locator('.batch-type-card').first();
    await expect(firstCard.or(sysadminPage.getByText('No batch jobs are available'))).toBeVisible();
    const hasTypes = await firstCard.isVisible();
    if (!hasTypes) {
      test.skip(true, 'No batch job types reachable — cannot exercise the deep link.');
      return;
    }
    const typeName = ((await firstCard.locator('.font-medium').first().textContent()) ?? '').trim();
    await sysadminPage.goto(`/sysadmin/batch/${encodeURIComponent(typeName)}`);
    // The pre-selected card carries the selected highlight class.
    await expect(
      sysadminPage.locator('.batch-type-card.is-selected').filter({ hasText: typeName }),
    ).toBeVisible();
  });

  test('the Execute JavaScript dialog opens, Schedule is disabled while blank, Cancel closes it', async ({
    sysadminPage,
  }) => {
    await sysadminPage.goto('/sysadmin/batch');
    await sysadminPage.getByRole('button', { name: 'Execute JavaScript…' }).click();

    const dialog = sysadminPage.getByRole('dialog', { name: 'Execute JavaScript' });
    await expect(dialog).toBeVisible();
    // Freshly opened, blank code → Schedule disabled. (Never click Schedule —
    // this would execute server-side JS against the live backend.)
    await expect(dialog.getByRole('button', { name: 'Schedule' })).toBeDisabled();

    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog).toBeHidden();
  });
});
