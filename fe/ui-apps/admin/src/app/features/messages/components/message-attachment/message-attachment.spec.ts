import { TestBed } from '@angular/core/testing';

import { MessageAttachment } from './message-attachment';
import { MessageUrlBuilderService } from '../../services/message-url-builder.service';
import { MessageAttachmentTypeService } from '../../services/message-attachment-type.service';
import { MessageDescriptionService } from '../../services/message-description.service';
import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { MessageAttachment as MessageAttachmentModel } from '../../models/message.model';

const makeAttachment = (
  overrides: Partial<MessageAttachmentModel> = {},
): MessageAttachmentModel => ({
  path: 'messages/123/file.png',
  fileName: 'file.png',
  type: 'image/png',
  ...overrides,
});

const mockDescriptionService = {
  getDescription: () => undefined,
  getTitle: () => '',
  getPublication: () => '',
  getInternalPublication: () => '',
  getSource: () => '',
};

describe('MessageAttachment', () => {
  function createComponent(attachment: MessageAttachmentModel, size?: number) {
    const fixture = TestBed.createComponent(MessageAttachment);
    fixture.componentRef.setInput('attachment', attachment);
    if (size !== undefined) {
      fixture.componentRef.setInput('size', size);
    }
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [MessageAttachment],
      providers: [
        { provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } },
        MessageUrlBuilderService,
        MessageAttachmentTypeService,
        { provide: MessageDescriptionService, useValue: mockDescriptionService },
      ],
    });
  });

  describe('thumbnailUrl', () => {
    it('should build thumbnail URL preserving path separators', () => {
      const { componentInstance: comp } = createComponent(makeAttachment());
      expect(comp.thumbnailUrl()).toBe('/rest/repo/thumb/messages/123/file.png?size=40');
    });

    it('should use custom size input', () => {
      const { componentInstance: comp } = createComponent(makeAttachment(), 80);
      expect(comp.thumbnailUrl()).toBe('/rest/repo/thumb/messages/123/file.png?size=80');
    });

    it('should return empty string when attachment has no path', () => {
      const { componentInstance: comp } = createComponent(makeAttachment({ path: undefined }));
      expect(comp.thumbnailUrl()).toBe('');
    });
  });

  describe('isImage', () => {
    it('should be true for image content type', () => {
      const { componentInstance: comp } = createComponent(makeAttachment({ type: 'image/png' }));
      expect(comp.isImage()).toBe(true);
    });

    it('should be false for video content type', () => {
      const { componentInstance: comp } = createComponent(makeAttachment({ type: 'video/mp4' }));
      expect(comp.isImage()).toBe(false);
    });

    it('should be false when type is undefined', () => {
      const { componentInstance: comp } = createComponent(makeAttachment({ type: undefined }));
      expect(comp.isImage()).toBe(false);
    });
  });

  describe('caption', () => {
    it('should return first description caption', () => {
      const { componentInstance: comp } = createComponent(
        makeAttachment({
          descs: [{ lang: 'en', caption: 'My caption' }],
        }),
      );
      expect(comp.caption()).toBe('My caption');
    });

    it('should return empty string when no descs', () => {
      const { componentInstance: comp } = createComponent(makeAttachment({ descs: undefined }));
      expect(comp.caption()).toBe('');
    });

    it('should return empty string when descs list is empty', () => {
      const { componentInstance: comp } = createComponent(makeAttachment({ descs: [] }));
      expect(comp.caption()).toBe('');
    });
  });

  describe('displayLabel', () => {
    it('should prefer caption over fileName', () => {
      const { componentInstance: comp } = createComponent(
        makeAttachment({
          fileName: 'file.png',
          descs: [{ caption: 'Nice image' }],
        }),
      );
      expect(comp.displayLabel()).toBe('Nice image');
    });

    it('should fall back to fileName when caption is absent', () => {
      const { componentInstance: comp } = createComponent(
        makeAttachment({
          fileName: 'file.png',
          descs: undefined,
        }),
      );
      expect(comp.displayLabel()).toBe('file.png');
    });

    it('should return empty string when neither caption nor fileName exist', () => {
      const { componentInstance: comp } = createComponent(
        makeAttachment({
          fileName: undefined,
          descs: undefined,
        }),
      );
      expect(comp.displayLabel()).toBe('');
    });
  });

  describe('onClick', () => {
    it('should emit the attachment', () => {
      const attachment = makeAttachment();
      const { componentInstance: comp } = createComponent(attachment);
      const emitted: MessageAttachmentModel[] = [];
      comp.clicked.subscribe((a) => emitted.push(a));

      comp.onClick();

      expect(emitted).toEqual([attachment]);
    });
  });
});
