import { expect, test } from './auth/fixtures';

// Publications section e2e: the series list and one series' detail against the
// real API.
//
// Everything here is located structurally — component selectors, roles, and the
// URL — because almost every visible string on these screens is backend data.
// The series NAME especially: it comes from the imported legacy template, and
// the import that produced the current archive wrote no names at all for a
// while. A spec that pinned one would have gone green throughout that.
//
// The one literal is the series id 'weekly-ntm', which the importer authors
// deterministically from the legacy template and which niord-core asserts on
// directly. It is a key, not a label.

const SERIES_ID = 'weekly-ntm';

// Both screens paint only after their fetch lands, and the detail's is the whole
// archive — 498 issues, a few hundred KB, against the shared test backend. The
// default 5s assertion budget is enough when a worker has the machine to itself
// and is not when four run at once, which is how this suite runs. The wait is on
// data arriving, not on anything the app does slowly.
const UNTIL_LOADED = { timeout: 15_000 };

// A series card — the tile in the cards grid.
//
// By its own test id rather than by structure. `.admin-card` alone also matches
// the page's three table wrappers, and the element carrying it has been a
// button and then a div while the class stayed put — so every structural form
// of this locator has gone stale at least once, silently, taking two tests with
// it each time.
const SERIES_CARD = 'app-publications-page [data-testid="series-card"]';

// The card's TITLE: the link that opens the series.
//
// The card is a container, not a control — the timeline strip inside it carries
// its own link per period, and that one navigates WITH an `?issue=` query. So
// clicking the card's centre would open the series for the wrong reason and the
// test would pass on it.
const SERIES_CARD_LINK = `${SERIES_CARD} a[appRowLinkAnchor]`;

// The desk the section is read from, seeded before the app boots.
//
// The dashboard lists the series the CURRENT desk is accountable for, and a
// fresh session starts on the first domain in the catalogue — which owns none
// of them, so the page renders "No publication series yet" and every card
// locator matches nothing whatever it is written as. `weekly-ntm`, the series
// the rest of this file deep-links to, is the NM desk's.
const OWNER_DOMAIN = 'niord-nm';

async function onTheOwningDesk(page: import('@playwright/test').Page): Promise<void> {
  await page.addInitScript((domain) => {
    localStorage.setItem('niord.sessionDomainId', domain);
  }, OWNER_DOMAIN);
}

test.describe('admin publications section', () => {
  // Sequential, unlike the rest of the suite. Each of these pulls the whole
  // 498-issue archive from the one backend the suite shares, and they have only
  // ever been run against it one at a time.
  test.describe.configure({ mode: 'serial' });

  test('the section renders the series list under the shell', async ({ authenticatedPage }) => {
    await onTheOwningDesk(authenticatedPage);
    await authenticatedPage.goto('/admin/publications');
    await expect(authenticatedPage).toHaveURL(new RegExp('/admin/publications(?:[?#]|$)'));
    await expect(authenticatedPage.locator('app-publications-page')).toBeVisible();
    // The groups render as cards, not as one table — a series is a card and the
    // retired ones are a collapsed disclosure below them. Located by the card's
    // own test id rather than by accessible name, because a card's name is the
    // series name, which is backend data.
    await expect(authenticatedPage.locator(SERIES_CARD).first()).toBeVisible(UNTIL_LOADED);
  });

  /**
   * CLICKING a series opens it.
   *
   * Every other test here reaches the detail with `goto`, and that is exactly how
   * the section shipped with selection broken: the list and the detail are sibling
   * routes, so the relative `['..', id]` resolved to `/admin/<id>`, matched nothing
   * and redirected to the app root. Deep links worked throughout, so the suite was
   * green while the only route a user actually takes was dead.
   *
   * The assertion is on the URL rather than on the detail rendering, because a
   * redirect to the root is precisely what it must not do.
   */
  test('clicking a series in the list opens it', async ({ authenticatedPage }) => {
    await onTheOwningDesk(authenticatedPage);
    await authenticatedPage.goto('/admin/publications');

    const title = authenticatedPage.locator(SERIES_CARD_LINK).first();
    await expect(title).toBeVisible(UNTIL_LOADED);
    // The address BEFORE the click, which guards the sibling-route bug directly
    // rather than through the redirect it caused. No query: the title opens the
    // series itself, where the strip's own cells open one of its periods.
    await expect(title).toHaveAttribute('href', /^\/admin\/publications\/[^?#]+$/);
    await title.click();

    await expect(authenticatedPage).toHaveURL(new RegExp('/admin/publications/.+'));
    await expect(authenticatedPage.locator('app-series-detail')).toBeVisible(UNTIL_LOADED);
  });

  test('a series deep-links straight to its detail', async ({ authenticatedPage }) => {
    await onTheOwningDesk(authenticatedPage);
    await authenticatedPage.goto(`/admin/publications/${SERIES_ID}`);

    const detail = authenticatedPage.locator('app-series-detail');
    await expect(detail).toBeVisible();
    // The id, read off an ADDRESS rather than off the page's text.
    //
    // It used to be rendered as bare text because the import wrote no names for
    // a while and the screen fell back to the key; now that the names resolve,
    // nothing prints the id and only the rows' own hrefs still carry it — which
    // is the more durable proof anyway, because it says the archive on screen
    // belongs to the series the URL named.
    await expect(
      detail.locator(`a[href^="/admin/publications/${SERIES_ID}?issue="]`).first(),
    ).toBeVisible(UNTIL_LOADED);
    await expect(detail.locator('table')).toBeVisible();
  });

  /**
   * The archive pages rather than rendering whole.
   *
   * weekly-ntm carries 498 issues. Before the pager the table painted all of
   * them, which is not a list anybody reads to the end of.
   */
  test('a long archive is paged, and the pager advances it', async ({ authenticatedPage }) => {
    await onTheOwningDesk(authenticatedPage);
    await authenticatedPage.goto(`/admin/publications/${SERIES_ID}`);

    const detail = authenticatedPage.locator('app-series-detail');
    const rows = detail.locator('tbody tr');
    await expect(rows.first()).toBeVisible(UNTIL_LOADED);

    const firstPageCount = await rows.count();
    expect(firstPageCount).toBeLessThan(100); // a page, not the archive
    const firstRowText = await rows.first().innerText();

    const pager = detail.locator('app-pager');
    await expect(pager).toBeVisible();

    await pager.getByRole('button', { name: 'Next page', exact: true }).click();
    await expect(rows.first()).not.toHaveText(firstRowText);
  });

  test('the back link returns to the series list', async ({ authenticatedPage }) => {
    await onTheOwningDesk(authenticatedPage);
    await authenticatedPage.goto(`/admin/publications/${SERIES_ID}`);
    // The back control is a button, and it is the first one on the page — the
    // detail (and its pager) render after it. Not located by name: 'All series'
    // is app text the runtime Dictionary can override.
    await authenticatedPage.locator('app-publications-page').getByRole('button').first().click();
    await expect(authenticatedPage).toHaveURL(new RegExp('/admin/publications(?:[?#]|$)'));
  });
});
