import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { of, throwError } from 'rxjs';

import { TAG_SELECTION_STORE } from '../../../core/domain/services/tag-selection-store.token';
import { NotificationService } from '../../../core/notifications/notification.service';
import type { MessageTag } from '../../../shared/models/message-tag.model';
import { TagsApiService } from './api/tags-api.service';
import { MessageTagActionsService } from './message-tag-actions.service';

function mkTag(opts: Partial<MessageTag> = {}): MessageTag {
  return {
    tagId: 't1',
    name: 'Tag 1',
    type: 'PRIVATE',
    messageCount: 0,
    locked: false,
    ...opts,
  };
}

interface SetupOptions {
  readonly apiError?: boolean;
  readonly lastTag?: MessageTag | undefined;
}

function setup(opts: SetupOptions = {}) {
  const addMessagesToTag = vi.fn(() =>
    opts.apiError ? throwError(() => new Error('boom')) : of(undefined),
  );
  const removeMessagesFromTag = vi.fn(() =>
    opts.apiError ? throwError(() => new Error('boom')) : of(undefined),
  );
  const tagsApi = { addMessagesToTag, removeMessagesFromTag } as unknown as TagsApiService;

  const lastSelection = signal<MessageTag | undefined>(opts.lastTag);
  const store = {
    getLastSelection: () => lastSelection,
    saveLastSelection: vi.fn(),
    clearLastSelection: vi.fn(),
  };

  const success = vi.fn();
  const error = vi.fn();
  const notifications = { success, error } as unknown as NotificationService;

  TestBed.configureTestingModule({
    providers: [
      MessageTagActionsService,
      { provide: TagsApiService, useValue: tagsApi },
      { provide: TAG_SELECTION_STORE, useValue: store },
      { provide: NotificationService, useValue: notifications },
    ],
  });

  return {
    service: TestBed.inject(MessageTagActionsService),
    addMessagesToTag,
    removeMessagesFromTag,
    success,
    error,
  };
}

describe('MessageTagActionsService', () => {
  beforeEach(() => undefined);

  describe('addMessagesToTag', () => {
    it('calls the API and fires addedSingle on count=1', () => {
      const tag = mkTag({ tagId: 'x', name: 'X' });
      const { service, addMessagesToTag, success } = setup();
      const next = vi.fn();
      service.addMessagesToTag(['m1'], tag).subscribe(next);
      expect(addMessagesToTag).toHaveBeenCalledWith('x', ['m1']);
      expect(success).toHaveBeenCalledWith('messages.tags.action.addedSingle', { name: 'X' });
      expect(next).toHaveBeenCalledWith(tag);
    });

    it('count>1 fires addedBulk', () => {
      const tag = mkTag({ name: 'X' });
      const { service, success } = setup();
      service.addMessagesToTag(['m1', 'm2'], tag).subscribe();
      expect(success).toHaveBeenCalledWith('messages.tags.action.addedBulk', { name: 'X' });
    });

    it('API failure → error toast with name param', () => {
      const tag = mkTag({ name: 'X' });
      const { service, success, error } = setup({ apiError: true });
      const next = vi.fn();
      service.addMessagesToTag(['m1'], tag).subscribe(next);
      expect(success).not.toHaveBeenCalled();
      expect(error).toHaveBeenCalledWith('messages.tags.action.error', { name: 'X' });
      expect(next).toHaveBeenCalledWith(undefined);
    });

    it('empty messageIds → no API call, no toast', () => {
      const tag = mkTag();
      const { service, addMessagesToTag, success } = setup();
      service.addMessagesToTag([], tag).subscribe();
      expect(addMessagesToTag).not.toHaveBeenCalled();
      expect(success).not.toHaveBeenCalled();
    });
  });

  describe('removeMessagesFromTag', () => {
    it('count=1 → removedSingle', () => {
      const tag = mkTag({ name: 'X' });
      const { service, removeMessagesFromTag, success } = setup();
      service.removeMessagesFromTag(['m1'], tag).subscribe();
      expect(removeMessagesFromTag).toHaveBeenCalled();
      expect(success).toHaveBeenCalledWith('messages.tags.action.removedSingle', { name: 'X' });
    });

    it('count>1 → removedBulk', () => {
      const tag = mkTag({ name: 'X' });
      const { service, success } = setup();
      service.removeMessagesFromTag(['m1', 'm2'], tag).subscribe();
      expect(success).toHaveBeenCalledWith('messages.tags.action.removedBulk', { name: 'X' });
    });
  });

  describe('addMessagesToLastSelectedTag', () => {
    it('with stored last-tag: calls the add API with it', () => {
      const last = mkTag({ tagId: 'last', name: 'Last' });
      const { service, addMessagesToTag, success } = setup({ lastTag: last });
      service.addMessagesToLastSelectedTag(['m1']).subscribe();
      expect(addMessagesToTag).toHaveBeenCalledWith('last', ['m1']);
      expect(success).toHaveBeenCalledWith('messages.tags.action.addedSingle', { name: 'Last' });
    });

    it('with no stored last-tag: no-op (defensive)', () => {
      const { service, addMessagesToTag, success, error } = setup({ lastTag: undefined });
      const next = vi.fn();
      service.addMessagesToLastSelectedTag(['m1']).subscribe(next);
      expect(addMessagesToTag).not.toHaveBeenCalled();
      expect(success).not.toHaveBeenCalled();
      expect(error).not.toHaveBeenCalled();
      expect(next).toHaveBeenCalledWith(undefined);
    });
  });
});
