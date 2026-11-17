import { expect, test } from '@playwright/test';

// Smoke test for the messages feature shell. Run with `npx playwright test`
// while a dev server is up at http://localhost:4200 (npm start). Covers the
// shell wiring for the messages feature:
// - tab routerLinks must navigate inside /messages/*
// - filter panel must be visible on browse views and hidden on Selected
// - browser title must follow "Niord - Messages - <Submenu>" format

test.describe('messages shell', () => {
  test('/messages redirects to /messages/table', async ({ page }) => {
    await page.goto('/messages');
    await expect(page).toHaveURL(/\/messages\/table$/);
  });

  test('each view-mode tab navigates inside /messages/* and updates the title', async ({
    page,
  }) => {
    const tabs: readonly { readonly label: string; readonly slug: string }[] = [
      { label: 'Table', slug: 'table' },
      { label: 'Grid', slug: 'grid' },
      { label: 'Details', slug: 'details' },
      { label: 'Map', slug: 'map' },
      { label: 'Selection', slug: 'selected' },
    ];

    await page.goto('/messages/table');

    for (const tab of tabs) {
      await page.getByRole('link', { name: tab.label }).click();
      // The map view writes lat/lon/zoom into the URL after navigation, so the
      // slug may be followed by `?` rather than end-of-string. Allow either.
      await expect(page).toHaveURL(new RegExp(`/messages/${tab.slug}(?:[?#]|$)`));
      await expect(page).toHaveTitle(`Niord - Messages - ${tab.label}`);
    }
  });

  test('filter panel is mounted on browse views and absent on Selected', async ({ page }) => {
    // The host element is in the DOM whenever shell.showFilter() is true (i.e. on browse
    // views). Its inner content only renders when at least one filter is enabled, so
    // `toBeVisible()` would flap with default state — assert the host's presence instead.
    const filterPanel = page.locator('app-messages-filter-panel');

    await page.goto('/messages/table');
    await expect(filterPanel).toHaveCount(1);

    await page.goto('/messages/grid');
    await expect(filterPanel).toHaveCount(1);

    await page.goto('/messages/details');
    await expect(filterPanel).toHaveCount(1);

    await page.goto('/messages/map');
    await expect(filterPanel).toHaveCount(1);

    await page.goto('/messages/selected');
    await expect(filterPanel).toHaveCount(0);
  });
});
