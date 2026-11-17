import { expect, test } from './auth/fixtures';

// App-breadth e2e: the cross-cutting surfaces that no single feature spec owns.
// Run with `npx playwright test` while a dev server is up at http://localhost:4200
// and the Keycloak test credentials are exported (see e2e/auth/README.md — the authed
// blocks fall back to the base pair when a per-role pair is absent). A plain `npm start`
// serves the base product (e2e/auth/README.md).
//
// Coverage:
//   1. Anonymous home '/': the landing map, the welcome panel + login button, and
//      the ABSENCE of the deploy-skew "refresh browser" banner (never shows on the
//      unhashed dev bundle — no build hash means no web/server skew to detect).
//   2. Hash deep-link redirect: the `/#/message/{shortId}` link that older mails and
//      tweets carry forwards to `/messages/table?messageId={shortId}`. The shortId is
//      picked live from the anonymous public API so the test never pins a message that
//      may be deleted.
//   3. Domain switching via the header domain menu: the indicator flips to the picked
//      domain and back (session-scoped, so no persistent mutation).
//   4. Messages Action-menu dialogs OPEN (never submit): Compare (needs 2 selected),
//      Print, and Mail (opened, asserted, closed — no mail is ever sent).
//   5. Import/Export Action-menu entries are gated per role (admin sees Import;
//      a plain logged-in user sees Export but not Import; anonymous sees neither).
//
// Read-only against the shared dev backend: the dialogs are only opened and cancelled,
// the domain switch is reverted, and no Send/Print submit is ever clicked.

const HOME = /localhost:4200\/(?:$|[?#])/;

// The Action menu trigger renders its label from the runtime message dictionary
// ("Action"); the menu items likewise ("Print...", "Compare...", etc.). These are
// the strings the running app actually paints, verified against the live backend.
const ACTION_TRIGGER = 'Action';

/** Open the messages Action menu (desktop toolbar). Assumes an md+ viewport. */
async function openActionMenu(page: import('@playwright/test').Page): Promise<void> {
  await page.getByRole('button', { name: ACTION_TRIGGER, exact: true }).click();
}

/** Toggle the first `count` message-selection stars in the current list view. */
async function selectMessages(page: import('@playwright/test').Page, count: number): Promise<void> {
  const toggles = page.locator('app-message-selection-toggle label');
  await expect(toggles.first()).toBeVisible({ timeout: 20000 });
  for (let i = 0; i < count; i++) {
    await toggles.nth(i).click();
  }
}

/** Close the single open app-dialog via its footer Close (header ✕ shares the label). */
async function closeDialogViaFooter(dialog: import('@playwright/test').Locator): Promise<void> {
  await dialog.locator('.dialog-foot').getByRole('button', { name: 'Close', exact: true }).click();
  await expect(dialog).toBeHidden();
}

test.describe('home landing page (anonymous)', () => {
  test('renders the map, the welcome panel + login button, and no version banner', async ({
    page,
  }) => {
    await page.goto('/');
    await expect(page).toHaveURL(HOME);

    // The full-bleed landing map mounts (OpenLayers renders a <canvas> into it).
    await expect(page.locator('app-ol-map')).toBeVisible({ timeout: 20000 });
    await expect(page.locator('app-ol-map canvas').first()).toBeVisible();

    // The welcome panel: heading + the NID-acronym subtitle.
    await expect(page.getByRole('heading', { name: 'Welcome to the', level: 1 })).toBeVisible();
    await expect(
      page.getByRole('heading', { name: 'Nautical Information Directory', level: 2 }),
    ).toBeVisible();

    // Anonymous ⇒ the welcome panel offers a Login button (scoped to <main> so it is
    // not confused with the header account control, which also reads "Login" anonymously).
    await expect(
      page.locator('main').getByRole('button', { name: 'Login', exact: true }),
    ).toBeVisible();

    // The deploy-skew banner never shows on the dev bundle (unhashed → no web/server
    // skew). Its "refresh browser" copy must be absent.
    await expect(page.getByText(/refresh web-browser/i)).toHaveCount(0);
  });
});

test.describe('hash deep-link redirect (anonymous)', () => {
  test('/#/message/{shortId} forwards to /messages/table?messageId={shortId}', async ({
    page,
    request,
  }) => {
    // Pick a real, currently-published shortId from the anonymous public API of the backend
    // the served application talks to (through baseURL), so the deep link points at a
    // message that actually exists right now.
    const res = await request.get('/rest/public/v1/messages?lang=en&limit=5');
    expect(res.ok()).toBeTruthy();
    const body = (await res.json()) as readonly { shortId?: string }[];
    const shortId = body.find((m) => !!m.shortId)?.shortId;
    test.skip(!shortId, 'no published message with a shortId on the backend right now');

    // The hash URL parses as path `/` + fragment `/message/{shortId}`; the
    // home-route guard rewrites it to the current shareable-message surface.
    await page.goto(`/#/message/${shortId}`);
    await page.waitForURL(
      (url) =>
        url.pathname.endsWith('/messages/table') && url.searchParams.get('messageId') === shortId,
      { timeout: 20000 },
    );
  });
});

test.describe('header domain switching (authed)', () => {
  test('switching the session domain updates the header indicator, then reverts', async ({
    sysadminPage,
  }) => {
    const page = sysadminPage;
    await page.goto('/messages/table');

    // Fresh session ⇒ the initial domain is the first catalog entry (NW). The header
    // domain trigger is a button whose accessible name is the active domain's display
    // name (account / language / home controls never read "NW"/"NM").
    const header = page.locator('header');
    const nwTrigger = header.getByRole('button', { name: 'NW', exact: true });
    await expect(nwTrigger).toBeVisible({ timeout: 20000 });

    // Switch NW → NM via the domain menu; the trigger relabels to the picked domain.
    await nwTrigger.click();
    await page.getByRole('menuitem', { name: 'NM', exact: true }).click();
    await expect(header.getByRole('button', { name: 'NM', exact: true })).toBeVisible();
    await expect(header.getByRole('button', { name: 'NW', exact: true })).toHaveCount(0);

    // Switch back NM → NW so the shared session is left as we found it.
    await header.getByRole('button', { name: 'NM', exact: true }).click();
    await page.getByRole('menuitem', { name: 'NW', exact: true }).click();
    await expect(header.getByRole('button', { name: 'NW', exact: true })).toBeVisible();
  });
});

test.describe('messages Action-menu dialogs (authed)', () => {
  test('Compare opens the two-message comparison dialog (2 selected)', async ({ sysadminPage }) => {
    const page = sysadminPage;
    await page.goto('/messages/table');
    await selectMessages(page, 2);

    await openActionMenu(page);
    await page.getByRole('menuitem', { name: 'Compare...', exact: true }).click();

    // Compare projects its own header (title + help anchor). It is still required to carry an
    // accessible name — the panel falls back to naming itself from the `header` string — and
    // this assertion is what proves that, so don't relax it to a bare getByRole('dialog').
    const dialog = page.getByRole('dialog', { name: 'Compare messages' });
    await expect(dialog).toBeVisible();
    // Proof it is the comparison surface: the Swap affordance is unique to it.
    await expect(dialog.getByRole('button', { name: 'Swap messages' })).toBeVisible();
    await closeDialogViaFooter(dialog);
  });

  test('Print opens the print dialog (no submit)', async ({ sysadminPage }) => {
    const page = sysadminPage;
    await page.goto('/messages/table');
    await expect(page.locator('app-message-selection-toggle label').first()).toBeVisible({
      timeout: 20000,
    });

    await openActionMenu(page);
    await page.getByRole('menuitem', { name: 'Print...', exact: true }).click();

    // Located without a name because the print dialog's title is count-dependent
    // ("Print N messages"), not because it lacks one — every dialog carries an accessible name.
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText(/Print \d+ messages/)).toBeVisible();
    await expect(
      dialog.locator('.dialog-foot').getByRole('button', { name: 'Print', exact: true }),
    ).toBeVisible();
    // Only ever cancel — clicking Print would navigate the browser to the PDF endpoint.
    await closeDialogViaFooter(dialog);
  });

  test('Mail opens the compose dialog and is cancelled without sending (2 selected)', async ({
    sysadminPage,
  }) => {
    const page = sysadminPage;
    await page.goto('/messages/table');
    await selectMessages(page, 2);

    await openActionMenu(page);
    await page.getByRole('menuitem', { name: 'E-mail selection...', exact: true }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText(/Mail \d+ selected messages/)).toBeVisible();
    // The Send action exists but is NEVER clicked — sending would fire a real mail.
    await expect(
      dialog.locator('.dialog-foot').getByRole('button', { name: 'Send Mail' }),
    ).toBeVisible();
    await closeDialogViaFooter(dialog);
  });
});

test.describe('messages Action-menu import/export gating', () => {
  test('an admin sees both Import and Export', async ({ adminPage }) => {
    await adminPage.goto('/messages/table');
    await openActionMenu(adminPage);
    await expect(adminPage.getByRole('menuitem', { name: 'Import...', exact: true })).toBeVisible();
    await expect(adminPage.getByRole('menuitem', { name: 'Export...', exact: true })).toBeVisible();
  });

  test('a plain user sees Export but NOT Import', async ({ userPage }) => {
    await userPage.goto('/messages/table');
    await openActionMenu(userPage);
    await expect(userPage.getByRole('menuitem', { name: 'Export...', exact: true })).toBeVisible();
    await expect(userPage.getByRole('menuitem', { name: 'Import...', exact: true })).toHaveCount(0);
  });

  test('an anonymous visitor sees neither Import nor Export (Print stays available)', async ({
    page,
  }) => {
    await page.goto('/messages/table');
    await openActionMenu(page);
    await expect(page.getByRole('menuitem', { name: 'Print...', exact: true })).toBeVisible();
    await expect(page.getByRole('menuitem', { name: 'Export...', exact: true })).toHaveCount(0);
    await expect(page.getByRole('menuitem', { name: 'Import...', exact: true })).toHaveCount(0);
  });
});
