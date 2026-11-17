import { expect, test } from './auth/fixtures';

// Charts section e2e: route + list smoke, the create drawer's Save gate, the
// geometry-editor modal launch surfaces, import/export wiring, and one live
// add→edit→delete round-trip with a disposable chart (self-cleaning). The
// extent is set via edit-as-text (deterministic); canvas drawing is covered by
// the geometry-editor sandbox suite and manual testing.

const DISPOSABLE_NUMBER = 'e2e-chart-901';

test.describe('admin charts section', () => {
  test('the section renders the list (or empty state) with the toolbar under the shell', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/admin/charts');
    await expect(authenticatedPage).toHaveURL(/\/admin\/charts(?:[?#]|$)/);
    await expect(authenticatedPage.getByRole('button', { name: 'New Chart' })).toBeVisible();
  });

  test('the create drawer gates Save on the required chart number and dirty-gates close', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/admin/charts');
    await authenticatedPage.getByRole('button', { name: 'New Chart' }).click();

    const dialog = authenticatedPage.getByRole('dialog');
    await expect(dialog).toBeVisible();
    // Add-mode defaults visible: datum WGS84 pre-filled.
    await expect(dialog.getByLabel('Horizontal datum')).toHaveValue('WGS84');

    const save = dialog.getByRole('button', { name: 'Save Chart' });
    await expect(save).toBeDisabled(); // no change yet

    await dialog.getByLabel('Name').fill('Gated');
    await expect(save).toBeDisabled(); // changed but chart number empty — required gate blocks

    await dialog.getByLabel('Chart number').fill(DISPOSABLE_NUMBER);
    await expect(save).toBeEnabled();

    // A dirty drawer must confirm before closing. The gate is the shared in-app confirm — an
    // `alertdialog`, which getByRole('dialog') deliberately does NOT match, so the locator above
    // stays pinned to the drawer while the prompt is up. Only the browser refresh / tab-close leg
    // still rides a native beforeunload.
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    const confirm = authenticatedPage.getByRole('alertdialog');
    await expect(confirm.getByRole('heading', { name: 'Unsaved changes' })).toBeVisible();
    // The drawer must survive an unanswered prompt — that is the half of the gate worth testing.
    await expect(dialog).toBeVisible();
    await confirm.getByRole('button', { name: 'Yes', exact: true }).click();
    await expect(dialog).toBeHidden();
  });

  test('the Edit button opens the chart geometry editor (Box + Area only, + WMS); Cancel returns to the drawer', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/admin/charts');
    await authenticatedPage.getByRole('button', { name: 'New Chart' }).click();
    const dialog = authenticatedPage.getByRole('dialog', { name: /Chart$/ });
    await expect(dialog).toBeVisible();

    // The Edit button lives inside the extent preview map (not the drawer footer).
    await dialog.getByRole('button', { name: /Edit$/ }).click();
    const editor = authenticatedPage.locator('app-geometry-editor');
    // A chart extent only needs the Box + Area (polygon) shape tools — point/line/
    // measure are intentionally NOT offered (a chart geometry is a coverage extent).
    await expect(editor.getByRole('button', { name: 'Box' })).toBeVisible();
    await expect(editor.getByRole('button', { name: 'Area' })).toBeVisible();
    await expect(editor.getByRole('button', { name: 'Point' })).toHaveCount(0);
    await expect(editor.getByRole('button', { name: 'Line' })).toHaveCount(0);
    await expect(editor.getByRole('button', { name: 'Measure' })).toHaveCount(0);
    // The WMS sea-chart base layer is available like the message-details map.
    await expect(editor.getByRole('checkbox', { name: 'WMS' })).toBeVisible();

    // Edit-as-text and Import remain available from the cog "Actions" menu (no
    // per-feature property panel — chart geometries carry no properties).
    await editor.getByRole('button', { name: 'Actions' }).click();
    await expect(authenticatedPage.getByRole('menuitem', { name: 'Edit as text' })).toBeVisible();
    await expect(
      authenticatedPage.getByRole('menuitem', { name: 'Import geometry' }),
    ).toBeVisible();
    await authenticatedPage.keyboard.press('Escape'); // close just the menu…
    await expect(editor).toHaveCount(1); // …the editor stays open

    // The editor owns its own Cancel (no separate dialog X chrome). Nothing was
    // edited, so the unsaved-changes guard does not prompt.
    await editor.getByRole('button', { name: 'Cancel' }).click();
    await expect(editor).toHaveCount(0);
    await expect(dialog).toBeVisible(); // back on the drawer, still open
  });

  test('the cog Import opens the JSON upload dialog; Export opens the public list in a new tab', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/admin/charts');

    await authenticatedPage.getByRole('button', { name: 'Chart actions' }).click();
    await authenticatedPage.getByRole('menuitem', { name: 'Import…' }).click();
    const importDialog = authenticatedPage.getByRole('dialog');
    await expect(importDialog.getByText('Upload Charts File')).toBeVisible();
    // getByLabel: the dialog has a header ✕ (aria-label "Close") AND a footer text
    // "Close" button — a role+name lookup matches both.
    await importDialog.getByLabel('Close').click();
    await expect(importDialog).toBeHidden();

    await authenticatedPage.getByRole('button', { name: 'Chart actions' }).click();
    const popupPromise = authenticatedPage.context().waitForEvent('page');
    await authenticatedPage.getByRole('menuitem', { name: 'Export…' }).click();
    const popup = await popupPromise;
    expect(popup.url()).toContain('/rest/charts/all');
    await popup.close();
  });

  test('live round-trip: add with a text-set extent, edit, then delete (self-cleaning)', async ({
    authenticatedPage,
  }) => {
    await authenticatedPage.goto('/admin/charts');

    // ADD with extent
    await authenticatedPage.getByRole('button', { name: 'New Chart' }).click();
    const dialog = authenticatedPage.getByRole('dialog', { name: /Chart$/ });
    await dialog.getByLabel('Chart number').fill(DISPOSABLE_NUMBER);
    await dialog.getByLabel('Name').fill('E2E disposable');
    await expect(dialog.getByText('No extent defined yet.')).toBeVisible();

    // Set the extent via the deterministic edit-as-text path (canvas dragging is
    // covered by the geometry-editor sandbox suite + manual testing).
    await dialog.getByRole('button', { name: /Edit as text$/ }).click();
    const textDialog = authenticatedPage.getByRole('dialog', { name: 'Edit as text' });
    await expect(textDialog).toBeVisible();
    // The dialog's onShow init (fires post-animation) pre-formats the current
    // geometry and can wipe text typed before it runs — retry the fill until
    // the parse confirms a feature.
    await expect(async () => {
      await textDialog.locator('textarea').fill("55° 00.000'N 010° 00.000'E");
      await expect(textDialog.getByText('1 feature(s)')).toBeVisible({ timeout: 3000 });
    }).toPass({ timeout: 15000 });
    await textDialog.getByRole('button', { name: 'Save geometry' }).click();
    await expect(dialog.getByText('No extent defined yet.')).toHaveCount(0);

    await dialog.getByRole('button', { name: 'Save Chart' }).click();
    await expect(dialog).toBeHidden();

    // The new row is findable via the filter; the extent survived the round-trip.
    await authenticatedPage.getByLabel('Filter charts').fill(DISPOSABLE_NUMBER);
    const row = authenticatedPage.getByRole('row').filter({ hasText: DISPOSABLE_NUMBER });
    await expect(row).toHaveCount(1);

    // EDIT
    await row.getByRole('button', { name: 'Edit chart' }).click();
    await expect(dialog.getByLabel('Chart number')).toHaveValue(DISPOSABLE_NUMBER);
    await expect(dialog.getByLabel('Chart number')).toBeDisabled(); // immutable PK
    await expect(dialog.getByText('No extent defined yet.')).toHaveCount(0); // extent persisted
    await dialog.getByLabel('Name').fill('E2E renamed');
    await dialog.getByRole('button', { name: 'Save Chart' }).click();
    await expect(dialog).toBeHidden();
    await expect(authenticatedPage.getByRole('row').filter({ hasText: 'E2E renamed' })).toHaveCount(
      1,
    );

    // DELETE (cleanup)
    await row.getByRole('button', { name: 'Delete chart' }).click();
    const confirm = authenticatedPage.getByRole('alertdialog');
    await expect(confirm.getByText(DISPOSABLE_NUMBER)).toBeVisible();
    await confirm.getByRole('button', { name: /Delete|Yes/ }).click();
    await expect(
      authenticatedPage.getByRole('row').filter({ hasText: DISPOSABLE_NUMBER }),
    ).toHaveCount(0);
  });
});
