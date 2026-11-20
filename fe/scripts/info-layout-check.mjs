#!/usr/bin/env node
/**
 * The public site's layout, checked in a real browser: the two bars, the message cards and the
 * filter chips.
 *
 *   npm run build:info && npm run layout:info            # run from fe/
 *   node scripts/info-layout-check.mjs [--dist dist/niord-info/browser] [--nginx <dir>]
 *
 * --nginx names the folder holding niord-security-headers.conf, whose policy every page runs
 * under: by default the base product's ui-apps/info/nginx/ (in a country workspace the copy under
 * .niord/base/), and for image:info the file it copied out of the image it built, beside that
 * image's web root as --dist.
 *
 * The menu bar and the filter bar under it sit at the top of the viewport, and what they cover
 * depends on the width of the screen and on the language: a row of labels that fits in Danish
 * wraps in Kalaallisut. jsdom lays nothing out, so no unit test can see any of it. This script
 * opens the built artefact in Chromium, answers every /rest call from the fixture below, and
 * asserts eighteen things:
 *
 *   1. At 320, 640, 1024 and 1920 px, in every language the build offers, every control of the
 *      menu bar — the four links and each language flag — lies inside the viewport and is the
 *      topmost element at its own centre, at the top of the list and scrolled down it.
 *   2. At 320 × 256 and 320 × 200 CSS px — 400 % zoom on a 1280 × 1024 and a 1280 × 800 screen —
 *      the bars are not pinned: scrolled down the list, neither is left in the viewport, and the
 *      map view is as tall as the viewport. On a tall viewport the map starts under the bars and
 *      ends at the bottom of the viewport.
 *   3. Focus never lands under a bar: walking backwards with Shift+Tab from the last control on
 *      the page, through the list and through the map view's footer, every control that takes
 *      focus is the topmost element where its focus ring starts.
 *   4. At 320 px neither list view nor the publications page scrolls sideways, the publications
 *      table fits its panel with every link whole, and a message part wider than its cell is a
 *      focusable region with a name, while a part that fits takes no tab stop at all.
 *   5. On a deployment that names itself in the corner ribbon, the skip link is the first tab stop
 *      and, focused, is the topmost element along its whole line of text.
 *   6. At 320, 640 and 1280 px, in every language the build offers, every field name of a message
 *      card shows whole — none cut to an ellipsis, none wider than its box — and the longest
 *      field name the language's bundle has is among them.
 *   7. At 390 px every chip of the filter bar is at least 40 px tall, on the current list and
 *      while an issue is browsed, when the bar names the issue in place of the main types; and
 *      at 320 px, in every language the build offers, the browsed issue's page does not scroll
 *      sideways.
 *   8. In a forced-colours theme each pressed filter chip looks unlike every unpressed one.
 *   9. On the card that carries every field, a link in the message text and an attachment's file
 *      name are underlined and coloured apart from the text around them, and a publication
 *      reference is drawn as the text it is part of.
 *  10. Printed at 794 px, the publications page prints the address of a link to another host in
 *      the line, never in the touch target placed over the link beside it, and prints no
 *      root-relative address.
 *  11. A message part whose table is narrower than its box in one language and wider in another
 *      becomes a named tab stop when a language switch widens the table, and stops being one
 *      when the switch back narrows it, although the box itself keeps its size. The fixture's
 *      table changes width between Danish and English, so a build that does not offer both — a
 *      country with one language — skips this one, and the run says so.
 *  12. Served over plain HTTP under the policy the image sends, the teaser and the map view each
 *      ask for base-map tiles and raise no Content-Security-Policy violation — and no page of the
 *      whole run raises one.
 *  13. At 320 × 568, 320 × 256, 768 × 500 and 1280 × 900, the map view's panel of messages
 *      without a position covers none of the chart's own controls: the zoom buttons, the layer
 *      switcher and every link of the credits are each the topmost element at their centre. A
 *      link that wraps onto a second line is tested at the centre of each line.
 *  14. Every link of a chart's credit line is underlined, on the map view and on the teaser.
 *  15. The teaser's banner text holds 4.5:1 over a white tile and over the chart's land and water,
 *      in the build's own colours.
 *  16. On the print sheet every control the Tab key reaches shows a focus ring.
 *  17. At 320 px under the WCAG 1.4.12 text spacing, a dialog whose title and legal document hold
 *      Kalaallisut's long words keeps its close button inside the panel, and no word runs past
 *      the body's edge.
 *  18. A message's dialog opened by its own address and closed with Escape gives focus to the
 *      message's row in the list, or to the main content when no list holds the message.
 *
 * The languages are the build's own: /rest/settings/web answers without a language setting, so
 * the country file decides, and each flag is pressed in turn. So are the root areas: the fixture
 * answers whichever root areas a build names, in the order the build asks for them, with the same
 * messages. Every page carries the Content-Security-Policy of the image's nginx configuration.
 * Nothing leaves the process — an image off the site's own origin, a map tile above all, is
 * answered with a blank one here, and every other request off the origin is refused.
 *
 * Text widths follow the machine's fonts. The Linux runner sets system-ui in DejaVu Sans, which
 * is wider than Segoe UI on Windows, so a word that fits on a Windows machine can still overflow
 * in CI.
 *
 * Exit codes: 0 when every assertion holds, 1 when one does not (every failure is named), 2 for a
 * usage or setup error.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';

const FE = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const USAGE = 'usage: info-layout-check.mjs [--dist <dir>] [--nginx <dir>]';

function fail(message) {
  console.error(`info-layout-check: ${message}`);
  process.exit(2);
}

// Run as a script rather than imported by its test, which asks only the fixture's answers.
const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

let dist = path.join(FE, 'dist/niord-info/browser');
let nginx = path.join(FE, 'ui-apps/info/nginx');
if (invokedDirectly) {
  const argv = process.argv.slice(2);
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--dist' && argv[i + 1]) dist = path.resolve(argv[++i]);
    else if (argv[i] === '--nginx' && argv[i + 1]) nginx = path.resolve(argv[++i]);
    else fail(`unknown or incomplete option ${argv[i]}\n${USAGE}`);
  }
  if (!fs.existsSync(path.join(dist, 'index.html'))) {
    fail(`no index.html in ${dist} — build the site first (npm run build:info)`);
  }
}

/**
 * An origin nobody resolves: every request to it is answered by this process. Plain HTTP, as the
 * container serves: a tile address without its own scheme then asks for an HTTP image, which the
 * policy refuses.
 */
const ORIGIN = 'http://niord-info.layout-check';

/**
 * The Content-Security-Policy the image sends, read from its nginx configuration so that every
 * page here runs under the policy a reader's browser enforces. The frame policy is the one every
 * page but the teaser carries; nothing here is framed. Read only when the script runs.
 */
const SITE_POLICY = !invokedDirectly
  ? ''
  : (() => {
      const file = path.join(nginx, 'niord-security-headers.conf');
      if (!fs.existsSync(file)) fail(`no niord-security-headers.conf in ${nginx}`);
      const policy = /add_header Content-Security-Policy "([^"]+)"/.exec(
        fs.readFileSync(file, 'utf8'),
      );
      if (!policy) fail(`no Content-Security-Policy header in ${file}`);
      return policy[1].replace('$niord_frame_ancestors', "'self'");
    })();

/** 1x1 transparent PNG: the answer to every image off the site's origin, a map tile above all. */
const BLANK_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
  'base64',
);

// ---------------------------------------------------------------------------------------------
// The fixture
// ---------------------------------------------------------------------------------------------

/**
 * The fixture's two root areas, each with the sub-areas its messages are filed under. Their
 * names are fictitious, in Danish and English of the lengths a real lineage has, long Danish
 * compounds included. They answer any root areas a build names, in the order it asks for them
 * (rootAreaFor below).
 */
const ROOTS = [
  {
    id: 1,
    mrn: 'urn:mrn:iho:country:xa',
    names: ['Ankerland', 'Anchorland'],
    subAreas: [
      ['Østhavet', 'The East Sea'],
      ['Strædet', 'The Strait'],
      ['Småskærsfarvandet', 'Smaaskaersfarvandet'],
      ['Storsundet', 'The Great Sound'],
      ['Farvandet syd for Midtøen', 'The waters south of Midtoe'],
      ['Lillesundet', 'The Little Sound'],
      ['Midthavet', 'The Mid Sea'],
      ['Langfjorden', 'The Long Fjord'],
      ['Ydrehavet', 'The Outer Sea'],
      ['Nordvandet', 'The North Water'],
    ],
  },
  {
    id: 2,
    mrn: 'urn:mrn:iho:country:xb',
    names: ['Bøjeland', 'Buoyland'],
    subAreas: [
      ['Vestbøjeland', 'West Buoyland'],
      ['Sydbøjeland', 'South Buoyland'],
      ['Østbøjeland', 'East Buoyland'],
    ],
  },
];

/**
 * Titles with the long compound words Danish is written in: a flex row that cannot break one of
 * them is wider than a phone.
 */
const TITLES = [
  ['Farvandsafmærkningsændringer', 'Changes to the aids to navigation'],
  ['Havbundsundersøgelser ved vindmølleparken Thor', 'Seabed surveys at the Thor wind farm'],
  ['Undervandssprængningsarbejde i havneindløbet', 'Underwater blasting in the harbour entrance'],
  ['Kabellægningsarbejde mellem Als og Fyn', 'Cable laying between Als and Funen'],
];

/** A position table states each position on one line, so on a phone it is wider than its cell. */
const POSITIONS = `<table class="positions"><tbody>${[1, 2, 3]
  .map(
    (n) =>
      `<tr><td>${n})</td><td>55° 4${n},123'N - 012° 3${n},567'E</td><td>Vindmølle nr. ${n}, nordlige række</td></tr>`,
  )
  .join('')}</tbody></table>`;

function descs(da, en, field) {
  return [
    { lang: 'da', [field]: da },
    { lang: 'en', [field]: en },
  ];
}

/** The position every row of the table below starts with, which finds its box on the page. */
const TOGGLE_MARK = "56° 07,000'N - 010° 12,000'E";

/**
 * A position table of one row, one position long in Danish and eight in English: at 1280 px the
 * Danish row fits its box and the English one is wider, while the box is as wide and as tall in
 * both languages. Only the content changes on a switch, which the box's own size never shows.
 */
const TOGGLE_POSITIONS = descs(
  `<table class="positions"><tbody><tr><td>1)</td><td>${TOGGLE_MARK}</td></tr></tbody></table>`,
  `<table class="positions"><tbody><tr><td>1)</td>${Array.from(
    { length: 8 },
    (_, i) => `<td>${i === 0 ? TOGGLE_MARK : `56° 07,00${i}'N - 010° 12,00${i}'E`}</td>`,
  ).join('')}</tr></tbody></table>`,
  'details',
);

/**
 * Every field a message card can name, for the first message of each root: the six kinds of part,
 * references, attachments, charts and a publication. Their names are the longest words a card
 * carries in each language, and two of them are links.
 */
const EVERY_FIELD = {
  references: [{ messageId: 'NW-099-26', type: 'REPETITION' }],
  attachments: [{ path: 'layout-check/bilag.pdf', fileName: 'bilag.pdf', type: 'application/pdf' }],
  charts: [{ chartNumber: '101', internationalNumber: 1301 }],
  parts: ['TIME', 'NOTE', 'PROHIBITION', 'SIGNALS'].map((type) => ({
    type,
    descs: descs(
      '<p>Se <a href="https://example.invalid/bilag.pdf">bilaget</a>.</p>',
      '<p>See <a href="https://example.invalid/annex.pdf">the annex</a>.</p>',
      'details',
    ),
  })),
  publication: descs(
    '<a href="https://example.invalid/efs.pdf" target="_blank">EfS 12/2026, punkt 17.</a>',
    '<a href="https://example.invalid/ntm.pdf" target="_blank">NtM 12/2026, item 17.</a>',
    'publication',
  ),
};

const MESSAGES = ROOTS.flatMap((root, rootIndex) =>
  root.subAreas.flatMap(([da, en], subIndex) =>
    [0, 1].map((k) => {
      const n = rootIndex * 100 + subIndex * 2 + k + 1;
      const [titleDa, titleEn] = TITLES[n % TITLES.length];
      // Every other message a temporary notice: the "(T)" beside its id is the widest badge a
      // row carries, and it leaves the title the least room.
      const notice = n % 2 === 0;
      // The first message of each root carries every field, whichever root the build opens on.
      const everyField = subIndex === 0 && k === 0;
      return {
        id: `layout-check-${n}`,
        shortId: `${notice ? 'NM' : 'NW'}-${String(n).padStart(3, '0')}-26`,
        mainType: notice ? 'NM' : 'NW',
        type: notice ? 'TEMPORARY_NOTICE' : 'COASTAL_WARNING',
        status: 'PUBLISHED',
        messageSeries: { seriesId: notice ? 'layout-check-nm' : 'layout-check-nw' },
        publishDateFrom: Date.UTC(2026, 8, 1) + n * 3_600_000,
        areas: [
          {
            id: root.id * 100 + subIndex + 1,
            descs: descs(da, en, 'name'),
            parent: { id: root.id, mrn: root.mrn, descs: descs(...root.names, 'name') },
          },
        ],
        descs: [
          {
            lang: 'da',
            title: titleDa,
            source: 'Layout check',
            ...(everyField ? EVERY_FIELD.publication[0] : {}),
          },
          {
            lang: 'en',
            title: titleEn,
            source: 'Layout check',
            ...(everyField ? EVERY_FIELD.publication[1] : {}),
          },
        ],
        parts: [
          {
            type: 'DETAILS',
            descs: descs(
              '<p>Arbejdet udføres i dagtimerne. Skibsfarten anmodes om at holde god afstand.</p>',
              '<p>The work is carried out in daytime. Mariners are requested to keep well clear.</p>',
              'details',
            ),
          },
          ...(n % 3 === 1
            ? [{ type: 'POSITIONS', descs: descs(POSITIONS, POSITIONS, 'details') }]
            : []),
          ...(everyField ? EVERY_FIELD.parts : []),
        ],
        ...(everyField
          ? {
              references: EVERY_FIELD.references,
              attachments: EVERY_FIELD.attachments,
              charts: EVERY_FIELD.charts,
            }
          : {}),
      };
    }),
  ),
);

// One message in each root carries the table whose width depends on the language.
for (const root of ROOTS) {
  MESSAGES.push({
    id: `layout-check-toggle-${root.id}`,
    shortId: `NW-${String(90 + root.id).padStart(3, '0')}-26`,
    mainType: 'NW',
    type: 'COASTAL_WARNING',
    status: 'PUBLISHED',
    messageSeries: { seriesId: 'layout-check-nw' },
    publishDateFrom: Date.UTC(2026, 8, 1),
    areas: [
      {
        id: root.id * 100 + 1,
        descs: descs(...root.subAreas[0], 'name'),
        parent: { id: root.id, mrn: root.mrn, descs: descs(...root.names, 'name') },
      },
    ],
    descs: descs(...TITLES[0], 'title').map((desc) => ({ ...desc, source: 'Layout check' })),
    parts: [{ type: 'POSITIONS', descs: TOGGLE_POSITIONS }],
  });
}

/**
 * A message a link can open but no list holds: an expired one, say. Answered by its id only, so a
 * dialog opened by its address has no row in the list to give focus back to.
 */
export const UNLISTED_MESSAGE = {
  ...MESSAGES[0],
  id: 'layout-check-unlisted',
  shortId: 'NW-900-26',
  status: 'EXPIRED',
};

/**
 * Two publications in a row: one whose file lies in the site's own repository, addressed by a
 * root-relative path, and one on another host. Each row carries a Download link and the first a
 * Browse link as well: beside the title from the sm width, under it below. The first title holds
 * a word as long as a real one ("Efterretninger", "Mariners"): the two link columns beside a title
 * that cannot wrap below its longest word are what pushed the page past 320 px.
 */
const PUBLICATIONS = [
  {
    publicationId: 'layout-check-efs',
    type: 'MESSAGE_REPORT',
    category: { categoryId: 'layout-check', descs: descs('Efterretninger', 'Notices', 'name') },
    descs: [
      {
        lang: 'da',
        title: 'Efterretninger uge 38',
        link: '/rest/repo/file/layout-check/efs-38.pdf',
      },
      {
        lang: 'en',
        title: 'Notices to Mariners week 38',
        link: '/rest/repo/file/layout-check/ntm-38.pdf',
      },
    ],
  },
  {
    publicationId: 'layout-check-lights',
    type: 'LINK',
    category: { categoryId: 'layout-check', descs: descs('Efterretninger', 'Notices', 'name') },
    descs: [
      { lang: 'da', title: 'Fyrliste 2026', link: 'https://example.invalid/fyrliste-2026.pdf' },
      { lang: 'en', title: 'List of Lights 2026', link: 'https://example.invalid/lights-2026.pdf' },
    ],
  },
];

function json(body, status = 200) {
  return { status, contentType: 'application/json', body: JSON.stringify(body) };
}

/** The deployment the settings name: production, but for the check that needs the ribbon. */
let executionMode = 'production';

/** The fixture root each MRN a build asked for was answered with, in the order asked. */
const rootsByMrn = new Map();

/**
 * The root area answered for an MRN: the first fixture root not given out yet, and the next in
 * turn once both are, under the MRN asked for. So whatever root areas a build names are read and
 * its cards render: the messages are filed under the fixture roots' ids, whatever MRN found them.
 */
export function rootAreaFor(mrn) {
  if (!rootsByMrn.has(mrn)) {
    const given = new Set(rootsByMrn.values());
    rootsByMrn.set(mrn, ROOTS.find((r) => !given.has(r)) ?? ROOTS[rootsByMrn.size % ROOTS.length]);
  }
  const root = rootsByMrn.get(mrn);
  return { id: root.id, mrn, descs: descs(...root.names, 'name') };
}

/** The answer to a /rest call of the site, from the fixture. */
export function answerRest(url) {
  // No language setting: the build's own country file decides what the switcher offers.
  if (url.pathname === '/rest/settings/web') return json({ mode: executionMode });
  const area = /^\/rest\/public\/v1\/area\/(.+)$/.exec(url.pathname);
  if (area) return json(rootAreaFor(decodeURIComponent(area[1])));
  if (url.pathname === '/rest/public/v1/messages') {
    const areaId = url.searchParams.get('areaId');
    return json(MESSAGES.filter((m) => !areaId || String(m.areas[0].parent.id) === areaId));
  }
  const message = /^\/rest\/public\/v1\/message\/(.+)$/.exec(url.pathname);
  if (message) {
    const id = decodeURIComponent(message[1]);
    const found = [...MESSAGES, UNLISTED_MESSAGE].find((m) => m.id === id || m.shortId === id);
    if (found) return json(found);
  }
  if (url.pathname === '/rest/public/v1/publications') return json(PUBLICATIONS);
  const publication = /^\/rest\/public\/v1\/publication\/(.+)$/.exec(url.pathname);
  if (publication) {
    const found = PUBLICATIONS.find((p) => p.publicationId === decodeURIComponent(publication[1]));
    if (found) return json(found);
  }
  return json({ error: `${url.pathname} is not in the layout check's fixture` }, 404);
}

const TYPES = {
  '.html': 'text/html',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.json': 'application/json',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain',
};

/** Every image off the site's origin the pages asked for, and so the policy let through. */
const offSiteImages = [];

/**
 * The artefact as nginx serves it: a file when one exists, the application for a route, each
 * with the site's policy. An image off the origin is answered with a blank one — the base map
 * requests its tiles in CORS mode, so the answer says any origin may read it — and anything else
 * off the origin is refused.
 */
function serve(route) {
  const url = new URL(route.request().url());
  if (url.origin !== ORIGIN) {
    if (route.request().resourceType() !== 'image') return route.abort();
    offSiteImages.push(url.href);
    return route.fulfill({
      status: 200,
      contentType: 'image/png',
      headers: { 'access-control-allow-origin': '*' },
      body: BLANK_PNG,
    });
  }
  const headers = { 'content-security-policy': SITE_POLICY };
  if (url.pathname.startsWith('/rest/')) return route.fulfill({ ...answerRest(url), headers });
  if (url.pathname.startsWith('/wms/')) return route.abort();
  const isRoute = path.extname(url.pathname) === '';
  const file = path.join(dist, isRoute ? 'index.html' : decodeURIComponent(url.pathname));
  if (!file.startsWith(dist) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
    return route.fulfill({ status: 404, headers, body: 'not found' });
  }
  return route.fulfill({
    status: 200,
    contentType: TYPES[path.extname(file)] ?? 'application/octet-stream',
    headers,
    body: fs.readFileSync(file),
  });
}

/** The menu label each bundle writes for the first link, to know a language switch has landed. */
function menuLabel(lang) {
  const file = path.join(dist, 'assets/i18n', `${lang}.json`);
  return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8'))['MENU_DETAILS'] : '';
}

// ---------------------------------------------------------------------------------------------
// Page helpers
// ---------------------------------------------------------------------------------------------

const MENU_CONTROLS = 'app-main-menu nav a, app-main-menu nav button';
/** The address Browse opens on the fixture's issue. */
const BROWSED = `/messages/details?publicationId=${PUBLICATIONS[0].publicationId}`;
const FLAGS = 'app-main-menu nav button';

/** Two frames: a resize observer's measurement and the layout that reads it have both landed. */
async function settle(page) {
  await page.evaluate(
    () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))),
  );
}

async function open(page, route) {
  await page.goto(`${ORIGIN}${route}`);
  await page.waitForSelector('app-main-menu nav');
  if (route.endsWith('/map')) {
    await page.waitForSelector('app-message-map');
  } else if (route === '/publications') {
    // Each link is in the page twice, one copy drawn below the sm width and one from it.
    await page.locator('app-publication-list tbody a:visible').first().waitFor();
  } else {
    await page.waitForSelector('.message-details-list li, .message-details-item');
  }
  await settle(page);
}

async function scrollTo(page, y) {
  await page.evaluate((top) => window.scrollTo(0, top), y);
  await settle(page);
}

/** Presses the flag at `index` the way a script does, which no bar can intercept. */
async function switchLanguage(page, index) {
  await page
    .locator(FLAGS)
    .nth(index)
    .evaluate((button) => button.click());
  const lang = await page.evaluate(() => document.documentElement.lang);
  const label = menuLabel(lang);
  await page.waitForFunction(
    (expected) =>
      !expected || document.querySelector('app-main-menu nav a')?.textContent?.trim() === expected,
    label,
  );
  await settle(page);
  return lang;
}

/** Presses the flag of one language; false when the build offers no such flag. */
async function switchTo(page, lang) {
  const index = await page
    .locator(FLAGS)
    .evaluateAll((buttons, wanted) => buttons.findIndex((b) => b.lang === wanted), lang);
  if (index < 0) return false;
  return (await switchLanguage(page, index)) === lang;
}

/** Every menu control that is outside the viewport or not the topmost element at its centre. */
function coveredMenuControls(page) {
  return page.evaluate((selector) => {
    const problems = [];
    for (const control of document.querySelectorAll(selector)) {
      const name =
        control.getAttribute('aria-label') || control.textContent.trim() || control.tagName;
      const box = control.getBoundingClientRect();
      if (box.width === 0 || box.height === 0) {
        problems.push(`"${name}" is not rendered`);
      } else if (
        box.left < 0 ||
        box.top < 0 ||
        box.right > innerWidth ||
        box.bottom > innerHeight
      ) {
        problems.push(`"${name}" lies outside the viewport`);
      } else {
        const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
        if (!hit || !control.contains(hit)) {
          problems.push(
            `"${name}" is covered by <${hit?.tagName.toLowerCase()} class="${hit?.className}">`,
          );
        }
      }
    }
    return problems;
  }, MENU_CONTROLS);
}

/** The bars that are still in the viewport although the page is scrolled well past them. */
function barsLeftInView(page) {
  return page.evaluate(() =>
    [
      ['the menu bar', document.querySelector('app-main-menu nav')],
      ['the filter bar', document.querySelector('app-message-filter > div')],
    ]
      .filter(([, bar]) => bar && bar.getBoundingClientRect().bottom > 0)
      .map(([name]) => `${name} stays pinned to the top of a ${innerHeight} px viewport`),
  );
}

/** Where the map view is, against the viewport and against the bars above it. */
function mapProblems(page, pinned) {
  return page.evaluate((expectPinned) => {
    const map = document.querySelector('app-message-map').getBoundingClientRect();
    const filter = document.querySelector('app-message-filter > div')?.getBoundingClientRect();
    const barsBottom = filter ? filter.bottom : 0;
    const problems = [];
    if (expectPinned) {
      if (map.top < barsBottom - 1)
        problems.push(`the map starts under the bars (${map.top} < ${barsBottom})`);
      if (Math.abs(map.bottom - innerHeight) > 1) {
        problems.push(
          `the map ends at ${map.bottom}, not at the bottom of the viewport (${innerHeight})`,
        );
      }
    } else if (map.height < innerHeight - 1) {
      problems.push(`the map is ${map.height} px tall in a ${innerHeight} px viewport`);
    }
    return problems;
  }, pinned);
}

/**
 * Every control of the chart that the panel of messages without a position, or anything else,
 * covers at its centre: the zoom buttons, the layer switcher's boxes and each link of the
 * credits. The fixture's messages carry no position, so the panel is always there; a run that
 * finds none says so rather than holding by default.
 *
 * A credit's link can wrap onto a second line at 320 px, as a long agency name does after the
 * accessibility link. The centre of its bounding box then lies between the two lines, where the
 * list around them is the topmost element, although nothing covers the link. So each line of a
 * control is tested at its own centre, and the first line that fails is named.
 */
async function mapControlsCovered(page) {
  // Where the bars are not pinned the chart is as tall as the viewport and starts below them.
  await page.evaluate(() =>
    document.querySelector('app-message-map').scrollIntoView({ block: 'start' }),
  );
  await settle(page);
  return page.evaluate(() => {
    const panel = document.querySelector('#no-pos-messages')?.parentElement;
    if (!panel) return ['the panel of messages without a position is not shown'];
    const controls = [
      ...document.querySelectorAll(
        'app-message-map .ol-zoom-in, app-message-map .ol-zoom-out, ' +
          'app-message-map .ol-layer-switcher input, app-message-map .ol-attribution a',
      ),
    ].filter((control) => control.getClientRects().length > 0);
    if (controls.length === 0) return ['the chart shows no control at all'];
    const problems = [];
    for (const control of controls) {
      const name =
        control.getAttribute('title') ||
        control.textContent.trim() ||
        control.closest('label')?.textContent.trim() ||
        control.className;
      for (const box of control.getClientRects()) {
        const x = box.left + box.width / 2;
        const y = box.top + box.height / 2;
        if (x < 0 || y < 0 || x > innerWidth || y > innerHeight) {
          problems.push(`"${name}" lies outside the viewport`);
          break;
        }
        const hit = document.elementFromPoint(x, y);
        if (!hit || !control.contains(hit)) {
          problems.push(
            `"${name}" is covered by ${
              hit && panel.contains(hit)
                ? 'the panel of messages without a position'
                : `<${hit?.tagName.toLowerCase()} class="${hit?.className}">`
            }`,
          );
          break;
        }
      }
    }
    return problems;
  });
}

/**
 * Walks backwards from the last control on the page and names every control that took focus
 * where a bar, or anything else, covers the start of it — the top of its focus ring. A control
 * taller than the room the bars leave cannot be shown whole; it is named when not even the
 * middle of what shows of it is clear.
 */
async function focusUnderBars(page, steps) {
  await page.evaluate(() => {
    const focusable = [
      ...document.querySelectorAll('a[href], button:not([disabled]), [tabindex="0"]'),
    ].filter((element) => element.getClientRects().length > 0);
    focusable.at(-1)?.focus();
  });
  const problems = [];
  for (let step = 0; step <= steps; step++) {
    if (step > 0) await page.keyboard.press('Shift+Tab');
    await settle(page);
    const problem = await page.evaluate(() => {
      const element = document.activeElement;
      if (!element || element === document.body) return null;
      const box = element.getBoundingClientRect();
      const name = (
        element.getAttribute('aria-label') ||
        element.textContent.trim() ||
        element.tagName
      ).slice(0, 40);
      if (box.bottom <= 0 || box.top >= innerHeight) return `"${name}" took focus off-screen`;
      const barsBottom = Math.max(
        0,
        ...[...document.querySelectorAll('app-main-menu nav, app-message-filter > div')].map(
          (bar) => bar.getBoundingClientRect().bottom,
        ),
      );
      const fits = box.height <= innerHeight - barsBottom;
      const x = Math.min(Math.max(box.left + box.width / 2, 0), innerWidth - 1);
      const y = fits
        ? Math.min(Math.max(box.top + Math.min(box.height, 16) / 2, 0), innerHeight - 1)
        : (Math.max(box.top, barsBottom) + Math.min(box.bottom, innerHeight)) / 2;
      const hit = document.elementFromPoint(x, y);
      return hit && element.contains(hit)
        ? null
        : `"${name}" took focus under <${hit?.tagName.toLowerCase()} class="${hit?.className}">`;
    });
    if (problem) problems.push(problem);
  }
  return problems;
}

/** The keys of every field name a message card can show. */
const FIELD_KEYS = [
  'FIELD_REFERENCES',
  'FIELD_ATTACHMENTS',
  'FIELD_CHARTS',
  'FIELD_PUBLICATION',
  ...['DETAILS', 'TIME', 'POSITIONS', 'NOTE', 'PROHIBITION', 'SIGNALS'].map(
    (t) => `PART_TYPE_${t}`,
  ),
];

/** The longest field name the build's bundle for a language carries. */
function longestFieldName(lang) {
  const file = path.join(dist, 'assets/i18n', `${lang}.json`);
  if (!fs.existsSync(file)) return '';
  const words = JSON.parse(fs.readFileSync(file, 'utf8'));
  return FIELD_KEYS.map((key) => words[key] ?? '').reduce((a, b) => (b.length > a.length ? b : a));
}

/**
 * Every field name of a message card that does not show whole — cut to an ellipsis, or wider
 * than the box it is drawn in — and whether the longest name the bundle has is among them.
 */
function clippedFieldNames(page, longest) {
  return page.evaluate((expected) => {
    const names = [...document.querySelectorAll('.message-details-item dl > dt')].filter((dt) =>
      dt.textContent.trim(),
    );
    const problems = [];
    if (expected && !names.some((dt) => dt.textContent.trim() === expected)) {
      problems.push(
        `no card shows "${expected}", the longest field name, so the check proves less`,
      );
    }
    for (const dt of names) {
      const name = dt.textContent.trim();
      const style = getComputedStyle(dt);
      if (style.textOverflow === 'ellipsis' && style.overflowX !== 'visible') {
        problems.push(`"${name}" is cut to an ellipsis`);
      } else if (dt.scrollWidth - dt.clientWidth > 1) {
        problems.push(`"${name}" is ${dt.scrollWidth} px wide in a ${dt.clientWidth} px box`);
      }
    }
    return problems;
  }, longest);
}

/** Every filter chip shorter than the 40 px a finger needs. */
function smallFilterChips(page) {
  return page.evaluate(() => {
    const chips = [...document.querySelectorAll('app-message-filter button.chip')];
    if (chips.length === 0) return ['the filter bar has no chip'];
    return chips
      .map((chip) => [chip.textContent.trim(), chip.getBoundingClientRect().height])
      .filter(([, height]) => height < 39.5)
      .map(([name, height]) => `"${name}" is ${Math.round(height)} px tall`);
  });
}

/**
 * In a forced-colours theme every colour comes from the reader's palette. Each pressed filter
 * chip must still look unlike every unpressed one.
 */
async function pressedChipsInForcedColours(page) {
  await page.emulateMedia({ forcedColors: 'active' });
  await settle(page);
  const problems = await page.evaluate(() => {
    // What is painted behind a chip: its own background, or where that is transparent the
    // nearest ancestor's — a transparent chip on a white bar looks exactly like a white one.
    const painted = (element) => {
      for (let node = element; node; node = node.parentElement) {
        const [r, g, b, a = 1] = (
          getComputedStyle(node).backgroundColor.match(/[\d.]+/g) ?? []
        ).map(Number);
        if (r !== undefined && a > 0) return `${r},${g},${b}`;
      }
      return '255,255,255';
    };
    const look = (chip) => `${painted(chip)} ${getComputedStyle(chip).color}`;
    const chips = [...document.querySelectorAll('app-message-filter button.chip[aria-pressed]')];
    const pressed = chips.filter((chip) => chip.getAttribute('aria-pressed') === 'true');
    const unpressed = chips.filter((chip) => chip.getAttribute('aria-pressed') === 'false');
    if (pressed.length === 0 || unpressed.length === 0) {
      return ['the filter bar needs a pressed and an unpressed chip, or the check proves nothing'];
    }
    const unpressedLooks = new Set(unpressed.map(look));
    return pressed
      .filter((chip) => unpressedLooks.has(look(chip)))
      .map((chip) => `pressed "${chip.textContent.trim()}" looks like an unpressed chip`);
  });
  await page.emulateMedia({ forcedColors: 'none' });
  return problems;
}

/**
 * On the card that carries every field: a link in the message text and an attachment's file name
 * are underlined and not in the colour of the text around them, and a publication reference is
 * both — the citation it is part of.
 */
async function linkProblems(page) {
  await page.evaluate(() =>
    document
      .querySelector('.message-details-item dd:has(app-icon[name="lucidePaperclip"]) button')
      ?.click(),
  );
  await settle(page);
  return page.evaluate(() => {
    const card = document.querySelector('.message-details-item:has(.message-publication)');
    if (!card) return ['no card carries a publication, so the check proves nothing'];
    const underlined = (a) => getComputedStyle(a).textDecorationLine.includes('underline');
    const plain = (a) => getComputedStyle(a).color === getComputedStyle(a.parentElement).color;
    const sets = [
      [
        'a link in the message text',
        card.querySelectorAll('.message-details-html:not(.message-publication) a[href]'),
      ],
      ['an attachment link', card.querySelectorAll('dd a[href*="/rest/repo/file/"]')],
      ['a publication link', card.querySelectorAll('.message-publication a[href]')],
    ].map(([what, list]) => [what, [...list]]);
    const problems = sets
      .filter(([, list]) => list.length === 0)
      .map(([what]) => `the card shows no ${what}, so the check proves nothing`);
    for (const [what, list] of sets) {
      for (const a of list) {
        const name = a.textContent.trim();
        const isPublication = what === 'a publication link';
        if (isPublication && (underlined(a) || !plain(a))) {
          problems.push(`publication link "${name}" does not read as the text around it`);
        } else if (!isPublication && !underlined(a)) {
          problems.push(`${what}, "${name}", is not underlined`);
        } else if (!isPublication && plain(a)) {
          problems.push(`${what}, "${name}", is in the colour of the text around it`);
        }
      }
    }
    return problems;
  });
}

/**
 * On paper, every link that names where it leads does so in the line: the address follows the
 * link's own text rather than sitting in the absolutely placed touch target the shared link
 * classes put on the same pseudo-element below the `lg` width, where it would cover the link
 * beside it. Only an address a reader can type is printed — not a root-relative path, not a
 * router link. Both kinds must be on the page, or the check proves nothing.
 */
async function printedLinkProblems(page) {
  await page.emulateMedia({ media: 'print' });
  await settle(page);
  const problems = await page.evaluate(() => {
    const links = [...document.querySelectorAll('a[href]')].filter(
      (a) => a.getClientRects().length > 0,
    );
    const suffix = (a) => getComputedStyle(a, '::after').content;
    const printed = links.filter((a) => /^https?:\/\//.test(a.getAttribute('href')));
    const local = links.filter((a) => a.getAttribute('href').startsWith('/'));
    const found = [];
    if (printed.length === 0 || local.length === 0) {
      found.push('the page needs a link to another host and a root-relative one');
    }
    for (const a of printed) {
      const name = a.textContent.trim();
      if (!suffix(a).includes('attr(href)') && !suffix(a).includes(a.getAttribute('href'))) {
        found.push(`"${name}" prints no address`);
      }
      if (getComputedStyle(a, '::after').position !== 'static') {
        found.push(`the address of "${name}" is printed out of the line`);
      }
    }
    for (const a of local) {
      if (suffix(a).includes('attr(href)') || suffix(a).includes(a.getAttribute('href'))) {
        found.push(`"${a.textContent.trim()}" prints its root-relative address`);
      }
    }
    return found;
  });
  await page.emulateMedia({ media: 'screen' });
  return problems;
}

/**
 * Every link of a chart's credit line is underlined: its grey is 2.2:1 from the line's, so colour
 * alone would tell it apart (WCAG 1.4.1). There must be one, or the check proves nothing.
 */
function creditLinkProblems(page) {
  return page.evaluate(() => {
    const links = [...document.querySelectorAll('.ol-attribution a[href]')].filter(
      (a) => a.getClientRects().length > 0,
    );
    if (links.length === 0) return ['the chart shows no credit link, so the check proves nothing'];
    return links
      .filter((a) => !getComputedStyle(a).textDecorationLine.includes('underline'))
      .map((a) => `the credit link "${a.textContent.trim()}" is not underlined`);
  });
}

/** OpenStreetMap's tile colours a banner over the chart can stand on: a white tile, land, water. */
const TILE_COLOURS = { 'a white tile': '#ffffff', land: '#f2efe9', water: '#aad3df' };

/**
 * The teaser's banner text holds 4.5:1 over every tile colour it can stand on (WCAG 1.4.3; the
 * text is 18 px and not bold, so not large). axe-core cannot measure text over an image, so the
 * banner's own fill is laid over each tile colour on a canvas, whatever syntax the browser
 * reports the fill in, and the text's colour is measured against what that gives. The colours are
 * the build's, so a country's build measures its own.
 */
function teaserBannerProblems(page) {
  return page.evaluate((tiles) => {
    const banner = document.querySelector('#teaser-banner');
    if (!banner) return ['the teaser shows no banner'];
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 1;
    const context = canvas.getContext('2d', { willReadFrequently: true });
    const paint = (...fills) => {
      context.clearRect(0, 0, 1, 1);
      for (const fill of fills) {
        context.fillStyle = fill;
        context.fillRect(0, 0, 1, 1);
      }
      return [...context.getImageData(0, 0, 1, 1).data.slice(0, 3)];
    };
    const luminance = (rgb) => {
      const [r, g, b] = rgb.map((c) => {
        const v = c / 255;
        return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
      });
      return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    };
    const ratio = (a, b) => {
      const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
      return (hi + 0.05) / (lo + 0.05);
    };
    const style = getComputedStyle(banner);
    const text = paint('#ffffff', style.color);
    return Object.entries(tiles)
      .map(([tile, colour]) => [tile, ratio(text, paint(colour, style.backgroundColor))])
      .filter(([, measured]) => measured < 4.5)
      .map(([tile, measured]) => `the banner's text is ${measured.toFixed(2)}:1 over ${tile}`);
  }, TILE_COLOURS);
}

/**
 * On the print sheet, every control the Tab key reaches shows a focus ring: the sheet draws its
 * link-styled buttons as plain text, which took their ring away (WCAG 2.4.7). There must be one,
 * or the check proves nothing.
 */
async function printSheetFocusProblems(page) {
  const problems = [];
  let stops = 0;
  for (let step = 0; step < 12; step++) {
    await page.keyboard.press('Tab');
    const stop = await page.evaluate(() => {
      const focused = document.activeElement;
      if (!focused || focused === document.body || !focused.closest('app-message-print-view')) {
        return null;
      }
      // The site's 2 px ring or the browser's own (`auto`), as on every other page of the site.
      const style = getComputedStyle(focused);
      return {
        name: focused.textContent.trim(),
        ring: style.outlineStyle !== 'none' && parseFloat(style.outlineWidth) > 0,
      };
    });
    if (!stop) continue;
    stops++;
    if (!stop.ring) problems.push(`"${stop.name}" shows no focus ring`);
  }
  if (stops === 0) {
    problems.push('Tab reached no control on the sheet, so the check proves nothing');
  }
  return problems;
}

/**
 * A dialog at 320 px under the WCAG 1.4.12 text spacing, with a title and a legal document of
 * Kalaallisut's long words: the close button stays inside the panel, the title inside the
 * header, and no word runs past the body's edge (WCAG 1.4.12). The base build has neither a legal
 * document nor a title that long, so both are written into an open message's dialog here, in the
 * classes a legal dialog renders them in.
 */
async function longWordDialogProblems(page) {
  await page.locator('.message-details-item button.row-link').first().click();
  await page.waitForSelector('.dialog-panel .dialog-body');
  await settle(page);
  await page.evaluate(() => {
    const spacing = document.createElement('style');
    spacing.id = 'layout-check-text-spacing';
    spacing.textContent = `* { line-height: 1.5 !important; letter-spacing: .12em !important;
      word-spacing: .16em !important; } p { margin-bottom: 2em !important; }`;
    document.head.append(spacing);
    document.querySelector('.dialog-panel .dialog-title').textContent =
      'Akisussaatitaannginnermik nalunaarut';
    const legal = document.createElement('div');
    legal.className = 'legal-document';
    legal.innerHTML =
      '<p>Nalunaarutit pissarsiarisinnaaniassammatigit. pissarsiarineqarsinnaagaluartut,</p>' +
      '<p>ilisimatitsissuteqartariaqarpugut, nalunaarutini.</p>';
    document.querySelector('.dialog-panel .dialog-body').prepend(legal);
  });
  await settle(page);
  const problems = await page.evaluate(() => {
    const found = [];
    const panel = document.querySelector('.dialog-panel').getBoundingClientRect();
    const inside = (rect) => rect.left >= panel.left - 0.5 && rect.right <= panel.right + 0.5;
    const close = document.querySelector('.dialog-panel .dialog-header .icon-button');
    if (!inside(close.getBoundingClientRect())) found.push('the close button is outside the panel');
    const title = document.querySelector('.dialog-panel .dialog-title');
    if (!inside(title.getBoundingClientRect())) found.push('the title runs past the panel');
    const body = document.querySelector('.dialog-panel .dialog-body');
    if (body.scrollWidth - body.clientWidth > 1) {
      found.push(`the body scrolls sideways by ${body.scrollWidth - body.clientWidth} px`);
    }
    for (const p of body.querySelectorAll('.legal-document p')) {
      if (p.scrollWidth - p.clientWidth > 1) {
        found.push(`a word of "${p.textContent.slice(0, 24)}…" runs past its line`);
      }
    }
    return found;
  });
  await page.evaluate(() => document.getElementById('layout-check-text-spacing').remove());
  await page.keyboard.press('Escape');
  await settle(page);
  return problems;
}

/**
 * A message's dialog opened by its own address, closed with Escape: focus goes to the row of the
 * message in the list when the list holds it, and to the main content when it does not — never to
 * the page's body, where the next Tab would start again at the top of the page (WCAG 2.4.3). The
 * focus is given once the router has drawn the list's own view, which only a browser shows.
 */
async function linkedDialogFocusProblems(page, message) {
  await page.goto(`${ORIGIN}/messages/details/message/${encodeURIComponent(message.id)}`);
  await page.waitForSelector('.dialog-panel .dialog-body');
  await page.waitForSelector('.message-details-list .message-details-item');
  await settle(page);
  await page.keyboard.press('Escape');
  await page.waitForFunction(
    () => location.pathname === '/messages/details' && !document.querySelector('.dialog-panel'),
  );
  await settle(page);
  await settle(page);
  return page.evaluate((shortId) => {
    const focused = document.activeElement;
    if (!focused || focused === document.body) return ['focus fell to the page body'];
    const row = [...document.querySelectorAll('.message-details-list .message-details-item')].find(
      (item) => item.textContent.includes(shortId),
    );
    const expected = row
      ? row.querySelector('button.row-link')
      : document.getElementById('main-content');
    if (focused === expected) return [];
    const where = focused.id ? `#${focused.id}` : focused.tagName.toLowerCase();
    return [`focus is on ${where}, not on ${row ? `the row of ${shortId}` : 'the main content'}`];
  }, message.shortId);
}

/**
 * The publications table fits inside its panel, and no link of it reaches past the panel's edge.
 * A table wider than its panel need not widen the page: an ancestor that clips it cuts the links
 * off instead, which the page's own width does not show.
 */
function publicationTableProblems(page) {
  return page.evaluate(() => {
    const table = [...document.querySelectorAll('app-publication-list table')].find(
      (t) => t.getClientRects().length > 0,
    );
    if (!table) return ['the publications page shows no table'];
    const panel = table.parentElement.parentElement;
    const style = getComputedStyle(panel);
    const rect = panel.getBoundingClientRect();
    const left = rect.left + parseFloat(style.borderLeftWidth) + parseFloat(style.paddingLeft);
    const right = rect.right - parseFloat(style.borderRightWidth) - parseFloat(style.paddingRight);
    const problems = [];
    const width = table.getBoundingClientRect().width;
    if (width > right - left + 1) {
      problems.push(
        `the table is ${Math.round(width)} px wide in a ${Math.round(right - left)} px panel`,
      );
    }
    for (const a of table.querySelectorAll('a')) {
      if (a.getClientRects().length === 0) continue;
      const box = a.getBoundingClientRect();
      if (box.left < left - 1 || box.right > right + 1) {
        problems.push(`"${a.textContent.trim()}" reaches past the panel's edge`);
      }
    }
    return problems;
  });
}

function sidewaysScroll(page) {
  return page.evaluate(() => {
    const width = document.documentElement.scrollWidth;
    return width > innerWidth
      ? [`the page is ${width} px wide in a ${innerWidth} px viewport`]
      : [];
  });
}

/**
 * Every box that scrolls sideways without being a named tab stop, and every tab stop that does
 * not. A box wider by one rounded pixel hides nothing and counts as fitting.
 */
function scrollRegionProblems(page) {
  return page.evaluate(() => {
    const problems = [];
    let scrolling = 0;
    for (const box of document.querySelectorAll('.message-details-item *')) {
      if (!['auto', 'scroll'].includes(getComputedStyle(box).overflowX)) continue;
      const scrolls = box.scrollWidth - box.clientWidth > 1;
      const text = box.textContent.trim().slice(0, 40);
      if (scrolls) {
        scrolling++;
        if (
          box.tabIndex !== 0 ||
          box.getAttribute('role') !== 'region' ||
          !box.getAttribute('aria-label')
        ) {
          problems.push(`"${text}" scrolls sideways but is not a focusable, named region`);
        }
      } else if (box.hasAttribute('tabindex')) {
        problems.push(`"${text}" takes a tab stop although it does not scroll`);
      }
    }
    if (scrolling === 0) {
      problems.push(
        'no message part scrolls at this width, so the check proves nothing — widen the fixture',
      );
    }
    return problems;
  });
}

/**
 * The box that holds the table whose width depends on the language: its size, how many cells the
 * table has — which says which language's table it holds — whether it scrolls, and what it tells
 * a keyboard and a screen reader. Null when no card shows it.
 */
function toggleBox(page) {
  return page.evaluate((mark) => {
    const box = [...document.querySelectorAll('.message-details-item .overflow-x-auto')].find(
      (candidate) => candidate.textContent.includes(mark),
    );
    if (!box) return null;
    return {
      width: box.clientWidth,
      height: box.clientHeight,
      cells: box.querySelectorAll('td').length,
      scrolls: box.scrollWidth - box.clientWidth > 1,
      region:
        box.tabIndex === 0 &&
        box.getAttribute('role') === 'region' &&
        Boolean(box.getAttribute('aria-label')),
      marks: ['tabindex', 'role', 'aria-label'].filter((name) => box.hasAttribute(name)),
    };
  }, TOGGLE_MARK);
}

/**
 * The box once it holds the table with `cells` cells and says what that table calls for, or as
 * it is when three seconds were not enough: a box that never catches up with its content is the
 * failure this is looking for.
 */
async function settledToggleBox(page, cells) {
  const deadline = Date.now() + 3000;
  let box = await toggleBox(page);
  const settled = (b) => b && b.cells === cells && (b.scrolls ? b.region : b.marks.length === 0);
  while (!settled(box) && Date.now() < deadline) {
    await settle(page);
    box = await toggleBox(page);
  }
  return box;
}

/**
 * A language switch that changes only what a box holds: the box keeps its size, so the only
 * sign that its content became wider, or narrower, than itself is the content. Both directions
 * are asked from a box measured as it was first drawn, so neither leans on the other.
 */
async function contentOnlyScrollRegionProblems(page) {
  const size = (b) => `${b.width} × ${b.height}`;
  await open(page, '/messages/details');
  const offered = await page.locator(FLAGS).evaluateAll((buttons) => buttons.map((b) => b.lang));
  // The fixture's table changes width between Danish and English only. A build that offers no
  // such pair — a country with one language, as the example's zz — has no switch to ask about.
  if (!offered.includes('da') || !offered.includes('en')) return null;
  // The page opens in the language the switcher chose last: the wide table, drawn afresh.
  await switchTo(page, 'en');
  await open(page, '/messages/details');
  const wide = await settledToggleBox(page, 9);
  if (!wide) return ['no card shows the table whose width depends on the language'];
  if (!wide.scrolls)
    return [`the wide table fits its ${size(wide)} box, so the check proves nothing`];
  const problems = [];
  if (!wide.region) problems.push('the box of the wide table is no named tab stop as first drawn');

  await switchTo(page, 'da');
  const narrowed = await settledToggleBox(page, 2);
  if (narrowed.scrolls) {
    return [`the narrow table scrolls in its ${size(narrowed)} box, so the check proves nothing`];
  }
  if (size(narrowed) !== size(wide)) {
    problems.push(
      `the box went from ${size(wide)} to ${size(narrowed)} on the switch, so the switch is no ` +
        'change of content alone',
    );
  }
  if (narrowed.marks.length > 0) {
    problems.push(
      `the table became narrower than its unchanged box on a switch to Danish, and the box kept ` +
        narrowed.marks.join(', '),
    );
  }

  // Now the Danish page drawn afresh, and the other direction.
  await open(page, '/messages/details');
  const narrow = await settledToggleBox(page, 2);
  if (narrow.marks.length > 0) {
    problems.push(`the box of the narrow table carries ${narrow.marks.join(', ')} as first drawn`);
  }
  await switchTo(page, 'en');
  const widened = await settledToggleBox(page, 9);
  if (size(widened) !== size(narrow)) {
    problems.push(
      `the box went from ${size(narrow)} to ${size(widened)} on the switch, so the switch is no ` +
        'change of content alone',
    );
  }
  if (!widened.region) {
    problems.push(
      `the table became wider than its unchanged box on a switch to English, and the box became ` +
        `no named tab stop (it carries ${widened.marks.join(', ') || 'no tabindex, role or aria-label'})`,
    );
  }
  return problems;
}

/** Every Content-Security-Policy violation a page of the run raised, in the order raised. */
const cspViolations = [];

function describeViolation(violation) {
  return `${new URL(violation.page).pathname} broke ${violation.directive} with ${violation.blocked || 'inline content'}`;
}

/**
 * Opens a map page over plain HTTP and waits until it has asked for a base-map tile or had one
 * refused, then names every violation the page raised. A page that asked for no tile at all is a
 * failure too: the check would prove nothing.
 */
async function tileProblems(page, route, selector) {
  offSiteImages.length = 0;
  const before = cspViolations.length;
  await page.goto(`${ORIGIN}${route}`);
  await page.waitForSelector(selector);
  const deadline = Date.now() + 5000;
  while (offSiteImages.length === 0 && cspViolations.length === before && Date.now() < deadline) {
    await settle(page);
  }
  await settle(page);
  const problems = cspViolations.slice(before).map(describeViolation);
  if (offSiteImages.length === 0 && problems.length === 0) {
    problems.push('the page asked for no base-map tile, so the check proves nothing');
  }
  return problems;
}

/**
 * Whether the focused element is the skip link, and whether anything is drawn over it anywhere
 * along the middle of its text. The ribbon has to reach the link, or the check proves nothing.
 */
function skipLinkProblems(page) {
  return page.evaluate(() => {
    const link = document.activeElement;
    if (link?.tagName !== 'A' || link.getAttribute('href') !== '#main-content') {
      return [`the first tab stop is <${link?.tagName.toLowerCase()}>, not the skip link`];
    }
    const box = link.getBoundingClientRect();
    const ribbon = document.querySelector('.execution-mode')?.getBoundingClientRect();
    if (
      !ribbon ||
      ribbon.width === 0 ||
      ribbon.right <= box.left ||
      ribbon.bottom <= box.top ||
      ribbon.left >= box.right ||
      ribbon.top >= box.bottom
    ) {
      return ['the deployment ribbon does not reach the skip link, so the check proves nothing'];
    }
    if (box.top < 0) return ['the focused skip link is above the viewport'];
    const y = box.top + box.height / 2;
    for (let x = box.left + 1; x < box.right; x += 4) {
      const hit = document.elementFromPoint(x, y);
      if (!hit || !link.contains(hit)) {
        return [
          `the focused skip link is covered at ${Math.round(x)} px by <${hit?.tagName.toLowerCase()} class="${hit?.className}">`,
        ];
      }
    }
    return [];
  });
}

// ---------------------------------------------------------------------------------------------
// The checks
// ---------------------------------------------------------------------------------------------

const failures = [];
let checks = 0;
function record(label, problems) {
  checks++;
  for (const problem of problems) failures.push(`${label}: ${problem}`);
  console.log(`${problems.length === 0 ? 'ok  ' : 'FAIL'}  ${label}`);
}

/** A check the build gives nothing to ask about: named, never counted as held. */
const skipped = [];
function skip(label, reason) {
  skipped.push(label);
  console.log(`skip  ${label} (${reason})`);
}

if (invokedDirectly) {
  let browser;
  try {
    browser = await chromium.launch();
  } catch (error) {
    fail(`Chromium did not start (npx playwright install chromium): ${error.message}`);
  }

  try {
    const context = await browser.newContext();
    await context.route('**/*', serve);
    // A refused tile or script leaves no trace in the layout, so the page reports each violation
    // of its policy as it happens.
    await context.exposeBinding('niordLayoutCheckCspViolation', ({ frame }, violation) => {
      cspViolations.push({ ...violation, page: frame.url() });
    });
    await context.addInitScript(() => {
      document.addEventListener('securitypolicyviolation', (event) =>
        window.niordLayoutCheckCspViolation({
          directive: event.effectiveDirective,
          blocked: event.blockedURI,
        }),
      );
    });
    const page = await context.newPage();

    // 1. Every menu control on top, at every width, in every language.
    for (const width of [320, 640, 1024, 1920]) {
      await page.setViewportSize({ width, height: 900 });
      await open(page, '/messages/details');
      const flags = await page.locator(FLAGS).count();
      if (flags === 0) record(`menu bar at ${width} px`, ['the language switcher offers no flag']);
      for (let index = 0; index < flags; index++) {
        const lang = await switchLanguage(page, index);
        for (const y of [0, 1500]) {
          await scrollTo(page, y);
          record(
            `menu bar at ${width} px, ${lang}, scrolled ${y} px`,
            await coveredMenuControls(page),
          );
        }
      }
    }

    // 2. At 400 % zoom the bars scroll away and the map takes the viewport.
    for (const [width, height] of [
      [320, 256],
      [320, 200],
    ]) {
      await page.setViewportSize({ width, height });
      await open(page, '/messages/details');
      await scrollTo(page, 1500);
      record(`bars at ${width} × ${height}`, await barsLeftInView(page));
      await open(page, '/messages/map');
      record(`map at ${width} × ${height}`, await mapProblems(page, false));
    }
    await page.setViewportSize({ width: 1280, height: 900 });
    await open(page, '/messages/map');
    record('map at 1280 × 900', await mapProblems(page, true));

    // 3. Focus is never under a bar, in the list or in the map view's footer.
    for (const [width, height] of [
      [1280, 900],
      [320, 256],
    ]) {
      await page.setViewportSize({ width, height });
      await open(page, '/messages/details');
      record(`focus in the list at ${width} × ${height}`, await focusUnderBars(page, 24));
      await open(page, '/messages/map');
      record(`focus in the map view at ${width} × ${height}`, await focusUnderBars(page, 6));
    }

    // 4. Nothing scrolls the page sideways at 320 px, and what scrolls inside it is reachable.
    //    In every language: the long words are the Danish ones.
    await page.setViewportSize({ width: 320, height: 800 });
    for (const [route, view] of [
      ['/messages/table', 'list view'],
      ['/messages/details', 'details view'],
      ['/publications', 'publications page'],
    ]) {
      await open(page, route);
      const flags = await page.locator(FLAGS).count();
      for (let index = 0; index < flags; index++) {
        const lang = await switchLanguage(page, index);
        record(`${view} at 320 px, ${lang}`, await sidewaysScroll(page));
        if (route.endsWith('/details')) {
          record(`message parts at 320 px, ${lang}`, await scrollRegionProblems(page));
        }
        if (route === '/publications') {
          record(`publications table at 320 px, ${lang}`, await publicationTableProblems(page));
        }
      }
    }

    // 5. A test site names itself in a corner ribbon, and the focused skip link is drawn above it.
    executionMode = 'test';
    for (const width of [320, 1280]) {
      await page.setViewportSize({ width, height: 900 });
      await open(page, '/messages/details');
      await page.keyboard.press('Tab');
      await settle(page);
      record(`skip link over the deployment ribbon at ${width} px`, await skipLinkProblems(page));
    }
    executionMode = 'production';

    // 6. Every field name shows whole, in every language — the longest one the bundle has among
    //    them — stacked above its value on a phone and in a column of its own from 640 px up.
    for (const width of [320, 640, 1280]) {
      await page.setViewportSize({ width, height: 900 });
      await open(page, '/messages/details');
      const flags = await page.locator(FLAGS).count();
      for (let index = 0; index < flags; index++) {
        const lang = await switchLanguage(page, index);
        record(
          `field names at ${width} px, ${lang}`,
          await clippedFieldNames(page, longestFieldName(lang)),
        );
      }
    }

    // 7. Below the lg breakpoint every filter chip is a 40 px touch target.
    await page.setViewportSize({ width: 390, height: 900 });
    await open(page, '/messages/details');
    record('filter chips at 390 px', await smallFilterChips(page));

    //    While an issue is browsed the bar names it, with its document and a close control in
    //    place of the main types: still 40 px chips on a phone, and nothing wider than 320 px,
    //    in every language — its title is the widest thing the bar holds.
    await page.setViewportSize({ width: 390, height: 900 });
    await open(page, BROWSED);
    record('filter chips at 390 px, browsing an issue', await smallFilterChips(page));
    await page.setViewportSize({ width: 320, height: 800 });
    await open(page, BROWSED);
    const browsedFlags = await page.locator(FLAGS).count();
    for (let index = 0; index < browsedFlags; index++) {
      const lang = await switchLanguage(page, index);
      record(`browsing an issue at 320 px, ${lang}`, await sidewaysScroll(page));
    }

    // 8. A pressed filter chip still looks pressed in a forced-colours theme.
    await page.setViewportSize({ width: 1280, height: 900 });
    await open(page, '/messages/details');
    record('pressed filter chips in forced colours', await pressedChipsInForcedColours(page));

    // 9. A link in a message looks like one at rest; a publication reference reads as its text.
    record('links in a message card', await linkProblems(page));

    // 10. On a printed page — narrower than the lg width — a link's address runs in its line, and
    //     only an address a reader can type is printed.
    await page.setViewportSize({ width: 794, height: 1123 });
    await page.goto(`${ORIGIN}/publications`);
    await page.locator('app-publication-list tbody a[href^="https:"]:visible').first().waitFor();
    await settle(page);
    record('printed links on the publications page', await printedLinkProblems(page));

    // 11. A box that scrolls sideways follows its content, not only its own size.
    await page.setViewportSize({ width: 1280, height: 900 });
    const toggled = await contentOnlyScrollRegionProblems(page);
    if (toggled) record('a message part whose table changes width on a language switch', toggled);
    else
      skip(
        'a message part whose table changes width on a language switch',
        'the build offers no Danish and English to switch between',
      );

    // 14. A chart's credit link is underlined, on the map view and on the teaser.
    await page.setViewportSize({ width: 1280, height: 900 });
    await open(page, '/messages/map');
    await page.waitForSelector('.ol-attribution a[href]');
    record('the credit links of the map view', await creditLinkProblems(page));
    await page.goto(`${ORIGIN}/teaser`);
    await page.waitForSelector('#teaser-banner');
    await settle(page);
    record('the credit link of the teaser', await creditLinkProblems(page));

    // 15. The teaser's banner text holds 4.5:1 over a white tile, land and water.
    record("the teaser's banner over the chart", await teaserBannerProblems(page));

    // 16. The print sheet's controls show the site's focus ring.
    await page.goto(`${ORIGIN}/messages/print`);
    await page.waitForSelector('app-message-print-view .message-details-item');
    await settle(page);
    record('focus on the print sheet', await printSheetFocusProblems(page));

    // 17. At 320 px under the text spacing, a dialog's long words wrap and its close button stays.
    await page.setViewportSize({ width: 320, height: 640 });
    await open(page, '/messages/details');
    record(
      'long words in a dialog at 320 px with text spacing',
      await longWordDialogProblems(page),
    );

    // 18. A dialog opened by its address gives focus to its row, or to the main content, on close.
    await page.setViewportSize({ width: 1280, height: 900 });
    record(
      'focus after a dialog opened by its address, its message in the list',
      await linkedDialogFocusProblems(page, MESSAGES[2]),
    );
    record(
      'focus after a dialog opened by its address, its message in no list',
      await linkedDialogFocusProblems(page, UNLISTED_MESSAGE),
    );

    // 13. The panel of messages without a position leaves every control of the chart uncovered —
    //     on a phone, at 400 % zoom, where the chart is barely taller than the panel's cap, and
    //     beside the chart on a wider screen. Run before 12, whose last record covers every page.
    for (const [width, height] of [
      [320, 568],
      [320, 256],
      [768, 500],
      [1280, 900],
    ]) {
      await page.setViewportSize({ width, height });
      await open(page, '/messages/map');
      record(`map controls at ${width} × ${height}`, await mapControlsCovered(page));
    }

    // 12. Over plain HTTP the base map asks for its tiles within the policy, and nothing on any page
    //     of the run broke the policy.
    record(
      'base-map tiles of the teaser over HTTP',
      await tileProblems(page, '/teaser', 'app-teaser'),
    );
    record(
      'base-map tiles of the map view over HTTP',
      await tileProblems(page, '/messages/map', 'app-message-map'),
    );
    record(
      'the Content-Security-Policy on every page of the run',
      cspViolations.map(describeViolation),
    );
  } finally {
    await browser.close();
  }

  if (failures.length > 0) {
    console.error(`\ninfo-layout-check: ${failures.length} failure(s) in ${checks} checks:`);
    for (const failure of failures) console.error(`  - ${failure}`);
    process.exit(1);
  }
  console.log(
    `\ninfo-layout-check: all ${checks} checks hold${skipped.length ? `; ${skipped.length} skipped, as named above` : ''}.`,
  );
}
