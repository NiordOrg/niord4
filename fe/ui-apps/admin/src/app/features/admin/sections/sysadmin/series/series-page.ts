import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { problemDetail } from '../../../../../shared/api/problem-detail';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { Pager } from '../../../../../shared/components/pager/pager';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { Icon } from '../../../../../shared/icons/icon';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import type { AdminMessageSeries } from '../../../models/admin-message-series.model';
import { MessageSeriesAdminApiService } from '../../../services/api/message-series-admin-api.service';
import { AdminSectionPage } from '../../../shell/admin-section-page';
import { createClientPager } from '../../../utils/client-pager';
import { ADMIN_PAGE_SIZE } from '../../../utils/kit-constants';
import { SeriesEditor } from './series-editor';
import { currentYearIn, numberAssigned } from './series-rules';
import { SeriesFacade } from './series.facade';

/** Editor-drawer input: the open mode plus the series the drawer opens on. */
export interface SeriesEditorModel {
  readonly mode: 'add' | 'edit';
  readonly series: AdminMessageSeries;
}

/**
 * Message Series (sysadmin) — client-filtered, client-paged catalog list with
 * edit / copy / delete row actions. The toolbar is filter + New only: there is
 * no JSON import/export (the backend has no series upload endpoint), so there
 * is deliberately no cog menu.
 */
@Component({
  selector: 'app-series-page',
  imports: [
    AdminSectionPage,
    Pager,
    Icon,
    Confirm,
    ProgressBar,
    EmptyState,
    ErrorBanner,
    SeriesEditor,
    TranslocoPipe,
  ],
  providers: [SeriesFacade, ConfirmService],
  templateUrl: './series-page.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class SeriesPage {
  protected readonly facade = inject(SeriesFacade);
  private readonly api = inject(MessageSeriesAdminApiService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly language = inject(LanguageService);
  private readonly localeFormat = inject(LocaleFormatService);

  /**
   * The next-number column header names the current year — always, even for
   * CONTINUOUS series — as the session domain's clock reads it.
   */
  protected readonly currentYear = computed(() =>
    currentYearIn(this.localeFormat.sessionTimeZone()),
  );

  protected readonly search = signal('');
  protected readonly editorModel = signal<SeriesEditorModel | undefined>(undefined);

  protected readonly filtered = computed(() => {
    const term = this.search().trim().toLowerCase();
    const entries = this.facade.entries();
    if (term === '') {
      return entries;
    }
    // Filter on the seriesId substring ONLY — no other column matches.
    return entries.filter((series) => series.seriesId.toLowerCase().includes(term));
  });

  protected readonly pager = createClientPager(this.filtered, ADMIN_PAGE_SIZE);

  /**
   * Display rows for the current page. Depends on the active language so the
   * imperative translate()s below re-run on a language switch.
   */
  protected readonly pageRows = computed(() => {
    this.language.selectedLanguage();
    return this.pager.paged().map((series) => ({
      series,
      typeLabel: this.typeLabel(series),
      sequenceLabel: this.transloco.translate(
        `admin.series.sequenceType.${series.numberSequenceType}`,
      ),
      // Only auto-numbered series show a next number; the cell stays blank otherwise.
      nextNumber: numberAssigned(series) ? series.nextMessageNumber : undefined,
    }));
  });

  protected onSearch(value: string): void {
    this.search.set(value);
    this.pager.reset();
  }

  protected addSeries(): void {
    // New-series defaults.
    this.editorModel.set({
      mode: 'add',
      series: {
        seriesId: '',
        mainType: 'NW',
        numberSequenceType: 'YEARLY',
        shortFormat: '',
        types: [],
        excludeFromMessageIndex: false,
      },
    });
  }

  protected editSeries(series: AdminMessageSeries): void {
    this.editorModel.set({ mode: 'edit', series });
  }

  /** Copy = clone into add mode with the id blanked (editable + required again). */
  protected copySeries(series: AdminMessageSeries): void {
    this.editorModel.set({ mode: 'add', series: { ...series, seriesId: '' } });
  }

  protected onEditorSaved(): void {
    this.editorModel.set(undefined);
    this.facade.refresh();
  }

  protected onEditorClosed(): void {
    this.editorModel.set(undefined);
  }

  protected deleteSeries(series: AdminMessageSeries): void {
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.series.confirmDelete.header'),
      message: this.transloco.translate('admin.series.confirmDelete.message', {
        seriesId: series.seriesId,
      }),
      accept: () => {
        this.api.delete(series.seriesId).subscribe({
          next: () => {
            this.notifications.success('admin.series.toast.deleted');
            this.facade.refresh();
          },
          error: (error: unknown) => {
            // A 409 names what still uses the series: say which.
            const detail =
              error instanceof HttpErrorResponse && error.status === 409
                ? problemDetail(error)
                : undefined;
            if (detail === undefined) {
              this.notifications.error('admin.series.toast.deleteError');
            } else {
              this.notifications.error('admin.series.toast.deleteRefused', { detail });
            }
          },
        });
      },
    });
  }

  /**
   * "NW (Local, Coastal)" — the localized main type plus the localized restrict
   * types in ONE parenthesis group (each type is not wrapped in its own parens).
   * No restrict types ⇒ the main type alone.
   */
  private typeLabel(series: AdminMessageSeries): string {
    const mainType = this.transloco.translate(`admin.series.mainType.${series.mainType}`);
    const types = series.types ?? [];
    if (types.length === 0) {
      return mainType;
    }
    const suffix = types.map((type) => this.transloco.translate(`msg.type.${type}`)).join(', ');
    return `${mainType} (${suffix})`;
  }
}
