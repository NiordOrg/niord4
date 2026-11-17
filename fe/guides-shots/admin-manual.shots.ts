import type { Locator, Page } from '@playwright/test';

import { expect, test } from './support/fixtures';
import { loginAs } from './support/login';
import { shot } from './support/shot';

/*
 * The admin manual's pictures of its chapters on the Admin page, Charts, Areas, Sources, Users &
 * Contacts and Mailing Lists, written to guides/admin-manual/images/. The Publications chapters
 * have their own spec.
 *
 * One test per picture, titled `<manual>/<file>` after the file it writes, so
 * `--grep "<manual>/<file>"` takes one picture again and manifest-check.mjs reads the list from the
 * titles. Every manifest entry marked retake, replace or new becomes one test here. Nothing is
 * saved: an editor, a drawer or a dialog is photographed open and left unsaved, and no test depends
 * on another or on data another writer made.
 *
 *   npx playwright test -c guides-shots/playwright.config.ts admin-manual.shots.ts
 */

/** The domain with a firing schedule, where the Areas cog offers the firing-area generation. */
const FIRING_DOMAIN = 'niord-fa';

/** A chart with an international number, a scale, a datum and a polygon extent. */
const CHART_ROW = /Bornholmsgat/;

/** An area whose message sorting is Clockwise, so the origin fields show, with an extent. */
const CLOCKWISE_AREA = 'The Baltic Sea';

/** A source whose name and abbreviation are English. */
const SOURCE_ROW = /Danish Geodata Agency/;

/** A contact that is a recipient of a mailing list. */
const CONTACT = 'bianca.fisher@example.org';

/** A mailing list with two status-change triggers, and one with a scheduled trigger. */
const STATUS_CHANGE_LIST = 'navwarn-update';
const SCHEDULED_LIST = 'navwarn-overview';

/**
 * The published coastal warning the status change trigger is tested with: English, in the series
 * the trigger's filter names, and with a position the mail prints rightly.
 */
const TEST_MESSAGE = 'NW-323-26';

/** The drawer or dialog with the given heading. */
function dialogNamed(page: Page, name: string | RegExp): Locator {
  return page.getByRole('dialog', { name });
}

/** The pager below a list (drawn only when the list has more than one page). */
function pager(page: Page): Locator {
  return page.locator('app-pager nav');
}

/** Opens a section of the Admin area as a role, in a domain, and waits for its first table row. */
async function openSection(
  page: Page,
  path: string,
  role: 'admin' | 'sysadmin' = 'admin',
  domain?: string,
): Promise<void> {
  await loginAs(page, role, domain);
  await page.goto(`/admin/${path}`);
}

/** Opens the chart editor drawer on the example chart. */
async function openChart(page: Page): Promise<Locator> {
  await openSection(page, 'charts');
  await page
    .getByRole('row', { name: CHART_ROW })
    .getByRole('button', { name: 'Edit chart' })
    .click();
  const drawer = dialogNamed(page, 'Edit Chart');
  await expect(drawer.getByLabel('Chart number')).toBeVisible();
  return drawer;
}

/** The near-full-window dialog that holds the geometry editor (opened with Edit on an extent). */
function geometryEditorDialog(page: Page): Locator {
  return page.getByRole('dialog').filter({ has: page.locator('app-geometry-editor') });
}

/** Opens the Areas page and selects an area under the root area Denmark. */
async function openArea(page: Page, name: string, domain?: string): Promise<Locator> {
  await openSection(page, 'areas', 'admin', domain);
  const tree = page.getByRole('tree');
  await tree
    .getByRole('treeitem', { name: 'Expand Denmark', exact: true })
    .getByRole('button', { name: 'Expand' })
    .click();
  await tree.getByRole('button', { name, exact: true }).click();
  const editor = page.locator('app-area-editor');
  await expect(editor.getByRole('heading', { name: 'Edit area' })).toBeVisible();
  return editor;
}

/** Opens the user editor drawer on the local realm's user `editor`. */
async function openUser(page: Page): Promise<Locator> {
  await openSection(page, 'users');
  await page
    .getByRole('row', { name: /^editor / })
    .getByRole('button', { name: 'Edit user' })
    .click();
  const drawer = dialogNamed(page, 'Edit User');
  await expect(drawer.getByLabel('Username')).toHaveValue('editor');
  return drawer;
}

/** Opens the contact editor drawer on the example contact. */
async function openContact(page: Page): Promise<Locator> {
  await openSection(page, 'users');
  await page.getByRole('tab', { name: 'Contacts' }).click();
  const filter = page.getByRole('textbox', { name: 'Filter contacts' });
  await filter.fill(CONTACT);
  await filter.blur();
  const row = page.getByRole('row', { name: new RegExp(CONTACT.replace('.', '\\.')) });
  await expect(page.getByRole('row')).toHaveCount(2);
  await row.getByRole('button', { name: 'Edit contact' }).click();
  const drawer = dialogNamed(page, 'Edit Contact');
  await expect(drawer.getByLabel('E-mail')).toHaveValue(CONTACT);
  return drawer;
}

/** The row of the mailing list with the given ID. */
function mailingListRow(page: Page, id: string): Locator {
  return page.getByRole('row').filter({ has: page.getByRole('cell', { name: id, exact: true }) });
}

/** Opens the mailing-list editor drawer on a list, as a sysadmin. */
async function openMailingList(page: Page, id: string): Promise<Locator> {
  await openSection(page, 'mailing-lists', 'sysadmin');
  await mailingListRow(page, id).getByRole('button', { name: 'Edit Mailing List' }).click();
  const drawer = dialogNamed(page, 'Edit Mailing List');
  await expect(drawer.getByLabel('Mailing List ID')).toHaveValue(id);
  return drawer;
}

test.describe('admin-manual', () => {
  // ---------------------------------------------------------------- 02 Admin Page

  test('admin-manual/AdminPage.png', async ({ page }) => {
    await openSection(page, 'overview');
    await expect(page.getByRole('link', { name: /Mailing Lists/ })).toHaveCount(2);
    await shot(page);
  });

  // ---------------------------------------------------------------- 03 Charts

  // The text names the 20 rows of a page and the pager below them: at 1300 px both are in the
  // window, not below its edge.
  test.describe('in a window as tall as a page of charts', () => {
    test.use({ viewport: { width: 1280, height: 1300 } });

    test('admin-manual/ChartsPage.png', async ({ page }) => {
      await openSection(page, 'charts');
      await expect(page.getByRole('row').nth(20)).toBeInViewport({ ratio: 1 });
      await expect(pager(page)).toBeInViewport({ ratio: 1 });
      await shot(page);
    });
  });

  test('admin-manual/ChartsActionMenu.png', async ({ page }) => {
    await openSection(page, 'charts');
    const button = page.getByRole('button', { name: 'Chart actions' });
    await button.click();
    const menu = page.getByRole('menu');
    await expect(menu.getByRole('menuitem', { name: 'Import…' })).toBeVisible();
    await shot(page, { target: [button, menu] });
  });

  // The editor itself, from its toolbar down: the dialog around it has an empty, untitled header.
  test('admin-manual/ChartsEditExtentPage.png', async ({ page }) => {
    const drawer = await openChart(page);
    await drawer.getByRole('button', { name: 'Edit', exact: true }).click();
    const editor = geometryEditorDialog(page).locator('app-geometry-editor');
    await expect(editor).toBeVisible();
    await shot(page, { target: editor, pad: 0 });
  });

  test('admin-manual/ChartsEditAsText.png', async ({ page }) => {
    const drawer = await openChart(page);
    await drawer.getByRole('button', { name: 'Edit as text' }).click();
    // The dialog is photographed and left with Cancel: Save geometry is never clicked.
    const dialog = dialogNamed(page, 'Edit as text');
    await expect(dialog.getByRole('textbox', { name: 'Edit as text' })).not.toHaveValue('');
    await shot(page, { target: dialog, pad: 0 });
  });

  // The chart drawer is as tall as the window; at 880 px every field and the whole map show above
  // its footer.
  test.describe('in a taller window', () => {
    test.use({ viewport: { width: 1280, height: 880 } });

    test('admin-manual/ChartsEditPage.png', async ({ page }) => {
      const drawer = await openChart(page);
      await shot(page, { target: drawer, pad: 0 });
    });
  });

  // ---------------------------------------------------------------- 04 Areas

  // At 800 px the area editor scrolls before its origin fields; at 1220 px they and the extent map
  // show.
  test.describe('in a window as tall as the area editor', () => {
    test.use({ viewport: { width: 1280, height: 1220 } });

    test('admin-manual/AreasPage.png', async ({ page }) => {
      const editor = await openArea(page, CLOCKWISE_AREA);
      await expect(editor.getByLabel('Origin angle')).toBeVisible();
      await shot(page);
    });
  });

  test('admin-manual/AreasEditExtentPage.png', async ({ page }) => {
    const editor = await openArea(page, CLOCKWISE_AREA);
    await editor.getByRole('button', { name: 'Edit', exact: true }).click();
    const geometry = geometryEditorDialog(page).locator('app-geometry-editor');
    await expect(geometry).toBeVisible();
    await shot(page, { target: geometry, pad: 0 });
  });

  test('admin-manual/AreasActionMenu.png', async ({ page }) => {
    await openSection(page, 'areas', 'admin', FIRING_DOMAIN);
    await expect(page.getByRole('tree')).toBeVisible();
    const button = page.getByRole('button', { name: 'Area actions' });
    await button.click();
    const menu = page.getByRole('menu');
    await expect(
      menu.getByRole('menuitem', { name: 'Generate firing area messages…' }),
    ).toBeVisible();
    await shot(page, { target: [button, menu] });
  });

  test('admin-manual/AreasFiringGenerateDialog.png', async ({ page }) => {
    await openSection(page, 'areas', 'admin', FIRING_DOMAIN);
    await expect(page.getByRole('tree')).toBeVisible();
    await page.getByRole('button', { name: 'Area actions' }).click();
    await page.getByRole('menuitem', { name: 'Generate firing area messages…' }).click();
    // The dialog is photographed and closed: no message is generated.
    const dialog = dialogNamed(page, 'Generate Firing Area Messages');
    await expect(dialog.getByLabel('Message series')).toBeVisible();
    await shot(page, { target: dialog, pad: 0 });
  });

  // ---------------------------------------------------------------- 06 Sources

  // The whole first page of sources and the pager below it, which the text names.
  test.describe('in a window as tall as a page of sources', () => {
    test.use({ viewport: { width: 1280, height: 1150 } });

    test('admin-manual/SourcesPage.png', async ({ page }) => {
      await openSection(page, 'sources');
      await expect(page.getByRole('row').nth(20)).toBeInViewport({ ratio: 1 });
      await expect(pager(page)).toBeInViewport({ ratio: 1 });
      await shot(page);
    });
  });

  // A drawer is as tall as the window; a short form is taken in a window just tall enough for it,
  // so the picture is not mostly empty drawer.
  test.describe('in a short window', () => {
    test.use({ viewport: { width: 1280, height: 440 } });

    test('admin-manual/SourcesEditPage.png', async ({ page }) => {
      await openSection(page, 'sources');
      await page
        .getByRole('row', { name: SOURCE_ROW })
        .getByRole('button', { name: 'Edit Source' })
        .click();
      const drawer = dialogNamed(page, 'Edit Source');
      await expect(drawer.getByRole('checkbox', { name: 'Active' })).toBeVisible();
      await shot(page, { target: drawer, pad: 0 });
    });

    test('admin-manual/ContactDetailsPanel.png', async ({ page }) => {
      const drawer = await openContact(page);
      await shot(page, { target: drawer, pad: 0 });
    });
  });

  test.describe('in a window as tall as the user form', () => {
    test.use({ viewport: { width: 1280, height: 580 } });

    test('admin-manual/UserDetailsPanel.png', async ({ page }) => {
      const drawer = await openUser(page);
      await shot(page, { target: drawer, pad: 0 });
    });

    test('admin-manual/UserGroupsPanel.png', async ({ page }) => {
      const drawer = await openUser(page);
      await drawer.getByRole('tab', { name: 'Groups' }).click();
      await expect(drawer.getByText('/Editors')).toBeVisible();
      await expect(drawer.getByRole('button', { name: 'MAS duty' })).toBeVisible();
      await shot(page, { target: drawer, pad: 0 });
    });
  });

  // ---------------------------------------------------------------- 07 Users & Contacts

  test('admin-manual/UsersPage.png', async ({ page }) => {
    await openSection(page, 'users');
    await expect(page.getByRole('row', { name: /^editor / })).toBeVisible();
    await shot(page);
  });

  test('admin-manual/UserMailingListsPanel.png', async ({ page }) => {
    const drawer = await openUser(page);
    await drawer.getByRole('tab', { name: 'Mailing lists' }).click();
    // The user is a recipient of two lists (data/seed-examples.mjs): those rows offer Leave.
    await expect(drawer.getByRole('button', { name: 'Leave' })).toHaveCount(2);
    await expect(drawer.getByRole('button', { name: 'Join' }).first()).toBeVisible();
    await shot(page, { target: drawer, pad: 0 });
  });

  // The whole first page of contacts, with the count line and the pager below it, which the text
  // names.
  test.describe('in a window as tall as a page of contacts', () => {
    test.use({ viewport: { width: 1280, height: 1200 } });

    test('admin-manual/ContactsPanel.png', async ({ page }) => {
      await openSection(page, 'users');
      await page.getByRole('tab', { name: 'Contacts' }).click();
      await expect(page.getByText(/Displaying 20 of \d+ contacts/)).toBeInViewport({ ratio: 1 });
      await expect(pager(page)).toBeInViewport({ ratio: 1 });
      await shot(page);
    });
  });

  test('admin-manual/ContactMailingListsPanel.png', async ({ page }) => {
    const drawer = await openContact(page);
    await drawer.getByRole('tab', { name: 'Mailing lists' }).click();
    await expect(drawer.getByRole('button', { name: 'Leave' }).first()).toBeVisible();
    await shot(page, { target: drawer, pad: 0 });
  });

  // ---------------------------------------------------------------- 08 Mailing Lists

  test('admin-manual/MailingListsPage.png', async ({ page }) => {
    await openSection(page, 'mailing-lists', 'sysadmin');
    await expect(mailingListRow(page, SCHEDULED_LIST)).toBeVisible();
    await shot(page);
  });

  test.describe('in a window as tall as the recipients drawer', () => {
    test.use({ viewport: { width: 1280, height: 660 } });

    test('admin-manual/MailingListRecipients.png', async ({ page }) => {
      // Opened as an admin, who manages the recipients; nothing is moved or saved.
      await openSection(page, 'mailing-lists');
      await mailingListRow(page, STATUS_CHANGE_LIST)
        .getByRole('button', { name: 'Manage recipients' })
        .click();
      const drawer = dialogNamed(page, 'Manage Recipients');
      await expect(drawer.getByRole('button', { name: 'Remove' }).first()).toBeVisible();
      await shot(page, { target: drawer, pad: 0 });
    });
  });

  test.describe('in a window as tall as the mailing list form', () => {
    test.use({ viewport: { width: 1280, height: 580 } });

    test('admin-manual/MailingListEditPage.png', async ({ page }) => {
      const drawer = await openMailingList(page, STATUS_CHANGE_LIST);
      await shot(page, { target: drawer, pad: 0 });
    });
  });

  test('admin-manual/MailingListScheduledTrigger.png', async ({ page }) => {
    const drawer = await openMailingList(page, SCHEDULED_LIST);
    const card = drawer.locator('app-trigger-card').first();
    await card.getByRole('button', { name: 'Show details' }).click();
    await expect(card.getByLabel('Time Zone')).toBeVisible();
    await shot(page, { target: card });
  });

  test('admin-manual/MailingListStatusChangeTrigger.png', async ({ page }) => {
    const drawer = await openMailingList(page, STATUS_CHANGE_LIST);
    const card = drawer.locator('app-trigger-card').nth(1);
    await card.getByRole('button', { name: 'Show details' }).click();
    await expect(card.getByText('Statuses')).toBeVisible();
    await shot(page, { target: card });
  });

  // The dialog lists one mail per recipient (20 for the example list) above the selected mail; at
  // 1550 px it shows whole, the test message and the mail included.
  test.describe('in a window as tall as the test dialog', () => {
    test.use({ viewport: { width: 1280, height: 1550 } });

    test('admin-manual/MailingListTest.png', async ({ page }) => {
      // The status change trigger (Published) of the list, tested with one warning: the text
      // describes picking the Test Message. The scheduled trigger's mail lists every warning in
      // force, among them one whose position the mail template prints as 56° 60.0'N.
      const drawer = await openMailingList(page, STATUS_CHANGE_LIST);
      await drawer
        .locator('app-trigger-card')
        .nth(1)
        .getByRole('button', { name: 'Test trigger' })
        .click();
      // The test builds the mails and sends none.
      const dialog = dialogNamed(page, 'Test Trigger Execution');
      // Part of the number: the field searches from three characters and offers the message.
      await dialog.getByRole('combobox').pressSequentially(TEST_MESSAGE.slice(3), { delay: 30 });
      await page
        .getByRole('option', { name: new RegExp(TEST_MESSAGE) })
        .first()
        .click();
      await expect(dialog.getByRole('button', { name: TEST_MESSAGE })).toBeVisible();
      await dialog.getByRole('button', { name: 'Execute' }).click();
      const rows = dialog.locator('tbody tr');
      await expect(rows.first()).toBeVisible();
      await rows.first().click();
      await expect(dialog.locator('app-sandboxed-html iframe')).toBeVisible();
      await shot(page, { target: dialog, pad: 0 });
    });
  });
});
