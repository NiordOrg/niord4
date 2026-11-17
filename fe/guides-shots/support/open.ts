import { expect, type Locator, type Page } from '@playwright/test';

import { INFO_URL, KEYCLOAK_ADMIN, KEYCLOAK_URL } from './env';

/**
 * An address of the public site, in English. The site takes its language from `?lang=` before
 * the reader's remembered choice and the deployment's default, and does not remember it.
 */
export function infoUrl(path: string): string {
  const address = new URL(path, `${INFO_URL}/`);
  if (!address.searchParams.has('lang')) address.searchParams.set('lang', 'en');
  return address.toString();
}

/**
 * Signs in to the local Keycloak's administration console as its bootstrap administrator and
 * opens a section of a realm, e.g. `openKeycloakConsole(page, 'niord', 'clients')`. Returns what
 * a picture leaves out: the banner Keycloak shows while the bootstrap administrator signs in ("You
 * are logged in as a temporary admin user"), which belongs to the local container, not to the
 * console a deployment's administrator sees.
 */
export async function openKeycloakConsole(
  page: Page,
  realm: string,
  section: string,
): Promise<Locator[]> {
  const consolePath = `${new URL(KEYCLOAK_URL).pathname.replace(/\/$/, '')}/admin/master/console`;
  await page.goto(`${KEYCLOAK_URL}/admin/master/console/`);
  await page.locator('#username').fill(KEYCLOAK_ADMIN.username);
  await page.locator('#password').fill(KEYCLOAK_ADMIN.password);
  await page.locator('#kc-login').click();
  await page.waitForURL((address) => address.pathname.startsWith(consolePath));
  await page.goto(`${KEYCLOAK_URL}/admin/master/console/#/${realm}/${section}`);
  await expect(page.locator('table tbody tr').first()).toBeVisible();
  // PatternFly's banner: Keycloak 26's console shows it in gold for the temporary administrator.
  return [page.locator('.pf-v5-c-banner.pf-m-gold')];
}
