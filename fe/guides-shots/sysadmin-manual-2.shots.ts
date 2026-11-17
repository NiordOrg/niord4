import type { APIRequestContext, Locator, Page } from '@playwright/test';

import { authHeaders, publishedMessageId } from './support/backend';
import { BACKEND_URL, DOMAIN, NOW } from './support/env';
import { expect, test } from './support/fixtures';
import { loginAs } from './support/login';
import { sendMessageMail } from './support/mailhog';
import { openPdfFirstPage } from './support/pdf';
import { shot } from './support/shot';
import {
  BATCH_FILES,
  GUIDE_INTEGRATION,
  SCRIPT_JOB,
  SERIES_IDS_SCRIPT,
  completedSeriesIdsRun,
  guideIntegrationId,
  releaseScriptResources,
  retimed,
  runningWaitRun,
  stopRun,
  stoppedWaitRun,
  type BatchRun,
} from './support/sysadmin-2';

/*
 * The sysadmin manual's pictures of its second half (Script Resources to Batch Jobs), written to
 * guides/sysadmin-manual/images/: the sysadmin pages as a sysadmin sees them. The Keycloak console
 * of the appendix has its own spec (keycloak.shots.ts).
 *
 * One test per picture, titled `<manual>/<file>` after the file it writes, so
 * `--grep "<manual>/<file>"` takes one picture again and manifest-check.mjs reads the list from the
 * titles. Nothing is saved: a drawer or a dialog is photographed open and closed unsaved. Where the
 * copy lacks what a picture needs, the test makes one example row, found before it is made (the
 * inactive Niord Integration example, a mail to the rig's own address), or it skips and says why.
 * The Batch Jobs pictures run scripts of their own that read or wait only (support/sysadmin-2.ts).
 *
 *   npx playwright test -c guides-shots/playwright.config.ts sysadmin-manual-2.shots.ts
 */

/**
 * A template that holds no second-language text and no address, short enough to show whole in the
 * editor, with revisions to compare (three in the copy).
 */
const SHORT_TEMPLATE = 'templates/tmpl/navtex.ftl';

/**
 * A template whose revisions record their source: the copy's one script resource with a revision
 * the import wrote (source Import) beside an older one made before the source was recorded. Its
 * text is English and holds no address.
 */
const TEMPLATE_WITH_SOURCES = 'templates/messages/nm-accumulated-report-pdf.ftl';

/** The subject of the mail the rig sends itself, so that the mail queue has a row to open. */
const GUIDE_MAIL_SUBJECT = 'Navigational warnings (guides)';

/**
 * What the pictures type in the Subject filter: a part of the subject that only the rig's mail has
 * (the filter matches a part), short enough to show whole in the field.
 */
const GUIDE_MAIL_FILTER = 'warnings (guides)';

/** The drawer or dialog with the given heading. */
function dialogNamed(page: Page, name: string | RegExp): Locator {
  return page.getByRole('dialog', { name });
}

/** Opens a Freemarker template in the Script Resources editor by its deep link. */
async function openScriptResource(page: Page, path: string): Promise<Locator> {
  await loginAs(page, 'sysadmin');
  await page.goto(`/sysadmin/script-resources?path=${encodeURIComponent(path)}`);
  const drawer = dialogNamed(page, 'Edit Script Resource');
  await expect(drawer).toBeVisible();
  await expect(drawer.getByRole('textbox', { name: 'Path' })).toHaveValue(path);
  return drawer;
}

/** Opens a category of the tree in the Categories editor: its parents' names, then its own. */
async function openCategory(
  page: Page,
  parents: readonly string[],
  name: string,
  domain: string = DOMAIN,
): Promise<Locator> {
  await loginAs(page, 'sysadmin', domain);
  await page.goto('/sysadmin/categories');
  const tree = page.getByRole('tree');
  for (const parent of parents) {
    await tree
      .getByRole('treeitem', { name: `Expand ${parent}`, exact: true })
      .getByRole('button', { name: 'Expand' })
      .click();
  }
  await tree.getByRole('button', { name: new RegExp(`^${name}( Template)?$`) }).click();
  const editor = page.locator('app-category-editor');
  await expect(editor.getByRole('heading', { name: 'Edit category' })).toBeVisible();
  return editor;
}

/**
 * How long the queue may take to send the rig's own mail: Niord sends the pending mails once a
 * minute (ScheduledMailService.sendPendingMails, at second 24), so a new mail is sent within about
 * a minute; the rest is room for the send itself.
 */
const MAIL_SENT_TIMEOUT = 150_000;

/**
 * Makes sure the mail queue holds the rig's own mail, sending one when it has none, and waits until
 * the queue has sent it: the pictures show a sent mail, as the text describes it.
 */
async function ensureGuideMail(request: APIRequestContext): Promise<void> {
  const headers = await authHeaders('sysadmin');
  const statusOfGuideMail = async (): Promise<string | undefined> => {
    const response = await request.get(`${BACKEND_URL}/rest/scheduled-mails/search`, {
      params: { subject: GUIDE_MAIL_SUBJECT, maxSize: 1 },
      headers,
    });
    if (!response.ok()) throw new Error(`The mail queue answered ${response.status()}`);
    return ((await response.json()) as { data?: { status?: string }[] }).data?.[0]?.status;
  };
  if ((await statusOfGuideMail()) === undefined) {
    await sendMessageMail(request, await publishedMessageId(request), GUIDE_MAIL_SUBJECT);
  }
  await expect
    .poll(statusOfGuideMail, {
      timeout: MAIL_SENT_TIMEOUT,
      intervals: [2_000, 5_000],
      message: `the status of the mail "${GUIDE_MAIL_SUBJECT}" (the queue sends once a minute)`,
    })
    .toBe('SENT');
}

/**
 * Shows the rig's own mail at the rig's fixed time, in the list and in its details, as a PDF
 * page's "Generated" line is: it was put in the queue when the copy first ran this spec, and the
 * pictures are the same on every copy. Only that mail's times are rewritten, both moved by one
 * amount so that it was put in the queue at GUIDES_NOW, in the browser; nothing is saved. The
 * backend writes a time as epoch milliseconds or as an ISO text: the same form is kept.
 */
async function showGuideMailAtNow(page: Page): Promise<void> {
  const fixed = Date.parse(NOW);
  interface Mail {
    subject?: string;
    created?: unknown;
    sendDate?: unknown;
  }
  const retime = (mail: Mail): void => {
    if (mail.subject !== GUIDE_MAIL_SUBJECT) return;
    const created =
      typeof mail.created === 'number'
        ? mail.created
        : typeof mail.created === 'string'
          ? Date.parse(mail.created)
          : NaN;
    if (!Number.isFinite(created)) return;
    const shift = fixed - created;
    mail.created = retimed(mail.created, (millis) => millis + shift);
    mail.sendDate = retimed(mail.sendDate, (millis) => millis + shift);
  };
  await page.route('**/rest/scheduled-mails/search**', async (route) => {
    const response = await route.fetch();
    const body = (await response.json()) as { data?: Mail[] };
    (body.data ?? []).forEach(retime);
    await route.fulfill({ response, json: body });
  });
  await page.route('**/rest/scheduled-mails/scheduled-mail/*', async (route) => {
    const response = await route.fetch();
    const body = (await response.json()) as Mail;
    retime(body);
    await route.fulfill({ response, json: body });
  });
}

/** Opens the template execution page of a template, as an editor of the session domain. */
async function openTemplateExecution(page: Page, template: string): Promise<void> {
  await loginAs(page, 'editor');
  await page.goto('/editor/execute-template');
  await page.getByRole('button', { name: 'Select NW template…' }).click();
  await page.getByRole('option', { name: new RegExp(`^${template} `) }).click();
}

/**
 * Shows the batch runs' times at the rig's fixed clock, as the Mail Queue picture does: every
 * start and end time of the list is moved by one amount, so that the oldest run starts at
 * GUIDES_NOW and the runs keep their order and durations. The oldest run is the same in every
 * picture (the reading script's, on a new copy), so the three pictures show the same times for it.
 * In the browser only; nothing is saved.
 */
async function showBatchTimesAtNow(page: Page): Promise<void> {
  const fixed = Date.parse(NOW);
  await page.route(`**/rest/batch/${SCRIPT_JOB}/instances**`, async (route) => {
    const response = await route.fetch();
    const body = (await response.json()) as {
      data?: { executions?: { startTime?: unknown; endTime?: unknown }[] }[];
    };
    const executions = (body.data ?? []).flatMap((run) => run.executions ?? []);
    const millisOf = (value: unknown): number =>
      typeof value === 'number' ? value : typeof value === 'string' ? Date.parse(value) : NaN;
    const starts = executions
      .map((execution) => millisOf(execution.startTime))
      .filter((value) => Number.isFinite(value));
    if (starts.length > 0) {
      const shift = fixed - Math.min(...starts);
      for (const execution of executions) {
        execution.startTime = retimed(execution.startTime, (millis) => millis + shift);
        execution.endTime = retimed(execution.endTime, (millis) => millis + shift);
      }
    }
    await route.fulfill({ response, json: body });
  });
}

/**
 * Shows the log files' time stamps at the rig's fixed clock (in the server's time zone, UTC), in
 * English: the server writes them in its own locale's format, which on a Danish-set workstation
 * names the month in Danish. In the browser only; nothing is saved.
 */
async function showLogTimesAtNow(page: Page): Promise<void> {
  const at = new Date(NOW);
  const hours = at.getUTCHours();
  const two = (value: number): string => String(value).padStart(2, '0');
  const month = at.toLocaleString('en-US', { month: 'short', timeZone: 'UTC' });
  const stamp =
    `${month} ${at.getUTCDate()}, ${at.getUTCFullYear()} ${((hours + 11) % 12) + 1}:` +
    `${two(at.getUTCMinutes())}:${two(at.getUTCSeconds())} ${hours < 12 ? 'AM' : 'PM'}`;
  await page.route('**/rest/batch/instance/*/logs/*', async (route) => {
    const response = await route.fetch();
    const text = (await response.text()).replace(
      /^\S+ \d{1,2}, \d{4} \d{1,2}:\d{2}:\d{2} \S+(?= )/gm,
      stamp,
    );
    await route.fulfill({ response, body: text });
  });
}

/** The header row of the newest run started with the data file. */
function runRow(page: Page, fileName: string): Locator {
  return page.locator('table tbody tr.batch-instance-row').filter({ hasText: fileName }).first();
}

/** The row of a run's newest execution: the row below the run's header. */
function executionRow(page: Page, run: BatchRun): Locator {
  return page
    .locator('table tbody tr.batch-instance-row')
    .filter({ hasText: new RegExp(`Job no\\. ${run.jobNo ?? ''}(?!\\d)`) })
    .locator('xpath=following-sibling::tr[1]');
}

test.describe('sysadmin-manual (second half)', () => {
  // ---------------------------------------------------------------- 09 Script Resources

  test('sysadmin-manual/ScriptResourcesPage.png', async ({ page }) => {
    await loginAs(page, 'sysadmin');
    await page.goto('/sysadmin/script-resources');
    await expect(page.locator('table tbody tr').first()).toBeVisible();
    await shot(page);
  });

  test('sysadmin-manual/ScriptResourcesAddBtn.png', async ({ page }) => {
    await loginAs(page, 'sysadmin');
    await page.goto('/sysadmin/script-resources');
    await expect(page.locator('table tbody tr').first()).toBeVisible();
    const button = page.getByRole('button', { name: 'Add Script Resource' });
    await button.click();
    const menu = page.getByRole('menu');
    await expect(menu.getByRole('menuitem', { name: 'JavaScript File' })).toBeVisible();
    await shot(page, { target: [button, menu] });
  });

  test('sysadmin-manual/ScriptResourceEditor.png', async ({ page }) => {
    const drawer = await openScriptResource(page, SHORT_TEMPLATE);
    await shot(page, { target: drawer, pad: 0 });
  });

  test('sysadmin-manual/ScriptResourceHistory.png', async ({ page }) => {
    const drawer = await openScriptResource(page, TEMPLATE_WITH_SOURCES);
    await drawer.getByRole('button', { name: 'History...' }).click();
    const dialog = dialogNamed(page, 'Revision History');
    const boxes = dialog.getByRole('checkbox', { name: 'Select revision' });
    await expect(boxes.nth(1)).toBeVisible();
    // The newest revision shows its source, the import.
    await expect(dialog.locator('tbody tr').first()).toContainText('Import');
    // The two newest revisions, compared.
    await boxes.nth(0).check();
    await boxes.nth(1).check();
    await expect(dialog.locator('app-code-merge-view')).toBeVisible();
    await shot(page, { target: dialog, pad: 0 });
  });

  test('sysadmin-manual/ScriptResourceImportPreview.png', async ({ page }) => {
    await loginAs(page, 'sysadmin');
    await page.goto('/sysadmin/script-resources');
    await expect(page.locator('table tbody tr').first()).toBeVisible();
    await page.getByRole('button', { name: 'Script resource actions' }).click();
    await page.getByRole('menuitem', { name: 'Import...' }).click();
    const dialog = dialogNamed(page, 'Upload Script Resources File');
    await expect(dialog).toBeVisible();
    // The preview only reads: nothing is imported until "Import ticked" is clicked, which it is not.
    await dialog.locator('#scriptResourceImportFile').setInputFiles(releaseScriptResources());
    await expect(dialog.getByRole('heading', { name: /^Would add/ })).toBeVisible();
    await shot(page, { target: dialog, pad: 0 });
  });

  // ---------------------------------------------------------------- 10 Categories

  test('sysadmin-manual/CategoriesPage.png', async ({ page }) => {
    await openCategory(page, ['Light buoy'], 'Light buoy');
    await shot(page);
  });

  // The template's editor is taller than the window: a taller window shows it whole. Below 1024 px
  // the page puts the editor under the tree, at the content's full width, so that the script
  // resource paths show whole in their fields.
  test.describe('in a tall window', () => {
    test.use({ viewport: { width: 1000, height: 2400 } });

    test('sysadmin-manual/CategoryEditorPanel.png', async ({ page }) => {
      const editor = await openCategory(page, ['Light buoy'], 'Light buoy unlit');
      await expect(editor.getByText('Script resources')).toBeVisible();
      await shot(page, { target: editor, pad: 0 });
    });
  });

  test('sysadmin-manual/DragHandle.png', async ({ page }) => {
    const editor = await openCategory(page, ['Light buoy'], 'Light buoy unlit');
    const field = editor.locator('app-script-resources-field');
    const handle = field.getByLabel('Reorder').first();
    await handle.scrollIntoViewIfNeeded();
    await shot(page, { target: handle, pad: 2 });
  });

  test('sysadmin-manual/CategoryTemplateResult.png', async ({ page }) => {
    // The copy's example messages are in the Legacy NW domain's series, and a template is tested
    // in the session domain, which must hold the example message's series. Test… sends no template
    // parameters, so a template that writes its details from them ("Light buoy unlit") shows an
    // empty Details; "Wreck" writes whole details without them.
    const editor = await openCategory(page, ['Obstruction'], 'Wreck', 'niord-legacy-nw');
    // Test… runs the template on the example message and shows the result; nothing is saved.
    await editor.getByRole('button', { name: 'Test…' }).click();
    const dialog = dialogNamed(page, 'Template Result');
    await expect(dialog).toContainText('has sunk');
    await shot(page, { target: dialog, pad: 0 });
  });

  test('sysadmin-manual/CategoryStdTemplateFields.png', async ({ page }) => {
    await openTemplateExecution(page, 'Light buoy unlit');
    const card = page
      .locator('app-section-panel, section')
      .filter({ hasText: 'Base fields' })
      .first();
    await expect(card).toBeVisible();
    await shot(page, { target: card });
  });

  // The card asks for the parameters once per position: one position is typed in. The form is
  // filled, never executed.
  test.describe('in a window tall enough for the parameter card', () => {
    test.use({ viewport: { width: 1280, height: 1100 } });

    test('sysadmin-manual/CategoryTemplateParams.png', async ({ page }) => {
      await openTemplateExecution(page, 'Light buoy unlit');
      const card = page
        .locator('app-section-panel, section')
        .filter({ hasText: 'Template parameters' })
        .first();
      await card.getByRole('button', { name: 'Edit as text' }).click();
      const text = dialogNamed(page, 'Edit as text');
      await text.getByRole('textbox', { name: 'Edit as text' }).fill("55° 41.0'N - 012° 36.0'E");
      await text.getByRole('button', { name: 'Save geometry' }).click();
      const buoy = card.getByRole('group', { name: 'Light buoy' });
      await expect(buoy.getByRole('combobox', { name: 'Light Buoy Type' })).toBeVisible();
      await shot(page, { target: card });
    });
  });

  // ---------------------------------------------------------------- 11 Print Reports

  test('sysadmin-manual/PrintReportsPage.png', async ({ page }) => {
    await loginAs(page, 'sysadmin');
    await page.goto('/sysadmin/reports');
    await expect(page.locator('table tbody tr').first()).toBeVisible();
    await shot(page);
  });

  // The drawer is as tall as the window: a taller window shows its test row too.
  test.describe('in a window tall enough for the report editor', () => {
    test.use({ viewport: { width: 1280, height: 1240 } });

    test('sysadmin-manual/PrintReportEditPage.png', async ({ page }) => {
      await loginAs(page, 'sysadmin');
      await page.goto('/sysadmin/reports');
      const row = page.locator('table tbody tr').filter({ hasText: 'nm-report' });
      await row.getByRole('button', { name: 'Edit Report' }).click();
      const drawer = dialogNamed(page, 'Edit Print Report');
      await expect(drawer.getByRole('textbox', { name: 'Name' })).toHaveValue('NM report');
      await shot(page, { target: drawer, pad: 0 });
    });
  });

  test('sysadmin-manual/PrintDialog.png', async ({ page }) => {
    await loginAs(page, 'editor', 'niord-nm');
    await page.goto('/messages/table');
    await expect(page.getByRole('row').nth(1)).toBeVisible();
    await page
      .getByRole('button', { name: /actions/i })
      .first()
      .click();
    await page.getByRole('menuitem', { name: /^Print/ }).click();
    const dialog = page.getByRole('dialog').first();
    await expect(dialog).toBeVisible();
    await dialog.getByRole('combobox', { name: 'Report' }).click();
    await page.getByRole('option', { name: 'NM report' }).click();
    // The report's parameters default to this week and year on the server's clock: the rig's
    // fixed day is written in instead (typed, never printed).
    await dialog.getByRole('textbox', { name: 'week' }).fill('46');
    await dialog.getByRole('textbox', { name: 'year' }).fill('2026');
    await dialog.getByRole('textbox', { name: 'year' }).blur();
    await shot(page, { target: dialog, pad: 0 });
  });

  test('sysadmin-manual/PrintReportExample.png', async ({ page }) => {
    // The base product's Standard report: its front page shows the list's search criteria and the
    // table of contents. (The copy's weekly NM report prints its dates the Danish way.)
    const query = new URLSearchParams({
      domain: DOMAIN,
      status: 'PUBLISHED',
      lang: 'en',
      report: 'standard',
    });
    const pdf = await openPdfFirstPage(page, {
      url: `${BACKEND_URL}/rest/message-reports/report.pdf?${query}`,
      headers: await authHeaders('editor'),
    });
    await shot(page, { target: pdf, pad: 0, maps: false, guard: { checks: 'external' } });
  });

  // ---------------------------------------------------------------- 12 Mail Queue

  test('sysadmin-manual/MailQueuePage.png', async ({ page }) => {
    test.setTimeout(MAIL_SENT_TIMEOUT + 120_000);
    await ensureGuideMail(page.request);
    await showGuideMailAtNow(page);
    await loginAs(page, 'sysadmin');
    await page.goto('/sysadmin/mails');
    // Only the rig's own mail: other writers' mails are not the copy's.
    const subject = page.getByRole('textbox', { name: 'Subject' });
    await subject.fill(GUIDE_MAIL_FILTER);
    await subject.blur();
    const rows = page.locator('table tbody tr');
    await expect(rows).toHaveCount(1);
    await expect(rows.first()).toContainText(GUIDE_MAIL_SUBJECT);
    await expect(rows.first()).toContainText('Sent');
    await shot(page);
  });

  test('sysadmin-manual/MailQueueDetails.png', async ({ page }) => {
    // The dialog shows the mail in a sandboxed frame. The rig's own start-up scripts, which
    // Playwright runs in every frame, fail there; the guard leaves out exactly those errors
    // (guard.ts) and still reads the mail's text.
    test.setTimeout(MAIL_SENT_TIMEOUT + 120_000);
    await ensureGuideMail(page.request);
    await showGuideMailAtNow(page);
    await loginAs(page, 'sysadmin');
    await page.goto('/sysadmin/mails');
    await page.getByRole('textbox', { name: 'Subject' }).fill(GUIDE_MAIL_FILTER);
    await page.getByRole('textbox', { name: 'Subject' }).blur();
    const row = page.locator('table tbody tr').filter({ hasText: GUIDE_MAIL_SUBJECT }).first();
    await row.getByRole('button', { name: 'Open mail details' }).click();
    const dialog = dialogNamed(page, /^Mail \d+ \(Sent\)/);
    await expect(dialog.locator('iframe')).toBeVisible();
    await shot(page, { target: dialog, pad: 0 });
  });

  // ---------------------------------------------------------------- 13 Niord Integration

  test('sysadmin-manual/NiordIntegrationsPage.png', async ({ page }) => {
    await guideIntegrationId(page.request);
    await loginAs(page, 'sysadmin');
    await page.goto('/sysadmin/niord-integration');
    await expect(page.locator('table tbody tr').first()).toContainText(GUIDE_INTEGRATION.url);
    // The copy has adminIntegrationPageEnabled on, so the Sysadmin rail lists the page.
    await expect(page.getByRole('link', { name: 'Niord Integration' }).first()).toBeVisible();
    await shot(page);
  });

  test('sysadmin-manual/NiordIntegrationEditPage.png', async ({ page }) => {
    await guideIntegrationId(page.request);
    await loginAs(page, 'sysadmin');
    await page.goto('/sysadmin/niord-integration');
    const row = page.locator('table tbody tr').filter({ hasText: GUIDE_INTEGRATION.url });
    await row.getByRole('button', { name: 'Edit Niord Integration' }).click();
    const drawer = dialogNamed(page, 'Edit Niord Integration');
    await expect(drawer.getByRole('textbox', { name: 'Niord URL' })).toHaveValue(
      GUIDE_INTEGRATION.url,
    );
    await shot(page, { target: drawer, pad: 0 });
  });

  // ---------------------------------------------------------------- 14 Settings

  test('sysadmin-manual/SettingsPage.png', async ({ page }) => {
    await loginAs(page, 'sysadmin');
    await page.goto('/sysadmin/settings');
    await expect(page.locator('table tbody tr').first()).toBeVisible();
    // The map settings: the mail settings would show the sender's address, which is no example.
    await page.getByRole('textbox', { name: 'Filter' }).fill('map');
    await expect(page.locator('table tbody tr').first()).toContainText(/map/i);
    await page.getByRole('textbox', { name: 'Filter' }).blur();
    await shot(page, { guard: { keyScan: false } });
  });

  // The drawer is as tall as the window: a window as tall as its three fields leaves no empty
  // half below them.
  test.describe('in a window as tall as the setting form', () => {
    test.use({ viewport: { width: 1280, height: 380 } });

    test('sysadmin-manual/SettingEditPage.png', async ({ page }) => {
      await loginAs(page, 'sysadmin');
      await page.goto('/sysadmin/settings');
      await page.getByRole('textbox', { name: 'Filter' }).fill('mailDeleteAfterDays');
      const row = page.locator('table tbody tr').filter({ hasText: 'mailDeleteAfterDays' });
      await row.getByRole('button', { name: 'Edit Setting' }).click();
      const drawer = dialogNamed(page, 'Edit Setting');
      await expect(drawer.getByRole('textbox', { name: 'Key' })).toHaveValue('mailDeleteAfterDays');
      await expect(drawer.getByRole('button', { name: 'Save Setting' })).toBeInViewport({
        ratio: 1,
      });
      await shot(page, { target: drawer, pad: 0, guard: { keyScan: false } });
    });
  });

  // ---------------------------------------------------------------- 15 Batch Jobs

  // The pictures show runs of their own: scripts started through Execute JavaScript (as the
  // sysadmin, under a data file name of their own) that read the message series ids, or wait 40
  // seconds, and write nothing. Each run adds a row to BatchData and a folder under the batch job
  // folder of the guides' home. The batch engine keeps its runs in memory only: after a backend
  // start the page lists the runs since it, and the tests start the runs they need again.

  test('sysadmin-manual/BatchJobsPage.png', async ({ page }) => {
    await completedSeriesIdsRun(page.request);
    await showBatchTimesAtNow(page);
    await loginAs(page, 'sysadmin');
    await page.goto(`/sysadmin/batch/${SCRIPT_JOB}`);
    await expect(runRow(page, BATCH_FILES.seriesIds)).toBeVisible();
    await expect(page.getByText('completed', { exact: true }).first()).toBeVisible();
    await shot(page);
  });

  test('sysadmin-manual/BatchJobRunning.png', async ({ page }) => {
    const run = await runningWaitRun(page.request);
    await showBatchTimesAtNow(page);
    await loginAs(page, 'sysadmin');
    await page.goto(`/sysadmin/batch/${SCRIPT_JOB}`);
    const execution = executionRow(page, run);
    await expect(execution.getByRole('button', { name: 'stop', exact: true })).toBeVisible();
    await expect(page.getByText('1 running', { exact: true })).toBeVisible();
    // The run's progress bar is the picture's point: the shot does not wait for it to go.
    await shot(page, { busy: false });
    // Stopped afterwards, as the next picture shows it (and so that it never ends by itself).
    await stopRun(page.request, run);
  });

  test('sysadmin-manual/BatchJobRestart.png', async ({ page }) => {
    const run = await stoppedWaitRun(page.request);
    await showBatchTimesAtNow(page);
    await loginAs(page, 'sysadmin');
    await page.goto(`/sysadmin/batch/${SCRIPT_JOB}`);
    const execution = executionRow(page, run);
    await expect(execution.getByText('stopped', { exact: true })).toBeVisible();
    await expect(execution.getByRole('button', { name: 'restart', exact: true })).toBeVisible();
    await expect(execution.getByRole('button', { name: 'abandon', exact: true })).toBeVisible();
    await shot(page);
  });

  test('sysadmin-manual/BatchJobLogDialog.png', async ({ page }) => {
    await completedSeriesIdsRun(page.request);
    await showLogTimesAtNow(page);
    await loginAs(page, 'sysadmin');
    await page.goto(`/sysadmin/batch/${SCRIPT_JOB}`);
    await runRow(page, BATCH_FILES.seriesIds).getByRole('button', { name: 'logs…' }).click();
    const dialog = dialogNamed(page, 'Batch Job Log Files');
    // The log that shows the script and how long it ran: the run's one execution only.
    await dialog.getByRole('radio', { name: /Processor/ }).click();
    const content = dialog.getByRole('textbox', { name: 'Log file content' });
    await expect(content).toHaveValue(/Executed script in/);
    expect((await content.inputValue()).match(/^INFO: Executed script in /gm)).toHaveLength(1);
    await shot(page, { target: dialog, pad: 0 });
  });

  test('sysadmin-manual/BatchJobExecuteJavaScript.png', async ({ page }) => {
    await loginAs(page, 'sysadmin');
    await page.goto('/sysadmin/batch');
    await page.getByRole('button', { name: 'Execute JavaScript…' }).click();
    const dialog = dialogNamed(page, 'Execute JavaScript');
    await expect(dialog).toBeVisible();
    // Typed and photographed; Schedule is never clicked.
    await dialog.getByRole('textbox', { name: 'JavaScript' }).fill(SERIES_IDS_SCRIPT);
    await shot(page, { target: dialog, pad: 0 });
  });

  test('sysadmin-manual/BatchSetUploadDialog.png', async ({ page }) => {
    await loginAs(page, 'sysadmin');
    await page.goto('/sysadmin/batch');
    await page.getByRole('button', { name: 'Upload batch set…' }).click();
    const dialog = dialogNamed(page, 'Upload Batch-Set Archive');
    await expect(dialog).toBeVisible();
    await shot(page, { target: dialog, pad: 0 });
  });
});
