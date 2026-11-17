import { Component, computed, inject, signal } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { NotificationService } from '../../../../../core/notifications/notification.service';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { Icon } from '../../../../../shared/icons/icon';
import { LocaleDatePipe } from '../../../../../shared/pipes/locale-date.pipe';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import {
  SecomAdminApiService,
  SecomRenewalResult,
} from '../../../services/api/secom-admin-api.service';
import { expiryToneClass, isExpired } from './secom-expiry';
import { problemDetail } from '../../../../../shared/api/problem-detail';
import { SecomFacade } from './secom.facade';

/**
 * Certificates tab: the node's MCP identity — the service certificate it
 * presents to SECOM peers, the root it chains to, and the trusted CAs inbound
 * peer certificates must anchor in.
 *
 * Two write actions, both of which leave the current key material active when
 * they fail: **Reload** re-reads the configured keystore/truststore and swaps
 * them in without a restart, and **Renew Now** asks the MCP Identity Registry
 * for fresh keys ahead of the expiry window (the supervised first-renewal tool,
 * hence the confirmation).
 */
@Component({
  selector: 'app-secom-certificates-tab',
  imports: [EmptyState, ErrorBanner, Icon, LocaleDatePipe, ProgressBar, TranslocoPipe],
  templateUrl: './secom-certificates-tab.html',
  host: { class: 'contents' },
})
export class SecomCertificatesTab {
  protected readonly facade = inject(SecomFacade);
  private readonly api = inject(SecomAdminApiService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);

  /** Blocks re-entry while a reload or renewal is in flight. */
  protected readonly busy = signal(false);

  protected readonly serviceCertificate = computed(() => this.facade.status()?.serviceCertificate);

  protected readonly expiryTone = computed(() =>
    expiryToneClass(this.serviceCertificate()?.daysToExpiry ?? 0),
  );

  protected readonly expired = computed(() =>
    isExpired(this.serviceCertificate()?.daysToExpiry ?? 0),
  );

  protected readonly trustedCertificates = computed(
    () => this.facade.status()?.trustedCertificates ?? [],
  );

  /** Re-reads the configured key material and swaps it in (certificate rotation). */
  protected reloadSecurity(): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.api.reloadSecurity().subscribe({
      next: (result) => {
        this.busy.set(false);
        this.notifications.success('admin.secom.toast.reloaded', {
          subject: result.serviceCertificateSubject,
        });
        this.facade.refreshStatus();
      },
      error: () => {
        this.busy.set(false);
        this.notifications.error('admin.secom.toast.reloadError');
      },
    });
  }

  protected renewCertificates(): void {
    if (this.busy()) {
      return;
    }
    this.confirm.confirmNeutral({
      header: this.transloco.translate('admin.secom.confirmRenew.header'),
      message: this.transloco.translate('admin.secom.confirmRenew.message'),
      accept: () => this.runRenewal(),
    });
  }

  private runRenewal(): void {
    this.busy.set(true);
    this.api.renewCertificates().subscribe({
      next: (result) => {
        this.busy.set(false);
        this.reportRenewal(result);
        this.facade.refreshStatus();
      },
      error: (err: unknown) => {
        this.busy.set(false);
        // The problem document's `detail` names the failing MIR step — what
        // tells the operator which credential to fix.
        const detail = problemDetail(err);
        if (detail === undefined) {
          this.notifications.error('admin.secom.toast.renewErrorNoDetail');
          return;
        }
        this.notifications.error('admin.secom.toast.renewError', { detail });
      },
    });
  }

  /**
   * A successful run always renewed something (a run that renews nothing 400s),
   * but it may still carry per-credential failures — reported as a warning so a
   * partial success is not mistaken for a clean one.
   */
  private reportRenewal(result: SecomRenewalResult): void {
    const renewed = result.renewed
      .map((credential) => `${credential.credential} ${credential.mrn}`)
      .join(', ');
    if (result.failures.length > 0) {
      this.notifications.warn('admin.secom.toast.renewedWithFailures', {
        renewed,
        failures: result.failures.join('; '),
      });
      return;
    }
    this.notifications.success('admin.secom.toast.renewed', { renewed });
  }
}
