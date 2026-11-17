import { expect, Page, test } from '@playwright/test';

// E2E coverage for the Messages refactor: the anchored filter
// pickers (tag + area/category tree), their toggle-close + stay-in-viewport
// behaviour, the multi-item popup Action menu, and the details right-drawer
// URL contract. Run with `npx playwright test` while a dev server is up at
// http://localhost:4200 (npm start). Anonymous — the filter pickers and details
// drawer are reachable without login; the row test mocks /messages/search so it
// does not need a real backend.

interface SearchRow {
  readonly id: string;
  readonly shortId: string;
  readonly title: string;
}

async function mockSearch(page: Page, rows: readonly SearchRow[]): Promise<void> {
  await page.route('**/messages/search*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: rows.map((r) => ({
          id: r.id,
          shortId: r.shortId,
          mainType: 'NW',
          status: 'PUBLISHED',
          descs: [{ lang: 'en', title: r.title }],
        })),
        total: rows.length,
      }),
    }),
  );
}

/** Open the filter bar's "Add filter" menu and enable a criterion by its label. */
async function enableFilter(page: Page, label: string): Promise<void> {
  await page
    .getByRole('button', { name: /filter/i })
    .first()
    .click();
  await page
    .getByRole('menuitem', { name: new RegExp(`^\\s*${label}\\s*$`, 'i') })
    .first()
    .click();
  await page.keyboard.press('Escape');
}

/** True when the open popover sits fully inside the viewport. */
function popoverWithinViewport(page: Page): Promise<boolean> {
  return page.evaluate(() => {
    const el = document.querySelector('.picker-panel');
    if (!el) return false;
    const r = el.getBoundingClientRect();
    return (
      r.top >= -2 &&
      r.left >= -2 &&
      r.bottom <= window.innerHeight + 2 &&
      r.right <= window.innerWidth + 2
    );
  });
}

test.describe('messages pickers & details drawer', () => {
  test('tag filter picker opens inside the viewport and toggles closed on re-click', async ({
    page,
  }) => {
    await page.goto('/messages/table');
    await enableFilter(page, 'Tag');

    // The panel is now a chips + typeahead field; the picker lives behind its browse button.
    const trigger = page
      .locator('app-tag-filter-panel')
      .getByRole('button', { name: 'Browse all tags' });
    await trigger.click();

    const popover = page.locator('.picker-panel');
    await expect(popover).toBeVisible();
    expect(await popoverWithinViewport(page)).toBe(true);

    // Re-clicking the trigger closes it: the popover has no backdrop and its
    // outside-click handler ignores clicks inside the stored anchor, so the
    // trigger's own toggle drives the close.
    await trigger.click();
    await expect(popover).toHaveCount(0);
  });

  test('area tree picker opens inside the viewport and toggles closed on re-click', async ({
    page,
  }) => {
    await page.goto('/messages/table');
    await enableFilter(page, 'Area');

    // The panel is now a chips + typeahead field; the tree lives behind its browse button.
    const trigger = page
      .locator('app-area-filter-panel')
      .getByRole('button', { name: 'Browse area tree' });
    await trigger.click();

    const popover = page.locator('.picker-panel');
    await expect(popover).toBeVisible();
    // The CDK tree (filter + checkbox nodes) renders inside the popover.
    await expect(popover.getByRole('tree')).toHaveCount(1);
    expect(await popoverWithinViewport(page)).toBe(true);

    await trigger.click();
    await expect(popover).toHaveCount(0);
  });

  test('toolbar Action menu is a multi-item popup using the .menu-item row recipe', async ({
    page,
  }) => {
    await page.goto('/messages/table');

    // Located by component, not by label. The per-row cogs are named "Message actions" (which
    // substring matching would collide with), and the toolbar trigger's own name comes from the
    // `menu.action` dictionary key — a backend table an admin can edit, so it is not ours to pin.
    // The host renders exactly one button; its menus and dialogs portal out to the overlay.
    await page.locator('app-messages-action-menu').getByRole('button').first().click();
    const overlay = page.locator('.menu-panel');
    await expect(overlay).toBeVisible();
    // Layer-3 row recipe — at least one actionable item.
    expect(await overlay.locator('.menu-item').count()).toBeGreaterThan(0);
  });

  test('clicking a row opens the details drawer and Close clears the messageId param', async ({
    page,
  }) => {
    await mockSearch(page, [{ id: 'uuid-d1', shortId: 'NW-501-26', title: 'Drawer fixture' }]);
    await page.goto('/messages/table');
    await expect(page.getByText('NW-501-26').first()).toBeVisible();

    await page.locator('tr.row-interactive').first().click();
    await expect(page).toHaveURL(/[?&]messageId=uuid-d1\b/);
    const dialog = page.locator('[role="dialog"]');
    await expect(dialog).toBeVisible();

    await dialog.getByRole('button', { name: /close/i }).first().click();
    await expect(page).not.toHaveURL(/[?&]messageId=/);
    await expect(dialog).toHaveCount(0);
  });
});
