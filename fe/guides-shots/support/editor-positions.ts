import { expect, type Locator, type Page } from '@playwright/test';

import { loginAs } from './login';
import { showMapAt, type MapView } from './map';

/*
 * The editor manual's position editor, template execution and firing area schedule pictures (the
 * editor-positions part): the steps several of its pictures share. Nothing here saves anything: a
 * blank message is opened in the editor and given positions in its working copy only, and every
 * test ends without Save, OK, Execute or a confirmation.
 */

/**
 * The example positions the position editor's pictures show, typed into "Edit as text": an
 * exercise area east of Bornholm and three buoys beside it, with invented English names. Typed by
 * the test, so the pictures depend on no message of the data.
 */
export const EXAMPLE_GEOMETRY_TEXT = [
  'Polygon, en: Exercise area',
  "55° 10.000'N 015° 16.000'E",
  "55° 10.000'N 015° 30.000'E",
  "55° 00.000'N 015° 30.000'E",
  "55° 00.000'N 015° 16.000'E",
  '',
  'Point, en: Buoy A',
  "55° 12.500'N 015° 14.000'E",
  '',
  'Point, en: Buoy B',
  "55° 07.500'N 015° 10.500'E",
  '',
  'Point, en: Buoy C',
  "55° 03.000'N 015° 14.000'E",
].join('\n');

/**
 * The position editor's map view for the example: the area and the buoys right of the Positions
 * panel, which floats over the map's left side, with Bornholm's east coast between them.
 */
export const EXAMPLE_VIEW: MapView = { lon: 15.25, lat: 55.1, zoom: 11 };

/**
 * Signs in as an editor and opens a new, blank navigational warning in the editor (Editor > New
 * blank NW). The message exists only in the browser until Save, which no test clicks.
 */
export async function openBlankWarning(page: Page): Promise<void> {
  await loginAs(page, 'editor');
  await page.goto('/editor');
  await page.getByRole('button', { name: 'New blank NW' }).click();
  await expect(page).toHaveURL(/\/editor\/edit$/);
  await expect(positionsRow(page)).toBeVisible();
}

/** The first part's Positions row in the edit form. */
export function positionsRow(page: Page): Locator {
  return page.locator('[data-field-row="positions"]').first();
}

/** Opens the first part's Positions row (its head is a button that expands the row). */
export async function expandPositions(page: Page): Promise<void> {
  const head = positionsRow(page).locator('.ed-row-head');
  if ((await head.getAttribute('aria-expanded')) !== 'true') await head.click();
  await expect(positionsRow(page).getByRole('button', { name: 'Edit positions' })).toBeVisible();
}

/** The "Edit as text" dialog. */
export function editAsTextDialog(page: Page): Locator {
  return page.getByRole('dialog', { name: 'Edit as text' });
}

/**
 * Types `text` into the open "Edit as text" dialog and waits until the backend has read it into
 * `features` features (the count beside the buttons).
 */
export async function fillGeometryText(page: Page, text: string, features: number): Promise<void> {
  const dialog = editAsTextDialog(page);
  await expect(dialog).toBeVisible();
  await dialog.getByRole('textbox', { name: 'Edit as text' }).fill(text);
  await expect(dialog.getByText(`${features} feature(s)`)).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Save geometry' })).toBeEnabled();
}

/**
 * Opens "Edit as text" from the Positions row and types `text`. Leaves the dialog open.
 */
export async function typeGeometryText(page: Page, text: string, features: number): Promise<void> {
  await expandPositions(page);
  await positionsRow(page).getByRole('button', { name: 'Edit as text' }).click();
  await fillGeometryText(page, text, features);
}

/** The position editor, once open. */
export function positionEditor(page: Page): Locator {
  return page.locator('app-geometry-editor');
}

/** Opens the position editor of a blank message's first part, with no positions. */
export async function openEmptyPositionEditor(page: Page): Promise<Locator> {
  await openBlankWarning(page);
  await expandPositions(page);
  await positionsRow(page).getByRole('button', { name: 'Edit positions' }).click();
  const editor = positionEditor(page);
  await expect(editor.getByText('No positions yet')).toBeVisible();
  return editor;
}

/**
 * Gives a blank message's first part the example positions (Edit as text, Save geometry: the
 * working copy only), opens the position editor on them and moves its map to EXAMPLE_VIEW.
 */
export async function openPositionEditorWithExample(page: Page): Promise<Locator> {
  await openBlankWarning(page);
  await typeGeometryText(page, EXAMPLE_GEOMETRY_TEXT, 4);
  await editAsTextDialog(page).getByRole('button', { name: 'Save geometry' }).click();
  await expect(editAsTextDialog(page)).toBeHidden();
  await positionsRow(page).getByRole('button', { name: 'Edit positions' }).click();
  const editor = positionEditor(page);
  await expect(editor.locator('app-geometry-tree')).toHaveCount(4);
  await showMapAt(page, EXAMPLE_VIEW);
  return editor;
}

/** The position editor's map with the Positions panel over it: the editor below its toolbar. */
export function editorBody(editor: Locator): Locator {
  return editor.locator('app-ol-map').locator('xpath=..');
}

/**
 * The left `width` CSS pixels of the editor below its toolbar: the Positions panel and the map
 * beside it, without the map's right part. A clear element laid over that part of the window, as
 * the target of a picture; it paints nothing.
 */
export async function editorBodyLeft(editor: Locator, width: number): Promise<Locator> {
  await editorBody(editor).evaluate((body, cut) => {
    const box = body.getBoundingClientRect();
    const frame = document.createElement('div');
    frame.id = 'guides-editor-body-left';
    Object.assign(frame.style, {
      position: 'fixed',
      left: `${box.left}px`,
      top: `${box.top}px`,
      width: `${Math.min(cut, box.width)}px`,
      height: `${box.height}px`,
      pointerEvents: 'none',
      // Clear, but painted as far as shot() is concerned, which takes the box of what paints.
      backgroundImage: 'linear-gradient(transparent, transparent)',
    });
    document.body.appendChild(frame);
  }, width);
  return editor.page().locator('#guides-editor-body-left');
}

/** A tool button of the position editor's toolbar, by its label (Point, Line, Area, ...). */
export function toolButton(editor: Locator, label: string): Locator {
  return editor.getByRole('button', { name: label, exact: true });
}

/** The card of a feature in the position editor's Positions panel, by its name or type. */
export function featureCard(editor: Locator, label: string): Locator {
  return editor
    .locator('app-geometry-tree')
    .filter({ has: editor.page().getByRole('button', { name: label, exact: true }) });
}

/** Selects one feature alone by clicking its card's title, as a click on it on the map would. */
export async function selectFeature(editor: Locator, label: string): Promise<void> {
  await featureCard(editor, label).getByRole('button', { name: label, exact: true }).click();
  await expect(featureCard(editor, label).getByRole('checkbox')).toBeChecked();
}

/** Ticks a feature card's checkbox, adding the feature to the selection. */
export async function tickFeature(editor: Locator, label: string): Promise<void> {
  await featureCard(editor, label).getByRole('checkbox', { name: 'Select geometry' }).check();
}

/** The position editor's Actions button (the cog). */
export function actionsButton(editor: Locator): Locator {
  return editor.getByRole('button', { name: 'Actions' });
}

/** Opens the position editor's Actions menu and returns the menu. */
export async function openActions(editor: Locator): Promise<Locator> {
  await actionsButton(editor).click();
  const menu = editor.page().getByRole('menu', { name: 'Actions' });
  await expect(menu).toBeVisible();
  return menu;
}

/** Runs one item of the position editor's Actions menu. */
export async function runAction(editor: Locator, item: string): Promise<void> {
  const menu = await openActions(editor);
  await menu.getByRole('menuitem', { name: item }).click();
  await expect(menu).toBeHidden();
}

/**
 * Opens the editor's start screen as an editor, opens "Select NW template…" and types `search`.
 * Returns the search box.
 */
export async function searchTemplates(page: Page, search: string): Promise<Locator> {
  await loginAs(page, 'editor');
  await page.goto('/editor');
  await page.getByRole('button', { name: 'Select NW template…' }).click();
  const box = page.getByRole('combobox', { name: 'Search templates…' });
  await expect(box).toBeFocused();
  await box.fill(search);
  return box;
}

/** Picks a template on the start screen and waits for the Execute templates page. */
export async function startFromTemplate(page: Page, template: string): Promise<void> {
  await searchTemplates(page, template);
  await page
    .getByRole('option', { name: new RegExp(`^${template}\\b`) })
    .first()
    .click();
  await expect(page).toHaveURL(/\/editor\/execute-template$/);
  await expect(page.locator('app-template-execute-fields')).toBeVisible();
}
