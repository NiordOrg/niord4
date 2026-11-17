import { Component, computed, inject } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { NotificationService } from '../../../../../core/notifications/notification.service';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { Pager } from '../../../../../shared/components/pager/pager';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { Icon } from '../../../../../shared/icons/icon';
import { LocaleDatePipe } from '../../../../../shared/pipes/locale-date.pipe';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import {
  SECOM_OUTBOX_STATUSES,
  SecomAdminApiService,
  SecomOutboxEntry,
  SecomOutboxStatus,
} from '../../../services/api/secom-admin-api.service';
import { createClientPager } from '../../../utils/client-pager';
import { ADMIN_PAGE_SIZE } from '../../../utils/kit-constants';
import {
  SECOM_OUTBOX_STATUS_KEYS,
  operationKey,
  outboxStatusClass,
  outboxStatusKey,
} from './secom-labels';
import { SecomFacade } from './secom.facade';

/** How much of a failure message fits in the cell before it is clipped. */
const ERROR_PREVIEW_LENGTH = 50;

/** Statuses whose entry can still be re-armed for dispatch. */
const RETRYABLE: ReadonlySet<string> = new Set<SecomOutboxStatus>(['PENDING', 'FAILED']);

/** Only a delivery that has not left yet can be cancelled. */
const CANCELLABLE: ReadonlySet<string> = new Set<SecomOutboxStatus>(['PENDING']);

/**
 * How many entries the outbox endpoint answers at most: its default `limit`,
 * since `getOutbox` sends none. It answers the newest first, so a list of this
 * length may leave older entries out.
 */
const OUTBOX_LIST_LIMIT = 100;

/**
 * Outbox tab: the outbound delivery queue — one row per upload or subscription
 * notification, with its attempt count, backoff schedule and last failure.
 *
 * The status filter is part of the request key, so switching it refetches
 * server-side rather than filtering the loaded page. **Retry** re-arms a pending
 * or failed entry for immediate dispatch; **Cancel** drops a delivery that has
 * not gone out yet. The endpoint answers the newest 100 entries; when the list
 * is that long, the tab says so.
 */
@Component({
  selector: 'app-secom-outbox-tab',
  imports: [EmptyState, ErrorBanner, Icon, LocaleDatePipe, Pager, ProgressBar, TranslocoPipe],
  templateUrl: './secom-outbox-tab.html',
  host: { class: 'contents' },
})
export class SecomOutboxTab {
  protected readonly facade = inject(SecomFacade);
  private readonly api = inject(SecomAdminApiService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);

  /** `''` = all, then one option per delivery status in lifecycle order. */
  protected readonly statusOptions: readonly {
    readonly value: '' | SecomOutboxStatus;
    readonly labelKey: string;
  }[] = [
    { value: '', labelKey: 'admin.secom.outbox.statusAll' },
    ...SECOM_OUTBOX_STATUSES.map((status) => ({
      value: status,
      labelKey: SECOM_OUTBOX_STATUS_KEYS[status],
    })),
  ];

  protected readonly pager = createClientPager(this.facade.outbox, ADMIN_PAGE_SIZE);

  /** Materialize the readonly page slice for the template. */
  protected readonly rows = computed(() => [...this.pager.paged()]);

  protected readonly listLimit = OUTBOX_LIST_LIMIT;

  /** The endpoint answered its full limit: the list is the newest entries, not the whole outbox. */
  protected readonly newestOnly = computed(() => this.facade.outbox().length >= OUTBOX_LIST_LIMIT);

  protected onStatusChange(status: '' | SecomOutboxStatus): void {
    this.facade.setOutboxStatus(status);
    this.pager.reset();
  }

  protected canRetry(entry: SecomOutboxEntry): boolean {
    return RETRYABLE.has(entry.status);
  }

  protected canCancel(entry: SecomOutboxEntry): boolean {
    return CANCELLABLE.has(entry.status);
  }

  protected errorPreview(lastError: string): string {
    return lastError.length > ERROR_PREVIEW_LENGTH
      ? `${lastError.slice(0, ERROR_PREVIEW_LENGTH)}…`
      : lastError;
  }

  protected retry(entry: SecomOutboxEntry): void {
    this.api.retryOutboxEntry(entry.id).subscribe({
      next: () => {
        this.notifications.success('admin.secom.toast.outboxRetried');
        this.facade.refreshOutbox();
        this.facade.refreshStatus();
      },
      error: () => this.notifications.error('admin.secom.toast.outboxRetryError'),
    });
  }

  protected cancel(entry: SecomOutboxEntry): void {
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.secom.confirmCancelOutbox.header'),
      message: this.transloco.translate('admin.secom.confirmCancelOutbox.message', {
        operation: this.transloco.translate(operationKey(entry.operation)),
        node: entry.nodeMrn,
      }),
      accept: () => {
        this.api.cancelOutboxEntry(entry.id).subscribe({
          next: () => {
            this.notifications.success('admin.secom.toast.outboxCancelled');
            this.facade.refreshOutbox();
            this.facade.refreshStatus();
          },
          error: () => this.notifications.error('admin.secom.toast.outboxCancelError'),
        });
      },
    });
  }

  /** Layer-3 status-pill class + translated label key — never the raw enum. */
  protected readonly statusClass = outboxStatusClass;
  protected readonly statusKey = outboxStatusKey;
  protected readonly operationKey = operationKey;
}
