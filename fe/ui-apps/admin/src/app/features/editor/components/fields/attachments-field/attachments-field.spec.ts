import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it, vi } from 'vitest';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { FileUpload } from '../../../../../shared/components/file-upload/file-upload';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { MessageDescriptionService } from '../../../../messages/services/message-description.service';
import { MessagesApiService } from '../../../../messages/services/api/messages-api.service';
import type { Message, MessageAttachment } from '../../../../messages/models/message.model';
import { EditorFacade } from '../../../services/editor-facade';
import { EditorFieldsService } from '../../../services/editor-fields.service';
import { AttachmentsField } from './attachments-field';

class StubFacade {
  readonly editableNow = signal(true);
  readonly message = signal<Message>({ id: 'm', attachments: [] });
  addAttachments = vi.fn();
  updateAttachment = vi.fn();
  removeAttachment = vi.fn();
  moveAttachment = vi.fn();
}

// Reaches the component's protected drop handler (the CDK drop event is not simulated).
interface DropHandle {
  onDrop(event: { previousIndex: number; currentIndex: number }): void;
}

class StubFields {
  readonly expanded = signal(true);
  isExpanded = () => this.expanded();
  edited = vi.fn(() => false);
  registerLabel = vi.fn();
  unregisterLabel = vi.fn();
  toggle = vi.fn();
  expand = vi.fn();
  collapse = vi.fn();
  focusNext = vi.fn();
  focusPrevious = vi.fn();
}

const threeLanguages = {
  modelLanguages: [
    { value: 'en', label: 'English' },
    { value: 'da', label: 'Dansk' },
    { value: 'de', label: 'Deutsch' },
  ],
};

const att = (over: Partial<MessageAttachment> = {}): MessageAttachment => ({
  path: 'messages/uid/3/photo.png',
  fileName: 'photo.png',
  fileSize: 2048,
  type: 'image/png',
  ...over,
});

function setup() {
  const facade = new StubFacade();
  const fields = new StubFields();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: EditorFacade, useValue: facade },
      { provide: EditorFieldsService, useValue: fields },
      { provide: LanguageService, useValue: threeLanguages },
      { provide: AuthFacade, useValue: { hasRole: () => signal(true) } },
      { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
      { provide: MessagesApiService, useValue: { attachmentUploadRepoPath: () => '/rest/upload' } },
      { provide: MessageDescriptionService, useValue: {} },
      {
        provide: NotificationService,
        useValue: { error: vi.fn(), success: vi.fn(), info: vi.fn() },
      },
    ],
  });
  TestBed.inject(TranslocoService).load('en').subscribe();

  const fixture = TestBed.createComponent(AttachmentsField);
  fixture.autoDetectChanges();
  const el = fixture.nativeElement as HTMLElement;
  return { fixture, facade, fields, el };
}

describe('AttachmentsField', () => {
  it('appends the uploader response through facade.addAttachments (array body)', async () => {
    const { fixture, facade } = setup();
    await fixture.whenStable();

    const uploader = fixture.debugElement.query(By.directive(FileUpload))
      .componentInstance as FileUpload;
    const uploaded = [att({ fileName: 'a.png' }), att({ fileName: 'b.png' })];
    uploader.uploaded.emit(uploaded);

    expect(facade.addAttachments).toHaveBeenCalledTimes(1);
    expect(facade.addAttachments.mock.calls[0][0]).toEqual(uploaded);
  });

  it('defensively wraps a single-object upload response into a one-element list', async () => {
    const { fixture, facade } = setup();
    await fixture.whenStable();

    const uploader = fixture.debugElement.query(By.directive(FileUpload))
      .componentInstance as FileUpload;
    uploader.uploaded.emit(att({ fileName: 'solo.png' }));

    expect(facade.addAttachments).toHaveBeenCalledTimes(1);
    expect(facade.addAttachments.mock.calls[0][0]).toHaveLength(1);
  });

  it('renders no Display select for a non-displayable attachment (PDF)', async () => {
    const { fixture, facade, el } = setup();
    facade.message.set({
      id: 'm',
      attachments: [att({ fileName: 'doc.pdf', type: 'application/pdf' })],
    });
    await fixture.whenStable();

    expect(el.querySelectorAll('select')).toHaveLength(0);
    expect(el.querySelectorAll('input[type="text"][pattern]')).toHaveLength(0);
  });

  it('renders the Display select for an image but no width/height until a display is chosen', async () => {
    const { fixture, facade, el } = setup();
    facade.message.set({ id: 'm', attachments: [att({ display: undefined })] });
    await fixture.whenStable();

    expect(el.querySelectorAll('select')).toHaveLength(1);
    expect(el.querySelectorAll('input[type="text"][pattern]')).toHaveLength(0);
  });

  it('renders the width + height inputs once a display mode is set on a displayable attachment', async () => {
    const { fixture, facade, el } = setup();
    facade.message.set({ id: 'm', attachments: [att({ display: 'ABOVE' })] });
    await fixture.whenStable();

    expect(el.querySelectorAll('select')).toHaveLength(1);
    expect(el.querySelectorAll('input[type="text"][pattern]')).toHaveLength(2);
  });

  it('renders one caption input per configured model language (N=3)', async () => {
    const { fixture, facade, el } = setup();
    facade.message.set({ id: 'm', attachments: [att()] });
    await fixture.whenStable();

    expect(el.querySelectorAll('input[type="text"]:not([pattern])')).toHaveLength(3);
  });

  it('routes a caption edit to updateAttachment with a full N-language desc list', async () => {
    const { fixture, facade, el } = setup();
    facade.message.set({
      id: 'm',
      attachments: [att({ descs: [{ lang: 'da', caption: 'Vrag' }] })],
    });
    await fixture.whenStable();

    const enInput = el.querySelector('input[type="text"]:not([pattern])') as HTMLInputElement;
    enInput.value = 'Wreck';
    enInput.dispatchEvent(new Event('input'));

    expect(facade.updateAttachment).toHaveBeenCalledTimes(1);
    const [index, patch] = facade.updateAttachment.mock.calls[0];
    expect(index).toBe(0);
    expect(patch.descs).toEqual([
      { lang: 'en', caption: 'Wreck' },
      { lang: 'da', caption: 'Vrag' },
      { lang: 'de', caption: undefined },
    ]);
  });

  it('routes a caption edit to the RIGHT attachment when several exist (per-row wiring)', async () => {
    const { fixture, facade, el } = setup();
    facade.message.set({
      id: 'm',
      attachments: [att({ fileName: 'a.png' }), att({ fileName: 'b.png' })],
    });
    await fixture.whenStable();

    // The second attachment's first (EN) caption input — its edit must target index 1, not 0.
    const rows = el.querySelectorAll('tbody tr');
    const secondRowEnInput = rows[1].querySelector(
      'input[type="text"]:not([pattern])',
    ) as HTMLInputElement;
    secondRowEnInput.value = 'Second';
    secondRowEnInput.dispatchEvent(new Event('input'));

    expect(facade.updateAttachment).toHaveBeenCalledTimes(1);
    expect(facade.updateAttachment.mock.calls[0][0]).toBe(1);
  });

  it('opens the attachment file in a new tab when the file name is activated (show)', async () => {
    const { fixture, facade, el } = setup();
    facade.message.set({ id: 'm', attachments: [att({ path: 'messages/uid/3/photo.png' })] });
    await fixture.whenStable();
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null);

    (el.querySelector('.link-button') as HTMLButtonElement).click();

    expect(openSpy).toHaveBeenCalledWith(
      '/rest/repo/file/messages/uid/3/photo.png',
      '_blank',
      'noopener',
    );
    openSpy.mockRestore();
  });

  it('deletes an attachment through facade.removeAttachment', async () => {
    const { fixture, facade, el } = setup();
    facade.message.set({ id: 'm', attachments: [att()] });
    await fixture.whenStable();

    (el.querySelector('[data-delete]') as HTMLButtonElement).click();

    expect(facade.removeAttachment).toHaveBeenCalledWith(0);
  });

  it('a row dropped at another place moves the attachment through facade.moveAttachment', async () => {
    const { fixture, facade } = setup();
    facade.message.set({
      id: 'm',
      attachments: [att({ fileName: 'a.png' }), att({ fileName: 'b.png' })],
    });
    await fixture.whenStable();

    (fixture.componentInstance as unknown as DropHandle).onDrop({
      previousIndex: 0,
      currentIndex: 1,
    });

    expect(facade.moveAttachment).toHaveBeenCalledWith(0, 1);
  });

  it('each row has a reorder grip, disabled when the message cannot be edited', async () => {
    const { fixture, facade, el } = setup();
    facade.message.set({
      id: 'm',
      attachments: [att({ fileName: 'a.png' }), att({ fileName: 'b.png' })],
    });
    await fixture.whenStable();

    const grips = el.querySelectorAll<HTMLButtonElement>('[data-reorder]');
    expect(grips).toHaveLength(2);
    expect(grips[0].getAttribute('aria-label')).toBe('editor.attachments.reorder');
    expect(grips[0].disabled).toBe(false);

    facade.editableNow.set(false);
    await fixture.whenStable();
    expect(grips[0].disabled).toBe(true);
  });

  it('the arrow keys on a grip move the row one place and keep the focus on its grip', async () => {
    const { fixture, facade, el } = setup();
    const files = [att({ fileName: 'a.png' }), att({ fileName: 'b.png' })];
    facade.message.set({ id: 'm', attachments: files });
    // The stub applies the move, as the facade would.
    facade.moveAttachment.mockImplementation((from: number, to: number) => {
      const next = [...files];
      const [moved] = next.splice(from, 1);
      next.splice(to, 0, moved);
      facade.message.set({ id: 'm', attachments: next });
    });
    await fixture.whenStable();

    const grips = () => el.querySelectorAll<HTMLButtonElement>('[data-reorder]');
    grips()[0].focus();
    grips()[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', cancelable: true }));
    await fixture.whenStable();

    expect(facade.moveAttachment).toHaveBeenCalledExactlyOnceWith(0, 1);
    expect(document.activeElement).toBe(grips()[1]);
    expect(el.querySelectorAll('tbody tr')[1].textContent).toContain('a.png');
  });

  it('the arrow keys do nothing past either end of the list', async () => {
    const { fixture, facade, el } = setup();
    facade.message.set({
      id: 'm',
      attachments: [att({ fileName: 'a.png' }), att({ fileName: 'b.png' })],
    });
    await fixture.whenStable();

    const grips = el.querySelectorAll<HTMLButtonElement>('[data-reorder]');
    grips[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp' }));
    grips[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    grips[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));

    expect(facade.moveAttachment).not.toHaveBeenCalled();
  });

  it('collapsed viewer shows the paperclip count and comma-joined file names', async () => {
    const { fixture, facade, fields, el } = setup();
    fields.expanded.set(false);
    facade.message.set({
      id: 'm',
      attachments: [att({ fileName: 'a.png' }), att({ fileName: 'b.png' })],
    });
    await fixture.whenStable();

    const viewer = el.querySelector('[fieldViewer]') as HTMLElement;
    expect(viewer.textContent).toContain('2');
    expect(viewer.textContent).toContain('a.png');
    expect(viewer.textContent).toContain('b.png');
  });
});
