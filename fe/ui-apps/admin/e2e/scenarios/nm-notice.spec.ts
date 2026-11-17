import type { Locator, Page } from '@playwright/test';

import { expect, test } from '../auth/fixtures';
import { AdminApi, english, PublicApi, type Desc } from './support/api';
import {
  expectPositions,
  expectSame,
  expectSameList,
  expectShows,
  normalise,
  type Position,
} from './support/compare';
import { annotate, describeScenario, scenarioEnv } from './support/env';
import { keepOffline } from './support/offline';
import { pdfText } from './support/pdf-text';
import { pickDay, pickTime } from './support/pickers';
import { restartBackend } from './support/restart';
import { newRunMarker } from './support/run-id';
import {
  addDays,
  instantAt,
  shownDay,
  shownTime,
  wallClockIn,
  type WallClock,
} from './support/zoned-time';

/*
 * Scenario 2: a notice to mariners written by hand, as a duty officer takes it. The editor makes
 * a blank NM with two areas, a category, two references, three parts with event dates and
 * positions typed in several formats, three charts, two attachments picked together, an
 * external and an internal publication and a source; saves it and reads it back. Then the parts
 * are moved, one removed and a Time part added, the positions reordered and an attachment
 * replaced; saved and read back, the files byte for byte. The notice is verified and published
 * and read in the public API, the NM list, on the public site and in the PDF; the backend is
 * restarted and all of it read again.
 *
 * The scenario needs nothing the sample lacks: the notices it refers to, the areas, the category
 * and the charts are read from the sample, and the attachments are made in memory. Its notice
 * stays as the run's record.
 */

// The slice of Node's globals the file needs: the e2e tsconfig loads no Node types.
declare const Buffer: {
  from(data: string, encoding?: 'base64' | 'utf8'): Uint8Array;
};

const RUN = newRunMarker();
const DOMAIN = 'niord-nm';

const VICINITY = `${RUN} approach`;
const SUBJECT_1 = `${RUN}: buoys laid and cable works`;
const SUBJECT_2 = `${RUN}: wreck and exclusion zone west`;
const DETAILS_1 = `${RUN} Two buoys are laid and a cable is laid between them.`;
const DETAILS_2 = `${RUN} A wreck lies west of the exclusion zone.`;
const NOTE = `${RUN} Mariners are requested to navigate with caution.`;
const TIME_TEXT = `${RUN} The works are expected to end in the new year.`;
const PUBLICATION = `${RUN}: List of Lights, vol. 2, no. 1234.`;
const INTERNAL_PUBLICATION = `${RUN} internal: chart correction file 77.`;
const SOURCE = `${RUN} harbour master`;
const REFERENCE_TEXTS = [`${RUN} see also`, `${RUN} replaced`];
const CAPTIONS = [`${RUN} sketch of the buoys`, `${RUN} survey notes`];
const REPLACEMENT_CAPTION = `${RUN} replacement sketch`;

// Part 1's positions, typed in four formats: decimal comma just below a whole degree, seconds, a
// numbered line with named first and last vertices, and a polygon without degree signs.
const POSITIONS_1 = [
  `Point, en: ${RUN} buoy A`,
  "55° 59,999'N - 012° 37,500'E",
  '',
  `Point, en: ${RUN} buoy B`,
  `55° 45' 30"N 12° 36' 15"E`,
  '',
  `LineString, en: ${RUN} cable`,
  `1) 55° 50.000'N - 012° 40.000'E, en: ${RUN} cable west end`,
  "2) 55° 51.000'N - 012° 42.000'E",
  `3) 55° 52.000'N - 012° 44.000'E, en: ${RUN} cable east end`,
  '',
  `Polygon, en: ${RUN} works area`,
  '55 46N 12 38E',
  '55 46N 12 39E',
  '55 45N 12 39E',
  '55 45N 12 38E',
].join('\n');

// Part 2's positions, west of Greenwich: decimal degrees, minutes just below a whole degree, and
// degrees and minutes divided by spaces only.
const POSITIONS_2 = [
  `Point, en: ${RUN} wreck west`,
  '55.5N 4.25W',
  '',
  `MultiPoint, en: ${RUN} markers`,
  "56° 01.000'N - 001° 59.9999'W",
  "56° 02.000'N - 002° 00.500'W",
  '',
  `Polygon, en: ${RUN} exclusion zone`,
  '55 30N 3 30W',
  '55 30N 3 00W',
  '55 15N 3 00W',
].join('\n');

// Part 1's positions in a new order, the cable first with its vertices reversed (east end first).
const POSITIONS_1_REORDERED = [
  `LineString, en: ${RUN} cable`,
  `1) 55° 52.000'N - 012° 44.000'E, en: ${RUN} cable east end`,
  "2) 55° 51.000'N - 012° 42.000'E",
  `3) 55° 50.000'N - 012° 40.000'E, en: ${RUN} cable west end`,
  '',
  `Point, en: ${RUN} buoy B`,
  `55° 45' 30"N 12° 36' 15"E`,
  '',
  `Point, en: ${RUN} buoy A`,
  "55° 59,999'N - 012° 37,500'E",
  '',
  `Polygon, en: ${RUN} works area`,
  '55 46N 12 38E',
  '55 46N 12 39E',
  '55 45N 12 39E',
  '55 45N 12 38E',
].join('\n');

/* ---------------------------------------------------------------------------------------------- */
/* What the API gives                                                                              */
/* ---------------------------------------------------------------------------------------------- */

interface NoticeDesc extends Desc {
  readonly publication?: string;
  readonly internalPublication?: string;
  readonly source?: string;
  readonly caption?: string;
  readonly description?: string;
}

interface DateIntervalVo {
  readonly allDay?: boolean;
  readonly fromDate?: string | number;
  readonly toDate?: string | number;
}

interface FeatureVo {
  readonly geometry: { readonly type: string; readonly coordinates: unknown };
  readonly properties?: Record<string, unknown>;
}

interface PartVo {
  readonly indexNo?: number;
  readonly type: string;
  readonly hideSubject?: boolean;
  readonly descs?: readonly NoticeDesc[];
  readonly eventDates?: readonly DateIntervalVo[];
  readonly geometry?: { readonly features?: readonly FeatureVo[] };
}

interface AttachmentVo {
  readonly fileName: string;
  readonly path?: string;
  readonly display?: string;
  readonly descs?: readonly NoticeDesc[];
}

interface NoticeVo {
  readonly id: string;
  readonly shortId?: string;
  readonly number?: number;
  readonly status: string;
  readonly mainType: string;
  readonly type?: string;
  readonly messageSeries?: { readonly seriesId: string };
  readonly areas?: readonly { readonly id: number; readonly descs?: readonly Desc[] }[];
  readonly categories?: readonly { readonly id: number; readonly descs?: readonly Desc[] }[];
  readonly charts?: readonly {
    readonly chartNumber: string;
    readonly internationalNumber?: number;
  }[];
  readonly descs?: readonly NoticeDesc[];
  readonly parts?: readonly PartVo[];
  readonly references?: readonly {
    readonly messageId: string;
    readonly type: string;
    readonly descs?: readonly NoticeDesc[];
  }[];
  readonly attachments?: readonly AttachmentVo[];
  readonly editRepoPath?: string;
}

interface AreaVo {
  readonly id: number;
  readonly type?: string;
  readonly descs?: readonly Desc[];
  readonly children?: readonly AreaVo[];
}

/* ---------------------------------------------------------------------------------------------- */
/* What the steps hand on to the next                                                              */
/* ---------------------------------------------------------------------------------------------- */

let zone = '';
let areaNames: string[] = [];
let categoryName = '';
let chartNumbers: string[] = [];
let references: { shortId: string; title: string }[] = [];
let messageId = '';
let shortId = '';
let uploads: { name: string; bytes: Uint8Array }[] = [];
let beforeRestart: { admin: unknown; files: string[] } | undefined;

/** The event dates the officer types, as wall clocks in the domain's zone. */
let dates: {
  timed: { from: WallClock; to: WallClock };
  allDay: { from: WallClock; to: WallClock };
  open: { from: WallClock };
  time: { from: WallClock };
};

/* ---------------------------------------------------------------------------------------------- */
/* The editor's rows                                                                               */
/* ---------------------------------------------------------------------------------------------- */

/** A field row of the edit form, by its label, inside `scope` (the form or one part card). */
function fieldRow(scope: Locator, label: string): Locator {
  const head = scope.page().getByRole('button', { name: new RegExp(`^${label}\\b`) });
  return scope.locator('app-editor-field-row').filter({ has: head }).first();
}

function rowHead(row: Locator, label: string): Locator {
  return row.getByRole('button', { name: new RegExp(`^${label}\\b`) }).first();
}

async function openRow(scope: Locator, label: string): Promise<Locator> {
  const row = fieldRow(scope, label);
  const head = rowHead(row, label);
  if ((await head.getAttribute('aria-expanded')) !== 'true') await head.click();
  await expect(head).toHaveAttribute('aria-expanded', 'true');
  return row;
}

async function closeRow(scope: Locator, label: string): Promise<void> {
  const head = rowHead(fieldRow(scope, label), label);
  if ((await head.getAttribute('aria-expanded')) === 'true') await head.click();
  await expect(head).toHaveAttribute('aria-expanded', 'false');
}

/** The value a collapsed row shows: its text without the label. */
async function rowValue(scope: Locator, label: string): Promise<string> {
  await closeRow(scope, label);
  const text = await rowHead(fieldRow(scope, label), label).innerText();
  return normalise(text.replace(new RegExp(`^\\s*${label}\\s*\\*?`), ''));
}

function parts(page: Page): Locator {
  return page.locator('main app-part-card');
}

/** Types into a rich-text box as an officer does: the box focused, the text keyed in. */
async function typeRich(box: Locator, text: string): Promise<void> {
  await box.click();
  await box.page().keyboard.press('Control+A');
  await box.page().keyboard.press('Delete');
  await box.page().keyboard.type(text);
}

/** Adds one event date to a part: all day, or with times; without `to` it stays open-ended. */
async function addEventDate(
  page: Page,
  part: Locator,
  from: WallClock,
  to: WallClock | undefined,
  allDay: boolean,
): Promise<void> {
  const row = await openRow(part, 'Event dates');
  const lines = row.getByRole('row');
  const before = await lines.count();
  await row.getByRole('button', { name: /^add date$/i }).click();
  // The header row and one row per date: wait for the new one before touching it.
  await expect(lines).toHaveCount(before + 1);
  const line = lines.nth(before);
  const allDayBox = line.getByRole('checkbox');
  if (allDay) await allDayBox.check();
  else await allDayBox.uncheck();
  const fromButtons = line.getByRole('button', { name: /^from$/i });
  const toButtons = line.getByRole('button', { name: /^to$/i });
  await expect(fromButtons).toHaveCount(allDay ? 1 : 2);
  await pickDay(page, fromButtons.first(), from);
  if (!allDay) await pickTime(page, fromButtons.nth(1), from);
  if (to) {
    await pickDay(page, toButtons.first(), to);
    if (!allDay) await pickTime(page, toButtons.nth(1), to);
  }
}

/* ---------------------------------------------------------------------------------------------- */
/* Comparing the screen with the API                                                               */
/* ---------------------------------------------------------------------------------------------- */

/** The points of a part's features in the order the editor lists them (a ring without its end). */
function coordinatesOf(features: readonly FeatureVo[]): Position[] {
  const out: Position[] = [];
  const push = (pair: unknown): void => {
    const [lon, lat] = pair as [number, number];
    out.push({ lat, lon });
  };
  for (const { geometry } of features) {
    const coordinates = geometry.coordinates as unknown[];
    if (geometry.type === 'Point') push(coordinates);
    else if (geometry.type === 'MultiPoint' || geometry.type === 'LineString') {
      coordinates.forEach(push);
    } else if (geometry.type === 'Polygon') {
      const ring = coordinates[0] as unknown[];
      ring.slice(0, -1).forEach(push);
    }
  }
  return out;
}

/** The names of a part's features and vertices in order, as the editor lists them. */
function namesOf(features: readonly FeatureVo[]): string {
  return features
    .map((feature) => {
      const properties = feature.properties ?? {};
      const vertices = Object.keys(properties)
        .map((key) => /^name:(\d+):en$/.exec(key))
        .filter((match): match is RegExpExecArray => !!match)
        .sort((a, b) => Number(a[1]) - Number(b[1]))
        .map((match) => `— ${String(properties[match[0]])}`);
      return [String(properties['name:en'] ?? ''), ...vertices].join(' ');
    })
    .join(' ');
}

/** The positions row of a part, open: its coordinate lines and its names against the features. */
async function expectPartPositions(part: Locator, features: readonly FeatureVo[], n: number) {
  const row = await openRow(part, 'Positions');
  // The coordinate list under the map has no role of its own; its class is the one handle.
  const list = row.locator('.ed-coords');
  const text = features.length ? await list.innerText() : '';
  expectPositions(`part ${n} positions`, text, coordinatesOf(features));
  const names = normalise(
    text
      .replace(/\d{1,3}°\s*\d{1,2}(?:\.\d+)?'[NS]\s*-\s*\d{1,3}°\s*\d{1,2}(?:\.\d+)?'[EW]/g, ' ')
      .replace(/(^|\s)\d+\)/g, ' '),
  );
  expectSame(`part ${n} position names`, names, namesOf(features));
  await closeRow(part, 'Positions');
}

/** What the screen shows of one event date, read back as wall clocks. */
async function shownEventDates(part: Locator): Promise<string[]> {
  const row = await openRow(part, 'Event dates');
  const lines = row.getByRole('row');
  const count = await lines.count();
  const shown: string[] = [];
  for (let i = 1; i < count; i++) {
    const line = lines.nth(i);
    const allDay = await line.getByRole('checkbox').isChecked();
    const read = async (name: RegExp): Promise<string> => {
      const buttons = line.getByRole('button', { name });
      const day = shownDay(await buttons.first().innerText());
      if (!day) return '';
      const time = allDay ? undefined : shownTime(await buttons.nth(1).innerText());
      return clockText({ ...day, hour: time?.hour ?? 0, minute: time?.minute ?? 0 }, allDay);
    };
    shown.push(`${allDay ? 'all day' : 'timed'} ${await read(/^from$/i)} - ${await read(/^to$/i)}`);
  }
  await closeRow(part, 'Event dates');
  return shown;
}

function clockText(clock: WallClock, dayOnly: boolean): string {
  const day = `${clock.year}-${clock.month}-${clock.day}`;
  return dayOnly ? day : `${day} ${clock.hour}:${String(clock.minute).padStart(2, '0')}`;
}

/** The same event dates as the API holds them, read in the domain's zone. */
function heldEventDates(part: PartVo | undefined): string[] {
  return (part?.eventDates ?? []).map((interval) => {
    const read = (value: string | number | undefined): string =>
      value === undefined || value === null
        ? ''
        : clockText(wallClockIn(value, zone), !!interval.allDay);
    return `${interval.allDay ? 'all day' : 'timed'} ${read(interval.fromDate)} - ${read(interval.toDate)}`;
  });
}

/** The type the part's switcher shows pressed. */
async function shownPartType(part: Locator): Promise<string> {
  const pressed = part.getByRole('group').first().getByRole('button', { pressed: true });
  return normalise(await pressed.innerText()).toUpperCase();
}

/** The edit form against the admin API's editable message: every field and list, in order. */
async function expectEditorMatchesApi(page: Page, api: AdminApi): Promise<NoticeVo> {
  const message = await api.get<NoticeVo>(`/rest/messages/editable-message/${messageId}`, {
    lang: 'en',
  });
  const main = page.locator('main');

  expectShows('type', await rowValue(main, 'Type'), 'Temporary');
  expect(message.type, 'the stored type').toBe('TEMPORARY_NOTICE');
  expectSame('title', await rowValue(main, 'Title'), english(message.descs, 'title'));
  expectSame(
    'areas',
    await rowValue(main, 'Areas'),
    `${(message.areas ?? []).map((area) => english(area.descs, 'name')).join(', ')} · ${english(message.descs, 'vicinity')}`,
  );
  expectSame(
    'categories',
    await rowValue(main, 'Categories'),
    (message.categories ?? []).map((category) => english(category.descs, 'name')).join(', '),
  );

  const referencesRow = await openRow(main, 'References');
  expectSameList(
    'reference ids',
    await referencesRow
      .getByRole('combobox', { name: /^message id$/i })
      .evaluateAll((boxes) => boxes.map((box) => (box as HTMLInputElement).value)),
    (message.references ?? []).map((reference) => reference.messageId),
  );
  expectSameList(
    'reference texts',
    await referencesRow
      .getByRole('textbox', { name: /^text in english$/i })
      .evaluateAll((boxes) => boxes.map((box) => (box as HTMLInputElement).value)),
    (message.references ?? []).map((reference) => english(reference.descs, 'description')),
  );
  await closeRow(main, 'References');

  const cards = parts(page);
  await expect(cards).toHaveCount(message.parts?.length ?? 0);
  for (const [i, part] of (message.parts ?? []).entries()) {
    const card = cards.nth(i);
    expect(await shownPartType(card), `part ${i + 1} type`).toBe(part.type);
    if (part.type === 'DETAILS' || part.type === 'TIME') {
      expectSameList(
        `part ${i + 1} event dates`,
        await shownEventDates(card),
        heldEventDates(part),
      );
    }
    if (part.type === 'DETAILS' || part.type === 'POSITIONS') {
      await expectPartPositions(card, part.geometry?.features ?? [], i + 1);
    }
    if (part.type === 'DETAILS') {
      const subject = await openRow(card, 'Subject');
      expectSame(
        `part ${i + 1} subject`,
        await subject.getByRole('textbox', { name: /^text in english$/i }).inputValue(),
        english(part.descs, 'subject'),
      );
      await closeRow(card, 'Subject');
    }
    expectSame(
      `part ${i + 1} description`,
      await rowValue(card, 'Description'),
      english(part.descs, 'details'),
    );
  }

  const charts = await rowValue(main, 'Charts');
  const shownCharts = [...charts.matchAll(/(?:^|,\s*)([^\s,(]+)/g)].map((match) => match[1]);
  expectSameList(
    'charts',
    shownCharts,
    (message.charts ?? []).map((chart) => chart.chartNumber),
  );

  const attachmentsRow = await openRow(main, 'Attachments');
  const attachmentLines = attachmentsRow.locator('tbody').getByRole('row');
  await expect(attachmentLines).toHaveCount(message.attachments?.length ?? 0);
  const shownFiles: string[] = [];
  const shownCaptions: string[] = [];
  const shownDisplays: string[] = [];
  for (let i = 0; i < (message.attachments?.length ?? 0); i++) {
    const line = attachmentLines.nth(i);
    shownFiles.push(await line.getByRole('button', { name: /^show /i }).innerText());
    shownCaptions.push(
      await line.getByRole('textbox', { name: /^text in english$/i }).inputValue(),
    );
    const display = line.getByRole('combobox', { name: /^display$/i });
    shownDisplays.push((await display.count()) ? await display.inputValue() : '');
  }
  const held = message.attachments ?? [];
  expectSameList(
    'attachment files',
    shownFiles,
    held.map((attachment) => attachment.fileName),
  );
  expectSameList(
    'attachment captions',
    shownCaptions,
    held.map((attachment) => english(attachment.descs, 'caption')),
  );
  expectSameList(
    'attachment displays',
    shownDisplays,
    held.map((attachment) => attachment.display ?? ''),
  );
  await closeRow(main, 'Attachments');

  const publication = await openRow(main, 'Publication');
  expectSame(
    'external publication',
    await publication
      .getByRole('textbox', { name: /^external publication in english$/i })
      .innerText(),
    english(message.descs, 'publication'),
  );
  expectSame(
    'internal publication',
    await publication
      .getByRole('textbox', { name: /^internal publication in english$/i })
      .innerText(),
    english(message.descs, 'internalPublication'),
  );
  await closeRow(main, 'Publication');
  const source = await openRow(main, 'Source');
  expectSame(
    'source',
    await source.getByRole('textbox', { name: /^text in english$/i }).inputValue(),
    english(message.descs, 'source'),
  );
  await closeRow(main, 'Source');
  return message;
}

/** The stored files of the notice's attachments, fetched, against the bytes uploaded. */
async function expectAttachmentBytes(api: AdminApi, message: NoticeVo): Promise<string[]> {
  const hashes: string[] = [];
  for (const attachment of message.attachments ?? []) {
    const upload = uploads.find((file) => file.name === attachment.fileName);
    expect(
      upload,
      `the attachment ${attachment.fileName} is one the officer uploaded`,
    ).toBeDefined();
    expect(attachment.path, `the attachment ${attachment.fileName} has a path`).toBeTruthy();
    const response = await api.fetch(`/rest/repo/file/${attachment.path}`);
    expect(response.ok(), `GET the attachment ${attachment.fileName}: ${response.status()}`).toBe(
      true,
    );
    const bytes = new Uint8Array(await response.body());
    expect(
      hex(bytes),
      `the stored ${attachment.fileName} holds the bytes uploaded (${bytes.length} against ${upload?.bytes.length})`,
    ).toBe(hex(upload?.bytes ?? new Uint8Array()));
    hashes.push(`${attachment.fileName}:${hex(bytes)}`);
  }
  return hashes;
}

function hex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
}

/** A small PNG drawn in the page's own canvas: a label of the run, so every run's file differs. */
async function drawnPng(page: Page, label: string, colour: string): Promise<Uint8Array> {
  const base64 = await page.evaluate(
    ([text, fill]) => {
      const canvas = document.createElement('canvas');
      canvas.width = 120;
      canvas.height = 24;
      const context = canvas.getContext('2d');
      if (!context) throw new Error('no 2d canvas');
      context.fillStyle = fill;
      context.fillRect(0, 0, 120, 24);
      context.fillStyle = '#000';
      context.fillText(text, 4, 16);
      return canvas.toDataURL('image/png').split(',')[1];
    },
    [label, colour],
  );
  return Buffer.from(base64, 'base64');
}

/** Records in the report that a read was left out, and why. */
function leftOut(description: string): void {
  annotate('skip', description);
}

/* ---------------------------------------------------------------------------------------------- */
/* The published notice                                                                            */
/* ---------------------------------------------------------------------------------------------- */

/** The public API by uid and by short id holds what the admin API holds, minus the internal. */
async function expectPublicMatchesAdmin(api: AdminApi, publicApi: PublicApi): Promise<NoticeVo> {
  const admin = await api.get<NoticeVo>(`/rest/messages/message/${messageId}`, { lang: 'en' });
  expect(admin.status).toBe('PUBLISHED');
  for (const id of [messageId, shortId]) {
    const published = await publicApi.get<NoticeVo>(`/rest/public/v1/message/${id}`, {
      lang: 'en',
    });
    const where = `the public API by ${id}`;
    expect(published.id, where).toBe(messageId);
    expect(published.status, where).toBe('PUBLISHED');
    expect(published.shortId, where).toBe(shortId);
    expectSame(`${where}: title`, english(published.descs, 'title'), english(admin.descs, 'title'));
    expectSame(
      `${where}: publication`,
      english(published.descs, 'publication'),
      english(admin.descs, 'publication'),
    );
    expect(
      english(published.descs, 'internalPublication'),
      `${where}: the internal publication is left out`,
    ).toBe('');
    expectSame(
      `${where}: source`,
      english(published.descs, 'source'),
      english(admin.descs, 'source'),
    );
    expectSameList(
      `${where}: areas`,
      (published.areas ?? []).map((area) => String(area.id)),
      (admin.areas ?? []).map((area) => String(area.id)),
    );
    expectSameList(
      `${where}: charts`,
      (published.charts ?? []).map((chart) => chart.chartNumber),
      (admin.charts ?? []).map((chart) => chart.chartNumber),
    );
    expectSameList(
      `${where}: references`,
      (published.references ?? []).map((reference) => `${reference.messageId} ${reference.type}`),
      (admin.references ?? []).map((reference) => `${reference.messageId} ${reference.type}`),
    );
    expectSameList(
      `${where}: parts`,
      (published.parts ?? []).map((part) => part.type),
      (admin.parts ?? []).map((part) => part.type),
    );
    (admin.parts ?? []).forEach((part, i) => {
      const shown = published.parts?.[i];
      expectSame(
        `${where}: part ${i + 1} details`,
        english(shown?.descs, 'details'),
        english(part.descs, 'details'),
      );
      expectSameList(
        `${where}: part ${i + 1} event dates`,
        heldEventDates(shown),
        heldEventDates(part),
      );
      expect(
        coordinatesOf(shown?.geometry?.features ?? []),
        `${where}: part ${i + 1} positions`,
      ).toEqual(coordinatesOf(part.geometry?.features ?? []));
    });
    expectSameList(
      `${where}: attachments`,
      (published.attachments ?? []).map(
        (attachment) => `${attachment.fileName} ${english(attachment.descs, 'caption')}`,
      ),
      (admin.attachments ?? []).map(
        (attachment) => `${attachment.fileName} ${english(attachment.descs, 'caption')}`,
      ),
    );
  }
  return admin;
}

async function expectInPublicList(publicApi: PublicApi): Promise<void> {
  const list = await publicApi.get<NoticeVo[]>('/rest/public/v1/messages', {
    lang: 'en',
    mainType: 'NM',
  });
  expect(
    list.some((message) => message.id === messageId && message.shortId === shortId),
    `the public NM list holds ${shortId}`,
  ).toBe(true);
}

/** The message PDF holds the short id, the title, the external publication and not the internal. */
async function expectPdf(publicApi: PublicApi, admin: NoticeVo): Promise<void> {
  const response = await publicApi.fetch(`/rest/message-reports/message/${shortId}.pdf`, {
    lang: 'en',
  });
  expect(response.ok(), `the notice's PDF: ${response.status()}`).toBe(true);
  expect(response.headers()['content-type']).toContain('pdf');
  const text = normalise(await pdfText(await response.body()));
  expectShows('PDF short id', text, shortId);
  expectShows('PDF vicinity', text, VICINITY);
  expectShows('PDF part 1 details', text, english(admin.parts?.[0]?.descs, 'details'));
  expectShows('PDF publication', text, PUBLICATION);
  expectShows('PDF replacement caption', text, REPLACEMENT_CAPTION);
  expect(text, 'the PDF leaves the internal publication out').not.toContain(INTERNAL_PUBLICATION);
}

/** The public site's page of the notice shows what the public API holds. */
async function expectPublicSite(page: Page, admin: NoticeVo): Promise<void> {
  if (!scenarioEnv.infoUrl) {
    leftOut('E2E_INFO_URL is not set: the public site is not read');
    return;
  }
  await page.goto(`${scenarioEnv.infoUrl}/messages/details/message/${shortId}`);
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText(shortId);
  const text = await dialog.innerText();
  expectShows('public site title', text, english(admin.descs, 'title'));
  for (const [i, part] of (admin.parts ?? []).entries()) {
    expectShows(`public site part ${i + 1}`, text, english(part.descs, 'details'));
  }
  for (const reference of references)
    expectShows('public site references', text, reference.shortId);
  expectShows('public site publication', text, PUBLICATION);
  expectShows('public site source', text, SOURCE);
  expect(normalise(text), 'the public site leaves the internal publication out').not.toContain(
    INTERNAL_PUBLICATION,
  );
}

/** The admin API's notice without what a read makes afresh (the edit folder of the request). */
function stable(message: NoticeVo): unknown {
  return { ...message, editRepoPath: undefined };
}

/* ---------------------------------------------------------------------------------------------- */
/* The scenario                                                                                    */
/* ---------------------------------------------------------------------------------------------- */

describeScenario('a notice to mariners with parts, positions and attachments', () => {
  test.beforeEach(async ({ page, baseURL }) => {
    await keepOffline(page, baseURL);
    // The duty officer works in the NM domain of the sample.
    await page.addInitScript((domain) => {
      localStorage.setItem('niord.sessionDomainId', domain);
    }, DOMAIN);
  });

  test('set-up: the sample holds the notices, areas, category and charts the notice uses', async ({
    editorPage: page,
    request,
  }) => {
    const api = new AdminApi(page, DOMAIN);
    const publicApi = new PublicApi(request);

    const domains = await api.get<{ domainId: string; timeZone?: string }[]>('/rest/domains/all', {
      lang: 'en',
    });
    zone = domains.find((domain) => domain.domainId === DOMAIN)?.timeZone ?? '';
    expect(zone, `the sample's ${DOMAIN} has a time zone`).not.toBe('');

    const published = await publicApi.get<NoticeVo[]>('/rest/public/v1/messages', {
      lang: 'en',
      mainType: 'NM',
    });
    references = published
      .filter(
        (message) =>
          message.status === 'PUBLISHED' && /^SAMPLE —/.test(english(message.descs, 'title')),
      )
      .filter((message) => !!message.shortId)
      .sort((a, b) => String(a.shortId).localeCompare(String(b.shortId)))
      .slice(0, 2)
      .map((message) => ({
        shortId: String(message.shortId),
        title: english(message.descs, 'title'),
      }));
    expect(references, 'two published sample notices to refer to').toHaveLength(2);

    // Two sea areas of the sample's country, not firing areas.
    const roots = await api.get<AreaVo[]>('/rest/areas/area-roots', { lang: 'en' });
    const seas = (roots[0]?.children ?? []).filter(
      (area) => area.type !== 'FIRING_AREA' && english(area.descs, 'name') !== '',
    );
    areaNames = seas.slice(1, 3).map((area) => english(area.descs, 'name'));
    expect(areaNames, 'two sea areas in the sample').toHaveLength(2);

    const categories = await api.get<{ descs?: Desc[]; type?: string }[]>(
      '/rest/categories/search',
      { lang: 'en', name: 'buoy', domain: DOMAIN, limit: 10 },
    );
    categoryName = english(
      categories.find((category) => category.type !== 'TEMPLATE')?.descs,
      'name',
    );
    expect(categoryName, 'a category of buoys in the sample').not.toBe('');

    // Three charts with plain numbers, to be typed out of order: middle, lowest, highest.
    const charts = (await api.get<{ chartNumber: string }[]>('/rest/charts/all'))
      .map((chart) => chart.chartNumber)
      .filter((number) => /^\d{3}$/.test(number))
      .sort();
    expect(charts.length, 'three charts in the sample').toBeGreaterThanOrEqual(3);
    chartNumbers = [charts[1], charts[0], charts[2]];

    // The dates lie next month and the month after, in the domain's zone.
    const now = wallClockIn(new Date(), zone);
    const next = { year: now.year + (now.month === 12 ? 1 : 0), month: (now.month % 12) + 1 };
    const day = (d: number, hour = 0, minute = 0): WallClock => ({ ...next, day: d, hour, minute });
    dates = {
      timed: { from: day(10, 8, 30), to: day(12, 16, 15) },
      allDay: { from: day(20), to: day(22) },
      open: { from: day(25) },
      time: { from: addDays(day(28), 7) },
    };
  });

  test('the duty officer writes the notice by hand and saves it', async ({ editorPage: page }) => {
    test.skip(!zone, 'the set-up did not finish');
    const api = new AdminApi(page, DOMAIN);
    const main = page.locator('main');

    await page.goto('/editor');
    await page
      .locator('main')
      .getByRole('button', { name: /^new blank nm$/i })
      .click();
    await page.waitForURL(/\/editor\/edit(?:[?#]|$)/);

    await test.step('type, areas, vicinity and category', async () => {
      const type = await openRow(main, 'Type');
      await type
        .getByRole('group')
        .getByRole('button', { name: /^temporary$/i })
        .click();
      await closeRow(main, 'Type');

      const areas = await openRow(main, 'Areas');
      for (const name of areaNames) {
        await areas.getByRole('combobox', { name: /^select areas$/i }).fill(name);
        await page
          .getByRole('option', { name: new RegExp(`^${escapeRegExp(name)}\\(`) })
          .first()
          .click();
      }
      await areas.getByRole('textbox', { name: /^text in english$/i }).fill(VICINITY);
      await closeRow(main, 'Areas');

      const categories = await openRow(main, 'Categories');
      await categories.getByRole('combobox', { name: /^select categories$/i }).fill(categoryName);
      await page
        .getByRole('option', { name: new RegExp(`^${escapeRegExp(categoryName)}\\b`) })
        .first()
        .click();
      await closeRow(main, 'Categories');
    });

    await test.step('two references, the second of type cancelled', async () => {
      const row = await openRow(main, 'References');
      for (const [i, reference] of references.entries()) {
        await row.getByRole('button', { name: /^add reference$/i }).click();
        const box = row.getByRole('combobox', { name: /^message id$/i }).nth(i);
        await box.pressSequentially(reference.shortId);
        await page
          .getByRole('option')
          .filter({ hasText: reference.shortId })
          .filter({ hasText: reference.title.slice(0, 20) })
          .first()
          .click();
        // The id picked stays in the box, as the officer typed it.
        await expect(box).toHaveValue(reference.shortId);
        if (i === 1) {
          await row
            .getByRole('combobox', { name: /^type$/i })
            .nth(i)
            .click();
          await page
            .getByRole('option', { name: /cancel/i })
            .first()
            .click();
        }
        await row
          .getByRole('textbox', { name: /^text in english$/i })
          .nth(i)
          .fill(REFERENCE_TEXTS[i]);
      }
      await closeRow(main, 'References');
    });

    await test.step('three parts: details, details and a note', async () => {
      // Part 1: three event dates (timed, all day, open-ended), positions, subject, description.
      const first = parts(page).nth(0);
      await addEventDate(page, first, dates.timed.from, dates.timed.to, false);
      await addEventDate(page, first, dates.allDay.from, dates.allDay.to, true);
      await addEventDate(page, first, dates.open.from, undefined, true);
      await closeRow(first, 'Event dates');
      await typePositions(page, first, POSITIONS_1);
      await (await openRow(first, 'Subject'))
        .getByRole('textbox', { name: /^text in english$/i })
        .fill(SUBJECT_1);
      await closeRow(first, 'Subject');
      await typeRich(
        (await openRow(first, 'Description')).getByRole('textbox', {
          name: /^description in english$/i,
        }),
        DETAILS_1,
      );
      await closeRow(first, 'Description');

      // Part 2: positions west of Greenwich, subject, description.
      await main.getByRole('button', { name: /^add part$/i }).click();
      await expect(parts(page)).toHaveCount(2);
      const second = parts(page).nth(1);
      await typePositions(page, second, POSITIONS_2);
      await (await openRow(second, 'Subject'))
        .getByRole('textbox', { name: /^text in english$/i })
        .fill(SUBJECT_2);
      await closeRow(second, 'Subject');
      await typeRich(
        (await openRow(second, 'Description')).getByRole('textbox', {
          name: /^description in english$/i,
        }),
        DETAILS_2,
      );
      await closeRow(second, 'Description');

      // Part 3: a note, which has only a description.
      await main.getByRole('button', { name: /^add part$/i }).click();
      await expect(parts(page)).toHaveCount(3);
      const third = parts(page).nth(2);
      await third
        .getByRole('group')
        .first()
        .getByRole('button', { name: /^note$/i })
        .click();
      await typeRich(
        (await openRow(third, 'Description')).getByRole('textbox', {
          name: /^description in english$/i,
        }),
        NOTE,
      );
      await closeRow(third, 'Description');
    });

    await test.step('three charts out of order', async () => {
      const row = await openRow(main, 'Charts');
      for (const number of chartNumbers) {
        await row.getByRole('combobox', { name: /^select charts$/i }).fill(number);
        await page
          .getByRole('option', { name: new RegExp(`^${number}\\D`) })
          .first()
          .click();
      }
      await closeRow(main, 'Charts');
    });

    await test.step('two attachments picked together, with captions and a display', async () => {
      uploads = [
        { name: `${RUN}-sketch.png`, bytes: await drawnPng(page, `${RUN} sketch`, '#9cf') },
        {
          name: `${RUN}-notes.txt`,
          bytes: Buffer.from(`${RUN} survey notes\nDepth 4.2 m at the works area.\n`, 'utf8'),
        },
      ];
      const row = await openRow(main, 'Attachments');
      // One pick of two files, through the upload control's own file input (the browser's file
      // chooser is not driven headless).
      await row.locator('input[type="file"]').setInputFiles(
        uploads.map((file) => ({
          name: file.name,
          mimeType: file.name.endsWith('.png') ? 'image/png' : 'text/plain',
          buffer: file.bytes as never,
        })),
      );
      const lines = row.locator('tbody').getByRole('row');
      await expect(lines).toHaveCount(2);
      // Files picked together are listed in the order picked.
      await expect(lines.nth(0)).toContainText(uploads[0].name);
      await expect(lines.nth(1)).toContainText(uploads[1].name);
      for (const [i, caption] of CAPTIONS.entries()) {
        await lines
          .nth(i)
          .getByRole('textbox', { name: /^text in english$/i })
          .fill(caption);
      }
      await lines
        .nth(0)
        .getByRole('combobox', { name: /^display$/i })
        .selectOption('BELOW');
      await closeRow(main, 'Attachments');
    });

    await test.step('external and internal publication, and the source', async () => {
      const publication = await openRow(main, 'Publication');
      await typeRich(
        publication.getByRole('textbox', { name: /^external publication in english$/i }),
        PUBLICATION,
      );
      await typeRich(
        publication.getByRole('textbox', { name: /^internal publication in english$/i }),
        INTERNAL_PUBLICATION,
      );
      await closeRow(main, 'Publication');
      const source = await openRow(main, 'Source');
      await source.getByRole('textbox', { name: /^text in english$/i }).fill(SOURCE);
      await closeRow(main, 'Source');
    });

    await test.step('the live preview holds what was put in', async () => {
      const preview = page.locator('app-live-preview article');
      for (const text of [VICINITY, DETAILS_1, DETAILS_2, NOTE, PUBLICATION, SOURCE]) {
        await expect(preview).toContainText(text);
      }
      for (const reference of references) await expect(preview).toContainText(reference.shortId);
    });

    await test.step('the notice is saved', async () => {
      await page.getByRole('button', { name: /^save$/i }).click();
      await page.waitForURL(/\/editor\/edit\/[^/?#]+/);
      await expect(page.getByText(/all changes saved/i)).toBeVisible();
      messageId = /\/editor\/edit\/([^/?#]+)/.exec(page.url())?.[1] ?? '';
      expect(messageId).not.toBe('');
    });

    await test.step('the form and the admin API hold the same notice, every list in order', async () => {
      const message = await expectEditorMatchesApi(page, api);
      expect(message.status).toBe('DRAFT');
      expect(english(message.descs, 'vicinity')).toBe(VICINITY);
      expectSameList(
        'stored areas',
        (message.areas ?? []).map((area) => english(area.descs, 'name')),
        areaNames,
      );
      expectSameList(
        'stored charts',
        (message.charts ?? []).map((chart) => chart.chartNumber),
        chartNumbers,
      );
      expectSameList(
        'stored references',
        (message.references ?? []).map((reference) => `${reference.messageId} ${reference.type}`),
        [`${references[0].shortId} REFERENCE`, `${references[1].shortId} CANCELLATION`],
      );
      expectSameList(
        'stored parts',
        (message.parts ?? []).map((part) => part.type),
        ['DETAILS', 'DETAILS', 'NOTE'],
      );
      expectSameList('stored event dates of part 1', heldEventDates(message.parts?.[0]), [
        `timed ${clockText(dates.timed.from, false)} - ${clockText(dates.timed.to, false)}`,
        `all day ${clockText(dates.allDay.from, true)} - ${clockText(dates.allDay.to, true)}`,
        `all day ${clockText(dates.open.from, true)} - `,
      ]);
      expect(
        new Date(message.parts?.[0]?.eventDates?.[0]?.fromDate ?? 0).toISOString(),
        'the timed date is the instant of its wall clock in the domain zone',
      ).toBe(instantAt(dates.timed.from, zone).toISOString());
      expectSameList(
        'stored attachments, in the order picked',
        (message.attachments ?? []).map(
          (attachment) => `${attachment.fileName} ${english(attachment.descs, 'caption')}`,
        ),
        uploads.map((file, i) => `${file.name} ${CAPTIONS[i]}`),
      );
      expectSame('stored publication', english(message.descs, 'publication'), PUBLICATION);
      expectSame(
        'stored internal publication',
        english(message.descs, 'internalPublication'),
        INTERNAL_PUBLICATION,
      );
      await expectAttachmentBytes(api, message);
    });

    await test.step('after a reload the form shows the same', async () => {
      await page.reload();
      await expect(page.getByText(/all changes saved/i)).toBeVisible();
      await expectEditorMatchesApi(page, api);
    });
  });

  test('parts are moved, removed and added, positions reordered, an attachment replaced', async ({
    editorPage: page,
  }) => {
    test.skip(!messageId, 'no notice was saved (an earlier test skipped or failed)');
    const api = new AdminApi(page, DOMAIN);
    const main = page.locator('main');
    await page.goto(`/editor/edit/${messageId}`);
    await expect(page.getByText(/all changes saved/i)).toBeVisible();

    await test.step('part 2 moves up; the note, now last, is removed; a Time part is added', async () => {
      await parts(page)
        .nth(1)
        .getByRole('button', { name: /^move part up$/i })
        .click();
      await expect(parts(page).nth(0)).toContainText(SUBJECT_2);
      await parts(page)
        .nth(2)
        .getByRole('button', { name: /^remove part$/i })
        .click();
      await expect(parts(page)).toHaveCount(2);
      await main.getByRole('button', { name: /^add part$/i }).click();
      await expect(parts(page)).toHaveCount(3);
      const time = parts(page).nth(2);
      await time
        .getByRole('group')
        .first()
        .getByRole('button', { name: /^time$/i })
        .click();
      await addEventDate(page, time, dates.time.from, undefined, true);
      await closeRow(time, 'Event dates');
      await typeRich(
        (await openRow(time, 'Description')).getByRole('textbox', {
          name: /^description in english$/i,
        }),
        TIME_TEXT,
      );
      await closeRow(time, 'Description');
    });

    await test.step('the buoys part lists its positions in a new order', async () => {
      await typePositions(page, parts(page).nth(1), POSITIONS_1_REORDERED);
    });

    await test.step('the sketch is removed and a replacement uploaded', async () => {
      const replacement = {
        name: `${RUN}-replacement.png`,
        bytes: await drawnPng(page, `${RUN} replacement`, '#fc9'),
      };
      uploads = [...uploads, replacement];
      const row = await openRow(main, 'Attachments');
      const lines = row.locator('tbody').getByRole('row');
      await lines
        .filter({ hasText: uploads[0].name })
        .getByRole('button', { name: /^delete attachment$/i })
        .click();
      await expect(lines).toHaveCount(1);
      await row.locator('input[type="file"]').setInputFiles({
        name: replacement.name,
        mimeType: 'image/png',
        buffer: replacement.bytes as never,
      });
      await expect(lines).toHaveCount(2);
      await expect(lines.nth(1)).toContainText(replacement.name);
      await lines
        .nth(1)
        .getByRole('textbox', { name: /^text in english$/i })
        .fill(REPLACEMENT_CAPTION);
      await lines
        .nth(1)
        .getByRole('combobox', { name: /^display$/i })
        .selectOption('ABOVE');
      await closeRow(main, 'Attachments');
    });

    await test.step('saved; the form and the admin API hold the new order', async () => {
      await page.getByRole('button', { name: /^save$/i }).click();
      await expect(page.getByText(/all changes saved/i)).toBeVisible();
      const message = await expectEditorMatchesApi(page, api);
      expectSameList(
        'stored parts',
        (message.parts ?? []).map((part) => `${part.type} ${english(part.descs, 'subject')}`),
        [`DETAILS ${SUBJECT_2}`, `DETAILS ${SUBJECT_1}`, 'TIME '],
      );
      expectSameList(
        'stored event dates of the buoys part, kept through the move',
        heldEventDates(message.parts?.[1]),
        [
          `timed ${clockText(dates.timed.from, false)} - ${clockText(dates.timed.to, false)}`,
          `all day ${clockText(dates.allDay.from, true)} - ${clockText(dates.allDay.to, true)}`,
          `all day ${clockText(dates.open.from, true)} - `,
        ],
      );
      expectSameList('stored event dates of the Time part', heldEventDates(message.parts?.[2]), [
        `all day ${clockText(dates.time.from, true)} - `,
      ]);
      expect(
        namesOf(message.parts?.[1]?.geometry?.features ?? []),
        'the vertex names moved with their vertices',
      ).toBe(
        `${RUN} cable — ${RUN} cable east end — ${RUN} cable west end ${RUN} buoy B ${RUN} buoy A ${RUN} works area`,
      );
      expectSameList(
        'stored attachments',
        (message.attachments ?? []).map(
          (attachment) =>
            `${attachment.fileName} ${english(attachment.descs, 'caption')} ${attachment.display ?? ''}`,
        ),
        [`${uploads[1].name} ${CAPTIONS[1]} `, `${uploads[2].name} ${REPLACEMENT_CAPTION} ABOVE`],
      );
      await expectAttachmentBytes(api, message);
    });

    await test.step('after a reload the form shows the same', async () => {
      await page.reload();
      await expect(page.getByText(/all changes saved/i)).toBeVisible();
      await expectEditorMatchesApi(page, api);
    });
  });

  test('the notice is verified and published, and read everywhere it goes', async ({
    editorPage: page,
    request,
  }) => {
    test.skip(!messageId, 'no notice was saved (an earlier test skipped or failed)');
    const api = new AdminApi(page, DOMAIN);
    const publicApi = new PublicApi(request);

    await test.step('Verify, then Publish', async () => {
      await page.goto(`/editor/status/${messageId}`);
      await page.getByRole('button', { name: /^verify message/i }).click();
      await page
        .getByRole('alertdialog')
        .getByRole('button', { name: /^verify$/i })
        .click();
      await expect(page.getByText(/status:\s*verified/i)).toBeVisible();
      expect((await api.get<NoticeVo>(`/rest/messages/message/${messageId}`)).status).toBe(
        'VERIFIED',
      );
      await page.getByRole('button', { name: /^publish message/i }).click();
      const dialog = page.getByRole('dialog', { name: /confirm publication/i });
      await dialog.getByRole('button', { name: /^publish$/i }).click();
      await expect(page.getByText(/status:\s*published/i)).toBeVisible();
    });

    let admin: NoticeVo | undefined;
    await test.step('the Status page and both APIs hold the published notice', async () => {
      const message = await api.get<NoticeVo>(`/rest/messages/editable-message/${messageId}`, {
        lang: 'en',
      });
      shortId = message.shortId ?? '';
      expect(shortId).toMatch(/^NM-\d{3,}-\d{2}$/);
      const article = page.locator('main article').first();
      await expect(article).toContainText(shortId);
      expectShows('status page title', await article.innerText(), english(message.descs, 'title'));
      admin = await expectPublicMatchesAdmin(api, publicApi);
      await expectInPublicList(publicApi);
      // Publishing does not touch the notices it refers to: nothing asked to cancel them.
      for (const reference of references) {
        const referred = await publicApi.get<NoticeVo>(
          `/rest/public/v1/message/${reference.shortId}`,
          { lang: 'en' },
        );
        expect(referred.status, `${reference.shortId} stays published`).toBe('PUBLISHED');
      }
    });

    await test.step('the PDF holds it', async () => {
      if (admin) await expectPdf(publicApi, admin);
    });

    await test.step('the public site shows it', async () => {
      if (admin) await expectPublicSite(page, admin);
    });
  });

  test('after a restart of the backend everything reads the same', async ({
    editorPage: page,
    request,
  }) => {
    test.skip(!shortId, 'no notice was published (an earlier test skipped or failed)');
    const api = new AdminApi(page, DOMAIN);
    const publicApi = new PublicApi(request);

    const readBack = async (): Promise<{ admin: unknown; files: string[] }> => {
      const message = await api.get<NoticeVo>(`/rest/messages/editable-message/${messageId}`, {
        lang: 'en',
      });
      return { admin: stable(message), files: await expectAttachmentBytes(api, message) };
    };
    beforeRestart = await readBack();

    await restartBackend(request);

    await test.step('the admin API holds the same notice, and the same files', async () => {
      expect(await readBack()).toEqual(beforeRestart);
    });

    await test.step('the form shows the same after a reload', async () => {
      await page.goto(`/editor/edit/${messageId}`);
      await expect(page.locator('main article').first()).toContainText(shortId);
      // A published notice opens read-only; its details hold every part.
      const message = await api.get<NoticeVo>(`/rest/messages/editable-message/${messageId}`, {
        lang: 'en',
      });
      const article = page.locator('main article').first();
      expectShows(
        'title after the restart',
        await article.innerText(),
        english(message.descs, 'title'),
      );
      for (const part of message.parts ?? []) {
        await expect(article).toContainText(normalise(english(part.descs, 'details')));
      }
      // The rest of what the notice shows: its references, its publication and its source.
      for (const reference of references) await expect(article).toContainText(reference.shortId);
      await expect(article).toContainText(PUBLICATION);
      await expect(article).toContainText(SOURCE);
    });

    await test.step('the public answers, the PDF and the public site are unchanged', async () => {
      const admin = await expectPublicMatchesAdmin(api, publicApi);
      await expectInPublicList(publicApi);
      await expectPdf(publicApi, admin);
      await expectPublicSite(page, admin);
    });
  });

  test('a user without the editor role reads the notice but cannot change it', async ({
    userPage: page,
  }) => {
    test.skip(!shortId, 'no notice was published (an earlier test skipped or failed)');
    await page.goto(`/editor/status/${messageId}`);
    await expect(page.locator('main article').first()).toContainText(shortId);
    await expect(page.getByText(/status:\s*published/i)).toBeVisible();
    await expect(page.getByRole('button', { name: /^cancel message/i })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /^copy message/i })).toHaveCount(0);
  });
});

/** Replaces a part's positions through Edit as text. */
async function typePositions(page: Page, part: Locator, text: string): Promise<void> {
  const row = await openRow(part, 'Positions');
  const hadPositions = (await row.locator('.ed-coords').count()) > 0;
  await row.getByRole('button', { name: /^edit as text$/i }).click();
  const dialog = page.getByRole('dialog', { name: /edit as text/i });
  const box = dialog.getByRole('textbox');
  // The dialog asks the backend to write the part's positions as text; typing before that answer
  // arrives would be overwritten by it.
  if (hadPositions) await expect(box).not.toHaveValue('');
  await box.fill(text);
  const save = dialog.getByRole('button', { name: /save geometry/i });
  // The backend parses the text as it is typed; Save opens once it has.
  await expect(save).toBeEnabled();
  await save.click();
  await expect(dialog).toBeHidden();
  await closeRow(part, 'Positions');
}

function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
