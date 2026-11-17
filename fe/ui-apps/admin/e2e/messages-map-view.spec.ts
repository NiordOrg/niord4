import { expect, test } from '@playwright/test';

// Smoke test for the message map view.
// Run with `npx playwright test` while a dev server is up at http://localhost:4200.

test.describe('messages map view', () => {
  test('renders the OL map container, result list panel, and layer switcher', async ({ page }) => {
    await page.goto('/messages/map');
    await expect(page.locator('app-ol-map')).toBeVisible();
    await expect(page.locator('app-map-result-list')).toBeVisible();
    // The `.ol-layer-switcher` class sits on the <app-ol-layer-switcher> host
    // itself (see OlLayerSwitcher's host metadata), so the selector must be
    // joined — `app-ol-layer-switcher .ol-layer-switcher` would look for a
    // descendant and never match.
    await expect(page.locator('app-ol-layer-switcher.ol-layer-switcher')).toBeVisible();
  });

  test('layer switcher lists the expected reference layers (no AtoN layer)', async ({ page }) => {
    await page.goto('/messages/map');
    const switcher = page.locator('.ol-layer-switcher');
    await expect(switcher).toBeVisible();
    // The OSM layer's display name resolves through transloco to "OpenStreetMap"
    // (en) — assert on the user-facing label, not the internal source key.
    await expect(switcher).toContainText('OpenStreetMap');
    // No OpenSeaMap assertion: whether that layer is offered follows the
    // backend's runtime `openSeaMapLayerEnabled` web setting (DB-editable),
    // so its presence is deployment state, not an app contract.
    await expect(switcher).not.toContainText('AtoN');
  });

  test('result list panel collapse / expand toggle', async ({ page }) => {
    await page.goto('/messages/map');
    const panel = page.locator('app-map-result-list');
    const toggle = panel.locator('header button');
    await expect(toggle).toBeVisible();
    // The toggle's accessible name reflects the panel state (it invites the
    // opposite action) and the result body mounts only when expanded. Default
    // is collapsed: the toggle is labelled "Expand" and the body is absent. A
    // click expands it (label -> "Collapse", body present); a second click
    // collapses it again.
    const body = panel.locator('app-message-list-status');
    await expect(toggle).toHaveAccessibleName('Expand');
    await expect(body).toHaveCount(0);
    await toggle.click();
    await expect(toggle).toHaveAccessibleName('Collapse');
    await expect(body).toHaveCount(1);
    await toggle.click();
    await expect(toggle).toHaveAccessibleName('Expand');
    await expect(body).toHaveCount(0);
  });

  test('flipping table → map → table preserves the user inside /messages/*', async ({ page }) => {
    await page.goto('/messages/table');
    await page.getByRole('link', { name: 'Map' }).click();
    await expect(page).toHaveURL(/\/messages\/map(?:[?#]|$)/);
    await expect(page.locator('app-ol-map')).toBeVisible();
    await page.getByRole('link', { name: 'Table' }).click();
    await expect(page).toHaveURL(/\/messages\/table/);
  });
});
