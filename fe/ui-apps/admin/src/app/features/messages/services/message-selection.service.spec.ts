import { TestBed } from '@angular/core/testing';

import { MessageSelectionService } from './message-selection.service';
import { Message } from '../models/message.model';

function createMessage(id: string, shortId?: string): Message {
  return { id, shortId, status: 'PUBLISHED' };
}

describe('MessageSelectionService', () => {
  let service: MessageSelectionService;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    service = TestBed.inject(MessageSelectionService);
  });

  it('should start empty', () => {
    expect(service.isEmpty()).toBe(true);
    expect(service.selectedCount()).toBe(0);
    expect(service.selectedIds()).toEqual([]);
    expect(service.selectedMessages()).toEqual([]);
  });

  it('should select a message', () => {
    const msg = createMessage('m1', 'NW-001');
    service.select(msg);
    expect(service.selectedCount()).toBe(1);
    expect(service.isSelected('m1')).toBe(true);
    expect(service.selectedIds()).toEqual(['m1']);
    expect(service.selectedMessages()).toEqual([msg]);
  });

  it('should not duplicate a selection', () => {
    const msg = createMessage('m1');
    service.select(msg);
    service.select(msg);
    expect(service.selectedCount()).toBe(1);
  });

  it('should remove a message', () => {
    service.select(createMessage('m1'));
    service.select(createMessage('m2'));
    service.remove('m1');
    expect(service.selectedCount()).toBe(1);
    expect(service.isSelected('m1')).toBe(false);
    expect(service.isSelected('m2')).toBe(true);
  });

  it('should toggle selection', () => {
    const msg = createMessage('m1');
    service.toggle(msg);
    expect(service.isSelected('m1')).toBe(true);
    service.toggle(msg);
    expect(service.isSelected('m1')).toBe(false);
  });

  it('should clear all selections', () => {
    service.select(createMessage('m1'));
    service.select(createMessage('m2'));
    service.clear();
    expect(service.isEmpty()).toBe(true);
    expect(service.selectedCount()).toBe(0);
  });

  it('should select loaded messages without duplicating existing', () => {
    service.select(createMessage('m1'));
    service.selectLoaded([createMessage('m1'), createMessage('m2'), createMessage('m3')]);
    expect(service.selectedCount()).toBe(3);
  });

  it('should toggle all loaded: clear when non-empty', () => {
    service.select(createMessage('m1'));
    service.toggleAllLoaded([createMessage('m1'), createMessage('m2')]);
    expect(service.isEmpty()).toBe(true);
  });

  it('should toggle all loaded: select when empty', () => {
    const messages = [createMessage('m1'), createMessage('m2')];
    service.toggleAllLoaded(messages);
    expect(service.selectedCount()).toBe(2);
  });

  it('should refresh selected snapshots from search results', () => {
    const original = createMessage('m1', 'OLD');
    service.select(original);

    const updated = createMessage('m1', 'NEW');
    service.refreshSnapshotsFromSearchResults([updated, createMessage('m3')]);

    expect(service.selectedMessages()[0].shortId).toBe('NEW');
    expect(service.selectedCount()).toBe(1);
  });

  it('should keep previous snapshot if not in search results', () => {
    const msg = createMessage('m1', 'ORIGINAL');
    service.select(msg);
    service.refreshSnapshotsFromSearchResults([createMessage('m99')]);
    expect(service.selectedMessages()[0].shortId).toBe('ORIGINAL');
  });

  it('should patch snapshot statuses from a bulk status update', () => {
    service.select(createMessage('m1')); // PUBLISHED
    service.select(createMessage('m2')); // PUBLISHED
    service.applyStatusUpdates([{ messageId: 'm1', status: 'CANCELLED' }]);
    expect(service.selectedMessages()[0].status).toBe('CANCELLED');
    // Untouched entries keep their status.
    expect(service.selectedMessages()[1].status).toBe('PUBLISHED');
  });

  it('applyStatusUpdates ignores ids that are not selected and an empty update list', () => {
    service.select(createMessage('m1'));
    service.applyStatusUpdates([{ messageId: 'not-selected', status: 'DRAFT' }]);
    service.applyStatusUpdates([]);
    expect(service.selectedMessages()[0].status).toBe('PUBLISHED');
    expect(service.selectedCount()).toBe(1);
  });

  it('should preserve insertion order', () => {
    service.select(createMessage('m3'));
    service.select(createMessage('m1'));
    service.select(createMessage('m2'));
    expect(service.selectedIds()).toEqual(['m3', 'm1', 'm2']);
  });
});
