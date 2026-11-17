import { Injectable, inject } from '@angular/core';
import type { Observable } from 'rxjs';

import { NotificationService } from '../../../core/notifications/notification.service';
import type { MessageStatus } from '../../../shared/models/message-status.model';
import type { MessageArea } from '../models/message.model';
import { TagsApiService } from './api/tags-api.service';
import { MessageListFacade } from './message-list.facade';
import { SortAreaDialogService } from '../dialogs/sort-area-dialog/sort-area-dialog.service';

/** Short-lived temp-tag TTL (minutes) for the area-heading sort selection. */
const TEMP_TAG_TTL = 30;

/**
 * Launches the area-sort dialog from its two entry points and refreshes the list once the
 * dialog closes (the reorder mutated the persisted sort order):
 * - `sortAreaHeading` collects the message ids under one area heading into a short-lived temp tag,
 *   then opens the dialog scoped to that tag — so a heading spanning mixed statuses sorts together;
 * - `sortInteractive` opens the dialog with no scope for the user to pick an area + status.
 */
@Injectable({ providedIn: 'root' })
export class MessageAreaSortLauncher {
  private readonly dialog = inject(SortAreaDialogService);
  private readonly list = inject(MessageListFacade);
  private readonly tags = inject(TagsApiService);
  private readonly notifications = inject(NotificationService);

  sortAreaHeading(area: MessageArea): void {
    const ids = this.messageIdsUnderHeading(area.id);
    if (ids.length === 0) return;
    this.tags.createTempMessageTag(ids, TEMP_TAG_TTL).subscribe({
      next: (tag) => this.openThenRefresh(this.dialog.openWithTag(tag.tagId)),
      error: () => this.notifications.error('messages.sortArea.toast.error'),
    });
  }

  sortInteractive(status: MessageStatus): void {
    this.openThenRefresh(this.dialog.openInteractive(status));
  }

  private openThenRefresh(result$: Observable<void | undefined>): void {
    result$.subscribe(() => this.list.refresh());
  }

  /** The contiguous run of message ids under a heading (until the next heading), from the grouped list. */
  private messageIdsUnderHeading(areaId: number): string[] {
    const ids: string[] = [];
    let inArea = false;
    for (const entry of this.list.groupedMessages()) {
      if (entry.areaHeading) {
        inArea = entry.areaHeading.id === areaId;
      }
      if (inArea) {
        ids.push(entry.message.id);
      }
    }
    return ids;
  }
}
