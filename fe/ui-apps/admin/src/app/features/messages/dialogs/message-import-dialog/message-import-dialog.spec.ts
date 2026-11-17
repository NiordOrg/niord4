import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal, WritableSignal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { provideFakeAuthFacade } from '../../../../shared/testing/fake-auth';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';
import { TagsApiService } from '../../services/api/tags-api.service';
import { MessageImportDialog } from './message-import-dialog';

const API_BASE_URL = '/rest';
const IMPORT_URL = `${API_BASE_URL}/message-io/import`;

/** Surface the protected/private members the tests drive without `any`. */
interface DialogAccess {
  open(): void;
  onFileSelected(event: Event): void;
  onDrop(event: DragEvent): void;
  selectedFile(): File | null;
  form: MessageImportDialog['form'];
  assignNewUids: WritableSignal<boolean>;
  preserveStatus: WritableSignal<boolean>;
  createBaseData: WritableSignal<boolean>;
  assignDefaultSeries: WritableSignal<boolean>;
  upload(): void;
  acceptsFile(name: string): boolean;
}

function setup(seriesIds: readonly string[] = ['NW-001']) {
  const tagsApi = {
    searchTags: vi.fn(() => of([])),
    getTagsByIds: vi.fn(() => of([])),
  } as unknown as TagsApiService;

  const domain = {
    domainMessageSeriesIds: signal<readonly string[]>(seriesIds),
  } as unknown as DomainFacade;

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideHttpClient(),
      provideHttpClientTesting(),
      provideRouter([]),
      provideFakeAuthFacade({ isAuthenticated: true, admin: true }),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: API_BASE_URL, appName: 'Niord' } },
      { provide: DomainFacade, useValue: domain },
      { provide: TagsApiService, useValue: tagsApi },
    ],
  });

  const fixture = TestBed.createComponent(MessageImportDialog);
  const comp = fixture.componentInstance as unknown as DialogAccess;
  const http = TestBed.inject(HttpTestingController);
  fixture.detectChanges();
  return { fixture, comp, http };
}

function mkFile(name: string): File {
  return new File(['zip-bytes'], name, { type: 'application/zip' });
}

function fileChangeEvent(file: File | undefined): Event {
  const input = { files: file ? [file] : [], value: 'x' } as unknown as HTMLInputElement;
  return { target: input } as unknown as Event;
}

function dropEvent(file: File | undefined): DragEvent {
  return {
    preventDefault: vi.fn(),
    dataTransfer: { files: file ? [file] : [] },
  } as unknown as DragEvent;
}

/** Reads a JSON-string FormData part back into an object. */
function dataPart(body: FormData): Record<string, unknown> {
  return JSON.parse(body.get('data') as string) as Record<string, unknown>;
}

describe('MessageImportDialog — file acceptance', () => {
  beforeEach(() => undefined);

  it('accepts a .zip file (case-insensitive)', () => {
    const { comp } = setup();
    expect(comp.acceptsFile('archive.zip')).toBe(true);
    expect(comp.acceptsFile('ARCHIVE.ZIP')).toBe(true);
  });

  it('rejects a non-zip file', () => {
    const { comp } = setup();
    expect(comp.acceptsFile('archive.tar')).toBe(false);
    expect(comp.acceptsFile('notes.txt')).toBe(false);
  });

  it('stores a chosen .zip file from the file input', () => {
    const { comp } = setup();
    comp.onFileSelected(fileChangeEvent(mkFile('msgs.zip')));
    expect(comp.selectedFile()?.name).toBe('msgs.zip');
  });

  it('ignores a non-zip file chosen via the file input', () => {
    const { comp } = setup();
    comp.onFileSelected(fileChangeEvent(mkFile('msgs.txt')));
    expect(comp.selectedFile()).toBeNull();
  });

  it('stores a dropped .zip file but ignores a dropped non-zip', () => {
    const { comp } = setup();
    comp.onDrop(dropEvent(mkFile('drop.zip')));
    expect(comp.selectedFile()?.name).toBe('drop.zip');

    comp.onDrop(dropEvent(mkFile('drop.tar')));
    // The non-zip drop is ignored, leaving the prior selection intact.
    expect(comp.selectedFile()?.name).toBe('drop.zip');
  });
});

describe('MessageImportDialog — upload FormData assembly', () => {
  it('posts a two-part FormData (file + JSON data) to the import endpoint', () => {
    const { comp, http } = setup(['NW-001']);
    comp.open();
    comp.onFileSelected(fileChangeEvent(mkFile('archive.zip')));
    comp.upload();

    const req = http.expectOne(IMPORT_URL);
    expect(req.request.method).toBe('POST');
    expect(req.request.responseType).toBe('text');

    const body = req.request.body as FormData;
    expect(body).toBeInstanceOf(FormData);
    expect((body.get('file') as File).name).toBe('archive.zip');
    expect(typeof body.get('data')).toBe('string');

    req.flush('ok');
    http.verify();
  });

  it('includes all boolean flags and seriesId in the JSON data part', () => {
    const { comp, http } = setup(['NW-001']);
    comp.open();
    comp.assignNewUids.set(true);
    comp.preserveStatus.set(true);
    comp.createBaseData.set(true);
    comp.assignDefaultSeries.set(true);
    comp.form.seriesId().value.set('NW-001');
    comp.onFileSelected(fileChangeEvent(mkFile('archive.zip')));
    comp.upload();

    const req = http.expectOne(IMPORT_URL);
    const data = dataPart(req.request.body as FormData);
    expect(data['assignNewUids']).toBe(true);
    expect(data['preserveStatus']).toBe(true);
    expect(data['createBaseData']).toBe(true);
    expect(data['assignDefaultSeries']).toBe(true);
    expect(data['seriesId']).toBe('NW-001');

    req.flush('ok');
    http.verify();
  });

  it('omits tagId from the data part when no tag is selected', () => {
    const { comp, http } = setup(['NW-001']);
    comp.open();
    comp.onFileSelected(fileChangeEvent(mkFile('archive.zip')));
    comp.upload();

    const req = http.expectOne(IMPORT_URL);
    const data = dataPart(req.request.body as FormData);
    expect('tagId' in data).toBe(false);

    req.flush('ok');
    http.verify();
  });

  it('includes tagId in the data part when a tag is selected', () => {
    const { comp, http } = setup(['NW-001']);
    comp.open();
    comp.form.tagId().value.set('tag-42');
    comp.onFileSelected(fileChangeEvent(mkFile('archive.zip')));
    comp.upload();

    const req = http.expectOne(IMPORT_URL);
    const data = dataPart(req.request.body as FormData);
    expect(data['tagId']).toBe('tag-42');

    req.flush('ok');
    http.verify();
  });

  it('does not post when no file has been selected', () => {
    const { comp, http } = setup(['NW-001']);
    comp.open();
    comp.upload();
    http.expectNone(IMPORT_URL);
    http.verify();
  });

  it('does not post when the form is invalid (no series available to seed seriesId)', () => {
    const { comp, http } = setup([]);
    comp.open();
    comp.onFileSelected(fileChangeEvent(mkFile('archive.zip')));
    comp.upload();
    http.expectNone(IMPORT_URL);
    http.verify();
  });
});
