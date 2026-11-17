import { expect, test, type Page } from './auth/fixtures';

// The sysadmin SECOM section (/sysadmin/secom) operates the S-124 exchange:
// node status + certificates, which message series are exposed, the generated
// datasets, the subscribers, and the delivery outbox.
//
// TWO independent switches decide what this page shows, and the suite makes no
// env assumptions about either:
//
//   • `secomEnabled` (computed by the backend from `niord.secom.enabled` and
//     served in `/rest/settings/web`; no setting row decides it) gates the RAIL
//     LINK and the OVERVIEW CARD only. The route is registered unconditionally,
//     so a deep link resolves regardless.
//   • `niord.secom.enabled` (deployment config) decides whether the backend
//     serves `/rest/secom/**` at all. Without it every endpoint 404s and the
//     page renders an explanatory note INSTEAD of the tabs.
//
// So a deep link lands on exactly one of two valid states — the tab set, or the
// note — and the golden-path block self-skips when the tabs are not the one.
//
// Locators are route- and role-based on purpose: the section label is a
// Dictionary-overridable backend string, so `a[href="/sysadmin/secom"]` and
// `app-secom-page` are the stable probes (see coding-standards.md → e2e locators).

const SECOM_URL = /\/sysadmin\/secom(?:[?#]|$)/;
const RAIL_LINK = 'a[href="/sysadmin/secom"]';

/** The section's tab strip — present only when the backend serves /rest/secom. */
function tabs(page: Page) {
  return page.locator('app-secom-page [role="tab"]');
}

/** The "subsystem is disabled on this node" note that replaces the tabs. */
function disabledNote(page: Page) {
  return page.locator('app-secom-page [role="alert"]');
}

/** Wait for the page to settle into one of its two valid states. */
async function waitForSection(page: Page): Promise<void> {
  await expect(tabs(page).first().or(disabledNote(page))).toBeVisible({ timeout: 15000 });
}

/** True when the backend actually serves the SECOM API (tabs rendered, not the note). */
async function subsystemDeployed(page: Page): Promise<boolean> {
  await page.goto('/sysadmin/secom');
  await waitForSection(page);
  return (await tabs(page).count()) > 0;
}

test.describe('sysadmin SECOM — visibility gate (runtime setting)', () => {
  test('deep link always resolves (the visibility gate is menu-only)', async ({ sysadminPage }) => {
    // The route is registered unconditionally; only the rail/card honour the
    // setting. So the page must load whatever the rail state is.
    await sysadminPage.goto('/sysadmin/secom');
    await expect(sysadminPage).toHaveURL(SECOM_URL);
    await expect(sysadminPage.locator('app-secom-page')).toBeVisible();
    await waitForSection(sysadminPage);
  });

  test('the rail link and the overview card agree (both present or both absent)', async ({
    sysadminPage,
  }) => {
    await sysadminPage.goto('/sysadmin/overview');
    const railCount = await sysadminPage.getByRole('navigation').locator(RAIL_LINK).count();
    const cardCount = await sysadminPage
      .locator('app-admin-overview-page')
      .locator(RAIL_LINK)
      .count();
    // The shell-provided gate service drives both surfaces, so they can never
    // disagree: a gated-off section shows neither, a gated-on section shows both.
    expect(railCount).toBe(cardCount);
  });

  test('a node without the SECOM API explains itself instead of rendering broken panels', async ({
    sysadminPage,
  }) => {
    await sysadminPage.goto('/sysadmin/secom');
    await waitForSection(sysadminPage);
    test.skip(
      (await tabs(sysadminPage).count()) > 0,
      'The SECOM subsystem IS deployed on this backend — nothing to assert here.',
    );
    // Exactly one of the two states, never both.
    await expect(disabledNote(sysadminPage)).toBeVisible();
    await expect(tabs(sysadminPage)).toHaveCount(0);
  });
});

// Golden path. Each test probes the live backend and self-skips when the
// subsystem is not deployed, so the block activates where SECOM runs and lies
// dormant everywhere else — no env assumptions.
test.describe('sysadmin SECOM — golden path (subsystem-on backends)', () => {
  test('renders the five tabs with Overview selected', async ({ sysadminPage }) => {
    test.skip(!(await subsystemDeployed(sysadminPage)), 'SECOM is not deployed on this backend.');
    await expect(tabs(sysadminPage)).toHaveCount(5);
    // Roving tabindex + aria-selected come from @angular/aria; exactly one is on.
    await expect(
      sysadminPage.locator('app-secom-page [role="tab"][aria-selected="true"]'),
    ).toHaveCount(1);
    await expect(disabledNote(sysadminPage)).toHaveCount(0);
  });

  test('each tab activates and reveals its own panel', async ({ sysadminPage }) => {
    test.skip(!(await subsystemDeployed(sysadminPage)), 'SECOM is not deployed on this backend.');
    const tabList = tabs(sysadminPage);
    const count = await tabList.count();
    for (let i = 0; i < count; i++) {
      const tab = tabList.nth(i);
      await tab.click();
      await expect(tab).toHaveAttribute('aria-selected', 'true');
      // The panel this tab controls stops being inert — and `.tabs-panel[inert]`
      // is display:none, so "not inert" and "visible" are the same assertion.
      const panelId = await tab.getAttribute('aria-controls');
      expect(panelId).toBeTruthy();
      await expect(sysadminPage.locator(`[id="${panelId}"]`)).toBeVisible();
    }
  });

  test('the Certificates tab shows the service certificate and its two actions', async ({
    sysadminPage,
  }) => {
    test.skip(!(await subsystemDeployed(sysadminPage)), 'SECOM is not deployed on this backend.');
    await tabs(sysadminPage).nth(1).click();
    const panel = sysadminPage.locator('app-secom-certificates-tab');
    // The fingerprint is rendered monospace — a cheap probe that the status
    // payload actually landed rather than the tab being an empty shell.
    await expect(panel.locator('.font-mono').first()).toBeVisible({ timeout: 15000 });
    // Reload + Renew Now are unconditional; the MIR Portal affordance is a LINK
    // (and only when the secomMirPortalUrl setting is set), so it never counts here.
    expect(await panel.getByRole('button').count()).toBeGreaterThanOrEqual(2);
  });

  test('the Outbox status filter refetches server-side with the status param', async ({
    sysadminPage,
  }) => {
    test.skip(!(await subsystemDeployed(sysadminPage)), 'SECOM is not deployed on this backend.');
    await tabs(sysadminPage).nth(4).click();
    const filter = sysadminPage.locator('app-secom-outbox-tab .seg .seg-btn');
    // "All" + one button per delivery status.
    await expect(filter).toHaveCount(7);

    // Picking a status re-issues the GET with ?status= rather than filtering the
    // loaded page — the filter is part of the request key.
    const requestPromise = sysadminPage.waitForRequest(
      (req) => req.method() === 'GET' && /\/secom\/outbox\?.*status=/.test(req.url()),
    );
    await filter.nth(1).click();
    const request = await requestPromise;
    expect(request.url()).toContain('status=PENDING');
  });

  test('the Datasets tab lists the exposure table and the datasets (or their empty states)', async ({
    sysadminPage,
  }) => {
    test.skip(!(await subsystemDeployed(sysadminPage)), 'SECOM is not deployed on this backend.');
    await tabs(sysadminPage).nth(2).click();
    const panel = sysadminPage.locator('app-secom-datasets-tab');
    // Reconcile Now is unconditional.
    await expect(panel.getByRole('button').first()).toBeVisible();

    // The tab holds two INDEPENDENT regions — S-124 series exposure, then the
    // dataset instances — and each settles to a table or an empty state on its
    // own. Assert them separately and scoped: a backend with series but no
    // datasets (the common case) shows a table AND an empty state at the same
    // time, so an unscoped `table.or(emptyState)` matches both and trips strict
    // mode. `.or(...).first()` picks one match; `.first().or(.first())` is a
    // union of two and does not.
    const sections = panel.locator('section');
    await expect(sections).toHaveCount(2);
    for (const section of await sections.all()) {
      await expect(
        section.locator('table.niord-table').or(section.locator('app-empty-state')).first(),
      ).toBeVisible({ timeout: 15000 });
    }
  });
});
