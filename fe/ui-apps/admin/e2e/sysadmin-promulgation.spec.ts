import { expect, test } from './auth/fixtures';

// Sysadmin Promulgation Types section e2e (authed via the sysadmin fixture).
// Deliberately read-only against real data: promulgation types feed the message
// editor's promulgation channels, and the nested surfaces (NAVTEX transmitters,
// SafetyNET areas, Twitter credentials) are live operational config — this spec
// never saves. It asserts the list renders, the type editor populates from a
// real row (Type ID immutable, Save gated while pristine), the per-service
// plugin dispatch works both ways (a navtex-service type shows the Transmitters
// block whose dialog opens above the drawer; a type whose serviceId has no
// registered admin plugin — e.g. `audio` — shows nothing), and a non-sysadmin
// is bounced by the role guard. Mutating walks run manually against throwaway
// entities.

const HOME = /localhost:4200\/(?:$|[?#])/;

test.describe('sysadmin promulgation types section', () => {
  test('the section loads with the toolbar and renders type rows', async ({ sysadminPage }) => {
    await sysadminPage.goto('/sysadmin/promulgation');
    await expect(sysadminPage).toHaveURL(/\/sysadmin\/promulgation(?:[?#]|$)/);
    await expect(sysadminPage.getByRole('button', { name: 'Add Promulgation Type' })).toBeVisible({
      timeout: 15000,
    });
    await expect(sysadminPage.getByRole('columnheader', { name: 'Service ID' })).toBeVisible();
    await expect(sysadminPage.locator('table.niord-table tbody tr').first()).toBeVisible();
    // Requirement renders localized labels, never the raw enum values.
    await expect(sysadminPage.locator('table.niord-table tbody')).not.toContainText('MANDATORY');
  });

  test('editing a navtex-service type shows the Transmitters plugin whose dialog opens above the drawer', async ({
    sysadminPage,
  }) => {
    const page = sysadminPage;
    await page.goto('/sysadmin/promulgation');
    const navtexRow = page
      .locator('table.niord-table tbody tr')
      .filter({ has: page.locator('td:nth-child(2)', { hasText: /^navtex$/ }) })
      .first();
    await expect(navtexRow).toBeVisible({ timeout: 15000 });
    const typeId = (await navtexRow.locator('td').nth(2).innerText()).trim();

    await navtexRow.getByRole('button', { name: 'Edit Promulgation Type' }).click();
    const drawer = page.getByRole('dialog', { name: 'Edit Promulgation Type' });
    await expect(drawer).toBeVisible();
    // The PK is immutable in edit mode; Save gated while pristine.
    await expect(drawer.getByRole('textbox', { name: 'Type ID' })).toBeDisabled();
    await expect(drawer.getByRole('textbox', { name: 'Type ID' })).toHaveValue(typeId);
    await expect(drawer.getByRole('button', { name: 'Save', exact: true })).toBeDisabled();

    // The registered navtex admin plugin renders its Manage… affordance.
    await drawer.getByRole('button', { name: 'Manage...' }).click();
    const transmitters = page.getByRole('dialog', { name: /Transmitters/ });
    await expect(transmitters).toBeVisible();
    await expect(transmitters.getByRole('columnheader', { name: 'Name' })).toBeVisible();
    // Two Close affordances exist (header ✕ + footer button) — use the footer's.
    await transmitters.locator('.dialog-foot').getByRole('button', { name: 'Close' }).click();
    await expect(transmitters).toHaveCount(0);

    // The drawer stayed open underneath the whole time.
    await expect(drawer).toBeVisible();
    await drawer.getByRole('button', { name: 'Cancel' }).click();
  });

  test('a type whose serviceId has no registered admin plugin renders no plugin block', async ({
    sysadminPage,
  }) => {
    const page = sysadminPage;
    await page.goto('/sysadmin/promulgation');
    await expect(page.locator('table.niord-table tbody tr').first()).toBeVisible({
      timeout: 15000,
    });
    // Every backend registers the `audio` service, which has no admin plugin in
    // this app — the drawer must render neither a plugin block nor a hint.
    const audioRow = page
      .locator('table.niord-table tbody tr')
      .filter({ has: page.locator('td:nth-child(2)', { hasText: /^audio$/ }) })
      .first();
    const hasAudio = (await audioRow.count()) > 0;
    test.skip(!hasAudio, 'no audio-service type on this backend — plugin-less case not testable');

    await audioRow.getByRole('button', { name: 'Edit Promulgation Type' }).click();
    const drawer = page.getByRole('dialog', { name: 'Edit Promulgation Type' });
    await expect(drawer).toBeVisible();
    await expect(drawer.getByRole('textbox', { name: 'Type ID' })).toBeDisabled();
    await expect(drawer.getByRole('button', { name: 'Manage...' })).toHaveCount(0);
    await expect(drawer.getByRole('button', { name: /Update Settings/ })).toHaveCount(0);
    await drawer.getByRole('button', { name: 'Cancel' }).click();
  });

  test('a non-sysadmin (editor role) is bounced from the section', async ({ editorPage }) => {
    await editorPage.goto('/sysadmin/promulgation');
    await expect(editorPage).toHaveURL(HOME, { timeout: 15000 });
  });
});
