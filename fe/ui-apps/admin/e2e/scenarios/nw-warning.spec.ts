import type { APIRequestContext, Locator, Page } from '@playwright/test';

import { expect, test } from '../auth/fixtures';
import {
  AdminApi,
  asTestUser,
  english,
  PublicApi,
  type MessageVo,
  type PromulgationVo,
} from './support/api';
import {
  expectPositions,
  expectSame,
  expectSameList,
  expectShows,
  normalise,
  pointsOf,
} from './support/compare';
import { annotate, describeScenario, scenarioEnv } from './support/env';
import { knownFault } from './support/known-fault';
import { waitForMails, type Mail } from './support/mailhog';
import { keepOffline } from './support/offline';
import { pdfText } from './support/pdf-text';
import { restartBackend } from './support/restart';
import { newRunMarker, RUN_MARKER } from './support/run-id';

/*
 * Scenario 1: a navigational warning from a template to its cancellation, as a duty officer takes
 * it. The editor makes the warning from the base product's "Light unlit" template, saves it, edits
 * it, verifies and publishes it; the warning is then read in the admin API, the public API, on the
 * public site, in the PDFs and in the mail it sends; the backend is restarted and all of it read
 * again; then the warning is cancelled, and the cancellation warning the backend publishes is read
 * on the Status page, in both APIs, its PDF, on the public site and in the mails.
 *
 * The set-up makes what the sample lacks: a contact on example.org and an active mailing list of
 * the run's own, whose triggers fire only for a warning whose title carries the run's marker; once
 * the warning has its short id, the list also takes the cancellation warning, whose title names
 * it. The list is switched off at the end; the messages stay as the run's record. The set-up first
 * tidies what an earlier run that crashed left behind: its list still on, its warning still in
 * force.
 */

const RUN = newRunMarker();
const DOMAIN = 'niord-nw';
const TEMPLATE = 'Light unlit';
const VICINITY = `${RUN} harbour`;
const VICINITY_EDITED = `${RUN} north harbour`;
const LIGHT_NAME = `${RUN} West Pier`;
const POINT_NAME = `${RUN} West Pier light`;
const CONTACT = `e2e-${RUN.toLowerCase()}@example.org`;
const MAILING_LIST = `e2e-nw-${RUN.toLowerCase()}`;
const PUBLISH_SUBJECT = 'E2E published';
const CANCEL_SUBJECT = 'E2E cancelled';

// Near a whole degree, so the texts must carry the rounding into the degree.
const TYPED_POSITION = "55° 59.99'N 012° 29.996'E";
const ROUNDED_POSITION = "56° 00.0'N - 012° 30.0'E";
const NAVTEX_POSITION = '56-00.0N 012-30.0E';

// What the steps hand on to the next.
let seriesId = '';
let mailingListCreated = false;
let messageId = '';
let shortId = '';
let lightType = '';
let savedVersions: number[] = [];
let beforeRestart: Readback | undefined;

interface Readback {
  readonly admin: Pick<MessageVo, 'status' | 'number' | 'shortId' | 'publishDateFrom'> & {
    readonly title: string;
  };
  readonly versions: number[];
  readonly publicTitle: string;
  readonly publishMails: number;
}

interface HistoryVo {
  readonly version: number;
  readonly status: string;
}

/**
 * The run's list fires for a coastal warning of the run's series whose title carries the run's
 * marker and, once the warning has its short id, for the cancellation warning whose title names it.
 */
function listFilter(cancelledShortId?: string): string {
  const title = "String(msg.getDesc('en').title)";
  const ours = [RUN, cancelledShortId]
    .filter((text): text is string => !!text)
    .map((text) => `${title}.indexOf('${text}') >= 0`)
    .join(' || ');
  return `msg.messageSeries.seriesId == '${seriesId}' && msg.type == 'COASTAL_WARNING' && (${ours})`;
}

/* ---------------------------------------------------------------------------------------------- */
/* Reading the editor                                                                              */
/* ---------------------------------------------------------------------------------------------- */

/** A preamble or part row of the edit form, by its label. */
function row(page: Page, label: string): Locator {
  return page.locator('main').getByRole('button', { name: new RegExp(`^${label}\\b`) });
}

/** The value a collapsed row shows: its text without the label. */
async function rowValue(page: Page, label: string): Promise<string> {
  const text = await row(page, label).first().innerText();
  return normalise(text.replace(new RegExp(`^\\s*${label}\\s*\\*?`), ''));
}

/** Closes a row opened for editing, so it shows its value again. */
async function collapse(page: Page, label: string): Promise<void> {
  const head = row(page, label).first();
  if ((await head.getAttribute('aria-expanded')) === 'true') await head.click();
  await expect(head).toHaveAttribute('aria-expanded', 'false');
}

/** The rendered message of the editor's live preview or the Status page. */
function preview(page: Page): Locator {
  return page.locator('main article').first();
}

async function expandPromulgation(page: Page): Promise<Locator> {
  const field = page.locator('app-promulgation-field');
  const head = row(page, 'Promulgation');
  if ((await head.getAttribute('aria-expanded')) !== 'true') await head.click();
  await expect(field.getByRole('tab').first()).toBeVisible();
  return field;
}

function navtexOf(message: MessageVo): PromulgationVo | undefined {
  return message.promulgations?.find((p) => p.type.typeId === 'navtex');
}

function audioOf(message: MessageVo): PromulgationVo | undefined {
  return message.promulgations?.find((p) => p.type.typeId === 'audio');
}

/** The editor's form against the admin API's editable message: every field the warning has. */
async function expectEditorMatchesApi(page: Page, api: AdminApi): Promise<MessageVo> {
  const message = await api.get<MessageVo>(`/rest/messages/editable-message/${messageId}`, {
    lang: 'en',
  });
  const part = message.parts?.[0];
  expectSame('title', await rowValue(page, 'Title'), english(message.descs, 'title'));
  expectSame(
    'areas',
    await rowValue(page, 'Areas'),
    [
      ...(message.areas ?? []).map((area) => english(area.descs, 'name')),
      english(message.descs, 'vicinity'),
    ].join(' · '),
  );
  expectSame(
    'categories',
    await rowValue(page, 'Categories'),
    (message.categories ?? []).map((category) => english(category.descs, 'name')).join(', '),
  );
  expectPositions(
    'positions',
    await rowValue(page, 'Positions'),
    pointsOf(part?.geometry?.features ?? []),
  );
  expectSame('description', await rowValue(page, 'Description'), english(part?.descs, 'details'));

  const promulgation = await expandPromulgation(page);
  await promulgation.getByRole('tab', { name: /navtex/i }).click();
  expectSame(
    'NAVTEX text',
    await promulgation.getByRole('textbox', { name: /navtex message body/i }).inputValue(),
    navtexOf(message)?.text,
  );
  expect(navtexOf(message)?.promulgate, 'NAVTEX is promulgated').toBe(true);
  return message;
}

/* ---------------------------------------------------------------------------------------------- */
/* Reading the published warning                                                                  */
/* ---------------------------------------------------------------------------------------------- */

/** The admin API's message and the public API's, by uid and by short id, hold the same warning. */
async function expectPublicMatchesAdmin(
  api: AdminApi,
  publicApi: PublicApi,
  status: string,
): Promise<MessageVo> {
  const admin = await api.get<MessageVo>(`/rest/messages/message/${messageId}`, { lang: 'en' });
  expect(admin.status).toBe(status);
  for (const id of [messageId, shortId]) {
    const published = await publicApi.get<MessageVo>(`/rest/public/v1/message/${id}`, {
      lang: 'en',
    });
    expect(published.id, `the public API by ${id}`).toBe(messageId);
    expect(published.status, `the public API by ${id}`).toBe(status);
    expect(published.shortId).toBe(shortId);
    expect(published.number).toBe(admin.number);
    expectSame(
      `public title by ${id}`,
      english(published.descs, 'title'),
      english(admin.descs, 'title'),
    );
    expectSame(
      `public details by ${id}`,
      english(published.parts?.[0]?.descs, 'details'),
      english(admin.parts?.[0]?.descs, 'details'),
    );
    expect(pointsOf(published.parts?.[0]?.geometry?.features ?? [])).toEqual(
      pointsOf(admin.parts?.[0]?.geometry?.features ?? []),
    );
  }
  return admin;
}

async function expectInPublicList(publicApi: PublicApi, present: boolean): Promise<void> {
  const list = await publicApi.get<MessageVo[]>('/rest/public/v1/messages', {
    lang: 'en',
    mainType: 'NW',
  });
  expect(
    list.some((message) => message.id === messageId),
    `the public NW list ${present ? 'holds' : 'leaves out'} ${shortId}`,
  ).toBe(present);
}

/** Records in the report that a read was left out, and why. */
function leftOut(description: string): void {
  annotate('skip', description);
}

/** The public site's page of the warning shows its short id, title and details. */
async function expectPublicSite(page: Page, title: string, details: string): Promise<void> {
  if (!scenarioEnv.infoUrl) {
    leftOut('E2E_INFO_URL is not set: the public site is not read');
    return;
  }
  await page.goto(`${scenarioEnv.infoUrl}/messages/details/message/${shortId}`);
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText(shortId);
  const text = await dialog.innerText();
  expectShows('public site title', text, title);
  expectShows('public site details', text, details);
  expectShows('public site position', text, ROUNDED_POSITION);
}

/** The message PDF and the NW list PDF hold the short id, the title and the rounded position. */
async function expectPdfs(publicApi: PublicApi, title: string): Promise<void> {
  const reads: [string, string, Record<string, string>][] = [
    ['message PDF', `/rest/message-reports/message/${shortId}.pdf`, { lang: 'en' }],
    ['NW list PDF', '/rest/message-reports/report.pdf', { lang: 'en', mainType: 'NW' }],
  ];
  for (const [name, path, params] of reads) {
    const response = await publicApi.fetch(path, params);
    expect(response.ok(), `${name}: ${response.status()}`).toBe(true);
    expect(response.headers()['content-type']).toContain('pdf');
    const text = normalise(await pdfText(await response.body()));
    expectShows(`${name} short id`, text, shortId);
    expectShows(`${name} title`, text, title);
    expectShows(`${name} position`, text, ROUNDED_POSITION);
  }
}

const isPublishMail = (mail: Mail): boolean => mail.subject === `${PUBLISH_SUBJECT} ${shortId}`;
const isCancelMail = (mail: Mail): boolean => mail.subject === `${CANCEL_SUBJECT} ${shortId}`;

async function publishMails(request: APIRequestContext): Promise<Mail[]> {
  return waitForMails(request, CONTACT, isPublishMail, 1);
}

async function historyVersions(api: AdminApi): Promise<number[]> {
  const history = await api.get<HistoryVo[]>(`/rest/messages/message/${messageId}/history`);
  return history.map((entry) => entry.version);
}

/** The History tab lists the versions the API holds, in its order. */
async function expectHistoryTab(page: Page, api: AdminApi): Promise<number[]> {
  const versions = await historyVersions(api);
  await page
    .locator('main nav')
    .getByRole('link', { name: /^history$/i })
    .click();
  const rows = page.locator('main table tbody tr');
  await expect(rows).toHaveCount(versions.length);
  const shown = await rows.locator('td:nth-child(2)').allInnerTexts();
  expectSameList('history versions', shown, versions.map(String));
  return versions;
}

/* ---------------------------------------------------------------------------------------------- */
/* The scenario                                                                                    */
/* ---------------------------------------------------------------------------------------------- */

describeScenario('a navigational warning from a template to its cancellation', () => {
  test.beforeEach(async ({ page, baseURL }) => {
    await keepOffline(page, baseURL);
    // The duty officer works in the NW domain of the sample.
    await page.addInitScript((domain) => {
      localStorage.setItem('niord.sessionDomainId', domain);
    }, DOMAIN);
  });

  test.afterAll(async ({ playwright }, testInfo) => {
    if (!mailingListCreated) return;
    const baseURL = String(testInfo.project.use.baseURL ?? '');
    // The run's mailing list is switched off, so a later run's warnings never reach it. A run that
    // crashes before this point leaves it on; the next run's set-up switches it off.
    await asTestUser(playwright, baseURL, 'sysadmin', DOMAIN, async (request) => {
      const path = `/rest/mailing-lists/mailing-list/${MAILING_LIST}`;
      const list = (await (await request.get(path)).json()) as Record<string, unknown>;
      const response = await request.put(path, { data: { ...list, active: false } });
      expect(response.ok(), `the mailing list ${MAILING_LIST} switched off`).toBe(true);
    });
  });

  test('set-up: a contact and a mailing list of the run catch the warning’s mails', async ({
    sysadminPage: page,
  }) => {
    const api = new AdminApi(page, DOMAIN);

    await test.step('what an earlier run left switched on is switched off', async () => {
      // A run that crashed skipped its end: its list stays on and its warning in force.
      const lists = await api.get<{ mailingListId: string; active: boolean }[]>(
        '/rest/mailing-lists/search',
        { lang: 'en' },
      );
      const earlierList = new RegExp(`^e2e-nw-${RUN_MARKER.source.toLowerCase()}$`);
      for (const list of lists.filter((l) => l.active && earlierList.test(l.mailingListId))) {
        const path = `/rest/mailing-lists/mailing-list/${list.mailingListId}`;
        const stored = await api.get<Record<string, unknown>>(path);
        await api.put(path, { ...stored, active: false });
        annotate('tidy', `an earlier run's mailing list ${list.mailingListId} switched off`);
      }
      const inForce = await api.get<{ data: MessageVo[] }>('/rest/messages/search', {
        lang: 'en',
        mainType: 'NW',
        status: 'PUBLISHED',
        maxSize: 1000,
      });
      for (const message of inForce.data.filter(
        (m) => m.kind !== 'CANCELLATION' && RUN_MARKER.test(english(m.descs, 'vicinity')),
      )) {
        await api.putWord(`/rest/messages/message/${message.id}/status`, 'CANCELLED');
        annotate('tidy', `an earlier run's warning ${message.shortId ?? message.id} cancelled`);
      }
    });

    const domains = await api.get<
      {
        domainId: string;
        messageSeries?: { seriesId: string; mainType: string; shortFormat?: string }[];
      }[]
    >('/rest/domains/all', { lang: 'en' });
    const series = domains
      .find((domain) => domain.domainId === DOMAIN)
      ?.messageSeries?.find((s) => s.mainType === 'NW' && !!s.shortFormat);
    expect(series, `the sample's ${DOMAIN} has an NW series with short ids`).toBeDefined();
    seriesId = series?.seriesId ?? '';

    const contact = await api.post<{ id: number }>('/rest/contacts/contact', {
      email: CONTACT,
      name: `E2E contact ${RUN}`,
      language: 'en',
    });
    const trigger = (status: string, template: string, subject: string) => ({
      type: 'STATUS_CHANGE',
      statusChanges: [status],
      messageFilter: listFilter(),
      scriptResourcePaths: [`templates/mailinglist/${template}`],
      descs: [{ lang: 'en', subject: `${subject} \${short-id}` }],
    });
    await api.post('/rest/mailing-lists/mailing-list', {
      mailingListId: MAILING_LIST,
      active: true,
      descs: [
        { lang: 'en', name: `E2E ${RUN}`, description: 'The duty-officer scenario of one run' },
      ],
      triggers: [
        trigger('PUBLISHED', 'publish-navwarn.ftl', PUBLISH_SUBJECT),
        trigger('CANCELLED', 'cancel-navwarn.ftl', CANCEL_SUBJECT),
      ],
      contacts: [{ id: contact.id, email: CONTACT }],
    });
    mailingListCreated = true;

    const stored = await api.get<{ active: boolean; contacts?: { email: string }[] }>(
      `/rest/mailing-lists/mailing-list/${MAILING_LIST}`,
      { includeRecipients: true },
    );
    expect(stored.active).toBe(true);
    expect(stored.contacts?.map((c) => c.email)).toEqual([CONTACT]);
  });

  test('the duty officer makes the warning from a template and saves it', async ({
    editorPage: page,
  }) => {
    const api = new AdminApi(page, DOMAIN);

    await page.goto('/editor');
    await expect(page.getByRole('heading', { name: /create a message/i })).toBeVisible();
    const fromTemplate = page.locator('main').getByRole('button', { name: /template/i });
    await expect(fromTemplate.first()).toBeVisible();
    await fromTemplate.first().click();

    await test.step('the template is chosen and filled in', async () => {
      const overlay = page.locator('.cdk-overlay-container');
      await overlay.getByRole('combobox').fill(TEMPLATE);
      const option = overlay.getByRole('option').filter({ hasText: TEMPLATE }).first();
      const found = await option
        .waitFor({ state: 'visible', timeout: 10_000 })
        .then(() => true)
        .catch(() => false);
      test.skip(!found, `the template "${TEMPLATE}" is not in the domain's catalogue`);
      await option.click();
      await page.waitForURL(/\/editor\/execute-template(?:[?#]|$)/);

      const view = page.locator('app-template-execution-view');
      // A new type makes the backend derive the promulgations again; tick them once it has.
      const adjusted = page.waitForResponse((r) => r.url().includes('/adjust-editable-message'));
      await view.getByRole('button', { name: /^coastal$/i }).click();
      expect((await adjusted).ok()).toBe(true);
      const navtexBox = view.getByRole('checkbox', { name: /navtex/i });
      const audioBox = view.getByRole('checkbox', { name: /audio/i });
      await navtexBox.check();
      await audioBox.check();

      await view.getByRole('button', { name: /^edit as text$/i }).click();
      const dialog = page.getByRole('dialog', { name: /edit as text/i });
      await dialog.getByRole('textbox').fill(`Point, en: ${POINT_NAME}\n${TYPED_POSITION}`);
      await expect(dialog).toContainText(/1 feature/);
      await dialog.getByRole('button', { name: /save geometry/i }).click();
      await expect(dialog).toBeHidden();
      await expect(view).toContainText(POINT_NAME);

      const typeBox = view.getByRole('combobox', { name: /light type/i });
      await typeBox.click();
      const types = page.getByRole('listbox', { name: /light type/i }).getByRole('option');
      await expect(types.first()).toBeVisible();
      const moleHead = types.filter({ hasText: /^mole head light$/ });
      const choice = (await moleHead.count()) > 0 ? moleHead.first() : types.first();
      lightType = normalise(await choice.innerText());
      await choice.click();
      await view.getByRole('textbox', { name: /light name/i }).fill(LIGHT_NAME);
      await view.getByRole('textbox', { name: /^text in english$/i }).fill(VICINITY);
      await expect(navtexBox).toBeChecked();
      await expect(audioBox).toBeChecked();
    });

    await test.step('the preview holds what was put in', async () => {
      const article = page.locator('app-template-execution-view article');
      await expect(article).toContainText(VICINITY);
      await expect(article).toContainText(LIGHT_NAME);
      await expect(article).toContainText(lightType);
      // 55° 59.99' rounds up into the whole degree, 29.996' into 30.0'.
      await expect(article).toContainText(ROUNDED_POSITION);
      await expect(article).toContainText(TEMPLATE);
    });

    await test.step('Execute hands an unsaved warning to the editor', async () => {
      await page.getByRole('button', { name: /^execute$/i }).click();
      await page.waitForURL(/\/editor\/edit(?:[?#]|$)/);
      await expect(page.getByText(/unsaved changes/i)).toBeVisible();
      await expect(page.getByRole('button', { name: /^save$/i })).toBeEnabled();
      const article = preview(page);
      await expect(article).toContainText(ROUNDED_POSITION);
      // The NAVTEX text the template wrote, in capitals.
      await expect(article).toContainText(LIGHT_NAME.toUpperCase());
      await expect(article).toContainText(NAVTEX_POSITION);
    });

    await test.step('the series is chosen and the warning saved', async () => {
      await row(page, 'ID').click();
      await page.getByRole('combobox', { name: /message series/i }).click();
      await page.getByRole('option', { name: seriesId, exact: true }).click();
      await page.getByRole('button', { name: /^save$/i }).click();
      await page.waitForURL(/\/editor\/edit\/[^/?#]+/);
      await expect(page.getByText(/all changes saved/i)).toBeVisible();
      messageId = /\/editor\/edit\/([^/?#]+)/.exec(page.url())?.[1] ?? '';
      expect(messageId).not.toBe('');
    });

    await test.step('the form and the admin API hold the same warning', async () => {
      const message = await expectEditorMatchesApi(page, api);
      expect(message.status).toBe('DRAFT');
      expect(message.messageSeries?.seriesId).toBe(seriesId);
      expect(message.type).toBe('COASTAL_WARNING');
      expect(english(message.descs, 'vicinity')).toBe(VICINITY);
      // Stored as typed, within the typed precision; only the texts round.
      expectPositions(
        'stored position',
        TYPED_POSITION,
        pointsOf(message.parts?.[0]?.geometry?.features ?? []),
      );
      expectShows(
        'stored details',
        english(message.parts?.[0]?.descs, 'details'),
        ROUNDED_POSITION,
      );
      expectShows('stored NAVTEX text', navtexOf(message)?.text, NAVTEX_POSITION);
      // The template writes no audio text; the run gives the ticked Audio the text of the details, so the
      // save keeps it (PROM20).
      expect(audioOf(message)?.promulgate, 'Audio stays promulgated').toBe(true);
      expect(audioOf(message)?.text ?? '', 'Audio has a text').not.toBe('');
    });

    await test.step('after a reload the form shows the same', async () => {
      await page.reload();
      await expect(page.getByText(/all changes saved/i)).toBeVisible();
      await expectEditorMatchesApi(page, api);
    });
  });

  test('the vicinity is edited and saved; History holds every version', async ({
    editorPage: page,
  }) => {
    test.skip(!messageId, 'no warning was saved (an earlier test skipped or failed)');
    const api = new AdminApi(page, DOMAIN);
    await page.goto(`/editor/edit/${messageId}`);
    await expect(page.getByText(/all changes saved/i)).toBeVisible();

    await row(page, 'Areas').click();
    await page.getByRole('textbox', { name: /^text in english$/i }).fill(VICINITY_EDITED);
    await expect(page.getByText(/unsaved changes/i)).toBeVisible();
    await page.getByRole('button', { name: /^save$/i }).click();
    await expect(page.getByText(/all changes saved/i)).toBeVisible();
    await collapse(page, 'Areas');

    const message = await expectEditorMatchesApi(page, api);
    expect(english(message.descs, 'vicinity')).toBe(VICINITY_EDITED);
    expectShows('auto title', english(message.descs, 'title'), VICINITY_EDITED);

    await page.reload();
    await expect(page.getByText(/all changes saved/i)).toBeVisible();
    await expectEditorMatchesApi(page, api);

    savedVersions = await expectHistoryTab(page, api);
    // One version per save, the newest first.
    expect(savedVersions).toHaveLength(2);
    expect([...savedVersions].sort((a, b) => b - a)).toEqual(savedVersions);
  });

  test('the warning is verified and published, and read everywhere it goes', async ({
    editorPage: page,
    request,
  }) => {
    test.skip(!messageId, 'no warning was saved (an earlier test skipped or failed)');
    const api = new AdminApi(page, DOMAIN);
    const publicApi = new PublicApi(request);

    await test.step('Verify, then Publish', async () => {
      await page.goto(`/editor/status/${messageId}`);
      await page.getByRole('button', { name: /^verify message/i }).click();
      const confirm = page.getByRole('alertdialog');
      await confirm.getByRole('button', { name: /^verify$/i }).click();
      await expect(page.getByText(/status:\s*verified/i)).toBeVisible();
      expect((await api.get<MessageVo>(`/rest/messages/message/${messageId}`)).status).toBe(
        'VERIFIED',
      );

      await page.getByRole('button', { name: /^publish message/i }).click();
      const dialog = page.getByRole('dialog', { name: /confirm publication/i });
      await dialog.getByRole('button', { name: /^publish$/i }).click();
      await expect(page.getByText(/status:\s*published/i)).toBeVisible();
    });

    let title = '';
    let details = '';
    await test.step('the Status page and both APIs hold the published warning', async () => {
      const admin = await api.get<MessageVo>(`/rest/messages/editable-message/${messageId}`, {
        lang: 'en',
      });
      expect(admin.status).toBe('PUBLISHED');
      shortId = admin.shortId ?? '';
      expect(shortId).toMatch(/^NW-\d{3,}-\d{2}$/);
      title = english(admin.descs, 'title');
      details = english(admin.parts?.[0]?.descs, 'details');

      const article = preview(page);
      await expect(article).toContainText(shortId);
      expectShows('status page title', await article.innerText(), title);
      expectShows('status page details', await article.innerText(), details);
      // The NAVTEX text as published: its preamble filled with the number.
      const navtex = navtexOf(admin);
      expect(navtex?.preamble ?? '').not.toContain('${');
      expectShows('status page NAVTEX preamble', await article.innerText(), navtex?.preamble);
      expectShows('status page NAVTEX text', await article.innerText(), navtex?.text);

      await expectPublicMatchesAdmin(api, publicApi, 'PUBLISHED');
      await expectInPublicList(publicApi, true);
    });

    await test.step('the PDFs hold it', async () => {
      await expectPdfs(publicApi, title);
    });

    await test.step('the public site shows it', async () => {
      await expectPublicSite(page, title, details);
    });

    await test.step('one publish mail reaches the run’s contact', async () => {
      if (!scenarioEnv.mailhogUrl) {
        leftOut('E2E_MAILHOG_URL is not set: the mails are not read');
        return;
      }
      const mails = await publishMails(request);
      expect(mails, 'one publish mail, not two').toHaveLength(1);
      expectShows('publish mail', mails[0].text, shortId);
      expectShows('publish mail', mails[0].text, VICINITY_EDITED);
      expectShows('publish mail', mails[0].text, ROUNDED_POSITION);
    });
  });

  test('a user without the editor role reads the warning but cannot change it', async ({
    userPage: page,
  }) => {
    test.skip(!shortId, 'no warning was published (an earlier test skipped or failed)');
    await page.goto(`/editor/status/${messageId}`);
    await expect(preview(page)).toContainText(shortId);
    await expect(page.getByText(/status:\s*published/i)).toBeVisible();
    await expect(page.getByRole('button', { name: /^cancel message/i })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /^copy message/i })).toHaveCount(0);
  });

  test('after a restart of the backend everything reads the same', async ({
    editorPage: page,
    request,
  }) => {
    test.skip(!shortId, 'no warning was published (an earlier test skipped or failed)');
    const api = new AdminApi(page, DOMAIN);
    const publicApi = new PublicApi(request);

    const readBack = async (): Promise<Readback> => {
      const admin = await api.get<MessageVo>(`/rest/messages/message/${messageId}`, {
        lang: 'en',
      });
      const published = await publicApi.get<MessageVo>(`/rest/public/v1/message/${shortId}`, {
        lang: 'en',
      });
      return {
        admin: {
          status: admin.status,
          number: admin.number,
          shortId: admin.shortId,
          publishDateFrom: admin.publishDateFrom,
          title: english(admin.descs, 'title'),
        },
        versions: await historyVersions(api),
        publicTitle: english(published.descs, 'title'),
        publishMails: scenarioEnv.mailhogUrl ? (await publishMails(request)).length : 0,
      };
    };

    beforeRestart = await readBack();
    expect(beforeRestart.versions.length).toBe(savedVersions.length + 2);

    await restartBackend(request);

    await test.step('the APIs, the history and the mails are unchanged', async () => {
      expect(await readBack()).toEqual(beforeRestart);
    });

    await test.step('the Status and History pages show the same after a reload', async () => {
      await page.goto(`/editor/status/${messageId}`);
      await expect(page.getByText(/status:\s*published/i)).toBeVisible();
      await expect(preview(page)).toContainText(shortId);
      expectShows('status page title', await preview(page).innerText(), beforeRestart?.admin.title);
      await expectHistoryTab(page, api);
    });

    await test.step('the public answers, the PDFs and the public site are unchanged', async () => {
      const admin = await expectPublicMatchesAdmin(api, publicApi, 'PUBLISHED');
      await expectInPublicList(publicApi, true);
      await expectPdfs(publicApi, english(admin.descs, 'title'));
      await expectPublicSite(
        page,
        english(admin.descs, 'title'),
        english(admin.parts?.[0]?.descs, 'details'),
      );
    });
  });

  test('set-up for the cancellation: the run’s list also takes the cancellation warning', async ({
    sysadminPage: page,
  }) => {
    test.skip(!shortId, 'no warning was published (an earlier test skipped or failed)');
    // The cancellation warning's title names the warning it cancels, not the run's marker.
    const api = new AdminApi(page, DOMAIN);
    const path = `/rest/mailing-lists/mailing-list/${MAILING_LIST}`;
    const list = await api.get<{ triggers?: Record<string, unknown>[] }>(path);
    await api.put(path, {
      ...list,
      triggers: (list.triggers ?? []).map((t) => ({ ...t, messageFilter: listFilter(shortId) })),
    });
    const stored = await api.get<{ active: boolean; triggers?: { messageFilter?: string }[] }>(
      path,
    );
    expect(stored.active).toBe(true);
    expect(stored.triggers?.map((t) => t.messageFilter)).toEqual([
      listFilter(shortId),
      listFilter(shortId),
    ]);
  });

  test('the warning is cancelled, and the cancellation warning and its mail follow', async ({
    editorPage: page,
    request,
  }) => {
    test.skip(!shortId, 'no warning was published (an earlier test skipped or failed)');
    const api = new AdminApi(page, DOMAIN);
    const publicApi = new PublicApi(request);
    let cancellation: MessageVo | undefined;

    await page.goto(`/editor/status/${messageId}`);
    await page.getByRole('button', { name: /^cancel message/i }).click();
    const dialog = page.getByRole('dialog', { name: /cancel message/i });
    await expect(dialog).toBeVisible();
    // Both footer buttons read "Cancel"; the confirming one is the last.
    await dialog.getByRole('contentinfo').getByRole('button').last().click();
    await expect(page.getByText(/status:\s*cancelled/i)).toBeVisible();

    await test.step('both APIs hold it cancelled; the public list leaves it out', async () => {
      const admin = await expectPublicMatchesAdmin(api, publicApi, 'CANCELLED');
      expect(admin.publishDateTo, 'a cancelled warning has an end').toBeTruthy();
      await expectInPublicList(publicApi, false);
    });

    await test.step('the backend published one cancellation warning that refers to it', async () => {
      const result = await api.get<{ data: MessageVo[] }>('/rest/messages/search', {
        lang: 'en',
        messageId,
        referenceLevels: 1,
      });
      const cancellations = result.data.filter((message) =>
        message.references?.some((reference) => reference.type === 'CANCELLATION'),
      );
      expect(cancellations, 'one cancellation warning').toHaveLength(1);
      cancellation = cancellations[0];
      expect(cancellation.status).toBe('PUBLISHED');
      expect(cancellation.messageSeries?.seriesId).toBe(seriesId);
      expect(cancellation.shortId).toMatch(/^NW-\d{3,}-\d{2}$/);
      expectShows('cancellation title', english(cancellation.descs, 'title'), shortId);
      const published = await publicApi.get<MessageVo>(
        `/rest/public/v1/message/${cancellation.shortId}`,
        { lang: 'en' },
      );
      expect(published.status).toBe('PUBLISHED');
      expectSame(
        'public cancellation title',
        english(published.descs, 'title'),
        english(cancellation.descs, 'title'),
      );
      await knownFault(
        'MSG6',
        "a cancellation warning's reference names the cancelled warning by its uid",
        () =>
          expect(
            published.references?.map((reference) => reference.messageId),
            `the reference names ${shortId}`,
          ).toEqual([shortId]),
      );
    });

    // What a duty officer and a mariner see of the cancellation warning: its own Status page, its
    // PDF and its page on the public site, each naming the warning it cancels.
    await test.step('the Status page, the PDF and the public site show the cancellation warning', async () => {
      const id = cancellation?.shortId ?? '';
      const title = english(cancellation?.descs, 'title');
      await page.goto(`/editor/status/${cancellation?.id}`);
      await expect(page.getByText(/status:\s*published/i)).toBeVisible();
      await expect(preview(page)).toContainText(id);
      expectShows('cancellation status page title', await preview(page).innerText(), title);

      const response = await publicApi.fetch(`/rest/message-reports/message/${id}.pdf`, {
        lang: 'en',
      });
      expect(response.ok(), `the cancellation warning's PDF: ${response.status()}`).toBe(true);
      const text = normalise(await pdfText(await response.body()));
      expectShows('cancellation PDF short id', text, id);
      expectShows('cancellation PDF title', text, title);

      if (!scenarioEnv.infoUrl) {
        leftOut('E2E_INFO_URL is not set: the public site is not read');
        return;
      }
      await page.goto(`${scenarioEnv.infoUrl}/messages/details/message/${id}`);
      const dialog = page.getByRole('dialog');
      await expect(dialog).toContainText(id);
      expectShows('cancellation public site title', await dialog.innerText(), title);
    });

    await test.step('the mails: one cancel mail, one of the cancellation warning, no second publish mail', async () => {
      if (!scenarioEnv.mailhogUrl) {
        leftOut('E2E_MAILHOG_URL is not set: the mails are not read');
        return;
      }
      const cancelMails = await waitForMails(request, CONTACT, isCancelMail, 1);
      expect(cancelMails, 'one cancel mail, not two').toHaveLength(1);
      expectShows('cancel mail', cancelMails[0].text, shortId);

      // The cancellation warning's own mail says what it cancels, not only the area.
      const id = cancellation?.shortId ?? '';
      const warningMails = await waitForMails(
        request,
        CONTACT,
        (mail) => mail.subject === `${PUBLISH_SUBJECT} ${id}`,
        1,
      );
      expect(warningMails, 'one mail of the cancellation warning, not two').toHaveLength(1);
      expectShows('cancellation warning mail', warningMails[0].text, id);
      expectShows(
        'cancellation warning mail',
        warningMails[0].text,
        english(cancellation?.descs, 'title'),
      );
      expect(await publishMails(request), 'the publish mail was not sent again').toHaveLength(1);
    });
  });
});
