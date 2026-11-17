import type { Page } from '@playwright/test';

import { seedAuthenticatedSession } from '../../ui-apps/admin/e2e/auth/inject-auth';
import {
  fetchTestTokenBundle,
  type TestRole,
  type TestTokenBundle,
} from '../../ui-apps/admin/e2e/auth/keycloak-login';
import { DOMAIN } from './env';

export type { TestRole };

// The end-to-end suite's password grant, one bundle per role per worker. The local realm's tokens
// live for hours, far longer than a run.
const bundles = new Map<TestRole, Promise<TestTokenBundle>>();

/** The role's token bundle, fetched once per worker; a failed fetch is tried again next time. */
export function tokenFor(role: TestRole): Promise<TestTokenBundle> {
  let pending = bundles.get(role);
  if (!pending) {
    pending = fetchTestTokenBundle(role);
    bundles.set(role, pending);
    pending.catch(() => bundles.delete(role));
  }
  return pending;
}

// The key the administration app keeps its session domain under in local storage.
const SESSION_DOMAIN_KEY = 'niord.sessionDomainId';

/**
 * Signs the page in as a role of the local realm, the way the end-to-end suite does: the token
 * bundle is seeded before the application starts, so no login page is shown. The page opens in
 * `domain` (the rig's domain by default) unless it was switched since. Called before the first
 * navigation.
 *
 * Playwright runs an init script in every frame. The session domain is the application's, read in
 * the top frame only, so this script does nothing in a frame; in a sandboxed frame (a mail or a
 * report's result in `app-sandboxed-html`) the storage would refuse it. The end-to-end seeding
 * (`seedAuthenticatedSession`) reads the storage in every frame and fails in a sandboxed one; the
 * guard leaves out exactly that error (guard.ts, `isSandboxedInitScriptError`).
 */
export async function loginAs(page: Page, role: TestRole, domain: string = DOMAIN): Promise<void> {
  await seedAuthenticatedSession(page, await tokenFor(role));
  await page.addInitScript(
    ({ key, domainId }) => {
      if (window !== window.top || window.origin === 'null') return;
      try {
        if (!localStorage.getItem(key)) localStorage.setItem(key, domainId);
      } catch {
        // Storage refused: the application opens in its first domain.
      }
    },
    { key: SESSION_DOMAIN_KEY, domainId: domain },
  );
}
