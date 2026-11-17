import type { APIRequestContext, Page } from '@playwright/test';

import { fixClock } from './support/clock';
import { expect, test } from './support/fixtures';
import { showMapAt } from './support/map';
import { infoUrl } from './support/open';
import {
  englishDesc,
  exampleMessage,
  geometryTypes,
  hasNamedPositions,
  messageWindow,
  openInfoView,
  positionTables,
  siteShot,
  type PublicMessage,
} from './support/public-site';

/*
 * The integration documents' pictures, written to guides/model/images/, guides/presentation/images/
 * and guides/public-api/images/: what a third party's client shows, taken from the public site.
 *
 * One test per picture, titled `<manual>/<file>` after the file it writes, so
 * `--grep "<manual>/<file>"` takes one picture again and manifest-check.mjs reads the list from the
 * titles. Every manifest entry marked retake, replace or new becomes one test here.
 */

/** A temporary notice that shows most of what a message can carry: the documents' examples. */
function isFullNotice(message: PublicMessage): boolean {
  const desc = message.descs?.find((entry) => entry.lang === 'en');
  return (
    message.mainType === 'NM' &&
    message.type === 'TEMPORARY_NOTICE' &&
    message.originalInformation === true &&
    (message.references?.length ?? 0) > 0 &&
    (message.charts?.length ?? 0) > 0 &&
    Boolean(desc?.publication) &&
    Boolean(desc?.source) &&
    hasNamedPositions(message)
  );
}

/**
 * The presentation document's example, without and with its map: a notice of one light, whose
 * position table and point on the map carry the same name.
 */
function presentationNotice(request: APIRequestContext): Promise<PublicMessage> {
  return exampleMessage(
    request,
    'NM-811-26',
    (message) =>
      isFullNotice(message) && positionTables(message) > 0 && geometryTypes(message).has('Point'),
    'a temporary notice with original information, a reference, a position table, a chart, a ' +
      'publication, a source and a named point',
  );
}

/**
 * The model document's example: a notice with a details part and a note, and a line of named
 * positions, so the picture shows more of the model than the presentation document's does, and
 * few enough positions that their names stand apart on the map.
 */
async function modelNotice(request: APIRequestContext): Promise<PublicMessage> {
  const other = await presentationNotice(request);
  return exampleMessage(
    request,
    'NM-550-26',
    (message) =>
      message.id !== other.id &&
      message.mainType === 'NM' &&
      message.originalInformation === true &&
      (message.references?.length ?? 0) > 0 &&
      (message.charts?.length ?? 0) > 0 &&
      Boolean(englishDesc(message).publication) &&
      Boolean(englishDesc(message).source) &&
      hasNamedPositions(message) &&
      positionTables(message) > 0 &&
      (message.parts ?? []).some((part) => part.type === 'NOTE') &&
      !geometryTypes(message).has('Point'),
    'a notice with original information, a reference, a position table, a note, a chart, a ' +
      'publication, a source and a line or an area with named positions',
  );
}

/** Every position of a message, as [longitude, latitude], from the GeoJSON of its parts. */
function messagePositions(message: PublicMessage): [number, number][] {
  const found: [number, number][] = [];
  const walk = (value: unknown): void => {
    if (!Array.isArray(value)) return;
    if (value.length >= 2 && typeof value[0] === 'number' && typeof value[1] === 'number') {
      found.push([value[0], value[1]]);
      return;
    }
    value.forEach(walk);
  };
  for (const part of message.parts ?? []) {
    for (const feature of part.geometry?.features ?? []) {
      walk((feature.geometry as { coordinates?: unknown } | undefined)?.coordinates);
    }
  }
  return found;
}

/**
 * Zooms the message window's map in on the message's positions, as a reader does with its +
 * button. The window fits its map to the message only as far as zoom 12, where the positions of a
 * message about one harbour or two leading lights lie on top of each other and their names run
 * into one another; closer in, each numbered position and its name stand apart.
 */
async function zoomToPositions(page: Page, message: PublicMessage): Promise<void> {
  const positions = messagePositions(message);
  if (positions.length === 0) throw new Error(`${message.shortId ?? message.id} has no position.`);
  const box = await page.getByRole('dialog').locator('app-ol-map').boundingBox();
  if (!box) throw new Error("The message window's map is not on the page.");
  const radius = 6378137;
  const toX = (lon: number): number => (lon * Math.PI * radius) / 180;
  const toY = (lat: number): number =>
    radius * Math.log(Math.tan(Math.PI / 4 + (lat * Math.PI) / 360));
  const xs = positions.map(([lon]) => toX(lon));
  const ys = positions.map(([, lat]) => toY(lat));
  const [left, right, bottom, top] = [
    Math.min(...xs),
    Math.max(...xs),
    Math.min(...ys),
    Math.max(...ys),
  ];
  // Room around the positions for their names, which are written under and beside them.
  const resolution = Math.max(
    (right - left) / (box.width * 0.6),
    (top - bottom) / (box.height * 0.6),
  );
  const zoom = Math.min(
    17,
    Math.floor(Math.log2((2 * Math.PI * radius) / 256 / resolution) * 4) / 4,
  );
  const centreY = (top + bottom) / 2;
  await showMapAt(page, {
    lon: (((left + right) / 2) * 180) / (Math.PI * radius),
    lat: ((2 * Math.atan(Math.exp(centreY / radius)) - Math.PI / 2) * 180) / Math.PI,
    zoom,
  });
}

/** Opens one message's window by its link, as a reader who was sent the link does. */
async function openMessageLink(page: Page, message: PublicMessage): Promise<void> {
  await page.goto(infoUrl(`/messages/details/message/${encodeURIComponent(message.id)}`));
  await messageWindow(page);
}

test.describe('integration-docs', () => {
  test.beforeEach(async ({ page }) => {
    await fixClock(page);
  });

  // ---- presentation/02-Presentation.adoc ----------------------------------------------------

  test('presentation/MessageList.png', async ({ page }) => {
    await openInfoView(page, '/messages/table');
    const headings = page.locator('app-message-table-view h4.message-area-heading');
    await expect(headings.first()).toBeVisible();
    // The first area heading whose first nine rows, eight at least, hold a temporary notice, a
    // preliminary notice and a local warning, the kinds the pseudo-code above the picture marks,
    // and those rows: an area where warnings and notices of several kinds stand together.
    let chosen = -1;
    for (let index = 0; index < (await headings.count()); index++) {
      const rows = headings.nth(index).locator('xpath=following-sibling::ul[1]/li');
      const texts = (await rows.allInnerTexts()).slice(0, 9);
      if (
        texts.length >= 8 &&
        texts.some((text) => text.includes('(T)')) &&
        texts.some((text) => text.includes('(P)')) &&
        texts.some((text) => /^\s*Local\b/.test(text))
      ) {
        chosen = index;
        break;
      }
    }
    expect(
      chosen,
      'an area with temporary and preliminary notices and a local warning among its first rows',
    ).toBeGreaterThanOrEqual(0);
    const heading = headings.nth(chosen);
    const rows = heading.locator('xpath=following-sibling::ul[1]/li');
    const shown = [heading];
    const count = Math.min(await rows.count(), 9);
    for (let index = 0; index < count; index++) shown.push(rows.nth(index));
    await siteShot(page, { target: shown });
  });

  test('presentation/MessageDetails.png', async ({ page }) => {
    const notice = await presentationNotice(page.request);
    await openInfoView(page, '/messages/details');
    const card = page
      .locator('.message-details-item')
      .filter({ hasText: englishDesc(notice).title ?? '' })
      .first();
    await card.scrollIntoViewIfNeeded();
    await expect(card).toBeVisible();
    await siteShot(page, { target: card });
  });

  // The message window keeps its whole text in view, without scrolling inside it, in a window
  // this tall; the documents show the whole of it.
  test.describe('in a tall window', () => {
    test.use({ viewport: { width: 1280, height: 2000 } });

    test('presentation/MessageGeometry.png', async ({ page }) => {
      const notice = await presentationNotice(page.request);
      await openMessageLink(page, notice);
      await zoomToPositions(page, notice);
      await siteShot(page, { target: page.locator('.dialog-panel'), pad: 0 });
    });

    // ---- model/02-MessageModel.adoc ----------------------------------------------------------

    test('model/Sample-NM.png', async ({ page }) => {
      const notice = await modelNotice(page.request);
      await openMessageLink(page, notice);
      await zoomToPositions(page, notice);
      await siteShot(page, { target: page.locator('.dialog-panel'), pad: 0 });
    });
  });
});
