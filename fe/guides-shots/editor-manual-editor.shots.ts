import type { Locator, Page } from '@playwright/test';

import { fixClock } from './support/clock';
import {
  afterLastComment,
  domainOf,
  example,
  expandField,
  fieldRow,
  openExample,
  partCard,
  type ExampleMessage,
} from './support/editor-editor';
import { expect, test } from './support/fixtures';
import { loginAs } from './support/login';
import { shot } from './support/shot';

/*
 * The pictures of the editor manual's Editor chapter (guides/editor-manual/04-Editor.adoc), written
 * to guides/editor-manual/images/: the start screen, the Edit page field by field, the text tools
 * and their dialogs, the Status page and its dialogs, Comments and History, as an editor sees them.
 *
 * One test per picture, titled `<manual>/<file>` after the file it writes. Every test opens its
 * own example message (support/editor-editor.ts picks it by a rule from the copy's data) and never
 * saves: a field, a dialog or a menu is photographed open, filled in where the picture needs it,
 * and the page is left without clicking Save, Verify, Publish, Delete, Cancel message or Copy.
 *
 *   npx playwright test -c guides-shots/playwright.config.ts editor-manual-editor.shots.ts
 */

/** A visible dialog by a text it holds (its header). */
function dialog(page: Page, text: string): Locator {
  return page.getByRole('dialog').filter({ hasText: text });
}

/** Expands the Description row of a part and returns the row. */
async function description(page: Page, part = 0): Promise<Locator> {
  return expandField(page, 'Description', part);
}

/** A button of a rich-text toolbar, photographed on its own (an inline icon in the text). */
async function toolButton(page: Page, row: Locator, name: string): Promise<void> {
  const button = row.getByRole('button', { name, exact: true }).first();
  await expect(button).toBeVisible();
  await shot(page, { target: button, pad: 3 });
}

/**
 * The rendered message of the live preview, framed from its first line to its last: the preview's
 * article fills the pane's height, so its own box would end in empty space.
 */
function previewTargets(page: Page): { target: Locator[]; keep: Locator } {
  const article = page.locator('app-live-preview article').first();
  const lines = article.locator('> *').filter({ visible: true });
  return { target: [lines.first(), lines.last()], keep: article };
}

/** Selects a phrase of a rich-text editor's text, as a reader does by dragging over it. */
async function selectPhrase(surface: Locator, phrase: string): Promise<void> {
  await surface.evaluate((root, wanted) => {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const at = (node.textContent ?? '').indexOf(wanted);
      if (at < 0) continue;
      const range = document.createRange();
      range.setStart(node, at);
      range.setEnd(node, at + wanted.length);
      const selection = window.getSelection();
      selection?.removeAllRanges();
      selection?.addRange(range);
      return;
    }
    throw new Error(`The text holds no "${wanted}".`);
  }, phrase);
}

/** The details drawer of a message on the Messages page, as a reader opens it. */
async function openDetails(page: Page, name: ExampleMessage): Promise<Locator> {
  await fixClock(page);
  await loginAs(page, 'editor', domainOf(name));
  await page.goto(`/messages/table?messageId=${encodeURIComponent(example(name))}`);
  const drawer = page.getByRole('dialog');
  await expect(drawer.locator('app-message-details-renderer')).toBeVisible();
  return drawer.locator('app-message-details-renderer');
}

test.describe('editor-manual: the editor', () => {
  // -- The start screen and the Edit page ----------------------------------------------------

  test('editor-manual/CreateMessage.png', async ({ page }) => {
    await fixClock(page);
    await loginAs(page, 'editor', 'niord-nw');
    await page.goto('/editor');
    const heading = page.getByRole('heading', { name: 'Create a message' });
    await expect(heading).toBeVisible();
    // The drafts the editor saved in the last month (the copy's "Guide example" drafts) are listed
    // below the cards.
    const recent = page.locator('app-recently-edited-drafts');
    await expect(recent.getByText('Guide example', { exact: false }).first()).toBeVisible();
    await shot(page, { target: heading.locator('..') });
  });

  test.describe('in a taller window (the page with its three panels)', () => {
    // At 800 px the picture ends with the Preamble panel; at 1000 px the Message parts panel
    // follows it.
    test.use({ viewport: { width: 1280, height: 1000 } });

    test('editor-manual/Editor.png', async ({ page }) => {
      await openExample(page, 'nmDraft');
      await shot(page);
    });
  });

  test('editor-manual/EditorLivePreview.png', async ({ page }) => {
    await openExample(page, 'nwDraft');
    const preview = page.locator('app-live-preview');
    await expect(preview).toBeVisible();
    // From the status steps to the message's last line; the pane's empty foot is left out.
    const flow = preview.getByRole('list', { name: 'Message status flow' }).locator('..');
    const { target } = previewTargets(page);
    await shot(page, { target: [flow, ...target], keep: preview });
  });

  test('editor-manual/EditorHeaderUnsaved.png', async ({ page }) => {
    await openExample(page, 'nwDraft');
    // An edit that is never saved: the vicinity gains a word, and the header says so.
    const row = await expandField(page, 'Areas');
    const vicinity = row.getByPlaceholder('Vicinity').first();
    await vicinity.click();
    await vicinity.press('End');
    await vicinity.pressSequentially(' E');
    await vicinity.blur();
    const header = page.locator('app-editor-view-header header');
    await expect(header).toContainText('Unsaved changes');
    await shot(page, { target: header, pad: 0 });
  });

  test('editor-manual/ManageOptionalFields.png', async ({ page }) => {
    await openExample(page, 'nwDraft');
    const trigger = page.getByRole('button', { name: /Manage optional fields/ });
    await trigger.click();
    const menu = page.getByRole('menu');
    // Layout chosen, as a reader adds a field: the menu opens again by itself, the field marked
    // with the dot. Nothing is saved; the choice lasts while the page is open.
    await menu.getByRole('menuitem', { name: 'Layout', exact: true }).click();
    await expect(
      page.getByRole('menu').getByRole('menuitem', { name: 'Layout' }).locator('.status-dot'),
    ).toBeVisible();
    await page.mouse.move(1, 1);
    await shot(page, { target: [trigger, page.getByRole('menu')] });
  });

  test('editor-manual/EditorActionMenu.png', async ({ page }) => {
    await openExample(page, 'nwDraft');
    const trigger = page.getByRole('button', { name: 'Actions' }).first();
    await trigger.click();
    const menu = page.getByRole('menu');
    await expect(menu.getByRole('menuitem', { name: 'Collapse all fields' })).toBeVisible();
    await shot(page, { target: [trigger, menu] });
  });

  // -- The preamble's fields -----------------------------------------------------------------

  test('editor-manual/EditorFieldType.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    await shot(page, { target: await expandField(page, 'Type') });
  });

  test('editor-manual/EditorFieldOrigInfo.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    await shot(page, { target: await expandField(page, 'Org. Information') });
  });

  test('editor-manual/EditorFieldId.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    await shot(page, { target: await expandField(page, 'ID') });
  });

  test('editor-manual/EditorFieldTitle.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    await shot(page, { target: await expandField(page, 'Title') });
  });

  test('editor-manual/EditorFieldReferences.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    await shot(page, { target: await expandField(page, 'References') });
  });

  test('editor-manual/EditorFieldPublishDate.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    await shot(page, { target: await expandField(page, 'Dates') });
  });

  test('editor-manual/EditorFieldAreasCollapsed.png', async ({ page }) => {
    await openExample(page, 'nwDraft');
    await shot(page, { target: fieldRow(page, 'Areas') });
  });

  test('editor-manual/EditorFieldAreas.png', async ({ page }) => {
    await openExample(page, 'nwDraft');
    await shot(page, { target: await expandField(page, 'Areas') });
  });

  test('editor-manual/EditorFieldCategories.png', async ({ page }) => {
    await openExample(page, 'nwDraft');
    const row = await expandField(page, 'Categories');
    await expect(row.getByRole('button', { name: 'Run template…' })).toBeVisible();
    await shot(page, { target: row });
  });

  // -- The message parts ---------------------------------------------------------------------

  test('editor-manual/MessagePartType.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    const head = partCard(page, 0).locator('> div > div').first();
    await expect(head).toContainText('Part 1');
    await shot(page, { target: head });
  });

  test('editor-manual/SampleNM.png', async ({ page }) => {
    const body = await openDetails(page, 'nmPublished');
    await shot(page, { target: body, pad: 12 });
  });

  test.describe('in a wider window', () => {
    // At 1280 px the interval's buttons run past the form's edge (the row scrolls sideways).
    test.use({ viewport: { width: 1440, height: 800 } });

    test('editor-manual/EditorFieldEventDates.png', async ({ page }) => {
      await openExample(page, 'nwDraft');
      await shot(page, { target: await expandField(page, 'Event dates') });
    });
  });

  test('editor-manual/EditorFieldPositions.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    await shot(page, { target: await expandField(page, 'Positions') });
  });

  test('editor-manual/EditorFieldSubject.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    await shot(page, { target: await expandField(page, 'Subject') });
  });

  test.describe('in a taller window (the preview of two parts)', () => {
    // At 800 px the preview pane scrolls before the second part ends.
    test.use({ viewport: { width: 1280, height: 1000 } });

    test('editor-manual/SampleNM2.png', async ({ page }) => {
      await openExample(page, 'nmTwoDetails');
      // Unticked and never saved: with Hide subject off, each part is headed by its subject.
      for (const part of [0, 1]) {
        const row = await expandField(page, 'Subject', part);
        const hide = row.getByRole('checkbox', { name: 'Hide subject' });
        if (await hide.isChecked()) await hide.uncheck();
      }
      await expect(page.locator('app-live-preview article').first()).toBeVisible();
      await shot(page, { ...previewTargets(page), pad: 12 });
    });
  });

  test('editor-manual/EditorFieldDescription.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    await shot(page, { target: await description(page) });
  });

  test('editor-manual/TextEditorToolFullScreen.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    await toolButton(page, await description(page), 'Fullscreen');
  });

  test('editor-manual/TextEditorToolLinks.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    await toolButton(page, await description(page), 'Insert or edit link');
  });

  test('editor-manual/TextEditorToolImage.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    await toolButton(page, await description(page), 'Insert image');
  });

  test('editor-manual/TextEditorToolTable.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    await toolButton(page, await description(page), 'Insert table');
  });

  test('editor-manual/TextEditorToolLocations.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    await toolButton(page, await description(page), 'Insert locations');
  });

  test('editor-manual/TextEditorToolTime.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    await toolButton(page, await description(page), 'Insert time');
  });

  test('editor-manual/InsertLinkDialog.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    const row = await description(page);
    // A word of the text selected, then the link tool; the link is typed and never inserted.
    const surface = row.locator('[contenteditable="true"]').first();
    await surface.click();
    await selectPhrase(surface, 'power cable');
    await row.getByRole('button', { name: 'Insert or edit link', exact: true }).first().click();
    const box = dialog(page, 'Insert / edit link');
    await expect(box).toBeVisible();
    await box.getByLabel('URL').fill('https://www.example.org/notices/cable-route.pdf');
    await shot(page, { target: box, pad: 0 });
  });

  test('editor-manual/ImagePickerDialog.png', async ({ page }) => {
    await openExample(page, 'nmImage');
    const row = await description(page);
    await row.getByRole('button', { name: 'Insert image', exact: true }).first().click();
    const box = dialog(page, 'Select an image');
    await expect(box.locator('img').first()).toBeVisible();
    // The picker is photographed; no image is chosen.
    await shot(page, { target: box, pad: 0 });
  });

  test.describe('in a taller window (the whole locations dialog)', () => {
    // At 800 px the dialog scrolls, and its result is cut off.
    test.use({ viewport: { width: 1280, height: 1000 } });

    test('editor-manual/TextEditorToolLocationDialog.png', async ({ page }) => {
      await openExample(page, 'nmDraft');
      const row = await description(page);
      await row.getByRole('button', { name: 'Insert locations', exact: true }).first().click();
      const box = dialog(page, 'Format Message Locations');
      await expect(box).toBeVisible();
      await box.getByRole('radio', { name: 'Table', exact: true }).check();
      // Every feature of the part ticked, so the result shows what would be inserted.
      await box.getByRole('button', { name: 'all', exact: true }).click();
      await expect(box.locator('table').first()).toBeVisible();
      await shot(page, { target: box, pad: 0 });
    });
  });

  // Two intervals in one part: the seed's exercise draft (data/seed-examples.mjs), which keeps both
  // since a part's intervals are saved in their order (MSG2, fixed 2026-11-12).
  test('editor-manual/TextEditorToolTimeDialog.png', async ({ page }) => {
    await openExample(page, 'nwTwoIntervals');
    const row = await description(page);
    await row.getByRole('button', { name: 'Insert time', exact: true }).first().click();
    const box = dialog(page, 'Format Message Time');
    await expect(box).toBeVisible();
    await shot(page, { target: box, pad: 0 });
  });

  // -- The fields of Miscellaneous -----------------------------------------------------------

  test('editor-manual/EditorFieldPromulgation.png', async ({ page }) => {
    await openExample(page, 'nwDraft');
    const row = await expandField(page, 'Promulgation');
    await row.getByRole('tab', { name: /NAVTEX/ }).click();
    await expect(row.getByRole('textbox', { name: 'NAVTEX message body' })).toBeVisible();
    await shot(page, { target: row });
  });

  test('editor-manual/EditorFieldCharts.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    await shot(page, { target: await expandField(page, 'Charts') });
  });

  test.describe('in a wider window (the attachment row)', () => {
    // At 1280 px the row is cramped: the caption is cut and the file name breaks inside a word.
    test.use({ viewport: { width: 1440, height: 800 } });

    test('editor-manual/EditorFieldAttachments.png', async ({ page }) => {
      await openExample(page, 'nmAttachments');
      const row = await expandField(page, 'Attachments');
      await expect(row.locator('img').first()).toBeVisible();
      await shot(page, { target: row });
    });
  });

  test('editor-manual/SampleNM3.png', async ({ page }) => {
    const body = await openDetails(page, 'nmPublishedAttachments');
    // The list of attachments unfolded, as a reader opens it.
    await body.getByRole('button', { name: 'Show attachments' }).click();
    await page.mouse.move(1, 1);
    await shot(page, { target: body, pad: 12 });
  });

  test('editor-manual/EditorFieldPublication.png', async ({ page }) => {
    await openExample(page, 'nmPublication');
    await shot(page, { target: await expandField(page, 'Publication') });
  });

  test('editor-manual/TextEditorToolPublication.png', async ({ page }) => {
    await openExample(page, 'nmPublication');
    const row = await expandField(page, 'Publication');
    await toolButton(page, row, 'Insert publications');
  });

  test('editor-manual/MessagePublications.png', async ({ page }) => {
    await openExample(page, 'nmPublication');
    const row = await expandField(page, 'Publication');
    // The cursor in the first publication of the external text: the dialog edits that one.
    const external = row.locator('[contenteditable="true"]').first();
    await external.locator('[publication]').first().click();
    await row.getByRole('button', { name: 'Insert publications', exact: true }).first().click();
    const box = dialog(page, 'Update Message Publication');
    await expect(box).toBeVisible();
    await shot(page, { target: box, pad: 0 });
  });

  test('editor-manual/EditorFieldSource.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    await shot(page, { target: await expandField(page, 'Source') });
  });

  test('editor-manual/MessageSourcesField.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    const row = await expandField(page, 'Source');
    await row.getByRole('button', { name: 'Add sources…' }).click();
    const box = dialog(page, 'Add message sources');
    await expect(box.getByRole('row').nth(2)).toBeVisible();
    // Two sources ticked; Add selection is never clicked.
    for (const index of [3, 5]) {
      await box.locator('tbody').first().getByRole('checkbox').nth(index).check();
    }
    await expect(box).toContainText('Selection');
    await shot(page, { target: box, pad: 0 });
  });

  test('editor-manual/EditorFieldLayout.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    await shot(page, { target: await expandField(page, 'Layout') });
  });

  test('editor-manual/EditorFieldLayoutDialog.png', async ({ page }) => {
    await openExample(page, 'nmDraft');
    const row = await expandField(page, 'Layout');
    await row.getByRole('button', { name: 'Select from map' }).click();
    const box = dialog(page, 'Select thumbnail from map');
    await expect(box.locator('app-ol-map')).toBeVisible();
    await shot(page, { target: box, pad: 0 });
  });

  // -- The Status page -----------------------------------------------------------------------

  test('editor-manual/EditorStatusFlow.png', async ({ page }) => {
    await openExample(page, 'nwVerified');
    const flow = page.getByRole('list', { name: 'Message status flow' }).locator('..');
    await expect(flow).toBeVisible();
    await shot(page, { target: flow, pad: 0 });
  });

  test('editor-manual/EditorStatusPage.png', async ({ page }) => {
    await openExample(page, 'nwVerified', 'status');
    await expect(page.getByRole('button', { name: 'Publish message…' })).toBeVisible();
    await shot(page);
  });

  test('editor-manual/PublishConfirmDialog.png', async ({ page }) => {
    // A verified warning that cancels a published one: the dialog lists it with the option to
    // cancel it too.
    await openExample(page, 'nwCancelling', 'status');
    await page.getByRole('button', { name: 'Publish message…' }).click();
    const box = dialog(page, 'Confirm publication');
    await expect(box).toBeVisible();
    await expect(box.getByText('Also cancel the referenced messages')).toBeVisible();
    // Photographed open; Publish is never clicked.
    await shot(page, { target: box, pad: 0 });
  });

  test('editor-manual/CancelMessageDialog.png', async ({ page }) => {
    await openExample(page, 'nmPublished', 'status');
    await page.getByRole('button', { name: 'Cancel message…' }).click();
    const box = dialog(page, 'Please confirm cancellation of this message.');
    await expect(box).toBeVisible();
    // Photographed open; its confirmation (also labelled Cancel) is never clicked.
    await shot(page, { target: box, pad: 0 });
  });

  test('editor-manual/CopyMessageDialog.png', async ({ page }) => {
    await openExample(page, 'nmPublished', 'status');
    await page.getByRole('button', { name: 'Copy message…' }).click();
    const box = dialog(page, 'Copy Message?');
    await expect(box).toBeVisible();
    await shot(page, { target: box, pad: 0 });
  });

  test('editor-manual/MessagePublicationsPanel.png', async ({ page }) => {
    await openExample(page, 'nmInPublications', 'status');
    const panel = page
      .locator('app-card')
      .filter({ has: page.locator('app-message-issues-panel') });
    await expect(panel.locator('li').first()).toBeVisible();
    await shot(page, { target: panel });
  });

  // -- Comments and History ------------------------------------------------------------------

  test.describe('in a taller window (the comments and the whole comment form)', () => {
    // At 800 px the comment form's e-mail field and buttons are below the window's edge.
    test.use({ viewport: { width: 1280, height: 1000 } });

    test('editor-manual/EditorCommentsPage.png', async ({ page }) => {
      // The page's clock after the comments were written, so their times have passed.
      await openExample(page, 'nwCommented', 'comments', 'editor', afterLastComment('nwCommented'));
      await expect(page.locator('app-comment-card').nth(1)).toBeVisible();
      await shot(page);
    });
  });

  test('editor-manual/EditorHistoryPage.png', async ({ page }) => {
    await openExample(page, 'nmVerified', 'history');
    const boxes = page.locator('app-revision-table').getByRole('checkbox');
    await expect(boxes.nth(1)).toBeVisible();
    await boxes.nth(0).check();
    await boxes.nth(1).check();
    await expect(page.getByRole('heading', { name: /^Comparing revision/ })).toBeVisible();
    // The data view shows the stored message as it is, its enumerations (TEMPORARY_NOTICE) too.
    await shot(page, { guard: { keyScan: false } });
  });
});
