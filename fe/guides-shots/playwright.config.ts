import { defineConfig } from '@playwright/test';

import { ADMIN_URL, KEYCLOAK_URL } from './support/env';

/*
 * The screenshot project of the user guides: its own configuration, so the end-to-end run
 * (`npx playwright test`, which reads fe/playwright.config.ts and ui-apps/admin/e2e/ only) never
 * picks it up. It is run by hand against the rig's local stack, one picture after another:
 *
 *   npx playwright test -c guides-shots/playwright.config.ts
 */

// The slice of Node's `process` the configuration reads and writes.
declare const process: { env: Record<string, string | undefined> };

if (process.env['CI']) {
  throw new Error('The guides rig photographs a local stack: it never runs in CI.');
}

// The rig signs in to the local realm only, as its users, each with its own name as password (a
// loopback-only development realm). A KEYCLOAK_* value the shell carries for the end-to-end suite,
// naming another realm or other users, is replaced, so the rig never signs in anywhere else.
process.env['KEYCLOAK_URL'] = KEYCLOAK_URL;
process.env['KEYCLOAK_REALM'] = 'niord';
process.env['KEYCLOAK_TEST_CLIENT_ID'] = 'niord-web';
for (const [suffix, user] of [
  ['', 'sysadmin'],
  ['_SYSADMIN', 'sysadmin'],
  ['_ADMIN', 'admin'],
  ['_EDITOR', 'editor'],
  ['_USER', 'user'],
] as const) {
  process.env[`KEYCLOAK_TEST_USERNAME${suffix}`] = user;
  process.env[`KEYCLOAK_TEST_PASSWORD${suffix}`] = user;
}

export default defineConfig({
  testDir: '.',
  testMatch: '**/*.shots.ts',
  outputDir: 'test-results',
  // One picture at a time: the stack is shared by every test, and a picture taken while another
  // test loads the backend can catch a page half-way.
  fullyParallel: false,
  workers: 1,
  // A picture that needed a second try is a picture that can differ between runs: fix the wait.
  retries: 0,
  forbidOnly: true,
  reporter: 'list',
  timeout: 180_000,
  expect: { timeout: 20_000 },
  use: {
    browserName: 'chromium',
    baseURL: ADMIN_URL,
    // 1280 x 800, a common laptop window. At 1024 CSS px (the administration app's `lg` breakpoint,
    // the narrowest that shows its desktop header) the tables wrap every area name onto two lines
    // and Keycloak's console folds its navigation away, hiding the realm; at 1280 neither happens.
    // Every picture is printed at the same share of its CSS width (shot.ts, PRINT_SCALE), so the
    // whole window prints 800 px wide, the widest a manual's column takes, and a menu or a panel
    // prints smaller with its text at the same size as in every other figure.
    viewport: { width: 1280, height: 800 },
    // One image pixel per CSS pixel. A picture is printed at PRINT_SCALE (0.625) of its CSS width,
    // so it already holds 1.6 pixels for each one it is shown at: sharp on a high-density screen
    // and in a manual's PDF. Twice the pixels made the pictures three to four times the bytes
    // (a map 2.3 MB), and every retake adds its bytes to the repository's history for good.
    deviceScaleFactor: 1,
    locale: 'en-GB',
    // The browser itself in English too: it formats an <input type="number"> in its own language,
    // which otherwise follows the workstation's regional format (54,5 for 54.5 on a Danish-set
    // Windows), whatever the page's locale is.
    launchOptions: { args: ['--lang=en-GB'] },
    timezoneId: 'Europe/Copenhagen',
    colorScheme: 'light',
    reducedMotion: 'reduce',
    actionTimeout: 20_000,
    navigationTimeout: 60_000,
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium' }],
});
