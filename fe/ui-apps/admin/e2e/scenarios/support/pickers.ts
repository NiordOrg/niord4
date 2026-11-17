import type { Locator, Page } from '@playwright/test';

import { expect } from '../../auth/fixtures';
import { normalise } from './compare';
import { monthName, type WallClock } from './zoned-time';

/*
 * The date and time controls of the administration app, picked as a person picks them. Both open
 * a popover in the overlay container, so the popover is looked for on the whole page.
 */

/** The months from year 0 to the month a calendar header names ("November 2026"), or NaN. */
function monthIndex(header: string): number {
  const [name, year] = header.split(' ');
  for (let month = 1; month <= 12; month++) {
    if (monthName({ month }) === name) return Number(year) * 12 + month;
  }
  return Number.NaN;
}

/** Opens a date control's calendar and picks the clock's day, paging months as needed. */
export async function pickDay(page: Page, trigger: Locator, clock: WallClock): Promise<void> {
  await trigger.click();
  const calendar = page.locator('app-calendar');
  await expect(calendar).toBeVisible();
  // The header button names the month shown; page until it names the clock's month.
  const header = calendar.getByRole('button', { name: /^\S+ \d{4},/ });
  const wanted = `${monthName(clock)} ${clock.year}`;
  for (let month = 0; month < 24; month++) {
    const shown = normalise(await header.innerText());
    if (shown === wanted) break;
    const back = monthIndex(shown) > clock.year * 12 + clock.month;
    await calendar.getByRole('button', { name: back ? /previous month/i : /next month/i }).click();
    // Wait for the new month: a read straight after the click races the re-render.
    await expect(header).not.toHaveText(shown);
  }
  await expect(header).toHaveText(wanted);
  await calendar.getByRole('gridcell', { name: new RegExp(`\\b${clock.day} ${wanted}$`) }).click();
  await expect(calendar).toBeHidden();
}

/**
 * Opens a time control and picks the clock's hour and minute. In a 12-hour locale the hour is
 * picked before AM or PM: AM and PM do nothing while the field is empty. Midnight is 12 AM.
 */
export async function pickTime(page: Page, trigger: Locator, clock: WallClock): Promise<void> {
  await trigger.click();
  const hours = page.getByRole('group', { name: /^hours$/i });
  await expect(hours).toBeVisible();
  const twelveHour = (await page.getByRole('button', { name: 'PM', exact: true }).count()) > 0;
  const hourLabel = twelveHour ? String(clock.hour % 12 === 0 ? 12 : clock.hour % 12) : clock.hour;
  await hours.getByRole('button', { name: new RegExp(`^0?${hourLabel} `) }).click();
  if (twelveHour) {
    await page.getByRole('button', { name: clock.hour < 12 ? 'AM' : 'PM', exact: true }).click();
  }
  await page
    .getByRole('group', { name: /^minutes$/i })
    .getByRole('button', { name: new RegExp(`^${String(clock.minute).padStart(2, '0')} `) })
    .click();
  await page.keyboard.press('Escape');
  await expect(hours).toBeHidden();
}
