import type { Locator, Page } from '@playwright/test';

import { fixClock } from './support/clock';
import { expect, test } from './support/fixtures';
import { showMapAt, type MapView } from './support/map';
import { infoUrl } from './support/open';
import { openPdfFirstPage } from './support/pdf';
import {
  badgeLines,
  catchOpenedAddress,
  englishDesc,
  exampleMessage,
  firstPoint,
  geometryTypes,
  guideWarning,
  hasListedPositions,
  messageWindow,
  openInfoView,
  positionTables,
  rootArea,
  siteShot,
} from './support/public-site';
import { shot } from './support/shot';
import { settle } from './support/stabilise';

/*
 * The public site's pictures, anonymous and in English (infoUrl adds ?lang=en). A title names
 * the manual folder the manifest places the picture in, whichever manual that is.
 *
 * One test per picture, titled `<manual>/<file>` after the file it writes, so
 * `--grep "<manual>/<file>"` takes one picture again and manifest-check.mjs reads the list from the
 * titles. Every manifest entry marked retake, replace or new becomes one test here.
 *
 * Every test starts in a fresh browser context, so the site shows its defaults: both kinds of
 * message on, Show Active Now off, English. The clock is fixed in every test: the footer's year and
 * the Downloads page's year buttons follow it.
 */

// The waters around Zealand and Funen: the Belts, the Sound and the South Funen archipelago, where
// the data's warnings and notices lie close enough to show what the chart draws and far enough
// apart to tell them from each other.
const THE_BELTS: MapView = { lon: 11.3, lat: 55.45, zoom: 8.2 };

// Sejerø Bay and the north-west of Zealand, warnings only: the firing exercises' areas off Gniben,
// Sejerø and Jægerspris beside the points of the warnings at Kalundborg and in Roskilde Fjord. The
// copy has no published warning about a line; the text describes a line in words.
const SEJERO_BAY: MapView = { lon: 11.45, lat: 55.8, zoom: 9.8 };

const MAIL_ADDRESS = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/;

/**
 * Turns Notices to Mariners off in the filter bar, as a reader does, with a click on its button,
 * and waits until the map draws the navigational warnings alone. The map pictures show warnings
 * only (the owner's choice of 2026-11-13): with both kinds on, the copy's hundreds of notices crowd
 * the map until no symbol can be told from the next. Fails if a notice is still drawn.
 */
async function showWarningsOnly(page: Page): Promise<void> {
  const notices = page.getByRole('button', { name: 'Notices to Mariners' });
  await expect(notices).toHaveAttribute('aria-pressed', 'true');
  await notices.click();
  await expect(notices).toHaveAttribute('aria-pressed', 'false');
  await expect(page.getByRole('button', { name: 'Navigational Warnings' })).toHaveAttribute(
    'aria-pressed',
    'true',
  );
  await expect(page.locator('[aria-busy="true"]')).toHaveCount(0, { timeout: 60_000 });
  await settle(page);
  // The map's features carry their message's main type: none may be a notice's any more.
  await expect
    .poll(() =>
      page.evaluate(() => {
        interface OlFeature {
          get(key: string): unknown;
        }
        interface OlLayer {
          getSource?(): { getFeatures?(): OlFeature[] } | null;
        }
        const debug = (window as unknown as { ng?: { getComponent?: (e: Element) => unknown } }).ng;
        const kinds: Record<string, number> = {};
        for (const host of Array.from(document.querySelectorAll('app-ol-map'))) {
          const map = (
            debug?.getComponent?.(host) as { map?: { getAllLayers(): OlLayer[] } } | null
          )?.map;
          for (const layer of map?.getAllLayers() ?? []) {
            for (const feature of layer.getSource?.()?.getFeatures?.() ?? []) {
              const kind = feature.get('mainType');
              if (typeof kind === 'string') kinds[kind] = (kinds[kind] ?? 0) + 1;
            }
          }
        }
        return { warnings: (kinds['NW'] ?? 0) > 0, notices: kinds['NM'] ?? 0 };
      }),
    )
    .toEqual({ warnings: true, notices: 0 });
}

/**
 * Scrolls the Details view to the first area heading from which the window shows no e-mail
 * address: a published notice may give an authority's mailbox, which is no address of the guides'
 * own (example.org). The first area, at the top of the page, is taken when it shows none.
 */
async function scrollToAreaWithoutMail(page: Page): Promise<void> {
  const headings = page.locator('h4.message-area-heading');
  await expect(headings.first()).toBeVisible();
  const count = await headings.count();
  for (let index = 0; index < count; index++) {
    const clean = await headings.nth(index).evaluate(
      (heading, { first, mail }) => {
        const bars = document.querySelector('div.filter-bar')?.getBoundingClientRect().bottom ?? 0;
        window.scrollTo(
          0,
          first ? 0 : window.scrollY + heading.getBoundingClientRect().top - bars - 16,
        );
        const address = new RegExp(mail);
        return !Array.from(document.querySelectorAll<HTMLElement>('.message-details-item')).some(
          (card) => {
            const box = card.getBoundingClientRect();
            return (
              box.bottom > bars && box.top < window.innerHeight && address.test(card.innerText)
            );
          },
        );
      },
      { first: index === 0, mail: MAIL_ADDRESS.source },
    );
    if (clean) return;
  }
  throw new Error('Every area of the Details view shows an e-mail address in the window.');
}

/**
 * The upper part of a drawn PDF page, from its top (the header, which says when the list was
 * made: the rig writes the rig's fixed instant over the server's, openPdfFirstPage) to the last
 * rule between two messages in the upper half of the page, as an element over that part of the
 * page for shot() to take, so the picture leaves out the footer: it carries the address of the
 * authority whose report template the copy of the data holds, not the base product's.
 */
async function pdfBody(page: Page, drawn: Locator): Promise<Locator> {
  const found = await drawn.evaluate((element) => {
    const canvas = element as HTMLCanvasElement;
    const context = canvas.getContext('2d');
    if (!context) return null;
    const { width, height } = canvas;
    const pixels = context.getImageData(0, 0, width, height).data;
    // A rule is a row that is grey across most of the page: the header's, the lines between the
    // messages and the footer's. A heading's coloured underline is not grey.
    const rules: number[] = [];
    for (let row = 0; row < height; row++) {
      let grey = 0;
      for (let column = 0; column < width; column++) {
        const at = (row * width + column) * 4;
        const [r, g, b] = [pixels[at], pixels[at + 1], pixels[at + 2]];
        if (r > 120 && r < 240 && Math.max(r, g, b) - Math.min(r, g, b) < 16) grey++;
      }
      if (grey > width * 0.6) rules.push(row);
    }
    // The upper half of the page is enough to show what a printed list looks like, and stops short
    // of the firing exercises the server writes again from its schedule every hour, whose times
    // and day of publication follow the real clock.
    const ends = rules.filter((row, index) => index > 0 && row <= height / 2);
    if (ends.length === 0) return null;
    const scale = canvas.getBoundingClientRect().height / height;
    const box = canvas.getBoundingClientRect();
    const top = 0;
    const bottom = (ends[ends.length - 1] - 1) * scale;
    const overlay = document.createElement('div');
    overlay.id = 'guides-pdf-body';
    Object.assign(overlay.style, {
      position: 'absolute',
      left: `${box.left + window.scrollX}px`,
      top: `${box.top + window.scrollY + top}px`,
      width: `${box.width}px`,
      height: `${bottom - top}px`,
      pointerEvents: 'none',
      // Clear, but painted as far as shot() is concerned, which takes the box of what paints.
      backgroundImage: 'linear-gradient(transparent, transparent)',
    });
    document.body.appendChild(overlay);
    return { top, bottom };
  });
  if (!found) throw new Error('The drawn PDF page has no rules to cut its body by.');
  return page.locator('#guides-pdf-body');
}

test.describe('public-site', () => {
  test.beforeEach(async ({ page }) => {
    await fixClock(page);
  });

  // ---- 01-Introduction.adoc and 02-Messages.adoc ---------------------------------------------

  test('public-site/PublicSiteDetailsPage.png', async ({ page }) => {
    await openInfoView(page, '/');
    await expect(page).toHaveURL(/\/messages\/details/);
    await expect(page.locator('.message-details-item').first()).toBeVisible();
    await scrollToAreaWithoutMail(page);
    await siteShot(page);
  });

  test('public-site/PublicSiteMenuBar.png', async ({ page }) => {
    await openInfoView(page, '/messages/details');
    // The bar's items, not the empty bar either side of them, on the bar's own colour.
    const bar = page.locator('app-main-menu nav');
    const links = bar.locator('> a');
    await expect(links).toHaveCount(4);
    const items = [0, 1, 2, 3].map((index) => links.nth(index));
    await siteShot(page, { target: [...items, bar.locator('> span')], pad: 12 });
  });

  test('public-site/PublicSiteFilterBar.png', async ({ page }) => {
    await openInfoView(page, '/messages/details');
    await expect(page.getByRole('button', { name: 'Print' })).toBeEnabled();
    // The row of buttons, not the empty bar either side of it, on the bar's own colour.
    await siteShot(page, { target: page.locator('div.filter-bar > div > div'), pad: 12 });
  });

  test('public-site/PublicSiteListPage.png', async ({ page }) => {
    await openInfoView(page, '/messages/table');
    await expect(page.locator('app-message-table-view li').first()).toBeVisible();
    await siteShot(page);
  });

  test('public-site/PublicSiteMessageCard.png', async ({ page }) => {
    // A temporary notice that shows most of the parts a message can have, among them two parts
    // of the same kind; another one than the integration documents show. Every position of it is
    // numbered in a table, as the text describes positions: a notice that gives one position as
    // a bullet and refers to it by a number elsewhere (NM-510-26 in niord_guides) leaves the
    // reader looking for a number the card does not show.
    const notice = await exampleMessage(
      page.request,
      'NM-770-26',
      (message) => {
        const desc = englishDesc(message);
        return (
          message.mainType === 'NM' &&
          message.type === 'TEMPORARY_NOTICE' &&
          message.originalInformation === true &&
          (message.references?.length ?? 0) > 0 &&
          (message.charts?.length ?? 0) > 0 &&
          Boolean(desc.publication) &&
          Boolean(desc.source) &&
          (message.parts?.length ?? 0) > 1 &&
          positionTables(message) > 0 &&
          !hasListedPositions(message) &&
          !['NM-811-26', 'NM-550-26'].includes(message.shortId ?? '')
        );
      },
      'a temporary notice with original information, a reference, two parts, a position table ' +
        'and no position given as a bullet, a chart, a publication and a source',
    );
    await openInfoView(page, '/messages/details');
    const card = page
      .locator('.message-details-item')
      .filter({ hasText: englishDesc(notice).title ?? '' })
      .first();
    await card.scrollIntoViewIfNeeded();
    // The menu bar and the filter bar stay at the top of the window, over a card that scrolls
    // under them: the picture is of the card alone.
    await siteShot(page, {
      target: card,
      hide: [page.locator('app-main-menu nav'), page.locator('div.filter-bar')],
    });
  });

  test('public-site/PublicSiteMessageDialog.png', async ({ page }) => {
    const warning = await guideWarning(page.request);
    await openInfoView(page, '/messages/table');
    await page
      .locator('app-message-table-view li button')
      .filter({ hasText: englishDesc(warning).title ?? '' })
      .first()
      .click();
    await messageWindow(page);
    await siteShot(page);
  });

  test('public-site/PublicSitePrintPdf.png', async ({ page }) => {
    // The list a reader prints: navigational warnings only, as the site composes its address. The
    // first page of the notices gives two mailboxes outside example.org.
    await openInfoView(page, '/messages/details');
    await page.getByRole('button', { name: 'Notices to Mariners' }).click();
    await expect(page.locator('[aria-busy="true"]')).toHaveCount(0, { timeout: 60_000 });
    const print = page.getByRole('button', { name: 'Print' });
    await expect(print).toBeEnabled();
    const address = await catchOpenedAddress(page, () => print.click());
    expect(address).toContain('/rest/message-reports/report.pdf');
    expect(address).toContain('report=public');
    expect(address).toContain('mainType=NW');
    const body = await pdfBody(page, await openPdfFirstPage(page, { url: address }));
    // The guard reads the e-mail addresses inside the picture only: the footer's mailbox, the
    // copy's authority's, is cut away above.
    await shot(page, { target: body, pad: 0, maps: false, guard: { checks: 'external' } });
  });

  // ---- 03-Map.adoc ---------------------------------------------------------------------------

  test('public-site/PublicSiteMapPage.png', async ({ page }) => {
    await openInfoView(page, '/messages/map');
    await expect(page.locator('app-ol-map')).toBeVisible();
    await showWarningsOnly(page);
    await showMapAt(page, THE_BELTS);
    await siteShot(page);
  });

  test('public-site/PublicSiteMapSymbols.png', async ({ page }) => {
    await openInfoView(page, '/messages/map');
    await expect(page.locator('app-ol-map')).toBeVisible();
    await page.getByRole('button', { name: 'Minimize' }).click();
    await showWarningsOnly(page);
    await showMapAt(page, SEJERO_BAY);
    await siteShot(page, { target: page.locator('app-message-map'), pad: 0 });
  });

  test('public-site/PublicSiteMapTooltip.png', async ({ page }) => {
    // A message whose title is short enough to stand beside its number on one line. The box is at
    // most 20rem wide, and a title longer than its line squeezes the number beside it, which then
    // breaks at its hyphens onto two or three lines: in this copy every place where several
    // messages lie together has such titles, so the picture names one message, and the text gives
    // the line the box ends with when there are more. The check below fails if the number breaks.
    const message = await exampleMessage(
      page.request,
      'NW-319-26',
      (candidate) =>
        geometryTypes(candidate).has('Point') && (englishDesc(candidate).title ?? '').length <= 40,
      'a message with a point and a title of 40 characters or fewer',
    );
    await openInfoView(page, '/messages/map');
    const map = page.locator('app-ol-map');
    await expect(map).toBeVisible();
    await page.getByRole('button', { name: 'Minimize' }).click();
    await showWarningsOnly(page);
    // The message's point in the middle of the map, close enough to stand apart from its
    // neighbours.
    const [lon, lat] = firstPoint(message);
    await showMapAt(page, { lon, lat, zoom: 10 });
    const box = await map.boundingBox();
    if (!box) throw new Error('The map is not on the page.');
    const [x, y] = [box.x + box.width / 2, box.y + box.height / 2];
    await page.mouse.move(x - 20, y - 20);
    await page.mouse.move(x, y, { steps: 5 });
    const tooltip = page.locator('app-message-map-tooltip > div');
    await expect(tooltip).toBeVisible();
    await expect(tooltip).toContainText(englishDesc(message).title ?? '');
    expect(await badgeLines(tooltip), 'lines each number in the box takes').toEqual([1]);
    await siteShot(page, { target: tooltip, pad: 48, backdrop: false });
  });

  // ---- 04-Downloads.adoc ---------------------------------------------------------------------

  test('public-site/PublicSiteDownloadsActive.png', async ({ page }) => {
    await openInfoView(page, '/publications');
    await expect(page.locator('app-publication-list tbody tr').first()).toBeVisible();
    await siteShot(page);
  });

  test('public-site/PublicSiteDownloadsHistorical.png', async ({ page }) => {
    await openInfoView(page, '/publications');
    await page.getByRole('tab', { name: 'Historical NtM and Publications' }).click();
    // The year before the fixed clock's: the year buttons are this year and the four before it.
    await page.getByRole('button', { name: '2025', exact: true }).click();
    const panel = page.getByRole('tabpanel');
    await expect(panel.locator('app-publication-list tbody tr').first()).toBeVisible();
    await expect(page.getByRole('button', { name: '2025', exact: true })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await siteShot(page);
  });

  test('public-site/PublicSiteBrowsePublication.png', async ({ page }) => {
    await openInfoView(page, '/publications');
    // A publication whose messages lie in several areas, so the page shows the area buttons; any
    // publication that can be browsed if the data has no such one. The window shows the top of
    // the list: its one cancelled message, far down, carries a number that does not follow its
    // series (FA/2025 2026 in niord_guides), so the picture does not scroll to its mark.
    // The page has no busy state of its own: wait for its table, or the choice below is made
    // before the rows are there and always falls back.
    const rows = page.locator('app-publication-list tbody tr');
    await expect(rows.getByRole('link', { name: 'Browse' }).first()).toBeVisible();
    const firing = rows.filter({ hasText: 'Firing Practice Areas' }).getByRole('link', {
      name: 'Browse',
    });
    const browse =
      (await firing.count()) > 0
        ? firing.first()
        : rows.getByRole('link', { name: 'Browse' }).first();
    await browse.click();
    await expect(page).toHaveURL(/\/messages\/details\?.*publicationId=/);
    await expect(page.locator('[aria-busy="true"]')).toHaveCount(0, { timeout: 60_000 });
    await expect(page.locator('.message-details-item').first()).toBeVisible();
    await siteShot(page);
  });

  // ---- 05-Phone.adoc -------------------------------------------------------------------------

  test.describe('at phone width', () => {
    test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });

    test('public-site/PublicSiteDetailsPagePhone.png', async ({ page }) => {
      await openInfoView(page, '/messages/details');
      await expect(page.locator('.message-details-item').first()).toBeVisible();
      await scrollToAreaWithoutMail(page);
      await siteShot(page);
    });

    test('public-site/PublicSiteMessageDialogPhone.png', async ({ page }) => {
      const warning = await guideWarning(page.request);
      await page.goto(infoUrl(`/messages/details/message/${encodeURIComponent(warning.id)}`));
      await messageWindow(page);
      await siteShot(page);
    });

    test('public-site/PublicSiteMapPagePhone.png', async ({ page }) => {
      await openInfoView(page, '/messages/map');
      await expect(page.locator('app-ol-map')).toBeVisible();
      await page.getByRole('button', { name: 'Minimize' }).click();
      await showWarningsOnly(page);
      await showMapAt(page, { lon: 11.3, lat: 55.6, zoom: 6.9 });
      await siteShot(page);
    });
  });

  // ---- 06-Embedding.adoc ---------------------------------------------------------------------

  test.describe('in a frame of another site', () => {
    test.use({ viewport: { width: 640, height: 400 } });

    test('public-site/PublicSiteTeaser.png', async ({ page }) => {
      // The teaser of one country's waters, as an embedding page names it with `area`: without
      // it, a deployment that names no area of its own shows every message it has, whichever
      // part of the world they lie in.
      const area = rootArea(await guideWarning(page.request));
      await page.goto(infoUrl(`/teaser?area=${area.id}`));
      await expect(page.locator('#teaser-banner')).toBeVisible();
      await siteShot(page, { fit: false });
    });
  });
});
