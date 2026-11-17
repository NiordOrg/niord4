import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { Menu } from '../../../../../shared/components/menu/menu';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { MenuTrigger } from '../../../../../shared/components/menu-trigger/menu-trigger';
import { Icon } from '../../../../../shared/icons/icon';
import { OlMap } from '@niord/map/ol-map';
import { OlTileLayer } from '@niord/map/layers/ol-tile-layer';
import { MapShellService } from '@niord/map/map-shell.service';
import { Pager } from '../../../../../shared/components/pager/pager';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { JsonImportDialog } from '../../../components/json-import-dialog/json-import-dialog';
import { Chart, ChartsAdminApiService } from '../../../services/api/charts-admin-api.service';
import { AdminSectionPage } from '../../../shell/admin-section-page';
import { createClientPager } from '../../../utils/client-pager';
import { ADMIN_PAGE_SIZE } from '../../../utils/kit-constants';
import { ChartEditor, ChartEditorModel } from './chart-editor';
import { ChartsExtentLayer } from './charts-extent-layer';
import { ChartsFacade } from './charts.facade';

/**
 * Charts section: client-filtered, client-paged catalog list with a read-only
 * extent-overview map beside it (outlining the CURRENT page's chart extents),
 * a create/edit drawer with the chart-extent geometry editor,
 * confirm-delete, and JSON import/export (the export endpoint is public — no
 * ticket).
 */
@Component({
  selector: 'app-charts-page',
  imports: [
    AdminSectionPage,
    Icon,
    Menu,
    Pager,
    Confirm,
    MenuTrigger,
    ProgressBar,
    EmptyState,
    ErrorBanner,
    JsonImportDialog,
    ChartEditor,
    ChartsExtentLayer,
    OlMap,
    OlTileLayer,
    TranslocoPipe,
  ],
  providers: [ChartsFacade, ConfirmService, MapShellService],
  templateUrl: './charts-page.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class ChartsPage {
  protected readonly facade = inject(ChartsFacade);
  protected readonly api = inject(ChartsAdminApiService);
  private readonly downloads = inject(TicketedDownloadService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly localeFormat = inject(LocaleFormatService);

  private readonly importDialog = viewChild.required(JsonImportDialog);

  protected readonly search = signal('');

  /**
   * Filter over FOUR fields — chartNumber, internationalNumber,
   * horizontalDatum (searchable despite not being a column), name —
   * case-insensitive substring; an unset/falsy field never matches (`scale`
   * is deliberately NOT in the predicate).
   */
  protected readonly filtered = computed(() => {
    const term = this.search().trim().toLowerCase();
    const charts = this.facade.charts();
    if (!term) {
      return charts;
    }
    const matches = (value: string | number | undefined): boolean =>
      value ? String(value).toLowerCase().includes(term) : false;
    return charts.filter(
      (chart) =>
        matches(chart.chartNumber) ||
        matches(chart.internationalNumber) ||
        matches(chart.horizontalDatum) ||
        matches(chart.name),
    );
  });

  protected readonly pager = createClientPager(this.filtered, ADMIN_PAGE_SIZE);

  protected readonly editorModel = signal<ChartEditorModel | undefined>(undefined);

  private readonly exportLabel = translateSignal('admin.charts.menu.export');
  private readonly importLabel = translateSignal('admin.charts.menu.import');

  protected readonly cogItems = computed<MenuItem[]>(() => [
    { label: this.exportLabel(), command: () => this.downloads.openPublic(this.api.exportUrl()) },
    { label: this.importLabel(), command: () => this.importDialog().open() },
  ]);

  /** `1:{scale}` with the active locale's grouping separator (period on da, comma on en). */
  protected formatScale(scale: number): string {
    return this.localeFormat.formatNumber(scale);
  }

  protected onSearch(value: string): void {
    this.search.set(value);
    this.pager.reset();
  }

  protected addChart(): void {
    // Add defaults: active true, datum WGS84, everything else blank.
    this.editorModel.set({
      mode: 'add',
      chart: { chartNumber: '', active: true, horizontalDatum: 'WGS84' },
    });
  }

  protected editChart(chart: Chart): void {
    // The list payload already carries the full record incl. geometry — no
    // details fetch. The editor deep-copies via the form.
    this.editorModel.set({ mode: 'edit', chart });
  }

  protected deleteChart(chart: Chart): void {
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.charts.confirmDelete.header'),
      message: this.transloco.translate('admin.charts.confirmDelete.message', {
        number: chart.chartNumber,
      }),
      accept: () => {
        this.api.delete(chart.chartNumber).subscribe({
          next: () => {
            this.notifications.success('admin.charts.toast.deleted');
            this.facade.refresh();
          },
          error: () => this.notifications.error('admin.charts.toast.deleteError'),
        });
      },
    });
  }

  protected onEditorSaved(): void {
    this.editorModel.set(undefined);
    this.facade.refresh();
  }

  protected onImportClosed(): void {
    // Deliberately refetch on EVERY close path, so an import's results are
    // always reflected in the list.
    this.facade.refresh();
  }
}
