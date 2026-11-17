import { expect, type APIRequestContext, type Locator, type Page } from '@playwright/test';

import { englishMessageIds } from './backend';
import { INFO_URL } from './env';
import { infoUrl } from './open';
import { shot, type ShotOptions } from './shot';
import { settle } from './stabilise';

/*
 * The public site's helpers for the public-site guide and the integration documents: which
 * published message a picture shows, and how to wait for each of the site's views.
 */

/** A message as the public API returns it: the fields a picture is chosen by. */
export interface PublicMessage {
  readonly id: string;
  readonly shortId?: string;
  readonly mainType?: 'NW' | 'NM';
  readonly type?: string;
  readonly originalInformation?: boolean;
  readonly references?: readonly unknown[];
  readonly charts?: readonly unknown[];
  readonly attachments?: readonly unknown[];
  readonly areas?: readonly PublicArea[];
  readonly descs?: readonly {
    readonly lang: string;
    readonly title?: string;
    readonly source?: string;
    readonly publication?: string;
  }[];
  readonly parts?: readonly {
    readonly type?: string;
    readonly descs?: readonly { readonly lang: string; readonly details?: string }[];
    readonly geometry?: {
      readonly features?: readonly {
        readonly geometry?: { readonly type?: string; readonly coordinates?: unknown };
        readonly properties?: Readonly<Record<string, unknown>>;
      }[];
    };
  }[];
}

/** An area as a message carries it: its id, its name and the area above it. */
export interface PublicArea {
  readonly id: number;
  readonly descs?: readonly { readonly lang: string; readonly name?: string }[];
  readonly parent?: PublicArea;
}

/** The top of the area tree a message's first area belongs to (a country). */
export function rootArea(message: PublicMessage): PublicArea {
  let area = message.areas?.[0];
  if (!area) throw new Error(`The message ${message.shortId ?? message.id} has no area.`);
  while (area.parent) area = area.parent;
  return area;
}

/**
 * A picture of the site. A message's text may hold a web address written without its scheme
 * (`www.example.dk`), which the guard's look for translation keys would take for a key with dots;
 * it is data, so the addresses the page shows once it is still are passed as text it may show.
 */
export async function siteShot(page: Page, options: ShotOptions = {}): Promise<string> {
  await settle(page, options);
  const allowText = [...(options.guard?.allowText ?? []), ...(await webAddressTexts(page))];
  return shot(page, { ...options, guard: { ...options.guard, allowText } });
}

/** The texts of the page that are a web address written without its scheme. */
async function webAddressTexts(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const found = new Set<string>();
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const text = (node.textContent ?? '').trim();
      if (/^www\.[a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,}$/i.test(text)) found.add(text);
    }
    return [...found];
  });
}

/**
 * The address the page asks the browser to open in a new tab (the print buttons' PDF), caught
 * instead of opened: a PDF in a tab of its own is a download in a headless browser, so the
 * picture fetches and draws it itself (openPdfFirstPage). Returns the address, made absolute.
 */
export async function catchOpenedAddress(page: Page, act: () => Promise<void>): Promise<string> {
  await page.evaluate(() => {
    const opened: string[] = [];
    (window as unknown as { guidesOpened: string[] }).guidesOpened = opened;
    window.open = (url?: string | URL) => {
      opened.push(String(url ?? ''));
      return null;
    };
  });
  await act();
  await expect
    .poll(() =>
      page.evaluate(() => (window as unknown as { guidesOpened: string[] }).guidesOpened.length),
    )
    .toBeGreaterThan(0);
  const address = await page.evaluate(
    () => (window as unknown as { guidesOpened: string[] }).guidesOpened[0],
  );
  return new URL(address, `${INFO_URL}/`).toString();
}

/** The domains whose published messages the copy's English rule is read for. */
const DOMAINS = ['niord-nw', 'niord-nm'] as const;

/**
 * Every published message the public site lists, in its own order, as the site reads them (in
 * English, repository links left relative), kept to those that are English throughout by the
 * copy's own rule (englishMessageIds).
 */
export async function englishPublicMessages(
  request: APIRequestContext,
): Promise<readonly PublicMessage[]> {
  const response = await request.get(`${INFO_URL}/rest/public/v1/messages`, {
    params: { lang: 'en', externalize: 'false' },
  });
  if (!response.ok()) {
    throw new Error(`The public site's message list answered ${response.status()}`);
  }
  const english = new Set(DOMAINS.flatMap((domain) => [...englishMessageIds(domain, 'PUBLISHED')]));
  return ((await response.json()) as PublicMessage[]).filter((message) => english.has(message.id));
}

/** The GeoJSON geometry types a message's parts carry. */
export function geometryTypes(message: PublicMessage): ReadonlySet<string> {
  return new Set(
    (message.parts ?? []).flatMap((part) =>
      (part.geometry?.features ?? []).map((feature) => feature.geometry?.type ?? ''),
    ),
  );
}

/** Whether any position of the message carries a name in English (`name:<n>:en`). */
export function hasNamedPositions(message: PublicMessage): boolean {
  return (message.parts ?? []).some((part) =>
    (part.geometry?.features ?? []).some((feature) =>
      Object.keys(feature.properties ?? {}).some((key) => /^name:\d+:en$/.test(key)),
    ),
  );
}

/** How many parts of the message hold a position table. */
export function positionTables(message: PublicMessage): number {
  return (message.parts ?? []).filter((part) =>
    (part.descs ?? []).some((desc) => /position-table/.test(desc.details ?? '')),
  ).length;
}

/**
 * Whether a part of the message gives a position as an item of a bulleted list, without the
 * number a position table gives it (a degree sign inside a list item).
 */
export function hasListedPositions(message: PublicMessage): boolean {
  return (message.parts ?? []).some((part) =>
    (part.descs ?? []).some((desc) =>
      /<li\b[^>]*>(?:(?!<\/li>)[\s\S])*?(?:°|&deg;)/i.test(desc.details ?? ''),
    ),
  );
}

/** The first point among the message's positions, as [longitude, latitude]. */
export function firstPoint(message: PublicMessage): [number, number] {
  for (const part of message.parts ?? []) {
    for (const feature of part.geometry?.features ?? []) {
      const coordinates = feature.geometry?.coordinates;
      if (
        feature.geometry?.type === 'Point' &&
        Array.isArray(coordinates) &&
        typeof coordinates[0] === 'number' &&
        typeof coordinates[1] === 'number'
      ) {
        return [coordinates[0], coordinates[1]];
      }
    }
  }
  throw new Error(`The message ${message.shortId ?? message.id} has no point.`);
}

/** The English description of a message. */
export function englishDesc(message: PublicMessage): NonNullable<PublicMessage['descs']>[number] {
  const desc = message.descs?.find((entry) => entry.lang === 'en') ?? message.descs?.[0];
  if (!desc) throw new Error(`The message ${message.shortId ?? message.id} has no description.`);
  return desc;
}

/**
 * The example message of a picture: the one with the preferred short id when the data still
 * holds it, English throughout and fit for the picture, otherwise the first message in the
 * site's own order that fits. The preferred id keeps the same example on every run of the same
 * data; the rule is what a new copy of the data is held to.
 */
export async function exampleMessage(
  request: APIRequestContext,
  preferredShortId: string,
  fits: (message: PublicMessage) => boolean,
  what: string,
): Promise<PublicMessage> {
  const messages = await englishPublicMessages(request);
  const message =
    messages.find((candidate) => candidate.shortId === preferredShortId && fits(candidate)) ??
    messages.find(fits);
  if (!message) throw new Error(`No published message that is English throughout is ${what}.`);
  return message;
}

/**
 * The published navigational warning the guide's message window shows: a coastal warning with
 * a position, so its window has a map, and different from the notices the integration
 * documents show.
 */
export function guideWarning(request: APIRequestContext): Promise<PublicMessage> {
  return exampleMessage(
    request,
    'NW-348-26',
    (message) =>
      message.mainType === 'NW' &&
      message.type === 'COASTAL_WARNING' &&
      geometryTypes(message).has('Point'),
    'a coastal warning with a position',
  );
}

/** Opens a view of the public site, in English, and waits for its list or map to arrive. */
export async function openInfoView(page: Page, path: string): Promise<void> {
  await page.goto(infoUrl(path));
  await expect(page.locator('app-main-menu nav')).toBeVisible();
  await expect(page.locator('[aria-busy="true"]')).toHaveCount(0, { timeout: 60_000 });
}

/** The message window the site opens over a view, once its message has arrived. */
export async function messageWindow(page: Page): Promise<Locator> {
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await expect(dialog.locator('.message-title').first()).toBeVisible({ timeout: 60_000 });
  await expect(dialog.locator('[aria-busy="true"]')).toHaveCount(0, { timeout: 60_000 });
  return dialog;
}

/**
 * How many lines each message number in an element takes: one when the number stands whole, two
 * or three when the layout around it has squeezed it and it breaks at its hyphens.
 */
export function badgeLines(element: Locator): Promise<number[]> {
  return element.evaluate((root) =>
    Array.from(root.querySelectorAll('.mono-id')).map((badge) => {
      const range = document.createRange();
      range.selectNodeContents(badge);
      return new Set(Array.from(range.getClientRects()).map((line) => Math.round(line.top))).size;
    }),
  );
}
