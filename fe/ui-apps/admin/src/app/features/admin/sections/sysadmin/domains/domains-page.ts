import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainService } from '../../../../../core/domain/services/domain.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { Menu } from '../../../../../shared/components/menu/menu';
import { MenuTrigger } from '../../../../../shared/components/menu-trigger/menu-trigger';
import { Pager } from '../../../../../shared/components/pager/pager';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { Icon } from '../../../../../shared/icons/icon';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { JsonImportDialog } from '../../../components/json-import-dialog/json-import-dialog';
import type { AdminDomain } from '../../../models/admin-domain.model';
import { DomainsAdminApiService } from '../../../services/api/domains-admin-api.service';
import { AdminSectionPage } from '../../../shell/admin-section-page';
import { createClientPager } from '../../../utils/client-pager';
import { keycloakConsoleUrl } from '../../../utils/keycloak-console-url';
import { ADMIN_PAGE_SIZE } from '../../../utils/kit-constants';
import { DomainEditor } from './domain-editor';
import { DomainsFacade } from './domains.facade';

/** Editor-drawer input: the open mode plus the domain the drawer opens on. */
export interface DomainEditorModel {
  readonly mode: 'add' | 'edit';
  readonly domain: AdminDomain;
}

/**
 * Domains (sysadmin) — the application's tenant catalog: client-filtered,
 * client-paged list with edit / copy / delete row actions, an external
 * Keycloak-console link-out, and a cog with JSON export/import. The list shows
 * ALL domains — including inactive and non-editable ones (the session-side
 * `editableDomains` filtering must never apply here).
 */
@Component({
  selector: 'app-domains-page',
  imports: [
    AdminSectionPage,
    DomainEditor,
    Pager,
    Icon,
    Menu,
    Confirm,
    MenuTrigger,
    ProgressBar,
    EmptyState,
    ErrorBanner,
    JsonImportDialog,
    TranslocoPipe,
  ],
  providers: [DomainsFacade, ConfirmService],
  templateUrl: './domains-page.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class DomainsPage {
  protected readonly facade = inject(DomainsFacade);
  protected readonly api = inject(DomainsAdminApiService);
  /** The app-wide session catalog this page writes — refreshed live after CRUD. */
  private readonly domainCatalog = inject(DomainService);
  private readonly downloads = inject(TicketedDownloadService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);

  private readonly importDialog = viewChild.required(JsonImportDialog);

  protected readonly search = signal('');
  protected readonly editorModel = signal<DomainEditorModel | undefined>(undefined);

  /** The external Keycloak admin-console link (url + realm from the deployment config). */
  protected readonly keycloakConsoleUrl = keycloakConsoleUrl(inject(APP_CONFIG).keycloak);

  protected readonly filtered = computed(() => {
    const term = this.search().trim().toLowerCase();
    const domains = this.facade.domains();
    if (term === '') {
      return domains;
    }
    // Filter on domainId OR name ONLY (null-safe) — the other columns
    // (timezone, areas, series, …) are deliberately not searched.
    return domains.filter(
      (domain) =>
        domain.domainId.toLowerCase().includes(term) ||
        (domain.name ?? '').toLowerCase().includes(term),
    );
  });

  protected readonly pager = createClientPager(this.filtered, ADMIN_PAGE_SIZE);

  private readonly exportLabel = translateSignal('admin.domains.menu.export');
  private readonly importLabel = translateSignal('admin.domains.menu.import');
  protected readonly cogItems = computed<MenuItem[]>(() => [
    { label: this.exportLabel(), icon: 'lucideUpload', command: () => this.export() },
    {
      label: this.importLabel(),
      icon: 'lucideDownload',
      command: () => this.importDialog().open(),
    },
  ]);

  protected onSearch(value: string): void {
    this.search.set(value);
    this.pager.reset();
  }

  /** First-desc names, one line per bound area/category (descs server-sorted by the active language). */
  protected descNames(
    refs: readonly { readonly descs?: readonly { readonly name?: string }[] }[] | undefined,
  ): string[] {
    return (refs ?? []).map((ref) => ref.descs?.[0]?.name ?? '');
  }

  protected seriesIds(domain: AdminDomain): string[] {
    return (domain.messageSeries ?? []).map((series) => series.seriesId);
  }

  protected addDomain(): void {
    // New-domain defaults. The zone starts empty and the editor requires one: everything the
    // domain shows and stores is read against it, and the machine the form happens to be
    // filled in on says nothing about where the domain's users are.
    this.editorModel.set({
      mode: 'add',
      domain: {
        domainId: '',
        active: true,
        sortOrder: 0,
        timeZone: '',
        messageSortOrder: 'AREA ASC',
        publish: false,
        atons: false,
        templates: false,
      },
    });
  }

  protected editDomain(domain: AdminDomain): void {
    this.editorModel.set({ mode: 'edit', domain });
  }

  /** Copy = clone into add mode with the id blanked (editable + required again). */
  protected copyDomain(domain: AdminDomain): void {
    this.editorModel.set({ mode: 'add', domain: { ...domain, domainId: '' } });
  }

  protected deleteDomain(domain: AdminDomain): void {
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.domains.confirmDelete.header'),
      message: this.transloco.translate('admin.domains.confirmDelete.message', {
        domainId: domain.domainId,
      }),
      accept: () => {
        this.api.delete(domain.domainId).subscribe({
          next: () => {
            this.notifications.success('admin.domains.toast.deleted');
            this.facade.refresh();
            // The live session catalog (header switcher/capabilities) follows
            // without a reload. Deleting the CURRENT session domain still
            // leaves the session on the stale object until reload.
            this.domainCatalog.refresh();
          },
          error: () => this.notifications.error('admin.domains.toast.deleteError'),
        });
      },
    });
  }

  /** Save landed: close the drawer, refetch the list AND the live session catalog. */
  protected onEditorSaved(): void {
    this.editorModel.set(undefined);
    this.facade.refresh();
    this.domainCatalog.refresh();
  }

  /** Pure cancel/✕: close only — deliberately no refetch. */
  protected onEditorClosed(): void {
    this.editorModel.set(undefined);
  }

  /** Export includes inactive domains so the JSON backup is complete. */
  private export(): void {
    this.downloads.openPublic(this.api.exportUrl());
  }

  protected onImportClosed(): void {
    this.facade.refresh();
  }
}
