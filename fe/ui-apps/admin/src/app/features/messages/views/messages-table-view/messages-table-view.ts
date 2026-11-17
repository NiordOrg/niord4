import { Component, computed, effect, inject, signal } from '@angular/core';
import { translateSignal } from '@jsverse/transloco';

import { Icon } from '../../../../shared/icons/icon';
import { EmptySearchResult } from '../../components/empty-search-result/empty-search-result';
import { LoadMoreMessages } from '../../components/load-more-messages/load-more-messages';
import { MessageAreaHeading } from '../../components/message-area-heading/message-area-heading';
import { MessageAreas } from '../../components/message-areas/message-areas';
import { MessageDateColumnHeader } from '../../components/message-date-column-header/message-date-column-header';
import { MessageEventDates } from '../../components/message-event-dates/message-event-dates';
import { MessageIdBadge } from '../../components/message-id-badge/message-id-badge';
import { MessageListStatus } from '../../components/message-list-status/message-list-status';
import { MessageSelectionToggle } from '../../components/message-selection-toggle/message-selection-toggle';
import { MessageRowMenu } from '../../message-details-menu/message-row-menu';
import type { Message } from '../../models/message.model';
import { MessageDetailsDialogService } from '../../services/message-details-dialog.service';
import {
  isMessageDateColumnType,
  type MessageDateColumnType,
} from '../../models/message-date-column-type.model';
import { MessageDescriptionService } from '../../services/message-description.service';
import { MessageEditShortcutService } from '../../services/message-edit-shortcut.service';
import { MessagesFacade } from '../../services/messages-facade.service';
import {
  RowLinkAnchorDirective,
  RowLinkDirective,
} from '../../../../shared/directives/row-link.directive';
import { RowAttentionDirective } from '../../components/row-attention.directive';
import { paintedOnce } from '../../../../shared/utils/next-paint';

const DATE_COLUMN_STORAGE_KEY = 'dateColumnType';

@Component({
  selector: 'app-messages-table-view',
  imports: [
    Icon,
    MessageListStatus,
    EmptySearchResult,
    LoadMoreMessages,
    MessageIdBadge,
    MessageAreas,
    MessageEventDates,
    MessageAreaHeading,
    MessageSelectionToggle,
    MessageDateColumnHeader,
    MessageRowMenu,
    RowLinkDirective,
    RowLinkAnchorDirective,
    RowAttentionDirective,
  ],
  templateUrl: './messages-table-view.html',
})
export class MessagesTableView {
  private readonly descriptionService = inject(MessageDescriptionService);
  private readonly dialog = inject(MessageDetailsDialogService);
  private readonly editShortcut = inject(MessageEditShortcutService);
  protected readonly facade = inject(MessagesFacade);

  /**
   * Whether the view has been painted once. Until then it shows no rows, as while a search is under
   * way, so the frame after a tab click already shows this view; its rows are built in the task after.
   */
  protected readonly painted = paintedOnce();

  readonly dateColumnType = signal<MessageDateColumnType>(this.readFromStorage() ?? 'DATE');

  constructor() {
    // When a date-type sort is chosen anywhere (e.g. the action menu), reflect it
    // in the column header so the visible column matches the active sort field.
    effect(() => {
      const sortBy = this.facade.filter.sortBy();
      if (isMessageDateColumnType(sortBy)) {
        this.setDateColumnType(sortBy);
      }
    });
  }

  readonly headers = {
    id: translateSignal('messages.table.column.id'),
    title: translateSignal('messages.table.column.title'),
    areas: translateSignal('messages.table.column.areas'),
    actions: translateSignal('messages.table.column.actions'),
    selection: translateSignal('messages.table.column.selection'),
  } as const;

  readonly idSortIcon = computed(() =>
    this.facade.filter.sortBy() === 'ID' ? this.sortIconClass() : null,
  );
  readonly areaSortIcon = computed(() =>
    this.facade.filter.sortBy() === 'AREA' ? this.sortIconClass() : null,
  );

  // aria-sort for the sortable column headers — announces which column is sorted and its direction.
  readonly idAriaSort = computed<'ascending' | 'descending' | 'none'>(() => {
    if (this.facade.filter.sortBy() !== 'ID') return 'none';
    return this.facade.filter.sortOrder() === 'ASC' ? 'ascending' : 'descending';
  });
  readonly areaAriaSort = computed<'ascending' | 'descending' | 'none'>(() => {
    if (this.facade.filter.sortBy() !== 'AREA') return 'none';
    return this.facade.filter.sortOrder() === 'ASC' ? 'ascending' : 'descending';
  });

  title(message: Message): string {
    return this.descriptionService.getTitle(message.descs);
  }

  onSortById(): void {
    this.facade.filter.toggleSort('ID');
  }

  onSortByArea(): void {
    this.facade.filter.toggleSort('AREA');
  }

  onSortByDateColumn(): void {
    this.facade.filter.toggleSort(this.dateColumnType());
  }

  onDateTypeChange(type: MessageDateColumnType): void {
    this.setDateColumnType(type);
    // If a date-type sort is currently active, follow the picker so the sort
    // applies to the newly chosen date field (preserving the current order).
    if (isMessageDateColumnType(this.facade.filter.sortBy())) {
      this.facade.filter.setSortBy(type);
    }
  }

  private setDateColumnType(type: MessageDateColumnType): void {
    this.dateColumnType.set(type);
    try {
      localStorage.setItem(DATE_COLUMN_STORAGE_KEY, type);
    } catch {
      // localStorage may throw in private-browsing or restricted environments.
    }
  }

  onToggleSelectAll(): void {
    this.facade.selection.toggleAllLoaded(this.facade.list.messages());
  }

  openDialogFor(message: Message): void {
    // Hold-E + click → edit directly (auth-gated); otherwise open the dialog.
    if (this.editShortcut.tryEdit(message)) {
      return;
    }
    this.dialog.open(
      message.id,
      this.facade.list.messages().map((m) => m.id),
    );
  }

  private readFromStorage(): MessageDateColumnType | null {
    try {
      const raw = localStorage.getItem(DATE_COLUMN_STORAGE_KEY);
      return isMessageDateColumnType(raw) ? raw : null;
    } catch {
      return null;
    }
  }

  private sortIconClass(): string {
    return this.facade.filter.sortOrder() === 'ASC' ? 'lucideArrowUp' : 'lucideArrowDown';
  }
}
