import type { Locator, Page } from '@playwright/test';

import { expect, test } from '../auth/fixtures';
import { AdminApi, asTestUser, english, PublicApi, type MessageVo } from './support/api';
import { expectSame, expectSameList, expectShows, normalise } from './support/compare';
import { annotate, describeScenario, scenarioEnv } from './support/env';
import { keepOffline } from './support/offline';
import { pdfText } from './support/pdf-text';
import { pickDay, pickTime } from './support/pickers';
import { restartBackend } from './support/restart';
import { newRunMarker, RUN_MARKER } from './support/run-id';
import { addDays, instantAt, wallClockIn, type WallClock } from './support/zoned-time';

/*
 * Scenario 4: a weekly publication, from a new series to an amended issue, as a duty officer
 * takes it. The admin makes a weekly series on the screen, checks its rules, creates and activates
 * it, opens its first issue, previews and publishes it. The issue is then read in the admin API,
 * the public API, the released PDF and on the public site. An editor publishes a notice dated
 * inside the released week; the issue says so, and the admin amends it: the same public link then
 * serves a document that holds both notices, and the one it replaced is archived. The backend is
 * restarted and everything read again, and the series is retired.
 *
 * The set-up makes what the sample lacks: a notice to mariners of the run, published now through
 * the API as the editor. The series and the notices carry the run's marker. The series is retired
 * at the end; its published issue stays public, by design.
 *
 * The report: the officer picks Standard, the first report the base product has, and the issue is
 * previewed and released with it; its front page captions the document with the issue's name.
 *
 * The period: the series' first interval starts two days before today in the domain's zone, and
 * the first issue closes one week later, so the planned cut-off is never past and the issue is
 * published with the cut-off "now". The back-dated notice is dated a day before now,
 * inside the released period.
 */

const DOMAIN = 'niord-nm';
const RUN = newRunMarker();
const SERIES_NAME = `E2E weekly ${RUN}`;
const FILE_PATTERN = `e2e-weekly-${RUN.toLowerCase()}-\${year}-w\${week-2-digits}.pdf`;
const NAME_PATTERN = `${SERIES_NAME}, week \${week} - \${year}`;
const NOTICE_TITLE = `${RUN} weekly notice`;
const LATE_TITLE = `${RUN} back-dated notice`;
const AMEND_REASON = `${RUN}: a notice was published into this week after its release`;
const RETIRE_REASON = `${RUN}: the scenario's series is no longer needed`;
const HOUR = 3_600_000;

interface DomainVo {
  readonly domainId: string;
  readonly timeZone?: string;
  readonly messageSeries?: readonly { readonly seriesId: string; readonly mainType: string }[];
}

interface CategoryVo {
  readonly categoryId: string;
  readonly publish: boolean;
}

interface SeriesVo {
  readonly seriesId: string;
  readonly status: string;
  readonly version?: number;
  readonly cadence: string;
  readonly nominalCutoffDay?: string;
  readonly numberingScheme: string;
  readonly categoryId?: string;
  readonly category?: { readonly categoryId: string };
  readonly reportId?: string;
  readonly timeRelation?: string;
  readonly firstIssueStartsAt?: string | number;
  readonly criteria?: unknown;
  readonly descs?: readonly {
    readonly lang: string;
    readonly name?: string;
    readonly fileNamePattern?: string;
    readonly nameSuggestionPattern?: string;
  }[];
}

interface IssueDescVo {
  readonly lang: string;
  readonly name?: string;
  readonly fileName?: string;
  readonly link?: string;
}

interface IssueVo {
  readonly publicId: string;
  readonly status: string;
  readonly intervalFrom?: string | number;
  readonly intervalTo?: string | number;
  readonly intervalFromSource?: string;
  readonly intervalToSource?: string;
  readonly cutoffStampedAt?: string | number;
  readonly publishedAt?: string | number;
  readonly publicFrom?: string | number;
  readonly memberCount?: number;
  readonly descs?: readonly IssueDescVo[];
}

interface MemberVo {
  readonly messageUid: string;
  readonly frozenShortId?: string;
  readonly title?: string;
  readonly sortIndex?: number;
  readonly reasonCode?: string;
}

interface AuditVo {
  readonly id: number;
  readonly action: string;
  readonly reason?: string;
  readonly archive?: Record<string, unknown> | null;
}

interface WorkbenchVo {
  readonly issue: IssueVo;
  readonly members: readonly MemberVo[];
  readonly audit?: readonly AuditVo[];
  readonly checklist?: { readonly canPublish: boolean };
  readonly publishedSinceRelease?: readonly { readonly messageUid: string }[];
}

interface PublicationVo {
  readonly publicationId: string;
  readonly descs?: readonly {
    readonly lang: string;
    readonly title?: string;
    readonly link?: string;
  }[];
}

/** What the run's issue looks like from everywhere it is read, for the compare across a restart. */
interface Readback {
  readonly status: string;
  readonly members: string[];
  readonly audit: string[];
  readonly cutoff: string | number | undefined;
  readonly publicFrom: string | number | undefined;
  readonly publicTitle: string;
  readonly link: string;
  readonly fileHash: string;
  readonly seriesStatus: string;
}

// What the set-up finds and the steps hand on to the next.
let zone = '';
let nmSeriesId = '';
let categoryId = '';
let firstStart: WallClock | undefined;
let plannedCutoff = 0;
let seriesId = '';
let seriesRetired = false;
let notice: MessageVo | undefined;
let lateNotice: MessageVo | undefined;
let issueId = '';
let issueName = '';
let releasedBytes: Uint8Array | undefined;
let publicLink = '';
let beforeRestart: Readback | undefined;

/* ---------------------------------------------------------------------------------------------- */
/* Helpers                                                                                         */
/* ---------------------------------------------------------------------------------------------- */

/** Records in the report that a read was left out, and why. */
function leftOut(description: string): void {
  annotate('skip', description);
}

const epoch = (value: string | number | undefined): number =>
  value === undefined ? Number.NaN : new Date(value).getTime();

/** A short fingerprint of a file's bytes, to tell two versions apart in a report. */
function fingerprint(bytes: Uint8Array): string {
  let hash = 0x811c9dc5;
  for (const byte of bytes) hash = Math.imul(hash ^ byte, 0x01000193) >>> 0;
  return `${bytes.length}:${hash.toString(16)}`;
}

/** The weekday a wall clock falls on, in the series editor's vocabulary ("FRIDAY"). */
function weekdayOf(clock: WallClock): string {
  return new Intl.DateTimeFormat('en-US', { weekday: 'long', timeZone: 'UTC' })
    .format(new Date(Date.UTC(clock.year, clock.month - 1, clock.day)))
    .toUpperCase();
}

/** A notice to mariners of the run, created and published through the API as the editor. */
async function publishNotice(
  api: AdminApi,
  title: string,
  publishDateFrom?: number,
): Promise<MessageVo> {
  const template = await api.get<Record<string, unknown>>('/rest/messages/new-message-template', {
    mainType: 'NM',
    populate: true,
  });
  const created = await api.post<MessageVo>('/rest/messages/message', {
    ...template,
    messageSeries: { seriesId: nmSeriesId },
    type: 'TEMPORARY_NOTICE',
    autoTitle: false,
    descs: [{ lang: 'en', title, subject: title }],
    parts: [
      {
        indexNo: 0,
        type: 'DETAILS',
        descs: [{ lang: 'en', subject: title, details: `<p>${title}: a buoy is off station.</p>` }],
      },
    ],
    ...(publishDateFrom === undefined ? {} : { publishDateFrom }),
  });
  await api.putWord(`/rest/messages/message/${created.id}/status`, 'VERIFIED');
  await api.putWord(`/rest/messages/message/${created.id}/status`, 'PUBLISHED');
  const published = await api.get<MessageVo>(`/rest/messages/message/${created.id}`, {
    lang: 'en',
  });
  expect(published.status, `${title} is published`).toBe('PUBLISHED');
  expect(published.shortId, `${title} has a short id`).toMatch(/^\S+-\d+-\d{2}$/);
  expectSame(`${title}: title`, english(published.descs, 'title'), title);
  return published;
}

function workbench(api: AdminApi): Promise<WorkbenchVo> {
  return api.get<WorkbenchVo>(`/rest/publication-issues/issue/${issueId}/workbench`, {
    lang: 'en',
  });
}

/** The members of the issue as the API lists them: short ids, in the API's order. */
function memberIds(members: readonly MemberVo[]): string[] {
  return [...members]
    .sort((a, b) => (a.sortIndex ?? 0) - (b.sortIndex ?? 0))
    .map((member) => member.frozenShortId ?? member.title ?? member.messageUid);
}

/** The issue page, loaded, for the run's issue. */
async function openIssue(page: Page): Promise<Locator> {
  await page.goto(`/admin/publications/${seriesId}?issue=${issueId}`);
  const detail = page.locator('app-issue-detail');
  await expect(detail.locator(':scope > header h2')).toBeVisible({ timeout: 30_000 });
  await expect(page.locator('app-progress-bar [role="progressbar"]')).toHaveCount(0, {
    timeout: 30_000,
  });
  return detail;
}

/** The short ids (or titles) the issue page's member table shows, in its order. */
async function shownMembers(detail: Locator): Promise<string[]> {
  const refs = detail
    .getByTestId('issue-members')
    .locator('table:not([data-testid="issue-late-table"]) tbody tr app-message-ref');
  const texts = await refs.allInnerTexts();
  // The (P)/(T) suffix the lists print stands beside the id inside the reference.
  return texts.map((text) => normalise(text).replace(/\s*\([A-Z]\)$/, ''));
}

/** The issue page shows the members the workbench API holds, in its order. */
async function expectMembersMatchApi(
  detail: Locator,
  api: AdminApi,
  label: string,
): Promise<string[]> {
  const held = memberIds((await workbench(api)).members);
  await expect
    .poll(async () => (await shownMembers(detail)).join(' | '), {
      message: `${label}: the member table shows what the API holds`,
      timeout: 20_000,
    })
    .toBe(held.join(' | '));
  expectSameList(`${label}: members`, await shownMembers(detail), held);
  return held;
}

/** The public API's publication of the run's issue, with its link as a path on this backend. */
async function publicPublication(publicApi: PublicApi): Promise<PublicationVo> {
  return publicApi.get<PublicationVo>(`/rest/public/v1/publication/${issueId}`, {
    lang: 'en',
    externalize: false,
  });
}

async function publicFile(publicApi: PublicApi, link: string): Promise<Uint8Array> {
  const response = await publicApi.fetch(link);
  expect(response.ok(), `the public link ${link} answers ${response.status()}`).toBe(true);
  expect(response.headers()['content-type']).toContain('pdf');
  return new Uint8Array(await response.body());
}

/** The public site's publications page lists the issue, and its Download link is the API's. */
async function expectPublicSite(page: Page, name: string, link: string): Promise<void> {
  if (!scenarioEnv.infoUrl) {
    leftOut('E2E_INFO_URL is not set: the public site is not read');
    return;
  }
  await page.goto(`${scenarioEnv.infoUrl}/publications`);
  const row = page.getByRole('row').filter({ has: page.getByRole('rowheader', { name }) });
  await expect(row).toHaveCount(1, { timeout: 30_000 });
  const download = row.getByRole('link').first();
  const href = (await download.getAttribute('href')) ?? '';
  expect(href.endsWith(link), `the public site's Download link ${href} serves ${link}`).toBe(true);
}

/* ---------------------------------------------------------------------------------------------- */
/* The scenario                                                                                    */
/* ---------------------------------------------------------------------------------------------- */

describeScenario('a weekly publication from its series to an amended issue', () => {
  test.beforeEach(async ({ page, baseURL }) => {
    await keepOffline(page, baseURL);
    // The duty officer works in the NM domain of the sample, which owns its publications.
    await page.addInitScript((domain) => {
      localStorage.setItem('niord.sessionDomainId', domain);
    }, DOMAIN);
  });

  test.afterAll(async ({ playwright }, testInfo) => {
    if (!seriesId || seriesRetired) return;
    // The run's series is retired, so it opens no issue for a later run. Its issues stay. A run
    // that crashes before this point leaves it active; the next run's set-up retires it.
    const baseURL = String(testInfo.project.use.baseURL ?? '');
    await asTestUser(playwright, baseURL, 'admin', DOMAIN, async (request) => {
      const response = await request.put(`/rest/publication-series/series/${seriesId}/status`, {
        params: { reason: RETIRE_REASON },
        // Sent as the JSON string "RETIRED", as the app sends a series' status.
        data: 'RETIRED',
        headers: { 'Content-Type': 'application/json' },
      });
      expect(response.ok(), `the series ${seriesId} retired`).toBe(true);
    });
  });

  test('set-up: a series an earlier run left active is retired', async ({ adminPage: page }) => {
    // A run that crashed skipped its end, so its series would open issues for good.
    const api = new AdminApi(page, DOMAIN);
    const active = await api.get<SeriesVo[]>('/rest/publication-series/search-details', {
      status: 'ACTIVE',
      domain: DOMAIN,
    });
    const earlier = new RegExp(`^e2e-weekly-${RUN_MARKER.source.toLowerCase()}$`);
    for (const series of active.filter((s) => earlier.test(s.seriesId))) {
      await api.putString(`/rest/publication-series/series/${series.seriesId}/status`, 'RETIRED', {
        reason: `${RUN}: an earlier run's series was left active`,
      });
      annotate('tidy', `an earlier run's series ${series.seriesId} retired`);
    }
  });

  test('set-up: a notice of the run is published now', async ({ editorPage: page }) => {
    const api = new AdminApi(page, DOMAIN);
    const domains = await api.get<DomainVo[]>('/rest/domains/all', { lang: 'en' });
    const domain = domains.find((d) => d.domainId === DOMAIN);
    zone = domain?.timeZone ?? '';
    expect(zone, `the sample's ${DOMAIN} has a time zone`).not.toBe('');
    const series = domain?.messageSeries?.filter((s) => s.mainType === 'NM') ?? [];
    expect(series, `the sample's ${DOMAIN} has one NM series`).toHaveLength(1);
    nmSeriesId = series[0].seriesId;

    const categories = await api.get<CategoryVo[]>('/rest/publication-categories/all', {
      lang: 'en',
    });
    categoryId = categories.find((category) => category.publish)?.categoryId ?? '';
    expect(categoryId, 'the sample has a public publication category').not.toBe('');

    const now = wallClockIn(Date.now(), zone);
    firstStart = { ...addDays(now, -2), hour: 0, minute: 0 };
    // The first issue closes one cadence period after the first start, in the domain's zone.
    plannedCutoff = instantAt(addDays(firstStart, 7), zone).getTime();

    notice = await publishNotice(api, NOTICE_TITLE);
  });

  test('the admin makes the weekly series, checks its rules and activates it', async ({
    adminPage: page,
  }) => {
    test.skip(!notice, 'no notice was published (the set-up skipped or failed)');
    const api = new AdminApi(page, DOMAIN);
    const start = firstStart as WallClock;
    const cutoffDay = weekdayOf(wallClockIn(plannedCutoff, zone));

    await page.goto('/admin/publications');
    await expect(page.locator('app-publications-page')).toBeVisible();
    await page.getByRole('button', { name: /create a publication/i }).click();
    await page.getByRole('menuitem', { name: /^new series$/i }).click();
    const drawer = page.getByRole('dialog', { name: /new series/i });
    await expect(drawer).toBeVisible();

    await test.step('Basics: the name and a public category', async () => {
      await drawer.getByRole('textbox', { name: /^name\b/i }).fill(SERIES_NAME);
      await drawer.getByLabel('Category', { exact: true }).selectOption(categoryId);
      // A series made from the criteria, the default.
      await expect(
        drawer.getByRole('button', { name: /^generated from criteria$/i }),
      ).toHaveAttribute('aria-pressed', 'true');
    });

    await test.step('Content: a report the base product has, and a file name ending in .pdf', async () => {
      await drawer.getByRole('tab', { name: /^content$/i }).click();
      await drawer.getByLabel('Report template').selectOption('standard');
      await drawer.getByRole('textbox', { name: /^file name pattern/i }).fill(FILE_PATTERN);
    });

    await test.step('Frequency: weekly, cut off two days ahead, the first interval two days back', async () => {
      await drawer.getByRole('tab', { name: /^frequency/i }).click();
      await drawer.getByLabel('Frequency', { exact: true }).selectOption('WEEKLY');
      await drawer.getByLabel('Cut-off weekday').selectOption(cutoffDay);
      await pickTime(page, drawer.getByRole('button', { name: /^nominal cut-off time$/i }), {
        ...start,
        hour: 12,
        minute: 0,
      });
      await drawer.getByLabel('Numbering').selectOption('ISO_WEEK_YEAR');
      await drawer.getByRole('textbox', { name: /^name suggestion pattern/i }).fill(NAME_PATTERN);
      const firstStartButtons = drawer.getByRole('button', { name: /^first interval starts$/i });
      await pickDay(page, firstStartButtons.first(), start);
      await pickTime(page, firstStartButtons.nth(1), start);
    });

    await test.step('Criteria: published in the interval, in the NM series', async () => {
      await drawer.getByRole('tab', { name: /^criteria$/i }).click();
      await expect(drawer.getByLabel('Time relation')).toHaveValue('PUBLISHED_IN_INTERVAL');
      await drawer.getByRole('button', { name: /^message series$/i }).click();
      await drawer.getByRole('checkbox', { name: nmSeriesId, exact: true }).check();
      // The probe counts what the criteria would select: the run's notice is among them.
      await expect(drawer.getByText(/\d+ message\(s\)/)).toBeVisible({ timeout: 20_000 });
    });

    await test.step('Check rules: every rule passes', async () => {
      await drawer.getByRole('button', { name: /^check rules$/i }).click();
      await expect(drawer.getByText(/every rule passes/i)).toBeVisible();
    });

    await test.step('Create as draft: the series page and the API hold the series', async () => {
      await drawer.getByRole('button', { name: /^create as draft$/i }).click();
      await expect(drawer).toBeHidden();
      await page.waitForURL(/\/admin\/publications\/[^/?#]+$/);
      seriesId = decodeURIComponent(
        /\/admin\/publications\/([^/?#]+)$/.exec(page.url())?.[1] ?? '',
      );
      expect(seriesId).not.toBe('');

      const stored = await api.get<SeriesVo>(
        `/rest/publication-series/editable-series/${seriesId}`,
      );
      expect(stored.status).toBe('DRAFT');
      expect(stored.cadence).toBe('WEEKLY');
      expect(stored.nominalCutoffDay).toBe(cutoffDay);
      expect(stored.numberingScheme).toBe('ISO_WEEK_YEAR');
      expect(stored.reportId).toBe('standard');
      expect(stored.categoryId ?? stored.category?.categoryId).toBe(categoryId);
      expect(stored.timeRelation).toBe('PUBLISHED_IN_INTERVAL');
      expect(
        epoch(stored.firstIssueStartsAt),
        'the first interval starts where it was picked',
      ).toBe(instantAt(start, zone).getTime());
      expect(JSON.stringify(stored.criteria)).toContain(nmSeriesId);
      const desc = stored.descs?.find((d) => d.lang === 'en');
      expect(desc?.name).toBe(SERIES_NAME);
      expect(desc?.fileNamePattern).toBe(FILE_PATTERN);
      expect(desc?.nameSuggestionPattern).toBe(NAME_PATTERN);

      const detail = page.locator('app-series-detail');
      expectSame('series name', await detail.locator('header h2').innerText(), SERIES_NAME);
      await expect(detail.locator('header .status-badge')).toHaveText(/^\s*draft\s*$/i);
    });

    await test.step('Activate: the series is active on the screen and in the API', async () => {
      await page.getByRole('button', { name: /^activate the series/i }).click();
      const confirm = page.getByRole('alertdialog', { name: /^activate series$/i });
      await confirm.getByRole('button', { name: /^yes$/i }).click();
      await expect(page.getByText(/^series activated$/i)).toBeVisible();
      await expect(page.getByRole('button', { name: /^activate the series/i })).toHaveCount(0);
      const stored = await api.get<SeriesVo>(
        `/rest/publication-series/editable-series/${seriesId}`,
      );
      expect(stored.status).toBe('ACTIVE');
      // No badge on a live series: "Active" is the ordinary case.
      await expect(page.locator('app-series-detail header .status-badge')).toHaveCount(0);
    });
  });

  test('the first issue is opened, previewed and published', async ({ adminPage: page }) => {
    test.skip(!seriesId, 'no series was made (an earlier test skipped or failed)');
    const api = new AdminApi(page, DOMAIN);
    const shortId = notice?.shortId ?? '';

    await test.step('New issue: the period runs from the first start to the planned cut-off', async () => {
      await page.goto(`/admin/publications/${seriesId}`);
      await page.getByRole('button', { name: /^new issue$/i }).click();
      const dialog = page.getByRole('dialog', { name: /^new issue$/i });
      await expect(dialog.getByText(/would be selected/i)).toBeVisible({ timeout: 20_000 });
      await expect(dialog.getByText(/\d+ message\(s\)/)).toBeVisible();
      await dialog.getByRole('button', { name: /^create issue$/i }).click();
      await expect(dialog).toBeHidden();
      // The series page shows the new issue as its current one; the officer opens it.
      await page.getByRole('button', { name: /^open the issue$/i }).click();
      await page.waitForURL(/[?&]issue=/);
      issueId = new URL(page.url()).searchParams.get('issue') ?? '';
      expect(issueId).not.toBe('');
    });

    const detail = await openIssue(page);

    await test.step('the issue page and the API hold the same open issue', async () => {
      const bench = await workbench(api);
      const issue = bench.issue;
      expect(issue.status).toBe('OPEN');
      expect(epoch(issue.intervalFrom)).toBe(instantAt(firstStart as WallClock, zone).getTime());
      expect(epoch(issue.intervalTo), 'the planned cut-off is the cadence close').toBe(
        plannedCutoff,
      );
      // Bounds the dialog proposed and nobody changed are recorded as nominal.
      expect(issue.intervalFromSource).toBe('NOMINAL');
      expect(issue.intervalToSource).toBe('NOMINAL');
      issueName = english(issue.descs, 'name');
      expectShows('issue name', issueName, SERIES_NAME);
      expectSame('issue name', await detail.locator(':scope > header h2').innerText(), issueName);
      await expect(detail.locator(':scope > header .status-badge').first()).toHaveText(
        /^\s*open\s*$/i,
      );
      await expect(detail.getByTestId('issue-facts')).toContainText('Nominal, from the cadence');

      const members = await expectMembersMatchApi(detail, api, 'open issue');
      expect(members, `the issue holds the run's notice ${shortId}`).toContain(shortId);
      expect(bench.checklist?.canPublish, 'the checklist lets the issue go out').toBe(true);
      await expect(detail.getByTestId('issue-verdict')).toBeVisible();
    });

    const generatePreview = async (): Promise<void> => {
      const previewed = page.waitForResponse(
        (r) => r.url().includes(`/issue/${issueId}/preview`) && r.request().method() === 'POST',
      );
      await detail.getByRole('button', { name: /^generate preview$/i }).click();
      const response = await previewed;
      expect(response.ok(), `the preview answers ${response.status()}`).toBe(true);
    };

    const previewText = async (): Promise<string> => {
      const response = await api.fetch(`/rest/publication-issues/issue/${issueId}/preview/en`);
      expect(response.ok(), `the preview answers ${response.status()}`).toBe(true);
      return normalise(await pdfText(new Uint8Array(await response.body())));
    };

    await test.step('a preview named for an earlier instant holds what stood then', async () => {
      // A second before the run's notice was published, the notice was not yet a member.
      const before = epoch(notice?.publishDateFrom) - 1000;
      await api.post(`/rest/publication-issues/issue/${issueId}/preview`, { at: before });
      expect(
        await previewText(),
        `a preview as of before ${shortId} was published leaves it out`,
      ).not.toContain(shortId);
    });

    await test.step('Generate preview: rendered at the screen’s instant, it holds the members shown', async () => {
      const shown = await shownMembers(detail);
      const sent = page.waitForRequest(
        (r) => r.url().includes(`/issue/${issueId}/preview`) && r.method() === 'POST',
      );
      await generatePreview();
      // The screen names the instant it shows, the planned cut-off or now, and the backend
      // renders at it. The planned cut-off lies ahead, so either is now.
      const at = Number(((await sent).postDataJSON() as { at?: unknown } | null)?.at);
      expect(
        at === plannedCutoff || Math.abs(at - Date.now()) < 5 * 60_000,
        `the preview is asked for the instant the screen shows, not ${at}`,
      ).toBe(true);
      await expect(detail.getByRole('button', { name: /^download preview$/i })).toBeVisible();
      const text = await previewText();
      expectShows('preview PDF', text, shortId);
      for (const member of shown)
        expectShows('preview PDF: a member the screen lists', text, member);
    });

    await test.step('Publish with the cut-off now: the dialog counts what the screen lists', async () => {
      const shown = await shownMembers(detail);
      await detail.getByRole('button', { name: /^publish…$/i }).click();
      const dialog = page.getByRole('dialog', { name: /^publish /i });
      await expect(dialog).toBeVisible();
      // The planned cut-off lies days ahead, so only "now" can be taken.
      await expect(dialog.locator('#publish-cutoff-planned')).toBeDisabled();
      await dialog.locator('#publish-cutoff-now').check();
      const summary = dialog.getByTestId('publish-summary');
      await expect(summary).toContainText(
        new RegExp(`goes out now with ${shown.length} messages?\\b`),
      );
      await expect(summary).toContainText(/the next issue opens/);
      await dialog.getByRole('button', { name: /^publish$/i }).click();
      await expect(dialog).toBeHidden({ timeout: 60_000 });
    });

    await test.step('the issue page, the admin API and the history agree on the release', async () => {
      const banner = detail.getByTestId('issue-published-banner');
      await expect(banner).toContainText('Published.');
      // The banner names no time; the header line names the publication time.
      expect(await banner.innerText()).not.toMatch(/\d{1,2}:\d{2}/);

      const bench = await workbench(api);
      const issue = bench.issue;
      expect(issue.status).toBe('PUBLISHED');
      expect(Math.abs(epoch(issue.cutoffStampedAt) - Date.now())).toBeLessThan(10 * 60_000);
      expect(epoch(issue.publicFrom)).toBe(epoch(issue.cutoffStampedAt));
      await expect(detail.locator(':scope > header .status-badge').first()).toHaveText(
        /published/i,
      );
      await expect(detail.locator(':scope > header')).toContainText(/published .+ by /i);
      // The name follows the week of the cut-off actually stamped, which may differ from the
      // planned one's; the screen shows the name the API holds.
      issueName = english(issue.descs, 'name');
      expectShows('issue name', issueName, SERIES_NAME);
      await expect(detail.locator(':scope > header h2')).toHaveText(issueName);
      // The pattern ends in .pdf, so nothing reads as tailored.
      await expect(detail.getByTestId('issue-tailored-mark')).toHaveCount(0);

      const members = await expectMembersMatchApi(detail, api, 'published issue');
      expect(members).toContain(shortId);
      expect(issue.memberCount).toBe(members.length);
      const actions = (bench.audit ?? []).map((entry) => entry.action);
      expect(actions).toEqual(
        expect.arrayContaining(['CREATED', 'PREVIEW_GENERATED', 'PUBLISHED']),
      );

      const fileName = issue.descs?.find((d) => d.lang === 'en')?.fileName ?? '';
      expect(fileName).toMatch(
        new RegExp(`^e2e-weekly-${RUN.toLowerCase()}-\\d{4}-w\\d{2}\\.pdf$`),
      );
      await expect(detail.getByTestId('issue-document-row')).toContainText(fileName);
    });
  });

  test('the public side serves the released issue', async ({ adminPage: page, request }) => {
    test.skip(!issueId, 'no issue was published (an earlier test skipped or failed)');
    const publicApi = new PublicApi(request);
    const shortId = notice?.shortId ?? '';

    const publication = await publicPublication(publicApi);
    expect(publication.publicationId).toBe(issueId);
    const desc = publication.descs?.find((d) => d.lang === 'en');
    expectSame('public title', desc?.title, issueName);
    publicLink = desc?.link ?? '';
    expect(publicLink).toMatch(/^\/rest\/repo\/file\/.+\.pdf$/);

    const list = await publicApi.get<PublicationVo[]>('/rest/public/v1/publications', {
      lang: 'en',
      externalize: false,
    });
    expect(
      list.map((p) => p.publicationId),
      'the public list holds the released issue',
    ).toContain(issueId);

    releasedBytes = await publicFile(publicApi, publicLink);
    const text = normalise(await pdfText(releasedBytes));
    expectShows('released PDF', text, shortId);
    expectShows('released PDF', text, NOTICE_TITLE);

    await expectPublicSite(page, issueName, publicLink);
  });

  test('a notice dated inside the released week is published, and the issue says so', async ({
    editorPage: page,
  }) => {
    test.skip(!releasedBytes, 'the issue was not read on the public side (an earlier test failed)');
    const api = new AdminApi(page, DOMAIN);
    const dated = Date.now() - 24 * HOUR;
    lateNotice = await publishNotice(api, LATE_TITLE, dated);
    // Published now, the notice keeps its date: it is filed in the released week.
    expect(
      Math.abs(epoch(lateNotice.publishDateFrom) - dated),
      'the notice keeps its date',
    ).toBeLessThan(1000);

    // An editor may not administer publications: the workbench refuses the editor.
    await expect(workbench(api)).rejects.toThrow(/answered 403/);
  });

  test('the admin amends the issue: the same link serves both notices, the old file is archived', async ({
    adminPage: page,
    request,
  }) => {
    test.skip(!lateNotice, 'no back-dated notice was published (an earlier test failed)');
    const api = new AdminApi(page, DOMAIN);
    const publicApi = new PublicApi(request);
    const shortIds = [notice?.shortId ?? '', lateNotice?.shortId ?? ''];
    const detail = await openIssue(page);

    await test.step('the released issue names the notice published into its period', async () => {
      const bench = await workbench(api);
      expect(
        bench.publishedSinceRelease?.map((m) => m.messageUid),
        'the workbench names the back-dated notice',
      ).toContain(lateNotice?.id);
      const missed = detail.getByTestId('issue-missed-banner');
      await expect(missed).toContainText(lateNotice?.shortId ?? '');
      expect(await shownMembers(detail)).not.toContain(lateNotice?.shortId);
    });

    await test.step('Amend with a reason', async () => {
      await detail
        .getByTestId('issue-missed-banner')
        .getByRole('button', { name: /^amend$/i })
        .click();
      const dialog = page.getByRole('dialog', { name: /^amend issue$/i });
      await dialog.getByLabel('Reason').fill(AMEND_REASON);
      await dialog.getByRole('button', { name: /^amend$/i }).click();
      await expect(dialog).toBeHidden({ timeout: 60_000 });
      await expect(detail.getByTestId('issue-missed-banner')).toHaveCount(0);
    });

    await test.step('the issue page and the API hold both notices; the trail holds the amend', async () => {
      const members = await expectMembersMatchApi(detail, api, 'amended issue');
      expect(members).toEqual(expect.arrayContaining(shortIds));
      const bench = await workbench(api);
      expect(bench.issue.status).toBe('PUBLISHED');
      expect(bench.issue.memberCount).toBe(members.length);
      const amended = (bench.audit ?? []).find((entry) => entry.action === 'AMENDED');
      expect(amended?.reason).toBe(AMEND_REASON);

      const archive = await api.fetch(
        `/rest/publication-issues/issue/${issueId}/archive/${amended?.id}/en`,
      );
      expect(archive.ok(), `the archived document answers ${archive.status()}`).toBe(true);
      expect(
        fingerprint(new Uint8Array(await archive.body())),
        'the archive holds the document that went out first',
      ).toBe(fingerprint(releasedBytes as Uint8Array));
    });

    await test.step('the same public link serves a new document holding both notices', async () => {
      const publication = await publicPublication(publicApi);
      expect(publication.descs?.find((d) => d.lang === 'en')?.link).toBe(publicLink);
      const bytes = await publicFile(publicApi, publicLink);
      expect(fingerprint(bytes), 'the document was replaced').not.toBe(
        fingerprint(releasedBytes as Uint8Array),
      );
      const text = normalise(await pdfText(bytes));
      for (const id of shortIds) expectShows('amended PDF', text, id);
      await expectPublicSite(page, issueName, publicLink);
    });
  });

  test('after a restart of the backend everything reads the same', async ({
    adminPage: page,
    request,
  }) => {
    test.skip(!lateNotice || !publicLink, 'the issue was not amended (an earlier test failed)');
    const api = new AdminApi(page, DOMAIN);
    const publicApi = new PublicApi(request);

    const readBack = async (): Promise<Readback> => {
      const bench = await workbench(api);
      const publication = await publicPublication(publicApi);
      const desc = publication.descs?.find((d) => d.lang === 'en');
      const series = await api.get<SeriesVo>(
        `/rest/publication-series/editable-series/${seriesId}`,
      );
      return {
        status: bench.issue.status,
        members: memberIds(bench.members),
        audit: (bench.audit ?? []).map((entry) => `${entry.id} ${entry.action}`),
        cutoff: bench.issue.cutoffStampedAt,
        publicFrom: bench.issue.publicFrom,
        publicTitle: desc?.title ?? '',
        link: desc?.link ?? '',
        fileHash: fingerprint(await publicFile(publicApi, desc?.link ?? publicLink)),
        seriesStatus: series.status,
      };
    };

    beforeRestart = await readBack();
    await restartBackend(request);

    await test.step('the admin API, the public API and the file are unchanged', async () => {
      expect(await readBack()).toEqual(beforeRestart);
    });

    await test.step('the issue page and the public site show the same after a reload', async () => {
      const detail = await openIssue(page);
      expectSameList(
        'members after the restart',
        await shownMembers(detail),
        beforeRestart?.members ?? [],
      );
      expectSame('issue name', await detail.locator(':scope > header h2').innerText(), issueName);
      await expect(detail.getByTestId('issue-missed-banner')).toHaveCount(0);
      await expectPublicSite(page, issueName, publicLink);
    });
  });

  test('the series is retired; its released issue stays public', async ({
    adminPage: page,
    request,
  }) => {
    test.skip(!seriesId, 'no series was made (an earlier test skipped or failed)');
    const api = new AdminApi(page, DOMAIN);
    const publicApi = new PublicApi(request);

    await page.goto(`/admin/publications/${seriesId}`);
    await page.getByRole('button', { name: /^publication actions$/i }).click();
    await page.getByRole('menuitem', { name: /^retire series$/i }).click();
    const dialog = page.getByRole('dialog', { name: /^retire series$/i });
    await dialog.getByLabel('Reason').fill(RETIRE_REASON);
    await dialog.getByRole('button', { name: /^retire series$/i }).click();
    await expect(dialog).toBeHidden();

    const stored = await api.get<SeriesVo>(`/rest/publication-series/editable-series/${seriesId}`);
    expect(stored.status).toBe('RETIRED');
    seriesRetired = true;
    await expect(page.locator('app-series-detail header .status-badge')).toHaveText(/retired/i);

    if (issueId && publicLink) {
      const list = await publicApi.get<PublicationVo[]>('/rest/public/v1/publications', {
        lang: 'en',
        externalize: false,
      });
      expect(list.map((p) => p.publicationId)).toContain(issueId);
      expect((await publicApi.fetch(publicLink)).ok()).toBe(true);
    }
  });
});
