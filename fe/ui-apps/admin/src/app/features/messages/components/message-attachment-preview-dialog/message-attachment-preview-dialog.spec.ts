import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';

import { MessageAttachmentPreviewDialog } from './message-attachment-preview-dialog';
import { MessageUrlBuilderService } from '../../services/message-url-builder.service';
import { MessageAttachmentTypeService } from '../../services/message-attachment-type.service';
import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { MessageAttachment } from '../../models/message.model';
import { provideNiordIcons } from '../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../shared/testing/transloco-testing';

const makeAttachment = (overrides: Partial<MessageAttachment> = {}): MessageAttachment => ({
  path: 'messages/123/file.png',
  fileName: 'file.png',
  type: 'image/png',
  ...overrides,
});

describe('MessageAttachmentPreviewDialog', () => {
  function createComponent(attachment: MessageAttachment, visible = true) {
    const fixture = TestBed.createComponent(MessageAttachmentPreviewDialog);
    fixture.componentRef.setInput('attachment', attachment);
    fixture.componentRef.setInput('visible', visible);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [MessageAttachmentPreviewDialog],
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
        MessageUrlBuilderService,
        MessageAttachmentTypeService,
        provideNiordIcons(),
        provideTestTransloco(),
      ],
    });
  });

  describe('fileUrl', () => {
    it('should build a file URL preserving path separators', () => {
      const { componentInstance: comp } = createComponent(makeAttachment());
      expect(comp.fileUrl()).toBe('/rest/repo/file/messages/123/file.png');
    });

    it('should return empty string when path is missing', () => {
      const { componentInstance: comp } = createComponent(makeAttachment({ path: undefined }));
      expect(comp.fileUrl()).toBe('');
    });
  });

  describe('media type detection', () => {
    it('should report image when content type is an image', () => {
      const { componentInstance: comp } = createComponent(makeAttachment({ type: 'image/jpeg' }));
      expect(comp.isImage()).toBe(true);
      expect(comp.isVideo()).toBe(false);
    });

    it('should report video when content type is a video', () => {
      const { componentInstance: comp } = createComponent(makeAttachment({ type: 'video/mp4' }));
      expect(comp.isVideo()).toBe(true);
      expect(comp.isImage()).toBe(false);
    });

    it('should report neither for an unknown file type', () => {
      const { componentInstance: comp } = createComponent(
        makeAttachment({ type: 'application/pdf' }),
      );
      expect(comp.isImage()).toBe(false);
      expect(comp.isVideo()).toBe(false);
    });
  });

  describe('dialogHeader', () => {
    it('should use the file name', () => {
      const { componentInstance: comp } = createComponent(makeAttachment({ fileName: 'doc.pdf' }));
      expect(comp.dialogHeader()).toBe('doc.pdf');
    });

    it('should be empty when file name is missing', () => {
      const { componentInstance: comp } = createComponent(makeAttachment({ fileName: undefined }));
      expect(comp.dialogHeader()).toBe('');
    });
  });

  describe('onHide', () => {
    it('should emit visibleChange(false)', () => {
      const { componentInstance: comp } = createComponent(makeAttachment());
      const emitted: boolean[] = [];
      comp.visibleChange.subscribe((v) => emitted.push(v));

      comp.onHide();

      expect(emitted).toEqual([false]);
    });
  });

  describe('openInNewTab', () => {
    it('should call window.open with the file URL', () => {
      const { componentInstance: comp } = createComponent(makeAttachment());
      const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null);

      comp.openInNewTab();

      expect(openSpy).toHaveBeenCalledWith(
        '/rest/repo/file/messages/123/file.png',
        '_blank',
        'noopener,noreferrer',
      );
      openSpy.mockRestore();
    });

    it('should be a no-op when fileUrl is empty', () => {
      const { componentInstance: comp } = createComponent(makeAttachment({ path: undefined }));
      const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null);

      comp.openInNewTab();

      expect(openSpy).not.toHaveBeenCalled();
      openSpy.mockRestore();
    });
  });
});
