import type { Locator, Page } from '@playwright/test';

import { scanPage } from '../a11y/axe';
import { expect, test } from '../auth/fixtures';
import { AdminApi, asTestUser, english, type MessageVo } from './support/api';
import { expectPositions, pointsOf } from './support/compare';
import { annotate, describeScenario } from './support/env';
import { knownFault } from './support/known-fault';
import { keepOffline } from './support/offline';
import { newRunMarker, RUN_MARKER } from './support/run-id';

/*
 * A navigational warning written, saved, verified and published with the keyboard alone, as a
 * duty officer who does not use a mouse takes it. After the first page load the walk presses keys
 * only: Tab, Shift+Tab, Enter, Space, the arrow keys, Escape and typing; no click, no fill and no
 * focus set by the test. After every step it asserts where the focus is and that it is visible,
 * that it comes back to the control a dialog or a calendar was opened from, and inside a list that
 * keeps DOM focus on its combobox, which option `aria-activedescendant` names. Every overlay it
 * opens is scanned with axe-core (a11y/axe.ts). The warning is read back through the admin API at
 * the end, so the walk proves that the keyboard path really published it.
 *
 * A control the keyboard cannot reach or operate is a finding: its keyboard path is asserted inside
 * `knownFault`, and the step is then done the nearest way a keyboard still allows, or by pointer
 * under a `known-fault` annotation, so the rest of the walk is still proved.
 *
 * The warning's vicinity carries the run's marker, and the end cancels it; a crashed run's warning
 * is cancelled by the next run of nw-warning.spec.ts, whose set-up cancels every warning in force
 * whose vicinity carries a marker. Each cancellation goes through the backend's cancel, which
 * publishes a cancellation warning in the auto-numbered NW series; that one stays in force.
 */

// The slice of Node's globals the file needs: the e2e tsconfig loads no Node types.
declare const Buffer: { from(data: string, encoding: 'utf8'): Uint8Array };

const RUN = newRunMarker();
const DOMAIN = 'niord-nw';
const TITLE = `${RUN} keyboard walk`;
const VICINITY_TEXT = 'keyboard harbour';
const VICINITY = `${RUN} ${VICINITY_TEXT}`;
const POINT_NAME = `${RUN} keyboard light`;
const TYPED_POSITION = "55° 59.99'N 012° 29.996'E";

/** The record of each fault the walk meets (fe/docs/backlog.md). */
const CALENDAR_FOCUS =
  "fe/docs/backlog.md: The date picker's calendar does not take focus when it opens";

let seriesId = '';
let messageId = '';

/* ---------------------------------------------------------------------------------------------- */
/* Where the focus is                                                                              */
/* ---------------------------------------------------------------------------------------------- */

interface Focused {
  readonly role: string;
  readonly name: string;
}

/** The focused element's role and accessible name, from its accessibility snapshot. */
async function focused(page: Page): Promise<Focused | undefined> {
  const element = page.locator(':focus');
  if ((await element.count()) === 0) return undefined;
  const snapshot = await element.first().ariaSnapshot({ timeout: 2_000 });
  const match = /^- (\w+)(?: "((?:[^"\\]|\\.)*)")?/.exec(snapshot.trim());
  return match ? { role: match[1], name: match[2] ?? '' } : undefined;
}

function describe(where: Focused | undefined): string {
  return where ? `${where.role} "${where.name}"` : 'nothing (the page body)';
}

/**
 * How the focused element shows that it has focus: an outline, a box shadow that is not
 * transparent, or a border in the primary colour, on it or on one of the three elements around it
 * (the select and the autocomplete draw it on their frame, `:focus-within`); `none` when there is
 * no such sign. Polled, because a field's ring fades in.
 */
async function focusIndicator(page: Page): Promise<string> {
  return page.evaluate(() => {
    const probe = document.createElement('span');
    probe.style.color = 'var(--niord-primary)';
    document.body.append(probe);
    const primary = getComputedStyle(probe).color;
    probe.remove();
    // A shadow is seen when its colour is not transparent and it has a blur or a spread.
    const seenShadow = (shadow: string): boolean =>
      shadow
        .split(/,(?![^(]*\))/)
        .some(
          (layer) =>
            !/(?:\/\s*0\)|,\s*0\)|transparent)/.test(layer) &&
            /(?:\s|^)[1-9][\d.]*px/.test(layer.replace(/^[^)]*\)/, '')),
        );
    let element: Element | null = document.activeElement;
    for (let depth = 0; element && element !== document.body && depth < 4; depth++) {
      const style = getComputedStyle(element);
      if (style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0) return 'outline';
      if (style.boxShadow !== 'none' && seenShadow(style.boxShadow)) return 'shadow';
      if (style.borderTopStyle !== 'none' && style.borderTopColor === primary) return 'border';
      element = element.parentElement;
    }
    return 'none';
  });
}

async function expectFocusVisible(page: Page, what: string): Promise<void> {
  await expect
    .poll(() => focusIndicator(page), {
      message: `the focus on ${what} is visible`,
      timeout: 2_000,
    })
    .not.toBe('none');
}

/** Asserts the focus is on the element of this role and name, and visible. */
async function expectFocus(page: Page, role: string, name: RegExp): Promise<void> {
  const where = await focused(page);
  expect(where?.role, `the focus is on a ${role} ${name}, not on ${describe(where)}`).toBe(role);
  expect(where?.name ?? '', `the focus is on a ${role} ${name}, not on ${describe(where)}`).toMatch(
    name,
  );
  await expectFocusVisible(page, `${role} ${name}`);
}

/**
 * Presses Tab (or Shift+Tab) until the focus is on the element of this role and name, at most
 * `max` times: the element is reached by the keyboard, and its focus is visible.
 */
async function tabTo(
  page: Page,
  role: string,
  name: RegExp,
  { back = false, max = 40 }: { back?: boolean; max?: number } = {},
): Promise<void> {
  for (let presses = 0; presses <= max; presses++) {
    if (presses > 0) await page.keyboard.press(back ? 'Shift+Tab' : 'Tab');
    const where = await focused(page);
    if (where?.role === role && name.test(where.name)) {
      await expectFocusVisible(page, `${role} ${name}`);
      return;
    }
  }
  throw new Error(
    `${back ? 'Shift+Tab' : 'Tab'} did not reach a ${role} ${name} in ${max} presses (at ${describe(await focused(page))})`,
  );
}

/** The option the focused combobox names as active (`aria-activedescendant`). */
async function activeOption(page: Page): Promise<string> {
  return page.evaluate(() => {
    const id = document.activeElement?.getAttribute('aria-activedescendant');
    const option = id ? document.getElementById(id) : null;
    return option?.getAttribute('role') === 'option' ? (option.textContent ?? '').trim() : '';
  });
}

/* ---------------------------------------------------------------------------------------------- */
/* The edit form's rows                                                                            */
/* ---------------------------------------------------------------------------------------------- */

/** A row head's accessible name starts with its label: "Type No type selected". */
function rowName(label: string): RegExp {
  return new RegExp(`^${label}\\b`, 'i');
}

const ROW_ORDER = [
  'Type',
  'ID',
  'Title',
  'References',
  'Dates',
  'Areas',
  'Categories',
  'Positions',
  'Subject',
  'Description',
];

/**
 * Moves from one row head to another with the arrow keys, the form's keyboard model for its rows:
 * the rows are one tab stop, and Up and Down move between them.
 */
async function rowTo(page: Page, label: string): Promise<void> {
  for (let presses = 0; presses < ROW_ORDER.length * 2; presses++) {
    const where = await focused(page);
    if (where?.role === 'button' && rowName(label).test(where.name)) {
      await expectFocusVisible(page, `the ${label} row`);
      return;
    }
    const at = ROW_ORDER.findIndex((row) => where && rowName(row).test(where.name));
    const target = ROW_ORDER.indexOf(label);
    await page.keyboard.press(at > target ? 'ArrowUp' : 'ArrowDown');
  }
  throw new Error(
    `the arrow keys did not reach the ${label} row (at ${describe(await focused(page))})`,
  );
}

/** Shift+Tab back to the open row's head. */
async function backToRow(page: Page, label: string): Promise<void> {
  await tabTo(page, 'button', rowName(label), { back: true, max: 12 });
}

/** Opens the focused row for editing. */
async function expandRow(page: Page, label: string): Promise<void> {
  await rowTo(page, label);
  await page.keyboard.press('Enter');
  await expect(page.locator('.ed-row-head:focus')).toHaveAttribute('aria-expanded', 'true');
}

/** Back on the row's head, closes it; the row then shows its value. */
async function collapseRow(page: Page, label: string): Promise<Locator> {
  await backToRow(page, label);
  await page.keyboard.press('Enter');
  const head = page.locator('.ed-row-head:focus');
  await expect(head).toHaveAttribute('aria-expanded', 'false');
  return head;
}

/** The overlay panels a step opens are in the overlay container, so they are scanned there. */
const LISTBOX = '.cdk-overlay-container [role="listbox"]';

/* ---------------------------------------------------------------------------------------------- */
/* The scenario                                                                                    */
/* ---------------------------------------------------------------------------------------------- */

describeScenario('a navigational warning written and published from the keyboard alone', () => {
  test.beforeEach(async ({ page, baseURL }) => {
    await keepOffline(page, baseURL);
    await page.addInitScript((domain) => {
      localStorage.setItem('niord.sessionDomainId', domain);
    }, DOMAIN);
  });

  test.afterAll(async ({ playwright }, testInfo) => {
    if (!messageId) return;
    const baseURL = String(testInfo.project.use.baseURL ?? '');
    // The walk's warning is cancelled, so it does not stay in force on the sample; it stays as the
    // run's record. The cancellation warning the backend publishes for it does stay in force.
    await asTestUser(playwright, baseURL, 'sysadmin', DOMAIN, async (request) => {
      const path = `/rest/messages/message/${messageId}`;
      const message = (await (await request.get(path)).json()) as MessageVo;
      if (message.status !== 'PUBLISHED') return;
      const response = await request.put(`${path}/status`, {
        data: Buffer.from('CANCELLED', 'utf8'),
        headers: { 'Content-Type': 'application/json' },
      });
      expect(response.ok(), `the walk's warning ${messageId} cancelled`).toBe(true);
    });
  });

  test('the editor writes, saves, verifies and publishes a warning by keyboard', async ({
    editorPage: page,
  }) => {
    const api = new AdminApi(page, DOMAIN);

    await test.step('what an earlier walk left in force is cancelled', async () => {
      // A walk that crashed after publishing skipped its end: its warning stays in force.
      const inForce = await api.get<{ data: MessageVo[] }>('/rest/messages/search', {
        lang: 'en',
        mainType: 'NW',
        status: 'PUBLISHED',
        maxSize: 1000,
      });
      for (const message of inForce.data) {
        const vicinity = english(message.descs, 'vicinity');
        if (!RUN_MARKER.test(vicinity) || !vicinity.endsWith(VICINITY_TEXT)) continue;
        await api.putWord(`/rest/messages/message/${message.id}/status`, 'CANCELLED');
        annotate('tidy', `an earlier walk's warning ${message.shortId ?? message.id} cancelled`);
      }
    });

    const domains = await api.get<
      {
        domainId: string;
        messageSeries?: { seriesId: string; mainType: string; shortFormat?: string }[];
      }[]
    >('/rest/domains/all', { lang: 'en' });
    seriesId =
      domains
        .find((domain) => domain.domainId === DOMAIN)
        ?.messageSeries?.find((series) => series.mainType === 'NW' && !!series.shortFormat)
        ?.seriesId ?? '';
    expect(seriesId, `the sample's ${DOMAIN} has an NW series with short ids`).not.toBe('');

    // The only navigation the test makes; from here on, keys only.
    await page.goto('/editor');
    await expect(page.getByRole('heading', { name: /create a message/i })).toBeVisible();

    await test.step('a blank warning is started from the Start screen', async () => {
      await tabTo(page, 'button', /^new blank nw$/i);
      await page.keyboard.press('Enter');
      await page.waitForURL(/\/editor\/edit(?:[?#]|$)/);
      await expect(page.getByRole('button', { name: /^save$/i })).toBeVisible();
      // The rows of the form are one tab stop.
      await tabTo(page, 'button', new RegExp(`^(?:${ROW_ORDER.join('|')})\\b`, 'i'));
    });

    await test.step('Type: Coastal', async () => {
      await expandRow(page, 'Type');
      await tabTo(page, 'button', /^coastal$/i, { max: 6 });
      await page.keyboard.press('Enter');
      await expect(page.locator(':focus')).toHaveAttribute('aria-pressed', 'true');
      const head = await collapseRow(page, 'Type');
      await expect(head).toContainText(/coastal/i);
    });

    await test.step('ID: the series, from the select', async () => {
      await expandRow(page, 'ID');
      await tabTo(page, 'combobox', /^message series$/i, { max: 6 });
      await page.keyboard.press('ArrowDown');
      await expect(page.locator(':focus')).toHaveAttribute('aria-expanded', 'true');
      await scanPage(page, 'keyboard walk: the series list', { include: LISTBOX });
      for (let presses = 0; presses < 20 && (await activeOption(page)) !== seriesId; presses++) {
        await page.keyboard.press('ArrowDown');
      }
      expect(await activeOption(page), 'the active option').toBe(seriesId);
      await page.keyboard.press('Enter');
      // The select keeps DOM focus on its combobox; the list closes and the choice shows.
      await expectFocus(page, 'combobox', /^message series$/i);
      await expect(page.locator(':focus')).toHaveAttribute('aria-expanded', 'false');
      await expect(page.locator(':focus')).toContainText(seriesId);
      await collapseRow(page, 'ID');
    });

    await test.step('Title: typed, the automatic title switched off', async () => {
      await expandRow(page, 'Title');
      await tabTo(page, 'checkbox', /^auto-generate title$/i, { max: 6 });
      await page.keyboard.press('Space');
      await expect(page.locator(':focus')).not.toBeChecked();
      await tabTo(page, 'textbox', /^text in english$/i, { max: 4 });
      await page.keyboard.type(TITLE);
      const head = await collapseRow(page, 'Title');
      await expect(head).toContainText(TITLE);
    });

    await test.step('Dates: the follow-up date, from the calendar', async () => {
      // The follow-up date, not the publish date: a verified warning with a publish date is
      // published by the backend at that time and offers no Publish button.
      await expandRow(page, 'Dates');
      await tabTo(page, 'button', /^follow-up date$/i, { max: 8 });
      await page.keyboard.press('Enter');
      const calendar = page.locator('app-calendar');
      await expect(calendar).toBeVisible();
      await scanPage(page, 'keyboard walk: the calendar', { include: 'app-calendar' });
      await knownFault(CALENDAR_FOCUS, 'the calendar does not take focus when it opens', () =>
        expect(
          page.locator('app-calendar :focus'),
          'the focus moves into the calendar when it opens',
        ).toHaveCount(1, { timeout: 2_000 }),
      );
      // The calendar sits at the end of the page, so Tab reaches it after the rest of the form;
      // its days are one tab stop, today's.
      await tabTo(page, 'gridcell', /\d{4}$/, { max: 60 });
      const today = (await focused(page))?.name ?? '';
      // The arrow keys move between the days: tomorrow is picked.
      await page.keyboard.press('ArrowRight');
      await expect.poll(async () => (await focused(page))?.name).not.toBe(today);
      await expectFocusVisible(page, 'the day after today');
      await page.keyboard.press('Enter');
      await expect(calendar).toBeHidden();
      // The focus comes back to the control the calendar was opened from, which shows the day.
      await expectFocus(page, 'button', /^follow-up date$/i);
      await expect(page.locator(':focus')).toHaveText(/\d/);
      await collapseRow(page, 'Dates');
    });

    await test.step('Areas: one picked from the autocomplete, and the vicinity typed', async () => {
      await expandRow(page, 'Areas');
      await tabTo(page, 'combobox', /^select areas$/i, { max: 6 });
      const field = page.locator('.ed-row.open .ed-row-editor');
      const pickBaltic = async (scan: boolean): Promise<void> => {
        await page.keyboard.type('Baltic');
        await expect(
          page
            .getByRole('listbox', { name: /^select areas$/i })
            .getByRole('option', { name: /baltic/i }),
        ).toBeVisible();
        await page.keyboard.press('ArrowDown');
        await expect
          .poll(() => activeOption(page), { message: 'the active option' })
          .toMatch(/baltic/i);
        if (scan) await scanPage(page, 'keyboard walk: the area list', { include: LISTBOX });
        await page.keyboard.press('Enter');
        await expectFocus(page, 'combobox', /^select areas$/i);
        await expect(field).toContainText(/baltic/i);
      };
      await pickBaltic(true);

      // The chip's remove button is named after its area and removes it on Enter; the focus goes
      // back to the box, not to the page's body.
      await tabTo(page, 'button', /^remove\b.*baltic/i, { back: true, max: 2 });
      await page.keyboard.press('Enter');
      await expect(field.locator('.ac-chip'), 'the chip is removed').toHaveCount(0, {
        timeout: 2_000,
      });
      await expectFocus(page, 'combobox', /^select areas$/i);
      // The area is picked again for the warning.
      await pickBaltic(false);

      await tabTo(page, 'textbox', /^text in english$/i, { max: 6 });
      await page.keyboard.type(VICINITY);
      const head = await collapseRow(page, 'Areas');
      await expect(head).toContainText(VICINITY);
    });

    await test.step('Categories: one picked from the autocomplete', async () => {
      await expandRow(page, 'Categories');
      await tabTo(page, 'combobox', /^select categories$/i, { max: 6 });
      await page.keyboard.type('Light');
      await expect(
        page
          .getByRole('listbox', { name: /^select categories$/i })
          .getByRole('option', { name: 'Light', exact: true }),
      ).toBeVisible();
      await page.keyboard.press('ArrowDown');
      await expect.poll(() => activeOption(page), { message: 'the active option' }).toBe('Light');
      await scanPage(page, 'keyboard walk: the category list', { include: LISTBOX });
      await page.keyboard.press('Enter');
      await expectFocus(page, 'combobox', /^select categories$/i);
      const head = await collapseRow(page, 'Categories');
      await expect(head).toContainText('Light');
    });

    await test.step('Positions: a point, typed in the Edit as text dialog', async () => {
      await expandRow(page, 'Positions');
      await tabTo(page, 'button', /^edit as text$/i, { max: 6 });
      await page.keyboard.press('Enter');
      const dialog = page.getByRole('dialog', { name: /edit as text/i });
      await expect(dialog).toBeVisible();
      await expect(dialog.locator(':focus'), 'the focus moves into the dialog').toHaveCount(1);
      await tabTo(page, 'textbox', /^edit as text$/i, { max: 6 });
      await page.keyboard.type(`Point, en: ${POINT_NAME}`);
      await page.keyboard.press('Enter');
      await page.keyboard.type(TYPED_POSITION);
      await expect(dialog).toContainText(/1 feature/);
      await scanPage(page, 'keyboard walk: the Edit as text dialog', {
        include: '[role="dialog"]',
      });
      await tabTo(page, 'button', /^save geometry$/i, { max: 6 });
      await page.keyboard.press('Enter');
      await expect(dialog).toBeHidden();
      // The focus comes back to the button the dialog was opened from.
      await expectFocus(page, 'button', /^edit as text$/i);
      const head = await collapseRow(page, 'Positions');
      await expect(head).not.toContainText(/no positions/i);
    });

    await test.step('the warning is saved', async () => {
      await tabTo(page, 'button', /^save$/i, { back: true, max: 40 });
      await page.keyboard.press('Enter');
      await page.waitForURL(/\/editor\/edit\/[^/?#]+/);
      await expect(page.getByText(/all changes saved/i)).toBeVisible();
      messageId = /\/editor\/edit\/([^/?#]+)/.exec(page.url())?.[1] ?? '';
      expect(messageId).not.toBe('');
    });

    await test.step('the Status tab is reached and the warning verified', async () => {
      await tabTo(page, 'link', /^status$/i, { max: 80 });
      await page.keyboard.press('Enter');
      await page.waitForURL(/\/editor\/status\/[^/?#]+/);
      await expect(page.getByText(/status:\s*draft/i)).toBeVisible();

      await tabTo(page, 'button', /^verify message/i, { max: 60 });
      await page.keyboard.press('Enter');
      const confirm = page.getByRole('alertdialog');
      await expect(confirm).toBeVisible();
      await expect(confirm.locator(':focus'), 'the focus moves into the confirmation').toHaveCount(
        1,
      );
      await scanPage(page, 'keyboard walk: the Verify confirmation', {
        include: '[role="alertdialog"]',
      });
      await tabTo(page, 'button', /^verify$/i, { max: 6 });
      await page.keyboard.press('Enter');
      await expect(confirm).toBeHidden();
      await expect(page.getByText(/status:\s*verified/i)).toBeVisible();
    });

    await test.step('the warning is published', async () => {
      await tabTo(page, 'button', /^publish message/i, { max: 60 });
      await page.keyboard.press('Enter');
      const dialog = page.getByRole('dialog', { name: /confirm publication/i });
      await expect(dialog).toBeVisible();
      await expect(dialog.locator(':focus'), 'the focus moves into the dialog').toHaveCount(1);
      await scanPage(page, 'keyboard walk: the Publish confirmation', {
        include: '[role="dialog"]',
      });
      await tabTo(page, 'button', /^publish$/i, { max: 12 });
      await page.keyboard.press('Enter');
      await expect(dialog).toBeHidden();
      await expect(page.getByText(/status:\s*published/i)).toBeVisible();
    });

    await test.step('the admin API holds the warning the keys wrote, published', async () => {
      const message = await api.get<MessageVo>(`/rest/messages/editable-message/${messageId}`, {
        lang: 'en',
      });
      expect(message.status).toBe('PUBLISHED');
      expect(message.type).toBe('COASTAL_WARNING');
      expect(message.messageSeries?.seriesId).toBe(seriesId);
      expect(english(message.descs, 'title')).toBe(TITLE);
      expect(english(message.descs, 'vicinity')).toBe(VICINITY);
      expect(message.areas?.map((area) => english(area.descs, 'name')).join(' ')).toMatch(
        /baltic/i,
      );
      expect(message.categories?.map((category) => english(category.descs, 'name'))).toContain(
        'Light',
      );
      expect(message.publishDateFrom, 'published: a publish date').toBeTruthy();
      expect(
        (message as MessageVo & { followUpDate?: unknown }).followUpDate,
        'the follow-up date',
      ).toBeTruthy();
      expectPositions(
        'stored position',
        TYPED_POSITION,
        pointsOf(message.parts?.[0]?.geometry?.features ?? []),
      );
    });
  });
});
