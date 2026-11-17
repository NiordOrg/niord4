import { expect, test } from './auth/fixtures';

// The issue workbench, against the real API.
//
// Located structurally throughout. Almost every visible string here is either
// backend data (the issue name, the member short ids) or a translated check
// code, and pinning either would go green through exactly the kind of defect
// this section keeps producing — a screen that renders confidently from wrong
// or missing data.
const SERIES_ID = 'weekly-ntm';

// The yearly compilation of that series: its issues are drawn as one run of
// rows per week, each heading linking to the week it was taken from.
const COMPILED_SERIES_ID = 'accumulated-yearly-ntm';

// The workbench arrives in ONE request, but that request re-decides the issue's
// membership over the whole published corpus on a shared backend, so it still
// needs more than the default 5s assertion budget.
const UNTIL_LOADED = { timeout: 25_000 };

// A REAL issue's row, never a synthesized one.
//
// The archive table also renders pseudo-rows for periods that have no issue, and
// their only control is the retro-create button. `tbody tr button` therefore
// aimed at whichever came first, so opening the workbench could instead POST a
// new RECOVERED issue into the shared test backend — or, since the overlap guard
// landed, be refused 409 and fail the spec with an identifier.
const ISSUE_ROW = 'app-series-detail tbody tr:not(.issue-row-pseudo)';

// The control that OPENS the issue: the row's name, which is a real link.
//
// Located as the anchor it is, rather than as whatever control the row happens
// to carry: the name is the row's one tab stop and the only thing in it that
// navigates, so this locator does not drift with the action column.
const ISSUE_ROW_LINK = `${ISSUE_ROW} a[appRowLinkAnchor]`;

// The workbench's OWN header, and not the header band of every card inside it.
//
// The shared card draws a `<header>` of its own, so a descendant match resolves
// to four elements and fails on strictness rather than on the screen — which is
// what the old locator hid, because it never navigated in the first place.
const WORKBENCH_HEADER = 'app-issue-detail > header';

test.describe('the issue workbench', () => {
  // Sequential for the same reason the series suite is: these tests pull the
  // whole 498-issue archive and then an issue's workbench, the one request that
  // re-resolves its membership.
  test.describe.configure({ mode: 'serial' });

  // On the owning desk before the app boots. A publication is never shown
  // outside the domain that owns it: a fresh session starts on the first
  // domain in the catalogue, and from there the series detail is a refusal
  // offering the switch rather than the archive every test here deep-links into.
  test.beforeEach(async ({ authenticatedPage }) => {
    await authenticatedPage.addInitScript((domain) => {
      localStorage.setItem('niord.sessionDomainId', domain);
    }, 'niord-nm');
  });

  test('opens from a row in the issue list and deep-links', async ({ authenticatedPage }) => {
    await authenticatedPage.goto(`/admin/publications/${SERIES_ID}`);
    await expect(authenticatedPage.locator('app-series-detail table')).toBeVisible(UNTIL_LOADED);

    await authenticatedPage.locator(ISSUE_ROW_LINK).first().click();

    await expect(authenticatedPage.locator(WORKBENCH_HEADER)).toBeVisible(UNTIL_LOADED);
    // The id is in the URL, so the screen survives a reload and a share.
    await expect(authenticatedPage).toHaveURL(/[?&]issue=/);
  });

  test('shows the period, the release rail, the members and the history', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto(`/admin/publications/${SERIES_ID}`);
    await expect(authenticatedPage.locator('app-series-detail table')).toBeVisible(UNTIL_LOADED);
    await authenticatedPage.locator(ISSUE_ROW_LINK).first().click();

    const detail = authenticatedPage.locator('app-issue-detail');
    await expect(authenticatedPage.locator(WORKBENCH_HEADER)).toBeVisible(UNTIL_LOADED);

    // The four panels this test is about, each located by what it IS.
    //
    // Not by counting cards: how many the workbench paints depends on the
    // issue's own state — an open issue has a release rail and a published one
    // does not, and the edit panel appears only while somebody is editing — so a
    // count asserts the fixture rather than the screen, and went red as soon as
    // the screen grew a panel.
    await expect(detail.getByTestId('issue-facts')).toBeVisible();
    await expect(detail.getByTestId('issue-release')).toBeVisible();
    await expect(detail.getByTestId('issue-members')).toBeVisible();
    // The trail arrives folded — it is the longest thing in the rail — so the
    // panel is reached the way a reader reaches it.
    await detail.getByTestId('issue-history-toggle').click();
    await expect(detail.getByTestId('issue-history')).toBeVisible();

    // Every label renders as words rather than as its own key, which is what an
    // unregistered code used to look like on screen.
    await expect(detail).not.toContainText('admin.publications.check.');
    await expect(detail).not.toContainText('admin.publications.action.');
    await expect(detail).not.toContainText('admin.publications.issue.');
    await expect(detail).not.toContainText('admin.publications.reason.');
  });

  test('the back control returns to the issue list', async ({ authenticatedPage }) => {
    await authenticatedPage.goto(`/admin/publications/${SERIES_ID}`);
    await expect(authenticatedPage.locator('app-series-detail table')).toBeVisible(UNTIL_LOADED);
    await authenticatedPage.locator(ISSUE_ROW_LINK).first().click();
    await expect(authenticatedPage.locator(WORKBENCH_HEADER)).toBeVisible(UNTIL_LOADED);

    await authenticatedPage.locator('app-issue-detail button').first().click();

    await expect(authenticatedPage.locator('app-series-detail table')).toBeVisible(UNTIL_LOADED);
    await expect(authenticatedPage.locator('app-issue-detail')).toHaveCount(0);
  });

  // A compiled issue's week headings link to the source issues, which live on
  // ANOTHER series under the same route. The page is kept and only the params
  // change, so this is the one navigation in the section that a snapshot read
  // of the route silently ignored: the address moved and the screen did not.
  test('a week heading on a compiled issue opens that week on its own series', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto(`/admin/publications/${COMPILED_SERIES_ID}`);
    await expect(authenticatedPage.locator('app-series-detail table')).toBeVisible(UNTIL_LOADED);
    await authenticatedPage.locator(ISSUE_ROW_LINK).first().click();
    await expect(authenticatedPage.locator(WORKBENCH_HEADER)).toBeVisible(UNTIL_LOADED);

    const weekLink = authenticatedPage.locator('[data-testid="issue-source-group"] a').first();
    await expect(weekLink).toBeVisible(UNTIL_LOADED);
    const weekName = (await weekLink.textContent())?.trim() ?? '';
    expect(weekName).not.toBe('');

    await weekLink.click();

    await expect(authenticatedPage).toHaveURL(
      new RegExp(`/admin/publications/${SERIES_ID}\\?issue=`),
    );
    // The screen followed the address: the workbench now names the week that
    // was clicked, and the year it was reached from is gone.
    await expect(authenticatedPage.locator(WORKBENCH_HEADER)).toContainText(weekName, UNTIL_LOADED);
    await expect(authenticatedPage.locator('[data-testid="issue-source-group"]')).toHaveCount(0);
  });
});
