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
  SecomAdminApiService,
  SecomSubscription,
} from '../../../services/api/secom-admin-api.service';
import { createClientPager } from '../../../utils/client-pager';
import { ADMIN_PAGE_SIZE } from '../../../utils/kit-constants';
import { SecomFacade } from './secom.facade';

/** How long a subscription's WKT geometry may run before it is clipped in the cell. */
const WKT_PREVIEW_LENGTH = 60;

/**
 * Subscribers tab: the live SECOM subscriptions and every peer node that has
 * ever interacted with this one, whether or not it currently subscribes.
 *
 * Removing a subscription also NOTIFIES the subscriber (a backend side effect),
 * so it is confirmed as a destructive action.
 */
@Component({
  selector: 'app-secom-subscribers-tab',
  imports: [EmptyState, ErrorBanner, Icon, LocaleDatePipe, Pager, ProgressBar, TranslocoPipe],
  templateUrl: './secom-subscribers-tab.html',
  host: { class: 'contents' },
})
export class SecomSubscribersTab {
  protected readonly facade = inject(SecomFacade);
  private readonly api = inject(SecomAdminApiService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);

  protected readonly subscriptionPager = createClientPager(
    this.facade.subscriptions,
    ADMIN_PAGE_SIZE,
  );

  protected readonly nodePager = createClientPager(this.facade.nodes, ADMIN_PAGE_SIZE);

  /** Materialize the readonly page slices for the template. */
  protected readonly subscriptionRows = computed(() => [...this.subscriptionPager.paged()]);
  protected readonly nodeRows = computed(() => [...this.nodePager.paged()]);

  /** Long WKT is clipped in the cell; the untruncated value stays in the row title. */
  protected wktPreview(wkt: string): string {
    return wkt.length > WKT_PREVIEW_LENGTH ? `${wkt.slice(0, WKT_PREVIEW_LENGTH)}…` : wkt;
  }

  protected hasPeriod(subscription: SecomSubscription): boolean {
    return subscription.periodStart !== undefined || subscription.periodEnd !== undefined;
  }

  protected deleteSubscription(subscription: SecomSubscription): void {
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.secom.confirmRemoveSubscription.header'),
      message: this.transloco.translate('admin.secom.confirmRemoveSubscription.message', {
        node: subscription.nodeMrn,
      }),
      accept: () => {
        this.api.deleteSubscription(subscription.uuid).subscribe({
          next: () => {
            this.notifications.success('admin.secom.toast.subscriptionRemoved');
            this.facade.refreshSubscriptions();
            this.facade.refreshStatus();
          },
          error: () => this.notifications.error('admin.secom.toast.subscriptionRemoveError'),
        });
      },
    });
  }
}
