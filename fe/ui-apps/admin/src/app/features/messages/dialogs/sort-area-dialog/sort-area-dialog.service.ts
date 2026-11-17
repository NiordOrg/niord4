import { Injectable, signal } from '@angular/core';
import { Observable } from 'rxjs';

import { DialogHostService } from '../../../../shared/dialogs/dialog-host.service';
import type { MessageStatus } from '../../../../shared/models/message-status.model';

/**
 * Opener for the area-sort dialog. Two entry shapes:
 * - `openWithTag` — a temp message-tag already scopes the exact rows (the area-heading "sort…" link
 *   builds one from the messages under that heading); the dialog hides the area/status pickers.
 * - `openInteractive` — no scope yet; the dialog shows an area picker + status toggle so the user
 *   chooses which area's messages to reorder (the Action-menu "Sort area messages" entry).
 *
 * Root-scoped like the other messages dialog services — a single `<app-sort-area-dialog>` mounted on
 * the messages route reads this state.
 */
@Injectable({ providedIn: 'root' })
export class SortAreaDialogService extends DialogHostService<void> {
  /** Non-null = temp-tag mode (pickers hidden); null = interactive area/status pick. */
  readonly tagId = signal<string | null>(null);
  /** Seed for the interactive status toggle (derived from the active list filter). */
  readonly initialStatus = signal<MessageStatus>('PUBLISHED');

  openWithTag(tagId: string): Observable<void | undefined> {
    this.tagId.set(tagId);
    return this.armOpen();
  }

  openInteractive(status: MessageStatus): Observable<void | undefined> {
    this.tagId.set(null);
    this.initialStatus.set(status);
    return this.armOpen();
  }
}
