import type { Locator, Page } from '@playwright/test';

import { expect, test } from './support/fixtures';
import { openKeycloakConsole } from './support/open';
import { shot, type ShotOptions } from './support/shot';

/*
 * The pictures of the local Keycloak's administration console for the sysadmin manual's
 * Keycloak chapter, written to guides/sysadmin-manual/images/ (openKeycloakConsole). Their look is
 * Keycloak's, of the version the compose file runs.
 *
 * One test per picture, titled `<manual>/<file>` after the file it writes, so
 * `--grep "<manual>/<file>"` takes one picture again and manifest-check.mjs reads the list from the
 * titles. Every manifest entry marked retake, replace or new becomes one test here. The console is
 * only looked at: no form is saved.
 *
 *   npx playwright test -c guides-shots/playwright.config.ts keycloak.shots.ts
 */

/** The group the pictures of a group show: an editor group of the NW domain, with an attribute. */
const GROUP = 'MAS duty';

/** The domain whose client the role pictures show: the session domain of the other pictures. */
const DOMAIN_CLIENT = 'niord-nw';

/** A picture of the console's window, without the local container's banner. */
function consoleShot(page: Page, hide: Locator[], options: ShotOptions = {}): Promise<string> {
  return shot(page, { hide, maps: false, guard: { checks: 'external' }, ...options });
}

/** Searches a console list (clients, users) for a term, and waits for its first row. */
async function search(page: Page, placeholder: string, term: string): Promise<void> {
  const box = page.getByPlaceholder(placeholder);
  await box.fill(term);
  await box.press('Enter');
  await expect(page.locator('table tbody tr').first()).toContainText(term);
  await box.blur();
}

/** Opens a client of the realm by its client id. */
async function openClient(page: Page, clientId: string): Promise<Locator[]> {
  const hide = await openKeycloakConsole(page, 'niord', 'clients');
  await search(page, 'Search for client', clientId);
  await page.getByRole('link', { name: clientId, exact: true }).click();
  await expect(page.getByRole('heading', { name: clientId })).toBeVisible();
  return hide;
}

/** Opens the group the pictures show. */
async function openGroup(page: Page): Promise<Locator[]> {
  const hide = await openKeycloakConsole(page, 'niord', 'groups');
  await page.locator('table').getByRole('link', { name: GROUP, exact: true }).click();
  await expect(page.getByRole('heading', { name: GROUP })).toBeVisible();
  return hide;
}

test.describe('keycloak', () => {
  // The manifest's entries, in the manual's order.

  // The search lists ten clients, one row each: a taller window shows them all, niord-nw (the
  // text's example) and niord-web among them.
  test.describe('in a window tall enough for the client list', () => {
    test.use({ viewport: { width: 1280, height: 1080 } });

    test('sysadmin-manual/KeycloakClients.png', async ({ page }) => {
      const hide = await openKeycloakConsole(page, 'niord', 'clients');
      // Niord's own clients, without Keycloak's built-in ones.
      await search(page, 'Search for client', 'niord');
      const table = page.locator('table');
      await expect(table.getByRole('link', { name: 'niord-nw', exact: true })).toBeVisible();
      await expect(table.getByRole('link', { name: 'niord-web', exact: true })).toBeVisible();
      await consoleShot(page, hide);
    });
  });

  test('sysadmin-manual/KeycloakClientRoles.png', async ({ page }) => {
    const hide = await openClient(page, DOMAIN_CLIENT);
    await page.getByRole('tab', { name: 'Roles' }).click();
    await expect(page.locator('table tbody tr').filter({ hasText: 'sysadmin' })).toBeVisible();
    await consoleShot(page, hide);
  });

  test('sysadmin-manual/KeycloakUsers.png', async ({ page }) => {
    const hide = await openKeycloakConsole(page, 'niord', 'users');
    await consoleShot(page, hide);
  });

  test('sysadmin-manual/KeycloakGroups.png', async ({ page }) => {
    const hide = await openKeycloakConsole(page, 'niord', 'groups');
    await expect(page.locator('table').getByText(GROUP, { exact: true })).toBeVisible();
    await consoleShot(page, hide);
  });

  test('sysadmin-manual/KeycloakGroupRoles.png', async ({ page }) => {
    const hide = await openGroup(page);
    // Beside the console's menu the group's tab strip is too narrow, and its scroller cuts the
    // Role mapping tab the text names: the menu is closed for this picture.
    await page.getByRole('button', { name: 'navigation', exact: true }).click();
    await expect(page.getByRole('link', { name: 'Clients', exact: true })).toBeHidden();
    await page.getByRole('tab', { name: 'Role mapping' }).click();
    await expect(
      page.locator('table tbody tr').filter({ hasText: 'editor' }).first(),
    ).toBeVisible();
    await consoleShot(page, hide);
  });

  test('sysadmin-manual/KeycloakClientMapper.png', async ({ page }) => {
    const hide = await openClient(page, 'niord-web');
    await page.getByRole('tab', { name: 'Client scopes' }).click();
    await page.getByRole('link', { name: 'niord-web-dedicated' }).click();
    await page.getByRole('link', { name: 'editor fields NW' }).click();
    await expect(page.getByRole('textbox', { name: 'Token Claim Name' })).toHaveValue(
      'editorFieldsNw',
    );
    // The console puts the focus in the User Attribute field: its focus ring is not the picture's.
    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
    await consoleShot(page, hide);
  });

  test('sysadmin-manual/KeycloakGroupAttribute.png', async ({ page }) => {
    const hide = await openGroup(page);
    // As for KeycloakGroupRoles: beside the console's menu the tab strip is cut and the Value field
    // narrow, so the menu is closed for this picture.
    await page.getByRole('button', { name: 'navigation', exact: true }).click();
    await expect(page.getByRole('link', { name: 'Clients', exact: true })).toBeHidden();
    await page.getByRole('tab', { name: 'Attributes' }).click();
    await expect(page.getByRole('textbox', { name: 'Value' }).first()).toHaveValue(/publication/);
    await consoleShot(page, hide);
  });
});
