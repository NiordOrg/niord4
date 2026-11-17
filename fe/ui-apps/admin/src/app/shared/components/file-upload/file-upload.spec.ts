import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { NotificationService } from '../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../testing/transloco-testing';
import { FileUpload } from './file-upload';

const URL = '/rest/messages/attachments/repo/1';

function makeFile(name: string, type = 'text/plain'): File {
  return new File(['content'], name, { type });
}

function fileList(files: File[]): FileList {
  const list: Record<number | string, unknown> = {
    length: files.length,
    item: (i: number) => files[i] ?? null,
  };
  files.forEach((f, i) => (list[i] = f));
  return list as unknown as FileList;
}

/** The name of the one file a multipart upload body carries. */
function uploadedName(body: unknown): string {
  return ((body as FormData).get('file') as File).name;
}

let httpMock: HttpTestingController;

function setup() {
  const notifications = {
    success: vi.fn(),
    info: vi.fn(),
    warn: vi.fn(),
    error: vi.fn(),
    notify: vi.fn(),
  };
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: NotificationService, useValue: notifications },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();
  httpMock = TestBed.inject(HttpTestingController);

  const fixture = TestBed.createComponent(FileUpload);
  fixture.componentRef.setInput('url', URL);
  fixture.autoDetectChanges();

  const el = fixture.nativeElement as HTMLElement;
  return { fixture, notifications, el, component: fixture.componentInstance };
}

function triggerBrowse(fixture: ComponentFixture<FileUpload>, files: File[]): void {
  const input = fixture.nativeElement.querySelector('input[type="file"]') as HTMLInputElement;
  Object.defineProperty(input, 'files', { value: fileList(files), configurable: true });
  input.dispatchEvent(new Event('change'));
}

function triggerDrop(fixture: ComponentFixture<FileUpload>, files: File[]): void {
  const zone = fixture.nativeElement.querySelector('.file-drop-zone') as HTMLElement;
  const event = new Event('drop');
  Object.defineProperty(event, 'dataTransfer', { value: { files: fileList(files) } });
  zone.dispatchEvent(event);
}

afterEach(() => httpMock.verify());

describe('FileUpload', () => {
  it('uploads a browsed file as a multipart POST to the current url (field name "file")', () => {
    const { fixture } = setup();
    triggerBrowse(fixture, [makeFile('a.png')]);

    const req = httpMock.expectOne(URL);
    expect(req.request.method).toBe('POST');
    const body = req.request.body as FormData;
    expect(body.has('file')).toBe(true);
    expect((body.get('file') as File).name).toBe('a.png');
    req.flush([]);
  });

  it('uploads a dropped file', () => {
    const { fixture } = setup();
    triggerDrop(fixture, [makeFile('b.png')]);

    const req = httpMock.expectOne(URL);
    expect(req.request.method).toBe('POST');
    req.flush([]);
  });

  it('reads the current url at upload time (url may change between uploads)', () => {
    const { fixture } = setup();
    fixture.componentRef.setInput('url', '/rest/messages/attachments/repo/2');
    triggerBrowse(fixture, [makeFile('a.png')]);

    httpMock.expectOne('/rest/messages/attachments/repo/2').flush([]);
  });

  it('shows an indeterminate activity bar while a file is uploading', async () => {
    // The fetch backend cannot report upload percentages (NG02824), so the bar is indeterminate.
    const { fixture, el } = setup();
    triggerBrowse(fixture, [makeFile('a.png')]);

    const req = httpMock.expectOne(URL);
    await fixture.whenStable();

    expect(el.querySelector('app-progress-bar')).not.toBeNull();
    req.flush([]);
  });

  it('emits the parsed JSON body on success', () => {
    const { fixture, component } = setup();
    const uploaded = vi.fn();
    component.uploaded.subscribe(uploaded);

    triggerBrowse(fixture, [makeFile('a.png')]);
    const vos = [{ path: 'repo/1/a.png', fileName: 'a.png' }];
    httpMock.expectOne(URL).flush(vos);

    expect(uploaded).toHaveBeenCalledWith(vos);
  });

  it('toasts and emits a typed failure when an upload errors', () => {
    const { fixture, notifications, component } = setup();
    const failed = vi.fn();
    component.failed.subscribe(failed);

    triggerBrowse(fixture, [makeFile('a.png')]);
    httpMock.expectOne(URL).flush('nope', { status: 500, statusText: 'Server Error' });

    expect(notifications.error).toHaveBeenCalledWith('shared.fileUpload.error', {
      fileName: 'a.png',
    });
    expect(failed).toHaveBeenCalledWith({ fileName: 'a.png', status: 500 });
  });

  it('forwards accept and multiple to the native input', async () => {
    const { fixture, el } = setup();
    fixture.componentRef.setInput('accept', 'image/png,image/jpeg');
    fixture.componentRef.setInput('multiple', true);
    await fixture.whenStable();

    const input = el.querySelector('input[type="file"]') as HTMLInputElement;
    expect(input.accept).toBe('image/png,image/jpeg');
    expect(input.multiple).toBe(true);
  });

  it('sends two files picked together one after the other and emits them in pick order', async () => {
    const { fixture, component, el } = setup();
    fixture.componentRef.setInput('multiple', true);
    const uploaded = vi.fn();
    component.uploaded.subscribe(uploaded);

    triggerBrowse(fixture, [makeFile('a.png'), makeFile('b.png')]);
    await fixture.whenStable();

    // Both files are listed as uploading at once, but only the first request is open.
    expect(el.querySelectorAll('li')).toHaveLength(2);
    const first = httpMock.expectOne(URL);
    expect(uploadedName(first.request.body)).toBe('a.png');
    first.flush([{ fileName: 'a.png' }]);

    const second = httpMock.expectOne(URL);
    expect(uploadedName(second.request.body)).toBe('b.png');
    second.flush([{ fileName: 'b.png' }]);

    expect(uploaded.mock.calls.map(([body]) => body)).toEqual([
      [{ fileName: 'a.png' }],
      [{ fileName: 'b.png' }],
    ]);
  });

  it('keeps the pick order across two picks made in a row', () => {
    const { fixture, component } = setup();
    const uploaded = vi.fn();
    component.uploaded.subscribe(uploaded);

    triggerBrowse(fixture, [makeFile('a.png')]);
    triggerDrop(fixture, [makeFile('b.png')]);

    const first = httpMock.expectOne(URL);
    expect(uploadedName(first.request.body)).toBe('a.png');
    first.flush('a');
    const second = httpMock.expectOne(URL);
    expect(uploadedName(second.request.body)).toBe('b.png');
    second.flush('b');

    expect(uploaded.mock.calls.map(([body]) => body)).toEqual(['a', 'b']);
  });

  it('a failed upload does not stop the next one', () => {
    const { fixture, component, notifications } = setup();
    fixture.componentRef.setInput('multiple', true);
    const uploaded = vi.fn();
    const failed = vi.fn();
    component.uploaded.subscribe(uploaded);
    component.failed.subscribe(failed);

    triggerBrowse(fixture, [makeFile('a.png'), makeFile('b.png')]);
    httpMock.expectOne(URL).flush('nope', { status: 500, statusText: 'Server Error' });

    const second = httpMock.expectOne(URL);
    expect(uploadedName(second.request.body)).toBe('b.png');
    second.flush([{ fileName: 'b.png' }]);

    expect(failed).toHaveBeenCalledWith({ fileName: 'a.png', status: 500 });
    expect(notifications.error).toHaveBeenCalledTimes(1);
    expect(uploaded).toHaveBeenCalledExactlyOnceWith([{ fileName: 'b.png' }]);
  });

  it('blocks all input when disabled', async () => {
    const { fixture, el } = setup();
    fixture.componentRef.setInput('disabled', true);
    await fixture.whenStable();

    triggerDrop(fixture, [makeFile('a.png')]);
    triggerBrowse(fixture, [makeFile('a.png')]);
    httpMock.expectNone(URL);

    const button = el.querySelector('button') as HTMLButtonElement;
    expect(button.disabled).toBe(true);
  });

  it('requests a text response and emits the string body in text mode', () => {
    const { fixture, component } = setup();
    const uploaded = vi.fn();
    component.uploaded.subscribe(uploaded);
    fixture.componentRef.setInput('responseType', 'text');

    triggerBrowse(fixture, [makeFile('thumb.png', 'image/png')]);
    const req = httpMock.expectOne(URL);
    expect(req.request.responseType).toBe('text');
    req.flush('repo/1/custom_thumb_256.png');

    expect(uploaded).toHaveBeenCalledWith('repo/1/custom_thumb_256.png');
  });
});
