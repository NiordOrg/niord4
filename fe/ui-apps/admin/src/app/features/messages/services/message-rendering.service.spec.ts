import { TestBed } from '@angular/core/testing';

import { MessageRenderingService } from './message-rendering.service';
import { Message } from '../models/message.model';

describe('MessageRenderingService', () => {
  let service: MessageRenderingService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(MessageRenderingService);
  });

  describe('getIdSuffix', () => {
    it('should return (T) for NM TEMPORARY_NOTICE', () => {
      const msg: Message = { id: '1', mainType: 'NM', type: 'TEMPORARY_NOTICE' };
      expect(service.getIdSuffix(msg)).toBe(' (T)');
    });

    it('should return (P) for NM PRELIMINARY_NOTICE', () => {
      const msg: Message = { id: '1', mainType: 'NM', type: 'PRELIMINARY_NOTICE' };
      expect(service.getIdSuffix(msg)).toBe(' (P)');
    });

    it('should return empty for NM PERMANENT_NOTICE', () => {
      const msg: Message = { id: '1', mainType: 'NM', type: 'PERMANENT_NOTICE' };
      expect(service.getIdSuffix(msg)).toBe('');
    });

    it('should return empty for NW messages', () => {
      const msg: Message = { id: '1', mainType: 'NW', type: 'TEMPORARY_NOTICE' };
      expect(service.getIdSuffix(msg)).toBe('');
    });

    it('should return empty when mainType is undefined', () => {
      const msg: Message = { id: '1' };
      expect(service.getIdSuffix(msg)).toBe('');
    });
  });
});
