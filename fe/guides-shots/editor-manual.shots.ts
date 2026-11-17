import type { Page } from '@playwright/test';

import { fixClock } from './support/clock';
import {
  filterPanel,
  firstWithStatus,
  foldGroups,
  foldLanguageBlocks,
  openActions,
  pick,
  plainPublishedMessage,
  runAction,
  settleFilterMenu,
  similarPublishedPair,
  starFirst,
  starOf,
  taggedPublishedMessage,
  toggleFilter,
  waitForMessages,
} from './support/editor-messages';
import { DOMAIN } from './support/env';
import { expect, test } from './support/fixtures';
import { loginAs } from './support/login';
import { showMapAt, type MapView } from './support/map';
import { settle } from './support/stabilise';
import { shot } from './support/shot';

/*
 * The editor manual's pictures, written to guides/editor-manual/images/: the Home and Messages
 * chapters (02-Home.adoc, 03-Messages.adoc), as an editor sees them. The editor, the position
 * editor and template execution have specs of their own.
 *
 * One test per picture, titled `<manual>/<file>` after the file it writes, so
 * `--grep "<manual>/<file>"` takes one picture again and manifest-check.mjs reads the list from the
 * titles. Every manifest entry marked retake, replace or new becomes one test here.
 *
 * Nothing is saved: a filter, a selection, a typed form or an open dialog lives in the page only.
 * No test clicks Save, Send Mail, Print, Import or Update, or drags a message; the one Execute
 * runs a mailing-list report, which reads the published messages and writes nothing.
 */

// Denmark's waters, from the Skaw to Bornholm's latitude: the markers of the Belts and the Baltic
// stand apart, and the neighbouring countries take the edges (the pilot's view).
const DENMARK: MapView = { lon: 11.2, lat: 56.15, zoom: 7.3 };

/** Signs in as an editor of the rig's domain and opens a view of the Messages page. */
async function openMessages(page: Page, path = '/messages/table', domain = DOMAIN): Promise<void> {
  await fixClock(page);
  await loginAs(page, 'editor', domain);
  await page.goto(path);
  await waitForMessages(page);
}

/** The bar's group of five view tabs. */
function viewTabs(page: Page) {
  return page
    .getByRole('link', { name: 'Table', exact: true })
    .locator('xpath=ancestor::div[contains(@class, "justify-center")][1]');
}

/** The header's menu triggers: the session domain's, the account's, the language's. */
function headerTrigger(page: Page, index: number) {
  return page.locator('app-main-header nav button[aria-haspopup]').nth(index);
}

/** The open dialog (the last, when one opens another). */
function dialog(page: Page, name?: string | RegExp) {
  return (name ? page.getByRole('dialog', { name }) : page.getByRole('dialog')).last();
}

test.describe('editor-manual', () => {
  // ---- 02-Home.adoc -------------------------------------------------------------------------

  test('editor-manual/Home.png', async ({ page }) => {
    await fixClock(page);
    // As a visitor sees it before logging in: the welcome panel holds the Login button.
    await page.goto(`/?domain=${DOMAIN}`);
    await expect(
      page.getByRole('heading', { name: 'Nautical Information Directory' }),
    ).toBeVisible();
    await expect(page.locator('app-home-map app-ol-map')).toBeVisible();
    await showMapAt(page, DENMARK);
    await shot(page);
  });

  test('editor-manual/UpdateBanner.png', async ({ page }) => {
    await openMessages(page);
    // What a tab left open across an installation meets: the parts of the application it has not
    // loaded yet are gone from the server, so opening the editor fails, and the banner says why.
    // (The rig's build names its main bundle without a hash, so the other sign, a deployed page
    // naming another main bundle, cannot be shown with it.)
    await page.route(/\/chunk-[A-Z0-9]+\.js$/, (route) => route.abort());
    await page.locator('app-main-header nav').getByRole('link', { name: 'Editor' }).click();
    const banner = page.getByRole('alert').filter({ hasText: 'Please refresh web-browser' });
    await expect(banner).toBeVisible();
    await page.unroute(/\/chunk-[A-Z0-9]+\.js$/);
    await page.mouse.move(640, 600);
    await shot(page, {
      target: [page.locator('app-main-header'), banner],
      pad: 0,
      // The banner's two version notes name the rig's own build (an unfilled build stamp, an
      // unhashed bundle), not a deployment's: they are left out.
      hide: [banner.locator('span.text-xs')],
      guard: {
        // The failed loads are the point of the picture.
        allowConsole: [/Failed to load resource/, /dynamically imported module/i, /ERR_FAILED/],
      },
    });
  });

  test('editor-manual/Log-In.png', async ({ page }) => {
    await page.goto(`/?domain=${DOMAIN}`);
    await page.locator('app-home-page').getByRole('button', { name: 'Login' }).click();
    await page.waitForURL(/\/realms\/niord\/protocol\/openid-connect\/auth/);
    const form = page.getByRole('main');
    await expect(page.getByRole('heading', { name: 'Sign in to your account' })).toBeVisible();
    await shot(page, { target: form, maps: false, guard: { checks: 'external' } });
  });

  test('editor-manual/ProfileMenu.png', async ({ page }) => {
    await openMessages(page);
    const trigger = headerTrigger(page, 1);
    await trigger.click();
    const menu = page.getByRole('menu');
    await expect(menu.getByRole('menuitem', { name: 'Logout' })).toBeVisible();
    await shot(page, {
      target: [trigger.locator('xpath=..'), menu],
      keep: [page.locator('app-main-header'), menu],
    });
  });

  test('editor-manual/DomainMenu.png', async ({ page }) => {
    await openMessages(page);
    const trigger = headerTrigger(page, 0);
    await trigger.click();
    const menu = page.getByRole('menu');
    await expect(menu.getByRole('menuitem', { name: 'NM', exact: true })).toBeVisible();
    await shot(page, {
      target: [trigger.locator('xpath=..'), menu],
      keep: [page.locator('app-main-header'), menu],
    });
  });

  test('editor-manual/DeploymentBadge.png', async ({ page }) => {
    await fixClock(page);
    await page.goto(`/messages/table?domain=${DOMAIN}`);
    await waitForMessages(page);
    // The stabiliser hides the badge in every other picture; this one is about it.
    await page.evaluate(() => document.documentElement.classList.add('guides-show-badge'));
    const logo = page.locator('app-main-header div.relative.shrink-0');
    await expect(logo.getByText('TEST', { exact: true })).toBeVisible();
    await shot(page, { target: logo, keep: page.locator('app-main-header') });
  });

  test('editor-manual/HeaderHelpLink.png', async ({ page }) => {
    await openMessages(page);
    const help = page.locator('app-main-header nav').getByRole('link', { name: 'Help' });
    await shot(page, { target: help, pad: 0 });
  });

  test('editor-manual/GuestBanner.png', async ({ page }) => {
    // The sign-in service cannot be reached: the application starts as a guest and says so.
    await page.route(/\/auth\/realms\//, (route) => route.abort());
    await fixClock(page);
    await page.goto(`/messages/table?domain=${DOMAIN}`);
    const banner = page.getByRole('status').filter({ hasText: 'Login is currently unavailable' });
    await expect(banner).toBeVisible({ timeout: 30_000 });
    await waitForMessages(page);
    await shot(page, {
      target: [page.locator('app-main-header'), banner],
      pad: 0,
      guard: {
        // The refused calls to the sign-in service are the point of the picture.
        allowConsole: [/Failed to load resource/, /ERR_FAILED/, /keycloak/i, /check-sso|silent/i],
      },
    });
  });

  // ---- 03-Messages.adoc: the page and its views ----------------------------------------------

  test('editor-manual/Messages.png', async ({ page }) => {
    await openMessages(page);
    await shot(page);
  });

  test('editor-manual/ViewModes.png', async ({ page }) => {
    await openMessages(page);
    await starFirst(page, 1);
    await shot(page, { target: viewTabs(page) });
  });

  test('editor-manual/DatesColumnMenu.png', async ({ page }) => {
    await openMessages(page);
    await page.getByRole('button', { name: 'Select date column' }).click();
    const menu = page.getByRole('menu');
    await expect(menu.getByRole('menuitem', { name: 'Follow-up date' })).toBeVisible();
    const heading = page.getByRole('columnheader', { name: /Event dates/ });
    await shot(page, { target: [heading, menu], keep: [heading, menu] });
  });

  test('editor-manual/FollowUpOverdue.png', async ({ page }) => {
    await openMessages(page);
    // The first three rows: each has passed its follow-up date (the rig's clock is fixed).
    const rows = page
      .locator('tbody tr')
      .filter({ has: page.locator('app-message-selection-toggle') });
    await expect(rows.first()).toHaveClass(/row-attention/);
    await shot(page, { target: [rows.nth(0), rows.nth(1), rows.nth(2)], pad: 4 });
  });

  test('editor-manual/LoadMore.png', async ({ page }) => {
    // The expired and cancelled messages created in 2024: a list longer than a page, whose count
    // does not move, and whose rows at the foot of the first page are English throughout.
    const from = Date.parse('2024-01-01T00:00:00+01:00');
    const to = Date.parse('2024-12-31T00:00:00+01:00');
    await openMessages(
      page,
      `/messages/table?status=EXPIRED&status=CANCELLED&dateType=CREATED_DATE&fromDate=${from}&toDate=${to}`,
    );
    const button = page.getByRole('button', { name: /^Load \d/ });
    await expect(button).toBeVisible();
    const rows = page
      .locator('tbody tr')
      .filter({ has: page.locator('app-message-selection-toggle') });
    await shot(page, { target: [rows.last(), button] });
  });

  test('editor-manual/GridViewMode.png', async ({ page }) => {
    await openMessages(page, '/messages/grid');
    await shot(page);
  });

  test('editor-manual/DetailsViewMode.png', async ({ page }) => {
    await openMessages(page, '/messages/details');
    await shot(page);
  });

  test('editor-manual/MapViewMode.png', async ({ page }) => {
    await fixClock(page);
    await loginAs(page, 'editor');
    await page.goto('/messages/map');
    await expect(page.locator('app-ol-map')).toBeVisible();
    await showMapAt(page, DENMARK);
    await page.locator('app-map-result-list').getByRole('button', { name: 'Expand' }).click();
    await settle(page);
    await shot(page);
  });

  test('editor-manual/SelectionButton.png', async ({ page }) => {
    await openMessages(page);
    await shot(page, { target: page.locator('app-message-selection-toggle').first(), pad: 4 });
  });

  test('editor-manual/MessageSelection.png', async ({ page }) => {
    await openMessages(page);
    await starFirst(page, 3);
    await viewTabs(page).getByRole('link', { name: 'Selection' }).click();
    await expect(page.locator('app-message-details-row')).toHaveCount(3);
    await settle(page);
    await shot(page);
  });

  // ---- 03-Messages.adoc: filters --------------------------------------------------------------

  test.describe('in a taller window (the Filter menu below its button)', () => {
    // At 800 px the menu, with a saved filter at its head, is too tall to open below the Filter
    // button and covers it, and with it the button's count.
    test.use({ viewport: { width: 1280, height: 1000 } });

    test('editor-manual/MessageFiltering.png', async ({ page }) => {
      await openMessages(page);
      await toggleFilter(page, 'Status');
      const status = filterPanel(page, 'Status');
      await status.getByRole('button', { name: 'Draft', exact: true }).click();
      await status.getByRole('button', { name: 'Verified', exact: true }).click();
      await toggleFilter(page, 'Area');
      await pick(
        page,
        filterPanel(page, 'Area').getByRole('combobox'),
        'Baltic',
        /^The Baltic Sea/,
      );
      const button = page.getByRole('button', { name: /^Filter/ });
      await button.click();
      await settleFilterMenu(page);
      // The editor's saved filter heads the menu.
      await expect(
        page.getByRole('menu', { name: 'Filter' }).getByText('Guide example: Baltic drafts'),
      ).toBeVisible();
      // The pointer is moved off the menu, so no item shows its hover colour.
      await page.mouse.move(400, 900);
      await settle(page, { maps: false });
      await shot(page);
    });
  });

  test('editor-manual/MessageFilterDomain.png', async ({ page }) => {
    await openMessages(page);
    await toggleFilter(page, 'Domain');
    const panel = filterPanel(page, 'Domain');
    await panel.getByRole('combobox').click();
    await page.getByRole('option', { name: 'NM', exact: true }).click();
    await settle(page, { maps: false });
    await shot(page, { target: panel });
  });

  test('editor-manual/MessageFilterSeries.png', async ({ page }) => {
    await openMessages(page);
    await toggleFilter(page, 'Message Series');
    const panel = filterPanel(page, 'Message series');
    await pick(page, panel.getByRole('combobox'), 'local', /./);
    await shot(page, { target: panel });
  });

  test('editor-manual/MessageFilterText.png', async ({ page }) => {
    await openMessages(page);
    await toggleFilter(page, 'Text');
    const panel = filterPanel(page, 'Search');
    await panel.getByRole('searchbox').fill('"light buoy"');
    await settle(page, { maps: false });
    await shot(page, { target: panel });
  });

  test('editor-manual/MessageFilterType.png', async ({ page }) => {
    await openMessages(page);
    await toggleFilter(page, 'Type');
    const panel = filterPanel(page, 'Type');
    await panel.getByRole('button', { name: 'Local', exact: true }).click();
    await panel.getByRole('button', { name: 'Coastal', exact: true }).click();
    await settle(page, { maps: false });
    await shot(page, { target: panel });
  });

  test('editor-manual/MessageFilterStatus.png', async ({ page }) => {
    await openMessages(page);
    await toggleFilter(page, 'Status');
    const panel = filterPanel(page, 'Status');
    for (const status of ['Published', 'Draft', 'Verified']) {
      await panel.getByRole('button', { name: status, exact: true }).click();
    }
    await settle(page, { maps: false });
    await shot(page, { target: panel });
  });

  test('editor-manual/MessageFilterTag.png', async ({ page }) => {
    await openMessages(page);
    await toggleFilter(page, 'Tag');
    const panel = filterPanel(page, 'Tag');
    await pick(page, panel.getByRole('combobox'), 'Guide example', /Guide example: buoys/);
    await shot(page, { target: panel });
  });

  test('editor-manual/TagButton.png', async ({ page }) => {
    await openMessages(page);
    await toggleFilter(page, 'Tag');
    const button = filterPanel(page, 'Tag').getByRole('button', { name: 'Browse all tags' });
    await shot(page, { target: button, pad: 2 });
  });

  test('editor-manual/MessageFilterPublication.png', async ({ page }) => {
    await openMessages(page, '/messages/table', 'niord-nm');
    await toggleFilter(page, 'Publication');
    const panel = filterPanel(page, 'Publication');
    await pick(page, panel.getByRole('combobox'), 'Week 38', /Week 38/);
    await shot(page, { target: panel });
  });

  test('editor-manual/MessageFilterPromulgation.png', async ({ page }) => {
    await openMessages(page);
    await toggleFilter(page, 'Promulgation');
    const panel = filterPanel(page, 'Promulgation');
    await pick(page, panel.getByRole('combobox'), 'NAVTEX', /^NAVTEX/);
    await shot(page, { target: panel });
  });

  test('editor-manual/MessageFilterUser.png', async ({ page }) => {
    await openMessages(page);
    await toggleFilter(page, 'User');
    const panel = filterPanel(page, 'User');
    await panel.getByRole('button', { name: 'Me', exact: true }).click();
    await panel.getByRole('button', { name: 'Last updated by', exact: true }).click();
    await settle(page, { maps: false });
    await shot(page, { target: panel });
  });

  test('editor-manual/MessageFilterComments.png', async ({ page }) => {
    await openMessages(page);
    await toggleFilter(page, 'Comments');
    const panel = filterPanel(page, 'Comments');
    await panel.getByRole('button', { name: 'Any unacknowledged', exact: true }).click();
    await settle(page, { maps: false });
    await shot(page, { target: panel });
  });

  test('editor-manual/MessageFilterReference.png', async ({ page }) => {
    await openMessages(page);
    const message = await plainPublishedMessage(page.request);
    await toggleFilter(page, 'Reference');
    const panel = filterPanel(page, 'Reference');
    const shortId = message.shortId ?? '';
    await pick(page, panel.getByRole('combobox'), shortId, new RegExp(`^${shortId}`));
    await shot(page, { target: panel });
  });

  test('editor-manual/MessageFilterChart.png', async ({ page }) => {
    await openMessages(page);
    await toggleFilter(page, 'Chart');
    const field = filterPanel(page, 'Chart').getByRole('combobox');
    await pick(page, field, '100', /^100Kattegat/);
    await pick(page, field, '101', /^101Kattegat/);
    await shot(page, { target: filterPanel(page, 'Chart') });
  });

  test('editor-manual/MessageFilterArea.png', async ({ page }) => {
    await openMessages(page);
    await toggleFilter(page, 'Area');
    const panel = filterPanel(page, 'Area');
    await pick(page, panel.getByRole('combobox'), 'Baltic', /^The Baltic Sea/);
    await shot(page, { target: panel });
  });

  test('editor-manual/MessageFilterCategory.png', async ({ page }) => {
    await openMessages(page);
    await toggleFilter(page, 'Category');
    const panel = filterPanel(page, 'Category');
    await pick(page, panel.getByRole('combobox'), 'Light buoy unlit', /^Light buoy unlit/);
    await shot(page, { target: panel });
  });

  test('editor-manual/MessageFilterDate.png', async ({ page }) => {
    // 1 to 30 November 2026 in the domain's time zone, set the way a bookmark carries it.
    const from = Date.parse('2026-11-01T00:00:00+01:00');
    const to = Date.parse('2026-11-30T00:00:00+01:00');
    await openMessages(page, `/messages/table?dateType=EVENT_DATE&fromDate=${from}&toDate=${to}`);
    await shot(page, { target: filterPanel(page, 'Date') });
  });

  test('editor-manual/AreaTree.png', async ({ page }) => {
    await openMessages(page);
    await toggleFilter(page, 'Area');
    await filterPanel(page, 'Area').getByRole('button', { name: 'Browse area tree' }).click();
    const picker = page.locator('.picker-overlay .picker-panel');
    await expect(picker.getByText('The Baltic Sea', { exact: true })).toBeVisible();
    await settle(page, { maps: false });
    // The picker's title, its filter and the rows from Denmark to Skagerrak: the picture ends at a
    // whole row instead of where the tree's scroll region cuts the next one.
    const rows = picker.locator('.tree-row');
    const last = rows.filter({ hasText: 'Skagerrak' }).first();
    const lastIndex = await rows.evaluateAll((all) =>
      all.findIndex((row) => row.textContent?.trim() === 'Skagerrak'),
    );
    if (lastIndex < 0) throw new Error('The area tree shows no row named Skagerrak.');
    const header = picker.locator('header');
    const filter = picker.locator('.tree-filter');
    await shot(page, {
      target: [header, filter, last],
      keep: [
        header,
        filter,
        ...Array.from({ length: lastIndex + 1 }, (_, index) => rows.nth(index)),
      ],
    });
  });

  test('editor-manual/SaveFilterDialog.png', async ({ page }) => {
    await openMessages(page);
    await toggleFilter(page, 'Status');
    await filterPanel(page, 'Status').getByRole('button', { name: 'Draft', exact: true }).click();
    await page.getByRole('button', { name: /^Filter/ }).click();
    await settleFilterMenu(page);
    await page.getByRole('menuitem', { name: 'Save Filter...' }).click();
    const save = dialog(page, 'Save filter');
    await save.getByRole('textbox', { name: 'Filter name' }).fill('Drafts to read through');
    // The dialog is photographed, never saved.
    await shot(page, { target: save, pad: 0 });
  });

  // ---- 03-Messages.adoc: actions ----------------------------------------------------------------

  // With a selection, the Actions menu holds every item and is taller than an 800 px window.
  test.describe('in a taller window', () => {
    test.use({ viewport: { width: 1280, height: 1000 } });

    test('editor-manual/MessageActions.png', async ({ page }) => {
      await openMessages(page);
      await starFirst(page, 2);
      const menu = await openActions(page);
      const trigger = page.getByRole('button', { name: /^Actions/ });
      await shot(page, { target: [trigger, menu], keep: [trigger, menu] });
    });
  });

  test('editor-manual/PrintDialog.png', async ({ page }) => {
    await openMessages(page);
    await starFirst(page, 2);
    await runAction(page, 'Print selection...');
    const print = dialog(page, 'Print 2 messages');
    await expect(print.getByRole('combobox', { name: 'Report' })).toBeVisible();
    await shot(page, { target: print, pad: 0 });
  });

  test('editor-manual/MailingListReportDialog.png', async ({ page }) => {
    // The report's result is shown in a sandboxed frame. The rig's start-up scripts fail there;
    // the guard leaves out exactly those errors (guard.ts). The result's stylesheet,
    // /css/templates/mail.css, comes from the backend, as a deployment's web server sends it.
    await loginAs(page, 'editor');
    await page.goto('/messages/table');
    await waitForMessages(page);
    await runAction(page, 'Report...');
    const reports = dialog(page, 'Execute Mailing List Report');
    // Running a report reads the published messages and writes nothing. Not the Danish coastal
    // warnings: their report prints the copy's NW-319-26 at "56° 60.0'N", a position the backend's
    // formatter rounds without carrying into the degrees (be/docs/potential-errors.md, POS1).
    await reports
      .getByRole('row', { name: /^Active coastal warnings - Greenland Daily/ })
      .getByRole('button', { name: 'Execute' })
      .click();
    await expect(reports.getByText('Result', { exact: true })).toBeVisible();
    await settle(page, { maps: false });
    await shot(page, { target: reports, pad: 0 });
  });

  test('editor-manual/MailDialog.png', async ({ page }) => {
    await openMessages(page);
    await starFirst(page, 2);
    await runAction(page, 'E-mail selection...');
    const mail = dialog(page, 'Mail 2 selected messages');
    // A whole address is offered as it is typed: an invented recipient outside the user directory.
    const recipient = 'harbour.master@example.org';
    await pick(
      page,
      mail.getByRole('combobox', { name: 'Select e-mail addresses' }),
      recipient,
      recipient,
    );
    await mail.getByRole('textbox', { name: 'Subject' }).fill('Buoys missing in the Baltic Sea');
    await mail
      .getByRole('textbox', { name: 'Message' })
      .fill('Please check the two warnings below before the morning broadcast.');
    // The mail is written and photographed, never sent.
    await shot(page, { target: mail, pad: 0 });
  });

  /** Compares the first two published messages with the same title, on one of the dialog's tabs. */
  async function compareTab(page: Page, tab: 'Side-by-side' | 'Changes' | 'Data'): Promise<void> {
    await openMessages(page);
    const [first, second] = await similarPublishedPair(page.request);
    await starOf(page, first.id).click();
    await starOf(page, second.id).click();
    await runAction(page, 'Compare two messages');
    const compare = dialog(page, 'Compare messages');
    await expect(compare.getByRole('article')).toHaveCount(2);
    await compare.getByRole('tab', { name: tab }).click();
    if (tab === 'Data') {
      const pane = compare.getByRole('tabpanel', { name: 'Data' });
      await foldLanguageBlocks(pane);
      // The areas and categories fill the pane before the message's own fields; folded, as a
      // reader folds a group by its name, they leave room for the rest.
      await foldGroups(pane, ['areas', 'categories']);
    }
    await settle(page, { maps: false });
    await shot(page, {
      target: compare,
      pad: 0,
      // The data comparison shows the message's type as it is stored.
      guard: { allowText: ['LOCAL_WARNING', 'COASTAL_WARNING'] },
    });
  }

  test('editor-manual/ComparisonDialog.png', async ({ page }) => {
    await compareTab(page, 'Side-by-side');
  });

  test('editor-manual/ComparisonDialogChanges.png', async ({ page }) => {
    await compareTab(page, 'Changes');
  });

  test('editor-manual/ComparisonDialogData.png', async ({ page }) => {
    await compareTab(page, 'Data');
  });

  test('editor-manual/ImportDialog.png', async ({ page }) => {
    await fixClock(page);
    await loginAs(page, 'admin');
    await page.goto('/messages/table');
    await waitForMessages(page);
    await runAction(page, 'Import...');
    // Nothing is chosen or imported: the dialog as it opens.
    await shot(page, { target: dialog(page, 'Import Messages'), pad: 0 });
  });

  test('editor-manual/StatusUpdateDialog.png', async ({ page }) => {
    await openMessages(page, '/messages/table?status=DRAFT&status=VERIFIED&status=PUBLISHED');
    for (const status of ['DRAFT', 'VERIFIED', 'PUBLISHED']) {
      const message = await firstWithStatus(page.request, status);
      await starOf(page, message.id).click();
    }
    await runAction(page, /^Update selection status/);
    const update = dialog(page, 'Update Statuses');
    await update.getByRole('combobox', { name: 'New status' }).first().click();
    await page.getByRole('option', { name: 'Verified', exact: true }).click();
    // The new status is chosen and photographed, never applied.
    await shot(page, { target: update, pad: 0 });
  });

  async function openSortArea(page: Page): Promise<ReturnType<typeof dialog>> {
    await openMessages(page);
    await runAction(page, /^Sort area/);
    const sort = dialog(page, 'Sort Messages');
    await pick(
      page,
      sort.getByRole('combobox', { name: 'Select an area' }),
      'Great Belt',
      /^The Great Belt/,
    );
    await expect(sort.getByRole('button', { name: 'Drag to reorder' }).first()).toBeVisible();
    return sort;
  }

  test('editor-manual/SortMessagesDialog.png', async ({ page }) => {
    const sort = await openSortArea(page);
    // Listed and photographed: no message is dragged, so the order is not changed.
    await shot(page, { target: sort, pad: 0 });
  });

  test('editor-manual/DragHandle.png', async ({ page }) => {
    const sort = await openSortArea(page);
    await shot(page, {
      target: sort.getByRole('button', { name: 'Drag to reorder' }).first(),
      pad: 2,
    });
  });

  test('editor-manual/SortAreaLink.png', async ({ page }) => {
    await openMessages(page);
    await shot(page, { target: page.locator('app-message-area-heading').first() });
  });

  // ---- 03-Messages.adoc: details and tags ----------------------------------------------------

  test('editor-manual/MessageDetailsDialog.png', async ({ page }) => {
    await openMessages(page);
    const message = await plainPublishedMessage(page.request);
    // Opened from the list, as a reader opens it, so the panel's foot has the previous and next
    // arrows (a deep link has none).
    await page.locator('tbody tr').locator(`a[href*="messageId=${message.id}"]`).first().click();
    const details = dialog(page, 'Message');
    await expect(details.getByRole('article')).toBeVisible();
    await expect(details.locator('app-ol-map')).toBeVisible();
    await expect(details.getByRole('button', { name: 'Next message' })).toBeVisible();
    await page.mouse.move(10, 400);
    await settle(page);
    // The whole window: the panel's buttons sit at its foot, at the window's bottom edge.
    await shot(page, { fit: false });
  });

  test('editor-manual/MessageDetailsMenu.png', async ({ page }) => {
    await fixClock(page);
    await loginAs(page, 'editor');
    // A message in a domain tag, so the menu offers Remove from… as well.
    const message = await taggedPublishedMessage(page.request);
    await page.goto(`/messages/table?messageId=${encodeURIComponent(message.id)}`);
    const details = dialog(page, 'Message');
    await expect(details.getByRole('article')).toBeVisible();
    const cog = details.getByRole('button', { name: 'Message actions' });
    await cog.click();
    const menu = page.getByRole('menu');
    await expect(menu.getByRole('menuitem', { name: 'Print' })).toBeVisible();
    await expect(menu.getByRole('menuitem', { name: /^Remove from/ })).toBeVisible();
    await settle(page);
    // The menu alone: the details panel is a modal dialog in the browser's top layer, which no
    // backdrop can paint over, so a frame holding the cog as well would show the panel between.
    await shot(page, { target: menu, pad: 0 });
  });

  test('editor-manual/TagPicker.png', async ({ page }) => {
    await openMessages(page);
    await toggleFilter(page, 'Tag');
    await filterPanel(page, 'Tag').getByRole('button', { name: 'Browse all tags' }).click();
    const picker = page.locator('.picker-overlay .picker-panel');
    // The editor's own and the domain's tags: the private and domain chips chosen.
    for (const scope of ['private', 'domain']) {
      await picker.getByRole('button', { name: scope, exact: true }).click();
    }
    await expect(picker.getByText('Guide example: to read through')).toBeVisible();
    await expect(picker.getByText('Guide example: buoys')).toBeVisible();
    await settle(page, { maps: false });
    await shot(page, { target: picker });
  });

  test('editor-manual/MessageTagsDialog.png', async ({ page }) => {
    await openMessages(page);
    await runAction(page, /^Manage tags/);
    const tags = dialog(page, 'Message Tags');
    // The public firing-area tags: locked ones and an unlocked one with its actions.
    await tags.getByRole('textbox', { name: 'Name' }).fill('firing-areas-202');
    await expect(tags.getByRole('row', { name: /firing-areas-2027-v1/ })).toBeVisible();
    await settle(page, { maps: false });
    await shot(page);
  });
});
