import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { DictionaryImportDialog } from './dictionary-import-dialog';

const PREVIEW_URL = '/rest/dictionaries/import-preview';
const UPLOAD_URL = '/rest/dictionaries/upload-dictionaries';

const PREVIEW = {
  add: [
    { dictionary: 'web', key: 'web.new', values: { en: 'New text' }, deleted: false },
    { dictionary: 'web', key: 'web.gone', values: { en: 'Gone text' }, deleted: true },
    { dictionary: 'mail', key: 'mail.new', values: { en: 'Mail text' }, deleted: false },
  ],
  overwrite: [
    {
      dictionary: 'message',
      key: 'msg.changed',
      current: {
        atonFilter: 'old-filter',
        values: { en: 'Deployment text', da: 'Installationens tekst' },
        longValues: { en: 'Same long' },
      },
      incoming: {
        values: { en: 'File text' },
        longValues: { en: 'Same long' },
      },
    },
  ],
  unchanged: 41,
  refused: [{ dictionary: 'web', key: 'web.long', reason: 'value longer than 1000 characters' }],
};

@Component({
  imports: [DictionaryImportDialog],
  template: `<app-dictionary-import-dialog (closed)="closedCount = closedCount + 1" />`,
})
class Host {
  readonly dialog = viewChild.required(DictionaryImportDialog);
  closedCount = 0;
}

async function setup() {
  TestBed.configureTestingModule({
    providers: [
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
      provideRouter([]),
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  fixture.componentInstance.dialog().open();
  await fixture.whenStable();
  return {
    fixture,
    host: fixture.componentInstance,
    httpTesting: TestBed.inject(HttpTestingController),
    doc: fixture.nativeElement.ownerDocument as Document,
  };
}

function selectFile(doc: Document, fileName = 'texts.json'): File {
  const input = doc.querySelector('#dictionaryImportFile') as HTMLInputElement;
  const file = new File(['[]'], fileName, { type: 'application/json' });
  // jsdom has no DataTransfer; assign a minimal FileList stand-in directly.
  Object.defineProperty(input, 'files', { value: [file], configurable: true });
  input.dispatchEvent(new Event('change'));
  return file;
}

async function previewed(preview: object = PREVIEW) {
  const ctx = await setup();
  const file = selectFile(ctx.doc);
  ctx.httpTesting.expectOne(PREVIEW_URL).flush(preview);
  await ctx.fixture.whenStable();
  return { ...ctx, file };
}

function checkboxes(doc: Document, section: string): HTMLInputElement[] {
  return Array.from(doc.querySelectorAll(`[data-testid="${section}"] input[type="checkbox"]`));
}

function importButton(doc: Document): HTMLButtonElement | null {
  return doc.querySelector('.dialog-foot .btn-primary');
}

async function click(fixture: ComponentFixture<unknown>, element: HTMLElement): Promise<void> {
  element.click();
  await fixture.whenStable();
}

describe('DictionaryImportDialog', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
    TestBed.inject(HttpTestingController).verify();
  });

  it("opens on the shared import dialog's picker, with no Import before a preview", async () => {
    const { doc } = await setup();
    const dialog = doc.querySelector('[role="dialog"]') as HTMLElement;
    expect(dialog.textContent).toContain('admin.dictionaries.import.title');
    expect(dialog.textContent).toContain('admin.import.chooseFile');
    expect(doc.querySelector('#dictionaryImportFile')?.getAttribute('accept')).toBe('.json');
    expect(importButton(doc)).toBeNull();
  });

  it('previews on pick: adds by dictionary, a deleted key offered unticked, overwrites unticked', async () => {
    const { doc, fixture, httpTesting } = await setup();
    const file = selectFile(doc);
    const req = httpTesting.expectOne(PREVIEW_URL);
    expect((req.request.body as FormData).get('file')).toBe(file);
    req.flush(PREVIEW);
    await fixture.whenStable();

    const add = doc.querySelector('[data-testid="import-add"]') as HTMLElement;
    expect(Array.from(add.querySelectorAll('h4')).map((h) => h.textContent?.trim())).toEqual([
      'web',
      'mail',
    ]);
    expect(checkboxes(doc, 'import-add').map((box) => box.checked)).toEqual([true, false, true]);
    expect(add.querySelectorAll('[data-testid="deleted"]').length).toBe(1);
    expect(add.textContent).toContain('New text');

    expect(checkboxes(doc, 'import-overwrite').map((box) => box.checked)).toEqual([false]);
    expect(doc.querySelector('[data-testid="import-unchanged"]')).not.toBeNull();
    const refused = doc.querySelector('[data-testid="import-refused"]') as HTMLElement;
    expect(refused.textContent).toContain('web.long');
    expect(refused.textContent).toContain('value longer than 1000 characters');
    httpTesting.expectNone(UPLOAD_URL);
  });

  it("shows an overwrite's languages the file carries, beside the deployment's, and a changed AtoN filter", async () => {
    const { doc } = await previewed();
    const rows = Array.from(doc.querySelectorAll('[data-testid="import-overwrite"] tbody tr')).map(
      (tr) => Array.from(tr.querySelectorAll('td')).map((td) => td.textContent?.trim()),
    );
    // The file carries only `en`: `da` is kept and not shown; the equal long value is left out;
    // the AtoN filter differs, so the import's replacement of it is shown, here as a clearing.
    expect(rows).toEqual([
      ['en', 'Deployment text', 'File text'],
      ['admin.dictionaries.field.atonFilter', 'old-filter', 'admin.dictionaries.import.cleared'],
    ]);
  });

  it('imports the file with the ticked keys as {dictionary, key}, then shows the answer', async () => {
    const { doc, fixture, file, httpTesting } = await previewed();
    // Tick the deleted key back in and the overwrite; untick the mail key.
    const adds = checkboxes(doc, 'import-add');
    await click(fixture, adds[1]);
    await click(fixture, adds[2]);
    await click(fixture, checkboxes(doc, 'import-overwrite')[0]);

    await click(fixture, importButton(doc)!);
    const req = httpTesting.expectOne(UPLOAD_URL);
    const body = req.request.body as FormData;
    expect(body.get('file')).toBe(file);
    expect(JSON.parse(body.get('add') as string)).toEqual([
      { dictionary: 'web', key: 'web.new' },
      { dictionary: 'web', key: 'web.gone' },
    ]);
    expect(JSON.parse(body.get('overwrite') as string)).toEqual([
      { dictionary: 'message', key: 'msg.changed' },
    ]);
    req.flush("Started batch job 'dictionary-import' with file texts.json");
    await fixture.whenStable();

    expect((doc.querySelector('textarea[readonly]') as HTMLTextAreaElement).value).toContain(
      "'dictionary-import'",
    );
    expect(doc.querySelector('a[href="/sysadmin/batch/dictionary-import"]')).not.toBeNull();
    expect(importButton(doc)?.disabled).toBe(true);
  });

  it('a failed import shows the import error for dictionaries, and no job link', async () => {
    const { doc, fixture, httpTesting } = await previewed();
    await click(fixture, importButton(doc)!);
    httpTesting.expectOne(UPLOAD_URL).flush('', { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    expect(doc.querySelector('[data-testid="import-error"]')?.textContent).toContain(
      'admin.import.error',
    );
    expect(doc.querySelector('textarea[readonly]')).toBeNull();
    expect(doc.querySelector('a[href="/sysadmin/batch/dictionary-import"]')).toBeNull();
  });

  it('with nothing ticked the Import stays disabled and says why', async () => {
    const { doc, fixture, httpTesting } = await previewed({
      add: [{ dictionary: 'web', key: 'k', values: { en: 'v' }, deleted: true }],
      overwrite: [],
      unchanged: 0,
      refused: [],
    });
    expect(importButton(doc)?.disabled).toBe(true);
    expect(doc.body.textContent).toContain('admin.import.nothingTicked');
    await click(fixture, importButton(doc)!);
    httpTesting.expectNone(UPLOAD_URL);
  });

  it('a failed preview shows the import error, and nothing to import', async () => {
    const { doc, fixture, httpTesting } = await setup();
    selectFile(doc);
    httpTesting.expectOne(PREVIEW_URL).flush('', { status: 400, statusText: 'Bad' });
    await fixture.whenStable();
    expect(doc.querySelector('[role="alert"]')?.textContent).toContain('admin.import.error');
    expect(importButton(doc)).toBeNull();
  });

  it('silently ignores a file that is not .json', async () => {
    const { doc, httpTesting } = await setup();
    selectFile(doc, 'texts.zip');
    httpTesting.expectNone(PREVIEW_URL);
  });

  it('closing emits closed, so the page refetches', async () => {
    const { host, fixture } = await setup();
    (host.dialog() as unknown as { close: () => void }).close();
    await fixture.whenStable();
    expect(host.closedCount).toBe(1);
  });

  describe('an overwrite shows the long value it clears', () => {
    const CURRENT = {
      values: { en: 'Same label' },
      longValues: { en: 'Important instructions' },
    };

    async function rowsFor(incoming: object) {
      const { doc } = await previewed({
        add: [],
        overwrite: [{ dictionary: 'template', key: 'aton.light', current: CURRENT, incoming }],
        unchanged: 0,
        refused: [],
      });
      return Array.from(doc.querySelectorAll('[data-testid="import-overwrite"] tbody tr')).map(
        (tr) => ({
          cells: Array.from(tr.querySelectorAll('td')).map((td) => td.textContent?.trim()),
          muted: tr.classList.contains('text-fg-muted'),
        }),
      );
    }

    const CLEARING = [
      { cells: ['en', 'Same label', 'Same label'], muted: true },
      {
        cells: [
          'admin.dictionaries.import.longValue',
          'Important instructions',
          'admin.dictionaries.import.cleared',
        ],
        muted: false,
      },
    ];

    // The first reviewer's fixture: the file carries `en` with no long value.
    it('when the file has no long value for the language', async () => {
      expect(await rowsFor({ values: { en: 'Same label' }, longValues: {} })).toEqual(CLEARING);
    });

    it('when the file has no long values at all', async () => {
      expect(await rowsFor({ values: { en: 'Same label' } })).toEqual(CLEARING);
    });

    it('when the file has an empty long value', async () => {
      expect(await rowsFor({ values: { en: 'Same label' }, longValues: { en: '' } })).toEqual(
        CLEARING,
      );
    });

    it('and a changed long value as the change it is', async () => {
      expect(
        await rowsFor({ values: { en: 'Same label' }, longValues: { en: 'New instructions' } }),
      ).toEqual([
        CLEARING[0],
        {
          cells: [
            'admin.dictionaries.import.longValue',
            'Important instructions',
            'New instructions',
          ],
          muted: false,
        },
      ]);
    });
  });

  describe('every answer belongs to the file it was asked for', () => {
    const overwriteOf = (key: string, basedOn: string) => ({
      add: [],
      overwrite: [
        {
          dictionary: 'web',
          key,
          current: { values: { en: 'row' } },
          incoming: { values: { en: key } },
          basedOn,
        },
      ],
      unchanged: 0,
      refused: [],
    });
    const PREVIEW_A = overwriteOf('web.only-in-a', 'sha256:aaaa');
    const PREVIEW_B = overwriteOf('web.only-in-b', 'sha256:bbbb');

    /** Picks A.json, closes and reopens the dialog while A's preview is pending. */
    async function aPendingThenReopened() {
      const ctx = await setup();
      selectFile(ctx.doc, 'A.json');
      const reqA = ctx.httpTesting.expectOne(PREVIEW_URL);
      const dialog = ctx.host.dialog() as unknown as { close: () => void; open: () => void };
      dialog.close();
      await ctx.fixture.whenStable();
      dialog.open();
      await ctx.fixture.whenStable();
      return { ...ctx, reqA };
    }

    function overwriteText(doc: Document): string {
      return doc.querySelector('[data-testid="import-overwrite"]')?.textContent ?? '';
    }

    it("a preview answered after close and reopen is never shown against the next file, nor imported with it (the first reviewer's reproduction)", async () => {
      const { doc, fixture, httpTesting, reqA } = await aPendingThenReopened();
      const fileB = selectFile(doc, 'B.json');
      httpTesting.expectOne(PREVIEW_URL).flush(PREVIEW_B);
      // The reviewer answered A's preview after B's; the dialog cancelled it on close, so it cannot.
      if (!reqA.cancelled) {
        reqA.flush(PREVIEW_A);
      }
      await fixture.whenStable();

      expect(overwriteText(doc)).toContain('web.only-in-b');
      expect(overwriteText(doc)).not.toContain('web.only-in-a');

      await click(fixture, checkboxes(doc, 'import-overwrite')[0]);
      await click(fixture, importButton(doc)!);
      const upload = httpTesting.expectOne(UPLOAD_URL);
      const body = upload.request.body as FormData;
      expect(body.get('file')).toBe(fileB);
      expect(JSON.parse(body.get('overwrite') as string)).toEqual([
        { dictionary: 'web', key: 'web.only-in-b', basedOn: 'sha256:bbbb' },
      ]);
      upload.flush("Started batch job 'dictionary-import' with file B.json");
      await fixture.whenStable();
      expect(reqA.cancelled).toBe(true);
    });

    it('a preview answered after close and reopen, with no file picked, shows nothing', async () => {
      const { doc, fixture, reqA } = await aPendingThenReopened();
      if (!reqA.cancelled) {
        reqA.flush(PREVIEW_A);
      }
      await fixture.whenStable();
      expect(doc.querySelector('[data-testid="import-overwrite"]')).toBeNull();
      expect(importButton(doc)).toBeNull();
      expect(reqA.cancelled).toBe(true);
    });

    it("an earlier file's failed preview after the next file's preview shows no error", async () => {
      const { doc, fixture, httpTesting, reqA } = await aPendingThenReopened();
      selectFile(doc, 'B.json');
      httpTesting.expectOne(PREVIEW_URL).flush(PREVIEW_B);
      if (!reqA.cancelled) {
        reqA.flush('', { status: 500, statusText: 'Server Error' });
      }
      await fixture.whenStable();
      expect(doc.querySelector('[role="alert"]')).toBeNull();
      expect(overwriteText(doc)).toContain('web.only-in-b');
    });

    it('a new file cancels the pending preview of the one before', async () => {
      const { doc, httpTesting } = await setup();
      selectFile(doc, 'A.json');
      const reqA = httpTesting.expectOne(PREVIEW_URL);
      selectFile(doc, 'B.json');
      expect(reqA.cancelled).toBe(true);
      httpTesting.expectOne(PREVIEW_URL).flush(PREVIEW_B);
    });
  });

  describe("the backend's reason and the result area", () => {
    const PROBLEM = {
      type: 'about:blank',
      title: 'Bad Request',
      status: 400,
      detail: 'The file x.json is not a list of dictionaries: Unexpected character',
    };

    function reasons(doc: Document): string[] {
      return Array.from(doc.querySelectorAll('[data-testid="import-reason"]')).map(
        (el) => el.textContent?.trim() ?? '',
      );
    }

    it("a 400 preview shows the backend's reason under the import error", async () => {
      const { doc, fixture, httpTesting } = await setup();
      selectFile(doc);
      httpTesting.expectOne(PREVIEW_URL).flush(PROBLEM, { status: 400, statusText: 'Bad Request' });
      await fixture.whenStable();
      expect(doc.querySelector('[data-testid="preview-error"]')?.textContent).toContain(
        'admin.import.error',
      );
      expect(reasons(doc)).toEqual([PROBLEM.detail]);
    });

    it('a 502 preview shows only the import error', async () => {
      const { doc, fixture, httpTesting } = await setup();
      selectFile(doc);
      httpTesting
        .expectOne(PREVIEW_URL)
        .flush('<html>Bad Gateway</html>', { status: 502, statusText: 'Bad Gateway' });
      await fixture.whenStable();
      expect(doc.querySelector('[data-testid="preview-error"]')?.textContent).toContain(
        'admin.import.error',
      );
      expect(reasons(doc)).toEqual([]);
    });

    it("a refused tick field shows the backend's reason from the text-typed upload's problem document", async () => {
      const { doc, fixture, httpTesting } = await previewed();
      await click(fixture, importButton(doc)!);
      const detail = "The field 'add' is not a JSON array of {dictionary, key}";
      httpTesting
        .expectOne(UPLOAD_URL)
        .flush(JSON.stringify({ ...PROBLEM, detail }), { status: 400, statusText: 'Bad Request' });
      await fixture.whenStable();
      expect(reasons(doc)).toEqual([detail]);
    });

    it('a failed import leaves the ticks open, so Import can be pressed again with the same file', async () => {
      const { doc, fixture, file, httpTesting } = await previewed();
      await click(fixture, importButton(doc)!);
      httpTesting.expectOne(UPLOAD_URL).flush('', { status: 502, statusText: 'Bad Gateway' });
      await fixture.whenStable();

      expect(reasons(doc)).toEqual([]);
      expect(checkboxes(doc, 'import-add').some((box) => box.disabled)).toBe(false);
      expect(importButton(doc)?.disabled).toBe(false);

      await click(fixture, importButton(doc)!);
      const retry = httpTesting.expectOne(UPLOAD_URL);
      expect((retry.request.body as FormData).get('file')).toBe(file);
      retry.flush("Started batch job 'dictionary-import' with file texts.json");
      await fixture.whenStable();
      expect(doc.querySelector('[data-testid="import-error"]')).toBeNull();
      expect(doc.querySelector('a[href="/sysadmin/batch/dictionary-import"]')).not.toBeNull();
      expect(checkboxes(doc, 'import-add').every((box) => box.disabled)).toBe(true);
      expect(importButton(doc)?.disabled).toBe(true);
    });

    it('an answer that started no job is shown as an error, without the job link or the background note', async () => {
      const { doc, fixture, httpTesting } = await previewed();
      await click(fixture, importButton(doc)!);
      const answer =
        'Error executing batch job dictionary-import with file texts.json: no such job';
      httpTesting.expectOne(UPLOAD_URL).flush(answer);
      await fixture.whenStable();

      expect(doc.querySelector('[data-testid="import-error"]')?.textContent).toContain(
        'admin.import.notStarted',
      );
      expect(reasons(doc)).toEqual([answer]);
      expect(doc.querySelector('a[href="/sysadmin/batch/dictionary-import"]')).toBeNull();
      expect(doc.body.textContent).not.toContain('admin.import.backgroundNote');
      expect(importButton(doc)?.disabled).toBe(false);
    });
  });
});
