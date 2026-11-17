import { expect, type APIRequestContext, type Locator, type Page } from '@playwright/test';

import { authHeaders } from './backend';
import { BACKEND_URL } from './env';
import { loginAs } from './login';

/*
 * Helpers of the sysadmin manual's first half (Sysadmin overview, Message Series, Domains,
 * Promulgation Types, Firing Schedules, Dictionaries, Parameter Types).
 */

/**
 * Signs in as the sysadmin and opens a section of the Sysadmin area, e.g. `series`, waiting for
 * its list: a table's first row, or the overview's cards.
 */
export async function openSysadminSection(page: Page, path: string): Promise<void> {
  await loginAs(page, 'sysadmin');
  await page.goto(`/sysadmin/${path}`);
  await expect(page.locator('app-admin-section-page table tbody tr').first()).toBeVisible();
}

/** The row of a list table whose first cells hold the text. */
export function rowWith(page: Page, text: string | RegExp): Locator {
  return page
    .locator('app-admin-section-page table tbody tr')
    .filter({ has: page.getByRole('cell', { name: text, exact: typeof text === 'string' }) });
}

/** The open editor drawer, by its header. */
export function drawer(page: Page, header: string): Locator {
  return page.getByRole('dialog', { name: header });
}

/**
 * Signs in as the editor and opens the Execute templates page on one NW template, the way an
 * editor gets there: Editor, Select NW template…, the template picked from the search. Nothing is
 * executed.
 */
export async function openTemplate(page: Page, name: string): Promise<void> {
  await loginAs(page, 'editor');
  await page.goto('/editor');
  await page.getByRole('button', { name: 'Select NW template…' }).click();
  await page.getByPlaceholder('Search templates…').fill(name);
  await page.getByText(name, { exact: true }).last().click();
  await expect(page.getByText('Execute templates', { exact: true })).toBeVisible();
  await expect(page.locator('app-template-execute-fields')).toBeVisible();
}

/*
 * The example promulgation types of the pictures. The copy's source has no SafetyNET type, and its
 * Twitter type's settings cannot be read (the backend answers 500 for a type that has settings), so
 * the dialogs of these two services are photographed on example types of their own, recognisable
 * by their name: inactive and in no domain, so no editor ever sees them. The copy's prepare.sql
 * makes them, the SafetyNET type with its two areas; on a copy without them, each is made once
 * through the REST interface the application uses, and found again on every later run.
 */
export const EXAMPLE_SAFETYNET = {
  typeId: 'guide-example-safetynet',
  serviceId: 'safetynet',
  name: 'Guide example SafetyNET',
} as const;

export const EXAMPLE_TWITTER = {
  typeId: 'guide-example-twitter',
  serviceId: 'twitter',
  name: 'Guide example Twitter',
} as const;

const EXAMPLE_SAFETYNET_AREAS = [
  {
    type: 'NAVAREA',
    name: 'Guide example NAVAREA I',
    active: true,
    sortOrder: 1,
    repetition: 'REPETITION_11',
    navarea: 'I',
  },
  {
    type: 'CIRCULAR',
    name: 'Guide example circle',
    active: true,
    sortOrder: 2,
    repetition: 'REPETITION_01',
    messageAreaPrefix: 'SKAGERRAK',
    // "Lat Lon" in whole degrees, the form the Areas dialog accepts.
    center: '57 10',
    radius: '25',
  },
] as const;

async function ok(response: Awaited<ReturnType<APIRequestContext['get']>>): Promise<void> {
  if (!response.ok()) {
    throw new Error(`${response.url()} answered ${response.status()} ${await response.text()}`);
  }
}

/** Makes an example promulgation type once (find before make); returns nothing. */
async function ensureType(
  request: APIRequestContext,
  type: { typeId: string; serviceId: string; name: string },
): Promise<boolean> {
  const headers = await authHeaders('sysadmin');
  const all = await request.get(`${BACKEND_URL}/rest/promulgations/promulgation-types/all`, {
    headers,
  });
  await ok(all);
  const types = (await all.json()) as readonly { typeId: string }[];
  if (types.some((existing) => existing.typeId === type.typeId)) return false;
  await ok(
    await request.post(`${BACKEND_URL}/rest/promulgations/promulgation-type/`, {
      headers,
      data: {
        ...type,
        active: false,
        requirement: 'OPTIONAL',
        priority: 900,
        language: 'en',
        domains: [],
        messageTypes: [],
        scriptResourcePaths: [],
      },
    }),
  );
  return true;
}

/** The example SafetyNET type and its two areas, made once. */
export async function ensureSafetyNetExample(request: APIRequestContext): Promise<void> {
  await ensureType(request, EXAMPLE_SAFETYNET);
  const headers = await authHeaders('sysadmin');
  const base = `${BACKEND_URL}/rest/promulgation/safetynet/areas/${EXAMPLE_SAFETYNET.typeId}`;
  const listed = await request.get(`${base}/all`, { headers });
  await ok(listed);
  const names = new Set(((await listed.json()) as readonly { name: string }[]).map((a) => a.name));
  for (const area of EXAMPLE_SAFETYNET_AREAS) {
    if (names.has(area.name)) continue;
    await ok(
      await request.post(`${base}/area/`, {
        headers,
        data: { ...area, promulgationType: { typeId: EXAMPLE_SAFETYNET.typeId } },
      }),
    );
  }
}

/** The example Twitter type, made once, without settings. */
export async function ensureTwitterExample(request: APIRequestContext): Promise<void> {
  await ensureType(request, EXAMPLE_TWITTER);
}
