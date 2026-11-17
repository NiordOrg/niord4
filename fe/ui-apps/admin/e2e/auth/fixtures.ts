import { test as base, expect, type Page } from '@playwright/test';

import { fetchTestTokenBundle, type TestRole, type TestTokenBundle } from './keycloak-login';
import { seedAuthenticatedSession } from './inject-auth';

interface AuthFixtures {
  /** Authenticated with the base test user (role-less — the original drop-in). */
  readonly authenticatedPage: Page;
  /** Authenticated as the sysadmin role-scoped user (falls back to the base pair). */
  readonly sysadminPage: Page;
  /** Authenticated as the admin role-scoped user (falls back to the base pair). */
  readonly adminPage: Page;
  /** Authenticated as the editor role-scoped user (falls back to the base pair). */
  readonly editorPage: Page;
  /** Authenticated as the plain-user role-scoped user (falls back to the base pair). */
  readonly userPage: Page;
  /** Fails the test when the browser reports a Content-Security-Policy violation. */
  readonly cspGuard: void;
}

// One password-grant per Playwright worker process PER ROLE; reused across tests
// in the worker. Reset on worker restart so a long suite never outlives a token.
const cachedBundles = new Map<TestRole | 'base', Promise<TestTokenBundle>>();

function bundleFor(role?: TestRole): Promise<TestTokenBundle> {
  const key = role ?? 'base';
  let pending = cachedBundles.get(key);
  if (!pending) {
    pending = fetchTestTokenBundle(role);
    cachedBundles.set(key, pending);
  }
  return pending;
}

async function authenticate(
  page: Page,
  role: TestRole | undefined,
  use: (p: Page) => Promise<void>,
) {
  const bundle = await bundleFor(role);
  await seedAuthenticatedSession(page, bundle);
  await use(page);
}

// Chromium logs every refusal of the policy to the console, also for a frame whose scripts are off
// (the sandboxed mail and report previews), where no listener of ours can run; the init script's
// listener adds the directive and the refused address for every frame that does run scripts.
const CSP_MESSAGE = /Content[- ]Security[- ]Policy|\[csp-violation\]/i;
const cspViolations = new WeakMap<Page, string[]>();

/** What the guard has collected on a page so far; the list is live. */
export function cspViolationsOf(page: Page): readonly string[] {
  return cspViolations.get(page) ?? [];
}

/** Forgets what the guard collected, for a test that provokes a violation on purpose. */
export function clearCspViolations(page: Page): void {
  cspViolations.get(page)?.splice(0);
}

export const test = base.extend<AuthFixtures>({
  authenticatedPage: async ({ page }, use) => authenticate(page, undefined, use),
  sysadminPage: async ({ page }, use) => authenticate(page, 'sysadmin', use),
  adminPage: async ({ page }, use) => authenticate(page, 'admin', use),
  editorPage: async ({ page }, use) => authenticate(page, 'editor', use),
  userPage: async ({ page }, use) => authenticate(page, 'user', use),
  // Every test built on the fixtures' `test` watches for violations (a spec that imports `test`
  // from @playwright/test does not). A server that sends no policy (the dev server) produces
  // none, so the guard only bites where a policy is served.
  cspGuard: [
    async ({ page }, use) => {
      const seen: string[] = [];
      cspViolations.set(page, seen);
      page.on('console', (message) => {
        if (CSP_MESSAGE.test(message.text())) seen.push(message.text());
      });
      await page.addInitScript(() => {
        document.addEventListener('securitypolicyviolation', (event) => {
          console.error(`[csp-violation] ${event.violatedDirective} ${event.blockedURI}`);
        });
      });
      await use();
      expect(seen, 'Content-Security-Policy violations logged by the browser').toEqual([]);
    },
    { auto: true },
  ],
});

export { expect, type Page };
