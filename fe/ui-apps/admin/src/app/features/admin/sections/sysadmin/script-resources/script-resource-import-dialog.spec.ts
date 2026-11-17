import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it } from 'vitest';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { ScriptResourceImportDialog } from './script-resource-import-dialog';

const PREVIEW_URL = '/rest/script-resources/import-preview';
const UPLOAD_URL = '/rest/script-resources/upload-script-resources';

const PREVIEW = {
  add: [
    { path: 'templates/report/intro.ftl', type: 'FM', content: 'A', listed: true },
    { path: 'templates/tmpl/extra.js', type: 'JS', content: 'B', listed: false },
  ],
  overwrite: [
    {
      path: 'templates/messages/mail.ftl',
      type: 'FM',
      current: 'shared\nTHE-DEPLOYMENT',
      incoming: 'shared\nTHE-FILE',
    },
  ],
  unchanged: ['templates/a.ftl', 'templates/b.ftl', 'templates/c.ftl'],
  refused: [{ path: 'templates/tmpl/bad.js', reason: "uses 'Java.type'" }],
};

@Component({
  imports: [ScriptResourceImportDialog],
  template: `<app-script-resource-import-dialog (closed)="closedCount = closedCount + 1" />`,
})
class Host {
  readonly dialog = viewChild.required(ScriptResourceImportDialog);
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

function selectFile(doc: Document, fileName = 'script-resources.json'): File {
  const input = doc.querySelector('#scriptResourceImportFile') as HTMLInputElement;
  const file = new File(['[]'], fileName, { type: 'application/json' });
  // jsdom has no DataTransfer; assign a minimal FileList stand-in directly.
  Object.defineProperty(input, 'files', { value: [file], configurable: true });
  input.dispatchEvent(new Event('change'));
  return file;
}

/** Picks a file and answers its preview. */
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

describe('ScriptResourceImportDialog', () => {
  afterEach(() => {
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
    TestBed.inject(HttpTestingController).verify();
  });

  it("opens on the shared import dialog's picker, with no Import before a preview", async () => {
    const { doc } = await setup();
    const dialog = doc.querySelector('[role="dialog"]') as HTMLElement;
    expect(dialog.textContent).toContain('admin.scriptResources.import.title');
    expect(dialog.textContent).toContain('admin.import.fileLabel');
    expect(dialog.textContent).toContain('admin.import.chooseFile');
    expect(dialog.textContent).toContain('admin.import.dropHint');
    expect(doc.querySelector('#scriptResourceImportFile')?.getAttribute('accept')).toBe('.json');
    expect(importButton(doc)).toBeNull();
  });

  it('previews on pick: adds ticked and marked when listed, overwrites unticked, counts, refusals', async () => {
    const { doc, fixture, httpTesting } = await setup();
    const file = selectFile(doc);
    const req = httpTesting.expectOne(PREVIEW_URL);
    expect(req.request.method).toBe('POST');
    expect((req.request.body as FormData).get('file')).toBe(file);
    req.flush(PREVIEW);
    await fixture.whenStable();

    const adds = checkboxes(doc, 'import-add');
    expect(adds.map((box) => box.checked)).toEqual([true, true]);
    const addSection = doc.querySelector('[data-testid="import-add"]') as HTMLElement;
    expect(addSection.textContent).toContain('templates/report/intro.ftl');
    expect(addSection.querySelectorAll('[data-testid="listed"]').length).toBe(1);

    expect(checkboxes(doc, 'import-overwrite').map((box) => box.checked)).toEqual([false]);
    expect(doc.querySelector('[data-testid="import-unchanged"]')?.textContent).toContain(
      'admin.import.unchanged',
    );
    const refused = doc.querySelector('[data-testid="import-refused"]') as HTMLElement;
    expect(refused.textContent).toContain('templates/tmpl/bad.js');
    expect(refused.textContent).toContain("uses 'Java.type'");
    expect(refused.querySelectorAll('input').length).toBe(0);

    expect(importButton(doc)?.disabled).toBe(false);
    httpTesting.expectNone(UPLOAD_URL);
  });

  it('"only the listed paths" unticks every add the missing-path report does not list', async () => {
    const { doc, fixture } = await previewed();
    const onlyListed = Array.from(
      doc.querySelectorAll<HTMLButtonElement>('[data-testid="import-add"] button'),
    ).find((button) => button.textContent?.includes('admin.scriptResources.import.onlyListed'));
    await click(fixture, onlyListed!);
    expect(checkboxes(doc, 'import-add').map((box) => box.checked)).toEqual([true, false]);
  });

  it('imports the file with the ticked adds and overwrites, then shows the answer and locks the ticks', async () => {
    const { doc, fixture, file, httpTesting } = await previewed();
    // Untick the second add; tick the overwrite.
    await click(fixture, checkboxes(doc, 'import-add')[1]);
    await click(fixture, checkboxes(doc, 'import-overwrite')[0]);

    await click(fixture, importButton(doc)!);
    const req = httpTesting.expectOne(UPLOAD_URL);
    const body = req.request.body as FormData;
    expect(body.get('file')).toBe(file);
    expect(JSON.parse(body.get('add') as string)).toEqual(['templates/report/intro.ftl']);
    expect(JSON.parse(body.get('overwrite') as string)).toEqual(['templates/messages/mail.ftl']);
    req.flush("Started batch job 'script-resource-import' with file script-resources.json");
    await fixture.whenStable();

    const result = doc.querySelector('textarea[readonly]') as HTMLTextAreaElement;
    expect(result.value).toContain("Started batch job 'script-resource-import'");
    expect(doc.querySelector('a[href="/sysadmin/batch/script-resource-import"]')).not.toBeNull();
    expect(importButton(doc)?.disabled).toBe(true);
    expect(checkboxes(doc, 'import-add').every((box) => box.disabled)).toBe(true);
  });

  it('with nothing ticked the Import stays disabled and says why', async () => {
    const { doc, fixture, httpTesting } = await previewed({
      add: [{ path: 'templates/x.ftl', type: 'FM', content: '', listed: false }],
      overwrite: [],
      unchanged: [],
      refused: [],
    });
    expect(doc.body.textContent).not.toContain('admin.import.nothingTicked');
    await click(fixture, checkboxes(doc, 'import-add')[0]);
    expect(importButton(doc)?.disabled).toBe(true);
    expect(doc.body.textContent).toContain('admin.import.nothingTicked');
    await click(fixture, importButton(doc)!);
    httpTesting.expectNone(UPLOAD_URL);
  });

  it('says so when the file adds and overwrites nothing', async () => {
    const { doc } = await previewed({
      add: [],
      overwrite: [],
      unchanged: ['templates/a.ftl'],
      refused: [],
    });
    expect(doc.body.textContent).toContain('admin.import.nothingToImport');
    expect(importButton(doc)?.disabled).toBe(true);
  });

  it("shows an overwrite's diff: the deployment's row against the file", async () => {
    const { doc, fixture } = await previewed();
    expect(doc.querySelector('app-code-merge-view')).toBeNull();
    const showDiff = doc.querySelector(
      '[data-testid="import-overwrite"] button',
    ) as HTMLButtonElement;
    expect(showDiff.getAttribute('aria-expanded')).toBe('false');
    await click(fixture, showDiff);
    expect(showDiff.getAttribute('aria-expanded')).toBe('true');

    // The merge view is CodeMirror behind a lazy import; wait for it to mount.
    let text = '';
    for (let i = 0; i < 100 && !text.includes('THE-FILE'); i++) {
      await new Promise((resolve) => setTimeout(resolve, 20));
      text = doc.querySelector('app-code-merge-view')?.textContent ?? '';
    }
    expect(text).toContain('THE-DEPLOYMENT');
    expect(text).toContain('THE-FILE');

    await click(fixture, showDiff);
    expect(doc.querySelector('app-code-merge-view')).toBeNull();
  });

  it('a failed preview shows the import error for script resources, and nothing to import', async () => {
    const { doc, fixture, httpTesting } = await setup();
    selectFile(doc);
    httpTesting.expectOne(PREVIEW_URL).flush('bad file', { status: 400, statusText: 'Bad' });
    await fixture.whenStable();
    expect(doc.querySelector('[role="alert"]')?.textContent).toContain('admin.import.error');
    expect(importButton(doc)).toBeNull();
  });

  it('silently ignores a file that is not .json', async () => {
    const { doc, fixture, httpTesting } = await setup();
    selectFile(doc, 'notes.txt');
    await fixture.whenStable();
    httpTesting.expectNone(PREVIEW_URL);
    expect(importButton(doc)).toBeNull();
  });

  it('a new file starts over with its own preview', async () => {
    const { doc, fixture, httpTesting } = await previewed();
    await click(fixture, checkboxes(doc, 'import-overwrite')[0]);
    selectFile(doc, 'other.json');
    httpTesting.expectOne(PREVIEW_URL).flush(PREVIEW);
    await fixture.whenStable();
    expect(checkboxes(doc, 'import-overwrite').map((box) => box.checked)).toEqual([false]);
  });

  it('closing emits closed, so the page refetches', async () => {
    const { host, fixture } = await setup();
    (host.dialog() as unknown as { close: () => void }).close();
    await fixture.whenStable();
    expect(host.closedCount).toBe(1);
  });

  describe('every answer belongs to the file it was asked for', () => {
    const PREVIEW_A = {
      add: [],
      overwrite: [
        {
          path: 'templates/only-in-a.ftl',
          type: 'FM',
          current: 'row',
          incoming: 'A-CONTENT',
          basedOn: 'sha256:aaaa',
        },
      ],
      unchanged: [],
      refused: [],
    };
    const PREVIEW_B = {
      add: [],
      overwrite: [
        {
          path: 'templates/only-in-b.ftl',
          type: 'FM',
          current: 'row',
          incoming: 'B-CONTENT',
          basedOn: 'sha256:bbbb',
        },
      ],
      unchanged: [],
      refused: [],
    };

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

      expect(overwriteText(doc)).toContain('templates/only-in-b.ftl');
      expect(overwriteText(doc)).not.toContain('templates/only-in-a.ftl');

      await click(fixture, checkboxes(doc, 'import-overwrite')[0]);
      await click(fixture, importButton(doc)!);
      const upload = httpTesting.expectOne(UPLOAD_URL);
      const body = upload.request.body as FormData;
      expect(body.get('file')).toBe(fileB);
      expect(JSON.parse(body.get('overwrite') as string)).toEqual([
        { path: 'templates/only-in-b.ftl', basedOn: 'sha256:bbbb' },
      ]);
      upload.flush("Started batch job 'script-resource-import' with file B.json");
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
      expect(overwriteText(doc)).toContain('templates/only-in-b.ftl');
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
      detail: 'The file x.json is not a list of script resources: Unexpected character',
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
      const alert = doc.querySelector('[data-testid="preview-error"]') as HTMLElement;
      expect(alert.textContent).toContain('admin.import.error');
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
      const detail = "The field 'overwrite' is not a JSON array";
      httpTesting
        .expectOne(UPLOAD_URL)
        .flush(JSON.stringify({ ...PROBLEM, detail }), { status: 400, statusText: 'Bad Request' });
      await fixture.whenStable();
      expect(doc.querySelector('[data-testid="import-error"]')?.textContent).toContain(
        'admin.import.error',
      );
      expect(reasons(doc)).toEqual([detail]);
    });

    it('a failed import leaves the ticks open, so Import can be pressed again with the same file', async () => {
      const { doc, fixture, file, httpTesting } = await previewed();
      await click(fixture, importButton(doc)!);
      httpTesting.expectOne(UPLOAD_URL).flush('', { status: 502, statusText: 'Bad Gateway' });
      await fixture.whenStable();

      expect(doc.querySelector('[data-testid="import-error"]')?.textContent).toContain(
        'admin.import.error',
      );
      expect(reasons(doc)).toEqual([]);
      expect(doc.querySelector('textarea[readonly]')).toBeNull();
      expect(doc.querySelector('a[href="/sysadmin/batch/script-resource-import"]')).toBeNull();
      expect(checkboxes(doc, 'import-add').some((box) => box.disabled)).toBe(false);
      expect(importButton(doc)?.disabled).toBe(false);

      await click(fixture, importButton(doc)!);
      const retry = httpTesting.expectOne(UPLOAD_URL);
      expect((retry.request.body as FormData).get('file')).toBe(file);
      retry.flush("Started batch job 'script-resource-import' with file script-resources.json");
      await fixture.whenStable();
      expect(doc.querySelector('[data-testid="import-error"]')).toBeNull();
      expect(doc.querySelector('a[href="/sysadmin/batch/script-resource-import"]')).not.toBeNull();
      expect(importButton(doc)?.disabled).toBe(true);
    });

    it('an answer that started no job is shown as an error, without the job link or the background note', async () => {
      const { doc, fixture, httpTesting } = await previewed();
      await click(fixture, importButton(doc)!);
      const answer =
        'Error executing batch job script-resource-import with file script-resources.json: no such job';
      httpTesting.expectOne(UPLOAD_URL).flush(answer);
      await fixture.whenStable();

      expect(doc.querySelector('[data-testid="import-error"]')?.textContent).toContain(
        'admin.import.notStarted',
      );
      expect(reasons(doc)).toEqual([answer]);
      expect(doc.querySelector('a[href="/sysadmin/batch/script-resource-import"]')).toBeNull();
      expect(doc.body.textContent).not.toContain('admin.import.backgroundNote');
      expect(importButton(doc)?.disabled).toBe(false);
    });
  });
});
