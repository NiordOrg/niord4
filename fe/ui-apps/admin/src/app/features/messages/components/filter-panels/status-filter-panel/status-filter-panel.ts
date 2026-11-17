import { Component, computed, inject } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { type MessageStatus, PUBLISHED_MESSAGE_STATUSES } from '../../../models/message.model';
import { FilterPanel } from '../../../../../shared/components/filter-panel/filter-panel';
import { StatusClassPipe } from '../../../../../shared/pipes/status-class.pipe';
import { MessagesFacade } from '../../../services/messages-facade.service';

// Published states first (the operational majority), then drafts in workflow
// order, then terminal states. Kept separate from `MESSAGE_STATUSES` because
// the serializer / message-id field rely on its model order. The spec
// asserts set-equality so a missing entry can't silently drop a status.
export const STATUS_DISPLAY_ORDER: readonly MessageStatus[] = [
  'PUBLISHED',
  'DRAFT',
  'VERIFIED',
  'CANCELLED',
  'EXPIRED',
  'DELETED',
];

const PUBLIC_STATUSES: readonly MessageStatus[] = STATUS_DISPLAY_ORDER.filter((s) =>
  PUBLISHED_MESSAGE_STATUSES.has(s),
);

@Component({
  selector: 'app-status-filter-panel',
  templateUrl: './status-filter-panel.html',
  imports: [FilterPanel, StatusClassPipe, TranslocoPipe],
})
export class StatusFilterPanel {
  private readonly messages = inject(MessagesFacade);
  private readonly domain = inject(DomainFacade);

  readonly statuses = computed(() => this.messages.filter.slice('status')().statuses);
  readonly visibleStatuses = computed<MessageStatus[]>(() => {
    if (this.domain.searchPublicMessages()) {
      return [...PUBLIC_STATUSES];
    }
    return [...STATUS_DISPLAY_ORDER];
  });

  isChecked(status: MessageStatus): boolean {
    return this.statuses().includes(status);
  }

  onToggle(status: MessageStatus, checked: boolean): void {
    const current = this.statuses();
    const next = checked
      ? current.includes(status)
        ? current
        : [...current, status]
      : current.filter((s) => s !== status);
    this.messages.filter.setSliceValue('status', { statuses: next });
  }

  selectAll(): void {
    this.messages.filter.setSliceValue('status', { statuses: [...this.visibleStatuses()] });
  }

  reset(): void {
    this.messages.filter.setSliceValue('status', { statuses: [] });
  }

  clear(): void {
    this.messages.filter.clearFilter('status');
  }
}
