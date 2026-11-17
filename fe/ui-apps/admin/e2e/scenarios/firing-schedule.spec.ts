import type { Locator, Page } from '@playwright/test';

import { expect, test } from '../auth/fixtures';
import {
  AdminApi,
  asTestUser,
  english,
  PublicApi,
  type Desc,
  type MessagePartVo,
  type MessageVo,
} from './support/api';
import { expectSame, expectSameList, expectShows, normalise } from './support/compare';
import { annotate, describeScenario, scenarioEnv } from './support/env';
import { keepOffline } from './support/offline';
import { pdfText } from './support/pdf-text';
import { pickTime } from './support/pickers';
import { restartBackend } from './support/restart';
import {
  addDays,
  instantAt,
  monthName,
  shownDay,
  shownTime,
  wallClockIn,
  type WallClock,
} from './support/zoned-time';

/*
 * Scenario 3: the firing schedule to its exercises, as a duty officer takes it. The editor enters
 * an area's firing periods for two days on the Schedule page, updates the firing exercises, and
 * reads the warning the backend makes from them in the admin API, the public API, on the Status
 * page, in its PDF and on the public site. Then a period's start is changed and the warning
 * follows; the backend is restarted and everything read again; a period is removed and the
 * warning follows again. Two checks of faults the error hunt found close it: a period across
 * midnight survives a save of its next day, and a firing that ended earlier today makes no warning.
 *
 * The set-up makes what the sample lacks: the firing schedule of the Firing Areas domain, made once
 * and kept (a domain has one schedule; it cannot be named per run). The set-up switches it on and
 * the end switches it off, so the hourly firing run does not act on the sample between runs; a run
 * that crashes leaves it on until the next run ends. Firing periods have no name, so
 * the run uses days two to five ahead of the browser's today and the area of a published firing
 * area message of the sample, and clears that area's days before it starts and when it ends. The
 * warnings stay as the run's record: the last one is cancelled by the clearing at the end.
 */

const FIRING_DOMAIN = 'niord-fa';
const PREFERRED_TARGET_DOMAIN = 'niord-nw';
const SESSION_DOMAIN_KEY = 'niord.sessionDomainId';
const SEARCH_PATH = '/rest/firing-schedules/search-firing-area-periods';
const PERIODS_PATH = '/rest/firing-schedules/firing-area-periods';
const UPDATE_PATH = '/rest/firing-schedules/update-firing-exercises';
/** The days around the browser's today that the run may write, and clears. */
const RUN_DAYS = [-1, 0, 1, 2, 3, 4, 5, 6];

interface DomainVo {
  readonly domainId: string;
  readonly timeZone?: string;
  readonly messageSeries?: readonly {
    readonly seriesId: string;
    readonly mainType: string;
    readonly shortFormat?: string;
  }[];
}

interface FiringScheduleVo {
  readonly id?: number;
  readonly domain: { readonly domainId: string };
  readonly targetDomain: { readonly domainId: string };
  readonly targetSeriesId: string;
  readonly scheduleDays?: number | null;
  readonly active: boolean;
}

interface FiringPeriodVo {
  readonly id?: number;
  readonly legacyId?: string;
  readonly fromDate: string | number;
  readonly toDate: string | number;
}

interface FiringAreaPeriodsVo {
  readonly area: { readonly id: number; readonly descs?: readonly Desc[] };
  readonly firingPeriods: readonly FiringPeriodVo[];
}

interface DateIntervalVo {
  readonly fromDate?: string | number;
  readonly toDate?: string | number;
}

interface ExercisePartVo extends MessagePartVo {
  readonly eventDates?: readonly DateIntervalVo[];
}

interface ExerciseVo extends MessageVo {
  readonly parts?: readonly ExercisePartVo[];
}

/** A firing period as instants, in epoch milliseconds. */
interface Interval {
  readonly from: number;
  readonly to: number;
}

interface FiringArea {
  readonly id: number;
  readonly name: string;
}

// What the set-up finds and the steps hand on to the next.
let scheduleId: number | undefined;
let zone = '';
let targetZone = '';
let targetDomain = '';
let targetSeries = '';
let targetMainType = '';
let area: FiringArea | undefined;
let pastArea: FiringArea | undefined;
let today: WallClock | undefined;
let exerciseId = '';
const replacedIds: string[] = [];
let day2Ids: number[] = [];

/* ---------------------------------------------------------------------------------------------- */
/* Days and periods                                                                                */
/* ---------------------------------------------------------------------------------------------- */

/** The browser's zone: the Schedule page's day is a day of the browser's calendar. */
function browserZone(): string {
  return test.info().project.use.timezoneId ?? Intl.DateTimeFormat().resolvedOptions().timeZone;
}

/** The day `offset` days after the browser's today, at midnight. */
function day(offset: number): WallClock {
  if (!today) throw new Error('the set-up did not run');
  return addDays(today, offset);
}

function at(base: WallClock, hour: number, minute = 0): WallClock {
  return { ...base, hour, minute };
}

function sameDay(a: WallClock, b: WallClock): boolean {
  return a.year === b.year && a.month === b.month && a.day === b.day;
}

const pad = (n: number): string => String(n).padStart(2, '0');

/** A wall clock as the scenario compares it: `2026-11-17 09:00`. */
function clockText(clock: WallClock): string {
  return `${clock.year}-${pad(clock.month)}-${pad(clock.day)} ${pad(clock.hour)}:${pad(clock.minute)}`;
}

function ms(value: string | number | undefined): number {
  return value === undefined ? Number.NaN : new Date(value).getTime();
}

function intervalOf(period: FiringPeriodVo | DateIntervalVo): Interval {
  return { from: ms(period.fromDate), to: ms(period.toDate) };
}

/** A period as the firing domain's clock reads it: `2026-11-17 09:00 - 2026-11-17 11:00`. */
function periodText(period: Interval): string {
  return `${clockText(wallClockIn(period.from, zone))} - ${clockText(wallClockIn(period.to, zone))}`;
}

/** A period as instants, for a readable failure. */
function instantText(period: Interval): string {
  return `${new Date(period.from).toISOString()} - ${new Date(period.to).toISOString()}`;
}

/** The noon of a day in the firing domain's zone: the date a period call names its day by. */
function noonOf(clock: WallClock): number {
  return instantAt(at(clock, 12), zone).getTime();
}

/* ---------------------------------------------------------------------------------------------- */
/* The periods API                                                                                 */
/* ---------------------------------------------------------------------------------------------- */

async function periodsOn(api: AdminApi, areaId: number, on: WallClock): Promise<FiringPeriodVo[]> {
  const result = await api.get<FiringAreaPeriodsVo[]>(SEARCH_PATH, {
    date: noonOf(on),
    lang: 'en',
    area: areaId,
  });
  return [...(result.find((row) => row.area.id === areaId)?.firingPeriods ?? [])];
}

/** Every period of the area on the run's days, once each (a period across midnight is listed twice). */
async function allPeriods(api: AdminApi, areaId: number): Promise<FiringPeriodVo[]> {
  const byId = new Map<number, FiringPeriodVo>();
  for (const offset of RUN_DAYS) {
    for (const period of await periodsOn(api, areaId, day(offset))) {
      if (period.id !== undefined) byId.set(period.id, period);
    }
  }
  return [...byId.values()].sort((a, b) => ms(a.fromDate) - ms(b.fromDate));
}

async function putPeriods(
  api: AdminApi,
  areaId: number,
  on: WallClock,
  periods: readonly FiringPeriodVo[],
): Promise<FiringPeriodVo[]> {
  const saved = await api.put<FiringAreaPeriodsVo>(
    PERIODS_PATH,
    { area: { id: areaId }, firingPeriods: periods },
    { date: noonOf(on), lang: 'en' },
  );
  return [...saved.firingPeriods];
}

/* ---------------------------------------------------------------------------------------------- */
/* The Schedule page                                                                               */
/* ---------------------------------------------------------------------------------------------- */

/** Makes `domain` the session domain of every page the test loads from now on. */
async function useDomain(page: Page, domain: string): Promise<void> {
  await page.addInitScript(({ key, id }) => localStorage.setItem(key, id), {
    key: SESSION_DOMAIN_KEY,
    id: domain,
  });
}

/**
 * Loads the Schedule page afresh and pages to `on` with the day arrows, then waits for that day's
 * search: the page names the day by the browser's noon of it. Rows read before that answer are the
 * previous day's.
 */
async function openSchedule(page: Page, on: WallClock): Promise<void> {
  const wanted = String(instantAt(at(on, 12), browserZone()).getTime());
  const loaded = page.waitForResponse(
    (response) =>
      response.url().includes(SEARCH_PATH) &&
      new URL(response.url()).searchParams.get('date') === wanted,
  );
  await page.goto('/schedule');
  await expect(page.getByRole('heading', { name: 'Firing Area Schedule' })).toBeVisible();
  const dateButton = page.getByRole('button', { name: 'Schedule Date' });
  const shown = shownDay(await dateButton.innerText());
  expect(shown, 'the Schedule page shows its day').toBeDefined();
  const offset = Math.round(
    (Date.UTC(on.year, on.month - 1, on.day) -
      Date.UTC(shown?.year ?? 0, (shown?.month ?? 1) - 1, shown?.day ?? 0)) /
      86_400_000,
  );
  const arrow = page.getByRole('button', { name: offset > 0 ? 'Next day' : 'Previous day' });
  for (let step = 0; step < Math.abs(offset); step++) {
    const before = await dateButton.innerText();
    await arrow.click();
    await expect(dateButton).not.toHaveText(before);
  }
  expect(shownDay(await dateButton.innerText())).toEqual({
    year: on.year,
    month: on.month,
    day: on.day,
  });
  expect((await loaded).ok(), "the day's search").toBe(true);
  // The answer has arrived, but the table shows the previous day's rows until the page has taken
  // it in: the progress bar goes when it has.
  await expect(page.getByRole('progressbar')).toHaveCount(0);
}

/** The table row of a firing area. */
function areaRow(page: Page, which: FiringArea): Locator {
  return page
    .locator('table.niord-table tbody tr')
    .filter({ has: page.locator('app-time-schedule-editor') })
    .filter({ has: page.getByRole('cell', { name: which.name, exact: true }) });
}

/**
 * The periods a row lists, as clock texts. A period within the day shows its times only; one
 * whose ends fall on different days shows the date on both.
 */
async function shownPeriods(row: Locator, on: WallClock): Promise<string[]> {
  const lines = (await row.locator('app-time-schedule-editor').innerText())
    .split('\n')
    .map(normalise)
    .filter((line) => line !== '');
  return lines.map((line) => {
    const ends = line.split(' - ');
    expect(ends, `a period line reads "<from> - <to>": "${line}"`).toHaveLength(2);
    return ends
      .map((end) => {
        const date = shownDay(end) ?? on;
        const time = shownTime(end);
        if (!time) throw new Error(`no time in "${end}" of the period line "${line}"`);
        return clockText({ ...date, ...time });
      })
      .join(' - ');
  });
}

/** The row lists what the periods API holds for the day, in its order. */
async function expectRowMatchesApi(
  page: Page,
  api: AdminApi,
  which: FiringArea,
  on: WallClock,
): Promise<FiringPeriodVo[]> {
  const held = await periodsOn(api, which.id, on);
  // Polled: the row takes in a new day's periods a moment after the table does.
  await expect
    .poll(() => shownPeriods(areaRow(page, which), on), {
      message: `${which.name} on ${clockText(on).slice(0, 10)}: the row lists what the API holds`,
    })
    .toEqual(held.map((period) => periodText(intervalOf(period))));
  return held;
}

async function startEditing(row: Locator): Promise<void> {
  await row.getByRole('button', { name: 'Edit firing periods' }).click();
  await expect(row.getByRole('button', { name: 'Save', exact: true })).toBeVisible();
}

/** Fills the period row `index` of an open editor (0 is the first row with time fields). */
async function enterPeriod(
  page: Page,
  row: Locator,
  index: number,
  from: WallClock,
  to: WallClock,
): Promise<void> {
  await pickTime(page, row.getByRole('button', { name: 'From', exact: true }).nth(index), from);
  await pickTime(page, row.getByRole('button', { name: 'To', exact: true }).nth(index), to);
}

/** Adds a row to an open editor and fills it. */
async function addPeriod(page: Page, row: Locator, from: WallClock, to: WallClock): Promise<void> {
  const fromButtons = row.getByRole('button', { name: 'From', exact: true });
  const before = await fromButtons.count();
  await row.getByRole('button', { name: 'Add', exact: true }).click();
  // The new row is not there at once: wait for it before touching it.
  await expect(fromButtons).toHaveCount(before + 1);
  await enterPeriod(page, row, before, from, to);
}

/** Saves an open editor and waits for the backend's answer and the row's view. */
async function savePeriods(page: Page, row: Locator): Promise<void> {
  const saved = page.waitForResponse(
    (response) => response.url().includes(PERIODS_PATH) && response.request().method() === 'PUT',
  );
  await row.getByRole('button', { name: 'Save', exact: true }).click();
  expect((await saved).status(), 'the periods saved').toBe(200);
  await expect(row.getByRole('button', { name: 'Edit firing periods' })).toBeVisible();
}

/** Presses Update Firing Exercises and accepts it. */
async function updateExercises(page: Page): Promise<void> {
  await page.getByRole('button', { name: /^update firing exercises/i }).click();
  const confirm = page.getByRole('alertdialog');
  await expect(confirm.getByRole('heading', { name: 'Update Firing Exercises' })).toBeVisible();
  const updated = page.waitForResponse(
    (response) => response.url().includes(UPDATE_PATH) && response.request().method() === 'PUT',
  );
  await confirm.locator('.confirm-foot').getByRole('button', { name: 'Yes', exact: true }).click();
  const response = await updated;
  expect(response.status(), 'the firing exercises updated').toBe(200);
  // The run answers what failed; a clean run is what the success toast below stands for.
  expect(await response.json(), 'nothing in the run failed').toEqual({
    failedSchedules: 0,
    failedAreas: 0,
    failedCancellations: 0,
  });
  await expect(page.getByText('Firing Exercises have been updated')).toBeVisible();
}

/* ---------------------------------------------------------------------------------------------- */
/* The warnings                                                                                    */
/* ---------------------------------------------------------------------------------------------- */

/** The published firing warnings of an area in the schedule's target series. */
async function publishedExercises(api: AdminApi, areaId: number): Promise<string[]> {
  const result = await api.get<{ data: MessageVo[] }>('/rest/messages/search', {
    lang: 'en',
    messageSeries: targetSeries,
    area: areaId,
    status: 'PUBLISHED',
    maxSize: 100,
  });
  return result.data
    .filter((message) => message.areas?.some((a) => a.id === areaId))
    .map((message) => message.id);
}

function timePartOf(message: ExerciseVo): ExercisePartVo | undefined {
  return message.parts?.find((part) => part.type === 'TIME');
}

function eventDatesOf(message: ExerciseVo): string[] {
  return (timePartOf(message)?.eventDates ?? []).map((date) => instantText(intervalOf(date)));
}

/**
 * The line the warning's time part gives a period, in the target domain's zone and the base
 * product's English format: "17 November 2026, hours 09:00 - 11:00", "... 18:00 - 24:00" for a
 * period until midnight, and both dates for one across midnight.
 */
function timeLine(period: Interval): string {
  const from = wallClockIn(period.from, targetZone);
  const to = wallClockIn(period.to, targetZone);
  const dated = (clock: WallClock): string =>
    `${clock.day} ${monthName(clock)} ${clock.year}, hours ${pad(clock.hour)}:${pad(clock.minute)}`;
  const untilMidnight = to.hour === 0 && to.minute === 0 && sameDay(addDays(from, 1), to);
  const end = untilMidnight
    ? '24:00'
    : sameDay(from, to)
      ? `${pad(to.hour)}:${pad(to.minute)}`
      : dated(to);
  return `${dated(from)} - ${end}`;
}

/**
 * The area has exactly one published firing warning, and it holds the periods: its time part's
 * dates and lines, its publication end, in the admin API, the public API and the public list.
 */
async function expectExercise(
  api: AdminApi,
  publicApi: PublicApi,
  periods: readonly Interval[],
): Promise<ExerciseVo> {
  if (!area) throw new Error('the set-up did not run');
  const ids = await publishedExercises(api, area.id);
  expect(ids, `one published firing warning for ${area.name}`).toHaveLength(1);
  const exercise = await api.get<ExerciseVo>(`/rest/messages/message/${ids[0]}`, { lang: 'en' });
  const sorted = [...periods].sort((a, b) => a.from - b.from || a.to - b.to);

  expect(exercise.status).toBe('PUBLISHED');
  expect(exercise.messageSeries?.seriesId).toBe(targetSeries);
  expect(exercise.type).toBe(targetMainType === 'NW' ? 'LOCAL_WARNING' : 'TEMPORARY_NOTICE');
  expect(exercise.areas?.map((a) => a.id)).toContain(area.id);
  expectSameList('the warning’s event dates', eventDatesOf(exercise), sorted.map(instantText));
  expect(
    new Date(ms(exercise.publishDateTo)).toISOString(),
    'the warning is published until its last period ends',
  ).toBe(new Date(Math.max(...sorted.map((period) => period.to))).toISOString());
  const time = english(timePartOf(exercise)?.descs, 'details');
  for (const period of sorted) expectShows('the warning’s time part', time, timeLine(period));
  expectShows('the warning’s title', english(exercise.descs, 'title'), area.name);

  const published = await publicApi.get<ExerciseVo>(`/rest/public/v1/message/${exercise.id}`, {
    lang: 'en',
  });
  expect(published.status, 'the public API').toBe('PUBLISHED');
  expectSame(
    'the public title',
    english(published.descs, 'title'),
    english(exercise.descs, 'title'),
  );
  expectSame('the public time part', english(timePartOf(published)?.descs, 'details'), time);
  expectSameList('the public event dates', eventDatesOf(published), eventDatesOf(exercise));
  expect(ms(published.publishDateTo)).toBe(ms(exercise.publishDateTo));
  await expectInPublicList(publicApi, exercise.id, true);
  return exercise;
}

async function expectInPublicList(
  publicApi: PublicApi,
  id: string,
  present: boolean,
): Promise<void> {
  const list = await publicApi.get<MessageVo[]>('/rest/public/v1/messages', {
    lang: 'en',
    domain: targetDomain,
  });
  expect(
    list.some((message) => message.id === id),
    `the public list of ${targetDomain} ${present ? 'holds' : 'leaves out'} the warning ${id}`,
  ).toBe(present);
}

/** A replaced warning is cancelled in both APIs and gone from the public list. */
async function expectCancelled(api: AdminApi, publicApi: PublicApi, id: string): Promise<void> {
  expect((await api.get<ExerciseVo>(`/rest/messages/message/${id}`)).status, `warning ${id}`).toBe(
    'CANCELLED',
  );
  const published = await publicApi.get<ExerciseVo>(`/rest/public/v1/message/${id}`, {
    lang: 'en',
  });
  expect(published.status, `warning ${id} in the public API`).toBe('CANCELLED');
  await expectInPublicList(publicApi, id, false);
}

/** Records in the report that a read was left out, and why. */
function leftOut(description: string): void {
  annotate('skip', description);
}

/**
 * The warning on the screens: the Status page of the admin app (in the target domain), its PDF
 * and the public site show its title and every time line.
 */
async function expectExerciseShown(
  page: Page,
  publicApi: PublicApi,
  exercise: ExerciseVo,
): Promise<void> {
  const title = english(exercise.descs, 'title');
  const lines = (timePartOf(exercise)?.eventDates ?? []).map((date) => timeLine(intervalOf(date)));

  await useDomain(page, targetDomain);
  await page.goto(`/editor/status/${exercise.id}`);
  await expect(page.getByText(/status:\s*published/i)).toBeVisible();
  const article = page.locator('main article').first();
  await expect(article).toContainText(lines[0] ?? title);
  const shown = await article.innerText();
  expectShows('the Status page title', shown, title);
  for (const line of lines) expectShows('the Status page time part', shown, line);

  const pdf = await publicApi.fetch(`/rest/message-reports/message/${exercise.id}.pdf`, {
    lang: 'en',
  });
  expect(pdf.ok(), `the warning's PDF: ${pdf.status()}`).toBe(true);
  expect(pdf.headers()['content-type']).toContain('pdf');
  const text = normalise(await pdfText(await pdf.body()));
  expectShows('the PDF title', text, title);
  for (const line of lines) expectShows('the PDF time part', text, line);

  if (!scenarioEnv.infoUrl) {
    leftOut('E2E_INFO_URL is not set: the public site is not read');
    return;
  }
  await page.goto(`${scenarioEnv.infoUrl}/messages/details/message/${exercise.id}`);
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText(lines[0] ?? title);
  const site = await dialog.innerText();
  expectShows('the public site title', site, title);
  for (const line of lines) expectShows('the public site time part', site, line);
}

/* ---------------------------------------------------------------------------------------------- */
/* Clearing the run's days                                                                         */
/* ---------------------------------------------------------------------------------------------- */

/** Clears the run's days of the run's areas and updates the exercises, so no warning of them stays. */
async function clearRunDays(api: AdminApi): Promise<void> {
  for (const which of [area, pastArea]) {
    if (!which) continue;
    for (const offset of RUN_DAYS) await putPeriods(api, which.id, day(offset), []);
  }
  await api.put(UPDATE_PATH, {});
}

/* ---------------------------------------------------------------------------------------------- */
/* The scenario                                                                                    */
/* ---------------------------------------------------------------------------------------------- */

describeScenario('the firing schedule to its exercises', () => {
  test.beforeEach(async ({ page, baseURL }) => {
    await keepOffline(page, baseURL);
  });

  test.afterAll(async ({ playwright }, testInfo) => {
    if (!area && scheduleId === undefined) return;
    const baseURL = String(testInfo.project.use.baseURL ?? '');
    await asTestUser(playwright, baseURL, 'sysadmin', FIRING_DOMAIN, async (request) => {
      // The run's days are cleared and the exercises updated, so the run's last warning is
      // cancelled and nothing of the run is announced by a later hourly run.
      for (const which of [area, pastArea]) {
        if (!which) continue;
        for (const offset of RUN_DAYS) {
          const response = await request.put(PERIODS_PATH, {
            params: { date: noonOf(day(offset)), lang: 'en' },
            data: { area: { id: which.id }, firingPeriods: [] },
          });
          expect(response.ok(), `${which.name} cleared on day ${offset}`).toBe(true);
        }
      }
      if (area) {
        expect((await request.put(UPDATE_PATH)).ok(), 'the exercises updated').toBe(true);
      }
      if (scheduleId === undefined) return;
      // The schedule is switched off, so the hourly firing run does not act on the sample
      // between runs. The next run's set-up switches it on again.
      const schedules = (await (
        await request.get('/rest/firing-schedules/all')
      ).json()) as FiringScheduleVo[];
      const schedule = schedules.find((s) => s.id === scheduleId);
      if (!schedule?.active) return;
      const response = await request.put(`/rest/firing-schedules/firing-schedule/${scheduleId}`, {
        data: { ...schedule, active: false },
      });
      expect(response.ok(), `the firing schedule of ${FIRING_DOMAIN} switched off`).toBe(true);
    });
  });

  test('set-up: the firing schedule exists and the run’s days are clear', async ({
    sysadminPage: page,
  }) => {
    const api = new AdminApi(page, FIRING_DOMAIN);
    const domains = await api.get<DomainVo[]>('/rest/domains/all', { lang: 'en' });
    const domainOf = (id: string): DomainVo | undefined => domains.find((d) => d.domainId === id);

    await test.step('the Firing Areas domain has an active schedule', async () => {
      const schedules = await api.get<FiringScheduleVo[]>('/rest/firing-schedules/all');
      let schedule = schedules.find((s) => s.domain.domainId === FIRING_DOMAIN);
      if (!schedule) {
        // Into the NW domain's series without short ids: the series of local warnings.
        const series = domainOf(PREFERRED_TARGET_DOMAIN)?.messageSeries?.find(
          (s) => s.mainType === 'NW' && !s.shortFormat,
        );
        expect(
          series,
          `the sample's ${PREFERRED_TARGET_DOMAIN} has an NW series without short ids`,
        ).toBeDefined();
        schedule = await api.post<FiringScheduleVo>('/rest/firing-schedules/firing-schedule', {
          domain: { domainId: FIRING_DOMAIN },
          targetDomain: { domainId: PREFERRED_TARGET_DOMAIN },
          targetSeriesId: series?.seriesId,
          active: true,
        });
      } else if (!schedule.active) {
        schedule = await api.put<FiringScheduleVo>(
          `/rest/firing-schedules/firing-schedule/${schedule.id}`,
          { ...schedule, active: true },
        );
      }
      expect(schedule.active).toBe(true);
      scheduleId = schedule.id;
      targetDomain = schedule.targetDomain.domainId;
      targetSeries = schedule.targetSeriesId;
      const firingSeries = (domainOf(FIRING_DOMAIN)?.messageSeries ?? []).map((s) => s.seriesId);
      expect(
        firingSeries,
        'the schedule writes into a series of its own, not the firing area messages’ series',
      ).not.toContain(targetSeries);
      targetMainType =
        domainOf(targetDomain)?.messageSeries?.find((s) => s.seriesId === targetSeries)?.mainType ??
        '';
      zone = domainOf(FIRING_DOMAIN)?.timeZone ?? '';
      targetZone = domainOf(targetDomain)?.timeZone ?? '';
      expect(zone, `${FIRING_DOMAIN} has a time zone`).not.toBe('');
      expect(targetZone, `${targetDomain} has a time zone`).not.toBe('');
    });

    await test.step('the run takes areas whose firing area message is published', async () => {
      const now = wallClockIn(new Date(), browserZone());
      today = { ...now, hour: 0, minute: 0 };
      const firingAreas = await api.get<FiringAreaPeriodsVo[]>(SEARCH_PATH, { lang: 'en' });
      const names = new Map(
        firingAreas.map((row) => [row.area.id, english(row.area.descs, 'name')] as const),
      );
      const templates = await new PublicApi(page.request).get<MessageVo[]>(
        '/rest/public/v1/messages',
        { lang: 'en', domain: FIRING_DOMAIN },
      );
      const ids = [
        ...new Set(
          templates
            .filter((message) => message.status === 'PUBLISHED')
            .flatMap((message) => (message.areas ?? []).map((a) => a.id))
            .filter((id) => names.has(id)),
        ),
      ].sort((a, b) => a - b);
      expect(
        ids.length,
        `the sample has a published firing area message in ${FIRING_DOMAIN}`,
      ).toBeGreaterThan(0);
      area = { id: ids[0], name: names.get(ids[0]) ?? '' };
      if (ids.length > 1) pastArea = { id: ids[1], name: names.get(ids[1]) ?? '' };
    });

    await test.step('the run’s days are cleared, and no warning of the areas stays', async () => {
      await clearRunDays(api);
      const target = api.inDomain(targetDomain);
      for (const which of [area, pastArea]) {
        if (!which) continue;
        expect(await allPeriods(api, which.id), `${which.name} has no period`).toEqual([]);
        expect(
          await publishedExercises(target, which.id),
          `${which.name} has no published warning`,
        ).toEqual([]);
      }
    });
  });

  test('the duty officer enters the firing periods of two days', async ({ editorPage: page }) => {
    test.skip(!area, 'the set-up found no firing area (an earlier test failed)');
    const which = area as FiringArea;
    const api = new AdminApi(page, FIRING_DOMAIN);
    await useDomain(page, FIRING_DOMAIN);
    const row = areaRow(page, which);

    await test.step('two periods on the first day', async () => {
      await openSchedule(page, day(2));
      expect(await expectRowMatchesApi(page, api, which, day(2)), 'the day starts empty').toEqual(
        [],
      );
      await startEditing(row);
      await enterPeriod(page, row, 0, at(day(2), 9), at(day(2), 11));
      await addPeriod(page, row, at(day(2), 14, 30), at(day(2), 16));
      await savePeriods(page, row);
      const held = await expectRowMatchesApi(page, api, which, day(2));
      expectSameList(
        'the stored periods',
        held.map((period) => periodText(intervalOf(period))),
        [
          `${clockText(at(day(2), 9))} - ${clockText(at(day(2), 11))}`,
          `${clockText(at(day(2), 14, 30))} - ${clockText(at(day(2), 16))}`,
        ],
      );
      day2Ids = held.map((period) => period.id ?? -1);
      expect(day2Ids.every((id) => id > 0)).toBe(true);
    });

    await test.step('one period until midnight on the next day', async () => {
      await openSchedule(page, day(3));
      expect(await expectRowMatchesApi(page, api, which, day(3)), 'the day starts empty').toEqual(
        [],
      );
      await startEditing(row);
      // An end of 00:00 is the next day's midnight.
      await enterPeriod(page, row, 0, at(day(3), 18), at(day(3), 0));
      await savePeriods(page, row);
      const held = await expectRowMatchesApi(page, api, which, day(3));
      expectSameList(
        'the stored period',
        held.map((period) => periodText(intervalOf(period))),
        [`${clockText(at(day(3), 18))} - ${clockText(at(day(4), 0))}`],
      );
    });

    await test.step('after a reload both days show what the API holds', async () => {
      await openSchedule(page, day(2));
      await expectRowMatchesApi(page, api, which, day(2));
      await openSchedule(page, day(3));
      await expectRowMatchesApi(page, api, which, day(3));
      // A period that ends at midnight is listed on its own day only.
      await openSchedule(page, day(4));
      expect(await expectRowMatchesApi(page, api, which, day(4))).toEqual([]);
    });
  });

  test('Update Firing Exercises makes one warning that holds the periods', async ({
    editorPage: page,
    request,
  }) => {
    test.skip(day2Ids.length === 0, 'no periods were saved (an earlier test failed)');
    const api = new AdminApi(page, FIRING_DOMAIN);
    const publicApi = new PublicApi(request);
    await useDomain(page, FIRING_DOMAIN);

    await openSchedule(page, day(2));
    await updateExercises(page);

    const periods = (await allPeriods(api, area?.id ?? 0)).map(intervalOf);
    expect(periods).toHaveLength(3);
    const exercise = await expectExercise(api.inDomain(targetDomain), publicApi, periods);
    exerciseId = exercise.id;
    await expectExerciseShown(page, publicApi, exercise);
  });

  test('a period’s start is changed, and the warning follows', async ({
    editorPage: page,
    request,
  }) => {
    test.skip(!exerciseId, 'no warning was made (an earlier test failed)');
    const which = area as FiringArea;
    const api = new AdminApi(page, FIRING_DOMAIN);
    const publicApi = new PublicApi(request);
    await useDomain(page, FIRING_DOMAIN);
    const row = areaRow(page, which);

    await test.step('09:00 becomes 08:00; the periods keep their identity', async () => {
      await openSchedule(page, day(2));
      expect(await expectRowMatchesApi(page, api, which, day(2))).toHaveLength(2);
      await startEditing(row);
      await pickTime(
        page,
        row.getByRole('button', { name: 'From', exact: true }).first(),
        at(day(2), 8),
      );
      await savePeriods(page, row);
      const held = await expectRowMatchesApi(page, api, which, day(2));
      expect(periodText(intervalOf(held[0]))).toBe(
        `${clockText(at(day(2), 8))} - ${clockText(at(day(2), 11))}`,
      );
      expect(
        held.map((period) => period.id),
        'a changed period is updated, not deleted and added',
      ).toEqual(day2Ids);
    });

    await test.step('the update cancels the old warning and publishes one with 08:00', async () => {
      await updateExercises(page);
      const target = api.inDomain(targetDomain);
      await expectCancelled(target, publicApi, exerciseId);
      const exercise = await expectExercise(
        target,
        publicApi,
        (await allPeriods(api, which.id)).map(intervalOf),
      );
      expect(exercise.id, 'a new warning').not.toBe(exerciseId);
      expectShows(
        'the new warning’s time part',
        english(timePartOf(exercise)?.descs, 'details'),
        timeLine({
          from: instantAt(at(day(2), 8), zone).getTime(),
          to: instantAt(at(day(2), 11), zone).getTime(),
        }),
      );
      replacedIds.push(exerciseId);
      exerciseId = exercise.id;
      await expectExerciseShown(page, publicApi, exercise);
    });
  });

  test('a user without the editor role cannot open the schedule or change it', async ({
    userPage: page,
  }) => {
    test.skip(!area, 'the set-up found no firing area (an earlier test failed)');
    await useDomain(page, FIRING_DOMAIN);
    await page.goto('/schedule');
    await expect(page.getByText(/access denied/i).first()).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Firing Area Schedule' })).toHaveCount(0);

    const api = new AdminApi(page, FIRING_DOMAIN);
    await expect(putPeriods(api, area?.id ?? 0, day(2), [])).rejects.toThrow(/answered 403/);
    await expect(api.put(UPDATE_PATH, {})).rejects.toThrow(/answered 403/);
    expect(await periodsOn(api, area?.id ?? 0, day(2)), 'the periods are unchanged').toHaveLength(
      2,
    );
  });

  test('after a restart of the backend everything reads the same', async ({
    editorPage: page,
    request,
  }) => {
    test.skip(!exerciseId, 'no warning was made (an earlier test failed)');
    const which = area as FiringArea;
    const api = new AdminApi(page, FIRING_DOMAIN);
    const target = api.inDomain(targetDomain);
    const publicApi = new PublicApi(request);

    const readBack = async () => {
      const exercise = await target.get<ExerciseVo>(`/rest/messages/message/${exerciseId}`, {
        lang: 'en',
      });
      const published = await publicApi.get<ExerciseVo>(`/rest/public/v1/message/${exerciseId}`, {
        lang: 'en',
      });
      return {
        periods: (await allPeriods(api, which.id)).map((period) => ({
          id: period.id,
          legacyId: period.legacyId,
          period: instantText(intervalOf(period)),
        })),
        warning: {
          status: exercise.status,
          title: english(exercise.descs, 'title'),
          time: english(timePartOf(exercise)?.descs, 'details'),
          eventDates: eventDatesOf(exercise),
          publishDateFrom: ms(exercise.publishDateFrom),
          publishDateTo: ms(exercise.publishDateTo),
        },
        published: {
          status: published.status,
          time: english(timePartOf(published)?.descs, 'details'),
          eventDates: eventDatesOf(published),
        },
        publishedIds: await publishedExercises(target, which.id),
        replaced: await Promise.all(
          replacedIds.map(
            async (id) => (await target.get<ExerciseVo>(`/rest/messages/message/${id}`)).status,
          ),
        ),
      };
    };

    const before = await readBack();
    expect(before.publishedIds).toEqual([exerciseId]);

    await restartBackend(request);

    await test.step('the periods, the warnings and the public answers are unchanged', async () => {
      expect(await readBack()).toEqual(before);
      await expectInPublicList(publicApi, exerciseId, true);
    });

    await test.step('the Schedule page shows the same days after a reload', async () => {
      await useDomain(page, FIRING_DOMAIN);
      for (const offset of [2, 3, 4]) {
        await openSchedule(page, day(offset));
        await expectRowMatchesApi(page, api, which, day(offset));
      }
    });

    await test.step('a second update changes nothing', async () => {
      await updateExercises(page);
      expect(await readBack()).toEqual(before);
    });

    await test.step('the screens show the same warning', async () => {
      await expectExerciseShown(
        page,
        publicApi,
        await target.get<ExerciseVo>(`/rest/messages/message/${exerciseId}`, { lang: 'en' }),
      );
    });
  });

  test('a period is removed, and the warning follows', async ({ editorPage: page, request }) => {
    test.skip(!exerciseId, 'no warning was made (an earlier test failed)');
    const which = area as FiringArea;
    const api = new AdminApi(page, FIRING_DOMAIN);
    const publicApi = new PublicApi(request);
    await useDomain(page, FIRING_DOMAIN);
    const row = areaRow(page, which);

    await openSchedule(page, day(2));
    expect(await expectRowMatchesApi(page, api, which, day(2))).toHaveLength(2);
    await startEditing(row);
    await row.getByRole('button', { name: 'Remove', exact: true }).nth(1).click();
    await expect(row.getByRole('button', { name: 'From', exact: true })).toHaveCount(1);
    await savePeriods(page, row);
    const held = await expectRowMatchesApi(page, api, which, day(2));
    expect(held.map((period) => period.id)).toEqual([day2Ids[0]]);

    await updateExercises(page);
    const target = api.inDomain(targetDomain);
    await expectCancelled(target, publicApi, exerciseId);
    const periods = (await allPeriods(api, which.id)).map(intervalOf);
    expect(periods).toHaveLength(2);
    const exercise = await expectExercise(target, publicApi, periods);
    expect(exercise.id).not.toBe(exerciseId);
    expect(english(timePartOf(exercise)?.descs, 'details')).not.toContain('14:30');
    replacedIds.push(exerciseId);
    exerciseId = exercise.id;
  });

  test('a period across midnight is kept when its next day is saved', async ({
    editorPage: page,
    request,
  }) => {
    test.skip(!exerciseId, 'no warning was made (an earlier test failed)');
    const which = area as FiringArea;
    const api = new AdminApi(page, FIRING_DOMAIN);
    const publicApi = new PublicApi(request);
    await useDomain(page, FIRING_DOMAIN);
    const row = areaRow(page, which);

    // The screen makes no period across midnight (it ends one at 00:00), but older data and API
    // clients hold them, so this one is made through the API: 22:00 to 02:00 the next morning.
    const [night] = await putPeriods(api, which.id, day(4), [
      {
        fromDate: instantAt(at(day(4), 22), zone).toISOString(),
        toDate: instantAt(at(day(5), 2), zone).toISOString(),
      },
    ]);
    expect(periodText(intervalOf(night))).toBe(
      `${clockText(at(day(4), 22))} - ${clockText(at(day(5), 2))}`,
    );

    await openSchedule(page, day(5));
    expect(await expectRowMatchesApi(page, api, which, day(5))).toHaveLength(1);
    await startEditing(row);
    // The night's period belongs to the day before: shown, but not offered for editing here.
    await expect(row.getByText('Starts on an earlier day. Edit it on that day.')).toBeVisible();
    await expect(row.getByRole('button', { name: 'From', exact: true })).toHaveCount(0);
    await addPeriod(page, row, at(day(5), 9), at(day(5), 10));
    await savePeriods(page, row);
    await expectRowMatchesApi(page, api, which, day(5));

    const kept = (await periodsOn(api, which.id, day(4))).find((p) => p.id === night.id);
    expect(kept, 'the night’s period is kept, with its id').toBeDefined();
    expect(instantText(intervalOf(kept ?? night))).toBe(instantText(intervalOf(night)));

    await updateExercises(page);
    const target = api.inDomain(targetDomain);
    await expectCancelled(target, publicApi, exerciseId);
    const periods = (await allPeriods(api, which.id)).map(intervalOf);
    expect(periods).toHaveLength(4);
    const exercise = await expectExercise(target, publicApi, periods);
    replacedIds.push(exerciseId);
    exerciseId = exercise.id;
  });

  test('a firing that ended earlier today makes no warning', async ({
    editorPage: page,
    request,
  }) => {
    test.skip(!pastArea, 'the sample has one firing area message only: no second area to use');
    const which = pastArea as FiringArea;
    // The run counts the periods that end after today's start in the backend's zone (UTC); a
    // firing that ended between then and now is the case the hourly run used to publish anew.
    const now = Date.now();
    const start = new Date(now);
    const windowStart = Date.UTC(start.getUTCFullYear(), start.getUTCMonth(), start.getUTCDate());
    const period = { from: windowStart + 5 * 60_000, to: windowStart + 35 * 60_000 };
    const from = wallClockIn(period.from, zone);
    const to = wallClockIn(period.to, zone);
    if (now < period.to + 5 * 60_000 || !sameDay(from, to)) {
      leftOut('a firing that ended earlier today: too soon after midnight UTC to make one');
      return;
    }
    const api = new AdminApi(page, FIRING_DOMAIN);
    await useDomain(page, FIRING_DOMAIN);
    const row = areaRow(page, which);
    const on = { ...from, hour: 0, minute: 0 };

    await openSchedule(page, on);
    expect(await expectRowMatchesApi(page, api, which, on), 'the day starts empty').toEqual([]);
    await startEditing(row);
    await enterPeriod(page, row, 0, from, to);
    await savePeriods(page, row);
    await expectRowMatchesApi(page, api, which, on);

    await updateExercises(page);
    expect(
      await publishedExercises(api.inDomain(targetDomain), which.id),
      `no warning for ${which.name}, whose only firing has ended`,
    ).toEqual([]);
    // The main area's warning is untouched.
    expect(await publishedExercises(api.inDomain(targetDomain), area?.id ?? 0)).toEqual(
      exerciseId ? [exerciseId] : [],
    );
    await expectInPublicList(new PublicApi(request), exerciseId, !!exerciseId);
  });
});
