import { type Page } from '@playwright/test';

import { expect, test } from './auth/fixtures';

// Smoke/skeleton for the edit form on the live edit/:id route. Run with
// `npx playwright test e2e/editor-edit-form.spec.ts` while a dev server is up at
// http://localhost:4200 (npm start) and the Keycloak test credentials are exported
// (see e2e/auth/README.md).
//
// Backend-dependent: it opens a real recently-edited draft (discovered via the Start screen's
// drafts widget), then re-opens it on the edit route. If the domain has no draft,
// the flow tests skip — the full create→edit→save round-trip runs during manual testing.

/** Open the first recent draft, then re-navigate to the same id on the `edit` route. */
async function openFirstDraftInEditForm(page: Page): Promise<string | null> {
  await page.goto('/editor');
  // The drafts list loads asynchronously — wait for the first row with a bounded
  // timeout; a timeout means the domain genuinely has no recent draft. Target the
  // "Edit draft" action specifically: the row's title button opens a preview dialog.
  const firstDraft = page
    .locator('app-recently-edited-drafts')
    .getByRole('button', { name: 'Edit draft' })
    .first();
  const appeared = await firstDraft
    .waitFor({ state: 'visible', timeout: 7000 })
    .then(() => true)
    .catch(() => false);
  if (appeared) {
    await firstDraft.click();
  } else {
    // Fallback: the fixture user may have no recently-edited drafts — harvest any
    // DRAFT from the messages table instead (Edit in the row menu).
    await page.goto('/messages/table?status=DRAFT');
    const rowMenu = page.getByRole('button', { name: 'Message actions' }).first();
    const hasRows = await rowMenu
      .waitFor({ state: 'visible', timeout: 7000 })
      .then(() => true)
      .catch(() => false);
    if (!hasRows) {
      return null;
    }
    await rowMenu.click();
    await page.getByRole('menuitem', { name: 'Edit' }).click();
  }
  await page.waitForURL(/\/editor\/edit\/[^/]+/);
  const id = page.url().match(/\/editor\/edit\/([^/?#]+)/)?.[1];
  if (!id) {
    return null;
  }
  await page.goto(`/editor/edit/${id}`);
  await page.waitForURL(/\/editor\/edit\/[^/]+/);
  return id;
}

test.describe('editor edit form', () => {
  test('the edit route resolves under the editor shell and an unknown id surfaces the retry panel', async ({
    editorPage,
  }) => {
    await editorPage.goto('/editor/edit/does-not-exist');
    await expect(editorPage).toHaveURL(/\/editor\/edit\/[^/]+(?:[?#]|$)/);
    await expect(editorPage.locator('app-editor-action-menu')).toHaveCount(1);
    // The backend answers an unknown id with 200 + null body — the form must surface the inline
    // error + retry panel, not hang on the progress bar (regression: the flip-gate smoke defect).
    await expect(editorPage.getByText('The message could not be loaded.')).toBeVisible();
    await expect(editorPage.getByRole('button', { name: 'Retry' })).toBeVisible();
    await expect(editorPage.getByRole('progressbar')).toHaveCount(0);
  });

  test('opening a draft renders the three section panels', async ({ editorPage }) => {
    const id = await openFirstDraftInEditForm(editorPage);
    test.skip(id === null, 'no recently-edited draft available in this domain');

    await expect(editorPage.locator('app-section-panel')).toHaveCount(3);
    await expect(editorPage.getByText(/preamble/i)).toBeVisible();
  });

  test('picking a type auto-deduces the series (single-write-path adjust)', async ({
    editorPage,
  }) => {
    const id = await openFirstDraftInEditForm(editorPage);
    test.skip(id === null, 'no recently-edited draft available in this domain');

    // Expand the Type row, then pick a type from the segmented control.
    await editorPage.getByRole('button', { name: /type/i }).first().click();
    const typeButton = editorPage
      .locator('app-type-field [role="group"] button:not([disabled])')
      .first();
    if ((await typeButton.count()) > 0) {
      await typeButton.click();
      // The live preview reflects the working message (inline pane at desktop width).
      await expect(editorPage.locator('app-live-preview')).toBeVisible();
    }
  });

  test('adding a reference reveals a new reference row', async ({ editorPage }) => {
    const id = await openFirstDraftInEditForm(editorPage);
    test.skip(id === null, 'no recently-edited draft available in this domain');

    // Expand the References row and add a reference.
    await editorPage
      .getByRole('button', { name: /reference/i })
      .first()
      .click();
    const before = await editorPage.locator('app-references-field-editor .ref-field').count();
    await editorPage.getByRole('button', { name: /add reference/i }).click();
    await expect(editorPage.locator('app-references-field-editor .ref-field')).toHaveCount(
      before + 1,
    );
  });

  test('editing a part Description reflects in the preview and persists across a save + reload', async ({
    editorPage,
  }) => {
    const id = await openFirstDraftInEditForm(editorPage);
    test.skip(id === null, 'no recently-edited draft available in this domain');

    const marker = `DescBody${Date.now()}`;
    const firstPart = editorPage.locator('app-part-card').first();

    // Expand the first part's Description row. The whole three-cell row head is the target and it
    // is a div carrying role="button", not a <button> element — match the role, not the tag.
    await firstPart.locator('app-description-field [role="button"][aria-expanded]').first().click();

    // Type the marker into every per-language editor so whichever language the preview/viewer
    // shows carries it (the surface selector is the accepted `.ProseMirror` contenteditable).
    const surfaces = firstPart.locator('app-description-field .ProseMirror');
    await surfaces.first().waitFor({ state: 'visible', timeout: 10000 });
    const count = await surfaces.count();
    for (let i = 0; i < count; i++) {
      await surfaces.nth(i).click();
      await editorPage.keyboard.type(marker);
    }

    // The inline live preview (desktop width) derives from the working message — it reflects typing.
    await expect(editorPage.locator('app-live-preview')).toContainText(marker);

    // Save, then wait for the PRISTINE state — the Save button disables the moment the
    // double-submit lock engages, so it is not proof the round-trip finished; reloading on it
    // cancels the in-flight PUT. "All changes saved" renders only after the post-save reload.
    await editorPage.getByRole('button', { name: 'Save' }).click();
    await expect(editorPage.getByText('All changes saved')).toBeVisible({ timeout: 15000 });

    await editorPage.reload();
    await editorPage.waitForURL(/\/editor\/edit\/[^/]+/);
    await expect(editorPage.locator('app-section-panel').first()).toBeVisible();

    // The collapsed Description viewer renders the persisted body.
    await expect(
      editorPage.locator('app-part-card').first().locator('app-description-field'),
    ).toContainText(marker);
  });

  test('keyboard navigation moves focus between field-row labels', async ({ editorPage }) => {
    const id = await openFirstDraftInEditForm(editorPage);
    test.skip(id === null, 'no recently-edited draft available in this domain');

    // The roving-tabindex label registry lets Up/Down move between rows that are not DOM siblings.
    // The row head is a div with role="button", not a <button> element.
    const rowHeads = editorPage.locator('app-editor-field-row [role="button"][aria-expanded]');
    const firstLabel = rowHeads.first();
    await firstLabel.focus();
    await editorPage.keyboard.press('ArrowDown');

    // Focus moved to a DIFFERENT row head — scoped to the field rows, because an unscoped
    // [aria-expanded]:focus would also be satisfied by any other expandable on the page.
    await expect(
      editorPage.locator('app-editor-field-row [role="button"][aria-expanded]:focus'),
    ).toHaveCount(1);
    await expect(firstLabel).not.toBeFocused();
  });
});
