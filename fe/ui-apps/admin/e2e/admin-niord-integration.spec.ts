import type { Page } from '@playwright/test';

import { expect, test } from './auth/fixtures';

// The sysadmin Niord Integration section's RUNTIME visibility is gated by the
// backend `adminIntegrationPageEnabled` setting (editable on the Settings page).
// The setting hides the rail link and overview card ONLY — the route always
// resolves, so a deep link loads the page regardless of the rail state.
//
// This suite makes NO env assumptions: it probes the live backend's current
// setting via the rail link's presence. On this dev backend the setting is TRUE,
// so the golden-path block runs; on a backend where it is off it self-skips. The
// deep-link and rail/card-agreement tests run unconditionally.

const RAIL_LINK_NAME = 'Niord Integration';

/** True when the gated section is currently exposed in this deployment's rail. */
async function integrationRailLinkVisible(page: Page): Promise<boolean> {
  await page.goto('/sysadmin/overview');
  const link = page.getByRole('navigation').getByRole('link', { name: RAIL_LINK_NAME });
  return (await link.count()) > 0;
}

test.describe('sysadmin niord integration — visibility gate (runtime setting)', () => {
  test('deep link always resolves (visibility gate is menu-only)', async ({ sysadminPage }) => {
    // The route is registered unconditionally; only the rail/card honour the
    // setting. So the page must load whatever the rail state is.
    await sysadminPage.goto('/sysadmin/niord-integration');
    await expect(sysadminPage).toHaveURL(/\/sysadmin\/niord-integration(?:[?#]|$)/);
    // The section page is up: its toolbar action is present (heading shares the
    // section label with the rail/card, so the button is the unambiguous probe).
    await expect(sysadminPage.getByRole('button', { name: 'New Niord Integration' })).toBeVisible();
  });

  test('the rail link and the overview card agree (both present or both absent)', async ({
    sysadminPage,
  }) => {
    await sysadminPage.goto('/sysadmin/overview');
    const railCount = await sysadminPage
      .getByRole('navigation')
      .getByRole('link', { name: RAIL_LINK_NAME })
      .count();
    const cardCount = await sysadminPage
      .locator('app-admin-overview-page')
      .getByRole('link', { name: RAIL_LINK_NAME })
      .count();
    // The shell-provided gate service drives both surfaces, so they can never
    // disagree: a gated-off section shows neither, a gated-on section shows both.
    expect(railCount).toBe(cardCount);
  });
});

// Golden-path / editor coverage. Each test probes the rail and self-skips when the
// section is gated off, so the block activates on backends where the setting is on
// (this dev backend) and lies dormant where it is off — no env assumptions.
test.describe('sysadmin niord integration — golden path (setting-on backends)', () => {
  test('the section loads under the shell with the toolbar (list or empty state)', async ({
    sysadminPage,
  }) => {
    test.skip(
      !(await integrationRailLinkVisible(sysadminPage)),
      'Niord Integration is gated off on this backend.',
    );
    await sysadminPage.goto('/sysadmin/niord-integration');
    await expect(sysadminPage).toHaveURL(/\/sysadmin\/niord-integration(?:[?#]|$)/);
    await expect(sysadminPage.getByRole('button', { name: 'New Niord Integration' })).toBeVisible();
  });

  test('New opens the editor with the three flags defaulted, URL field, one seeded mapping row', async ({
    sysadminPage,
  }) => {
    test.skip(
      !(await integrationRailLinkVisible(sysadminPage)),
      'Niord Integration is gated off on this backend.',
    );
    await sysadminPage.goto('/sysadmin/niord-integration');
    await sysadminPage.getByRole('button', { name: 'New Niord Integration' }).click();

    const dialog = sysadminPage.getByRole('dialog');
    await expect(dialog).toBeVisible();

    // The three flags with their helper sentences; the URL field.
    await expect(dialog.getByText('Active', { exact: true })).toBeVisible();
    await expect(dialog.getByText('Assign new UIDs', { exact: true })).toBeVisible();
    await expect(
      dialog.getByText('Assign new underlying UID to each imported message', { exact: true }),
    ).toBeVisible();
    await expect(dialog.getByText('Create base data', { exact: true })).toBeVisible();
    await expect(
      dialog.getByText('Create missing base data such as areas, charts, etc.', { exact: true }),
    ).toBeVisible();
    await expect(dialog.getByText('Niord URL', { exact: true })).toBeVisible();

    // Default flag states: active + assignNewUids checked, createBaseData unchecked.
    const checkboxes = dialog.getByRole('checkbox');
    await expect(checkboxes.nth(0)).toBeChecked(); // active
    await expect(checkboxes.nth(1)).toBeChecked(); // assignNewUids
    await expect(checkboxes.nth(2)).not.toBeChecked(); // createBaseData

    // Exactly one seeded mapping row → one source input + one target select.
    await expect(dialog.getByText('Source Message Series', { exact: true })).toBeVisible();
    await expect(dialog.getByText('Target Message Series', { exact: true })).toBeVisible();

    // Freshly opened: no net change yet → Save disabled.
    await expect(dialog.getByRole('button', { name: 'Save Niord Integration' })).toBeDisabled();

    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog).toBeHidden();
  });

  test('trash on the sole mapping row re-seeds a blank (never zero rows)', async ({
    sysadminPage,
  }) => {
    test.skip(
      !(await integrationRailLinkVisible(sysadminPage)),
      'Niord Integration is gated off on this backend.',
    );
    await sysadminPage.goto('/sysadmin/niord-integration');
    await sysadminPage.getByRole('button', { name: 'New Niord Integration' }).click();
    const dialog = sysadminPage.getByRole('dialog');
    await expect(dialog).toBeVisible();

    // One seeded row at open. Remove it → the editor re-seeds a fresh blank, so
    // the source/target column headers stay present (still one row).
    await dialog.getByRole('button', { name: 'Remove mapping' }).first().click();
    await expect(dialog.getByText('Target Message Series', { exact: true })).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'Remove mapping' })).toHaveCount(1);

    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog).toBeHidden();
  });

  test('per-row Execute issues the execute PUT when a row exists', async ({ sysadminPage }) => {
    test.skip(
      !(await integrationRailLinkVisible(sysadminPage)),
      'Niord Integration is gated off on this backend.',
    );
    await sysadminPage.goto('/sysadmin/niord-integration');
    await expect(sysadminPage.getByRole('button', { name: 'New Niord Integration' })).toBeVisible();

    const executeButtons = sysadminPage.getByRole('button', { name: 'Execute Niord Integration' });
    if ((await executeButtons.count()) === 0) {
      test.skip(true, 'No integrations reachable — cannot exercise the execute PUT.');
      return;
    }

    const putPromise = sysadminPage.waitForRequest(
      (req) => req.method() === 'PUT' && /\/niord-integrations\/execute\/\d+/.test(req.url()),
    );
    await executeButtons.first().click();
    const put = await putPromise;
    expect(put.method()).toBe('PUT');
  });
});
