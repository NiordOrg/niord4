import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of, tap } from 'rxjs';

import { TAG_SELECTION_STORE } from '../../../core/domain/services/tag-selection-store.token';
import { NotificationService } from '../../../core/notifications/notification.service';
import type { MessageTag } from '../../../shared/models/message-tag.model';
import { TagsApiService } from './api/tags-api.service';

/**
 * Orchestrator for tag-membership mutations behind the tag menus and picker.
 *
 * Each method:
 *   1. Calls the appropriate TagsApiService mutation for the given tag.
 *   2. Fires the success toast on completion (singular vs plural per messageIds.length).
 *   3. Fires the error toast on failure.
 *
 * Returns the affected tag, or undefined when the operation failed or there
 * was nothing to do. Tag PICKING happens upstream in the TagPicker popover —
 * callers always hand a concrete tag in.
 *
 * NOT `providedIn: 'root'` — must be provided at the consuming route so it
 * resolves `TAG_SELECTION_STORE` against the route's override rather than the
 * no-op root factory.
 */
@Injectable()
export class MessageTagActionsService {
  private readonly tagsApi = inject(TagsApiService);
  private readonly lastTagStore = inject(TAG_SELECTION_STORE);
  private readonly notifications = inject(NotificationService);

  addMessagesToTag(
    messageIds: readonly string[],
    tag: MessageTag,
  ): Observable<MessageTag | undefined> {
    if (messageIds.length === 0) {
      return of(undefined);
    }
    return this.callApi(
      this.tagsApi.addMessagesToTag(tag.tagId, messageIds),
      tag,
      messageIds.length === 1 ? 'addedSingle' : 'addedBulk',
    );
  }

  removeMessagesFromTag(
    messageIds: readonly string[],
    tag: MessageTag,
  ): Observable<MessageTag | undefined> {
    if (messageIds.length === 0) {
      return of(undefined);
    }
    return this.callApi(
      this.tagsApi.removeMessagesFromTag(tag.tagId, messageIds),
      tag,
      messageIds.length === 1 ? 'removedSingle' : 'removedBulk',
    );
  }

  addMessagesToLastSelectedTag(messageIds: readonly string[]): Observable<MessageTag | undefined> {
    const lastTag = this.lastTagStore.getLastSelection()();
    if (!lastTag) {
      // Defensive: the menu hides the item when no last tag exists. If we
      // still get called, no-op silently.
      return of(undefined);
    }
    return this.addMessagesToTag(messageIds, lastTag);
  }

  private callApi(
    op$: Observable<unknown>,
    tag: MessageTag,
    severityKey: 'addedSingle' | 'addedBulk' | 'removedSingle' | 'removedBulk',
  ): Observable<MessageTag | undefined> {
    return op$.pipe(
      tap(() =>
        this.notifications.success(`messages.tags.action.${severityKey}`, { name: tag.name }),
      ),
      map(() => tag),
      catchError(() => {
        this.notifications.error('messages.tags.action.error', { name: tag.name });
        return of<MessageTag | undefined>(undefined);
      }),
    );
  }
}
