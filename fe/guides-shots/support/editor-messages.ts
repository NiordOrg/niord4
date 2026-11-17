import { expect, type APIRequestContext, type Locator, type Page } from '@playwright/test';

import { authHeaders, englishMessageIds, psql } from './backend';
import { BACKEND_URL, DOMAIN } from './env';
import { settle } from './stabilise';

/*
 * Helpers of the editor manual's Home and Messages chapters (editor-manual.shots.ts): the Messages
 * page's filter menu and panels, its Actions menu, the selection star, and the example messages
 * those chapters' pictures show. Nothing here writes to the backend: a filter, a selection, an
 * open dialog or a form typed into lives in the page only, and no test presses a button that saves.
 */

/** A message's texts in one language, the fields the helpers below read. */
interface SearchedDesc {
  readonly lang: string;
  readonly title?: string | null;
  readonly publication?: string | null;
  readonly internalPublication?: string | null;
}

/** The fields of a message the helpers below read from a search answer. */
export interface SearchedMessage {
  readonly id: string;
  readonly shortId?: string;
  readonly status?: string;
  readonly parts?: readonly { readonly geometry?: { readonly features?: readonly unknown[] } }[];
  readonly descs?: readonly SearchedDesc[];
  readonly attachments?: readonly unknown[];
}

/** The messages of a domain in a status, as an editor searches them, in the search's own order. */
async function search(
  request: APIRequestContext,
  status: string,
  domain: string = DOMAIN,
): Promise<readonly SearchedMessage[]> {
  const response = await request.get(`${BACKEND_URL}/rest/messages/search`, {
    params: { domain, status, lang: 'en', maxSize: 200 },
    headers: await authHeaders('editor', domain),
  });
  if (!response.ok()) {
    throw new Error(`${response.url()} answered ${response.status()} ${response.statusText()}`);
  }
  const body = (await response.json()) as { readonly data?: readonly SearchedMessage[] };
  const english = englishMessageIds(domain, status);
  return (body.data ?? []).filter((message) => english.has(message.id));
}

function englishDesc(message: SearchedMessage): SearchedDesc | undefined {
  return message.descs?.find((desc) => desc.lang === 'en');
}

function drawn(message: SearchedMessage): boolean {
  return (message.parts ?? []).some((part) => (part.geometry?.features?.length ?? 0) > 0);
}

/**
 * A published message of the domain for the details panel: English throughout, with a position
 * (so the panel shows its map), no publication line (the copy's publication lines cite the case
 * numbers of an internal case system) and no attachment; the first in the search's order.
 */
export async function plainPublishedMessage(request: APIRequestContext): Promise<SearchedMessage> {
  const found = (await search(request, 'PUBLISHED')).find((message) => {
    const desc = englishDesc(message);
    return (
      drawn(message) &&
      !(desc?.publication ?? '').trim() &&
      !(desc?.internalPublication ?? '').trim() &&
      (message.attachments ?? []).length === 0
    );
  });
  if (!found) throw new Error('No published message without a publication line and with a map.');
  return found;
}

/**
 * A published message of the domain that is in an unlocked domain tag of the domain, so its cog
 * menu offers "Remove from…": the first in the search's order.
 */
export async function taggedPublishedMessage(request: APIRequestContext): Promise<SearchedMessage> {
  const tagged = new Set(
    psql(
      `select distinct m.uid from "MessageTag" t join "MessageTag_Message" tm on tm."tags_id" = t.id` +
        ` join "Message" m on m.id = tm."messages_id" join "Domain" d on d.id = t.domain_id` +
        ` where t.type = 'DOMAIN' and not t.locked and d."domainId" = '${DOMAIN}'` +
        ` and m.status = 'PUBLISHED'`,
      'the published messages in a domain tag',
    )
      .split(/\r?\n/)
      .map((uid) => uid.trim())
      .filter(Boolean),
  );
  const found = (await search(request, 'PUBLISHED')).find((message) => tagged.has(message.id));
  if (!found) throw new Error(`No published message of ${DOMAIN} is in an unlocked domain tag.`);
  return found;
}

/**
 * Two published messages of the domain with the same English title (one buoy reported twice, a
 * warning and its repetition): the pair the comparison pictures show, the first such pair in the
 * search's order.
 */
export async function similarPublishedPair(
  request: APIRequestContext,
): Promise<readonly [SearchedMessage, SearchedMessage]> {
  const byTitle = new Map<string, SearchedMessage>();
  for (const message of await search(request, 'PUBLISHED')) {
    const title = englishDesc(message)?.title?.trim();
    if (!title) continue;
    const earlier = byTitle.get(title);
    if (earlier) return [earlier, message];
    byTitle.set(title, message);
  }
  throw new Error('No two published messages share an English title.');
}

/** The first message of the domain in a status that is English throughout. */
export async function firstWithStatus(
  request: APIRequestContext,
  status: string,
): Promise<SearchedMessage> {
  const found = (await search(request, status))[0];
  if (!found) throw new Error(`No message in the status ${status} is English throughout.`);
  return found;
}

/** Waits until the list shows its first message (a row, a card or a details row). */
export async function waitForMessages(page: Page): Promise<void> {
  await expect(page.locator('app-message-selection-toggle').first()).toBeVisible();
  await settle(page, { maps: false });
}

/**
 * Waits until the open Filter menu holds still. The menu loads the saved filters when it opens and
 * shows a "Loading…" row at its top until they are in; the row then goes and every item below it
 * moves up, so a click aimed at one filter while it shows lands on the next one.
 */
export async function settleFilterMenu(page: Page): Promise<void> {
  const menu = page.getByRole('menu', { name: 'Filter' });
  await expect(menu).toBeVisible();
  await expect(menu.getByRole('menuitem', { name: 'Loading…' })).toHaveCount(0);
  await settle(page, { maps: false });
}

/**
 * Switches a filter on or off from the Filter menu. The menu opens again after each switch, so
 * a reader can switch several; it is closed here once it has opened again.
 */
export async function toggleFilter(page: Page, name: string): Promise<void> {
  const trigger = page.getByRole('button', { name: /^Filter/ });
  const menu = page.getByRole('menu', { name: 'Filter' });
  if (!(await menu.isVisible())) await trigger.click();
  await settleFilterMenu(page);
  await menu.getByRole('menuitem', { name, exact: true }).click();
  // The menu closes on the click and opens again a moment later; its trigger closes it.
  await page.waitForTimeout(300);
  await expect(menu).toBeVisible();
  await trigger.click();
  await expect(menu).toBeHidden();
}

/** A filter's panel above the list, by its label ("Search" for the text filter). */
export function filterPanel(page: Page, label: string): Locator {
  return page.getByRole('group', { name: label, exact: true });
}

/**
 * Types into a picker (a combobox) and chooses the option the text names, as a reader picks a
 * value from the suggestions.
 */
export async function pick(
  page: Page,
  combobox: Locator,
  typed: string,
  option: string | RegExp,
): Promise<void> {
  await combobox.click();
  await combobox.pressSequentially(typed, { delay: 40 });
  const choice = page.getByRole('option', { name: option }).first();
  await choice.click();
  // The suggestions close once a value is picked; a list still open is closed by moving the focus
  // away (Escape would also close a dialog the picker sits in).
  const listbox = page.getByRole('listbox');
  // A picker of one value replaces its box by the value's chip, so the box may be gone between
  // the look at the list and the key (seen once in a whole run): then there is nothing to leave.
  if ((await listbox.isVisible()) && (await combobox.isVisible())) {
    await combobox.press('Tab', { timeout: 2000 }).catch(() => undefined);
  }
  await settle(page, { maps: false });
}

/** The selection star of the row, card or details row that links to a message. */
export function starOf(page: Page, messageId: string): Locator {
  return page
    .locator('tr, app-message-grid-card, app-message-details-row')
    .filter({ has: page.locator(`a[href*="messageId=${messageId}"]`) })
    .first()
    .locator('app-message-selection-toggle');
}

/** Stars the first `count` messages of the list, in its order. */
export async function starFirst(page: Page, count: number): Promise<void> {
  for (let index = 0; index < count; index++) {
    await page.locator('app-message-selection-toggle').nth(index).click();
  }
  await expect(page.locator('.badge-accent').first()).toHaveText(String(count));
}

/**
 * Folds every language block of a data comparison (the Compare dialog's Data tab), as a reader
 * folds a node by clicking its label: each object that holds a `lang` field, a message's or a
 * part's or an area's texts in one language. The copy keeps the Danish texts of every message,
 * and an English text block names the internal case system in its internal publication, so the
 * picture shows the data's structure and its changed values with the texts folded away.
 */
export async function foldLanguageBlocks(pane: Locator): Promise<void> {
  const folded = await pane.evaluate((root) => {
    const buttons = Array.from(root.querySelectorAll('button[aria-expanded="true"]'));
    const targets = buttons.filter((button) => {
      const list = button.nextElementSibling;
      if (!list || list.tagName !== 'UL') return false;
      return Array.from(list.children).some((item) =>
        (item.firstElementChild?.firstElementChild?.textContent ?? '').trim().startsWith('lang:'),
      );
    });
    targets.forEach((button) => (button as HTMLButtonElement).click());
    return targets.length;
  });
  if (folded === 0) throw new Error('The data comparison holds no language block to fold.');
  await expect(pane.locator('text=/^lang:/')).toHaveCount(0);
}

/**
 * Folds the named groups of a data comparison by clicking their labels, as a reader does; the first
 * group of each name in the tree's order (a top-level one, for the message's own groups).
 */
export async function foldGroups(pane: Locator, labels: readonly string[]): Promise<void> {
  for (const label of labels) {
    const button = pane.getByRole('button', { name: label, exact: true }).first();
    await expect(button).toHaveAttribute('aria-expanded', 'true');
    await button.click();
    await expect(button).toHaveAttribute('aria-expanded', 'false');
  }
  // A label under the pointer is underlined; the pointer is moved off the dialog.
  await pane.page().mouse.move(4, 4);
}

/** Opens the Actions menu of the Messages page and returns it. */
export async function openActions(page: Page): Promise<Locator> {
  await page.getByRole('button', { name: /^Actions/ }).click();
  const menu = page.getByRole('menu', { name: 'Actions' });
  await expect(menu).toBeVisible();
  return menu;
}

/** Runs an item of the Actions menu. */
export async function runAction(page: Page, item: string | RegExp): Promise<void> {
  const menu = await openActions(page);
  await menu.getByRole('menuitem', { name: item }).click();
}
