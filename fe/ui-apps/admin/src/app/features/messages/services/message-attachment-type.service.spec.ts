import { TestBed } from '@angular/core/testing';

import { MessageAttachmentTypeService } from './message-attachment-type.service';

describe('MessageAttachmentTypeService', () => {
  let service: MessageAttachmentTypeService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(MessageAttachmentTypeService);
  });

  describe('isImage', () => {
    it('should return true for image/png', () => {
      expect(service.isImage('image/png')).toBe(true);
    });

    it('should return true for image/jpeg', () => {
      expect(service.isImage('image/jpeg')).toBe(true);
    });

    it('should return false for video/mp4', () => {
      expect(service.isImage('video/mp4')).toBe(false);
    });

    it('should return false for application/pdf', () => {
      expect(service.isImage('application/pdf')).toBe(false);
    });

    it('should return false for undefined', () => {
      expect(service.isImage(undefined)).toBe(false);
    });
  });

  describe('isVideo', () => {
    it('should return true for video/mp4', () => {
      expect(service.isVideo('video/mp4')).toBe(true);
    });

    it('should return false for image/png', () => {
      expect(service.isVideo('image/png')).toBe(false);
    });

    it('should return false for undefined', () => {
      expect(service.isVideo(undefined)).toBe(false);
    });
  });

  describe('isPreviewable', () => {
    it('should return true for images', () => {
      expect(service.isPreviewable('image/png')).toBe(true);
    });

    it('should return true for videos', () => {
      expect(service.isPreviewable('video/mp4')).toBe(true);
    });

    it('should return false for PDFs', () => {
      expect(service.isPreviewable('application/pdf')).toBe(false);
    });

    it('should return false for undefined', () => {
      expect(service.isPreviewable(undefined)).toBe(false);
    });
  });
});
