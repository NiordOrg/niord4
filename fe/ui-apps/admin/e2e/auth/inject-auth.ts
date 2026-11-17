import type { Page } from '@playwright/test';

import type { TestTokenBundle } from './keycloak-login';

// Must match TEST_AUTH_WINDOW_KEY in src/app/core/auth/services/test-keycloak.ts.
// The KEYCLOAK provider reads this window key in non-prod builds and, if set,
// returns a stub Keycloak pre-hydrated from the bundle — skipping the SSO flow.
export const TEST_AUTH_WINDOW_KEY = '__NIORD_TEST_AUTH__';

export async function seedAuthenticatedSession(page: Page, bundle: TestTokenBundle): Promise<void> {
  await page.addInitScript(
    ({ key, value }) => {
      (window as unknown as Record<string, unknown>)[key] = value;
      // Authenticated flow locators use English regardless of the deployment's default.
      if (!localStorage.getItem('niord.language')) localStorage.setItem('niord.language', 'en');
    },
    { key: TEST_AUTH_WINDOW_KEY, value: bundle },
  );
}
