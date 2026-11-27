import type { Locator, Page } from '@playwright/test';

import {
  ANNEX,
  COMPILED,
  EXAMPLE_WEEKLY,
  IN_FORCE,
  NM,
  ONE_OFF_FILE,
  RELEASED_HERE,
  WEEKLY,
  cardOf,
  issueId,
  issueLoaded,
  issuePath,
  openSettings,
  openTab,
} from './support/admin-publications';
import { fixClock } from './support/clock';
import { expect, test } from './support/fixtures';
import type { GuardOptions } from './support/guard';
import { loginAs } from './support/login';
import { infoUrl } from './support/open';
import { shot } from './support/shot';

/*
 * The admin manual's pictures of publications and publication categories, written to
 * guides/admin-manual/images/ (chapters 05-Publications.adoc and 05a-PublicationCategories.adoc).
 *
 * One test per picture, titled `<manual>/<file>` after the file it writes. Every picture is taken
 * as an administrator on the copy's own series: the weekly NtM, the weekly in-force list and the
 * yearly compilation of the NM domain, and the one-offs of the NM Annex domain. Nothing is saved:
 * a dialog or a drawer is photographed filled in, and the test ends without confirming it.
 *
 *   npx playwright test -c guides-shots/playwright.config.ts admin-manual-publications.shots.ts
 */

/** Signs in as an administrator of a domain, with the page's clock fixed, and opens a path. */
async function openAs(page: Page, domain: string, path: string): Promise<void> {
  await fixClock(page);
  await loginAs(page, 'admin', domain);
  await page.goto(path);
}

/** The open issue of the in-force series, which has members, late messages and left-out ones. */
async function openInForceIssue(page: Page): Promise<void> {
  await openAs(page, NM, issuePath(IN_FORCE, issueId(IN_FORCE, 'OPEN')));
  await issueLoaded(page);
}

/** The message the seed added by hand to the open compilation (data/seed-examples.mjs). */
const HAND_ADDED = 'NM-1050-25';

// The external-link one-off is named after the web address it points at: data, not a key.
const LINK_ONE_OFF = 'www.danskehavnelods.dk';

/** A drawer or a dialog, photographed edge to edge. */
function panelShot(page: Page, panel: Locator, guard?: GuardOptions): Promise<string> {
  return shot(page, { target: panel, pad: 0, guard });
}

test.describe('admin-manual', () => {
  // ------------------------------------------------------------ the Publications page

  test.describe('in a window as tall as the page', () => {
    // The page's three groups: the series cards, the one-offs and, last, the folded Retired line.
    test.use({ viewport: { width: 1280, height: 1150 } });

    test('admin-manual/PublicationsOverview.png', async ({ page }) => {
      await openAs(page, NM, '/admin/publications');
      await expect(page.getByTestId('series-card').first()).toBeVisible();
      const retired = page.getByRole('button', { name: /^Retired/ });
      await expect(retired).toBeInViewport({ ratio: 1 });
      await shot(page);
    });
  });

  test('admin-manual/PublicationSeriesCard.png', async ({ page }) => {
    await openAs(page, NM, '/admin/publications');
    const card = page
      .getByTestId('series-card')
      .filter({ has: page.getByRole('link', { name: 'Weekly NtM', exact: true }) });
    await expect(card).toBeVisible();
    await shot(page, { target: card });
  });

  test.describe('with room below the table', () => {
    // At 800 px the row menu has no room below the table and opens upwards over the rows above;
    // in a taller window it drops below its own cog.
    test.use({ viewport: { width: 1280, height: 1080 } });

    test('admin-manual/PublicationOneOffTable.png', async ({ page }) => {
      await openAs(page, ANNEX, '/admin/publications');
      const table = page.locator('table.niord-table').first();
      await expect(table.getByRole('row').nth(1)).toBeVisible();
      const trigger = page.getByRole('button', {
        name: 'Actions for Navigation through Danish Waters',
      });
      await trigger.click();
      const menu = page.getByRole('menu');
      await expect(menu).toBeVisible();
      // The pointer is moved off the rows, so no other row's cog is lit as if it were pointed at.
      await page.mouse.move(4, 4);
      const [cog, list] = await Promise.all([trigger.boundingBox(), menu.boundingBox()]);
      expect(list && cog && list.y >= cog.y + cog.height - 1).toBe(true);
      await shot(page, {
        target: [cardOf(table), menu],
        keep: [cardOf(table), menu],
        guard: { allowText: [LINK_ONE_OFF] },
      });
    });
  });

  test('admin-manual/PublicationImportSeriesConfirm.png', async ({ page }) => {
    await openAs(page, NM, '/admin/publications');
    await page.getByRole('button', { name: 'Publication actions' }).click();
    await page.getByRole('menuitem', { name: 'Import series definitions' }).click();
    const confirm = page.getByRole('alertdialog', { name: 'Import series definitions' });
    await expect(confirm).toBeVisible();
    // Stopped at the question: the file picker behind it is never opened.
    await panelShot(page, confirm);
  });

  // ----------------------------------------------------------------- series settings

  test.describe('in a taller window', () => {
    // The settings drawer is as tall as the window; at 960 px each tab shows whole.
    test.use({ viewport: { width: 1280, height: 960 } });

    test('admin-manual/PublicationSeriesBasics.png', async ({ page }) => {
      await fixClock(page);
      await loginAs(page, 'admin', NM);
      const drawer = await openSettings(page, WEEKLY, 'Basics');
      await shot(page, { target: drawer, pad: 0 });
    });

    test('admin-manual/PublicationSeriesContent.png', async ({ page }) => {
      await fixClock(page);
      await loginAs(page, 'admin', NM);
      const drawer = await openSettings(page, WEEKLY, 'Content');
      await shot(page, { target: openTab(drawer) });
    });

    test('admin-manual/PublicationSeriesFrequency.png', async ({ page }) => {
      await fixClock(page);
      await loginAs(page, 'admin', NM);
      const drawer = await openSettings(page, WEEKLY, 'Frequency & naming');
      await shot(page, { target: openTab(drawer) });
    });

    test('admin-manual/PublicationSeriesCriteria.png', async ({ page }) => {
      // The in-force list: two criteria, and the forced liveness filter.
      await fixClock(page);
      await loginAs(page, 'admin', NM);
      const drawer = await openSettings(page, IN_FORCE, 'Criteria');
      await expect(drawer.getByText(/\d+ message\(s\)/).first()).toBeVisible();
      await shot(page, { target: openTab(drawer) });
    });

    test('admin-manual/PublicationSeriesCompiledFrom.png', async ({ page }) => {
      await fixClock(page);
      await loginAs(page, 'admin', NM);
      const drawer = await openSettings(page, COMPILED, 'Criteria');
      await shot(page, { target: openTab(drawer) });
    });

    test('admin-manual/PublicationAvailabilityField.png', async ({ page }) => {
      await fixClock(page);
      await loginAs(page, 'admin', NM);
      const drawer = await openSettings(page, WEEKLY, 'Basics');
      // Changed in the form only; the drawer is never saved.
      await drawer.getByRole('radio', { name: 'Selected domains' }).check();
      const list = drawer.getByTestId('availability-domains');
      await list.getByRole('checkbox').first().check();
      const field = drawer
        .getByText('Can be cited from messages in', { exact: true })
        .locator('xpath=ancestor::div[1]');
      await shot(page, { target: field });
    });
  });

  test.describe('in a window that holds the whole new-series drawer', () => {
    // The drawer's notice, its tabs and the list of failing rules together: scrolled to the rules
    // in a shorter window, the notice at the top is cut through.
    test.use({ viewport: { width: 1280, height: 1080 } });

    test('admin-manual/PublicationSeriesCheckRules.png', async ({ page }) => {
      await openAs(page, NM, '/admin/publications');
      await page.getByRole('button', { name: 'Create a publication' }).click();
      await page.getByRole('menuitem', { name: 'New series' }).click();
      const drawer = page.getByRole('dialog', { name: 'New series' });
      await expect(drawer).toBeVisible();
      // Checked, never created: "Check rules" asks the server what a save would refuse.
      await drawer.getByRole('textbox').first().fill('Guide example monthly overview');
      await drawer.getByRole('combobox').first().selectOption({ label: 'Notices to Mariners' });
      const check = drawer.getByRole('button', { name: 'Check rules' });
      await check.click();
      const result = drawer.getByRole('alert').filter({ hasText: /rule\(s\) fail/ });
      await expect(result).toBeVisible();
      // The link is drawn as it rests, not in the colour of the pointer over it or of its focus.
      await page.mouse.move(4, 4);
      await check.blur();
      // Everything the picture is about is in view without scrolling: the tabs, the notice above
      // the fields and the last failing rule.
      await expect(drawer.getByRole('tablist')).toBeInViewport({ ratio: 1 });
      await expect(drawer.getByText(/is created as a draft/)).toBeInViewport({ ratio: 1 });
      await expect(result.locator('li').last()).toBeInViewport({ ratio: 1 });
      await panelShot(page, drawer);
    });
  });

  // ------------------------------------------------------------------ the series page

  test('admin-manual/PublicationSeriesPage.png', async ({ page }) => {
    await openAs(page, NM, `/admin/publications/${WEEKLY}`);
    await expect(page.getByRole('row').nth(2)).toBeVisible();
    await shot(page);
  });

  test('admin-manual/PublicationSeriesHistory.png', async ({ page }) => {
    await openAs(page, NM, `/admin/publications/${WEEKLY}`);
    await expect(page.getByRole('row').nth(2)).toBeVisible();
    await page.getByTestId('series-history-toggle').click();
    const history = page.getByTestId('series-history');
    await expect(history.locator('li').first()).toBeVisible();
    await shot(page, { target: cardOf(history) });
  });

  test('admin-manual/PublicationNewIssueDialog.png', async ({ page }) => {
    // The seeded weekly series with no issue: New issue on its page opens the dialog for its first
    // period. Nothing is saved.
    await openAs(page, NM, `/admin/publications/${EXAMPLE_WEEKLY}`);
    await page.getByRole('button', { name: 'New issue' }).click();
    const dialog = page.getByRole('dialog', { name: 'New issue' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText('Working out what this period would contain…')).toHaveCount(0);
    // Reviewed and left: "Create issue" is never pressed.
    await panelShot(page, dialog);
  });

  // ------------------------------------------------------------------ an open issue

  test('admin-manual/PublicationOpenIssue.png', async ({ page }) => {
    await openInForceIssue(page);
    // The issue's History card is left out: with one entry it reads "1 entries", a plural the
    // application's English texts have no singular for. PublicationIssueHistory.png shows it.
    const history = cardOf(page.getByText('History', { exact: true }).first());
    await shot(page, { hide: [history] });
  });

  test('admin-manual/PublicationIssueLeftOut.png', async ({ page }) => {
    await openInForceIssue(page);
    const toggle = page.getByTestId('issue-omissions-toggle');
    await toggle.click();
    const rows = page.getByTestId('issue-omissions').locator('li');
    await expect(rows.first()).toBeVisible();
    // The heading row and the first six reasons: the list runs on in the same shape.
    const header = toggle.locator('xpath=..');
    await shot(page, {
      target: [header, rows.nth(5)],
      keep: [header, rows.first().locator('xpath=..')],
    });
  });

  test('admin-manual/PublicationIssueDocument.png', async ({ page }) => {
    await openInForceIssue(page);
    // As it stands: "Generate preview" would write a preview and a line in the issue's history.
    const card = cardOf(page.getByText('Document', { exact: true }).first());
    await shot(page, { target: card });
  });

  test('admin-manual/PublicationAddMessageDialog.png', async ({ page }) => {
    await openInForceIssue(page);
    await page.getByRole('button', { name: 'Add message' }).click();
    const dialog = page.getByRole('dialog', { name: /^Add a message to/ });
    await expect(dialog).toBeVisible();
    // A permanent notice: the in-force list of temporary and preliminary notices does not select it.
    // Part of the number: a pick whose label equals the typed text leaves the box empty (the
    // label is written back only when it differs from what was typed).
    const search = dialog.getByRole('combobox');
    await expect(async () => {
      await search.fill('');
      await search.pressSequentially('817-26', { delay: 30 });
      await expect(page.getByRole('option', { name: /NM-817-26/ }).first()).toBeVisible({
        timeout: 5_000,
      });
      await search.press('ArrowDown');
      await search.press('Enter');
      await expect(search).toHaveValue(/NM-817-26/, { timeout: 1_000 });
    }).toPass({ timeout: 30_000 });
    await dialog.getByRole('textbox').last().fill('Repeated by agreement with the pilotage.');
    // Not added: "Add to the issue" is never pressed.
    await panelShot(page, dialog);
  });

  test('admin-manual/PublicationExcludeDialog.png', async ({ page }) => {
    await openInForceIssue(page);
    await page
      .getByRole('checkbox', { name: /^Select NM-/ })
      .first()
      .check();
    await page.getByRole('button', { name: 'Exclude selected (1)' }).click();
    const dialog = page.getByRole('dialog', { name: 'Exclude from this issue' });
    await expect(dialog).toBeVisible();
    await dialog.getByRole('textbox').fill('Replaced by a newer notice for the same works.');
    await panelShot(page, dialog);
  });

  test('admin-manual/PublicationTailorIssue.png', async ({ page }) => {
    await openInForceIssue(page);
    await page.getByTestId('issue-tailor').click();
    const drawer = page.getByRole('dialog', { name: 'Tailor this issue' });
    await expect(drawer).toBeVisible();
    // The drawer focuses its first field; nothing is typed, so the field is shown at rest. Its
    // value is the issue's name, so leaving it shows no validation message.
    const name = drawer.getByRole('textbox').first();
    await expect(name).not.toHaveValue('');
    await name.blur();
    await panelShot(page, drawer);
  });

  test('admin-manual/PublicationChecklist.png', async ({ page }) => {
    // The open compilation: its checklist carries two warnings.
    await openAs(page, NM, issuePath(COMPILED, issueId(COMPILED, 'OPEN')));
    await issueLoaded(page);
    const card = cardOf(page.getByText('Checklist', { exact: true }).first());
    await shot(page, { target: card });
  });

  test('admin-manual/PublicationPublishDialog.png', async ({ page }) => {
    await openInForceIssue(page);
    await page.getByRole('button', { name: 'Publish…' }).click();
    const dialog = page.getByRole('dialog', { name: /^Publish / });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText(/counting them/)).toHaveCount(0);
    // Stopped at the dialog: "Publish" is never pressed.
    await panelShot(page, dialog);
  });

  // ---------------------------------------------------------------- a published issue

  test('admin-manual/PublicationPublishedIssue.png', async ({ page }) => {
    // Week 36, released in Niord itself and amended once by the seed: the amendment ran the
    // selection again, so no member carries a changed mark (the issues carried over from the old
    // system still do, but their rows read "Carried over from the legacy issue" and they have no
    // cog menu, which is not the issue the chapter describes).
    await openAs(page, NM, issuePath(WEEKLY, issueId(WEEKLY, 'PUBLISHED', RELEASED_HERE)));
    await expect(page.getByText('frozen at publication')).toBeVisible();
    await shot(page);
  });

  test('admin-manual/PublicationAmendDialog.png', async ({ page }) => {
    await openAs(page, NM, issuePath(WEEKLY, issueId(WEEKLY, 'PUBLISHED', RELEASED_HERE)));
    await expect(page.getByText('frozen at publication')).toBeVisible();
    await page.getByRole('button', { name: 'Publication actions' }).click();
    await page.getByRole('menuitem', { name: 'Amend' }).click();
    const dialog = page.getByRole('dialog', { name: 'Amend issue' });
    await expect(dialog).toBeVisible();
    await dialog.getByRole('textbox').fill('Corrected title of one notice.');
    // Not amended: the dialog's own "Amend" is never pressed.
    await panelShot(page, dialog);
  });

  test('admin-manual/PublicationIssueHistory.png', async ({ page }) => {
    await openAs(page, NM, issuePath(WEEKLY, issueId(WEEKLY, 'PUBLISHED', RELEASED_HERE)));
    await expect(page.getByText('frozen at publication')).toBeVisible();
    const label = page.getByText('History', { exact: true }).first();
    const card = cardOf(label);
    await card.getByRole('button').first().click();
    await shot(page, { target: card });
  });

  // ----------------------------------------------------------------- compilations

  test.describe('in a wider window', () => {
    // At 1280 px the member table is narrow, and the hand-added row's long reason leaves its
    // message column so little room that the number and the title are cut short.
    test.use({ viewport: { width: 1600, height: 900 } });

    test('admin-manual/PublicationCompilationOpen.png', async ({ page }) => {
      await openAs(page, NM, issuePath(COMPILED, issueId(COMPILED, 'OPEN')));
      await issueLoaded(page);
      // The hand-added run follows every source issue's: opened, and photographed with the last
      // source groups above it, inside the member list's card.
      const groups = page.getByTestId('issue-source-group');
      const byHand = groups.filter({ hasText: 'Added by hand' });
      await byHand.getByTestId('issue-source-group-toggle').click();
      const added = byHand.locator('xpath=following-sibling::tr[1]');
      await expect(added).toContainText('Added by hand');
      await expect(added).toContainText(HAND_ADDED);
      const firstShown = groups.nth((await groups.count()) - 5);
      await added.evaluate((row) => row.scrollIntoView({ block: 'end' }));
      await expect(firstShown).toBeInViewport({ ratio: 1 });
      await shot(page, { target: [firstShown, added], keep: page.getByTestId('issue-members') });
    });
  });

  test('admin-manual/PublicationCompilationPublished.png', async ({ page }) => {
    await openAs(page, NM, issuePath(COMPILED, issueId(COMPILED, 'PUBLISHED')));
    await issueLoaded(page);
    await expect(page.getByText(/compiled from \d+ issues/)).toBeVisible();
    await shot(page);
  });

  // ------------------------------------------------------------------- one-offs

  test.describe('in a window that holds the whole one-off drawer', () => {
    // Advanced opened ends with how messages cite the publication, below the fold at 800 px.
    test.use({ viewport: { width: 1280, height: 1040 } });

    test('admin-manual/PublicationNewOneOffDialog.png', async ({ page }) => {
      await openAs(page, ANNEX, '/admin/publications');
      await page.getByRole('button', { name: 'Create a publication' }).click();
      await page.getByRole('menuitem', { name: 'New one-off publication' }).click();
      const dialog = page.getByRole('dialog', { name: 'New one-off publication' });
      await expect(dialog).toBeVisible();
      await dialog.getByRole('textbox').first().fill('Guide example pilotage handbook');
      await dialog.getByRole('button', { name: 'Uploaded PDF', exact: true }).click();
      await dialog
        .getByRole('combobox')
        .first()
        .selectOption({ label: 'Annexes - Notices to Mariners' });
      await dialog.getByRole('button', { name: /^Advanced/ }).click();
      // Citable, so the Reference format shows under it.
      await dialog
        .getByRole('combobox', { name: /^Selectable as a reference in messages/ })
        .selectOption({ label: 'External (shown publicly on the message)' });
      const format = dialog.getByRole('textbox', { name: /Reference format/ }).first();
      await format.fill('Pilotage handbook, section ${parameters}');
      await format.blur();
      await expect(format).toBeInViewport({ ratio: 1 });
      // Not saved: the drawer is closed with the test.
      await panelShot(page, dialog, { allowText: [LINK_ONE_OFF] });
    });
  });

  test('admin-manual/PublicationOneOffPage.png', async ({ page }) => {
    await openAs(page, ANNEX, `/admin/publications/one-off/${ONE_OFF_FILE}`);
    await expect(page.getByText('On the public site', { exact: true })).toBeVisible();
    await shot(page);
  });

  test('admin-manual/PublicationPublicPeriodDialog.png', async ({ page }) => {
    await openAs(page, ANNEX, `/admin/publications/one-off/${ONE_OFF_FILE}`);
    await page.getByRole('button', { name: 'Edit public period' }).click();
    const dialog = page.getByRole('dialog', { name: 'Public period' });
    await expect(dialog).toBeVisible();
    await panelShot(page, dialog);
  });

  // ------------------------------------------------------------------ domains

  test('admin-manual/PublicationForeignDomain.png', async ({ page }) => {
    // The NW desk opens the NM desk's weekly series.
    await openAs(page, 'niord-nw', `/admin/publications/${WEEKLY}`);
    await expect(page.getByText("Another domain's publication")).toBeVisible();
    await shot(page);
  });

  // ---------------------------------------------------------------- the public site

  test('admin-manual/PublicSitePublications.png', async ({ page }) => {
    await fixClock(page);
    await page.goto(infoUrl('/publications'));
    await expect(page.getByRole('link', { name: 'Download' }).first()).toBeVisible();
    await shot(page);
  });

  test('admin-manual/PublicSitePublicationBrowse.png', async ({ page }) => {
    await fixClock(page);
    await page.goto(infoUrl('/publications'));
    const row = page.getByRole('row', { name: /NtM Week \d+ - \d{4}/ }).first();
    await row.getByRole('link', { name: 'Browse' }).click();
    await page.waitForURL(/publicationId=/);
    await page.waitForLoadState('networkidle');
    await shot(page);
  });

  // ------------------------------------------------------------ publication categories

  test('admin-manual/PublicationCategoriesPage.png', async ({ page }) => {
    await openAs(page, NM, '/admin/publication-categories');
    await expect(page.getByRole('row').nth(1)).toBeVisible();
    await shot(page);
  });

  test('admin-manual/PublicationCategoryEditor.png', async ({ page }) => {
    await openAs(page, NM, '/admin/publication-categories');
    await page.getByRole('button', { name: 'Edit Notices to Mariners' }).click();
    const drawer = page.getByRole('dialog', { name: 'Publication category' });
    await expect(drawer).toBeVisible();
    await panelShot(page, drawer);
  });
});
