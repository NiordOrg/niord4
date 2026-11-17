import { TestBed } from '@angular/core/testing';

import { MessageUrlBuilderService } from './message-url-builder.service';
import { APP_CONFIG } from '../../../core/config/app-config.token';

describe('MessageUrlBuilderService', () => {
  let service: MessageUrlBuilderService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [{ provide: APP_CONFIG, useValue: { apiBaseUrl: '/rest' } }],
    });
    service = TestBed.inject(MessageUrlBuilderService);
  });

  describe('getAttachmentThumbnailUrl', () => {
    it('should build a thumbnail URL preserving path separators', () => {
      const url = service.getAttachmentThumbnailUrl('messages/123/file.png');
      expect(url).toBe('/rest/repo/thumb/messages/123/file.png?size=40');
    });

    it('should build a thumbnail URL with custom size', () => {
      const url = service.getAttachmentThumbnailUrl('messages/123/file.png', 100);
      expect(url).toBe('/rest/repo/thumb/messages/123/file.png?size=100');
    });

    it('should pass through already-URL-encoded paths unchanged (no double-encoding)', () => {
      const url = service.getAttachmentThumbnailUrl(
        'messages/6/83/uuid/2/direkte%20i%20SIA%20PC.png',
      );
      expect(url).toBe('/rest/repo/thumb/messages/6/83/uuid/2/direkte%20i%20SIA%20PC.png?size=40');
    });

    it('should keep a # in a file name out of the fragment, ahead of the size', () => {
      expect(service.getAttachmentThumbnailUrl('messages/123/chart #12.png')).toBe(
        '/rest/repo/thumb/messages/123/chart%20%2312.png?size=40',
      );
    });

    it('should return empty string for empty path', () => {
      expect(service.getAttachmentThumbnailUrl('')).toBe('');
    });
  });

  describe('getAttachmentFileUrl', () => {
    it('should build a file URL preserving path separators', () => {
      const url = service.getAttachmentFileUrl('messages/123/file.pdf');
      expect(url).toBe('/rest/repo/file/messages/123/file.pdf');
    });

    it('should pass through already-URL-encoded paths unchanged (no double-encoding)', () => {
      const url = service.getAttachmentFileUrl('messages/123/my%20file.pdf');
      expect(url).toBe('/rest/repo/file/messages/123/my%20file.pdf');
    });

    it('should encode a file name as it was uploaded', () => {
      const url = service.getAttachmentFileUrl('messages/123/my file.pdf');
      expect(url).toBe('/rest/repo/file/messages/123/my%20file.pdf');
    });

    it('should keep a #, a ? and a % in a file name part of the name', () => {
      expect(service.getAttachmentFileUrl('messages/123/Søkort #12.pdf')).toBe(
        '/rest/repo/file/messages/123/S%C3%B8kort%20%2312.pdf',
      );
      expect(service.getAttachmentFileUrl('messages/123/Hvad nu?.pdf')).toBe(
        '/rest/repo/file/messages/123/Hvad%20nu%3F.pdf',
      );
      expect(service.getAttachmentFileUrl('messages/123/100% dækning.pdf')).toBe(
        '/rest/repo/file/messages/123/100%25%20d%C3%A6kning.pdf',
      );
    });

    it('should return empty string for empty path', () => {
      expect(service.getAttachmentFileUrl('')).toBe('');
    });
  });

  describe('thumbnailUrl', () => {
    it('should build a thumbnail URL with the apiBaseUrl prefix', () => {
      expect(service.thumbnailUrl('uuid-1')).toBe('/rest/message-map-image/uuid-1.png');
    });

    it('should URL-encode the path segment', () => {
      expect(service.thumbnailUrl('id with spaces')).toBe(
        '/rest/message-map-image/id%20with%20spaces.png',
      );
    });
  });
});
