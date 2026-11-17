import type { Page } from '@playwright/test';

import { fixClock } from './support/clock';
import { expect, test } from './support/fixtures';
import { loginAs } from './support/login';
import { shot } from './support/shot';
import {
  drawer,
  ensureSafetyNetExample,
  ensureTwitterExample,
  EXAMPLE_SAFETYNET,
  EXAMPLE_TWITTER,
  openSysadminSection,
  openTemplate,
  rowWith,
} from './support/sysadmin-1';

/*
 * The sysadmin manual's pictures, written to guides/sysadmin-manual/images/: the sysadmin pages,
 * as a sysadmin sees them. Keycloak's own console has a spec of its own (keycloak.shots.ts).
 *
 * One test per picture, titled `<manual>/<file>` after the file it writes, so
 * `--grep "<manual>/<file>"` takes one picture again and manifest-check.mjs reads the list from the
 * titles. Every manifest entry marked retake, replace or new becomes one test here.
 *
 * Nothing here saves: a drawer or a dialog is opened, filled in where the picture needs it, and
 * photographed; no Save, Update, Import or Delete is clicked. The one exception is made once and
 * named for what it is: the example SafetyNET and Twitter promulgation types (support/sysadmin-1.ts).
 */

/** Opens the promulgation type of a row (by its name) in the editor drawer. */
async function editPromulgationType(page: Page, name: string): Promise<void> {
  await openSysadminSection(page, 'promulgation');
  await rowWith(page, name).getByRole('button', { name: 'Edit Promulgation Type' }).click();
  await expect(drawer(page, 'Edit Promulgation Type')).toBeVisible();
}

/** Opens the dictionaries page on a dictionary. */
async function openDictionary(page: Page, name: string): Promise<void> {
  await openSysadminSection(page, 'dictionaries');
  await page.getByRole('combobox', { name: 'Dictionary' }).click();
  await page.getByRole('option', { name, exact: true }).click();
  await expect(page.getByRole('combobox', { name: 'Dictionary' })).toContainText(name);
  await expect(page.locator('app-admin-section-page table tbody tr').first()).toBeVisible();
}

/** The dictionaries and the parameter types list keys on purpose. */
const KEYS_SHOWN = { keyScan: false } as const;

test.describe('sysadmin-manual', () => {
  // The manifest's entries, in the manual's order.

  // --- 02 Sysadmin -------------------------------------------------------------------------

  test('sysadmin-manual/SysadminPage.png', async ({ page }) => {
    await loginAs(page, 'sysadmin');
    await page.goto('/sysadmin/overview');
    await expect(page.locator('main a[href$="/sysadmin/batch"]').last()).toBeVisible();
    await shot(page);
  });

  // --- 03 Message Series -------------------------------------------------------------------

  test('sysadmin-manual/MessageSeriesPage.png', async ({ page }) => {
    // The Next Number column names the current year.
    await fixClock(page);
    await openSysadminSection(page, 'series');
    await shot(page);
  });

  test.describe('in a window as tall as the series drawer', () => {
    test.use({ viewport: { width: 1280, height: 880 } });

    test('sysadmin-manual/MessageSeriesEditPage.png', async ({ page }) => {
      await fixClock(page);
      await openSysadminSection(page, 'series');
      await rowWith(page, 'dma-nw').getByRole('button', { name: 'Edit Message Series' }).click();
      const editor = drawer(page, 'Edit Message Series');
      await expect(editor.locator('#series-id')).toHaveValue('dma-nw');
      // Every preamble of the copy names its country; the base product's example names none
      // (the chapter's JSON example has the same one). Typed in, not saved.
      const preamble = editor.locator('#series-navtex-format');
      await preamble.fill('${publish-date}\nNAV WARN ${number-year-id}');
      await preamble.blur();
      await shot(page, { target: editor, pad: 0 });
    });
  });

  test('sysadmin-manual/MessageSeriesNextNumber.png', async ({ page }) => {
    await fixClock(page);
    await openSysadminSection(page, 'series');
    await rowWith(page, 'dma-nw').getByRole('button', { name: 'Edit Message Series' }).click();
    const editor = drawer(page, 'Edit Message Series');
    // The popover is opened and photographed; neither Compute nor Update is clicked.
    await editor.getByRole('button', { name: 'Edit Next Message Series Number' }).click();
    const popover = page.locator('form').filter({ hasText: 'Next Message Number' });
    await expect(popover.locator('#next-number-value')).not.toHaveValue('');
    await shot(page, { target: popover });
  });

  // --- 04 Domains --------------------------------------------------------------------------

  test('sysadmin-manual/DomainsPage.png', async ({ page }) => {
    await openSysadminSection(page, 'domains');
    await shot(page);
  });

  test.describe('in a window as tall as the domain drawer', () => {
    test.use({ viewport: { width: 1280, height: 1320 } });

    test('sysadmin-manual/DomainsEditPage.png', async ({ page }) => {
      await openSysadminSection(page, 'domains');
      // The domain with every field filled in: a map centre, a sort order and areas.
      await rowWith(page, /(^|\s)niord-gl-nw$/)
        .getByRole('button', { name: 'Edit Domain' })
        .click();
      const editor = drawer(page, 'Edit Domain');
      await expect(editor.locator('#domain-kc-exists')).toBeVisible();
      await shot(page, { target: editor, pad: 0 });
    });
  });

  // --- 05 Promulgation Types ---------------------------------------------------------------

  test('sysadmin-manual/PromulgationTypesPage.png', async ({ page }) => {
    await openSysadminSection(page, 'promulgation');
    await expect(rowWith(page, 'NAVTEX/S-53')).toBeVisible();
    // With the copy's example types (prepare.sql), inactive and in no domain: a SafetyNET type
    // shows in the list, and the dialogs below are taken on them.
    await expect(rowWith(page, EXAMPLE_SAFETYNET.name)).toBeVisible();
    await shot(page);
  });

  test('sysadmin-manual/PromulgationTypesAddBtn.png', async ({ page }) => {
    await openSysadminSection(page, 'promulgation');
    const trigger = page.getByRole('button', { name: 'Add Promulgation Type' });
    await trigger.click();
    const menu = page.getByRole('menu');
    await expect(menu.getByRole('menuitem')).toHaveCount(4);
    await shot(page, { target: [trigger, menu] });
  });

  test.describe('in a window as tall as the type drawer', () => {
    test.use({ viewport: { width: 1280, height: 1080 } });

    test('sysadmin-manual/PromulgationTypeEditPage.png', async ({ page }) => {
      await editPromulgationType(page, 'NAVTEX/S-53');
      const editor = drawer(page, 'Edit Promulgation Type');
      await expect(editor.getByRole('button', { name: 'Manage...' })).toBeVisible();
      await shot(page, { target: editor, pad: 0 });
    });
  });

  test('sysadmin-manual/NavtexTransmitterDialog.png', async ({ page }) => {
    await editPromulgationType(page, 'NAVTEX/S-53');
    await drawer(page, 'Edit Promulgation Type').getByRole('button', { name: 'Manage...' }).click();
    const dialog = page.getByRole('dialog', { name: /^Transmitters/ });
    await expect(dialog.locator('tbody tr').first()).toBeVisible();
    await shot(page, { target: dialog, pad: 0 });
  });

  test('sysadmin-manual/SafetyNetAreaDialog.png', async ({ page }) => {
    await ensureSafetyNetExample(page.request);
    await editPromulgationType(page, EXAMPLE_SAFETYNET.name);
    await drawer(page, 'Edit Promulgation Type').getByRole('button', { name: 'Manage...' }).click();
    const dialog = page.getByRole('dialog', { name: /^Areas/ });
    await expect(dialog.locator('tbody tr')).toHaveCount(2);
    await shot(page, { target: dialog, pad: 0 });
  });

  test('sysadmin-manual/TwitterSettingsDialog.png', async ({ page }) => {
    await ensureTwitterExample(page.request);
    await editPromulgationType(page, EXAMPLE_TWITTER.name);
    await drawer(page, 'Edit Promulgation Type')
      .getByRole('button', { name: 'Update Settings...' })
      .click();
    const dialog = page.getByRole('dialog', { name: 'OAuth Settings' });
    await expect(dialog.locator('#twitter-settings-api-key')).toBeVisible();
    // Filled in with invented values and not saved.
    await dialog
      .locator('#twitter-settings-format')
      .fill('${short-id} ${tweet} ${admin-app-url}/messages?messageId=${uid}');
    await dialog.locator('#twitter-settings-api-key').fill('example-api-key');
    await dialog.locator('#twitter-settings-api-secret').fill('example-api-secret');
    await dialog.locator('#twitter-settings-access-token').fill('example-access-token');
    await dialog.locator('#twitter-settings-access-token-secret').fill('example-token-secret');
    await dialog.locator('#twitter-settings-format').focus();
    await shot(page, { target: dialog, pad: 0 });
  });

  // --- 06 Firing Schedules -----------------------------------------------------------------

  test('sysadmin-manual/FiringSchedulesPage.png', async ({ page }) => {
    await openSysadminSection(page, 'schedules');
    // The action menu open: the update of the firing exercises is shown, not clicked.
    await page.getByRole('button', { name: 'Firing schedule actions' }).click();
    await expect(page.getByRole('menuitem', { name: 'Update firing exercises...' })).toBeVisible();
    await shot(page);
  });

  test.describe('in a window as tall as the area form with its map', () => {
    test.use({ viewport: { width: 1280, height: 1120 } });

    test('sysadmin-manual/FiringArea.png', async ({ page }) => {
      await loginAs(page, 'admin');
      await page.goto('/admin/areas');
      await page
        .getByPlaceholder(/filter/i)
        .first()
        .fill('Bornholm');
      // The tree keeps only the matches and their parents: an area beside them is gone.
      await expect(page.getByText('The Sound', { exact: true })).toBeHidden();
      await page.getByText('ES D 138 Bornholm N.', { exact: true }).click();
      await expect(page.locator('select').filter({ hasText: 'Firing area' })).toHaveValue(/.+/);
      await expect(page.getByLabel('Name').first()).toHaveValue('ES D 138 Bornholm N.');
      // The Extent map whole, with its buttons: the window's bottom edge cuts through no control.
      await expect(page.getByRole('button', { name: 'Edit as text' }).first()).toBeInViewport({
        ratio: 1,
      });
      await shot(page);
    });
  });

  test('sysadmin-manual/FiringAreaMessages.png', async ({ page }) => {
    await loginAs(page, 'editor', 'niord-fa');
    await page.goto('/messages/table');
    await expect(page.getByText('ES D 138 Bornholm N.', { exact: false }).first()).toBeVisible();
    await shot(page);
  });

  test.describe('in a window as tall as the schedule form', () => {
    test.use({ viewport: { width: 1280, height: 560 } });

    test('sysadmin-manual/FiringScheduleEditPage.png', async ({ page }) => {
      await openSysadminSection(page, 'schedules');
      await page.getByRole('button', { name: 'Edit Schedule' }).first().click();
      const editor = drawer(page, 'Edit Firing Schedule');
      await expect(editor.locator('#schedule-days')).toBeVisible();
      await shot(page, { target: editor, pad: 0 });
    });
  });

  // --- 07 Dictionaries ---------------------------------------------------------------------

  test('sysadmin-manual/DictionariesPage.png', async ({ page }) => {
    await openDictionary(page, 'message');
    await shot(page, { guard: KEYS_SHOWN });
  });

  test('sysadmin-manual/DictionariesExtendedPage.png', async ({ page }) => {
    await openDictionary(page, 'aton');
    await expect(page.getByLabel('Show Extended')).toBeChecked();
    await page.getByPlaceholder('Filter').fill('cardinal');
    await expect(page.locator('app-admin-section-page table tbody tr')).not.toHaveCount(0);
    await page.getByPlaceholder('Filter').blur();
    await shot(page, { guard: KEYS_SHOWN });
  });

  test.describe('in a window as tall as the entry form', () => {
    test.use({ viewport: { width: 1280, height: 380 } });

    test('sysadmin-manual/DictionaryEntryEditPage.png', async ({ page }) => {
      await openDictionary(page, 'mail');
      await page.getByPlaceholder('Filter').fill('mail.dear.user');
      await rowWith(page, 'mail.dear.user').getByRole('button', { name: 'Edit Entry' }).click();
      const editor = drawer(page, 'Edit Entry');
      await expect(editor.locator('#dict-entry-key')).toHaveValue('mail.dear.user');
      await shot(page, { target: editor, pad: 0, guard: KEYS_SHOWN });
    });
  });

  test.describe('in a window as tall as the extended entry form', () => {
    test.use({ viewport: { width: 1280, height: 560 } });

    test('sysadmin-manual/DictionaryEntryExtendedEditPage.png', async ({ page }) => {
      await openDictionary(page, 'aton');
      await page.getByPlaceholder('Filter').fill('aton.function.buoy.north_cardinal');
      await rowWith(page, 'aton.function.buoy.north_cardinal')
        .getByRole('button', { name: 'Edit Entry' })
        .click();
      const editor = drawer(page, 'Edit Entry');
      await expect(editor.locator('#dict-entry-aton')).not.toHaveValue('');
      await shot(page, { target: editor, pad: 0, guard: KEYS_SHOWN });
    });
  });

  test('sysadmin-manual/DictionaryImportPreview.png', async ({ page }) => {
    await openDictionary(page, 'web');
    await page.getByRole('button', { name: 'Dictionary actions' }).click();
    await page.getByRole('menuitem', { name: 'Import...' }).click();
    const dialog = page.getByRole('dialog', { name: 'Upload Dictionary JSON File' });
    // An example file: one key the deployment lacks, one with another value. Choosing the file
    // shows the preview, which writes nothing; Import ticked is not clicked.
    const file = [
      {
        name: 'web',
        entries: [
          { key: 'guide.example.welcome', descs: [{ lang: 'en', value: 'Welcome to Niord' }] },
          { key: 'editor.save_msg', descs: [{ lang: 'en', value: 'Save message' }] },
        ],
      },
    ];
    // Chosen the way the browser would: a File in the input, then its change event.
    await dialog.locator('#dictionaryImportFile').evaluate(
      (input, text) => {
        const files = new DataTransfer();
        files.items.add(new File([text], 'web-dictionary.json', { type: 'application/json' }));
        (input as HTMLInputElement).files = files.files;
        input.dispatchEvent(new Event('change', { bubbles: true }));
      },
      JSON.stringify(file, null, 2),
    );
    await expect(dialog.getByRole('heading', { name: 'Would overwrite (1)' })).toBeVisible();
    await shot(page, { target: dialog, pad: 0, guard: KEYS_SHOWN });
  });

  test('sysadmin-manual/DictionaryDeleteConfirm.png', async ({ page }) => {
    await openDictionary(page, 'mail');
    await page.getByPlaceholder('Filter').fill('mail.dear.user');
    await rowWith(page, 'mail.dear.user').getByRole('button', { name: 'Delete Entry' }).click();
    const confirm = page.getByRole('alertdialog', { name: 'Delete entry?' });
    await expect(confirm.getByText(/script resource/)).toBeVisible();
    // Shown, then left with the window: nothing is deleted.
    await shot(page, { target: confirm, pad: 0, guard: KEYS_SHOWN });
  });

  // --- 08 Parameter Types ------------------------------------------------------------------

  test('sysadmin-manual/ParameterTypesPage.png', async ({ page }) => {
    await openSysadminSection(page, 'param-types');
    // A composite type and two list types, short enough to show whole. ("buoy" ended on the two
    // values of buoy_function_list that both read "buoy", aton.function.buoy and
    // aton.function.aton, which looked like one value listed twice.)
    await page.getByPlaceholder('Filter').fill('obstruction');
    await expect(rowWith(page, 'obstruction_type_list')).toBeVisible();
    await expect(rowWith(page, 'buoy_function_list')).toBeHidden();
    await page.getByPlaceholder('Filter').blur();
    await shot(page);
  });

  test('sysadmin-manual/ParameterTypeExample.png', async ({ page }) => {
    await openTemplate(page, 'Wreck');
    const wreck = page.locator('app-template-param-field').first();
    await expect(wreck).toContainText('Vessel Type');
    await shot(page, { target: wreck });
  });

  test('sysadmin-manual/ParameterTypeAddBtn.png', async ({ page }) => {
    await openSysadminSection(page, 'param-types');
    const trigger = page.getByRole('button', { name: 'New Parameter Type' });
    await trigger.click();
    const menu = page.getByRole('menu');
    await expect(menu.getByRole('menuitem', { name: 'Composite Param Type' })).toBeVisible();
    await shot(page, { target: [trigger, menu] });
  });

  test.describe('in a window as tall as the list drawer', () => {
    test.use({ viewport: { width: 1280, height: 720 } });

    test('sysadmin-manual/ParameterTypesListEditPage.png', async ({ page }) => {
      await openSysadminSection(page, 'param-types');
      await rowWith(page, 'buoy_function_list').getByRole('button', { name: 'Edit' }).click();
      const editor = drawer(page, 'Edit Parameter Type');
      await expect(editor.getByText('north cardinal buoy').first()).toBeVisible();
      // The available values of the dictionary the list's values come from, narrowed to buoys.
      await editor.getByRole('combobox', { name: 'Dictionary' }).click();
      await page.getByRole('option', { name: 'aton', exact: true }).click();
      await editor.getByPlaceholder('Filter').fill('buoy');
      await expect(editor.getByText('aton.function.buoy.special').last()).toBeVisible();
      await editor.getByPlaceholder('Filter').blur();
      await shot(page, { target: editor, pad: 0, guard: KEYS_SHOWN });
    });
  });

  test.describe('in a window as tall as the composite form', () => {
    test.use({ viewport: { width: 1280, height: 500 } });

    test('sysadmin-manual/ParameterTypesCompositeEditPage.png', async ({ page }) => {
      await openSysadminSection(page, 'param-types');
      await page.getByPlaceholder('Filter').fill('wreck');
      await rowWith(page, 'wreck').getByRole('button', { name: 'Edit' }).click();
      const editor = drawer(page, 'Edit Parameter Type');
      await expect(editor.locator('tbody tr')).toHaveCount(3);
      await shot(page, { target: editor, pad: 0 });
    });
  });

  test('sysadmin-manual/ParameterTypesCompositeDialog.png', async ({ page }) => {
    await openSysadminSection(page, 'param-types');
    await page.getByPlaceholder('Filter').fill('wreck');
    await rowWith(page, 'wreck').getByRole('button', { name: 'Edit' }).click();
    const editor = drawer(page, 'Edit Parameter Type');
    await editor.locator('tbody tr').first().getByRole('button', { name: 'Edit' }).click();
    const dialog = page.getByRole('dialog', { name: 'Edit Parameter', exact: true });
    await expect(dialog.locator('#tp-param-id')).not.toHaveValue('');
    await shot(page, { target: dialog, pad: 0 });
  });

  test.describe('in a window as tall as the positions and their parameters', () => {
    test.use({ viewport: { width: 1280, height: 1560 } });

    test('sysadmin-manual/ParameterTypeExample2.png', async ({ page }) => {
      await openTemplate(page, 'Buoy off station');
      // Two positions, typed in the template's Positions field: the Buoy parameter repeats
      // for each. Nothing is executed or saved.
      await page.getByRole('button', { name: 'Edit as text' }).first().click();
      await page
        .getByRole('dialog', { name: 'Edit as text' })
        .locator('textarea')
        .fill("56° 05.000'N 011° 30.000'E\n\n56° 10.000'N 011° 40.000'E");
      await page.getByRole('button', { name: 'Save geometry' }).click();
      // One Buoy parameter per position, each with its position's number beside it.
      const buoys = page.locator('div.flex.items-start:has(> app-template-param-field)');
      await expect(buoys).toHaveCount(2);
      await shot(page, {
        target: [page.locator('app-geometry-preview-field').first(), buoys.last()],
        backdrop: false,
      });
    });
  });
});
