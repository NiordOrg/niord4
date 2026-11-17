import { expect, test } from './auth/fixtures';
import type { Page } from '@playwright/test';

// Full Firing Area Schedule flow (the deferred half of `schedule.spec.ts`, which
// only smoke-checks that the view shell mounts). The schedule is gated on TWO
// things: the editor role AND the session domain's `firingSchedule` flag. The
// role rides the `editorPage` fixture; the domain flag is arranged by pre-seeding
// the persisted session-domain id into localStorage BEFORE the app bootstraps
// (DomainService.bootstrap reads `niord.sessionDomainId` in its app initializer).
//
// On the shared dev backend `niord-fa` ("Firing Areas") is the firing-schedule
// domain and `niord-nw` ("NW") is a normal one; the editor test user holds the
// `editor` role on both. All firing-area rows start with NO periods on the search
// day, so the inline-editor round-trip (add an interval → save → remove → save)
// mutates one row transiently and restores it to the found (empty) state; a
// best-effort cleanup re-empties it even on assertion failure.
//
// "Update Firing Exercises" is DESTRUCTIVE on a shared backend — its accept path
// PUTs /firing-schedules/update-firing-exercises, regenerating the firing-exercise
// messages. So the confirm is only OPENED and CANCELLED here; the accept button is
// never clicked.
//
// Requires the editor Keycloak pair — see e2e/auth/README.md.

const FIRING_DOMAIN = 'niord-fa'; // firingSchedule: true
const NON_FIRING_DOMAIN = 'niord-nw'; // firingSchedule: false; editor holds the role here too
const SESSION_DOMAIN_LS_KEY = 'niord.sessionDomainId'; // must match DomainService

// Pre-seed the persisted session domain so bootstrap resolves it as the session
// domain (no domain-switcher reload needed). Registered before the first goto;
// re-applies on every navigation, so it wins over any value the app persists.
async function seedSessionDomain(page: Page, domainId: string): Promise<void> {
  await page.addInitScript(
    ({ key, id }) => {
      try {
        window.localStorage.setItem(key, id);
      } catch {
        /* storage unavailable — the test will surface it downstream */
      }
    },
    { key: SESSION_DOMAIN_LS_KEY, id: domainId },
  );
}

// Data rows only — the table interleaves top-area group-heading rows (a bare
// `<th colspan>`), which carry no inline editor.
function dataRows(page: Page) {
  return page
    .locator('table.niord-table tbody tr')
    .filter({ has: page.locator('app-time-schedule-editor') });
}

function firstDataRow(page: Page) {
  return dataRows(page).first();
}

// Best-effort restore: reload the page (drops any open editor) and, if the first
// row still carries periods, clear + save it back to empty. Non-asserting so it
// never masks the original failure.
async function ensureFirstRowEmpty(page: Page): Promise<void> {
  try {
    await page.goto('/schedule');
    const row = firstDataRow(page);
    await row.waitFor({ state: 'visible', timeout: 15000 });
    const editor = row.locator('app-time-schedule-editor');
    if ((await editor.innerText()).trim() !== '') {
      await row.getByRole('button', { name: 'Edit firing periods' }).click();
      await row.getByRole('button', { name: 'Clear' }).click();
      await row.getByRole('button', { name: 'Save' }).click();
      await expect(editor).toHaveText('', { timeout: 10000 });
    }
  } catch {
    /* best-effort cleanup */
  }
}

test.describe('firing area schedule — firing-schedule domain (niord-fa)', () => {
  test.beforeEach(async ({ editorPage }) => {
    await seedSessionDomain(editorPage, FIRING_DOMAIN);
  });

  test('the schedule loads with the toolbar, filters and rows (and the Schedule nav link shows)', async ({
    editorPage,
  }) => {
    const page = editorPage;
    await page.goto('/schedule');

    await expect(page.getByRole('heading', { name: 'Firing Area Schedule' })).toBeVisible({
      timeout: 20000,
    });
    // A firing-schedule domain ⇒ the action, the area filter and the date picker render.
    await expect(page.getByRole('button', { name: /Update Firing Exercises/ })).toBeVisible();
    await expect(page.getByRole('searchbox', { name: 'Area name' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Schedule Date' })).toBeVisible();
    // The schedule table populates (the backend has firing areas for the day).
    await expect(firstDataRow(page)).toBeVisible({ timeout: 20000 });

    // Positive nav gate: the Schedule link is present in a firing-schedule domain.
    await expect(
      page
        .getByRole('navigation', { name: 'Main navigation' })
        .getByRole('link', { name: 'Schedule', exact: true }),
    ).toBeVisible();
  });

  test('date navigation reloads the schedule (prev/next day round-trips)', async ({
    editorPage,
  }) => {
    const page = editorPage;
    await page.goto('/schedule');
    await expect(firstDataRow(page)).toBeVisible({ timeout: 20000 });

    const dateBtn = page.getByRole('button', { name: 'Schedule Date' });
    const before = (await dateBtn.innerText()).trim();
    expect(before).not.toBe('');

    await page.getByRole('button', { name: 'Next day' }).click();
    await expect(dateBtn).not.toHaveText(before);
    // The reload keeps rendering rows (all days carry the same firing areas).
    await expect(firstDataRow(page)).toBeVisible();

    await page.getByRole('button', { name: 'Previous day' }).click();
    await expect(dateBtn).toHaveText(before);
  });

  test('the area-name filter narrows the rows and clears back', async ({ editorPage }) => {
    const page = editorPage;
    await page.goto('/schedule');
    await expect(firstDataRow(page)).toBeVisible({ timeout: 20000 });

    const rows = dataRows(page);
    const initialCount = await rows.count();
    expect(initialCount).toBeGreaterThan(1);

    // Data-drive the query from the first row's area name (e.g. "Bornholm" from
    // "ES D 140 Bornholm W.") so the assertion never hardcodes backend data.
    const firstName = (await firstDataRow(page).locator('td').first().innerText()).trim();
    const token = (
      firstName.split(/\s+/).find((w) => /^[A-Za-z]{4,}$/.test(w)) ?? firstName
    ).trim();
    expect(token.length).toBeGreaterThanOrEqual(4);

    const areaFilter = page.getByRole('searchbox', { name: 'Area name' });
    await areaFilter.fill(token);
    // Debounced backend search narrows the list (the facade latches the old rows
    // until the fresh result lands, so the count only settles once it's applied).
    await expect(rows).not.toHaveCount(initialCount);
    const filteredCount = await rows.count();
    expect(filteredCount).toBeGreaterThanOrEqual(1);
    expect(filteredCount).toBeLessThan(initialCount);
    await expect(firstDataRow(page).locator('td').first()).toContainText(token);

    // Clearing restores the full list.
    await areaFilter.fill('');
    await expect(rows).toHaveCount(initialCount);
  });

  test('an editor can add a firing interval, save it, then remove it (restores the row to empty)', async ({
    editorPage,
  }) => {
    const page = editorPage;
    await page.goto('/schedule');

    const row = firstDataRow(page);
    await expect(row).toBeVisible({ timeout: 20000 });
    const editor = row.locator('app-time-schedule-editor');

    // Guard: only mutate a row that starts empty, so the round-trip restores it
    // EXACTLY (never clobber a populated firing schedule on the shared backend).
    const initial = (await editor.innerText()).trim();
    expect(
      initial,
      'target row must start with no firing periods so the test restores it exactly',
    ).toBe('');

    try {
      // ADD — enter the inline editor, set a "From" time (00:00→picked hour; the
      // missing "To" normalises to 23:59 on save), and save.
      await row.getByRole('button', { name: 'Edit firing periods' }).click();
      const fromField = row.getByLabel('From');
      await expect(fromField).toBeVisible();
      await fromField.click();
      // The time popover portals to the overlay container (document-level).
      await page.getByRole('button', { name: '10 Hours', exact: true }).click();
      await page.keyboard.press('Escape'); // close the picker popover
      await expect(fromField).toContainText('10');

      await row.getByRole('button', { name: 'Save' }).click();
      // Back to view mode with the saved period shown.
      await expect(row.getByRole('button', { name: 'Edit firing periods' })).toBeVisible();
      await expect(editor).toContainText('10');

      // REMOVE — re-enter, delete the sole interval, save the now-empty schedule.
      await row.getByRole('button', { name: 'Edit firing periods' }).click();
      await expect(row.getByLabel('From')).toBeVisible();
      await row.getByRole('button', { name: 'Remove' }).click();
      await expect(row.getByLabel('From')).toHaveCount(0);
      await row.getByRole('button', { name: 'Save' }).click();

      await expect(row.getByRole('button', { name: 'Edit firing periods' })).toBeVisible();
      await expect(editor).toHaveText(initial); // restored to the found (empty) state
    } finally {
      await ensureFirstRowEmpty(page);
    }
  });

  test('the "Update Firing Exercises" action opens a confirm which we cancel (never accepted — destructive on a shared backend)', async ({
    editorPage,
  }) => {
    const page = editorPage;
    await page.goto('/schedule');

    await page.getByRole('button', { name: /Update Firing Exercises/ }).click();

    const confirm = page.getByRole('alertdialog');
    await expect(confirm).toBeVisible();
    await expect(confirm.getByRole('heading', { name: 'Update Firing Exercises' })).toBeVisible();
    await expect(confirm).toContainText('automatically updated');

    // Cancel — the accept ("Yes") would regenerate the firing-exercise messages
    // on the shared backend, so it is deliberately never clicked. Scope to the
    // footer to avoid the header ✕ (which shares the "No" accessible name).
    await confirm.locator('.confirm-foot').getByRole('button', { name: 'No', exact: true }).click();
    await expect(confirm).toBeHidden();
  });
});

test.describe('firing area schedule — non-firing-schedule domain gating (niord-nw)', () => {
  test.beforeEach(async ({ editorPage }) => {
    await seedSessionDomain(editorPage, NON_FIRING_DOMAIN);
  });

  test('a domain without the firingSchedule flag hides the Schedule nav and blocks the view', async ({
    editorPage,
  }) => {
    const page = editorPage;

    // Nav gate: with an editor role but a non-firing domain, the Editor link
    // still shows while the Schedule link is gone — proving the hide is the
    // firingSchedule flag, not the (shared) editor role gate.
    await page.goto('/messages/table');
    const nav = page.getByRole('navigation', { name: 'Main navigation' });
    await expect(nav.getByRole('link', { name: 'Editor', exact: true })).toBeVisible({
      timeout: 20000,
    });
    await expect(nav.getByRole('link', { name: 'Schedule', exact: true })).toHaveCount(0);

    // View gate: direct navigation still resolves (the route is role-gated only),
    // but the view shows the "no firing schedule" empty state — no toolbar action,
    // no table, no area filter.
    await page.goto('/schedule');
    await expect(page.locator('app-schedule-view')).toBeAttached();
    await expect(page.getByText('Domain has no firing schedule')).toBeVisible({ timeout: 20000 });
    await expect(page.getByRole('button', { name: /Update Firing Exercises/ })).toHaveCount(0);
    await expect(page.locator('table.niord-table')).toHaveCount(0);
    await expect(page.getByRole('searchbox', { name: 'Area name' })).toHaveCount(0);
  });
});
