import { Component, DestroyRef, computed, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  CdkDrag,
  CdkDragHandle,
  type CdkDragDrop,
  CdkDropList,
  moveItemInArray,
} from '@angular/cdk/drag-drop';
import { TranslocoPipe } from '@jsverse/transloco';

import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { Dialog } from '../../../../shared/components/dialog/dialog';
import { HelpAnchor } from '../../../../core/main-shell/help/help-anchor';
import { Icon } from '../../../../shared/icons/icon';
import type { MessageStatus } from '../../../../shared/models/message-status.model';
import { AreasField } from '../../components/areas-field/areas-field';
import { MessageAreas } from '../../components/message-areas/message-areas';
import { MessageIdBadge } from '../../components/message-id-badge/message-id-badge';
import type { Message } from '../../models/message.model';
import type { AreaRef } from '../../models/messages-filter-params.model';
import { MessagesApiService } from '../../services/api/messages-api.service';
import { MessagesAreaSortApiService } from '../../services/api/messages-area-sort-api.service';
import { SortAreaDialogService } from './sort-area-dialog.service';

/** The status filters offered in interactive mode. */
const STATUS_OPTIONS: readonly MessageStatus[] = ['PUBLISHED', 'VERIFIED', 'DRAFT'];
const MAX_ROWS = 1000;

/**
 * Area-sort dialog: lists the messages of one area in area-sort order and lets an editor
 * drag rows to reorder them. Each drop resolves the dropped row's new neighbours and calls
 * `change-area-sort-order` (after/before id semantics), then reloads to show the authoritative order.
 * Temp-tag mode hides the pickers; interactive mode shows an area picker + status toggle.
 */
@Component({
  selector: 'app-sort-area-dialog',
  imports: [
    Dialog,
    HelpAnchor,
    Icon,
    CdkDropList,
    CdkDrag,
    CdkDragHandle,
    AreasField,
    MessageAreas,
    MessageIdBadge,
    TranslocoPipe,
  ],
  templateUrl: './sort-area-dialog.html',
})
export class SortAreaDialog {
  protected readonly service = inject(SortAreaDialogService);
  private readonly api = inject(MessagesApiService);
  private readonly areaSort = inject(MessagesAreaSortApiService);
  private readonly notifications = inject(NotificationService);
  private readonly domain = inject(DomainFacade);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly statusOptions = STATUS_OPTIONS;
  protected readonly domainId = computed(() => this.domain.sessionDomain()?.domainId ?? null);

  protected readonly status = signal<MessageStatus>('PUBLISHED');
  protected readonly pickedAreas = signal<readonly AreaRef[]>([]);
  protected readonly rows = signal<readonly Message[]>([]);
  protected readonly loading = signal(false);

  protected readonly tagMode = computed(() => this.service.tagId() !== null);
  private readonly pickedArea = computed(() => this.pickedAreas()[0] ?? null);

  /** The search query for the current scope, or null when interactive mode has no area picked yet. */
  private readonly queryParams = computed<string | null>(() => {
    if (!this.service.visible()) return null;
    const tagId = this.service.tagId();
    if (tagId) return `tag=${encodeURIComponent(tagId)}&sortBy=AREA&sortOrder=ASC`;
    const area = this.pickedArea();
    if (!area) return null;
    return `area=${area.id}&status=${this.status()}&sortBy=AREA&sortOrder=ASC`;
  });

  private readonly reloadTick = signal(0);
  private reqSeq = 0;

  constructor() {
    // Seed the interactive controls each time the dialog opens; clear rows on close.
    effect(() => {
      if (this.service.visible()) {
        untracked(() => {
          this.status.set(this.service.initialStatus());
          this.pickedAreas.set([]);
        });
      } else {
        untracked(() => this.rows.set([]));
      }
    });

    // Fetch whenever the scope changes (or a reorder asks for a reload).
    effect(() => {
      const params = this.queryParams();
      this.reloadTick();
      untracked(() => this.fetch(params));
    });
  }

  private fetch(params: string | null): void {
    if (params === null) {
      this.rows.set([]);
      return;
    }
    const seq = ++this.reqSeq;
    this.loading.set(true);
    this.api
      .searchMessages(params, 0, MAX_ROWS)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          if (seq !== this.reqSeq) return;
          this.rows.set(result.data);
          this.loading.set(false);
        },
        error: () => {
          if (seq !== this.reqSeq) return;
          this.rows.set([]);
          this.loading.set(false);
        },
      });
  }

  protected onDrop(event: CdkDragDrop<readonly Message[]>): void {
    if (event.previousIndex === event.currentIndex) return;
    const arr = [...this.rows()];
    moveItemInArray(arr, event.previousIndex, event.currentIndex);
    this.rows.set(arr);
    const i = event.currentIndex;
    const id = arr[i].id;
    const afterId = i > 0 ? arr[i - 1].id : null;
    const beforeId = i < arr.length - 1 ? arr[i + 1].id : null;
    this.areaSort.changeAreaSortOrder(id, afterId, beforeId).subscribe({
      next: () => this.reloadTick.update((tick) => tick + 1),
      error: () => {
        this.notifications.error('messages.sortArea.toast.error');
        this.reloadTick.update((tick) => tick + 1);
      },
    });
  }

  protected setStatus(status: MessageStatus): void {
    this.status.set(status);
  }

  /**
   * The interactive area picker sorts one area at a time, so cap the multi-select to a single
   * area — a fresh pick replaces the previous one.
   */
  protected onAreaPicked(areas: readonly AreaRef[]): void {
    const last = areas.at(-1);
    this.pickedAreas.set(last ? [last] : []);
  }
}
