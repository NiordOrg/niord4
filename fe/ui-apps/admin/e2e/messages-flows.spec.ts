import { expect, Page, test } from '@playwright/test';

// End-to-end coverage for the most regression-sensitive message-search flows:
// 1. Mocked search response renders rows in the table view (golden path)
// 2. Filter URL contract: ?text= round-trips through reload + view navigation
// 3. Clicking a sortable column header updates the sortBy URL param
// 4. Toggling selection in table shows the row in the Selected view
//
// Run with `npx playwright test` while a dev server is up at
// http://localhost:4200 (npm start). Tests that need result rows mock the
// /messages/search endpoint via page.route so they do not depend on a real
// backend running locally.

interface SearchFixture {
  readonly id: string;
  readonly shortId: string;
  readonly title: string;
}

async function mockSearch(page: Page, fixtures: readonly SearchFixture[]): Promise<void> {
  await page.route('**/messages/search*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: fixtures.map((f) => ({
          id: f.id,
          shortId: f.shortId,
          mainType: 'NW',
          status: 'PUBLISHED',
          descs: [{ lang: 'en', title: f.title }],
        })),
        total: fixtures.length,
      }),
    }),
  );
}

test.describe('messages flows', () => {
  test('table view renders rows from a mocked search response', async ({ page }) => {
    await mockSearch(page, [
      { id: 'uuid-1', shortId: 'NW-001-25', title: 'First fixture message' },
      { id: 'uuid-2', shortId: 'NW-002-25', title: 'Second fixture message' },
    ]);

    await page.goto('/messages/table');

    await expect(page.getByText('NW-001-25').first()).toBeVisible();
    await expect(page.getByText('First fixture message').first()).toBeVisible();
    await expect(page.getByText('NW-002-25').first()).toBeVisible();
    await expect(page.getByText('Second fixture message').first()).toBeVisible();
  });

  test('filter URL contract: ?text= round-trips through reload and view navigation', async ({
    page,
  }) => {
    await page.goto('/messages/table?text=hello');
    await expect(page).toHaveURL(/[?&]text=hello\b/);

    await page.reload();
    await expect(page).toHaveURL(/[?&]text=hello\b/);

    await page.getByRole('link', { name: 'Grid' }).click();
    await expect(page).toHaveURL(/\/messages\/grid\?.*\btext=hello\b/);

    await page.getByRole('link', { name: 'Details' }).click();
    await expect(page).toHaveURL(/\/messages\/details\?.*\btext=hello\b/);
  });

  test('clicking a sortable table column header updates the sortBy URL param', async ({ page }) => {
    await mockSearch(page, [{ id: 'uuid-1', shortId: 'NW-001-25', title: 'Row for sort test' }]);

    await page.goto('/messages/table');
    await expect(page.getByText('NW-001-25').first()).toBeVisible();

    const idHeader = page
      .locator('th')
      .filter({ hasText: /^\s*ID\s*$/ })
      .first();
    await idHeader.click();
    await expect(page).toHaveURL(/[?&]sortBy=ID\b/);

    // Click again - sortBy stays ID, sortOrder flips
    await idHeader.click();
    await expect(page).toHaveURL(
      /[?&]sortBy=ID\b.*[?&]sortOrder=DESC\b|[?&]sortOrder=DESC\b.*[?&]sortBy=ID\b/,
    );
  });

  test('selecting a message in table view surfaces it in the Selected view', async ({ page }) => {
    await mockSearch(page, [{ id: 'uuid-99', shortId: 'NW-099-25', title: 'Selectable row' }]);

    await page.goto('/messages/table');
    await expect(page.getByText('NW-099-25').first()).toBeVisible();

    // Click the selection toggle inside the first (only) row. The visible
    // control is the star label wrapping a visually-hidden native checkbox;
    // clicking the label toggles the checkbox via native label association.
    const selectionToggle = page.locator('app-message-selection-toggle label').first();
    await selectionToggle.click();

    // Navigate to the Selection tab; the message should be present there.
    await page.getByRole('link', { name: 'Selection' }).click();
    await expect(page).toHaveURL(/\/messages\/selected\b/);
    await expect(page.getByText('NW-099-25').first()).toBeVisible();
    await expect(page.getByText('Selectable row').first()).toBeVisible();
  });
});
