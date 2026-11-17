import { expect, Page, test } from '@playwright/test';

// A message row leads to an address — the details dialog's `?messageId=` on the
// current search — so the title is a real link. This covers the half a unit test
// cannot reach: the BROWSER acting on that link, opening a second tab from a
// middle click without the app navigating the tab the row is in.
//
// Run with `npx playwright test e2e/messages-row-links.spec.ts` while a dev
// server is up at http://localhost:4200 (npm start). The search endpoint is
// mocked, so no backend is needed.

async function mockSearch(page: Page): Promise<void> {
  await page.route('**/messages/search*', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        data: [
          {
            id: 'uuid-link-1',
            shortId: 'NW-101-25',
            mainType: 'NW',
            status: 'PUBLISHED',
            descs: [{ lang: 'en', title: 'Row that opens in a new tab' }],
          },
        ],
        total: 1,
      }),
    }),
  );
}

test.describe('message row links', () => {
  test('middle-clicking a row title opens the message in a new tab', async ({ page, context }) => {
    await mockSearch(page);
    await page.goto('/messages/table');

    const title = page.getByRole('link', { name: 'Row that opens in a new tab' });
    await expect(title).toBeVisible();
    // The href is what the browser acts on; the row's own click handler never
    // sees a middle click.
    await expect(title).toHaveAttribute('href', /messageId=uuid-link-1/);

    const opened = context.waitForEvent('page');
    await title.click({ button: 'middle' });
    const newTab = await opened;
    // A background tab starts at about:blank and resolves the href after, so the
    // address has to be waited for rather than read.
    await newTab.waitForURL(/messageId=uuid-link-1/);

    // The tab the row lives in stayed where it was.
    expect(page.url()).not.toContain('messageId=');

    await newTab.close();
  });
});
