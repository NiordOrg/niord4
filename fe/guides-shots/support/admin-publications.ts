import { expect, type Locator, type Page } from '@playwright/test';

import { psql } from './backend';

/*
 * The admin manual's publication pictures: which series and issues of the copy they show, read
 * through psql in the database container (read only), and the few steps several pictures share.
 * Nothing here writes to the copy: a dialog or a drawer is photographed filled in and closed again.
 */

/** The domain each example series is administered in. */
export const NM = 'niord-nm';
export const ANNEX = 'niord-annex';
/** The example series: a weekly NtM, its in-force sister, the yearly compilation. */
export const WEEKLY = 'weekly-ntm';
export const IN_FORCE = 'weekly-ntm-p-t';
export const COMPILED = 'accumulated-yearly-ntm';

/**
 * The seeded weekly series of the NM domain with no issue (data/seed-examples.mjs): its first
 * period opened a week or more before the seed ran, so its page has a Missing row with Create and
 * an Upcoming row.
 */
export const EXAMPLE_WEEKLY = 'guide-example-weekly';

/** The one-off with an uploaded file and a public period, and one cited by reference only. */
export const ONE_OFF_FILE = 'navigation-through-danish-waters';

function quoted(value: string): string {
  if (!/^[\w-]+$/.test(value)) throw new Error(`Not a series id: ${value}`);
  return `'${value}'`;
}

/**
 * The public id of a series' issue in a status, the latest by its cut-off: the same one on every
 * run of the same data. `where` adds a condition on the issue (`i`).
 */
export function issueId(seriesId: string, status: string, where = 'true'): string {
  if (!/^[A-Z]+$/.test(status)) throw new Error(`Not an issue status: ${status}`);
  const id = psql(
    'SELECT i."publicId" FROM "PublicationIssue" i JOIN "PublicationSeries" s ON s.id = i.series_id ' +
      `WHERE s."seriesId" = ${quoted(seriesId)} AND i.status = '${status}' AND (${where}) ` +
      'ORDER BY i."intervalTo" DESC NULLS LAST, i.id DESC LIMIT 1',
    `the ${status} issue of ${seriesId}`,
  ).trim();
  if (!id) throw new Error(`The series ${seriesId} has no ${status} issue that fits: ${where}`);
  return id;
}

/**
 * A published issue released in Niord itself (not carried over from the old system): week 36 of
 * the weekly NtM, which the seed amended once (data/seed-examples.mjs), so its history holds the
 * replaced document.
 */
export const RELEASED_HERE = 'i."legacyPublicationId" IS NULL AND i."memberCount" >= 5';

/** The address of an issue's page. */
export function issuePath(seriesId: string, publicId: string): string {
  return `/admin/publications/${seriesId}?issue=${encodeURIComponent(publicId)}`;
}

/** Waits until an issue's page has drawn its checklist (the last card to answer). */
export async function issueLoaded(page: Page): Promise<void> {
  await expect(page.getByText('Checklist', { exact: true }).first()).toBeVisible();
  await expect(page.getByRole('heading', { level: 2 }).first()).toBeVisible();
}

/** The card (an `app-card` or an `.admin-card`) a heading or a label sits in. */
export function cardOf(label: Locator): Locator {
  return label.locator('xpath=ancestor::*[contains(@class, "admin-card") or self::app-card][1]');
}

/** Opens the series' settings drawer from its page, on a tab. */
export async function openSettings(page: Page, seriesId: string, tab: string): Promise<Locator> {
  await page.goto(`/admin/publications/${seriesId}`);
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  const drawer = page.getByRole('dialog', { name: 'Series settings' });
  await expect(drawer).toBeVisible();
  await drawer.getByRole('tab', { name: tab, exact: true }).click();
  return drawer;
}

/** The tab list and the open tab of a drawer: the part of it a tab's picture is about. */
export function openTab(drawer: Locator): readonly Locator[] {
  return [drawer.getByRole('tablist'), drawer.locator('[role="tabpanel"]:visible')];
}
