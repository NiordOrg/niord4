import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal, viewChild } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it } from 'vitest';

import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { JsonImportDialog } from './json-import-dialog';

@Component({
  imports: [JsonImportDialog],
  template: `
    <app-json-import-dialog
      titleKey="admin.sources.import.title"
      uploadUrl="/rest/sources/upload-sources"
      entityKey="admin.sections.sources.label"
      [accept]="accept()"
      (closed)="closedCount = closedCount + 1"
    />
  `,
})
class Host {
  readonly dialog = viewChild.required(JsonImportDialog);
  readonly accept = signal('.json');
  closedCount = 0;
}

async function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      provideTestTransloco(),
      provideNiordIcons(),
    ],
  });
  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  await fixture.whenStable();
  return {
    fixture,
    host: fixture.componentInstance,
    httpTesting: TestBed.inject(HttpTestingController),
  };
}

function selectFile(fixture: ComponentFixture<unknown>, fileName = 'sources.json'): void {
  const input = fixture.nativeElement.ownerDocument.querySelector(
    'input[type="file"]',
  ) as HTMLInputElement;
  const file = new File(['{}'], fileName, { type: 'application/json' });
  // jsdom has no DataTransfer; assign a minimal FileList stand-in directly.
  Object.defineProperty(input, 'files', { value: [file], configurable: true });
  input.dispatchEvent(new Event('change'));
}

function dropFile(fixture: ComponentFixture<unknown>, fileName = 'sources.json'): void {
  const zone = fixture.nativeElement.ownerDocument.querySelector('.file-drop-zone') as HTMLElement;
  const file = new File(['{}'], fileName, { type: 'application/json' });
  // jsdom has no DataTransfer; dispatch a plain Event with a stubbed payload.
  const event = new Event('drop', { bubbles: true });
  Object.defineProperty(event, 'dataTransfer', {
    value: { files: [file] },
    configurable: true,
  });
  zone.dispatchEvent(event);
}

describe('JsonImportDialog', () => {
  afterEach(() => {
    // Dispose any leaked overlay panels across the shared single-fork TestBed.
    document.querySelectorAll('.cdk-overlay-container').forEach((el) => el.replaceChildren());
    TestBed.inject(HttpTestingController).verify();
  });

  it('only accepts .json files via the picker filter (silent rejection)', async () => {
    const { fixture, host } = await setup();
    host.dialog().open();
    await fixture.whenStable();
    const input = fixture.nativeElement.ownerDocument.querySelector('input[type="file"]');
    expect(input?.getAttribute('accept')).toBe('.json');
  });

  it('auto-uploads on selection and shows the raw backend response, staying open', async () => {
    const { fixture, host, httpTesting } = await setup();
    host.dialog().open();
    await fixture.whenStable();

    selectFile(fixture);
    const req = httpTesting.expectOne('/rest/sources/upload-sources');
    expect(req.request.method).toBe('POST');
    expect(req.request.body instanceof FormData).toBe(true);
    req.flush('Started import job 42');
    await fixture.whenStable();

    const result = fixture.nativeElement.ownerDocument.querySelector('textarea[readonly]');
    expect((result as HTMLTextAreaElement).value).toContain('Started import job 42');
    expect(host.closedCount).toBe(0); // stays open after upload
  });

  it('a failed upload shows the per-section entity error, never a hardcoded charts copy', async () => {
    const { fixture, host, httpTesting } = await setup();
    host.dialog().open();
    await fixture.whenStable();

    selectFile(fixture);
    httpTesting
      .expectOne('/rest/sources/upload-sources')
      .flush('boom', { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();

    const result = fixture.nativeElement.ownerDocument.querySelector(
      'textarea[readonly]',
    ) as HTMLTextAreaElement;
    expect(result.value).toContain('admin.import.error');
    expect(result.value).not.toContain('charts');
  });

  it('silently ignores a non-.json selection — no upload, no state change', async () => {
    const { fixture, host, httpTesting } = await setup();
    host.dialog().open();
    await fixture.whenStable();

    const input = fixture.nativeElement.ownerDocument.querySelector(
      'input[type="file"]',
    ) as HTMLInputElement;
    const file = new File(['nope'], 'notes.txt', { type: 'text/plain' });
    Object.defineProperty(input, 'files', { value: [file], configurable: true });
    input.dispatchEvent(new Event('change'));
    await fixture.whenStable();

    httpTesting.expectNone('/rest/sources/upload-sources');
    const result = fixture.nativeElement.ownerDocument.querySelector(
      'textarea[readonly]',
    ) as HTMLTextAreaElement;
    expect(result.value).toBe('');
  });

  it('closing the dialog emits closed (consumers refetch on every close path)', async () => {
    const { fixture, host } = await setup();
    host.dialog().open();
    await fixture.whenStable();

    (host.dialog() as unknown as { close: () => void }).close();
    await fixture.whenStable();
    expect(host.closedCount).toBe(1);
  });

  it('drops a dragged file through the same validate+upload routine', async () => {
    const { fixture, host, httpTesting } = await setup();
    host.dialog().open();
    await fixture.whenStable();

    dropFile(fixture);
    const req = httpTesting.expectOne('/rest/sources/upload-sources');
    expect(req.request.method).toBe('POST');
    expect(req.request.body instanceof FormData).toBe(true);
    req.flush('Started import job 7');
    await fixture.whenStable();

    const result = fixture.nativeElement.ownerDocument.querySelector(
      'textarea[readonly]',
    ) as HTMLTextAreaElement;
    expect(result.value).toContain('Started import job 7');
  });

  it('toggles the is-dragover highlight on dragover/dragleave', async () => {
    const { fixture, host } = await setup();
    host.dialog().open();
    await fixture.whenStable();

    const zone = fixture.nativeElement.ownerDocument.querySelector(
      '.file-drop-zone',
    ) as HTMLElement;
    zone.dispatchEvent(new Event('dragover', { bubbles: true }));
    await fixture.whenStable();
    expect(zone.classList).toContain('is-dragover');

    zone.dispatchEvent(new Event('dragleave', { bubbles: true }));
    await fixture.whenStable();
    expect(zone.classList).not.toContain('is-dragover');
  });

  it('silently ignores a dropped non-matching file (silent rejection)', async () => {
    const { fixture, host, httpTesting } = await setup();
    host.dialog().open();
    await fixture.whenStable();

    dropFile(fixture, 'notes.txt');
    await fixture.whenStable();

    httpTesting.expectNone('/rest/sources/upload-sources');
    const result = fixture.nativeElement.ownerDocument.querySelector(
      'textarea[readonly]',
    ) as HTMLTextAreaElement;
    expect(result.value).toBe('');
  });

  it('honours an accept-variant input on both the picker attribute and the guard', async () => {
    const { fixture, host, httpTesting } = await setup();
    host.accept.set('.zip');
    host.dialog().open();
    await fixture.whenStable();

    const input = fixture.nativeElement.ownerDocument.querySelector('input[type="file"]');
    expect(input?.getAttribute('accept')).toBe('.zip');

    // A .json file is now rejected silently; a .zip file uploads.
    selectFile(fixture, 'data.json');
    httpTesting.expectNone('/rest/sources/upload-sources');

    selectFile(fixture, 'data.zip');
    httpTesting.expectOne('/rest/sources/upload-sources').flush('ok');
    await fixture.whenStable();
  });

  it('renders the "view batch job" link only after a result when jobLink is set', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideTestTransloco(),
        provideNiordIcons(),
        provideRouter([]),
      ],
    });
    const fixture = TestBed.createComponent(JobLinkHost);
    fixture.autoDetectChanges();
    await fixture.whenStable();
    const host = fixture.componentInstance;
    const httpTesting = TestBed.inject(HttpTestingController);
    host.dialog().open();
    await fixture.whenStable();

    // No result yet → no link.
    const doc = fixture.nativeElement.ownerDocument as Document;
    expect(doc.querySelector('a[href="/sysadmin/batch/charts-import"]')).toBeNull();

    selectFile(fixture, 'set.zip');
    httpTesting.expectOne('/rest/sources/upload-sources').flush('Started import job 5');
    await fixture.whenStable();

    expect(doc.querySelector('a[href="/sysadmin/batch/charts-import"]')).not.toBeNull();
  });
});

@Component({
  imports: [JsonImportDialog],
  template: `
    <app-json-import-dialog
      titleKey="admin.batch.import.title"
      uploadUrl="/rest/sources/upload-sources"
      entityKey="admin.sections.batch.label"
      accept=".zip"
      jobLink="/sysadmin/batch/charts-import"
    />
  `,
})
class JobLinkHost {
  readonly dialog = viewChild.required(JsonImportDialog);
}
