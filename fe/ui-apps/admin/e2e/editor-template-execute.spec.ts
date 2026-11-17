import { type Locator, type Page } from '@playwright/test';

import { expect, test } from './auth/fixtures';

// Editor "create from template" happy path (authed via the editor fixture, which falls back to the
// base pair). Backend-dependent — it drives the real template-execution pipeline end to end against
// a live backend at http://localhost:4200:
//
//   /editor  →  "New NW from Template"  →  pick a template in the search popover
//            →  the execution view (base fields + areas + the NEW per-language vicinity inputs)
//            →  Execute  →  the rendered draft opens in the editor  →  Save (persists a real DRAFT)
//            →  (best-effort) re-run the template on the saved draft
//            →  DELETE the draft from the Status page (self-cleaning).
//
// The per-language vicinity inputs under the areas field are the feature this spec exists to prove:
// they render on the execution view, and their value flows through Execute into the rendered draft
// (its auto-title carries the vicinity text).
//
// Mutation discipline: the ONLY persisted entity is the draft this spec creates. Its vicinity text
// is timestamped so parallel/interrupted runs never collide, the draft is deleted at the end, and a
// finally-block safety net deletes it even when an assertion fails mid-flight. The template catalog
// and everything else are read-only — a domain that exposes no executable templates skips the test
// rather than failing.

/** The template this spec executes — a standard NW template of the base product's bootstrap that declares the areas std field
 *  (so the per-language vicinity inputs render). Skips if the catalog no longer offers it. */
const TEMPLATE_NAME = 'Light unlit';

/** Ensure a collapsible edit-form field row is expanded (its head carries `aria-expanded`). */
async function expandRow(head: Locator): Promise<void> {
  if ((await head.getAttribute('aria-expanded')) !== 'true') {
    await head.click();
  }
}

/**
 * Delete a persisted draft via the Status page's "Delete draft…" action + destructive confirm.
 * Strict when `strict` (asserts the affordance appears and the message ends up DELETED); best-effort
 * otherwise (the finally safety net — never masks the original failure).
 */
async function deleteDraft(page: Page, id: string, strict: boolean): Promise<void> {
  await page.goto(`/editor/status/${id}`);
  const del = page.getByRole('button', { name: /delete draft/i });
  if (strict) {
    await expect(del).toBeVisible({ timeout: 15000 });
  } else if (
    !(await del
      .first()
      .isVisible()
      .catch(() => false))
  ) {
    return; // already gone (deleted / not a draft) — nothing to clean up
  }
  await del.first().click();
  const confirm = page.getByRole('alertdialog');
  await expect(confirm).toBeVisible({ timeout: 10000 });
  // Two buttons: reject (Cancel) then the destructive accept — the accept is last.
  await confirm.getByRole('button').last().click();
  // The delete transition reloads the now-DELETED message: the Delete affordance disappears.
  await expect(del).toHaveCount(0, { timeout: 15000 });
}

test.describe('editor create-from-template', () => {
  test('New NW from Template → fill vicinity → execute → draft opens → save → delete (self-cleaning)', async ({
    editorPage,
  }) => {
    // The pipeline spans two navigations, a save round-trip, an optional re-run and a delete —
    // comfortably past the 30 s default.
    test.setTimeout(120000);
    const page = editorPage;
    // Unique per run so parallel/interrupted runs never collide, and so the draft is identifiable.
    const marker = `E2E-tmpl-${Date.now()}`;
    let savedId: string | null = null;

    try {
      // ── Start screen ──────────────────────────────────────────────────────────
      await page.goto('/editor');
      await expect(page.getByRole('heading', { name: /create a message/i })).toBeVisible({
        timeout: 15000,
      });

      // The "from a template" affordance is domain-gated (the domain's `templates` capability). Skip cleanly
      // where the active domain exposes none rather than failing.
      const fromTemplate = page.getByRole('button', { name: /from template/i }).first();
      const hasTemplates = await fromTemplate.isVisible().catch(() => false);
      test.skip(!hasTemplates, 'active domain exposes no executable templates');

      // ── Template-search popover: pick the template ────────────────────────────
      await fromTemplate.click();
      const overlay = page.locator('.cdk-overlay-container');
      // The field is an ARIA combobox — it drives the listbox via aria-activedescendant — and an
      // explicit role replaces the input's implicit `textbox` one, so getByRole('textbox') finds
      // nothing here.
      const search = overlay.getByRole('combobox');
      await expect(search).toBeVisible({ timeout: 10000 });
      await search.fill(TEMPLATE_NAME);
      // The <li> IS the option: a nested button would sit between the listbox and its options and
      // break that relationship, so there is no `li button` to match.
      const result = overlay.getByRole('option').filter({ hasText: TEMPLATE_NAME });
      const found = await result
        .first()
        .waitFor({ state: 'visible', timeout: 10000 })
        .then(() => true)
        .catch(() => false);
      test.skip(!found, `template "${TEMPLATE_NAME}" not in the domain catalog`);
      await result.first().click();

      // ── Execution view ────────────────────────────────────────────────────────
      await page.waitForURL(/\/editor\/execute-template(?:[?#]|$)/, { timeout: 15000 });

      // The chosen template shows as a chip, and the Base fields card renders the std fields —
      // including the areas picker with its per-language vicinity inputs (the NEW surface).
      await expect(page.locator('app-template-execution-view')).toContainText(TEMPLATE_NAME);
      const baseCard = page.locator('app-card').filter({ hasText: /base fields/i });
      await expect(baseCard).toBeVisible();
      await expect(baseCard.locator('app-areas-field')).toBeVisible();

      const vicinity = baseCard.locator('app-localized-inputs input');
      await expect(vicinity.first()).toBeVisible();
      const langCount = await vicinity.count();
      expect(langCount).toBeGreaterThan(0);
      // Fill a per-language vicinity for every configured model language (N-language, never two).
      for (let i = 0; i < langCount; i++) {
        await vicinity.nth(i).click();
        await vicinity.nth(i).fill(`${marker} ${i}`);
      }

      // ── Execute → the rendered draft hands off to the editor ──────────────────
      const execute = page.getByRole('button', { name: /^execute$/i });
      await expect(execute).toBeEnabled();
      await execute.click();

      // A fresh (never-saved) execution lands born-dirty on the bare template edit route.
      await page.waitForURL(/\/editor\/edit(?:[/?#]|$)/, { timeout: 20000 });
      await expect(page.locator('app-editor-action-menu')).toHaveCount(1);
      await expect(page.locator('app-section-panel')).toHaveCount(3);

      // The vicinity flowed through Execute into the rendered message: its auto-title carries the
      // per-language vicinity text (proves the NEW field is wired end to end, not just displayed).
      await expect(page.locator('app-editor-view-header')).toContainText(marker);

      // Born dirty and not stored: the header reads "unsaved" and Save is enabled before any edit.
      const save = page.getByRole('button', { name: /save/i });
      await expect(page.getByText('Unsaved changes')).toBeVisible();
      await expect(save).toBeEnabled();

      // ── Fill the one remaining required std field (Type) ──────────────────────
      // Type is required for a savable message (the save's own check would refuse without it); it
      // also auto-deduces the sole permitting series. The message stays unsaved.
      const typeHead = page.locator('app-type-field [aria-expanded]');
      await expandRow(typeHead);
      const typeGroup = page.locator('app-type-field [role="group"]');
      await expect(typeGroup).toBeVisible();
      await typeGroup.locator('button:not([disabled])').first().click();
      await expect(page.getByText('Unsaved changes')).toBeVisible();
      await expect(save).toBeEnabled();

      // ── Save → persists a real DRAFT and lands on the canonical edit URL ──────
      await save.click();
      await page.waitForURL(/\/editor\/edit\/[^/]+/, { timeout: 20000 });
      savedId = page.url().match(/\/editor\/edit\/([^/?#]+)/)?.[1] ?? null;
      expect(savedId).toBeTruthy();
      await expect(page.getByText('All changes saved')).toBeVisible({ timeout: 15000 });

      // ── Re-run path (best-effort): re-execute the template on the saved draft ──
      // The in-editor launcher lives in the Categories field row ("Run template…"); it seeds a clone
      // of the working message and re-enters the execution view with the template already chosen.
      const catHead = page.locator('app-categories-field-row [aria-expanded]');
      if ((await catHead.count()) > 0) {
        await expandRow(catHead);
        const runTemplate = page.getByRole('button', { name: /run template/i });
        if (
          await runTemplate
            .first()
            .isVisible()
            .catch(() => false)
        ) {
          await runTemplate.first().click();
          await page.waitForURL(/\/editor\/execute-template(?:[?#]|$)/, { timeout: 15000 });
          await expect(page.locator('app-template-execution-view')).toContainText(TEMPLATE_NAME);
          const reExecute = page.getByRole('button', { name: /^execute$/i });
          await expect(reExecute).toBeEnabled();
          await reExecute.click();
          // A re-run on a SAVED message applies in place and returns to the same edit URL. The
          // template's text is only in the browser until saved: the header reads "unsaved" and
          // Save is enabled. Save it, so the delete below finds its buttons enabled.
          await page.waitForURL(new RegExp(`/editor/edit/${savedId}`), { timeout: 20000 });
          await expect(page.getByText('Unsaved changes')).toBeVisible({ timeout: 15000 });
          await expect(save).toBeEnabled();
          await save.click();
          await expect(page.getByText('All changes saved')).toBeVisible({ timeout: 15000 });
        }
      }

      // ── Delete the draft (cleanup) ────────────────────────────────────────────
      await deleteDraft(page, savedId!, true);
      await expect(page.locator('app-status-action-panel')).toContainText(/deleted/i);
      savedId = null; // cleaned — the finally net has nothing to do
    } finally {
      // Safety net (runs on failure too): delete the draft if it survived. Best-effort and
      // non-asserting so it never masks the original test failure.
      if (savedId) {
        await deleteDraft(page, savedId, false).catch(() => undefined);
      }
    }
  });
});
