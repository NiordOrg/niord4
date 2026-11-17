import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { LocaleDatePipe } from '../../../../../shared/pipes/locale-date.pipe';
import { SECOM_OUTBOX_STATUSES } from '../../../services/api/secom-admin-api.service';
import { expiryToneClass, isExpired } from './secom-expiry';
import { outboxStatusKey } from './secom-labels';
import { SecomFacade } from './secom.facade';

/** One outbox status and its live count, for the overview's per-status summary. */
interface OutboxCount {
  readonly status: string;
  readonly count: number;
}

/**
 * Overview tab: the at-a-glance health of the SECOM node — certificate age and
 * expiry, when unattended renewal next fires, which series are exposed, and the
 * dataset / subscription / outbox volumes.
 */
@Component({
  selector: 'app-secom-overview-tab',
  imports: [ErrorBanner, LocaleDatePipe, ProgressBar, RouterLink, TranslocoPipe],
  templateUrl: './secom-overview-tab.html',
  host: { class: 'contents' },
})
export class SecomOverviewTab {
  protected readonly facade = inject(SecomFacade);

  protected readonly serviceCertificate = computed(() => this.facade.status()?.serviceCertificate);
  protected readonly renewal = computed(() => this.facade.status()?.renewal);

  /** Text tone for the service certificate's remaining lifetime. */
  protected readonly expiryTone = computed(() =>
    expiryToneClass(this.serviceCertificate()?.daysToExpiry ?? 0),
  );

  protected readonly expired = computed(() =>
    isExpired(this.serviceCertificate()?.daysToExpiry ?? 0),
  );

  /**
   * Renewal is armed only when it is both enabled AND fully configured — the
   * backend then computes `renewsAt`. Enabled-without-configuration is called
   * out explicitly rather than shown as "off", because the operator believes it
   * is on.
   */
  protected readonly renewalState = computed<'scheduled' | 'incomplete' | 'disabled'>(() => {
    const renewal = this.renewal();
    if (renewal?.renewsAt !== undefined) {
      return 'scheduled';
    }
    return renewal?.enabled ? 'incomplete' : 'disabled';
  });

  /**
   * The outbox counts in the fixed lifecycle order rather than the object's key
   * order, and only for statuses that actually have entries.
   */
  protected readonly outboxCounts = computed<readonly OutboxCount[]>(() => {
    const byStatus = this.facade.status()?.outboxByStatus ?? {};
    return SECOM_OUTBOX_STATUSES.filter((status) => (byStatus[status] ?? 0) > 0).map((status) => ({
      status,
      count: byStatus[status] ?? 0,
    }));
  });

  protected readonly exposedSeries = computed(() => this.facade.status()?.scopedSeries ?? []);

  protected readonly outboxStatusKey = outboxStatusKey;
}
