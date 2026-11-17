import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { form } from '@angular/forms/signals';
import { TranslocoService } from '@jsverse/transloco';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import type { Message } from '../../../../messages/models/message.model';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { ThumbnailMapDialog } from '../../thumbnail-map-dialog/thumbnail-map-dialog';
import { LayoutField } from './layout-field';

const UPLOAD_URL = '/rest/message-map-image/messages/temp/xyz/3';

// jsdom lacks ResizeObserver, which ol.Map (rendered when the capture dialog opens) references.
globalThis.ResizeObserver ??= class {
  observe = (): void => undefined;
  unobserve = (): void => undefined;
  disconnect = (): void => undefined;
} as unknown as typeof ResizeObserver;

class StubFields {
  readonly expanded = signal(true);
  isExpanded = () => this.expanded();
  edited = () => false;
  toggle = vi.fn();
  expand = vi.fn();
  collapse = vi.fn();
  registerLabel = vi.fn();
  unregisterLabel = vi.fn();
  focusNext = vi.fn();
  focusPrevious = vi.fn();
}

class StubFacade {
  readonly messageState = signal<Message | undefined>({
    id: 'm-1',
    editRepoPath: 'messages/temp/xyz',
    revision: 3,
  });
  readonly editableState = signal(true);
  message = () => this.messageState();
  editableNow = () => this.editableState();
  setThumbnail = vi.fn((path: string) => {
    this.messageState.update((m) => (m ? { ...m, thumbnailPath: path } : m));
  });
  clearThumbnail = vi.fn(() => {
    this.messageState.update((m) => (m ? { ...m, thumbnailPath: undefined } : m));
  });
}

function authStub(): AuthFacade {
  return { hasRole: (role: string) => signal(role === 'editor') } as unknown as AuthFacade;
}

@Component({
  imports: [LayoutField],
  template: `<app-layout-field [field]="form.separatePage" />`,
})
class Host {
  readonly model = signal({ separatePage: false });
  readonly form = form(this.model);
}

let httpMock: HttpTestingController;

function setup() {
  const fields = new StubFields();
  const facade = new StubFacade();
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest', appName: 'Niord', helpUrls: {} } },
      { provide: EditorFieldsService, useValue: fields },
      { provide: EditorFacade, useValue: facade },
      { provide: AuthFacade, useValue: authStub() },
      {
        provide: NotificationService,
        useValue: { success: vi.fn(), info: vi.fn(), warn: vi.fn(), error: vi.fn() },
      },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();
  httpMock = TestBed.inject(HttpTestingController);

  const fixture = TestBed.createComponent(Host);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;

  const img = () => el.querySelector<HTMLImageElement>('img');
  const checkbox = () => el.querySelector<HTMLInputElement>('input[type="checkbox"]')!;
  const browseButton = () => el.querySelector<HTMLButtonElement>('app-file-upload button')!;
  const button = (key: string) =>
    [...el.querySelectorAll<HTMLButtonElement>('button')].find((b) =>
      b.textContent?.includes(key),
    )!;
  const uploadFile = () => {
    const input = el.querySelector('input[type="file"]') as HTMLInputElement;
    const file = new File(['img-bytes'], 'thumb.png', { type: 'image/png' });
    const list = { 0: file, length: 1, item: () => file } as unknown as FileList;
    Object.defineProperty(input, 'files', { value: list, configurable: true });
    input.dispatchEvent(new Event('change'));
  };

  return {
    fixture,
    host: fixture.componentInstance,
    fields,
    facade,
    el,
    img,
    checkbox,
    browseButton,
    button,
    uploadFile,
  };
}

afterEach(() => httpMock.verify());

describe('LayoutField', () => {
  it('renders the placeholder box (no img) when the message has no thumbnailPath', async () => {
    const { fixture, el, img } = setup();
    await fixture.whenStable();
    expect(img()).toBeNull();
    expect(el.textContent).toContain('editor.layout.noThumbnail');
  });

  it('uploads to the message-map-image URL, sets the trimmed path and renders a cache-broken img', async () => {
    const { fixture, facade, img, uploadFile } = setup();
    await fixture.whenStable();

    uploadFile();
    const req = httpMock.expectOne(UPLOAD_URL);
    expect(req.request.method).toBe('POST');
    expect(req.request.responseType).toBe('text');
    req.flush('  messages/temp/xyz/3/custom_thumb_256.png\n');
    await fixture.whenStable();

    expect(facade.setThumbnail).toHaveBeenCalledWith('messages/temp/xyz/3/custom_thumb_256.png');
    const src = img()!.getAttribute('src')!;
    expect(src).toMatch(
      /^\/rest\/repo\/file\/messages\/temp\/xyz\/3\/custom_thumb_256\.png\?cb=\d+$/,
    );
  });

  it('bumps the cache-breaker on every upload — a same-path re-upload changes the img src', async () => {
    const { fixture, img, uploadFile } = setup();
    await fixture.whenStable();

    uploadFile();
    httpMock.expectOne(UPLOAD_URL).flush('messages/temp/xyz/3/custom_thumb_256.png');
    await fixture.whenStable();
    const firstSrc = img()!.getAttribute('src')!;

    uploadFile();
    httpMock.expectOne(UPLOAD_URL).flush('messages/temp/xyz/3/custom_thumb_256.png');
    await fixture.whenStable();
    const secondSrc = img()!.getAttribute('src')!;

    expect(secondSrc).not.toBe(firstSrc);
    expect(secondSrc.split('?')[0]).toBe(firstSrc.split('?')[0]);
  });

  it('clears the thumbnail through the facade (which owns the guarded physical delete)', async () => {
    const { fixture, facade, button, img } = setup();
    facade.messageState.update((m) => ({ ...m!, thumbnailPath: 'messages/temp/xyz/2/old.png' }));
    await fixture.whenStable();
    expect(img()).not.toBeNull();

    button('editor.layout.clear').click();
    await fixture.whenStable();

    expect(facade.clearThumbnail).toHaveBeenCalledOnce();
    expect(img()).toBeNull();
  });

  it('enables the select-from-map button and opens the capture dialog on click', async () => {
    const { fixture, button } = setup();
    await fixture.whenStable();
    const selectFromMap = button('editor.layout.selectFromMap');
    expect(selectFromMap.disabled).toBe(false);

    selectFromMap.click();
    await fixture.whenStable();
    const dialog = fixture.debugElement.query(By.directive(ThumbnailMapDialog))
      .componentInstance as ThumbnailMapDialog;
    expect(dialog.visible()).toBe(true);
  });

  it('persists a captured map thumbnail: PUTs the data URL and sets the returned path', async () => {
    const { fixture, facade, img } = setup();
    await fixture.whenStable();

    const dialog = fixture.debugElement.query(By.directive(ThumbnailMapDialog))
      .componentInstance as ThumbnailMapDialog;
    dialog.thumbnailCaptured.emit('data:image/png;base64,AAAA');

    const req = httpMock.expectOne(UPLOAD_URL);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toBe('data:image/png;base64,AAAA');
    req.flush('  messages/temp/xyz/3/custom_thumb_256.png\n');
    await fixture.whenStable();

    expect(facade.setThumbnail).toHaveBeenCalledWith('messages/temp/xyz/3/custom_thumb_256.png');
    expect(img()!.getAttribute('src')).toMatch(
      /^\/rest\/repo\/file\/messages\/temp\/xyz\/3\/custom_thumb_256\.png\?cb=\d+$/,
    );
  });

  it('disables the select-from-map button when not editable', async () => {
    const { fixture, facade, button } = setup();
    facade.editableState.set(false);
    await fixture.whenStable();
    expect(button('editor.layout.selectFromMap').disabled).toBe(true);
  });

  it('disables upload, clear and the separate-page checkbox when not editableNow', async () => {
    const { fixture, facade, browseButton, button, checkbox } = setup();
    facade.messageState.update((m) => ({ ...m!, thumbnailPath: 'messages/temp/xyz/3/t.png' }));
    await fixture.whenStable();
    expect(browseButton().disabled).toBe(false);
    expect(button('editor.layout.clear').disabled).toBe(false);

    facade.editableState.set(false);
    await fixture.whenStable();

    expect(browseButton().disabled).toBe(true);
    expect(button('editor.layout.clear').disabled).toBe(true);
    expect(checkbox().disabled).toBe(true);
  });

  it('disables clear while there is no thumbnail to remove', async () => {
    const { fixture, button } = setup();
    await fixture.whenStable();
    expect(button('editor.layout.clear').disabled).toBe(true);
  });

  it('writes the separate-page checkbox through the field slice', async () => {
    const { fixture, host, checkbox } = setup();
    await fixture.whenStable();

    checkbox().checked = true;
    checkbox().dispatchEvent(new Event('change', { bubbles: true }));
    await fixture.whenStable();

    expect(host.form.separatePage().value()).toBe(true);
  });
});
