import type { Page } from '@playwright/test';

import { expect, test } from './support/fixtures';
import { fixClock } from './support/clock';
import {
  actionsButton,
  editAsTextDialog,
  editorBody,
  editorBodyLeft,
  EXAMPLE_GEOMETRY_TEXT,
  featureCard,
  fillGeometryText,
  openActions,
  openBlankWarning,
  openEmptyPositionEditor,
  openPositionEditorWithExample,
  runAction,
  searchTemplates,
  selectFeature,
  startFromTemplate,
  tickFeature,
  toolButton,
  typeGeometryText,
} from './support/editor-positions';
import { loginAs } from './support/login';
import { shot } from './support/shot';

/*
 * The editor manual's pictures of the position editor (05-PositionEditor.adoc), template
 * execution (06-TemplateExecution.adoc) and the firing area schedule (07-FiringAreaSchedule.adoc),
 * written to guides/editor-manual/images/. One test per picture, titled `<manual>/<file>`.
 *
 * Nothing is saved. The position editor's pictures open a new, blank warning and give it the
 * example positions of support/editor-positions.ts in its working copy; template execution starts
 * from the start screen and never clicks Execute; the schedule's pictures open an editor row and a
 * confirmation without saving or confirming.
 *
 *   npx playwright test -c guides-shots/playwright.config.ts editor-manual-positions.shots.ts
 */

test.describe('editor-manual: position editor', () => {
  test('editor-manual/PositionEditor.png', async ({ page }) => {
    const editor = await openPositionEditorWithExample(page);
    await shot(page, { target: editor, pad: 0 });
  });

  test('editor-manual/PositionTextEditor.png', async ({ page }) => {
    await openBlankWarning(page);
    await typeGeometryText(page, EXAMPLE_GEOMETRY_TEXT, 4);
    // Edge to edge: a margin would show a strip of the dimmed page behind the dialog.
    await shot(page, { target: editAsTextDialog(page), pad: 0 });
  });

  test('editor-manual/PositionGraphicalEditor.png', async ({ page }) => {
    const editor = await openPositionEditorWithExample(page);
    await toolButton(editor, 'Select').click();
    await selectFeature(editor, 'Exercise area');
    await expect(featureCard(editor, 'Exercise area').getByText('Restriction')).toBeVisible();
    await shot(page, { target: editor, pad: 0 });
  });

  // The icons of the toolbar's buttons, each on its own for the heading it stands in before the
  // button's word ("[icon] Point"), each not chosen: the editor opens in Point, so that one is
  // taken with Select chosen. One literal title per test, which the manifest check reads.
  async function toolPicture(page: Page, label: string): Promise<void> {
    const editor = await openEmptyPositionEditor(page);
    if (label === 'Point') await toolButton(editor, 'Select').click();
    await shot(page, {
      target: toolButton(editor, label).locator('app-icon'),
      pad: 2,
      maps: false,
    });
  }

  // The affected-area pictures end right of the buoys, before the exercise area and the hatched
  // military area of the base map, which say nothing about the affected area.
  const AFFECTED_WIDTH = 620;

  test('editor-manual/PositionEditorPointTool.png', async ({ page }) => {
    await toolPicture(page, 'Point');
  });

  test('editor-manual/PositionEditorPathTool.png', async ({ page }) => {
    await toolPicture(page, 'Line');
  });

  test('editor-manual/PositionEditorPolygonTool.png', async ({ page }) => {
    await toolPicture(page, 'Area');
  });

  test('editor-manual/PositionEditorModifyTool.png', async ({ page }) => {
    await toolPicture(page, 'Select');
  });

  test('editor-manual/PositionEditorDeleteTool.png', async ({ page }) => {
    await toolPicture(page, 'Delete');
  });

  test('editor-manual/PositionEditorMeasureTool.png', async ({ page }) => {
    await toolPicture(page, 'Measure');
  });

  test('editor-manual/PositionEditorUndoRedo.png', async ({ page }) => {
    const editor = await openPositionEditorWithExample(page);
    // Two edits and one step back: both buttons can be used.
    await featureCard(editor, 'Buoy C').getByRole('button', { name: 'Delete geometry' }).click();
    await featureCard(editor, 'Buoy B').getByRole('button', { name: 'Delete geometry' }).click();
    await editor.getByRole('button', { name: 'Undo' }).click();
    await expect(editor.getByRole('button', { name: 'Redo' })).toBeEnabled();
    await expect(editor.getByRole('button', { name: 'Undo' })).toBeEnabled();
    // The pointer off Undo, so neither button shows its hover colour.
    await page.mouse.move(1, 1);
    await shot(page, {
      target: [
        editor.getByRole('button', { name: 'Undo' }),
        editor.getByRole('button', { name: 'Redo' }),
      ],
      pad: 2,
    });
  });

  test('editor-manual/PositionEditorCollapse.png', async ({ page }) => {
    const editor = await openEmptyPositionEditor(page);
    await shot(page, { target: editor.getByRole('button', { name: 'Collapse' }), pad: 2 });
  });

  test('editor-manual/PositionEditorActionMenu.png', async ({ page }) => {
    const editor = await openPositionEditorWithExample(page);
    await toolButton(editor, 'Select').click();
    for (const buoy of ['Buoy A', 'Buoy B', 'Buoy C']) await tickFeature(editor, buoy);
    const menu = await openActions(editor);
    await shot(page, { target: [actionsButton(editor), menu] });
  });

  test('editor-manual/PositionEditorImportGeometry.png', async ({ page }) => {
    const editor = await openEmptyPositionEditor(page);
    await runAction(editor, 'Import geometry');
    const dialog = page.getByRole('dialog', { name: 'Import geometry' });
    await expect(dialog).toBeVisible();
    // The tree opened by hand, the search box left empty.
    const baltic = dialog.locator('.tree-row').filter({ hasText: /^The Baltic Sea$/ });
    await baltic.getByRole('button', { name: 'Expand' }).click();
    await dialog.getByRole('button', { name: 'ES D 139 Bornholm E.' }).click();
    await expect(dialog.getByText(/\b[1-9]\d* feature\(s\)/)).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'Import' })).toBeEnabled();
    await shot(page, { target: dialog, pad: 0 });
  });

  test('editor-manual/PositionEditorAffectedRadius.png', async ({ page }) => {
    const editor = await openPositionEditorWithExample(page);
    await toolButton(editor, 'Select').click();
    await selectFeature(editor, 'Buoy B');
    await runAction(editor, 'Add affected radius');
    await selectFeature(editor, 'Affected area');
    await shot(page, { target: await editorBodyLeft(editor, AFFECTED_WIDTH), pad: 0 });
  });

  test('editor-manual/PositionEditorAffectedPath.png', async ({ page }) => {
    const editor = await openPositionEditorWithExample(page);
    await toolButton(editor, 'Select').click();
    for (const buoy of ['Buoy A', 'Buoy B', 'Buoy C']) await tickFeature(editor, buoy);
    await runAction(editor, 'Add affected path');
    await selectFeature(editor, 'Affected area');
    // A radius turns the path into a corridor.
    const radius = featureCard(editor, 'Affected area').getByRole('spinbutton', { name: 'Radius' });
    await radius.fill('0.5');
    await radius.blur();
    await expect(radius).toHaveValue('0.5');
    await shot(page, { target: await editorBodyLeft(editor, AFFECTED_WIDTH), pad: 0 });
  });

  test('editor-manual/PositionEditorAffectedArea.png', async ({ page }) => {
    const editor = await openPositionEditorWithExample(page);
    await toolButton(editor, 'Select').click();
    for (const buoy of ['Buoy A', 'Buoy B', 'Buoy C']) await tickFeature(editor, buoy);
    await runAction(editor, 'Add affected area');
    await selectFeature(editor, 'Affected area');
    await shot(page, { target: await editorBodyLeft(editor, AFFECTED_WIDTH), pad: 0 });
  });

  test('editor-manual/PositionEditorFeaturePanel.png', async ({ page }) => {
    const editor = await openPositionEditorWithExample(page);
    await toolButton(editor, 'Select').click();
    await selectFeature(editor, 'Exercise area');
    const card = featureCard(editor, 'Exercise area');
    // The area's restriction, and a name for its first position.
    await card.getByRole('combobox', { name: 'Restriction' }).click();
    await page.getByRole('option', { name: 'Restricted', exact: true }).click();
    await card.getByRole('button', { name: 'Edit names' }).first().click();
    const name = card.getByRole('textbox', { name: 'Name for this position in English' });
    await name.fill('North-west corner');
    await name.press('Tab');
    await shot(page, { target: editor.locator('app-card').first(), pad: 0 });
  });

  test('editor-manual/PositionEditorMeasurement.png', async ({ page }) => {
    const editor = await openPositionEditorWithExample(page);
    await toolButton(editor, 'Measure').click();
    const map = editor.locator('app-ol-map');
    const box = await map.boundingBox();
    if (!box) throw new Error('The map has no box.');
    // From Buoy A across the exercise area, without finishing: the length follows the pointer.
    // Three clicks, apart enough in time not to read as a double-click (which ends the path).
    for (const [x, y] of [
      [600, 60],
      [520, 330],
      [800, 430],
    ] as const) {
      await page.mouse.click(box.x + x, box.y + y);
      await page.waitForTimeout(400);
    }
    await page.mouse.move(box.x + 800, box.y + 430);
    await expect(map.getByText(/ nm$/)).toBeVisible();
    await shot(page, { target: editorBody(editor), pad: 0 });
  });
});

test.describe('editor-manual: template execution', () => {
  test('editor-manual/TemplateSearchPopover.png', async ({ page }) => {
    // A term that narrows the list to a few whole rows (the list scrolls past ten).
    const box = await searchTemplates(page, 'light u');
    const first = page.getByRole('option').first();
    await expect(first).toContainText('Light unlit');
    await first.hover();
    const panel = page.locator('.cdk-overlay-pane').filter({ has: box });
    await expect(panel.getByText('EXAMPLE', { exact: false })).toBeVisible();
    await expect(panel.locator('app-message-details-renderer')).toBeVisible();
    await shot(page, { target: [page.locator('app-card').first(), panel] });
  });

  /**
   * Fills in the Light unlit template's parameters as an editor does: a position at Stevns (Edit
   * as text in the template's card), the first light type and the light's name.
   */
  async function fillLightUnlit(page: Page): Promise<void> {
    const fields = page.locator('app-template-execute-fields');
    await fields.getByRole('button', { name: 'Edit as text' }).click();
    await fillGeometryText(page, "Point, en: Stevns\n55° 17.400'N 012° 27.300'E", 1);
    await editAsTextDialog(page).getByRole('button', { name: 'Save geometry' }).click();
    await expect(editAsTextDialog(page)).toBeHidden();
    await fields.getByRole('combobox', { name: 'Light Type' }).click();
    await page.getByRole('option').first().click();
    await fields.getByRole('textbox', { name: 'Light Name' }).fill('Stevns');
    await fields.getByRole('textbox', { name: 'Light Name' }).press('Tab');
  }

  test.describe('in a taller window (the whole page)', () => {
    // At 800 px the filled-in parameters are below the window's edge.
    test.use({ viewport: { width: 1280, height: 1400 } });

    test('editor-manual/TemplateExecution.png', async ({ page }) => {
      await startFromTemplate(page, 'Light unlit');
      await fillLightUnlit(page);
      // The preview written from the filled-in parameters, its audio text included.
      const preview = page.locator('app-live-preview');
      await expect(preview).toContainText('Stevns');
      await expect(page.getByText('Preview updated.')).toBeVisible();
      // Back to the page's top, which filling in the last field scrolled away.
      await page.evaluate(() => {
        window.scrollTo(0, 0);
        for (const element of Array.from(document.querySelectorAll('*'))) {
          if (element.scrollTop > 0) element.scrollTop = 0;
        }
      });
      await page.mouse.move(1, 1);
      await shot(page);
    });
  });

  test('editor-manual/TemplateSelectionDrawer.png', async ({ page }) => {
    await startFromTemplate(page, 'Light unlit');
    await page.getByRole('button', { name: 'Change' }).click();
    const drawer = page.getByRole('dialog', { name: 'Select templates' });
    await expect(drawer).toBeVisible();
    await drawer.getByText('Light', { exact: true }).click();
    await drawer.getByText('Light unlit', { exact: true }).hover();
    await expect(drawer.locator('app-message-details-renderer')).toBeVisible();
    await shot(page, { target: drawer, pad: 0 });
  });

  // At 1080 px the live preview is a drawer behind the Preview button, so the parameters take the
  // page's width.
  test.describe('with the preview in a drawer', () => {
    test.use({ viewport: { width: 1080, height: 800 } });

    test('editor-manual/TemplateExecutionDialog.png', async ({ page }) => {
      await startFromTemplate(page, 'Light unlit');
      await fillLightUnlit(page);
      const fields = page.locator('app-template-execute-fields');
      await shot(page, { target: fields.locator('app-section-panel'), pad: 0 });
    });
  });
});

test.describe('editor-manual: firing area schedule', () => {
  // The schedule opens on the page's today: the rig's fixed day, which has firing periods.
  async function openSchedule(page: Page): Promise<void> {
    await fixClock(page);
    await loginAs(page, 'editor', 'niord-fa');
    await page.goto('/schedule');
    await expect(page.getByRole('heading', { name: 'Firing Area Schedule' })).toBeVisible();
    await expect(page.getByRole('row', { name: /Raghammer/ })).toBeVisible();
  }

  test('editor-manual/FiringAreaSchedule.png', async ({ page }) => {
    await openSchedule(page);
    await shot(page);
  });

  test('editor-manual/FiringAreaScheduleEdit.png', async ({ page }) => {
    await openSchedule(page);
    const row = page.getByRole('row', { name: /Raghammer/ });
    await row.getByRole('button', { name: 'Edit firing periods' }).click();
    await row.getByRole('button', { name: 'Add' }).click();
    await expect(row.getByRole('button', { name: 'Save' })).toBeVisible();
    await shot(page, { target: row });
  });

  test('editor-manual/FiringAreaScheduleUpdate.png', async ({ page }) => {
    await openSchedule(page);
    await page.getByRole('button', { name: 'Update Firing Exercises…' }).click();
    // Answered No afterwards, never Yes: Yes would regenerate the firing exercise messages.
    const dialog = page.getByRole('alertdialog', { name: 'Update Firing Exercises' });
    await expect(dialog).toBeVisible();
    await shot(page, { target: dialog, pad: 0 });
  });
});
