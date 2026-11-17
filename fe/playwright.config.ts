import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './ui-apps/admin/e2e',
  fullyParallel: true,
  reporter: 'list',
  use: {
    // E2E_BASE_URL points the suite at another dev server, e.g. one of several running side by side.
    baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:4200',
    // The same off-centre browser clock the unit suite runs under (UTC-3:30, with DST):
    // a screen that renders the browser's zone instead of the domain's shows it here.
    timezoneId: 'America/St_Johns',
  },
});
