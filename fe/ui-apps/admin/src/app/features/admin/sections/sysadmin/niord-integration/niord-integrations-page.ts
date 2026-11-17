import { Component, computed, inject, signal } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { Icon } from '../../../../../shared/icons/icon';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { Pager } from '../../../../../shared/components/pager/pager';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import {
  NiordIntegration,
  NiordIntegrationsApiService,
} from '../../../services/api/niord-integrations-api.service';
import { AdminSectionPage } from '../../../shell/admin-section-page';
import { createClientPager } from '../../../utils/client-pager';
import { ADMIN_PAGE_SIZE } from '../../../utils/kit-constants';
import { NiordIntegrationEditor, NiordIntegrationEditorModel } from './niord-integration-editor';
import { NiordIntegrationsFacade } from './niord-integrations.facade';

/**
 * Niord Integration section: a cross-domain, client-filtered, client-paged list
 * of server-to-server pull-integration records with a create/edit drawer,
 * confirm-delete, and a per-row "execute" action that fires the backend pull.
 */
@Component({
  selector: 'app-niord-integrations-page',
  imports: [
    AdminSectionPage,
    Pager,
    Icon,
    Confirm,
    ProgressBar,
    EmptyState,
    ErrorBanner,
    NiordIntegrationEditor,
    TranslocoPipe,
  ],
  providers: [NiordIntegrationsFacade, ConfirmService],
  templateUrl: './niord-integrations-page.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class NiordIntegrationsPage {
  protected readonly facade = inject(NiordIntegrationsFacade);
  protected readonly api = inject(NiordIntegrationsApiService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);

  protected readonly search = signal('');

  /** Filter on the URL OR any mapping's raw source/target series id, case-insensitive. */
  protected readonly filtered = computed(() => {
    const term = this.search().trim().toLowerCase();
    const integrations = this.facade.integrations();
    if (!term) {
      return integrations;
    }
    return integrations.filter((integration) => {
      const haystack = [
        integration.url,
        ...integration.messageSeriesMappings.flatMap((mapping) => [
          mapping.sourceSeriesId,
          mapping.targetSeriesId ?? '',
        ]),
      ]
        .join(' ')
        .toLowerCase();
      return haystack.includes(term);
    });
  });

  protected readonly pager = createClientPager(this.filtered, ADMIN_PAGE_SIZE);

  /** Materialize the readonly page slice for the template. */
  protected readonly pageRows = computed(() => [...this.pager.paged()]);

  protected readonly editorModel = signal<NiordIntegrationEditorModel | undefined>(undefined);

  protected onSearch(value: string): void {
    this.search.set(value);
    this.pager.reset();
  }

  protected addIntegration(): void {
    // Add defaults: active + assignNewUids true, createBaseData false,
    // one blank mapping row (seeded by the editor).
    this.editorModel.set({
      mode: 'add',
      integration: {
        url: '',
        active: true,
        assignNewUids: true,
        createBaseData: false,
        messageSeriesMappings: [],
      },
    });
  }

  protected editIntegration(integration: NiordIntegration): void {
    this.editorModel.set({ mode: 'edit', integration });
  }

  protected deleteIntegration(integration: NiordIntegration): void {
    // Summary shows the URL, falling back to the id.
    const summary = integration.url || String(integration.id ?? '');
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.niord-integration.confirmDelete.header'),
      message: this.transloco.translate('admin.niord-integration.confirmDelete.message', {
        summary,
      }),
      accept: () => {
        if (integration.id === undefined) {
          return;
        }
        this.api.delete(integration.id).subscribe({
          next: () => {
            this.notifications.success('admin.niord-integration.toast.deleted');
            this.facade.refresh();
          },
          error: () => this.notifications.error('admin.niord-integration.toast.deleteError'),
        });
      },
    });
  }

  /**
   * Per-row execute: fire the backend pull job. Reports that it started and
   * surfaces a failure (no pre-confirm).
   */
  protected executeIntegration(integration: NiordIntegration): void {
    if (integration.id === undefined) {
      return;
    }
    this.api.execute(integration.id).subscribe({
      next: () => this.notifications.info('admin.niord-integration.toast.executeStarted'),
      error: () => this.notifications.error('admin.niord-integration.toast.executeError'),
    });
  }

  protected onEditorSaved(): void {
    this.notifications.success('admin.niord-integration.toast.saved');
    this.editorModel.set(undefined);
    this.facade.refresh();
  }
}
