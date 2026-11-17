import type { Page } from '@playwright/test';

import {
  authHeaders,
  hasSubject,
  messageIdWithStatus,
  publishedMessageId,
  settingValue,
} from './support/backend';
import { fixClock } from './support/clock';
import { buildDictionaryIndex, danishReason } from './support/danish';
import { ADMIN_URL, BACKEND_URL, DOMAIN, pilotDir } from './support/env';
import { expect, test } from './support/fixtures';
import { assertClean } from './support/guard';
import { loginAs } from './support/login';
import {
  GUIDES_MAIL_TO,
  mailCount,
  openNewestMail,
  sendMessageMail,
  waitForMail,
} from './support/mailhog';
import { infoUrl, openKeycloakConsole } from './support/open';
import { showMapAt, type MapView } from './support/map';
import { openPdfFirstPage } from './support/pdf';
import { shot, type ShotOptions } from './support/shot';

/*
 * The pilot: one picture of each kind the manuals need, taken on the rig's stack to prove the
 * helpers before the manuals' own specs are written. Its pictures go to the pilot folder
 * (GUIDES_PILOT_DIR, by default .pilot/ beside this file), never into the guides, and the manifest
 * check leaves this file out.
 *
 *   npx playwright test -c guides-shots/playwright.config.ts pilot.shots.ts
 */

function pilotShot(page: Page, options: ShotOptions = {}): Promise<string> {
  return shot(page, { ...options, outDir: pilotDir() });
}

// Denmark's waters, from the Skaw to Bornholm's latitude, closer than a fit to every message: the
// markers of the Belts and the Baltic stand apart, and the neighbouring countries take the edges.
const DENMARK: MapView = { lon: 11.2, lat: 56.15, zoom: 7.3 };

// The guard's Danish check, without a picture: English texts that carry Danish names pass, Danish
// sentences, dates and interface words fail.
test.describe('pilot: the Danish guard', () => {
  const index = buildDictionaryIndex(
    [
      { en: 'Draft', da: 'Kladde' },
      { en: 'Verify', da: 'Verificér' },
      { en: 'Status', da: 'Status' },
      { en: 'NW', da: 'NW' },
      // Data values (a part's subject, an area's name): refused when a text holds them, word for
      // word, unless they are names only.
      {
        en: 'Pipe works. Temporary prohibited area established',
        da: 'Rørarbejde. Midlertidigt forbudsområde oprettet',
        ui: false,
      },
      { en: 'Port of Copenhagen', da: 'Københavns Havn', ui: false },
      { en: 'Table of Contents', da: 'Indholdsfortegnelse' },
    ],
    new Set(['Draft', 'Verify', 'Status']),
  );

  test('pilot/danish-guard-passes-english', () => {
    for (const text of [
      'Denmark. The Waters South of Zealand. Bøgestrøm. Reduced depth.',
      'Denmark. The Baltic Sea. Rønne Havn. Buoy missing.',
      'Denmark. The Little Belt. Bogense Havn og Marina. Construction works.',
      '(A/S Havnen ved Kolby Kås 18 May 2026. Published 1 Jun 2026, 9:29 AM)',
      'Denmark. The Sound. Københavns Havn. Tuborg Syd. Construction Works.',
      'The light Skagen Fyr in position 57 degrees 44.1 min. North is unlit.',
      'The light Skagen fyr in position 57 degrees 44.1 min. North is unlit.',
      'Denmark. Kattegat. Odense Fjord. Odense Kanal. Bridge leaf closed.',
      "55° 25.338'N - 010° 22.824'E, Kong Christian X's Bro.",
      "54° 54,660'N - 009° 46,981'E, Kong Christian X's bro. The bridge can be contacted.",
      'Denmark. The North Sea. Hvide Sande Havn. Lights to be established.',
      'The mole head light Aggersund Broen in position 56 degrees 60.0 min. North is unlit.',
      'Signal mast 1 - at the coast at observation post South.',
      'Status',
      'NW',
      'Draft',
      'Verify',
      'Chapter 1. Introduction',
      // The third check (2026-11-11): codes, English beside Danish names, the copy's English
      // position names and NAVTEX texts, and an AtoN's name.
      'NY',
      'JA',
      'EN',
      'Show her the map',
      'Den Helder',
      'Bornholm SE, S and SW',
      'green light buoy',
      "(G) ← the Sound's northern crossing point",
      'Denmark. The Sound. Sundby Hage. Wreck. Correction.',
      'SAFE WATER LIGHT BUOY 5 56-12.7N 011-05.5E MISSING.',
      'Rønne Havn Forfyr',
      // The fourth check (2026-11-11): the copy's English for what round 3 found.
      'Signal mast.',
      'Allinge Havn traffic signal',
      'Nymindegab Firing Area signal mast S. Al.Fl.WR.4s',
      'Explanatory notes to NtM.',
      'Danish Notices to Mariners (EfS) is published by the Danish Geodata Agency.',
      // The fifth check (2026-11-11): the copy's English for the older versions of live messages.
      'Tel. 3915 7315',
      'Kronløbet, N side, N end of Marmorkaj',
      'bounded by a line through positions 1) - 4) and 5) - 8).',
      "2) 55° 41.0'N - 012° 06.0'E, yellow",
      'red spar buoy with topmark',
      'MAS duty',
      'Cable Works. Virtual AtoN established.',
      'Approach to Randers Fjord',
      'Langelandsbæltet. Dumping ground. Reduced depth.',
    ]) {
      expect(danishReason(text, index), text).toBeNull();
    }
  });

  test('pilot/danish-guard-fails-danish', () => {
    for (const text of [
      "Molefyret Aggersund Broen på pos. 56° 60,0'N - 009° 17,6'E er slukket.",
      'Indtil videre er der observeret dybder på 8,1 m i området ved nævnte position.',
      'Meddelelse fra Marinestaben om istjeneste samt om ismeldinger m.m. (Danish Only)',
      '(Grenaa Havn A/S 7. juni 2022. Published 23 Jun 2022)',
      'In the period 31 August - 16 oktober 2026, construction works will be carried out.',
      'bounded by a line through positions 1) - 4) og 5) - 8).',
      "57° 10,77'N - 009° 26,66'E, signal mast 1 ved Ejstrup Strand.",
      '[J.nr. 2023151044].',
      'Kladde',
      'Verificér',
      // Found by the second check (2026-11-11): interface words, content words, a data value.
      'Gem',
      'Gem ændringer',
      'Annullér',
      'Afventer godkendelse',
      'Bøje savnet og fyr slukket',
      'Fyr slukket',
      'Geotekniske boringer',
      'Contacted on VHF kanal 16.',
      'Tilføjet manuelt',
      'Aktive navigationsadvarsler - Grønland',
      'Farvandsoplæsninger 11-11-2026',
      'fast lys med blink, hurtige blink, grønne formørkelser',
      'Søg efter meddelelser',
      'Denmark. Greenland. E-kyst, Carlsberg Fjord. Mindre dybde.',
      'Denmark. The Sound. Rørarbejde. Midlertidigt forbudsområde oprettet.',
      // Found by the third check (2026-11-11): interface words the data does not hold, any case,
      // a hidden character inside a word, short sentences, the data's titles, subjects, position
      // names and NAVTEX texts.
      'Opret',
      'Fortsæt',
      'Indstillinger',
      'Annulleret',
      'kladde',
      'KLADDE',
      'INDHOLDSFORTEGNELSE',
      'Kla\u00ADdde',
      'Verifi\u200Bcér',
      'Skriv en kommentar her',
      'Vis på kortet',
      'Denmark. The Sound. Sundby Hage. Vrag.',
      'Kabelarbejde',
      'Denmark. Kattegat. Roskild Fiord, Veddelev. Grundstødt pram.',
      "2) 55° 41.0'N - 012° 06.0'E, usikker",
      '(G) ← Sundets nordlige overgangspunkt',
      'gul stage med krydstopbetegnelse, Fl.Y.3s.',
      'SAFE WATER LIGHT BUOY BØJE 5 56-12.7N 011-05.5E MISSING.',
      // Found by the third check, added by the fourth (2026-11-11): interface words and data words
      // the guard passed.
      'Afslut',
      'Åbn',
      'Gem som',
      'Udgaver',
      'Indtast titel',
      'Zoom ind',
      'Fjern filter',
      'Signalmast.',
      'Allinge Havn trafiksignal',
      'Nymindegab Skydeområde Signalmast S.',
      'bropille',
      'Quay established. Bassin dredged.',
      'GREENLAND. GROENLANDS SYDVESTKYST, 20 SOEMIL SW FOR BREDE- FJORDEN.',
      // Found by the fifth check (2026-11-11) in older versions of live messages, which the guard
      // passed, and the local realm's group name.
      'Tlf. 3915 7315',
      'Tlf.: +299 364000',
      'Kronløb N-lige, N-enden af Marmorkaj',
      "2) 55° 41.0'N - 012° 06.0'E, gul",
      'gul',
      'MAS-vagt',
      // The sixth check (2026-11-11): older versions of published NMs.
      'Cable Works. Virtual AtoN oprettet.',
      'Indsejlingen Randers Fjrod',
      'Langelandsbæltet. Klapplads. Reduced depth.',
      // The Publications writer (2026-11-12): a series' Danish name and patterns in form fields.
      'Ugentlig EfS',
      'EfS uge ${week} - ${year}',
      'EfS-Uge-${week}-${year}.pdf',
    ]) {
      expect(danishReason(text, index), text).not.toBeNull();
    }
  });

  test('pilot/danish-guard-on-a-page', async ({ page }) => {
    await page.setContent(
      '<main><h1>Buoy missing</h1><p>The special buoy off <b>Rønne Havn</b> is missing.</p>' +
        '<div><button>Edit</button> <button>Verify</button> <button>Publish</button></div></main>',
    );
    await assertClean(page);
    await page.setContent(
      "<main><p>Fyret <b>Skagen Fyr</b> er slukket på position 57° 44,1'N.</p>" +
        '<input placeholder="Søg efter meddelelser og kort"></main>',
    );
    await expect(assertClean(page)).rejects.toThrow(/Danish text: "Fyret Skagen Fyr er slukket/);
    await expect(
      assertClean(page, {
        allowDanish: [
          { text: /^Fyret Skagen Fyr/, reason: 'the test' },
          { text: 'Søg efter meddelelser og kort', reason: 'the test' },
        ],
      }),
    ).resolves.toBeUndefined();
  });

  // Each a Danish word the first guard let through (the second check, 2026-11-11): inside a row of
  // inline controls, inside an inline element of a sentence, or where the page's text is not.
  for (const [name, html, seen] of [
    [
      'the status button among the toolbar buttons',
      '<div><button>Edit</button> <button>Verificér</button> <button>Publish</button></div>',
      /"Verificér"/,
    ],
    ['a status in a sentence', '<p>Status: <span>Kladde</span></p>', /"Kladde"/],
    [
      'the selected option of a list box',
      '<select><option>Draft</option><option selected>Afventer godkendelse</option></select>',
      /"Afventer godkendelse"/,
    ],
    [
      'the alternative text of an image that did not load',
      '<img src="/missing.png" alt="Bøje savnet og fyr slukket" width="200" height="40">',
      /"Bøje savnet og fyr slukket"/,
    ],
    [
      'generated content',
      '<style>.state::before { content: "Kladde"; }</style><p class="state"> </p>',
      /"Kladde"/,
    ],
    ['a title', '<button title="Slet">x</button>', /"Slet"/],
    ['a shadow root', '<div id="host"></div>', /"Gem ændringer"/],
    // The third check (2026-11-11).
    [
      'a word set in capitals by CSS',
      '<style>button { text-transform: uppercase; }</style><button>kladde</button>',
      /"kladde"|"KLADDE"/,
    ],
    ['a soft hyphen inside a word', '<button>Kla&shy;dde</button>', /Danish interface word/],
    ['a zero-width space inside a word', '<button>Verifi&#8203;cér</button>', /Danish/],
    [
      'a short placeholder',
      '<textarea placeholder="Skriv en kommentar her"></textarea>',
      /"Skriv en kommentar her"/,
    ],
    ['a short title', '<button title="Vis på kortet">M</button>', /"Vis på kortet"/],
    ['a button the data does not hold', '<button>Opret</button>', /"Opret"/],
    // The fourth check (2026-11-11).
    [
      'the title of a visible SVG',
      '<svg width="24" height="24"><title>Søkort</title><rect width="24" height="24"/></svg>',
      /"Søkort"/,
    ],
    // The fifth check (2026-11-11): the Users drawer's Groups tab lists the local realm's group
    // 'MAS-vagt' among the available groups.
    [
      'a group name in a list',
      '<h3>Available groups</h3><ul><li>Sysadmin</li><li>MAS-vagt</li></ul>',
      /"MAS-vagt"/,
    ],
  ] as const) {
    test(`pilot/danish-guard-on-a-page: ${name}`, async ({ page }) => {
      await page.setContent(`<main>${html}</main>`);
      if (name === 'a shadow root') {
        await page.evaluate(() => {
          const root = document.getElementById('host')?.attachShadow({ mode: 'open' });
          if (root) root.innerHTML = '<button>Gem ændringer</button>';
        });
      }
      await expect(assertClean(page)).rejects.toThrow(seen);
    });
  }

  test('pilot/danish-guard-on-a-page: S-124', async ({ page }) => {
    await page.setContent('<main><button>Generate S-124...</button></main>');
    await expect(assertClean(page)).rejects.toThrow(/S-124 or SECOM: "Generate S-124/);
  });
});

// What the guard leaves out because it is not in the picture, each beside the same thing where
// the picture shows it, which the guard still refuses (2026-11-11).
test.describe("pilot: the guard's reach", () => {
  const DANISH = 'Fyret er slukket og bøjen er inddraget på position.';

  /** Serves `html` at an address of the administration app's origin and opens it. */
  async function openAtAdminOrigin(page: Page, html: string): Promise<void> {
    const address = `${ADMIN_URL}/guides-pilot/reach`;
    await page.route(address, (route) =>
      route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: html }),
    );
    await page.goto(address);
  }

  test("pilot/guard-reach: the rig's own scripts in a sandboxed frame", async ({ page }) => {
    const seen: string[] = [];
    page.on('console', (message) => seen.push(message.text()));
    page.on('pageerror', (error) => seen.push(error.message));
    // The same scripts as every signed-in picture: the token seeding, the session domain and the
    // stabiliser, run by Playwright in every frame, the sandboxed one included.
    await loginAs(page, 'editor');
    await openAtAdminOrigin(
      page,
      '<main><h1>Buoy missing</h1>' +
        '<iframe sandbox="" srcdoc="<p>The light is unlit.</p>"></iframe></main>',
    );
    await expect(page.frameLocator('iframe').getByText('The light is unlit.')).toBeVisible();
    // The frame did refuse them, and the guard leaves exactly that out.
    expect(seen.join('\n')).toMatch(/document is sandboxed/);
    await assertClean(page);
  });

  test('pilot/guard-reach: a sandboxed frame is still read', async ({ page }) => {
    await loginAs(page, 'editor');
    await openAtAdminOrigin(
      page,
      `<main><iframe sandbox="" srcdoc="<p>${DANISH}</p><p>person@harbour.test</p>"></iframe></main>`,
    );
    await expect(page.frameLocator('iframe').getByText('person@harbour.test')).toBeVisible();
    await expect(assertClean(page)).rejects.toThrow(/Danish text: "Fyret er slukket/);
    await expect(assertClean(page)).rejects.toThrow(/e-mail addresses outside .*: pe\*\*\*/);
  });

  test("pilot/guard-reach: the sandbox's storage error from the page's own script", async ({
    page,
  }) => {
    // The message the guard leaves out when an init script raises it in a sandboxed frame, raised
    // by a script of the page itself: it counts.
    await page.route(`${ADMIN_URL}/guides-pilot/raise.js`, (route) =>
      route.fulfill({
        contentType: 'text/javascript',
        body:
          "throw new DOMException(\"Failed to read the 'localStorage' property from 'Window': " +
          'The document is sandboxed and lacks the \'allow-same-origin\' flag.", "SecurityError");',
      }),
    );
    await openAtAdminOrigin(
      page,
      '<main><h1>Buoy missing</h1><script src="/guides-pilot/raise.js"></script></main>',
    );
    await expect(page.getByRole('heading', { name: 'Buoy missing' })).toBeVisible();
    await expect(assertClean(page)).rejects.toThrow(
      /page errors: Failed to read the 'localStorage'/,
    );
  });

  test('pilot/guard-reach: e-mail addresses within the picture only', async ({ page }) => {
    const region = { x: 0, y: 0, width: 1280, height: 400 };
    await page.setContent(
      '<main><h1>Buoy missing</h1>' +
        '<p style="margin-top: 3000px">far.below@harbour.test</p>' +
        '<iframe style="margin-top: 1000px" srcdoc="<p>framed.below@harbour.test</p>"></iframe></main>',
    );
    // Below the picture, in the page and in a frame: not in it.
    await assertClean(page, {}, undefined, region);
    // Without a region the guard reads the whole page, as before.
    await expect(assertClean(page)).rejects.toThrow(/fa\*\*\*/);
    // Inside the picture's region, under a solid layer painted above it: not in the picture.
    for (const cover of ['#ffffff', 'oklch(1 0 0)', 'rgb(250 250 250 / 1)']) {
      await page.setContent(
        '<main><p>under.a.dialog@harbour.test</p><div style="position: fixed; inset: 0; ' +
          `background: ${cover}"><p>The dialog</p></div></main>`,
      );
      await assertClean(page, {}, undefined, region);
    }
    for (const [html, seen] of [
      ['<p>near@harbour.test</p>', /ne\*\*\*/],
      ['<p>Write to <b>split</b>@harbour.test today.</p>', /sp\*\*\*/],
      ['<input value="field@harbour.test">', /fi\*\*\*/],
      ['<iframe srcdoc="<p>framed@harbour.test</p>"></iframe>', /fr\*\*\*/],
      ['<p style="margin-top: 390px">cut.by.the.edge@harbour.test</p>', /cu\*\*\*/],
      // Behind a translucent scrim the page stays legible.
      [
        '<p>dimmed@harbour.test</p><div style="position: fixed; inset: 0; background: rgba(0, 0, 0, 0.4)"></div>',
        /di\*\*\*/,
      ],
      // Covered in part: the rest shows.
      [
        '<p style="font-size: 30px">half.covered@harbour.test</p>' +
          '<div style="position: fixed; left: 0; top: 0; width: 120px; height: 400px; background: #fff"></div>',
        /ha\*\*\*/,
      ],
      // A text that takes no pointer, over a solid button: the browser cannot tell, so it shows.
      [
        '<button style="position: relative; width: 320px; height: 40px; background: #fff"></button>' +
          '<span style="position: absolute; left: 16px; top: 16px; pointer-events: none">label@harbour.test</span>',
        /la\*\*\*/,
      ],
    ] as const) {
      await page.setContent(`<main>${html}</main>`);
      if (html.includes('iframe')) {
        await expect(page.frameLocator('iframe').getByText('framed@harbour.test')).toBeVisible();
      }
      await expect(assertClean(page, {}, undefined, region), html).rejects.toThrow(seen);
    }
  });

  test('pilot/guard-reach: hidden text is not read, shown text is', async ({ page }) => {
    for (const style of ['visibility: hidden', 'opacity: 0', 'display: none']) {
      await page.setContent(`<main><h1>Buoy missing</h1><p style="${style}">${DANISH}</p></main>`);
      await assertClean(page);
    }
    await page.setContent(
      `<main><h1>Buoy missing</h1><p style="opacity: 0.4">${DANISH}</p></main>`,
    );
    await expect(assertClean(page)).rejects.toThrow(/Danish text: "Fyret er slukket/);
  });

  test('pilot/guard-reach-hide.png', async ({ page }) => {
    await page.setContent(
      `<main><h1>Buoy missing</h1><aside style="height: 40px">${DANISH}</aside></main>`,
    );
    const heading = page.getByRole('heading', { name: 'Buoy missing' });
    const aside = page.locator('aside');
    // Shown, the text refuses the picture even outside its target; left out by `hide`, it does not.
    await expect(pilotShot(page, { target: heading, maps: false })).rejects.toThrow(
      /Danish text: "Fyret er slukket/,
    );
    await pilotShot(page, { target: heading, hide: [aside], maps: false });
    await expect(aside).toBeHidden();
  });
});

test.describe('pilot', () => {
  test('pilot/messages-list.png', async ({ page }) => {
    await loginAs(page, 'editor');
    await page.goto('/messages/table');
    await expect(page.getByRole('row').nth(1)).toBeVisible();
    await pilotShot(page);
  });

  test('pilot/filter-panel-status.png', async ({ page }) => {
    await loginAs(page, 'editor');
    await page.goto('/messages/table?status=PUBLISHED&status=DRAFT');
    const panel = page.locator('app-status-filter-panel');
    await expect(panel.getByRole('button').first()).toBeVisible();
    await pilotShot(page, { target: panel });
  });

  test('pilot/message-details-dialog.png', async ({ page }) => {
    await loginAs(page, 'editor');
    const id = await publishedMessageId(page.request);
    await page.goto(`/messages/table?messageId=${encodeURIComponent(id)}`);
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    // Edge to edge: a margin would show a strip of the dimmed page behind the dialog.
    await pilotShot(page, { target: dialog, pad: 0 });
  });

  test('pilot/domain-menu.png', async ({ page }) => {
    await loginAs(page, 'editor');
    await page.goto('/messages/table');
    // The desktop header's first menu trigger is the session domain's (then account, language).
    const trigger = page.locator('app-main-header nav button[aria-haspopup]').first();
    await trigger.click();
    const menu = page.getByRole('menu');
    await expect(menu).toBeVisible();
    // Framed with the header's whole group of menus, so no control is cut at the edge; the header
    // stays as it is and the page under the menu is painted over.
    await pilotShot(page, {
      target: [trigger.locator('xpath=..'), menu],
      keep: [page.locator('app-main-header'), menu],
    });
  });

  test('pilot/editor-edit-page.png', async ({ page }) => {
    await fixClock(page);
    await loginAs(page, 'editor');
    const id = await messageIdWithStatus(page.request, 'editor', 'DRAFT', hasSubject);
    // Opening a draft in the editor reads it; nothing is saved.
    await page.goto(`/editor/edit/${encodeURIComponent(id)}`);
    await expect(page.locator('app-section-panel').first()).toBeVisible();
    await pilotShot(page);
  });

  test('pilot/messages-map.png', async ({ page }) => {
    await loginAs(page, 'editor');
    await page.goto('/messages/map');
    await expect(page.locator('app-ol-map')).toBeVisible();
    await showMapAt(page, DENMARK);
    await pilotShot(page);
  });

  test('pilot/admin-charts.png', async ({ page }) => {
    await loginAs(page, 'admin');
    await page.goto('/admin/charts');
    await expect(page.getByRole('row').nth(1)).toBeVisible();
    await pilotShot(page);
  });

  // The chart drawer is as tall as the window; at 800 px its footer covers the chart's map, at
  // 880 px every field and the whole map show above it.
  test.describe('in a taller window', () => {
    test.use({ viewport: { width: 1280, height: 880 } });

    test('pilot/admin-chart-drawer.png', async ({ page }) => {
      await loginAs(page, 'admin');
      await page.goto('/admin/charts');
      // The drawer is opened and photographed, never saved.
      await page
        .getByRole('button', { name: /edit chart/i })
        .first()
        .click();
      const drawer = page.getByRole('dialog', { name: /chart$/i });
      await expect(drawer).toBeVisible();
      await pilotShot(page, { target: drawer, pad: 0 });
    });
  });

  test('pilot/sysadmin-settings.png', async ({ page }) => {
    await loginAs(page, 'sysadmin');
    await page.goto('/sysadmin/settings');
    await expect(page.locator('table tbody tr').first()).toBeVisible();
    await pilotShot(page);
  });

  test('pilot/public-map.png', async ({ page }) => {
    await fixClock(page);
    await page.goto(infoUrl('/messages/map'));
    await expect(page.locator('app-ol-map')).toBeVisible();
    // The list of messages without a position folds away, as a reader does to see the map, and
    // the map shows the navigational warnings: with the 236 notices to mariners of the data as
    // well, the markers cover Denmark's waters at any zoom that shows the whole country.
    await page.getByRole('button', { name: 'Minimize' }).click();
    await page.getByRole('button', { name: 'Notices to Mariners' }).click();
    await showMapAt(page, DENMARK);
    await pilotShot(page);
  });

  test('pilot/public-publications.png', async ({ page }) => {
    await fixClock(page);
    await page.goto(infoUrl('/publications'));
    await expect(page.locator('app-main-menu nav')).toBeVisible();
    await pilotShot(page);
  });

  test.describe('at phone width', () => {
    test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });

    test('pilot/public-publications-phone.png', async ({ page }) => {
      await fixClock(page);
      await page.goto(infoUrl('/publications'));
      await expect(page.locator('app-main-menu nav')).toBeVisible();
      // The page is taller than a phone's window and its lists sit in one bordered card: the
      // window's edge would cut the card open, so the picture runs to the card's end and the footer.
      await pilotShot(page, { whole: true });
    });
  });

  test('pilot/keycloak-clients.png', async ({ page }) => {
    const hide = await openKeycloakConsole(page, 'niord', 'clients');
    // Niord's own clients, without Keycloak's built-in ones.
    const search = page.getByPlaceholder('Search for client');
    await search.fill('niord');
    await search.press('Enter');
    await expect(page.locator('table tbody tr').first()).toContainText('niord');
    await search.blur();
    await pilotShot(page, { hide, maps: false, guard: { checks: 'external' } });
  });

  test('pilot/mailhog-mail.png', async ({ page }) => {
    test.setTimeout(240_000);
    // With LOG (the default) the backend only logs a mail; MailHog catches every one it sends.
    const recipients = String(await settingValue(page.request, 'mailValidRecipients'));
    test.skip(
      !/\bALL\b/.test(recipients) && !recipients.includes(GUIDES_MAIL_TO),
      `mailValidRecipients is ${recipients} in the rig's database: no mail would reach MailHog.`,
    );
    const subject = 'Navigational warnings (guides)';
    const before = await mailCount(page.request, subject);
    await sendMessageMail(page.request, await publishedMessageId(page.request), subject);
    await waitForMail(page.request, subject, before);
    const mail = await openNewestMail(page, subject);
    // The sender is the base product's default address, an organisation's: the guard records it.
    await pilotShot(page, { target: mail, pad: 16, maps: false, guard: { checks: 'external' } });
  });

  test('pilot/report-first-page.png', async ({ page }) => {
    const query = new URLSearchParams({ domain: DOMAIN, status: 'PUBLISHED', lang: 'en' });
    const pdf = await openPdfFirstPage(page, {
      url: `${BACKEND_URL}/rest/message-reports/report.pdf?${query}`,
      headers: await authHeaders('editor'),
    });
    await pilotShot(page, { target: pdf, pad: 0, maps: false, guard: { checks: 'external' } });
  });
});
