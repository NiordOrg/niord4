import { scanPage, settle } from './a11y/axe';
import { expect, test, type Page } from './auth/fixtures';
import { AdminApi } from './scenarios/support/api';
import { expectSampleDeployment } from './scenarios/support/env';
import { keepOffline } from './scenarios/support/offline';

/*
 * The administration app's accessibility scan: axe-core over every route and the most used
 * overlays, against the WCAG 2.1 A and AA rules, each page held to the known list in
 * a11y/known-violations.json (a11y/axe.ts says how).
 *
 * Read-only: pages are opened and left, dialogs and menus opened and closed, nothing is saved. The
 * known list is the sample deployment's, so the spec runs only with E2E_A11Y=1 and only against the
 * sample (`./start-local.sh --sample`), and skips by name otherwise. Light mode and English only:
 * the base product has no dark theme and ships one UI language; a country runs its own languages
 * and brand tokens in its own workspace.
 *
 *   E2E_A11Y=1 E2E_BASE_URL=http://localhost:<admin port> KEYCLOAK_URL=http://localhost:8080/auth \
 *   KEYCLOAK_TEST_USERNAME=sysadmin KEYCLOAK_TEST_PASSWORD=... npx playwright test a11y-routes.spec.ts
 *
 * One test per page, so the report has a line per page. The pages reach no host but this machine
 * (scenarios/support/offline.ts): a map tile gets a one-pixel image.
 */

// The slice of Node's globals the file needs: the e2e tsconfig loads no Node types.
declare const process: { readonly env: Record<string, string | undefined> };

const A11Y_ON = process.env['E2E_A11Y']?.trim() === '1';

const SKIP_REASON =
  'E2E_A11Y is not set: the accessibility scan holds the sample deployment to its list of known ' +
  'violations and runs only there (fe/docs/testing.md)';

/** The sample's domains: its warnings, its notices and publications, its firing areas. */
const NW_DOMAIN = 'niord-nw';
const NM_DOMAIN = 'niord-nm';
const FA_DOMAIN = 'niord-fa';

/** The sections of the two admin areas, as `ADMIN_AREA` and `SYSADMIN_AREA` in
 *  src/app/features/admin/section-descriptors.ts list them (written out: the e2e tsconfig does
 *  not compile the application). */
const ADMIN_SECTIONS = [
  'charts',
  'areas',
  'publications',
  'publication-categories',
  'sources',
  'users',
  'mailing-lists',
];
const SYSADMIN_SECTIONS = [
  'series',
  'domains',
  'promulgation',
  'schedules',
  'dictionaries',
  'param-types',
  'script-resources',
  'categories',
  'reports',
  'mails',
  'niord-integration',
  'secom',
  'settings',
  'batch',
];

test.skip(!A11Y_ON, SKIP_REASON);
// A page that polls waits out network idle before it is scanned.
test.describe.configure({ timeout: 90_000 });

test.beforeAll(async ({ playwright }, testInfo) => {
  const baseURL = String(testInfo.project.use.baseURL ?? '');
  const request = await playwright.request.newContext({ baseURL });
  try {
    await expectSampleDeployment(request, baseURL);
  } finally {
    await request.dispose();
  }
});

test.beforeEach(async ({ page, baseURL }) => {
  await keepOffline(page, baseURL);
});

/** Opens a page in the session domain given and waits for its main landmark and for it to settle. */
async function open(page: Page, path: string, domain = NW_DOMAIN): Promise<void> {
  await page.addInitScript((id) => localStorage.setItem('niord.sessionDomainId', id), domain);
  await page.goto(path);
  // The admin areas nest the section's landmark in the shell's: the first is the page's.
  await expect(page.locator('main').first()).toBeVisible({ timeout: 20_000 });
  await settle(page);
}

/** Opens a page and scans it. */
async function openAndScan(page: Page, path: string, label: string, domain = NW_DOMAIN) {
  await open(page, path, domain);
  await scanPage(page, label);
}

/** The Actions menu of the messages views. */
function actionsTrigger(page: Page) {
  return page.locator('main').getByRole('button', { name: /^actions?$/i });
}

/** The id of a published warning of the sample, taken live, and its short id. */
async function publishedWarning(page: Page): Promise<{ id: string; shortId: string }> {
  const api = new AdminApi(page, NW_DOMAIN);
  const found = await api.get<{ data: { id: string; shortId?: string }[] }>(
    '/rest/messages/search',
    { lang: 'en', mainType: 'NW', status: 'PUBLISHED', maxSize: 1 },
  );
  const message = found.data[0];
  expect(message, 'the sample holds a published warning').toBeDefined();
  return { id: message.id, shortId: message.shortId ?? message.id };
}

test.describe('anonymous', () => {
  test('/ (the landing page)', async ({ page }) => {
    await openAndScan(page, '/', 'anonymous: /');
  });

  test('/messages/table', async ({ page }) => {
    await openAndScan(page, '/messages/table', 'anonymous: /messages/table');
  });
});

test.describe('messages, as sysadmin', () => {
  for (const view of ['table', 'grid', 'details', 'map', 'selected']) {
    test(`/messages/${view}`, async ({ sysadminPage: page }) => {
      await openAndScan(page, `/messages/${view}`, `/messages/${view}`);
    });
  }

  test('/messages/table, a message opened in the drawer', async ({ sysadminPage: page }) => {
    const { shortId } = await publishedWarning(page);
    await page.goto(`/messages/table?messageId=${encodeURIComponent(shortId)}`);
    const drawer = page.getByRole('dialog').first();
    await expect(drawer).toBeVisible({ timeout: 20_000 });
    await scanPage(page, '/messages/table?messageId=<id>');
  });

  test('/messages/table, the Action menu open', async ({ sysadminPage: page }) => {
    await open(page, '/messages/table');
    await actionsTrigger(page).click();
    const menu = page.getByRole('menu');
    await expect(menu).toBeVisible();
    await scanPage(page, '/messages/table, the Action menu', { include: '[role="menu"]' });
    await page.keyboard.press('Escape');
    await expect(menu).toBeHidden();
  });

  test('/messages/table, the Print dialog open', async ({ sysadminPage: page }) => {
    await open(page, '/messages/table');
    await actionsTrigger(page).click();
    await page.getByRole('menuitem', { name: /^print/i }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await scanPage(page, '/messages/table, the Print dialog', { include: '[role="dialog"]' });
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
  });

  test('the header, the domain menu open', async ({ sysadminPage: page }) => {
    await open(page, '/messages/table');
    await page.locator('header').getByRole('button', { name: 'NW', exact: true }).click();
    const menu = page.getByRole('menu');
    await expect(menu).toBeVisible();
    await scanPage(page, 'the header, the domain menu', { include: '[role="menu"]' });
    await page.keyboard.press('Escape');
    await expect(menu).toBeHidden();
  });

  test('the header, the language menu open', async ({ sysadminPage: page }) => {
    await open(page, '/messages/table');
    // The trigger is named after the language shown; the base product ships English alone.
    await page
      .locator('header')
      .getByRole('button', { name: /^english$/i })
      .click();
    const menu = page.getByRole('menu');
    await expect(menu).toBeVisible();
    await scanPage(page, 'the header, the language menu', { include: '[role="menu"]' });
    await page.keyboard.press('Escape');
    await expect(menu).toBeHidden();
  });
});

test.describe('schedule, as sysadmin', () => {
  // The Schedule page shows a schedule only in niord-fa, and only once the firing-schedule
  // scenario has made one; on a sample without it the page's empty state is what is scanned.
  test('/schedule (niord-fa)', async ({ sysadminPage: page }) => {
    await openAndScan(page, '/schedule', '/schedule', FA_DOMAIN);
  });
});

test.describe('editor, as sysadmin', () => {
  test('/editor (the Start screen)', async ({ sysadminPage: page }) => {
    await openAndScan(page, '/editor', '/editor');
  });

  test('/editor/edit, a new warning (not saved)', async ({ sysadminPage: page }) => {
    // The edit form opens from the Start screen: a bare /editor/edit has nothing to edit and
    // goes back to the Start screen.
    await open(page, '/editor');
    await page
      .locator('main')
      .getByRole('button', { name: /^new blank/i })
      .first()
      .click();
    await page.waitForURL(/\/editor\/edit(?:[?#]|$)/);
    await expect(page.getByRole('button', { name: /^save$/i })).toBeVisible();
    await scanPage(page, '/editor/edit (new)');
  });

  for (const view of ['edit', 'status', 'history', 'comments']) {
    test(`/editor/${view}/<id>`, async ({ sysadminPage: page }) => {
      const { id } = await publishedWarning(page);
      await openAndScan(page, `/editor/${view}/${encodeURIComponent(id)}`, `/editor/${view}/<id>`);
    });
  }
});

test.describe('admin, as sysadmin', () => {
  for (const section of ['overview', ...ADMIN_SECTIONS]) {
    test(`/admin/${section}`, async ({ sysadminPage: page }) => {
      await openAndScan(page, `/admin/${section}`, `/admin/${section}`);
    });
  }

  test('/admin/publications/<series>', async ({ sysadminPage: page }) => {
    // The sample has no series of its own; the weekly-publication scenario makes one in niord-nm.
    await open(page, '/admin/publications', NM_DOMAIN);
    const series = page.locator('[data-testid="series-card"] a').first();
    test.skip(
      (await series.count()) === 0,
      'no publication series in niord-nm: the weekly-publication scenario has not run on this sample',
    );
    await series.click();
    await page.waitForURL(/\/admin\/publications\/[^/?#]+/);
    await expect(page.locator('main').first()).toBeVisible();
    await scanPage(page, '/admin/publications/<series>');
  });
});

test.describe('sysadmin, as sysadmin', () => {
  for (const section of ['overview', ...SYSADMIN_SECTIONS]) {
    test(`/sysadmin/${section}`, async ({ sysadminPage: page }) => {
      await openAndScan(page, `/sysadmin/${section}`, `/sysadmin/${section}`);
    });
  }
});
